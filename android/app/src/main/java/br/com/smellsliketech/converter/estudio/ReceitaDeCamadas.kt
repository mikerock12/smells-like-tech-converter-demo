package br.com.smellsliketech.converter.estudio

/** Composição RGBA, espelho de estudio-camadas.mjs. */
object ReceitaDeCamadas {
    fun montar(p: Projeto,entradas: EntradasDaReceita): Receita {
        val audio=Estudio.receita(p.copy(saida="audio",textos=emptyList()),entradas)
        val args=audio.args.toMutableList(); val filtros=mutableListOf(audio.filtro)
        val total=Estudio.duracaoTotal(p); val w=p.largura; val h=p.altura; val fps=p.fps
        var indice=p.clipes.size+p.audios.size; var video="cfundo"; var etapa=0
        filtros+=FiltrosDeCamada.fundo(p.fundo,w,h,fps,total)
        fun compor(rotulo: String,t: Transformacao?,inicio: Double,fim: Double) {
            val proximo="cc${etapa++}"
            filtros+=FiltrosDeCamada.sobrepor(video,rotulo,proximo,t?.mescla,inicio,fim); video=proximo
        }
        val camadas=p.camadas.mapIndexed { i,c -> c to i }.filter { it.first.visivel }.sortedBy { it.first.ordem }
        fun extra(valor: Pair<Camada,Int>) {
            val(c,i)=valor; val duracao=c.fim-c.inicio
            if(c.tipo=="imagem") args+=listOf("-loop","1","-framerate",fps.toString())
            else {
                if(c.repetir) args+=listOf("-stream_loop","-1")
                if(c.tipo=="gif") args+=listOf("-ignore_loop","1")
                args+=listOf("-ss",n(c.deslocamento))
            }
            args+=listOf("-t",n(duracao),"-i",entradas.camadas[i])
            val entrada=indice++; val rotulo="ce$i"
            filtros+="[$entrada:v]fps=$fps,format=rgba,scale=$w:$h:force_original_aspect_ratio=decrease:force_divisible_by=2,pad=$w:$h:(ow-iw)/2:(oh-ih)/2:color=black@0,setsar=1,${FiltrosDeCamada.transformacao(c.transformacao,w,h)},tpad=stop_mode=clone:stop_duration=${n(duracao)},trim=duration=${n(duracao)},setpts=PTS-STARTPTS+${n(c.inicio)}/TB[$rotulo]"
            compor(rotulo,c.transformacao,c.inicio,c.fim)
        }
        camadas.filter { it.first.ordem<0 }.forEach(::extra)
        var inicio=0.0
        p.clipes.forEachIndexed { i,c ->
            val duracao=Estudio.duracaoDoClipe(c); val rotulo="cp$i"
            val fim="setsar=1,${FiltrosDeCamada.transformacao(c.transformacao,w,h)},setpts=PTS-STARTPTS+${n(inicio)}/TB[$rotulo]"
            filtros+=when(c.ajuste) {
                "preencher" -> "[$i:v]fps=$fps,format=rgba,scale=$w:$h:force_original_aspect_ratio=increase,crop=$w:$h,$fim"
                "caber" -> "[$i:v]fps=$fps,format=rgba,scale=$w:$h:force_original_aspect_ratio=decrease:force_divisible_by=2,pad=$w:$h:(ow-iw)/2:(oh-ih)/2:color=black@0,$fim"
                else -> "[$i:v]fps=$fps,format=rgba,split[cb$i][cf$i];[cb$i]scale=$w:$h:force_original_aspect_ratio=increase,crop=$w:$h,scale=${Estudio.par(w/8.0)}:${Estudio.par(h/8.0)},gblur=sigma=3,scale=$w:$h[cblur$i];[cf$i]scale=$w:$h:force_original_aspect_ratio=decrease:force_divisible_by=2[cfit$i];[cblur$i][cfit$i]overlay=(W-w)/2:(H-h)/2:format=auto,$fim"
            }
            compor(rotulo,c.transformacao,inicio,inicio+duracao); inicio+=duracao
        }
        camadas.filter { it.first.ordem>=0 }.forEach(::extra)
        p.textos.forEachIndexed { i,t ->
            args+=listOf("-loop","1","-framerate",fps.toString(),"-t",n(total),"-i",entradas.textos[i])
            val entrada=indice++; val rotulo="ct$i"
            filtros+="[$entrada:v]${FiltrosDeCamada.transformacao(t.transformacao,w,h)}[$rotulo]"
            compor(rotulo,t.transformacao,t.inicio,t.fim+1.0/fps)
        }
        if(p.saida=="gif") {
            val(gw,gh)=Estudio.tamanhoDoGif(w,h)
            filtros+="[$video]fps=${Estudio.FPS_DO_GIF},scale=$gw:$gh:flags=lanczos,split[g0][g1];[g0]palettegen=stats_mode=diff[paleta];[g1][paleta]paletteuse=dither=bayer:bayer_scale=4[gif]"; video="gif"
        }
        val mapas=mutableListOf("-map","[$video]")
        if(p.saida=="video") mapas+=audio.mapas else filtros+="${audio.mapas[1]}anullsink"
        return Receita(args,filtros.joinToString(";"),mapas,total)
    }
    private fun n(valor: Double)=Estudio.numero(valor)
}
