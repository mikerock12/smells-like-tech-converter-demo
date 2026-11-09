namespace SmellsLikeTech.Converter.Core.Music;

public sealed record MusicParameter(string Id, string Label, double Min, double Max, double Default, string Unit);

public sealed record MusicEffectDefinition(string Kind, string Label, string Group, IReadOnlyList<MusicParameter> Parameters);

/// <summary>Um efeito pronto para tocar: processa no lugar, sem alocar.</summary>
public interface IMusicProcessor
{
    void Process(float[] left, float[] right, int n);
    void Update(MusicEffect effect);
}

/// <summary>
/// Efeitos da criacao musical, espelho de packages/converter-core/musica-dsp.mjs: mesmo
/// catalogo, mesmas contas (em double, buffers float) e mesmas mensagens.
/// </summary>
public static class MusicDsp
{
    private const double TwoPi = Math.PI * 2;

    private static MusicParameter P(string id, string label, double min, double max, double def, string unit) => new(id, label, min, max, def, unit);

    public static readonly IReadOnlyList<MusicEffectDefinition> Catalog =
    [
        new("passa-alta", "Filtro passa-alta", "Filtros", [P("frequencia", "Corte", 20, 2000, 80, "Hz")]),
        new("passa-baixa", "Filtro passa-baixa", "Filtros", [P("frequencia", "Corte", 500, 20000, 12000, "Hz")]),
        new("equalizador", "Equalizador", "Filtros",
        [
            P("gravesDb", "Graves (100 Hz)", -15, 15, 0, "dB"),
            P("mediosFrequencia", "Médios: frequência", 150, 2000, 500, "Hz"),
            P("mediosDb", "Médios: ganho", -15, 15, 0, "dB"),
            P("mediosQ", "Médios: largura (Q)", 0.3, 8, 1, ""),
            P("presencaFrequencia", "Presença: frequência", 1500, 8000, 3000, "Hz"),
            P("presencaDb", "Presença: ganho", -15, 15, 0, "dB"),
            P("agudosDb", "Agudos (8 kHz)", -15, 15, 0, "dB"),
        ]),
        new("compressor", "Compressor", "Dinâmica",
        [
            P("limiarDb", "Limiar", -60, 0, -18, "dB"),
            P("razao", "Razão (x:1)", 1, 20, 3, ""),
            P("ataqueMs", "Ataque", 0.1, 100, 10, "ms"),
            P("liberacaoMs", "Liberação", 10, 1000, 120, "ms"),
            P("joelhoDb", "Joelho", 0, 12, 6, "dB"),
            P("ganhoDb", "Ganho de compensação", 0, 24, 0, "dB"),
        ]),
        new("porta", "Gate / expansor", "Dinâmica",
        [
            P("limiarDb", "Limiar", -80, 0, -50, "dB"),
            P("faixaDb", "Redução quando fechado", -80, 0, -60, "dB"),
            P("ataqueMs", "Abertura", 0.1, 50, 1, "ms"),
            P("liberacaoMs", "Fechamento", 10, 1000, 100, "ms"),
        ]),
        new("limitador", "Limitador", "Dinâmica", [P("tetoDb", "Teto", -12, 0, -1, "dB"), P("liberacaoMs", "Liberação", 1, 500, 50, "ms")]),
        new("desesser", "De-esser", "Dinâmica", [P("frequencia", "Frequência", 3000, 10000, 6500, "Hz"), P("limiarDb", "Limiar", -60, 0, -30, "dB"), P("reducaoMaxDb", "Redução máxima", 0, 24, 10, "dB")]),
        new("saturacao", "Saturação", "Cor", [P("driveDb", "Drive", 0, 24, 6, "dB"), P("mistura", "Mistura", 0, 1, 1, ""), P("saidaDb", "Saída", -24, 6, 0, "dB")]),
        new("atraso", "Delay", "Espaço", [P("tempoMs", "Tempo", 1, 2000, 350, "ms"), P("retorno", "Realimentação", 0, 0.95, 0.35, ""), P("mistura", "Mistura", 0, 1, 0.25, "")]),
        new("reverberacao", "Reverb", "Espaço", [P("tamanho", "Tamanho da sala", 0, 1, 0.6, ""), P("amortecimento", "Amortecimento", 0, 1, 0.4, ""), P("largura", "Largura", 0, 1, 1, ""), P("mistura", "Mistura", 0, 1, 0.25, "")]),
        new("coro", "Chorus", "Modulação", [P("taxaHz", "Velocidade", 0.1, 5, 0.8, "Hz"), P("profundidadeMs", "Profundidade", 0, 10, 3, "ms"), P("mistura", "Mistura", 0, 1, 0.35, "")]),
        new("tremolo", "Tremolo", "Modulação", [P("taxaHz", "Velocidade", 0.5, 15, 5, "Hz"), P("profundidade", "Profundidade", 0, 1, 0.5, "")]),
        new("largura", "Largura estéreo", "Espaço", [P("largura", "Largura", 0, 2, 1, "×")]),
        new("ganho", "Ganho", "Utilidades", [P("ganhoDb", "Ganho", -24, 24, 0, "dB")]),
    ];

