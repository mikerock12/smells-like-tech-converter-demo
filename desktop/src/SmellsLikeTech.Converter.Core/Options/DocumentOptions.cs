using SmellsLikeTech.Converter.Core.Media;
using SmellsLikeTech.Converter.Core.Operations;

namespace SmellsLikeTech.Converter.Core.Options;

/// <summary>Idiomas aceitos no reconhecimento de texto.</summary>
public static class OcrLanguages
{
    public static IReadOnlyDictionary<string, string> All { get; } = new Dictionary<string, string>(StringComparer.Ordinal)
    {
        ["pt-BR"] = "Português (Brasil)",
        ["pt"] = "Português",
        ["en-US"] = "Inglês (Estados Unidos)",
        ["es"] = "Espanhol",
        ["fr"] = "Francês",
        ["de"] = "Alemão",
        ["it"] = "Italiano"
    };

    public static void Validate(string language)
    {
        if (!All.ContainsKey(language))
        {
            throw new ConversionException("unsupported_language", "Idioma de reconhecimento fora da lista permitida.");
        }
    }
}

/// <summary>PDF para documento de texto: TXT, Markdown, DOCX ou HTML.</summary>
public sealed record PdfToDocumentOptions : JobOptions
{
    public override string Operation => OperationIds.PdfToDocument;
    public override string OutputExtension => OutputFormat;

    public string OutputFormat { get; init; } = "docx";

    /// <summary>
    /// Quando o PDF nao tem camada de texto (foi escaneado), reconhecer as paginas
    /// por imagem em vez de devolver arquivo vazio.
    /// </summary>
    public bool UseOcrWhenNeeded { get; init; } = true;

    public string OcrLanguage { get; init; } = "pt-BR";

    /// <summary>Marcar onde comeca cada pagina no texto produzido.</summary>
    public bool IncludePageMarks { get; init; } = true;

    public static IReadOnlyList<string> OutputFormats { get; } = ["docx", "txt", "md", "html"];

    public const int MaxPages = 2_000;

    public override void Validate()
    {
        if (!OutputFormats.Contains(OutputFormat))
        {
            throw new ConversionException(
                "unsupported_output_format",
                "Saída do PDF deve ser DOCX, TXT, MD ou HTML.");
        }

        OcrLanguages.Validate(OcrLanguage);
    }

    public override string Summary()
    {
        var parts = new List<string> { OutputFormat.ToUpperInvariant() };
        if (UseOcrWhenNeeded)
        {
            parts.Add("OCR quando necessário");
        }

        return string.Join(" · ", parts);
    }
}

/// <summary>Reconhecimento de texto em imagem.</summary>
public sealed record OcrOptions : JobOptions
{
    public override string Operation => OperationIds.OcrImageToText;
    public override string OutputExtension => OutputFormat;

    public string OutputFormat { get; init; } = "txt";

    public string Language { get; init; } = "pt-BR";

    /// <summary>Preservar as quebras de linha vistas na imagem.</summary>
    public bool KeepLineBreaks { get; init; } = true;

    public static IReadOnlyList<string> OutputFormats { get; } = ["txt", "md"];

    public override void Validate()
    {
        if (!OutputFormats.Contains(OutputFormat))
        {
            throw new ConversionException("unsupported_output_format", "Saída do OCR deve ser TXT ou MD.");
        }

        OcrLanguages.Validate(Language);
    }

    public override string Summary()
    {
        var idioma = OcrLanguages.All.TryGetValue(Language, out var nome) ? nome : Language;
        return $"{OutputFormat.ToUpperInvariant()} · {idioma}";
    }
}

/// <summary>PDF para imagens, uma por pagina.</summary>
public sealed record PdfToImageOptions : JobOptions
{
    public override string Operation => OperationIds.PdfToImage;
    public override string OutputExtension => FormatCatalog.FileExtension(OutputFormat);

    public string OutputFormat { get; init; } = "png";

    /// <summary>Resolucao do rasterizador.</summary>
    public int Dpi { get; init; } = 150;

    public int MaxPages { get; init; } = 200;

    public static IReadOnlyList<string> OutputFormats { get; } = ["png", "jpg", "webp"];

    public static IReadOnlyList<int> DpiOptions { get; } = [72, 96, 150, 200, 300, 600];

    public override void Validate()
    {
        if (!OutputFormats.Contains(OutputFormat))
        {
            throw new ConversionException("unsupported_output_format", "Páginas devem sair em PNG, JPG ou WEBP.");
        }

        if (!DpiOptions.Contains(Dpi))
        {
            throw new ConversionException("invalid_resolution", "DPI fora da lista permitida.");
        }

        if (MaxPages is < 1 or > PdfToDocumentOptions.MaxPages)
        {
            throw new ConversionException("invalid_page_limit", "Limite de páginas inválido.");
        }
    }

    public override string Summary() => $"{OutputFormat.ToUpperInvariant()} · {Dpi} DPI";
}

