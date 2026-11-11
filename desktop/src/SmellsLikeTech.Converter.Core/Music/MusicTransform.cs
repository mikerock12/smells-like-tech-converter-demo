using System.Globalization;

namespace SmellsLikeTech.Converter.Core.Music;

/// <summary>Tempo e transposicao por clipe, sem alterar a midia original.</summary>
public static class MusicTransform
{
    public const double MinSpeed = 0.25;
    public const double MaxSpeed = 4;

    public static bool IsTransformed(MusicClip clip) => (clip.Speed ?? 1) != 1 || (clip.Semitones ?? 0) != 0;

    public static string SourceKey(MusicClip clip) => !IsTransformed(clip) ? clip.Media :
        $"{clip.Media}~{(clip.Speed ?? 1).ToString("0.000000", CultureInfo.InvariantCulture)}~{(clip.Semitones ?? 0).ToString("0.000", CultureInfo.InvariantCulture)}~{(clip.PreservePitch ?? true ? 1 : 0)}";

    public static MusicClip WithSpeed(MusicClip clip, MusicMedia media, double speed)
    {
        if (!double.IsFinite(speed) || speed is < MinSpeed or > MaxSpeed) throw new ConversionException("invalid_speed", "A velocidade vai de 25% a 400%.");
        var previous = clip.Speed ?? 1;
        var available = Math.Max(1, media.Frames - clip.SourceStart);
        var sourceFrames = Math.Min(available, clip.Duration * previous);
        var copy = clip.Copy();
        copy.Duration = Math.Max(1, (long)Math.Floor(sourceFrames / speed));
        copy.Speed = speed;
        copy.FadeIn = Math.Min(copy.FadeIn, copy.Duration);
        copy.FadeOut = Math.Min(copy.FadeOut, copy.Duration);
        return copy;
    }

    public static double SpeedToMatch(double sourceBpm, double targetBpm)
    {
        if (!(sourceBpm > 0) || !(targetBpm > 0)) throw new ConversionException("bpm_missing", "Identifique o BPM das duas faixas primeiro.");
        return Math.Clamp(sourceBpm / targetBpm, MinSpeed, MaxSpeed);
    }

    public static float[][] Resample(float[][] channels, double factor)
    {
        if (factor == 1) return [.. channels.Select(c => (float[])c.Clone())];
        return [.. channels.Select(channel =>
        {
            if (channel.Length == 0) return Array.Empty<float>();
            var size = Math.Max(1, (int)MusicRules.Round(channel.Length / factor));
            var output = new float[size];
            for (var i = 0; i < size; i++)
            {
                var position = Math.Min(channel.Length - 1, i * factor);
                var a = Math.Max(0, (int)Math.Floor(position));
                var b = Math.Min(channel.Length - 1, a + 1);
                var weight = Math.Max(0, position - a);
                output[i] = (float)(channel[a] + (channel[b] - channel[a]) * weight);
            }
            return output;
        })];
    }

    private static float[][] Stretch(float[][] channels, int rate, double speed)
    {
        if (speed == 1) return [.. channels.Select(c => (float[])c.Clone())];
        var sourceFrames = channels.FirstOrDefault()?.Length ?? 0;
        var size = Math.Max(1, (int)MusicRules.Round(sourceFrames / speed));
        var window = Math.Max(64, (int)MusicRules.Round(rate * 0.04 / 2) * 2);
        var hop = window / 2;
        var search = (int)MusicRules.Round(rate * 0.008);
        var output = channels.Select(_ => new float[size]).ToArray();
        var weights = new double[size];
        var envelope = new double[window];
        for (var i = 0; i < window; i++) envelope[i] = 0.5 - 0.5 * Math.Cos(2 * Math.PI * (i + 0.5) / window);
        var previous = 0;
        for (var position = 0; position < size; position += hop)
        {
            var expected = (int)MusicRules.Round(position * speed);
            var maximum = Math.Max(0, sourceFrames - window);
            var chosen = Math.Min(expected, maximum);
            if (position > 0 && expected < maximum)
            {
                var best = double.NegativeInfinity;
                for (var candidate = Math.Max(0, expected - search); candidate <= Math.Min(maximum, expected + search); candidate += 4)
                {
                    double product = 0, a = 1e-9, b = 1e-9;
                    for (var i = 0; i < hop; i += 8)
                    {
                        var x = previous + hop + i < sourceFrames ? channels[0][previous + hop + i] : 0;
                        var y = candidate + i < sourceFrames ? channels[0][candidate + i] : 0;
                        product += x * y;
                        a += x * x;
                        b += y * y;
                    }
                    var score = product / Math.Sqrt(a * b) - Math.Abs(candidate - expected) * 1e-8;
                    if (score > best) { best = score; chosen = candidate; }
                }
            }
            for (var i = 0; i < window && position + i < size; i++)
            {
                var weight = envelope[i];
                weights[position + i] += weight;
                for (var c = 0; c < channels.Length; c++) output[c][position + i] += (chosen + i < sourceFrames ? channels[c][chosen + i] : 0) * (float)weight;
            }
            previous = chosen;
        }
        for (var i = 0; i < size; i++) if (weights[i] > 0) for (var c = 0; c < output.Length; c++) output[c][i] /= (float)weights[i];
        return output;
    }

    public static float[][] Transform(float[][] channels, int rate, double speed = 1, double semitones = 0, bool preservePitch = true)
    {
        if (!(speed >= MinSpeed && speed <= MaxSpeed)) throw new ConversionException("invalid_speed", "A velocidade vai de 25% a 400%.");
        if (!(semitones >= -12 && semitones <= 12)) throw new ConversionException("invalid_pitch", "A tonalidade vai de −12 a +12 semitons.");
        var pitch = Math.Pow(2, semitones / 12);
        if (preservePitch)
        {
            var tuned = Resample(channels, pitch);
            return Stretch(tuned, rate, speed / pitch);
        }
        var atSpeed = Resample(channels, speed * pitch);
        return Stretch(atSpeed, rate, 1 / pitch);
    }

    public static float[][] ReadAll(IMusicSource source) => Enumerable.Range(0, source.Channels).Select(channel =>
    {
        var data = new float[checked((int)source.Frames)];
        const int step = 65536;
        var buffer = new float[step];
        for (long start = 0; start < source.Frames; start += step)
        {
            var n = (int)Math.Min(step, source.Frames - start);
            source.Copy(channel, start, n, buffer);
            Array.Copy(buffer, 0, data, start, n);
        }
        return data;
    }).ToArray();
}
