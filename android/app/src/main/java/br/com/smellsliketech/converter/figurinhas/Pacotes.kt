package br.com.smellsliketech.converter.figurinhas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/**
 * As regras do WhatsApp para pacotes de figurinhas de outros apps. Valem para o pacote
 * inteiro: 3 a 30 figurinhas, todas estáticas ou todas animadas, WebP 512×512.
 */
object Regras {
    const val MINIMO = 3
    const val MAXIMO = 30
    const val LADO = 512
    const val ESTATICA_MAX_BYTES = 100 * 1024
    const val ANIMADA_MAX_BYTES = 500 * 1024
    const val ANIMADA_MAX_SEGUNDOS = 10.0
    /**
     * Quadros por segundo da figurinha animada, escolhidos pela pessoa e sempre respeitados:
     * nenhum quadro é pulado. Quando o vídeo tem movimento demais para os 500 KB, a figurinha
     * fica mais curta, não mais travada.
     */
    val QUADROS_POR_SEGUNDO = listOf(10, 12, 15)
    const val QUADROS_POR_SEGUNDO_PADRAO = 10
    /** Abaixo disso a imagem fica quadriculada demais: daí em diante, encurta. */
    const val QUALIDADE_MINIMA_ANIMADA = 25
    const val BANDEJA_LADO = 96
    const val BANDEJA_MAX_BYTES = 50 * 1024
    const val EMOJIS_MAX = 3
    const val NOME_MAX = 128
}

data class Figurinha(val arquivo: String, val emojis: List<String>)

data class PacoteDeFigurinhas(
    val id: String,
    val nome: String,
    val autor: String,
    val animado: Boolean,
    /** Muda a cada alteração: é como o WhatsApp sabe que precisa buscar o pacote de novo. */
    val versao: Int,
    val figurinhas: List<Figurinha>,
) {
    val prontoParaWhatsApp: Boolean get() = figurinhas.size in Regras.MINIMO..Regras.MAXIMO
    val cheio: Boolean get() = figurinhas.size >= Regras.MAXIMO
    val faltam: Int get() = (Regras.MINIMO - figurinhas.size).coerceAtLeast(0)
}

/**
 * Os pacotes ficam na pasta privada do app: `files/figurinhas/<id>/pacote.json`, as figurinhas
 * (`NN.webp`) e o ícone da bandeja (`bandeja.png`). Nada sai do celular: o WhatsApp lê os
 * arquivos pelo [ProvedorDeFigurinhas] quando a pessoa adiciona o pacote.
 */
class Pacotes(private val contexto: Context) {
    private val raiz = File(contexto.filesDir, "figurinhas").apply { mkdirs() }

    fun pasta(id: String) = File(raiz, id)

    fun listar(): List<PacoteDeFigurinhas> =
        raiz.listFiles { arquivo -> arquivo.isDirectory }.orEmpty()
            .mapNotNull { ler(it.name) }
            .sortedBy { it.nome.lowercase() }

    fun ler(id: String): PacoteDeFigurinhas? {
        if (!idValido(id)) return null
        val json = File(pasta(id), ARQUIVO).takeIf { it.isFile } ?: return null
        return runCatching {
            val objeto = JSONObject(json.readText())
            val lista = objeto.getJSONArray("figurinhas")
            PacoteDeFigurinhas(
                id = id,
                nome = objeto.getString("nome"),
                autor = objeto.optString("autor", AUTOR_PADRAO),
                animado = objeto.getBoolean("animado"),
                versao = objeto.optInt("versao", 1),
                figurinhas = (0 until lista.length()).map { indice ->
                    val item = lista.getJSONObject(indice)
                    val emojis = item.getJSONArray("emojis")
                    Figurinha(item.getString("arquivo"), (0 until emojis.length()).map { emojis.getString(it) })
                },
            )
        }.getOrNull()
    }

    fun criar(nome: String, animado: Boolean): PacoteDeFigurinhas {
        val pacote = PacoteDeFigurinhas(
            id = "slt_" + UUID.randomUUID().toString().replace("-", "").take(20),
            nome = nomeLimpo(nome),
            autor = AUTOR_PADRAO,
            animado = animado,
            versao = 1,
            figurinhas = emptyList(),
        )
        pasta(pacote.id).mkdirs()
        gravar(pacote)
        return pacote
    }

