package br.com.smellsliketech.converter.musica

import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class Clique(val quadro: Long, val forte: Boolean)

data class PosicaoMusical(val compasso: Int, val batida: Int, val centesimos: Int, val texto: String)

data class PlanoDaGravacao(val inicioDoTransporte: Long, val inicioDaTomada: Long, val fimDaTomada: Long?, val cliquesDaContagem: List<Clique>, val preRolagem: Long)

/**
 * Regras da criação musical, espelho de packages/converter-core/musica.mjs: mesmas contas e
 * mensagens, conferidas pelos casos de musica-casos/ (MusicaCasosTest).
 */
object Musica {
    const val VERSAO = 1
    const val TAXA_PADRAO = 48000
    val TAXAS = listOf(44100, 48000, 96000)
    const val BLOCO = 128
    const val GANHO_MINIMO_DB = -60.0
    const val GANHO_MAXIMO_DB = 12.0
    const val AJUSTE_MINIMO_DB = -24.0
    const val AJUSTE_MAXIMO_DB = 12.0
    const val BPM_MINIMO = 30.0
    const val BPM_MAXIMO = 300.0
    val CORES = listOf("#FF7A18", "#1E88E5", "#43A047", "#E53935", "#8E24AA", "#FFD54A", "#00ACC1", "#F06292")
    val PROFUNDIDADES = listOf(16, 24, 32)

    data class Modelo(val id: String, val rotulo: String, val faixas: List<String>, val barramentos: List<String>)

    val MODELOS = listOf(
        Modelo("vazio", "Em branco", listOf("Faixa 1"), emptyList()),
        Modelo("violao-e-voz", "Violão e voz", listOf("Violão", "Voz"), listOf("Reverb")),
        Modelo("duo-vocal", "Duo vocal", listOf("Base", "Voz 1", "Voz 2"), listOf("Vozes", "Reverb")),
        Modelo("banda", "Banda", listOf("Bateria", "Baixo", "Guitarra", "Teclado", "Voz"), listOf("Guitarras", "Reverb", "Delay")),
        Modelo("podcast", "Podcast", listOf("Apresentação", "Convidado", "Trilha"), emptyList()),
    )

    private val ID = Regex("^[a-z0-9][a-z0-9-]{0,63}$")
    private val COR = Regex("^#[0-9A-Fa-f]{6}$")

    fun idValido(id: String?) = id != null && ID.matches(id)

    /** Math.round do JS: metade para cima. */
    fun arredondar(valor: Double): Long = floor(valor + 0.5).toLong()

    /** Número escrito como no JS (sem ".0" nos inteiros). */
    fun js(valor: Double): String = if (valor == floor(valor) && abs(valor) < 1e15) valor.toLong().toString() else valor.toString()

    fun dbParaLinear(db: Double): Double = if (!db.isFinite() || db <= GANHO_MINIMO_DB) 0.0 else 10.0.pow(db / 20)

    fun linearParaDb(ganho: Double): Double = if (ganho > 0) 20 * log10(ganho) else Double.NEGATIVE_INFINITY

    fun quadrosPorBatida(p: ProjetoMusical): Double = p.taxa * 60 / p.bpm * (4.0 / p.compasso[1])

    fun quadroDaBatida(p: ProjetoMusical, k: Long): Long = arredondar(k * quadrosPorBatida(p))

    fun quadrosPorCompasso(p: ProjetoMusical): Double = quadrosPorBatida(p) * p.compasso[0]

    fun posicao(p: ProjetoMusical, quadro: Long): PosicaoMusical {
        val porBatida = quadrosPorBatida(p)
        val batidas = max(0L, quadro) / porBatida
        val inteiras = floor(batidas + 1e-9).toLong()
        val compasso = (inteiras / p.compasso[0]).toInt() + 1
        val batida = (inteiras % p.compasso[0]).toInt() + 1
        val centesimos = min(99.0, floor((batidas - inteiras) * 100 + 1e-9)).toInt()
        return PosicaoMusical(compasso, batida, centesimos, "$compasso.$batida.${max(0, centesimos).toString().padStart(2, '0')}")
    }

    fun tempo(p: ProjetoMusical, quadro: Long): String {
        val ms = arredondar(max(0L, quadro).toDouble() / p.taxa * 1000)
        return "${ms / 60000}:${(ms % 60000 / 1000).toString().padStart(2, '0')}.${(ms % 1000).toString().padStart(3, '0')}"
    }

