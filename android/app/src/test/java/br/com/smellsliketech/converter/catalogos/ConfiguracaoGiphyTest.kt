package br.com.smellsliketech.converter.catalogos

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ConfiguracaoGiphyTest {
    @Test fun cifraCompartilhadaEAdulteracao() {
        val caso = JSONObject(File("../../packages/converter-core/giphy-caso.json").readText(Charsets.UTF_8).removePrefix("\uFEFF"))
        val protegido = caso.getString("protegido")
        assertEquals(caso.getString("claro"), ConfiguracaoGiphy.abrir(protegido))
        val partes = protegido.split('.').toMutableList()
        partes[2] = (if (partes[2][0] == 'A') "B" else "A") + partes[2].drop(1)
        assertEquals("", ConfiguracaoGiphy.abrir(partes.joinToString(".")))
        assertEquals("", ConfiguracaoGiphy.abrir("v1.invalido"))
    }
}
