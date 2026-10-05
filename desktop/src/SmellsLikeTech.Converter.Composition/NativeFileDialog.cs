using System.Runtime.InteropServices;

namespace SmellsLikeTech.Converter.Composition;

/// <summary>Um grupo de extensões oferecido na caixa de seleção.</summary>
public sealed record FileDialogFilter(string Description, IReadOnlyList<string> Extensions)
{
    /// <summary>Formato aceito pelo Windows: "*.jpg;*.png".</summary>
    public string Pattern => Extensions.Count == 0
        ? "*.*"
        : string.Join(';', Extensions.Select(extension => "*." + extension.TrimStart('.').ToLowerInvariant()));

    public string Label => Extensions.Count == 0
        ? Description
        : $"{Description} ({Pattern.Replace(";", ", ", StringComparison.Ordinal)})";
}

/// <summary>
/// Caixa de abrir arquivo e escolher pasta do próprio Windows (IFileOpenDialog).
/// O seletor do WinRT falha com E_FAIL em aplicativos desempacotados como este,
/// e é o mesmo diálogo que o Explorer usa - some o problema e ganha-se o visual nativo.
/// </summary>
public static class NativeFileDialog
{
    private const uint FosPickFolders = 0x00000020;
    private const uint FosAllowMultiSelect = 0x00000200;
    private const uint FosForceFileSystem = 0x00000040;
    private const uint FosFileMustExist = 0x00001000;
    private const uint FosPathMustExist = 0x00000800;
    private const uint FosNoChangeDir = 0x00000008;

    private const uint SigdnFileSysPath = 0x80058000;

    /// <summary>HRESULT devolvido quando o usuário fecha ou cancela.</summary>
    private const int ErrorCancelled = unchecked((int)0x800704C7);

    public static string? PickFile(nint owner, string title, IReadOnlyList<FileDialogFilter> filters)
    {
        var dialog = CreateDialog();
        try
        {
            dialog.SetOptions(FosForceFileSystem | FosFileMustExist | FosPathMustExist | FosNoChangeDir);
            dialog.SetTitle(title);
            ApplyFilters(dialog, filters);
            return ShowAndRead(dialog, owner);
        }
        finally
        {
            Marshal.ReleaseComObject(dialog);
        }
    }

    /// <summary>
    /// A mesma caixa, aceitando varios arquivos de uma vez. Devolve lista vazia quando a
    /// pessoa cancela - nunca nulo, porque quem chama sempre percorre o resultado.
    /// </summary>
    public static IReadOnlyList<string> PickFiles(nint owner, string title, IReadOnlyList<FileDialogFilter> filters)
    {
        var dialog = CreateDialog();
        try
        {
            dialog.SetOptions(
                FosForceFileSystem | FosFileMustExist | FosPathMustExist | FosNoChangeDir | FosAllowMultiSelect);
            dialog.SetTitle(title);
            ApplyFilters(dialog, filters);

            var result = dialog.Show(owner);
            if (result == ErrorCancelled)
            {
                return [];
            }

            Marshal.ThrowExceptionForHR(result);

            dialog.GetResults(out var items);
            try
            {
                items.GetCount(out var count);
                var paths = new List<string>((int)count);
                for (uint index = 0; index < count; index++)
                {
                    items.GetItemAt(index, out var item);
                    try
                    {
                        if (PathOf(item) is { } path)
                        {
                            paths.Add(path);
                        }
                    }
                    finally
                    {
                        Marshal.ReleaseComObject(item);
                    }
                }

                return paths;
            }
            finally
            {
                Marshal.ReleaseComObject(items);
            }
        }
        finally
        {
            Marshal.ReleaseComObject(dialog);
        }
    }

    public static string? PickFolder(nint owner, string title)
    {
        var dialog = CreateDialog();
        try
        {
            dialog.SetOptions(FosPickFolders | FosForceFileSystem | FosPathMustExist | FosNoChangeDir);
            dialog.SetTitle(title);
            return ShowAndRead(dialog, owner);
        }
        finally
        {
            Marshal.ReleaseComObject(dialog);
        }
    }

    /// <summary>
    /// A coclasse e a interface só se encontram em tempo de execução, via QueryInterface:
    /// por isso o objeto é criado solto e convertido depois.
    /// </summary>
    private static IFileOpenDialog CreateDialog()
    {
        object instancia = new FileOpenDialog();
        return (IFileOpenDialog)instancia;
    }

