package br.com.smellsliketech.converter.musica

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Fonte de áudio de uma mídia, lida em pedaços (memória, arquivo mapeado...). */
interface FonteDeAudio {
    val canais: Int
    val quadros: Long
    /** Copia n quadros do canal a partir de inicio; o que passar do fim vira zero. */
    fun copiar(canal: Int, inicio: Long, n: Int, destino: FloatArray)
}

class FonteNaMemoria(private val dados: Array<FloatArray>) : FonteDeAudio {
    override val canais get() = dados.size
    override val quadros get() = if (dados.isEmpty()) 0L else dados[0].size.toLong()
    override fun copiar(canal: Int, inicio: Long, n: Int, destino: FloatArray) {
        val origem = dados[min(canal, dados.size - 1)]
        var i = 0
        var j = inicio
        while (j < min(origem.size.toLong(), inicio + n)) destino[i++] = origem[(j++).toInt()]
        while (i < n) destino[i++] = 0f
    }
}

class OpcoesDoMisturador(
    val entradaEsq: FloatArray? = null,
    val entradaDir: FloatArray? = null,
    val somente: String? = null,
    val semMaster: Boolean = false,
    val metronomo: Boolean = false,
)

/**
 * O mixer da criação musical, espelho de musica-mixer.mjs. Toca em tempo real (AudioTrack) e
 * exporta; depois de [definirProjeto], [processar] não aloca. Quem chama troca o projeto entre
 * dois blocos (o motor faz isso na própria thread de áudio).
 */
class MisturadorDaMusica(private val taxa: Int, private val bloco: Int = Musica.BLOCO) {
    private val fontes = HashMap<String, FonteDeAudio>()
    private val cadeias = HashMap<String, CadeiaMusical>()
    private val barramentos = HashMap<String, Array<FloatArray>>()
    private val te = FloatArray(bloco); private val td = FloatArray(bloco)
    private val le = FloatArray(bloco); private val ld = FloatArray(bloco)
    private val me = FloatArray(bloco); private val md = FloatArray(bloco)
    private val cliqueForte = somDoClique(taxa, true)
    private val cliqueFraco = somDoClique(taxa, false)
    private var audiveis: Set<String> = emptySet()
    private var projeto: ProjetoMusical? = null
    private var cliquesDaContagem: List<Clique> = emptyList()
    private var fimDaContagem = -1L

    /** Pico L, pico R, RMS L, RMS R do último processar, por faixa, barramento e "master". */
    val medidores = HashMap<String, FloatArray>()

    fun definirFonte(midia: String, fonte: FonteDeAudio?) {
        if (fonte == null) fontes.remove(midia) else fontes[midia] = fonte
    }

    fun definirContagem(cliques: List<Clique>, fim: Long) {
        cliquesDaContagem = cliques
        fimDaContagem = fim
    }

    fun definirProjeto(p: ProjetoMusical) {
        projeto = p
        audiveis = Musica.faixasAudiveis(p).toHashSet()
        val vivas = hashSetOf("master")
        fun cadeia(id: String, efeitos: List<EfeitoMusical>) {
            vivas += id
            cadeias.getOrPut(id) { CadeiaMusical(taxa) }.sincronizar(efeitos)
            medidores.getOrPut(id) { FloatArray(4) }
        }
        for (f in p.faixas) cadeia(f.id, f.efeitos)
        for (b in p.barramentos) {
            cadeia(b.id, b.efeitos)
            barramentos.getOrPut(b.id) { arrayOf(FloatArray(bloco), FloatArray(bloco)) }
        }
        cadeia("master", p.master.efeitos)
        cadeias.keys.retainAll(vivas)
        medidores.keys.retainAll(vivas)
        barramentos.keys.retainAll(p.barramentos.map { it.id }.toSet())
    }

    fun processar(inicio: Long, n: Int, saidaEsq: FloatArray, saidaDir: FloatArray, opcoes: OpcoesDoMisturador = PADRAO) {
        for (m in medidores.values) m.fill(0f)
        var feito = 0
        while (feito < n) {
            val k = min(bloco, n - feito)
            blocoDe(inicio + feito, k, opcoes, feito)
            System.arraycopy(me, 0, saidaEsq, feito, k)
            System.arraycopy(md, 0, saidaDir, feito, k)
            feito += k
        }
        for (m in medidores.values) {
            m[2] = sqrt(m[2] / max(1, n).toDouble()).toFloat()
            m[3] = sqrt(m[3] / max(1, n).toDouble()).toFloat()
        }
    }

    private fun medir(id: String, e: FloatArray, d: FloatArray, n: Int) {
        val m = medidores[id] ?: return
        var s2 = m[2].toDouble(); var s3 = m[3].toDouble()
        for (i in 0 until n) {
            val a = abs(e[i]); val b = abs(d[i])
            if (a > m[0]) m[0] = a
            if (b > m[1]) m[1] = b
            s2 += e[i].toDouble() * e[i]; s3 += d[i].toDouble() * d[i]
        }
        m[2] = s2.toFloat(); m[3] = s3.toFloat()
    }