    public static MusicEffectDefinition? Definition(string kind) => Catalog.FirstOrDefault(d => d.Kind == kind);

    public static MusicEffect NewEffect(string kind, string id)
    {
        var definition = Definition(kind) ?? throw new ArgumentException($"Efeito desconhecido: {kind}");
        var effect = new MusicEffect { Id = id, Kind = kind, Enabled = true };
        foreach (var p in definition.Parameters) effect.Set(p.Id, p.Default);
        return effect;
    }

    public static double Param(MusicEffect effect, string id) =>
        effect.Raw(id) ?? Definition(effect.Kind)!.Parameters.First(p => p.Id == id).Default;

    public static string? Validate(MusicEffect effect)
    {
        var definition = Definition(effect.Kind ?? "");
        if (definition is null) return $"efeito desconhecido ({effect.Kind}).";
        if (effect.Enabled is null) return $"{definition.Label}: falta dizer se está ligado.";
        foreach (var p in definition.Parameters)
        {
            if (!effect.Has(p.Id)) continue;
            var value = effect.Raw(p.Id);
            if (!effect.IsNumber(p.Id) || value is null || !double.IsFinite(value.Value) || value < p.Min || value > p.Max)
                return $"{definition.Label}: {p.Label.ToLowerInvariant()} vai de {MusicRules.Js(p.Min)} a {MusicRules.Js(p.Max)}{(p.Unit.Length > 0 ? $" {p.Unit}" : "")}.";
        }

        return null;
    }

    public static double Tail(MusicEffect effect)
    {
        if (!effect.IsEnabled) return 0;
        if (effect.Kind == "reverberacao") return 1 + 5 * Param(effect, "tamanho");
        if (effect.Kind == "atraso")
        {
            var feedback = Param(effect, "retorno");
            var time = Param(effect, "tempoMs") / 1000;
            return feedback <= 0 ? time : Math.Min(10, time * (Math.Log(0.001) / Math.Log(feedback)) + time);
        }

        return effect.Kind == "coro" ? 0.05 : 0;
    }

    internal static double Anti(double value) => Math.Abs(value) < 1e-25 ? 0 : value;

    internal static double Coefficient(double ms, int rate) => ms <= 0 ? 0 : Math.Exp(-1 / (rate * ms / 1000));

    internal static double Db(double value) => Math.Pow(10, value / 20);

