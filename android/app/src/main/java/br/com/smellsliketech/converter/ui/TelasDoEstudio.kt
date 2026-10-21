package br.com.smellsliketech.converter.ui

import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import br.com.smellsliketech.converter.conversao.ModeloWhisper
import br.com.smellsliketech.converter.conversao.ModelosWhisper
import br.com.smellsliketech.converter.conversao.Opcoes
import br.com.smellsliketech.converter.conversao.textoDoAndamento
import br.com.smellsliketech.converter.estudio.AudioExtra
import br.com.smellsliketech.converter.estudio.Clipe
import br.com.smellsliketech.converter.estudio.DesenhoDoTexto
import br.com.smellsliketech.converter.estudio.Estudio
import br.com.smellsliketech.converter.estudio.EstudioViewModel
import br.com.smellsliketech.converter.estudio.Preset
import br.com.smellsliketech.converter.estudio.Projeto
import br.com.smellsliketech.converter.estudio.Texto
import java.io.File
import java.util.Locale

/**
 * O estúdio: primeiro o destino (a rede e o formato), depois o que exportar, e então o editor.
 * O mesmo projeto e as mesmas regras do site e do aplicativo para Windows (docs/estudio.md).
 */
@Composable
fun TelaDoEstudio(modelo: EstudioViewModel, podeExportar: Boolean, abrirLicenca: () -> Unit, abrirModelos: () -> Unit, voltar: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            when (modelo.etapa) {
                EstudioViewModel.Etapa.DESTINO -> {
                    BackHandler { if (modelo.projeto != null) modelo.etapa = EstudioViewModel.Etapa.EDITOR else voltar() }
                    EscolhaDoDestino(modelo, voltar = { if (modelo.projeto != null) modelo.etapa = EstudioViewModel.Etapa.EDITOR else voltar() })
                }
                EstudioViewModel.Etapa.SAIDA -> {
                    BackHandler { modelo.etapa = EstudioViewModel.Etapa.DESTINO }
                    EscolhaDaSaida(modelo.projeto?.saida) { modelo.escolherSaida(it) }
                }
                EstudioViewModel.Etapa.EDITOR -> {
                    BackHandler(onBack = voltar)
                    Editor(modelo, podeExportar, abrirLicenca, abrirModelos, voltar)
                }
            }
        }
        modelo.ocupado?.let { texto -> Ocupado(texto, modelo.progresso, modelo.restante, modelo::cancelar) }
        modelo.erro?.let { mensagem ->
            AlertDialog(
                onDismissRequest = { modelo.erro = null },
                confirmButton = { TextButton(onClick = { modelo.erro = null }) { Text("Ok") } },
                title = { Text("Não deu certo") },
                text = { Text(mensagem) },
            )
        }
        modelo.videoDaPrevia?.let { video -> PlayerDaPrevia(video) { modelo.videoDaPrevia = null } }
    }
}

