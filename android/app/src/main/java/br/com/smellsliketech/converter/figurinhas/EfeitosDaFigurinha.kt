package br.com.smellsliketech.converter.figurinhas

import br.com.smellsliketech.converter.estudio.Efeitos
import br.com.smellsliketech.converter.estudio.EstadoDoEfeito
import br.com.smellsliketech.converter.estudio.Transformacao
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Em que ponto da figurinha animada um quadro está. `fimDosVideos` diz onde acaba cada camada
 * de vídeo (id → segundos), para a saída dela terminar junto com o trecho.
 */
data class InstanteDosEfeitos(val tempo: Double, val duracao: Double, val quadrosPorSegundo: Int, val fimDosVideos: Map<String, Double> = emptyMap()) {
    fun duracaoDe(camada: CamadaDeFigurinha) = fimDosVideos[camada.id]?.coerceAtMost(duracao) ?: duracao
}

/**
 * Os efeitos do estúdio na figurinha. As contas de tempo são as de [Efeitos]; muda só o desenho:
 * a figurinha é composta quadro a quadro no Canvas, sem FFmpeg. Tudo aqui é matemática: o
 * [CompositorDeFigurinhas] aplica no bitmap.
 */
object EfeitosDaFigurinha {
    /** Uma imagem sem movimento próprio dura isso ao ganhar um efeito. */
    const val DURACAO_SEM_MIDIA = 3.0
    private const val COLUNAS = 6
    private const val LINHAS = 6

    /** Quanto dura uma volta de cada efeito contínuo, em segundos. */
    private val PERIODOS = mapOf(
        "girar" to 2.0, "balancar" to 1.25, "pulsar" to 5.0 / 6, "coracao" to 1.0,
        "tremer" to 1.0, "flutuar" to 5.0 / 3, "gelatina" to 2.0 / 3, "arcoiris" to 3.0,
    )

    fun transformacao(e: Edicao) = Transformacao(
        efeitoEntrada = e.efeitoEntrada, duracaoEntrada = e.duracaoEntrada.toDouble(),
        efeitoSaida = e.efeitoSaida, duracaoSaida = e.duracaoSaida.toDouble(), efeitoContinuo = e.efeitoContinuo,
    )

    fun tem(e: Edicao) = Efeitos.tem(transformacao(e))

    /**
     * A figurinha repete para sempre: o efeito contínuo acelera ou freia um pouco para caber um
     * número inteiro de voltas, e o último quadro emenda no primeiro sem pulo.
     */
    fun tempoEmLaco(tipo: String, tempo: Double, duracao: Double): Double {
        val periodo = PERIODOS[tipo] ?: return tempo
        if (duracao <= 0) return tempo
        return tempo * max(1, (duracao / periodo).roundToInt()) * periodo / duracao
    }

    fun estado(e: Edicao, tempo: Double, duracao: Double): EstadoDoEfeito =
        Efeitos.estado(transformacao(e), tempo, duracao, tempoEmLaco(e.efeitoContinuo, tempo, duracao))

    /** Quantas letras a máquina de escrever mostra; nulo quando o texto já está inteiro. */
    fun letrasVisiveis(total: Int, e: Edicao, tempo: Double, duracao: Double, fps: Int): Int? {
        if (e.efeitoEntrada != "maquina" || total <= 0) return null
        return Efeitos.letrasVisiveis(Efeitos.passosDaMaquina(total, e.duracaoEntrada.toDouble(), duracao, fps), tempo).takeIf { it < total }
    }

    /** Giro de matiz em torno da luminância (arco-íris), como uma matriz de cor 4×5. */
    fun matrizDoMatiz(graus: Double): FloatArray {
        val a = Math.toRadians(graus)
        val c = cos(a)
        val s = sin(a)
        return doubleArrayOf(
            .213 + c * .787 - s * .213, .715 - c * .715 - s * .715, .072 - c * .072 + s * .928, 0.0, 0.0,
            .213 - c * .213 + s * .143, .715 + c * .285 + s * .140, .072 - c * .072 - s * .283, 0.0, 0.0,
            .213 - c * .213 - s * .787, .715 - c * .715 + s * .715, .072 + c * .928 + s * .072, 0.0, 0.0,
            0.0, 0.0, 0.0, 1.0, 0.0,
        ).map { it.toFloat() }.toFloatArray()
    }

    /** Um estilhaço: o retângulo da figura e onde ele vai parar (centro em pixels, giro em graus). */
    data class Estilhaco(val x: Int, val y: Int, val largura: Int, val altura: Int, val centroX: Float, val centroY: Float, val giro: Float)

    /**
     * A figura dividida em 6×6 pedaços, cada um longe do centro por `afastamento` (0 = montada,
     * 1 = espalhada). A grade cobre a caixa da figura, e não o quadro inteiro: uma camada pequena
     * também quebra em 36 pedaços. O sorteio é o do estúdio.
     */
    fun estilhacos(esquerda: Int, topo: Int, direita: Int, base: Int, afastamento: Double): List<Estilhaco> {
        val tw = ceil((direita - esquerda).toDouble() / COLUNAS).toInt()
        val th = ceil((base - topo).toDouble() / LINHAS).toInt()
        val px = (esquerda + direita) / 2.0
        val py = (topo + base) / 2.0
        val alcance = 1.2 * Regras.LADO
        return (0 until COLUNAS * LINHAS).mapNotNull { k ->
            val x = esquerda + k % COLUNAS * tw
            val y = topo + k / COLUNAS * th
            val w = min(tw, direita - x)
            val h = min(th, base - y)
            if (w <= 0 || h <= 0) return@mapNotNull null
            val cx = x + w / 2.0
            val cy = y + h / 2.0
            val distancia = max(1.0, hypot(cx - px, cy - py))
            val voo = alcance * (.5 + Efeitos.sorteio(k, 1)) * afastamento / distancia
            val volta = (Efeitos.sorteio(k, 2) - .5) * 720 * afastamento
            Estilhaco(x, y, w, h, (cx + (cx - px) * voo).toFloat(), (cy + (cy - py) * voo).toFloat(), volta.toFloat())
        }
    }

    /** Lado do grão da desintegração, em pixels da figurinha. */
    val GRAO = max(2, (Regras.LADO / 90.0).roundToInt())

    /** A partícula (coluna, linha) aparece quando o ruído fica abaixo de `limite`: a figura some da direita para a esquerda. */
    fun particulaVisivel(coluna: Int, linha: Int, colunas: Int, limite: Double): Boolean {
        val ruido = sin(coluna * 12.9898 + linha * 78.233) * 43758.5453
        return (ruido - floor(ruido)) * .7 + coluna.toDouble() / colunas * .3 < limite
    }
}
