package br.com.smellsliketech.converter.estudio

/** Composição RGBA, espelho de estudio-camadas.mjs. */
object ReceitaDeCamadas {
    fun montar(p: Projeto,entradas: EntradasDaReceita, otimizar: Boolean = false): Receita {
        val audio=Estudio.receita(p.copy(saida="audio",textos=emptyList()),entradas)
        val args=audio.args.toMutableList(); val filtros=mutableListOf(audio.filtro)
        val total=Estudio.duracaoTotal(p); val w=p.largura; val h=p.altura; val fps=p.fps
        var indice=p.clipes.size+p.audios.size; var video="cfundo"; var etapa=0
        val transicoes=Estudio.temTransicoes(p); val lista=Estudio.segmentos(p)
        if(!transicoes) filtros+=FiltrosDeCamada.fundo(p.fundo,w,h,fps,total,otimizar)
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
            val pad="tpad=stop_mode=clone:stop_duration=${n(duracao)},trim=duration=${n(duracao)},"
            val animada=c.transformacao?.quadrosChave?.isNotEmpty()==true
            val entrada=indice++; val rotulo="ce$i"
            filtros+="[$entrada:v]fps=$fps,format=rgba,scale=$w:$h:force_original_aspect_ratio=decrease:force_divisible_by=2,pad=$w:$h:(ow-iw)/2:(oh-ih)/2:color=black@0,setsar=1,${if(animada) pad else ""}${FiltrosDeCamada.transformacao(c.transformacao,w,h,otimizar=otimizar)},${if(animada) "" else pad}setpts=PTS-STARTPTS+${n(c.inicio)}/TB[$rotulo]"
            compor(rotulo,c.transformacao,c.inicio,c.fim)
        }
        if(!transicoes) camadas.filter { it.first.ordem<0 }.forEach(::extra)
        fun fundoLocal(i: Int,inicio: Double,duracao: Double): String {
            var base="cbglocal$i";var etapaLocal=0
            filtros+=FiltrosDeCamada.fundo(p.fundo,w,h,fps,duracao,otimizar).replace("[cfundo]","[$base]")
            for((c,j) in camadas.filter {it.first.ordem<0}) {
                val de=maxOf(inicio,c.inicio);val ate=minOf(inicio+duracao,c.fim);if(ate<=de) continue
                val antes=de-c.inicio;val tempo=ate-de;val necessario=antes+tempo
                if(c.tipo=="imagem") args+=listOf("-loop","1","-framerate",fps.toString())
                else {if(c.repetir) args+=listOf("-stream_loop","-1");if(c.tipo=="gif") args+=listOf("-ignore_loop","1");args+=listOf("-ss",n(c.deslocamento))}
                args+=listOf("-t",n(necessario),"-i",entradas.camadas[j])
                val entrada=indice++;val rotulo="cbl${i}_$j";val proximo="cblbase${i}_${etapaLocal++}"
                filtros+="[$entrada:v]fps=$fps,tpad=stop_mode=clone:stop_duration=${n(necessario)},trim=start=${n(antes)}:duration=${n(tempo)},setpts=PTS-STARTPTS,format=rgba,scale=$w:$h:force_original_aspect_ratio=decrease:force_divisible_by=2,pad=$w:$h:(ow-iw)/2:(oh-ih)/2:color=black@0,setsar=1,${FiltrosDeCamada.transformacao(c.transformacao,w,h,antes,otimizar)},setpts=PTS-STARTPTS+${n(de-inicio)}/TB[$rotulo]"
                filtros+=FiltrosDeCamada.sobrepor(base,rotulo,proximo,c.transformacao?.mescla,de-inicio,ate-inicio);base=proximo
            }
            return base
        }
        var inicio=0.0
        p.clipes.forEachIndexed { i,c ->
            inicio=lista[i].inicio
            val original=Estudio.duracaoDoClipe(c);val duracao=if(i==p.clipes.lastIndex) maxOf(original,total-inicio) else original; val rotulo="cp$i"
            val estender=if(duracao>original) "tpad=stop_mode=clone:stop_duration=${n(duracao-original)},trim=duration=${n(duracao)}," else ""
            val fim="setsar=1,$estender${FiltrosDeCamada.transformacao(c.transformacao,w,h,otimizar=otimizar)},setpts=PTS-STARTPTS${if(transicoes) ",format=gbrap,settb=AVTB" else "+${n(inicio)}/TB"}[$rotulo]"
            filtros+=when(c.ajuste) {
                "preencher" -> "[$i:v]${Estudio.filtroDeVelocidade(c)}fps=$fps,format=rgba,scale=$w:$h:force_original_aspect_ratio=increase,crop=$w:$h,$fim"
                "caber" -> "[$i:v]${Estudio.filtroDeVelocidade(c)}fps=$fps,format=rgba,scale=$w:$h:force_original_aspect_ratio=decrease:force_divisible_by=2,pad=$w:$h:(ow-iw)/2:(oh-ih)/2:color=black@0,$fim"
                else -> "[$i:v]${Estudio.filtroDeVelocidade(c)}fps=$fps,format=rgba,split[cb$i][cf$i];[cb$i]scale=$w:$h:force_original_aspect_ratio=increase,crop=$w:$h,scale=${Estudio.par(w/8.0)}:${Estudio.par(h/8.0)},gblur=sigma=3,scale=$w:$h[cblur$i];[cf$i]scale=$w:$h:force_original_aspect_ratio=decrease:force_divisible_by=2[cfit$i];[cblur$i][cfit$i]overlay=(W-w)/2:(H-h)/2:format=auto,$fim"
            }
            if(!transicoes) compor(rotulo,c.transformacao,inicio,inicio+duracao)
            else {
                val base=fundoLocal(i,inicio,duracao)
                filtros+=FiltrosDeCamada.sobrepor(base,rotulo,"cq$i",c.transformacao?.mescla,0.0,duracao)
                filtros+="[cq$i]format=gbrap,settb=AVTB[cqf$i]"
            }
            inicio+=duracao
        }
        if(transicoes) {
            var anterior="cqf0"
            for(i in 1 until p.clipes.size) {
                val d=Estudio.duracaoDaTransicao(p,i-1); val proximo="cx$i"
                val tipo=when(p.clipes[i-1].transicao?.tipo) {"fade"->"custom:expr='if(eq(PLANE,3),255,if(gte(P,0.5),A*(2*P-1),B*(1-2*P)))'";"dissolver"->"fade";"deslizar"->"slideleft";"zoom"->"zoomin";else->"fade"}
                filtros+=if(d>0) "[$anterior][cqf$i]xfade=transition=$tipo:duration=${n(d)}:offset=${n(lista[i].inicio)}[$proximo]" else "[$anterior][cqf$i]concat=n=2:v=1:a=0[$proximo]"
                anterior=proximo
            }
            video=anterior
        }
        camadas.filter { it.first.ordem>=0 }.forEach(::extra)
        p.textos.forEachIndexed { i,t ->
            args+=listOf("-loop","1","-framerate",fps.toString(),"-t",n(total),"-i",entradas.textos[i])
            val entrada=indice++; val rotulo="ct$i"
            filtros+="[$entrada:v]${FiltrosDeCamada.transformacao(t.transformacao,w,h,-t.inicio,otimizar)}[$rotulo]"
            compor(rotulo,t.transformacao,t.inicio,t.fim+(if((t.id.startsWith("legenda-") || t.id.startsWith("contagem-"))) 0.0 else 1.0/fps))
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