    private static void ApplyFilters(IFileOpenDialog dialog, IReadOnlyList<FileDialogFilter> filters)
    {
        if (filters.Count == 0)
        {
            return;
        }

        var specs = filters
            .Select(filter => new FilterSpec { Name = filter.Label, Spec = filter.Pattern })
            .ToArray();

        dialog.SetFileTypes((uint)specs.Length, specs);
        dialog.SetFileTypeIndex(1);
    }

    private static string? ShowAndRead(IFileOpenDialog dialog, nint owner)
    {
        var result = dialog.Show(owner);
        if (result == ErrorCancelled)
        {
            return null;
        }

        Marshal.ThrowExceptionForHR(result);

        dialog.GetResult(out var item);
        try
        {
            return PathOf(item);
        }
        finally
        {
            Marshal.ReleaseComObject(item);
        }
    }

    private static string? PathOf(IShellItem item)
    {
        item.GetDisplayName(SigdnFileSysPath, out var pointer);
        try
        {
            return Marshal.PtrToStringUni(pointer);
        }
        finally
        {
            Marshal.FreeCoTaskMem(pointer);
        }
    }

    // ==================== interoperabilidade COM ====================
    // A ordem dos métodos espelha a vtable: não reordenar nem remover.

    [ComImport]
    [Guid("DC1C5A9C-E88A-4DDE-A5A1-60F82A20AEF7")]
    private sealed class FileOpenDialog
    {
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct FilterSpec
    {
        [MarshalAs(UnmanagedType.LPWStr)] public string Name;
        [MarshalAs(UnmanagedType.LPWStr)] public string Spec;
    }

    [ComImport]
    [Guid("D57C7288-D4AD-4768-BE02-9D969532D960")]
    [InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IFileOpenDialog
    {
        // IModalWindow
        [PreserveSig] int Show(nint parent);

        // IFileDialog
        void SetFileTypes(uint count, [In, MarshalAs(UnmanagedType.LPArray)] FilterSpec[] filters);
        void SetFileTypeIndex(uint index);
        void GetFileTypeIndex(out uint index);
        void Advise(nint events, out uint cookie);
        void Unadvise(uint cookie);
        void SetOptions(uint options);
        void GetOptions(out uint options);
        void SetDefaultFolder(IShellItem folder);
        void SetFolder(IShellItem folder);
        void GetFolder(out IShellItem folder);
        void GetCurrentSelection(out IShellItem item);
        void SetFileName([MarshalAs(UnmanagedType.LPWStr)] string name);
        void GetFileName([MarshalAs(UnmanagedType.LPWStr)] out string name);
        void SetTitle([MarshalAs(UnmanagedType.LPWStr)] string title);
        void SetOkButtonLabel([MarshalAs(UnmanagedType.LPWStr)] string text);
        void SetFileNameLabel([MarshalAs(UnmanagedType.LPWStr)] string label);
        void GetResult(out IShellItem item);
        void AddPlace(IShellItem place, int order);
        void SetDefaultExtension([MarshalAs(UnmanagedType.LPWStr)] string extension);
        void Close(int result);
        void SetClientGuid(ref Guid client);
        void ClearClientData();
        void SetFilter(nint filter);

        // IFileOpenDialog
        void GetResults(out IShellItemArray items);
        void GetSelectedItems(out IShellItemArray items);
    }

    [ComImport]
    [Guid("B63EA76D-1F85-456F-A19C-48159EFA858B")]
    [InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IShellItemArray
    {
        void BindToHandler(nint bindContext, ref Guid handler, ref Guid interfaceId, out nint result);
        void GetPropertyStore(uint flags, ref Guid interfaceId, out nint store);
        void GetPropertyDescriptionList(nint key, ref Guid interfaceId, out nint list);
        void GetAttributes(uint options, uint mask, out uint attributes);
        void GetCount(out uint count);
        void GetItemAt(uint index, out IShellItem item);
        void EnumItems(out nint enumerator);
    }

    [ComImport]
    [Guid("43826D1E-E718-42EE-BC55-A1E261C37BFE")]
    [InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IShellItem
    {
        void BindToHandler(nint bindContext, ref Guid handler, ref Guid interfaceId, out nint result);
        void GetParent(out IShellItem parent);
        void GetDisplayName(uint kind, out nint name);
        void GetAttributes(uint mask, out uint attributes);
        void Compare(IShellItem other, uint hint, out int order);
    }
}
