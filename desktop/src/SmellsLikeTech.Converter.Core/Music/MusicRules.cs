using System.Globalization;
using System.Text;
using System.Text.RegularExpressions;

namespace SmellsLikeTech.Converter.Core.Music;

public readonly record struct MusicClick(long Frame, bool Strong);

public readonly record struct MusicPosition(int Bar, int Beat, int Hundredths, string Text);

public sealed record MusicRecordingPlan(long TransportStart, long TakeStart, long? TakeEnd, IReadOnlyList<MusicClick> CountInClicks, long PreRoll);

/// <summary>
/// Regras da criacao musical, espelho de packages/converter-core/musica.mjs (mesmas contas e
/// mensagens), conferidas pelos casos de musica-casos/.
/// </summary>
public static partial class MusicRules
{
    public const int ProjectVersion = 1;
    public const int DefaultSampleRate = 48000;
    public static readonly int[] SampleRates = [44100, 48000, 96000];
    public const int Block = 128;
    public const double MinGainDb = -60;
    public const double MaxGainDb = 12;
    public const double MinTrimDb = -24;
    public const double MaxTrimDb = 12;
    public const double MinBpm = 30;
    public const double MaxBpm = 300;
    public static readonly string[] TrackColors = ["#FF7A18", "#1E88E5", "#43A047", "#E53935", "#8E24AA", "#FFD54A", "#00ACC1", "#F06292"];
    public static readonly int[] BitDepths = [16, 24, 32];

    public sealed record Template(string Id, string Label, string[] Tracks, string[] Buses);

    public static readonly IReadOnlyList<Template> Templates =
    [
        new("vazio", "Em branco", ["Faixa 1"], []),
        new("violao-e-voz", "Violão e voz", ["Violão", "Voz"], ["Reverb"]),
        new("duo-vocal", "Duo vocal", ["Base", "Voz 1", "Voz 2"], ["Vozes", "Reverb"]),
        new("banda", "Banda", ["Bateria", "Baixo", "Guitarra", "Teclado", "Voz"], ["Guitarras", "Reverb", "Delay"]),
        new("podcast", "Podcast", ["Apresentação", "Convidado", "Trilha"], []),
    ];

    [GeneratedRegex("^[a-z0-9][a-z0-9-]{0,63}$")] private static partial Regex IdPattern();
    [GeneratedRegex("^#[0-9A-Fa-f]{6}$")] private static partial Regex ColorPattern();

    /// <summary>Arredondamento do JS (Math.round): metade para cima.</summary>
    public static long Round(double value) => (long)Math.Floor(value + 0.5);

    /// <summary>Numero no formato do JS (sem ".0" nos inteiros).</summary>
    public static string Js(double value) => value.ToString("R", CultureInfo.InvariantCulture);

    public static double DbToLinear(double db) => !double.IsFinite(db) || db <= MinGainDb ? 0 : Math.Pow(10, db / 20);

    public static double LinearToDb(double gain) => gain > 0 ? 20 * Math.Log10(gain) : double.NegativeInfinity;

    public static double FramesPerBeat(MusicProject project) => project.SampleRate * 60 / project.Bpm * (4.0 / project.Meter[1]);

    public static long BeatFrame(MusicProject project, long k) => Round(k * FramesPerBeat(project));

    public static double FramesPerBar(MusicProject project) => FramesPerBeat(project) * project.Meter[0];

    public static MusicPosition Position(MusicProject project, long frame)
    {
        var perBeat = FramesPerBeat(project);
        var beats = Math.Max(0, frame) / perBeat;
        var whole = (long)Math.Floor(beats + 1e-9);
        var bar = (int)(whole / project.Meter[0]) + 1;
        var beat = (int)(whole % project.Meter[0]) + 1;
        var hundredths = (int)Math.Min(99, Math.Floor((beats - whole) * 100 + 1e-9));
        return new MusicPosition(bar, beat, hundredths, $"{bar}.{beat}.{Math.Max(0, hundredths):00}");
    }

    public static string TimeOfFrame(MusicProject project, long frame)
    {
        var ms = Round(Math.Max(0, frame) / (double)project.SampleRate * 1000);
        return $"{ms / 60000}:{ms % 60000 / 1000:00}.{ms % 1000:000}";
    }

    public static List<MusicClick> MetronomeClicks(MusicProject project, long start, long end)
    {
        var perBeat = FramesPerBeat(project);
        var clicks = new List<MusicClick>();
        var k = Math.Max(0, (long)Math.Ceiling(start / perBeat - 1e-9));
        while (true)
        {
            var frame = BeatFrame(project, k);
            if (frame >= end) break;
            if (frame >= start) clicks.Add(new MusicClick(frame, k % project.Meter[0] == 0));
            k++;
        }

        return clicks;
    }

