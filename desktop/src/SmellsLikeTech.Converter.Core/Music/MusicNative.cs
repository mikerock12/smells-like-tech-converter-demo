using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json.Nodes;
using Microsoft.Win32.SafeHandles;

namespace SmellsLikeTech.Converter.Core.Music;

/// <summary>Ponte da C ABI. O aplicativo pode usar o motor gerenciado se a DLL não estiver disponível.</summary>
public static partial class MusicNative
{
    private const string Library = "slt_native";
    private const uint MixerCapability = 1u << 5;
    private static readonly Lazy<bool> Disponivel = new(() =>
    {
        try { return Version() >> 16 == 1 && (Capabilities() & MixerCapability) != 0; }
        catch (Exception e) when (e is DllNotFoundException or EntryPointNotFoundException or BadImageFormatException)
        {
            return false;
        }
    });

    public static bool IsAvailable => Disponivel.Value;

    [LibraryImport(Library, EntryPoint = "slt_get_api_version")]
    internal static partial uint Version();
    [LibraryImport(Library, EntryPoint = "slt_get_capabilities")]
    internal static partial uint Capabilities();
    [LibraryImport(Library, EntryPoint = "slt_mixer_create")]
    private static partial MixerHandle CreateMixer(uint sampleRate);
    [LibraryImport(Library, EntryPoint = "slt_mixer_destroy")]
    private static partial void DestroyMixer(nint mixer);
    [LibraryImport(Library, EntryPoint = "slt_mixer_set_source", StringMarshalling = StringMarshalling.Utf8)]
    private static partial int Source(MixerHandle mixer, string key, nint left, nint right, ulong frames);
    [LibraryImport(Library, EntryPoint = "slt_mixer_set_project")]
    private static unsafe partial int Project(MixerHandle mixer, byte* json, uint length);
    [LibraryImport(Library, EntryPoint = "slt_mixer_refresh")]
    private static partial int RefreshMixer(MixerHandle mixer);
    [LibraryImport(Library, EntryPoint = "slt_mixer_meters", StringMarshalling = StringMarshalling.Utf8)]
    private static unsafe partial int ReadMeters(MixerHandle mixer, string id, float* four);
    [LibraryImport(Library, EntryPoint = "slt_mixer_render")]
    private static unsafe partial int Render(MixerHandle mixer, long start, uint frames, float* left, float* right, nint options);
    [LibraryImport(Library, EntryPoint = "slt_mixer_play")]
    private static partial int Play(MixerHandle mixer, long position);
    [LibraryImport(Library, EntryPoint = "slt_mixer_stop")]
    private static partial int Stop(MixerHandle mixer);
    [LibraryImport(Library, EntryPoint = "slt_mixer_position")]
    private static partial long Position(MixerHandle mixer);
    [LibraryImport(Library, EntryPoint = "slt_mixer_set_loop")]
    private static partial int Loop(MixerHandle mixer, long start, long end);
    [LibraryImport(Library, EntryPoint = "slt_mixer_set_metronome")]
    private static partial int Metronome(MixerHandle mixer, uint enabled);
    [LibraryImport(Library, EntryPoint = "slt_stream_open")]
    private static partial int OpenStream(in StreamConfig config, MixerHandle mixer, out StreamHandle stream);
    [LibraryImport(Library, EntryPoint = "slt_stream_close")]
    private static partial void CloseStream(nint stream);
    [LibraryImport(Library, EntryPoint = "slt_stream_start")]
    private static partial int StartStream(StreamHandle stream);
    [LibraryImport(Library, EntryPoint = "slt_stream_stop")]
    private static partial int StopStream(StreamHandle stream);
    [LibraryImport(Library, EntryPoint = "slt_stream_get_info")]
    private static partial int GetStreamInfo(StreamHandle stream, out StreamInfo info);
    [LibraryImport(Library, EntryPoint = "slt_stream_get_stats")]
    private static partial int GetStreamStats(StreamHandle stream, out StreamStats stats);
    [LibraryImport(Library, EntryPoint = "slt_stream_capture")]
    private static partial int CaptureStream(StreamHandle stream, uint enabled);
    [LibraryImport(Library, EntryPoint = "slt_stream_read")]
    private static unsafe partial uint ReadStream(StreamHandle stream, float* left, float* right, uint capacity, out long position);
    [LibraryImport(Library, EntryPoint = "slt_restorer_create")]
    private static partial RestorerHandle CreateRestorer(in RestorerConfig config);
    [LibraryImport(Library, EntryPoint = "slt_restorer_destroy")]
    private static partial void DestroyRestorer(nint restorer);
    [LibraryImport(Library, EntryPoint = "slt_restorer_process")]
    private static unsafe partial int Restore(RestorerHandle restorer, float* left, float* right, uint frames);
    [LibraryImport(Library, EntryPoint = "slt_restorer_matching_gain")]
    private static partial int MatchingGain(RestorerHandle restorer, out float gain);

