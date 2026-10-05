package br.com.smellsliketech.converter.conversao.documentos

import br.com.smellsliketech.converter.conversao.ErroDeConversao
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DocumentosTest {
    private val exemplo = Documento(
        listOf(
            Bloco.Titulo(1, "Relatório & resumo"),
            Bloco.Paragrafo("Primeira linha\nsegunda linha"),
            Bloco.Item("maçã"),
            Bloco.Item("pera", numero = 2),
            Bloco.Tabela(listOf(listOf("Nome", "Valor"), listOf("a|b", "1,5"))),
            Bloco.Quebra,
            Bloco.Paragrafo("Fim"),
        ),
    )

    private fun zip(vararg partes: Pair<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            partes.forEach { (nome, conteudo) ->
                zip.putNextEntry(ZipEntry(nome))
                zip.write(conteudo.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun recurso(nome: String) = javaClass.classLoader!!.getResourceAsStream("office97/$nome")!!.readBytes()

    private fun textos(documento: Documento) = documento.blocos.mapNotNull {
        when (it) {
            is Bloco.Titulo -> it.texto
            is Bloco.Paragrafo -> it.texto
            is Bloco.Item -> it.texto
            else -> null
        }
    }

    // ------------------------------------------------------------------ escritores

    @Test
    fun `texto e markdown`() {
        assertEquals(
            "Relatório & resumo\n\nPrimeira linha\nsegunda linha\n\n• maçã\n2. pera\nNome\tValor\na|b\t1,5\n\nFim\n",
            Escritores.texto(exemplo),
        )
        val md = Escritores.markdown(exemplo)
        assertTrue(md.startsWith("# Relatório & resumo\n\nPrimeira linha  \nsegunda linha\n\n- maçã\n2. pera\n\n"))
        assertTrue("| Nome | Valor |\n| --- | --- |\n| a\\|b | 1,5 |" in md)
    }

    @Test
    fun `html escapa e agrupa listas`() {
        val html = Escritores.html(exemplo, "<título>")
        assertTrue("<title>&lt;título&gt;</title>" in html)
        assertTrue("<h1>Relatório &amp; resumo</h1>" in html)
        assertTrue("<p>Primeira linha<br>segunda linha</p>" in html)
        assertTrue("<ul>\n<li>maçã</li>\n</ul>\n<ol>\n<li>pera</li>\n</ol>" in html)
        assertTrue("<th>Nome</th>" in html)
    }

    @Test
    fun `csv com aspas quando precisa`() {
        assertEquals("Nome,Valor\r\na|b,\"1,5\"\r\n", Escritores.csv(exemplo))
        assertEquals("Só texto\r\n", Escritores.csv(Documento(listOf(Bloco.Paragrafo("Só texto")))))
    }

    @Test
    fun `docx gerado e lido de volta`() {
        val lido = Ooxml.docx(Escritores.docx(exemplo))
        assertEquals(Bloco.Titulo(1, "Relatório & resumo"), lido.blocos[0])
        assertEquals(Bloco.Paragrafo("Primeira linha\nsegunda linha"), lido.blocos[1])
        assertEquals(Bloco.Tabela(listOf(listOf("Nome", "Valor"), listOf("a|b", "1,5"))), lido.blocos.first { it is Bloco.Tabela })
        assertEquals("Fim", (lido.blocos.last() as Bloco.Paragrafo).texto)
    }

    // ------------------------------------------------------------------ leitores

    @Test
    fun `docx do Word em portugues, com estilo Ttulo1, lista, revisao e campo`() {
        val w = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
        val docx = zip(
            "word/document.xml" to """<?xml version="1.0"?><w:document xmlns:w="$w"><w:body>
                <w:p><w:pPr><w:pStyle w:val="Ttulo1"/></w:pPr><w:r><w:t>Capítulo</w:t></w:r></w:p>
                <w:p><w:pPr><w:numPr><w:ilvl w:val="0"/><w:numId w:val="1"/></w:numPr></w:pPr><w:r><w:t>item</w:t></w:r></w:p>
                <w:p><w:r><w:t xml:space="preserve">Texto </w:t></w:r><w:del><w:r><w:delText>apagado</w:delText></w:r></w:del><w:ins><w:r><w:t>novo</w:t></w:r></w:ins>
                  <w:r><w:fldChar w:fldCharType="begin"/></w:r><w:r><w:instrText>PAGE</w:instrText></w:r><w:r><w:fldChar w:fldCharType="separate"/></w:r><w:r><w:t>7</w:t></w:r><w:r><w:fldChar w:fldCharType="end"/></w:r></w:p>
                </w:body></w:document>""",
            "word/styles.xml" to """<w:styles xmlns:w="$w"><w:style w:type="paragraph" w:styleId="Ttulo1"><w:name w:val="heading 1"/></w:style></w:styles>""",
        )
        val documento = Leitores.ler("docx", docx)
        assertEquals(listOf(Bloco.Titulo(1, "Capítulo"), Bloco.Item("item"), Bloco.Paragrafo("Texto novo7")), documento.blocos)
    }

    @Test
    fun `xlsx com strings compartilhadas, numeros e datas`() {
        val xlsx = zip(
            "xl/workbook.xml" to """<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Vendas" sheetId="1" r:id="rId1"/></sheets></workbook>""",
            "xl/_rels/workbook.xml.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="x" Target="worksheets/sheet1.xml"/></Relationships>""",
            "xl/sharedStrings.xml" to """<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><si><t>Produto</t></si><si><r><t>Qu</t></r><r><t>ando</t></r></si></sst>""",
            "xl/styles.xml" to """<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><cellXfs><xf numFmtId="0"/><xf numFmtId="14"/></cellXfs></styleSheet>""",
            "xl/worksheets/sheet1.xml" to """<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>
                <row r="1"><c r="A1" t="s"><v>0</v></c><c r="C1" t="s"><v>1</v></c></row>
                <row r="2"><c r="A2" t="inlineStr"><is><t>Café</t></is></c><c r="B2"><v>0.30000000000000004</v></c><c r="C2" s="1"><v>46295</v></c></row>
                </sheetData></worksheet>""",
        )
        val documento = Leitores.ler("xlsx", xlsx)
        assertEquals(Bloco.Titulo(2, "Vendas"), documento.blocos[0])
        assertEquals(Bloco.Tabela(listOf(listOf("Produto", "", "Quando"), listOf("Café", "0.3", "30/09/2026"))), documento.blocos[1])
        assertEquals(26, Ooxml.coluna("AA1"))
    }

    @Test
    fun `pptx com titulo e texto por slide`() {
        val p = "http://schemas.openxmlformats.org/presentationml/2006/main"
        val a = "http://schemas.openxmlformats.org/drawingml/2006/main"
        val r = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        val slide = { titulo: String, corpo: String ->
            """<p:sld xmlns:p="$p" xmlns:a="$a"><p:cSld><p:spTree>
               <p:sp><p:nvSpPr><p:nvPr><p:ph type="title"/></p:nvPr></p:nvSpPr><p:txBody><a:p><a:r><a:t>$titulo</a:t></a:r></a:p></p:txBody></p:sp>
               <p:sp><p:nvSpPr><p:nvPr/></p:nvSpPr><p:txBody><a:p><a:r><a:t>$corpo</a:t></a:r></a:p></p:txBody></p:sp>
               </p:spTree></p:cSld></p:sld>"""
        }
        val pptx = zip(
            "ppt/presentation.xml" to """<p:presentation xmlns:p="$p" xmlns:r="$r"><p:sldIdLst><p:sldId id="256" r:id="rId2"/><p:sldId id="257" r:id="rId3"/></p:sldIdLst></p:presentation>""",
            "ppt/_rels/presentation.xml.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId2" Type="x" Target="slides/slide1.xml"/><Relationship Id="rId3" Type="x" Target="/ppt/slides/slide2.xml"/></Relationships>""",
            "ppt/slides/slide1.xml" to slide("Abertura", "Olá"),
            "ppt/slides/slide2.xml" to slide("Fim", "Tchau"),
        )
        assertEquals(
            listOf(Bloco.Titulo(2, "1. Abertura"), Bloco.Paragrafo("Olá"), Bloco.Quebra, Bloco.Titulo(2, "2. Fim"), Bloco.Paragrafo("Tchau")),
            Leitores.ler("pptx", pptx).blocos,
        )
    }

    @Test
    fun `odt, ods e odp`() {
        val ns = """xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0" xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0" xmlns:presentation="urn:oasis:names:tc:opendocument:xmlns:presentation:1.0""""
        val odt = zip("content.xml" to """<office:document-content $ns><office:body><office:text>
            <text:h text:outline-level="2">Seção</text:h>
            <text:p>Um<text:s text:c="2"/>dois<text:tab/>três<text:line-break/>quatro<text:note><text:note-body><text:p>nota</text:p></text:note-body></text:note></text:p>
            <text:list><text:list-item><text:p>item</text:p></text:list-item></text:list>
            </office:text></office:body></office:document-content>""")
        assertEquals(listOf(Bloco.Titulo(2, "Seção"), Bloco.Paragrafo("Um dois três\nquatro"), Bloco.Item("item")), Leitores.ler("odt", odt).blocos)

        val ods = zip("content.xml" to """<office:document-content $ns><office:body><office:spreadsheet><table:table table:name="Plan1">
            <table:table-row><table:table-cell><text:p>A</text:p></table:table-cell><table:table-cell table:number-columns-repeated="2"/><table:table-cell office:value-type="float" office:value="2.5"/><table:table-cell table:number-columns-repeated="16380"/></table:table-row>
            <table:table-row table:number-rows-repeated="1048575"><table:table-cell table:number-columns-repeated="16384"/></table:table-row>
            </table:table></office:spreadsheet></office:body></office:document-content>""")
        assertEquals(listOf(Bloco.Titulo(2, "Plan1"), Bloco.Tabela(listOf(listOf("A", "", "", "2.5")))), Leitores.ler("ods", ods).blocos)

        val odp = zip("content.xml" to """<office:document-content $ns><office:body><office:presentation>
            <draw:page><draw:frame presentation:class="title"><draw:text-box><text:p>Título</text:p></draw:text-box></draw:frame>
            <draw:frame presentation:class="outline"><draw:text-box><text:list><text:list-item><text:p>ponto</text:p></text:list-item></text:list></draw:text-box></draw:frame></draw:page>
            </office:presentation></office:body></office:document-content>""")
        assertEquals(listOf(Bloco.Titulo(2, "1. Título"), Bloco.Item("ponto")), Leitores.ler("odp", odp).blocos)
    }

    @Test
    fun `rtf com acentos, unicode, tabela e grupos ignorados`() {
        val rtf = """{\rtf1\ansi\ansicpg1252{\fonttbl{\f0 Arial;}}{\*\generator Teste;}
            \pard Ol\'e1, mundo\par
            Linha\line quebrada \u8364? euro\par
            \trowd\cellx1000\cellx2000\pard\intbl A\cell B\cell\row
            \pard Fim\par}""".toByteArray(Charsets.ISO_8859_1)
        assertEquals(
            listOf(Bloco.Paragrafo("Olá, mundo"), Bloco.Paragrafo("Linha\nquebrada € euro"), Bloco.Tabela(listOf(listOf("A", "B"))), Bloco.Paragrafo("Fim")),
            Leitores.ler("rtf", rtf).blocos,
        )
    }

    @Test
    fun `um doc que na verdade e rtf`() {
        assertEquals(listOf(Bloco.Paragrafo("oi")), Leitores.ler("doc", """{\rtf1 oi\par}""".toByteArray()).blocos)
    }

    @Test
    fun `markdown`() {
        val md = """
            # Título *grande*

            Um [link](https://x.y) e **negrito**
            continua aqui.

            - item `code`
            1. primeiro

            | a | b |
            |---|:-:|
            | 1 | 2 |

            ```
            código
              identado
            ```
        """.trimIndent()
        assertEquals(
            listOf(
                Bloco.Titulo(1, "Título grande"),
                Bloco.Paragrafo("Um link e negrito continua aqui."),
                Bloco.Item("item code"),
                Bloco.Item("primeiro", 1),
                Bloco.Tabela(listOf(listOf("a", "b"), listOf("1", "2"))),
                Bloco.Paragrafo("código\nidentado"),
            ),
            Leitores.ler("md", md.toByteArray()).blocos,
        )
    }

    @Test
    fun `html com listas aninhadas, tabela e script ignorado`() {
        val html = """<html><head><title>x</title><script>alert(1)</script></head><body>
            <h2>Sobre</h2><div>Solto <b>no</b> div<p>Parágrafo<br>com quebra</p></div>
            <ol start="3"><li>três<ul><li>dentro</li></ul></li></ol>
            <table><tr><th>A</th><td>B</td></tr></table></body></html>"""
        assertEquals(
            listOf(
                Bloco.Titulo(2, "Sobre"), Bloco.Paragrafo("Solto no div"), Bloco.Paragrafo("Parágrafo\ncom quebra"),
                Bloco.Item("três", 3), Bloco.Item("dentro"), Bloco.Tabela(listOf(listOf("A", "B"))),
            ),
            Leitores.ler("html", html.toByteArray()).blocos,
        )
    }

    @Test
    fun `epub na ordem de leitura`() {
        val epub = zip(
            "mimetype" to "application/epub+zip",
            "META-INF/container.xml" to """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/livro.opf"/></rootfiles></container>""",
            "OEBPS/livro.opf" to """<package xmlns="http://www.idpf.org/2007/opf"><manifest><item id="c1" href="cap%201.xhtml"/><item id="c2" href="cap2.xhtml"/></manifest><spine><itemref idref="c2"/><itemref idref="c1"/></spine></package>""",
            "OEBPS/cap 1.xhtml" to """<html><body><p>Um</p></body></html>""",
            "OEBPS/cap2.xhtml" to """<html><body><h1>Dois</h1></body></html>""",
        )
        assertEquals(listOf(Bloco.Titulo(1, "Dois"), Bloco.Quebra, Bloco.Paragrafo("Um")), Leitores.ler("epub", epub).blocos)
    }

    @Test
    fun `csv com ponto e virgula, aspas e windows-1252`() {
        val csv = "Nome;Obs\r\n\"Silva; J\";\"diz \"\"oi\"\"\"\r\nJoão;\r\n".toByteArray(charset("windows-1252"))
        assertEquals(Bloco.Tabela(listOf(listOf("Nome", "Obs"), listOf("Silva; J", "diz \"oi\""), listOf("João", ""))), Leitores.ler("csv", csv).blocos.single())
    }

    @Test
    fun `srt e txt viram documento`() {
        assertEquals(listOf(Bloco.Paragrafo("Oi\nTchau")), Leitores.ler("srt", "1\n00:00:01,000 --> 00:00:02,000\nOi\n\n2\n00:00:03,000 --> 00:00:04,000\nTchau\n".toByteArray()).blocos)
        assertEquals(listOf(Bloco.Paragrafo("a\nb"), Bloco.Paragrafo("c")), Leitores.ler("txt", "﻿a\nb\n\n\nc".toByteArray()).blocos)
    }

    @Test
    fun `documento vazio ou corrompido da erro claro`() {
        assertThrows(ErroDeConversao::class.java) { Leitores.ler("docx", "não é zip".toByteArray()) }
        assertThrows(ErroDeConversao::class.java) { Leitores.ler("txt", "   ".toByteArray()) }
        assertThrows(ErroDeConversao::class.java) { Leitores.ler("doc", ByteArray(600)) }
    }

    // ------------------------------------------------------------------ Office 97 (amostras do Apache POI)

    @Test
    fun `doc do Word 97`() {
        val textos = textos(Leitores.ler("doc", recurso("SampleDoc.doc")))
        assertTrue(textos.toString(), "I am a test document" in textos)
        assertTrue(textos.toString(), "This is page two" in textos)
        assertTrue(textos.toString(), textos.none { "PAGE" in it || '\u0013' in it })
    }

    @Test
    fun `xls do Excel 97`() {
        val documento = Leitores.ler("xls", recurso("SampleSS.xls"))
        val tabelas = documento.blocos.filterIsInstance<Bloco.Tabela>()
        assertTrue(documento.blocos.toString(), tabelas.isNotEmpty())
        assertEquals(Bloco.Titulo(2, "First Sheet"), documento.blocos.first())
        assertEquals(listOf("2nd row", "2nd row 2nd column"), tabelas.first().linhas[1])
        // Fórmulas saem com o resultado guardado: a soma dá 13.
        assertEquals(listOf("1", "10", "2", "13"), tabelas[1].linhas.last())
    }

    @Test
    fun `ppt do PowerPoint 97`() {
        val documento = Leitores.ler("ppt", recurso("SampleShow.ppt"))
        assertTrue(documento.blocos.toString(), documento.blocos.first() is Bloco.Titulo)
        assertTrue(documento.blocos.toString(), textos(documento).none { "Click to edit Master" in it })
    }

    @Test
    fun `semCampos e montarDoc com tabela`() {
        assertEquals("Página 7 de 9", Office97.semCampos("Página \u0013 PAGE \u00147\u0015 de \u0013NUMPAGES\u00149\u0015"))
        val documento = Office97.montarDoc("Antes\rA\u0007B\u0007\u0007C\u0007D\u0007\u0007Depois\r")
        assertEquals(
            listOf(Bloco.Paragrafo("Antes"), Bloco.Tabela(listOf(listOf("A", "B"), listOf("C", "D"))), Bloco.Paragrafo("Depois")),
            documento.blocos,
        )
        assertEquals(12.5, Office97.rk(0x40290000), 0.0) // os 30 bits altos do double 12,5
        assertEquals(123.0, Office97.rk((123 shl 2) or 2), 0.0)
        assertEquals(1.23, Office97.rk((123 shl 2) or 3), 1e-9)
    }
}
