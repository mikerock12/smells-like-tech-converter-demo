using System.Globalization;
using System.Security.Cryptography;
using System.Text;

namespace SmellsLikeTech.Converter.Engine.Studio;

/// <summary>
/// Guarda o vídeo já recortado da prévia: mexer no giro, na cor ou no texto não refaz o recorte
/// do fundo, que é a parte cara. A chave muda se o arquivo, o trecho, o tamanho, o fps ou o
/// intervalo das máscaras mudar.
/// </summary>
public static class RemovalCache
{
    /// <summary>Teto da pasta; os recortes usados há mais tempo saem primeiro.</summary>
    public const long MaxBytes = 2L * 1024 * 1024 * 1024;

    public static string PathFor(string directory, string source, double start, double duration, int width, int height, int fps, int maskEvery = 1)
    {
        Directory.CreateDirectory(directory);
        var file = new FileInfo(source);
        // O começo da chave muda com o jeito de recortar: 2 é a máscara limpa, sem os resquícios.
        var key = string.Join('|', "2", Path.GetFullPath(source).ToLowerInvariant(), file.Length.ToString(CultureInfo.InvariantCulture),
            file.LastWriteTimeUtc.Ticks.ToString(CultureInfo.InvariantCulture), start.ToString("R", CultureInfo.InvariantCulture),
            duration.ToString("R", CultureInfo.InvariantCulture), width, height, fps, maskEvery);
        var hash = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(key)))[..24].ToLowerInvariant();
        var path = Path.Combine(directory, $"recorte-{hash}.mkv");
        if (File.Exists(path))
        {
            try { File.SetLastWriteTimeUtc(path, DateTime.UtcNow); } catch (IOException) { }
        }

        return path;
    }

    /// <summary>Apaga os mais antigos até caber no teto, sem tocar no que acabou de ser gravado.</summary>
    public static void Trim(string directory, string keep, long maxBytes = MaxBytes)
    {
        try
        {
            var files = new DirectoryInfo(directory).GetFiles("recorte-*.mkv").OrderByDescending(f => f.LastWriteTimeUtc).ToList();
            var total = files.Sum(f => f.Length);
            foreach (var file in files.AsEnumerable().Reverse())
            {
                if (total <= maxBytes) break;
                if (string.Equals(file.FullName, Path.GetFullPath(keep), StringComparison.OrdinalIgnoreCase)) continue;
                total -= file.Length;
                file.Delete();
            }
        }
        catch (Exception exception) when (exception is IOException or UnauthorizedAccessException)
        {
            // Limpar o cache nunca pode derrubar a prévia.
        }
    }
}
