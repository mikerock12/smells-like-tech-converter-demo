package br.com.smellsliketech.converter.estudio

import java.math.BigDecimal
import kotlin.math.max
import kotlin.math.min

/** Uma das saídas do estúdio. */
data class SaidaDoEstudio(val id: String, val titulo: String, val descricao: String, val extensao: String)

data class AjusteDoEstudio(val id: String, val titulo: String, val descricao: String)

data class FonteDoEstudio(val id: String, val nome: String, val arquivo: String?)

data class Segmento(val indice: Int, val id: String, val inicio: Double, val fim: Double, val duracao: Double)

/** O que só o app sabe: caminho de cada entrada e se o vídeo tem som. */
data class EntradasDaReceita(val clipes: List<EntradaDeClipe>, val textos: List<String>, val audios: List<String>, val camadas: List<String> = emptyList())

data class EntradaDeClipe(val caminho: String, val temAudio: Boolean)

/** Argumentos de entrada, o filter_complex e os mapas; o codificador fica com quem chama. */
data class Receita(val args: List<String>, val filtro: String, val mapas: List<String>, val duracao: Double)

/**
 * Regras do estúdio, espelho de packages/converter-core/estudio.mjs (e do StudioRules/StudioRecipe
 * do Windows). Textos e números saem idênticos aos do JS: os casos de
 * packages/converter-core/estudio-casos são conferidos no EstudioTest. Kotlin puro.
 */
object Estudio {
    fun camadasNoTempo(projeto: Projeto, tempo: Double) =
        projeto.camadas.filter { it.visivel && tempo >= it.inicio && tempo < it.fim }.sortedBy { it.ordem }

    fun usaCamadas(projeto: Projeto) = projeto.fundo != null || projeto.camadas.isNotEmpty() ||
        projeto.clipes.any { it.transformacao != null } || projeto.textos.any { it.transformacao != null }
    const val VERSAO_DO_PROJETO = 4
    const val DURACAO_PADRAO_DA_IMAGEM = 3.0
    const val DURACAO_MINIMA = 0.1
    const val LADO_DO_GIF = 480
    const val FPS_DO_GIF = 15

    // Layout do texto (frações do tamanho da letra), igual em todas as plataformas.
    const val LARGURA_MAXIMA_DO_TEXTO = 0.9
    const val ENTRELINHA = 1.15
    const val CONTORNO_DO_TEXTO = 1.0 / 8
    const val MARGEM_DA_CAIXA = 0.3
    const val RAIO_DA_CAIXA = 0.25

    val SAIDAS = listOf(
        SaidaDoEstudio("video", "Vídeo", "MP4 pronto para postar, com som.", "mp4"),
        SaidaDoEstudio("gif", "GIF", "Animação leve, sem som, que toca sozinha.", "gif"),
        SaidaDoEstudio("imagem", "Imagem", "Um quadro em PNG: capa, post ou miniatura.", "png"),
        SaidaDoEstudio("audio", "Só o áudio", "MP3 com a mistura final do som.", "mp3"),
        SaidaDoEstudio("texto", "Texto", "A fala transcrita, sem enviar nada para a nuvem.", "txt"),
    )

    val AJUSTES = listOf(
        AjusteDoEstudio("preencher", "Preencher", "Ocupa o quadro todo; corta as sobras."),
        AjusteDoEstudio("caber", "Caber", "Mostra tudo, com faixas pretas."),
        AjusteDoEstudio("caber-desfocado", "Caber com fundo desfocado", "Mostra tudo; as sobras viram a própria imagem desfocada."),
    )

    val FONTES = listOf(
        FonteDoEstudio("padrao", "Padrão", null),
        FonteDoEstudio("anton", "Impacto", "Anton-Regular.ttf"),
        FonteDoEstudio("bangers", "Quadrinho", "Bangers-Regular.ttf"),
        FonteDoEstudio("pacifico", "Cursiva", "Pacifico-Regular.ttf"),
        FonteDoEstudio("marcador", "Marcador", "PermanentMarker-Regular.ttf"),
    )