/// <summary>Varios PDFs viram um so. Os demais arquivos vem em <see cref="Jobs.ConversionJob.ExtraInputPaths"/>.</summary>
public sealed record PdfMergeOptions : JobOptions
{
    public override string Operation => OperationIds.PdfMerge;
    public override string OutputExtension => "pdf";

    public const int MaxFiles = 200;

    public override void Validate()
    {
        // Nada a validar nas opcoes em si: a lista de arquivos e conferida pelo engine.
    }

    public override string Summary() => "PDF · junção";
}

/// <summary>Extrair paginas ("1-3, 7") ou separar em um arquivo por pagina ou por bloco.</summary>
public sealed record PdfSplitOptions : JobOptions
{
    public override string Operation => OperationIds.PdfSplit;
    public override string OutputExtension => "pdf";

    /// <summary>"pages", "each" ou "blocks".</summary>
    public string Mode { get; init; } = "pages";

    /// <summary>Faixas de paginas, no modo "pages". Ex.: "1-3, 7, 10-12".</summary>
    public string Pages { get; init; } = "1";

    /// <summary>Paginas por arquivo, no modo "blocks".</summary>
    public int PagesPerBlock { get; init; } = 10;

    public static IReadOnlyList<string> Modes { get; } = ["pages", "each", "blocks"];

    public override void Validate()
    {
        if (!Modes.Contains(Mode))
        {
            throw new ConversionException("invalid_mode", "Modo de divisão inválido.");
        }

        if (Mode == "pages" && !PageRanges.IsValid(Pages))
        {
            throw new ConversionException("invalid_pages", "Informe as páginas como \"1-3, 7, 10-12\".");
        }

        if (PagesPerBlock is < 1 or > 1000)
        {
            throw new ConversionException("invalid_block", "Páginas por bloco deve ficar entre 1 e 1000.");
        }
    }

    public override string Summary() => Mode switch
    {
        "each" => "PDF · uma página por arquivo",
        "blocks" => $"PDF · blocos de {PagesPerBlock}",
        _ => $"PDF · páginas {Pages}"
    };
}

/// <summary>Imagens viram um PDF, uma por pagina. Os demais arquivos vem em ExtraInputPaths.</summary>
public sealed record ImageToPdfOptions : JobOptions
{
    public override string Operation => OperationIds.ImageToPdf;
    public override string OutputExtension => "pdf";

    /// <summary>"image" (do tamanho da imagem), "a4-portrait" ou "a4-landscape".</summary>
    public string PageSize { get; init; } = "a4-portrait";

    /// <summary>Margem em milimetros, quando a pagina e A4.</summary>
    public int MarginMm { get; init; } = 10;

    /// <summary>Qualidade JPEG das fotos embutidas, 30 a 100.</summary>
    public int Quality { get; init; } = 85;

    public static IReadOnlyList<string> PageSizes { get; } = ["image", "a4-portrait", "a4-landscape"];

    public override void Validate()
    {
        if (!PageSizes.Contains(PageSize))
        {
            throw new ConversionException("invalid_page_size", "Tamanho de página inválido.");
        }

        if (MarginMm is < 0 or > 50)
        {
            throw new ConversionException("invalid_margin", "Margem deve ficar entre 0 e 50 mm.");
        }

        if (Quality is < 30 or > 100)
        {
            throw new ConversionException("invalid_quality", "Qualidade deve ficar entre 30 e 100.");
        }
    }

    public override string Summary() => PageSize == "image" ? "PDF · página do tamanho da imagem" : $"PDF · A4 · margem {MarginMm} mm";
}

/// <summary>Leitura de faixas de paginas: "1-3, 7, 10-12". Nunca aceita nada fora de digitos, virgulas e hifens.</summary>
public static class PageRanges
{
    public static bool IsValid(string text) =>
        !string.IsNullOrWhiteSpace(text)
        && text.All(character => char.IsAsciiDigit(character) || character is ',' or '-' or ' ' or ';');

    /// <summary>Numeros de pagina (a partir de 1), limitados ao total, em ordem e sem repeticao.</summary>
    public static IReadOnlyList<int> Parse(string text, int total)
    {
        var pages = new SortedSet<int>();
        if (string.IsNullOrWhiteSpace(text))
        {
            for (var page = 1; page <= total; page++)
            {
                pages.Add(page);
            }

            return [.. pages];
        }

        foreach (var chunk in text.Split([',', ';'], StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries))
        {
            var bounds = chunk.Split('-', 2, StringSplitOptions.TrimEntries);
            if (bounds.Length == 2 && int.TryParse(bounds[0], out var from) && int.TryParse(bounds[1], out var to))
            {
                for (var page = Math.Max(1, from); page <= Math.Min(total, to); page++)
                {
                    pages.Add(page);
                }
            }
            else if (int.TryParse(chunk, out var single) && single >= 1 && single <= total)
            {
                pages.Add(single);
            }
        }

        return [.. pages];
    }
}
