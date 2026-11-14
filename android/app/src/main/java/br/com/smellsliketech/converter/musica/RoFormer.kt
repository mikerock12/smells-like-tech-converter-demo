package br.com.smellsliketech.converter.musica

import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlin.math.min

internal object RoFormer {
    const val ID = "mvsep-mega53-v1-onnx-int8-5s"
    const val ARQUIVO = "mega53-espectral-int8-5s-atencao64.onnx"
    const val BYTES = 96842472L
    const val SHA256 = "c7c3e822ac3ffe49c16f2824ad5af75211e2d780bdbf20c40b86d2792646381f"
    val disponivel get() = android.os.Process.is64Bit() && NucleoNativo.temStftComplexa
    val modo = ModoDeSeparacao("6", "Voz, bateria, baixo, guitarra, piano e resíduo", listOf(
        "vozes" to "Voz", "bateria" to "Bateria", "baixo" to "Baixo", "guitarra" to "Guitarra",
        "piano" to "Piano", "outros" to "Outros (resíduo)",
    ).map { (id, nome) -> FaixaDoModo(id, nome, listOf(id)) })

    private const val TAMANHO = 220500
    private const val PASSO = TAMANHO / 2
    private const val TRANSICAO = TAMANHO / 10
    private const val SAIDAS = 5

    suspend fun separar(fonte: FonteWav, sessao: InferenciaOnnx, saidas: List<EscritorDeWav>, progresso: (Double) -> Unit) {
        require(fonte.quadros > 0) { "O áudio está vazio." }
        val esquerda = NucleoNativo.plano(TAMANHO)
        val direita = NucleoNativo.plano(TAMANHO)
        val pcm = List(2) { FloatArray(TAMANHO) }
        val original = FloatArray(TAMANHO)
        val reconstruido = List(2) { FloatArray(TAMANHO) }
        val paraEscrever = List(2) { FloatArray(PASSO) }
        val somas = List(SAIDAS) { List(2) { FloatArray(TAMANHO) } }
        val pesos = FloatArray(TAMANHO)
        val pesoDoBloco = FloatArray(TAMANHO)
        val posicoes = LongArray(TAMANHO)
        val margem = min(PASSO.toLong(), fonte.quadros - 1)
        val fim = fonte.quadros + margem
        var inicio = -margem
        NucleoNativo.StftComplexa(2048, 512, TAMANHO).use { stft ->
            val espectro = NucleoNativo.plano(stft.tamanho)
            val mascara = NucleoNativo.plano(stft.tamanho)
            val entrada = FloatArray(stft.tamanho)
            val forma = longArrayOf(1, 431, 4100)
            while (inicio < fim) {
                coroutineContext.ensureActive()
                val validos = min(TAMANHO.toLong(), fim - inicio).toInt()
                var menor = fonte.quadros - 1
                var maior = 0L
                for (i in 0 until validos) {
                    var pos = inicio + i
                    if (pos < 0) pos = -pos
                    if (pos >= fonte.quadros) pos = 2 * (fonte.quadros - 1) - pos
                    posicoes[i] = pos
                    menor = minOf(menor, pos)
                    maior = maxOf(maior, pos)
                    val entradaPeso = if (inicio > -margem) i.toFloat() / (TRANSICAO - 1) else 1f
                    val saidaPeso = if (inicio + validos < fim) (TAMANHO - 1 - i).toFloat() / (TRANSICAO - 1) else 1f
                    pesoDoBloco[i] = minOf(1f, entradaPeso, saidaPeso)
                    pesos[i] += pesoDoBloco[i]
                }
                for (c in 0..1) {
                    fonte.copiar(min(c, fonte.canais - 1), menor, (maior - menor + 1).toInt(), original)
                    for (i in 0 until validos) pcm[c][i] = original[(posicoes[i] - menor).toInt()]
                    if (validos > TAMANHO - validos) {
                        for (i in validos until TAMANHO) pcm[c][i] = pcm[c][2 * (validos - 1) - i]
                    } else pcm[c].fill(0f, validos)
                }
                esquerda.asFloatBuffer().put(pcm[0])
                direita.asFloatBuffer().put(pcm[1])
                stft.analisar(esquerda, direita, espectro)
                espectro.asFloatBuffer().get(entrada)
                val mascaras = sessao.executar(entrada, forma, "espectro", "mascaras")
                check(mascaras.size == SAIDAS * stft.tamanho) { "O formato de saída do RoFormer não confere." }
                for (f in 0 until SAIDAS) {
                    coroutineContext.ensureActive()
                    mascara.asFloatBuffer().put(mascaras, f * stft.tamanho, stft.tamanho)
                    stft.sintetizar(espectro, mascara, esquerda, direita, zerarDc = true)
                    esquerda.asFloatBuffer().get(reconstruido[0])
                    direita.asFloatBuffer().get(reconstruido[1])
                    for (c in 0..1) for (i in 0 until validos) somas[f][c][i] += reconstruido[c][i] * pesoDoBloco[i]
                }
                val primeiro = maxOf(0L, -inicio).toInt()
                val ultimo = (fonte.quadros - inicio).coerceIn(0L, PASSO.toLong()).toInt()
                if (ultimo > primeiro) {
                    for (c in 0..1) for (i in primeiro until ultimo) {
                        var residuo = pcm[c][i]
                        for (f in 0 until SAIDAS) {
                            val valor = somas[f][c][i] / pesos[i]
                            check(valor.isFinite()) { "O RoFormer devolveu uma amostra inválida." }
                            somas[f][c][i] = valor
                            residuo -= valor
                        }
                        reconstruido[c][i] = residuo
                    }
                    for (f in saidas.indices) {
                        val canais = if (f < SAIDAS) somas[f] else reconstruido
                        for (c in 0..1) canais[c].copyInto(paraEscrever[c], 0, primeiro, ultimo)
                        saidas[f].escreverCanais(paraEscrever, ultimo - primeiro)
                    }
                }
                for (buffer in somas.flatten() + listOf(pesos)) {
                    buffer.copyInto(buffer, 0, PASSO)
                    buffer.fill(0f, PASSO)
                }
                inicio += PASSO
                progresso((inicio.toDouble() / fonte.quadros).coerceIn(0.0, 1.0))
            }
        }
    }
}
