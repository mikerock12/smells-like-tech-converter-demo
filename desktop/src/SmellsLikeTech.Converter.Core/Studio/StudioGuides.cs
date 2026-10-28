namespace SmellsLikeTech.Converter.Core.Studio;

/// <summary>A mídia e o quadro, em pixels: a caixa da camada depende dos dois.</summary>
public readonly record struct StudioMeasures(double Width, double Height, double FrameWidth, double FrameHeight)
{
    public bool IsValid => Width > 0 && Height > 0 && FrameWidth > 0 && FrameHeight > 0;
}

/// <summary>A caixa que a camada ocupa no quadro, em frações do lado (0..1).</summary>
public readonly record struct StudioBox(double Left, double Top, double Right, double Bottom)
{
    public double[] Verticals => [Left, (Left + Right) / 2, Right];
    public double[] Horizontals => [Top, (Top + Bottom) / 2, Bottom];
}

/// <summary>Uma linha encaixada: vertical (x fixo) ou horizontal (y fixo). Tipo: centro, borda ou camada.</summary>
public sealed record StudioGuide(bool Vertical, double Position, string Kind);

public sealed record StudioAlignment(StudioTransform Transform, IReadOnlyList<StudioGuide> Guides, bool Straight);

/// <summary>
/// As guias do enquadramento, espelho de packages/converter-core/guias.mjs (e do Guias.kt das
/// figurinhas): o que fica a menos de <see cref="Tolerance"/> de uma linha (centro, borda do quadro,
/// centro ou borda de outra camada) encaixa nela, e o giro encaixa no reto a menos de
/// <see cref="RotationTolerance"/> graus. Os casos de guias-casos.json valem para os três clientes.
/// </summary>
public static class StudioGuides
{
    public const double Tolerance = 0.015;
    public const double RotationTolerance = 3;
    private const double Same = 0.002;
    private static readonly Dictionary<string, int> Priority = new() { ["centro"] = 0, ["borda"] = 1, ["camada"] = 2 };

    /// <summary>Os quatro cantos projetados, em pixels do quadro. Igual a cantosDaCamada (camadas.mjs).</summary>
    public static (double X, double Y)[] Corners(StudioMeasures m, StudioTransform t)
    {
        var a = t.Rotation * Math.PI / 180; var ax = t.RotationX * Math.PI / 180; var ay = t.RotationY * Math.PI / 180;
        var scale = Math.Min(m.FrameWidth / m.Width, m.FrameHeight / m.Height) * t.Scale;
        var camera = Math.Max(m.FrameWidth, m.FrameHeight) * 3;
        (double U, double V)[] uv = [(0, 0), (m.Width, 0), (m.Width, m.Height), (0, m.Height)];
        return uv.Select(p =>
        {
            var x = (p.U - m.Width / 2) * scale; var y = (p.V - m.Height / 2) * scale;
            var y1 = y * Math.Cos(ax); var z1 = y * Math.Sin(ax);
            var x2 = x * Math.Cos(ay) + z1 * Math.Sin(ay); var z2 = -x * Math.Sin(ay) + z1 * Math.Cos(ay);
            var factor = camera / Math.Max(camera * 0.1, camera - z2);
            return (m.FrameWidth * t.X + (x2 * Math.Cos(a) - y1 * Math.Sin(a)) * factor * (t.MirrorX ? -1 : 1),
                m.FrameHeight * t.Y + (x2 * Math.Sin(a) + y1 * Math.Cos(a)) * factor * (t.MirrorY ? -1 : 1));
        }).ToArray();
    }

    public static StudioBox? Box(StudioMeasures m, StudioTransform t)
    {
        if (!m.IsValid) return null;
        var c = Corners(m, t);
        return new StudioBox(c.Min(p => p.X) / m.FrameWidth, c.Min(p => p.Y) / m.FrameHeight, c.Max(p => p.X) / m.FrameWidth, c.Max(p => p.Y) / m.FrameHeight);
    }

    private static List<(double Value, string Kind)> Lines(IReadOnlyList<StudioBox> references, bool vertical) =>
        [(0, "borda"), (0.5, "centro"), (1, "borda"), .. references.SelectMany(r => (vertical ? r.Verticals : r.Horizontals).Select(v => (v, "camada")))];