    private fun blocoDe(t0: Long, n: Int, opcoes: OpcoesDoMisturador, deslocamentoDaEntrada: Int) {
        val p = projeto ?: return
        me.fill(0f, 0, n); md.fill(0f, 0, n)
        for (b in barramentos.values) { b[0].fill(0f, 0, n); b[1].fill(0f, 0, n) }
        for (faixa in p.faixas) {
            val audivel = if (opcoes.somente != null) faixa.id == opcoes.somente else faixa.id in audiveis
            if (!audivel) continue
            te.fill(0f, 0, n); td.fill(0f, 0, n)
            for (clipe in faixa.clipes) {
                if (clipe.mudo) continue
                val a = max(t0, clipe.inicio)
                val b = min(t0 + n, clipe.inicio + clipe.duracao)
                if (a >= b) continue
                val transformada = fontes[TransformacaoDaMusica.chaveDaFonte(clipe)]
                val fonte = transformada ?: fontes[clipe.midia] ?: continue
                val deslocamento = a - clipe.inicio
                val k = (b - a).toInt()
                val origem = if (transformada != null) floor(clipe.inicioNaMidia / (clipe.velocidade ?: 1.0)).toLong() + deslocamento else clipe.inicioNaMidia + deslocamento
                fonte.copiar(0, origem, k, le)
                if (fonte.canais > 1) fonte.copiar(1, origem, k, ld) else System.arraycopy(le, 0, ld, 0, k)
                val destino = (a - t0).toInt()
                for (i in 0 until k) {
                    val f = Musica.envelopeDoClipe(clipe, deslocamento + i)
                    te[destino + i] = (te[destino + i] + le[i] * f).toFloat()
                    td[destino + i] = (td[destino + i] + ld[i] * f).toFloat()
                }
            }
            val ee = opcoes.entradaEsq
            val ed = opcoes.entradaDir
            if (ee != null && ed != null && faixa.gravar && faixa.monitorar) {
                for (i in 0 until n) {
                    te[i] = (te[i].toDouble() + ee[deslocamentoDaEntrada + i]).toFloat()
                    td[i] = (td[i].toDouble() + ed[deslocamentoDaEntrada + i]).toFloat()
                }
            }
            cadeias[faixa.id]?.processar(te, td, n)
            for (envio in faixa.envios) {
                if (!envio.preFader) continue
                val bus = barramentos[envio.barramento] ?: continue
                val g = Musica.dbParaLinear(envio.nivelDb)
                for (i in 0 until n) { bus[0][i] = (bus[0][i] + te[i] * g).toFloat(); bus[1][i] = (bus[1][i] + td[i] * g).toFloat() }
            }
            val volume = faixa.automacao.volume
            val pan = faixa.automacao.pan
            val (e0, d0) = Musica.ganhosEstereo(Musica.valorDaAutomacao(volume, t0, faixa.ganhoDb), Musica.valorDaAutomacao(pan, t0, faixa.pan), faixa.ajusteEsquerdoDb, faixa.ajusteDireitoDb)
            val (e1, d1) = Musica.ganhosEstereo(Musica.valorDaAutomacao(volume, t0 + n, faixa.ganhoDb), Musica.valorDaAutomacao(pan, t0 + n, faixa.pan), faixa.ajusteEsquerdoDb, faixa.ajusteDireitoDb)
            for (i in 0 until n) {
                val w = i / n.toDouble()
                te[i] = (te[i] * (e0 + (e1 - e0) * w)).toFloat()
                td[i] = (td[i] * (d0 + (d1 - d0) * w)).toFloat()
            }
            for (envio in faixa.envios) {
                if (envio.preFader) continue
                val bus = barramentos[envio.barramento] ?: continue
                val g = Musica.dbParaLinear(envio.nivelDb)
                for (i in 0 until n) { bus[0][i] = (bus[0][i] + te[i] * g).toFloat(); bus[1][i] = (bus[1][i] + td[i] * g).toFloat() }
            }
            val saida = if (faixa.saida == "master") null else barramentos[faixa.saida]
            val de = saida?.get(0) ?: me
            val dd = saida?.get(1) ?: md
            for (i in 0 until n) { de[i] = (de[i].toDouble() + te[i]).toFloat(); dd[i] = (dd[i].toDouble() + td[i]).toFloat() }
            medir(faixa.id, te, td, n)
        }
        for (bus in p.barramentos) {
            val buffers = barramentos[bus.id] ?: continue
            if (bus.mudo) continue
            val e = buffers[0]; val d = buffers[1]
            cadeias[bus.id]?.processar(e, d, n)
            val (ge, gd) = Musica.ganhosEstereo(bus.ganhoDb, bus.pan)
            for (i in 0 until n) {
                e[i] = (e[i] * ge).toFloat(); d[i] = (d[i] * gd).toFloat()
                me[i] = (me[i].toDouble() + e[i]).toFloat(); md[i] = (md[i].toDouble() + d[i]).toFloat()
            }
            medir(bus.id, e, d, n)
        }
        if (!opcoes.semMaster) cadeias["master"]?.processar(me, md, n)
        val g = Musica.dbParaLinear(p.master.ganhoDb)
        for (i in 0 until n) { me[i] = (me[i] * g).toFloat(); md[i] = (md[i] * g).toFloat() }
        medir("master", me, md, n)
        if (opcoes.metronomo || fimDaContagem > t0) metronomo(p, t0, n, opcoes.metronomo)
    }

