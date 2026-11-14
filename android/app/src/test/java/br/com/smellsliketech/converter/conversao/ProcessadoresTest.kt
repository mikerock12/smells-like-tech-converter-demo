package br.com.smellsliketech.converter.conversao

import org.junit.Assert.assertEquals
import org.junit.Test

class ProcessadoresTest {
    @Test
    fun soOsNucleosRapidosNoBigLittle() {
        // Snapdragon 8 Gen 2: 1 de 3,2 GHz, 4 de 2,8 GHz e 3 de 2,0 GHz.
        assertEquals(5, Processadores.rapidos(listOf(2_016_000, 2_016_000, 2_016_000, 2_803_200, 2_803_200, 2_803_200, 2_803_200, 3_187_200).map { it.toLong() }))
        // Dimensity 7300 (POCO X7 Pro é 8400; o padrão é o mesmo): 4 de 2,5 GHz e 4 de 2,0 GHz.
        assertEquals(4, Processadores.rapidos(listOf(2_000_000L, 2_000_000, 2_000_000, 2_000_000, 2_500_000, 2_500_000, 2_500_000, 2_500_000)))
    }

    @Test
    fun semBigLittleOuSemInformacaoDeixaUmNucleoLivre() {
        assertEquals(3, Processadores.rapidos(listOf(3_000_000L, 3_000_000, 3_000_000, 3_000_000)))
        assertEquals(7, Processadores.rapidos(listOf(0L, 0, 0, 0, 0, 0, 0, 0)))
        assertEquals(1, Processadores.rapidos(listOf(0L)))
    }
}
