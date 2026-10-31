package br.com.smellsliketech.converter.estudio

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
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
class FundoOtimizadoNoAparelhoTest {
    @Test fun fundoSolidoPreservaAlfaConteudoEEquivalenteAoOriginal()=runBlocking {
        val contexto=InstrumentationRegistry.getInstrumentation().targetContext
        val pasta=File(contexto.cacheDir,"fundo-otimizado").apply {mkdirs()}
        try {
            val origem=File(pasta,"origem.png")
            val bitmap=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.TRANSPARENT)
            for(y in 16..47) for(x in 16..47) bitmap.setPixel(x,y,Color.RED)
            origem.outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
            val projeto=Projeto(largura=64,altura=64,fps=10,saida="imagem",fundo=FundoDoProjeto("#01020380"),
                clipes=listOf(Clipe("c",origem.absolutePath,"imagem",duracao=1.0,ajuste="caber")))
            val novo=File(pasta,"novo.png");Exportador.gerar(contexto,projeto,novo) {}
            val receita=Estudio.receita(projeto,EntradasDaReceita(listOf(EntradaDeClipe(origem.absolutePath,false)),emptyList(),emptyList()),otimizar=false)
            val filtro=File(pasta,"antigo.fffilter").apply {writeText(receita.filtro)};val antigo=File(pasta,"antigo.png")
            Ffmpeg.executar(contexto,listOf("-v","error","-y")+receita.args+listOf("-/filter_complex",filtro.absolutePath)+receita.mapas+
                listOf("-ss","0","-frames:v","1","-update","1",antigo.absolutePath),null)
            val a=BitmapFactory.decodeFile(antigo.absolutePath);val b=BitmapFactory.decodeFile(novo.absolutePath)
            try {
                for(x in listOf(0,32,63)) for(y in listOf(0,32,63)) {
                    val pa=a.getPixel(x,y);val pb=b.getPixel(x,y)
                    assertEquals("alfa em $x,$y",Color.alpha(pa),Color.alpha(pb))
                    assertTrue("RGB equivalente em $x,$y",listOf(Color.red(pa)-Color.red(pb),Color.green(pa)-Color.green(pb),Color.blue(pa)-Color.blue(pb)).all {abs(it)<=2})
                }
                assertEquals(128,Color.alpha(b.getPixel(0,0)))
                val fundo=b.getPixel(0,0)
                assertTrue("cor do fundo ${Integer.toHexString(fundo)}",abs(Color.red(fundo)-1)<=2&&abs(Color.green(fundo)-2)<=2&&abs(Color.blue(fundo)-3)<=2)
                assertTrue("centro preservado",Color.red(b.getPixel(32,32))>240&&Color.alpha(b.getPixel(32,32))==255)
            } finally {a.recycle();b.recycle()}
        } finally {pasta.deleteRecursively()}
        Unit
    }
}
