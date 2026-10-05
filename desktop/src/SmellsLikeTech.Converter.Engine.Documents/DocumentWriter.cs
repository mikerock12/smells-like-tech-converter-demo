using System.Net;
using System.Text;
using DocumentFormat.OpenXml;
using DocumentFormat.OpenXml.Packaging;
using DocumentFormat.OpenXml.Wordprocessing;
using SmellsLikeTech.Converter.Core;

namespace SmellsLikeTech.Converter.Engine.Documents;

/// <summary>Um bloco de conteudo do documento produzido.</summary>
public sealed record DocumentSection(string Title, string Text);

/// <summary>
/// Escreve o texto extraido no formato pedido. O DOCX sai como Office Open XML de
/// verdade, sem depender do Word nem do LibreOffice instalados.
/// </summary>
public static class DocumentWriter
{
    public static async Task WriteAsync(
        string outputPath,
        string format,
        string documentTitle,
        IReadOnlyList<DocumentSection> sections,
        bool includeSectionTitles,
        CancellationToken cancellationToken)
    {
        switch (format)
        {
            case "txt":
                await File.WriteAllTextAsync(outputPath, PlainText(sections, includeSectionTitles), Utf8, cancellationToken);
                break;
            case "md":
                await File.WriteAllTextAsync(outputPath, Markdown(documentTitle, sections, includeSectionTitles), Utf8, cancellationToken);
                break;
            case "html":
                await File.WriteAllTextAsync(outputPath, Html(documentTitle, sections, includeSectionTitles), Utf8, cancellationToken);
                break;
            case "docx":
                WriteDocx(outputPath, documentTitle, sections, includeSectionTitles);
                break;
            default:
                throw new ConversionException("unsupported_output_format", $"Formato de documento não suportado: '{format}'.");
        }
    }

    private static UTF8Encoding Utf8 => new(encoderShouldEmitUTF8Identifier: false);

    private static string PlainText(IReadOnlyList<DocumentSection> sections, bool includeTitles)
    {
        var builder = new StringBuilder();
        foreach (var section in sections)
        {
            if (includeTitles)
            {
                builder.AppendLine($"--- {section.Title} ---");
            }

            builder.AppendLine(section.Text.Trim());
            builder.AppendLine();
        }

        return builder.ToString().TrimEnd() + Environment.NewLine;
    }

    private static string Markdown(string title, IReadOnlyList<DocumentSection> sections, bool includeTitles)
    {
        var builder = new StringBuilder();
        builder.AppendLine($"# {title}");
        builder.AppendLine();

        foreach (var section in sections)
        {
            if (includeTitles)
            {
                builder.AppendLine($"## {section.Title}");
                builder.AppendLine();
            }

            foreach (var paragraph in Paragraphs(section.Text))
            {
                builder.AppendLine(paragraph);
                builder.AppendLine();
            }
        }

        return builder.ToString().TrimEnd() + Environment.NewLine;
    }

    private static string Html(string title, IReadOnlyList<DocumentSection> sections, bool includeTitles)
    {
        var builder = new StringBuilder();
        builder.AppendLine("<!doctype html>");
        builder.AppendLine("<html lang=\"pt-BR\">");
        builder.AppendLine("<head>");
        builder.AppendLine("<meta charset=\"utf-8\">");
        builder.AppendLine($"<title>{WebUtility.HtmlEncode(title)}</title>");
        builder.AppendLine("</head>");
        builder.AppendLine("<body>");
        builder.AppendLine($"<h1>{WebUtility.HtmlEncode(title)}</h1>");

        foreach (var section in sections)
        {
            if (includeTitles)
            {
                builder.AppendLine($"<h2>{WebUtility.HtmlEncode(section.Title)}</h2>");
            }

            foreach (var paragraph in Paragraphs(section.Text))
            {
                builder.AppendLine($"<p>{WebUtility.HtmlEncode(paragraph)}</p>");
            }
        }

        builder.AppendLine("</body>");
        builder.AppendLine("</html>");
        return builder.ToString();
    }

    private static void WriteDocx(
        string outputPath,
        string title,
        IReadOnlyList<DocumentSection> sections,
        bool includeTitles)
    {
        using var package = WordprocessingDocument.Create(outputPath, WordprocessingDocumentType.Document);
        var mainPart = package.AddMainDocumentPart();
        mainPart.Document = new Document();
        var body = mainPart.Document.AppendChild(new Body());

        body.AppendChild(Heading(title, "Title"));

        foreach (var section in sections)
        {
            if (includeTitles)
            {
                body.AppendChild(Heading(section.Title, "Heading1"));
            }

            foreach (var paragraph in Paragraphs(section.Text))
            {
                body.AppendChild(TextParagraph(paragraph));
            }
        }

        mainPart.Document.Save();
    }

    private static Paragraph Heading(string text, string style)
    {
        var paragraph = new Paragraph(
            new ParagraphProperties(new ParagraphStyleId { Val = style }),
            new Run(new Text(text) { Space = SpaceProcessingModeValues.Preserve }));
        return paragraph;
    }

    /// <summary>Cada quebra de linha vira uma quebra de verdade dentro do paragrafo.</summary>
    private static Paragraph TextParagraph(string text)
    {
        var run = new Run();
        var lines = text.Split('\n');

        for (var index = 0; index < lines.Length; index++)
        {
            if (index > 0)
            {
                run.AppendChild(new Break());
            }

            run.AppendChild(new Text(lines[index].TrimEnd('\r')) { Space = SpaceProcessingModeValues.Preserve });
        }

        return new Paragraph(run);
    }

    /// <summary>Separa em paragrafos por linha em branco, preservando as quebras internas.</summary>
    private static IEnumerable<string> Paragraphs(string text)
    {
        var normalized = text.Replace("\r\n", "\n", StringComparison.Ordinal).Trim();
        if (normalized.Length == 0)
        {
            yield break;
        }

        foreach (var block in normalized.Split("\n\n", StringSplitOptions.RemoveEmptyEntries))
        {
            var trimmed = block.Trim();
            if (trimmed.Length > 0)
            {
                yield return trimmed;
            }
        }
    }
}