    fun saida(id: String) = SAIDAS.firstOrNull { it.id == id }

    fun preset(presets: List<Preset>, id: String) = presets.firstOrNull { it.id == id }

    fun novoProjeto(presets: List<Preset>, destino: String, saida: String = "video", largura: Int? = null, altura: Int? = null): Projeto {
        val preset = preset(presets, destino) ?: preset(presets, "personalizado")!!
        return Projeto(
            destino = preset.id,
            largura = par((largura ?: preset.largura).toDouble()),
            altura = par((altura ?: preset.altura).toDouble()),
            fps = preset.fps,
            saida = if (saida(saida) != null) saida else "video",
        )
    }

    fun velocidadeDoClipe(c: Clipe) = if(c.ehImagem || !c.velocidade.isFinite() || c.velocidade<.25 || c.velocidade>4) 1.0 else c.velocidade
    fun duracaoDaOrigem(c: Clipe) = max(DURACAO_MINIMA,(c.fim ?: 0.0)-(c.inicio ?: 0.0))
    fun duracaoDaTransicao(p: Projeto,i: Int): Double {
        val c=p.clipes.getOrNull(i) ?: return 0.0
        val seguinte=p.clipes.getOrNull(i+1) ?: return 0.0
        val t=c.transicao ?: return 0.0
        return if(t.tipo=="corte") 0.0 else arredondar(max(0.0,min(t.duracao,min(duracaoDoClipe(c)/2,duracaoDoClipe(seguinte)/2))))
    }
    fun temTransicoes(p: Projeto) = p.clipes.indices.any { duracaoDaTransicao(p,it)>0 }
    fun filtroDeVelocidade(c: Clipe) = if(velocidadeDoClipe(c)==1.0) "" else "setpts=(PTS-STARTPTS)/${numero(velocidadeDoClipe(c))},"
    fun filtroDeTempoAudio(c: Clipe): String {
        var v=velocidadeDoClipe(c); if(v==1.0) return ""
        val lista=mutableListOf<String>()
        while(v>2) {lista+="atempo=2";v/=2}
        while(v<.5) {lista+="atempo=0.5";v*=2}
        lista+="atempo=${numero(v)}"; return lista.joinToString(",")+","
    }
    fun duracaoDoClipe(clipe: Clipe): Double =
        if (clipe.ehImagem) max(DURACAO_MINIMA, clipe.duracao ?: DURACAO_PADRAO_DA_IMAGEM)
        else duracaoDaOrigem(clipe)/velocidadeDoClipe(clipe)

    fun segmentos(projeto: Projeto): List<Segmento> {
        var agora = 0.0
        return projeto.clipes.mapIndexed { indice, clipe ->
            if(indice>0) agora-=duracaoDaTransicao(projeto,indice-1)
            val duracao = duracaoDoClipe(clipe)
            Segmento(indice, clipe.id, arredondar(agora), arredondar(agora + duracao), arredondar(duracao)).also { agora += duracao }
        }
    }

    fun duracaoTotal(projeto: Projeto): Double = if(projeto.versao<4) segmentos(projeto).lastOrNull()?.fim ?: 0.0 else arredondar(maxOf(segmentos(projeto).lastOrNull()?.fim ?: 0.0, projeto.camadas.filter { it.visivel }.maxOfOrNull { it.fim } ?: 0.0, projeto.audios.maxOfOrNull { (it.entrada ?: 0.0)+it.fim-it.inicio } ?: 0.0))

