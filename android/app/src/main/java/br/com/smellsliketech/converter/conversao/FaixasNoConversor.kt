package br.com.smellsliketech.converter.conversao

import android.content.Context
import br.com.smellsliketech.converter.conversao.ffmpeg.Ffmpeg
import br.com.smellsliketech.converter.musica.BibliotecaDaMusica
import br.com.smellsliketech.converter.musica.FaixaDoModo
import br.com.smellsliketech.converter.musica.FonteWav
import br.com.smellsliketech.converter.musica.MasterDaMusica
import br.com.smellsliketech.converter.musica.PacoteDeFaixas
import br.com.smellsliketech.converter.musica.PedidoDoPacote
import br.com.smellsliketech.converter.musica.ProblemaNoPacote
import br.com.smellsliketech.converter.musica.RoFormer
import java.io.File
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * As ferramentas experimentais do conversor: um áudio vira um .zip com as faixas separadas,
 * juntas do jeito pedido e com os efeitos de cada faixa; um vídeo fica só com a voz ou sem
 * ela. A separação (Spleeter ou RoFormer) e a mistura são as do estúdio musical. Tudo roda no
 * serviço em primeiro plano da fila, então continua com a tela apagada ou em outro app.
 */
object FaixasNoConversor {
    /** As faixas são misturadas na taxa do modelo, sem reamostrar duas vezes. */
    private const val TAXA = 44_100

    private fun pedido(opcoes: Opcoes, manter: String? = null): PedidoDoPacote = try {
        PacoteDeFaixas.lerPedido(opcoes.pacote, manterPadrao = manter)
    } catch (erro: ProblemaNoPacote) {
        throw ErroDeConversao(erro.message ?: "Pedido de separação inválido.")
    }

    /** Separa com o modelo do pedido, guardando as faixas como mídias de um projeto temporário. */
    private suspend fun separar(
        context: Context, biblioteca: BibliotecaDaMusica, entrada: File, pedido: PedidoDoPacote, projeto: String, aoAvancar: (Int) -> Unit,
    ): Pair<List<Pair<FaixaDoModo, File>>, Long> {
        val modelo = PacoteDeFaixas.modelo(pedido.modelo)!!
        val roformer = modelo.id == "roformer"
        if (roformer && !RoFormer.disponivel) throw ErroDeConversao("O RoFormer não roda neste aparelho. Escolha o Spleeter.")
        aoAvancar(pct("modelo", 0.0))
        biblioteca.garantirModelo(roformer) { f -> aoAvancar(pct("modelo", f)) }
        val pasta = File(biblioteca.pasta(projeto), "separadas").apply { mkdirs() }
        val t = pedido.tratamento
        if (t.aceleracao && !roformer) Processadores.ligarAcelerador(context, true)
        val faixas = biblioteca.separar(entrada, pasta, modelo.modo, TAXA, t.refinarAgudos, t.recuperarBateria) { _, f -> aoAvancar(pct("separando", f)) }
        // A mistura lê as mídias do projeto pelo id: cada faixa vira a mídia com o id dela.
        val guardadas = faixas.map { (definicao, arquivo) ->
            val destino = biblioteca.midia(projeto, definicao.id).apply { parentFile?.mkdirs() }
            // A mesma qualidade do estúdio musical: ruído de cada faixa e restauração por instrumento.
            var atual = arquivo
            if (t.reduzirRuido) {
                val limpo = File(pasta, "${definicao.id}-limpo.wav")
                biblioteca.reduzirRuido(atual, limpo, TAXA)
                atual.delete()
                atual = limpo
            }
            if (t.restauracao > 0 && br.com.smellsliketech.converter.musica.NucleoNativo.disponivel) {
                val restaurado = File(pasta, "${definicao.id}-restaurado.wav")
                biblioteca.restaurarFaixa(atual, restaurado, TAXA, definicao.id, t.restauracao)
                atual.delete()
                atual = restaurado
            }
            check(atual.renameTo(destino)) { "Não foi possível guardar a faixa ${definicao.rotulo}." }
            definicao to destino
        }
        val quadros = guardadas.firstOrNull()?.second?.let { f -> FonteWav(f).use { it.quadros } } ?: 0L
        if (quadros <= 0L) throw ErroDeConversao("A separação não devolveu áudio.")
        return guardadas to quadros
    }

    private fun pct(etapa: String, f: Double) = (PacoteDeFaixas.fracao(etapa, f) * 100).toInt()

    /** Mistura um arquivo do pacote pelo exportador do estúdio musical. */
    private suspend fun misturar(
        biblioteca: BibliotecaDaMusica, pedido: PedidoDoPacote, projeto: String, indice: Int, total: Int, quadros: Long,
        formato: String, cauda: Boolean, pasta: File, aoAvancar: (Int) -> Unit,
    ): File {
        val configuracao = pedido.configuracao.copy(id = projeto, titulo = "arquivo-${indice + 1}", taxa = TAXA)
        val p = PacoteDeFaixas.projetoDoArquivo(configuracao, pedido.grupos[indice], quadros)
        return biblioteca.exportar(p, formato, pedido.master, false, false, cauda, null, pasta) { _, f ->
            aoAvancar(pct("mixando", (indice + f) / total))
        }.single().arquivo
    }

