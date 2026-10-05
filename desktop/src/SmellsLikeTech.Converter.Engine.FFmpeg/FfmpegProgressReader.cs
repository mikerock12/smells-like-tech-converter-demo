using System.Globalization;

namespace SmellsLikeTech.Converter.Engine.FFmpeg;

/// <summary>
/// Le a saida de "-progress pipe:1" e converte em porcentagem e velocidade.
/// </summary>
public sealed class FfmpegProgressReader(TimeSpan? totalDuration, Action<double?, double?> onUpdate)
{
    private double? lastSpeed;

    public void OnLine(string line)
    {
        var separator = line.IndexOf('=');
        if (separator <= 0)
        {
            return;
        }

        var key = line[..separator].Trim();
        var value = line[(separator + 1)..].Trim();

        switch (key)
        {
            case "out_time_us":
            case "out_time_ms":
                ReportTime(value);
                break;
            case "speed":
                ReadSpeed(value);
                break;
            case "progress" when value == "end":
                onUpdate(1, lastSpeed);
                break;
        }
    }

    private void ReportTime(string value)
    {
        if (!long.TryParse(value, NumberStyles.Integer, CultureInfo.InvariantCulture, out var microseconds)
            || microseconds < 0)
        {
            return;
        }

        if (totalDuration is null || totalDuration.Value.TotalSeconds <= 0)
        {
            onUpdate(null, lastSpeed);
            return;
        }

        var seconds = microseconds / 1_000_000.0;
        onUpdate(Math.Clamp(seconds / totalDuration.Value.TotalSeconds, 0, 0.999), lastSpeed);
    }

    private void ReadSpeed(string value)
    {
        var text = value.TrimEnd('x');
        lastSpeed = double.TryParse(text, NumberStyles.Float, CultureInfo.InvariantCulture, out var speed) && speed > 0
            ? speed
            : null;
    }
}
