using System.Diagnostics;
using System.Runtime.InteropServices;

namespace SmellsLikeTech.Converter.Desktop;

/// <summary>
/// Abre pastas e revela arquivos no Explorer.
/// </summary>
public static class ShellHelper
{
    public static void OpenFolder(string directory)
    {
        if (string.IsNullOrWhiteSpace(directory) || !Directory.Exists(directory))
        {
            return;
        }

        Start("explorer.exe", [Path.GetFullPath(directory)]);
    }

    /// <summary>Abre a pasta do arquivo com ele já selecionado.</summary>
    public static void RevealInExplorer(string path)
    {
        if (string.IsNullOrWhiteSpace(path))
        {
            return;
        }

        var full = Path.GetFullPath(path);
        if (!File.Exists(full))
        {
            OpenFolder(Path.GetDirectoryName(full) ?? string.Empty);
            return;
        }

        // O Explorer não aceita "/select," e o caminho como argumentos separados - e é
        // exatamente isso que ProcessStartInfo.ArgumentList produz, com cada um entre
        // aspas. Por isso se usa a API do shell, que recebe o item já resolvido.
        if (TrySelectWithShell(full))
        {
            return;
        }

        OpenFolder(Path.GetDirectoryName(full) ?? string.Empty);
    }

    private static bool TrySelectWithShell(string filePath)
    {
        var folder = nint.Zero;
        var file = nint.Zero;

        try
        {
            var directory = Path.GetDirectoryName(filePath);
            if (directory is null)
            {
                return false;
            }

            if (SHParseDisplayName(directory, nint.Zero, out folder, 0, out _) != 0
                || SHParseDisplayName(filePath, nint.Zero, out file, 0, out _) != 0)
            {
                return false;
            }

            return SHOpenFolderAndSelectItems(folder, 1, [file], 0) == 0;
        }
        catch (Exception exception) when (exception is DllNotFoundException or EntryPointNotFoundException)
        {
            return false;
        }
        finally
        {
            if (folder != nint.Zero)
            {
                Marshal.FreeCoTaskMem(folder);
            }

            if (file != nint.Zero)
            {
                Marshal.FreeCoTaskMem(file);
            }
        }
    }

    private static void Start(string executable, IReadOnlyList<string> arguments)
    {
        try
        {
            var startInfo = new ProcessStartInfo { FileName = executable, UseShellExecute = false };
            foreach (var argument in arguments)
            {
                startInfo.ArgumentList.Add(argument);
            }

            using var process = Process.Start(startInfo);
        }
        catch (Exception exception) when (exception is System.ComponentModel.Win32Exception or InvalidOperationException)
        {
            // Abrir o Explorer nunca deve derrubar o aplicativo.
        }
    }

    [DllImport("shell32.dll", CharSet = CharSet.Unicode, ExactSpelling = true)]
    private static extern int SHParseDisplayName(
        string name,
        nint bindContext,
        out nint idList,
        uint attributesIn,
        out uint attributesOut);

    [DllImport("shell32.dll", ExactSpelling = true)]
    private static extern int SHOpenFolderAndSelectItems(
        nint folderIdList,
        uint count,
        [In, MarshalAs(UnmanagedType.LPArray)] nint[] itemIdList,
        uint flags);
}
