package br.com.smellsliketech.converter.musica

import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

data class EntradaAberta(val nome: String, val canais: Int, val semProcessamento: Boolean, val avisos: List<String>)

data class Tomada(val quadroAlinhado: Long, val quadros: Long, val canais: Int, val alinhadaPeloRelogio: Boolean, val medidas: Int = 0)

/** A saída por onde a pessoa está ouvindo; a calibração do alinhamento é guardada por [chave]. */
data class RotaDeAudio(val chave: String, val nome: String)

/**
 * O motor de áudio da criação musical no Android: AudioTrack em float, modo de baixa latência
 * (o caminho rápido do AAudio quando o aparelho tem), e AudioRecord sem processamento de voz
 * (UNPROCESSED quando o aparelho declara, senão VOICE_RECOGNITION; AGC, supressão de ruído e
 * cancelamento de eco desligados quando existem). O transporte anda em quadros entregues à
 * saída. O mixer é o mesmo do site e do Windows (MisturadorDaMusica).
 *
 * Alinhamento: AudioTrack.getTimestamp diz que quadro está soando em cada instante, e
 * AudioRecord.getTimestamp diz quando cada quadro foi capturado. O primeiro quadro gravado
 * ganha o quadro do transporte que a pessoa ouvia; os buffers do Android ficam descontados.
 * A conta é refeita a cada bloco nos primeiros segundos e vale a mediana, para um relógio
 * desencontrado num instante não deslocar a tomada inteira. O que os relógios não contam
 * (conversores, Bluetooth) sai pela calibração da saída ([CalibracaoDaRota]).
 * Na thread de áudio nada de disco: a tomada vai por fila para quem escreve o WAV.
 */
class MotorDaMusica(private val contexto: Context, private val taxa: Int = Musica.TAXA_PADRAO) {
    private val misturador = MisturadorDaMusica(taxa)
    private val comandos = ConcurrentLinkedQueue<() -> Unit>()
    @Volatile private var projetoPendente: ProjetoMusical? = null
    private var projeto: ProjetoMusical? = null
    private val cursor = AtomicLong(0)
    @Volatile var tocando = false
        private set
    private var metronomo = false
    @Volatile private var monitorarParado = false
    @Volatile private var gravacao: Gravacao? = null
    @Volatile var medidores: Map<String, FloatArray> = emptyMap()
        private set
    @Volatile private var ligado = false
    @Volatile private var capturando = false
    private var saida: Thread? = null
    private var entrada: Thread? = null
    private val anel = Anel(taxa)
    /** Última leitura do relógio da saída: quadro que soava e o instante (ns). O par vai numa
     *  referência só: com dois campos, a captura podia ler o quadro novo com o instante velho e
     *  errar um período inteiro na tomada. */
    private class PontoDoRelogio(val quadro: Long, val em: Long)
    @Volatile private var relogioDaSaida: PontoDoRelogio? = null
    var entradaAberta: EntradaAberta? = null
        private set
    var burst = 256
        private set
    @Volatile private var trilhaAtual: AudioTrack? = null

    private class Gravacao(val canais: Int, val escrever: (FloatArray) -> Unit) {
        var primeiroEscrito = -1L
        var primeiroTransporte = 0L
        @Volatile var alinhado = Long.MIN_VALUE
        @Volatile var peloRelogio = false
        /** Estimativas do alinhamento, uma por bloco, nos primeiros [MEDIDAS] blocos. */
        val estimativas = LongArray(MEDIDAS)
        @Volatile var medidas = 0
        var quadros = 0L
        var aoTerminar: ((Tomada) -> Unit)? = null
    }

    private val erroDeLeitura = AtomicReference<String?>()
    fun consumirErroDeLeitura(): String? = erroDeLeitura.getAndSet(null)

    val posicao get() = cursor.get()
    val gravando get() = gravacao != null

