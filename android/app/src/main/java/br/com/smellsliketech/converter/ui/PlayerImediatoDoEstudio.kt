package br.com.smellsliketech.converter.ui

import android.content.Context
import android.graphics.*
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import android.widget.FrameLayout
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import br.com.smellsliketech.converter.estudio.*
import kotlin.math.abs

/** Decoder do Android + composição por GPU, sem transcodificar o vídeo antes de tocar. */
@Composable
fun PlayerImediatoDoEstudio(modelo: EstudioViewModel, projeto: Projeto, modifier: Modifier) {
    key(projeto.clipes.single().arquivo) {
        AndroidView(
            factory = { contexto -> PlayerImediato(contexto).apply {
                aoAvancar = modelo::avancarPlayer
                aoTerminar = { modelo.tocandoPrevia = false }
                aoFalhar = modelo::falhouPlayer
            } },
            update = { it.atualizar(projeto, modelo.instante, modelo.tocandoPrevia) },
            onRelease = { it.liberar() },
            modifier = modifier,
        )
    }
}

/** View pública para exercitar decoder, seek, filtros e liberação nos testes instrumentados. */
class PlayerImediato(contexto: Context) : FrameLayout(contexto), TextureView.SurfaceTextureListener {
    var aoAvancar: (Double) -> Unit = {}
    var aoPreparar: () -> Unit = {}
    var aoTerminar: () -> Unit = {}
    var aoFalhar: () -> Unit = {}
    private val textura = TextureView(contexto).apply { isOpaque = false; surfaceTextureListener = this@PlayerImediato }
    private var player: MediaPlayer? = null
    private var superficie: Surface? = null
    private var pronto = false
    private var projeto: Projeto? = null
    private var instante = 0.0
    private var tocar = false
    private var buscando = false
    private var ultimoSeek = Double.NaN
    private var solto = false
    private val pintura = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    init { setWillNotDraw(false); addView(textura, LayoutParams(-1, -1)) }

    fun atualizar(p: Projeto, tempo: Double, tocando: Boolean) {
        projeto = p; instante = tempo; tocar = tocando
        if (textura.isAvailable && player == null && !solto) abrir(textura.surfaceTexture!!)
        sincronizar(); aparencia(); invalidate()
    }

    private fun abrir(textura: SurfaceTexture) {
        val c = projeto?.clipes?.singleOrNull() ?: return
        try {
            superficie = Surface(textura)
            player = MediaPlayer().also { mp ->
                mp.setDataSource(c.arquivo); mp.setSurface(superficie)
                mp.setOnPreparedListener { pronto = true; sincronizar(); exibirQuadroPausado(mp); aparencia(); post(pulso); aoPreparar() }
                mp.setOnVideoSizeChangedListener { _, _, _ -> aparencia() }
                mp.setOnSeekCompleteListener { buscando = false; sincronizar(); exibirQuadroPausado(mp); aparencia(); invalidate() }
                mp.setOnCompletionListener {
                    tocar = false; instante = Estudio.duracaoDoClipe(c); aoAvancar(instante); aoTerminar()
                }
                mp.setOnErrorListener { _, _, _ -> falhar(); true }
                mp.prepareAsync()
            }
        } catch (_: Exception) { falhar() }
    }

    private fun exibirQuadroPausado(mp: MediaPlayer) {
        if (!tocar && !buscando && !mp.isPlaying) {
            mp.start()
            mp.pause()
        }
    }

    private fun sincronizar() {
        val mp = player ?: return
        val c = projeto?.clipes?.singleOrNull() ?: return
        if (!pronto) return
        try {
            val alvo = PreviaImediata.tempoNaOrigem(c, instante)
                .coerceAtMost(maxOf(c.inicio ?: 0.0, (c.fim ?: mp.duration / 1000.0) - .001))
            val distante = abs(mp.currentPosition / 1000.0 - alvo) > .18
            val alvoNovo = ultimoSeek.isNaN() || abs(alvo - ultimoSeek) > .05
            if (!buscando && distante && alvoNovo) {
                buscando = true; ultimoSeek = alvo
                mp.seekTo((alvo * 1000).toLong(), MediaPlayer.SEEK_CLOSEST)
            }
            val volume = if (c.mudo == true) 0f else (c.volume ?: 1.0).toFloat().coerceIn(0f, 1f)
            mp.setVolume(volume, volume)
            if (tocar && !buscando) {
                if (mp.playbackParams.speed != Estudio.velocidadeDoClipe(c).toFloat()) mp.playbackParams = mp.playbackParams.setSpeed(Estudio.velocidadeDoClipe(c).toFloat()).setPitch(1f)
                if (!mp.isPlaying) mp.start()
            } else if (mp.isPlaying) mp.pause()
        } catch (_: Exception) { falhar() }
    }

