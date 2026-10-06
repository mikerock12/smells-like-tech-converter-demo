namespace SmellsLikeTech.Converter.Core.Abstractions;

/// <summary>Voz anunciada por um motor de sintese.</summary>
public sealed record SpeechVoice(string Id, string DisplayName, string Language, string Gender)
{
    public bool IsBrazilianPortuguese =>
        Language.StartsWith("pt-BR", StringComparison.OrdinalIgnoreCase);
}

/// <summary>
/// Abstracao de Text-to-Speech. A primeira implementacao usa o SAPI do Windows;
/// motores neurais futuros entram por aqui sem mexer no nucleo, na fila nem na interface.
/// </summary>
public interface ITtsEngine
{
    string Name { get; }

    string Version { get; }

    /// <summary>Vozes realmente instaladas e utilizaveis nesta maquina.</summary>
    IReadOnlyList<SpeechVoice> ListVoices();

    /// <summary>Carrega o modelo antes de começar a medir a velocidade da narração.</summary>
    Task PrepareAsync(CancellationToken cancellationToken) => Task.CompletedTask;

    /// <summary>Sintetiza o texto em um WAV local. A codificacao final fica com o FFmpeg.</summary>
    Task SynthesizeToWaveAsync(
        string text,
        string voiceId,
        int rate,
        int volume,
        string outputWavePath,
        IProgress<double>? progress,
        CancellationToken cancellationToken);
}

/// <summary>Modelo de transcricao disponivel no disco.</summary>
public sealed record SpeechModel(string Id, string DisplayName, string Path, long SizeBytes, bool IsInstalled)
{
    public string SizeDisplay => IsInstalled ? Media.MediaInfo.FormatBytes(SizeBytes) : "não instalado";
}

/// <summary>Catalogo dos modelos Whisper instalados sob demanda.</summary>
public interface ISpeechModelCatalog
{
    IReadOnlyList<SpeechModel> ListModels();

    SpeechModel? Find(string modelId);

    string ModelsDirectory { get; }
}
