package br.com.smellsliketech.converter.musica

import android.content.Context
import br.com.smellsliketech.converter.conversao.ffmpeg.Ffmpeg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

data class ResumoDoProjeto(val id: String, val titulo: String, val faixas: Int, val atualizado: Long)

data class Exportado(val arquivo: File, val medidas: Medidas?, val ganhoDb: Double?)

/**
 * Os projetos musicais do Android: filesDir/musica/<id>/projeto.json e midias/<id>.wav (float32
 * na taxa do projeto). O projeto é gravado num temporário e trocado de uma vez; a mídia
 * importada nunca é alterada. Importar, exportar e separar passam pelo FFmpeg do próprio app e
 * pelo núcleo comum (Musica, MisturadorDaMusica, MasterDaMusica, SeparacaoDaMusica).
 */
class BibliotecaDaMusica(private val contexto: Context) {
    val raiz = File(contexto.filesDir, "musica")
    private val temporaria get() = File(contexto.cacheDir, "musica").apply { mkdirs() }
    val modelo = File(contexto.filesDir, "modelos/${SeparacaoDaMusica.ARQUIVO}")

    fun pasta(id: String) = File(raiz, id)
    fun midia(projeto: String, midia: String) = File(pasta(projeto), "midias/$midia.wav")

    fun listar(): List<ResumoDoProjeto> = raiz.listFiles { f -> f.isDirectory }.orEmpty().mapNotNull { dir ->
        runCatching {
            val p = ler(dir.name)
            ResumoDoProjeto(p.id, p.titulo, p.faixas.size, File(dir, "projeto.json").lastModified())
        }.getOrNull()
    }.sortedByDescending { it.atualizado }

    fun ler(id: String): ProjetoMusical {
        val arquivo = File(pasta(id), "projeto.json").takeIf { it.exists() } ?: File(pasta(id), "projeto.json.tmp")
        return ProjetoMusical.deTexto(arquivo.readText())
    }

    @Synchronized
    fun salvar(p: ProjetoMusical) {
        val dir = pasta(p.id).apply { File(this, "midias").mkdirs() }
        val temp = File(dir, "projeto.json.tmp")
        temp.writeText(p.paraJson().toString())
        check(temp.renameTo(File(dir, "projeto.json")) || run { File(dir, "projeto.json").delete(); temp.renameTo(File(dir, "projeto.json")) }) { "Não foi possível salvar o projeto." }
    }

    fun apagar(id: String) { pasta(id).deleteRecursively() }

    /** WAVs que o projeto não conhece: tomadas de uma gravação interrompida. */
    fun soltas(p: ProjetoMusical): List<MidiaMusical> {
        val conhecidas = p.midias.map { it.id }.toSet()
        return File(pasta(p.id), "midias").listFiles { f -> f.name.endsWith(".wav") }.orEmpty()
            .filter { it.nameWithoutExtension !in conhecidas && it.length() > 44 }
            .mapNotNull { f -> runCatching { FonteMapeada(f).use { MidiaMusical(f.nameWithoutExtension, f.name, "wav", p.taxa, it.canais, it.quadros) } }.getOrNull() }
    }

    private suspend fun ffmpeg(argumentos: List<String>) = Ffmpeg.executar(contexto, listOf("-hide_banner", "-nostdin", "-y") + argumentos, null)

    /** Qualquer áudio vira WAV float32 na taxa do projeto (mono continua mono). */
    suspend fun importar(entrada: File, projeto: String, midia: String, taxa: Int): Pair<Long, Int> {
        val destino = midia(projeto, midia).apply { parentFile?.mkdirs() }
        ffmpeg(listOf("-i", entrada.absolutePath, "-vn", "-af", "aformat=channel_layouts=mono|stereo", "-ar", "$taxa", "-c:a", "pcm_f32le", destino.absolutePath))
        return FonteMapeada(destino).use { require(it.quadros <= taxa * 7200L) { "O áudio passa de 2 horas." }; it.quadros to it.canais }
    }

