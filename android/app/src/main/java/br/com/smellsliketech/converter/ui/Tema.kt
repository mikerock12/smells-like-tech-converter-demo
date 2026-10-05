package br.com.smellsliketech.converter.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** As cores do site e do aplicativo para Windows (app/globals.css, App.xaml). */
object Cores {
    val fundo = Color(0xFF0E0E12)
    val superficie = Color(0xFF15151B)
    val borda = Color(0xFF26262F)
    val texto = Color(0xFFF3F3F5)
    val textoApagado = Color(0xFFA6A6B0)
    val laranja = Color(0xFFFF7A18)
    val ambar = Color(0xFFFFC107)
    val amarelo = Color(0xFFFFD54A)
}

@Composable
fun TemaDoConverter(conteudo: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Cores.laranja,
            onPrimary = Color.Black,
            secondary = Cores.ambar,
            background = Cores.fundo,
            onBackground = Cores.texto,
            surface = Cores.superficie,
            onSurface = Cores.texto,
            surfaceVariant = Cores.superficie,
            onSurfaceVariant = Cores.textoApagado,
            outline = Cores.borda,
            error = Cores.amarelo,
        ),
        content = conteudo,
    )
}
