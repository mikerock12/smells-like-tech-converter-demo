using System.IO.Compression;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text.Json;
using SmellsLikeTech.Converter.Core;
using SmellsLikeTech.Converter.Core.Music;

namespace SmellsLikeTech.Converter.Engine.Music;

/// <summary>Um plugin da biblioteca (packages/converter-core/musica-catalogo/efeitos.json, "bibliotecaVst3").</summary>
public sealed record MusicVstEntry(string Id, string Name, string Group, string Use, string License, string Version, string Source,
    string Bundle, bool Preinstalled, string File, long Bytes, string Sha256);

/// <summary>
/// Biblioteca de VST3 gratuitos e de código aberto: os incluídos vêm no instalador (pasta VST3
/// do aplicativo); os extras são baixados do site (R2) sob demanda, conferidos pelo SHA-256 e
/// guardados em &lt;dados&gt;\Plugins\VST3. A busca de plugins junta essas pastas às pastas
/// padrão do Windows (Arquivos comuns\VST3).
/// </summary>
public sealed class MusicVstLibrary(string dataRoot)
{
    public const string Site = "https://converter.smellsliketech.com.br";

    public static string BundledFolder => Path.Combine(AppContext.BaseDirectory, "VST3");
    public string ExtrasFolder => Path.Combine(dataRoot, "Plugins", "VST3");

    private static readonly Lazy<IReadOnlyList<MusicVstEntry>> Catalog = new(() =>
    {
        using var stream = typeof(MusicDsp).Assembly.GetManifestResourceStream("musica-efeitos.json")
            ?? throw new InvalidOperationException("Catálogo de efeitos indisponível.");
        using var json = JsonDocument.Parse(stream);
        return [.. json.RootElement.GetProperty("bibliotecaVst3").EnumerateArray().Select(x => new MusicVstEntry(
            x.GetProperty("id").GetString()!, x.GetProperty("nome").GetString()!, x.GetProperty("grupo").GetString()!,
            x.GetProperty("uso").GetString()!, x.GetProperty("licenca").GetString()!, x.GetProperty("versao").GetString()!,
            x.GetProperty("fonte").GetString()!, x.GetProperty("pacote").GetString()!, x.GetProperty("preinstalado").GetBoolean(),
            x.GetProperty("arquivo").GetString()!, x.GetProperty("bytes").GetInt64(), x.GetProperty("sha256").GetString()!))];
    });

    public static IReadOnlyList<MusicVstEntry> Entries => Catalog.Value;

    /// <summary>Onde o bundle do plugin está nesta máquina (incluído ou baixado), ou nulo.</summary>
    public string? Installed(MusicVstEntry entry)
    {
        foreach (var folder in new[] { BundledFolder, ExtrasFolder })
        {
            var path = Path.Combine(folder, entry.Bundle);
            if (Directory.Exists(path)) return path;
        }
        return null;
    }

    /// <summary>Todos os efeitos VST3: pastas padrão do Windows, a biblioteca incluída e os extras baixados.</summary>
    public IReadOnlyList<MusicNative.PluginInfo> Scan()
    {
        if (!MusicNative.HasVst3) return [];
        var found = new List<MusicNative.PluginInfo>(MusicNative.ScanPlugins());
        foreach (var folder in new[] { BundledFolder, ExtrasFolder })
        {
            if (!Directory.Exists(folder)) continue;
            foreach (var bundle in Directory.EnumerateDirectories(folder, "*.vst3"))
            {
                try { found.AddRange(MusicNative.ScanPlugins(bundle)); }
                catch (InvalidOperationException) { } // plugin que falhou fica em quarentena na sessão
            }
        }
        return [.. found.GroupBy(p => (p.Path.ToLowerInvariant(), p.ClassId)).Select(g => g.First()).OrderBy(p => p.Name, StringComparer.CurrentCultureIgnoreCase)];
    }

    /// <summary>Baixa um extra do site, confere tamanho e SHA-256 e extrai em <see cref="ExtrasFolder"/>.</summary>
    public async Task InstallAsync(HttpClient http, MusicVstEntry entry, IProgress<double>? progress, CancellationToken token)
    {
        if (!entry.File.StartsWith("/modelos/vst3/", StringComparison.Ordinal) || entry.File.Contains("..", StringComparison.Ordinal))
            throw new ConversionException("invalid_plugin", "Endereço do plugin fora da biblioteca.");
        Directory.CreateDirectory(ExtrasFolder);
        var temp = Path.Combine(ExtrasFolder, $".{entry.Id}-{Guid.NewGuid():N}.zip");
        try
        {
            using (var response = await http.GetAsync(Site + entry.File, HttpCompletionOption.ResponseHeadersRead, token))
            {
                response.EnsureSuccessStatusCode();
                await using var input = await response.Content.ReadAsStreamAsync(token);
                await using var output = File.Create(temp);
                using var hash = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
                var buffer = new byte[1 << 16];
                long received = 0;
                for (int n; (n = await input.ReadAsync(buffer, token)) > 0;)
                {
                    received += n;
                    if (received > entry.Bytes) throw new ConversionException("plugin_mismatch", "O plugin baixado é maior que o esperado; foi descartado.");
                    hash.AppendData(buffer, 0, n);
                    await output.WriteAsync(buffer.AsMemory(0, n), token);
                    progress?.Report((double)received / entry.Bytes);
                }
                if (received != entry.Bytes || Convert.ToHexStringLower(hash.GetHashAndReset()) != entry.Sha256)
                    throw new ConversionException("plugin_mismatch", "O plugin baixado não confere com o SHA-256 da biblioteca; foi descartado.");
            }
            var destination = Path.Combine(ExtrasFolder, entry.Bundle);
            if (Directory.Exists(destination)) Directory.Delete(destination, true);
            using var zip = ZipFile.OpenRead(temp);
            var root = Path.GetFullPath(ExtrasFolder) + Path.DirectorySeparatorChar;
            foreach (var item in zip.Entries)
            {
                // Só o próprio bundle, sem sair da pasta (zip slip).
                var target = Path.GetFullPath(Path.Combine(ExtrasFolder, item.FullName));
                if (!target.StartsWith(root, StringComparison.OrdinalIgnoreCase) || !item.FullName.StartsWith(entry.Bundle + "/", StringComparison.Ordinal))
                    throw new ConversionException("invalid_plugin", "O pacote do plugin tem arquivos fora do esperado.");
                if (item.FullName.EndsWith('/')) continue;
                Directory.CreateDirectory(Path.GetDirectoryName(target)!);
                item.ExtractToFile(target, overwrite: true);
            }
        }
        finally
        {
            try { File.Delete(temp); } catch (IOException) { }
        }
    }

    /// <summary>Remove um extra baixado (os incluídos no instalador ficam).</summary>
    public void Remove(MusicVstEntry entry)
    {
        var path = Path.Combine(ExtrasFolder, entry.Bundle);
        if (Directory.Exists(path)) Directory.Delete(path, true);
    }
}
