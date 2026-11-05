using System.Globalization;

namespace SmellsLikeTech.Converter.Core.Studio;

/// <summary>O efeito num instante: frações do quadro, escala, radianos, alfa, estilhaços, partículas e matiz.</summary>
public sealed record StudioEffectState(double Dx, double Dy, double Sx, double Sy, double Rotation, double Alpha, double Pieces, double Particles, double Hue);

/// <summary>
/// Efeitos de entrada, saída e contínuos de clipes, camadas e textos. Espelho de
/// estudio-efeitos.mjs e Efeitos.kt: a receita sai idêntica, conferida pelos casos do estúdio.
/// Entrada e saída rodam só na sua janela de tempo (split + trim + concat), com filtros LGPL.
/// </summary>
public static class StudioEffects
{
    public static readonly IReadOnlyList<(string Id, string Title)> Entries =
    [
        ("nenhum", "Nenhum"), ("fade", "Surgir suave (fade in)"), ("cima", "Surgir de cima"), ("baixo", "Surgir de baixo"),
        ("esquerda", "Surgir da esquerda"), ("direita", "Surgir da direita"), ("zoom", "Crescer do nada"),
        ("impacto", "Impacto (vem de perto e recua)"), ("giro", "Entrar girando"), ("quicar", "Cair quicando"),
        ("estilhacos", "Montar de estilhaços"), ("desintegrar", "Formar de partículas"), ("maquina", "Máquina de escrever")
    ];

    public static readonly IReadOnlyList<(string Id, string Title)> Exits =
    [
        ("nenhum", "Nenhum"), ("fade", "Esmaecer (fade out)"), ("cima", "Sair por cima"), ("baixo", "Sair por baixo"),
        ("esquerda", "Sair pela esquerda"), ("direita", "Sair pela direita"), ("zoom", "Encolher até sumir"),
        ("impacto", "Crescer e sumir"), ("giro", "Sair girando"), ("quicar", "Pular para fora"),
        ("estilhacos", "Estilhaçar"), ("desintegrar", "Desintegrar")
    ];

    public static readonly IReadOnlyList<(string Id, string Title)> Loops =
    [
        ("nenhum", "Nenhum"), ("girar", "Girar sem parar"), ("balancar", "Balançar"), ("pulsar", "Pulsar"),
        ("coracao", "Bater como coração"), ("tremer", "Tremer"), ("flutuar", "Flutuar"), ("gelatina", "Gelatina"), ("arcoiris", "Arco-íris")
    ];

    public const double DefaultDuration = .6;
    private const int Columns = 6, Rows = 6, MaxTypewriterSteps = 120;

    private static string N(double v)
    {
        var r = Math.Floor((v * 1e6) + .5) / 1e6;
        return (r == 0 ? 0 : r).ToString("0.######", CultureInfo.InvariantCulture);
    }

    private static int Even(double v) => Math.Max(2, (int)Math.Floor((v / 2) + .5) * 2);

    private static bool Active(string id) => id != "nenhum";

    public static bool Has(StudioTransform? t) => t is not null && (Active(t.EntryEffect) || Active(t.ExitEffect) || Active(t.LoopEffect));

    public static string? Problem(StudioTransform t)
    {
        if (Entries.All(e => e.Id != t.EntryEffect)) return "Efeito de entrada desconhecido.";
        if (Exits.All(e => e.Id != t.ExitEffect)) return "Efeito de saída desconhecido.";
        if (Loops.All(e => e.Id != t.LoopEffect)) return "Efeito contínuo desconhecido.";
        foreach (var d in new[] { t.EntryDuration, t.ExitDuration })
            if (!double.IsFinite(d) || d < .1 || d > 10) return "A duração do efeito vai de 0,1 a 10 segundos.";
        return null;
    }

    public static (double Entry, double Exit) Windows(StudioTransform t, double duration)
    {
        var entry = Active(t.EntryEffect) && t.EntryEffect != "maquina" ? Math.Min(t.EntryDuration, duration) : 0;
        var exit = Active(t.ExitEffect) ? Math.Min(t.ExitDuration, Math.Max(0, duration - entry)) : 0;
        return (entry, exit);
    }

