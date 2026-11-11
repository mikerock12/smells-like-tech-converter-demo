namespace SmellsLikeTech.Converter.Core.Music;

/// <summary>Fonte de audio de uma midia, lida em pedacos (memoria, arquivo mapeado...).</summary>
public interface IMusicSource
{
    int Channels { get; }
    long Frames { get; }

    /// <summary>Copia n quadros do canal a partir de start; o que passar do fim vira zero.</summary>
    void Copy(int channel, long start, int n, float[] destination);
}

/// <summary>Midia inteira na memoria: um float[] por canal.</summary>
public sealed class MemoryMusicSource(float[][] channels) : IMusicSource
{
    public int Channels => channels.Length;
    public long Frames => channels.Length == 0 ? 0 : channels[0].Length;

    public void Copy(int channel, long start, int n, float[] destination)
    {
        var source = channels[Math.Min(channel, channels.Length - 1)];
        var i = 0;
        for (var j = start; j < Math.Min(source.Length, start + n); j++) destination[i++] = source[j];
        for (; i < n; i++) destination[i] = 0;
    }
}

public sealed class MusicMixOptions
{
    public float[]? InputLeft { get; init; }
    public float[]? InputRight { get; init; }
    public string? Only { get; init; }
    public bool WithoutMaster { get; init; }
    public bool Metronome { get; init; }
}

/// <summary>
/// O mixer da criacao musical, espelho de musica-mixer.mjs. Toca em tempo real e exporta;
/// depois de <see cref="SetProject"/>, <see cref="Process"/> nao aloca.
/// Nao e seguro entre threads: quem chama troca o projeto entre dois blocos.
/// </summary>
public sealed class MusicMixer
{
    private const double ClickDuration = 0.03;
    private readonly int rate;
    private readonly int block;
    private readonly Dictionary<string, IMusicSource> sources = [];
    private readonly Dictionary<string, MusicChain> chains = [];
    private readonly Dictionary<string, (float[] L, float[] R)> buses = [];
    private readonly float[] tl, tr, ll, lr, ml, mr;
    private readonly float[] strongClick, weakClick;
    private HashSet<string> audible = [];
    private MusicProject? project;
    private IReadOnlyList<MusicClick> countInClicks = [];
    private long countInEnd = -1;

    public MusicMixer(int sampleRate, int block = MusicRules.Block)
    {
        rate = sampleRate;
        this.block = block;
        tl = new float[block]; tr = new float[block]; ll = new float[block]; lr = new float[block]; ml = new float[block]; mr = new float[block];
        strongClick = ClickSound(sampleRate, true);
        weakClick = ClickSound(sampleRate, false);
    }

    /// <summary>Pico L, pico R, RMS L, RMS R do ultimo Process, por faixa, barramento e "master".</summary>
    public Dictionary<string, float[]> Meters { get; } = [];

    public static float[] ClickSound(int rate, bool strong)
    {
        var n = (int)MusicRules.Round(rate * ClickDuration);
        var sound = new float[n];
        var f = strong ? 2000 : 1500;
        for (var i = 0; i < n; i++) sound[i] = (float)(Math.Sin(2 * Math.PI * f * i / rate) * Math.Exp(-i / (rate * 0.006)) * (strong ? 1 : 0.7));
        return sound;
    }

    public void SetSource(string media, IMusicSource? source)
    {
        if (source is null) sources.Remove(media);
        else sources[media] = source;
    }

    public void SetCountIn(IReadOnlyList<MusicClick> clicks, long end)
    {
        countInClicks = clicks;
        countInEnd = end;
    }

    public void SetProject(MusicProject value)
    {
        project = value;
        audible = [.. MusicRules.AudibleTracks(value)];
        var alive = new HashSet<string> { "master" };
        void Chain(string id, List<MusicEffect> effects)
        {
            alive.Add(id);
            if (!chains.TryGetValue(id, out var chain))
            {
                chain = new MusicChain(rate);
                chains[id] = chain;
            }

            chain.Sync(effects);
            if (!Meters.ContainsKey(id)) Meters[id] = new float[4];
        }

        foreach (var track in value.Tracks) Chain(track.Id, track.Effects);
        foreach (var bus in value.Buses)
        {
            Chain(bus.Id, bus.Effects);
            if (!buses.ContainsKey(bus.Id)) buses[bus.Id] = (new float[block], new float[block]);
        }

        Chain("master", value.Master.Effects);
        foreach (var id in chains.Keys.Where(k => !alive.Contains(k)).ToList()) chains.Remove(id);
        foreach (var id in Meters.Keys.Where(k => !alive.Contains(k)).ToList()) Meters.Remove(id);
        foreach (var id in buses.Keys.Where(k => value.Buses.All(b => b.Id != k)).ToList()) buses.Remove(id);
    }