    /// <summary>Coeficientes RBJ normalizados: b0, b1, b2, a1, a2.</summary>
    public static double[] Biquad(string kind, double frequency, int rate, double q = 0.7071067811865476, double gainDb = 0)
    {
        var f = Math.Clamp(frequency, 10, rate * 0.45);
        var w0 = TwoPi * f / rate;
        var cosw = Math.Cos(w0);
        var sinw = Math.Sin(w0);
        var A = Math.Pow(10, gainDb / 40);
        double b0, b1, b2, a0, a1, a2;
        if (kind is "passa-baixa" or "passa-alta")
        {
            var alpha = sinw / (2 * q);
            var s = kind == "passa-baixa" ? 1 - cosw : 1 + cosw;
            b0 = s / 2;
            b1 = kind == "passa-baixa" ? s : -s;
            b2 = s / 2;
            a0 = 1 + alpha;
            a1 = -2 * cosw;
            a2 = 1 - alpha;
        }
        else if (kind == "pico")
        {
            var alpha = sinw / (2 * q);
            b0 = 1 + alpha * A;
            b1 = -2 * cosw;
            b2 = 1 - alpha * A;
            a0 = 1 + alpha / A;
            a1 = -2 * cosw;
            a2 = 1 - alpha / A;
        }
        else
        {
            var alpha = sinw / 2 * Math.Sqrt(2);
            var root = 2 * Math.Sqrt(A) * alpha;
            if (kind == "prateleira-grave")
            {
                b0 = A * (A + 1 - (A - 1) * cosw + root);
                b1 = 2 * A * (A - 1 - (A + 1) * cosw);
                b2 = A * (A + 1 - (A - 1) * cosw - root);
                a0 = A + 1 + (A - 1) * cosw + root;
                a1 = -2 * (A - 1 + (A + 1) * cosw);
                a2 = A + 1 + (A - 1) * cosw - root;
            }
            else
            {
                b0 = A * (A + 1 + (A - 1) * cosw + root);
                b1 = -2 * A * (A - 1 + (A + 1) * cosw);
                b2 = A * (A + 1 + (A - 1) * cosw - root);
                a0 = A + 1 - (A - 1) * cosw + root;
                a1 = 2 * (A - 1 - (A + 1) * cosw);
                a2 = A + 1 - (A - 1) * cosw - root;
            }
        }

        return [b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0];
    }

    public static IMusicProcessor Create(MusicEffect effect, int rate) => effect.Kind switch
    {
        "passa-alta" or "passa-baixa" => new FilterProcessor(effect, rate),
        "equalizador" => new EqualizerProcessor(effect, rate),
        "compressor" => new CompressorProcessor(effect, rate),
        "porta" => new GateProcessor(effect, rate),
        "limitador" => new LimiterProcessor(effect, rate),
        "desesser" => new DeEsserProcessor(effect, rate),
        "saturacao" => new SaturationProcessor(effect),
        "atraso" => new DelayProcessor(effect, rate),
        "reverberacao" => new ReverbProcessor(effect, rate),
        "coro" => new ChorusProcessor(effect, rate),
        "tremolo" => new TremoloProcessor(effect, rate),
        "largura" => new WidthProcessor(effect),
        "ganho" => new GainProcessor(effect),
        _ => throw new ArgumentException($"Efeito desconhecido: {effect.Kind}")
    };
}

/// <summary>Biquad estereo, estado separado por canal.</summary>
public sealed class MusicBiquad
{
    private readonly double[] state = new double[8];
    private double b0, b1, b2, a1, a2;

    public MusicBiquad(double[] c) => Set(c);

    public void Set(double[] c)
    {
        b0 = c[0]; b1 = c[1]; b2 = c[2]; a1 = c[3]; a2 = c[4];
    }

    public double Sample(double x, int channel)
    {
        var k = channel * 4;
        var e = state;
        var y = MusicDsp.Anti(b0 * x + b1 * e[k] + b2 * e[k + 1] - a1 * e[k + 2] - a2 * e[k + 3]);
        e[k + 1] = e[k];
        e[k] = x;
        e[k + 3] = e[k + 2];
        e[k + 2] = y;
        return y;
    }

    public void Process(float[] left, float[] right, int n)
    {
        for (var i = 0; i < n; i++)
        {
            left[i] = (float)Sample(left[i], 0);
            right[i] = (float)Sample(right[i], 1);
        }
    }
}

internal sealed class FilterProcessor : IMusicProcessor
{
    private readonly int rate;
    private MusicBiquad? biquad;

    public FilterProcessor(MusicEffect effect, int rate)
    {
        this.rate = rate;
        Update(effect);
    }

    public void Update(MusicEffect effect)
    {
        var c = MusicDsp.Biquad(effect.Kind, MusicDsp.Param(effect, "frequencia"), rate);
        if (biquad is null) biquad = new MusicBiquad(c);
        else biquad.Set(c);
    }

    public void Process(float[] left, float[] right, int n) => biquad!.Process(left, right, n);
}

internal sealed class EqualizerProcessor : IMusicProcessor
{
    private readonly int rate;
    private readonly MusicBiquad?[] bands = new MusicBiquad?[4];
    private readonly bool[] active = new bool[4];

