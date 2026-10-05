using Microsoft.Win32;
using SmellsLikeTech.Converter.Core.Options;
using SmellsLikeTech.Converter.Infrastructure.Execution;
using SmellsLikeTech.Converter.Infrastructure.Storage;

namespace SmellsLikeTech.Converter.Infrastructure.Hardware;

public sealed record HardwareInfo
{
    public required string CpuName { get; init; }
    public required int LogicalCores { get; init; }
    public required long TotalMemoryBytes { get; init; }
    public required long FreeDiskBytes { get; init; }
    public required string DiskRoot { get; init; }
    public IReadOnlyList<string> Gpus { get; init; } = [];
    public IReadOnlyList<string> HardwareEncoders { get; init; } = [];

    public bool HasNvidia => HardwareEncoders.Any(name => name.Contains("nvenc", StringComparison.Ordinal));
    public bool HasAmd => HardwareEncoders.Any(name => name.Contains("amf", StringComparison.Ordinal));
    public bool HasIntel => HardwareEncoders.Any(name => name.Contains("qsv", StringComparison.Ordinal));

    /// <summary>Aceleracao a usar quando o usuario deixa em Automatico.</summary>
    public HardwareAcceleration PreferredAcceleration =>
        HasNvidia ? HardwareAcceleration.Nvidia
        : HasAmd ? HardwareAcceleration.Amd
        : HasIntel ? HardwareAcceleration.Intel
        : HardwareAcceleration.Cpu;
}

/// <summary>
/// Detecta CPU, memoria, disco, GPU e encoders de hardware disponiveis.
/// Os encoders vem da propria build do FFmpeg instalada, nao de suposicao.
/// </summary>
public sealed class HardwareProbe(ConverterPaths paths, ProcessRunner processRunner)
{
    private static readonly string[] AcceleratedEncoders =
    [
        "h264_nvenc", "hevc_nvenc", "av1_nvenc",
        "h264_qsv", "hevc_qsv", "av1_qsv",
        "h264_amf", "hevc_amf", "av1_amf"
    ];

    public async Task<HardwareInfo> DescribeAsync(string ffmpegPath, CancellationToken cancellationToken)
    {
        var encoders = await DetectEncodersAsync(ffmpegPath, cancellationToken);
        return new HardwareInfo
        {
            CpuName = ReadCpuName(),
            LogicalCores = Environment.ProcessorCount,
            TotalMemoryBytes = GC.GetGCMemoryInfo().TotalAvailableMemoryBytes,
            FreeDiskBytes = paths.FreeDiskBytes(),
            DiskRoot = Path.GetPathRoot(paths.Root) ?? "?",
            Gpus = ReadGpuNames(),
            HardwareEncoders = encoders
        };
    }

    /// <summary>
    /// Encoders de hardware realmente utilizaveis. Nao basta estarem compilados no
    /// FFmpeg: uma build completa lista NVENC mesmo em maquina sem placa NVIDIA, e a
    /// conversao so falharia na hora do job. Por isso cada candidato faz uma codificacao
    /// minuscula de teste antes de ser anunciado.
    /// </summary>
    private async Task<IReadOnlyList<string>> DetectEncodersAsync(string ffmpegPath, CancellationToken cancellationToken)
    {
        var compiled = await ListCompiledEncodersAsync(ffmpegPath, cancellationToken);
        if (compiled.Count == 0)
        {
            return [];
        }

        var checks = compiled.Select(async encoder => (Encoder: encoder, Works: await WorksAsync(ffmpegPath, encoder, cancellationToken)));
        var results = await Task.WhenAll(checks);
        return [.. results.Where(item => item.Works).Select(item => item.Encoder)];
    }

    private async Task<IReadOnlyList<string>> ListCompiledEncodersAsync(string ffmpegPath, CancellationToken cancellationToken)
    {
        try
        {
            var command = new CommandSpec(ffmpegPath, ["-hide_banner", "-loglevel", "error", "-encoders"]);
            var result = await processRunner.RunAsync(
                command,
                workingDirectory: paths.Cache,
                temporaryDirectory: Path.GetTempPath(),
                cancellationToken: cancellationToken);

            return result.ExitCode != 0
                ? []
                : [.. AcceleratedEncoders.Where(name => result.StandardOutput.Contains(name, StringComparison.Ordinal))];
        }
        catch (Core.ConversionException)
        {
            return [];
        }
        catch (OperationCanceledException)
        {
            return [];
        }
    }

    /// <summary>Codifica meio segundo de imagem sintetica so para ver se o encoder inicializa.</summary>
    private async Task<bool> WorksAsync(string ffmpegPath, string encoder, CancellationToken cancellationToken)
    {
        // Driver travado nao pode segurar a abertura do aplicativo.
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        timeout.CancelAfter(TimeSpan.FromSeconds(15));

        try
        {
            var command = new CommandSpec(ffmpegPath,
            [
                "-hide_banner", "-nostdin", "-loglevel", "error",
                "-f", "lavfi", "-i", "testsrc=size=128x128:rate=5:duration=0.2",
                "-c:v", encoder,
                "-f", "null", "-"
            ]);

            var result = await processRunner.RunAsync(
                command,
                workingDirectory: paths.Cache,
                temporaryDirectory: Path.GetTempPath(),
                cancellationToken: timeout.Token);

            return result.ExitCode == 0;
        }
        catch (Core.ConversionException)
        {
            return false;
        }
        catch (OperationCanceledException)
        {
            return false;
        }
    }

    private static string ReadCpuName()
    {
        try
        {
            using var key = Registry.LocalMachine.OpenSubKey(
                @"HARDWARE\DESCRIPTION\System\CentralProcessor\0");
            return key?.GetValue("ProcessorNameString") as string ?? "CPU desconhecida";
        }
        catch (Exception exception) when (exception is System.Security.SecurityException or UnauthorizedAccessException)
        {
            return "CPU desconhecida";
        }
    }

    /// <summary>
    /// Placas de video presentes agora, perguntadas ao DXGI.
    ///
    /// Ja foi lido do registro, pelas descricoes de driver — e estava errado: ali fica
    /// registrado todo driver ja instalado, presente ou nao, entao uma maquina que um dia
    /// teve placa dedicada anunciava essa placa para sempre.
    /// </summary>
    private static IReadOnlyList<string> ReadGpuNames() => GpuInventory.Present();
}
