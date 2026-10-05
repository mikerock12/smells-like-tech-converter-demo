using System.Runtime.Versioning;
using System.Text;
using SmellsLikeTech.Converter.Core;
using Windows.Globalization;
using Windows.Graphics.Imaging;
using Windows.Media.Ocr;
using Windows.Storage.Streams;

namespace SmellsLikeTech.Converter.Engine.Documents;

/// <summary>
/// Reconhecimento de texto pelo motor do proprio Windows. Nao exige instalar Tesseract
/// nem baixar dados de idioma: usa os pacotes de reconhecimento ja presentes no sistema.
/// </summary>
[SupportedOSPlatform("windows10.0.19041.0")]
public sealed class WindowsTextRecognizer
{
    /// <summary>Idiomas que este computador consegue reconhecer.</summary>
    public static IReadOnlyList<string> AvailableLanguages()
    {
        try
        {
            return [.. OcrEngine.AvailableRecognizerLanguages.Select(language => language.LanguageTag)];
        }
        catch (Exception exception) when (exception is TypeInitializationException or NotSupportedException)
        {
            return [];
        }
    }

    public static bool IsAvailable => AvailableLanguages().Count > 0;

    /// <summary>
    /// Motor para o idioma pedido. Se ele nao estiver instalado, tenta a variante mais
    /// generica ("pt-BR" -> "pt") e, por fim, o idioma do perfil do usuario.
    /// </summary>
    internal static OcrEngine Resolve(string language)
    {
        var candidates = new List<string> { language };

        var separator = language.IndexOf('-');
        if (separator > 0)
        {
            candidates.Add(language[..separator]);
        }
        else
        {
            candidates.AddRange(
                AvailableLanguages().Where(tag => tag.StartsWith(language + "-", StringComparison.OrdinalIgnoreCase)));
        }

        foreach (var candidate in candidates)
        {
            try
            {
                var engine = OcrEngine.TryCreateFromLanguage(new Language(candidate));
                if (engine is not null)
                {
                    return engine;
                }
            }
            catch (ArgumentException)
            {
                // Marca de idioma invalida para o Windows; tenta a proxima.
            }
        }

        var fallback = OcrEngine.TryCreateFromUserProfileLanguages();
        if (fallback is not null)
        {
            return fallback;
        }

        var installed = AvailableLanguages();
        throw new ConversionException(
            "ocr_language_not_installed",
            installed.Count == 0
                ? "Este computador não tem nenhum pacote de reconhecimento de texto instalado."
                : $"O idioma '{language}' não está instalado. Disponíveis: {string.Join(", ", installed)}.");
    }

    /// <summary>Le o texto de um arquivo de imagem.</summary>
    public async Task<string> ReadFileAsync(string imagePath, string language, bool keepLineBreaks, CancellationToken cancellationToken)
    {
        var bytes = await File.ReadAllBytesAsync(imagePath, cancellationToken);
        return await ReadAsync(bytes, language, keepLineBreaks, cancellationToken);
    }

    /// <summary>Le o texto de uma imagem ja em memoria.</summary>
    public async Task<string> ReadAsync(byte[] image, string language, bool keepLineBreaks, CancellationToken cancellationToken)
    {
        var engine = Resolve(language);

        using var stream = new InMemoryRandomAccessStream();
        using (var writer = new DataWriter(stream))
        {
            writer.WriteBytes(image);
            await writer.StoreAsync().AsTask(cancellationToken);
            await writer.FlushAsync().AsTask(cancellationToken);
            writer.DetachStream();
        }

        stream.Seek(0);

        BitmapDecoder decoder;
        try
        {
            decoder = await BitmapDecoder.CreateAsync(stream).AsTask(cancellationToken);
        }
        catch (Exception exception) when (exception is ArgumentException or System.Runtime.InteropServices.COMException)
        {
            throw new ConversionException("invalid_image", "A imagem não pôde ser lida para reconhecimento.", exception);
        }

        using var bitmap = await decoder.GetSoftwareBitmapAsync().AsTask(cancellationToken);
        var result = await engine.RecognizeAsync(bitmap).AsTask(cancellationToken);

        if (!keepLineBreaks)
        {
            return result.Text.Trim();
        }

        var builder = new StringBuilder();
        foreach (var line in result.Lines)
        {
            builder.AppendLine(line.Text);
        }

        return builder.ToString().TrimEnd();
    }
}
