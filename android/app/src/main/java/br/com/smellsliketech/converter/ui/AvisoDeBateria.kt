package br.com.smellsliketech.converter.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A otimização de bateria do Android (e, com mais força, a de Xiaomi/POCO/Redmi, Samsung,
 * Oppo e Vivo) pode congelar o app em segundo plano mesmo com o serviço em primeiro plano e
 * a trava de CPU: a conversão para na mesma porcentagem e só volta quando o app vem à frente.
 * O Converter não pede a permissão de ignorar a otimização (a Google Play restringe); ele
 * explica e abre o ajuste certo, e a pessoa escolhe "Sem restrições".
 */
object Bateria {
    private const val PREFERENCIAS = "segundo-plano"
    private const val DISPENSADO = "aviso-bateria-dispensado"

    fun restrita(contexto: Context): Boolean {
        val energia = contexto.getSystemService(PowerManager::class.java) ?: return false
        return !energia.isIgnoringBatteryOptimizations(contexto.packageName)
    }

    fun dispensado(contexto: Context) =
        contexto.getSharedPreferences(PREFERENCIAS, Context.MODE_PRIVATE).getBoolean(DISPENSADO, false)

    fun dispensar(contexto: Context) =
        contexto.getSharedPreferences(PREFERENCIAS, Context.MODE_PRIVATE).edit().putBoolean(DISPENSADO, true).apply()

    val xiaomi get() = Build.MANUFACTURER.equals("Xiaomi", true) || Build.BRAND.lowercase() in setOf("xiaomi", "redmi", "poco")

    /** Abre o ajuste de bateria do app (o da Xiaomi direto, quando existe), ou os detalhes do app. */
    fun abrirAjuste(contexto: Context) {
        val pacote = contexto.packageName
        val tentativas = buildList {
            if (xiaomi) add(Intent().setComponent(ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"))
                .putExtra("package_name", pacote).putExtra("package_label", "Converter"))
            add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pacote")))
            add(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        for (intent in tentativas) {
            try {
                contexto.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Exception) {
                // Ajuste que não existe neste aparelho: tenta o próximo.
            }
        }
    }
}

/** Aparece durante um trabalho longo quando a bateria do app ainda está otimizada. */
@Composable
fun AvisoDeBateria(modifier: Modifier = Modifier) {
    val contexto = LocalContext.current
    var visivel by remember { mutableStateOf(Bateria.restrita(contexto) && !Bateria.dispensado(contexto)) }
    if (!visivel) return
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Para continuar com a tela apagada ou em outro app sem pausar, deixe a bateria do Converter em " +
                    "“Sem restrições”" + if (Bateria.xiaomi) " (Xiaomi/POCO: Economia de bateria → Sem restrições, e ative a Inicialização automática)." else ".",
                fontSize = 13.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { Bateria.abrirAjuste(contexto) }) { Text("Abrir o ajuste") }
                TextButton(onClick = { Bateria.dispensar(contexto); visivel = false }) { Text("Agora não") }
            }
        }
    }
}