    /** A mistura (com master), cada faixa ou uma faixa, no formato pedido. */
    suspend fun exportar(p: ProjetoMusical, formatoId: String, master: String, soLoop: Boolean, cadaFaixa: Boolean, cauda: Boolean, faixa: String?, pasta: File, progresso: (String, Double) -> Unit): List<Exportado> = withContext(Dispatchers.Default) {
        val formato = MasterDaMusica.FORMATOS.first { it.id == formatoId }
        val usarLoop = soLoop && p.loop.fim > p.loop.inicio
        val inicio = if (usarLoop) p.loop.inicio else 0L
        val fim = if (usarLoop) p.loop.fim else Musica.duracaoEmQuadros(p)
        require(fim > inicio) { "Não há nada para exportar: grave ou importe uma faixa primeiro." }
        val quadros = (fim - inicio + if (cauda) MisturadorDaMusica.caudaEmQuadros(p) else 0L).toInt()
        val titulo = nome(p.titulo)
        val alvos: List<Pair<String?, String>> = when {
            faixa != null -> p.faixas.filter { it.id == faixa }.map { it.id to "$titulo-${nome(it.nome)}" }
            cadaFaixa -> p.faixas.filter { f -> f.clipes.any { !it.mudo } }.map { it.id to "$titulo-${nome(it.nome)}" }
            else -> listOf(null to titulo)
        }
        pasta.mkdirs()
        alvos.mapIndexed { i, (id, nomeDoArquivo) ->
            coroutineContext.ensureActive()
            progresso(if (id == null) "Mixando" else "Mixando $nomeDoArquivo", i.toDouble() / alvos.size)
            val fontes = p.midias.associate { it.id to (FonteMapeada(midia(p.id, it.id)) as FonteDeAudio) }.toMutableMap()
            try {
                val transformacoes = p.faixas.flatMap { it.clipes }.filter { TransformacaoDaMusica.temTransformacao(it) }.distinctBy { TransformacaoDaMusica.chaveDaFonte(it) }
                for (clipe in transformacoes) {
                    coroutineContext.ensureActive()
                    val original = fontes[clipe.midia] ?: continue
                    val canais = TransformacaoDaMusica.lerTudo(original)
                    fontes[TransformacaoDaMusica.chaveDaFonte(clipe)] = FonteNaMemoria(TransformacaoDaMusica.transformar(canais, p.taxa, clipe.velocidade ?: 1.0, clipe.semitons ?: 0.0, clipe.preservarTom ?: true))
                }
                var (esq, dir) = MisturadorDaMusica.renderizar(p, fontes, inicio, quadros, id, metronomo = p.metronomo.naExportacao)
                var medidas: Medidas? = null
                var ganho: Double? = null
                if (id == null) {
                    progresso("Masterizando", (i + 0.8) / alvos.size)
                    val m = MasterDaMusica.masterizar(esq, dir, p.taxa, master)
                    esq = m.esq; dir = m.dir; medidas = m.depois; ganho = m.ganhoDb
                }
                progresso("Codificando", (i + 0.9) / alvos.size)
                val arquivo = File(pasta, "$nomeDoArquivo.${formato.extensao}")
                if (formato.extensao == "wav") arquivo.outputStream().buffered().use { MasterDaMusica.escreverWav(it, listOf(esq, dir), p.taxa, formato.bits) }
                else {
                    val temp = File(temporaria, "exportar-${System.nanoTime()}.wav")
                    temp.outputStream().buffered().use { MasterDaMusica.escreverWav(it, listOf(esq, dir), p.taxa, 32) }
                    try {
                        val codec = when (formato.id) {
                            "flac" -> listOf("-c:a", "flac", "-sample_fmt", "s32", "-bits_per_raw_sample", "24")
                            "mp3" -> listOf("-c:a", "libmp3lame", "-b:a", "320k")
                            "aac" -> listOf("-c:a", "aac", "-b:a", "256k")
                            else -> listOf("-c:a", "libopus", "-b:a", "192k")
                        }
                        ffmpeg(listOf("-i", temp.absolutePath) + codec + arquivo.absolutePath)
                    } finally { temp.delete() }
                }
                Exportado(arquivo, medidas, ganho)
            } finally {
                fontes.values.forEach { (it as? AutoCloseable)?.close() }
            }
        }.also { progresso("Pronto", 1.0) }
    }

    private fun nome(texto: String): String {
        val limpo = java.text.Normalizer.normalize(texto, java.text.Normalizer.Form.NFD).filter { it.isLetterOrDigit() && it.code < 128 || it == ' ' || it == '-' || it == '_' }.trim().replace(' ', '-')
        return limpo.ifEmpty { "musica" }.take(60)
    }

    val modeloPronto get() = modelo.exists() && modelo.length() == SeparacaoDaMusica.BYTES

