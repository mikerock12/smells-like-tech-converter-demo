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
    /** O produto único da loja: a licença vitalícia do app, criada no Play Console com este id. */
    const val PRODUTO = "android_vitalicio"

    data class Recibo(val pedido: String, val token: String, val compradoEm: Long)

    /**
     * O recibo, se a assinatura confere e a compra é deste app, deste produto e está paga.
     * Nulo para qualquer outra coisa, inclusive compra pendente (boleto ou Pix ainda não pagos).
     */
    fun conferir(json: String, assinatura: String, chavePublica: String, pacote: String): Recibo? {
        if (chavePublica.isBlank() || !assinaturaConfere(json, assinatura, chavePublica)) return null
        val campos = JsonPlano.ler(json) ?: return null
        if (campos["packageName"] != pacote) return null
        val produtos = (campos["productIds"] as? List<*>)?.filterIsInstance<String>() ?: listOfNotNull(campos["productId"] as? String)
        if (PRODUTO !in produtos) return null
        // 0 é paga. A Play Store manda outros valores para cancelada e pendente.
        if ((campos["purchaseState"] as? Long ?: 0L) != 0L) return null
        val token = campos["purchaseToken"] as? String ?: return null
        return Recibo(
            // Compra de teste (conta de testador) pode vir sem número de pedido.
            pedido = campos["orderId"] as? String ?: "teste-${token.take(8)}",
            token = token,
            compradoEm = campos["purchaseTime"] as? Long ?: 0L,
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
