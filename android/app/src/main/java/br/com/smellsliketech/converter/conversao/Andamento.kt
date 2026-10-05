package br.com.smellsliketech.converter.conversao

import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * O tempo que falta numa conversão, a partir da porcentagem: o que já levou, proporcional ao
 * que falta, suavizado para o número não pular a cada atualização. Kotlin puro, testável.
 */
class Andamento(private val relogioMs: () -> Long = { System.nanoTime() / 1_000_000 }) {
    /** Quando a porcentagem começou a andar, e de onde: a preparação (copiar, abrir) não entra na conta. */
    private var base: Pair<Long, Int>? = null
    private var ultimoRestante: Double? = null
    private var ultimoMomento = 0L

    /** Segundos que faltam, ou null enquanto ainda não dá para estimar. */
    fun registrar(porcentagem: Int): Int? {
        val agora = relogioMs()
        if (porcentagem <= 0 || porcentagem >= 100) return null
        val (inicio, porcentagemInicial) = base ?: (agora to porcentagem).also { base = it }
        val decorrido = (agora - inicio) / 1000.0
        val andou = porcentagem - porcentagemInicial
        if (andou < 2 || decorrido < 2) return null
        val bruto = decorrido * (100 - porcentagem) / andou
        val anterior = ultimoRestante?.let { (it - (agora - ultimoMomento) / 1000.0).coerceAtLeast(0.0) }
        val suave = if (anterior == null) bruto else 0.75 * anterior + 0.25 * bruto
        ultimoRestante = suave
        ultimoMomento = agora
        return suave.roundToInt()
    }

    companion object {
        /** "faltam cerca de 2 min", "faltam cerca de 1 h 10 min", "quase pronto". */
        fun descrever(segundos: Int): String = when {
            segundos <= 5 -> "quase pronto"
            segundos < 60 -> "faltam cerca de ${(ceil(segundos / 5.0) * 5).toInt()} s"
            segundos < 3600 -> "faltam cerca de ${ceil(segundos / 60.0).toInt()} min"
            else -> {
                val minutos = ceil(segundos / 60.0).toInt()
                "faltam cerca de ${minutos / 60} h" + if (minutos % 60 != 0) " ${minutos % 60} min" else ""
            }
        }
    }
}

/** "37% · faltam cerca de 2 min"; antes da primeira porcentagem, "Preparando…". */
fun textoDoAndamento(porcentagem: Int, restante: Int?): String {
    val tempo = restante?.let { Andamento.descrever(it) }
    return when {
        porcentagem <= 0 && tempo == null -> "Preparando…"
        porcentagem <= 0 -> "Preparando… · $tempo"
        tempo == null -> "$porcentagem%"
        else -> "$porcentagem% · $tempo"
    }
}

/**
 * O andamento da transcrição. O filtro whisper do FFmpeg transcreve em blocos de 30 s e
 * avisa no log quando começa cada um ("run transcription at X ms … (Y seconds)"): o que vem
 * antes de X já está pronto. Entre um aviso e outro, a porcentagem anda pelo ritmo do
 * aparelho (segundos de processamento por segundo de áudio), medido nos blocos anteriores
 * ou, no começo, o da última transcrição com o mesmo modelo. Kotlin puro, testável.
 */
class ProgressoDaTranscricao(private val totalSegundos: Double, ritmoInicial: Double, private val cargaSegundos: Double) {
    private var feitoSegundos = 0.0
    private var blocoSegundos = 0.0
    private var inicioDoBlocoMs: Long? = null
    private var primeiroBlocoMs: Long? = null
    private var primeiroBlocoEm = 0.0
    var ritmo: Double = ritmoInicial
        private set

    private val bloco = Regex("""run transcription at (\d+) ms, \d+/\d+ samples \((\d+(?:\.\d+)?) seconds\)""")

    /** Uma linha do log do FFmpeg. */
    fun linha(texto: String, agoraMs: Long) {
        val (inicioMs, duracao) = bloco.find(texto)?.destructured ?: return
        val inicio = inicioMs.toDouble() / 1000
        val primeiro = primeiroBlocoMs
        if (primeiro == null) {
            primeiroBlocoMs = agoraMs
            primeiroBlocoEm = inicio
        } else if (inicio > primeiroBlocoEm) {
            // Ritmo medido: o tempo entre o começo do primeiro bloco e o deste, pelo áudio entre eles.
            ritmo = ((agoraMs - primeiro) / 1000.0 / (inicio - primeiroBlocoEm)).coerceIn(0.01, 50.0)
        }
        feitoSegundos = inicio
        blocoSegundos = duracao.toDouble()
        inicioDoBlocoMs = agoraMs
    }

    /**
     * O ritmo desta transcrição, do começo do primeiro bloco até `fimMs` (sem a carga do
     * modelo), para a estimativa da próxima. Null se o áudio era curto demais para medir.
     */
    fun ritmoMedido(fimMs: Long): Double? {
        val primeiro = primeiroBlocoMs ?: return null
        val audio = totalSegundos - primeiroBlocoEm
        if (audio < 3) return null
        return ((fimMs - primeiro) / 1000.0 / audio).coerceIn(0.01, 50.0)
    }

    /** A porcentagem (0–99) e os segundos que faltam, em `agoraMs` desde o começo (`comecoMs`). */
    fun estado(agoraMs: Long, comecoMs: Long): Pair<Int, Int> {
        val inicioDoBloco = inicioDoBlocoMs
        if (inicioDoBloco == null || totalSegundos <= 0) {
            // Ainda carregando o modelo: nada transcrito; o tempo é a carga mais o áudio todo.
            val restante = (cargaSegundos - (agoraMs - comecoMs) / 1000.0).coerceAtLeast(0.0) + totalSegundos * ritmo
            return 0 to restante.roundToInt()
        }
        val noBloco = ((agoraMs - inicioDoBloco) / 1000.0 / ritmo).coerceAtMost(blocoSegundos * 0.98)
        val feito = (feitoSegundos + noBloco).coerceAtMost(totalSegundos)
        val porcentagem = (feito / totalSegundos * 100).toInt().coerceIn(0, 99)
        return porcentagem to ((totalSegundos - feito) * ritmo).roundToInt()
    }
}
