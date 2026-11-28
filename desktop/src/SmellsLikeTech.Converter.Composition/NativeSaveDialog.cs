using System.ComponentModel;
using System.Runtime.InteropServices;

namespace SmellsLikeTech.Converter.Composition;

public static class NativeSaveDialog
{
    // The common dialog runs a modal message loop. Keep the XAML dispatcher free
    // while the shell selects the destination and confirms any overwrite.
    public static Task<string?> PickAsync(nint owner, string title, string suggestedName, string extension)
    {
        var completion = new TaskCompletionSource<string?>(TaskCreationOptions.RunContinuationsAsynchronously);
        var thread = new Thread(() =>
        {
            try { completion.SetResult(Pick(owner, title, suggestedName, extension)); }
            catch (Exception e) { completion.SetException(e); }
        }) { IsBackground = true };
        thread.SetApartmentState(ApartmentState.STA);
        thread.Start();
        return completion.Task;
    }

    private static string? Pick(nint owner, string title, string suggestedName, string extension)
    {
        const int capacity = 32768;
        var filter = $"{title} (*.{extension})\0*.{extension}\0\0";
        if (suggestedName.Length >= capacity) throw new ArgumentException("Nome de arquivo muito longo.");
        var name = Marshal.AllocHGlobal(capacity * sizeof(char));
        try
        {
            Marshal.Copy((suggestedName + '\0').ToCharArray(), 0, name, suggestedName.Length + 1);
            var dialog = new OpenFileName
            {
                Size = Marshal.SizeOf<OpenFileName>(), Owner = owner, Title = title,
                Filter = filter, FilterIndex = 1, File = name, MaxFile = capacity,
                DefaultExtension = extension,
                // OVERWRITEPROMPT, NOCHANGEDIR, PATHMUSTEXIST, EXPLORER.
                Flags = 0x2 | 0x8 | 0x800 | 0x80000
            };
            if (GetSaveFileNameW(ref dialog)) return Marshal.PtrToStringUni(name);
            int error = CommDlgExtendedError();
            if (error != 0) throw new Win32Exception(error, $"O seletor de destino do Windows falhou (0x{error:X}).");
            return null;
        }
        finally { Marshal.FreeHGlobal(name); }
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct OpenFileName
    {
        public int Size;
        public nint Owner, Instance;
        public string? Filter;
        public nint CustomFilter;
        public int MaxCustomFilter, FilterIndex;
        public nint File;
        public int MaxFile;
        public nint FileTitle;
        public int MaxFileTitle;
        public nint InitialDirectory;
        public string? Title;
        public int Flags;
        public ushort FileOffset, FileExtension;
        public string? DefaultExtension;
        public nint CustomData, Hook, TemplateName, Reserved;
        public int ReservedSize, FlagsEx;
    }

    [DllImport("comdlg32.dll", CharSet = CharSet.Unicode, ExactSpelling = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GetSaveFileNameW(ref OpenFileName dialog);

    [DllImport("comdlg32.dll", ExactSpelling = true)]
    private static extern int CommDlgExtendedError();
}