    fun cliquesDoMetronomo(p: ProjetoMusical, inicio: Long, fim: Long): List<Clique> {
        val porBatida = quadrosPorBatida(p)
        val cliques = mutableListOf<Clique>()
        var k = max(0L, ceil(inicio / porBatida - 1e-9).toLong())
        while (true) {
            val quadro = quadroDaBatida(p, k)
            if (quadro >= fim) break
            if (quadro >= inicio) cliques += Clique(quadro, k % p.compasso[0] == 0L)
            k++
        }
        return cliques
    }

    fun novaFaixa(id: String, nome: String, indice: Int = 0) = FaixaMusical(id = id, nome = nome, cor = CORES[indice % CORES.size])

    fun novoProjeto(id: String, titulo: String = "Nova música", taxa: Int = TAXA_PADRAO) = ProjetoMusical(id = id, titulo = titulo, taxa = taxa)

    private fun idDoNome(prefixo: String, nome: String, usados: MutableSet<String>): String {
        val semAcento = Normalizer.normalize(nome, Normalizer.Form.NFD).filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-' || it.isWhitespace() }
        val slug = semAcento.trim().lowercase().replace(Regex("[\\s_]+"), "-")
        val base = "$prefixo-$slug".take(40)
        var id = base
        var n = 2
        while (id in usados) id = "$base-${n++}"
        usados += id
        return id
    }

    fun projetoDoModelo(modeloId: String, id: String, titulo: String, taxa: Int = TAXA_PADRAO): ProjetoMusical {
        val modelo = MODELOS.firstOrNull { it.id == modeloId } ?: MODELOS[0]
        val usados = mutableSetOf<String>()
        val barramentos = modelo.barramentos.map { nome ->
            val efeitos = when (nome) {
                "Reverb" -> listOf(EfeitoMusical("fx-reverb", "reverberacao", true, mapOf("tamanho" to 0.7, "amortecimento" to 0.4, "largura" to 1.0, "mistura" to 1.0)))
                "Delay" -> listOf(EfeitoMusical("fx-delay", "atraso", true, mapOf("tempoMs" to 375.0, "retorno" to 0.35, "mistura" to 1.0)))
                else -> emptyList()
            }
            Barramento(idDoNome("bus", nome, usados), nome, efeitos = efeitos)
        }
        val faixas = modelo.faixas.mapIndexed { i, nome ->
            var faixa = novaFaixa(idDoNome("faixa", nome, usados), nome, i)
            barramentos.firstOrNull { it.nome == "Reverb" }?.let { if (Regex("voz|viol", RegexOption.IGNORE_CASE).containsMatchIn(nome)) faixa = faixa.copy(envios = listOf(Envio(it.id, -12.0))) }
            barramentos.firstOrNull { it.nome == "Vozes" }?.let { if (Regex("voz", RegexOption.IGNORE_CASE).containsMatchIn(nome)) faixa = faixa.copy(saida = it.id) }
            barramentos.firstOrNull { it.nome == "Guitarras" }?.let { if (Regex("guitarra", RegexOption.IGNORE_CASE).containsMatchIn(nome)) faixa = faixa.copy(saida = it.id) }
            faixa
        }
        return novoProjeto(id, titulo, taxa).copy(faixas = faixas, barramentos = barramentos)
    }

    /** Com algum solo, só as faixas em solo; o mudo sempre vence. */
    fun faixasAudiveis(p: ProjetoMusical): List<String> {
        val algumSolo = p.faixas.any { it.solo }
        return p.faixas.filter { !it.mudo && (!algumSolo || it.solo) }.map { it.id }
    }

    /** Fader, pan de potência constante sem reforço e ajuste L/R. Devolve (esquerdo, direito). */
    fun ganhosEstereo(ganhoDb: Double, pan: Double, ajusteEsquerdoDb: Double = 0.0, ajusteDireitoDb: Double = 0.0): Pair<Double, Double> {
        val ganho = dbParaLinear(ganhoDb)
        val angulo = (pan.coerceIn(-100.0, 100.0) + 100) / 200 * (Math.PI / 2)
        val esquerdo = min(1.0, sqrt(2.0) * cos(angulo))
        val direito = min(1.0, sqrt(2.0) * sin(angulo))
        return Pair(
            ganho * esquerdo * (if (ajusteEsquerdoDb == 0.0) 1.0 else 10.0.pow(ajusteEsquerdoDb / 20)),
            ganho * direito * (if (ajusteDireitoDb == 0.0) 1.0 else 10.0.pow(ajusteDireitoDb / 20)),
        )
    }

