package br.com.smellsliketech.converter.estudio

import org.json.JSONObject

data class QuadroChave(val tempo: Double, val x: Double, val y: Double, val escala: Double, val curva: String = "linear") {
    fun paraJson() = JSONObject().put("tempo", tempo).put("x", x).put("y", y).put("escala", escala).put("curva", curva)
    companion object {
        fun deJson(j: JSONObject) = QuadroChave(j.getDouble("tempo"), j.getDouble("x"), j.getDouble("y"), j.getDouble("escala"), j.optString("curva", "linear"))
    }
}

object Animacao {
    fun problema(t: Transformacao): String? {
        if(!t.tempoDaAnimacao.isFinite()||t.tempoDaAnimacao<0||t.tempoDaAnimacao>86400) return "Tempo da animação inválido."
        if (t.mascara !in listOf("nenhuma", "circulo", "oval", "arredondada")) return "Máscara desconhecida."
        if (t.quadrosChave.size > 100) return "Use até 100 quadros-chave por camada."
        var anterior = -1.0
        for (q in t.quadrosChave) {
            if (!q.tempo.isFinite() || q.tempo < 0 || q.tempo <= anterior || !q.x.isFinite() || q.x !in -2.0..3.0 ||
                !q.y.isFinite() || q.y !in -2.0..3.0 || !q.escala.isFinite() || q.escala !in .05..6.0 || q.curva !in listOf("linear", "suave"))
                return "Quadros-chave inválidos: use tempos crescentes e posição/zoom dentro dos limites."
            anterior = q.tempo
        }
        return null
    }
    fun noTempo(t: Transformacao, instante: Double): QuadroChave {
        val tempo=instante+t.tempoDaAnimacao
        val qs = t.quadrosChave
        if (qs.isEmpty()) return QuadroChave(tempo, t.x, t.y, t.escala)
        if (tempo <= qs.first().tempo) return qs.first()
        val i = qs.indexOfFirst { it.tempo > tempo }
        if (i < 0) return qs.last()
        val a = qs[i-1]; val b = qs[i]
        var p = (tempo-a.tempo)/(b.tempo-a.tempo)
        if (a.curva == "suave") p = p*p*(3-2*p)
        return QuadroChave(tempo, a.x+(b.x-a.x)*p, a.y+(b.y-a.y)*p, a.escala+(b.escala-a.escala)*p)
    }
    fun expressao(t: Transformacao, campo: String, instante: String = "T"): String {
        val tempo=if(t.tempoDaAnimacao!=0.0) "($instante+${FiltrosDeCamada.d(t.tempoDaAnimacao)})" else instante
        fun n(v: Double) = FiltrosDeCamada.d(v)
        fun valor(q: QuadroChave) = when(campo) { "x" -> q.x; "y" -> q.y; else -> q.escala }
        if(t.quadrosChave.isEmpty()) return n(when(campo) { "x" -> t.x; "y" -> t.y; else -> t.escala })
        val qs = t.quadrosChave
        var fim = n(valor(qs.last()))
        for(i in qs.size-2 downTo 0) {
            val a=qs[i]; val b=qs[i+1]
            val p="clip(($tempo-${n(a.tempo)})/${n(b.tempo-a.tempo)},0,1)"
            val curva=if(a.curva=="suave") "($p*$p*(3-2*$p))" else p
            fim="if(lt($tempo,${n(b.tempo)}),${n(valor(a))}+(${n(valor(b)-valor(a))})*$curva,$fim)"
        }
        return fim
    }
    fun mudarBase(antes: Transformacao,depois: Transformacao): Transformacao {
        if(antes.quadrosChave.isEmpty()) return depois
        val dx=depois.x-antes.x;val dy=depois.y-antes.y;val z=depois.escala/antes.escala
        if(dx==0.0&&dy==0.0&&z==1.0) return depois
        return depois.copy(quadrosChave=antes.quadrosChave.map {it.copy(x=(it.x+dx).coerceIn(-2.0,3.0),y=(it.y+dy).coerceIn(-2.0,3.0),escala=(it.escala*z).coerceIn(.05,6.0))})
    }
    fun kenBurns(t: Transformacao = Transformacao(), duracao: Double = 3.0, afastar: Boolean = false): Transformacao {
        val a=QuadroChave(0.0,t.x,t.y,t.escala,"suave")
        val b=a.copy(tempo=maxOf(.1,duracao),escala=minOf(6.0,t.escala*1.2))
        return t.copy(tempoDaAnimacao=0.0,quadrosChave=if(afastar) listOf(b.copy(tempo=0.0),a.copy(tempo=b.tempo)) else listOf(a,b))
    }
}
