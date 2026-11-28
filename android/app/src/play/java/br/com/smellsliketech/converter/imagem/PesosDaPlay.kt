package br.com.smellsliketech.converter.imagem

import android.content.Context
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import com.google.android.play.core.assetpacks.model.AssetPackStatus
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

internal suspend fun pesosDaPlay(context: Context): ByteArray {
    val nome = "ampliador_slti"
    val gerente = AssetPackManagerFactory.getInstance(context)
    fun arquivo() = gerente.getPackLocation(nome)?.assetsPath()?.let { File(it, "ampliador-2x.json") }
    arquivo()?.let { return it.readBytes() }
    gerente.fetch(listOf(nome)).await()
    return withTimeoutOrNull(120_000) {
        var local = arquivo()
        while (local == null) {
            val estado = gerente.getPackStates(listOf(nome)).await().packStates()[nome]?.status()
            check(estado != AssetPackStatus.FAILED && estado != AssetPackStatus.CANCELED &&
                estado != AssetPackStatus.WAITING_FOR_WIFI && estado != AssetPackStatus.REQUIRES_USER_CONFIRMATION) {
                "A Google Play não concluiu a instalação do ampliador. Confira o Wi-Fi e a Play Store."
            }
            delay(500)
            local = arquivo()
        }
        local.readBytes()
    } ?: error("A instalação do ampliador pela Google Play ainda não terminou. Tente novamente.")
}
