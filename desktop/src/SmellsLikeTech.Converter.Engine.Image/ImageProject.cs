using System.IO.Compression;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
using ImageMagick;
using SkiaSharp;

namespace SmellsLikeTech.Converter.Engine.Image;

public sealed record ImageFrame(int Width, int Height, string ColorSpace = "sRGB", string PixelFormat = "RGBA8");
public sealed record ImageLayer(string Tipo, string Nome)
{
    public string Id { get; init; } = Guid.NewGuid().ToString();
    public string? Asset { get; init; }
    public string? Texto { get; init; }
    public string Cor { get; init; } = "#ffffff";
    public float Tamanho { get; init; } = 48;
    public float X { get; init; }
    public float Y { get; init; }
    public float Escala { get; init; } = 1;
    public float Angulo { get; init; }
    public float Opacidade { get; init; } = 1;
    public bool Visivel { get; init; } = true;
    public float Exposicao { get; init; }
    public float Contraste { get; init; } = 1;
    public float Saturacao { get; init; } = 1;
}
public sealed record ImageDocument(string Title, ImageFrame Canvas, ImageLayer[] Layers)
{
    public string DocumentId { get; init; } = Guid.NewGuid().ToString();
    public int SchemaVersion { get; init; } = 1;
}

public sealed class ImageProject
{
    public const string Mime = "application/vnd.smellsliketech.image-project+zip";
    private const int MiB = 1024 * 1024;
    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web)
    {
        DefaultIgnoreCondition = System.Text.Json.Serialization.JsonIgnoreCondition.WhenWritingNull
    };
    private readonly Dictionary<string, byte[]> assets = [];
    private readonly Dictionary<string, ImageFrame> sizes = [];
    public ImageDocument Document { get; private set; }
    private ImageProject(ImageDocument doc) => Document = doc;

    private static void Check(bool valid)
    {
        if (!valid) throw new InvalidDataException("Estrutura inválida ou acima dos limites do editor de imagem.");
    }
    private static bool Dimension(long w, long h) => w is > 0 and <= 16384 && h is > 0 and <= 16384 && w * h <= 16 * MiB;
    private static bool Id(string? id) => id is not null && Regex.IsMatch(id, @"\A[a-zA-Z0-9-]{1,64}\z");
    private static void Validate(ImageDocument doc)
    {
        Check(doc.SchemaVersion == 1 && Id(doc.DocumentId) && doc.Title is { Length: <= 512 } &&
            doc.Canvas is { ColorSpace: "sRGB", PixelFormat: "RGBA8" } && Dimension(doc.Canvas.Width, doc.Canvas.Height) &&
            doc.Layers is { Length: <= 64 });
        var seen = new HashSet<string>();
        foreach (var c in doc.Layers)
        {
            Check(c is not null && Id(c.Id) && seen.Add(c.Id) && c.Nome is { Length: <= 512 } &&
                Regex.IsMatch(c.Cor ?? "", @"\A#[0-9a-fA-F]{6}\z") && c.X is >= -32768 and <= 32768 &&
                c.Y is >= -32768 and <= 32768 && c.Angulo is >= -360 and <= 360 && c.Tamanho is >= 1 and <= 4096 &&
                c.Escala is >= 0.01f and <= 16 && c.Opacidade is >= 0 and <= 1 && c.Exposicao is >= -4 and <= 4 &&
                c.Contraste is >= 0 and <= 4 && c.Saturacao is >= 0 and <= 4 &&
                (c.Tipo == "texto" && c.Texto is { Length: <= 4096 } ||
                 c.Tipo == "foto" && Regex.IsMatch(c.Asset ?? "", @"\A[a-f0-9]{64}\z")));
        }
    }
    public void Apply(ImageDocument doc)
    {
        Validate(doc);
        Check(doc.Layers.All(c => c.Tipo != "foto" || assets.ContainsKey(c.Asset!)));
        Document = doc;
    }
    private (string Id, ImageFrame Frame) Import(byte[] bytes)
    {
        Check(bytes.Length is > 0 and <= 64 * MiB);
        var id = Convert.ToHexStringLower(SHA256.HashData(bytes));
        if (sizes.TryGetValue(id, out var existing)) return (id, existing);
        var info = new MagickImageInfo(bytes);
        Check(info.Format is MagickFormat.Png or MagickFormat.Jpeg or MagickFormat.WebP or MagickFormat.Jxl);
        Check(Dimension(info.Width, info.Height));
        var size = new ImageFrame((int)info.Width, (int)info.Height);
        Check(assets.Values.Sum(b => (long)b.Length) + bytes.Length <= 127 * MiB &&
            sizes.Values.Sum(s => (long)s.Width * s.Height) + (long)size.Width * size.Height <= 32 * MiB);
        assets.Add(id, bytes);
        sizes.Add(id, size);
        return (id, size);
    }
    public static ImageProject FromPhoto(string name, byte[] bytes)
    {
        var p = new ImageProject(new ImageDocument(name, new(1, 1), []));
        p.AddPhoto(name, bytes, true);
        return p;
    }
    public void AddPhoto(string name, byte[] bytes, bool usePhotoSize = false)
    {
        Check(Document.Layers.Length < 64 && name.Length <= 512);
        var (id, frame) = Import(bytes);
        Apply(Document with { Canvas = usePhotoSize ? frame : Document.Canvas,
            Layers = [..Document.Layers, new ImageLayer("foto", name) { Asset = id }] });
    }
    public static ImageProject Open(Stream input)
    {
        Check(input.CanSeek && input.Length <= 128 * MiB);
        using var zip = new ZipArchive(input, ZipArchiveMode.Read, true);
        Check(zip.Entries.Count <= 66);
        var entries = new Dictionary<string, ZipArchiveEntry>();
        long total = 0;
        foreach (var e in zip.Entries)
        {
            var limit = e.FullName switch { "mimetype" => Mime.Length, "manifest.json" => MiB,
                _ when Regex.IsMatch(e.FullName, @"\Aassets/[a-f0-9]{64}\z") => 64 * MiB, _ => -1 };
            Check(e.Length <= limit && e.Length == e.CompressedLength && (total += e.Length) <= 128 * MiB &&
                entries.TryAdd(e.FullName, e));
        }
        byte[] Read(string name)
        {
            if (!entries.TryGetValue(name, out var entry)) throw new InvalidDataException("Entrada ausente no projeto.");
            using var stream = entry.Open();
            var data = new byte[(int)entry.Length];
            stream.ReadExactly(data);
            Check(stream.ReadByte() == -1);
            return data;
        }
        Check(Encoding.UTF8.GetString(Read("mimetype")) == Mime);
        var doc = JsonSerializer.Deserialize<ImageDocument>(Read("manifest.json"), Json) ?? throw new InvalidDataException();
        Validate(doc);
        var project = new ImageProject(doc);
        foreach (var id in doc.Layers.Where(c => c.Tipo == "foto").Select(c => c.Asset!).Distinct())
            Check(project.Import(Read("assets/" + id)).Id == id);
        return project;
    }
    public void Save(Stream output)
    {
        using var zip = new ZipArchive(output, ZipArchiveMode.Create, true);
        void Write(string name, byte[] bytes)
        {
            using var dest = zip.CreateEntry(name, CompressionLevel.NoCompression).Open();
            dest.Write(bytes);
        }
        Write("mimetype", Encoding.UTF8.GetBytes(Mime));
        Write("manifest.json", JsonSerializer.SerializeToUtf8Bytes(Document, Json));
        foreach (var id in Document.Layers.Where(c => c.Tipo == "foto").Select(c => c.Asset!).Distinct())
            Write("assets/" + id, assets[id]);
    }
    public byte[] Render(MagickFormat format)
    {
        using var bitmap = new SKBitmap(Document.Canvas.Width, Document.Canvas.Height);
        using var canvas = new SKCanvas(bitmap);
        canvas.Clear(SKColors.Transparent);
        foreach (var c in Document.Layers.Where(c => c.Visivel))
        {
            using var restore = new SKAutoCanvasRestore(canvas);
            canvas.Translate(c.X, c.Y);
            canvas.RotateDegrees(c.Angulo);
            canvas.Scale(c.Escala);
            float gain = MathF.Pow(2, c.Exposicao) * c.Contraste, offset = (1-c.Contraste)/2;
            float r = .2126f*(1-c.Saturacao), g = .7152f*(1-c.Saturacao), b = .0722f*(1-c.Saturacao);
            using var filter = SKColorFilter.CreateColorMatrix([
                gain*(r+c.Saturacao),gain*g,gain*b,0,offset, gain*r,gain*(g+c.Saturacao),gain*b,0,offset,
                gain*r,gain*g,gain*(b+c.Saturacao),0,offset, 0,0,0,1,0]);
            using var paint = new SKPaint { IsAntialias = true, ColorFilter = filter,
                Color = (c.Tipo == "foto" ? SKColors.White : SKColor.Parse(c.Cor)).WithAlpha((byte)Math.Round(c.Opacidade * 255)) };
            if (c.Tipo == "texto")
            {
                using var font = new SKFont(SKTypeface.Default, c.Tamanho);
                canvas.DrawText(c.Texto!, 0, -font.Metrics.Ascent, SKTextAlign.Left, font, paint);
            }
            else
            {
                using var source = new MagickImage(assets[c.Asset!]);
                using var photo = SKBitmap.Decode(source.ToByteArray(MagickFormat.Png));
                canvas.DrawBitmap(photo, 0, 0, paint);
            }
        }
        using var png = bitmap.Encode(SKEncodedImageFormat.Png, 100);
        if (format == MagickFormat.Png) return png.ToArray();
        using var result = new MagickImage(png.ToArray()) { Quality = 100 };
        return result.ToByteArray(format);
    }
}
