package br.com.smellsliketech.converter.musica

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import br.com.smellsliketech.converter.conversao.Saida
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue

/**
 * Estado da criação musical no Android. Toda edição passa por [mudar] (desfazer/refazer, toca
 * e salva); o motor de áudio fica aberto enquanto a tela está aberta.
 */
class MusicaViewModel(aplicacao: Application) : AndroidViewModel(aplicacao) {
    val biblioteca = BibliotecaDaMusica(aplicacao)
    private var motor: MotorDaMusica? = null
    private val antes = ArrayDeque<ProjetoMusical>()
    private val depois = ArrayDeque<ProjetoMusical>()
    private var chave: String? = null
    private var chaveEm = 0L
    private var salvarJob: Job? = null
    private var relogio: Job? = null
    private var trabalho: Job? = null

    var lista by mutableStateOf(emptyList<ResumoDoProjeto>()); private set
    var projeto by mutableStateOf<ProjetoMusical?>(null); private set
    val picos = mutableStateMapOf<String, FloatArray>()
    var posicao by mutableStateOf(0L); private set
    var tocando by mutableStateOf(false); private set
    var gravando by mutableStateOf<String?>(null); private set
    var segundosGravados by mutableStateOf(0.0); private set
    var medidores by mutableStateOf<Map<String, FloatArray>>(emptyMap()); private set
    var entrada by mutableStateOf<EntradaAberta?>(null); private set
    var mensagem by mutableStateOf<String?>(null)
    var andamento by mutableStateOf<Pair<String, Double>?>(null); private set
    var soltas by mutableStateOf(emptyList<MidiaMusical>()); private set
    var podeDesfazer by mutableStateOf(false); private set
    var podeRefazer by mutableStateOf(false); private set
    var exportados by mutableStateOf(emptyList<String>()); private set
    /** Saída por onde a pessoa ouve e o atraso calibrado dela (ms), guardado no aparelho por saída. */
    var rota by mutableStateOf<RotaDeAudio?>(null); private set
    var atrasoDaRotaMs by mutableStateOf<Double?>(null); private set
    var calibrando by mutableStateOf(false); private set
    private val alinhamentos = aplicacao.getSharedPreferences("criacao-musical-alinhamento", Context.MODE_PRIVATE)

    init { recarregar() }

    fun recarregar() { viewModelScope.launch(Dispatchers.IO) { lista = biblioteca.listar() } }

    private fun novoId(prefixo: String) = "$prefixo-${UUID.randomUUID().toString().replace("-", "").take(16)}"

    private fun garantirMotor(): MotorDaMusica = motor ?: MotorDaMusica(getApplication()).also { it.iniciar(); motor = it }

    /** Lê por onde o som sai agora e a calibração guardada para essa saída. */
    fun atualizarRota(): RotaDeAudio {
        val r = garantirMotor().rotaDaSaida()
        rota = r
        val ms = alinhamentos.getFloat(r.chave, Float.NaN)
        atrasoDaRotaMs = if (ms.isNaN()) null else ms.toDouble()
        return r
    }

    /** A latência que vale para uma tomada gravada pela saída [r]: a sobra calibrada dela (os
     *  relógios do Android já descontam o resto) mais a correção manual do projeto. A latência
     *  guardada no projeto por outro aparelho (site, Windows) não vale aqui. */
    private fun latenciaDaTomada(p: ProjetoMusical, r: RotaDeAudio): Latencia {
        val ms = alinhamentos.getFloat(r.chave, Float.NaN)
        val quadros = if (ms.isNaN()) 0.0 else ms * p.taxa / 1000.0
        return Latencia(quadros, 0.0, !ms.isNaN(), p.latencia.ajuste)
    }

    fun criar(modeloId: String) {
        val modelo = Musica.MODELOS.first { it.id == modeloId }
        val p = Musica.projetoDoModelo(modeloId, novoId("musica"), if (modeloId == "vazio") "Nova música" else modelo.rotulo)
        viewModelScope.launch(Dispatchers.IO) { biblioteca.salvar(p); withContext(Dispatchers.Main) { abrir(p) } }
    }

