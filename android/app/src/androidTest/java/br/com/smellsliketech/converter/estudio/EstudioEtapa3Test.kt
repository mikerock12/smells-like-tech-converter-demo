package br.com.smellsliketech.converter.estudio

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import br.com.smellsliketech.converter.conversao.ffmpeg.Ffmpeg
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class EstudioEtapa3Test {
    private val contexto=InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun animacaoMascaraFaixaLivreEProjetoReaberto() = runBlocking {
        val pasta=File(contexto.cacheDir,"etapa3-instrumentado").apply {mkdirs()}
        fun foto(nome: String,cor: Int): File {
            val b=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888);b.eraseColor(cor)
            return File(pasta,nome).also {f->f.outputStream().use {b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
        }
        try {
            val preto=foto("preto.png",Color.BLACK);val branco=foto("branco.png",Color.WHITE)
            val t=Transformacao(escala=.5,mascara="circulo",quadrosChave=listOf(QuadroChave(0.0,.25,.5,.5),QuadroChave(1.5,.75,.5,.5)))
            val p=Projeto(largura=64,altura=64,fps=10,saida="imagem",
                clipes=listOf(Clipe("c",preto.absolutePath,"imagem",duracao=.2,ajuste="caber")),
                camadas=listOf(Camada("l",branco.absolutePath,inicio=0.0,fim=1.5,transformacao=t)))
            val zip=File(pasta,"projeto.sltstudio");zip.outputStream().use {ArquivoDoProjeto.salvar(p,it)}
            val reaberto=zip.inputStream().use {ArquivoDoProjeto.abrir(it,File(pasta,"reaberto"),File(pasta,"temporario.zip"))}
            for(instante in listOf(0.0,1.4)) {
                val saida=File(pasta,"quadro-$instante.png");Exportador.gerar(contexto,reaberto.copy(quadro=instante),saida) {}
                val b=BitmapFactory.decodeFile(saida.absolutePath)
                try {
                    val novo=if(instante==0.0) 16 else 46;val antigo=if(instante==0.0) 46 else 8
                    assertTrue("centro acompanha animação",Color.red(b.getPixel(novo,32))>230)
                    assertTrue("posição anterior limpa",Color.red(b.getPixel(antigo,32))<20)
                    assertTrue("canto da máscara limpo",Color.red(b.getPixel(if(instante==0.0) 0 else 30,16))<20)
                } finally {b.recycle()}
            }
            val video=File(pasta,"composto.mp4");Exportador.gerar(contexto,reaberto.copy(saida="video"),video) {}
            val s=Ffmpeg.sondar(contexto,video);assertTrue(s.temVideo);assertEquals(1.5,s.duracaoSegundos!!,.2)
            Log.i("EstudioEtapa3","Animação/máscara/projeto portátil: vídeo ${video.length()} bytes, ${s.duracaoSegundos} s")
        } finally {pasta.deleteRecursively()}
        Unit
    }
    @Test fun projetoDoNavegadorAbreComNarracao() = runBlocking {
        val pasta=File(contexto.cacheDir,"etapa3-interoperavel").apply {mkdirs()}
        try {
            val instrumentacao=InstrumentationRegistry.getInstrumentation()
            val zip=File(pasta,"navegador.sltstudio")
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentacao.uiAutomation.executeShellCommand("cat /data/local/tmp/estudio-portatil.sltstudio")).use {entrada->zip.outputStream().use {entrada.copyTo(it)}}
            assertTrue("Projeto real salvo pelo navegador",zip.length()>1000)
            val p=zip.inputStream().use {ArquivoDoProjeto.abrir(it,File(pasta,"reaberto"),File(pasta,"temporario.zip"))}
            assertEquals(4,p.versao);assertEquals(2,p.audios.size);assertEquals(4.5,Estudio.duracaoTotal(p),.01)
            val audio=File(pasta,"mixado.mp3");Exportador.gerar(contexto,p.copy(saida="audio"),audio) {}
            val info=Ffmpeg.sondar(contexto,audio);assertTrue(info.temAudio);assertEquals(4.5,info.duracaoSegundos!!,.2)
            val resalvo=File(pasta,"resalvo.sltstudio");resalvo.outputStream().use {ArquivoDoProjeto.salvar(p,it)}
            assertTrue(resalvo.length()>zip.length()*.9)
            Log.i("EstudioEtapa3","Projeto do navegador com narração abriu e exportou áudio no Android: ${audio.length()} bytes")
        } finally {pasta.deleteRecursively()}
        Unit
    }

}
