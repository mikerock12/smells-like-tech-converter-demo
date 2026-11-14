package br.com.smellsliketech.converter.musica

import android.content.Context
import br.com.smellsliketech.converter.conversao.ffmpeg.Ffmpeg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlin.math.pow

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
    private fun arquivoDoModelo(roformer: Boolean) = if (roformer) File(modelo.parentFile, RoFormer.ARQUIVO) else modelo
    private fun tamanhoDoModelo(roformer: Boolean) = if (roformer) RoFormer.BYTES else SeparacaoDaMusica.BYTES
    private fun hashDoModelo(roformer: Boolean) = if (roformer) RoFormer.SHA256 else SeparacaoDaMusica.SHA256
    private val travaDaTransformacao = Mutex()
    private val travaDoModelo = Mutex()

    suspend fun restaurarFaixa(entrada: File, saida: File, taxa: Int, instrumento: String, intensidade: Int) = withContext(Dispatchers.IO) {
        val perfil = when (instrumento) { "vozes" -> 0; "bateria" -> 1; "baixo" -> 2; else -> 3 }
        val temp = File.createTempFile("stem-", ".wav", temporaria)
        val e = FloatArray(65536)
        val d = FloatArray(e.size)
        val diretoE = NucleoNativo.plano(e.size)
        val diretoD = NucleoNativo.plano(d.size)
        val canais = listOf(e, d)
        try {
            NucleoNativo.Restaurador(taxa, perfil, intensidade.toFloat()).use { rest ->
                FonteWav(entrada).use { fonte ->
                    EscritorDeWav(temp, 2, taxa).use { escritor ->
                        var inicio = 0L
                        while (inicio < fonte.quadros) {
                            coroutineContext.ensureActive()
                            val n = minOf(e.size.toLong(), fonte.quadros - inicio).toInt()
                            fonte.copiar(0, inicio, n, e)
                            fonte.copiar(1, inicio, n, d)
                            diretoE.asFloatBuffer().put(e, 0, n)
                            diretoD.asFloatBuffer().put(d, 0, n)
                            rest.processar(n, diretoE, diretoD)
                            diretoE.asFloatBuffer().get(e, 0, n)
                            diretoD.asFloatBuffer().get(d, 0, n)
                            escritor.escreverCanais(canais, n)
                            inicio += n
                        }
                    }
                }
                val ganho = rest.ganho()
                FonteWav(temp).use { fonte ->
                    EscritorDeWav(saida, 2, taxa).use { escritor ->
                        var inicio = 0L
                        while (inicio < fonte.quadros) {
                            coroutineContext.ensureActive()
                            val n = minOf(e.size.toLong(), fonte.quadros - inicio).toInt()
                            fonte.copiar(0, inicio, n, e)
                            fonte.copiar(1, inicio, n, d)
                            for (j in 0 until n) { e[j] *= ganho; d[j] *= ganho }
                            escritor.escreverCanais(canais, n)
                            inicio += n
                        }
                    }
                }
            }
        } catch (e: Exception) {
            saida.delete()
            throw e
        } finally {
            temp.delete()
        }
    }

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
            .mapNotNull { f -> runCatching { FonteWav(f).use { MidiaMusical(f.nameWithoutExtension, f.name, "wav", p.taxa, it.canais, it.quadros) } }.getOrNull() }
    }

    private suspend fun ffmpeg(argumentos: List<String>) = Ffmpeg.executar(contexto, listOf("-hide_banner", "-nostdin", "-y") + argumentos, null)

    private suspend fun transformarArquivo(origem: File, destino: File, taxa: Int, fonte: FonteDeAudio, velocidade: Double, semitons: Double, preservarTom: Boolean) = travaDaTransformacao.withLock {
        val quadros = Musica.arredondar(fonte.quadros / velocidade)
        require(quadros <= Int.MAX_VALUE.toLong() / (4L * fonte.canais)) { "A transformação gera um arquivo grande demais. Aumente a velocidade ou use uma mídia mais curta." }
        val filtro = TransformacaoDaMusica.filtroEmFluxo(taxa, fonte.quadros, velocidade, semitons, preservarTom)
        ffmpeg(listOf("-loglevel", "error", "-progress", "pipe:1", "-i", origem.absolutePath,
            "-map", "0:a:0", "-af", filtro, "-c:a", "pcm_f32le", destino.absolutePath))
    }

    /** A fonte transformada fica em disco, com a mesma duração em quadros usada no mixer. */
    internal suspend fun prepararClipe(p: ProjetoMusical, clipe: ClipeMusical): FonteWav {
        var fontePronta: FonteWav? = null
        try {
            return withContext(Dispatchers.IO) {
                val origem = midia(p.id, clipe.midia)
                val destino = File.createTempFile("tempo-tom-", ".wav", temporaria)
                try {
                    FonteWav(origem).use { fonte ->
                        transformarArquivo(origem, destino, p.taxa, fonte, clipe.velocidade ?: 1.0, clipe.semitons ?: 0.0, clipe.preservarTom ?: true)
                    }
                    coroutineContext.ensureActive()
                    FonteWav(destino, apagarAoFechar = true).also { fontePronta = it }
                } finally {
                    if (fontePronta == null) destino.delete()
                }
            }
        } catch (erro: Throwable) {
            // A troca de dispatcher pode descartar o resultado se o ajuste mudou durante a leitura.
            fontePronta?.close()
            throw erro
        }
    }

    /** Qualquer áudio vira WAV float32 na taxa do projeto (mono continua mono). */
    suspend fun importar(entrada: File, projeto: String, midia: String, taxa: Int): Pair<Long, Int> {
        val destino = midia(projeto, midia).apply { parentFile?.mkdirs() }
        ffmpeg(listOf("-i", entrada.absolutePath, "-vn", "-af", "aformat=channel_layouts=mono|stereo", "-ar", "$taxa", "-c:a", "pcm_f32le", destino.absolutePath))
        return FonteWav(destino).use { require(it.quadros <= taxa * 7200L) { "O áudio passa de 2 horas." }; it.quadros to it.canais }
    }

    /** A mistura (com master), cada faixa ou uma faixa, no formato pedido. */
    suspend fun exportar(p: ProjetoMusical, formatoId: String, master: String, soLoop: Boolean, cadaFaixa: Boolean, cauda: Boolean, faixa: String?, pasta: File, progresso: (String, Double) -> Unit): List<Exportado> = withContext(Dispatchers.Default) {
        val formato = MasterDaMusica.FORMATOS.first { it.id == formatoId }
        val usarLoop = soLoop && p.loop.fim > p.loop.inicio
        val inicio = if (usarLoop) p.loop.inicio else 0L
        val fim = if (usarLoop) p.loop.fim else Musica.duracaoEmQuadros(p)
        require(fim > inicio) { "Não há nada para exportar: grave ou importe uma faixa primeiro." }
        val quadros = fim - inicio + if (cauda) MisturadorDaMusica.caudaEmQuadros(p) else 0L
        require(quadros <= Int.MAX_VALUE / 8L) { "A mistura é grande demais para um único arquivo. Exporte um trecho pela região do loop." }
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
            val contextoDaExportacao = coroutineContext
            val verificar = { contextoDaExportacao.ensureActive() }
            val fontes = mutableMapOf<String, FonteWav>()
            val temporarios = mutableListOf<File>()
            fun temp() = File.createTempFile("exportar-", ".wav", temporaria).also { temporarios.add(it) }
            val arquivo = File(pasta, "$nomeDoArquivo.${formato.extensao}")
            try {
                val clipes = p.faixas.filter { id == null || it.id == id }.flatMap { it.clipes }.filterNot { it.mudo }
                for (midia in clipes.map { it.midia }.distinct()) {
                    verificar()
                    fontes[midia] = FonteWav(midia(p.id, midia))
                }
                val transformacoes = clipes.filter(TransformacaoDaMusica::temTransformacao).distinctBy(TransformacaoDaMusica::chaveDaFonte)
                for (clipe in transformacoes) {
                    verificar()
                    fontes[TransformacaoDaMusica.chaveDaFonte(clipe)] = prepararClipe(p, clipe)
                }
                var mistura = temp()
                EscritorDeWav(mistura, 2, p.taxa).use { MisturadorDaMusica.renderizarEmWav(p, fontes, inicio, quadros, id, it, verificar) }
                var medidas: Medidas? = null
                var ganho: Double? = null
                var corte = 1.0
                if (id == null) {
                    progresso("Masterizando", (i + 0.8) / alvos.size)
                    if (p.master.velocidade != 1.0 || p.master.semitons != 0.0) {
                        val ajustada = temp()
                        FonteWav(mistura).use {
                            transformarArquivo(mistura, ajustada, p.taxa, it, p.master.velocidade, p.master.semitons, p.master.preservarTom)
                        }
                        mistura.delete()
                        mistura = ajustada
                    }
                    val preset = MasterDaMusica.PRESETS.firstOrNull { it.id == master } ?: MasterDaMusica.PRESETS[0]
                    val limitada = temp()
                    ganho = FonteWav(mistura).use { fonte ->
                        EscritorDeWav(limitada, 2, p.taxa).use { MasterDaMusica.limitar(fonte, it, p.taxa, preset, verificar) }
                    }
                    mistura.delete()
                    mistura = limitada
                    FonteWav(mistura).use { fonte ->
                        val depois = MasterDaMusica.medir(fonte, p.taxa, verificar)
                        if (depois.realDb > preset.tetoDbtp) {
                            corte = 10.0.pow((preset.tetoDbtp - depois.realDb - 0.05) / 20)
                            medidas = MasterDaMusica.medir(fonteComGanho(fonte, corte), p.taxa, verificar)
                        } else medidas = depois
                    }
                }
                progresso("Codificando", (i + 0.9) / alvos.size)
                try {
                    if (formato.extensao == "wav") {
                        FonteWav(mistura).use { fonte ->
                            arquivo.outputStream().buffered().use {
                                MasterDaMusica.escreverWav(it, fonteComGanho(fonte, corte), p.taxa, formato.bits, verificar = verificar)
                            }
                        }
                    } else {
                        val codec = when (formato.id) {
                            "flac" -> listOf("-c:a", "flac", "-sample_fmt", "s32", "-bits_per_raw_sample", "24")
                            "mp3" -> listOf("-c:a", "libmp3lame", "-b:a", "320k")
                            "aac" -> listOf("-c:a", "aac", "-b:a", "256k")
                            else -> listOf("-c:a", "libopus", "-b:a", "192k")
                        }
                        ffmpeg(listOf("-i", mistura.absolutePath, "-af", "volume=$corte") + codec + arquivo.absolutePath)
                    }
                } catch (erro: Throwable) {
                    arquivo.delete()
                    throw erro
                }
                Exportado(arquivo, medidas, ganho)
            } finally {
                fontes.values.forEach { it.close() }
                temporarios.forEach { it.delete() }
            }
        }.also { progresso("Pronto", 1.0) }
    }

    private fun fonteComGanho(fonte: FonteDeAudio, ganho: Double): FonteDeAudio {
        if (ganho == 1.0) return fonte
        return object : FonteDeAudio by fonte {
            override fun copiar(canal: Int, inicio: Long, n: Int, destino: FloatArray) {
                fonte.copiar(canal, inicio, n, destino)
                for (i in 0 until n) destino[i] = (destino[i] * ganho).toFloat()
            }
        }
    }

    private fun nome(texto: String): String {
        val limpo = java.text.Normalizer.normalize(texto, java.text.Normalizer.Form.NFD).filter { it.isLetterOrDigit() && it.code < 128 || it == ' ' || it == '-' || it == '_' }.trim().replace(' ', '-')
        return limpo.ifEmpty { "musica" }.take(60)
    }

    val modeloPronto get() = modeloPronto(false)
    fun modeloPronto(roformer: Boolean) = arquivoDoModelo(roformer).length() == tamanhoDoModelo(roformer)

    private suspend fun conferirModelo(roformer: Boolean): Boolean {
        if (!modeloPronto(roformer)) return false
        val resumo = MessageDigest.getInstance("SHA-256")
        arquivoDoModelo(roformer).inputStream().use { entrada ->
            val b = ByteArray(1 shl 16)
            while (true) {
                coroutineContext.ensureActive()
                val n = entrada.read(b)
                if (n < 0) break
                resumo.update(b, 0, n)
            }
        }
        return resumo.digest().joinToString("") { "%02x".format(it) } == hashDoModelo(roformer)
    }

    suspend fun excluirModelo(roformer: Boolean) = withContext(Dispatchers.IO) {
        travaDoModelo.withLock {
            val arquivo = arquivoDoModelo(roformer)
            check(!arquivo.exists() || arquivo.delete()) { "Não foi possível excluir o modelo baixado." }
        }
    }

    /** Baixa o modelo (78 MB) na primeira vez e confere o SHA-256 antes de usar. */
    suspend fun garantirModelo(roformer: Boolean = false, progresso: (Double) -> Unit) = withContext(Dispatchers.IO) { travaDoModelo.withLock {
        if (conferirModelo(roformer)) return@withLock
        val modelo = arquivoDoModelo(roformer)
        val bytes = tamanhoDoModelo(roformer)
        modelo.parentFile?.mkdirs()
        val temp = File.createTempFile("modelo-", ".baixando", modelo.parentFile)
        val endereco = if (roformer) "https://converter.smellsliketech.com.br/modelos/${RoFormer.ARQUIVO}" else SeparacaoDaMusica.URL
        val conexao = URL(endereco).openConnection() as HttpURLConnection
        conexao.connectTimeout = 20_000
        conexao.readTimeout = 60_000
        try {
            check(conexao.responseCode == 200) { "Não foi possível baixar o modelo de separação (HTTP ${conexao.responseCode})." }
            val total = conexao.contentLengthLong.takeIf { it > 0 } ?: bytes
            val resumo = MessageDigest.getInstance("SHA-256")
            conexao.inputStream.use { entrada ->
                temp.outputStream().use { saida ->
                    val b = ByteArray(1 shl 16)
                    var recebido = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = entrada.read(b)
                        if (n < 0) break
                        recebido += n
                        check(recebido <= bytes) { "O modelo de separação é maior que o esperado." }
                        saida.write(b, 0, n)
                        resumo.update(b, 0, n)
                        progresso(recebido.toDouble() / total)
                    }
                }
            }
            val hex = resumo.digest().joinToString("") { "%02x".format(it) }
            check(hex == hashDoModelo(roformer) && temp.length() == bytes) { "O modelo baixado não confere e foi descartado." }
            check(temp.renameTo(modelo)) { "Não foi possível guardar o modelo." }
        } finally {
            conexao.disconnect()
            temp.delete()
        }
    } }

    /**
     * Separa uma mídia do projeto: converte para 44,1 kHz, roda o modelo em trechos (memória
     * limitada), escreve uma faixa por WAV e volta para a taxa do projeto. Devolve os arquivos
     * na ordem do modo.
     */
    suspend fun separar(entrada: File, saida: File, modo: String, taxa: Int, refinada: Boolean = false, progresso: (String, Double) -> Unit): List<Pair<FaixaDoModo, File>> = withContext(Dispatchers.Default) {
        val roformer = modo == RoFormer.modo.id
        check(!roformer || RoFormer.disponivel) { "O núcleo deste aparelho não oferece suporte ao RoFormer." }
        check(withContext(Dispatchers.IO) { conferirModelo(roformer) }) { "O modelo de separação está ausente ou não confere." }
        val trabalhoAtual = coroutineContext[kotlinx.coroutines.Job]
        val trabalho = File(temporaria, "separacao-${System.nanoTime()}").apply { mkdirs() }
        try {
            progresso("Preparando o áudio", 0.0)
            val a44 = File(trabalho, "entrada.wav")
            ffmpeg(listOf("-i", entrada.absolutePath, "-ac", "2", "-ar", "44100", "-c:a", "pcm_f32le", a44.absolutePath))
            val definicao = if (roformer) RoFormer.modo else SeparacaoDaMusica.modo(modo) ?: error("Modo de separação desconhecido.")
            val escritores = definicao.faixas.map { EscritorDeWav(File(trabalho, "${it.id}-44k.wav"), 2, SeparacaoDaMusica.TAXA) }
            val esquerdos = arrayOfNulls<FloatArray>(definicao.faixas.size)
            val forma = longArrayOf(1, 2, SeparacaoDaMusica.QUADROS_POR_TRECHO.toLong(), SeparacaoDaMusica.BANDAS.toLong())
            try {
                FonteWav(a44).use { fonte ->
                    val threads = maxOf(1, Runtime.getRuntime().availableProcessors() - 1)
                    InferenciaOnnx(arquivoDoModelo(roformer).absolutePath, if (roformer) minOf(2, threads) else threads, roformer).use { sessao ->
                        if (roformer) RoFormer.separar(fonte, sessao, escritores) { progresso("Separando com o RoFormer", it) }
                        else {
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
                            nativo = contexto.getSharedPreferences("criacao-musical", Context.MODE_PRIVATE).getBoolean("motor-nativo", false),
                            refinada = refinada,
                        )
                        }
                    }
                }
            } finally {
                escritores.forEach { it.close() }
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
        FonteWav(entrada).use { fonte ->
            EscritorDeWav(saida, 2, taxa).use { escritor ->
                var esquerdo: FloatArray? = null
                SeparacaoDaMusica.reduzirRuido(fonte.quadros, fonte.canais, { c, inicio, n, d -> fonte.copiar(c, inicio, n, d) }, EmissorDeAmostras { c, _, amostras, n ->
                    if (c == 0) esquerdo = amostras.copyOf(n) else escritor.escreverCanais(listOf(esquerdo!!, amostras.copyOf(n)), n)
                })
            }
        }
    }
}
