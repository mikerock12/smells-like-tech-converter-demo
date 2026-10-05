package br.com.smellsliketech.converter.conversao

import android.content.Context
import android.graphics.BitmapFactory
import android.os.SystemClock
import br.com.smellsliketech.converter.conversao.documentos.EscritorDePdf
import br.com.smellsliketech.converter.conversao.documentos.Escritores
import br.com.smellsliketech.converter.conversao.ffmpeg.Codificador
import br.com.smellsliketech.converter.conversao.ffmpeg.CodificadorDoCelular
import br.com.smellsliketech.converter.conversao.ffmpeg.Comandos
import br.com.smellsliketech.converter.conversao.ffmpeg.Ffmpeg
import br.com.smellsliketech.converter.conversao.ffmpeg.Sondagem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Vídeo e áudio com o FFmpeg, as mesmas operações do aplicativo para Windows. A entrada é
 * copiada para o cache (o FFmpeg lê arquivo, não content://) e a saída vai para Downloads.
 */
object Midia {
    private fun duracaoProcessada(sondagem: Sondagem, opcoes: Opcoes): Double? {
        val total = (sondagem.duracaoSegundos ?: return null) * opcoes.repeticoesDoGif
        val fim = if (opcoes.fimSegundos > opcoes.inicioSegundos) minOf(opcoes.fimSegundos, total) else total
        return (fim - opcoes.inicioSegundos).coerceAtLeast(0.1) / opcoes.velocidade
    }

    private fun conferirCorte(opcoes: Opcoes, sondagem: Sondagem) {
        val total = sondagem.duracaoSegundos ?: return
        if (opcoes.inicioSegundos >= total) throw ErroDeConversao("O início do corte passa do fim do arquivo (${total.toInt()} s).")
    }

    suspend fun audio(context: Context, entrada: Entrada, opcoes: Opcoes, sufixo: String, aoAvancar: (Int) -> Unit): Resultado =
        Ffmpeg.comCopia(context, entrada) { copia ->
            val sondagem = Ffmpeg.sondar(context, copia)
            if (!sondagem.temAudio) throw ErroDeConversao("${entrada.nome} não tem áudio.")
            conferirCorte(opcoes, sondagem)
            val saida = Saida.temporario(context, opcoes.formato)
            Ffmpeg.executar(context, Comandos.audio(copia.absolutePath, saida.absolutePath, opcoes, sondagem.duracaoSegundos), duracaoProcessada(sondagem, opcoes), aoAvancar = aoAvancar)
            gravar(context, entrada, sufixo, opcoes.formato, saida)
        }

    suspend fun video(context: Context, entrada: Entrada, opcoes: Opcoes, sufixo: String, aoAvancar: (Int) -> Unit): Resultado =
        Ffmpeg.comCopia(context, entrada) { copia ->
            val sondagem = Ffmpeg.sondar(context, copia)
            if (!sondagem.temVideo) throw ErroDeConversao("${entrada.nome} não tem imagem de vídeo.")
            conferirCorte(opcoes, sondagem)
            val saida = Saida.temporario(context, opcoes.formato)
            var falha: ErroDeConversao? = null
            // O codificador do celular primeiro; se o aparelho recusar, o MPEG-4 do FFmpeg.
            for (codificador in Codificador.para(opcoes.formato)) {
                try {
                    if (codificador == Codificador.H264_CELULAR) {
                        pelaCelular(context, copia, saida, opcoes, sondagem, aoAvancar)
                    } else {
                        Ffmpeg.executar(context, Comandos.video(copia.absolutePath, saida.absolutePath, opcoes, sondagem, codificador), duracaoProcessada(sondagem, opcoes), aoAvancar = aoAvancar)
                    }
                    falha = null
                    break
                } catch (erro: ErroDeConversao) {
                    falha = erro
                }
            }
            falha?.let { saida.delete(); throw it }
            gravar(context, entrada, sufixo, opcoes.formato, saida)
        }

    /** H.264 pelo codificador do celular: o FFmpeg filtra, o MediaCodec codifica, o FFmpeg junta com o áudio. */
    private suspend fun pelaCelular(context: Context, copia: File, saida: File, opcoes: Opcoes, sondagem: Sondagem, aoAvancar: (Int) -> Unit) {
        val quadros = Comandos.quadrosDoCelular(opcoes, sondagem) ?: throw ErroDeConversao("O vídeo não informou o tamanho da imagem.")
        if (!CodificadorDoCelular.aceita(quadros)) throw ErroDeConversao("O codificador do celular não aceita ${quadros.largura}x${quadros.altura}.")
        val video = Saida.temporario(context, "mp4")
        val audio = if (!opcoes.removerAudio && sondagem.temAudio) Saida.temporario(context, "m4a") else null
        try {
            val argumentos = Comandos.videoParaOCelular(copia.absolutePath, audio?.absolutePath, opcoes, sondagem, quadros)
            val bitrate = Comandos.bitrateDoVideo(quadros.largura, quadros.altura, quadros.porSegundo.toDouble(), opcoes.qualidadeDoVideo)
            val total = duracaoProcessada(sondagem, opcoes)?.let { (it * quadros.porSegundo).toInt() }
            CodificadorDoCelular.codificar(context, argumentos, quadros, bitrate, video, total, aoAvancar)
            Ffmpeg.executar(context, Comandos.juntar(video.absolutePath, audio?.takeIf { it.length() > 0 }?.absolutePath, saida.absolutePath, opcoes.formato), null)
        } finally {
            video.delete()
            audio?.delete()
        }
    }

    suspend fun gif(context: Context, entrada: Entrada, opcoes: Opcoes, aoAvancar: (Int) -> Unit): Resultado =
        Ffmpeg.comCopia(context, entrada) { copia ->
            val sondagem = Ffmpeg.sondar(context, copia)
            if (!sondagem.temVideo) throw ErroDeConversao("${entrada.nome} não tem imagem de vídeo.")
            conferirCorte(opcoes, sondagem)
            val saida = Saida.temporario(context, "gif")
            val comando = Comandos.gif(copia.absolutePath, saida.absolutePath, opcoes)
            val fim = if (opcoes.fimSegundos > opcoes.inicioSegundos) minOf(opcoes.fimSegundos, opcoes.inicioSegundos + 120) else opcoes.inicioSegundos + 10
            Ffmpeg.executar(context, comando, fim - opcoes.inicioSegundos, aoAvancar = aoAvancar)
            gravar(context, entrada, "", "gif", saida)
        }

    /** Quadros do vídeo, um arquivo por imagem. WEBP sai do FFmpeg em PNG e o Android recodifica. */
    suspend fun quadros(context: Context, entrada: Entrada, opcoes: Opcoes, aoAvancar: (Int) -> Unit): List<Resultado> =
        Ffmpeg.comCopia(context, entrada) { copia ->
            val sondagem = Ffmpeg.sondar(context, copia)
            if (!sondagem.temVideo) throw ErroDeConversao("${entrada.nome} não tem imagem de vídeo.")
            conferirCorte(opcoes, sondagem)
            val pasta = File(context.cacheDir, "quadros-${System.nanoTime()}").apply { mkdirs() }
            try {
                val extensao = if (opcoes.formato == "jpg") "jpg" else "png"
                Ffmpeg.executar(context, Comandos.quadros(copia.absolutePath, File(pasta, "q%04d.$extensao").absolutePath, opcoes), duracaoProcessada(sondagem, opcoes), aoAvancar = { aoAvancar(it * 8 / 10) })
                val arquivos = pasta.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }
                if (arquivos.isEmpty()) throw ErroDeConversao("Nenhum quadro no trecho escolhido.")
                arquivos.mapIndexed { indice, arquivo ->
                    val nome = Saida.nomeDeSaida(entrada.nome, "quadro-${(indice + 1).toString().padStart(4, '0')}", opcoes.formato)
                    val resultado = if (opcoes.formato == "webp") {
                        val bitmap = BitmapFactory.decodeFile(arquivo.absolutePath) ?: throw ErroDeConversao("Quadro ilegível.")
                        Saida.gravar(context, nome, Mimes.de("webp")) { Imagem.comprimir(bitmap, "webp", 90, it) }.also { bitmap.recycle() }
                    } else {
                        Saida.gravarArquivo(context, nome, Mimes.de(opcoes.formato), arquivo)
                    }
                    aoAvancar(80 + (indice + 1) * 20 / arquivos.size)
                    resultado
                }
            } finally {
                pasta.deleteRecursively()
            }
        }

    /**
     * Transcrição com o Whisper, no próprio celular. O filtro whisper do FFmpeg grava SRT; o
     * resultado volta como [Transcricao] (texto na tela, e a pessoa escolhe em que formato
     * salvar). A porcentagem e o tempo que falta saem de [ProgressoDaTranscricao], a cada segundo.
     */
    suspend fun transcrever(
        context: Context,
        entrada: Entrada,
        opcoes: Opcoes,
        aoAvancar: (Int) -> Unit,
        aoEstimar: (Int?) -> Unit = {},
    ): Transcricao {
        val modelo = ModeloWhisper.de(opcoes.modelo) ?: throw ErroDeConversao("Escolha um modelo de transcrição.")
        if (!ModelosWhisper.instalado(context, modelo)) throw ErroDeConversao("Baixe o modelo ${modelo.rotulo} antes de transcrever (em Modelos de transcrição).")
        return Ffmpeg.comCopia(context, entrada) { copia ->
            val sondagem = Ffmpeg.sondar(context, copia)
            if (!sondagem.temAudio) throw ErroDeConversao("${entrada.nome} não tem áudio para transcrever.")
            conferirCorte(opcoes, sondagem)
            val duracao = duracaoProcessada(sondagem, opcoes.copy(velocidade = 1.0)) ?: 0.0
            val srt = Saida.temporario(context, "srt")
            val comeco = SystemClock.elapsedRealtime()
            val progresso = ProgressoDaTranscricao(duracao, ModelosWhisper.ritmo(context, modelo), modelo.cargaSegundos)
            try {
                val comando = Comandos.transcrever(
                    copia.absolutePath, srt.absolutePath, ModelosWhisper.arquivo(context, modelo).absolutePath,
                    opcoes.idioma, "srt", opcoes.inicioSegundos, opcoes.fimSegundos,
                    linhas = Runtime.getRuntime().availableProcessors(),
                )
                coroutineScope {
                    val relogio = launch {
                        while (isActive) {
                            val (porcentagem, restante) = synchronized(progresso) { progresso.estado(SystemClock.elapsedRealtime(), comeco) }
                            aoAvancar(porcentagem)
                            aoEstimar(restante)
                            delay(1_000)
                        }
                    }
                    try {
                        Ffmpeg.executar(context, comando, null, aoLerLog = { linha ->
                            if ("run transcription" in linha) synchronized(progresso) { progresso.linha(linha, SystemClock.elapsedRealtime()) }
                        })
                    } finally {
                        relogio.cancel()
                    }
                }
                synchronized(progresso) { progresso.ritmoMedido(SystemClock.elapsedRealtime()) }?.let { ModelosWhisper.guardarRitmo(context, modelo, it) }
                val trechos = Legenda.limpar(Legenda.ler(withContext(Dispatchers.IO) { srt.readText() }), (duracao * 1000).toLong().takeIf { it > 0 })
                if (trechos.isEmpty()) throw ErroDeConversao("O Whisper não ouviu fala em ${entrada.nome}.")
                Transcricao(entrada.nome, trechos)
            } finally {
                srt.delete()
            }
        }
    }

    /** Salva uma transcrição no formato escolhido, em Downloads/SmellsLikeTech. */
    suspend fun salvar(context: Context, transcricao: Transcricao, formato: String): Resultado = withContext(Dispatchers.IO) {
        val nome = Saida.nomeDeSaida(transcricao.nomeOriginal, "transcricao", formato)
        Saida.gravar(context, nome, Mimes.de(formato)) { saida ->
            when (formato) {
                "srt", "vtt" -> saida.write(Legenda.gravar(transcricao.trechos, formato).toByteArray(Charsets.UTF_8))
                "txt" -> saida.write((transcricao.texto + "\n").toByteArray(Charsets.UTF_8))
                "md" -> saida.write(Escritores.markdown(transcricao.documento()).toByteArray(Charsets.UTF_8))
                "docx" -> saida.write(Escritores.docx(transcricao.documento()))
                "pdf" -> EscritorDePdf.escrever(transcricao.documento(), saida)
                else -> throw ErroDeConversao("Formato não suportado: $formato.")
            }
        }
    }

    private suspend fun gravar(context: Context, entrada: Entrada, sufixo: String, formato: String, arquivo: File): Resultado =
        withContext(Dispatchers.IO) {
            if (arquivo.length() == 0L) {
                arquivo.delete()
                throw ErroDeConversao("A conversão não gerou nada.")
            }
            val nome = Saida.nomeDeSaida(entrada.nome, sufixo.ifBlank { if (entrada.extensao == formato) "convertido" else "" }, formato)
            Saida.gravarArquivo(context, nome, Mimes.de(formato), arquivo)
        }
}
