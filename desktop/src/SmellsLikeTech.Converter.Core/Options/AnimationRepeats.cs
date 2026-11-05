namespace SmellsLikeTech.Converter.Core.Options;

/// <summary>Quantas vezes uma animação toca; 0 é sem fim. Espelho de repeticoes.mjs.</summary>
public static class AnimationRepeats
{
    public static IReadOnlyList<int> Allowed { get; } = [0, 3, 5, 10];

    public static string Label(int repeats) => repeats == 0 ? "Sem fim" : $"{repeats} vezes";

    /// <summary>O GIF guarda quantas vezes repete depois da primeira: tocar 3 vezes é gravar 2.</summary>
    public static int GifLoop(int repeats) => repeats > 0 ? repeats - 1 : 0;
}