    public EqualizerProcessor(MusicEffect effect, int rate)
    {
        this.rate = rate;
        Update(effect);
    }

    public void Update(MusicEffect e)
    {
        var c = new[]
        {
            MusicDsp.Biquad("prateleira-grave", 100, rate, 0.7071067811865476, MusicDsp.Param(e, "gravesDb")),
            MusicDsp.Biquad("pico", MusicDsp.Param(e, "mediosFrequencia"), rate, MusicDsp.Param(e, "mediosQ"), MusicDsp.Param(e, "mediosDb")),
            MusicDsp.Biquad("pico", MusicDsp.Param(e, "presencaFrequencia"), rate, 1, MusicDsp.Param(e, "presencaDb")),
            MusicDsp.Biquad("prateleira-aguda", 8000, rate, 0.7071067811865476, MusicDsp.Param(e, "agudosDb")),
        };
        var gains = new[] { MusicDsp.Param(e, "gravesDb"), MusicDsp.Param(e, "mediosDb"), MusicDsp.Param(e, "presencaDb"), MusicDsp.Param(e, "agudosDb") };
        for (var i = 0; i < 4; i++)
        {
            active[i] = gains[i] != 0;
            if (bands[i] is null) bands[i] = new MusicBiquad(c[i]);
            else bands[i]!.Set(c[i]);
        }
    }

    public void Process(float[] left, float[] right, int n)
    {
        for (var b = 0; b < 4; b++)
        {
            if (active[b]) bands[b]!.Process(left, right, n);
        }
    }
}

internal sealed class CompressorProcessor : IMusicProcessor
{
    private readonly int rate;
    private double envelope, threshold, ratio, knee, makeup, attack, release;

    public CompressorProcessor(MusicEffect effect, int rate)
    {
        this.rate = rate;
        Update(effect);
    }

    public void Update(MusicEffect e)
    {
        threshold = MusicDsp.Param(e, "limiarDb");
        ratio = MusicDsp.Param(e, "razao");
        knee = MusicDsp.Param(e, "joelhoDb");
        makeup = MusicDsp.Param(e, "ganhoDb");
        attack = MusicDsp.Coefficient(MusicDsp.Param(e, "ataqueMs"), rate);
        release = MusicDsp.Coefficient(MusicDsp.Param(e, "liberacaoMs"), rate);
    }

    private double Reduction(double levelDb)
    {
        var over = levelDb - threshold;
        var slope = 1 / ratio - 1;
        var w = knee;
        if (w > 0 && 2 * Math.Abs(over) <= w) return slope * (over + w / 2) * (over + w / 2) / (2 * w);
        return over > 0 ? slope * over : 0;
    }

    public void Process(float[] left, float[] right, int n)
    {
        for (var i = 0; i < n; i++)
        {
            var level = Math.Max(Math.Abs((double)left[i]), Math.Abs((double)right[i]));
            var target = Reduction(20 * Math.Log10(level + 1e-12));
            var c = target < envelope ? attack : release;
            envelope = c * envelope + (1 - c) * target;
            var g = Math.Pow(10, (envelope + makeup) / 20);
            left[i] = (float)(left[i] * g);
            right[i] = (float)(right[i] * g);
        }
    }
}

internal sealed class GateProcessor : IMusicProcessor
{
    private readonly int rate;
    private readonly double fall;
    private double detector, gain = 1, threshold, closed, attack, release;

    public GateProcessor(MusicEffect effect, int rate)
    {
        this.rate = rate;
        fall = MusicDsp.Coefficient(10, rate);
        Update(effect);
    }

    public void Update(MusicEffect e)
    {
        threshold = MusicDsp.Param(e, "limiarDb");
        closed = MusicDsp.Db(MusicDsp.Param(e, "faixaDb"));
        attack = MusicDsp.Coefficient(MusicDsp.Param(e, "ataqueMs"), rate);
        release = MusicDsp.Coefficient(MusicDsp.Param(e, "liberacaoMs"), rate);
    }