    suspend fun pacote(context: Context, entrada: Entrada, opcoes: Opcoes, aoAvancar: (Int) -> Unit): Resultado =
        Ffmpeg.comCopia(context, entrada) { copia ->
            val pedido = pedido(opcoes)
            val biblioteca = BibliotecaDaMusica(context)
            val projeto = "pacote-${System.nanoTime()}"
            try {
                aoAvancar(pct("lendo", 0.5))
                val (_, quadros) = separar(context, biblioteca, copia, pedido, projeto, aoAvancar)
                val formato = MasterDaMusica.FORMATOS.firstOrNull { it.id == pedido.formato } ?: MasterDaMusica.FORMATOS.first()
                val nomes = PacoteDeFaixas.nomesDosArquivos(entrada.nome.substringBeforeLast('.'), pedido.grupos, formato.extensao)
                val pasta = File(biblioteca.pasta(projeto), "saida").apply { mkdirs() }
                val arquivos = pedido.grupos.indices.map { i -> misturar(biblioteca, pedido, projeto, i, pedido.grupos.size, quadros, formato.id, true, File(pasta, "$i"), aoAvancar) }
                aoAvancar(pct("juntando", 0.2))
                Saida.gravar(context, Saida.nomeDeSaida(entrada.nome, "faixas", "zip"), "application/zip") { saida ->
                    ZipOutputStream(saida).use { zip ->
                        // Áudio já comprimido (MP3, FLAC) ou WAV grande: guardar sem recomprimir é mais rápido.
                        zip.setLevel(Deflater.NO_COMPRESSION)
                        arquivos.forEachIndexed { i, arquivo ->
                            zip.putNextEntry(ZipEntry(nomes[i]))
                            arquivo.inputStream().use { it.copyTo(zip) }
                            zip.closeEntry()
                            aoAvancar(pct("juntando", (i + 1.0) / arquivos.size))
                        }
                    }
                }
            } finally {
                biblioteca.pasta(projeto).deleteRecursively()
            }
        }

    suspend fun vozDoVideo(context: Context, entrada: Entrada, opcoes: Opcoes, aoAvancar: (Int) -> Unit): Resultado =
        Ffmpeg.comCopia(context, entrada) { copia ->
            val bruto = pedido(opcoes, manter = "resto")
            val manter = bruto.manter ?: "resto"
            val pedido = bruto.copy(grupos = listOf(PacoteDeFaixas.grupoDoVideo(bruto.modelo, manter)))
            val sondagem = Ffmpeg.sondar(context, copia)
            if (!sondagem.temVideo) throw ErroDeConversao("${entrada.nome} não tem imagem de vídeo.")
            if (!sondagem.temAudio) throw ErroDeConversao("${entrada.nome} não tem áudio para separar.")
            val biblioteca = BibliotecaDaMusica(context)
            val projeto = "voz-${System.nanoTime()}"
            try {
                aoAvancar(pct("lendo", 0.0))
                val audio = File(biblioteca.pasta(projeto), "audio-do-video.wav").apply { parentFile?.mkdirs() }
                Ffmpeg.executar(context, listOf("-hide_banner", "-nostdin", "-y", "-i", copia.absolutePath, "-vn", "-ac", "2", "-ar", "$TAXA", "-c:a", "pcm_f32le", audio.absolutePath), null)
                val (_, quadros) = separar(context, biblioteca, audio, pedido, projeto, aoAvancar)
                val mistura = misturar(biblioteca, pedido, projeto, 0, 1, quadros, "wav-32f", false, File(biblioteca.pasta(projeto), "saida"), aoAvancar)
                // MP4/MOV/3GP continuam MP4; o resto vira MKV, que aceita qualquer vídeo copiado com AAC.
                val extensao = if (entrada.extensao in setOf("mp4", "m4v", "mov", "3gp")) "mp4" else "mkv"
                val saida = Saida.temporario(context, extensao)
                aoAvancar(pct("juntando", 0.1))
                Ffmpeg.executar(context, listOf("-hide_banner", "-nostdin", "-y", "-i", copia.absolutePath, "-i", mistura.absolutePath,
                    "-map", "0:v", "-map", "1:a:0", "-c:v", "copy", "-c:a", "aac", "-b:a", "192k", "-map_metadata", "0") +
                    (if (extensao == "mp4") listOf("-movflags", "+faststart") else emptyList()) + saida.absolutePath,
                    sondagem.duracaoSegundos) { aoAvancar(pct("juntando", it / 100.0)) }
                Saida.gravarArquivo(context, Saida.nomeDeSaida(entrada.nome, if (manter == "voz") "so-voz" else "sem-voz", extensao),
                    if (extensao == "mp4") "video/mp4" else "video/x-matroska", saida)
            } finally {
                biblioteca.pasta(projeto).deleteRecursively()
            }
        }
}