    public void Process(long start, int n, float[] outLeft, float[] outRight, MusicMixOptions? options = null)
    {
        options ??= Default;
        foreach (var m in Meters.Values) Array.Clear(m);
        var done = 0;
        while (done < n)
        {
            var k = Math.Min(block, n - done);
            Block(start + done, k, options, done);
            Array.Copy(ml, 0, outLeft, done, k);
            Array.Copy(mr, 0, outRight, done, k);
            done += k;
        }

        foreach (var m in Meters.Values)
        {
            m[2] = (float)Math.Sqrt(m[2] / Math.Max(1, n));
            m[3] = (float)Math.Sqrt(m[3] / Math.Max(1, n));
        }
    }

    private static readonly MusicMixOptions Default = new();

    private void Measure(string id, float[] l, float[] r, int n)
    {
        if (!Meters.TryGetValue(id, out var m)) return;
        double s2 = m[2], s3 = m[3];
        for (var i = 0; i < n; i++)
        {
            var a = Math.Abs((double)l[i]);
            var b = Math.Abs((double)r[i]);
            if (a > m[0]) m[0] = (float)a;
            if (b > m[1]) m[1] = (float)b;
            s2 += (double)l[i] * l[i];
            s3 += (double)r[i] * r[i];
        }

        m[2] = (float)s2;
        m[3] = (float)s3;
    }

    private void Block(long t0, int n, MusicMixOptions options, int inputOffset)
    {
        var p = project!;
        Array.Clear(ml, 0, n);
        Array.Clear(mr, 0, n);
        foreach (var (l, r) in buses.Values)
        {
            Array.Clear(l, 0, n);
            Array.Clear(r, 0, n);
        }

        foreach (var track in p.Tracks)
        {
            var isAudible = options.Only is not null ? track.Id == options.Only : audible.Contains(track.Id);
            if (!isAudible) continue;
            Array.Clear(tl, 0, n);
            Array.Clear(tr, 0, n);
            foreach (var clip in track.Clips)
            {
                if (clip.Mute) continue;
                var a = Math.Max(t0, clip.Start);
                var b = Math.Min(t0 + n, clip.Start + clip.Duration);
                if (a >= b) continue;
                var transformed = sources.TryGetValue(MusicTransform.SourceKey(clip), out var prepared) ? prepared : null;
                var source = transformed ?? (sources.TryGetValue(clip.Media, out var original) ? original : null);
                if (source is null) continue;
                var offset = a - clip.Start;
                var k = (int)(b - a);
                var origin = transformed is not null ? (long)Math.Floor(clip.SourceStart / (clip.Speed ?? 1)) + offset : clip.SourceStart + offset;
                source.Copy(0, origin, k, ll);
                if (source.Channels > 1) source.Copy(1, origin, k, lr);
                else Array.Copy(ll, lr, k);
                var dest = (int)(a - t0);
                for (var i = 0; i < k; i++)
                {
                    var f = MusicRules.ClipEnvelope(clip, offset + i);
                    tl[dest + i] = (float)(tl[dest + i] + ll[i] * f);
                    tr[dest + i] = (float)(tr[dest + i] + lr[i] * f);
                }
            }

            if (options.InputLeft is not null && options.InputRight is not null && track.Arm && track.Monitor)
            {
                for (var i = 0; i < n; i++)
                {
                    tl[i] = (float)((double)tl[i] + options.InputLeft[inputOffset + i]);
                    tr[i] = (float)((double)tr[i] + options.InputRight[inputOffset + i]);
                }
            }

            if (chains.TryGetValue(track.Id, out var chain)) chain.Process(tl, tr, n);
            foreach (var send in track.Sends)
            {
                if (!send.PreFader || !buses.TryGetValue(send.Bus, out var bus)) continue;
                var g = MusicRules.DbToLinear(send.LevelDb);
                for (var i = 0; i < n; i++)
                {
                    bus.L[i] = (float)(bus.L[i] + tl[i] * g);
                    bus.R[i] = (float)(bus.R[i] + tr[i] * g);
                }
            }

            var volume = track.Automation.Volume;
            var pan = track.Automation.Pan;
            var (l0, r0) = MusicRules.StereoGains(MusicRules.AutomationValue(volume, t0, track.GainDb), MusicRules.AutomationValue(pan, t0, track.Pan), track.LeftTrimDb, track.RightTrimDb);
            var (l1, r1) = MusicRules.StereoGains(MusicRules.AutomationValue(volume, t0 + n, track.GainDb), MusicRules.AutomationValue(pan, t0 + n, track.Pan), track.LeftTrimDb, track.RightTrimDb);
            for (var i = 0; i < n; i++)
            {
                var w = i / (double)n;
                tl[i] = (float)(tl[i] * (l0 + (l1 - l0) * w));
                tr[i] = (float)(tr[i] * (r0 + (r1 - r0) * w));
            }

            foreach (var send in track.Sends)
            {
                if (send.PreFader || !buses.TryGetValue(send.Bus, out var bus)) continue;
                var g = MusicRules.DbToLinear(send.LevelDb);
                for (var i = 0; i < n; i++)
                {
                    bus.L[i] = (float)(bus.L[i] + tl[i] * g);
                    bus.R[i] = (float)(bus.R[i] + tr[i] * g);
                }
            }

            var (dl, dr) = track.Output != "master" && buses.TryGetValue(track.Output, out var target) ? target : (ml, mr);
            for (var i = 0; i < n; i++)
            {
                dl[i] = (float)((double)dl[i] + tl[i]);
                dr[i] = (float)((double)dr[i] + tr[i]);
            }

            Measure(track.Id, tl, tr, n);
        }

        foreach (var bus in p.Buses)
        {
            if (bus.Mute || !buses.TryGetValue(bus.Id, out var buffers)) continue;
            var (l, r) = buffers;
            if (chains.TryGetValue(bus.Id, out var chain)) chain.Process(l, r, n);
            var (gl, gr) = MusicRules.StereoGains(bus.GainDb, bus.Pan);
            for (var i = 0; i < n; i++)
            {
                l[i] = (float)(l[i] * gl);
                r[i] = (float)(r[i] * gr);
                ml[i] = (float)((double)ml[i] + l[i]);
                mr[i] = (float)((double)mr[i] + r[i]);
            }

            Measure(bus.Id, l, r, n);
        }

        if (!options.WithoutMaster && chains.TryGetValue("master", out var master)) master.Process(ml, mr, n);
        var gm = MusicRules.DbToLinear(p.Master.GainDb);
        for (var i = 0; i < n; i++)
        {
            ml[i] = (float)(ml[i] * gm);
            mr[i] = (float)(mr[i] * gm);
        }

        Measure("master", ml, mr, n);
        if (options.Metronome || countInEnd > t0) Metronome(t0, n, options.Metronome);
    }