    fun valorDaAutomacao(pontos: List<PontoDeAutomacao>, quadro: Long, padrao: Double): Double {
        if (pontos.isEmpty()) return padrao
        if (quadro <= pontos[0].quadro) return pontos[0].valor
        val ultimo = pontos.last()
        if (quadro >= ultimo.quadro) return ultimo.valor
        for (i in 1 until pontos.size) {
            val b = pontos[i]
            if (quadro <= b.quadro) {
                val a = pontos[i - 1]
                if (b.quadro == a.quadro) return b.valor
                return a.valor + (b.valor - a.valor) * (quadro - a.quadro) / (b.quadro - a.quadro).toDouble()
            }
        }
        return ultimo.valor
    }

    fun duracaoEmQuadros(p: ProjetoMusical): Long = p.faixas.flatMap { it.clipes }.maxOfOrNull { it.inicio + it.duracao } ?: 0L

    fun envelopeDoClipe(c: ClipeMusical, deslocamento: Long): Double {
        var fator = if (c.ganhoDb == 0.0) 1.0 else dbParaLinear(c.ganhoDb)
        if (c.entradaSuave > 0 && deslocamento < c.entradaSuave) fator *= deslocamento / c.entradaSuave.toDouble()
        val restante = c.duracao - deslocamento
        if (c.saidaSuave > 0 && restante < c.saidaSuave) fator *= restante / c.saidaSuave.toDouble()
        return fator
    }

    fun planoDaGravacao(p: ProjetoMusical, cursor: Long): PlanoDaGravacao {
        val contagem = max(0.0, min(4.0, p.contagem.toLong().toDouble())).toInt()
        val preRolagem = arredondar(contagem * quadrosPorCompasso(p))
        val punch = if (p.punch.ligado && p.punch.saida > p.punch.entrada) p.punch else null
        val inicioDaTomada = punch?.entrada ?: cursor
        val inicioDoTransporte = max(0L, (if (punch != null) min(cursor, punch.entrada) else cursor) - preRolagem)
        val cliques = mutableListOf<Clique>()
        if (contagem > 0) {
            val porBatida = quadrosPorBatida(p)
            val batidas = contagem * p.compasso[0]
            for (k in 0 until batidas) {
                val quadro = inicioDaTomada - arredondar((batidas - k) * porBatida)
                if (quadro >= inicioDoTransporte) cliques += Clique(quadro, k % p.compasso[0] == 0)
            }
        }
        return PlanoDaGravacao(inicioDoTransporte, inicioDaTomada, punch?.saida, cliques, preRolagem)
    }

    fun inicioCompensado(quadroAlinhado: Long, latencia: Latencia): Long =
        quadroAlinhado - arredondar(latencia.entrada) - arredondar(latencia.saida) + arredondar(latencia.ajuste ?: 0.0)

    fun clipeDaTomada(p: ProjetoMusical, id: String, midia: String, quadroAlinhado: Long, quadrosCapturados: Long, plano: PlanoDaGravacao): ClipeMusical? {
        val posicao = inicioCompensado(quadroAlinhado, p.latencia)
        var inicioNaMidia = 0L
        var inicio = posicao
        val corte = max(plano.inicioDaTomada, 0L)
        if (inicio < corte) {
            inicioNaMidia = corte - inicio
            inicio = corte
        }
        var fim = posicao + quadrosCapturados
        if (plano.fimDaTomada != null) fim = min(fim, plano.fimDaTomada)
        val duracao = fim - inicio
        if (duracao <= 0) return null
        return ClipeMusical(id, midia, inicio, inicioNaMidia, duracao)
    }

    /** A tomada nova entra; as que ela cobre ficam mudas (nunca apagadas). */
    fun adicionarTomada(f: FaixaMusical, clipe: ClipeMusical): FaixaMusical {
        val fim = clipe.inicio + clipe.duracao
        val clipes = f.clipes.map { if (!it.mudo && it.inicio < fim && it.inicio + it.duracao > clipe.inicio) it.copy(mudo = true) else it }
        return f.copy(clipes = clipes + clipe)
    }