    fun abrirSalvo(id: String) { viewModelScope.launch(Dispatchers.IO) { val p = biblioteca.ler(id); withContext(Dispatchers.Main) { abrir(p) } } }

    fun abrir(p: ProjetoMusical) {
        val m = garantirMotor()
        antes.clear(); depois.clear(); atualizarPassos()
        projeto = p
        m.definirProjeto(p)
        m.posicionar(0)
        picos.clear()
        p.midias.forEach { carregarMidia(p, it) }
        viewModelScope.launch(Dispatchers.IO) { soltas = biblioteca.soltas(p) }
        relogio?.cancel()
        relogio = viewModelScope.launch {
            while (isActive) {
                posicao = m.posicao
                tocando = m.tocando
                medidores = m.medidores
                delay(33)
            }
        }
    }

    fun fechar() {
        motor?.parar()
        projeto?.let { p -> viewModelScope.launch(Dispatchers.IO) { biblioteca.salvar(p); lista = biblioteca.listar() } }
        relogio?.cancel()
        projeto = null
    }

    private fun carregarMidia(p: ProjetoMusical, midia: MidiaMusical) {
        val arquivo = biblioteca.midia(p.id, midia.id)
        if (!arquivo.exists()) return
        garantirMotor().definirFonte(midia.id, FonteMapeada(arquivo))
        viewModelScope.launch(Dispatchers.Default) {
            val pico = runCatching {
                FonteMapeada(arquivo).use { fonte ->
                    val colunas = ((fonte.quadros + 1023) / 1024).toInt()
                    val resultado = FloatArray(colunas * 2)
                    val b = FloatArray(1024)
                    for (k in 0 until colunas) {
                        var min = 0f; var max = 0f
                        for (c in 0 until fonte.canais) {
                            fonte.copiar(c, k * 1024L, 1024, b)
                            for (v in b) { if (v < min) min = v; if (v > max) max = v }
                        }
                        resultado[k * 2] = min; resultado[k * 2 + 1] = max
                    }
                    resultado
                }
            }.getOrNull()
            if (pico != null) withContext(Dispatchers.Main) { picos[midia.id] = pico }
        }
    }

    /** Toda edição: guarda o passo, toca o projeto novo e salva logo depois. */
    fun mudar(juntar: String? = null, mudanca: (ProjetoMusical) -> ProjetoMusical) {
        val p = projeto ?: return
        val novo = mudanca(p)
        if (novo == p) return
        val agora = System.currentTimeMillis()
        if (!(juntar != null && juntar == chave && agora - chaveEm < 800)) {
            antes.addLast(p)
            if (antes.size > 200) antes.removeFirst()
        }
        chave = juntar; chaveEm = agora
        depois.clear()
        aplicar(novo)
    }

    private fun aplicar(p: ProjetoMusical) {
        projeto = p
        motor?.definirProjeto(p)
        atualizarPassos()
        salvarJob?.cancel()
        salvarJob = viewModelScope.launch(Dispatchers.IO) { delay(700); biblioteca.salvar(p) }
    }

    private fun atualizarPassos() { podeDesfazer = antes.isNotEmpty(); podeRefazer = depois.isNotEmpty() }

    fun desfazer() { val p = projeto ?: return; val a = antes.removeLastOrNull() ?: return; depois.addLast(p); chave = null; aplicar(a) }
    fun refazer() { val p = projeto ?: return; val d = depois.removeLastOrNull() ?: return; antes.addLast(p); chave = null; aplicar(d) }

    fun mudarFaixa(id: String, juntar: String? = null, f: (FaixaMusical) -> FaixaMusical) = mudar(juntar) { p -> p.copy(faixas = p.faixas.map { if (it.id == id) f(it) else it }) }

    // ==================== transporte ====================

