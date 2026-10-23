package br.com.smellsliketech.converter.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import br.com.smellsliketech.converter.estudio.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProjetosDoEstudio(modelo: EstudioViewModel) {
    val abrir=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {it?.let(modelo::abrirProjeto)}
    val salvar=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) {it?.let(modelo::salvarProjeto)}
    var aberto by rememberSaveable {mutableStateOf(false)}
    Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
        OutlinedButton({abrir.launch(arrayOf("application/zip","application/octet-stream","*/*"))},enabled=modelo.ocupado==null) {Text("Abrir projeto salvo")}
        if(modelo.projeto?.clipes?.isNotEmpty()==true) {
            OutlinedButton({salvar.launch("projeto-${System.currentTimeMillis()}.sltstudio")},enabled=modelo.ocupado==null) {Text("Salvar projeto com mídias")}
            Text("O arquivo inclui as mídias e abre no site, Windows e Android. Salve antes de iniciar outro projeto.",style=MaterialTheme.typography.bodySmall)
            TextButton({aberto=!aberto}) {Text("Modelos e contagem regressiva")}
            if(aberto) {
                FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    Criacao.modelos.forEach {(id,nome)->OutlinedButton({modelo.aplicarModelo(id)}) {Text(nome)}}
                }
                var segundos by rememberSaveable {mutableStateOf("3")}
                OutlinedTextField(segundos,{segundos=it},label={Text("Contagem (1–10 segundos)")},singleLine=true)
                Button({modelo.adicionarContagem(segundos.toIntOrNull()?:0)}) {Text("Adicionar contagem no cursor")}
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NarracaoDoEstudio(modelo: EstudioViewModel,podeExportar: Boolean,abrirLicenca: ()->Unit) {
    var aberto by rememberSaveable {mutableStateOf(false)}
    TextButton({aberto=!aberto}) {Text("Narrar com Kokoro na linha do tempo")}
    if(!aberto) return
    var texto by rememberSaveable {mutableStateOf("")}
    var voz by rememberSaveable {mutableStateOf("pf_dora")}
    var velocidade by rememberSaveable {mutableFloatStateOf(1f)}
    OutlinedTextField(texto,{if(it.length<=50000) texto=it},label={Text("Texto da narração")},modifier=Modifier.fillMaxWidth())
    FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        listOf("pf_dora" to "Dora","pm_alex" to "Alex","pm_santa" to "Santa").forEach {(id,nome)->FilterChip(voz==id,{voz=id},label={Text(nome)})}
    }
    Text("Velocidade da fala: ${Math.round(velocidade*100)}%")
    Slider(velocidade,{velocidade=it},valueRange=.5f..2f)
    Text("A narração entra no cursor e pode ser cortada, movida e mixada na aba Áudio.",style=MaterialTheme.typography.bodySmall)
    Button({if(podeExportar) modelo.narrarNaLinhaDoTempo(texto,voz,velocidade) else abrirLicenca()},enabled=texto.isNotBlank()&&modelo.projeto?.clipes?.isNotEmpty()==true&&modelo.ocupado==null) {Text("Gerar e adicionar narração")}
}
