using SmellsLikeTech.Converter.Core.Jobs;
using SmellsLikeTech.Converter.Core.Media;

namespace SmellsLikeTech.Converter.Core.Abstractions;

/// <summary>
/// Pasta isolada de um job: input, work, output e temp.
/// Nada e gravado fora dela enquanto o job esta em execucao.
/// </summary>
public interface IJobWorkspace : IDisposable
{
    Guid JobId { get; }
    string JobDirectory { get; }
    string InputDirectory { get; }
    string WorkDirectory { get; }
    string OutputDirectory { get; }
    string TempDirectory { get; }

    string WorkPath(string fileName);
    string OutputPath(string fileName);
    void EnsureContained(string path);

    /// <summary>
    /// Remove a pasta do job. Devolve falso quando algum arquivo continuou preso, para
    /// que quem chama - que tem o log - possa registrar. Um temporario que ficou para
    /// tras precisa aparecer em algum lugar antes de virar disco cheio.
    /// </summary>
    bool TryDelete();
}

public interface IWorkspaceFactory
{
    IJobWorkspace Create(Guid jobId);
}

/// <summary>Contexto entregue ao engine durante a execucao.</summary>
public sealed record EngineContext(
    ConversionJob Job,
    IJobWorkspace Workspace,
    IProgress<JobProgress> Progress);

/// <summary>Arquivos produzidos dentro do workspace, ainda nao movidos para o destino final.</summary>
public sealed record EngineResult(IReadOnlyList<string> OutputFiles, string? Note = null);

/// <summary>
/// Um motor de conversao. A interface do aplicativo nunca fala com FFmpeg, ImageMagick,
/// Whisper ou SAPI diretamente - apenas cria jobs que a fila entrega a um destes.
/// </summary>
public interface IConversionEngine
{
    string Name { get; }

    bool CanExecute(string operation);

    Task<EngineResult> ExecuteAsync(EngineContext context, CancellationToken cancellationToken);
}

/// <summary>Deteccao de tipo, codec, resolucao, duracao e demais propriedades da entrada.</summary>
public interface IMediaInspector
{
    bool CanInspect(MediaKind kind);

    Task<MediaInfo> InspectAsync(string path, CancellationToken cancellationToken);
}

/// <summary>Canais de log separados, conforme o documento base.</summary>
public enum LogChannel
{
    App = 0,
    Jobs = 1,
    Ffmpeg = 2,
    Speech = 3,
    Errors = 4
}

public interface IConverterLog
{
    void Write(LogChannel channel, string message);

    void Error(string code, string message, Exception? exception = null);
}
