package br.com.smellsliketech.converter.imagem

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import com.awxkee.jxlcoder.JxlCoder
import com.awxkee.jxlcoder.PreferredColorConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.pow

internal data class FonteImagem(val id: String, val bytes: ByteArray, val bitmap: Bitmap)

internal object ProjetoImagem {
    const val MIME = "application/vnd.smellsliketech.image-project+zip"
    private const val MAX = 128 * 1024 * 1024
    private const val PIXELS = 16L * 1024 * 1024
    private val idFonte = Regex("[a-f0-9]{64}")

    fun ler(fluxo: InputStream, limite: Int): ByteArray {
        val resultado = ByteArrayOutputStream()
        val bloco = ByteArray(32768)
        while (true) {
            val n = fluxo.read(bloco)
            if (n < 0) break
            require(n <= limite - resultado.size()) { "Arquivo de imagem acima do limite." }
            resultado.write(bloco, 0, n)
        }
        return resultado.toByteArray()
    }

    fun fonte(dados: ByteArray): FonteImagem {
        val id = MessageDigest.getInstance("SHA-256").digest(dados).joinToString("") { "%02x".format(it) }
        val tamanho = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(dados, 0, dados.size, tamanho)
        val bitmap = if (tamanho.outWidth > 0) {
            dimensoes(tamanho.outWidth, tamanho.outHeight)
            BitmapFactory.decodeByteArray(dados, 0, dados.size)
        } else {
            val info = JxlCoder.getSize(dados) ?: error("Imagem incompatível.")
            dimensoes(info.width, info.height)
            JxlCoder.decode(dados, PreferredColorConfig.RGBA_8888)
        }
        return FonteImagem(id, dados, bitmap ?: error("Não foi possível abrir a imagem."))
    }

    private fun dimensoes(w: Int, h: Int) {
        require(w in 1..16384 && h in 1..16384 && w.toLong() * h <= PIXELS) { "O limite por imagem é de 16 megapixels." }
    }

    fun camada(tipo: String, nome: String) = JSONObject()
        .put("tipo", tipo).put("nome", nome.take(512)).put("id", UUID.randomUUID().toString())
        .put("visivel", true).put("cor", "#ffffff").put("tamanho", 48).put("x", 0).put("y", 0)
        .put("escala", 1).put("angulo", 0).put("opacidade", 1).put("exposicao", 0)
        .put("contraste", 1).put("saturacao", 1)

    fun novo(nome: String, fonte: FonteImagem): JSONObject {
        val canvas = JSONObject().put("width", fonte.bitmap.width).put("height", fonte.bitmap.height)
            .put("colorSpace", "sRGB").put("pixelFormat", "RGBA8")
        return JSONObject().put("documentId", UUID.randomUUID().toString()).put("schemaVersion", 1)
            .put("title", nome.take(512)).put("canvas", canvas).put("layers", JSONArray())
    }

    fun conferir(doc: JSONObject) {
        require(doc.getInt("schemaVersion") == 1 && doc.getString("documentId").matches(Regex("[a-zA-Z0-9-]{1,64}")))
        require(doc.getString("title").length <= 512)
        val canvas = doc.getJSONObject("canvas")
        require(canvas.getString("pixelFormat") == "RGBA8" && canvas.getString("colorSpace") == "sRGB")
        dimensoes(canvas.getInt("width"), canvas.getInt("height"))
        val lista = doc.getJSONArray("layers")
        require(lista.length() <= 64)
        val ids = mutableSetOf<String>()
        for (i in 0 until lista.length()) {
            val c = lista.getJSONObject(i)
            require(ids.add(c.getString("id")) && c.getString("id").matches(Regex("[a-zA-Z0-9-]{1,64}")))
            require(c.getString("nome").length <= 512 && c.getString("cor").matches(Regex("#[0-9a-fA-F]{6}")))
            c.getBoolean("visivel")
            for (key in listOf("x", "y")) require(c.getDouble(key) in -32768.0..32768.0)
            require(c.getDouble("escala") in 0.01..16.0 && c.getDouble("angulo") in -360.0..360.0)
            require(c.getDouble("opacidade") in 0.0..1.0 && c.getDouble("tamanho") in 1.0..4096.0)
            require(c.getDouble("exposicao") in -4.0..4.0)
            for (key in listOf("contraste", "saturacao")) require(c.getDouble(key) in 0.0..4.0)
            when (c.getString("tipo")) {
                "foto" -> require(idFonte.matches(c.getString("asset")))
                "texto" -> require(c.getString("texto").length <= 4096)
                else -> error("Tipo de camada incompatível.")
            }
        }
    }

    fun recortar(doc: JSONObject, esquerda: Int, topo: Int, direita: Int, base: Int): JSONObject {
        val novo = JSONObject(doc.toString())
        val quadro = novo.getJSONObject("canvas")
        val largura = quadro.getInt("width")
        val altura = quadro.getInt("height")
        require(minOf(esquerda, topo, direita, base) >= 0 &&
            esquerda.toLong() + direita < largura && topo.toLong() + base < altura) {
            "As margens devem deixar pelo menos um pixel de largura e altura."
        }
        quadro.put("width", largura - esquerda - direita).put("height", altura - topo - base)
        val lista = novo.getJSONArray("layers")
        for (i in 0 until lista.length()) {
            val c = lista.getJSONObject(i)
            c.put("x", c.getDouble("x") - esquerda).put("y", c.getDouble("y") - topo)
        }
        conferir(novo)
        return novo
    }