    /// <summary>Sorteio fixo de cada estilhaço (0 a 1): o mesmo resultado em todos os clientes.</summary>
    public static double Draw(int k, int m) => (((k * 73) + (m * 37) + 11) % 101) / 100.0;

    private static StudioEffectState Window(StudioEffectState e, string kind, double p, bool entry)
    {
        var q = entry ? 1 - Math.Pow(1 - p, 3) : 1 - Math.Pow(p, 3);
        var r = entry ? p : 1 - p;
        var fade = entry ? p : 1 - p;
        var shrink = Math.Max(.02, q);
        switch (kind)
        {
            case "fade": return e with { Alpha = e.Alpha * fade };
            case "cima": return e with { Dy = e.Dy - (1 - q) };
            case "baixo": return e with { Dy = e.Dy + (1 - q) };
            case "esquerda": return e with { Dx = e.Dx - (1 - q) };
            case "direita": return e with { Dx = e.Dx + (1 - q) };
            case "zoom": return e with { Sx = e.Sx * shrink, Sy = e.Sy * shrink };
            case "impacto":
                var s = 1 + (2 * (1 - q));
                return e with { Sx = e.Sx * s, Sy = e.Sy * s, Alpha = e.Alpha * fade };
            case "giro": return e with { Rotation = e.Rotation + (6.2832 * (1 - q)), Sx = e.Sx * shrink, Sy = e.Sy * shrink };
            case "quicar": return e with { Dy = e.Dy - (Math.Abs(Math.Cos(7.854 * r)) * Math.Pow(1 - r, 2)) };
            case "estilhacos": return e with { Pieces = 1 - q, Alpha = e.Alpha * fade };
            case "desintegrar": return e with { Particles = q };
            default: return e;
        }
    }

    public static StudioEffectState State(StudioTransform t, double time, double duration)
    {
        var e = new StudioEffectState(0, 0, 1, 1, 0, 1, 0, 1, 0);
        if (!Has(t)) return e;
        var T = time;
        switch (t.LoopEffect)
        {
            case "girar": e = e with { Rotation = Math.PI * T }; break;
            case "balancar": e = e with { Rotation = .26 * Math.Sin(5.0265 * T) }; break;
            case "pulsar": { var s = 1 + (.1 * Math.Sin(7.5398 * T)); e = e with { Sx = s, Sy = s }; break; }
            case "coracao": { var s = 1 + (.18 * Math.Pow(Math.Max(0, Math.Sin(6.2832 * T)), 4)); e = e with { Sx = s, Sy = s }; break; }
            case "tremer": e = e with { Dx = .012 * Math.Sin(56.5487 * T), Dy = .012 * Math.Sin((69.115 * T) + 1) }; break;
            case "flutuar": e = e with { Dy = .025 * Math.Sin(3.7699 * T) }; break;
            case "gelatina": { var v = .14 * Math.Sin(9.4248 * T); e = e with { Sx = 1 + v, Sy = 1 - v }; break; }
            case "arcoiris": e = e with { Hue = 120 * T }; break;
        }

        var (entry, exit) = Windows(t, duration);
        if (entry > 0 && time < entry) return Window(e, t.EntryEffect, Math.Max(0, time / entry), true);
        if (exit > 0 && time >= duration - exit) return Window(e, t.ExitEffect, Math.Min(1, (time - duration + exit) / exit), false);
        return e;
    }

