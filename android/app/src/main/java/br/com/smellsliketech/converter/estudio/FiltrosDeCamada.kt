package br.com.smellsliketech.converter.estudio

import java.math.BigDecimal
import kotlin.math.*

/** Perspectiva e cor com filtros LGPL. Espelho de camadas-ffmpeg.mjs. */
object FiltrosDeCamada {
    fun d(v: Double): String = BigDecimal.valueOf(floor(v * 1e9 + .5) / 1e9).stripTrailingZeros().toPlainString()

    private fun inversa(m: DoubleArray?): DoubleArray? {
        if (m == null) return null
        val a=m[0]; val b=m[1]; val c=m[2]; val d=m[3]; val e=m[4]; val f=m[5]; val g=m[6]; val h=m[7]; val i=m[8]
        val cof=doubleArrayOf(e*i-f*h,c*h-b*i,b*f-c*e,f*g-d*i,a*i-c*g,c*d-a*f,d*h-e*g,b*g-a*h,a*e-b*d)
        val det=a*cof[0]+b*cof[3]+c*cof[6]
        return if(abs(det)<1e-8) null else cof.map { it/det }.toDoubleArray()
    }
    internal fun projecao(t: Transformacao,w: Int,h: Int): DoubleArray? {
        val a=t.giro*PI/180; val ax=t.giroX*PI/180; val ay=t.giroY*PI/180
        val camera=max(w,h)*3.0
        val p=listOf(0.0 to 0.0,w.toDouble() to 0.0,w.toDouble() to h.toDouble(),0.0 to h.toDouble()).map { (u,v) ->
            val x=(u-w/2.0)*t.escala; val y=(v-h/2.0)*t.escala
            val y1=y*cos(ax); val z1=y*sin(ax)
            val x2=x*cos(ay)+z1*sin(ay); val z2=-x*sin(ay)+z1*cos(ay)
            val fator=camera/max(camera*.1,camera-z2)
            ((w*t.x+(x2*cos(a)-y1*sin(a))*fator*(if(t.espelharX) -1 else 1))/w) to
                ((h*t.y+(x2*sin(a)+y1*cos(a))*fator*(if(t.espelharY) -1 else 1))/h)
        }
        val dx1=p[1].first-p[2].first; val dx2=p[3].first-p[2].first; val dx3=p[0].first-p[1].first+p[2].first-p[3].first
        val dy1=p[1].second-p[2].second; val dy2=p[3].second-p[2].second; val dy3=p[0].second-p[1].second+p[2].second-p[3].second
        val det=dx1*dy2-dx2*dy1
        if(abs(det)<1e-8) return null
        val g=(dx3*dy2-dx2*dy3)/det; val j=(dx1*dy3-dx3*dy1)/det
        return doubleArrayOf(p[1].first-p[0].first+g*p[1].first,p[3].first-p[0].first+j*p[3].first,p[0].first,
            p[1].second-p[0].second+g*p[1].second,p[3].second-p[0].second+j*p[3].second,p[0].second,g,j,1.0)
    }
    fun matrizDeCor(t: Transformacao): DoubleArray {
        fun saturar(s: Double): DoubleArray {
            val r=.213*(1-s); val g=.715*(1-s); val b=.072*(1-s)
            return doubleArrayOf(r+s,g,b,0.0,r,g+s,b,0.0,r,g,b+s,0.0)
        }
        fun compor(a: DoubleArray,b: DoubleArray)=DoubleArray(12) { i ->
            val linha=i/4; val coluna=i%4
            a[linha*4]*b[coluna]+a[linha*4+1]*b[4+coluna]+a[linha*4+2]*b[8+coluna]+(if(coluna==3) a[i] else 0.0)
        }
        var m=when(t.filtro) {
            "vivido" -> saturar(1.6)
            "pb" -> saturar(0.0)
            "sepia" -> compor(doubleArrayOf(1.07,0.0,0.0,0.0,0.0,.74,0.0,0.0,0.0,0.0,.43,0.0),saturar(0.0))
            "vintage" -> compor(doubleArrayOf(1.0,0.0,0.0,30.0,0.0,.95,0.0,20.0,0.0,0.0,.8,0.0),saturar(.6))
            "frio" -> doubleArrayOf(.9,0.0,0.0,0.0,0.0,1.0,0.0,0.0,0.0,0.0,1.15,15.0)
            "quente" -> doubleArrayOf(1.15,0.0,0.0,15.0,0.0,1.02,0.0,5.0,0.0,0.0,.85,0.0)
            "negativo" -> doubleArrayOf(-1.0,0.0,0.0,255.0,0.0,-1.0,0.0,255.0,0.0,0.0,-1.0,255.0)
            else -> doubleArrayOf(1.0,0.0,0.0,0.0,0.0,1.0,0.0,0.0,0.0,0.0,1.0,0.0)
        }
        m=compor(saturar(1+t.saturacao),m)
        val c=1+t.contraste; val d=128*(1-c)+t.brilho*100
        return compor(doubleArrayOf(c,0.0,0.0,d,0.0,c,0.0,d,0.0,0.0,c,d),m)
    }
    fun transformacao(valor: Transformacao?,w: Int,h: Int,deslocamento: Double = 0.0, otimizar: Boolean = false): String {
        val t=valor?:Transformacao()
        // Não reamostrar uma identidade: evita bilhões de avaliações de expressão em vídeos longos.
        // Mesclagem e remoção de fundo são feitas fora deste filtro.
        if (otimizar && t.copy(mescla="normal", removerFundo=false, tempoDaAnimacao=0.0) == Transformacao()) return "format=rgba"
        val m=inversa(projecao(t,w,h))?:return "format=rgba,colorchannelmixer=aa=0"
        var u="(X+0.5)/W";var v="(Y+0.5)/H";var prefixo=""
        if(t.quadrosChave.isNotEmpty()) {
            val tempo=if(deslocamento!=0.0) "(T+${d(deslocamento)})" else "T"
            val ax=Animacao.expressao(t,"x",tempo);val ay=Animacao.expressao(t,"y",tempo);val az=Animacao.expressao(t,"escala",tempo)
            prefixo="st(2,$ax);st(3,$ay);st(4,${d(t.escala)}/($az));st(5,($u-ld(2))*ld(4)+${d(t.x)});st(6,($v-ld(3))*ld(4)+${d(t.y)});"
            u="ld(5)";v="ld(6)"
        }
        fun termo(j: Int)="(${d(m[j])}*$u+${d(m[j+1])}*$v+${d(m[j+2])})"
        val animada=t.quadrosChave.isNotEmpty();val divisor=if(animada) "ld(7)" else termo(6)
        if(animada) prefixo+="st(7,${termo(6)});"
        val x="(W*${termo(0)}/$divisor-0.5)"; val y="(H*${termo(3)}/$divisor-0.5)"
        val cor=matrizDeCor(t); val canais=listOf("r","g","b")
        val posicao="${prefixo}st(0,$x);st(1,$y);"
        val rgb=canais.mapIndexed { i,c -> "$c='$posicao${canais.mapIndexedNotNull { j,fonte -> if(!animada||cor[i*4+j]!=0.0) "${d(cor[i*4+j])}*$fonte(ld(0),ld(1))" else null }.ifEmpty {listOf("0")}.joinToString("+")}+${d(cor[i*4+3])}'" }
        val mascara=when(t.mascara) {
            "circulo"->"lte(pow((ld(0)+0.5-W/2)/(min(W,H)/2),2)+pow((ld(1)+0.5-H/2)/(min(W,H)/2),2),1)"
            "oval"->"lte(pow((ld(0)+0.5-W/2)/(W/2),2)+pow((ld(1)+0.5-H/2)/(H/2),2),1)"
            "arredondada"->"lte(pow(max(abs(ld(0)+0.5-W/2)-(W/2-min(W,H)*0.15),0),2)+pow(max(abs(ld(1)+0.5-H/2)-(H/2-min(W,H)*0.15),0),2),pow(min(W,H)*0.15,2))"
            else->"1"
        }
        val alfa=if(t.mascara!="nenhuma") "*$mascara" else ""
        return "format=gbrap,geq=${rgb.joinToString(":")}:a='${posicao}if(between(ld(0),-0.5,W-0.5)*between(ld(1),-0.5,H-0.5),alpha(ld(0),ld(1))*${d(t.opacidade)}$alfa,0)':interpolation=bilinear,format=rgba"
    }
    fun sobrepor(fundo: String,frente: String,saida: String,mescla: String?,inicio: Double,fim: Double): String {
        val ativo="gte(t,${d(inicio)})*lt(t,${d(fim)})"
        if(mescla==null || mescla=="normal") return "[$fundo][$frente]overlay=0:0:format=auto:enable='$ativo'[$saida]"
        val modo=when(mescla){"multiplicar"->"multiply";"clarear"->"screen";else->"overlay"}
        val p=saida
        return "[$fundo]split=3[${p}b0][${p}b1][${p}b2];[$frente]split=3[${p}f0][${p}f1][${p}f2];"+
            "[${p}b0][${p}f0]blend=all_mode=$modo:shortest=0[${p}rgb];"+
            "[${p}b1]alphaextract,format=gbrp[${p}ba];[${p}f1]alphaextract[${p}fa];"+
            "[${p}f2][${p}rgb][${p}ba]maskedmerge[${p}mix];[${p}mix][${p}fa]alphamerge[${p}fg];"+
            "[${p}b2][${p}fg]overlay=0:0:format=auto:enable='$ativo'[$saida]"
    }
    fun fundo(fundo: FundoDoProjeto?,w: Int,h: Int,fps: Int,duracao: Double, otimizar: Boolean = false): String {
        val a=fundo?.cor?:"#000000"; val b=fundo?.corFinal?:a
        fun rgba(c: String)=listOf(0,2,4,6).map { i -> if(i==6&&c.length==7) 255 else c.substring(i+1,i+3).toInt(16) }
        val inicio=rgba(a); val fim=rgba(b)
        // LUT preserva os valores RGBA exatos (inclusive alpha), sem conversão RGB → YUV da cor.
        if (otimizar && inicio == fim) return "color=c=black:s=${w}x$h:r=$fps:d=${d(duracao)},format=rgba,lutrgb=r=${inicio[0]}:g=${inicio[1]}:b=${inicio[2]}:a=${inicio[3]}[cfundo]"
        val canais=listOf("r","g","b","a").mapIndexed { i,c -> "$c='${inicio[i]}+(${fim[i]-inicio[i]})*clip(((X+0.5)*W+(Y+0.5)*H)/(W*W+H*H),0,1)'" }
        return "color=c=black:s=${w}x$h:r=$fps:d=${d(duracao)},format=rgba,geq=${canais.joinToString(":")},format=rgba[cfundo]"
    }
}