    fun iniciar() {
        if (ligado) return
        val gerenciador = contexto.getSystemService(AudioManager::class.java)
        burst = gerenciador.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull()?.takeIf { it in 64..2048 } ?: 256
        val minimo = AudioTrack.getMinBufferSize(taxa, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        val trilha = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(taxa).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(max(minimo, burst * 8 * 4))
            .build()
        ligado = true
        trilhaAtual = trilha
        saida = Thread({ lacoDaSaida(trilha) }, "criacao-musical-saida").apply { start() }
    }

    private fun lacoDaSaida(trilha: AudioTrack) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val n = burst
        val intercalado = FloatArray(n * 2)
        val esq = FloatArray(n); val dir = FloatArray(n)
        val entE = FloatArray(n); val entD = FloatArray(n)
        val comClique = OpcoesDoMisturador(entE, entD, metronomo = true)
        val semClique = OpcoesDoMisturador(entE, entD, metronomo = false)
        val ts = AudioTimestamp()
        var escritos = 0L
        var blocos = 0
        try {
            trilha.play()
            while (ligado) {
                while (true) (comandos.poll() ?: break).invoke()
                projetoPendente?.let { projetoPendente = null; projeto = it; misturador.definirProjeto(it) }
                anel.ler(entE, entD, n, monitorarParado || tocando)
                val p = projeto
                val inicio = if (tocando && p != null) cursor.get() else -1L
                if (p == null || !tocando) {
                    for (i in 0 until n) { intercalado[i * 2] = if (monitorarParado) entE[i] else 0f; intercalado[i * 2 + 1] = if (monitorarParado) entD[i] else 0f }
                } else {
                    try {
                        misturador.processar(inicio, n, esq, dir, if (metronomo) comClique else semClique)
                    } catch (e: IOException) {
                        tocando = false
                        erroDeLeitura.set(e.message ?: "Não foi possível ler o áudio do projeto.")
                        esq.fill(0f)
                        dir.fill(0f)
                    }
                    for (i in 0 until n) { intercalado[i * 2] = esq[i]; intercalado[i * 2 + 1] = dir[i] }
                    var depois = inicio + n
                    if (gravacao == null && p.loop.ligado && p.loop.fim > p.loop.inicio && depois >= p.loop.fim) depois = p.loop.inicio
                    cursor.set(depois)
                    if (++blocos % 8 == 0) medidores = misturador.medidores.mapValues { it.value.copyOf() }
                }
                val g = gravacao
                if (g != null && g.primeiroEscrito < 0 && inicio >= 0) { g.primeiroTransporte = inicio; g.primeiroEscrito = escritos }
                trilha.write(intercalado, 0, n * 2, AudioTrack.WRITE_BLOCKING)
                escritos += n
                if (trilha.getTimestamp(ts)) relogioDaSaida = PontoDoRelogio(ts.framePosition, ts.nanoTime)
            }
        } finally {
            trilhaAtual = null
            runCatching { trilha.stop() }
            trilha.release()
        }
    }

    /** Por onde o som está saindo agora (fone Bluetooth, USB, com fio ou alto-falante). */
    fun rotaDaSaida(): RotaDeAudio {
        val d = trilhaAtual?.routedDevice ?: return RotaDeAudio("desconhecida", "Saída padrão")
        val tipo = when (d.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth"
            TIPO_BLE_HEADSET, TIPO_BLE_SPEAKER -> "Bluetooth LE"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Fone com fio"
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Alto-falante"
            else -> "Saída ${d.type}"
        }
        val produto = d.productName?.toString()?.trim().orEmpty()
        val nome = if (produto.isEmpty() || tipo == "Alto-falante") tipo else "$tipo · $produto"
        return RotaDeAudio("${d.type}:$produto", nome)
    }

    private fun tocadoEm(nano: Long): Long? {
        val ponto = relogioDaSaida ?: return null
        return ponto.quadro + Musica.arredondar((nano - ponto.em) / 1e9 * taxa)
    }

    fun definirProjeto(p: ProjetoMusical) { projetoPendente = p }

    fun definirFonte(midia: String, fonte: FonteDeAudio?) { comandos.add { misturador.definirFonte(midia, fonte) } }

