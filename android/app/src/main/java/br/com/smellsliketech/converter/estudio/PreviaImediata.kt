package br.com.smellsliketech.converter.estudio

/** Só habilita reprodução direta quando todos os efeitos podem ser mostrados pelo player.
 * A prévia renderizada continua disponível para as demais composições; não omitir efeitos.
 */
object PreviaImediata {
    fun aceita(p: Projeto): Boolean {
        val c = p.clipes.singleOrNull() ?: return false
        val t = c.transformacao ?: Transformacao()
        return c.tipo == "video" && c.ajuste in listOf("caber", "preencher") &&
            (c.volume ?: 1.0) in 0.0..1.0 &&
            p.camadas.none { it.visivel } && p.textos.isEmpty() && p.audios.isEmpty() &&
            !t.removerFundo && t.mescla == "normal" && !Estudio.temTransicoes(p)
    }

    fun tempoNaOrigem(c: Clipe, instante: Double): Double =
        (c.inicio ?: 0.0) + instante.coerceIn(0.0, Estudio.duracaoDoClipe(c)) * Estudio.velocidadeDoClipe(c)
}