    fun tocarOuParar() {
        val m = garantirMotor(); val p = projeto ?: return
        if (m.tocando) m.parar() else m.tocar(m.posicao, p.metronomo.ligado)
    }

    fun irPara(quadro: Long) = motor?.posicionar(quadro)

    fun importar(uri: Uri) {
        val p = projeto ?: return
        val app = getApplication<Application>()
        trabalho = viewModelScope.launch {
            val nome = app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "audio"
            andamento = "Importando $nome" to 0.0
            try {
                val temp = File(app.cacheDir, "importar-${System.nanoTime()}")
                withContext(Dispatchers.IO) { app.contentResolver.openInputStream(uri)!!.use { entrada -> temp.outputStream().use { entrada.copyTo(it) } } }
                val midiaId = novoId("midia")
                val (quadros, canais) = try { biblioteca.importar(temp, p.id, midiaId, p.taxa) } finally { temp.delete() }
                val midia = MidiaMusical(midiaId, nome, "wav", p.taxa, canais, quadros, origem = nome)
                val inicio = posicao
                mudar { q ->
                    val base = nome.substringBeforeLast('.').take(60)
                    val faixa = Musica.novaFaixa(novoId("faixa"), base, q.faixas.size).copy(clipes = listOf(ClipeMusical(novoId("clipe"), midiaId, inicio, 0, quadros, nome = base)))
                    q.copy(midias = q.midias + midia, faixas = q.faixas + faixa)
                }
                carregarMidia(projeto!!, midia)
            } catch (e: Exception) {
                mensagem = "Não deu para importar $nome: ${e.message}. Formatos aceitos: WAV, MP3, FLAC, M4A, AAC, OGG e Opus."
            } finally {
                andamento = null
            }
        }
    }

    fun recuperarSoltas() {
        val achadas = soltas
        mudar { p ->
            val faixa = Musica.novaFaixa(novoId("faixa"), "Tomadas recuperadas", p.faixas.size).copy(clipes = achadas.map { ClipeMusical(novoId("clipe"), it.id, 0, 0, maxOf(1, it.quadros), nome = "Recuperada") })
            p.copy(midias = p.midias + achadas, faixas = p.faixas + faixa)
        }
        achadas.forEach { carregarMidia(projeto!!, it) }
        soltas = emptyList()
        mensagem = "As tomadas recuperadas estão numa faixa nova, no começo do projeto."
    }

    // ==================== gravação ====================

    fun abrirEntrada(canais: Int) {
        try { entrada = garantirMotor().abrirEntrada(canais); atualizarRota() } catch (e: Exception) { mensagem = e.message }
    }

    fun fecharEntrada() { motor?.fecharEntrada(); entrada = null }

    fun monitorarParado(ligar: Boolean) = motor?.monitorarParado(ligar)

