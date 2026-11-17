using System.Runtime.InteropServices;
using Microsoft.Win32.SafeHandles;

namespace SmellsLikeTech.Converter.Core.Music;

/// <summary>
/// ABI 1.2 do núcleo: refino dos agudos das faixas separadas (Wiener multicanal), diagnóstico
/// de qualidade e reparo conservador do restaurador. Os três trabalham em fluxo, com uma
/// latência fixa: quem chama empurra blocos, depois zeros, e descarta o começo atrasado.
/// </summary>
public static partial class MusicNative
{
    private const uint StemRefineCapability = 1u << 9;
    private const uint DiagnosticsCapability = 1u << 10;
    private const uint RepairCapability = 1u << 11;

    /// <summary>Ordem dos valores do diagnóstico (SLT_DIAG_* em slt_media_api.h).</summary>
    public static readonly IReadOnlyList<string> DiagnosisFields =
    [
        "duracao", "picoDb", "rmsDb", "cristaDb", "dc", "recorte", "pisoDeRuidoDb", "zumbidoHz", "zumbidoDb", "bandaHz",
        "estalosPorMinuto", "correlacao", "silencio", "agudosDb", "dinamicaDb", "taxa", "planuraDoRuido",
    ];

    /// <summary>Ordem dos parâmetros do reparo (SLT_REPAIR_* em slt_media_api.h).</summary>
    public static readonly IReadOnlyList<string> RepairParameters =
        ["dc", "zumbidoHz", "zumbido", "estalos", "recorte", "nivelDoRecorte", "ruido", "arDb", "corteGraveHz", "tetoDb", "ganhoDb", "reservado"];

    public const int NoiseProfileBins = 2049;

    /// <summary>Versão da ABI do núcleo (maior &lt;&lt; 16 | menor), ou 0 sem a DLL.</summary>
    public static uint AbiVersion => IsAvailable ? Version() : 0;

    public static bool HasStemRefine => IsAvailable && (Capabilities() & StemRefineCapability) != 0;
    public static bool HasAudioRestoration => IsAvailable && (Capabilities() & (DiagnosticsCapability | RepairCapability)) == (DiagnosticsCapability | RepairCapability);

    private sealed class StemRefinerHandle : SafeHandleZeroOrMinusOneIsInvalid
    {
        public StemRefinerHandle() : base(true) { }
        protected override bool ReleaseHandle() { DestroyStemRefiner(handle); return true; }
    }

    private sealed class AnalyzerHandle : SafeHandleZeroOrMinusOneIsInvalid
    {
        public AnalyzerHandle() : base(true) { }
        protected override bool ReleaseHandle() { DestroyAnalyzer(handle); return true; }
    }

    private sealed class RepairHandle : SafeHandleZeroOrMinusOneIsInvalid
    {
        public RepairHandle() : base(true) { }
        protected override bool ReleaseHandle() { DestroyRepair(handle); return true; }
    }

    [LibraryImport(Library, EntryPoint = "slt_stem_refiner_create")]
    private static partial StemRefinerHandle CreateStemRefiner(uint rate, uint stems, float cutoff, float amount);
    [LibraryImport(Library, EntryPoint = "slt_stem_refiner_latency")]
    private static partial uint StemRefinerLatency(StemRefinerHandle refiner);
    [LibraryImport(Library, EntryPoint = "slt_stem_refiner_process")]
    private static unsafe partial int ProcessStemRefiner(StemRefinerHandle refiner, float* planar, uint frames);
    [LibraryImport(Library, EntryPoint = "slt_stem_refiner_destroy")]
    private static partial void DestroyStemRefiner(nint refiner);

    [LibraryImport(Library, EntryPoint = "slt_audio_analyzer_create")]
    private static partial AnalyzerHandle CreateAnalyzer(uint rate);
    [LibraryImport(Library, EntryPoint = "slt_audio_analyzer_feed")]
    private static unsafe partial int FeedAnalyzer(AnalyzerHandle analyzer, float* left, float* right, uint frames);
    [LibraryImport(Library, EntryPoint = "slt_audio_analyzer_result")]
    private static unsafe partial int AnalyzerResult(AnalyzerHandle analyzer, float* values, uint capacity);
    [LibraryImport(Library, EntryPoint = "slt_audio_analyzer_noise_profile")]
    private static unsafe partial int AnalyzerNoiseProfile(AnalyzerHandle analyzer, float* values, uint capacity);
    [LibraryImport(Library, EntryPoint = "slt_audio_analyzer_destroy")]
    private static partial void DestroyAnalyzer(nint analyzer);

    [LibraryImport(Library, EntryPoint = "slt_audio_repair_create")]
    private static unsafe partial RepairHandle CreateRepair(uint rate, float* parameters, uint count);
    [LibraryImport(Library, EntryPoint = "slt_audio_repair_set_noise_profile")]
    private static unsafe partial int SetRepairNoiseProfile(RepairHandle repair, float* profile, uint count);
    [LibraryImport(Library, EntryPoint = "slt_audio_repair_latency")]
    private static partial uint RepairLatency(RepairHandle repair);
    [LibraryImport(Library, EntryPoint = "slt_audio_repair_process")]
    private static unsafe partial int ProcessRepair(RepairHandle repair, float* left, float* right, uint frames);
    [LibraryImport(Library, EntryPoint = "slt_audio_repair_stats")]
    private static unsafe partial int RepairStats(RepairHandle repair, float* values, uint capacity);
    [LibraryImport(Library, EntryPoint = "slt_audio_repair_destroy")]
    private static partial void DestroyRepair(nint repair);

