package br.com.smellsliketech.converter.ui

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Ícones vetoriais; a descrição também fica disponível ao TalkBack e ao toque longo. */
enum class AcaoEditor(val desenho: String) {
    SALVAR("M5,3 L19,3 L21,5 L21,21 L3,21 L3,3 Z M7,3 L7,9 L17,9 L17,3 M7,21 L7,14 L17,14 L17,21"),
    CANCELAR("M6,6 L18,18 M6,18 L18,6"),
    DESFAZER("M9,5 L4,10 L9,15 M4,10 L15,10 Q21,10 20,17"),
    REFAZER("M15,5 L20,10 L15,15 M20,10 L9,10 Q3,10 4,17"),
    CAMADAS("M3,8 L12,3 L21,8 L12,13 Z M3,12 L12,17 L21,12 M3,16 L12,21 L21,16"),
    ENQUADRAR("M3,9 L3,3 L9,3 M15,3 L21,3 L21,9 M21,15 L21,21 L15,21 M9,21 L3,21 L3,15"),
    FUNDO("M4,4 L20,4 L20,20 L4,20 Z M4,20 L20,4 M4,12 L12,4 M12,20 L20,12"),
    FILTRO("M4,7 L20,7 M4,17 L20,17 M8,4 L8,10 M16,14 L16,20"),
    TEXTO("M4,5 L20,5 M12,5 L12,20 M8,20 L16,20"),
    AUDIO("M9,18 L9,4 L20,2 L20,16 M9,4 L20,2 M9,16 C1,12 1,24 9,20 M20,14 C12,10 12,22 20,18"),
    CONTORNO("M4,4 L20,4 L20,20 L4,20 Z M8,8 L16,8 L16,16 L8,16 Z"),
    EMOJI("M12,3 A9,9 0,1 1,11.9,3 M8,9 L8,10 M16,9 L16,10 M7,14 Q12,21 17,14"),
    TOCAR("M8,4 L20,12 L8,20 Z"),
    ADICIONAR("M12,4 L12,20 M4,12 L20,12"),
    SUBIR("M5,13 L12,6 L19,13 M12,6 L12,21 M4,3 L20,3"),
    DESCER("M5,11 L12,18 L19,11 M12,18 L12,3 M4,21 L20,21"),
    VISIBILIDADE("M2,12 Q12,0 22,12 Q12,24 2,12 Z M12,8 A4,4 0,1 1,11.9,8"),
    EXCLUIR("M5,6 L19,6 M9,3 L15,3 M7,6 L8,21 L16,21 L17,6 M10,10 L10,17 M14,10 L14,17"),
    GIRAR_ESQUERDA("M8,3 L3,8 L8,13 M3,8 L13,8 Q24,8 19,19 Q15,24 10,19"),
    GIRAR_DIREITA("M16,3 L21,8 L16,13 M21,8 L11,8 Q0,8 5,19 Q9,24 14,19"),
    ESPELHAR("M12,2 L12,22 M3,6 L9,18 L3,18 Z M21,6 L15,18 L21,18 Z"),
    INVERTER("M2,12 L22,12 M6,3 L18,9 L18,3 Z M6,21 L18,15 L18,21 Z"),
    CENTRALIZAR("M12,2 L12,22 M2,12 L22,12 M7,7 L17,7 L17,17 L7,17 Z"),
    REDEFINIR("M4,10 A8,8 0,1 1,6,18 M4,3 L4,10 L11,10"),
    ZOOM("M10,3 A7,7 0,1 1,9.9,3 M15,15 L22,22 M10,6 L10,14 M6,10 L14,10"),
    APAGAR("M3,15 L13,3 L22,12 L13,21 L8,21 Z M7,11 L17,17 M13,21 L22,21"),
    PINCEL("M4,20 Q2,15 7,15 L10,18 Q10,22 4,20 M9,14 L18,3 L22,6 L12,17 Z"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AcaoDoEditor(acao: AcaoEditor, descricao: String, modifier: Modifier = Modifier, habilitada: Boolean = true,
    selecionada: Boolean = false, executar: () -> Unit) {
    val vetor = remember(acao) {
        ImageVector.Builder(acao.name, 24.dp, 24.dp, 24f, 24f).apply {
            addPath(PathParser().parsePathString(acao.desenho).toNodes(), fill = null,
                stroke = androidx.compose.ui.graphics.SolidColor(Color.White), strokeLineWidth = 1.7f,
                strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round,
                strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round)
        }.build()
    }
    TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(descricao) } }, state = rememberTooltipState()) {
        FilledTonalIconButton(onClick = executar, enabled = habilitada, modifier = modifier,
            colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = if (selecionada)
                MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer)) {
            Icon(vetor, contentDescription = descricao)
        }
    }
}
