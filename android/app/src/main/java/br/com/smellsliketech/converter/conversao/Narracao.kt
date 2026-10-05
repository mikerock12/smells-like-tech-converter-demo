package br.com.smellsliketech.converter.conversao

import android.content.Context
import br.com.smellsliketech.converter.conversao.ffmpeg.Comandos
import br.com.smellsliketech.converter.conversao.ffmpeg.Ffmpeg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Todas as entradas narradas passam pelo mesmo Kokoro local e pelo mesmo codificador. */
object Narracao {
    suspend fun narrar(context: Context, texto: String, nomeBase: String, opcoes: Opcoes,
        aoEstimar: (Int?, String) -> Unit = { _, _ -> }, aoAvancar: (Int) -> Unit): Resultado {
        if (texto.isBlank()) throw ErroDeConversao("Não há texto para narrar.")
        Comandos.validarAudio(opcoes)
        val wav = Saida.temporario(context, "wav")
        try {
            Kokoro.gravar(context, texto, opcoes, wav, aoEstimar, aoAvancar)
            aoEstimar(null, "Salvando áudio…")
            val nomeDeSaida = Saida.nomeDeSaida(nomeBase, "narracao", opcoes.formato)
            if (opcoes.formato == "wav" && opcoes.taxaDeAmostragem == 0 && opcoes.canais == 0 &&
                opcoes.volume == 1.0 && !opcoes.normalizar && opcoes.fadeEntrada == 0.0 && opcoes.fadeSaida == 0.0) {
                return withContext(Dispatchers.IO) { Saida.gravarArquivo(context, nomeDeSaida, "audio/wav", wav) }
            }
            val saida = Saida.temporario(context, opcoes.formato)
            try {
                aoEstimar(null, "Codificando áudio…")
                val duracao = (wav.length() - 44).coerceAtLeast(0) / 48000.0
                Ffmpeg.executar(context, Comandos.audio(wav.absolutePath, saida.absolutePath, opcoes, duracao), duracao) {
                    aoAvancar(85 + it.coerceIn(0, 100) * 13 / 100)
                }
                aoEstimar(null, "Salvando áudio…")
                aoAvancar(99)
                return withContext(Dispatchers.IO) { Saida.gravarArquivo(context, nomeDeSaida, Mimes.de(opcoes.formato), saida) }
            } finally { saida.delete() }
        } finally { wav.delete() }
    }
}
