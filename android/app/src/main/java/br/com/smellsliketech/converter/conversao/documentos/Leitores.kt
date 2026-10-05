package br.com.smellsliketech.converter.conversao.documentos

import br.com.smellsliketech.converter.conversao.ErroDeConversao
import br.com.smellsliketech.converter.conversao.Legenda

/** Escolhe o leitor pela extensão e, quando ela engana (um .doc que é RTF ou HTML), pelo conteúdo. */
object Leitores {
    fun ler(extensao: String, bytes: ByteArray): Documento {
        val documento = when (extensaoReal(extensao.lowercase(), bytes)) {
            "doc" -> Office97.doc(bytes)
            "xls" -> Office97.xls(bytes)
            "ppt" -> Office97.ppt(bytes)
            "docx", "docm", "dotx" -> Ooxml.docx(bytes)
            "xlsx", "xlsm" -> Ooxml.xlsx(bytes)
            "pptx", "pptm", "ppsx" -> Ooxml.pptx(bytes)
            "odt" -> OpenDocument.odt(bytes)
            "ods" -> OpenDocument.ods(bytes)
            "odp" -> OpenDocument.odp(bytes)
            "rtf" -> Rtf.ler(bytes)
            "html", "htm", "xhtml" -> Web.html(bytes)
            "epub" -> Web.epub(bytes)
            "md", "markdown" -> Web.markdown(bytes)
            "csv" -> Web.csv(bytes)
            "srt", "vtt" -> Documento.deTexto(Legenda.texto(Legenda.limpar(Legenda.ler(Codificacao.decodificar(bytes)))))
            "txt" -> Documento.deTexto(Codificacao.decodificar(bytes))
            else -> throw ErroDeConversao("Formato de documento não suportado: .$extensao")
        }
        if (documento.vazio) throw ErroDeConversao("O documento não tem texto para converter.")
        return documento
    }

    /** O Word salva "doc" que é RTF ou HTML; o Excel abre "xls" que é HTML ou CSV. O conteúdo decide. */
    private fun extensaoReal(extensao: String, bytes: ByteArray): String {
        val inicio = String(bytes, 0, minOf(bytes.size, 512), Charsets.ISO_8859_1).trimStart('﻿', 'ï', '»', '¿', ' ', '\r', '\n', '\t')
        val zip = bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
        return when {
            inicio.startsWith("{\\rtf") -> "rtf"
            Cfb.eh(bytes) -> when (extensao) {
                "xls", "ppt", "doc" -> extensao
                "docx", "xlsx", "pptx" -> extensao.dropLast(1) // .docx salvo no formato antigo
                else -> "doc"
            }
            zip && extensao in listOf("doc", "xls", "ppt") -> extensao + "x"
            extensao in listOf("doc", "xls") && (inicio.startsWith("<") || inicio.contains("<html", ignoreCase = true)) -> "html"
            else -> extensao
        }
    }
}
