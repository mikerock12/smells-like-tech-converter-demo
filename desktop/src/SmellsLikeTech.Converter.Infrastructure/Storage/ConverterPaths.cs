namespace SmellsLikeTech.Converter.Infrastructure.Storage;

/// <summary>
/// Estrutura de pastas do produto. Por decisao do projeto, tudo que e pesado ou temporario
/// mora no HD de 1 TB em D:\SmellsLikeTechConverter.
/// </summary>
public sealed class ConverterPaths
{
    public const string DefaultRoot = @"D:\SmellsLikeTechConverter";

    public ConverterPaths(string root)
    {
        Root = Path.GetFullPath(root);
        Temp = Path.Combine(Root, "Temp");
        Cache = Path.Combine(Root, "Cache");
        Models = Path.Combine(Root, "Models");
        WhisperModels = Path.Combine(Models, "Whisper");
        Logs = Path.Combine(Root, "Logs");
        FailedJobs = Path.Combine(Root, "FailedJobs");
        Output = Path.Combine(Root, "Output");
        DatabaseFile = Path.Combine(Root, "converter.db");
    }

    public string Root { get; }
    public string Temp { get; }
    public string Cache { get; }
    public string Models { get; }
    public string WhisperModels { get; }
    public string Logs { get; }
    public string FailedJobs { get; }
    public string Output { get; }
    public string DatabaseFile { get; }

    /// <summary>
    /// D:\SmellsLikeTechConverter quando o disco existir; caso contrario, uma pasta
    /// equivalente no perfil do usuario, para o aplicativo nunca falhar ao abrir.
    /// </summary>
    public static ConverterPaths CreateDefault()
    {
        var root = DefaultRoot;
        try
        {
            var driveRoot = Path.GetPathRoot(DefaultRoot);
            if (driveRoot is null || !Directory.Exists(driveRoot))
            {
                root = Path.Combine(
                    Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                    "SmellsLikeTechConverter");
            }
        }
        catch (IOException)
        {
            root = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "SmellsLikeTechConverter");
        }

        return new ConverterPaths(root);
    }

    public void EnsureCreated()
    {
        foreach (var directory in new[] { Root, Temp, Cache, Models, WhisperModels, Logs, FailedJobs, Output })
        {
            Directory.CreateDirectory(directory);
        }
    }

    public long FreeDiskBytes()
    {
        try
        {
            var driveRoot = Path.GetPathRoot(Root);
            return driveRoot is null ? 0 : new DriveInfo(driveRoot).AvailableFreeSpace;
        }
        catch (Exception exception) when (exception is IOException or ArgumentException or UnauthorizedAccessException)
        {
            return 0;
        }
    }
}
