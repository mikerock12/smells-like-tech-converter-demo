package br.com.smellsliketech.converter.estudio

import android.content.Context
import android.graphics.*
import br.com.smellsliketech.converter.figurinhas.Fundo
import br.com.smellsliketech.converter.conversao.ErroDeConversao
import br.com.smellsliketech.converter.conversao.ffmpeg.Ffmpeg
import kotlinx.coroutines.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Recorta cada origem antes dos filtros e da perspectiva. Vídeos usam pipes e FFV1 com alfa. */
object RecorteDeFundo {
    private suspend fun recortar(imagem: Bitmap): Bitmap {
        val mascara=Fundo.mascara(imagem)
        try {
            return imagem.copy(Bitmap.Config.ARGB_8888,true).also { resultado ->
                Canvas(resultado).drawBitmap(mascara,0f,0f,Paint().apply { xfermode=PorterDuffXfermode(PorterDuff.Mode.DST_IN) })
            }
        } finally {mascara.recycle()}
    }
    suspend fun imagem(arquivo: File) = withContext(Dispatchers.IO) {
        val original=BitmapFactory.decodeFile(arquivo.absolutePath)?:throw ErroDeConversao("Não foi possível abrir a imagem para remover o fundo.")
        try {
            val recortada=recortar(original)
            try {arquivo.outputStream().use {recortada.compress(Bitmap.CompressFormat.PNG,100,it)}} finally {recortada.recycle()}
        } finally {original.recycle()}
    }
    suspend fun video(contexto: Context,origem: File,destino: File,w: Int,h: Int,fps: Int,inicio: Double,duracao: Double) = withContext(Dispatchers.IO) {
        val decodificar=Ffmpeg.iniciar(contexto,listOf("-v","error","-nostdin","-ss",Estudio.numero(inicio),"-t",Estudio.numero(duracao),"-i",origem.absolutePath,
            "-vf","fps=$fps,scale=$w:$h","-an","-f","rawvideo","-pix_fmt","argb","pipe:1"))
        try {
            val codificar=Ffmpeg.iniciar(contexto,listOf("-v","error","-y","-f","rawvideo","-pix_fmt","argb","-s","${w}x$h","-framerate",fps.toString(),"-i","pipe:0",
                "-ss",Estudio.numero(inicio),"-t",Estudio.numero(duracao),"-i",origem.absolutePath,"-map","0:v","-map","1:a?","-c:v","ffv1","-level","3","-pix_fmt","yuva444p","-c:a","pcm_s16le","-t",Estudio.numero(duracao),destino.absolutePath))
            try {
                val bytes=ByteArray(Math.multiplyExact(Math.multiplyExact(w,h),4));val pontos=IntArray(w*h)
                val buffer=ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
                var quadros=0
                while(true) {
                    currentCoroutineContext().ensureActive()
                    val leu=runInterruptible {
                        var lidos=0
                        while(lidos<bytes.size) {
                            val n=decodificar.saida.read(bytes,lidos,bytes.size-lidos)
                            if(n<0) {if(lidos>0) throw ErroDeConversao("Um quadro do vídeo veio incompleto.");break}
                            lidos+=n
                        }
                        lidos>0
                    }
                    if(!leu) break
                    buffer.clear();buffer.asIntBuffer().get(pontos)
                    val quadro=Bitmap.createBitmap(pontos,w,h,Bitmap.Config.ARGB_8888)
                    val recortado=try {recortar(quadro)} finally {quadro.recycle()}
                    try {recortado.getPixels(pontos,0,w,0,0,w,h)} finally {recortado.recycle()}
                    buffer.clear();buffer.asIntBuffer().put(pontos)
                    runInterruptible {codificar.entrada.write(bytes)}
                    quadros++
                }
                codificar.entrada.close()
                runInterruptible {decodificar.fim();codificar.fim()}
                if(quadros==0) throw ErroDeConversao("O trecho não contém quadros para remover o fundo.")
            } finally {codificar.matar()}
        } finally {decodificar.matar()}
    }
}
