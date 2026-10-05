package br.com.smellsliketech.converter.conversao

import android.content.Context
import br.com.smellsliketech.converter.conversao.documentos.Codificacao
import br.com.smellsliketech.converter.conversao.documentos.Documento
import br.com.smellsliketech.converter.conversao.documentos.EscritorDePdf
import br.com.smellsliketech.converter.conversao.documentos.Escritores
import br.com.smellsliketech.converter.conversao.documentos.Leitores
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Liga cada ferramenta ao seu motor. A conversão é local; modelos e vozes precisam estar
 * instalados antes. Compras, restauração pela loja e páginas externas exigem internet.
 */
object Motor {
    /** Documentos maiores que isto não cabem na memória de um celular simples. */
    private const val BYTES_DE_DOCUMENTO = 150L * 1024 * 1024

    suspend fun executar(
        context: Context,
        ferramenta: Ferramenta,
        entradas: List<Entrada>,
        opcoes: Opcoes,
        aoAvancar: (Int) -> Unit,
    ): List<Resultado> = withContext(Dispatchers.Default) {
        /** Um arquivo por vez, com o progresso de cada um somado no total. */
        suspend fun porArquivo(fazer: suspend (Entrada, (Int) -> Unit) -> List<Resultado>): List<Resultado> =
            entradas.flatMapIndexed { indice, entrada ->
                fazer(entrada) { parcial -> aoAvancar((indice * 100 + parcial.coerceIn(0, 100)) / entradas.size) }
                    .also { aoAvancar(((indice + 1) * 100) / entradas.size) }
            }
        suspend fun umPorArquivo(fazer: suspend (Entrada, (Int) -> Unit) -> Resultado) = porArquivo { entrada, progresso -> listOf(fazer(entrada, progresso)) }

        when (ferramenta) {
            Ferramenta.IMAGEM_CONVERTER -> umPorArquivo { entrada, progresso -> Imagem.converter(context, entrada, opcoes, "", progresso) }
            Ferramenta.IMAGEM_COMPRIMIR -> umPorArquivo { entrada, progresso -> Imagem.converter(context, entrada, opcoes, "comprimido", progresso) }
            Ferramenta.IMAGEM_REDIMENSIONAR -> umPorArquivo { entrada, progresso ->
                Imagem.converter(context, entrada, opcoes, if (opcoes.larguraMaxima > 0) "${opcoes.larguraMaxima}px" else "redimensionado", progresso)
            }
            Ferramenta.IMAGEM_RECORTAR -> umPorArquivo { entrada, progresso -> Imagem.converter(context, entrada, opcoes, opcoes.proporcao.rotulo.replace(':', 'x'), progresso) }
            Ferramenta.IMAGEM_GIRAR -> umPorArquivo { entrada, progresso -> Imagem.converter(context, entrada, opcoes, "girado", progresso) }
            Ferramenta.IMAGEM_ICONE -> umPorArquivo { entrada, progresso -> Imagem.converter(context, entrada, opcoes.copy(formato = "ico"), "", progresso) }
            Ferramenta.IMAGENS_PARA_PDF -> listOf(Imagem.paraPdf(context, entradas, aoAvancar))
            Ferramenta.IMAGEM_OCR -> umPorArquivo { entrada, progresso ->
                progresso(10)
                val texto = Ocr.textoDaImagem(context, entrada)
                progresso(85)
                if (texto.isBlank()) throw ErroDeConversao("Nenhum texto encontrado em ${entrada.nome}.")
                gravarDocumento(context, Documento.deTexto(texto), entrada.nome, "texto", opcoes.formato)
            }

            Ferramenta.VIDEO_CONVERTER -> umPorArquivo { entrada, progresso -> Midia.video(context, entrada, opcoes, "", progresso) }
            Ferramenta.GIF_PARA_VIDEO -> umPorArquivo { entrada, progresso ->
                if (entrada.extensao != "gif") throw ErroDeConversao("GIF → vídeo aceita apenas arquivos GIF.")
                Midia.video(context, entrada, opcoes.copy(removerAudio = true), "", progresso)
            }
            Ferramenta.VIDEO_COMPRIMIR -> umPorArquivo { entrada, progresso -> Midia.video(context, entrada, opcoes, "comprimido", progresso) }
            Ferramenta.VIDEO_CORTAR -> umPorArquivo { entrada, progresso -> Midia.video(context, entrada, opcoes, "cortado", progresso) }
            Ferramenta.VIDEO_SEM_AUDIO -> umPorArquivo { entrada, progresso -> Midia.video(context, entrada, opcoes.copy(removerAudio = true), "sem-audio", progresso) }
            Ferramenta.VIDEO_PARA_AUDIO -> umPorArquivo { entrada, progresso -> Midia.audio(context, entrada, opcoes, "", progresso) }
            Ferramenta.VIDEO_GIF -> umPorArquivo { entrada, progresso -> Midia.gif(context, entrada, opcoes, progresso) }
            Ferramenta.VIDEO_QUADROS -> porArquivo { entrada, progresso -> Midia.quadros(context, entrada, opcoes, progresso) }
            Ferramenta.VIDEO_TRANSCREVER, Ferramenta.AUDIO_TRANSCREVER -> error("A transcrição volta como texto: use Motor.transcrever.")

            Ferramenta.AUDIO_CONVERTER -> umPorArquivo { entrada, progresso -> Midia.audio(context, entrada, opcoes, "", progresso) }
            Ferramenta.AUDIO_CORTAR -> umPorArquivo { entrada, progresso -> Midia.audio(context, entrada, opcoes, "cortado", progresso) }
            Ferramenta.AUDIO_EDITAR -> umPorArquivo { entrada, progresso -> Midia.audio(context, entrada, opcoes, "editado", progresso) }

            Ferramenta.PDF_JUNTAR -> listOf(withContext(Dispatchers.IO) { Pdf.juntar(context, entradas, aoAvancar) })
            Ferramenta.PDF_DIVIDIR -> porArquivo { entrada, progresso -> withContext(Dispatchers.IO) { Pdf.dividir(context, entrada, opcoes, progresso) } }
            Ferramenta.PDF_GIRAR -> umPorArquivo { entrada, progresso -> withContext(Dispatchers.IO) { Pdf.girar(context, entrada, opcoes.graus, progresso) } }
            Ferramenta.PDF_COMPRIMIR -> umPorArquivo { entrada, progresso -> withContext(Dispatchers.IO) { Pdf.comprimir(context, entrada, opcoes.compressaoPdf, progresso) } }
            Ferramenta.PDF_PARA_IMAGENS -> porArquivo { entrada, progresso -> Pdf.paraImagens(context, entrada, opcoes, progresso) }
            Ferramenta.PDF_PARA_DOCUMENTO -> umPorArquivo { entrada, progresso ->
                val documento = Pdf.paraDocumento(context, entrada, progresso)
                if (documento.vazio) throw ErroDeConversao("Nenhum texto encontrado em ${entrada.nome}.")
                gravarDocumento(context, documento, entrada.nome, "", opcoes.formato)
            }

            Ferramenta.DOCUMENTO_CONVERTER, Ferramenta.TEXTO_CONVERTER -> umPorArquivo { entrada, progresso ->
                progresso(5)
                val bytes = ler(context, entrada)
                progresso(25)
                val documento = Leitores.ler(entrada.extensao.ifBlank { "txt" }, bytes)
                progresso(60)
                gravarDocumento(context, documento, entrada.nome, if (entrada.extensao == opcoes.formato) "convertido" else "", opcoes.formato)
            }

            Ferramenta.LEGENDA_CONVERTER -> umPorArquivo { entrada, progresso ->
                progresso(30)
                val trechos = Legenda.deslocar(Legenda.limpar(Legenda.ler(textoDe(context, entrada))), opcoes.atrasoLegendaSegundos)
                if (trechos.isEmpty()) throw ErroDeConversao("${entrada.nome} não tem legendas.")
                val texto = Legenda.gravar(trechos, opcoes.formato)
                withContext(Dispatchers.IO) {
                    Saida.gravar(context, Saida.nomeDeSaida(entrada.nome, if (entrada.extensao == opcoes.formato) "convertido" else "", opcoes.formato), Mimes.de(opcoes.formato)) {
                        it.write(texto.toByteArray(Charsets.UTF_8))
                    }
                }
            }

            Ferramenta.TEXTO_NARRAR -> umPorArquivo { entrada, progresso ->
                Narracao.narrar(context, textoDe(context, entrada), entrada.nome, opcoes, progresso)
            }
            // Ler em voz alta: o texto sai do arquivo (com OCR quando é foto ou PDF escaneado),
            // depois a voz do celular narra. A leitura ocupa os primeiros 30% da barra.
            Ferramenta.PDF_NARRAR, Ferramenta.DOCUMENTO_NARRAR, Ferramenta.IMAGEM_NARRAR -> umPorArquivo { entrada, progresso ->
                val texto = when (ferramenta) {
                    Ferramenta.PDF_NARRAR -> Escritores.texto(Pdf.paraDocumento(context, entrada) { progresso(it * 30 / 100) })
                    Ferramenta.IMAGEM_NARRAR -> Ocr.textoDaImagem(context, entrada).also { progresso(30) }
                    else -> Escritores.texto(Leitores.ler(entrada.extensao.ifBlank { "txt" }, ler(context, entrada))).also { progresso(30) }
                }.replace('\t', ' ').trim()
                if (texto.isBlank()) throw ErroDeConversao("Não há texto para ler em ${entrada.nome}.")
                Narracao.narrar(context, texto, entrada.nome, opcoes) { parcial -> progresso(30 + parcial * 70 / 100) }
            }
            Ferramenta.LEGENDA_NARRAR -> umPorArquivo { entrada, progresso ->
                Narracao.narrar(context, Legenda.falaCorrida(textoDe(context, entrada)), entrada.nome, opcoes, progresso)
            }
        }
    }

