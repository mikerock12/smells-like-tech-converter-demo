using SmellsLikeTech.Converter.Core.Media;

namespace SmellsLikeTech.Converter.Core.Abstractions;

/// <summary>
/// Le o texto de um arquivo que nao e texto puro (PDF, imagem, documento) para que ele
/// possa ser narrado. Implementado no modulo de documentos; a sintese de voz so conhece
/// esta interface.
/// </summary>
public interface IDocumentTextReader
{
    bool CanRead(MediaKind kind, string extension);

    /// <summary>O texto legivel do arquivo, na ordem de leitura. Progresso de 0 a 1.</summary>
    Task<string> ReadAsync(string path, MediaKind kind, IProgress<double>? progress, CancellationToken cancellationToken);
}