    /** O clipe visível no instante `t` e o ponto dele no arquivo. */
    fun clipeNoTempo(projeto: Projeto, t: Double): Triple<Int, Clipe, Double>? {
        val lista = segmentos(projeto)
        if (lista.isEmpty()) return null
        val alvo = min(max(0.0, t), duracaoTotal(projeto))
        val segmento = lista.firstOrNull { alvo < it.fim } ?: lista.last()
        val clipe = projeto.clipes[segmento.indice]
        val dentro = min(alvo - segmento.inicio, if(projeto.versao>=4 && alvo>=segmento.fim) maxOf(0.0,(kotlin.math.ceil(segmento.duracao*projeto.fps)-1)/projeto.fps) else segmento.duracao)
        return Triple(segmento.indice, clipe, arredondar(if (clipe.ehImagem) 0.0 else (clipe.inicio ?: 0.0) + dentro * velocidadeDoClipe(clipe)))
    }

    fun textosNoTempo(projeto: Projeto, t: Double) = projeto.textos.filter { t >= it.inicio && (if((it.id.startsWith("legenda-") || it.id.startsWith("contagem-"))) t < it.fim else t <= it.fim) }

    /** Onde a origem é desenhada para preencher ou caber (pares, como o scale do FFmpeg): x, y, largura, altura. */
    fun retanguloDoAjuste(ajuste: String, larguraDaOrigem: Double, alturaDaOrigem: Double, largura: Int, altura: Int): List<Double> {
        val escala = if (ajuste == "preencher") max(largura / larguraDaOrigem, altura / alturaDaOrigem)
        else min(largura / larguraDaOrigem, altura / alturaDaOrigem)
        val w = par(larguraDaOrigem * escala)
        val h = par(alturaDaOrigem * escala)
        return listOf((largura - w) / 2.0, (altura - h) / 2.0, w.toDouble(), h.toDouble())
    }

    fun tamanhoDoGif(largura: Int, altura: Int): Pair<Int, Int> {
        val menor = min(largura, altura)
        if (menor <= LADO_DO_GIF) return par(largura.toDouble()) to par(altura.toDouble())
        val escala = LADO_DO_GIF.toDouble() / menor
        return par(largura * escala) to par(altura * escala)
    }

    /** Problemas que impedem exportar, nos mesmos textos do site e do Windows. */
    fun problemas(projeto: Projeto): List<String> {
        val lista = mutableListOf<String>()
        if (projeto.versao !in listOf(1, 2, 3, VERSAO_DO_PROJETO)) lista += "Projeto de uma versão que este app não conhece."
        if (!(projeto.largura >= 16 && projeto.altura >= 16 && projeto.largura <= 4096 && projeto.altura <= 4096)) {
            lista += "O tamanho do quadro precisa ficar entre 16 e 4096 pixels."
        }
        if (saida(projeto.saida) == null) lista += "Escolha o que exportar."
        if (projeto.clipes.isEmpty()) lista += "Adicione pelo menos um vídeo ou imagem."
        projeto.clipes.forEachIndexed { i, clipe ->
            if (clipe.tipo == "video" && !((clipe.fim ?: 0.0) - (clipe.inicio ?: 0.0) >= DURACAO_MINIMA)) lista += "O clipe ${i + 1} termina antes de começar."
            if (!clipe.velocidade.isFinite() || clipe.velocidade<.25 || clipe.velocidade>4) lista += "A velocidade do clipe ${i+1} precisa ficar entre 0,25× e 4×."
            clipe.transicao?.let { t -> if(t.tipo !in listOf("corte","fade","dissolver","deslizar","zoom") || !t.duracao.isFinite() || t.duracao<.1 || t.duracao>5) lista += "A transição do clipe ${i+1} é inválida (use de 0,1 a 5 segundos)." }
            if (AJUSTES.none { it.id == clipe.ajuste }) lista += "O clipe ${i + 1} está sem enquadramento."
        }
        projeto.textos.forEachIndexed { i, texto ->
            if (texto.texto.isBlank()) lista += "O texto ${i + 1} está vazio."
            if (!(texto.fim > texto.inicio)) lista += "O texto ${i + 1} termina antes de começar."
        }
        projeto.audios.forEachIndexed { i, audio ->
            if (!(audio.fim - audio.inicio >= DURACAO_MINIMA)) lista += "O áudio ${i + 1} termina antes de começar."
        }
        if (projeto.camadas.size > 12) lista += "Use até 12 camadas adicionais."
        projeto.camadas.forEachIndexed { i, camada ->
            val numero = i + 1
            if (camada.tipo !in listOf("imagem", "video", "gif") || camada.arquivo.isEmpty()) lista += "A camada $numero está sem mídia válida."
            if (!camada.inicio.isFinite() || !camada.fim.isFinite() || camada.inicio < 0 || camada.fim <= camada.inicio) lista += "A camada $numero está com o intervalo inválido."
            if (!camada.deslocamento.isFinite() || camada.deslocamento < 0) lista += "A camada $numero está com o início da mídia inválido."
        }
        (projeto.clipes.map { it.transformacao } + projeto.textos.map { it.transformacao } + projeto.camadas.map { it.transformacao }).forEach {
            it?.problema()?.let { erro -> lista += erro }
        }
        fun corValida(cor: String) = cor.matches(Regex("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?"))
        projeto.fundo?.let { if (!corValida(it.cor) || (it.corFinal != null && !corValida(it.corFinal))) lista += "Cor de fundo inválida." }
        val total = duracaoTotal(projeto)
        if(projeto.versao>=4) {
            if(projeto.fps !in 1..60) lista+="Use uma taxa de 1 a 60 quadros por segundo."
            if(!total.isFinite()||total<0||total>86400) lista+="A composição deve ter duração finita de até 24 horas."
            if(projeto.clipes.size>100||projeto.textos.size>1100||projeto.audios.size>20) lista+="Use até 100 clipes, 1100 textos e 20 áudios."
            if(projeto.audios.any {!(it.entrada?:0.0).isFinite()||(it.entrada?:0.0)<0||!it.inicio.isFinite()||it.inicio<0||!it.fim.isFinite()}) lista+="O intervalo de um áudio é inválido."
        }
        if (projeto.saida == "imagem" && projeto.clipes.isNotEmpty() && !(projeto.quadro >= 0 && projeto.quadro <= total)) {
            lista += "Escolha um instante dentro do vídeo para a imagem."
        }
        return lista
    }

