using SmellsLikeTech.Converter.Core;

namespace SmellsLikeTech.Converter.Infrastructure.Storage;

/// <summary>
/// Impede que junctions e links simbolicos plantados dentro da area temporaria
/// facam a limpeza apagar arquivos fora dela.
/// </summary>
public static class FileAccessGuard
{
    public static void EnsureNoReparsePoint(string directory)
    {
        if (!Directory.Exists(directory))
        {
            return;
        }

        var attributes = File.GetAttributes(directory);
        if ((attributes & FileAttributes.ReparsePoint) != 0)
        {
            throw new ConversionException(
                "unsafe_directory",
                "A pasta de trabalho é um link e não pode ser usada com segurança.");
        }
    }

    public static bool TreeContainsReparsePoint(string root)
    {
        var directories = new Stack<string>();
        directories.Push(root);
        while (directories.TryPop(out var directory))
        {
            foreach (var entry in Directory.EnumerateFileSystemEntries(directory, "*", SearchOption.TopDirectoryOnly))
            {
                var attributes = File.GetAttributes(entry);
                if ((attributes & FileAttributes.ReparsePoint) != 0)
                {
                    return true;
                }

                if ((attributes & FileAttributes.Directory) != 0)
                {
                    directories.Push(entry);
                }
            }
        }

        return false;
    }
}