    public static MusicTrack NewTrack(string id, string name, int index = 0) => new()
    {
        Id = id,
        Name = name,
        Color = TrackColors[index % TrackColors.Length]
    };

    public static MusicProject NewProject(string id, string title = "Nova música", int sampleRate = DefaultSampleRate) => new()
    {
        Id = id,
        Title = title,
        SampleRate = sampleRate
    };

    private static string IdFromName(string prefix, string name, HashSet<string> used)
    {
        var decomposed = name.Normalize(NormalizationForm.FormD);
        var clean = new StringBuilder();
        foreach (var ch in decomposed)
        {
            if (char.IsAsciiLetterOrDigit(ch) || ch == '_' || ch == '-' || char.IsWhiteSpace(ch)) clean.Append(ch);
        }

        var slug = Regex.Replace(clean.ToString().Trim().ToLowerInvariant(), "[\\s_]+", "-");
        var full = $"{prefix}-{slug}";
        var baseId = full.Length > 40 ? full[..40] : full;
        var id = baseId;
        for (var n = 2; used.Contains(id); n++) id = $"{baseId}-{n}";
        used.Add(id);
        return id;
    }

    public static MusicProject FromTemplate(string templateId, string id, string title, int sampleRate = DefaultSampleRate)
    {
        var template = Templates.FirstOrDefault(t => t.Id == templateId) ?? Templates[0];
        var project = NewProject(id, title, sampleRate);
        var used = new HashSet<string>();
        foreach (var name in template.Buses)
        {
            var bus = new MusicBus { Id = IdFromName("bus", name, used), Name = name };
            if (name == "Reverb")
            {
                var fx = new MusicEffect { Id = "fx-reverb", Kind = "reverberacao", Enabled = true };
                fx.Set("tamanho", 0.7); fx.Set("amortecimento", 0.4); fx.Set("largura", 1); fx.Set("mistura", 1);
                bus.Effects.Add(fx);
            }

            if (name == "Delay")
            {
                var fx = new MusicEffect { Id = "fx-delay", Kind = "atraso", Enabled = true };
                fx.Set("tempoMs", 375); fx.Set("retorno", 0.35); fx.Set("mistura", 1);
                bus.Effects.Add(fx);
            }

            project.Buses.Add(bus);
        }

        for (var i = 0; i < template.Tracks.Length; i++)
        {
            var name = template.Tracks[i];
            var track = NewTrack(IdFromName("faixa", name, used), name, i);
            var reverb = project.Buses.FirstOrDefault(b => b.Name == "Reverb");
            if (reverb is not null && Regex.IsMatch(name, "voz|viol", RegexOptions.IgnoreCase)) track.Sends.Add(new MusicSend { Bus = reverb.Id, LevelDb = -12 });
            var voices = project.Buses.FirstOrDefault(b => b.Name == "Vozes");
            if (voices is not null && Regex.IsMatch(name, "voz", RegexOptions.IgnoreCase)) track.Output = voices.Id;
            var guitars = project.Buses.FirstOrDefault(b => b.Name == "Guitarras");
            if (guitars is not null && Regex.IsMatch(name, "guitarra", RegexOptions.IgnoreCase)) track.Output = guitars.Id;
            project.Tracks.Add(track);
        }

        return project;
    }

    /// <summary>Com algum solo, so as faixas em solo; o mudo sempre vence.</summary>
    public static List<string> AudibleTracks(MusicProject project)
    {
        var anySolo = project.Tracks.Any(t => t.Solo);
        return [.. project.Tracks.Where(t => !t.Mute && (!anySolo || t.Solo)).Select(t => t.Id)];
    }

    /// <summary>Fader, pan de potencia constante sem reforco e ajuste L/R independente.</summary>
    public static (double Left, double Right) StereoGains(double gainDb, double pan, double leftTrimDb = 0, double rightTrimDb = 0)
    {
        var gain = DbToLinear(gainDb);
        var angle = (Math.Clamp(pan, -100, 100) + 100) / 200 * (Math.PI / 2);
        var left = Math.Min(1, Math.Sqrt(2) * Math.Cos(angle));
        var right = Math.Min(1, Math.Sqrt(2) * Math.Sin(angle));
        return (
            gain * left * (leftTrimDb == 0 ? 1 : Math.Pow(10, leftTrimDb / 20)),
            gain * right * (rightTrimDb == 0 ? 1 : Math.Pow(10, rightTrimDb / 20)));
    }

