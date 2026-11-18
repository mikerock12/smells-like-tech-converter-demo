package br.com.smellsliketech.converter.conversao

import android.content.Context
import br.com.smellsliketech.converter.conversao.ffmpeg.Ffmpeg
import br.com.smellsliketech.converter.musica.BibliotecaDaMusica
import br.com.smellsliketech.converter.musica.NucleoNativo
import br.com.smellsliketech.converter.musica.RestauradorDeAudio
import kotlin.math.abs

/**
 * Restaurar áudio com IA (experimental) no conversor: diagnóstico, plano e reparo no núcleo
 * nativo, neste celular, e o arquivo no formato pedido. O original não muda. Roda no serviço
 * em primeiro plano da fila, então continua com a tela apagada ou em outro app.
 */
object RestauradorNoConversor {
    suspend fun restaurar(context: Context, entrada: Entrada, opcoes: Opcoes, aoAvancar: (Int) -> Unit): Resultado {
        if (!NucleoNativo.temRestaurador) throw ErroDeConversao("O restaurador precisa do núcleo de áudio deste aparelho, que não carregou.")
        val pedido = try { RestauradorDeAudio.lerOpcoes(opcoes.restauracao) } catch (e: IllegalArgumentException) { throw ErroDeConversao(e.message ?: "Opções inválidas.") }
        return Ffmpeg.comCopia(context, entrada) { copia ->
            val biblioteca = BibliotecaDaMusica(context)
            val extensao = when (pedido.formato) { "flac" -> "flac"; "mp3" -> "mp3"; else -> "wav" }
            val saida = Saida.temporario(context, extensao)
            try {
                val r = biblioteca.restaurarAudio(copia, saida, pedido) { _, f -> aoAvancar((f * 100).toInt().coerceIn(0, 99)) }
                val nome = RestauradorDeAudio.nomeDoRestaurado(entrada.nome, extensao)
                val volume = when {
                    abs(r.ganhoFinalDb) < 0.05 -> ""
                    pedido.manterVolume -> " · volume igualado ao original (${"%+.1f".format(java.util.Locale.ROOT, r.ganhoFinalDb)} dB; sem passar de −1 dBFS de pico)"
                    else -> " · volume subiu ${"%.1f".format(java.util.Locale.ROOT, r.ganhoFinalDb)} dB até o pico de −1 dBFS (mais alto que o original: compare no mesmo volume)"
                }
                val nota = if (r.plano.semMudanca) "${RestauradorDeAudio.textoDoAviso(r.plano.avisos.last())} ${RestauradorDeAudio.resumo(r.diagnostico)}"
                    else "Restaurado: ${r.plano.etapas.joinToString { RestauradorDeAudio.rotuloDaEtapa(it.first) }} · Encontrado: ${RestauradorDeAudio.resumo(r.diagnostico)}" +
                        " · Antes → depois: ${RestauradorDeAudio.antesEDepois(r.diagnostico, r.depois)}$volume · experimental"
                Saida.gravarArquivo(context, nome, Mimes.de(extensao), saida).copy(nota = nota)
            } catch (e: IllegalStateException) {
                throw ErroDeConversao(e.message ?: "Não foi possível restaurar este áudio.", e)
            } finally {
                saida.delete()
            }
        }
    }
}