    fun medirLatencia(gravado: FloatArray, pulsos: List<Long>, taxa: Int): Long? {
        var pico = 0.0
        for (v in gravado) pico = max(pico, abs(v.toDouble()))
        if (pico < 1e-3) return null
        val limiar = pico * 0.5
        val janela = arredondar(taxa * 0.25)
        val atrasos = mutableListOf<Long>()
        for (pulso in pulsos) {
            var i = max(0L, pulso)
            while (i < min(gravado.size.toLong(), pulso + janela)) {
                if (abs(gravado[i.toInt()].toDouble()) >= limiar) {
                    atrasos += i - pulso
                    break
                }
                i++
            }
        }
        if (atrasos.size < ceil(pulsos.size / 2.0).toInt()) return null
        atrasos.sort()
        return atrasos[atrasos.size / 2]
    }

    private fun problemasDosEfeitos(efeitos: List<EfeitoMusical>, onde: String, erros: MutableList<String>) {
        val ids = mutableSetOf<String>()
        for (efeito in efeitos) {
            if (!idValido(efeito.id) || !ids.add(efeito.id)) erros += "$onde: efeito sem id válido."
            DspDaMusica.validar(efeito)?.let { erros += "$onde: $it" }
        }
    }

    fun problemas(p: ProjetoMusical): List<String> {
        val erros = mutableListOf<String>()
        if (p.versao != VERSAO) erros += "Versão ${p.versao} do projeto musical não suportada."
        if (!idValido(p.id)) erros += "O projeto precisa de um id."
        if (p.taxa !in TAXAS) erros += "Taxa de amostragem não suportada (44,1, 48 ou 96 kHz)."
        if (p.profundidade !in PROFUNDIDADES) erros += "Profundidade de saída inválida (16, 24 ou 32 bits)."
        if (!p.bpm.isFinite() || p.bpm < BPM_MINIMO || p.bpm > BPM_MAXIMO) erros += "O andamento vai de ${js(BPM_MINIMO)} a ${js(BPM_MAXIMO)} BPM."
        if (p.compasso.size != 2 || p.compasso[0] < 1 || p.compasso[0] > 16 || p.compasso[1] !in listOf(2, 4, 8)) erros += "Fórmula de compasso inválida."
        val midias = mutableMapOf<String, MidiaMusical>()
        for (m in p.midias) {
            if (!idValido(m.id) || m.id in midias) erros += "Mídia sem id válido." else midias[m.id] = m
            if (m.quadros < 0) erros += "Mídia ${m.id}: duração inválida."
            if (m.canais != 1 && m.canais != 2) erros += "Mídia ${m.id}: só mono ou estéreo."
            if (m.taxa != p.taxa) erros += "Mídia ${m.id}: precisa estar na taxa do projeto."
        }
        val barramentos = mutableSetOf<String>()
        for (b in p.barramentos) {
            if (!idValido(b.id) || b.id in barramentos || b.id == "master") erros += "Barramento sem id válido." else barramentos += b.id
            if (!b.ganhoDb.isFinite() || b.ganhoDb > GANHO_MAXIMO_DB) erros += "Barramento ${b.nome}: ganho inválido."
            if (!b.pan.isFinite() || abs(b.pan) > 100) erros += "Barramento ${b.nome}: pan vai de −100 a +100."
            problemasDosEfeitos(b.efeitos, "Barramento ${b.nome}", erros)
        }
        val faixas = mutableSetOf<String>()
        for (f in p.faixas) {
            val nome = f.nome
            if (!idValido(f.id) || f.id in faixas) erros += "Faixa sem id válido." else faixas += f.id
            if (f.cor != null && !COR.matches(f.cor)) erros += "Faixa $nome: cor inválida."
            if (!f.ganhoDb.isFinite() || f.ganhoDb > GANHO_MAXIMO_DB) erros += "Faixa $nome: o fader vai até +${js(GANHO_MAXIMO_DB)} dB."
            if (!f.pan.isFinite() || abs(f.pan) > 100) erros += "Faixa $nome: pan vai de −100 a +100."
            for (ajuste in listOf(f.ajusteEsquerdoDb, f.ajusteDireitoDb))
                if (!ajuste.isFinite() || ajuste < AJUSTE_MINIMO_DB || ajuste > AJUSTE_MAXIMO_DB) erros += "Faixa $nome: ajuste L/R vai de ${js(AJUSTE_MINIMO_DB)} a +${js(AJUSTE_MAXIMO_DB)} dB."
            if (f.saida != "master" && f.saida !in barramentos) erros += "Faixa $nome: saída para um barramento que não existe."
            for (e in f.envios) {
                if (e.barramento !in barramentos) erros += "Faixa $nome: envio para um barramento que não existe."
                if (!e.nivelDb.isFinite() || e.nivelDb > GANHO_MAXIMO_DB) erros += "Faixa $nome: nível do envio inválido."
            }
            problemasDosEfeitos(f.efeitos, "Faixa $nome", erros)
            for ((tipo, pontos) in listOf("volume" to f.automacao.volume, "pan" to f.automacao.pan)) {
                var anterior = -1L
                for (ponto in pontos) {
                    if (ponto.quadro < anterior || !ponto.valor.isFinite()) {
                        erros += "Faixa $nome: automação de $tipo fora de ordem."
                        break
                    }
                    anterior = ponto.quadro
                }
            }
            val clipes = mutableSetOf<String>()
            for (c in f.clipes) {
                if (!idValido(c.id) || !clipes.add(c.id)) erros += "Faixa $nome: clipe sem id válido."
                val m = midias[c.midia]
                if (m == null) {
                    erros += "Faixa $nome: o clipe ${c.id} usa uma mídia que não está no projeto."
                    continue
                }
                if (listOf(c.inicio, c.inicioNaMidia, c.duracao, c.entradaSuave, c.saidaSuave).any { it < 0 } || c.duracao == 0L)
                    erros += "Faixa $nome: o clipe ${c.id} tem posição ou duração inválida."
                else if (c.inicioNaMidia + c.duracao > m.quadros) erros += "Faixa $nome: o clipe ${c.id} passa do fim da mídia."
                else if (c.entradaSuave + c.saidaSuave > c.duracao) erros += "Faixa $nome: as rampas do clipe ${c.id} são maiores que ele."
                if (!c.ganhoDb.isFinite() || c.ganhoDb > 24) erros += "Faixa $nome: ganho do clipe ${c.id} inválido."
            }
        }
        if (!p.master.ganhoDb.isFinite() || p.master.ganhoDb > GANHO_MAXIMO_DB) erros += "Ganho do master inválido."
        problemasDosEfeitos(p.master.efeitos, "Master", erros)
        if (p.loop.ligado && !(p.loop.fim > p.loop.inicio && p.loop.inicio >= 0)) erros += "O loop precisa terminar depois de começar."
        if (p.punch.ligado && !(p.punch.saida > p.punch.entrada && p.punch.entrada >= 0)) erros += "O punch precisa sair depois de entrar."
        return erros
    }