    private static void Conferir(int resultado)
    {
        if (resultado != 0) throw new InvalidOperationException($"O núcleo de áudio devolveu o erro {resultado}.");
    }

    internal sealed class MixerHandle : SafeHandleZeroOrMinusOneIsInvalid
    {
        public List<float[]> Fontes { get; } = [];
        public MixerHandle() : base(true) { }
        protected override bool ReleaseHandle() { DestroyMixer(handle); Fontes.Clear(); return true; }
    }

    internal sealed class StreamHandle : SafeHandleZeroOrMinusOneIsInvalid
    {
        private MixerHandle? mixer;
        public StreamHandle() : base(true) { }
        public void ReterMixer(MixerHandle valor)
        {
            var adicionada = false;
            valor.DangerousAddRef(ref adicionada);
            if (adicionada) mixer = valor;
        }
        protected override bool ReleaseHandle()
        {
            CloseStream(handle);
            mixer?.DangerousRelease();
            return true;
        }
    }

    private sealed class RestorerHandle : SafeHandleZeroOrMinusOneIsInvalid
    {
        public RestorerHandle() : base(true) { }
        protected override bool ReleaseHandle() { DestroyRestorer(handle); return true; }
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct StreamConfig
    {
        public uint Size;
        public uint Backend;
        public nint OutputId;
        public nint InputId;
        public uint UseInput;
        public uint Rate;
        public uint BufferFrames;
        public uint Reserved;
    }

    [StructLayout(LayoutKind.Sequential)]
    public struct StreamInfo
    {
        public uint Backend, SampleRate, BufferFrames, OutputChannels, InputChannels,
            OutputLatencyFrames, InputLatencyFrames, Running, Lost, LowLatency;
    }

