using SmellsLikeTech.Converter.Core;
using SmellsLikeTech.Converter.Core.Abstractions;
using SmellsLikeTech.Converter.Core.Media;

namespace SmellsLikeTech.Converter.Infrastructure.Media;

/// <summary>
/// Detecta o arquivo arrastado usando o inspetor certo para cada tipo e, se nenhum
/// souber ler, devolve ao menos nome, tamanho e tipo - com o erro registrado.
/// </summary>
public sealed class CompositeMediaInspector(IEnumerable<IMediaInspector> inspectors) : IMediaInspector
{
    private readonly IReadOnlyList<IMediaInspector> inspectors = [.. inspectors];

    public bool CanInspect(MediaKind kind) => true;

    public async Task<MediaInfo> InspectAsync(string path, CancellationToken cancellationToken)
    {
        var file = new FileInfo(path);
        if (!file.Exists)
        {
            throw new ConversionException("input_not_found", "O arquivo não foi encontrado.");
        }

        var kind = FormatCatalog.KindOfPath(path);
        if (kind == MediaKind.Unknown)
        {
            throw new ConversionException(
                "unsupported_input",
                $"O formato '{file.Extension}' não está na lista de tipos aceitos.");
        }

        var inspector = inspectors.FirstOrDefault(candidate => candidate.CanInspect(kind));
        if (inspector is null)
        {
            return Basic(file, kind, null);
        }

        try
        {
            return await inspector.InspectAsync(path, cancellationToken);
        }
        catch (ConversionException exception)
        {
            // O arquivo continua listado, mas sem operacoes que dependam dos metadados.
            return Basic(file, kind, exception.Message);
        }
    }

    private static MediaInfo Basic(FileInfo file, MediaKind kind, string? probeError) => new()
    {
        Path = file.FullName,
        FileName = file.Name,
        Extension = FormatCatalog.Normalize(file.Extension),
        Kind = kind,
        SizeBytes = file.Length,
        ProbeError = probeError
    };
}