    /// <summary>
    /// O efeito congelado num instante, como transformação comum: é o que o quadro parado da
    /// prévia monta, sem renderizar a linha do tempo. Estilhaços e partículas viram transparência.
    /// </summary>
    public static StudioTransform Freeze(StudioTransform t, double time, double duration, int width, int height, double? pivotX = null, double? pivotY = null)
    {
        var e = State(t, time, duration);
        var scale = (e.Sx + e.Sy) / 2;
        var px = (pivotX ?? t.X) * width;
        var py = (pivotY ?? t.Y) * height;
        var vx = (t.X * width) - px;
        var vy = (t.Y * height) - py;
        var x = px + (e.Dx * width) + (scale * ((vx * Math.Cos(e.Rotation)) - (vy * Math.Sin(e.Rotation))));
        var y = py + (e.Dy * height) + (scale * ((vx * Math.Sin(e.Rotation)) + (vy * Math.Cos(e.Rotation))));
        var degrees = t.Rotation + (e.Rotation * 180 / Math.PI);
        degrees -= 360 * Math.Round(degrees / 360);
        return t with
        {
            X = Math.Clamp(x / width, -2, 3),
            Y = Math.Clamp(y / height, -2, 3),
            Scale = Math.Clamp(t.Scale * scale, .05, 6),
            Rotation = degrees,
            Opacity = Math.Clamp(t.Opacity * e.Alpha * e.Particles * (1 - e.Pieces), 0, 1),
            EntryEffect = "nenhum",
            ExitEffect = "nenhum",
            LoopEffect = "nenhum"
        };
    }

    public static bool UsesTypewriter(StudioText text) => text.Transform?.EntryEffect == "maquina";

    private static int CountLetters(string text) => text.EnumerateRunes().Count();

    /// <summary>Máquina de escrever: quantas imagens o texto tem e a taxa delas.</summary>
    public static (int Letters, int Steps, double Duration, double Rate) TypewriterSteps(StudioText text, int fps)
    {
        var letters = CountLetters(text.Text.Trim());
        var duration = Math.Max(.1, Math.Min(text.Transform?.EntryDuration ?? DefaultDuration, text.End - text.Start));
        var steps = Math.Max(1, Math.Min(Math.Min(letters, (int)Math.Floor((duration * fps) + .5)), MaxTypewriterSteps));
        return (letters, steps, duration, steps / duration);
    }

    public static int LettersAtStep(int letters, int steps, int step) => (int)Math.Ceiling(step * (double)letters / steps);

    public static int VisibleLetters(StudioText text, double time, int fps)
    {
        var (letters, steps, _, rate) = TypewriterSteps(text, fps);
        return LettersAtStep(letters, steps, Math.Max(1, Math.Min(steps, (int)Math.Floor(time * rate) + 1)));
    }

    private readonly record struct Move(string Dx = "0", string Dy = "0", string? Sx = null, string? Sy = null, string? Rotation = null);

    private static string Movement(Move m, int w, int h, int fps, double px, double py, string r)
    {
        var chain = new List<string>();
        if (m.Rotation is not null)
        {
            var d = Even(Math.Ceiling(Math.Sqrt(((double)w * w) + ((double)h * h))));
            chain.Add($"rotate=a='{m.Rotation}':ow={d}:oh={d}:fillcolor=black@0");
        }

        if (m.Sx is not null) chain.Add($"scale=w='max(2,trunc(iw*({m.Sx})/2)*2)':h='max(2,trunc(ih*({m.Sy})/2)*2)':eval=frame");
        if (chain.Count == 0) chain.Add("setsar=1");
        var x = m.Dx;
        var y = m.Dy;
        if (m.Rotation is not null || m.Sx is not null)
        {
            var cx = N((w / 2.0) - px);
            var cy = N((h / 2.0) - py);
            var a = m.Rotation;
            var vx = a is not null ? $"({cx}*cos({a})-({cy})*sin({a}))" : cx;
            var vy = a is not null ? $"({cx}*sin({a})+({cy})*cos({a}))" : cy;
            x = $"{N(px)}+({m.Dx})+({m.Sx ?? "1"})*({vx})-w/2";
            y = $"{N(py)}+({m.Dy})+({m.Sy ?? "1"})*({vy})-h/2";
        }

        return $"{string.Join(",", chain)}[{r}z];color=c=black@0:s={w}x{h}:r={fps},format=rgba[{r}b];"
            + $"[{r}b][{r}z]overlay=x='{x}':y='{y}':eval=frame:format=auto:shortest=1,format=rgba";
    }