    public static double AutomationValue(IReadOnlyList<MusicAutomationPoint>? points, long frame, double fallback)
    {
        if (points is null || points.Count == 0) return fallback;
        if (frame <= points[0].Frame) return points[0].Value;
        var last = points[^1];
        if (frame >= last.Frame) return last.Value;
        for (var i = 1; i < points.Count; i++)
        {
            var b = points[i];
            if (frame <= b.Frame)
            {
                var a = points[i - 1];
                if (b.Frame == a.Frame) return b.Value;
                return a.Value + (b.Value - a.Value) * (frame - a.Frame) / (double)(b.Frame - a.Frame);
            }
        }

        return last.Value;
    }

    public static long DurationInFrames(MusicProject project)
    {
        long end = 0;
        foreach (var track in project.Tracks)
        foreach (var clip in track.Clips)
            end = Math.Max(end, clip.Start + clip.Duration);
        return end;
    }

    public static double ClipEnvelope(MusicClip clip, long offset)
    {
        var factor = clip.GainDb == 0 ? 1 : DbToLinear(clip.GainDb);
        if (clip.FadeIn > 0 && offset < clip.FadeIn) factor *= offset / (double)clip.FadeIn;
        var remaining = clip.Duration - offset;
        if (clip.FadeOut > 0 && remaining < clip.FadeOut) factor *= remaining / (double)clip.FadeOut;
        return factor;
    }

    public static MusicRecordingPlan RecordingPlan(MusicProject project, long cursor)
    {
        var countIn = (int)Math.Max(0, Math.Min(4, Math.Truncate(project.CountIn)));
        var preRoll = Round(countIn * FramesPerBar(project));
        var punch = project.Punch.Enabled && project.Punch.Out > project.Punch.In ? project.Punch : null;
        var takeStart = punch?.In ?? cursor;
        var transportStart = Math.Max(0, (punch is not null ? Math.Min(cursor, punch.In) : cursor) - preRoll);
        var clicks = new List<MusicClick>();
        if (countIn > 0)
        {
            var perBeat = FramesPerBeat(project);
            var beats = countIn * project.Meter[0];
            for (var k = 0; k < beats; k++)
            {
                var frame = takeStart - Round((beats - k) * perBeat);
                if (frame >= transportStart) clicks.Add(new MusicClick(frame, k % project.Meter[0] == 0));
            }
        }

        return new MusicRecordingPlan(transportStart, takeStart, punch?.Out, clicks, preRoll);
    }

    public static long CompensatedStart(long alignedFrame, MusicLatency latency) =>
        alignedFrame - Round(latency.Input) - Round(latency.Output) + Round(latency.Adjust ?? 0);

    public static MusicClip? ClipFromTake(MusicProject project, string id, string media, long alignedFrame, long capturedFrames, MusicRecordingPlan plan)
    {
        var position = CompensatedStart(alignedFrame, project.Latency);
        long sourceStart = 0;
        var start = position;
        var cut = Math.Max(plan.TakeStart, 0);
        if (start < cut)
        {
            sourceStart = cut - start;
            start = cut;
        }

        var end = position + capturedFrames;
        if (plan.TakeEnd is { } takeEnd) end = Math.Min(end, takeEnd);
        var duration = end - start;
        if (duration <= 0) return null;
        return new MusicClip { Id = id, Media = media, Start = start, SourceStart = sourceStart, Duration = duration };
    }

    /// <summary>A tomada nova entra; as que ela cobre ficam mudas (nunca apagadas).</summary>
    public static void AddTake(MusicTrack track, MusicClip clip)
    {
        var end = clip.Start + clip.Duration;
        foreach (var c in track.Clips)
        {
            if (!c.Mute && c.Start < end && c.Start + c.Duration > clip.Start) c.Mute = true;
        }

        track.Clips.Add(clip);
    }

    /// <summary>Latencia de ida e volta mediana pelos pulsos gravados, ou null.</summary>
    public static long? MeasureLatency(ReadOnlySpan<float> recorded, IReadOnlyList<long> pulses, int sampleRate)
    {
        double peak = 0;
        foreach (var v in recorded) peak = Math.Max(peak, Math.Abs((double)v));
        if (peak < 1e-3) return null;
        var threshold = peak * 0.5;
        var window = Round(sampleRate * 0.25);
        var delays = new List<long>();
        foreach (var pulse in pulses)
        {
            for (var i = Math.Max(0, pulse); i < Math.Min(recorded.Length, pulse + window); i++)
            {
                if (Math.Abs((double)recorded[(int)i]) >= threshold)
                {
                    delays.Add(i - pulse);
                    break;
                }
            }
        }

        if (delays.Count < (int)Math.Ceiling(pulses.Count / 2.0)) return null;
        delays.Sort();
        return delays[delays.Count / 2];
    }