    fun renomear(pacote: PacoteDeFigurinhas, nome: String): PacoteDeFigurinhas =
        pacote.copy(nome = nomeLimpo(nome), versao = pacote.versao + 1).also(::gravar)

    /** Acrescenta uma figurinha já pronta (WebP 512×512 dentro do limite de tamanho). */
    fun adicionar(pacote: PacoteDeFigurinhas, webp: ByteArray, emojis: List<String>): PacoteDeFigurinhas {
        require(!pacote.cheio) { "O pacote já tem ${Regras.MAXIMO} figurinhas, o máximo do WhatsApp." }
        val limite = if (pacote.animado) Regras.ANIMADA_MAX_BYTES else Regras.ESTATICA_MAX_BYTES
        require(webp.size <= limite) { "A figurinha passou de ${limite / 1024} KB." }
        val usados = pacote.figurinhas.map { it.arquivo }.toSet()
        val nome = generateSequence(pacote.figurinhas.size + 1) { it + 1 }
            .map { "%02d.webp".format(it) }.first { it !in usados }
        val pronta = if (pacote.animado) WebpAnimado.normalizar(webp) else webp
        File(pasta(pacote.id), nome).writeBytes(pronta)
        val atualizado = pacote.copy(
            figurinhas = pacote.figurinhas + Figurinha(nome, emojis.filter { it.isNotBlank() }.take(Regras.EMOJIS_MAX).ifEmpty { listOf(EMOJI_PADRAO) }),
            versao = pacote.versao + 1,
        )
        gravar(atualizado)
        if (atualizado.figurinhas.size == 1) gerarBandeja(atualizado)
        return atualizado
    }

    /**
     * Troca a imagem (e os emojis) de uma figurinha que já está no pacote, depois de editada de
     * novo. O nome do arquivo e a posição continuam; a versão sobe para o WhatsApp buscar de novo.
     */
    fun substituir(pacote: PacoteDeFigurinhas, figurinha: Figurinha, webp: ByteArray, emojis: List<String>): PacoteDeFigurinhas {
        require(figurinha in pacote.figurinhas) { "A figurinha não está mais no pacote." }
        val limite = if (pacote.animado) Regras.ANIMADA_MAX_BYTES else Regras.ESTATICA_MAX_BYTES
        require(webp.size <= limite) { "A figurinha passou de ${limite / 1024} KB." }
        val pronta = if (pacote.animado) WebpAnimado.normalizar(webp) else webp
        val destino = File(pasta(pacote.id), figurinha.arquivo)
        val temporario = File(pasta(pacote.id), "${figurinha.arquivo}.tmp")
        temporario.writeBytes(pronta)
        if (!temporario.renameTo(destino)) { destino.delete(); check(temporario.renameTo(destino)) { "Não deu para trocar a figurinha. Tente de novo." } }
        val nova = Figurinha(figurinha.arquivo, emojis.filter { it.isNotBlank() }.take(Regras.EMOJIS_MAX).ifEmpty { listOf(EMOJI_PADRAO) })
        val atualizado = pacote.copy(figurinhas = pacote.figurinhas.map { if (it == figurinha) nova else it }, versao = pacote.versao + 1)
        gravar(atualizado)
        if (atualizado.figurinhas.firstOrNull()?.arquivo == figurinha.arquivo) gerarBandeja(atualizado)
        return atualizado
    }

    /** Onde fica o projeto de uma figurinha (para reabrir no editor), ao lado do pacote. */
    fun pastaDoProjeto(pacote: PacoteDeFigurinhas, arquivo: String): File =
        File(pasta(pacote.id), "projetos/" + arquivo.substringBeforeLast('.'))

    fun remover(pacote: PacoteDeFigurinhas, figurinha: Figurinha): PacoteDeFigurinhas {
        File(pasta(pacote.id), figurinha.arquivo).delete()
        pastaDoProjeto(pacote, figurinha.arquivo).deleteRecursively()
        val atualizado = pacote.copy(figurinhas = pacote.figurinhas - figurinha, versao = pacote.versao + 1)
        gravar(atualizado)
        gerarBandeja(atualizado)
        return atualizado
    }

    fun excluir(pacote: PacoteDeFigurinhas) {
        pasta(pacote.id).deleteRecursively()
    }

    fun arquivo(pacote: PacoteDeFigurinhas, nome: String): File = File(pasta(pacote.id), nome)

