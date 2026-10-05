package br.com.smellsliketech.converter.licenca

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

/**
 * O recibo da Google Play conferido no celular. A chave de licenciamento de verdade só existe
 * no Play Console, então os recibos daqui são assinados com um par RSA descartável, do mesmo
 * jeito que a Play Store assina (SHA1withRSA sobre o JSON exato).
 */
class ReciboDaPlayTest {
    private val pacote = "br.com.smellsliketech.converter"
    private val par: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.genKeyPair()
    private val publica = Base64.getEncoder().encodeToString(par.public.encoded)

    private fun assinar(json: String, chave: KeyPair = par): String =
        Base64.getEncoder().encodeToString(
            Signature.getInstance("SHA1withRSA").run {
                initSign(chave.private)
                update(json.toByteArray(Charsets.UTF_8))
                sign()
            },
        )

    private fun recibo(
        produto: String = "\"productId\":\"android_vitalicio\"",
        estado: Int = 0,
        pacoteDoRecibo: String = pacote,
    ) = """{"orderId":"GPA.3312-4415-2231-90871","packageName":"$pacoteDoRecibo",$produto,""" +
        """"purchaseTime":1790740000000,"purchaseState":$estado,"purchaseToken":"abcdefghijklmnop.AO-J1Oy","quantity":1,"acknowledged":false}"""

    @Test
    fun `recibo assinado pela chave da loja libera`() {
        val json = recibo()
        val conferido = ReciboDaPlay.conferir(json, assinar(json), publica, pacote)
        assertNotNull(conferido)
        assertEquals("GPA.3312-4415-2231-90871", conferido!!.pedido)
        assertEquals(1790740000000L, conferido.compradoEm)
    }

    @Test
    fun `o recibo com a lista productIds tambem vale`() {
        val json = recibo(produto = "\"productIds\":[\"android_vitalicio\"]")
        assertNotNull(ReciboDaPlay.conferir(json, assinar(json), publica, pacote))
    }

    @Test
    fun `mudar uma letra do recibo invalida a assinatura`() {
        val json = recibo()
        val assinatura = assinar(json)
        assertNull(ReciboDaPlay.conferir(json.replace("GPA.3312", "GPA.3313"), assinatura, publica, pacote))
    }

    @Test
    fun `recibo assinado por outra chave nao vale`() {
        val json = recibo()
        val outra = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.genKeyPair()
        assertNull(ReciboDaPlay.conferir(json, assinar(json, outra), publica, pacote))
    }

    @Test
    fun `sem a chave da Play nenhum recibo vale`() {
        val json = recibo()
        assertNull(ReciboDaPlay.conferir(json, assinar(json), "", pacote))
    }

    @Test
    fun `recibo de outro app, de outro produto ou nao pago nao libera`() {
        for (json in listOf(
            recibo(pacoteDoRecibo = "com.exemplo.outro"),
            recibo(produto = "\"productId\":\"moedas_100\""),
            recibo(estado = 4),
        )) {
            assertNull(json, ReciboDaPlay.conferir(json, assinar(json), publica, pacote))
        }
    }
}