    private static void EffectProblems(List<MusicEffect>? effects, string where, List<string> errors)
    {
        if (effects is null)
        {
            errors.Add($"{where}: lista de efeitos inválida.");
            return;
        }

        var ids = new HashSet<string>();
        foreach (var effect in effects)
        {
            if (!IdPattern().IsMatch(effect.Id ?? "") || !ids.Add(effect.Id!)) errors.Add($"{where}: efeito sem id válido.");
            var problem = MusicDsp.Validate(effect);
            if (problem is not null) errors.Add($"{where}: {problem}");
        }
    }

    public static List<string> Problems(MusicProject project)
    {
        var errors = new List<string>();
        if (project.Version != ProjectVersion) errors.Add($"Versão {project.Version} do projeto musical não suportada.");
        if (!IdPattern().IsMatch(project.Id ?? "")) errors.Add("O projeto precisa de um id.");
        if (!SampleRates.Contains(project.SampleRate)) errors.Add("Taxa de amostragem não suportada (44,1, 48 ou 96 kHz).");
        if (!BitDepths.Contains(project.BitDepth)) errors.Add("Profundidade de saída inválida (16, 24 ou 32 bits).");
        if (!double.IsFinite(project.Bpm) || project.Bpm < MinBpm || project.Bpm > MaxBpm) errors.Add($"O andamento vai de {Js(MinBpm)} a {Js(MaxBpm)} BPM.");
        if (project.Meter is not { Length: 2 } || project.Meter[0] < 1 || project.Meter[0] > 16 || project.Meter[1] is not (2 or 4 or 8)) errors.Add("Fórmula de compasso inválida.");
        var media = new Dictionary<string, MusicMedia>();
        foreach (var m in project.Media)
        {
            if (!IdPattern().IsMatch(m.Id ?? "") || media.ContainsKey(m.Id!)) errors.Add("Mídia sem id válido.");
            else media[m.Id!] = m;
            if (m.Frames < 0) errors.Add($"Mídia {m.Id}: duração inválida.");
            if (m.Channels is not (1 or 2)) errors.Add($"Mídia {m.Id}: só mono ou estéreo.");
            if (m.SampleRate != project.SampleRate) errors.Add($"Mídia {m.Id}: precisa estar na taxa do projeto.");
        }

        var buses = new HashSet<string>();
        foreach (var bus in project.Buses)
        {
            if (!IdPattern().IsMatch(bus.Id ?? "") || buses.Contains(bus.Id!) || bus.Id == "master") errors.Add("Barramento sem id válido.");
            else buses.Add(bus.Id!);
            if (!double.IsFinite(bus.GainDb) || bus.GainDb > MaxGainDb) errors.Add($"Barramento {bus.Name}: ganho inválido.");
            if (!double.IsFinite(bus.Pan) || Math.Abs(bus.Pan) > 100) errors.Add($"Barramento {bus.Name}: pan vai de −100 a +100.");
            EffectProblems(bus.Effects, $"Barramento {bus.Name}", errors);
        }

        var tracks = new HashSet<string>();
        foreach (var track in project.Tracks)
        {
            var name = track.Name ?? track.Id;
            if (!IdPattern().IsMatch(track.Id ?? "") || tracks.Contains(track.Id!)) errors.Add("Faixa sem id válido.");
            else tracks.Add(track.Id!);
            if (track.Color is not null && !ColorPattern().IsMatch(track.Color)) errors.Add($"Faixa {name}: cor inválida.");
            if (!double.IsFinite(track.GainDb) || track.GainDb > MaxGainDb) errors.Add($"Faixa {name}: o fader vai até +{Js(MaxGainDb)} dB.");
            if (!double.IsFinite(track.Pan) || Math.Abs(track.Pan) > 100) errors.Add($"Faixa {name}: pan vai de −100 a +100.");
            foreach (var trim in new[] { track.LeftTrimDb, track.RightTrimDb })
            {
                if (!double.IsFinite(trim) || trim < MinTrimDb || trim > MaxTrimDb) errors.Add($"Faixa {name}: ajuste L/R vai de {Js(MinTrimDb)} a +{Js(MaxTrimDb)} dB.");
            }

            if (track.Output != "master" && !buses.Contains(track.Output)) errors.Add($"Faixa {name}: saída para um barramento que não existe.");
            foreach (var send in track.Sends)
            {
                if (!buses.Contains(send.Bus)) errors.Add($"Faixa {name}: envio para um barramento que não existe.");
                if (!double.IsFinite(send.LevelDb) || send.LevelDb > MaxGainDb) errors.Add($"Faixa {name}: nível do envio inválido.");
            }

            EffectProblems(track.Effects, $"Faixa {name}", errors);
            foreach (var (kind, points) in new[] { ("volume", track.Automation.Volume), ("pan", track.Automation.Pan) })
            {
                long previous = -1;
                foreach (var point in points)
                {
                    if (point.Frame < previous || !double.IsFinite(point.Value))
                    {
                        errors.Add($"Faixa {name}: automação de {kind} fora de ordem.");
                        break;
                    }

                    previous = point.Frame;
                }
            }

            var clips = new HashSet<string>();
            foreach (var clip in track.Clips)
            {
                if (!IdPattern().IsMatch(clip.Id ?? "") || !clips.Add(clip.Id!)) errors.Add($"Faixa {name}: clipe sem id válido.");
                if (!media.TryGetValue(clip.Media, out var m))
                {
                    errors.Add($"Faixa {name}: o clipe {clip.Id} usa uma mídia que não está no projeto.");
                    continue;
                }

                if (new[] { clip.Start, clip.SourceStart, clip.Duration, clip.FadeIn, clip.FadeOut }.Any(v => v < 0) || clip.Duration == 0)
                    errors.Add($"Faixa {name}: o clipe {clip.Id} tem posição ou duração inválida.");
                else if (clip.SourceStart + clip.Duration > m.Frames) errors.Add($"Faixa {name}: o clipe {clip.Id} passa do fim da mídia.");
                else if (clip.FadeIn + clip.FadeOut > clip.Duration) errors.Add($"Faixa {name}: as rampas do clipe {clip.Id} são maiores que ele.");
                if (!double.IsFinite(clip.GainDb) || clip.GainDb > 24) errors.Add($"Faixa {name}: ganho do clipe {clip.Id} inválido.");
            }
        }

        if (!double.IsFinite(project.Master.GainDb) || project.Master.GainDb > MaxGainDb) errors.Add("Ganho do master inválido.");
        EffectProblems(project.Master.Effects, "Master", errors);
        if (project.Loop.Enabled && !(project.Loop.End > project.Loop.Start && project.Loop.Start >= 0)) errors.Add("O loop precisa terminar depois de começar.");
        if (project.Punch.Enabled && !(project.Punch.Out > project.Punch.In && project.Punch.In >= 0)) errors.Add("O punch precisa sair depois de entrar.");
        return errors;
    }

