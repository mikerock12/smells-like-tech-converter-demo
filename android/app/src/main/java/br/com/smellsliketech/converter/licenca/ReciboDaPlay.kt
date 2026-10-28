package br.com.smellsliketech.converter.licenca

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * O recibo de uma compra feita pela Google Play, conferido no próprio celular.
 *
 * A Play Store entrega ao app o JSON da compra e uma assinatura RSA (SHA1withRSA) feita com
 * a chave de licenciamento do app, cuja parte pública fica no Play Console (Monetização →
 * Licenciamento). Conferir aqui, sem servidor, mantém o app sem permissão de internet: um
 * recibo inventado ou alterado não passa, e o recibo guardado vale sem rede.
 *
 * Kotlin puro (java.security), sem nada do Android: os testes rodam no computador.
 */
object ReciboDaPlay {
    /** A licença vitalícia do app: produto único, criado no Play Console com este id. */
    const val PRODUTO = "android_vitalicio"

    /** A assinatura mensal (R$ 19,90, renovação automática pela Play), criada no Play Console com este id. */
    const val PRODUTO_MENSAL = "android_mensal"

    /** `mensal`: recibo da assinatura. Ele não traz vencimento: quem diz se continua ativa é a Play. */
    data class Recibo(val pedido: String, val token: String, val compradoEm: Long, val mensal: Boolean = false)

    /**
     * O recibo, se a assinatura confere e a compra é deste app, deste produto e está paga.
     * Nulo para qualquer outra coisa, inclusive compra pendente (boleto ou Pix ainda não pagos).
     */
    fun conferir(json: String, assinatura: String, chavePublica: String, pacote: String): Recibo? {
        if (chavePublica.isBlank() || !assinaturaConfere(json, assinatura, chavePublica)) return null
        val campos = JsonPlano.ler(json) ?: return null
        if (campos["packageName"] != pacote) return null
        val produtos = (campos["productIds"] as? List<*>)?.filterIsInstance<String>() ?: listOfNotNull(campos["productId"] as? String)
        val mensal = PRODUTO !in produtos && PRODUTO_MENSAL in produtos
        if (PRODUTO !in produtos && !mensal) return null
        // 0 é paga. A Play Store manda outros valores para cancelada e pendente.
        if ((campos["purchaseState"] as? Long ?: 0L) != 0L) return null
        val token = campos["purchaseToken"] as? String ?: return null
        return Recibo(
            // Compra de teste (conta de testador) pode vir sem número de pedido.
            pedido = campos["orderId"] as? String ?: "teste-${token.take(8)}",
            token = token,
            compradoEm = campos["purchaseTime"] as? Long ?: 0L,
            mensal = mensal,
        )
    }

    private fun assinaturaConfere(json: String, assinatura: String, chavePublica: String): Boolean = try {
        val chave = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(chavePublica.trim())))
        Signature.getInstance("SHA1withRSA").run {
            initVerify(chave)
            update(json.toByteArray(Charsets.UTF_8))
            verify(Base64.getDecoder().decode(assinatura.trim()))
        }
    } catch (_: Exception) {
        false
    }
}
