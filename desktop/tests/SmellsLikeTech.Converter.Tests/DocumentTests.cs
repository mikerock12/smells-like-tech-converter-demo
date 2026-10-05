using DocumentFormat.OpenXml.Packaging;
using ImageMagick;
using ImageMagick.Drawing;
using SmellsLikeTech.Converter.Core;
using SmellsLikeTech.Converter.Core.Abstractions;
using SmellsLikeTech.Converter.Core.Jobs;
using SmellsLikeTech.Converter.Core.Media;
using SmellsLikeTech.Converter.Core.Operations;
using SmellsLikeTech.Converter.Core.Options;
using SmellsLikeTech.Converter.Engine.Documents;
using SmellsLikeTech.Converter.Infrastructure.Storage;
using UglyToad.PdfPig.Content;
using UglyToad.PdfPig.Fonts.Standard14Fonts;
using UglyToad.PdfPig.Writer;
using Xunit;

namespace SmellsLikeTech.Converter.Tests;

/// <summary>
/// PDF para documento e reconhecimento de texto em imagem, com arquivos gerados na hora:
/// um PDF com camada de texto e um PDF que e so imagem, como sai de um escaner.
/// </summary>
public class DocumentTests : IDisposable
{
    private readonly string root = Path.Combine(Path.GetTempPath(), "slt-doc-" + Guid.NewGuid().ToString("N"));

    private const string Frase = "Smells Like Tech Converter processa arquivos localmente.";
    private const string SegundaPagina = "A segunda pagina fala de video, audio e imagem.";

    public DocumentTests() => Directory.CreateDirectory(root);

    private static bool OcrDisponivel => WindowsTextRecognizer.IsAvailable;

    // ==================== PDF com texto ====================

    [Fact]
    public async Task PdfComTexto_ViraTxtComOConteudoDasPaginas()
    {
        var pdf = CriarPdfComTexto("com-texto.pdf");
        var resultado = await ConverterPdfAsync(pdf, new PdfToDocumentOptions { OutputFormat = "txt" });

        var texto = await File.ReadAllTextAsync(resultado);
        Assert.Contains("Smells Like Tech Converter", texto);
        Assert.Contains("segunda pagina", texto, StringComparison.OrdinalIgnoreCase);
        Assert.Contains("Página 1", texto);
        Assert.Contains("Página 2", texto);
    }

    [Fact]
    public async Task PdfComTexto_ViraDocxValidoComOTextoDentro()
    {
        var pdf = CriarPdfComTexto("para-docx.pdf");
        var resultado = await ConverterPdfAsync(pdf, new PdfToDocumentOptions { OutputFormat = "docx" });

        Assert.EndsWith(".docx", resultado);

        // Abre como Office Open XML de verdade, não só um arquivo com a extensão certa.
        using var documento = WordprocessingDocument.Open(resultado, isEditable: false);
        var corpo = documento.MainDocumentPart?.Document?.Body;
        Assert.NotNull(corpo);
        Assert.Contains("Smells Like Tech Converter", corpo!.InnerText);
        Assert.Contains("Página 1", corpo.InnerText);
    }

    [Fact]
    public async Task PdfComTexto_ViraMarkdownComTitulos()
    {
        var pdf = CriarPdfComTexto("para-md.pdf");
        var resultado = await ConverterPdfAsync(pdf, new PdfToDocumentOptions { OutputFormat = "md" });

        var texto = await File.ReadAllTextAsync(resultado);
        Assert.StartsWith("# para-md", texto);
        Assert.Contains("## Página 1", texto);
    }

    [Fact]
    public async Task PdfComTexto_ViraHtmlComConteudoEscapado()
    {
        var pdf = CriarPdfComTexto("para-html.pdf", "Contrato <Cliente> & Cia");
        var resultado = await ConverterPdfAsync(pdf, new PdfToDocumentOptions { OutputFormat = "html" });

        var texto = await File.ReadAllTextAsync(resultado);
        Assert.Contains("<!doctype html>", texto);
        Assert.Contains("&lt;Cliente&gt; &amp; Cia", texto);
        Assert.DoesNotContain("<Cliente>", texto);
    }

    [Fact]
    public async Task PdfSemMarcaDePagina_NaoInsereOsTitulos()
    {
        var pdf = CriarPdfComTexto("sem-marcas.pdf");
        var resultado = await ConverterPdfAsync(
            pdf,
            new PdfToDocumentOptions { OutputFormat = "txt", IncludePageMarks = false });

        var texto = await File.ReadAllTextAsync(resultado);
        Assert.Contains("Smells Like Tech Converter", texto);
        Assert.DoesNotContain("Página 1", texto);
    }

