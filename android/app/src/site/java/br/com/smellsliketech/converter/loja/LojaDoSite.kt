package br.com.smellsliketech.converter.loja

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri

/** O APK do site: a licença é comprada no site e a chave, colada no app. */
fun novaLoja(@Suppress("UNUSED_PARAMETER") contexto: Context, @Suppress("UNUSED_PARAMETER") ouvinte: Loja.Ouvinte): Loja = LojaDoSite

private object LojaDoSite : Loja {
    private const val PRECOS = "https://converter.smellsliketech.com.br/precos?origem=android"

    override suspend fun preco(): String? = null

    override fun comprar(atividade: Activity) {
        runCatching { atividade.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PRECOS))) }
    }

    override suspend fun sincronizar() = Unit

    override fun encerrar() = Unit
}