    /// <summary>O menor deslocamento que leva uma das marcas da caixa a uma das linhas, ou 0.</summary>
    private static double Snap(double[] marks, List<(double Value, string Kind)> lines, double tolerance)
    {
        double best = 0, distance = double.MaxValue;
        foreach (var mark in marks)
            foreach (var (line, _) in lines)
            {
                var d = line - mark;
                if (Math.Abs(d) <= tolerance && Math.Abs(d) < distance) { best = d; distance = Math.Abs(d); }
            }
        return best;
    }

    private static List<StudioGuide> GuidesOf(StudioBox box, List<(double Value, string Kind)> linesX, List<(double Value, string Kind)> linesY)
    {
        var found = linesX.Where(l => box.Verticals.Any(v => Math.Abs(v - l.Value) <= Same)).Select(l => new StudioGuide(true, l.Value, l.Kind))
            .Concat(linesY.Where(l => box.Horizontals.Any(v => Math.Abs(v - l.Value) <= Same)).Select(l => new StudioGuide(false, l.Value, l.Kind)));
        // A mesma posição só uma vez: o centro do quadro vale mais que o de outra camada.
        return found.GroupBy(g => (g.Vertical, (int)Math.Round(g.Position * 1000)))
            .Select(group => group.OrderBy(g => Priority[g.Kind]).First()).ToList();
    }

    private static double NormalizeRotation(double degrees) => ((((degrees + 540) % 360) + 360) % 360) - 180;

    /// <summary>Encaixa a transformação que o gesto propôs nas linhas mais próximas e diz quais ficaram encaixadas.</summary>
    public static StudioAlignment Align(StudioTransform raw, StudioMeasures m, IReadOnlyList<StudioBox>? references = null,
        double tolerance = Tolerance, double rotationTolerance = RotationTolerance)
    {
        references ??= [];
        var t = raw;
        var straightAngle = Math.Round(t.Rotation / 90, MidpointRounding.AwayFromZero) * 90;
        if (Math.Abs(t.Rotation - straightAngle) <= rotationTolerance) t = t with { Rotation = NormalizeRotation(straightAngle) };
        var straight = t.Rotation % 90 == 0;
        if (Box(m, t) is not { } box) return new StudioAlignment(t, [], straight);
        var linesX = Lines(references, true); var linesY = Lines(references, false);
        var dx = Snap(box.Verticals, linesX, tolerance); var dy = Snap(box.Horizontals, linesY, tolerance);
        if (dx != 0 || dy != 0) t = t with { X = t.X + dx, Y = t.Y + dy };
        return new StudioAlignment(t, Box(m, t) is { } final ? GuidesOf(final, linesX, linesY) : [], straight);
    }

    /// <summary>As linhas em que a camada já está, sem mexer nela: para quando a mudança vem dos controles.</summary>
    public static IReadOnlyList<StudioGuide> Guides(StudioMeasures m, StudioTransform t, IReadOnlyList<StudioBox>? references = null) =>
        Box(m, t) is { } box ? GuidesOf(box, Lines(references ?? [], true), Lines(references ?? [], false)) : [];

    private static StudioTransform Straighten(StudioTransform t) => t with { X = .5, Y = .5, Rotation = 0, RotationX = 0, RotationY = 0 };

    /// <summary>Cobre o quadro inteiro, centralizada e reta.</summary>
    public static StudioTransform Fill(StudioTransform t, StudioMeasures m)
    {
        if (!m.IsValid) return Straighten(t);
        var cover = Math.Max(m.FrameWidth / m.Width, m.FrameHeight / m.Height) / Math.Min(m.FrameWidth / m.Width, m.FrameHeight / m.Height);
        return Straighten(t) with { Scale = Math.Min(6, cover) };
    }

    /// <summary>Inteira dentro do quadro, centralizada e reta.</summary>
    public static StudioTransform FitWhole(StudioTransform t) => Straighten(t) with { Scale = 1 };

    /// <summary>Só leva ao centro, sem mudar tamanho nem giro.</summary>
    public static StudioTransform Center(StudioTransform t) => t with { X = .5, Y = .5 };
}
