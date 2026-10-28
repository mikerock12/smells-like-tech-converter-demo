package br.com.smellsliketech.converter.loja

import android.app.Activity
import android.content.Context
import br.com.smellsliketech.converter.licenca.ReciboDaPlay
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * A versão da Google Play: a licença vitalícia é um produto único, e a mensal, uma assinatura
 * com renovação automática; as duas são pagas pelo Google Play.
 */
fun novaLoja(contexto: Context, ouvinte: Loja.Ouvinte): Loja = LojaDaPlay(contexto.applicationContext, ouvinte)

/**
 * O faturamento da Google Play. O app fala com a Play Store por IPC, e quem acessa a rede é
 * ela: o app continua sem permissão de internet. O recibo é conferido no celular
 * ([ReciboDaPlay]) antes de liberar, e só então a compra é confirmada ao Google — compra
 * não confirmada em 3 dias é devolvida ao cliente.
 */
private class LojaDaPlay(contexto: Context, private val ouvinte: Loja.Ouvinte) : Loja, PurchasesUpdatedListener {
    private val escopo = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val cliente = BillingClient.newBuilder(contexto)
        .setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()
    private val produtos = mutableMapOf<String, ProductDetails>()

    private suspend fun conectar(): Boolean {
        if (cliente.isReady) return true
        return suspendCancellableCoroutine { continuacao ->
            cliente.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(resultado: BillingResult) {
                    if (continuacao.isActive) continuacao.resume(resultado.responseCode == BillingResponseCode.OK)
                }

                override fun onBillingServiceDisconnected() {
                    if (continuacao.isActive) continuacao.resume(false)
                }
            })
        }
    }

    private suspend fun detalhes(mensal: Boolean = false): ProductDetails? {
        val id = if (mensal) ReciboDaPlay.PRODUTO_MENSAL else ReciboDaPlay.PRODUTO
        produtos[id]?.let { return it }
        if (!conectar()) return null
        val pedido = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(id)
                        .setProductType(if (mensal) BillingClient.ProductType.SUBS else BillingClient.ProductType.INAPP)
                        .build(),
                ),
            )
            .build()
        return cliente.queryProductDetails(pedido).productDetailsList?.firstOrNull()?.also { produtos[id] = it }
    }

    /** A oferta do plano básico (sem promoção): é ela que vale depois dos 7 dias de teste do app. */
    private fun ofertaMensal(detalhes: ProductDetails) =
        detalhes.subscriptionOfferDetails?.let { ofertas -> ofertas.firstOrNull { it.offerId == null } ?: ofertas.firstOrNull() }

    override suspend fun preco(): String? = detalhes()?.oneTimePurchaseOfferDetails?.formattedPrice

    override suspend fun precoMensal(): String? =
        detalhes(mensal = true)?.let(::ofertaMensal)?.pricingPhases?.pricingPhaseList?.lastOrNull()?.formattedPrice

    override fun comprar(atividade: Activity, mensal: Boolean) {
        escopo.launch {
            val detalhes = detalhes(mensal) ?: return@launch ouvinte.falhou(
                "A Google Play não respondeu. Confira se a Play Store está atualizada e com a sua conta, e tente de novo.",
            )
            val item = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(detalhes)
            if (mensal) {
                val oferta = ofertaMensal(detalhes) ?: return@launch ouvinte.falhou("A assinatura mensal ainda não está disponível na Google Play.")
                item.setOfferToken(oferta.offerToken)
            } else {
                detalhes.oneTimePurchaseOfferDetailsList?.firstOrNull()?.offerToken?.let(item::setOfferToken)
            }
            val resultado = cliente.launchBillingFlow(
                atividade,
                BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(item.build())).build(),
            )
            when (resultado.responseCode) {
                BillingResponseCode.OK, BillingResponseCode.USER_CANCELED -> Unit
                BillingResponseCode.ITEM_ALREADY_OWNED -> sincronizar()
                else -> ouvinte.falhou("A Google Play não abriu a compra (código ${resultado.responseCode}). Tente de novo.")
            }
        }
    }

    override fun onPurchasesUpdated(resultado: BillingResult, compras: List<Purchase>?) {
        escopo.launch {
            when (resultado.responseCode) {
                BillingResponseCode.OK -> compras.orEmpty().forEach { tratar(it) }
                BillingResponseCode.USER_CANCELED -> Unit
                BillingResponseCode.ITEM_ALREADY_OWNED -> sincronizar()
                else -> ouvinte.falhou("A compra não foi concluída (código ${resultado.responseCode}). Nada foi cobrado por este app.")
            }
        }
    }

    private suspend fun tratar(compra: Purchase) {
        if (ReciboDaPlay.PRODUTO !in compra.products && ReciboDaPlay.PRODUTO_MENSAL !in compra.products) return
        when (compra.purchaseState) {
            Purchase.PurchaseState.PURCHASED -> {
                if (!ouvinte.comprou(compra.originalJson, compra.signature)) return
                if (!compra.isAcknowledged) {
                    cliente.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(compra.purchaseToken).build())
                }
            }
            Purchase.PurchaseState.PENDING -> ouvinte.pendente()
        }
    }

    /**
     * Pergunta as duas compras. A Play só devolve assinatura enquanto ela está ativa (inclusive
     * no período de carência): é assim que o app sabe que a mensal continua valendo.
     */
    override suspend fun sincronizar() {
        if (!conectar()) return
        val vitalicia = cliente.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build(),
        )
        if (vitalicia.billingResult.responseCode == BillingResponseCode.OK) {
            val nossas = vitalicia.purchasesList.filter { ReciboDaPlay.PRODUTO in it.products }
            if (nossas.isEmpty()) ouvinte.semCompra() else nossas.forEach { tratar(it) }
        }
        val mensal = cliente.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build(),
        )
        if (mensal.billingResult.responseCode == BillingResponseCode.OK) {
            val ativas = mensal.purchasesList.filter { ReciboDaPlay.PRODUTO_MENSAL in it.products }
            if (ativas.isEmpty()) ouvinte.semAssinatura() else ativas.forEach { tratar(it) }
        }
    }

    override fun encerrar() {
        escopo.cancel()
        cliente.endConnection()
    }
}
