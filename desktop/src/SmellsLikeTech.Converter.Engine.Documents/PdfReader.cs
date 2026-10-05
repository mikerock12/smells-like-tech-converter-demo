using System.Text;
using SmellsLikeTech.Converter.Core;
using UglyToad.PdfPig;
using UglyToad.PdfPig.Core;
using UglyToad.PdfPig.DocumentLayoutAnalysis.WordExtractor;

namespace SmellsLikeTech.Converter.Engine.Documents;

/// <summary>Texto de uma pagina do PDF.</summary>
public sealed record PdfPage(int Number, string Text)
{
    /// <summary>Uma pagina so de imagem devolve pouco ou nenhum caractere util.</summary>
    public bool HasUsableText => Text.Count(char.IsLetterOrDigit) >= 12;
}

/// <summary>
/// Leitura de PDF em .NET puro (PdfPig): nao depende de LibreOffice, Ghostscript nem
/// qualquer ferramenta instalada no computador.
/// </summary>
public static partial class PdfReader
{
    /// <summary>Espacos repetidos viram um so, sem tocar nas quebras de linha.</summary>
    [System.Text.RegularExpressions.GeneratedRegex(@"[^\S\r\n]{2,}")]
    private static partial System.Text.RegularExpressions.Regex CollapseSpaces();

    public static IReadOnlyList<PdfPage> ExtractPages(string pdfPath, int maxPages, CancellationToken cancellationToken)
    {
        try
        {
            using var document = PdfDocument.Open(pdfPath);
            var pages = new List<PdfPage>(Math.Min(document.NumberOfPages, maxPages));

            for (var number = 1; number <= document.NumberOfPages && number <= maxPages; number++)
            {
                cancellationToken.ThrowIfCancellationRequested();
                pages.Add(new PdfPage(number, ReadPage(document, number)));
            }

            return pages;
        }
        catch (OperationCanceledException)
        {
            throw;
        }
        catch (Exception exception) when (exception is PdfDocumentFormatException or ArgumentException or IndexOutOfRangeException)
        {
            throw new ConversionException("invalid_pdf", "O arquivo não pôde ser lido como PDF válido.", exception);
        }
    }

    public static int CountPages(string pdfPath)
    {
        try
        {
            using var document = PdfDocument.Open(pdfPath);
            return document.NumberOfPages;
        }
        catch (Exception exception) when (exception is PdfDocumentFormatException or ArgumentException)
        {
            throw new ConversionException("invalid_pdf", "O arquivo não pôde ser lido como PDF válido.", exception);
        }
    }

    /// <summary>
    /// Reagrupa as palavras em linhas usando a posicao delas na pagina. O texto cru do
    /// PDF costuma vir fora de ordem e sem quebras.
    /// </summary>
    private static string ReadPage(PdfDocument document, int number)
    {
        var page = document.GetPage(number);
        var words = NearestNeighbourWordExtractor.Instance.GetWords(page.Letters).ToList();

        if (words.Count == 0)
        {
            return page.Text.Trim();
        }

        var builder = new StringBuilder();
        double? baseline = null;

        // Palavras na ordem de leitura: de cima para baixo, da esquerda para a direita.
        foreach (var word in words
                     .OrderByDescending(word => Math.Round(word.BoundingBox.Bottom, 1))
                     .ThenBy(word => word.BoundingBox.Left))
        {
            // O extrator devolve os proprios espacos como palavras; o espacamento
            // entre elas e reconstruido aqui.
            if (string.IsNullOrWhiteSpace(word.Text))
            {
                continue;
            }

            var currentBaseline = Math.Round(word.BoundingBox.Bottom, 1);
            if (baseline is null)
            {
                baseline = currentBaseline;
            }
            else if (Math.Abs(baseline.Value - currentBaseline) > 2)
            {
                builder.AppendLine();
                baseline = currentBaseline;
            }
            else
            {
                builder.Append(' ');
            }

            builder.Append(word.Text.Trim());
        }

        return CollapseSpaces().Replace(builder.ToString(), " ").Trim();
    }
}