    /** Avisos que não impedem exportar. */
    fun avisos(projeto: Projeto, presets: List<Preset>): List<String> {
        val lista = mutableListOf<String>()
        val preset = preset(presets, projeto.destino)
        val total = duracaoTotal(projeto)
        val maximo = preset?.duracaoMaxima
        if (preset != null && maximo != null && maximo > 0 && total > maximo && projeto.saida in listOf("video", "gif")) {
            lista += "${preset.rede} · ${preset.nome} costuma aceitar até ${duracaoLegivel(maximo)}; este projeto tem ${duracaoLegivel(total)}."
        }
        if (projeto.saida == "gif" && total > 15) lista += "GIF com mais de 15 segundos fica pesado; considere exportar em vídeo."
        if (total > 0 && projeto.textos.any { it.inicio >= total }) lista += "Algum texto começa depois do fim do vídeo e não vai aparecer."
        return lista
    }

    /** A receita do FFmpeg: idêntica, caractere por caractere, à do JS e à do Windows. */
    fun receita(projeto: Projeto, entradas: EntradasDaReceita, otimizar: Boolean = false): Receita {
        if (projeto.saida !in listOf("audio", "texto") && (usaCamadas(projeto) || temTransicoes(projeto))) return ReceitaDeCamadas.montar(projeto, entradas, otimizar)
        val w = projeto.largura
        val h = projeto.altura
        val fps = projeto.fps
        val total = duracaoTotal(projeto)
        val comVideo = projeto.saida != "audio" && projeto.saida != "texto"
        val comAudio = projeto.saida in listOf("video", "audio", "texto")
        val args = mutableListOf<String>()
        val filtros = mutableListOf<String>()
        var indiceDaEntrada = 0

        projeto.clipes.forEachIndexed { i, clipe ->
            val caminho = entradas.clipes[i].caminho
            val duracao = duracaoDoClipe(clipe)
            if (clipe.ehImagem) args += listOf("-loop", "1", "-framerate", fps.toString(), "-t", numero(duracao), "-i", caminho)
            else args += listOf("-ss", numero(clipe.inicio ?: 0.0), "-t", numero(duracaoDaOrigem(clipe)), "-i", caminho)
            val n = indiceDaEntrada++
            if (comVideo) filtros += filtroDoClipe(clipe.ajuste, n, i, w, h, fps).replace("[$n:v]", "[$n:v]${filtroDeVelocidade(clipe)}")
            if (comAudio) {
                val temAudio = clipe.tipo == "video" && entradas.clipes[i].temAudio && clipe.mudo != true && (clipe.volume ?: 1.0) > 0
                filtros += if (temAudio) {
                    "[$n:a]${filtroDeTempoAudio(clipe)}aresample=48000,aformat=channel_layouts=stereo,volume=${numero(clipe.volume ?: 1.0)},apad=whole_dur=${numero(duracao)},atrim=duration=${numero(duracao)},asetpts=PTS-STARTPTS[a$i]"
                } else {
                    "anullsrc=r=48000:cl=stereo,atrim=duration=${numero(duracao)},asetpts=PTS-STARTPTS[a$i]"
                }
            }
        }

        val pares = projeto.clipes.indices.joinToString("") { i -> (if (comVideo) "[v$i]" else "") + (if (comAudio) "[a$i]" else "") }
        if(temTransicoes(projeto) && !comVideo) {
            var anterior="a0"
            for(i in 1 until projeto.clipes.size) {
                val d=duracaoDaTransicao(projeto,i-1); val proximo="ax$i"
                filtros+=if(d>0) "[$anterior][a$i]acrossfade=d=${numero(d)}:c1=tri:c2=tri[$proximo]" else "[$anterior][a$i]concat=n=2:v=0:a=1[$proximo]"
                anterior=proximo
            }
            filtros+="[$anterior]anull[ab]"
        } else filtros += "${pares}concat=n=${projeto.clipes.size}:v=${if (comVideo) 1 else 0}:a=${if (comAudio) 1 else 0}${if (comVideo) "[vb]" else ""}${if (comAudio) "[ab]" else ""}"

        if(comAudio && total>(segmentos(projeto).lastOrNull()?.fim ?: 0.0)) {
            val antigo=filtros.removeAt(filtros.lastIndex);filtros+=antigo.replace("[ab]","[abase]")
            filtros+="[abase]apad=whole_dur=${numero(total)},atrim=duration=${numero(total)}[ab]"
        }
        if(comVideo && total>(segmentos(projeto).lastOrNull()?.fim ?: 0.0)) {
            for(i in filtros.indices) filtros[i]=filtros[i].replace("[vb]","[vbase]")
            filtros+="[vbase]tpad=stop_mode=clone:stop_duration=${numero(total-(segmentos(projeto).lastOrNull()?.fim?:0.0))},trim=duration=${numero(total)}[vb]"
        }
        var video = "vb"
        if (comVideo) {
            projeto.textos.forEachIndexed { j, texto ->
                args += listOf("-loop", "1", "-framerate", fps.toString(), "-t", numero(total), "-i", entradas.textos[j])
                val n = indiceDaEntrada++
                val proximo = "vt$j"
                filtros += "[$video][$n:v]overlay=0:0:enable='${if((texto.id.startsWith("legenda-") || texto.id.startsWith("contagem-"))) "gte(t,${numero(texto.inicio)})*lt(t,${numero(texto.fim)})" else "between(t,${numero(texto.inicio)},${numero(texto.fim)})"}'[$proximo]"
                video = proximo
            }
            if (projeto.saida == "gif") {
                val (gl, ga) = tamanhoDoGif(w, h)
                filtros += "[$video]fps=$FPS_DO_GIF,scale=$gl:$ga:flags=lanczos,split[g0][g1];[g0]palettegen=stats_mode=diff[paleta];[g1][paleta]paletteuse=dither=bayer:bayer_scale=4[gif]"
                video = "gif"
            }
        }

        var audio = "ab"
        if (comAudio && projeto.audios.isNotEmpty()) {
            val rotulos = projeto.audios.mapIndexed { k, musica ->
                args += listOf("-ss", numero(musica.inicio), "-t", numero(musica.fim - musica.inicio), "-i", entradas.audios[k])
                val n = indiceDaEntrada++
                val comprimento = musica.fim - musica.inicio
                val cadeia = mutableListOf("aresample=48000", "aformat=channel_layouts=stereo", "volume=${numero(musica.volume ?: 1.0)}")
                val fadeEntrada = musica.fadeEntrada ?: 0.0
                val fadeSaida = musica.fadeSaida ?: 0.0
                if (fadeEntrada > 0) cadeia += "afade=t=in:st=0:d=${numero(fadeEntrada)}"
                if (fadeSaida > 0) cadeia += "afade=t=out:st=${numero(max(0.0, comprimento - fadeSaida))}:d=${numero(fadeSaida)}"
                val atraso = Math.round((musica.entrada ?: 0.0) * 1000)
                if (atraso > 0) cadeia += "adelay=$atraso:all=1"
                filtros += "[$n:a]${cadeia.joinToString(",")}[m$k]"
                "[m$k]"
            }
            filtros += "[ab]${rotulos.joinToString("")}amix=inputs=${rotulos.size + 1}:duration=first:normalize=0[am]"
            audio = "am"
        }

        val mapas = mutableListOf<String>()
        if (comVideo) mapas += listOf("-map", "[$video]")
        if (comAudio) mapas += listOf("-map", "[$audio]")
        return Receita(args, filtros.joinToString(";"), mapas, total)
    }

