package br.com.smellsliketech.converter.licenca

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Vetores gerados pelo código do site (packages/licenca, WebCrypto) com um par de chaves de
 * teste: o que o servidor assina, o celular precisa aceitar, e nada além disso.
 */
class LicencaTest {
    private val publicaDeTeste = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAECsMVRMue_OUTI38XpErkhh4pbmN0bbWikcDcnJXa0x1MCdB14x3PhtcOslv99K-BKHmem8nAtHEHRzzGPNahog"
    private val android = "SLT1.eyJ2IjoxLCJpZCI6IlNMVC1BTkRSLU9JRDEiLCJwbGFubyI6ImFuZHJvaWQiLCJlbWFpbCI6InRlc3RlQGV4ZW1wbG8uY29tLmJyIiwiZW1pdGlkYSI6IjIwMjYtMDktMjhUMTI6MDA6MDAuMDAwWiIsImV4cGlyYSI6bnVsbCwibWFxdWluYXMiOjN9.neQCshRPL42Gj41Oqkv2pkf5SuP5QZCWRhniCbrUgaEvM5tGqenSth_-Y_F6Jb1ejYwMaCpPbNf2VJtgMJ9XYA"
    private val pro = "SLT1.eyJ2IjoxLCJpZCI6IlNMVC1QUk9NLUVOUzEiLCJwbGFubyI6InBybyIsImVtYWlsIjoidGVzdGVAZXhlbXBsby5jb20uYnIiLCJlbWl0aWRhIjoiMjAyNi0wOS0yOFQxMjowMDowMC4wMDBaIiwiZXhwaXJhIjoiMjAyNi0xMC0yOVQxMjowMDowMC4wMDBaIiwibWFxdWluYXMiOjN9.h7m0WBOU-Vf4PYaNhl89Xmj0q6oKLi9aT3n8xb5-ope0WlUNEqjSHgKejsz8wQG-tHRxnn7aWjcu48kyIuec-A"
    private val adulterada = "SLT1.eyJ2IjoxLCJpZCI6IlNMVC1BTkRSLU9JRDEiLCJwbGFubyI6ImNvbXBsZXRvIiwiZW1haWwiOiJ0ZXN0ZUBleGVtcGxvLmNvbS5iciIsImVtaXRpZGEiOiIyMDI2LTA5LTI4VDEyOjAwOjAwLjAwMFoiLCJleHBpcmEiOm51bGwsIm1hcXVpbmFzIjozfQ.neQCshRPL42Gj41Oqkv2pkf5SuP5QZCWRhniCbrUgaEvM5tGqenSth_-Y_F6Jb1ejYwMaCpPbNf2VJtgMJ9XYA"

    @Test
    fun aceitaAChaveAssinadaPeloSite() {
        val resultado = Licenca.verificar(android, publicaDeTeste)
        assertTrue(resultado is Licenca.Resultado.Valida)
        val carga = (resultado as Licenca.Resultado.Valida).carga
        assertEquals("SLT-ANDR-OID1", carga.id)
        assertEquals("android", carga.plano)
        assertEquals("teste@exemplo.com.br", carga.email)
        assertEquals(null, carga.expira)
        assertEquals(3, carga.maquinas)
        assertTrue(carga.liberaAndroid)
        assertFalse(carga.vencida())
    }

    @Test
    fun aceitaAChaveQuebradaEmLinhasComoOSiteMostra() {
        val emLinhas = android.chunked(40).joinToString("\n")
        assertTrue(Licenca.verificar(emLinhas, publicaDeTeste) is Licenca.Resultado.Valida)
    }

    @Test
    fun aChaveDoProNaoLiberaOAndroidEVence() {
        val carga = (Licenca.verificar(pro, publicaDeTeste) as Licenca.Resultado.Valida).carga
        assertFalse(carga.liberaAndroid)
        assertTrue(carga.vencida(Instant.parse("2026-11-01T00:00:00Z")))
        assertFalse(carga.vencida(Instant.parse("2026-10-01T00:00:00Z")))
    }

    @Test
    fun recusaCargaAdulteradaEOutraChavePublica() {
        assertEquals(Licenca.Resultado.Invalida(Licenca.Motivo.ASSINATURA), Licenca.verificar(adulterada, publicaDeTeste))
        // A chave de produção não aceita o que foi assinado pelo par de teste.
        assertEquals(Licenca.Resultado.Invalida(Licenca.Motivo.ASSINATURA), Licenca.verificar(android))
    }

    @Test
    fun recusaTextoQueNaoEChave() {
        for (texto in listOf("", "SLT1.abc", "XYZ1.a.b", "SLT1.!!!.???", "SLT1." + "e30" + "." + "AAAA")) {
            assertEquals(texto, Licenca.Resultado.Invalida(Licenca.Motivo.FORMATO), Licenca.verificar(texto, publicaDeTeste))
        }
    }
}
