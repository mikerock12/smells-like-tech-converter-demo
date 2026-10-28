package br.com.smellsliketech.converter.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.smellsliketech.converter.licenca.Acesso

/** Um caminho da tela inicial: o que é e, em uma frase, o que dá para fazer. */
private data class Caminho(val icone: String, val titulo: String, val descricao: String, val destaque: Boolean, val abrir: () -> Unit)

/**
 * A primeira tela do app: em vez de começar por "Escolher arquivos", diz o que o app faz e
 * leva cada pessoa direto ao caminho certo. A oficina de conversão e o estúdio são as duas
 * grandes portas; transcrever, narrar e figurinhas ficam visíveis porque são os pedidos
 * mais comuns e ninguém os acharia dentro de "converter".
 */
@Composable
fun TelaInicial(
    situacao: Acesso.Situacao,
    abrirLicenca: () -> Unit,
    converter: () -> Unit,
    estudio: () -> Unit,
    transcrever: () -> Unit,
    narrar: () -> Unit,
    figurinhas: () -> Unit,
    modelos: () -> Unit,
) {
    val caminhos = listOf(
        Caminho("⇄", "Converter uma mídia", "Troque o formato, comprima, corte ou junte vídeos, áudios, imagens, PDFs e documentos.", true, converter),
        Caminho("✦", "Criar no estúdio", "Monte vídeo, GIF, imagem ou áudio para Instagram, TikTok, YouTube, WhatsApp e outras redes: cortes, textos e música.", true, estudio),
        Caminho("✎", "Transcrever áudio ou vídeo", "A fala vira texto ou legenda, sem enviar nada para a nuvem.", false, transcrever),
        Caminho("♪", "Narrar um texto", "Seu texto vira áudio com vozes naturais em português.", false, narrar),
        Caminho("☺", "Figurinhas do WhatsApp", "Imagens, GIFs e vídeos viram figurinhas, com recorte, texto e fundo removido.", false, figurinhas),
    )
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            Modifier.fillMaxSize().safeDrawingPadding(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("SMELLS LIKE TECH", color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("CONVERTER", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, letterSpacing = 3.sp)
                    }
                    val (texto, alerta) = when (situacao) {
                        is Acesso.Situacao.Ativa, is Acesso.Situacao.Comprada, is Acesso.Situacao.Assinada -> "Licença ativa" to false
                        is Acesso.Situacao.Teste -> "Teste: ${situacao.diasRestantes} ${if (situacao.diasRestantes == 1) "dia" else "dias"}" to false
                        Acesso.Situacao.TesteAcabou -> "Teste acabou" to true
                    }
                    OutlinedButton(onClick = abrirLicenca, border = BorderStroke(1.dp, if (alerta) Cores.amarelo else Cores.borda)) {
                        Text(texto, fontSize = 12.sp, color = if (alerta) Cores.amarelo else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
            item {
                Text("O que você quer fazer?", fontSize = 26.sp, fontWeight = FontWeight.SemiBold, lineHeight = 30.sp)
                Text(
                    "Tudo acontece no celular: nenhum arquivo é enviado para a internet.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            caminhos.forEach { caminho -> item { CartaoDoCaminho(caminho) } }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.TextButton(onClick = modelos) { Text("Modelos de transcrição") }
                }
            }
        }
    }
}

@Composable
private fun CartaoDoCaminho(caminho: Caminho) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = caminho.abrir),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, if (caminho.destaque) Cores.laranja else Cores.borda),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Surface(shape = RoundedCornerShape(12.dp), color = if (caminho.destaque) Cores.laranja else Cores.borda, modifier = Modifier.size(44.dp)) {
                androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                    Text(caminho.icone, fontSize = 22.sp, color = if (caminho.destaque) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(caminho.titulo, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(caminho.descricao, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 17.sp)
            }
        }
    }
}