    /**
     * Corrige também os pacotes criados antes da atualização, antes de abrir o WhatsApp.
     * Mantém identificador, imagens e emojis; muda a versão só se algum tempo for ajustado.
     */
    fun prepararParaWhatsApp(pacote: PacoteDeFigurinhas): PacoteDeFigurinhas {
        require(pacote.prontoParaWhatsApp) { "O pacote precisa ter de 3 a 30 figurinhas." }
        val alteradas = mutableListOf<Pair<File, ByteArray>>()
        pacote.figurinhas.forEach { figurinha ->
            val arquivo = arquivo(pacote, figurinha.arquivo)
            val bytes = arquivo.readBytes()
            if (pacote.animado) {
                val corrigida = WebpAnimado.normalizar(bytes)
                if (corrigida !== bytes) {
                    alteradas += arquivo to corrigida
                }
            } else {
                val medidas = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, medidas)
                require(bytes.size <= Regras.ESTATICA_MAX_BYTES && medidas.outMimeType == "image/webp" &&
                    medidas.outWidth == Regras.LADO && medidas.outHeight == Regras.LADO) { "A figurinha ${figurinha.arquivo} não está no formato do WhatsApp." }
            }
        }
        alteradas.forEach { (arquivo, corrigida) ->
            val temporario = File(arquivo.parentFile, "${arquivo.name}.tmp")
            temporario.writeBytes(corrigida)
            check(temporario.renameTo(arquivo)) { "Não deu para atualizar a figurinha. Tente de novo." }
        }
        if (!arquivo(pacote, BANDEJA).isFile) gerarBandeja(pacote)
        return if (alteradas.isNotEmpty()) pacote.copy(versao = pacote.versao + 1).also(::gravar) else pacote
    }

    /**
     * O ícone da bandeja do WhatsApp: PNG 96×96 com até 50 KB, feito do primeiro quadro da
     * primeira figurinha.
     */
    fun gerarBandeja(pacote: PacoteDeFigurinhas) {
        val destino = File(pasta(pacote.id), BANDEJA)
        val primeira = pacote.figurinhas.firstOrNull() ?: run { destino.delete(); return }
        val origem = File(pasta(pacote.id), primeira.arquivo)
        val bitmap = runCatching { ImageDecoder.decodeBitmap(ImageDecoder.createSource(origem)) { decodificador, _, _ ->
            decodificador.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        } }.getOrNull() ?: BitmapFactory.decodeFile(origem.absolutePath) ?: return
        val icone = Bitmap.createBitmap(Regras.BANDEJA_LADO, Regras.BANDEJA_LADO, Bitmap.Config.ARGB_8888)
        Canvas(icone).drawBitmap(bitmap, null, Rect(0, 0, Regras.BANDEJA_LADO, Regras.BANDEJA_LADO), Paint(Paint.FILTER_BITMAP_FLAG))
        val saida = ByteArrayOutputStream()
        icone.compress(Bitmap.CompressFormat.PNG, 100, saida)
        destino.writeBytes(saida.toByteArray())
    }

    private fun gravar(pacote: PacoteDeFigurinhas) {
        val objeto = JSONObject()
            .put("nome", pacote.nome)
            .put("autor", pacote.autor)
            .put("animado", pacote.animado)
            .put("versao", pacote.versao)
            .put("figurinhas", JSONArray(pacote.figurinhas.map { figurinha ->
                JSONObject().put("arquivo", figurinha.arquivo).put("emojis", JSONArray(figurinha.emojis))
            }))
        val destino = File(pasta(pacote.id), ARQUIVO)
        val temporario = File(pasta(pacote.id), "$ARQUIVO.tmp")
        temporario.writeText(objeto.toString())
        if (!temporario.renameTo(destino)) {
            destino.delete()
            temporario.renameTo(destino)
        }
    }

    companion object {
        const val ARQUIVO = "pacote.json"
        const val BANDEJA = "bandeja.png"
        const val AUTOR_PADRAO = "Smells Like Tech Converter"
        const val EMOJI_PADRAO = "😀"

        /** O WhatsApp aceita só letras, números, "_", "-", "." e espaço no identificador. */
        fun idValido(id: String) = id.isNotEmpty() && id.length <= 128 && id.all { it.isLetterOrDigit() || it in "_-. " }

        fun nomeLimpo(nome: String) = nome.trim().replace(Regex("\\s+"), " ").take(Regras.NOME_MAX).ifBlank { "Minhas figurinhas" }
    }
}
