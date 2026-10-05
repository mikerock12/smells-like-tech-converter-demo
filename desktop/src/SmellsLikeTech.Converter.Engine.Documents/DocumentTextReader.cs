using System.Net;
using System.Runtime.Versioning;
using System.Text;
using System.Text.RegularExpressions;
using DocumentFormat.OpenXml.Packaging;
using DocumentFormat.OpenXml.Wordprocessing;
using SkiaSharp;
using SmellsLikeTech.Converter.Core;
using SmellsLikeTech.Converter.Core.Abstractions;
using SmellsLikeTech.Converter.Core.Media;
using SmellsLikeTech.Converter.Core.Options;

namespace SmellsLikeTech.Converter.Engine.Documents;

/// <summary>
/// O texto de PDF, imagem e documento para a narracao. PDF: a camada de texto de cada
/// pagina e, na pagina escaneada, o reconhecimento de texto do Windows. Imagem: o OCR.
/// Documento: DOCX, Markdown e HTML. O texto nunca vai para o log.
/// </summary>
[SupportedOSPlatform("windows10.0.19041.0")]
public sealed partial class DocumentTextReader(WindowsTextRecognizer recognizer, string ocrLanguage = "pt-BR") : IDocumentTextReader
{
    private const int OcrDpi = 300;

    private static readonly HashSet<string> Documentos = new(StringComparer.OrdinalIgnoreCase) { "docx", "md", "markdown", "html", "htm" };

    public bool CanRead(MediaKind kind, string extension) => kind switch
    {
        MediaKind.Pdf or MediaKind.Image => true,
        MediaKind.Document => Documentos.Contains(extension.TrimStart('.')),
        _ => false
    };

    public async Task<string> ReadAsync(string path, MediaKind kind, IProgress<double>? progress, CancellationToken cancellationToken)
    {
        var extension = Path.GetExtension(path).TrimStart('.').ToLowerInvariant();
        return kind switch
        {
            MediaKind.Pdf => await ReadPdfAsync(path, progress, cancellationToken),
            MediaKind.Image => await recognizer.ReadFileAsync(path, ocrLanguage, keepLineBreaks: false, cancellationToken),
            MediaKind.Document when extension == "docx" => ReadDocx(path),
            MediaKind.Document when extension is "html" or "htm" => FromHtml(await File.ReadAllTextAsync(path, cancellationToken)),
            MediaKind.Document when extension is "md" or "markdown" => FromMarkdown(await File.ReadAllTextAsync(path, cancellationToken)),
            _ => throw new ConversionException(
                "unsupported_document",
                $"A narração ainda não lê arquivos .{extension}. Salve como PDF ou DOCX e tente de novo.")
        };
    }

    private async Task<string> ReadPdfAsync(string path, IProgress<double>? progress, CancellationToken cancellationToken)
    {
        var pages = PdfReader.ExtractPages(path, PdfToDocumentOptions.MaxPages, cancellationToken);
        var semTexto = pages.Where(page => !page.HasUsableText).ToList();
        byte[]? pdf = semTexto.Count > 0 ? await File.ReadAllBytesAsync(path, cancellationToken) : null;

        var textos = new List<string>(pages.Count);
        for (var indice = 0; indice < pages.Count; indice++)
        {
            cancellationToken.ThrowIfCancellationRequested();
            var page = pages[indice];
            var texto = page.Text;
            if (!page.HasUsableText && pdf is not null)
            {
                // Pagina escaneada: desenha e passa pelo OCR do Windows.
                var imagem = PdfRasterizer.RenderPage(pdf, page.Number - 1, OcrDpi, SKEncodedImageFormat.Png, 100);
                texto = await recognizer.ReadAsync(imagem, ocrLanguage, keepLineBreaks: false, cancellationToken);
            }

            textos.Add(JoinLines(texto));
            progress?.Report((indice + 1.0) / pages.Count);
        }

        return string.Join("\n\n", textos.Where(texto => texto.Length > 0));
    }

    /// <summary>Linhas quebradas pelo layout da pagina viram frases corridas; paragrafos ficam.</summary>
    internal static string JoinLines(string texto)
    {
        var paragrafos = ParagraphBreak().Split(texto.Replace("\r\n", "\n").Trim());
        return string.Join(
            "\n\n",
            paragrafos
                .Select(paragrafo => Hyphenation().Replace(paragrafo, "$1$2"))
                .Select(paragrafo => Whitespace().Replace(paragrafo, " ").Trim())
                .Where(paragrafo => paragrafo.Length > 0));
    }

    internal static string ReadDocx(string path)
    {
        try
        {
            using var documento = WordprocessingDocument.Open(path, false);
            var corpo = documento.MainDocumentPart?.Document?.Body;
            if (corpo is null)
            {
                return "";
            }

            var builder = new StringBuilder();
            foreach (var paragrafo in corpo.Descendants<Paragraph>())
            {
                var texto = string.Concat(paragrafo.Descendants<Text>().Select(item => item.Text)).Trim();
                if (texto.Length > 0)
                {
                    builder.Append(texto).Append("\n\n");
                }
            }

            return builder.ToString().Trim();
        }
        catch (Exception exception) when (exception is OpenXmlPackageException or InvalidDataException or IOException)
        {
            throw new ConversionException("invalid_document", "O arquivo não pôde ser lido como documento do Word.", exception);
        }
    }

    internal static string FromHtml(string html)
    {
        var semCodigo = ScriptOrStyle().Replace(html, " ");
        var comQuebras = BlockEnd().Replace(semCodigo, "\n\n");
        var semTags = Tag().Replace(comQuebras, " ");
        return JoinLines(WebUtility.HtmlDecode(semTags));
    }

    internal static string FromMarkdown(string markdown)
    {
        var linhas = markdown.Replace("\r\n", "\n").Split('\n').Select(linha =>
        {
            var limpa = MarkdownPrefix().Replace(linha, "");
            limpa = MarkdownLink().Replace(limpa, "$1");
            return MarkdownEmphasis().Replace(limpa, "");
        });
        return JoinLines(string.Join("\n", linhas));
    }

    [GeneratedRegex(@"\n\s*\n")]
    private static partial Regex ParagraphBreak();

    [GeneratedRegex(@"(\w)-\n(\w)")]
    private static partial Regex Hyphenation();

    [GeneratedRegex(@"\s+")]
    private static partial Regex Whitespace();

    [GeneratedRegex(@"<(script|style)[^>]*>.*?</\1>", RegexOptions.Singleline | RegexOptions.IgnoreCase)]
    private static partial Regex ScriptOrStyle();

    [GeneratedRegex(@"</(p|div|h[1-6]|li|tr|section|article)>|<br\s*/?>", RegexOptions.IgnoreCase)]
    private static partial Regex BlockEnd();

    [GeneratedRegex(@"<[^>]+>")]
    private static partial Regex Tag();

    [GeneratedRegex(@"^\s*(#{1,6}\s+|[-*+]\s+|\d+[.)]\s+|>\s*)")]
    private static partial Regex MarkdownPrefix();

    [GeneratedRegex(@"!?\[([^\]]*)\]\([^)]*\)")]
    private static partial Regex MarkdownLink();

    [GeneratedRegex(@"[*_`~]{1,3}")]
    private static partial Regex MarkdownEmphasis();
}
