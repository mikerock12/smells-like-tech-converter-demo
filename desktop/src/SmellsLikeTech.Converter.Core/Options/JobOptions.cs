using System.Text.Json;
using System.Text.Json.Serialization;
using SmellsLikeTech.Converter.Core.Operations;

namespace SmellsLikeTech.Converter.Core.Options;

/// <summary>
/// Base das opcoes de job. Cada operacao tem um schema proprio, validado por allowlist
/// antes de qualquer engine ser acionado.
/// </summary>
[JsonPolymorphic(TypeDiscriminatorPropertyName = "operation")]
[JsonDerivedType(typeof(ImageConvertOptions), OperationIds.ImageConvert)]
[JsonDerivedType(typeof(VideoConvertOptions), OperationIds.VideoConvert)]
[JsonDerivedType(typeof(ExtractAudioOptions), OperationIds.VideoExtractAudio)]
[JsonDerivedType(typeof(VideoToGifOptions), OperationIds.VideoToGif)]
[JsonDerivedType(typeof(VideoToFramesOptions), OperationIds.VideoToFrames)]
[JsonDerivedType(typeof(AudioConvertOptions), OperationIds.AudioConvert)]
[JsonDerivedType(typeof(TranscribeOptions), OperationIds.SpeechTranscribe)]
[JsonDerivedType(typeof(SynthesizeOptions), OperationIds.SpeechSynthesize)]
[JsonDerivedType(typeof(PdfToDocumentOptions), OperationIds.PdfToDocument)]
[JsonDerivedType(typeof(PdfToImageOptions), OperationIds.PdfToImage)]
[JsonDerivedType(typeof(OcrOptions), OperationIds.OcrImageToText)]
[JsonDerivedType(typeof(PdfMergeOptions), OperationIds.PdfMerge)]
[JsonDerivedType(typeof(PdfSplitOptions), OperationIds.PdfSplit)]
[JsonDerivedType(typeof(ImageToPdfOptions), OperationIds.ImageToPdf)]
public abstract record JobOptions
{
    /// <summary>Operacao a que estas opcoes pertencem.</summary>
    [JsonIgnore]
    public abstract string Operation { get; }

    /// <summary>Extensao do arquivo produzido.</summary>
    [JsonIgnore]
    public abstract string OutputExtension { get; }

    /// <summary>Lanca <see cref="ConversionException"/> se algum valor estiver fora da allowlist.</summary>
    public abstract void Validate();

    /// <summary>Resumo curto e sem conteudo sensivel, seguro para historico e logs.</summary>
    public abstract string Summary();
}

public static class JobOptionsJson
{
    public static JsonSerializerOptions Options { get; } = new()
    {
        WriteIndented = false,
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull
    };

    public static string Serialize(JobOptions options) => JsonSerializer.Serialize(options, Options);

    public static JobOptions? Deserialize(string json)
    {
        try
        {
            return JsonSerializer.Deserialize<JobOptions>(json, Options);
        }
        catch (JsonException)
        {
            return null;
        }
        catch (NotSupportedException)
        {
            return null;
        }
    }
}