    private void Add(float[] sound, long clickFrame, long t0, int n, double volume)
    {
        var a = Math.Max(t0, clickFrame);
        var b = Math.Min(t0 + n, clickFrame + sound.Length);
        for (var t = a; t < b; t++)
        {
            var v = sound[t - clickFrame] * volume;
            ml[t - t0] = (float)(ml[t - t0] + v);
            mr[t - t0] = (float)(mr[t - t0] + v);
        }
    }

    private void Metronome(long t0, int n, bool grid)
    {
        var p = project!;
        var volume = MusicRules.DbToLinear(p.Metronome.VolumeDb);
        var length = strongClick.Length;
        foreach (var click in countInClicks)
        {
            if (click.Frame + length > t0 && click.Frame < t0 + n) Add(click.Strong ? strongClick : weakClick, click.Frame, t0, n, volume);
        }

        if (!grid) return;
        var perBeat = MusicRules.FramesPerBeat(p);
        var beats = p.Meter[0];
        var k = Math.Max(0, (long)Math.Floor((t0 - length) / perBeat));
        while (true)
        {
            var frame = MusicRules.Round(k * perBeat);
            if (frame >= t0 + n) break;
            if (frame >= countInEnd && frame + length > t0) Add(k % beats == 0 ? strongClick : weakClick, frame, t0, n, volume);
            k++;
        }
    }

    public static long TailInFrames(MusicProject project, int? rate = null)
    {
        double seconds = 0;
        void Look(IEnumerable<MusicEffect> effects)
        {
            foreach (var effect in effects) seconds = Math.Max(seconds, MusicDsp.Tail(effect));
        }

        foreach (var track in project.Tracks) Look(track.Effects);
        foreach (var bus in project.Buses) Look(bus.Effects);
        Look(project.Master.Effects);
        return MusicRules.Round(Math.Min(10, seconds) * (rate ?? project.SampleRate));
    }

    /// <summary>Exporta um trecho inteiro em memoria.</summary>
    public static (float[] Left, float[] Right) Render(MusicProject project, IReadOnlyDictionary<string, IMusicSource> sources, long start, int frames, string? only = null, bool withoutMaster = false, bool metronome = false)
    {
        var mixer = new MusicMixer(project.SampleRate);
        foreach (var (id, source) in sources) mixer.SetSource(id, source);
        mixer.SetProject(project);
        var left = new float[frames];
        var right = new float[frames];
        const int step = 4096;
        var bl = new float[step];
        var br = new float[step];
        var options = new MusicMixOptions { Only = only, WithoutMaster = withoutMaster, Metronome = metronome };
        for (var done = 0; done < frames; done += step)
        {
            var k = Math.Min(step, frames - done);
            mixer.Process(start + done, k, bl, br, options);
            Array.Copy(bl, 0, left, done, k);
            Array.Copy(br, 0, right, done, k);
        }

        return (left, right);
    }
}
