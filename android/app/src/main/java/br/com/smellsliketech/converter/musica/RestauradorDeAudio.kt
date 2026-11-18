package br.com.smellsliketech.converter.musica

import org.json.JSONException
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Restaurar áudio com IA (experimental): o diagnóstico do núcleo nativo vira etapas e
 * parâmetros do reparo. Espelho de packages/converter-core/restaurador.mjs, conferido pelos
 * casos de restaurador-casos.json. Conservador: sem defeito suficiente, nada muda; o que a
 * gravação perdeu não volta com filtro; modelos de IA ainda em revisão de licença não entram.
 */
object RestauradorDeAudio {
    const val VERSAO = 3

    /** As oitavas do diagnóstico e da equalização e a referência tonal (dB ÷ 1 kHz), como no JS. */
    val OITAVAS = doubleArrayOf(31.5, 63.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0)
    private val SUFIXOS = listOf("31", "63", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")
    val REFERENCIA_TONAL = doubleArrayOf(3.0, 5.0, 4.5, 2.5, 1.0, 0.0, -2.0, -4.5, -7.5, -13.0)

    data class Perfil(val id: String, val rotulo: String, val resumo: String)
    val PERFIS = listOf(
        Perfil("auto", "Automático", "Analisa e trata só o que encontrar, com cuidado."),
        Perfil("lossy", "MP3 / música comprimida", "Com a IA ligada, o Apollo refaz o que a compressão destruiu; sem ela, só a limpeza leve."),
        Perfil("live", "Show ao vivo / gravação de fã", "Gravação esmagada (ataque, dinâmica e timbre), ruído, zumbido e recorte, preservando a plateia."),
        Perfil("archive", "Fita, vinil ou gravação antiga", "Zumbido, estalos, chiado e um pouco de brilho."),
        Perfil("manual", "Avançado (eu escolho)", "Você liga e dosa cada etapa."),
    )
    val ETAPAS = listOf(
        "compressao" to "Reparo de compressão por IA (Apollo)", "dc" to "Deslocamento DC", "zumbido" to "Zumbido da rede (50/60 Hz)", "estalos" to "Estalos e cliques",
        "recorte" to "Recorte (distorção por volume alto)", "ruido" to "Chiado e ruído de fundo",
        "grave" to "Ronco e vento (corte de grave)", "brilho" to "Brilho (agudos)",
        "transientes" to "Ataque e dinâmica (gravação esmagada)", "timbre" to "Equilíbrio tonal (graves, médios e agudos)",
    )
    val FORMATOS = listOf("wav-24", "flac", "wav-32f", "mp3")

    data class Opcoes(val perfil: String = "auto", val intensidade: Int = 35, val preservarAmbiencia: Int = 80,
                      val formato: String = "wav-24", val etapas: Map<String, Int> = ETAPAS.associate { it.first to 0 }, val relatorio: Boolean = false,
                      val ia: Boolean = false, val manterVolume: Boolean = true) {
        fun paraJson(): JSONObject = JSONObject().put("perfil", perfil).put("intensidade", intensidade).put("preservarAmbiencia", preservarAmbiencia)
            .put("formato", formato).put("etapas", JSONObject(etapas)).put("relatorio", relatorio).put("ia", ia).put("manterVolume", manterVolume)
    }

    /** O reparo de compressão por IA (Apollo) e quanto dele entra na mistura. */
    data class EtapaDeIa(val modelo: String, val mistura: Double, val corteHz: Double)

    data class Problema(val gravidade: Double, val confianca: Double, val hz: Double? = null)
    data class Avaliacao(val problemas: Map<String, Problema>, val recomendacoes: List<String>, val avisos: List<String>, val semDefeitos: Boolean)
    data class Plano(val perfil: String, val etapas: List<Pair<String, Double>>, val parametros: Map<String, Double>,
                     val avisos: List<String>, val semMudanca: Boolean, val avaliacao: Avaliacao, val ia: EtapaDeIa? = null)

    private fun limitar(v: Double, a: Double, b: Double) = min(b, maxOf(a, v))
    // Como o Math.round do JavaScript (metade para cima), para os três núcleos baterem.
    private fun arredondar(v: Double) = floor(v * 1e4 + 0.5) / 1e4
    private fun inteiro(v: Double) = floor(v + 0.5).toInt()

    private fun numero(o: JSONObject?, k: String, padrao: Double): Double {
        val v = o?.opt(k) ?: return padrao
        val d = when (v) { is Number -> v.toDouble(); is String -> v.toDoubleOrNull(); else -> null }
        return if (d != null && d.isFinite()) d else padrao
    }

    fun lerOpcoes(texto: String?): Opcoes {
        val o = try { if (texto.isNullOrBlank()) JSONObject() else JSONObject(texto) } catch (_: JSONException) {
            throw IllegalArgumentException("As opções da restauração estão corrompidas. Escolha de novo.")
        }
        val perfil = o.optString("perfil").takeIf { p -> PERFIS.any { it.id == p } } ?: "auto"
        val e = o.optJSONObject("etapas")
        val etapas = ETAPAS.associate { (id, _) -> id to limitar(inteiro(numero(e, id, 0.0)).toDouble(), if (id == "brilho") -100.0 else 0.0, 100.0).toInt() }
        val formato = o.optString("formato").takeIf { it in FORMATOS } ?: "wav-24"
        return Opcoes(perfil, limitar(inteiro(numero(o, "intensidade", 35.0)).toDouble(), 0.0, 100.0).toInt(),
            limitar(inteiro(numero(o, "preservarAmbiencia", 80.0)).toDouble(), 0.0, 100.0).toInt(), formato, etapas, o.opt("relatorio") == true, o.opt("ia") == true,
            o.opt("manterVolume") != false)
    }

    private fun Map<String, Double>.v(k: String) = this[k] ?: 0.0
    private fun Map<String, Double>.tem(k: String) = this[k]?.isFinite() == true

    private fun oitavaValida(d: Map<String, Double>, i: Int): Boolean {
        val k = "oitava${SUFIXOS[i]}"
        return d.tem(k) && d.v(k) > -59 && (!(d.v("bandaHz") > 0) || OITAVAS[i] <= 0.9 * d.v("bandaHz"))
    }

    private fun estabilidadeDosAgudos(d: Map<String, Double>): Double? {
        val bandas = listOf("estabilidade8k" to 12000.0, "estabilidade12k" to 16000.0)
            .filter { (k, topo) -> d.tem(k) && d.v(k) > -59 && (!(d.v("bandaHz") > 0) || topo <= d.v("bandaHz") * 1.05) }
        return if (bandas.isEmpty()) null else bandas.maxOf { d.v(it.first) }
    }

    private fun desvioTonal(d: Map<String, Double>): Double? {
        var soma = 0.0
        var n = 0
        for (i in 1..8) {
            if (!oitavaValida(d, i)) continue
            val x = d.v("oitava${SUFIXOS[i]}") - REFERENCIA_TONAL[i]
            soma += x * x
            n += 1
        }
        return if (n >= 5) sqrt(soma / n) else null
    }

    private fun equalizacao(d: Map<String, Double>, quanto: Double): DoubleArray {
        val bruto = DoubleArray(OITAVAS.size) { i ->
            if (!oitavaValida(d, i)) 0.0
            else {
                var alvo = REFERENCIA_TONAL[i] - d.v("oitava${SUFIXOS[i]}")
                if (i == 0) alvo = min(alvo, 3.0)
                limitar(alvo * 0.85 * quanto, -6.0, 6.0)
            }
        }
        return DoubleArray(bruto.size) { i ->
            var soma = bruto[i]
            var n = 1
            if (i > 0) { soma += bruto[i - 1]; n += 1 }
            if (i + 1 < bruto.size) { soma += bruto[i + 1]; n += 1 }
            arredondar(0.5 * bruto[i] + 0.5 * (soma / n))
        }
    }

    fun avaliar(d: Map<String, Double>): Avaliacao {
        val nyquist = d.v("taxa") / 2
        val recorte = if (d.v("recorte") > 1e-4) limitar(log10(d.v("recorte") / 1e-4) / 2, 0.0, 1.0) else 0.0
        val zumbido = limitar((d.v("zumbidoDb") - 6) / 14, 0.0, 1.0)
        val plano = limitar((d.v("planuraDoRuido") - 0.15) / 0.45, 0.0, 1.0)
        val ruido = limitar((d.v("pisoDeRuidoDb") + 70) / 30, 0.0, 1.0) * plano
        val estalos = limitar((d.v("estalosPorMinuto") - 3) / 40, 0.0, 1.0)
        val dc = limitar((abs(d.v("dc")) - 0.003) / 0.027, 0.0, 1.0)
        val banda = if (d.v("bandaHz") > 0 && d.v("bandaHz") < 0.85 * nyquist && d.v("bandaHz") < 17000) limitar((17000 - d.v("bandaHz")) / 7000, 0.0, 1.0) else 0.0
        // Esmagada por limitador (ABI 1.3): crista baixa e o platô (99,5º percentil) perto do RMS.
        val limitado = if (d.tem("limiteDb") && d.v("limiteDb") > -60)
            limitar((16 - d.v("cristaDb")) / 7, 0.0, 1.0) * limitar((14 - (d.v("limiteDb") - d.v("rmsDb"))) / 6, 0.0, 1.0) * limitar((15 - d.v("dinamicaDb")) / 6, 0.0, 1.0) else 0.0
        // Chiado mesmo sem pausas (ABI 1.4): a energia dos agudos quase constante ao longo do tempo.
        val estabilidade = estabilidadeDosAgudos(d)
        val chiado = if (estabilidade == null) 0.0 else limitar((estabilidade + 12) / 8, 0.0, 1.0)
        val desvio = desvioTonal(d)
        // Acima de 12 dB de desvio não é uma mixagem (voz sozinha, um tom, instrumento solo): não se equaliza.
        val timbre = if (desvio == null || desvio > 12) 0.0 else limitar((desvio - 4) / 3.5, 0.0, 1.0)
        val problemas = mapOf(
            "recorte" to Problema(arredondar(recorte), if (d.v("picoDb") > -1) 0.8 else 0.6),
            "zumbido" to Problema(arredondar(zumbido), 0.7, d.v("zumbidoHz")),
            "ruido" to Problema(arredondar(ruido), arredondar(0.4 + 0.4 * plano)),
            "estalos" to Problema(arredondar(estalos), 0.6),
            "dc" to Problema(arredondar(dc), 0.9),
            "banda" to Problema(arredondar(banda), 0.5, floor(d.v("bandaHz") + 0.5)),
            "limitado" to Problema(arredondar(limitado), 0.7),
            "chiado" to Problema(arredondar(chiado), 0.6),
            "timbre" to Problema(arredondar(timbre), 0.5, if (desvio == null) 0.0 else arredondar(desvio)),
        )
        val recomendacoes = listOf("recorte", "zumbido", "ruido", "chiado", "estalos", "dc", "limitado", "timbre").filter { problemas.getValue(it).gravidade >= 0.15 }
        val avisos = mutableListOf("o_diagnostico_nao_garante_a_origem")
        if (banda >= 0.15) avisos += "banda_limitada"
        if (d.v("silencio") > 0.9) avisos += "quase_silencio"
        return Avaliacao(problemas, recomendacoes, avisos, recomendacoes.isEmpty())
    }

    fun planejar(d: Map<String, Double>, opcoes: Opcoes): Plano {
        val avaliacao = avaliar(d)
        val p = linkedMapOf("dc" to 0.0, "zumbidoHz" to 0.0, "zumbido" to 0.0, "estalos" to 0.0, "recorte" to 0.0, "nivelDoRecorte" to 0.0,
            "ruido" to 0.0, "arDb" to 0.0, "corteGraveHz" to 0.0, "tetoDb" to 0.0, "ganhoDb" to 0.0, "entradaDb" to 0.0, "transientes" to 0.0, "ruidoPorMinimos" to 0.0, "ruidoDesdeHz" to 0.0)
        for (x in SUFIXOS) p["eq$x"] = 0.0
        val avisos = avaliacao.avisos.toMutableList()
        val s = opcoes.intensidade / 100.0
        fun g(id: String) = avaliacao.problemas.getValue(id).gravidade
        val nivel = min(1.0, 10.0.pow(d.v("picoDb") / 20)) * 0.995
        val hum = if (d.v("zumbidoHz") == 60.0) 60.0 else 50.0
        if (opcoes.perfil == "manual") {
            val e = opcoes.etapas
            if ((e["dc"] ?: 0) > 0) p["dc"] = 1.0
            if ((e["zumbido"] ?: 0) > 0) { p["zumbidoHz"] = hum; p["zumbido"] = (e["zumbido"] ?: 0) / 100.0 }
            p["estalos"] = (e["estalos"] ?: 0) / 100.0
            if ((e["recorte"] ?: 0) > 0) { p["recorte"] = (e["recorte"] ?: 0) / 100.0; p["nivelDoRecorte"] = nivel }
            p["ruido"] = (e["ruido"] ?: 0) / 100.0
            if ((e["grave"] ?: 0) > 0) p["corteGraveHz"] = 20 + 0.4 * (e["grave"] ?: 0)
            p["arDb"] = 6.0 * (e["brilho"] ?: 0) / 100
            p["transientes"] = (e["transientes"] ?: 0) / 100.0
            if ((e["timbre"] ?: 0) > 0) equalizacao(d, (e["timbre"] ?: 0) / 100.0).forEachIndexed { i, v -> p["eq${SUFIXOS[i]}"] = v }
        } else if (s > 0) {
            val perfil = opcoes.perfil
            fun dose(x: Double) = limitar(x * (0.4 + 1.2 * s), 0.0, 1.0)
            if (g("dc") > 0 || perfil == "archive") p["dc"] = 1.0
            if (g("zumbido") >= 0.15) { p["zumbidoHz"] = hum; p["zumbido"] = limitar(0.5 + 0.5 * g("zumbido"), 0.0, 1.0) * min(1.0, 0.5 + s) }
            if (g("estalos") >= 0.15) p["estalos"] = dose(g("estalos")) * (if (perfil == "archive") 1.0 else 0.8)
            if (g("recorte") >= 0.15) { p["recorte"] = limitar(dose(0.5 + 0.5 * g("recorte")), 0.0, 1.0); p["nivelDoRecorte"] = nivel }
            val ruidoMedido = maxOf(g("ruido"), g("chiado"))
            if (ruidoMedido >= 0.15) {
                var r = limitar(ruidoMedido * (0.3 + s), 0.0, 1.0)
                if (perfil == "live") r *= 1 - 0.7 * opcoes.preservarAmbiencia / 100.0
                if (perfil == "archive") r = min(1.0, r * 1.2)
                p["ruido"] = r
            }
            if (perfil == "live") p["corteGraveHz"] = 35.0
            if (perfil == "archive") { p["corteGraveHz"] = 30.0; p["arDb"] = 1.5 * s * (1 - g("chiado")) }
            if (perfil == "lossy" && !opcoes.ia) avisos += "reparo_de_compressao_por_ia_desligado"
            // Esmagada por limitador: o platô vira pico de novo e o ataque volta; ali os "estalos"
            // quase sempre são batidas de bateria, e o reparo deles cai.
            if (g("limitado") >= 0.15) {
                p["transientes"] = limitar(g("limitado") * (0.8 + 0.6 * s), 0.0, 1.0)
                p["recorte"] = maxOf(p.v("recorte"), p.v("transientes"))
                if (perfil != "archive") p["estalos"] = p.v("estalos") * 0.25
                avisos += "gravacao_esmagada"
            }
            if (g("timbre") >= 0.25) {
                val quanto = limitar(g("timbre") * (0.7 + 0.6 * s), 0.0, 1.0) * (if (perfil == "auto") 0.85 else 1.0)
                equalizacao(d, quanto).forEachIndexed { i, v -> p["eq${SUFIXOS[i]}"] = if (perfil == "archive" && i >= 7) min(v, 3.0) else v }
            }
        }
        if (p.v("ruido") > 0 && g("ruido") < 0.15 && g("chiado") >= 0.15) { p["ruidoPorMinimos"] = 1.0; p["ruidoDesdeHz"] = 2000.0 }
        // Margem antes de tudo: o pico abaixo de −1,5 dBFS e espaço para os picos que voltam.
        if (p.v("transientes") > 0 || d.v("picoDb") > -1.5) p["entradaDb"] = arredondar(-(maxOf(0.0, d.v("picoDb") + 1.5) + 9 * p.v("transientes")))
        if (p.v("transientes") > 0 && d.tem("limiteDb") && d.v("limiteDb") > -40) {
            p["recorte"] = maxOf(p.v("recorte"), p.v("transientes"))
            p["nivelDoRecorte"] = arredondar(10.0.pow((d.v("limiteDb") + p.v("entradaDb") - 2) / 20))
        } else if (p.v("recorte") > 0) {
            p["nivelDoRecorte"] = arredondar(nivel * 10.0.pow(p.v("entradaDb") / 20))
        }
        // Reparo de compressão por IA (Apollo): no Avançado, a etapa "compressao" dosa; nos outros
        // perfis, só com a IA ligada e no MP3 ou quando os agudos param cedo.
        val corte = (floor(((if (d.v("bandaHz") > 0) d.v("bandaHz") else 16000.0) - 3000) / 100 + 0.5) * 100).coerceIn(4000.0, 16000.0)
        val ia = when {
            opcoes.perfil == "manual" -> (opcoes.etapas["compressao"] ?: 0).takeIf { it > 0 }?.let { EtapaDeIa("apollo", arredondar(it / 100.0), corte) }
                ?: if (opcoes.ia) EtapaDeIa("apollo", 1.0, corte) else null
            opcoes.ia -> EtapaDeIa("apollo", arredondar(min(1.0, 0.4 + s)), corte)
            else -> null
        }
        for (k in p.keys.toList()) p[k] = arredondar(p.getValue(k))
        val ativos = buildList {
            if (ia != null) add("compressao" to ia.mistura)
            if (p.v("dc") > 0) add("dc" to 1.0)
            if (p.v("zumbido") > 0) add("zumbido" to p.v("zumbido"))
            if (p.v("estalos") > 0) add("estalos" to p.v("estalos"))
            if (p.v("recorte") > 0) add("recorte" to p.v("recorte"))
            if (p.v("ruido") > 0) add("ruido" to p.v("ruido"))
            if (p.v("corteGraveHz") > 0) add("grave" to p.v("corteGraveHz"))
            if (p.v("arDb") != 0.0) add("brilho" to p.v("arDb"))
            if (p.v("transientes") > 0) add("transientes" to p.v("transientes"))
            val eqMaior = SUFIXOS.maxOf { abs(p.v("eq$it")) }
            if (eqMaior >= 0.3) add("timbre" to arredondar(eqMaior))
        }
        val semMudanca = ativos.isEmpty()
        if (!semMudanca) p["tetoDb"] = -1.0 else p["entradaDb"] = 0.0
        if (semMudanca) avisos += if (opcoes.perfil == "manual" || s == 0.0) "nada_ligado" else "sem_defeitos_suficientes"
        return Plano(opcoes.perfil, ativos, p, avisos, semMudanca, avaliacao, ia)
    }

    private val TEXTOS = mapOf(
        "o_diagnostico_nao_garante_a_origem" to "O diagnóstico mede o arquivo; não garante como ele foi gravado ou comprimido.",
        "banda_limitada" to "Os agudos param cedo: o que a compressão ou a gravação cortou não volta com filtros.",
        "quase_silencio" to "O áudio é quase todo silêncio.",
        "reparo_de_compressao_por_ia_desligado" to "O reparo de compressão por IA (Apollo) está desligado: este perfil faz só a limpeza leve. Ligue a IA para refazer o que a compressão destruiu.",
        "gravacao_esmagada" to "A gravação foi esmagada por limitador: os picos e o ataque voltam em parte, e o volume final fica mais baixo e com mais dinâmica (é o esperado).",
        "sem_defeitos_suficientes" to "O diagnóstico não encontrou defeitos suficientes para recomendar uma restauração: o áudio sai como entrou.",
        "nada_ligado" to "Nenhuma etapa ligada: o áudio sai como entrou.",
    )

    fun textoDoAviso(id: String) = TEXTOS[id] ?: id
    fun rotuloDaEtapa(id: String) = ETAPAS.firstOrNull { it.first == id }?.second ?: id

    fun resumo(d: Map<String, Double>): String {
        val a = avaliar(d)
        fun forca(g: Double) = if (g >= 0.66) "forte" else if (g >= 0.33) "médio" else "leve"
        val nomes = mapOf("recorte" to "Recorte", "zumbido" to "Zumbido de ${d.v("zumbidoHz").toInt()} Hz", "ruido" to "Ruído de fundo", "chiado" to "Chiado nos agudos", "estalos" to "Estalos", "dc" to "Deslocamento DC",
            "limitado" to "Esmagada por limitador", "timbre" to "Timbre desequilibrado")
        val achados = a.recomendacoes.map { "${nomes.getValue(it)} (${forca(a.problemas.getValue(it).gravidade)})" }
        val banda = if (a.problemas.getValue("banda").gravidade >= 0.15) " · Agudos até ~${"%.1f".format(java.util.Locale.ROOT, d.v("bandaHz") / 1000)} kHz" else ""
        return "${achados.ifEmpty { listOf("Sem defeitos fortes") }.joinToString(" · ")}$banda · pico ${"%.1f".format(java.util.Locale.ROOT, d.v("picoDb"))} dBFS · piso de ruído ${d.v("pisoDeRuidoDb").toInt()} dB"
    }

    /** As medidas do resultado ao lado das da entrada (como resumoAntesEDepois do JS). */
    fun antesEDepois(antes: Map<String, Double>, depois: Map<String, Double>): String {
        fun f(v: Double) = if (v.isFinite()) "%.1f".format(java.util.Locale.ROOT, v) else "?"
        val partes = mutableListOf(
            "piso de ruído ${f(antes.v("pisoDeRuidoDb"))} → ${f(depois.v("pisoDeRuidoDb"))} dB",
            "crista ${f(antes.v("cristaDb"))} → ${f(depois.v("cristaDb"))} dB",
            "pico ${f(antes.v("picoDb"))} → ${f(depois.v("picoDb"))} dBFS",
        )
        // Quanto sobra nos agudos (onde mora o chiado): a oitava ÷ a de 1 kHz.
        for ((k, nome) in listOf("oitava8k" to "8 kHz", "oitava16k" to "16 kHz")) {
            if (antes.tem(k) && antes.v(k) > -59 && depois.tem(k) && depois.v(k) > -59) partes += "agudos em $nome ${f(antes.v(k))} → ${f(depois.v(k))} dB (÷ 1 kHz)"
        }
        return partes.joinToString(" · ")
    }

    fun nomeDoRestaurado(nome: String, extensao: String) = "${nome.substringBeforeLast('.').ifEmpty { "audio" }}-restaurado.$extensao"
}
