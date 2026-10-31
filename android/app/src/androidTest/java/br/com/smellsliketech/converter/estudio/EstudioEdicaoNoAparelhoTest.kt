package br.com.smellsliketech.converter.estudio

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import br.com.smellsliketech.converter.ui.TelaDoEstudio
import br.com.smellsliketech.converter.ui.TemaDoConverter
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class EstudioEdicaoNoAparelhoTest {
    private val instrumentacao=InstrumentationRegistry.getInstrumentation()
    private val contexto=instrumentacao.targetContext
    private fun tela(bloco: ()->Unit)=instrumentacao.runOnMainSync(bloco)
    private fun esperar(condicao: ()->Boolean) {
        val fim=SystemClock.uptimeMillis()+20_000
        while(SystemClock.uptimeMillis()<fim) {
            var ok=false;tela {ok=condicao()};if(ok)return
            Thread.sleep(100)
        }
        error("Estado não apareceu")
    }
    private fun nos(raiz: AccessibilityNodeInfo?=instrumentacao.uiAutomation.rootInActiveWindow): List<AccessibilityNodeInfo> =
        if(raiz==null) emptyList() else listOf(raiz)+(0 until raiz.childCount).flatMap {nos(raiz.getChild(it))}
    private fun no(descricao: String): AccessibilityNodeInfo {
        val fim=SystemClock.uptimeMillis()+20_000
        while(SystemClock.uptimeMillis()<fim) {
            val arvore=nos()
            arvore.firstOrNull {it.contentDescription?.toString()==descricao||it.text?.toString()==descricao}?.let {return it}
            val rolavel=arvore.filter {it.isScrollable}.maxByOrNull {no->android.graphics.Rect().also {no.getBoundsInScreen(it)}.height()}
            if(rolavel!=null&&!rolavel.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) rolavel.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            Thread.sleep(200)
        }
        error("Controle não encontrado: $descricao")
    }
    private fun tocar(descricao: String) {
        repeat(30) {
            if(nos().any {it.contentDescription?.toString()==descricao||it.text?.toString()==descricao}) return@repeat
            val rolavel=nos().filter {it.isScrollable}.maxByOrNull {no->android.graphics.Rect().also {no.getBoundsInScreen(it)}.height()} ?: return@repeat
            if(!rolavel.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) return@repeat
            Thread.sleep(100)
        }
        var alvo=no(descricao);alvo.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
        instrumentacao.waitForIdleSync();Thread.sleep(200);alvo=no(descricao)
        var clicavel: AccessibilityNodeInfo?=alvo
        while(clicavel!=null&&!clicavel.isClickable) clicavel=clicavel.parent
        if(clicavel?.performAction(AccessibilityNodeInfo.ACTION_CLICK)!=true) error("Não foi possível tocar em $descricao")
        instrumentacao.waitForIdleSync();Thread.sleep(250)
    }

    @Test fun previaControlesDivisaoEExplicacoesNaTela()=kotlinx.coroutines.runBlocking {
        val curto=File(contexto.cacheDir,"edicao-estudio-curto.mp4");val arquivo=File(contexto.cacheDir,"edicao-estudio.mp4")
        instrumentacao.context.assets.open("video-com-audio.mp4").use {entrada->curto.outputStream().use {entrada.copyTo(it)}}
        br.com.smellsliketech.converter.conversao.ffmpeg.Ffmpeg.executar(contexto,listOf("-v","error","-y","-stream_loop","29","-i",curto.absolutePath,"-t","60","-c:v","copy","-an",arquivo.absolutePath),null)
        lateinit var modelo:EstudioViewModel
        tela {
            modelo=EstudioViewModel(contexto.applicationContext as Application);modelo.novoProjeto()
            modelo.escolherDestino("personalizado",128,128);modelo.escolherSaida("video")
            modelo.adicionarMidias(listOf(Uri.fromFile(arquivo)))
        }
        esperar {modelo.ocupado==null&&modelo.projeto?.clipes?.size==1}
        val atividade=instrumentacao.startActivitySync(Intent().setClassName(contexto.packageName,"br.com.smellsliketech.converter.EditorDeTesteActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as ComponentActivity
        try {
            tela {atividade.setContent {TemaDoConverter {TelaDoEstudio(modelo,true,{},{},{})}}}
            tocar("Camadas");tocar("Laranja")
            esperar {modelo.previaDireta}
            no("Prévia imediata");tocar("Reproduzir");esperar {modelo.instante>.05&&modelo.tocandoPrevia};tocar("Pausar")
            tocar("Clipes");no("Brilho");no("Contraste");no("Saturação")
            instrumentacao.uiAutomation.takeScreenshot()?.let {bitmap->
                File(contexto.filesDir,"estudio-1-0-8-controles.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
            }
            tocar("Posição");no("Giro no plano (Z)");no("Inclinar para frente/trás (X)");no("Tamanho")
            tocar("Recorte");no("Sem máscara");no("Círculo");no("Cantos arredondados")
            tocar("Animação");no("Ken Burns: aproximar")
            tela {modelo.irPara(1.0)}
            tocar("Dividir clipe no cursor")
            esperar {modelo.projeto?.clipes?.size==2}
            assertEquals(1.0,modelo.projeto!!.clipes[0].fim!!,.001)
            assertEquals(1.0,modelo.projeto!!.clipes[1].inicio!!,.001)
            tocar("Exportar");tocar("Modelos e contagem regressiva")
            no("Fotos em movimento aplica uma aproximação suave (Ken Burns) às fotos da faixa principal. Não transforma fotos em vídeos gerados por IA.")
            no("Contagem regressiva adiciona números, um por segundo, sobre a composição a partir do cursor. Posicione o cursor antes de adicionar.")
        } finally {tela {modelo.novoProjeto();atividade.finish()};curto.delete();arquivo.delete()}
        Unit
    }
}