    [StructLayout(LayoutKind.Sequential)]
    public struct StreamStats
    {
        public ulong Callbacks, Frames;
        public uint XRuns, MaxCallbackUs, AverageCallbackUs, CaptureOverflows;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct RestorerConfig
    {
        public uint Size, Rate, Profile;
        public float Intensity;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct RenderOptions
    {
        public uint Size, NoMaster, Metronome, Reserved;
        public nint OnlyTrack, InputLeft, InputRight;
    }

    public sealed class Mixer : IDisposable
    {
        private readonly MixerHandle handle;
        private readonly object controle = new();
        private Stream? fluxo;

        public Mixer(int taxa)
        {
            if (!IsAvailable) throw new PlatformNotSupportedException("Motor de áudio nativo indisponível.");
            handle = CreateMixer(checked((uint)taxa));
            if (handle.IsInvalid) throw new InvalidOperationException("O núcleo não criou o mixer.");
        }

        public unsafe void SetSource(string id, ReadOnlySpan<float> left, ReadOnlySpan<float> right = default)
        {
            ArgumentException.ThrowIfNullOrEmpty(id);
            if (id.Contains('\0') || left.IsEmpty || (!right.IsEmpty && left.Length != right.Length))
                throw new ArgumentException("Fonte de áudio inválida.");
            lock (controle)
            {
                ObjectDisposedException.ThrowIf(handle.IsClosed, this);
                // O plano de áudio pode continuar usando uma versão anterior da fonte.
                // O heap fixo e a lista do mixer mantêm os endereços estáveis até fechar.
                var esquerda = GC.AllocateUninitializedArray<float>(left.Length, pinned: true);
                var direita = right.IsEmpty ? null : GC.AllocateUninitializedArray<float>(right.Length, pinned: true);
                left.CopyTo(esquerda);
                if (direita is not null) right.CopyTo(direita);
                var fontes = handle.Fontes;
                fontes.EnsureCapacity(fontes.Count + (direita is null ? 1 : 2));
                fixed (float* e = esquerda, d = direita)
                    Conferir(Source(handle, id, (nint)e, (nint)d, (ulong)esquerda.Length));
                fontes.Add(esquerda);
                if (direita is not null) fontes.Add(direita);
            }
        }

        public unsafe void SetProject(string json)
        {
            var bytes = Encoding.UTF8.GetBytes(json);
            lock (controle)
                fixed (byte* dados = bytes)
                    Conferir(Project(handle, dados, (uint)bytes.Length));
        }

        public void SetProject(MusicProject project)
        {
            var json = project.ToJson();
            if (!project.Tracks.SelectMany(track => track.Clips).Any(MusicTransform.IsTransformed))
            {
                SetProject(json);
                return;
            }

            var documento = JsonNode.Parse(json)!;
            var faixas = documento["faixas"]!.AsArray();
            for (var f = 0; f < project.Tracks.Count; f++)
            {
                var clipes = project.Tracks[f].Clips;
                for (var c = 0; c < clipes.Count; c++)
                    if (MusicTransform.IsTransformed(clipes[c]))
                        faixas[f]!["clipes"]![c]!["_fonte"] = MusicTransform.SourceKey(clipes[c]);
            }
            SetProject(documento.ToJsonString());
        }

        public unsafe float[] Meters(string id)
        {
            var values = new float[4];
            lock (controle)
                fixed (float* buffer = values)
                    Conferir(ReadMeters(handle, id, buffer));
            return values;
        }

        public void RemoveSource(string id)
        {
            ArgumentException.ThrowIfNullOrEmpty(id);
            if (id.Contains('\0')) throw new ArgumentException("Id de fonte inválido.", nameof(id));
            lock (controle) Conferir(Source(handle, id, 0, 0, 0));
        }

        public void Refresh() { lock (controle) Conferir(RefreshMixer(handle)); }

        public unsafe void Render(long start, Span<float> left, Span<float> right,
                                  string? onlyTrack = null, bool withoutMaster = false, bool metronome = false)
        {
            if (left.Length != right.Length || left.IsEmpty || left.Overlaps(right))
                throw new ArgumentException("Saídas planares inválidas.");
            lock (controle)
            {
                if (fluxo is not null) throw new InvalidOperationException("Feche o fluxo antes de renderizar.");
                var faixa = onlyTrack is null ? null : Encoding.UTF8.GetBytes(onlyTrack + '\0');
                fixed (byte* id = faixa)
                fixed (float* e = left, d = right)
                {
                    var options = new RenderOptions
                    {
                        Size = (uint)sizeof(RenderOptions), OnlyTrack = (nint)id,
                        NoMaster = withoutMaster ? 1u : 0u, Metronome = metronome ? 1u : 0u,
                    };
                    Conferir(MusicNative.Render(handle, start, (uint)left.Length, e, d, (nint)(&options)));
                }
            }
        }

        public void Play(long position) { lock (controle) Conferir(MusicNative.Play(handle, position)); }
        public void Stop() { lock (controle) Conferir(MusicNative.Stop(handle)); }
        public long Position { get { lock (controle) return MusicNative.Position(handle); } }
        public void SetLoop(long start, long end) { lock (controle) Conferir(Loop(handle, start, end)); }
        public void SetMetronome(bool enabled) { lock (controle) Conferir(Metronome(handle, enabled ? 1u : 0u)); }

        public unsafe Stream Open(uint backend = 0, string? output = null, string? input = null,
                                  bool capture = false, uint bufferFrames = 0)
        {
            lock (controle)
            {
                if (fluxo is not null) throw new InvalidOperationException("Já há um fluxo de áudio aberto.");
                var outputBytes = output is null ? null : Encoding.UTF8.GetBytes(output + '\0');
                var inputBytes = input is null ? null : Encoding.UTF8.GetBytes(input + '\0');
                fixed (byte* saida = outputBytes, entrada = inputBytes)
                {
                    var config = new StreamConfig
                    {
                        Size = (uint)sizeof(StreamConfig), Backend = backend, OutputId = (nint)saida,
                        InputId = (nint)entrada, UseInput = capture ? 1u : 0u, BufferFrames = bufferFrames,
                    };
                    Conferir(OpenStream(in config, handle, out var novo));
                    novo.ReterMixer(handle);
                    fluxo = new Stream(novo, controle, () => fluxo = null);
                    return fluxo;
                }
            }
        }

        public void Dispose()
        {
            lock (controle)
            {
                fluxo?.Dispose();
                handle.Dispose();
            }
        }
    }

    public sealed class Stream : IDisposable
    {
        private readonly StreamHandle handle;
        private readonly object controle;
        private readonly Action fechado;
        internal Stream(StreamHandle handle, object controle, Action fechado)
        {
            this.handle = handle;
            this.controle = controle;
            this.fechado = fechado;
        }

        public void Start() { lock (controle) Conferir(StartStream(handle)); }
        public void Stop() { lock (controle) Conferir(StopStream(handle)); }
        public StreamInfo Info { get { lock (controle) { Conferir(GetStreamInfo(handle, out var info)); return info; } } }
        public StreamStats Stats { get { lock (controle) { Conferir(GetStreamStats(handle, out var stats)); return stats; } } }
        public void Capture(bool enabled) { lock (controle) Conferir(CaptureStream(handle, enabled ? 1u : 0u)); }
        public unsafe (long Position, int Frames) Read(Span<float> left, Span<float> right)
        {
            if (left.Length != right.Length || left.IsEmpty || left.Overlaps(right))
                throw new ArgumentException("Canais de captura inválidos.");
            lock (controle)
                fixed (float* e = left, d = right)
                {
                    var n = ReadStream(handle, e, d, (uint)left.Length, out var position);
                    return (n == 0 ? -1 : position, (int)n);
                }
        }
        public void Dispose()
        {
            lock (controle)
            {
                if (handle.IsClosed) return;
                handle.Dispose();
                fechado();
            }
        }
    }

    public sealed class Restorer : IDisposable
    {
        private readonly RestorerHandle handle;
        public unsafe Restorer(uint rate, uint profile, float intensity)
        {
            if (!IsAvailable) throw new PlatformNotSupportedException("Motor de áudio nativo indisponível.");
            var config = new RestorerConfig { Size = (uint)sizeof(RestorerConfig), Rate = rate, Profile = profile, Intensity = intensity };
            handle = CreateRestorer(in config);
            if (handle.IsInvalid) throw new ArgumentOutOfRangeException(nameof(intensity), "Configuração de restauração inválida.");
        }

        public unsafe void Process(Span<float> left, Span<float> right = default)
        {
            if (left.IsEmpty || (!right.IsEmpty && (right.Length != left.Length || left.Overlaps(right))))
                throw new ArgumentException("Canais de restauração inválidos.");
            fixed (float* e = left, d = right) Conferir(Restore(handle, e, d, (uint)left.Length));
        }

        public float Gain { get { Conferir(MatchingGain(handle, out var gain)); return gain; } }
        public void Dispose() => handle.Dispose();
    }
}
