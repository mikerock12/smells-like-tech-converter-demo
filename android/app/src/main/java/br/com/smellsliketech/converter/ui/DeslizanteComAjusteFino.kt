package br.com.smellsliketech.converter.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import br.com.smellsliketech.converter.figurinhas.AjusteFino

/** Mantém a barra contínua e oferece passos pequenos nas duas extremidades. */
@Composable
fun DeslizanteComAjusteFino(valor: Float, mudar: (Float) -> Unit,
    limites: ClosedFloatingPointRange<Float> = 0f..1f, passo: Float = .01f, descricao: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        AcaoDoEditor(AcaoEditor.DIMINUIR, "Diminuir $descricao", habilitada = valor > limites.start) {
            mudar(AjusteFino.aplicar(valor, -1, passo, limites))
        }
        Slider(valor.coerceIn(limites.start, limites.endInclusive), mudar, valueRange = limites,
            modifier = Modifier.weight(1f).semantics { contentDescription = descricao })
        AcaoDoEditor(AcaoEditor.ADICIONAR, "Aumentar $descricao", habilitada = valor < limites.endInclusive) {
            mudar(AjusteFino.aplicar(valor, 1, passo, limites))
        }
    }
}