    private fun filtroDoClipe(ajuste: String, n: Int, i: Int, w: Int, h: Int, fps: Int): String {
        val fim = "setsar=1,format=yuv420p,setpts=PTS-STARTPTS[v$i]"
        return when (ajuste) {
            "preencher" -> "[$n:v]fps=$fps,scale=$w:$h:force_original_aspect_ratio=increase,crop=$w:$h,$fim"
            "caber" -> "[$n:v]fps=$fps,scale=$w:$h:force_original_aspect_ratio=decrease:force_divisible_by=2,pad=$w:$h:(ow-iw)/2:(oh-ih)/2:color=black,$fim"
            else -> "[$n:v]fps=$fps,split[f$i][p$i];" +
                "[f$i]scale=$w:$h:force_original_aspect_ratio=increase,crop=$w:$h,scale=${par(w / 8.0)}:${par(h / 8.0)},gblur=sigma=3,scale=$w:$h[fd$i];" +
                "[p$i]scale=$w:$h:force_original_aspect_ratio=decrease:force_divisible_by=2[pc$i];" +
                "[fd$i][pc$i]overlay=(W-w)/2:(H-h)/2,$fim"
        }
    }

    /** Número para o FFmpeg: até 3 casas, sem zeros sobrando ("2.5", "10", "0.333"), como o JS. */
    fun numero(valor: Double): String {
        val arredondado = arredondar(valor)
        return if (arredondado == Math.floor(arredondado)) arredondado.toLong().toString()
        else BigDecimal.valueOf(arredondado).stripTrailingZeros().toPlainString()
    }

    /** "1 min 30 s", "45 s". */
    fun duracaoLegivel(segundos: Double): String {
        val inteiro = Math.round(segundos).toInt()
        val minutos = inteiro / 60
        val resto = inteiro % 60
        if (minutos == 0) return "$resto s"
        return if (resto == 0) "$minutos min" else "$minutos min $resto s"
    }

    fun arredondar(valor: Double): Double = Math.round(valor * 1000) / 1000.0

    fun par(valor: Double): Int = max(2, Math.round(valor / 2).toInt() * 2)
}
