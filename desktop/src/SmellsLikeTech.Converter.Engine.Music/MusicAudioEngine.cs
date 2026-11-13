using System.Collections.Concurrent;
using System.Runtime.InteropServices;
using NAudio.CoreAudioApi;
using NAudio.Dsp;
using NAudio.Wave;
using SmellsLikeTech.Converter.Core.Music;

namespace SmellsLikeTech.Converter.Engine.Music;

public sealed record MusicDevice(string Id, string Name);

public sealed record MusicInputInfo(string Name, int Channels, int DeviceRate, int LatencyFrames);

public sealed record MusicTake(long AlignedFrame, long Frames, int Channels);

/// <summary>
/// O motor de audio da criacao musical no Windows, direto no WASAPI compartilhado (pelas
/// interfaces COM do NAudio). A saida e orientada a evento, a 48 kHz float (o Windows
/// converte para o formato do dispositivo); o transporte anda em quadros entregues ao
/// dispositivo, nunca por timers.
///
/// Alinhamento da tomada: cada pacote capturado traz o instante (QPC) em que o primeiro
/// quadro foi gravado, e o IAudioClock diz que quadro da saida estava soando em cada instante.
/// O primeiro quadro gravado ganha, assim, o quadro do transporte que a pessoa ouvia naquele
/// momento: os buffers do Windows ja ficam descontados, e sobra so o atraso dos conversores,
/// que a calibracao mede. Na thread de audio nada de disco nem travas: a tomada vai por fila
/// para quem escreve o WAV.
/// </summary>
public sealed class MusicAudioEngine : IDisposable
{
    public static bool UsesNativeOutput => Environment.GetEnvironmentVariable("SLT_MUSIC_OUTPUT") is "wasapi" or "asio";
    private readonly int rate;
    private readonly MusicMixer mixer;
    private readonly ConcurrentQueue<Action> commands = new();
    private readonly float[] left = new float[16384];
    private readonly float[] right = new float[16384];
    private readonly float[] inLeft = new float[16384];
    private readonly float[] inRight = new float[16384];
    private readonly InputRing ring;
    private readonly MusicMixOptions withClick;
    private readonly MusicMixOptions withoutClick;
    private Thread? renderThread;
    private Thread? captureThread;
    private volatile bool running;
    private volatile bool capturing;
    private MusicProject? pendingProject;
    private MusicProject? project;
    private long cursor;
    private volatile bool playing;
    private bool metronome;
    private volatile bool monitorStopped;
    private Recording? recording;
    private Dictionary<string, float[]> meters = [];
    private int blocks;
    private readonly object nativeGate = new();
    private MusicNative.Mixer? nativeMixer;
    private MusicNative.Stream? nativeStream;
    private string? selectedOutput;
    private int selectedLatency;
    private MusicProject? nativeProject;
    private readonly Dictionary<string, IMusicSource> nativeSources = [];
    private readonly bool nativeRequested = UsesNativeOutput;
    // Ultima leitura do relogio da saida: quadros que ja soaram e o instante (100 ns). O par vai
    // numa referencia so: com dois campos, a captura podia ler o quadro novo com o instante velho
    // e errar um periodo inteiro (~10 ms) na tomada, o que aparecia com o PC carregado.
    private sealed record ClockPoint(long Frames, long At);
    private ClockPoint? played;

    private sealed class Recording(int channels, Action<float[]> write)
    {
        public int Channels { get; } = channels;
        public Action<float[]> Write { get; } = write;
        public long FirstWritten = -1;
        public long FirstTransport;
        public long Aligned = long.MinValue;
        public long Frames;
        public TaskCompletionSource<MusicTake> Done { get; } = new(TaskCreationOptions.RunContinuationsAsynchronously);
    }

    public MusicAudioEngine(int sampleRate = MusicRules.DefaultSampleRate)
    {
        rate = sampleRate;
        mixer = new MusicMixer(sampleRate);
        ring = new InputRing(sampleRate);
        withClick = new MusicMixOptions { InputLeft = inLeft, InputRight = inRight, Metronome = true };
        withoutClick = new MusicMixOptions { InputLeft = inLeft, InputRight = inRight, Metronome = false };
    }