    // ==================== ajuste fino (espelho de musica.mjs) ====================

    /// <summary>Passos dos botões − e + dos controles da mistura.</summary>
    public const double StepGainDb = 0.5, StepPan = 1, StepTrimDb = 0.5, StepSendDb = 0.5, StepMetronomeDb = 1, StepFadeSeconds = 0.01;

    /// <summary>Passo do − e + de um parâmetro de efeito (mesma regra de <c>passoDoParametro</c>).</summary>
    public static (double Step, bool Relative) StepFor(double min, double max, string unit)
    {
        var range = max - min;
        if (unit == "dB") return (0.5, false);
        if (unit == "Hz") return (0.02, true);
        if (unit == "ms") return (range <= 100 ? 0.5 : range <= 1000 ? 1 : 10, false);
        var raw = range / 100;
        var power = Math.Pow(10, Math.Floor(Math.Log10(raw)));
        var m = raw / power;
        return ((m < 1.5 ? 1 : m < 3.5 ? 2 : m < 7.5 ? 5 : 10) * power, false);
    }

    /// <summary>Um toque no − (−1) ou no + (1), encaixado na grade do passo e dentro de [min, max].</summary>
    public static double FineStep(double value, int direction, double step, double min, double max, bool relative = false)
    {
        double next;
        if (relative)
        {
            next = Round(value * Math.Pow(1 + step, direction));
            if (next == Round(value)) next = Round(value) + direction;
        }
        else next = Round((value + direction * step) / step) * step;
        next = Round(next * 1e6) / 1e6 + 0.0;
        return Math.Clamp(next, min, max);
    }

    /// <summary>Nome da faixa criada por uma separação: o que ela é primeiro (voz_, bateria_...).</summary>
    public static string SeparatedTrackName(string label, string trackName) => $"{label.ToLowerInvariant()}_{trackName}";
}