    private static string LoopFilter(string kind, int w, int h, int fps, double px, double py, string r)
    {
        string M(Move m) => Movement(m, w, h, fps, px, py, r);
        switch (kind)
        {
            case "girar": return M(new(Rotation: "PI*t"));
            case "balancar": return M(new(Rotation: "0.26*sin(5.0265*t)"));
            case "pulsar": return M(new(Sx: "(1+0.1*sin(7.5398*t))", Sy: "(1+0.1*sin(7.5398*t))"));
            case "coracao": return M(new(Sx: "(1+0.18*pow(max(0,sin(6.2832*t)),4))", Sy: "(1+0.18*pow(max(0,sin(6.2832*t)),4))"));
            case "tremer": return M(new(Dx: $"{N(.012 * w)}*sin(56.5487*t)", Dy: $"{N(.012 * h)}*sin(69.115*t+1)"));
            case "flutuar": return M(new(Dy: $"{N(.025 * h)}*sin(3.7699*t)"));
            case "gelatina": return M(new(Sx: "(1+0.14*sin(9.4248*t))", Sy: "(1-0.14*sin(9.4248*t))"));
            default: return "format=gbrap,hue=h='120*t',format=rgba";
        }
    }

    private static string Shards(string q, int w, int h, int fps, double px, double py, string r)
    {
        var tw = Even(Math.Ceiling((double)w / Columns));
        var th = Even(Math.Ceiling((double)h / Rows));
        var total = Columns * Rows;
        var side = Even(Math.Ceiling(Math.Sqrt(((double)tw * tw) + ((double)th * th))));
        var reach = 1.2 * Math.Max(w, h);
        var parts = new List<string> { $"split={total}{string.Concat(Enumerable.Range(0, total).Select(k => $"[{r}p{k}]"))}", $"color=c=black@0:s={w}x{h}:r={fps},format=rgba[{r}b0]" };
        for (var k = 0; k < total; k++)
        {
            var x0 = k % Columns * tw;
            var y0 = k / Columns * th;
            var cw = Math.Min(tw, w - x0);
            var ch = Math.Min(th, h - y0);
            if (cw <= 0 || ch <= 0)
            {
                parts.Add($"[{r}p{k}]nullsink");
                continue;
            }

            var cx = x0 + (cw / 2.0);
            var cy = y0 + (ch / 2.0);
            var ax = cx - px;
            var ay = cy - py;
            var d = Math.Max(1, Math.Sqrt((ax * ax) + (ay * ay)));
            var distance = reach * (.5 + Draw(k, 1));
            var vx = N(ax / d * distance);
            var vy = N(ay / d * distance);
            var turn = N((Draw(k, 2) - .5) * 12.5664);
            var next = k == total - 1 ? "" : $"[{r}b{k + 1}]";
            parts.Add($"[{r}p{k}]crop={cw}:{ch}:{x0}:{y0},rotate=a='{turn}*(1-{q})':ow={side}:oh={side}:fillcolor=black@0[{r}t{k}]");
            parts.Add($"[{r}b{k}][{r}t{k}]overlay=x='{N(cx)}+({vx})*(1-{q})-w/2':y='{N(cy)}+({vy})*(1-{q})-h/2':eval=frame:format=auto:shortest=1{next}");
        }

        return string.Join(";", parts);
    }

    private static string Particles(string q, int w, int h, int fps, double d, string r)
    {
        var b = Math.Max(2, (int)Math.Floor((Math.Min(w, h) / 90.0) + .5));
        var mw = (int)Math.Ceiling((double)w / b);
        var mh = (int)Math.Ceiling((double)h / b);
        return $"split[{r}a][{r}c];[{r}a]alphaextract[{r}l];"
            + $"color=c=black:s={mw}x{mh}:r={fps}:d={N(d)},format=gray,geq=lum='255*lt(mod(sin(X*12.9898+Y*78.233)*43758.5453,1)*0.7+X/W*0.3,{q})',scale={w}:{h}:flags=neighbor,format=gray[{r}m];"
            + $"[{r}l][{r}m]blend=all_mode=multiply:shortest=1[{r}n];[{r}c][{r}n]alphamerge";
    }

