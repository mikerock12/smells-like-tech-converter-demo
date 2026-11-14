using System.Globalization;

namespace SmellsLikeTech.Converter.Core.Jobs;

/// <summary>
/// O tempo que falta num processamento longo, a partir da fração concluída: o que já levou,
/// proporcional ao que falta, suavizado para não pular a cada atualização. O relógio começa
/// quando a fração começa a andar (download e preparação não contam). Uma fração que volta é
/// outra etapa, e a estimativa recomeça. Espelho de packages/converter-core/andamento.mjs.
/// </summary>
public sealed class ProgressEstimate(Func<double>? clockSeconds = null)
{
    private readonly Func<double> clock = clockSeconds ?? (() => Environment.TickCount64 / 1000.0);
    private (double Time, double Fraction)? start;
    private double? last;
    private double lastTime;
    private double lastFraction;

    /// <summary>Segundos que faltam, ou nulo enquanto ainda não dá para estimar.</summary>
    public double? Register(double fraction)
    {
        if (fraction + 0.001 < lastFraction) { start = null; last = null; }
        lastFraction = fraction;
        if (!(fraction > 0) || fraction >= 1) return null;
        var now = clock();
        start ??= (now, fraction);
        var elapsed = now - start.Value.Time;
        var done = fraction - start.Value.Fraction;
        if (done < 0.02 || elapsed < 2) return null;
        var raw = elapsed * (1 - fraction) / done;
        var previous = last is { } l ? Math.Max(0, l - (now - lastTime)) : (double?)null;
        var smooth = previous is { } p ? 0.75 * p + 0.25 * raw : raw;
        last = smooth;
        lastTime = now;
        return Math.Round(smooth);
    }

    /// <summary>"37% · faltam cerca de 2 min".</summary>
    public string Text(double fraction) => Describe(fraction, Register(fraction));

    public static string? Remaining(double? seconds)
    {
        if (seconds is not { } s || !double.IsFinite(s)) return null;
        if (s <= 5) return "quase pronto";
        if (s < 60) return $"faltam cerca de {Math.Ceiling(s / 5) * 5:0} s";
        var minutes = (int)Math.Ceiling(s / 60);
        if (s < 3600) return $"faltam cerca de {minutes} min";
        return $"faltam cerca de {minutes / 60} h" + (minutes % 60 != 0 ? $" {minutes % 60} min" : "");
    }

    public static string Describe(double fraction, double? seconds)
    {
        var percent = (int)Math.Clamp(Math.Floor(fraction * 100), 0, 100);
        var time = Remaining(seconds);
        if (percent <= 0) return time is null ? "Preparando…" : $"Preparando… · {time}";
        return time is null ? $"{percent.ToString(CultureInfo.InvariantCulture)}%" : $"{percent}% · {time}";
    }
}