    /**
     * Transcreve cada arquivo e devolve o texto (a pessoa lê, copia e escolhe em que formato
     * salvar). `aoEstimar`: os segundos que faltam, calculados pelo ritmo do Whisper.
     */
    suspend fun transcrever(
        context: Context,
        entradas: List<Entrada>,
        opcoes: Opcoes,
        aoAvancar: (Int) -> Unit,
        aoEstimar: (Int?) -> Unit,
    ): List<Transcricao> = withContext(Dispatchers.Default) {
        entradas.mapIndexed { indice, entrada ->
            Midia.transcrever(
                context, entrada, opcoes,
                aoAvancar = { parcial -> aoAvancar((indice * 100 + parcial.coerceIn(0, 100)) / entradas.size) },
                // Com vários arquivos, o tempo que falta deste não é o do lote: aí a tela estima pela porcentagem.
                aoEstimar = { restante -> if (entradas.size == 1) aoEstimar(restante) },
            ).also { aoAvancar(((indice + 1) * 100) / entradas.size) }
        }
    }

    private suspend fun ler(context: Context, entrada: Entrada): ByteArray = withContext(Dispatchers.IO) {
        if (entrada.bytes > BYTES_DE_DOCUMENTO) throw ErroDeConversao("${entrada.nome} é grande demais para abrir no celular.")
        context.contentResolver.openInputStream(entrada.uri)?.use { it.readBytes() }
            ?: throw ErroDeConversao("Não deu para ler ${entrada.nome}.")
    }