// ==================== assistente ====================

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EscolhaDoDestino(modelo: EstudioViewModel, voltar: () -> Unit) {
    var personalizado by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            TextButton(onClick = voltar) { Text("← Voltar") }
            Text("Para onde é?", fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Escolha a rede e o formato: o estúdio já ajusta o tamanho do quadro e avisa se passar do tempo que ela costuma aceitar.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp,
            )
        }
        val grupos = modelo.presets.filter { it.id != "personalizado" }.groupBy { it.rede }
        grupos.forEach { (rede, lista) ->
            item(key = rede) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Rotulo(rede.uppercase(Locale.ROOT))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        lista.forEach { preset -> CartaoDoPreset(preset, selecionado = modelo.projeto?.destino == preset.id) { modelo.escolherDestino(preset.id) } }
                    }
                }
            }
        }
        item {
            OutlinedButton(onClick = { personalizado = true }, modifier = Modifier.fillMaxWidth()) { Text("Tamanho personalizado…") }
            Text("Os limites das redes mudam; o estúdio só avisa, não impede.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
    if (personalizado) {
        var largura by remember { mutableStateOf("1080") }
        var altura by remember { mutableStateOf("1080") }
        val l = largura.toIntOrNull()
        val a = altura.toIntOrNull()
        val valido = l != null && a != null && l in 16..4096 && a in 16..4096
        AlertDialog(
            onDismissRequest = { personalizado = false },
            title = { Text("Tamanho do quadro") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(largura, { largura = it.filter(Char::isDigit).take(4) }, label = { Text("Largura (px)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(altura, { altura = it.filter(Char::isDigit).take(4) }, label = { Text("Altura (px)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    Text("Entre 16 e 4096 pixels; números ímpares viram pares.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                TextButton(enabled = valido, onClick = { personalizado = false; modelo.escolherDestino("personalizado", l, a) }) { Text("Usar") }
            },
            dismissButton = { TextButton(onClick = { personalizado = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun CartaoDoPreset(preset: Preset, selecionado: Boolean, escolher: () -> Unit) {
    Card(
        modifier = Modifier.clickable(onClick = escolher),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, if (selecionado) Cores.laranja else Cores.borda),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // Um retângulo na proporção do formato: em pé, quadrado ou deitado.
            val maior = maxOf(preset.largura, preset.altura).toFloat()
            Box(
                Modifier.size((26 * preset.largura / maior).dp, (26 * preset.altura / maior).dp)
                    .clip(RoundedCornerShape(3.dp)).background(if (selecionado) Cores.laranja else Cores.textoApagado),
            )
            Column {
                Text(preset.nome, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                val limite = preset.duracaoMaxima?.let { " · até ${Estudio.duracaoLegivel(it)}" } ?: ""
                Text("${preset.largura}×${preset.altura}$limite", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun EscolhaDaSaida(atual: String?, escolher: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("O que você quer no fim?", fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            Text("Dá para trocar depois, na aba Exportar.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
        }
        items(Estudio.SAIDAS, key = { it.id }) { saida ->
            Card(
                modifier = Modifier.fillMaxWidth().clickable { escolher(saida.id) },
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, if (saida.id == atual) Cores.laranja else Cores.borda),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("${saida.titulo} · ${saida.extensao.uppercase(Locale.ROOT)}", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text(saida.descricao, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

// ==================== editor ====================

private enum class AbaDoEstudio(val titulo: String) { CLIPES("Clipes"), CAMADAS("Camadas"), TEXTOS("Textos"), AUDIO("Áudio"), EXPORTAR("Exportar") }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Editor(modelo: EstudioViewModel, podeExportar: Boolean, abrirLicenca: () -> Unit, abrirModelos: () -> Unit, voltar: () -> Unit) {
    val projeto = modelo.projeto ?: return
    var aba by rememberSaveable { mutableStateOf(AbaDoEstudio.CLIPES) }
    var confirmarNovo by remember { mutableStateOf(false) }
    val preset = Estudio.preset(modelo.presets, projeto.destino)
    val adicionarMidias = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> if (uris.isNotEmpty()) modelo.adicionarMidias(uris) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = voltar) { Text("← Início") }
                Column(Modifier.weight(1f).clickable { modelo.etapa = EstudioViewModel.Etapa.DESTINO }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (preset == null || preset.id == "personalizado") "Personalizado" else preset.titulo, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Text("${projeto.largura}×${projeto.altura} · trocar", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { confirmarNovo = true }) { Text("Novo") }
            }
        }
        item { Previa(modelo, projeto) }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AbaDoEstudio.entries.forEach { opcao -> FilterChip(selected = aba == opcao, onClick = { aba = opcao }, label = { Text(opcao.titulo) }) }
            }
        }
        when (aba) {
            AbaDoEstudio.CLIPES -> {
                item {
                    Button(onClick = { adicionarMidias.launch(arrayOf("video/*", "image/*")) }, modifier = Modifier.fillMaxWidth()) { Text("Adicionar vídeos ou fotos") }
                    if (projeto.clipes.isEmpty()) Dica("Os clipes tocam um depois do outro, na ordem da lista. Toque num clipe para cortar, enquadrar e ajustar o som.")
                }
                items(projeto.clipes, key = { it.id }) { clipe -> CartaoDoClipe(modelo, projeto, clipe) }
            }
            AbaDoEstudio.TEXTOS -> {
                item {
                    Button(onClick = { modelo.adicionarTexto() }, enabled = projeto.clipes.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("Adicionar texto") }
                    FlowRow {
                        listOf("😀", "❤️", "🔥", "✨", "🎉", "👍").forEach { emoji ->
                            TextButton(enabled = projeto.clipes.isNotEmpty(), onClick = {
                                val id = modelo.adicionarTexto()
                                modelo.mudarTexto(id) { it.copy(texto = emoji, fonte = "padrao", contorno = null) }
                            }) { Text(emoji) }
                        }
                    }
                    if (projeto.clipes.isEmpty()) Dica("Adicione um vídeo ou foto antes: o texto aparece por cima deles.")
                }
                items(projeto.textos, key = { it.id }) { texto -> CartaoDoTexto(modelo, texto) }
            }
            AbaDoEstudio.AUDIO -> {
                item { BotaoDeAudio(modelo) }
                items(projeto.audios, key = { it.id }) { audio -> CartaoDoAudio(modelo, audio) }
            }
            AbaDoEstudio.CAMADAS -> item { PainelDeCamadasDoEstudio(modelo) }
            AbaDoEstudio.EXPORTAR -> item { PainelDeExportar(modelo, projeto, podeExportar, abrirLicenca, abrirModelos) }
        }
    }
    if (confirmarNovo) {
        AlertDialog(
            onDismissRequest = { confirmarNovo = false },
            title = { Text("Começar outro projeto?") },
            text = { Text("O projeto atual e as cópias das mídias dentro do app serão apagados. Os arquivos originais e o que já foi exportado continuam onde estão.") },
            confirmButton = { TextButton(onClick = { confirmarNovo = false; modelo.novoProjeto() }) { Text("Começar outro") } },
            dismissButton = { TextButton(onClick = { confirmarNovo = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun Previa(modelo: EstudioViewModel, projeto: Projeto) {
    val proporcao = projeto.largura.toFloat() / projeto.altura
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.heightIn(max = 420.dp).aspectRatio(proporcao).clip(RoundedCornerShape(10.dp)).background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            val quadro = modelo.previa
            if (projeto.clipes.isEmpty()) Text("Adicione vídeos ou fotos", color = Cores.textoApagado, fontSize = 13.sp)
            else if (quadro != null) Image(quadro.asImageBitmap(), contentDescription = "Prévia do projeto", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
        val total = modelo.total
        if (total > 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tempo(modelo.instante), fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text(tempo(total), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Slider(value = modelo.instante.toFloat(), onValueChange = { modelo.irPara(it.toDouble()) }, valueRange = 0f..total.toFloat())
            OutlinedButton(onClick = modelo::gerarPreviaEmVideo, modifier = Modifier.fillMaxWidth()) { Text("Assistir à prévia (baixa resolução)") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun CartaoDoClipe(modelo: EstudioViewModel, projeto: Projeto, clipe: Clipe) {
    val aberto = modelo.clipeAberto == clipe.id
    val indice = projeto.clipes.indexOf(clipe)
    val info = modelo.infos[clipe.arquivo]
    Painel {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { modelo.clipeAberto = if (aberto) null else clipe.id }) {
            Column(Modifier.weight(1f)) {
                Text("${indice + 1}. ${info?.nome ?: if (clipe.ehImagem) "Foto" else "Vídeo"}", fontSize = 14.sp, maxLines = 1)
                Text(
                    "${if (clipe.ehImagem) "Foto" else "Vídeo"} · ${tempo(Estudio.duracaoDoClipe(clipe))} · ${Estudio.AJUSTES.first { it.id == clipe.ajuste }.titulo}" +
                        if (clipe.mudo == true) " · sem som" else "",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { modelo.moverClipe(clipe.id, -1) }, enabled = indice > 0) { Text("↑") }
            TextButton(onClick = { modelo.moverClipe(clipe.id, 1) }, enabled = indice < projeto.clipes.lastIndex) { Text("↓") }
            TextButton(onClick = { modelo.removerClipe(clipe.id) }) { Text("✕") }
        }
        if (!aberto) return@Painel
        if (clipe.ehImagem) {
            Text("Na tela por ${decimal1(clipe.duracao ?: Estudio.DURACAO_PADRAO_DA_IMAGEM)} s", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(
                value = (clipe.duracao ?: Estudio.DURACAO_PADRAO_DA_IMAGEM).toFloat(), valueRange = 0.5f..15f, steps = 28,
                onValueChange = { valor -> modelo.mudarClipe(clipe.id) { it.copy(duracao = Math.round(valor * 2) / 2.0) } },
            )
        } else {
            Text("Trecho do vídeo", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            PreviaDoTrecho(Uri.fromFile(File(clipe.arquivo)), video = true, opcoes = Opcoes("mp4", inicioSegundos = clipe.inicio ?: 0.0, fimSegundos = clipe.fim ?: info?.duracao ?: 0.0)) { opcoes ->
                val fim = if (opcoes.fimSegundos > opcoes.inicioSegundos) opcoes.fimSegundos else (info?.duracao ?: opcoes.inicioSegundos + 1)
                modelo.mudarClipe(clipe.id) { it.copy(inicio = opcoes.inicioSegundos, fim = fim) }
            }
        }
        Text("Enquadramento", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Estudio.AJUSTES.forEach { ajuste ->
                FilterChip(selected = clipe.ajuste == ajuste.id, onClick = { modelo.mudarClipe(clipe.id) { it.copy(ajuste = ajuste.id) } }, label = { Text(ajuste.titulo, fontSize = 12.sp) })
            }
        }
        Text(Estudio.AJUSTES.first { it.id == clipe.ajuste }.descricao, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ControlesDeTransformacao(clipe.transformacao) { valor -> modelo.mudarClipe(clipe.id) { it.copy(transformacao = valor) } }
        if (!clipe.ehImagem) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Som do vídeo", fontSize = 13.sp, modifier = Modifier.weight(1f))
                Switch(checked = clipe.mudo != true, onCheckedChange = { ligado -> modelo.mudarClipe(clipe.id) { it.copy(mudo = !ligado) } })
            }
            if (clipe.mudo != true) {
                Text("Volume: ${((clipe.volume ?: 1.0) * 100).toInt()}%", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(value = (clipe.volume ?: 1.0).toFloat(), valueRange = 0f..2f, onValueChange = { v -> modelo.mudarClipe(clipe.id) { it.copy(volume = Math.round(v * 20) / 20.0) } })
            }
        }
    }
}

private val CORES_DO_TEXTO = listOf("#FFFFFF", "#000000", "#FFD54A", "#FF7A18", "#E53935", "#43A047", "#1E88E5", "#8E24AA")

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun CartaoDoTexto(modelo: EstudioViewModel, texto: Texto) {
    val aberto = modelo.textoAberto == texto.id
    val total = modelo.total
    Painel {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { modelo.textoAberto = if (aberto) null else texto.id }) {
            Column(Modifier.weight(1f)) {
                Text(texto.texto.ifBlank { "(vazio)" }, fontSize = 14.sp, maxLines = 1)
                Text("${tempo(texto.inicio)} → ${tempo(texto.fim)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { modelo.removerTexto(texto.id) }) { Text("✕") }
        }
        if (!aberto) return@Painel
        OutlinedTextField(texto.texto, { valor -> modelo.mudarTexto(texto.id) { it.copy(texto = valor.take(300)) } }, label = { Text("Texto") }, modifier = Modifier.fillMaxWidth())
        Text("Fonte", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Estudio.FONTES.forEach { fonte -> FilterChip(selected = texto.fonte == fonte.id, onClick = { modelo.mudarTexto(texto.id) { it.copy(fonte = fonte.id) } }, label = { Text(fonte.nome, fontSize = 12.sp) }) }
        }
        Text("Cor", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CORES_DO_TEXTO.forEach { cor ->
                Box(
                    Modifier.size(32.dp).clip(CircleShape).background(Color(DesenhoDoTexto.cor(cor)!!))
                        .then(if (texto.cor.equals(cor, ignoreCase = true)) Modifier.padding(2.dp).background(Cores.laranja, CircleShape).padding(3.dp).background(Color(DesenhoDoTexto.cor(cor)!!), CircleShape) else Modifier)
                        .clickable { modelo.mudarTexto(texto.id) { it.copy(cor = cor) } },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Contorno", fontSize = 13.sp, modifier = Modifier.weight(1f))
            Switch(checked = texto.contorno != null, onCheckedChange = { ligado -> modelo.mudarTexto(texto.id) { it.copy(contorno = if (ligado) (if (it.cor.equals("#000000", true)) "#FFFFFF" else "#000000") else null) } })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Caixa de fundo", fontSize = 13.sp, modifier = Modifier.weight(1f))
            Switch(checked = texto.caixa != null, onCheckedChange = { ligado -> modelo.mudarTexto(texto.id) { it.copy(caixa = if (ligado) "#000000AA" else null) } })
        }
        Text("Tamanho", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(value = texto.tamanho.toFloat(), valueRange = 0.03f..0.16f, onValueChange = { v -> modelo.mudarTexto(texto.id) { it.copy(tamanho = Math.round(v * 1000) / 1000.0) } })
        Text("Posição", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Em cima" to 0.15, "No meio" to 0.5, "Embaixo" to 0.85).forEach { (rotulo, y) ->
                FilterChip(selected = kotlin.math.abs(texto.y - y) < 0.01, onClick = { modelo.mudarTexto(texto.id) { it.copy(y = y, x = 0.5) } }, label = { Text(rotulo, fontSize = 12.sp) })
            }
        }
        Text("Altura: ${(texto.y * 100).toInt()}%", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(value = texto.y.toFloat(), valueRange = 0f..1f, onValueChange = { v -> modelo.mudarTexto(texto.id) { it.copy(y = Math.round(v * 100) / 100.0) } })
        ControlesDeTransformacao(texto.transformacao, permitirRecorte = false) { valor -> modelo.mudarTexto(texto.id) { it.copy(transformacao = valor) } }
        if (total > 0) {
            Text("Aparece de ${tempo(texto.inicio)} até ${tempo(texto.fim)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            RangeSlider(
                value = texto.inicio.toFloat().coerceIn(0f, total.toFloat())..texto.fim.toFloat().coerceIn(0f, total.toFloat()),
                valueRange = 0f..total.toFloat(),
                onValueChange = { faixa -> modelo.mudarTexto(texto.id) { it.copy(inicio = Math.round(faixa.start * 10) / 10.0, fim = Math.round(faixa.endInclusive * 10) / 10.0) } },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { modelo.mudarTexto(texto.id) { it.copy(inicio = modelo.instante, fim = maxOf(it.fim, modelo.instante + 0.5)) } }) { Text("Começa aqui", fontSize = 12.sp) }
                OutlinedButton(onClick = { modelo.mudarTexto(texto.id) { it.copy(fim = maxOf(modelo.instante, it.inicio + 0.5)) } }) { Text("Termina aqui", fontSize = 12.sp) }
            }
        }
    }
}

@Composable
private fun BotaoDeAudio(modelo: EstudioViewModel) {
    val adicionar = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) modelo.adicionarAudio(uri) }
    Button(onClick = { adicionar.launch(arrayOf("audio/*")) }, modifier = Modifier.fillMaxWidth()) { Text("Adicionar música ou áudio") }
    if (modelo.projeto?.audios.isNullOrEmpty()) Dica("A música toca por cima do som dos vídeos. Escolha o trecho, onde ela entra, o volume e o fade.")
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun CartaoDoAudio(modelo: EstudioViewModel, audio: AudioExtra) {
    val aberto = modelo.audioAberto == audio.id
    val info = modelo.infos[audio.arquivo]
    val duracaoDoArquivo = info?.duracao ?: audio.fim
    val total = modelo.total
    Painel {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { modelo.audioAberto = if (aberto) null else audio.id }) {
            Column(Modifier.weight(1f)) {
                Text(info?.nome ?: "Áudio", fontSize = 14.sp, maxLines = 1)
                Text("Trecho ${tempo(audio.inicio)}–${tempo(audio.fim)} · entra em ${tempo(audio.entrada ?: 0.0)} · ${((audio.volume ?: 1.0) * 100).toInt()}%", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { modelo.removerAudio(audio.id) }) { Text("✕") }
        }
        if (!aberto) return@Painel
        Text("Trecho da música: ${tempo(audio.inicio)} até ${tempo(audio.fim)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        RangeSlider(
            value = audio.inicio.toFloat()..audio.fim.toFloat().coerceAtMost(duracaoDoArquivo.toFloat()),
            valueRange = 0f..duracaoDoArquivo.toFloat().coerceAtLeast(0.2f),
            onValueChange = { faixa -> modelo.mudarAudio(audio.id) { it.copy(inicio = Math.round(faixa.start * 10) / 10.0, fim = Math.round(faixa.endInclusive * 10) / 10.0) } },
        )
        if (total > 0) {
            Text("Entra no vídeo em ${tempo(audio.entrada ?: 0.0)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(value = (audio.entrada ?: 0.0).toFloat().coerceIn(0f, total.toFloat()), valueRange = 0f..total.toFloat(), onValueChange = { v -> modelo.mudarAudio(audio.id) { it.copy(entrada = Math.round(v * 10) / 10.0) } })
        }
        Text("Volume: ${((audio.volume ?: 1.0) * 100).toInt()}%", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(value = (audio.volume ?: 1.0).toFloat(), valueRange = 0f..2f, onValueChange = { v -> modelo.mudarAudio(audio.id) { it.copy(volume = Math.round(v * 20) / 20.0) } })
        Text("Fade de entrada e de saída", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0.0, 1.0, 2.0, 3.0).forEach { s -> FilterChip(selected = (audio.fadeEntrada ?: 0.0) == s, onClick = { modelo.mudarAudio(audio.id) { it.copy(fadeEntrada = s) } }, label = { Text("Entra ${decimal1(s)} s", fontSize = 12.sp) }) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0.0, 1.0, 2.0, 3.0).forEach { s -> FilterChip(selected = (audio.fadeSaida ?: 0.0) == s, onClick = { modelo.mudarAudio(audio.id) { it.copy(fadeSaida = s) } }, label = { Text("Sai ${decimal1(s)} s", fontSize = 12.sp) }) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PainelDeExportar(modelo: EstudioViewModel, projeto: Projeto, podeExportar: Boolean, abrirLicenca: () -> Unit, abrirModelos: () -> Unit) {
    val contexto = LocalContext.current
    val problemas = Estudio.problemas(projeto)
    val avisos = Estudio.avisos(projeto, modelo.presets)
    val instalados = remember(projeto.saida) { ModeloWhisper.entries.filter { ModelosWhisper.instalado(contexto, it) } }
    var modeloWhisper by rememberSaveable { mutableStateOf<String?>(null) }
    val escolhido = modeloWhisper?.takeIf { id -> instalados.any { it.id == id } } ?: instalados.firstOrNull { it.id == "small" }?.id ?: instalados.firstOrNull()?.id
    Painel {
        Text("Exportar como", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Estudio.SAIDAS.forEach { saida -> FilterChip(selected = projeto.saida == saida.id, onClick = { modelo.mudarProjeto { it.copy(saida = saida.id) } }, label = { Text(saida.titulo, fontSize = 12.sp) }) }
        }
        Text(Estudio.saida(projeto.saida)?.descricao ?: "", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("${projeto.largura}×${projeto.altura} · ${Estudio.duracaoLegivel(modelo.total)}", fontSize = 13.sp)
        if (projeto.saida == "imagem") {
            Text("Quadro em ${tempo(projeto.quadro)}", fontSize = 13.sp)
            OutlinedButton(onClick = { modelo.mudarProjeto { it.copy(quadro = modelo.instante) } }) { Text("Usar o ponto da prévia (${tempo(modelo.instante)})") }
        }
        if (projeto.saida == "texto") {
            if (instalados.isEmpty()) {
                Text("Para transcrever, baixe um modelo do Whisper uma vez.", fontSize = 13.sp, color = Cores.amarelo)
                OutlinedButton(onClick = abrirModelos) { Text("Modelos de transcrição") }
            } else {
                Text("Modelo de transcrição", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    instalados.forEach { m -> FilterChip(selected = escolhido == m.id, onClick = { modeloWhisper = m.id }, label = { Text(m.rotulo, fontSize = 12.sp) }) }
                }
            }
        }
        problemas.forEach { Text("• $it", fontSize = 13.sp, color = Cores.amarelo) }
        avisos.forEach { Text("• $it", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        val pronto = problemas.isEmpty() && (projeto.saida != "texto" || escolhido != null)
        Button(
            onClick = { if (podeExportar) modelo.exportar(escolhido ?: "small") else abrirLicenca() },
            enabled = pronto,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Exportar ${Estudio.saida(projeto.saida)?.titulo?.lowercase(Locale.ROOT) ?: ""}") }
        Dica("O resultado vai para a pasta Downloads/SmellsLikeTech.")
    }
    modelo.resultado?.let { resultado ->
        Painel {
            Rotulo("PRONTO")
            Text(resultado.nome, fontSize = 14.sp)
            BarraDeCompartilhar(resultado)
        }
    }
}

// ==================== peças ====================

@Composable
private fun Ocupado(texto: String, progresso: Int?, restante: Int?, cancelar: () -> Unit) {
    val suave by animateFloatAsState((progresso ?: 0) / 100f, animationSpec = tween(400), label = "progresso")
    AlertDialog(
        onDismissRequest = {},
        confirmButton = { TextButton(onClick = cancelar) { Text("Cancelar") } },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(texto)
                if (progresso != null) {
                    LinearProgressIndicator(progress = { suave }, modifier = Modifier.fillMaxWidth())
                    Text(textoDoAndamento(progresso, restante), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
    )
}

/** A prévia em vídeo, num diálogo com os controles do próprio Android, repetindo. */
@Composable
private fun PlayerDaPrevia(video: File, fechar: () -> Unit) {
    Dialog(onDismissRequest = fechar, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().padding(12.dp), shape = RoundedCornerShape(14.dp), color = Color.Black) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                AndroidView(
                    factory = { contexto ->
                        VideoView(contexto).apply {
                            setVideoURI(Uri.fromFile(video))
                            setMediaController(MediaController(contexto).also { it.setAnchorView(this) })
                            setOnPreparedListener { tocador -> tocador.isLooping = true; start() }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp),
                )
                TextButton(onClick = fechar) { Text("Fechar") }
            }
        }
    }
}

@Composable
private fun Painel(conteudo: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, Cores.borda)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = conteudo)
    }
}

@Composable
private fun Rotulo(texto: String) = Text(texto, color = MaterialTheme.colorScheme.secondary, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)


/** "01:05,3": minutos, segundos e décimos, como no player do corte. */
private fun tempo(segundos: Double): String {
    val decimos = Math.round(segundos * 10).coerceAtLeast(0)
    return String.format(Locale.ROOT, "%02d:%02d,%d", decimos / 600, (decimos / 10) % 60, decimos % 10)
}

private fun decimal1(valor: Double) = if (valor == Math.floor(valor)) valor.toInt().toString() else String.format(Locale.ROOT, "%.1f", valor).replace('.', ',')
