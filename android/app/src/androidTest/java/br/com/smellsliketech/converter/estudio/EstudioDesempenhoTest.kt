package br.com.smellsliketech.converter.estudio

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import br.com.smellsliketech.converter.conversao.ffmpeg.Ffmpeg
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class EstudioDesempenhoTest {
    private val instrumentacao=InstrumentationRegistry.getInstrumentation()
    private val contexto=instrumentacao.targetContext

    private suspend fun renderizar(projeto: Projeto,video: File,otimizar: Boolean,destino: File,pasta: File) {
        val receita=Estudio.receita(projeto,EntradasDaReceita(listOf(EntradaDeClipe(video.absolutePath,true)),emptyList(),emptyList()),otimizar=otimizar)
        val filtro=File(pasta,"composicao-${if(otimizar) "nova" else "antiga"}.fffilter").apply {writeText(receita.filtro)}
        val argumentos=listOf("-hide_banner","-nostdin","-v","error","-y","-progress","pipe:1","-nostats")+receita.args+
            listOf("-/filter_complex",filtro.absolutePath)+receita.mapas+
            listOf("-c:v","mpeg4","-q:v","3","-pix_fmt","yuv420p","-r",projeto.fps.toString(),"-c:a","aac","-b:a","96k","-movflags","+faststart",destino.absolutePath)
        Ffmpeg.executar(contexto,argumentos,receita.duracao)
    }

    private suspend fun quadro(video: File,destino: File,instante: Int=30): Bitmap {
        Ffmpeg.executar(contexto,listOf("-v","error","-y","-ss",instante.toString(),"-i",video.absolutePath,"-frames:v","1","-update","1",destino.absolutePath),null)
        return BitmapFactory.decodeFile(destino.absolutePath) ?: error("Quadro não abriu")
    }

    @Test fun videoComGiroEMascaraAceleraSemMudarOsPixels()=runBlocking {
        val pasta=File(contexto.cacheDir,"estudio-desempenho-giro").apply {mkdirs()}
        try {
            val ppm=File(pasta,"gradiente.ppm")
            val w=320;val h=320
            val dados=ByteArray(w*h*3)
            for(y in 0 until h) for(x in 0 until w){val i=(y*w+x)*3;dados[i]=(255*x/(w-1)).toByte();dados[i+1]=(255*y/(h-1)).toByte();dados[i+2]=128.toByte()}
            ppm.writeBytes("P6\n$w $h\n255\n".toByteArray()+dados)
            val audio=File(pasta,"tom.mp4")
            instrumentacao.context.assets.open("video-com-audio.mp4").use {entrada->audio.outputStream().use {entrada.copyTo(it)}}
            val origem=File(pasta,"origem-giro.mp4")
            Ffmpeg.executar(contexto,listOf("-v","error","-y","-loop","1","-framerate","15","-i",ppm.absolutePath,
                "-stream_loop","-1","-i",audio.absolutePath,"-map","0:v:0","-map","1:a:0","-t","30","-r","15",
                "-c:v","mpeg4","-q:v","2","-pix_fmt","yuv420p","-c:a","aac","-b:a","96k",origem.absolutePath),null)
            val projeto=Projeto(largura=320,altura=320,fps=15,fundo=FundoDoProjeto("#FF7A18"),
                clipes=listOf(Clipe("c",origem.absolutePath,"video",inicio=0.0,fim=30.0,ajuste="caber",volume=1.0,
                    transformacao=Transformacao(giro=3.0,mascara="circulo"))))
            val saidas=mutableMapOf<Boolean,File>();val tempos=mutableMapOf<Boolean,Long>()
            for(otimizar in listOf(false,true)) {
                val destino=File(pasta,if(otimizar) "novo.mp4" else "antigo.mp4");saidas[otimizar]=destino
                val inicio=System.nanoTime();renderizar(projeto,origem,otimizar,destino,pasta);tempos[otimizar]=(System.nanoTime()-inicio)/1_000_000
                assertTrue(Ffmpeg.sondar(contexto,destino).temVideo)
            }
            val antigo=quadro(saidas.getValue(false),File(pasta,"antigo.png"),15)
            val novo=quadro(saidas.getValue(true),File(pasta,"novo.png"),15)
            var erro=0.0;var amostras=0
            try {
                assertEquals(antigo.width to antigo.height,novo.width to novo.height)
                for(y in 0 until antigo.height step 4) for(x in 0 until antigo.width step 4) {
                    val a=antigo.getPixel(x,y);val b=novo.getPixel(x,y)
                    val alfaA=Color.alpha(a);val alfaB=Color.alpha(b)
                    erro+=listOf(Color.red(a)*alfaA-Color.red(b)*alfaB,Color.green(a)*alfaA-Color.green(b)*alfaB,Color.blue(a)*alfaA-Color.blue(b)*alfaB).sumOf {(it/255.0)*(it/255.0)}/3.0;amostras++
                }
            } finally {antigo.recycle();novo.recycle()}
            val mse=erro/amostras
            val antes=tempos.getValue(false);val depois=tempos.getValue(true)
            Log.i("EstudioDesempenho","30 s, 320×320, 15 fps, giro+máscara: original=$antes ms; otimizado=$depois ms; aceleração=${antes.toDouble()/depois}; MSE ponderada=$mse")
            assertTrue("saídas equivalentes, MSE ponderada=$mse",mse<400.0)
            assertTrue("otimização deve acelerar: antes=$antes depois=$depois",depois<antes)
        } finally {pasta.deleteRecursively()}
        Unit
    }

    @Test fun videoDe60SegundosAntesEDepoisComOsMesmosPixels()=runBlocking {
        val pasta=File(contexto.cacheDir,"estudio-desempenho").apply {mkdirs()}
        try {
            val png=File(pasta,"origem.png")
            val bitmap=Bitmap.createBitmap(320,240,Bitmap.Config.ARGB_8888)
            Canvas(bitmap).apply {
                drawRect(0f,0f,160f,120f,Paint().apply {color=Color.RED})
                drawRect(160f,0f,320f,120f,Paint().apply {color=Color.GREEN})
                drawRect(0f,120f,160f,240f,Paint().apply {color=Color.BLUE})
                drawRect(160f,120f,320f,240f,Paint().apply {color=Color.YELLOW})
            }
            png.outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
            val audio=File(pasta,"tom.mp4")
            instrumentacao.context.assets.open("video-com-audio.mp4").use {entrada->audio.outputStream().use {entrada.copyTo(it)}}
            val origem=File(pasta,"origem-60.mp4")
            Ffmpeg.executar(contexto,listOf("-v","error","-y","-loop","1","-framerate","10","-i",png.absolutePath,
                "-stream_loop","-1","-i",audio.absolutePath,"-map","0:v:0","-map","1:a:0","-t","60","-r","10",
                "-c:v","mpeg4","-q:v","2","-pix_fmt","yuv420p","-c:a","aac","-b:a","96k",origem.absolutePath),null)
            val projeto=Projeto(largura=480,altura=480,fps=10,fundo=FundoDoProjeto("#FF7A18"),
                clipes=listOf(Clipe("c",origem.absolutePath,"video",inicio=0.0,fim=60.0,ajuste="caber",volume=1.0)))
            val saidas=mutableMapOf<Boolean,File>();val tempos=mutableMapOf<Boolean,Long>()
            for(otimizar in listOf(false,true)) {
                val destino=File(pasta,if(otimizar) "otimizado.mp4" else "original.mp4");saidas[otimizar]=destino
                val inicio=System.nanoTime();renderizar(projeto,origem,otimizar,destino,pasta);tempos[otimizar]=(System.nanoTime()-inicio)/1_000_000
                val info=Ffmpeg.sondar(contexto,destino)
                assertTrue("vídeo e áudio preservados",info.temVideo&&info.temAudio)
                assertEquals("duração preservada",60.0,info.duracaoSegundos!!,.2)
            }
            val antigo=quadro(saidas.getValue(false),File(pasta,"antigo.png"))
            val novo=quadro(saidas.getValue(true),File(pasta,"novo.png"))
            var erro=0.0;var amostras=0
            try {
                assertEquals(antigo.width to antigo.height,novo.width to novo.height)
                for(y in 0 until antigo.height step 8) for(x in 0 until antigo.width step 8) {
                    val a=antigo.getPixel(x,y);val b=novo.getPixel(x,y)
                    erro+=listOf(Color.red(a)-Color.red(b),Color.green(a)-Color.green(b),Color.blue(a)-Color.blue(b)).sumOf {it*it}/3.0;amostras++
                }
                for(b in listOf(antigo,novo)) {
                    val fundo=b.getPixel(240,10)
                    assertTrue("fundo laranja preservado",abs(Color.red(fundo)-255)<35&&abs(Color.green(fundo)-122)<35&&abs(Color.blue(fundo)-24)<35)
                }
            } finally {antigo.recycle();novo.recycle()}
            val mse=erro/amostras
            assertTrue("saídas equivalentes, MSE=$mse",mse<16.0)
            val antes=tempos.getValue(false);val depois=tempos.getValue(true)
            Log.i("EstudioDesempenho","60 s, 480×480, 10 fps, com áudio: original=$antes ms; otimizado=$depois ms; aceleração=${antes.toDouble()/depois}; MSE=$mse")
            assertTrue("otimização deve acelerar: antes=$antes depois=$depois",depois<antes)
        } finally {pasta.deleteRecursively()}
        Unit
    }
}