    /// <summary>Refino dos agudos: blocos planares [(faixa*2+canal)*quadros + i], no lugar, atrasados por <see cref="Latency"/>.</summary>
    public sealed class StemRefiner : IDisposable
    {
        private readonly StemRefinerHandle handle;
        public int Stems { get; }

        public StemRefiner(uint rate, int stems, float cutoffHz = 8000, float amount = 0.8f)
        {
            if (!HasStemRefine) throw new PlatformNotSupportedException("Refino dos agudos indisponível neste núcleo.");
            Stems = stems;
            handle = CreateStemRefiner(rate, (uint)stems, cutoffHz, amount);
            if (handle.IsInvalid) throw new ArgumentOutOfRangeException(nameof(stems), "Refino dos agudos recusado.");
        }

        public int Latency => (int)StemRefinerLatency(handle);

        public unsafe void Process(Span<float> planar, int frames)
        {
            if (frames < 0 || planar.Length < frames * 2 * Stems) throw new ArgumentException("Bloco do refino inválido.");
            fixed (float* p = planar) Conferir(ProcessStemRefiner(handle, p, (uint)frames));
        }

        public void Dispose() => handle.Dispose();
    }

    /// <summary>Diagnóstico de qualidade: só mede.</summary>
    public sealed class AudioAnalyzer : IDisposable
    {
        private readonly AnalyzerHandle handle;

        public AudioAnalyzer(uint rate)
        {
            if (!HasAudioRestoration) throw new PlatformNotSupportedException("Diagnóstico de áudio indisponível neste núcleo.");
            handle = CreateAnalyzer(rate);
            if (handle.IsInvalid) throw new ArgumentOutOfRangeException(nameof(rate), "Taxa inválida para o diagnóstico.");
        }

        public unsafe void Feed(ReadOnlySpan<float> left, ReadOnlySpan<float> right)
        {
            if (!right.IsEmpty && right.Length != left.Length) throw new ArgumentException("Canais com tamanhos diferentes.");
            fixed (float* e = left, d = right) Conferir(FeedAnalyzer(handle, e, right.IsEmpty ? null : d, (uint)left.Length));
        }

        public unsafe IReadOnlyDictionary<string, double> Result()
        {
            var valores = new float[DiagnosisFields.Count];
            fixed (float* v = valores) Conferir(AnalyzerResult(handle, v, (uint)valores.Length));
            return DiagnosisFields.Select((nome, i) => (nome, (double)valores[i])).ToDictionary(x => x.nome, x => x.Item2);
        }

        public unsafe float[] NoiseProfile()
        {
            var perfil = new float[NoiseProfileBins];
            fixed (float* p = perfil) Conferir(AnalyzerNoiseProfile(handle, p, (uint)perfil.Length));
            return perfil;
        }

        public void Dispose() => handle.Dispose();
    }

    /// <summary>Reparo conservador: no lugar, atrasado por <see cref="Latency"/>.</summary>
    public sealed class AudioRepair : IDisposable
    {
        private readonly RepairHandle handle;

        public unsafe AudioRepair(uint rate, IReadOnlyDictionary<string, double> parameters, float[]? noiseProfile = null)
        {
            if (!HasAudioRestoration) throw new PlatformNotSupportedException("Reparo de áudio indisponível neste núcleo.");
            var lista = RepairParameters.Select(n => parameters.TryGetValue(n, out var v) && double.IsFinite(v) ? (float)v : 0f).ToArray();
            fixed (float* p = lista) handle = CreateRepair(rate, p, (uint)lista.Length);
            if (handle.IsInvalid) throw new ArgumentOutOfRangeException(nameof(parameters), "Reparo recusado (taxa ou parâmetros inválidos).");
            if (noiseProfile is { Length: NoiseProfileBins })
                fixed (float* p = noiseProfile) Conferir(SetRepairNoiseProfile(handle, p, (uint)noiseProfile.Length));
        }

        public int Latency => (int)RepairLatency(handle);

        public unsafe void Process(Span<float> left, Span<float> right)
        {
            if (!right.IsEmpty && right.Length != left.Length) throw new ArgumentException("Canais com tamanhos diferentes.");
            fixed (float* e = left, d = right) Conferir(ProcessRepair(handle, e, right.IsEmpty ? null : d, (uint)left.Length));
        }

        /// <summary>Estalos corrigidos, recortes refeitos, redução média de ruído (dB) e maior redução do limitador (dB).</summary>
        public unsafe (double Clicks, double Clips, double DenoiseDb, double LimiterDb) Stats()
        {
            var v = new float[4];
            fixed (float* p = v) Conferir(RepairStats(handle, p, 4));
            return (v[0], v[1], v[2], v[3]);
        }

        public void Dispose() => handle.Dispose();
    }
}
