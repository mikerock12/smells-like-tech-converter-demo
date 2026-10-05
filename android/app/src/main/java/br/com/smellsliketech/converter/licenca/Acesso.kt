package br.com.smellsliketech.converter.licenca

import android.content.Context
import br.com.smellsliketech.converter.BuildConfig
import java.time.Duration
import java.time.Instant

/**
 * Quem pode converter: 7 dias de teste completo desde a primeira abertura, e depois só com
 * a licença do Android (ou a "Tudo, para sempre") ou com a compra feita pela Google Play.
 * A chave e o recibo ficam guardados no próprio celular e são conferidos a cada abertura,
 * sem internet.
 */
class Acesso(
    private val context: Context,
    /** A chave pública de licenciamento do Play Console; vazia na versão do site. */
    private val chaveDaPlay: String = BuildConfig.CHAVE_DA_PLAY,
) {
    private val preferencias = context.getSharedPreferences("licenca", Context.MODE_PRIVATE)

    sealed interface Situacao {
        val podeConverter: Boolean
        data class Teste(val diasRestantes: Int) : Situacao { override val podeConverter = true }
        data class Ativa(val carga: Licenca.Carga) : Situacao { override val podeConverter = true }
        data class Comprada(val recibo: ReciboDaPlay.Recibo) : Situacao { override val podeConverter = true }
        data object TesteAcabou : Situacao { override val podeConverter = false }
    }

    fun situacao(agora: Instant = Instant.now()): Situacao {
        val guardada = preferencias.getString(CHAVE, null)
        if (guardada != null) {
            val resultado = Licenca.verificar(guardada)
            if (resultado is Licenca.Resultado.Valida && resultado.carga.liberaAndroid && !resultado.carga.vencida(agora)) {
                return Situacao.Ativa(resultado.carga)
            }
        }
        compraGuardada()?.let { return Situacao.Comprada(it) }
        val inicio = preferencias.getLong(PRIMEIRA_ABERTURA, 0L).takeIf { it > 0 }
            ?: agora.toEpochMilli().also { preferencias.edit().putLong(PRIMEIRA_ABERTURA, it).apply() }
        val usados = Duration.between(Instant.ofEpochMilli(inicio), agora).toDays().toInt()
        val restantes = DIAS_DE_TESTE - usados
        return if (restantes > 0) Situacao.Teste(restantes) else Situacao.TesteAcabou
    }

    sealed interface Ativacao {
        data class Ativada(val carga: Licenca.Carga) : Ativacao
        data class Recusada(val motivo: String) : Ativacao
    }

    fun ativar(texto: String): Ativacao {
        return when (val resultado = Licenca.verificar(texto)) {
            is Licenca.Resultado.Invalida -> Ativacao.Recusada(
                if (resultado.motivo == Licenca.Motivo.FORMATO) "Isso não parece uma chave. Ela começa com SLT1. e tem três partes separadas por ponto."
                else "Essa chave não foi emitida por nós, ou foi alterada. Confira se copiou inteira.",
            )
            is Licenca.Resultado.Valida -> when {
                !resultado.carga.liberaAndroid -> Ativacao.Recusada(
                    "Esta chave é de outro produto (${resultado.carga.plano}) e não libera o app para Android.",
                )
                resultado.carga.vencida() -> Ativacao.Recusada("Esta chave venceu.")
                else -> {
                    preferencias.edit().putString(CHAVE, texto.trim().replace(Regex("\\s+"), "")).apply()
                    Ativacao.Ativada(resultado.carga)
                }
            }
        }
    }

    fun remover() {
        preferencias.edit().remove(CHAVE).apply()
    }

    /** Guarda a compra da Google Play se o recibo conferir. Nulo para recibo que não confere. */
    fun guardarCompra(json: String, assinatura: String): ReciboDaPlay.Recibo? {
        val recibo = ReciboDaPlay.conferir(json, assinatura, chaveDaPlay, context.packageName) ?: return null
        preferencias.edit().putString(RECIBO, json).putString(ASSINATURA_DO_RECIBO, assinatura).apply()
        return recibo
    }

    /** A Google Play diz que esta conta não tem a compra (estornada, ou nunca feita). */
    fun esquecerCompra() {
        preferencias.edit().remove(RECIBO).remove(ASSINATURA_DO_RECIBO).apply()
    }

    private fun compraGuardada(): ReciboDaPlay.Recibo? {
        val json = preferencias.getString(RECIBO, null) ?: return null
        val assinatura = preferencias.getString(ASSINATURA_DO_RECIBO, null) ?: return null
        return ReciboDaPlay.conferir(json, assinatura, chaveDaPlay, context.packageName)
    }

    companion object {
        const val DIAS_DE_TESTE = 7
        private const val CHAVE = "chave"
        private const val RECIBO = "recibo_da_play"
        private const val ASSINATURA_DO_RECIBO = "assinatura_do_recibo_da_play"
        private const val PRIMEIRA_ABERTURA = "primeira_abertura"
    }
}
