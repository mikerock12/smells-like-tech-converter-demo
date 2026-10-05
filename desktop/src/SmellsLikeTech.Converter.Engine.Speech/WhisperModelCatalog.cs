using SmellsLikeTech.Converter.Core.Abstractions;

namespace SmellsLikeTech.Converter.Engine.Speech;

/// <summary>Modelo Whisper previsto, com tamanho aproximado e origem para instalacao sob demanda.</summary>
public sealed record WhisperModelDescriptor(
    string Id,
    string DisplayName,
    string FileName,
    long ApproximateBytes,
    string DownloadUrl,
    string Notes);

/// <summary>
/// Modelos ficam em D:\SmellsLikeTechConverter\Models\Whisper e sao instalados sob demanda,
/// para o instalador do produto nao carregar gigabytes.
/// </summary>
public sealed class WhisperModelCatalog(string modelsDirectory) : ISpeechModelCatalog
{
    private const string BaseUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/";

    /// <summary>
    /// Os tempos sao aproximados, medidos em CPU de quatro nucleos sobre audio de
    /// conversa real: servem para o usuario escolher sabendo o que esperar.
    /// </summary>
    public static IReadOnlyList<WhisperModelDescriptor> Descriptors { get; } =
    [
        new("tiny", "Tiny", "ggml-tiny.bin", 78L * 1024 * 1024, BaseUrl + "ggml-tiny.bin",
            "O mais rápido e o menos preciso. Serve para testar o fluxo."),
        new("base", "Base", "ggml-base.bin", 148L * 1024 * 1024, BaseUrl + "ggml-base.bin",
            "Rápido (~0,2x a duração do áudio), mas erra nomes próprios e fala regional."),
        new("small", "Small", "ggml-small.bin", 488L * 1024 * 1024, BaseUrl + "ggml-small.bin",
            "Equilíbrio recomendado: bom português em ~0,6x a duração do áudio."),
        new("medium", "Medium", "ggml-medium.bin", 1_530L * 1024 * 1024, BaseUrl + "ggml-medium.bin",
            "Alta precisão, inclusive sotaque e nomes de lugares. Leva ~1,7x a duração do áudio."),
        new("large", "Large v3", "ggml-large-v3.bin", 3_100L * 1024 * 1024, BaseUrl + "ggml-large-v3.bin",
            "A melhor qualidade e a mais lenta. Sem GPU, reserve várias vezes a duração do áudio.")
    ];

    /// <summary>Ordem de qualidade, do mais simples ao melhor.</summary>
    private static int QualityRank(string modelId) => modelId switch
    {
        "tiny" => 0,
        "base" => 1,
        "small" => 2,
        "medium" => 3,
        "large" => 4,
        _ => -1
    };

    public string ModelsDirectory { get; } = modelsDirectory;

    public IReadOnlyList<SpeechModel> ListModels() =>
        [.. Descriptors.Select(descriptor => Describe(descriptor))];

    public SpeechModel? Find(string modelId)
    {
        var descriptor = Descriptors.FirstOrDefault(item => string.Equals(item.Id, modelId, StringComparison.Ordinal));
        return descriptor is null ? null : Describe(descriptor);
    }

    public WhisperModelDescriptor? Descriptor(string modelId) =>
        Descriptors.FirstOrDefault(item => string.Equals(item.Id, modelId, StringComparison.Ordinal));

    /// <summary>Melhor modelo presente no disco, ou nulo se nenhum foi baixado.</summary>
    public SpeechModel? BestInstalled() =>
        ListModels()
            .Where(model => model.IsInstalled)
            .OrderByDescending(model => QualityRank(model.Id))
            .FirstOrDefault();

    /// <summary>
    /// O modelo escolhido nas configuracoes quando ele existe no disco; caso contrario,
    /// o melhor que estiver instalado. Evita mandar o usuario para um erro quando o
    /// preferido ainda nao foi baixado.
    /// </summary>
    public string ResolvePreferred(string preferred) =>
        Find(preferred)?.IsInstalled == true
            ? preferred
            : BestInstalled()?.Id ?? preferred;

    public string PathFor(WhisperModelDescriptor descriptor) =>
        Path.Combine(ModelsDirectory, descriptor.FileName);

    private SpeechModel Describe(WhisperModelDescriptor descriptor)
    {
        var path = PathFor(descriptor);
        var file = new FileInfo(path);
        return new SpeechModel(
            descriptor.Id,
            descriptor.DisplayName,
            path,
            file.Exists ? file.Length : descriptor.ApproximateBytes,
            file.Exists && file.Length > 1024 * 1024);
    }
}
