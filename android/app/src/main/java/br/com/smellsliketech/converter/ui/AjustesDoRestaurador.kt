package br.com.smellsliketech.converter.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import br.com.smellsliketech.converter.conversao.Opcoes
import br.com.smellsliketech.converter.musica.Apollo
import br.com.smellsliketech.converter.musica.BibliotecaDaMusica
import br.com.smellsliketech.converter.musica.RestauradorDeAudio
import kotlinx.coroutines.launch

/** Restaurar áudio com IA (experimental): tipo de gravação, intensidade e, no Avançado, cada etapa. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun AjustesDoRestaurador(opcoes: Opcoes, mudar: (Opcoes) -> Unit) {
    val atual = runCatching { RestauradorDeAudio.lerOpcoes(opcoes.restauracao) }.getOrDefault(RestauradorDeAudio.Opcoes())
    fun gravar(novo: RestauradorDeAudio.Opcoes) = mudar(opcoes.copy(restauracao = novo.paraJson().toString()))
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Dica("Experimental. O áudio é processado neste celular. A restauração estima e limpa; não recria o master original nem devolve o que a compressão cortou. O original não muda.")
        Text("Tipo de gravação", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RestauradorDeAudio.PERFIS.forEach { p -> FilterChip(atual.perfil == p.id, { gravar(atual.copy(perfil = p.id)) }, { Text(p.rotulo, fontSize = 12.sp) }) }
        }
        Dica(RestauradorDeAudio.PERFIS.first { it.id == atual.perfil }.resumo)
        if (atual.perfil == "manual") {
            RestauradorDeAudio.ETAPAS.forEach { (id, rotulo) ->
                val v = atual.etapas[id] ?: 0
                Text("$rotulo ($v)", fontSize = 13.sp)
                Slider(v.toFloat(), { gravar(atual.copy(etapas = atual.etapas + (id to it.toInt()))) }, valueRange = (if (id == "brilho") -100f else 0f)..100f)
            }
        } else {
            Text("Intensidade (${atual.intensidade}%)", fontSize = 13.sp)
            Slider(atual.intensidade.toFloat(), { gravar(atual.copy(intensidade = it.toInt())) }, valueRange = 0f..100f, steps = 19)
        }
        if (atual.perfil == "live") {
            Text("Preservar plateia e ambiência (${atual.preservarAmbiencia}%)", fontSize = 13.sp)
            Slider(atual.preservarAmbiencia.toFloat(), { gravar(atual.copy(preservarAmbiencia = it.toInt())) }, valueRange = 0f..100f, steps = 19)
        }
        if (atual.perfil != "manual" && Apollo.disponivel) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Reparo de compressão por IA (Apollo, experimental)", Modifier.weight(1f).padding(end = 8.dp), fontSize = 13.sp)
                Switch(atual.ia, { gravar(atual.copy(ia = it)) })
            }
            Dica("Para MP3/AAC de taxa baixa: o Apollo recria os agudos que a compressão cortou e mantém os graves do original. É pesado no celular (bem mais que a duração da música) e baixa o modelo na primeira vez.")
        }
        if (Apollo.disponivel && (atual.ia || (atual.perfil == "manual" && (atual.etapas["compressao"] ?: 0) > 0))) ModeloApollo()
        Dica("O formato vem da lista acima. Salvar em WAV ou FLAC não melhora a qualidade por si: só evita perder mais na hora de gravar.")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Manter o volume original", Modifier.weight(1f).padding(end = 8.dp), fontSize = 13.sp)
            Switch(atual.manterVolume, { gravar(atual.copy(manterVolume = it)) })
        }
        Dica("O restaurado sai no mesmo volume da entrada (sem passar de −1 dBFS de pico), para a comparação ser justa: mais alto parece melhor sem ser. Desligado, sobe até o pico de −1 dBFS.")
    }
}

/** O modelo Apollo neste celular: baixar (conferido pelo SHA-256) ou excluir, como os de separação. */
@Composable
private fun ModeloApollo() {
    val contexto = LocalContext.current
    val biblioteca = remember { BibliotecaDaMusica(contexto.applicationContext) }
    val escopo = rememberCoroutineScope()
    var baixado by remember { mutableStateOf(biblioteca.apolloPronto) }
    var andamento by remember { mutableStateOf<Double?>(null) }
    var erro by remember { mutableStateOf<String?>(null) }
    Dica("Modelo Apollo (${Apollo.BYTES / 1_000_000} MB): ${if (baixado) "baixado neste celular" else "ainda não baixado (baixa sozinho na primeira vez)"}")
    andamento?.let { Dica("Baixando… ${(it * 100).toInt()}%") } ?: OutlinedButton({
        erro = null
        escopo.launch {
            runCatching {
                if (baixado) biblioteca.excluirApollo()
                else { andamento = 0.0; biblioteca.garantirApollo { f -> andamento = f } }
            }.onFailure { erro = it.message }
            andamento = null
            baixado = biblioteca.apolloPronto
        }
    }) { Text(if (baixado) "Excluir modelo" else "Baixar agora") }
    erro?.let { Dica(it) }
    Dica(Apollo.ATRIBUICAO)
}
