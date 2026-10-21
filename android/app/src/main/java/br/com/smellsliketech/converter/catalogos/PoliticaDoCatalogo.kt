package br.com.smellsliketech.converter.catalogos

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Mesmas regras de packages/converter-core/catalogo-stickers.mjs, sem dependência Android. */
data class EstadoDoCatalogo(
    val tentativas: List<Long> = emptyList(),
    val tentarEm: Long = 0,
    val motivo: String? = null,
    val falhas: Int = 0,
)

object PoliticaDoCatalogo {
    const val HORA = 3_600_000L
    const val LIMITE = 100

    fun preparar(estado: EstadoDoCatalogo, agora: Long): EstadoDoCatalogo {
        val recentes = estado.tentativas.filter { it > agora - HORA && it <= agora }
        val atual = estado.copy(tentativas = recentes)
        if (atual.tentarEm > agora) return atual
        return if (recentes.size >= LIMITE)
            atual.copy(tentarEm = recentes.min() + HORA, motivo = "limite")
        else atual.copy(tentarEm = 0, motivo = null)
    }

    fun resposta(estado: EstadoDoCatalogo, status: Int, agora: Long, retryAfter: String? = null): EstadoDoCatalogo {
        if (status == 200) return estado.copy(tentarEm = 0, motivo = null, falhas = 0)
        val falhas = (estado.falhas + 1).coerceIn(1, 100)
        val fator = 1L shl (falhas - 1).coerceAtMost(4)
        val espera = when (status) {
            429 -> espera(retryAfter, agora, (60_000 * fator).coerceAtMost(900_000))
            401, 403 -> HORA
            else -> (30_000 * fator).coerceAtMost(300_000)
        }
        return estado.copy(tentarEm = agora + espera, motivo = if (status == 429) "limite" else "indisponivel", falhas = falhas)
    }

    fun espera(valor: String?, agora: Long, padrao: Long = 60_000): Long {
        val texto = valor?.trim().orEmpty()
        if (texto.matches(Regex("\\d+(\\.\\d+)?"))) {
            return (texto.toDouble() * 1000).toLong().coerceAtLeast(1000)
        }
        val data = runCatching { ZonedDateTime.parse(texto, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
        return if (data != null && data > agora) data - agora else padrao
    }

    fun aviso(motivo: String?): String? = when (motivo) {
        "limite" -> "O GIPHY atingiu o limite de consultas. Mostrando Google Noto por enquanto. O GIPHY voltará automaticamente quando estiver disponível."
        "indisponivel" -> "O GIPHY está indisponível. Mostrando Google Noto enquanto tentamos restabelecer a busca."
        else -> null
    }
}