    [Fact]
    public async Task ArquivoQueNaoEhPdf_FalhaComMensagemPropria()
    {
        var falso = Path.Combine(root, "falso.pdf");
        await File.WriteAllTextAsync(falso, "isto não é um PDF");

        var erro = await Assert.ThrowsAsync<ConversionException>(() =>
            ConverterPdfAsync(falso, new PdfToDocumentOptions()));

        Assert.Equal("invalid_pdf", erro.Code);
    }

    // ==================== PDF escaneado ====================

    [Fact]
    public async Task PdfEscaneado_SemOcr_ExplicaOQueFazer()
    {
        if (!OcrDisponivel)
        {
            return;
        }

        var pdf = CriarPdfEscaneado("escaneado-sem-ocr.pdf");
        var erro = await Assert.ThrowsAsync<ConversionException>(() =>
            ConverterPdfAsync(pdf, new PdfToDocumentOptions { OutputFormat = "txt", UseOcrWhenNeeded = false }));

        Assert.Equal("no_text_in_pdf", erro.Code);
        Assert.Contains("OCR", erro.Message, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public async Task PdfEscaneado_ComOcr_RecuperaOTexto()
    {
        if (!OcrDisponivel)
        {
            return;
        }

        var pdf = CriarPdfEscaneado("escaneado.pdf");
        var resultado = await ConverterPdfAsync(
            pdf,
            new PdfToDocumentOptions { OutputFormat = "txt", UseOcrWhenNeeded = true, OcrLanguage = "pt-BR" });

        var texto = await File.ReadAllTextAsync(resultado);
        Assert.Contains("CONVERTER", texto, StringComparison.OrdinalIgnoreCase);
    }

    // ==================== texto para narrar ====================

    [Fact]
    public async Task LeitorDeTexto_PdfComTexto_DevolveOTextoCorrido()
    {
        var pdf = CriarPdfComTexto("narrar.pdf");
        var leitor = new DocumentTextReader(new WindowsTextRecognizer());

        Assert.True(leitor.CanRead(MediaKind.Pdf, "pdf"));
        var texto = await leitor.ReadAsync(pdf, MediaKind.Pdf, null, CancellationToken.None);

        Assert.Contains("Smells Like Tech Converter", texto);
        Assert.Contains("segunda pagina", texto, StringComparison.OrdinalIgnoreCase);
        Assert.DoesNotContain("Página 1", texto);
    }

    [Fact]
    public async Task LeitorDeTexto_PdfEscaneado_PassaPeloOcr()
    {
        if (!OcrDisponivel)
        {
            return;
        }

        var pdf = CriarPdfEscaneado("narrar-escaneado.pdf");
        var texto = await new DocumentTextReader(new WindowsTextRecognizer()).ReadAsync(pdf, MediaKind.Pdf, null, CancellationToken.None);
        Assert.Contains("CONVERTER", texto, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public async Task LeitorDeTexto_Imagem_PassaPeloOcr()
    {
        if (!OcrDisponivel)
        {
            return;
        }

        var imagem = CriarImagemComTexto("narrar.png", "SMELLS LIKE TECH", "CONVERTER LOCAL");
        var texto = await new DocumentTextReader(new WindowsTextRecognizer()).ReadAsync(imagem, MediaKind.Image, null, CancellationToken.None);
        Assert.Contains("CONVERTER", texto, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public async Task LeitorDeTexto_Docx_LeOsParagrafos()
    {
        var pdf = CriarPdfComTexto("para-docx.pdf");
        var docx = await ConverterPdfAsync(pdf, new PdfToDocumentOptions { OutputFormat = "docx" });

        var texto = DocumentTextReader.ReadDocx(docx);
        Assert.Contains("Smells Like Tech Converter", texto);
    }

    [Fact]
    public void LeitorDeTexto_HtmlEMarkdown_ViramTextoLimpo()
    {
        var html = DocumentTextReader.FromHtml("<html><style>p{}</style><body><h1>Título</h1><p>Olá &amp; bem-vindo</p><script>x()</script></body></html>");
        Assert.Equal("Título\n\nOlá & bem-vindo", html);

        var markdown = DocumentTextReader.FromMarkdown("# Título\n\nUm [link](https://x.y) e **negrito**.\n- item");
        Assert.Equal("Título\n\nUm link e negrito. item", markdown);

        Assert.Equal("Uma frase quebrada no meio.\n\nOutro parágrafo.", DocumentTextReader.JoinLines("Uma frase que-\nbrada no\nmeio.\n\nOutro parágrafo."));
        Assert.False(new DocumentTextReader(new WindowsTextRecognizer()).CanRead(MediaKind.Document, "xlsx"));
    }

    // ==================== OCR de imagem ====================

    [Fact]
    public async Task ImagemComTexto_ViraTxt()
    {
        if (!OcrDisponivel)
        {
            return;
        }

        var imagem = CriarImagemComTexto("aviso.png", "SMELLS LIKE TECH", "CONVERTER LOCAL");
        var resultado = await ReconhecerAsync(imagem, new OcrOptions { OutputFormat = "txt", Language = "pt-BR" });

        var texto = (await File.ReadAllTextAsync(resultado)).ToUpperInvariant();
        Assert.Contains("SMELLS", texto);
        Assert.Contains("CONVERTER", texto);
    }

    [Fact]
    public async Task ImagemSemTexto_FalhaComMensagemPropria()
    {
        if (!OcrDisponivel)
        {
            return;
        }

        var vazia = Path.Combine(root, "vazia.png");
        using (var imagem = new MagickImage(MagickColors.White, 600, 400))
        {
            imagem.Write(vazia);
        }

        var erro = await Assert.ThrowsAsync<ConversionException>(() =>
            ReconhecerAsync(vazia, new OcrOptions()));

        Assert.Equal("no_text_found", erro.Code);
    }

    [Fact]
    public void IdiomaDeOcr_ForaDaListaEhRecusado()
    {
        var erro = Assert.Throws<ConversionException>(() => new OcrOptions { Language = "klingon" }.Validate());
        Assert.Equal("unsupported_language", erro.Code);
    }

    // ==================== PDF em imagens ====================

    [Fact]
    public async Task PdfEmImagens_GeraUmArquivoPorPagina()
    {
        var pdf = CriarPdfComTexto("paginas.pdf");
        var job = new ConversionJob
        {
            Options = new PdfToImageOptions { OutputFormat = "png", Dpi = 96 },
            InputPath = pdf,
            OutputDirectory = Path.Combine(root, "saida")
        };

        using var workspace = JobWorkspace.Create(Path.Combine(root, "temp"), Guid.NewGuid());
        var resultado = await new PdfImageEngine(new SilentLog()).ExecuteAsync(
            new EngineContext(job, workspace, new IgnoreProgress()),
            CancellationToken.None);

        Assert.Equal(2, resultado.OutputFiles.Count);
        foreach (var arquivo in resultado.OutputFiles)
        {
            var info = new MagickImageInfo(arquivo);
            Assert.True(info.Width > 100);
            Assert.Equal(MagickFormat.Png, info.Format);
        }
    }

    // ==================== catálogo ====================

    [Fact]
    public void Catalogo_OfereceAsNovasOperacoesParaPdfEImagem()
    {
        var pdf = new MediaInfo
        {
            Path = @"C:\temp\contrato.pdf",
            FileName = "contrato.pdf",
            Extension = "pdf",
            Kind = MediaKind.Pdf,
            SizeBytes = 1024
        };

        var operacoesPdf = OperationCatalog.For(pdf).Where(item => item.Available).Select(item => item.Id).ToArray();
        Assert.Contains(OperationIds.PdfToDocument, operacoesPdf);
        Assert.Contains(OperationIds.PdfToImage, operacoesPdf);

        var imagem = pdf with { Extension = "png", Kind = MediaKind.Image, FileName = "foto.png" };
        var operacoesImagem = OperationCatalog.For(imagem).Where(item => item.Available).Select(item => item.Id).ToArray();
        Assert.Contains(OperationIds.OcrImageToText, operacoesImagem);
        Assert.Contains(OperationIds.ImageConvert, operacoesImagem);
    }

    [Fact]
    public void OpcoesDePdf_SaoSerializaveis()
    {
        JobOptions original = new PdfToDocumentOptions { OutputFormat = "md", UseOcrWhenNeeded = false };
        var restaurado = JobOptionsJson.Deserialize(JobOptionsJson.Serialize(original));

        var pdf = Assert.IsType<PdfToDocumentOptions>(restaurado);
        Assert.Equal("md", pdf.OutputFormat);
        Assert.False(pdf.UseOcrWhenNeeded);
    }

    // ==================== apoio ====================

    private Task<string> ConverterPdfAsync(string pdfPath, PdfToDocumentOptions options) =>
        ExecutarAsync(new PdfDocumentEngine(new WindowsTextRecognizer(), new SilentLog()), pdfPath, options);

    private Task<string> ReconhecerAsync(string imagePath, OcrOptions options) =>
        ExecutarAsync(new OcrImageEngine(new WindowsTextRecognizer(), new SilentLog()), imagePath, options);

    /// <summary>
    /// Roda o motor e leva a saída para fora do workspace, como a fila faz: a pasta do
    /// job é apagada ao final e o arquivo produzido não pode sumir junto.
    /// </summary>
    private async Task<string> ExecutarAsync(IConversionEngine engine, string inputPath, JobOptions options)
    {
        var destino = Path.Combine(root, "saida");
        Directory.CreateDirectory(destino);

        var job = new ConversionJob
        {
            Options = options,
            InputPath = inputPath,
            OutputDirectory = destino
        };

        using var workspace = JobWorkspace.Create(Path.Combine(root, "temp"), Guid.NewGuid());
        var resultado = await engine.ExecuteAsync(
            new EngineContext(job, workspace, new IgnoreProgress()),
            CancellationToken.None);

        var produzido = resultado.OutputFiles[0];
        var final = Path.Combine(destino, $"{Guid.NewGuid():N}{Path.GetExtension(produzido)}");
        File.Move(produzido, final);
        return final;
    }

    /// <summary>PDF de duas paginas com camada de texto de verdade.</summary>
    private string CriarPdfComTexto(string nome, string? primeiraLinha = null)
    {
        var caminho = Path.Combine(root, nome);
        var builder = new PdfDocumentBuilder();
        var fonte = builder.AddStandard14Font(Standard14Font.Helvetica);

        var pagina1 = builder.AddPage(PageSize.A4);
        pagina1.AddText(primeiraLinha ?? Frase, 12, new UglyToad.PdfPig.Core.PdfPoint(50, 700), fonte);

        var pagina2 = builder.AddPage(PageSize.A4);
        pagina2.AddText(SegundaPagina, 12, new UglyToad.PdfPig.Core.PdfPoint(50, 700), fonte);

        File.WriteAllBytes(caminho, builder.Build());
        return caminho;
    }

    /// <summary>
    /// PDF sem camada de texto: a pagina e uma imagem, como sai de um escaner.
    /// </summary>
    private string CriarPdfEscaneado(string nome)
    {
        var imagem = CriarImagemComTexto("pagina-escaneada.jpg", "DOCUMENTO", "CONVERTER");
        var caminho = Path.Combine(root, nome);

        var builder = new PdfDocumentBuilder();
        var pagina = builder.AddPage(PageSize.A4);
        pagina.AddJpeg(
            File.ReadAllBytes(imagem),
            new UglyToad.PdfPig.Core.PdfRectangle(40, 380, 560, 800));

        File.WriteAllBytes(caminho, builder.Build());
        return caminho;
    }

    /// <summary>Imagem com texto desenhado, para o reconhecimento ter o que ler.</summary>
    private string CriarImagemComTexto(string nome, params string[] linhas)
    {
        var caminho = Path.Combine(root, nome);
        using var imagem = new MagickImage(MagickColors.White, 1000, 500);

        var desenho = new Drawables()
            .FillColor(MagickColors.Black)
            .Font("Arial")
            .FontPointSize(64);

        var y = 150;
        foreach (var linha in linhas)
        {
            desenho.Text(60, y, linha);
            y += 110;
        }

        desenho.Draw(imagem);
        imagem.Write(caminho);
        return caminho;
    }

    public void Dispose()
    {
        try
        {
            if (Directory.Exists(root))
            {
                Directory.Delete(root, recursive: true);
            }
        }
        catch (IOException)
        {
            // Arquivo ainda bloqueado; irrelevante para o teste.
        }

        GC.SuppressFinalize(this);
    }

    private sealed class IgnoreProgress : IProgress<JobProgress>
    {
        public void Report(JobProgress value)
        {
        }
    }

    private sealed class SilentLog : IConverterLog
    {
        public void Write(LogChannel channel, string message)
        {
        }

        public void Error(string code, string message, Exception? exception = null)
        {
        }
    }
}