    /** Grava na faixa armada (uma por vez), direto para o arquivo, com o serviço de microfone. */
    fun gravar() {
        val p = projeto ?: return
        val m = garantirMotor()
        if (m.tocando) return
        val armadas = p.faixas.filter { it.gravar }
        if (armadas.size != 1) { mensagem = if (armadas.isEmpty()) "Arme uma faixa (REC) antes de gravar." else "Grava uma faixa por vez: deixe só uma armada."; return }
        val e = entrada ?: run { mensagem = "Abra a entrada de áudio na aba Gravar antes de gravar."; return }
        val faixa = armadas[0]
        val rotaDaTomada = atualizarRota()
        val plano = Musica.planoDaGravacao(p, m.posicao)
        val midiaId = novoId("tomada")
        val canais = if (faixa.canaisDeEntrada == 2 && e.canais >= 2) 2 else 1
        val app = getApplication<Application>()
        val escritor = EscritorDeWav(biblioteca.midia(p.id, midiaId), canais, p.taxa)
        val fila = LinkedBlockingQueue<FloatArray>()
        val fim = FloatArray(0)
        val escrita = Thread {
            while (true) { val parte = fila.take(); if (parte === fim) break; escritor.escrever(parte) }
            escritor.close()
        }.apply { start() }
        ServicoDeGravacao.parar = { motor?.parar() }
        ServicoDeGravacao.iniciar(app, faixa.nome)
        gravando = faixa.id
        segundosGravados = 0.0
        var capturados = 0L
        m.gravar(plano, canais, p.metronomo.ligado, { parte ->
            fila.put(parte)
            capturados += parte.size / canais
            if (capturados % 4800 < parte.size / canais) viewModelScope.launch { segundosGravados = capturados.toDouble() / p.taxa }
        }) { tomada ->
            fila.put(fim)
            viewModelScope.launch {
                withContext(Dispatchers.IO) { escrita.join() }
                ServicoDeGravacao.encerrar(app)
                gravando = null
                val atual = projeto ?: return@launch
                val clipe = Musica.clipeDaTomada(atual.copy(latencia = latenciaDaTomada(atual, rotaDaTomada)), novoId("clipe"), midiaId, tomada.quadroAlinhado, escritor.quadros, plano)
                if (clipe == null) { mensagem = "A gravação parou antes do ponto de entrada: nada foi acrescentado (o arquivo ficou guardado)."; return@launch }
                val midia = MidiaMusical(midiaId, "${faixa.nome} — tomada.wav", "wav", atual.taxa, canais, escritor.quadros)
                mudar { q -> q.copy(midias = q.midias + midia, faixas = q.faixas.map { f -> if (f.id == faixa.id) Musica.adicionarTomada(f, clipe.copy(nome = "Tomada ${f.clipes.size + 1}")) else f }) }
                carregarMidia(projeto!!, midia)
                if (alinhamentos.getFloat(rotaDaTomada.chave, Float.NaN).isNaN())
                    mensagem = "Tomada gravada. Para ela ficar exatamente no tempo do que você ouve, calibre esta saída (${rotaDaTomada.nome}) na aba Gravar: é uma vez só por fone."
            }
        }
    }

    fun pararGravacao() { motor?.parar() }

    /** Calibra a saída atual: toca 12 cliques e grava. Com fone, a pessoa bate palmas junto com
     *  cada clique; com o alto-falante, o clique volta sozinho pelo microfone. O atraso que sobra
     *  depois dos relógios fica guardado no aparelho para esta saída ([CalibracaoDaRota]). */
    fun calibrar() {
        val p = projeto ?: return
        val m = motor ?: return
        if (entrada == null) { mensagem = "Abra a entrada antes de calibrar."; return }
        if (m.tocando || calibrando) return
        val r = atualizarRota()
        calibrando = true
        mensagem = if (r.nome == "Alto-falante") "Calibrando o alto-falante: deixe o celular tocar os 12 cliques perto do microfone, em silêncio."
            else "Calibrando ${r.nome}: bata palmas forte, exatamente junto com cada um dos 12 cliques que você ouvir."
        val cliques = CalibracaoDaRota.cliques(p.taxa)
        val partes = mutableListOf<FloatArray>()
        m.definirProjeto(Musica.novoProjeto("calibracao", "Calibração", p.taxa).copy(metronomo = Metronomo(volumeDb = 0.0)))
        m.gravar(PlanoDaGravacao(0, Long.MAX_VALUE / 4, null, cliques.map { Clique(it, true) }, 0), 1, false, { synchronized(partes) { partes += it } }) { tomada ->
            viewModelScope.launch {
                m.definirProjeto(projeto ?: p)
                calibrando = false
                val gravado = withContext(Dispatchers.Default) { synchronized(partes) { partes.flatMap { it.asList() }.toFloatArray() } }
                val resultado = withContext(Dispatchers.Default) { CalibracaoDaRota.medir(gravado, cliques.map { it - tomada.quadroAlinhado }, p.taxa) }
                if (resultado == null) {
                    mensagem = "Não deu para medir com segurança. Tente de novo: palmas fortes e no tempo de cada clique (com fone), ou o celular perto e em silêncio (alto-falante)."
                } else {
                    val ms = resultado.atraso * 1000.0 / p.taxa
                    alinhamentos.edit().putFloat(r.chave, ms.toFloat()).apply()
                    atrasoDaRotaMs = ms
                    mensagem = "Alinhamento de ${r.nome}: ${"%.1f".format(ms)} ms (variação ±${"%.1f".format(resultado.espalhamento * 1000.0 / p.taxa)} ms, ${resultado.achados} de ${cliques.size} cliques). Vale para todas as gravações por esta saída."
                }
            }
        }
        viewModelScope.launch { delay((cliques.last() + p.taxa) * 1000 / p.taxa); m.parar() }
    }

