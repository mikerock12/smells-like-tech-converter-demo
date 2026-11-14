package br.com.smellsliketech.converter.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.smellsliketech.converter.conversao.Opcoes
import br.com.smellsliketech.converter.musica.GrupoDoPacote
import br.com.smellsliketech.converter.musica.MasterDaMusica
import br.com.smellsliketech.converter.musica.PacoteDeFaixas
import br.com.smellsliketech.converter.musica.PedidoDoPacote
import br.com.smellsliketech.converter.musica.ProblemaNoPacote
import br.com.smellsliketech.converter.musica.RoFormer
import br.com.smellsliketech.converter.musica.SeparacaoDaMusica

/**
 * O que gerar e como soar, antes de separar (ferramentas experimentais do conversor): o
 * modelo, os arquivos do .zip (cada um junta as faixas marcadas), o formato, o volume final e
 * os efeitos de cada faixa — o mesmo editor do estúdio musical. No vídeo, o que fica no áudio.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AjustesDasFaixas(video: Boolean, opcoes: Opcoes, mudar: (Opcoes) -> Unit) {
    val pedido = remember(opcoes.pacote) {
        try { PacoteDeFaixas.lerPedido(opcoes.pacote, manterPadrao = if (video) "resto" else null) }
        catch (_: ProblemaNoPacote) { PacoteDeFaixas.lerPedido(null, manterPadrao = if (video) "resto" else null) }
    }
    // Grupos em edição podem ficar inválidos por um momento (sem faixa, sem nome): guardados à parte.
    var grupos by remember(opcoes.pacote) { mutableStateOf(pedido.grupos) }
    val modelo = PacoteDeFaixas.modelo(pedido.modelo)!!
    fun gravar(novo: PedidoDoPacote) = mudar(opcoes.copy(pacote = novo.paraJson().toString()))
    fun gravarGrupos(novos: List<GrupoDoPacote>) {
        grupos = novos
        if (PacoteDeFaixas.problemas(pedido.modelo, novos).isEmpty()) gravar(pedido.copy(grupos = novos))
    }
    var alvo by rememberSaveable { mutableStateOf(if (video) "master" else "vozes") }
    var verEfeitos by rememberSaveable { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Experimental: a separação por IA nunca é perfeita e pode levar vários minutos (o RoFormer, bem mais). Continua com a tela apagada ou em outro app, com o andamento na notificação.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        Text("Modelo de separação", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PacoteDeFaixas.MODELOS.forEach { m ->
                val pode = m.id != "roformer" || RoFormer.disponivel
                FilterChip(selected = m.id == pedido.modelo, enabled = pode, onClick = {
                    val configuracao = PacoteDeFaixas.configuracao(m.id).let { base ->
                        base.copy(faixas = base.faixas.map { f -> pedido.configuracao.faixas.firstOrNull { it.id == f.id } ?: f }, master = pedido.configuracao.master)
                    }
                    val novos = if (video) listOf(PacoteDeFaixas.grupoDoVideo(m.id, pedido.manter ?: "resto"))
                        else grupos.map { g -> g.copy(faixas = g.faixas.filter { it in m.faixas }) }.filter { it.faixas.isNotEmpty() }.ifEmpty { PacoteDeFaixas.combinacoes(m.id)[0].grupos }
                    gravar(pedido.copy(modelo = m.id, configuracao = configuracao, grupos = novos))
                }, label = { Text(m.rotulo, fontSize = 12.sp) })
            }
        }
        Dica(modelo.resumo + if (!RoFormer.disponivel) " O RoFormer precisa de um celular de 64 bits." else "")
        if (pedido.modelo == "spleeter") AceleradorDoAparelho()

        if (video) {
            Text("O que fica no áudio do vídeo", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("resto" to "Tirar a voz, manter o resto", "voz" to "Manter só a voz").forEach { (id, rotulo) ->
                    FilterChip(selected = (pedido.manter ?: "resto") == id, onClick = { gravar(pedido.copy(manter = id, grupos = listOf(PacoteDeFaixas.grupoDoVideo(pedido.modelo, id)))) },
                        label = { Text(rotulo, fontSize = 12.sp) })
                }
            }
        } else {
            Text("Começar de uma combinação pronta", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PacoteDeFaixas.combinacoes(pedido.modelo).forEach { c ->
                    FilterChip(selected = c.grupos == grupos, onClick = { gravarGrupos(c.grupos) }, label = { Text(c.rotulo, fontSize = 12.sp) })
                }
            }
            Text("Arquivos dentro do .zip (${grupos.size})", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            grupos.forEachIndexed { i, g ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(g.nome, { nome -> gravarGrupos(grupos.mapIndexed { k, x -> if (k == i) x.copy(nome = nome.take(60)) else x }) },
                                label = { Text("Arquivo ${i + 1}") }, singleLine = true, modifier = Modifier.weight(1f))
                            TextButton(onClick = { gravarGrupos(grupos.filterIndexed { k, _ -> k != i }) }, enabled = grupos.size > 1) { Text("Tirar") }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            modelo.faixas.forEach { f ->
                                FilterChip(selected = f in g.faixas, onClick = {
                                    val faixas = if (f in g.faixas) g.faixas - f else modelo.faixas.filter { it == f || it in g.faixas }
                                    val automatico = g.nome == PacoteDeFaixas.nomeDoGrupo(g.faixas, modelo)
                                    gravarGrupos(grupos.mapIndexed { k, x -> if (k == i) GrupoDoPacote(if (automatico) PacoteDeFaixas.nomeDoGrupo(faixas, modelo) else x.nome, faixas) else x })
                                }, label = { Text(PacoteDeFaixas.rotulo(f), fontSize = 12.sp) })
                            }
                        }
                    }
                }
            }
            OutlinedButton(onClick = { gravarGrupos(grupos + GrupoDoPacote("Arquivo ${grupos.size + 1}", emptyList())) }, enabled = grupos.size < PacoteDeFaixas.MAXIMO_DE_ARQUIVOS) { Text("+ Arquivo") }
            PacoteDeFaixas.problemas(pedido.modelo, grupos).forEach { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            val formatos = MasterDaMusica.FORMATOS.filter { it.id in setOf("wav-24", "wav-16", "flac", "mp3", "aac", "opus") }
            Text("Formato dos arquivos", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                formatos.forEach { f -> FilterChip(selected = f.id == pedido.formato, onClick = { gravar(pedido.copy(formato = f.id)) }, label = { Text(f.rotulo, fontSize = 12.sp) }) }
            }
        }

        Text("Volume final", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MasterDaMusica.PRESETS.forEach { p -> FilterChip(selected = p.id == pedido.master, onClick = { gravar(pedido.copy(master = p.id)) }, label = { Text(p.rotulo, fontSize = 12.sp) }) }
        }

        OutlinedButton(onClick = { verEfeitos = !verEfeitos }, Modifier.fillMaxWidth()) {
            Text(if (video) "Efeitos e equalização no áudio que fica" else "Efeitos e equalização de cada faixa")
        }
        if (verEfeitos) {
            Dica(if (video) "Escolha Master para mexer no áudio inteiro que fica no vídeo, ou uma faixa para mexer só nela."
                else "Cada faixa leva os próprios efeitos para todos os arquivos em que entra. O Master vale para todos os arquivos.")
            EditorDeEfeitosMusicais(pedido.configuracao, alvo, { alvo = it }, rolar = false) { _, f -> gravar(pedido.copy(configuracao = f(pedido.configuracao))) }
        }
        Dica(if (pedido.modelo == "roformer") "Pode haver vazamento entre as faixas. Comece por um trecho curto." else SeparacaoDaMusica.AVISO)
        Dica("Separar músicas de outras pessoas não muda os direitos autorais delas: use em gravações suas ou que você tem direito de usar.")
    }
}

/**
 * Liga o acelerador de IA do aparelho (NNAPI: Snapdragon, MediaTek, Exynos, Tensor) para o
 * Spleeter. Experimental: depende do driver de cada celular; se ele recusar, roda na CPU.
 */
@Composable
internal fun AceleradorDoAparelho() {
    val contexto = androidx.compose.ui.platform.LocalContext.current
    var ligado by remember { mutableStateOf(br.com.smellsliketech.converter.conversao.Processadores.aceleradorLigado(contexto)) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Usar o acelerador de IA do celular (experimental)", fontSize = 13.sp)
            Dica("Snapdragon, MediaTek e outros, pelo NNAPI do Android, sem perder precisão. Se o celular não aceitar, separa na CPU como sempre.")
        }
        androidx.compose.material3.Switch(checked = ligado, onCheckedChange = {
            ligado = it
            br.com.smellsliketech.converter.conversao.Processadores.ligarAcelerador(contexto, it)
        })
    }
}