    fun tocar(de: Long, comMetronomo: Boolean) = comandos.add {
        cursor.set(max(0L, de)); metronomo = comMetronomo; misturador.definirContagem(emptyList(), -1); tocando = true
    }

    fun parar() = comandos.add {
        tocando = false
        misturador.definirContagem(emptyList(), -1)
        val g = gravacao
        gravacao = null
        if (g != null) g.aoTerminar?.invoke(Tomada(if (g.alinhado == Long.MIN_VALUE) cursor.get() else g.alinhado, g.quadros, g.canais, g.peloRelogio, g.medidas))
    }

    fun posicionar(quadro: Long) = comandos.add { cursor.set(max(0L, quadro)) }

    fun monitorarParado(ligar: Boolean) { monitorarParado = ligar }

    /** Abre o microfone (precisa da permissão RECORD_AUDIO já concedida). */
    @SuppressLint("MissingPermission")
    fun abrirEntrada(canais: Int): EntradaAberta {
        fecharEntrada()
        val gerenciador = contexto.getSystemService(AudioManager::class.java)
        val semProcesso = gerenciador.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val fonte = if (semProcesso) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
        val mascara = if (canais == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val minimo = AudioRecord.getMinBufferSize(taxa, mascara, AudioFormat.ENCODING_PCM_FLOAT)
        require(minimo > 0) { "Este aparelho não grava áudio em 48 kHz neste formato." }
        val gravador = AudioRecord.Builder()
            .setAudioSource(fonte)
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(taxa).setChannelMask(mascara).build())
            .setBufferSizeInBytes(max(minimo, burst * canais * 4 * 8))
            .build()
        require(gravador.state == AudioRecord.STATE_INITIALIZED) { "Não foi possível abrir o microfone. Outro app pode estar usando." }
        val avisos = mutableListOf<String>()
        val sessao = gravador.audioSessionId
        if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(sessao)?.apply { enabled = false }
        if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(sessao)?.apply { enabled = false }
        if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(sessao)?.apply { enabled = false }
        if (!semProcesso) avisos += "Este aparelho não oferece a entrada sem processamento; o Android pode aplicar algum ajuste de voz."
        if (canais == 2 && gravador.channelCount < 2) avisos += "Esta entrada só entregou um canal (mono)."
        capturando = true
        entrada = Thread({ lacoDaEntrada(gravador) }, "criacao-musical-entrada").apply { start() }
        val nome = gravador.routedDevice?.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Microfone"
        return EntradaAberta(nome, gravador.channelCount, semProcesso, avisos).also { entradaAberta = it }
    }

    fun fecharEntrada() {
        capturando = false
        entrada?.join(1000)
        entrada = null
        entradaAberta = null
    }

