package br.com.smellsliketech.converter

import androidx.activity.ComponentActivity
import android.os.Bundle

/** Hospeda testes da interface sem iniciar licença, navegação e permissões da tela inicial. */
class EditorDeTesteActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        JanelaDoApp.preparar(window)
    }
}
