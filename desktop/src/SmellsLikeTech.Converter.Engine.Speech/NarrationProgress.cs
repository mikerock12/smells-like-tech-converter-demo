namespace SmellsLikeTech.Converter.Engine.Speech;

/// <summary>Mesmas regras do Android/site: caracteres concluídos e janela de oito blocos.</summary>
public sealed class NarrationProgress
{
    private readonly int[] sizes;
    private readonly Func<long> clock;
    private readonly long total;
    private readonly Queue<(long Milliseconds, int Characters)> samples = new();
    private long started;
    private long characters;
    private int completed;

    public NarrationProgress(IEnumerable<int> sizes, Func<long>? clock = null)
    {
        this.sizes = sizes.ToArray();
        if (this.sizes.Length == 0 || this.sizes.Any(size => size <= 0))
            throw new ArgumentException("Blocos inválidos.", nameof(sizes));
        this.clock = clock ?? (() => Environment.TickCount64);
        total = this.sizes.Sum(size => (long)size);
        started = this.clock();
    }

    public void CompleteChunk()
    {
        if (completed >= sizes.Length) return;
        var now = clock();
        var size = sizes[completed++];
        samples.Enqueue((Math.Max(1, now - started), size));
        if (samples.Count > 8) samples.Dequeue();
        characters += size;
        started = now;
    }

    public (double Fraction, TimeSpan? Eta) Snapshot()
    {
        var fraction = (double)characters / total;
        var milliseconds = samples.Sum(sample => sample.Milliseconds);
        if (completed < 3 || milliseconds < 5_000 || completed == sizes.Length) return (fraction, null);
        var rate = (double)milliseconds / samples.Sum(sample => sample.Characters);
        var predicted = sizes[completed] * rate;
        var elapsed = Math.Max(0, clock() - started);
        if (elapsed > Math.Max(30_000, predicted * 2)) return (fraction, null);
        var remaining = (total - characters - sizes[completed]) * rate +
            Math.Max(predicted - elapsed, predicted * 0.2);
        return (fraction, TimeSpan.FromSeconds(Math.Ceiling(remaining / 1_000)));
    }
}