    /** Esquece a calibração da saída atual. */
    fun esquecerCalibracao() {
        val r = rota ?: atualizarRota()
        alinhamentos.edit().remove(r.chave).apply()
        atrasoDaRotaMs = null
    }

    // ==================== separação, limpeza e exportação ====================

    fun separar(faixaId: String, clipeId: String, modo: String) {
        val p = projeto ?: return
        val faixa = p.faixas.first { it.id == faixaId }
        val clipe = faixa.clipes.first { it.id == clipeId }
        trabalho = viewModelScope.launch {
            try {
                if (!biblioteca.modeloPronto) {
                    andamento = "Baixando o modelo (78 MB, só na primeira vez)" to 0.0
                    biblioteca.garantirModelo { f -> viewModelScope.launch { andamento = "Baixando o modelo (78 MB, só na primeira vez)" to f } }
                }
                val faixas = biblioteca.separar(biblioteca.midia(p.id, clipe.midia), File(biblioteca.pasta(p.id), "midias"), modo, p.taxa) { etapa, f -> viewModelScope.launch { andamento = etapa to f } }
                val novas = faixas.map { (definicao, arquivo) ->
                    val midiaId = novoId("stem")
                    val destino = biblioteca.midia(p.id, midiaId)
                    check(arquivo.renameTo(destino)) { "Não foi possível guardar a faixa ${definicao.rotulo}." }
                    val quadros = FonteMapeada(destino).use { it.quadros }
                    definicao to MidiaMusical(midiaId, "${definicao.rotulo}.wav", "wav", p.taxa, 2, quadros, origem = clipe.midia, instrumento = definicao.id, modelo = SeparacaoDaMusica.MODELO_ID, versaoDoModelo = "1")
                }
                mudar { q ->
                    val i = q.faixas.indexOfFirst { it.id == faixaId }
                    val criadas = novas.mapIndexed { k, (d, m) -> Musica.novaFaixa(novoId("faixa"), "${faixa.nome} — ${d.rotulo}", i + k + 1).copy(clipes = listOf(clipe.copy(id = novoId("clipe"), midia = m.id, nome = d.rotulo))) }
                    val faixas = q.faixas.toMutableList().apply { this[i] = this[i].copy(mudo = true); addAll(i + 1, criadas) }
                    q.copy(midias = q.midias + novas.map { it.second }, faixas = faixas, separacoes = q.separacoes + RegistroDeSeparacao(novoId("sep"), clipe.midia, modo, SeparacaoDaMusica.MODELO_ID, novas.map { it.second.id }))
                }
                novas.forEach { carregarMidia(projeto!!, it.second) }
                mensagem = "Pronto: ${novas.size} faixas novas abaixo de “${faixa.nome}”, que ficou muda como referência."
            } catch (e: kotlinx.coroutines.CancellationException) {
                mensagem = "Separação cancelada."
            } catch (e: Exception) {
                mensagem = e.message
            } finally {
                andamento = null
            }
        }
    }

    fun cancelar() { trabalho?.cancel() }