    private fun lacoDaEntrada(gravador: AudioRecord) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val canais = gravador.channelCount
        val n = burst
        val bloco = FloatArray(n * canais)
        val estereo = FloatArray(n * 2)
        val ts = AudioTimestamp()
        var lidos = 0L
        try {
            gravador.startRecording()
            while (capturando) {
                val lido = gravador.read(bloco, 0, bloco.size, AudioRecord.READ_BLOCKING)
                if (lido <= 0) continue
                val quadros = lido / canais
                for (i in 0 until quadros) {
                    estereo[i * 2] = bloco[i * canais]
                    estereo[i * 2 + 1] = if (canais > 1) bloco[i * canais + 1] else bloco[i * canais]
                }
                anel.escrever(estereo, quadros)
                val g = gravacao
                if (g != null && tocando && g.primeiroEscrito >= 0) {
                    if (g.medidas < MEDIDAS) {
                        // Instante em que o primeiro quadro deste bloco foi capturado, e o quadro do
                        // transporte que soava nele; menos o que a tomada já tem, é onde ela começa.
                        val antes = g.quadros
                        val capturadoEm = if (gravador.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS)
                            ts.nanoTime + Musica.arredondar((lidos - ts.framePosition) * 1e9 / taxa) else null
                        val tocado = capturadoEm?.let { tocadoEm(it) }
                        if (tocado != null) {
                            g.estimativas[g.medidas] = g.primeiroTransporte + (tocado - g.primeiroEscrito) - antes
                            g.medidas += 1
                            g.alinhado = CalibracaoDaRota.mediana(g.estimativas.copyOf(g.medidas).asList())
                            g.peloRelogio = true
                        } else if (g.alinhado == Long.MIN_VALUE) {
                            // Sem o relógio da entrada: o bloco acabou de chegar, então começou a ser
                            // capturado pelo menos a sua duração atrás; o relógio da saída diz o que
                            // soava. Sem nenhum relógio, só o que foi entregue à saída (pior caso).
                            val estimado = tocadoEm(System.nanoTime() - Musica.arredondar(quadros * 1e9 / taxa))
                            g.alinhado = if (estimado != null) g.primeiroTransporte + (estimado - g.primeiroEscrito) - antes else cursor.get() - quadros - antes
                        }
                    }
                    val tomada = FloatArray(quadros * g.canais)
                    for (i in 0 until quadros) {
                        if (g.canais == 1) tomada[i] = estereo[i * 2]
                        else { tomada[i * 2] = estereo[i * 2]; tomada[i * 2 + 1] = estereo[i * 2 + 1] }
                    }
                    g.quadros += quadros
                    g.escrever(tomada)
                }
                lidos += quadros
            }
        } finally {
            runCatching { gravador.stop() }
            gravador.release()
        }
    }

    /** Grava pelo plano; `aoTerminar` vem depois de [parar]. */
    fun gravar(plano: PlanoDaGravacao, canais: Int, comMetronomo: Boolean, escrever: (FloatArray) -> Unit, aoTerminar: (Tomada) -> Unit) {
        check(capturando) { "Escolha a entrada de áudio antes de gravar." }
        val g = Gravacao(canais, escrever).apply { this.aoTerminar = aoTerminar }
        comandos.add {
            cursor.set(plano.inicioDoTransporte)
            metronomo = comMetronomo
            misturador.definirContagem(plano.cliquesDaContagem, plano.inicioDaTomada)
            anel.limpar()
            gravacao = g
            tocando = true
        }
    }

    fun desligar() {
        parar()
        fecharEntrada()
        ligado = false
        saida?.join(1000)
        saida = null
    }

    companion object {
        /** Blocos medidos para a mediana do alinhamento (os primeiros 128 blocos da tomada). */
        const val MEDIDAS = 128
        // AudioDeviceInfo.TYPE_BLE_HEADSET e TYPE_BLE_SPEAKER (API 31); o número vale em qualquer versão.
        private const val TIPO_BLE_HEADSET = 26
        private const val TIPO_BLE_SPEAKER = 27
    }

    /** Anel de um produtor (captura) e um consumidor (saída) para o monitoramento. */
    private class Anel(taxa: Int) {
        private val dados = FloatArray(taxa * 2)
        private val escrito = AtomicLong(0)
        private val lido = AtomicLong(0)
        fun limpar() = lido.set(escrito.get())
        fun escrever(estereo: FloatArray, n: Int) {
            val tamanho = dados.size / 2
            val w = escrito.get()
            for (i in 0 until n) { val k = ((w + i) % tamanho).toInt() * 2; dados[k] = estereo[i * 2]; dados[k + 1] = estereo[i * 2 + 1] }
            escrito.set(w + n)
        }
        fun ler(e: FloatArray, d: FloatArray, n: Int, consumir: Boolean) {
            val tamanho = dados.size / 2
            val w = escrito.get()
            var p = lido.get()
            if (w - p > tamanho) p = w - tamanho
            val disponivel = min(n.toLong(), w - p).toInt()
            for (i in 0 until disponivel) { val k = ((p + i) % tamanho).toInt() * 2; e[i] = dados[k]; d[i] = dados[k + 1] }
            for (i in disponivel until n) { e[i] = 0f; d[i] = 0f }
            lido.set(if (consumir) p + disponivel else w)
        }
    }
}