    public long Position
    {
        get { lock (nativeGate) return nativeMixer?.Position ?? Interlocked.Read(ref cursor); }
    }
    public bool Playing => playing;
    public bool IsUsingNativeOutput { get { lock (nativeGate) return nativeStream is not null; } }
    public bool IsRecording => recording is not null;
    public MusicInputInfo? Input { get; private set; }
    public string? OutputName { get; private set; }

    /// <summary>Buffer da saida em milissegundos (informativo: a tomada ja o desconta).</summary>
    public double OutputBufferMs { get; private set; }

    /// <summary>Atraso que sobra depois do alinhamento pelos relogios: so os conversores (desconhecido sem calibrar).</summary>
    public int OutputLatencyFrames => 0;

    public IReadOnlyDictionary<string, float[]> Meters
    {
        get
        {
            lock (nativeGate)
            {
                if (nativeMixer is null || nativeProject is null) return Volatile.Read(ref meters);
                var snapshot = new Dictionary<string, float[]> { ["master"] = nativeMixer.Meters("master") };
                foreach (var track in nativeProject.Tracks) snapshot.TryAdd(track.Id, nativeMixer.Meters(track.Id));
                foreach (var bus in nativeProject.Buses) snapshot.TryAdd(bus.Id, nativeMixer.Meters(bus.Id));
                return snapshot;
            }
        }
    }

    public static IReadOnlyList<MusicDevice> Outputs() => Devices(DataFlow.Render);

    public static IReadOnlyList<MusicDevice> Inputs() => Devices(DataFlow.Capture);

    private static List<MusicDevice> Devices(DataFlow flow)
    {
        using var enumerator = new MMDeviceEnumerator();
        return [.. enumerator.EnumerateAudioEndPoints(flow, DeviceState.Active).Select(d => new MusicDevice(d.ID, d.FriendlyName))];
    }

    // ==================== saída ====================

    public void Start(string? outputId = null, int latencyMs = 20) => Start(outputId, latencyMs, true);