    fun reduzirRuido(faixaId: String, clipeId: String) {
        val p = projeto ?: return
        val clipe = p.faixas.first { it.id == faixaId }.clipes.first { it.id == clipeId }
        trabalho = viewModelScope.launch {
            andamento = "Reduzindo o ruído" to 0.5
            try {
                val midiaId = novoId("limpa")
                biblioteca.reduzirRuido(biblioteca.midia(p.id, clipe.midia), biblioteca.midia(p.id, midiaId), p.taxa)
                val quadros = FonteMapeada(biblioteca.midia(p.id, midiaId)).use { it.quadros }
                val midia = MidiaMusical(midiaId, "Sem ruído.wav", "wav", p.taxa, 2, quadros, origem = clipe.midia)
                mudarFaixa(faixaId) { f -> f.copy(clipes = f.clipes.map { if (it.id == clipeId) it.copy(midia = midiaId, nome = "${it.nome ?: "Clipe"} (sem ruído)") else it }) }
                mudar { q -> q.copy(midias = q.midias + midia) }
                carregarMidia(projeto!!, midia)
                mensagem = "Ruído reduzido. A gravação original continua guardada: Desfazer volta para ela."
            } catch (e: Exception) { mensagem = e.message } finally { andamento = null }
        }
    }

    fun exportar(formato: String, master: String, soLoop: Boolean, cadaFaixa: Boolean, cauda: Boolean, faixa: String?) {
        val p = projeto ?: return
        val app = getApplication<Application>()
        trabalho = viewModelScope.launch {
            try {
                val pasta = File(app.cacheDir, "musica-exportada-${System.nanoTime()}")
                val arquivos = biblioteca.exportar(p, formato, master, soLoop, cadaFaixa, cauda, faixa, pasta) { etapa, f -> viewModelScope.launch { andamento = etapa to f } }
                val mime = when (formato) { "flac" -> "audio/flac"; "mp3" -> "audio/mpeg"; "aac" -> "audio/mp4"; "opus" -> "audio/ogg"; else -> "audio/wav" }
                exportados = withContext(Dispatchers.IO) {
                    arquivos.map { e ->
                        val r = Saida.gravarArquivo(app, e.arquivo.name, mime, e.arquivo)
                        "${r.nome}${e.medidas?.let { m -> " · ${if (m.lufs.isFinite()) "%.1f LUFS".format(m.lufs) else "silêncio"} · pico real ${"%.1f".format(m.realDb)} dBTP" } ?: ""}"
                    }.also { pasta.deleteRecursively() }
                }
                mensagem = "Exportado em Downloads/SmellsLikeTech."
            } catch (e: Exception) { mensagem = e.message } finally { andamento = null }
        }
    }

    fun salvarArquivo() {
        val p = projeto ?: return
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                biblioteca.salvar(p)
                val r = Saida.gravar(app, "${p.titulo.ifBlank { "musica" }.replace(Regex("[^\\p{L}\\p{N} _-]"), "")}.sltmusic", "application/zip") { saida ->
                    ArquivoDaMusica.salvar(p, { biblioteca.midia(p.id, it.id) }, saida)
                }
                mensagem = "Projeto salvo em Downloads/SmellsLikeTech/${r.nome}."
            } catch (e: Exception) { mensagem = e.message }
        }
    }

    fun abrirArquivo(uri: Uri) {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val temp = File(app.cacheDir, "sltmusic-${System.nanoTime()}")
                val p = app.contentResolver.openInputStream(uri)!!.use { ArquivoDaMusica.abrir(it, temp) }
                val pasta = biblioteca.pasta(p.id)
                File(pasta, "midias").mkdirs()
                File(temp, "midias").listFiles().orEmpty().forEach { it.copyTo(File(pasta, "midias/${it.name}"), overwrite = true) }
                temp.deleteRecursively()
                biblioteca.salvar(p)
                withContext(Dispatchers.Main) { abrir(p) }
            } catch (e: Exception) { mensagem = e.message }
        }
    }

    override fun onCleared() {
        motor?.desligar()
        motor = null
        super.onCleared()
    }
}