    public void Process(float[] left, float[] right, int n)
    {
        for (var i = 0; i < n; i++)
        {
            var level = Math.Max(Math.Abs((double)left[i]), Math.Abs((double)right[i]));
            detector = Math.Max(level, detector * fall);
            var target = 20 * Math.Log10(detector + 1e-12) > threshold ? 1 : closed;
            var c = target > gain ? attack : release;
            gain = MusicDsp.Anti(c * gain + (1 - c) * target);
            left[i] = (float)(left[i] * gain);
            right[i] = (float)(right[i] * gain);
        }
    }
}

internal sealed class LimiterProcessor : IMusicProcessor
{
    private readonly int rate;
    private double gain = 1, ceiling, release;

    public LimiterProcessor(MusicEffect effect, int rate)
    {
        this.rate = rate;
        Update(effect);
    }

    public void Update(MusicEffect e)
    {
        ceiling = MusicDsp.Db(MusicDsp.Param(e, "tetoDb"));
        release = MusicDsp.Coefficient(MusicDsp.Param(e, "liberacaoMs"), rate);
    }

    public void Process(float[] left, float[] right, int n)
    {
        for (var i = 0; i < n; i++)
        {
            var peak = Math.Max(Math.Abs((double)left[i]), Math.Abs((double)right[i]));
            var target = peak > ceiling ? ceiling / peak : 1;
            gain = target < gain ? target : release * gain + (1 - release) * target;
            left[i] = (float)(left[i] * gain);
            right[i] = (float)(right[i] * gain);
        }
    }
}

internal sealed class DeEsserProcessor : IMusicProcessor
{
    private readonly int rate;
    private readonly double attack, release;
    private MusicBiquad? filter;
    private double envelope, threshold, maximum;

    public DeEsserProcessor(MusicEffect effect, int rate)
    {
        this.rate = rate;
        attack = MusicDsp.Coefficient(1, rate);
        release = MusicDsp.Coefficient(60, rate);
        Update(effect);
    }

    public void Update(MusicEffect e)
    {
        var c = MusicDsp.Biquad("passa-alta", MusicDsp.Param(e, "frequencia"), rate);
        if (filter is null) filter = new MusicBiquad(c);
        else filter.Set(c);
        threshold = MusicDsp.Param(e, "limiarDb");
        maximum = MusicDsp.Param(e, "reducaoMaxDb");
    }

    public void Process(float[] left, float[] right, int n)
    {
        for (var i = 0; i < n; i++)
        {
            var hl = filter!.Sample(left[i], 0);
            var hr = filter.Sample(right[i], 1);
            var level = Math.Max(Math.Abs(hl), Math.Abs(hr));
            var c = level > envelope ? attack : release;
            envelope = MusicDsp.Anti(c * envelope + (1 - c) * level);
            var reduction = Math.Min(maximum, Math.Max(0, 20 * Math.Log10(envelope + 1e-12) - threshold));
            var g = Math.Pow(10, -reduction / 20);
            left[i] = (float)(left[i] - hl + hl * g);
            right[i] = (float)(right[i] - hr + hr * g);
        }
    }
}

internal sealed class SaturationProcessor : IMusicProcessor
{
    private double drive, normal, mix, output;

    public SaturationProcessor(MusicEffect effect) => Update(effect);

    public void Update(MusicEffect e)
    {
        drive = MusicDsp.Db(MusicDsp.Param(e, "driveDb"));
        normal = Math.Tanh(drive);
        mix = MusicDsp.Param(e, "mistura");
        output = MusicDsp.Db(MusicDsp.Param(e, "saidaDb"));
    }

    public void Process(float[] left, float[] right, int n)
    {
        for (var i = 0; i < n; i++)
        {
            double l = left[i], r = right[i];
            left[i] = (float)((l * (1 - mix) + Math.Tanh(drive * l) / normal * mix) * output);
            right[i] = (float)((r * (1 - mix) + Math.Tanh(drive * r) / normal * mix) * output);
        }
    }
}

internal sealed class GainProcessor : IMusicProcessor
{
    private double g;

    public GainProcessor(MusicEffect effect) => Update(effect);

    public void Update(MusicEffect e) => g = MusicDsp.Db(MusicDsp.Param(e, "ganhoDb"));

    public void Process(float[] left, float[] right, int n)
    {
        for (var i = 0; i < n; i++)
        {
            left[i] = (float)(left[i] * g);
            right[i] = (float)(right[i] * g);
        }
    }
}

