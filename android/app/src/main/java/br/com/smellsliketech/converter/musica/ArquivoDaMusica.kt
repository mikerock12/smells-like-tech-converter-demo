package br.com.smellsliketech.converter.musica

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class EntradaDoArquivo(val nome: String, val tamanho: Long, val comprimido: Long)

/**
 * O .sltmusic: ZIP com projeto.json e midias/<id>.<ext>, espelho de musica-arquivo.mjs. Confere
 * caminhos, tetos e bomba de compressão; extrai conferindo o tamanho de verdade (o cabeçalho do
 * ZIP pode mentir).
 */
object ArquivoDaMusica {
    const val EXTENSAO = "sltmusic"
    const val LIMITE_TOTAL = 4L * 1024 * 1024 * 1024
    const val LIMITE_POR_MIDIA = 2L * 1024 * 1024 * 1024
    const val LIMITE_DO_PROJETO = 16L * 1024 * 1024
    const val LIMITE_DE_ENTRADAS = 2048
    const val RAZAO_MAXIMA = 200.0
    val EXTENSOES = listOf("wav", "flac", "mp3", "m4a", "aac", "ogg", "opus")
    private val COMPRIMIDOS = setOf("flac", "mp3", "m4a", "aac", "ogg", "opus")
    private val CAMINHO = Regex("^midias/[a-z0-9][a-z0-9-]{0,63}\\.(wav|flac|mp3|m4a|aac|ogg|opus)$")

    fun caminhoDaMidia(m: MidiaMusical): String {
        val ext = m.formato.lowercase()
        return "midias/${m.id}.${if (ext in EXTENSOES) ext else "wav"}"
    }

    fun comprimir(caminho: String) = caminho.substringAfterLast('.').lowercase() !in COMPRIMIDOS

    fun conferirEntradas(entradas: List<EntradaDoArquivo>): String? {
        if (entradas.size > LIMITE_DE_ENTRADAS) return "O arquivo tem entradas demais."
        val nomes = mutableSetOf<String>()
        var total = 0L
        var temProjeto = false
        for (e in entradas) {
            if (!nomes.add(e.nome)) return "O arquivo tem entradas repetidas."
            if (e.nome == "projeto.json") {
                temProjeto = true
                if (e.tamanho > LIMITE_DO_PROJETO) return "O projeto.json é grande demais."
            } else if (!CAMINHO.matches(e.nome)) return "Entrada não permitida no projeto: ${e.nome.take(80)}"
            else if (e.tamanho > LIMITE_POR_MIDIA) return "Uma mídia do projeto passa de 2 GB."
            if (e.tamanho < 0 || e.comprimido < 0) return "Entrada com tamanho inválido."
            if (e.tamanho > 10 * 1024 * 1024 && e.tamanho / maxOf(1L, e.comprimido).toDouble() > RAZAO_MAXIMA) return "O arquivo parece uma bomba de compressão e foi recusado."
            total += e.tamanho
            if (total > LIMITE_TOTAL) return "O projeto passa de 4 GB descomprimido."
        }
        return if (temProjeto) null else "O arquivo não tem projeto.json."
    }

    fun conferirProjeto(p: ProjetoMusical, nomes: Collection<String>): List<String> {
        if (p.versao > Musica.VERSAO) return listOf("Este projeto foi salvo numa versão mais nova (${p.versao}). Atualize o aplicativo para abrir.")
        val erros = Musica.problemas(p).toMutableList()
        for (m in p.midias) if (caminhoDaMidia(m) !in nomes) erros += "A mídia ${m.nome.ifEmpty { m.id }} não está no arquivo."
        return erros
    }

    /** Grava o projeto e as mídias num .sltmusic. WAV comprime; o resto vai guardado (STORED). */
    fun salvar(p: ProjetoMusical, arquivoDaMidia: (MidiaMusical) -> File, saida: OutputStream) {
        val problemas = Musica.problemas(p)
        require(problemas.isEmpty()) { problemas.joinToString(" ") }
        ZipOutputStream(saida.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("projeto.json"))
            zip.write(p.paraJson().toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            for (m in p.midias) {
                val caminho = caminhoDaMidia(m)
                val origem = arquivoDaMidia(m)
                val entrada = ZipEntry(caminho)
                if (!comprimir(caminho)) {
                    val crc = CRC32()
                    origem.inputStream().use { input -> val b = ByteArray(1 shl 16); while (true) { val n = input.read(b); if (n < 0) break; crc.update(b, 0, n) } }
                    entrada.method = ZipEntry.STORED
                    entrada.size = origem.length(); entrada.compressedSize = origem.length(); entrada.crc = crc.value
                }
                zip.putNextEntry(entrada)
                origem.inputStream().use { it.copyTo(zip, 1 shl 16) }
                zip.closeEntry()
            }
        }
    }

    /**
     * Abre um .sltmusic na pasta (mídias em pasta/midias). O ZIP é lido em fluxo; cada entrada é
     * conferida pelo caminho e pelo tamanho real extraído, e o projeto só vale no fim.
     */
    fun abrir(entrada: InputStream, pasta: File): ProjetoMusical {
        val midias = File(pasta, "midias").apply { mkdirs() }
        var json: String? = null
        val nomes = mutableListOf<String>()
        val vistos = mutableListOf<EntradaDoArquivo>()
        ZipInputStream(entrada.buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val nome = e.name
                require(nome == "projeto.json" || CAMINHO.matches(nome)) { "Entrada não permitida no projeto: ${nome.take(80)}" }
                require(nome !in nomes) { "O arquivo tem entradas repetidas." }
                require(vistos.size < LIMITE_DE_ENTRADAS) { "O arquivo tem entradas demais." }
                val limite = if (nome == "projeto.json") LIMITE_DO_PROJETO else LIMITE_POR_MIDIA
                var total = 0L
                if (nome == "projeto.json") {
                    val bytes = java.io.ByteArrayOutputStream()
                    val b = ByteArray(1 shl 16)
                    while (true) { val n = zip.read(b); if (n < 0) break; total += n; require(total <= limite) { "O projeto.json é grande demais." }; bytes.write(b, 0, n) }
                    json = bytes.toString(Charsets.UTF_8.name())
                } else {
                    val destino = File(pasta, nome).canonicalFile
                    require(destino.path.startsWith(midias.canonicalPath)) { "Caminho inválido no projeto." }
                    destino.outputStream().use { out ->
                        val b = ByteArray(1 shl 16)
                        while (true) { val n = zip.read(b); if (n < 0) break; total += n; require(total <= limite) { "Uma mídia do projeto passa de 2 GB." }; out.write(b, 0, n) }
                    }
                }
                vistos += EntradaDoArquivo(nome, total, if (e.compressedSize >= 0) e.compressedSize else total)
                nomes += nome
            }
        }
        conferirEntradas(vistos)?.let { error(it) }
        val p = ProjetoMusical.deTexto(json ?: error("O arquivo não tem projeto.json."))
        val erros = conferirProjeto(p, nomes)
        require(erros.isEmpty()) { erros.joinToString(" ") }
        return p
    }
}