    private fun somar(som: FloatArray, quadroDoClique: Long, t0: Long, n: Int, volume: Double) {
        val a = max(t0, quadroDoClique)
        val b = min(t0 + n, quadroDoClique + som.size)
        var t = a
        while (t < b) {
            val v = som[(t - quadroDoClique).toInt()] * volume
            val i = (t - t0).toInt()
            me[i] = (me[i] + v).toFloat(); md[i] = (md[i] + v).toFloat()
            t++
        }
    }

    private fun metronomo(p: ProjetoMusical, t0: Long, n: Int, grade: Boolean) {
        val volume = Musica.dbParaLinear(p.metronomo.volumeDb)
        val comprimento = cliqueForte.size
        for (clique in cliquesDaContagem) {
            if (clique.quadro + comprimento > t0 && clique.quadro < t0 + n) somar(if (clique.forte) cliqueForte else cliqueFraco, clique.quadro, t0, n, volume)
        }
        if (!grade) return
        val porBatida = Musica.quadrosPorBatida(p)
        val batidas = p.compasso[0]
        var k = max(0L, floor((t0 - comprimento) / porBatida).toLong())
        while (true) {
            val quadro = Musica.arredondar(k * porBatida)
            if (quadro >= t0 + n) break
            if (quadro >= fimDaContagem && quadro + comprimento > t0) somar(if (k % batidas == 0L) cliqueForte else cliqueFraco, quadro, t0, n, volume)
            k++
        }
    }

    companion object {
        private val PADRAO = OpcoesDoMisturador()

        fun somDoClique(taxa: Int, forte: Boolean): FloatArray {
            val n = Musica.arredondar(taxa * 0.03).toInt()
            val f = if (forte) 2000 else 1500
            return FloatArray(n) { i -> (sin(2 * Math.PI * f * i / taxa) * exp(-i / (taxa * 0.006)) * (if (forte) 1.0 else 0.7)).toFloat() }
        }

        fun caudaEmQuadros(p: ProjetoMusical, taxa: Int = p.taxa): Long {
            var segundos = 0.0
            fun olhar(efeitos: List<EfeitoMusical>) { for (e in efeitos) segundos = max(segundos, DspDaMusica.cauda(e)) }
            for (f in p.faixas) olhar(f.efeitos)
            for (b in p.barramentos) olhar(b.efeitos)
            olhar(p.master.efeitos)
            return Musica.arredondar(min(10.0, segundos) * taxa)
        }

        /** Exporta um trecho inteiro em memória. */
        fun renderizar(p: ProjetoMusical, fontes: Map<String, FonteDeAudio>, inicio: Long, quadros: Int, somente: String? = null, semMaster: Boolean = false, metronomo: Boolean = false): Pair<FloatArray, FloatArray> {
            val m = MisturadorDaMusica(p.taxa)
            for ((id, fonte) in fontes) m.definirFonte(id, fonte)
            m.definirProjeto(p)
            val esq = FloatArray(quadros); val dir = FloatArray(quadros)
            val passo = 4096
            val be = FloatArray(passo); val bd = FloatArray(passo)
            val opcoes = OpcoesDoMisturador(somente = somente, semMaster = semMaster, metronomo = metronomo)
            var feito = 0
            while (feito < quadros) {
                val k = min(passo, quadros - feito)
                m.processar(inicio + feito, k, be, bd, opcoes)
                System.arraycopy(be, 0, esq, feito, k); System.arraycopy(bd, 0, dir, feito, k)
                feito += k
            }
            return Pair(esq, dir)
        }

        /** Exporta o mesmo trecho por blocos, mantendo o estado dos efeitos entre eles. */
        internal fun renderizarEmWav(p: ProjetoMusical, fontes: Map<String, FonteDeAudio>, inicio: Long, quadros: Long,
            somente: String?, destino: EscritorDeWav, verificar: () -> Unit) {
            val mixer = MisturadorDaMusica(p.taxa)
            fontes.forEach { (id, fonte) -> mixer.definirFonte(id, fonte) }
            mixer.definirProjeto(p)
            val esq = FloatArray(4096)
            val dir = FloatArray(4096)
            val intercalado = FloatArray(8192)
            val opcoes = OpcoesDoMisturador(somente = somente, metronomo = p.metronomo.naExportacao)
            var feitos = 0L
            while (feitos < quadros) {
                verificar()
                val n = min(esq.size.toLong(), quadros - feitos).toInt()
                mixer.processar(inicio + feitos, n, esq, dir, opcoes)
                for (i in 0 until n) { intercalado[i * 2] = esq[i]; intercalado[i * 2 + 1] = dir[i] }
                destino.escrever(intercalado, n)
                feitos += n
            }
        }
    }
}
