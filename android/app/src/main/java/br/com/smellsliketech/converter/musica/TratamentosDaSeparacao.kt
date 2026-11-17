package br.com.smellsliketech.converter.musica

import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Os tratamentos novos sobre WAV float32 em fluxo (a música não precisa caber na memória do
 * celular): refino dos agudos das faixas (Wiener do núcleo), bateria recuperada do resíduo e
 * o restaurador de áudio (diagnóstico, plano e reparo do núcleo). Mesmas regras do site e do
 * Windows.
 */
object TratamentosDaSeparacao {
    private const val BLOCO = 1 shl 15

    data class Restaurado(val plano: RestauradorDeAudio.Plano, val diagnostico: Map<String, Double>, val estatisticas: FloatArray?)

    /** Refina os agudos de faixas separadas (mesma duração, estéreo), trocando os arquivos. */
    fun refinarAgudos(faixas: List<File>, taxa: Int) {
        if (faixas.size < 2) return
        val temporarios = faixas.map { File(it.path + ".refinando") }
        val fontes = faixas.map { FonteWav(it) }
        try {
            val quadros = fontes.minOf { it.quadros }
            NucleoNativo.RefinadorDeAgudos(taxa, faixas.size).use { refinador ->
                val atraso = refinador.latencia
                val escritores = temporarios.map { EscritorDeWav(it, 2, taxa) }
                try {
                    val planar = NucleoNativo.plano(BLOCO * 2 * faixas.size)
                    val canal = FloatArray(BLOCO)
                    val esquerda = FloatArray(BLOCO)
                    val direita = FloatArray(BLOCO)
                    val total = quadros + atraso
                    var inicio = 0L
                    while (inicio < total) {
                        val n = minOf(BLOCO.toLong(), total - inicio).toInt()
                        val floats = planar.asFloatBuffer()
                        for (f in faixas.indices) for (c in 0..1) {
                            if (inicio < quadros) fontes[f].copiar(c, inicio, n, canal) else canal.fill(0f, 0, n)
                            floats.position((f * 2 + c) * n)
                            floats.put(canal, 0, n)
                        }
                        refinador.processar(planar, n)
                        val de = maxOf(0L, atraso - inicio).toInt()
                        val quantos = minOf((n - de).toLong(), quadros - (inicio + de - atraso)).toInt()
                        if (quantos > 0) for (f in faixas.indices) {
                            val saida = planar.asFloatBuffer()
                            saida.position((f * 2) * n + de); saida.get(esquerda, 0, quantos)
                            saida.position((f * 2 + 1) * n + de); saida.get(direita, 0, quantos)
                            escritores[f].escreverCanais(listOf(esquerda, direita), quantos)
                        }
                        inicio += n
                    }
                } finally {
                    escritores.forEach { it.close() }
                }
            }
        } finally {
            fontes.forEach { it.close() }
        }
        faixas.forEachIndexed { i, f -> check(temporarios[i].renameTo(f.also { it.delete() })) { "Não foi possível trocar a faixa refinada." } }
    }

    /** Soma a bateria recuperada à faixa da bateria e tira do resíduo. */
    fun juntarBateria(bateria: File, residuo: File, recuperada: File) {
        val novaBateria = File(bateria.path + ".nova")
        val novoResiduo = File(residuo.path + ".novo")
        FonteWav(bateria).use { b -> FonteWav(residuo).use { r -> FonteWav(recuperada).use { x ->
            EscritorDeWav(novaBateria, 2, SeparacaoDaMusica.TAXA).use { wb -> EscritorDeWav(novoResiduo, 2, SeparacaoDaMusica.TAXA).use { wr ->
                val bl = FloatArray(BLOCO); val br = FloatArray(BLOCO); val rl = FloatArray(BLOCO); val rr = FloatArray(BLOCO); val xl = FloatArray(BLOCO); val xr = FloatArray(BLOCO)
                val quadros = minOf(b.quadros, r.quadros)
                var i = 0L
                while (i < quadros) {
                    val n = minOf(BLOCO.toLong(), quadros - i).toInt()
                    b.copiar(0, i, n, bl); b.copiar(1, i, n, br); r.copiar(0, i, n, rl); r.copiar(1, i, n, rr); x.copiar(0, i, n, xl); x.copiar(1, i, n, xr)
                    for (k in 0 until n) { bl[k] += xl[k]; br[k] += xr[k]; rl[k] -= xl[k]; rr[k] -= xr[k] }
                    wb.escreverCanais(listOf(bl, br), n)
                    wr.escreverCanais(listOf(rl, rr), n)
                    i += n
                }
            } }
        } } }
        check(novaBateria.renameTo(bateria.also { it.delete() }) && novoResiduo.renameTo(residuo.also { it.delete() })) { "Não foi possível juntar a bateria recuperada." }
    }

