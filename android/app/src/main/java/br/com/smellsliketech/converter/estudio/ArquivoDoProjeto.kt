package br.com.smellsliketech.converter.estudio

import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Mesmo .sltstudio do site/Windows: projeto.json e mídias incluídas. */
object ArquivoDoProjeto {
    private const val LIMITE=4L*1024*1024*1024
    private val seguro=Regex("^midias/[0-9]{3}\\.[a-z0-9]{1,10}$")
    fun salvar(original: Projeto,saida: OutputStream) {
        val p=Criacao.atualizar(original)
        require(Estudio.problemas(p).isEmpty()) { Estudio.problemas(p).joinToString(" ") }
        val mapa=linkedMapOf<String,String>();var total=0L
        ZipOutputStream(saida).use { zip ->
            zip.setLevel(0)
            Criacao.arquivos(p).forEach { caminho ->
                val arquivo=File(caminho);require(arquivo.isFile) { "Uma mídia do projeto não está disponível." }
                total+=arquivo.length();require(total<=LIMITE&&mapa.size<511) { "Use até 4 GB e 511 mídias por projeto." }
                val ext=arquivo.extension.lowercase().replace(Regex("[^a-z0-9]"),"").take(10).ifBlank {"bin"}
                val nome="midias/${mapa.size.toString().padStart(3,'0')}.$ext";mapa[caminho]=nome
                zip.putNextEntry(ZipEntry(nome));arquivo.inputStream().use {it.copyTo(zip)};zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("projeto.json"));zip.write(Criacao.mapear(Criacao.atualizar(p)) {mapa.getValue(it)}.paraJson().toString().toByteArray(Charsets.UTF_8));zip.closeEntry()
        }
    }
    /** Pasta nova; não substitui o projeto em uso até a leitura inteira terminar. */
    fun abrir(entrada: InputStream,pasta: File,temporario: File): Projeto {
        require(!pasta.exists()) {"Escolha uma pasta nova para abrir o projeto."}
        pasta.mkdirs()
        try {
            entrada.use { origem -> temporario.outputStream().use {destino ->
                val buffer=ByteArray(65536);var total=0L
                while(true) {val n=origem.read(buffer);if(n<0) break;total+=n;require(total<=LIMITE) {"Projeto grande demais."};destino.write(buffer,0,n)}
            }}
            ZipFile(temporario).use {zip ->
                val entradas=zip.entries().toList()
                require(entradas.size<=512&&entradas.sumOf {it.size}<=LIMITE&&entradas.all {it.size>=0&&(it.name=="projeto.json"||seguro.matches(it.name))}&&entradas.map {it.name}.distinct().size==entradas.size) {"Projeto inválido ou grande demais."}
                val json=zip.getEntry("projeto.json");require(json!=null&&json.size<=4*1024*1024) {"O arquivo não contém um projeto válido."}
                val p=zip.getInputStream(json).bufferedReader().use {Projeto.deJson(JSONObject(it.readText()))}
                require(Estudio.problemas(p).isEmpty()) {Estudio.problemas(p).joinToString(" ")}
                val mapa=mutableMapOf<String,String>();File(pasta,"midias").mkdirs()
                Criacao.arquivos(p).forEach {ref ->
                    require(seguro.matches(ref)) {"Referência de mídia inválida."}
                    val item=zip.getEntry(ref);require(item!=null) {"Uma mídia está ausente no projeto salvo."}
                    val arquivo=File(pasta,ref);zip.getInputStream(item).use {origem -> arquivo.outputStream().use {origem.copyTo(it)}};mapa[ref]=arquivo.absolutePath
                }
                return Criacao.mapear(Criacao.atualizar(p)) {mapa.getValue(it)}
            }
        } catch(e: Exception) {pasta.deleteRecursively();throw e} finally {temporario.delete()}
    }
}
