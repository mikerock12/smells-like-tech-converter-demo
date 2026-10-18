package br.com.smellsliketech.converter.figurinhas

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * A ponte com o WhatsApp: saber se está instalado (o normal e o Business), se o pacote já foi
 * adicionado, e pedir para ele mostrar a tela "Adicionar ao WhatsApp".
 */
object WhatsApp {
    const val NORMAL = "com.whatsapp"
    const val BUSINESS = "com.whatsapp.w4b"
    private const val ACAO_ADICIONAR = "com.whatsapp.intent.action.ENABLE_STICKER_PACK"

    fun instalados(contexto: Context): List<String> = listOf(NORMAL, BUSINESS).filter { pacote ->
        runCatching { contexto.packageManager.getPackageInfo(pacote, 0) }.isSuccess
    }

    /**
     * O próprio WhatsApp responde se o pacote já está nele. Quando não dá para perguntar
     * (versão antiga, sem o provedor), assume que não está.
     */
    fun jaAdicionado(contexto: Context, whatsapp: String, pacote: PacoteDeFigurinhas): Boolean {
        val consulta = Uri.Builder().scheme("content")
            .authority("$whatsapp.provider.sticker_whitelist_check")
            .appendPath("is_whitelisted")
            .appendQueryParameter("authority", ProvedorDeFigurinhas.autoridade(contexto.packageName))
            .appendQueryParameter("identifier", pacote.id)
            .build()
        return runCatching {
            contexto.contentResolver.query(consulta, null, null, null, null)?.use { cursor ->
                cursor.moveToFirst() && cursor.getInt(cursor.getColumnIndexOrThrow("result")) == 1
            } ?: false
        }.getOrDefault(false)
    }

    /** A intenção que abre "Adicionar ao WhatsApp" para este pacote no app escolhido. */
    fun intencaoDeAdicionar(contexto: Context, whatsapp: String, pacote: PacoteDeFigurinhas): Intent =
        Intent(ACAO_ADICIONAR)
            .setPackage(whatsapp)
            .putExtra("sticker_pack_id", pacote.id)
            .putExtra("sticker_pack_authority", ProvedorDeFigurinhas.autoridade(contexto.packageName))
            .putExtra("sticker_pack_name", pacote.nome)

    /** Avisa o WhatsApp de que o pacote mudou (figurinha nova, removida, nome). */
    fun avisarMudanca(contexto: Context) {
        val base = Uri.Builder().scheme("content").authority(ProvedorDeFigurinhas.autoridade(contexto.packageName)).build()
        contexto.contentResolver.notifyChange(base, null)
    }
}
