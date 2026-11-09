using System.IO.MemoryMappedFiles;
using SmellsLikeTech.Converter.Core;
using SmellsLikeTech.Converter.Core.Music;

namespace SmellsLikeTech.Converter.Engine.Music;

/// <summary>
/// WAV float32 lido direto do disco por memoria mapeada: a musica longa nao precisa caber
/// inteira na RAM, e o sistema guarda em cache so o que toca. Cada mixer usa a sua instancia
/// (o buffer de leitura e dela).
/// </summary>
public sealed class MappedWavSource : IMusicSource, IDisposable
{
    private readonly MemoryMappedFile file;
    private readonly MemoryMappedViewAccessor view;
    private readonly long dataOffset;
    private float[] scratch = new float[8192];

    public MappedWavSource(string path)
    {
        using (var stream = File.OpenRead(path))
        {
            var header = new byte[Math.Min(4096, stream.Length)];
            stream.ReadExactly(header);
            (Channels, dataOffset, Frames) = Parse(header, stream.Length);
        }

        file = MemoryMappedFile.CreateFromFile(path, FileMode.Open, null, 0, MemoryMappedFileAccess.Read);
        view = file.CreateViewAccessor(0, 0, MemoryMappedFileAccess.Read);
    }

    public int Channels { get; }
    public long Frames { get; }

    private static (int Channels, long DataOffset, long Frames) Parse(byte[] h, long length)
    {
        if (h.Length < 44 || System.Text.Encoding.ASCII.GetString(h, 0, 4) != "RIFF") throw new ConversionException("invalid_wav", "Mídia do projeto não é um WAV.");
        var p = 12;
        int channels = 0, bits = 0, type = 0;
        while (p + 8 <= h.Length)
        {
            var id = System.Text.Encoding.ASCII.GetString(h, p, 4);
            long size = BitConverter.ToUInt32(h, p + 4);
            if (id == "fmt ")
            {
                type = BitConverter.ToUInt16(h, p + 8);
                if (type == 0xfffe && size >= 26) type = BitConverter.ToUInt16(h, p + 32);
                channels = BitConverter.ToUInt16(h, p + 10);
                bits = BitConverter.ToUInt16(h, p + 22);
            }
            else if (id == "data")
            {
                if (type != 3 || bits != 32) throw new ConversionException("invalid_wav", "As mídias do projeto são WAV float32.");
                var available = length - (p + 8);
                if (size == 0 || size > available) size = available;
                return (channels, p + 8, size / (4 * channels));
            }

            p += 8 + (int)size + (int)(size & 1);
        }

        throw new ConversionException("invalid_wav", "WAV sem dados.");
    }

    public void Copy(int channel, long start, int n, float[] destination)
    {
        var c = Math.Min(channel, Channels - 1);
        var available = (int)Math.Clamp(Frames - start, 0, n);
        if (available > 0)
        {
            var needed = available * Channels;
            if (scratch.Length < needed) scratch = new float[needed];
            view.ReadArray(dataOffset + start * Channels * 4, scratch, 0, needed);
            for (var i = 0; i < available; i++) destination[i] = scratch[i * Channels + c];
        }

        Array.Clear(destination, Math.Max(0, available), n - Math.Max(0, available));
    }

    /// <summary>Le o arquivo uma vez em segundo plano para o cache do sistema (o primeiro play nao espera o disco).</summary>
    public static Task WarmAsync(string path, CancellationToken token = default) => Task.Run(() =>
    {
        try
        {
            using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite, 1 << 20, FileOptions.SequentialScan);
            var buffer = new byte[1 << 20];
            while (!token.IsCancellationRequested && stream.Read(buffer, 0, buffer.Length) > 0) { }
        }
        catch (IOException)
        {
        }
    }, token);

    public void Dispose()
    {
        view.Dispose();
        file.Dispose();
    }
}

/// <summary>
/// Escreve WAV float32 aos pedacos, com flush no disco a cada segundo: se o app cair no meio
/// da gravacao, o arquivo abre (o tamanho do cabecalho e recuperado pelo leitor).
/// </summary>
public sealed class StreamingWavWriter : IDisposable
{
    private readonly FileStream stream;
    private readonly BinaryWriter writer;
    private readonly int channels;
    private readonly int rate;
    private DateTime lastFlush = DateTime.UtcNow;
    private byte[] buffer = new byte[65536];

    public StreamingWavWriter(string path, int channels, int rate)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        this.channels = channels;
        this.rate = rate;
        stream = new FileStream(path, FileMode.Create, FileAccess.Write, FileShare.Read, 1 << 16);
        writer = new BinaryWriter(stream);
        MusicMastering.WriteWavHeader(writer, channels, rate, 32, 0);
        writer.Flush();
    }

    public long Frames { get; private set; }

    /// <summary>Recebe os canais separados (um array por canal) e intercala.</summary>
    public void Write(IReadOnlyList<float[]> data, int offset, int n)
    {
        var bytes = n * channels * 4;
        if (buffer.Length < bytes) buffer = new byte[bytes];
        var span = buffer.AsSpan(0, bytes);
        var floats = System.Runtime.InteropServices.MemoryMarshal.Cast<byte, float>(span);
        for (var i = 0; i < n; i++)
        for (var c = 0; c < channels; c++)
            floats[i * channels + c] = data[Math.Min(c, data.Count - 1)][offset + i];
        stream.Write(span);
        Frames += n;
        if ((DateTime.UtcNow - lastFlush).TotalSeconds >= 1)
        {
            stream.Flush(true);
            lastFlush = DateTime.UtcNow;
        }
    }

    /// <summary>Intercalado ja pronto (canais × quadros).</summary>
    public void WriteInterleaved(ReadOnlySpan<float> interleaved)
    {
        stream.Write(System.Runtime.InteropServices.MemoryMarshal.AsBytes(interleaved));
        Frames += interleaved.Length / channels;
        if ((DateTime.UtcNow - lastFlush).TotalSeconds >= 1)
        {
            stream.Flush(true);
            lastFlush = DateTime.UtcNow;
        }
    }

    public void Dispose()
    {
        stream.Position = 0;
        MusicMastering.WriteWavHeader(writer, channels, rate, 32, Frames);
        writer.Flush();
        stream.Flush(true);
        writer.Dispose();
    }
}
