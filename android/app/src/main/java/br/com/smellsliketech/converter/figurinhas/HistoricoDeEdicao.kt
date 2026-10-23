package br.com.smellsliketech.converter.figurinhas

/** Guarda estados imutáveis, com limite de memória e agrupamento de arrastes. */
class HistoricoDeEdicao<T>(private val peso: (T) -> Long = { 1L }, private val limite: Long = 32L * 1024 * 1024) {
    private val anteriores = ArrayDeque<T>()
    private val seguintes = ArrayDeque<T>()
    private var agrupado = false
    private var registrado = false
    private var ultimaChave: String? = null
    private var ultimoTempo = Long.MIN_VALUE
    val podeDesfazer get() = anteriores.isNotEmpty()
    val podeRefazer get() = seguintes.isNotEmpty()

    fun iniciar() { agrupado = true; registrado = false; ultimaChave = null }
    fun terminar() { agrupado = false; registrado = false; ultimaChave = null }
    fun registrar(atual: T, chave: String = "", tempo: Long = System.currentTimeMillis()) {
        if (agrupado && registrado) return
        if (!agrupado && chave.isNotEmpty() && chave == ultimaChave && tempo - ultimoTempo in 0..450) {
            ultimoTempo = tempo
            return
        }
        anteriores.addLast(atual)
        seguintes.clear()
        while (anteriores.size > 1 && (anteriores.size > 30 || anteriores.sumOf(peso) > limite)) anteriores.removeFirst()
        registrado = true; ultimaChave = chave; ultimoTempo = tempo
    }
    fun desfazer(atual: T): T? {
        terminar()
        if (anteriores.isEmpty()) return null
        seguintes.addLast(atual)
        return anteriores.removeLast()
    }
    fun refazer(atual: T): T? {
        terminar()
        if (seguintes.isEmpty()) return null
        anteriores.addLast(atual)
        return seguintes.removeLast()
    }
    fun limpar() { anteriores.clear(); seguintes.clear(); terminar() }
}