    // ==================== ajuste fino (espelho de musica.mjs) ====================

    /** Passos dos botões − e + dos controles da mistura. */
    const val PASSO_GANHO_DB = 0.5
    const val PASSO_PAN = 1.0
    const val PASSO_AJUSTE_DB = 0.5
    const val PASSO_ENVIO_DB = 0.5
    const val PASSO_METRONOMO_DB = 1.0
    const val PASSO_SUAVE_SEGUNDOS = 0.01

    data class Passo(val passo: Double, val relativo: Boolean)

    /** Passo do − e + de um parâmetro de efeito (mesma regra de `passoDoParametro`). */
    fun passoDoParametro(min: Double, max: Double, unidade: String): Passo {
        val faixa = max - min
        if (unidade == "dB") return Passo(0.5, false)
        if (unidade == "Hz") return Passo(0.02, true)
        if (unidade == "ms") return Passo(if (faixa <= 100) 0.5 else if (faixa <= 1000) 1.0 else 10.0, false)
        val bruto = faixa / 100
        val potencia = Math.pow(10.0, kotlin.math.floor(kotlin.math.log10(bruto)))
        val m = bruto / potencia
        return Passo((if (m < 1.5) 1.0 else if (m < 3.5) 2.0 else if (m < 7.5) 5.0 else 10.0) * potencia, false)
    }

    /** Um toque no − (−1) ou no + (1), encaixado na grade do passo e dentro de [de, ate]. */
    fun ajusteFino(valor: Double, direcao: Int, passo: Double, de: Double, ate: Double, relativo: Boolean = false): Double {
        var novo = if (relativo) {
            val n = arredondar(valor * Math.pow(1 + passo, direcao.toDouble())).toDouble()
            if (n == arredondar(valor).toDouble()) arredondar(valor).toDouble() + direcao else n
        } else arredondar((valor + direcao * passo) / passo) * passo
        novo = arredondar(novo * 1e6) / 1e6 + 0.0
        return novo.coerceIn(de, ate)
    }

    /** Nome da faixa criada por uma separação: o que ela é primeiro (voz_, bateria_...). */
    fun nomeDaFaixaSeparada(rotulo: String, nomeDaFaixa: String) = "${rotulo.lowercase()}_$nomeDaFaixa"
}
