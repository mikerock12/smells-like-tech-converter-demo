package br.com.smellsliketech.converter.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.StatFs
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.smellsliketech.converter.BuildConfig
import br.com.smellsliketech.converter.conversao.Andamento
import br.com.smellsliketech.converter.conversao.CompressaoPdf
import br.com.smellsliketech.converter.conversao.Encaixe
import br.com.smellsliketech.converter.conversao.Ferramenta
import br.com.smellsliketech.converter.conversao.Listas
import br.com.smellsliketech.converter.conversao.ModeloWhisper
import br.com.smellsliketech.converter.conversao.ModelosWhisper
import br.com.smellsliketech.converter.conversao.ModoDeDividir
import br.com.smellsliketech.converter.conversao.Opcoes
import br.com.smellsliketech.converter.conversao.Proporcao
import br.com.smellsliketech.converter.conversao.Resultado
import br.com.smellsliketech.converter.conversao.Tipo
import br.com.smellsliketech.converter.conversao.textoDoAndamento
import br.com.smellsliketech.converter.conversao.Transcricao
import br.com.smellsliketech.converter.licenca.Acesso
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

private const val POLITICA_DE_PRIVACIDADE = "https://converter.smellsliketech.com.br/privacidade"
private const val CODIGO_ABERTO = "https://converter.smellsliketech.com.br/codigo-aberto"

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Oficina(modelo: OficinaViewModel, escolherArquivos: () -> Unit) {
    if (modelo.mostrarLicenca) {
        BackHandler { modelo.mostrarLicenca = false }
        TelaDaLicenca(modelo) { modelo.mostrarLicenca = false }
        return
    }
    if (modelo.mostrarModelos) {
        BackHandler { modelo.mostrarModelos = false }
        TelaDosModelos(modelo) { modelo.mostrarModelos = false }
        return
    }
    modelo.textoParaNarrar?.let { inicial -> DialogoDeNarracao(modelo, inicial, aoFechar = { modelo.textoParaNarrar = null }) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            Modifier.fillMaxSize().safeDrawingPadding(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { Cabecalho(modelo.situacao) { modelo.mostrarLicenca = true } }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = escolherArquivos) { Text("Escolher arquivos") }
                    OutlinedButton(onClick = { modelo.textoParaNarrar = "" }) { Text("Narrar um texto") }
                }
            }
            modelo.aviso?.let { aviso -> item { Aviso(aviso) } }
            val tipos = Tipo.entries.filter { tipo -> modelo.entradas.any { it.tipo == tipo } }
            items(tipos, key = { "tipo-${it.name}" }) { tipo -> CartaoDoTipo(modelo, tipo) }
            if (modelo.entradas.isEmpty() && modelo.trabalhos.isEmpty()) item { Explicacao() }
            if (modelo.trabalhos.isNotEmpty()) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Rotulo("CONVERSÕES", Modifier.weight(1f))
                        TextButton(onClick = modelo::limparTerminados) { Text("Limpar terminadas") }
                    }
                }
                items(modelo.trabalhos, key = { it.id }) { trabalho ->
                    CartaoDoTrabalho(trabalho, cancelar = { modelo.cancelar(trabalho) }, salvar = { transcricao, formato -> modelo.salvarTranscricao(trabalho, transcricao, formato) })
                }
            }
            item {
                Dica("* Conversões, OCR e narração Kokoro-82M funcionam sem internet com arquivos salvos no aparelho. As vozes Dora, Alex e Santa já estão no app. Para transcrever, baixe um modelo do Whisper uma vez. Internet é necessária para esse download, instalar/atualizar o app, comprar ou restaurar pela Google Play e abrir páginas externas. Arquivos que estão só na nuvem precisam ser baixados antes. Sua licença já ativada é conferida no aparelho.")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { modelo.mostrarModelos = true }) { Text("Modelos de transcrição") }
                    // Abre no navegador a página do site sobre o código aberto do site, do app e do aplicativo para Windows.
                    val contexto = LocalContext.current
                    TextButton(onClick = { abrirNoNavegador(contexto, CODIGO_ABERTO) }) { Text("Código aberto") }
                    TextButton(onClick = { abrirNoNavegador(contexto, "https://github.com/mikerock12/smells-like-tech-converter-demo/releases") }) { Text("Fontes GPL") }
                }
                Dica("Copyright © 2026 Smells Like Tech Informática e colaboradores. Código próprio dos clientes integrados sob GPLv3 ou posterior, sem garantia; você pode modificar e redistribuir conforme essa licença. Textos completos e avisos de terceiros estão incluídos no app; os fontes e as receitas estão em Fontes GPL.")
            }
        }
    }
}

