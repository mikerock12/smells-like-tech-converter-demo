package br.com.smellsliketech.converter

import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowInsetsController
import androidx.core.view.WindowCompat

/** Conteúdo atrás das barras; as telas Compose respeitam safeDrawing/IME. */
object JanelaDoApp {
    fun preparar(window: Window) {
        // Inicializa o decor antes de consultar o controller no onCreate.
        val decor = window.decorView
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // As cores transparentes e o recorte por versão ficam no tema, sem chamar
        // setStatusBarColor/setNavigationBarColor nem usar SHORT_EDGES em código.
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.setSystemBarsAppearance(0,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
        } else {
            @Suppress("DEPRECATION")
            decor.systemUiVisibility = decor.systemUiVisibility and
                (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR).inv()
        }
        if (Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
        }
    }
}
