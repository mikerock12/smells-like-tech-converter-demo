package br.com.smellsliketech.converter.estudio

import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * As três qualidades da exportação e a prévia leve. Espelho de estudio-qualidade.mjs (site) e de
 * StudioQuality.cs (Windows e plugin): a baixa reduz o quadro para até 720p e codifica com menos
 * bits; a média mantém o tamanho do projeto; a alta mantém o tamanho com o melhor acabamento. A
 * prévia é só para ter uma ideia: lado maior de 480 px e até 12 quadros/s.
 */
data class Qualidade(
    val id: String,
    val titulo: String,
    val descricao: String,
    val ladoMaximo: Int,
    val fpsMaximo: Int,
    /** De 0 a 100, como no conversor (vira a taxa de bits do codificador do celular). */
    val qualidadeDoVideo: Int,
    val custo: Double,
) {
    /** O projeto no tamanho e fps desta qualidade (o lado maior nunca passa do limite). */
    fun aplicar(projeto: Projeto): Projeto {
        val escala = minOf(1.0, ladoMaximo.toDouble() / maxOf(projeto.largura, projeto.altura))
        return projeto.copy(largura = Estudio.par(projeto.largura * escala), altura = Estudio.par(projeto.altura * escala), fps = minOf(projeto.fps, fpsMaximo))
    }

    companion object {
        val PREVIA = Qualidade("previa", "Prévia", "Só para ter uma ideia dos efeitos.", 480, 12, 40, .35)
        val TODAS = listOf(
            Qualidade("baixa", "Baixa", "Até 720p, a mais rápida e leve.", 1280, 60, 55, .55),
            Qualidade("media", "Média", "Tamanho do projeto, codificação rápida.", 4096, 60, 70, .75),
            Qualidade("alta", "Alta", "Tamanho do projeto, melhor acabamento.", 4096, 60, 82, 1.0),
        )
        fun porId(id: String?) = TODAS.firstOrNull { it.id == id } ?: TODAS.last()
    }
}

/**
 * Quanto este celular leva para compor um megapixel-quadro e para calcular a máscara de fundo de
 * um quadro.
 * Começa com uma medida de celular intermediário e se ajusta a cada prévia ou exportação.
 */
data class Velocidade(val segundosPorMegapixelQuadro: Double = .03, val segundosPorMascara: Double = .12) {
    companion object {
        /** Ler e decodificar o vídeo de origem custa o mesmo para qualquer tamanho de saída. */
        const val SEGUNDOS_POR_QUADRO = .015
    }

    fun aprender(megapixelQuadro: Double?, mascara: Double?) = Velocidade(
        if (megapixelQuadro != null && megapixelQuadro > 0 && megapixelQuadro < 10) segundosPorMegapixelQuadro * .4 + megapixelQuadro * .6 else segundosPorMegapixelQuadro,
        if (mascara != null && mascara > 0 && mascara < 30) segundosPorMascara * .4 + mascara * .6 else segundosPorMascara,
    )
}

object Estimativa {
    /** Quadros de vídeo que passam pelo recorte do fundo (o trecho inteiro de cada mídia). */
    fun quadrosRecortados(p: Projeto): Double =
        if (p.saida == "audio" || p.saida == "texto") 0.0 else
            p.clipes.filter { !it.ehImagem && it.transformacao?.removerFundo == true }.sumOf { ceil(Estudio.duracaoDaOrigem(it) * p.fps) } +
                p.camadas.filter { it.visivel && it.tipo != "imagem" && it.transformacao?.removerFundo == true }.sumOf { ceil((it.deslocamento + it.fim - it.inicio) * p.fps) }

    /** Peso relativo da composição: camadas, fundo desfocado, textos e transições pesam mais. */
    fun pesoDaComposicao(p: Projeto): Double =
        1 + p.camadas.count { it.visivel } * .35 + p.clipes.count { it.ajuste == "caber-desfocado" } * .25 / maxOf(1, p.clipes.size) +
            (if (p.textos.isNotEmpty()) .1 else 0.0) + (if (Estudio.temTransicoes(p)) .2 else 0.0)

    /** Segundos estimados para montar o projeto na qualidade dada (a prévia inclusive). */
    fun segundos(projeto: Projeto, qualidade: Qualidade, velocidade: Velocidade): Double {
        val p = qualidade.aplicar(projeto)
        if (p.saida == "audio" || p.saida == "texto") return maxOf(2.0, Estudio.duracaoTotal(p) * .05)
        val quadros = if (p.saida == "imagem") 1.0 else Estudio.duracaoTotal(p) * p.fps
        val megapixels = p.largura * p.altura / 1e6
        val composicao = quadros * (Velocidade.SEGUNDOS_POR_QUADRO + megapixels * velocidade.segundosPorMegapixelQuadro * pesoDaComposicao(p) * qualidade.custo)
        // O ML Kit do celular recorta todos os quadros; ler e gravar cada um custa pouco.
        val recorte = quadrosRecortados(p) * (velocidade.segundosPorMascara + .01 * (megapixels / 2.07).coerceIn(.35, 1.5))
        return maxOf(1.0, composicao + recorte + 1.5)
    }

    /** "cerca de 40 s", "cerca de 3 min", "cerca de 1 h 10 min". */
    fun legivel(segundos: Double): String {
        val s = maxOf(1, segundos.roundToInt())
        if (s < 60) return "cerca de ${maxOf(5, ceil(s / 5.0).toInt() * 5)} s"
        val minutos = ceil(s / 60.0).toInt()
        return if (minutos < 60) "cerca de $minutos min" else "cerca de ${minutos / 60} h ${"%02d".format(minutos % 60)} min"
    }
}
