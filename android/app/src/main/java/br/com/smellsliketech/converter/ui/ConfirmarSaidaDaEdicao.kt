package br.com.smellsliketech.converter.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/** Voltar, tocar fora e continuar deixam o editor e suas mídias intactos. */
@Composable
internal fun ConfirmarSaidaDaEdicao(rascunhoAutomatico: Boolean = false, continuar: () -> Unit, sair: () -> Unit) {
    AlertDialog(
        onDismissRequest = continuar,
        title = { Text("Sair da edição?") },
        text = { Text(if (rascunhoAutomatico)
            "Antes de sair, salve uma cópia do projeto para proteger suas edições. O estúdio mantém um rascunho automático neste aparelho. Deseja sair do editor?"
        else "Se você voltar sem salvar, perderá as edições feitas nesta figurinha. Deseja sair mesmo assim?") },
        confirmButton = { TextButton(onClick = sair) { Text("Sair da edição") } },
        dismissButton = { TextButton(onClick = continuar) { Text("Continuar editando") } },
    )
}
