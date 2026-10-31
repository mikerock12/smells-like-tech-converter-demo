package br.com.smellsliketech.converter.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import br.com.smellsliketech.converter.estudio.*
import br.com.smellsliketech.converter.conversao.Opcoes
import java.io.File
import java.util.Locale

/**
 * Controles compartilhados por clipes, textos e mídias sobrepostas. `medidas`: a mídia e o quadro,
 * para o Preencher. `enquadrar`: nos clipes, Preencher e Caber trocam o enquadramento do clipe.
 * `mostrarGuias`: os controles mostram as guias em que a camada ficou, sem encaixar.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ControlesDeTransformacao(
    valor: Transformacao?, permitirRecorte: Boolean = true, duracao: Double = 3.0,
    medidas: GuiasDoEstudio.Medidas? = null, enquadrar: ((String) -> Unit)? = null, mostrarGuias: (Transformacao) -> Unit = {},
    mudarDireto: (Transformacao) -> Unit,
) {
    var secao by rememberSaveable { mutableStateOf("Cor") }
    val t = valor ?: Transformacao()
    // Guias só quando muda onde a camada fica (posição, tamanho ou giro), não com filtro ou brilho.
    val mudar: (Transformacao) -> Unit = {
        mudarDireto(it)
        if (it.x != t.x || it.y != t.y || it.escala != t.escala || it.giro != t.giro || it.giroX != t.giroX || it.giroY != t.giroY) mostrarGuias(it)
    }
    Text("Editar esta mídia", style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("Cor", "Posição", "Recorte", "Animação", "Mescla").forEach { nome ->
            FilterChip(secao == nome, { secao = nome }, label = { Text(nome) })
        }
    }
    if (secao == "Posição" || secao == "Recorte") {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton({ enquadrar?.invoke("preencher"); mudar(Animacao.mudarBase(t, if (enquadrar != null) GuiasDoEstudio.caberInteira(t) else GuiasDoEstudio.preencher(t, medidas))) }) { Text("Preencher") }
        OutlinedButton({ enquadrar?.invoke("caber"); mudar(Animacao.mudarBase(t, GuiasDoEstudio.caberInteira(t))) }) { Text("Caber inteira") }
        OutlinedButton({ mudar(Animacao.mudarBase(t, GuiasDoEstudio.centralizar(t))) }) { Text("Centralizar") }
    }
    Text("Arraste para mover; pinça para zoom; dois toques e arraste para inclinar. As guias ajudam a alinhar.", style = MaterialTheme.typography.bodySmall)
    }
    if (secao == "Recorte") {
    Text("Recorte visual: use Preencher e mova a imagem na prévia para escolher o que fica no quadro. Máscaras recortam o formato sem apagar o original.", style = MaterialTheme.typography.bodySmall)
    Text("Máscara")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("nenhuma" to "Sem máscara", "circulo" to "Círculo", "oval" to "Oval", "arredondada" to "Cantos arredondados").forEach { (id,nome) ->
            FilterChip(t.mascara==id, { mudar(t.copy(mascara=id)) }, label={Text(nome)})
        }
    }
    }
    if (secao == "Animação") {
        var tempo by rememberSaveable { mutableStateOf(0.0) }
        FlowRow {
            TextButton({ mudar(Animacao.kenBurns(t,duracao)) }) { Text("Ken Burns: aproximar") }
            TextButton({ mudar(Animacao.kenBurns(t,duracao,true)) }) { Text("Ken Burns: afastar") }
        }
        ControleDaCamada("Tempo local do novo quadro",tempo,0.0..maxOf(.1,duracao)," s") { tempo=it }
        TextButton({ mudar(t.copy(quadrosChave=(t.quadrosChave.filter { it.tempo!=tempo }+QuadroChave(tempo,t.x,t.y,t.escala)).sortedBy { it.tempo })) }, enabled=t.quadrosChave.size<100) { Text("Gravar posição e tamanho neste tempo") }
        t.quadrosChave.forEachIndexed { i,q ->
            Text("Quadro em ${q.tempo} s")
            fun alterar(novo: QuadroChave) = mudar(t.copy(quadrosChave=t.quadrosChave.mapIndexed { j,a -> if(i==j) novo else a }))
            ControleDaCamada("Horizontal",q.x,-2.0..3.0,"") { alterar(q.copy(x=it)) }
            ControleDaCamada("Vertical",q.y,-2.0..3.0,"") { alterar(q.copy(y=it)) }
            ControleDaCamada("Zoom",q.escala,.05..6.0,"") { alterar(q.copy(escala=it)) }
            Row {
                FilterChip(q.curva=="suave",{ alterar(q.copy(curva=if(q.curva=="suave") "linear" else "suave")) },label={Text("Movimento suave")})
                TextButton({ mudar(t.copy(quadrosChave=t.quadrosChave.filterIndexed { j,_ -> j!=i })) }) { Text("Remover quadro") }
            }
        }
        TextButton({ mudar(t.copy(quadrosChave=emptyList())) }) { Text("Remover animação") }
    }
    if (secao == "Posição" || secao == "Recorte") {
    ControleDaCamada("Giro no plano (Z)", t.giro, -360.0..360.0, "°") { mudar(t.copy(giro = it)) }
    ControleDaCamada("Inclinar para frente/trás (X)", t.giroX, -180.0..180.0, "°") { mudar(t.copy(giroX = it)) }
    ControleDaCamada("Inclinar para esquerda/direita (Y)", t.giroY, -180.0..180.0, "°") { mudar(t.copy(giroY = it)) }
    ControleDaCamada("Posição horizontal", t.x * 100, -200.0..300.0, "%") { mudar(Animacao.mudarBase(t,t.copy(x = it / 100))) }
    ControleDaCamada("Posição vertical", t.y * 100, -200.0..300.0, "%") { mudar(Animacao.mudarBase(t,t.copy(y = it / 100))) }
    ControleDaCamada("Tamanho", t.escala * 100, 5.0..600.0, "%") { mudar(Animacao.mudarBase(t,t.copy(escala = it / 100))) }
    ControleDaCamada("Opacidade", t.opacidade * 100, 0.0..100.0, "%") { mudar(t.copy(opacidade = it / 100)) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(t.espelharX, { mudar(t.copy(espelharX = !t.espelharX)) }, label = { Text("Espelhar horizontal") })
        FilterChip(t.espelharY, { mudar(t.copy(espelharY = !t.espelharY)) }, label = { Text("Espelhar vertical") })
    }
    }
    if (secao == "Mescla") {
    Text("Mesclagem")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("normal" to "Normal", "multiplicar" to "Multiplicar", "clarear" to "Clarear", "sobrepor" to "Sobrepor").forEach { (id, nome) ->
            FilterChip(t.mescla == id, { mudar(t.copy(mescla = id)) }, label = { Text(nome) })
        }
    }
    }
    if (secao == "Cor") {
    Text("Filtro desta camada")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("original" to "Original", "vivido" to "Vívido", "pb" to "Preto e branco", "sepia" to "Sépia", "vintage" to "Retrô", "frio" to "Frio", "quente" to "Quente", "negativo" to "Negativo").forEach { (id,nome) ->
            FilterChip(t.filtro == id, { mudar(t.copy(filtro = id)) }, label = { Text(nome) })
        }
    }
    ControleDaCamada("Brilho", t.brilho * 100, -100.0..100.0, "%") { mudar(t.copy(brilho = it / 100)) }
    ControleDaCamada("Contraste", t.contraste * 100, -80.0..100.0, "%") { mudar(t.copy(contraste = it / 100)) }
    ControleDaCamada("Saturação", t.saturacao * 100, -100.0..100.0, "%") { mudar(t.copy(saturacao = it / 100)) }
    }
    if (permitirRecorte && secao == "Recorte") {
        FilterChip(t.removerFundo, { mudar(t.copy(removerFundo = !t.removerFundo)) }, label = { Text("Remover o fundo desta mídia") })
        Text("Processado no celular. Vídeos são recortados quadro a quadro e podem demorar.", style = MaterialTheme.typography.bodySmall)
    }
    TextButton(onClick = { mudar(Transformacao()) }) { Text("Redefinir os ajustes desta camada") }
}

@Composable
private fun ControleDaCamada(nome: String, valor: Double, limites: ClosedFloatingPointRange<Double>, unidade: String, mudar: (Double) -> Unit) {
    Text("$nome: ${String.format(Locale.ROOT, "%.1f", valor)}$unidade")
    DeslizanteComAjusteFino(valor.toFloat(), { mudar(Math.round(it * 10) / 10.0) },
        limites.start.toFloat()..limites.endInclusive.toFloat(), .1f, nome)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PainelDeCamadasDoEstudio(modelo: EstudioViewModel) {
    val p = modelo.projeto ?: return
    var catalogo by remember { mutableStateOf(false) }
    val importar = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) modelo.adicionarCamada(uri) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button({ importar.launch(arrayOf("image/*", "video/*")) }, enabled = p.clipes.isNotEmpty() && p.camadas.size < 12) { Text("Adicionar mídia por cima") }
        OutlinedButton({ catalogo = true }, enabled = p.clipes.isNotEmpty() && p.camadas.size < 12) { Text("Stickers e GIF stickers") }
        Text("Fundo da composição")
        Text("A cor aparece nas áreas livres e substitui o preenchimento desfocado dos clipes.", style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("#00000000" to "Transparente", "#000000" to "Preto", "#FFFFFF" to "Branco", "#FF7A18" to "Laranja", "#7536E8" to "Roxo").forEach { (cor, nome) ->
                FilterChip(p.fundo?.cor == cor && p.fundo.corFinal == null, { modelo.mudarProjeto { it.copy(fundo = FundoDoProjeto(cor), clipes = it.clipes.map { c -> if (c.ajuste == "caber-desfocado") c.copy(ajuste = "caber") else c }) } }, label = { Text(nome) })
            }
            FilterChip(p.fundo?.corFinal != null, { modelo.mudarProjeto { it.copy(fundo = FundoDoProjeto("#7536E8", "#FF7A18"), clipes = it.clipes.map { c -> if (c.ajuste == "caber-desfocado") c.copy(ajuste = "caber") else c }) } }, label = { Text("Gradiente") })
        }
        p.camadas.sortedBy { it.ordem }.forEach { camada ->
            key(camada.id) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(modelo.infos[camada.arquivo]?.nome ?: File(camada.arquivo).name,
                            Modifier.fillMaxWidth().clickable { modelo.camadaAberta = if (modelo.camadaAberta == camada.id) null else camada.id })
                        Row {
                            Checkbox(camada.visivel, { valor -> modelo.mudarCamada(camada.id) { it.copy(visivel = valor) } })
                            TextButton({ modelo.moverCamada(camada.id, -1) }) { Text("↓") }
                            TextButton({ modelo.moverCamada(camada.id, 1) }) { Text("↑") }
                            TextButton({ modelo.removerCamada(camada.id) }) { Text("Excluir") }
                        }
                        if (modelo.camadaAberta == camada.id) {
                            ControlesDeTransformacao(camada.transformacao, duracao=camada.fim-camada.inicio, medidas = modelo.medidasDaCamada(camada.id),
                                mostrarGuias = { modelo.mostrarGuias(modelo.medidasDaCamada(camada.id), it, modelo.referenciasDaCamada(camada.id)) }) { valor -> modelo.mudarCamada(camada.id) { it.copy(transformacao = valor) } }
                            if (camada.tipo == "video") {
                                Text("Trecho desta camada (independente das outras)", style=MaterialTheme.typography.titleSmall)
                                PreviaDoTrecho(Uri.fromFile(File(camada.arquivo)), true,
                                    Opcoes("mp4", inicioSegundos=camada.deslocamento, fimSegundos=camada.deslocamento+camada.fim-camada.inicio)) { opcoes ->
                                    if (opcoes.fimSegundos > opcoes.inicioSegundos) modelo.mudarCamada(camada.id) {
                                        it.copy(deslocamento=opcoes.inicioSegundos, fim=it.inicio+opcoes.fimSegundos-opcoes.inicioSegundos, repetir=false)
                                    }
                                }
                            }
                            TextButton({ modelo.mudarCamada(camada.id) { it.copy(ordem = if (it.ordem < 0) 1 else -1) } }) {
                                Text(if (camada.ordem < 0) "Trazer para frente do clipe" else "Usar como fundo")
                            }
                            Text("Faixa livre: entrada, saída e ordem independentes. O último quadro principal permanece ao prolongar a composição.",style=MaterialTheme.typography.bodySmall)
                            var entradaLivre by remember(camada.inicio) {mutableStateOf(camada.inicio.toString())}
                            var saidaLivre by remember(camada.fim) {mutableStateOf(camada.fim.toString())}
                            OutlinedTextField(entradaLivre,{entradaLivre=it},label={Text("Entrada livre (s)")},singleLine=true)
                            OutlinedTextField(saidaLivre,{saidaLivre=it},label={Text("Saída livre (s)")},singleLine=true)
                            TextButton({
                                val de=entradaLivre.replace(',','.').toDoubleOrNull();val ate=saidaLivre.replace(',','.').toDoubleOrNull()
                                if(de!=null&&ate!=null&&de.isFinite()&&ate.isFinite()&&de>=0&&ate>de&&ate<=86400) modelo.mudarCamada(camada.id) {it.copy(inicio=de,fim=ate)}
                                else modelo.erro="Use entrada e saída válidas, de 0 a 86400 segundos."
                            }) {Text("Aplicar intervalo livre")}
                            TextButton({runCatching {modelo.mudarProjeto {Criacao.dividirCamada(it,camada.id,modelo.instante,java.util.UUID.randomUUID().toString())}}.onFailure {modelo.erro=it.message}}) {Text("Dividir faixa no cursor")}
                            val total = maxOf(.1, modelo.total)
                            ControleDaCamada("Aparece em", camada.inicio, 0.0..maxOf(0.0, total - .1), " s") { valor -> modelo.mudarCamada(camada.id) { it.copy(inicio = valor, fim = maxOf(valor + .1, it.fim)) } }
                            ControleDaCamada("Termina em", camada.fim, .1..total, " s") { valor -> modelo.mudarCamada(camada.id) { it.copy(fim = maxOf(it.inicio + .1, valor)) } }
                            if (camada.tipo != "imagem") {
                                FilterChip(camada.repetir, { modelo.mudarCamada(camada.id) { it.copy(repetir = !it.repetir) } }, label = { Text("Repetir animação") })
                                modelo.infos[camada.arquivo]?.duracao?.takeIf { it > .1 }?.let { duracao ->
                                    ControleDaCamada("Início no arquivo", camada.deslocamento, 0.0..maxOf(0.0, duracao - .1), " s") { valor -> modelo.mudarCamada(camada.id) { it.copy(deslocamento = valor) } }
                                }
                            }

                        }
                    }
                }
            }
        }
    }
    if (catalogo) SeletorDeStickers("editor", { catalogo = false }) { arquivo ->
        catalogo = false
        modelo.adicionarCamada(Uri.fromFile(arquivo), descartarAoAbrir = true)
    }
}
