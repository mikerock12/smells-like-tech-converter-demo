namespace SmellsLikeTech.Converter.Core.Studio;

/// <summary>
/// Limpeza da máscara da remoção de fundo: tira as manchas soltas e aperta a borda, para não
/// sobrar névoa nem halo do fundo. Espelho de recorte-mascara.mjs e RefinoDaMascara.kt,
/// conferido por recorte-casos.json.
/// </summary>
public static class StudioMask
{
    public const float IslandCut = .5f;
    public const float MinimumIsland = .02f;
    public const float Low = .45f;
    public const float High = .85f;

    /// <summary>Encurta a transição da borda e a leva para dentro da figura.</summary>
    public static float Curve(float value)
    {
        var t = Math.Clamp((value - Low) / (High - Low), 0f, 1f);
        return t * t * (3 - (2 * t));
    }

    /// <summary>Tira as manchas soltas, no lugar. Devolve quantas saíram.</summary>
    public static int RemoveIslands(float[] mask, int width, int height)
    {
        var labels = new int[width * height];
        var sizes = new List<int> { 0 };
        var stack = new int[width * height];
        for (var start = 0; start < mask.Length; start++)
        {
            if (labels[start] != 0 || mask[start] < IslandCut) continue;
            var label = sizes.Count;
            var top = 0;
            var size = 0;
            stack[top++] = start;
            labels[start] = label;
            while (top > 0)
            {
                var point = stack[--top];
                size++;
                var x = point % width;
                Span<int> neighbours = [x > 0 ? point - 1 : -1, x < width - 1 ? point + 1 : -1, point - width, point + width];
                foreach (var neighbour in neighbours)
                {
                    if (neighbour < 0 || neighbour >= mask.Length || labels[neighbour] != 0 || mask[neighbour] < IslandCut) continue;
                    labels[neighbour] = label;
                    stack[top++] = neighbour;
                }
            }

            sizes.Add(size);
        }

        var limit = sizes.Max() * MinimumIsland;
        for (var i = 0; i < mask.Length; i++)
        {
            if (labels[i] != 0 && sizes[labels[i]] < limit) mask[i] = 0;
        }

        return sizes.Skip(1).Count(size => size < limit);
    }
}