    internal fun taxaDoWav(wav: File): Int {
        // readNBytes só existe a partir do Android 13: lê o cabeçalho com read.
        val h = wav.inputStream().use { entrada ->
            val b = ByteArray(4096)
            var n = 0
            while (n < b.size) { val r = entrada.read(b, n, b.size - n); if (r < 0) break; n += r }
            b.copyOf(n)
        }
        val b = java.nio.ByteBuffer.wrap(h).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        var p = 12
        while (p + 8 <= h.size) {
            val id = String(h, p, 4, Charsets.US_ASCII)
            val tamanho = b.getInt(p + 4)
            if (id == "fmt ") return b.getInt(p + 12)
            p += 8 + tamanho + (tamanho and 1)
        }
        error("WAV sem cabeçalho de formato.")
    }

    /** Diagnóstico → plano → reparo de um WAV float32, com a conferência antes de devolver. */
    /**
     * [antesDoReparo]: com o plano pedindo a IA (Apollo), devolve um WAV (mesma taxa, canais e
     * duração) que o reparo usa no lugar do original; recebe o plano e um progresso de 0 a 1.
     */
    suspend fun restaurar(wav: File, saida: File, opcoes: RestauradorDeAudio.Opcoes, nivelar: Boolean, trabalho: Job?,
                          progresso: (String, Double) -> Unit,
                          antesDoReparo: (suspend (RestauradorDeAudio.Plano, (Double) -> Unit) -> File)? = null): Restaurado {
        val taxa = taxaDoWav(wav)
        FonteWav(wav).use { fonte ->
            check(fonte.quadros > 0) { "O arquivo não tem áudio." }
            val estereo = fonte.canais > 1
            val e = NucleoNativo.plano(BLOCO)
            val d = NucleoNativo.plano(BLOCO)
            val esquerda = FloatArray(BLOCO)
            val direita = FloatArray(BLOCO)
            var energiaDeEntrada = 0.0
            var picoDeEntrada = 0.0
            val diagnostico: Map<String, Double>
            val perfil: FloatArray
            NucleoNativo.Analisador(taxa).use { analisador ->
                var i = 0L
                while (i < fonte.quadros) {
                    trabalho?.ensureActive()
                    val n = minOf(BLOCO.toLong(), fonte.quadros - i).toInt()
                    fonte.copiar(0, i, n, esquerda)
                    fonte.copiar(1, i, n, direita)
                    for (k in 0 until n) {
                        energiaDeEntrada += (esquerda[k] * esquerda[k] + direita[k] * direita[k]).toDouble()
                        picoDeEntrada = maxOf(picoDeEntrada, abs(esquerda[k]).toDouble(), abs(direita[k]).toDouble())
                    }
                    e.asFloatBuffer().put(esquerda, 0, n)
                    d.asFloatBuffer().put(direita, 0, n)
                    analisador.alimentar(n, e, if (estereo) d else null)
                    i += n
                    progresso("Analisando a qualidade", 0.25 * i / fonte.quadros)
                }
                diagnostico = analisador.resultado()
                perfil = analisador.perfil()
            }
            val plano = RestauradorDeAudio.planejar(diagnostico, opcoes)
            if (plano.semMudanca) {
                wav.copyTo(saida, overwrite = true)
                return Restaurado(plano, diagnostico, null)
            }
            val canais = if (estereo) 2 else 1
            var deReparo = 0.25
            val doApollo = if (plano.ia != null && antesDoReparo != null) {
                antesDoReparo(plano) { f -> progresso("Reparando a compressão (Apollo)", 0.25 + 0.6 * f) }.also { deReparo = 0.85 }
            } else null
            val origem = doApollo?.let { FonteWav(it) } ?: fonte
            try {
            doApollo?.let { check(origem.quadros == fonte.quadros) { "A duração do áudio restaurado pelo Apollo não confere." } }
            val parcial = File(saida.path + ".parcial")
            var energiaDeSaida = 0.0
            var picoDeSaida = 0.0
            val estatisticas: FloatArray
            NucleoNativo.Reparo(taxa, plano.parametros, perfil).use { reparo ->
                EscritorDeWav(parcial, canais, taxa).use { escritor ->
                    val atraso = reparo.latencia
                    val total = fonte.quadros + atraso
                    var inicio = 0L
                    while (inicio < total) {
                        trabalho?.ensureActive()
                        val n = minOf(BLOCO.toLong(), total - inicio).toInt()
                        if (inicio < fonte.quadros) { origem.copiar(0, inicio, n, esquerda); origem.copiar(1, inicio, n, direita) }
                        else { esquerda.fill(0f, 0, n); direita.fill(0f, 0, n) }
                        e.asFloatBuffer().put(esquerda, 0, n)
                        d.asFloatBuffer().put(direita, 0, n)
                        reparo.processar(n, e, if (estereo) d else null)
                        e.asFloatBuffer().get(esquerda, 0, n)
                        d.asFloatBuffer().get(direita, 0, n)
                        val de = maxOf(0L, atraso - inicio).toInt()
                        val quantos = minOf((n - de).toLong(), fonte.quadros - (inicio + de - atraso)).toInt()
                        if (quantos > 0) {
                            for (k in de until de + quantos) {
                                check(esquerda[k].isFinite() && (!estereo || direita[k].isFinite())) { "A restauração produziu amostras inválidas; o original não foi alterado." }
                                energiaDeSaida += (esquerda[k] * esquerda[k] + (if (estereo) direita[k] * direita[k] else esquerda[k] * esquerda[k])).toDouble()
                                picoDeSaida = maxOf(picoDeSaida, abs(esquerda[k]).toDouble(), if (estereo) abs(direita[k]).toDouble() else 0.0)
                            }
                            val l = esquerda.copyOfRange(de, de + quantos)
                            escritor.escreverCanais(if (estereo) listOf(l, direita.copyOfRange(de, de + quantos)) else listOf(l), quantos)
                        }
                        inicio += n
                        progresso("Restaurando", deReparo + (0.95 - deReparo) * minOf(1.0, inicio.toDouble() / total))
                    }
                }
                estatisticas = reparo.estatisticas()
            }
            // Conferência: mesma duração e som de verdade (a entrada não era silêncio).
            FonteWav(parcial).use { check(it.quadros == fonte.quadros) { "A duração do áudio restaurado não confere." } }
            check(!(picoDeEntrada > 1e-3 && picoDeSaida < 1e-3)) { "O áudio restaurado ficou em silêncio; o original não foi alterado." }
            val ganho = if (nivelar && energiaDeSaida > 0) sqrt(energiaDeEntrada / energiaDeSaida).coerceIn(0.5, 2.0) else 1.0
            if (abs(ganho - 1) < 1e-4) {
                check(parcial.renameTo(saida.also { it.delete() })) { "Não foi possível guardar o áudio restaurado." }
            } else {
                FonteWav(parcial).use { r ->
                    EscritorDeWav(saida, canais, taxa).use { final ->
                        var i = 0L
                        while (i < r.quadros) {
                            val n = minOf(BLOCO.toLong(), r.quadros - i).toInt()
                            r.copiar(0, i, n, esquerda); r.copiar(1, i, n, direita)
                            for (k in 0 until n) { esquerda[k] = (esquerda[k] * ganho).toFloat(); direita[k] = (direita[k] * ganho).toFloat() }
                            final.escreverCanais(if (estereo) listOf(esquerda, direita) else listOf(esquerda), n)
                            i += n
                        }
                    }
                }
                parcial.delete()
            }
            return Restaurado(plano, diagnostico, estatisticas)
            } finally {
                if (doApollo != null) (origem as FonteWav).close()
            }
        }
    }
}