@Composable
private fun Cabecalho(situacao: Acesso.Situacao, abrirLicenca: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("SMELLS LIKE TECH", color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text("CONVERTER", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, letterSpacing = 3.sp)
            }
            val (texto, destaque) = when (situacao) {
                is Acesso.Situacao.Ativa, is Acesso.Situacao.Comprada -> "Licença ativa" to false
                is Acesso.Situacao.Teste -> "Teste: ${situacao.diasRestantes} ${if (situacao.diasRestantes == 1) "dia" else "dias"}" to false
                Acesso.Situacao.TesteAcabou -> "Teste acabou" to true
            }
            OutlinedButton(onClick = abrirLicenca, border = BorderStroke(1.dp, if (destaque) Cores.amarelo else Cores.borda)) {
                Text(texto, fontSize = 12.sp, color = if (destaque) Cores.amarelo else MaterialTheme.colorScheme.onSurface)
            }
        }
        Text("Converta no celular, sem enviar nada.", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, lineHeight = 28.sp)
        Text(
            "Imagem, vídeo, áudio, PDF, documentos, transcrição, OCR e narração, tudo no aparelho. Pode converter sem internet.*",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp,
        )
    }
}

@Composable
private fun Explicacao() {
    Painel {
        Rotulo("COMO USAR")
        Text("Toque em Escolher arquivos, ou compartilhe um arquivo de outro app (galeria, WhatsApp, Arquivos) e escolha Converter: um áudio do WhatsApp vira MP3 ou texto, um .doc vira PDF.", fontSize = 14.sp)
        Text("Os resultados vão para a pasta Downloads/SmellsLikeTech.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CartaoDoTipo(modelo: OficinaViewModel, tipo: Tipo) {
    val arquivos = modelo.entradas.filter { it.tipo == tipo }
    val ferramenta = modelo.ferramentaDe(tipo)
    val opcoes = modelo.opcoesDe(ferramenta)
    Painel {
        Rotulo("${tipo.rotulo.uppercase()} · ${arquivos.size} ${if (arquivos.size == 1) "ARQUIVO" else "ARQUIVOS"}")
        arquivos.forEach { entrada ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(entrada.nome, Modifier.weight(1f), fontSize = 13.sp, maxLines = 1)
                Text(tamanho(entrada.bytes), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                TextButton(onClick = { modelo.remover(entrada) }) { Text("×") }
            }
        }
        Text("O que fazer", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Ferramenta.para(tipo, arquivos.map { it.extensao }).forEach { opcao ->
                FilterChip(selected = opcao == ferramenta, onClick = { modelo.ferramentaDoTipo[tipo] = opcao }, label = { Text(opcao.titulo, fontSize = 12.sp) })
            }
        }
        Ajustes(modelo, ferramenta, opcoes) { modelo.opcoesDaFerramenta[ferramenta] = it }
        Button(onClick = { modelo.converter(tipo) }, modifier = Modifier.fillMaxWidth()) {
            Text(if (arquivos.size == 1 || ferramenta.juntaTudo) ferramenta.titulo else "${ferramenta.titulo} (${arquivos.size})")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Escolha(titulo: String, valores: List<T>, atual: T, rotulo: (T) -> String, escolher: (T) -> Unit) {
    Text(titulo, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        valores.forEach { valor -> FilterChip(selected = valor == atual, onClick = { escolher(valor) }, label = { Text(rotulo(valor), fontSize = 12.sp) }) }
    }
}

@Composable
private fun Chave(titulo: String, ligada: Boolean, mudar: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(titulo, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Switch(checked = ligada, onCheckedChange = mudar)
    }
}

private fun maiusculo(formato: String) = formato.uppercase()
private fun comOriginal(valor: Int, sufixo: String) = if (valor == 0) "Original" else "$valor$sufixo"
private fun decimal(valor: Number) = valor.toString().removeSuffix(".0").replace('.', ',')

/**
 * Os ajustes de cada ferramenta, os mesmos do aplicativo para Windows. Os valores vêm das
 * listas permitidas (Listas), e o motor confere de novo antes de montar o comando.
 */
@Composable
private fun Ajustes(modelo: OficinaViewModel, ferramenta: Ferramenta, opcoes: Opcoes, mudar: (Opcoes) -> Unit) {
    var mais by remember(ferramenta) { mutableStateOf(false) }
    if (ferramenta.formatos.size > 1) {
        Escolha("Formato", ferramenta.formatos, opcoes.formato, ::maiusculo) { mudar(opcoes.copy(formato = it)) }
    }
    val perdeQualidade = opcoes.formato in listOf("jpg", "webp", "avif")
    when (ferramenta) {
        Ferramenta.IMAGEM_CONVERTER, Ferramenta.IMAGEM_COMPRIMIR, Ferramenta.IMAGEM_REDIMENSIONAR -> {
            if (perdeQualidade) Escolha("Qualidade", listOf(95, 85, 75, 60, 45, 30), opcoes.qualidade, { "$it%" }) { mudar(opcoes.copy(qualidade = it)) }
            Escolha("Largura máxima", listOf(0, 3840, 2560, 1920, 1280, 1080, 800, 640, 320), opcoes.larguraMaxima, { comOriginal(it, " px") }) { mudar(opcoes.copy(larguraMaxima = it)) }
            if (opcoes.formato == "ico") Dica("O ícone sai quadrado, com os tamanhos de 16 a 256 px.")
        }
        Ferramenta.IMAGEM_RECORTAR -> {
            Escolha("Proporção", Proporcao.entries.drop(1), opcoes.proporcao, { it.rotulo }) { mudar(opcoes.copy(proporcao = it)) }
            Escolha("Como encaixar", Encaixe.entries, opcoes.encaixe, { it.rotulo }) { mudar(opcoes.copy(encaixe = it)) }
            if (perdeQualidade) Escolha("Qualidade", listOf(95, 85, 75, 60), opcoes.qualidade, { "$it%" }) { mudar(opcoes.copy(qualidade = it)) }
        }
        Ferramenta.IMAGEM_GIRAR -> {
            Escolha("Girar", listOf(0, 90, 180, 270), opcoes.graus, { "$it°" }) { mudar(opcoes.copy(graus = it)) }
            Chave("Espelhar na horizontal", opcoes.espelharHorizontal) { mudar(opcoes.copy(espelharHorizontal = it)) }
            Chave("Espelhar na vertical", opcoes.espelharVertical) { mudar(opcoes.copy(espelharVertical = it)) }
        }
        Ferramenta.IMAGEM_ICONE -> Dica("Sai um .ico quadrado com os tamanhos de 16 a 256 px (o que sobra fica transparente).")

        Ferramenta.VIDEO_CONVERTER, Ferramenta.VIDEO_COMPRIMIR, Ferramenta.GIF_PARA_VIDEO -> {
            if (ferramenta == Ferramenta.GIF_PARA_VIDEO) Escolha("Repetições do GIF", listOf(1, 2, 3, 5, 10, 20), opcoes.repeticoesDoGif, { "$it" }) { mudar(opcoes.copy(repeticoesDoGif = it)) }
            Escolha("Resolução", listOf(0) + Listas.ALTURAS, opcoes.alturaDoVideo, { comOriginal(it, "p") }) { mudar(opcoes.copy(alturaDoVideo = it)) }
            Escolha("Qualidade", listOf(20, 40, 65, 80, 95), opcoes.qualidadeDoVideo, {
                when (it) { 20 -> "Mínima"; 40 -> "Arquivo menor"; 65 -> "Boa"; 80 -> "Alta"; else -> "Máxima" }
            }) { mudar(opcoes.copy(qualidadeDoVideo = it)) }
            if (ferramenta == Ferramenta.VIDEO_CONVERTER) {
                Chave("Mais ajustes (FPS, velocidade, proporção, girar)", mais) { mais = it }
                if (mais) {
                    Escolha("Quadros por segundo", listOf(0) + Listas.FPS, opcoes.quadrosPorSegundo, { comOriginal(it, "") }) { mudar(opcoes.copy(quadrosPorSegundo = it)) }
                    Escolha("Velocidade", Listas.VELOCIDADES, opcoes.velocidade, { "${decimal(it)}x" }) { mudar(opcoes.copy(velocidade = it)) }
                    Escolha("Proporção", Proporcao.entries, opcoes.proporcao, { it.rotulo }) { mudar(opcoes.copy(proporcao = it)) }
                    if (opcoes.proporcao != Proporcao.ORIGINAL) Escolha("Como encaixar", Encaixe.entries, opcoes.encaixe, { it.rotulo }) { mudar(opcoes.copy(encaixe = it)) }
                    Escolha("Girar", listOf(0, 90, 180, 270), opcoes.graus, { "$it°" }) { mudar(opcoes.copy(graus = it)) }
                    Chave("Tirar o áudio", opcoes.removerAudio) { mudar(opcoes.copy(removerAudio = it)) }
                }
            }
        }
        Ferramenta.VIDEO_CORTAR -> Trecho(opcoes, mudar)
        Ferramenta.AUDIO_CORTAR, Ferramenta.AUDIO_EDITAR -> {
            Trecho(opcoes, mudar)
            AjustesDeAudio(opcoes, mudar)
            Escolha("Volume", listOf(0.0, 0.5, 1.0, 1.5, 2.0, 3.0, 4.0), opcoes.volume, { "${(it * 100).toInt()}%" }) { mudar(opcoes.copy(volume = it)) }
            Escolha("Velocidade", Listas.VELOCIDADES, opcoes.velocidade, { "${decimal(it)}x" }) { mudar(opcoes.copy(velocidade = it)) }
            Escolha("Fade de entrada", listOf(0.0, 1.0, 2.0, 3.0, 5.0, 10.0), opcoes.fadeEntrada, { "${decimal(it)} s" }) { mudar(opcoes.copy(fadeEntrada = it)) }
            Escolha("Fade de saída", listOf(0.0, 1.0, 2.0, 3.0, 5.0, 10.0), opcoes.fadeSaida, { "${decimal(it)} s" }) { mudar(opcoes.copy(fadeSaida = it)) }
        }
        Ferramenta.VIDEO_PARA_AUDIO, Ferramenta.AUDIO_CONVERTER -> {
            AjustesDeAudio(opcoes, mudar)
            Chave("Cortar um trecho", mais) { mais = it }
            if (mais) Trecho(opcoes, mudar)
        }
        Ferramenta.VIDEO_GIF -> {
            Escolha("Quadros por segundo", Listas.FPS_DO_GIF, opcoes.fpsDoGif, { "$it" }) { mudar(opcoes.copy(fpsDoGif = it)) }
            Escolha("Largura", Listas.LARGURAS_DO_GIF, opcoes.larguraDoGif, { "$it px" }) { mudar(opcoes.copy(larguraDoGif = it)) }
            Dica("Sem corte, o GIF usa os 10 primeiros segundos (no máximo 2 minutos).")
            Trecho(opcoes, mudar)
        }
        Ferramenta.VIDEO_QUADROS -> {
            Escolha("Imagens por segundo de vídeo", Listas.TAXAS_DE_QUADROS, opcoes.quadrosExtraidosPorSegundo, ::decimal) { mudar(opcoes.copy(quadrosExtraidosPorSegundo = it)) }
            Escolha("No máximo", listOf(30, 100, 300, 1000), opcoes.maximoDeQuadros, { "$it imagens" }) { mudar(opcoes.copy(maximoDeQuadros = it)) }
            Trecho(opcoes, mudar)
        }
        Ferramenta.VIDEO_TRANSCREVER, Ferramenta.AUDIO_TRANSCREVER -> {
            val instalados = modelo.modelosInstalados()
            if (instalados.isEmpty()) {
                Text("Nenhum modelo do Whisper no celular ainda.", color = Cores.amarelo, fontSize = 13.sp)
            } else {
                Escolha("Modelo", instalados.map { it.id }, opcoes.modelo, { id -> ModeloWhisper.de(id)?.rotulo ?: id }) { mudar(opcoes.copy(modelo = it)) }
            }
            OutlinedButton(onClick = { modelo.mostrarModelos = true }) { Text(if (instalados.isEmpty()) "Baixar um modelo" else "Modelos de transcrição") }
            Escolha("Idioma", Listas.IDIOMAS.keys.toList(), opcoes.idioma, { Listas.IDIOMAS[it] ?: it }) { mudar(opcoes.copy(idioma = it)) }
            Chave("Transcrever só um trecho", mais) { mais = it }
            if (mais) Trecho(opcoes, mudar)
        }

        Ferramenta.PDF_DIVIDIR -> {
            Escolha("Como dividir", ModoDeDividir.entries, opcoes.modoDeDividir, { it.rotulo }) { mudar(opcoes.copy(modoDeDividir = it)) }
            when (opcoes.modoDeDividir) {
                ModoDeDividir.PAGINAS -> CampoDeTexto("Páginas (ex.: 1-3, 5, 8-)", opcoes.paginas) { mudar(opcoes.copy(paginas = it)) }
                ModoDeDividir.BLOCOS -> Escolha("Páginas por arquivo", listOf(2, 5, 10, 20, 50), opcoes.paginasPorBloco, { "$it" }) { mudar(opcoes.copy(paginasPorBloco = it)) }
                ModoDeDividir.CADA_PAGINA -> Unit
            }
        }
        Ferramenta.PDF_GIRAR -> Escolha("Girar", listOf(90, 180, 270), opcoes.graus, { "$it°" }) { mudar(opcoes.copy(graus = it)) }
        Ferramenta.PDF_COMPRIMIR -> {
            Escolha("Compressão", CompressaoPdf.entries, opcoes.compressaoPdf, { it.rotulo }) { mudar(opcoes.copy(compressaoPdf = it)) }
            Dica("Reduz fotos sem transformar o texto em imagem. PDFs já compactos podem não diminuir; nunca aumenta o arquivo. Imagens com transparência, assinaturas e proteção recebem tratamento conservador.")
        }
        Ferramenta.PDF_PARA_IMAGENS -> Escolha("Resolução", Listas.DPIS, opcoes.dpi, { "$it dpi" }) { mudar(opcoes.copy(dpi = it)) }
        Ferramenta.PDF_PARA_DOCUMENTO -> Dica("Páginas escaneadas passam pelo OCR (reconhecimento de texto).")
        Ferramenta.LEGENDA_CONVERTER -> {
            Escolha("Atraso (negativo adianta)", listOf(-10.0, -5.0, -2.0, -1.0, -0.5, 0.0, 0.5, 1.0, 2.0, 5.0, 10.0), opcoes.atrasoLegendaSegundos, { "${decimal(it)} s" }) { mudar(opcoes.copy(atrasoLegendaSegundos = it)) }
        }

        Ferramenta.TEXTO_NARRAR, Ferramenta.LEGENDA_NARRAR, Ferramenta.PDF_NARRAR, Ferramenta.DOCUMENTO_NARRAR, Ferramenta.IMAGEM_NARRAR -> {
            if (ferramenta == Ferramenta.PDF_NARRAR || ferramenta == Ferramenta.IMAGEM_NARRAR) Dica("Páginas escaneadas e fotos passam pelo reconhecimento de texto antes da leitura.")
            AjustesDaFala(opcoes, mudar)
        }
        else -> Unit
    }
}

@Composable
private fun Dica(texto: String) {
    Text(texto, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
}

@Composable
private fun AjustesDeAudio(opcoes: Opcoes, mudar: (Opcoes) -> Unit) {
    if (!Listas.semPerda(opcoes.formato)) {
        Escolha("Bitrate", listOf(0) + Listas.BITRATES, opcoes.bitrate, { if (it == 0) "Padrão" else "$it kbps" }) { mudar(opcoes.copy(bitrate = it)) }
    }
    val taxas = if (opcoes.formato == "opus") Listas.TAXAS.filter { it in Listas.TAXAS_DO_OPUS } else Listas.TAXAS
    Escolha("Taxa de amostragem", listOf(0) + taxas, opcoes.taxaDeAmostragem.takeIf { it == 0 || it in taxas } ?: 0, {
        if (it == 0) "Original" else "${decimal(it / 1000.0)} kHz"
    }) { mudar(opcoes.copy(taxaDeAmostragem = it)) }
    Escolha("Canais", listOf(0, 2, 1), opcoes.canais, { when (it) { 0 -> "Original"; 1 -> "Mono"; else -> "Estéreo" } }) { mudar(opcoes.copy(canais = it)) }
    Chave("Normalizar o volume", opcoes.normalizar) { mudar(opcoes.copy(normalizar = it)) }
}

@Composable
private fun AjustesDaFala(opcoes: Opcoes, mudar: (Opcoes) -> Unit) {
    Escolha("Voz Kokoro-82M", listOf("pf_dora", "pm_alex", "pm_santa"), opcoes.vozDaNarracao, {
        when (it) { "pf_dora" -> "Dora"; "pm_alex" -> "Alex"; else -> "Santa" }
    }) { mudar(opcoes.copy(vozDaNarracao = it)) }
    Escolha("Velocidade da fala", listOf(0.75f, 1f, 1.25f, 1.5f), opcoes.velocidadeDaFala, { "${decimal(it)}x" }) { mudar(opcoes.copy(velocidadeDaFala = it)) }
    if (!Listas.semPerda(opcoes.formato)) {
        Escolha("Bitrate", listOf(0, 64, 128, 192), opcoes.bitrate, { if (it == 0) "Padrão" else "$it kbps" }) { mudar(opcoes.copy(bitrate = it)) }
    }
}

@Composable
private fun Trecho(opcoes: Opcoes, mudar: (Opcoes) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CampoDeTempo("Início", opcoes.inicioSegundos, Modifier.weight(1f)) { mudar(opcoes.copy(inicioSegundos = it)) }
        CampoDeTempo("Fim", opcoes.fimSegundos, Modifier.weight(1f)) { mudar(opcoes.copy(fimSegundos = it)) }
    }
}

@Composable
private fun CampoDeTexto(rotulo: String, valor: String, mudar: (String) -> Unit) {
    OutlinedTextField(value = valor, onValueChange = mudar, label = { Text(rotulo) }, singleLine = true, modifier = Modifier.fillMaxWidth())
}

/** Tempo em "m:ss" ou em segundos. */
@Composable
private fun CampoDeTempo(rotulo: String, segundos: Double, modifier: Modifier, mudar: (Double) -> Unit) {
    var texto by remember { mutableStateOf(if (segundos > 0) paraRelogio(segundos) else "") }
    OutlinedTextField(
        value = texto,
        onValueChange = { novo ->
            texto = novo
            if (novo.isBlank()) mudar(0.0) else deRelogio(novo)?.let(mudar)
        },
        label = { Text("$rotulo (m:ss)") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

fun deRelogio(texto: String): Double? {
    val partes = texto.trim().split(":").map { it.trim().replace(',', '.') }
    if (partes.isEmpty() || partes.any { it.isEmpty() }) return null
    return partes.fold(0.0) { total, parte -> total * 60 + (parte.toDoubleOrNull() ?: return null) }
}

fun paraRelogio(segundos: Double): String = "%d:%02d".format(Locale.ROOT, (segundos / 60).toInt(), (segundos % 60).toInt())

/** Resultados mostrados por conversão; o resto fica só na pasta (um vídeo pode virar 300 quadros). */
private const val RESULTADOS_NA_TELA = 12

@Composable
private fun CartaoDoTrabalho(trabalho: br.com.smellsliketech.converter.conversao.Trabalho, cancelar: () -> Unit, salvar: (Transcricao, String) -> Unit) {
    val contexto = LocalContext.current
    Painel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(trabalho.titulo, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (!trabalho.terminado) TextButton(onClick = cancelar) { Text("Cancelar") }
        }
        Text(trabalho.arquivos.joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (!trabalho.terminado) {
            if (trabalho.progresso <= 0) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(progress = { trabalho.progresso / 100f }, modifier = Modifier.fillMaxWidth())
            Text(textoDoAndamento(trabalho.progresso, trabalho.restante), fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(trabalho.etapa, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trabalho.erro?.let { Aviso(it) }
        trabalho.transcricoes.forEach { transcricao ->
            CaixaDaTranscricao(transcricao, mostrarNome = trabalho.transcricoes.size > 1, salvando = trabalho.salvando) { formato -> salvar(transcricao, formato) }
        }
        trabalho.resultados.take(RESULTADOS_NA_TELA).forEach { resultado ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    NomeDoArquivo(resultado.nome)
                    Text(tamanho(resultado.bytes), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
                TextButton(onClick = { abrir(contexto, resultado) }) { Text("Abrir") }
                TextButton(onClick = { compartilhar(contexto, resultado) }) { Text("Enviar") }
            }
        }
        val resto = trabalho.resultados.size - RESULTADOS_NA_TELA
        if (resto > 0) Text("E mais $resto ${if (resto == 1) "arquivo" else "arquivos"}.", fontSize = 13.sp)
        if (trabalho.resultados.isNotEmpty() && trabalho.erro == null) {
            Text("Salvo em Downloads/SmellsLikeTech.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
    }
}

/** Formatos de "Salvar como" e o rótulo de cada um. */
private val ROTULOS_DE_SALVAR = mapOf("txt" to "TXT", "pdf" to "PDF", "docx" to "Word (.docx)", "srt" to "Legenda SRT", "vtt" to "Legenda VTT", "md" to "Markdown")

/** O texto transcrito numa caixa, para ler e copiar, e os formatos para salvar. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CaixaDaTranscricao(transcricao: Transcricao, mostrarNome: Boolean, salvando: Boolean, salvar: (String) -> Unit) {
    val contexto = LocalContext.current
    val escopo = rememberCoroutineScope()
    var copiado by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (mostrarNome) Text(transcricao.nomeOriginal, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.background,
            border = BorderStroke(1.dp, Cores.borda),
            modifier = Modifier.fillMaxWidth(),
        ) {
            SelectionContainer(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()).padding(12.dp)) {
                Text(transcricao.texto, fontSize = 15.sp, lineHeight = 22.sp)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = {
                val area = contexto.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                area.setPrimaryClip(ClipData.newPlainText("Transcrição", transcricao.texto))
                copiado = true
                escopo.launch { delay(2_000); copiado = false }
            }) { Text(if (copiado) "Copiado" else "Copiar o texto") }
            Text("${transcricao.palavras} palavras", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
        Text("Salvar como", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Transcricao.FORMATOS.forEach { formato ->
                OutlinedButton(onClick = { salvar(formato) }, enabled = !salvando) { Text(ROTULOS_DE_SALVAR[formato] ?: formato.uppercase(), fontSize = 13.sp) }
            }
        }
    }
}

/**
 * A licença. As duas variantes mudam só aqui: a do site manda comprar no site; a da Google
 * Play vende pelo Google Play e não pode apontar para outro jeito de pagar (regra da loja),
 * então nela o texto não fala do site. A chave de quem já comprou vale nas duas.
 */
@Composable
private fun TelaDaLicenca(modelo: OficinaViewModel, fechar: () -> Unit) {
    val contexto = LocalContext.current
    val atividade = contexto.atividade()
    val pelaPlay = BuildConfig.PELA_PLAY
    val escopo = rememberCoroutineScope()
    var chave by remember { mutableStateOf("") }
    var mensagem by remember { mutableStateOf<String?>(null) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(Modifier.fillMaxSize().safeDrawingPadding(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Licença", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    TextButton(onClick = fechar) { Text("Voltar") }
                }
            }
            item {
                Painel {
                    when (val situacao = modelo.situacao) {
                        is Acesso.Situacao.Ativa -> {
                            Rotulo("LICENÇA ATIVA")
                            Text("${nomeDoPlano(situacao.carga.plano)} · ${situacao.carga.email}", fontWeight = FontWeight.SemiBold)
                            Text("Pedido ${situacao.carga.id} · ${if (situacao.carga.expira == null) "não vence" else "vale até ${situacao.carga.expira.take(10)}"}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                            OutlinedButton(onClick = modelo::removerLicenca) { Text("Remover deste celular") }
                        }
                        is Acesso.Situacao.Comprada -> {
                            Rotulo("COMPRADO NA GOOGLE PLAY")
                            Text("Aplicativo para Android · vitalício", fontWeight = FontWeight.SemiBold)
                            Text("Pedido ${situacao.recibo.pedido} · não vence", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                        }
                        is Acesso.Situacao.Teste -> {
                            Rotulo("TESTE GRÁTIS")
                            Text("Faltam ${situacao.diasRestantes} ${if (situacao.diasRestantes == 1) "dia" else "dias"} de teste completo.", fontWeight = FontWeight.SemiBold)
                            Text("Depois, o app pede a licença vitalícia do Android.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                        }
                        Acesso.Situacao.TesteAcabou -> {
                            Rotulo("O TESTE ACABOU")
                            Text("Para continuar convertendo, ative a licença do app para Android.", fontWeight = FontWeight.SemiBold)
                        }
                    }
                    val ativa = modelo.situacao is Acesso.Situacao.Ativa || modelo.situacao is Acesso.Situacao.Comprada
                    if (!ativa) {
                        Button(onClick = { atividade?.let(modelo::comprar) }, enabled = atividade != null) {
                            Text(modelo.precoDaLoja?.let { "Comprar a licença vitalícia · $it" } ?: "Comprar a licença vitalícia")
                        }
                    }
                    if (pelaPlay && modelo.situacao !is Acesso.Situacao.Comprada) {
                        TextButton(onClick = modelo::restaurar) { Text("Já comprei: restaurar a compra") }
                    }
                    modelo.recadoDaLoja?.let { Aviso(it) }
                }
            }
            item {
                Painel {
                    Rotulo("ATIVAR UMA CHAVE")
                    Text(
                        if (pelaPlay) "Tem uma chave de licença (SLT1…)? Cole aqui. Ela é conferida no celular, sem internet."
                        else "Cole a chave que chegou no e-mail da compra (ou em Minha conta, no site). Ela é conferida aqui, sem internet.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                    )
                    OutlinedTextField(
                        value = chave,
                        onValueChange = { chave = it },
                        placeholder = { Text("SLT1.…") },
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth().height(120.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = {
                            val area = contexto.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            area.primaryClip?.getItemAt(0)?.coerceToText(contexto)?.toString()?.let { chave = it }
                        }) { Text("Colar") }
                        Button(onClick = {
                            escopo.launch {
                                mensagem = when (val resultado = modelo.ativar(chave)) {
                                    is Acesso.Ativacao.Ativada -> "Pronto: ${nomeDoPlano(resultado.carga.plano)} ativado neste celular.".also { chave = "" }
                                    is Acesso.Ativacao.Recusada -> resultado.motivo
                                }
                            }
                        }, enabled = chave.isNotBlank()) { Text("Ativar") }
                    }
                    mensagem?.let { Aviso(it) }
                }
            }
            item {
                Text(
                    if (pelaPlay) "A compra pela Google Play não vence e vale nos celulares com a mesma conta Google."
                    else "A licença vale em até 3 celulares seus e não vence. Minha conta, no site, guarda todas as suas chaves.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
            }
            // A Google Play exige o link da política dentro do app. Abre no navegador.
            item {
                TextButton(onClick = { runCatching { contexto.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(POLITICA_DE_PRIVACIDADE))) } }) {
                    Text("Política de privacidade")
                }
            }
        }
    }
}

@Composable
private fun DialogoDeNarracao(modelo: OficinaViewModel, inicial: String, aoFechar: () -> Unit) {
    var texto by remember { mutableStateOf(inicial) }
    val opcoes = modelo.opcoesDaNarracao
    AlertDialog(
        onDismissRequest = aoFechar,
        title = { Text("Narrar um texto") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text("Kokoro-82M em português, inteiramente no celular e sem internet.", fontSize = 13.sp)
                OutlinedTextField(value = texto, onValueChange = { texto = it }, modifier = Modifier.fillMaxWidth().height(160.dp))
                Escolha("Formato", Ferramenta.TEXTO_NARRAR.formatos, opcoes.formato, ::maiusculo) { modelo.opcoesDaNarracao = opcoes.copy(formato = it) }
                AjustesDaFala(opcoes) { modelo.opcoesDaNarracao = it }
            }
        },
        confirmButton = { Button(onClick = { modelo.narrar(texto) }, enabled = texto.isNotBlank()) { Text("Narrar") } },
        dismissButton = { TextButton(onClick = aoFechar) { Text("Cancelar") } },
    )
}

/** Os modelos do Whisper: baixar uma vez (da internet), depois transcrever sem internet. */
@Composable
private fun TelaDosModelos(modelo: OficinaViewModel, fechar: () -> Unit) {
    val contexto = LocalContext.current
    LaunchedEffect(Unit) {
        while (true) {
            modelo.atualizarModelos()
            delay(1_000)
        }
    }
    val livre = remember(modelo.estadoDosModelos.values.toList()) {
        runCatching { StatFs((contexto.getExternalFilesDir(null) ?: contexto.filesDir).path).availableBytes }.getOrDefault(0L)
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(Modifier.fillMaxSize().safeDrawingPadding(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Modelos de transcrição", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    TextButton(onClick = fechar) { Text("Voltar") }
                }
            }
            item {
                Text(
                    "A transcrição usa o Whisper, no próprio celular. Cada modelo é baixado uma vez, do repositório oficial do whisper.cpp; depois tudo funciona sem internet, e o áudio nunca sai do aparelho. Prefira o Wi-Fi: os arquivos são grandes.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                )
            }
            if (livre > 0) item { Dica("Espaço livre no celular: ${tamanho(livre)}") }
            items(ModeloWhisper.entries, key = { it.id }) { item ->
                val estado = modelo.estadoDosModelos[item] ?: ModelosWhisper.Estado.Ausente
                val cabe = livre == 0L || livre > item.bytes + 50_000_000
                Painel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(item.rotulo, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text(tamanho(item.bytes), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                    Text(item.nota, fontSize = 13.sp)
                    when (estado) {
                        ModelosWhisper.Estado.Ausente -> Button(onClick = { modelo.baixarModelo(item) }, enabled = cabe) { Text(if (cabe) "Baixar" else "Falta espaço") }
                        is ModelosWhisper.Estado.Baixando -> {
                            if (estado.bytes <= 0) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            else LinearProgressIndicator(progress = { estado.bytes.toFloat() / estado.total.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
                            val andamento = remember(item) { Andamento() }
                            val porcentagem = (estado.bytes * 100 / estado.total.coerceAtLeast(1)).toInt()
                            val restante = remember(estado.bytes) { andamento.registrar(porcentagem) }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Baixando: ${tamanho(estado.bytes).ifEmpty { "0 MB" }} de ${tamanho(estado.total)}", fontSize = 12.sp)
                                    Text(textoDoAndamento(porcentagem, restante), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                }
                                TextButton(onClick = { modelo.cancelarModelo(item) }) { Text("Cancelar") }
                            }
                        }
                        ModelosWhisper.Estado.Conferindo -> {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Text("Conferindo o arquivo…", fontSize = 12.sp)
                        }
                        ModelosWhisper.Estado.Pronto -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("No celular, pronto para usar.", color = MaterialTheme.colorScheme.secondary, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            TextButton(onClick = { modelo.apagarModelo(item) }) { Text("Apagar") }
                        }
                        is ModelosWhisper.Estado.Falhou -> {
                            Aviso(estado.motivo)
                            Button(onClick = { modelo.baixarModelo(item) }, enabled = cabe) { Text("Tentar de novo") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Painel(conteudo: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, Cores.borda),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { conteudo() }
    }
}

/** Uma linha, com reticências no nome e a extensão sempre à vista: "IMG_…-e-mais-2.pdf". */
@Composable
private fun NomeDoArquivo(nome: String) {
    val ponto = nome.lastIndexOf('.').takeIf { it > 0 } ?: nome.length
    Row {
        Text(nome.substring(0, ponto), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Text(nome.substring(ponto), fontSize = 13.sp, maxLines = 1)
    }
}

@Composable
private fun Rotulo(texto: String, modifier: Modifier = Modifier) {
    Text(texto, color = Cores.ambar, fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.SemiBold, modifier = modifier)
}

@Composable
private fun Aviso(texto: String) {
    Surface(shape = RoundedCornerShape(8.dp), color = Cores.amarelo.copy(alpha = 0.08f), border = BorderStroke(1.dp, Cores.amarelo.copy(alpha = 0.35f))) {
        Row(Modifier.padding(10.dp)) {
            Spacer(Modifier.width(2.dp))
            Text(texto, color = Cores.amarelo, fontSize = 13.sp)
        }
    }
}

private fun nomeDoPlano(plano: String) = when (plano) {
    "android" -> "Aplicativo para Android"
    "completo" -> "Tudo, para sempre"
    else -> plano
}

fun tamanho(bytes: Long): String = when {
    bytes <= 0 -> ""
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.0f KB".format(Locale("pt", "BR"), bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(Locale("pt", "BR"), bytes / 1048576.0)
    else -> "%.2f GB".format(Locale("pt", "BR"), bytes / 1073741824.0)
}

private fun abrirNoNavegador(contexto: Context, endereco: String) {
    runCatching { contexto.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(endereco))) }
}

private fun abrir(contexto: Context, resultado: Resultado) {
    val intencao = Intent(Intent.ACTION_VIEW).setDataAndType(resultado.uri, resultado.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { contexto.startActivity(intencao) }
}

private fun compartilhar(contexto: Context, resultado: Resultado) {
    val intencao = Intent(Intent.ACTION_SEND).setType(resultado.mime).putExtra(Intent.EXTRA_STREAM, resultado.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    contexto.startActivity(Intent.createChooser(intencao, "Enviar ${resultado.nome}"))
}

/** A tela vive dentro da MainActivity; a compra da Google Play precisa dela para abrir. */
private fun Context.atividade(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.atividade()
    else -> null
}