internal sealed class WidthProcessor : IMusicProcessor
{
    private double width;

    public WidthProcessor(MusicEffect effect) => Update(effect);

    public void Update(MusicEffect e) => width = MusicDsp.Param(e, "largura");

    public void Process(float[] left, float[] right, int n)
    {
        for (var i = 0; i < n; i++)
        {
            var mid = ((double)left[i] + right[i]) * 0.5;
            var side = ((double)left[i] - right[i]) * 0.5 * width;
            left[i] = (float)(mid + side);
            right[i] = (float)(mid - side);
        }
    }
}

internal sealed class TremoloProcessor : IMusicProcessor
{
    private readonly int rate;
    private double phase, step, depth;

    public TremoloProcessor(MusicEffect effect, int rate)
    {
        this.rate = rate;
        Update(effect);
    }

    public void Update(MusicEffect e)
    {
        step = Math.PI * 2 * MusicDsp.Param(e, "taxaHz") / rate;
        depth = MusicDsp.Param(e, "profundidade");
    }

    public void Process(float[] left, float[] right, int n)
    {
        for (var i = 0; i < n; i++)
        {
            var g = 1 - depth * 0.5 * (1 + Math.Sin(phase));
            left[i] = (float)(left[i] * g);
            right[i] = (float)(right[i] * g);
            phase += step;
            if (phase >= Math.PI * 2) phase -= Math.PI * 2;
        }
    }
}

internal sealed class DelayProcessor : IMusicProcessor
{
    private readonly int rate, size;
    private readonly float[] bl, br;
    private int write, delay;
    private double feedback, mix;

    public DelayProcessor(MusicEffect effect, int rate)
    {
        this.rate = rate;
        size = (int)Math.Ceiling(rate * 2.0) + 1;
        bl = new float[size];
        br = new float[size];
        Update(effect);
    }

    public void Update(MusicEffect e)
    {
        delay = (int)Math.Clamp(MusicRules.Round(MusicDsp.Param(e, "tempoMs") * rate / 1000), 1, size - 1);
        feedback = MusicDsp.Param(e, "retorno");
        mix = MusicDsp.Param(e, "mistura");
    }

    public void Process(float[] left, float[] right, int n)
    {
        for (var i = 0; i < n; i++)
        {
            var read = write - delay;
            if (read < 0) read += size;
            double dl = bl[read], dr = br[read];
            bl[write] = (float)MusicDsp.Anti(left[i] + dl * feedback);
            br[write] = (float)MusicDsp.Anti(right[i] + dr * feedback);
            left[i] = (float)(left[i] * (1 - mix) + dl * mix);
            right[i] = (float)(right[i] * (1 - mix) + dr * mix);
            write++;
            if (write == size) write = 0;
        }
    }
}

internal sealed class ChorusProcessor : IMusicProcessor
{
    private readonly int rate, size;
    private readonly float[] bl, br;
    private readonly double baseDelay;
    private int write;
    private double phase, step, depth, mix;

    public ChorusProcessor(MusicEffect effect, int rate)
    {
        this.rate = rate;
        size = (int)Math.Ceiling(rate * 0.05) + 2;
        bl = new float[size];
        br = new float[size];
        baseDelay = rate * 0.015;
        Update(effect);
    }

    public void Update(MusicEffect e)
    {
        step = Math.PI * 2 * MusicDsp.Param(e, "taxaHz") / rate;
        depth = MusicDsp.Param(e, "profundidadeMs") * rate / 1000;
        mix = MusicDsp.Param(e, "mistura");
    }

    private double Read(float[] buffer, double delay)
    {
        var position = write - delay;
        while (position < 0) position += size;
        var i0 = (int)Math.Floor(position);
        var fraction = position - i0;
        var i1 = i0 + 1 == size ? 0 : i0 + 1;
        return buffer[i0] + (buffer[i1] - (double)buffer[i0]) * fraction;
    }