    fun abrir(fluxo: InputStream): Pair<JSONObject, Map<String, FonteImagem>> {
        val entradas = mutableMapOf<String, ByteArray>()
        var total = 0
        ZipInputStream(fluxo.buffered()).use { zip ->
            while (true) {
                val entrada = zip.nextEntry ?: break
                val nome = entrada.name
                require(entrada.method == ZipEntry.STORED && entradas.size < 66 && nome !in entradas)
                val teto = when {
                    nome == "mimetype" -> MIME.length
                    nome == "manifest.json" -> 1024 * 1024
                    nome.matches(Regex("assets/[0-9a-f]{64}")) -> 64 * 1024 * 1024
                    else -> error("Entrada de projeto incompatível.")
                }
                require(entrada.size in 0..minOf(teto, MAX - total).toLong())
                val bytes = ler(zip, entrada.size.toInt())
                total += bytes.size
                entradas[nome] = bytes
            }
        }
        require(entradas["mimetype"]?.toString(Charsets.UTF_8) == MIME)
        val doc = JSONObject(entradas.getValue("manifest.json").toString(Charsets.UTF_8))
        conferir(doc)
        val fontes = mutableMapOf<String, FonteImagem>()
        try {
            val lista = doc.getJSONArray("layers")
            var totalPixels = 0L
            for (i in 0 until lista.length()) {
                val c = lista.getJSONObject(i)
                if (c.getString("tipo") != "foto") continue
                val id = c.getString("asset")
                if (id in fontes) continue
                val f = fonte(entradas.getValue("assets/" + id))
                fontes[id] = f
                totalPixels += f.bitmap.width.toLong() * f.bitmap.height
                require(f.id == id && totalPixels <= 2 * PIXELS) { "Fonte corrompida ou projeto muito grande." }
            }
            return doc to fontes
        } catch (e: Exception) { fontes.values.forEach { it.bitmap.recycle() }; throw e }
    }

    fun salvar(doc: JSONObject, fontes: Map<String, FonteImagem>, destino: OutputStream) {
        conferir(doc)
        ZipOutputStream(destino.buffered()).use { zip ->
            fun adicionar(nome: String, dados: ByteArray) {
                zip.putNextEntry(ZipEntry(nome).apply {
                    method = ZipEntry.STORED
                    size = dados.size.toLong()
                    compressedSize = size
                    crc = CRC32().apply { update(dados) }.value
                })
                zip.write(dados)
                zip.closeEntry()
            }
            adicionar("mimetype", MIME.toByteArray())
            adicionar("manifest.json", doc.toString().toByteArray())
            val lista = doc.getJSONArray("layers")
            val ids = (0 until lista.length()).map { lista.getJSONObject(it) }
                .filter { it.getString("tipo") == "foto" }.map { it.getString("asset") }.toSet()
            require(ids.sumOf { fontes.getValue(it).bytes.size.toLong() } <= MAX - 1024 * 1024)
            for (id in ids) adicionar("assets/" + id, fontes.getValue(id).bytes)
        }
    }

    fun render(doc: JSONObject, fontes: Map<String, FonteImagem>): Bitmap {
        conferir(doc)
        val quadro = doc.getJSONObject("canvas")
        val saida = Bitmap.createBitmap(quadro.getInt("width"), quadro.getInt("height"), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(saida)
        val lista = doc.getJSONArray("layers")
        for (i in 0 until lista.length()) {
            val c = lista.getJSONObject(i)
            if (!c.getBoolean("visivel")) continue
            canvas.save()
            canvas.translate(c.getDouble("x").toFloat(), c.getDouble("y").toFloat())
            canvas.rotate(c.getDouble("angulo").toFloat())
            val escala = c.getDouble("escala").toFloat()
            canvas.scale(escala, escala)
            val pincel = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            pincel.color = Color.parseColor(c.getString("cor"))
            pincel.alpha = Math.round(c.getDouble("opacidade") * 255).toInt()
            val contraste = c.getDouble("contraste").toFloat()
            val ganho = 2.0.pow(c.getDouble("exposicao")).toFloat() * contraste
            val bias = (1f - contraste) * 127.5f
            val matriz = ColorMatrix(floatArrayOf(ganho,0f,0f,0f,bias, 0f,ganho,0f,0f,bias,
                0f,0f,ganho,0f,bias, 0f,0f,0f,1f,0f))
            matriz.postConcat(ColorMatrix().apply { setSaturation(c.getDouble("saturacao").toFloat()) })
            pincel.colorFilter = ColorMatrixColorFilter(matriz)
            if (c.getString("tipo") == "foto") {
                val bitmap = fontes.getValue(c.getString("asset")).bitmap
                canvas.drawBitmap(bitmap, null, android.graphics.Rect(0, 0, bitmap.width, bitmap.height), pincel)
            } else {
                pincel.textSize = c.getDouble("tamanho").toFloat()
                canvas.drawText(c.getString("texto"), 0f, -pincel.ascent(), pincel)
            }
            canvas.restore()
        }
        return saida
    }
}