    /** Baixa o modelo (78 MB) na primeira vez e confere o SHA-256 antes de usar. */
    suspend fun garantirModelo(progresso: (Double) -> Unit) = withContext(Dispatchers.IO) {
        if (modeloPronto) return@withContext
        modelo.parentFile?.mkdirs()
        val temp = File(modelo.path + ".baixando")
        val conexao = URL(SeparacaoDaMusica.URL).openConnection() as HttpURLConnection
        conexao.connectTimeout = 20_000
        conexao.readTimeout = 60_000
        try {
            check(conexao.responseCode == 200) { "Não foi possível baixar o modelo de separação (HTTP ${conexao.responseCode})." }
            val total = conexao.contentLengthLong.takeIf { it > 0 } ?: SeparacaoDaMusica.BYTES
            val resumo = MessageDigest.getInstance("SHA-256")
            conexao.inputStream.use { entrada ->
                temp.outputStream().use { saida ->
                    val b = ByteArray(1 shl 16)
                    var recebido = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = entrada.read(b)
                        if (n < 0) break
                        saida.write(b, 0, n)
                        resumo.update(b, 0, n)
                        recebido += n
                        progresso(recebido.toDouble() / total)
                    }
                }
            }
            val hex = resumo.digest().joinToString("") { "%02x".format(it) }
            if (hex != SeparacaoDaMusica.SHA256) { temp.delete(); error("O modelo baixado não confere (SHA-256 diferente) e foi descartado.") }
            check(temp.renameTo(modelo)) { "Não foi possível guardar o modelo." }
        } finally {
            conexao.disconnect()
        }
    }

    /**
     * Separa uma mídia do projeto: converte para 44,1 kHz, roda o modelo em trechos (memória
     * limitada), escreve uma faixa por WAV e volta para a taxa do projeto. Devolve os arquivos
     * na ordem do modo.
     */
    suspend fun separar(entrada: File, saida: File, modo: String, taxa: Int, progresso: (String, Double) -> Unit): List<Pair<FaixaDoModo, File>> = withContext(Dispatchers.Default) {
        check(modeloPronto) { "O modelo de separação não está baixado." }
        val trabalhoAtual = coroutineContext[kotlinx.coroutines.Job]
        val trabalho = File(temporaria, "separacao-${System.nanoTime()}").apply { mkdirs() }
        try {
            progresso("Preparando o áudio", 0.0)
            val a44 = File(trabalho, "entrada.wav")
            ffmpeg(listOf("-i", entrada.absolutePath, "-ac", "2", "-ar", "44100", "-c:a", "pcm_f32le", a44.absolutePath))
            val definicao = SeparacaoDaMusica.modo(modo) ?: error("Modo de separação desconhecido.")
            val escritores = definicao.faixas.map { EscritorDeWav(File(trabalho, "${it.id}-44k.wav"), 2, SeparacaoDaMusica.TAXA) }
            val esquerdos = arrayOfNulls<FloatArray>(definicao.faixas.size)
            val forma = longArrayOf(1, 2, SeparacaoDaMusica.QUADROS_POR_TRECHO.toLong(), SeparacaoDaMusica.BANDAS.toLong())
            FonteMapeada(a44).use { fonte ->
                InferenciaOnnx(modelo.absolutePath, maxOf(1, Runtime.getRuntime().availableProcessors() - 1)).use { sessao ->
                    try {
                        SeparacaoDaMusica.separar(
                            fonte.quadros, fonte.canais,
                            { c, inicio, n, destino -> fonte.copiar(c, inicio, n, destino) },
                            { magnitude -> sessao.executar(magnitude, forma, "magnitude", "estimativas") },
                            { f, c, _, amostras, n ->
                                val copia = amostras.copyOf(n)
                                if (c == 0) esquerdos[f] = copia else escritores[f].escreverCanais(listOf(esquerdos[f]!!, copia), n)
                            },
                            modo,
                            progresso = { progresso("Separando as faixas", it) },
                            cancelado = { trabalhoAtual?.isActive == false },
                        )
                    } finally {
                        escritores.forEach { it.close() }
                    }
                }
            }
            saida.mkdirs()
            definicao.faixas.map { faixa ->
                progresso("Voltando ${faixa.rotulo} à taxa do projeto", 1.0)
                val destino = File(saida, "${faixa.id}-${System.nanoTime()}.wav")
                ffmpeg(listOf("-i", File(trabalho, "${faixa.id}-44k.wav").absolutePath, "-ar", "$taxa", "-c:a", "pcm_f32le", destino.absolutePath))
                faixa to destino
            }
        } finally {
            trabalho.deleteRecursively()
        }
    }

    /** Redução de ruído offline numa mídia: escreve um WAV novo (estéreo). */
    suspend fun reduzirRuido(entrada: File, saida: File, taxa: Int) = withContext(Dispatchers.Default) {
        FonteMapeada(entrada).use { fonte ->
            EscritorDeWav(saida, 2, taxa).use { escritor ->
                var esquerdo: FloatArray? = null
                SeparacaoDaMusica.reduzirRuido(fonte.quadros, fonte.canais, { c, inicio, n, d -> fonte.copiar(c, inicio, n, d) }, EmissorDeAmostras { c, _, amostras, n ->
                    if (c == 0) esquerdo = amostras.copyOf(n) else escritor.escreverCanais(listOf(esquerdo!!, amostras.copyOf(n)), n)
                })
            }
        }
    }
}