    private static string WindowFilter(string kind, bool entry, double d, int w, int h, int fps, double px, double py, string r)
    {
        var p = $"clip(t/{N(d)},0,1)";
        var q = entry ? $"(1-pow(1-{p},3))" : $"(1-pow({p},3))";
        var back = entry ? p : $"(1-{p})";
        var fade = $"fade=t={(entry ? "in" : "out")}:st=0:d={N(d)}:alpha=1";
        string M(Move m) => Movement(m, w, h, fps, px, py, r);
        switch (kind)
        {
            case "fade": return fade;
            case "cima": return M(new(Dy: $"-{h}*(1-{q})"));
            case "baixo": return M(new(Dy: $"{h}*(1-{q})"));
            case "esquerda": return M(new(Dx: $"-{w}*(1-{q})"));
            case "direita": return M(new(Dx: $"{w}*(1-{q})"));
            case "zoom": return M(new(Sx: $"max(0.02,{q})", Sy: $"max(0.02,{q})"));
            case "impacto": return $"{M(new(Sx: $"(1+2*(1-{q}))", Sy: $"(1+2*(1-{q}))"))},{fade}";
            case "giro": return M(new(Rotation: $"6.2832*(1-{q})", Sx: $"max(0.02,{q})", Sy: $"max(0.02,{q})"));
            case "quicar": return M(new(Dy: $"-{h}*abs(cos(7.854*{back}))*pow(1-{back},2)"));
            case "estilhacos": return $"{Shards(q, w, h, fps, px, py, r)},format=rgba,{fade}";
            default:
                var g = $"clip(T/{N(d)},0,1)";
                return $"{Particles(entry ? $"(1-pow(1-{g},3))" : $"(1-pow({g},3))", w, h, fps, d, r)},format=rgba";
        }
    }

    /// <summary>A cadeia dos efeitos, para vir logo depois da transformação; começa com vírgula, ou é vazia.</summary>
    public static string Filter(StudioTransform? value, int w, int h, int fps, double duration, string label, double? pivotX = null, double? pivotY = null)
    {
        if (value is not { } t || !Has(t)) return "";
        var px = (pivotX ?? t.X) * w;
        var py = (pivotY ?? t.Y) * h;
        var s = "";
        if (Active(t.LoopEffect)) s += $",setpts=PTS-STARTPTS,{LoopFilter(t.LoopEffect, w, h, fps, px, py, $"{label}c")}";
        var (entry, exit) = Windows(t, duration);
        if (entry <= 0 && exit <= 0) return s;
        var middle = duration - entry - exit > 1e-6;
        var end = duration - exit;
        var parts = new List<(string R, string Trim, string Chain)>();
        if (entry > 0) parts.Add(("e", $"end={N(entry)}", $",{WindowFilter(t.EntryEffect, true, entry, w, h, fps, px, py, $"{label}e")}"));
        if (middle) parts.Add(("m", $"start={N(entry)}:end={N(end)}", ""));
        if (exit > 0) parts.Add(("s", $"start={N(end)}", $",{WindowFilter(t.ExitEffect, false, exit, w, h, fps, px, py, $"{label}s")}"));
        s += ",setpts=PTS-STARTPTS";
        if (parts.Count == 1) return s + parts[0].Chain;
        s += $",split={parts.Count}{string.Concat(parts.Select(p => $"[{label}w{p.R}]"))}";
        foreach (var p in parts) s += $";[{label}w{p.R}]trim={p.Trim},setpts=PTS-STARTPTS{p.Chain},setsar=1[{label}x{p.R}]";
        return $"{s};{string.Concat(parts.Select(p => $"[{label}x{p.R}]"))}concat=n={parts.Count}:v=1:a=0";
    }
}
