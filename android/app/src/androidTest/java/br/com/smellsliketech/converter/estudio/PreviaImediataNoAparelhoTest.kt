package br.com.smellsliketech.converter.estudio

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import br.com.smellsliketech.converter.conversao.ffmpeg.Ffmpeg
import br.com.smellsliketech.converter.ui.PlayerImediato
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PreviaImediataNoAparelhoTest {
    private val instrumentacao=InstrumentationRegistry.getInstrumentation()
    private val contexto=instrumentacao.targetContext
    private val pasta=File(contexto.cacheDir,"previa-imediata").apply {mkdirs()}
    private var atividade: ComponentActivity?=null
    private var player: PlayerImediato?=null
    private var falhas=0

    private fun tela(bloco: ()->Unit)=instrumentacao.runOnMainSync(bloco)
    private fun abrir(arquivo: File,projeto: Projeto,instante: Double=0.0,tocando: Boolean=false): CountDownLatch {
        val preparado=CountDownLatch(1)
        atividade=instrumentacao.startActivitySync(Intent().setClassName(contexto.packageName,"br.com.smellsliketech.converter.EditorDeTesteActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as ComponentActivity
        tela {
            player=PlayerImediato(atividade!!).also {p->
                p.aoPreparar={preparado.countDown()};p.aoFalhar={falhas++}
                atividade!!.setContentView(p,ViewGroup.LayoutParams(128,128))
                p.atualizar(projeto,instante,tocando)
            }
        }
        assertTrue("arquivo de teste existe",arquivo.isFile)
        assertTrue("MediaPlayer preparou",preparado.await(15,TimeUnit.SECONDS))
        instrumentacao.waitForIdleSync();Thread.sleep(1000)
        assertEquals(0,falhas)
        return preparado
    }

    private fun captura(): Bitmap {
        val atual=atividade!!;val view=player!!
        val rect=Rect();tela {view.getGlobalVisibleRect(rect)}
        assertTrue("player visível $rect",rect.width()>=128&&rect.height()>=128)
        val bitmap=Bitmap.createBitmap(rect.width(),rect.height(),Bitmap.Config.ARGB_8888)
        val copiado=CountDownLatch(1);var resultado=-1
        PixelCopy.request(atual.window,rect,bitmap,{resultado=it;copiado.countDown()},Handler(Looper.getMainLooper()))
        assertTrue("PixelCopy respondeu",copiado.await(10,TimeUnit.SECONDS))
        assertEquals(PixelCopy.SUCCESS,resultado)
        return bitmap
    }

    private fun atualizar(p: Projeto,instante: Double=0.0) {
        tela {player!!.atualizar(p,instante,false)}
        instrumentacao.waitForIdleSync();Thread.sleep(1000)
    }

    private fun perto(pixel: Int,cor: Int,tolerancia: Int=45) = listOf(Color.red(pixel)-Color.red(cor),Color.green(pixel)-Color.green(cor),Color.blue(pixel)-Color.blue(cor)).all {kotlin.math.abs(it)<=tolerancia}

    private fun videoDosQuadrantes(): File=runBlocking {
        val png=File(pasta,"quadrantes.png");val video=File(pasta,"quadrantes.mp4")
        val bitmap=Bitmap.createBitmap(128,64,Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawRect(0f,0f,64f,32f,Paint().apply {color=Color.RED})
            drawRect(64f,0f,128f,32f,Paint().apply {color=Color.GREEN})
            drawRect(0f,32f,64f,64f,Paint().apply {color=Color.BLUE})
            drawRect(64f,32f,128f,64f,Paint().apply {color=Color.YELLOW})
        }
        png.outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        Ffmpeg.executar(contexto,listOf("-v","error","-y","-loop","1","-i",png.absolutePath,"-t","2","-r","10","-c:v","mpeg4","-q:v","2","-pix_fmt","yuv420p",video.absolutePath),null)
        assertTrue(Ffmpeg.sondar(contexto,video).temVideo)
        video
    }

    private fun projeto(video: File,ajuste: String="caber",transformacao: Transformacao?=null)=Projeto(
        largura=128,altura=128,fps=10,fundo=FundoDoProjeto("#FF7A18"),
        clipes=listOf(Clipe("c",video.absolutePath,"video",inicio=0.0,fim=2.0,ajuste=ajuste,transformacao=transformacao)),
    )

    @Test fun pixelsFundoCorMascaraPosicaoEAnimacao() {
        val video=videoDosQuadrantes()
        val origem=runBlocking {br.com.smellsliketech.converter.figurinhas.Producao.quadro(contexto,video,0.0)}
        android.util.Log.i("PreviaImediata","origem ${origem.width}×${origem.height}; azul=${Integer.toHexString(origem.getPixel(20,40))}; verde=${Integer.toHexString(origem.getPixel(100,10))}");origem.recycle()
        abrir(video,projeto(video))
        captura().let {b->
            try {
                android.util.Log.i("PreviaImediata","captura ${b.width}×${b.height}; fundo=${Integer.toHexString(b.getPixel(64,5))}; azul=${Integer.toHexString(b.getPixel(20,80))}; verde=${Integer.toHexString(b.getPixel(100,40))}; centro=${Integer.toHexString(b.getPixel(64,64))}")
                assertTrue("fundo laranja acima",perto(b.getPixel(64,5),Color.rgb(255,122,24)))
                assertTrue("quadrante azul: ${Integer.toHexString(b.getPixel(20,80))}",perto(b.getPixel(20,80),Color.BLUE))
                assertTrue("quadrante verde",perto(b.getPixel(100,40),Color.GREEN))
            } finally {b.recycle()}
        }
        atualizar(projeto(video,transformacao=Transformacao(filtro="negativo")))
        captura().let {b->
            try {
                assertTrue("negativo aplicado ao vídeo",perto(b.getPixel(20,40),Color.CYAN,65))
                assertTrue("filtro não altera o fundo",perto(b.getPixel(64,5),Color.rgb(255,122,24)))
            } finally {b.recycle()}
        }
        atualizar(projeto(video,"preencher",Transformacao(mascara="circulo")))
        captura().let {b->
            try {assertTrue("canto fora do círculo",perto(b.getPixel(2,2),Color.rgb(255,122,24)))} finally {b.recycle()}
        }
        atualizar(projeto(video,transformacao=Transformacao(x=.25,escala=.5)))
        captura().let {b->
            try {assertTrue("espaço liberado pela posição/escala",perto(b.getPixel(110,64),Color.rgb(255,122,24)))} finally {b.recycle()}
        }
        val animada=Transformacao(quadrosChave=listOf(QuadroChave(0.0,.25,.5,1.0),QuadroChave(1.0,.75,.5,1.0)))
        atualizar(projeto(video,transformacao=animada),1.0)
        captura().let {b->
            try {assertTrue("quadro-chave moveu a mídia",perto(b.getPixel(110,40),Color.GREEN,60))} finally {b.recycle()}
        }
        assertEquals(0,falhas)
    }

    @Test fun videoDe60SegundosAbreSemRenderizacaoPrevia()=runBlocking {
        val curto=videoDosQuadrantes();val longo=File(pasta,"longo-60.mp4")
        Ffmpeg.executar(contexto,listOf("-v","error","-y","-stream_loop","29","-i",curto.absolutePath,"-t","60","-c","copy",longo.absolutePath),null)
        assertEquals(60.0,Ffmpeg.sondar(contexto,longo).duracaoSegundos!!,.2)
        val p=Projeto(largura=128,altura=128,fps=10,fundo=FundoDoProjeto("#FF7A18"),
            clipes=listOf(Clipe("c",longo.absolutePath,"video",inicio=0.0,fim=60.0,ajuste="caber")))
        assertTrue(PreviaImediata.aceita(p))
        val inicio=System.nanoTime();abrir(longo,p);val milis=(System.nanoTime()-inicio)/1_000_000
        android.util.Log.i("PreviaImediata","vídeo de 60 s preparado para tocar em $milis ms, sem criar MP4 de prévia")
        assertTrue("abertura imediata em $milis ms",milis<10_000)
        captura().let {b->try {assertTrue(perto(b.getPixel(20,80),Color.BLUE))} finally {b.recycle()}}
        Unit
    }

    @Test fun reproducaoSeekVelocidadeETrilhaDeAudio() {
        val video=File(pasta,"com-audio.mp4")
        instrumentacao.context.assets.open("video-com-audio.mp4").use {entrada->video.outputStream().use {entrada.copyTo(it)}}
        val retriever=MediaMetadataRetriever()
        try {
            retriever.setDataSource(video.absolutePath)
            assertEquals("yes",retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))
        } finally {retriever.release()}
        val base=Clipe("c",video.absolutePath,"video",inicio=.25,fim=1.75,ajuste="caber",volume=.8,velocidade=1.25)
        val p=Projeto(largura=128,altura=128,fps=10,fundo=FundoDoProjeto("#FF7A18"),clipes=listOf(base))
        val tempos=Collections.synchronizedList(mutableListOf<Double>());val terminado=CountDownLatch(1)
        abrir(video,p,.75,false)
        tela {player!!.aoAvancar={tempos+=it};player!!.aoTerminar={terminado.countDown()};player!!.atualizar(p,.75,true)}
        assertTrue("reprodução terminou sem loop de seek",terminado.await(10,TimeUnit.SECONDS))
        assertTrue("relógio avançou $tempos",tempos.isNotEmpty()&&tempos.last()>1.0)
        assertTrue("relógio monotônico",tempos.zipWithNext().all {(a,b)->b>=a-.05})
        assertFalse("player não falhou",falhas>0)
        tela {player!!.liberar()}
    }

    @After fun limpar() {
        tela {player?.liberar();atividade?.finish()};player=null;atividade=null;pasta.deleteRecursively()
    }
}