    private val pulso = object : Runnable {
        override fun run() {
            if (solto) return
            val mp = player
            val c = projeto?.clipes?.singleOrNull()
            if (pronto && tocar && !buscando && mp != null && c != null) {
                try {
                    val tempo = (mp.currentPosition / 1000.0 - (c.inicio ?: 0.0)) / Estudio.velocidadeDoClipe(c)
                    if (tempo >= Estudio.duracaoDoClipe(c) - .02) {
                        mp.pause(); tocar = false; instante = Estudio.duracaoDoClipe(c); aoTerminar()
                    } else instante = maxOf(0.0, tempo)
                    aoAvancar(instante); aparencia(); invalidate()
                } catch (_: Exception) { falhar() }
            }
            postDelayed(this, 33)
        }
    }

    private fun aparencia() {
        val p = projeto ?: return
        val c = p.clipes.single()
        val mp = player ?: return
        if (!pronto || width == 0 || height == 0 || mp.videoWidth == 0 || mp.videoHeight == 0) return
        val (x, y, w, h) = Estudio.retanguloDoAjuste(c.ajuste, mp.videoWidth.toDouble(), mp.videoHeight.toDouble(), width, height)
        textura.setTransform(Matrix().apply { setRectToRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), RectF(x.toFloat(), y.toFloat(), (x+w).toFloat(), (y+h).toFloat()), Matrix.ScaleToFit.FILL) })
        val t = c.transformacao ?: Transformacao()
        if (t.filtro == "original" && t.brilho == 0.0 && t.contraste == 0.0 && t.saturacao == 0.0 && t.opacidade == 1.0) {
            textura.setLayerPaint(null)
        } else {
            val cor = FiltrosDeCamada.matrizDeCor(t)
            val matriz = floatArrayOf(cor[0].toFloat(),cor[1].toFloat(),cor[2].toFloat(),0f,cor[3].toFloat(),
                cor[4].toFloat(),cor[5].toFloat(),cor[6].toFloat(),0f,cor[7].toFloat(),
                cor[8].toFloat(),cor[9].toFloat(),cor[10].toFloat(),0f,cor[11].toFloat(),0f,0f,0f,t.opacidade.toFloat(),0f)
            textura.setLayerPaint(Paint().apply { colorFilter = ColorMatrixColorFilter(matriz) })
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { super.onSizeChanged(w,h,oldw,oldh); aparencia() }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val fundo = projeto?.fundo
        pintura.shader = if (fundo?.corFinal != null) LinearGradient(0f,0f,width.toFloat(),height.toFloat(),DesenhoDoTexto.cor(fundo.cor) ?: Color.BLACK,DesenhoDoTexto.cor(fundo.corFinal) ?: Color.BLACK,Shader.TileMode.CLAMP) else null
        pintura.color = DesenhoDoTexto.cor(fundo?.cor ?: "#000000") ?: Color.BLACK
        canvas.drawRect(0f,0f,width.toFloat(),height.toFloat(),pintura)
    }
    override fun dispatchDraw(canvas: Canvas) {
        val t = projeto?.clipes?.singleOrNull()?.transformacao ?: Transformacao()
        if (width == 0 || height == 0) return
        val q = Animacao.noTempo(t, instante)
        val m = FiltrosDeCamada.projecao(t.copy(x=q.x,y=q.y,escala=q.escala),width,height) ?: return
        val matriz = Matrix().apply { setValues(floatArrayOf(m[0].toFloat(),(m[1]*width/height).toFloat(),(m[2]*width).toFloat(),
            (m[3]*height/width).toFloat(),m[4].toFloat(),(m[5]*height).toFloat(),(m[6]/width).toFloat(),(m[7]/height).toFloat(),1f)) }
        canvas.save(); canvas.concat(matriz)
        val area = RectF(0f,0f,width.toFloat(),height.toFloat())
        canvas.clipRect(area)
        val mascara = Path()
        when(t.mascara) {
            "circulo" -> mascara.addCircle(width/2f,height/2f,minOf(width,height)/2f,Path.Direction.CW)
            "oval" -> mascara.addOval(area,Path.Direction.CW)
            "arredondada" -> mascara.addRoundRect(area,minOf(width,height)*.15f,minOf(width,height)*.15f,Path.Direction.CW)
        }
        if (!mascara.isEmpty) canvas.clipPath(mascara)
        super.dispatchDraw(canvas); canvas.restore()
    }
    private fun falhar() { liberar(); aoFalhar() }
    fun liberar() {
        solto = true; removeCallbacks(pulso); pronto = false
        player?.release(); player = null; superficie?.release(); superficie = null
    }
    override fun onDetachedFromWindow() { liberar(); super.onDetachedFromWindow() }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != VISIBLE && pronto) { tocar = false; player?.pause(); aoTerminar() }
    }
    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) { abrir(surface) }
    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) { aparencia() }
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean { liberar(); return true }
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
}
