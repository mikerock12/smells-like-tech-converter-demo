using SmellsLikeTech.Converter.Core;
using SmellsLikeTech.Converter.Core.Abstractions;
using SmellsLikeTech.Converter.Core.Operations;
using SmellsLikeTech.Converter.Infrastructure.Execution;

namespace SmellsLikeTech.Converter.Engine.Documents;

/// <summary>Ferramenta externa prevista para os marcos de PDF, OCR e documentos.</summary>
public sealed record DocumentTool(string Id, string DisplayName, string Executable, string Purpose)
{
    public string? ResolvedPath { get; init; }
    public bool IsInstalled => ResolvedPath is not null;
}

/// <summary>
/// Marcos 6 e 7. O modulo ja existe na solution e a interface ja lista as operacoes,
/// mas nada e prometido antes das ferramentas estarem instaladas e validadas.
/// </summary>
public static class DocumentToolset
{
    public static IReadOnlyList<DocumentTool> Detect() =>
    [
        Probe("libreoffice", "LibreOffice", "soffice", "DOCX, XLSX, PPTX e ODF para PDF"),
        Probe("pandoc", "Pandoc", "pandoc", "Markdown, HTML e DOCX entre si"),
        Probe("tesseract", "Tesseract OCR", "tesseract", "Imagem e PDF escaneado para texto"),
        Probe("ghostscript", "Ghostscript", "gswin64c", "Compressão e manipulação de PDF")
    ];

    private static DocumentTool Probe(string id, string displayName, string executable, string purpose) =>
        new(id, displayName, executable, purpose) { ResolvedPath = ExecutableGuard.Locate(executable) };
}

/// <summary>
/// Placeholder registrado na fila: responde pelas operacoes dos marcos 6 e 7 com um erro
/// claro em vez de deixar a interface oferecer algo que ainda nao existe.
/// </summary>
public sealed class PlannedDocumentEngine : IConversionEngine
{
    public string Name => "Documentos (planejado)";

    public bool CanExecute(string operation) => operation == OperationIds.DocumentConvert;

    public Task<EngineResult> ExecuteAsync(EngineContext context, CancellationToken cancellationToken) =>
        throw new ConversionException(
            "operation_not_available",
            "Esta conversão entra nos marcos de PDF, OCR e documentos e ainda não está habilitada.");
}
