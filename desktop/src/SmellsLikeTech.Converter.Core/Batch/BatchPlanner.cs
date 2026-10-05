using SmellsLikeTech.Converter.Core.Media;
using SmellsLikeTech.Converter.Core.Operations;

namespace SmellsLikeTech.Converter.Core.Batch;

/// <summary>
/// Um conjunto de arquivos que aceita exatamente as mesmas conversoes, e por isso pode
/// ser perguntado de uma vez so: "todos os MP3 para MP3 normalizando o volume".
/// </summary>
public sealed record BatchGroup
{
    public required MediaKind Kind { get; init; }
    public required IReadOnlyList<MediaInfo> Files { get; init; }
    public required IReadOnlyList<OperationDescriptor> Operations { get; init; }

    public int Count => Files.Count;

    public long TotalBytes => Files.Sum(file => file.SizeBytes);

    /// <summary>Extensoes distintas presentes no grupo, em maiusculas e ordenadas.</summary>
    public IReadOnlyList<string> Extensions =>
        [.. Files.Select(file => file.Extension.ToUpperInvariant()).Distinct(StringComparer.Ordinal).Order(StringComparer.Ordinal)];

    /// <summary>Primeira operacao disponivel, ou nula quando nenhum motor atende o grupo.</summary>
    public OperationDescriptor? DefaultOperation => Operations.FirstOrDefault(operation => operation.Available);

    public bool HasWork => DefaultOperation is not null;

    /// <summary>"5 áudios", "1 vídeo", "3 PDFs".</summary>
    public string Title => $"{Count} {NameFor(Kind, Count)}";

    /// <summary>"MP3, WAV · 42,3 MB", com a ressalva do grupo quando houver uma.</summary>
    public string Detail
    {
        get
        {
            var parts = new List<string>
            {
                string.Join(", ", Extensions),
                MediaInfo.FormatBytes(TotalBytes)
            };

            if (Note is { } note)
            {
                parts.Add(note);
            }

            return string.Join(" · ", parts);
        }
    }

    /// <summary>
    /// O que separou este grupo de outro do mesmo tipo. Sem isto, dois cartoes "3 vídeos"
    /// com listas de conversao diferentes pareceriam um erro do programa.
    /// </summary>
    public string? Note => Kind switch
    {
        MediaKind.Video when Files.Count > 0 && !Files[0].HasAudio => "sem trilha sonora",
        MediaKind.Video when Files.Count > 0 && !Files[0].HasVideo => "sem imagem",
        _ => null
    };

    private static string NameFor(MediaKind kind, int count) => (kind, count) switch
    {
        (MediaKind.Image, 1) => "imagem",
        (MediaKind.Image, _) => "imagens",
        (MediaKind.Video, 1) => "vídeo",
        (MediaKind.Video, _) => "vídeos",
        (MediaKind.Audio, 1) => "áudio",
        (MediaKind.Audio, _) => "áudios",
        (MediaKind.Pdf, 1) => "PDF",
        (MediaKind.Pdf, _) => "PDFs",
        (MediaKind.Document, 1) => "documento",
        (MediaKind.Document, _) => "documentos",
        (MediaKind.Text, 1) => "texto",
        (MediaKind.Text, _) => "textos",
        (_, 1) => "arquivo",
        _ => "arquivos"
    };
}

/// <summary>
/// Separa os arquivos soltos de uma vez em grupos que podem ser configurados juntos.
/// A regra e uma so: mesmo tipo e exatamente a mesma lista de conversoes compativeis.
/// Video com trilha sonora nao cai no mesmo cartao de video sem trilha - senao o cartao
/// ofereceria "extrair áudio" para arquivos que nao tem audio nenhum.
/// </summary>
public static class BatchPlanner
{
    public static IReadOnlyList<BatchGroup> Group(IEnumerable<MediaInfo> files)
    {
        var unique = new List<MediaInfo>();
        var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        foreach (var file in files)
        {
            // O mesmo arquivo solto duas vezes nao vira duas conversoes.
            if (seen.Add(file.Path))
            {
                unique.Add(file);
            }
        }

        return
        [
            .. unique
                .GroupBy(SignatureOf, StringComparer.Ordinal)
                .Select(entry => new BatchGroup
                {
                    Kind = entry.First().Kind,
                    Files = [.. entry],
                    Operations = OperationCatalog.For(entry.First())
                })
                .OrderBy(group => group.Kind)
                .ThenByDescending(group => group.Count)
                .ThenBy(group => group.Detail, StringComparer.Ordinal)
        ];
    }

    /// <summary>Total de arquivos que virariam job, ignorando grupos sem motor local.</summary>
    public static int ConvertibleCount(IEnumerable<BatchGroup> groups) =>
        groups.Where(group => group.HasWork).Sum(group => group.Count);

    /// <summary>
    /// A identidade do grupo a que o arquivo pertence. Publica porque a interface precisa
    /// reencontrar o cartao ja configurado quando mais arquivos entram no lote.
    /// </summary>
    public static string SignatureOf(MediaInfo info) =>
        $"{(int)info.Kind}:{string.Join(',', OperationCatalog.For(info).Select(operation => operation.Id).Order(StringComparer.Ordinal))}";
}