    public void Process(float[] left, float[] right, int n)
    {
        for (var i = 0; i < n; i++)
        {
            bl[write] = left[i];
            br[write] = right[i];
            var dl = baseDelay + depth * Math.Sin(phase);
            var dr = baseDelay + depth * Math.Sin(phase + Math.PI / 2);
            var wl = Read(bl, dl);
            var wr = Read(br, dr);
            left[i] = (float)(left[i] * (1 - mix) + wl * mix);
            right[i] = (float)(right[i] * (1 - mix) + wr * mix);
            write++;
            if (write == size) write = 0;
            phase += step;
            if (phase >= Math.PI * 2) phase -= Math.PI * 2;
        }
    }
}

/// <summary>Freeverb (Jezar, dominio publico).</summary>
internal sealed class ReverbProcessor : IMusicProcessor
{
    private static readonly int[] Combs = [1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617];
    private static readonly int[] AllPasses = [556, 441, 341, 225];
    private const int Spread = 23;

    private sealed class Line(int size)
    {
        public readonly float[] B = new float[Math.Max(1, size)];
        public int I;
        public double F;
    }

    private readonly Line[][] combs;
    private readonly Line[][] allPasses;
    private double feedback, damping, wet1, wet2, dry;

    public ReverbProcessor(MusicEffect effect, int rate)
    {
        var scale = rate / 44100.0;
        Line[] Make(int[] sizes, int extra) => [.. sizes.Select(t => new Line((int)MusicRules.Round((t + extra) * scale)))];
        combs = [Make(Combs, 0), Make(Combs, Spread)];
        allPasses = [Make(AllPasses, 0), Make(AllPasses, Spread)];
        Update(effect);
    }

    public void Update(MusicEffect e)
    {
        feedback = MusicDsp.Param(e, "tamanho") * 0.28 + 0.7;
        damping = MusicDsp.Param(e, "amortecimento") * 0.4;
        var mix = MusicDsp.Param(e, "mistura");
        var width = MusicDsp.Param(e, "largura");
        var wet = mix * 3;
        wet1 = wet * (width / 2 + 0.5);
        wet2 = wet * ((1 - width) / 2);
        dry = 1 - mix;
    }

    private double Channel(double input, int c)
    {
        double output = 0;
        var d1 = damping;
        var d2 = 1 - d1;
        foreach (var comb in combs[c])
        {
            double value = comb.B[comb.I];
            comb.F = MusicDsp.Anti(value * d2 + comb.F * d1);
            comb.B[comb.I] = (float)(input + comb.F * feedback);
            if (++comb.I == comb.B.Length) comb.I = 0;
            output += value;
        }

        foreach (var filter in allPasses[c])
        {
            double value = filter.B[filter.I];
            filter.B[filter.I] = (float)MusicDsp.Anti(output + value * 0.5);
            output = value - output;
            if (++filter.I == filter.B.Length) filter.I = 0;
        }

        return output;
    }

    public void Process(float[] left, float[] right, int n)
    {
        for (var i = 0; i < n; i++)
        {
            var input = ((double)left[i] + right[i]) * 0.015;
            var ol = Channel(input, 0);
            var or = Channel(input, 1);
            double ll = left[i], lr = right[i];
            left[i] = (float)(ol * wet1 + or * wet2 + ll * dry);
            right[i] = (float)(or * wet1 + ol * wet2 + lr * dry);
        }
    }
}

/// <summary>Cadeia que reaproveita o processador de cada efeito pelo id (o reverb nao corta).</summary>
public sealed class MusicChain(int rate)
{
    private sealed class Item(string id, string kind, bool enabled, IMusicProcessor processor)
    {
        public string Id { get; } = id;
        public string Kind { get; } = kind;
        public bool Enabled { get; set; } = enabled;
        public IMusicProcessor Processor { get; } = processor;
    }

    private Item[] items = [];

    public void Sync(IReadOnlyList<MusicEffect>? effects)
    {
        var previous = items.ToDictionary(i => i.Id);
        items = [.. (effects ?? []).Select(effect =>
        {
            if (previous.TryGetValue(effect.Id, out var old) && old.Kind == effect.Kind)
            {
                old.Processor.Update(effect);
                old.Enabled = effect.IsEnabled;
                return old;
            }

            return new Item(effect.Id, effect.Kind, effect.IsEnabled, MusicDsp.Create(effect, rate));
        })];
    }

    public void Process(float[] left, float[] right, int n)
    {
        foreach (var item in items)
        {
            if (item.Enabled) item.Processor.Process(left, right, n);
        }
    }
}
