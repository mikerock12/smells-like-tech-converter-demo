using System.IO.Compression;
using System.Text.RegularExpressions;

namespace SmellsLikeTech.Converter.Core.Music;

public sealed record MusicArchiveEntry(string Name, long Size, long Compressed);

/// <summary>
/// O .sltmusic: ZIP com projeto.json e midias/&lt;id&gt;.&lt;ext&gt;, espelho de musica-arquivo.mjs.
/// Confere as entradas antes de extrair (caminhos, tetos, bomba de compressao).
/// </summary>
public static partial class MusicArchive
{
    public const string Extension = "sltmusic";
    public const long TotalLimit = 4L * 1024 * 1024 * 1024;
    public const long MediaLimit = 2L * 1024 * 1024 * 1024;
    public const long ProjectLimit = 16L * 1024 * 1024;
    public const int EntryLimit = 2048;
    public const double RatioLimit = 200;
    public static readonly string[] AudioExtensions = ["wav", "flac", "mp3", "m4a", "aac", "ogg", "opus"];
    private static readonly HashSet<string> Compressed = ["flac", "mp3", "m4a", "aac", "ogg", "opus"];

    [GeneratedRegex("^midias/[a-z0-9][a-z0-9-]{0,63}\\.(wav|flac|mp3|m4a|aac|ogg|opus)$")] private static partial Regex MediaPath();

    public static string PathOf(MusicMedia media)
    {
        var extension = (media.Format ?? "wav").ToLowerInvariant();
        return $"midias/{media.Id}.{(AudioExtensions.Contains(extension) ? extension : "wav")}";
    }

    public static bool ShouldCompress(string path) => !Compressed.Contains(path.Split('.')[^1].ToLowerInvariant());

    public static string? CheckEntries(IReadOnlyList<MusicArchiveEntry> entries)
    {
        if (entries.Count > EntryLimit) return "O arquivo tem entradas demais.";
        var names = new HashSet<string>();
        long total = 0;
        var hasProject = false;
        foreach (var entry in entries)
        {
            if (!names.Add(entry.Name)) return "O arquivo tem entradas repetidas.";
            if (entry.Name == "projeto.json")
            {
                hasProject = true;
                if (entry.Size > ProjectLimit) return "O projeto.json é grande demais.";
            }
            else if (!MediaPath().IsMatch(entry.Name)) return $"Entrada não permitida no projeto: {(entry.Name.Length > 80 ? entry.Name[..80] : entry.Name)}";
            else if (entry.Size > MediaLimit) return "Uma mídia do projeto passa de 2 GB.";
            if (entry.Size < 0 || entry.Compressed < 0) return "Entrada com tamanho inválido.";
            if (entry.Size > 10 * 1024 * 1024 && entry.Size / (double)Math.Max(1, entry.Compressed) > RatioLimit) return "O arquivo parece uma bomba de compressão e foi recusado.";
            total += entry.Size;
            if (total > TotalLimit) return "O projeto passa de 4 GB descomprimido.";
        }

        return hasProject ? null : "O arquivo não tem projeto.json.";
    }

    public static List<string> CheckProject(MusicProject project, IReadOnlyCollection<string> names)
    {
        if (project.Version > MusicRules.ProjectVersion) return [$"Este projeto foi salvo numa versão mais nova ({project.Version}). Atualize o aplicativo para abrir."];
        var errors = MusicRules.Problems(project);
        foreach (var media in project.Media)
        {
            if (!names.Contains(PathOf(media))) errors.Add($"A mídia {(string.IsNullOrEmpty(media.Name) ? media.Id : media.Name)} não está no arquivo.");
        }

        return errors;
    }

    /// <summary>Grava o projeto e as midias (arquivos na pasta do projeto) num .sltmusic.</summary>
    public static void Save(MusicProject project, Func<MusicMedia, string> mediaFile, string destination)
    {
        var problems = MusicRules.Problems(project);
        if (problems.Count > 0) throw new ConversionException("invalid_project", string.Join(" ", problems));
        var temp = destination + ".tmp";
        using (var zip = ZipFile.Open(temp, ZipArchiveMode.Create))
        {
            var json = zip.CreateEntry("projeto.json", CompressionLevel.Optimal);
            using (var writer = new StreamWriter(json.Open())) writer.Write(project.ToJson());
            foreach (var media in project.Media)
            {
                var path = PathOf(media);
                zip.CreateEntryFromFile(mediaFile(media), path, ShouldCompress(path) ? CompressionLevel.Fastest : CompressionLevel.NoCompression);
            }
        }

        File.Move(temp, destination, overwrite: true);
    }

    /// <summary>Abre um .sltmusic na pasta indicada (midias em folder/midias). Confere tudo antes.</summary>
    public static MusicProject Open(string archive, string folder)
    {
        using var zip = ZipFile.OpenRead(archive);
        var problem = CheckEntries([.. zip.Entries.Select(e => new MusicArchiveEntry(e.FullName, e.Length, e.CompressedLength))]);
        if (problem is not null) throw new ConversionException("invalid_project", problem);
        MusicProject project;
        using (var reader = new StreamReader(zip.GetEntry("projeto.json")!.Open())) project = MusicProject.Parse(reader.ReadToEnd());
        var errors = CheckProject(project, [.. zip.Entries.Select(e => e.FullName)]);
        if (errors.Count > 0) throw new ConversionException("invalid_project", string.Join(" ", errors));
        Directory.CreateDirectory(Path.Combine(folder, "midias"));
        foreach (var media in project.Media)
        {
            var path = PathOf(media);
            var target = Path.GetFullPath(Path.Combine(folder, path));
            if (!target.StartsWith(Path.GetFullPath(folder), StringComparison.OrdinalIgnoreCase)) throw new ConversionException("invalid_project", "Caminho inválido no projeto.");
            zip.GetEntry(path)!.ExtractToFile(target, overwrite: true);
        }

        return project;
    }
}
