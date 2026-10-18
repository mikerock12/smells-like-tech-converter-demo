package br.com.smellsliketech.converter.figurinhas

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/**
 * O que o WhatsApp lê para mostrar e instalar os nossos pacotes, no contrato público de
 * figurinhas de terceiros: a lista de pacotes (`metadata`), as figurinhas de um pacote
 * (`stickers`) e os arquivos (`stickers_asset`). Só lê; o WhatsApp precisa da permissão
 * `com.whatsapp.sticker.READ` (manifesto), então outros apps não enxergam nada.
 */
class ProvedorDeFigurinhas : ContentProvider() {
    private lateinit var pacotes: Pacotes
    private lateinit var autoridade: String
    private lateinit var rotas: UriMatcher

    override fun onCreate(): Boolean {
        val contexto = context ?: return false
        pacotes = Pacotes(contexto)
        autoridade = autoridade(contexto.packageName)
        rotas = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(autoridade, METADATA, LISTA)
            addURI(autoridade, "$METADATA/*", UM_PACOTE)
            addURI(autoridade, "$STICKERS/*", FIGURINHAS)
            addURI(autoridade, "$STICKERS_ASSET/*/*", ARQUIVO)
        }
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? =
        when (rotas.match(uri)) {
            LISTA -> cursorDePacotes(pacotes.listar().filter { it.prontoParaWhatsApp }, uri)
            UM_PACOTE -> cursorDePacotes(listOfNotNull(pacotes.ler(uri.lastPathSegment.orEmpty())), uri)
            FIGURINHAS -> cursorDeFigurinhas(uri)
            else -> throw IllegalArgumentException("Endereço desconhecido: $uri")
        }

    private fun cursorDePacotes(lista: List<PacoteDeFigurinhas>, uri: Uri): Cursor {
        val cursor = MatrixCursor(
            arrayOf(
                "sticker_pack_identifier", "sticker_pack_name", "sticker_pack_publisher", "sticker_pack_icon",
                "android_play_store_link", "ios_app_download_link", "sticker_pack_publisher_email",
                "sticker_pack_publisher_website", "sticker_pack_privacy_policy_website",
                "sticker_pack_license_agreement_website", "image_data_version", "whatsapp_will_not_cache_stickers",
                "animated_sticker_pack",
            ),
        )
        lista.forEach { pacote ->
            cursor.addRow(
                arrayOf<Any>(
                    pacote.id, pacote.nome, pacote.autor, Pacotes.BANDEJA,
                    LINK_DA_LOJA, "", EMAIL, SITE, PRIVACIDADE, TERMOS,
                    pacote.versao.toString(), 0, if (pacote.animado) 1 else 0,
                ),
            )
        }
        cursor.setNotificationUri(context!!.contentResolver, uri)
        return cursor
    }

    private fun cursorDeFigurinhas(uri: Uri): Cursor {
        val cursor = MatrixCursor(arrayOf("sticker_file_name", "sticker_emoji", "sticker_accessibility_text"))
        pacotes.ler(uri.lastPathSegment.orEmpty())?.figurinhas?.forEach { figurinha ->
            cursor.addRow(arrayOf<Any>(figurinha.arquivo, figurinha.emojis.joinToString(","), ""))
        }
        cursor.setNotificationUri(context!!.contentResolver, uri)
        return cursor
    }

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor {
        if (rotas.match(uri) != ARQUIVO || mode != "r") throw FileNotFoundException("Só leitura de figurinhas: $uri")
        val partes = uri.pathSegments
        val id = partes.getOrNull(partes.size - 2).orEmpty()
        val nome = partes.lastOrNull().orEmpty()
        val pacote = pacotes.ler(id) ?: throw FileNotFoundException("Pacote não existe: $id")
        // Só arquivos do próprio pacote: nada de "../" nem de outro caminho.
        val permitido = nome == Pacotes.BANDEJA || pacote.figurinhas.any { it.arquivo == nome }
        val arquivo = File(pacotes.pasta(id), nome)
        if (!permitido || !arquivo.isFile) throw FileNotFoundException("Arquivo não existe: $nome")
        return AssetFileDescriptor(ParcelFileDescriptor.open(arquivo, ParcelFileDescriptor.MODE_READ_ONLY), 0, AssetFileDescriptor.UNKNOWN_LENGTH)
    }

    override fun getType(uri: Uri): String = when (rotas.match(uri)) {
        LISTA -> "vnd.android.cursor.dir/vnd.$autoridade.$METADATA"
        UM_PACOTE -> "vnd.android.cursor.item/vnd.$autoridade.$METADATA"
        FIGURINHAS -> "vnd.android.cursor.dir/vnd.$autoridade.$STICKERS"
        ARQUIVO -> if (uri.lastPathSegment.orEmpty().endsWith(".png")) "image/png" else "image/webp"
        else -> throw IllegalArgumentException("Endereço desconhecido: $uri")
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()

    companion object {
        private const val METADATA = "metadata"
        private const val STICKERS = "stickers"
        private const val STICKERS_ASSET = "stickers_asset"
        private const val LISTA = 1
        private const val UM_PACOTE = 2
        private const val FIGURINHAS = 3
        private const val ARQUIVO = 4

        private const val LINK_DA_LOJA = "https://play.google.com/store/apps/details?id=br.com.smellsliketech.converter"
        private const val EMAIL = "converter@smellsliketech.com.br"
        private const val SITE = "https://converter.smellsliketech.com.br"
        private const val PRIVACIDADE = "https://converter.smellsliketech.com.br/privacidade"
        private const val TERMOS = "https://converter.smellsliketech.com.br/termos"

        /** Uma autoridade por instalação (a versão de teste tem outro pacote). */
        fun autoridade(pacoteDoApp: String) = "$pacoteDoApp.figurinhas"
    }
}