    private suspend fun textoDe(context: Context, entrada: Entrada): String =
        Codificacao.decodificar(ler(context, entrada))

    private suspend fun gravarDocumento(context: Context, documento: Documento, nomeOriginal: String, sufixo: String, formato: String): Resultado =
        withContext(Dispatchers.IO) {
            val nome = Saida.nomeDeSaida(nomeOriginal, sufixo, formato)
            val titulo = nomeOriginal.substringBeforeLast('.')
            Saida.gravar(context, nome, Mimes.de(formato)) { saida ->
                when (formato) {
                    "pdf" -> EscritorDePdf.escrever(documento, saida)
                    "docx" -> saida.write(Escritores.docx(documento))
                    "txt" -> saida.write(Escritores.texto(documento).toByteArray(Charsets.UTF_8))
                    "md" -> saida.write(Escritores.markdown(documento).toByteArray(Charsets.UTF_8))
                    "html" -> saida.write(Escritores.html(documento, titulo).toByteArray(Charsets.UTF_8))
                    // Com BOM: o Excel só reconhece acentos em CSV UTF-8 assim.
                    "csv" -> saida.write(("﻿" + Escritores.csv(documento)).toByteArray(Charsets.UTF_8))
                    else -> throw ErroDeConversao("Formato de documento não suportado: $formato.")
                }
            }
        }
}
