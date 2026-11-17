using System.Diagnostics;
using System.Net.Http;
using System.Reflection;
using System.Security.Cryptography;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace SmellsLikeTech.Converter.Desktop.Services;

/// <summary>Uma versão publicada do instalador (o mesmo manifesto que o site usa em /baixar).</summary>
public sealed record AppRelease(Version Version, string File, long Bytes, string Sha256, string Published);

/// <summary>
/// Procurar atualização: lê o manifesto público do site (/baixar/app.json), baixa o instalador
/// com conferência de tamanho e SHA-256 e, quando a pessoa pede, instala em silêncio e reabre
/// o aplicativo. Nada é instalado sem o clique; o arquivo baixado só roda se o hash bater.
/// </summary>
public static partial class AppUpdater
{
    public const string Site = "https://converter.smellsliketech.com.br";
    private const string Manifest = Site + "/baixar/app.json";

    [GeneratedRegex(@"^SmellsLikeTechConverterSetup-\d+\.\d+\.\d+\.exe$")]
    private static partial Regex InstallerName();

    public static Version Current
    {
        get
        {
            var v = Assembly.GetEntryAssembly()?.GetName().Version ?? new Version(0, 0, 0);
            return new Version(v.Major, v.Minor, Math.Max(0, v.Build));
        }
    }

    public static string Folder => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "SmellsLikeTech", "Atualizacoes");

    /// <summary>A versão publicada no site, ou erro de rede/manifesto.</summary>
    public static async Task<AppRelease> LatestAsync(HttpClient http, CancellationToken token)
    {
        using var request = new HttpRequestMessage(HttpMethod.Get, Manifest);
        request.Headers.UserAgent.ParseAdd($"SmellsLikeTechConverter/{Current}");
        request.Headers.CacheControl = new System.Net.Http.Headers.CacheControlHeaderValue { NoCache = true };
        using var response = await http.SendAsync(request, token);
        response.EnsureSuccessStatusCode();
        await using var stream = await response.Content.ReadAsStreamAsync(token);
        using var json = await JsonDocument.ParseAsync(stream, cancellationToken: token);
        var root = json.RootElement;
        var file = root.GetProperty("arquivo").GetString() ?? "";
        var sha = root.GetProperty("sha256").GetString() ?? "";
        if (!InstallerName().IsMatch(file) || sha.Length != 64 || !Version.TryParse(root.GetProperty("versao").GetString(), out var version))
            throw new InvalidDataException("O manifesto de atualização do site não está no formato esperado.");
        return new AppRelease(version, file, root.GetProperty("bytes").GetInt64(), sha.ToLowerInvariant(), root.TryGetProperty("publicado", out var p) ? p.GetString() ?? "" : "");
    }

    public static bool IsNewer(AppRelease release) => release.Version > Current;

    /// <summary>Baixa (ou reaproveita) o instalador e confere tamanho e SHA-256 antes de devolver o caminho.</summary>
    public static async Task<string> DownloadAsync(HttpClient http, AppRelease release, IProgress<double>? progress, CancellationToken token)
    {
        Directory.CreateDirectory(Folder);
        var target = Path.Combine(Folder, release.File);
        if (File.Exists(target) && new FileInfo(target).Length == release.Bytes && await HashAsync(target, token) == release.Sha256) return target;
        foreach (var old in Directory.EnumerateFiles(Folder, "SmellsLikeTechConverterSetup-*")) TryDelete(old);
        var partial = target + ".parcial";
        using (var request = new HttpRequestMessage(HttpMethod.Get, $"{Site}/baixar/{release.File}"))
        {
            request.Headers.UserAgent.ParseAdd($"SmellsLikeTechConverter/{Current}");
            using var response = await http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, token);
            response.EnsureSuccessStatusCode();
            await using var input = await response.Content.ReadAsStreamAsync(token);
            await using var output = File.Create(partial);
            using var hash = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
            var buffer = new byte[1 << 20];
            long received = 0;
            for (int n; (n = await input.ReadAsync(buffer, token)) > 0;)
            {
                received += n;
                if (received > release.Bytes) throw new InvalidDataException("O instalador baixado é maior que o anunciado; foi descartado.");
                hash.AppendData(buffer, 0, n);
                await output.WriteAsync(buffer.AsMemory(0, n), token);
                progress?.Report((double)received / release.Bytes);
            }
            if (received != release.Bytes || Convert.ToHexStringLower(hash.GetHashAndReset()) != release.Sha256)
            {
                output.Close();
                TryDelete(partial);
                throw new InvalidDataException("O instalador baixado não confere com o SHA-256 publicado; foi descartado.");
            }
        }
        File.Move(partial, target, overwrite: true);
        return target;
    }

    /// <summary>Instala em silêncio (fecha o aplicativo, atualiza e reabre) e encerra este processo.</summary>
    public static void InstallAndRestart(string installer)
    {
        Process.Start(new ProcessStartInfo(installer, "/SILENT /SUPPRESSMSGBOXES /NORESTART /CLOSEAPPLICATIONS /REABRIR=1") { UseShellExecute = true });
        Microsoft.UI.Xaml.Application.Current.Exit();
    }

    private static async Task<string> HashAsync(string path, CancellationToken token)
    {
        await using var stream = File.OpenRead(path);
        return Convert.ToHexStringLower(await SHA256.HashDataAsync(stream, token));
    }

    private static void TryDelete(string path)
    {
        try { File.Delete(path); } catch (IOException) { } catch (UnauthorizedAccessException) { }
    }
}
