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

/** A versão da Google Play: a licença vitalícia é um produto único, pago pelo Google Play. */
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
    private var produto: ProductDetails? = null

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

    private suspend fun detalhes(): ProductDetails? {
        produto?.let { return it }
        if (!conectar()) return null
        val pedido = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(ReciboDaPlay.PRODUTO)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build(),
                ),
            )
            .build()
        return cliente.queryProductDetails(pedido).productDetailsList?.firstOrNull()?.also { produto = it }
    }

    override suspend fun preco(): String? = detalhes()?.oneTimePurchaseOfferDetails?.formattedPrice

    override fun comprar(atividade: Activity) {
        escopo.launch {
            val detalhes = detalhes() ?: return@launch ouvinte.falhou(
                "A Google Play não respondeu. Confira se a Play Store está atualizada e com a sua conta, e tente de novo.",
            )
            val item = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(detalhes)
            detalhes.oneTimePurchaseOfferDetailsList?.firstOrNull()?.offerToken?.let(item::setOfferToken)
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
        if (ReciboDaPlay.PRODUTO !in compra.products) return
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

    override suspend fun sincronizar() {
        if (!conectar()) return
        val resposta = cliente.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build(),
        )
        if (resposta.billingResult.responseCode != BillingResponseCode.OK) return
        val nossas = resposta.purchasesList.filter { ReciboDaPlay.PRODUTO in it.products }
        if (nossas.isEmpty()) ouvinte.semCompra() else nossas.forEach { tratar(it) }
    }

    override fun encerrar() {
        escopo.cancel()
        cliente.endConnection()
    }
}