    private void Start(string? outputId, int latencyMs, bool allowNative)
    {
        lock (nativeGate)
        {
            selectedOutput = outputId;
            selectedLatency = latencyMs;
            StopNative();
        }
        StopRender();
        var enumerator = new MMDeviceEnumerator();
        var device = outputId is null ? enumerator.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia) : enumerator.GetDevice(outputId);
        OutputName = device.FriendlyName;
        if (allowNative && Input is null && !playing && nativeRequested && MusicNative.IsAvailable)
        {
            lock (nativeGate)
            {
                try
                {
                    nativeMixer = new MusicNative.Mixer(rate);
                    foreach (var (id, source) in nativeSources) CopyNativeSource(id, source);
                    if (nativeProject is not null) PublishNative(nativeProject);
                    var asio = Environment.GetEnvironmentVariable("SLT_MUSIC_OUTPUT") == "asio";
                    nativeStream = nativeMixer.Open(asio ? 2u : 1u,
                        asio ? Environment.GetEnvironmentVariable("SLT_MUSIC_ASIO_DEVICE") : outputId,
                        bufferFrames: (uint)Math.Max(0, latencyMs * (long)rate / 1000));
                    nativeStream.Start();
                    OutputBufferMs = nativeStream.Info.BufferFrames * 1000.0 / rate;
                    if (asio) OutputName = "ASIO";
                    enumerator.Dispose();
                    return;
                }
                catch (Exception e) when (e is InvalidOperationException or IOException)
                {
                    StopNative();
                }
            }
        }
        var client = device.AudioClient;
        var format = WaveFormat.CreateIeeeFloatWaveFormat(rate, 2);
        client.Initialize(AudioClientShareMode.Shared, AudioClientStreamFlags.EventCallback | AudioClientStreamFlags.AutoConvertPcm | AudioClientStreamFlags.SrcDefaultQuality, latencyMs * 10_000L, 0, format, Guid.Empty);
        OutputBufferMs = client.BufferSize * 1000.0 / rate;
        running = true;
        renderThread = new Thread(() => RenderLoop(client, enumerator)) { IsBackground = true, Priority = ThreadPriority.Highest, Name = "Criação musical: saída" };
        renderThread.Start();
    }

    private void RenderLoop(AudioClient client, MMDeviceEnumerator enumerator)
    {
        using var ready = new AutoResetEvent(false);
        client.SetEventHandle(ready.SafeWaitHandle.DangerousGetHandle());
        var render = client.AudioRenderClient;
        var clock = client.AudioClockClient;
        var frequency = (double)clock.Frequency;
        var size = client.BufferSize;
        var scratch = new float[size * 2];
        long written = 0;
        try
        {
            client.Start();
            while (running)
            {
                ready.WaitOne(200);
                var available = size - client.CurrentPadding;
                if (available <= 0) continue;
                var start = Render(scratch, available);
                var buffer = render.GetBuffer(available);
                Marshal.Copy(scratch, 0, buffer, available * 2);
                render.ReleaseBuffer(available, AudioClientBufferFlags.None);
                var r = recording;
                if (r is not null && r.FirstWritten < 0 && start >= 0)
                {
                    r.FirstTransport = start;
                    r.FirstWritten = written;
                }

                written += available;
                clock.GetPosition(out var position, out var qpc);
                Volatile.Write(ref played, new ClockPoint((long)(position / frequency * rate), (long)qpc));
            }
        }
        catch (COMException)
        {
            // Dispositivo removido ou trocado: a tela avisa e reabre.
            running = false;
        }
        finally
        {
            try { client.Stop(); } catch (COMException) { }
            client.Dispose();
            enumerator.Dispose();
        }
    }

    /// <summary>Quadro de escrita que estava soando no instante `qpc` (100 ns).</summary>
    private long PlayedIndexAt(long qpc)
    {
        var point = Volatile.Read(ref played);
        if (point is null) return 0;
        return point.Frames + MusicRules.Round((qpc - point.At) / 1e7 * rate);
    }

    public void SetProject(MusicProject value)
    {
        var copia = value.Clone();
        Volatile.Write(ref pendingProject, copia);
        lock (nativeGate)
        {
            nativeProject = copia;
            if (nativeMixer is not null)
            {
                try { PublishNative(copia); }
                catch (InvalidOperationException) { UseManagedOutput(); }
            }
        }
    }

    public void SetSource(string media, IMusicSource? source)
    {
        commands.Enqueue(() => mixer.SetSource(media, source));
        if (!nativeRequested) return;
        lock (nativeGate)
        {
            if (source is null) nativeSources.Remove(media);
            else nativeSources[media] = source;
            if (nativeMixer is null) return;
            try
            {
                if (source is null) nativeMixer.RemoveSource(media);
                else CopyNativeSource(media, source);
                if (nativeProject is not null) nativeMixer.Refresh();
            }
            catch (Exception e) when (e is InvalidOperationException or IOException)
            {
                UseManagedOutput();
            }
        }
    }

    private void CopyNativeSource(string id, IMusicSource source)
    {
        if (source.Frames <= 0 || source.Frames > 4L * 60 * rate)
            throw new InvalidOperationException("Fonte acima do limite do mixer nativo experimental.");
        var l = new float[(int)source.Frames];
        source.Copy(0, 0, l.Length, l);
        if (source.Channels == 1)
        {
            nativeMixer!.SetSource(id, l);
            return;
        }
        var r = new float[l.Length];
        source.Copy(1, 0, r.Length, r);
        nativeMixer!.SetSource(id, l, r);
    }

    public void Play(long from, bool withMetronome)
    {
        lock (nativeGate)
        {
            if (nativeMixer is not null)
            {
                nativeMixer.SetMetronome(withMetronome);
                metronome = withMetronome;
                nativeMixer.Play(Math.Max(0, from));
                playing = true;
                return;
            }
        }
        commands.Enqueue(() =>
        {
            Interlocked.Exchange(ref cursor, Math.Max(0, from));
            metronome = withMetronome;
            mixer.SetCountIn([], -1);
            playing = true;
        });
    }

    public void Stop()
    {
        lock (nativeGate)
        {
            if (nativeMixer is not null)
            {
                nativeMixer.Stop();
                playing = false;
                return;
            }
        }
        commands.Enqueue(() =>
        {
            playing = false;
            mixer.SetCountIn([], -1);
            var r = recording;
            recording = null;
            if (r is not null) r.Done.TrySetResult(new MusicTake(r.Aligned == long.MinValue ? Position : r.Aligned, r.Frames, r.Channels));
        });
    }

    public void Seek(long frame)
    {
        lock (nativeGate)
        {
            if (nativeMixer is not null)
            {
                nativeMixer.Play(Math.Max(0, frame));
                if (!playing) nativeMixer.Stop();
                return;
            }
        }
        commands.Enqueue(() => Interlocked.Exchange(ref cursor, Math.Max(0, frame)));
    }

    private void PublishNative(MusicProject value)
    {
        nativeMixer!.SetProject(value);
        nativeMixer.SetLoop(value.Loop.Start, value.Loop.Enabled ? value.Loop.End : value.Loop.Start);
    }

    private void StopNative()
    {
        if (nativeMixer is null) return;
        Interlocked.Exchange(ref cursor, nativeMixer.Position);
        nativeMixer.Dispose();
        nativeMixer = null;
        nativeStream = null;
    }

    private void UseManagedOutput()
    {
        lock (nativeGate)
        {
            if (nativeMixer is null) return;
            StopNative();
            if (playing) Play(Interlocked.Read(ref cursor), metronome);
        }
        Start(selectedOutput, selectedLatency, allowNative: false);
    }

    public void MonitorWhileStopped(bool enabled) => monitorStopped = enabled;

    // ==================== entrada ====================

    public MusicInputInfo OpenInput(string? inputId, int channels)
    {
        UseManagedOutput();
        CloseInput();
        var enumerator = new MMDeviceEnumerator();
        var device = inputId is null ? enumerator.GetDefaultAudioEndpoint(DataFlow.Capture, Role.Communications) : enumerator.GetDevice(inputId);
        return StartCapture(device, enumerator, loopback: false, device.FriendlyName);
    }

    /// <summary>Grava o som que sai do computador (loopback): áudio de outro programa, ou conferir a saída sem microfone.</summary>
    public MusicInputInfo OpenLoopbackInput()
    {
        UseManagedOutput();
        CloseInput();
        var enumerator = new MMDeviceEnumerator();
        var device = enumerator.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia);
        return StartCapture(device, enumerator, loopback: true, "Som do computador (loopback)");
    }

    private MusicInputInfo StartCapture(MMDevice device, MMDeviceEnumerator enumerator, bool loopback, string name)
    {
        var client = device.AudioClient;
        var format = client.MixFormat;
        client.Initialize(AudioClientShareMode.Shared, loopback ? AudioClientStreamFlags.Loopback : AudioClientStreamFlags.None, 1_000_000, 0, format, Guid.Empty);
        WdlResampler? resampler = null;
        if (format.SampleRate != rate)
        {
            resampler = new WdlResampler();
            resampler.SetMode(true, 2, false);
            resampler.SetFilterParms();
            resampler.SetFeedMode(true);
            resampler.SetRates(format.SampleRate, rate);
        }

        capturing = true;
        captureThread = new Thread(() => CaptureLoop(client, enumerator, format, resampler)) { IsBackground = true, Priority = ThreadPriority.AboveNormal, Name = "Criação musical: entrada" };
        captureThread.Start();
        Input = new MusicInputInfo(name, Math.Min(2, format.Channels), format.SampleRate, 0);
        return Input;
    }

    public void CloseInput()
    {
        capturing = false;
        captureThread?.Join(1000);
        captureThread = null;
        Input = null;
    }

    private void CaptureLoop(AudioClient client, MMDeviceEnumerator enumerator, WaveFormat format, WdlResampler? resampler)
    {
        var capture = client.AudioCaptureClient;
        var bytesPerFrame = format.BlockAlign;
        var buffer = new byte[bytesPerFrame * format.SampleRate];
        try
        {
            client.Start();
            while (capturing)
            {
                Thread.Sleep(4);
                while (capture.GetNextPacketSize() > 0)
                {
                    var data = capture.GetBuffer(out var frames, out var flags, out _, out var qpc);
                    var bytes = frames * bytesPerFrame;
                    if (buffer.Length < bytes) buffer = new byte[bytes];
                    if ((flags & AudioClientBufferFlags.Silent) != 0) Array.Clear(buffer, 0, bytes);
                    else Marshal.Copy(data, buffer, 0, bytes);
                    capture.ReleaseBuffer(frames);
                    OnCaptured(buffer, bytes, format, resampler, qpc);
                }
            }
        }
        catch (COMException)
        {
            capturing = false;
        }
        finally
        {
            try { client.Stop(); } catch (COMException) { }
            client.Dispose();
            enumerator.Dispose();
        }
    }

    private void OnCaptured(byte[] buffer, int bytes, WaveFormat format, WdlResampler? resampler, long qpc)
    {
        var channels = format.Channels;
        int frames;
        float[] stereo;
        if (format.Encoding == WaveFormatEncoding.IeeeFloat || (format.Encoding == WaveFormatEncoding.Extensible && format.BitsPerSample == 32))
        {
            frames = bytes / (4 * channels);
            stereo = Stereo(MemoryMarshal.Cast<byte, float>(buffer.AsSpan(0, bytes)), channels, frames);
        }
        else
        {
            frames = bytes / (2 * channels);
            var shorts = MemoryMarshal.Cast<byte, short>(buffer.AsSpan(0, bytes));
            var temp = new float[frames * channels];
            for (var i = 0; i < temp.Length; i++) temp[i] = shorts[i] / 32768f;
            stereo = Stereo(temp, channels, frames);
        }

        if (resampler is not null)
        {
            var needed = resampler.ResamplePrepare(frames, 2, out var inBuffer, out var inOffset);
            Array.Copy(stereo, 0, inBuffer, inOffset, needed * 2);
            var outFrames = (int)(frames * (double)rate / format.SampleRate) + 16;
            var result = new float[outFrames * 2];
            frames = resampler.ResampleOut(result, 0, needed, outFrames, 2);
            stereo = result;
        }

        ring.Write(stereo, frames);
        var r = recording;
        if (r is null || !playing || r.FirstWritten < 0) return;
        if (r.Aligned == long.MinValue) r.Aligned = r.FirstTransport + (PlayedIndexAt(qpc) - r.FirstWritten);
        var take = new float[frames * r.Channels];
        for (var i = 0; i < frames; i++)
        {
            if (r.Channels == 1) take[i] = stereo[i * 2];
            else
            {
                take[i * 2] = stereo[i * 2];
                take[i * 2 + 1] = stereo[i * 2 + 1];
            }
        }

        r.Frames += frames;
        r.Write(take);
    }

    private static float[] Stereo(ReadOnlySpan<float> data, int channels, int frames)
    {
        var result = new float[frames * 2];
        for (var i = 0; i < frames; i++)
        {
            result[i * 2] = data[i * channels];
            result[i * 2 + 1] = channels > 1 ? data[i * channels + 1] : data[i * channels];
        }

        return result;
    }

    // ==================== gravação ====================

    public Task<MusicTake> RecordAsync(MusicRecordingPlan plan, int channels, bool withMetronome, Action<float[]> write)
    {
        if (!capturing) throw new InvalidOperationException("Escolha a entrada de áudio antes de gravar.");
        var r = new Recording(channels, write);
        commands.Enqueue(() =>
        {
            Interlocked.Exchange(ref cursor, plan.TransportStart);
            metronome = withMetronome;
            mixer.SetCountIn(plan.CountInClicks, plan.TakeStart);
            ring.Clear();
            recording = r;
            playing = true;
        });
        return r.Done.Task;
    }

    public async Task<(float[] Recorded, long Aligned)> CaptureClicksAsync(IReadOnlyList<long> pulses, int durationMs)
    {
        var empty = MusicRules.NewProject("calibracao", "Calibração", rate);
        empty.Metronome.VolumeDb = 0;
        var saved = project;
        SetProject(empty);
        var parts = new List<float[]>();
        var plan = new MusicRecordingPlan(0, long.MaxValue / 4, null, [.. pulses.Select(p => new MusicClick(p, true))], 0);
        var task = RecordAsync(plan, 1, false, parts.Add);
        await Task.Delay(durationMs);
        Stop();
        var take = await task;
        if (saved is not null) SetProject(saved);
        return ([.. parts.SelectMany(p => p)], take.AlignedFrame);
    }

    /// <summary>Preenche `interleaved` e devolve o quadro do transporte do bloco (−1 parado).</summary>
    private long Render(float[] interleaved, int frames)
    {
        while (commands.TryDequeue(out var command)) command();
        var next = Interlocked.Exchange(ref pendingProject, null);
        if (next is not null)
        {
            project = next;
            mixer.SetProject(next);
        }

        var blockStart = playing && project is not null ? Interlocked.Read(ref cursor) : -1;
        var done = 0;
        while (done < frames)
        {
            var n = Math.Min(left.Length, frames - done);
            ring.Read(inLeft, inRight, n, monitorStopped || playing);
            if (project is null || !playing)
            {
                for (var i = 0; i < n; i++)
                {
                    interleaved[(done + i) * 2] = monitorStopped ? inLeft[i] : 0;
                    interleaved[(done + i) * 2 + 1] = monitorStopped ? inRight[i] : 0;
                }
            }
            else
            {
                var start = Interlocked.Read(ref cursor);
                mixer.Process(start, n, left, right, metronome ? withClick : withoutClick);
                for (var i = 0; i < n; i++)
                {
                    interleaved[(done + i) * 2] = left[i];
                    interleaved[(done + i) * 2 + 1] = right[i];
                }

                var after = start + n;
                if (recording is null && project.Loop.Enabled && project.Loop.End > project.Loop.Start && after >= project.Loop.End) after = project.Loop.Start;
                Interlocked.Exchange(ref cursor, after);
                if (++blocks % 3 == 0)
                {
                    var snapshot = new Dictionary<string, float[]>(mixer.Meters.Count);
                    foreach (var (id, m) in mixer.Meters) snapshot[id] = (float[])m.Clone();
                    Volatile.Write(ref meters, snapshot);
                }
            }

            done += n;
        }

        return blockStart;
    }

    private void StopRender()
    {
        running = false;
        renderThread?.Join(1000);
        renderThread = null;
    }

    public void Dispose()
    {
        Stop();
        CloseInput();
        StopRender();
        lock (nativeGate) StopNative();
        nativeSources.Clear();
    }

    /// <summary>Anel de um produtor (captura) e um consumidor (saída) para o monitoramento.</summary>
    private sealed class InputRing(int rate)
    {
        private readonly float[] data = new float[rate * 2];
        private long written;
        private long read;

        public void Clear() => Interlocked.Exchange(ref read, Interlocked.Read(ref written));

        public void Write(float[] stereo, int frames)
        {
            var size = data.Length / 2;
            var w = Interlocked.Read(ref written);
            for (var i = 0; i < frames; i++)
            {
                var k = (int)((w + i) % size) * 2;
                data[k] = stereo[i * 2];
                data[k + 1] = stereo[i * 2 + 1];
            }

            Interlocked.Exchange(ref written, w + frames);
        }

        public void Read(float[] l, float[] r, int n, bool consume)
        {
            var size = data.Length / 2;
            var w = Interlocked.Read(ref written);
            var p = Interlocked.Read(ref read);
            if (w - p > size) p = w - size;
            var available = (int)Math.Min(n, w - p);
            for (var i = 0; i < available; i++)
            {
                var k = (int)((p + i) % size) * 2;
                l[i] = data[k];
                r[i] = data[k + 1];
            }

            Array.Clear(l, available, n - available);
            Array.Clear(r, available, n - available);
            Interlocked.Exchange(ref read, consume ? p + available : w);
        }
    }
}
