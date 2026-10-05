using Microsoft.UI.Xaml;
using SmellsLikeTech.Converter.Composition;
using SmellsLikeTech.Converter.Core.Media;

namespace SmellsLikeTech.Converter.Desktop.Services;

/// <summary>Atalhos de seleção de arquivo e pasta com os filtros do produto.</summary>
public static class FileDialogs
{
    /// <summary>Grupos oferecidos na caixa: tudo o que o produto aceita, depois por tipo.</summary>
    public static IReadOnlyList<FileDialogFilter> InputFilters { get; } =
    [
        new("Todos os arquivos suportados", [
            .. FormatCatalog.ImageFormats,
            .. FormatCatalog.VideoFormats,
            .. FormatCatalog.AudioFormats,
            .. FormatCatalog.TextFormats,
            .. FormatCatalog.PdfFormats,
            .. FormatCatalog.DocumentFormats
        ]),
        new("Vídeo", FormatCatalog.VideoFormats),
        new("Áudio", FormatCatalog.AudioFormats),
        new("Imagem", FormatCatalog.ImageFormats),
        new("Texto", FormatCatalog.TextFormats),
        new("PDF", FormatCatalog.PdfFormats),
        new("Documento", FormatCatalog.DocumentFormats),
        new("Todos os arquivos", [])
    ];

    public static IReadOnlyList<FileDialogFilter> TextFilters { get; } =
    [
        new("Texto", FormatCatalog.TextFormats),
        new("Todos os arquivos", [])
    ];

    public static string? PickInputFile(Window? owner) =>
        NativeFileDialog.PickFile(HandleOf(owner), "Selecionar arquivo para converter", InputFilters);

    public static IReadOnlyList<string> PickInputFiles(Window? owner) =>
        NativeFileDialog.PickFiles(HandleOf(owner), "Selecionar arquivos para converter", InputFilters);

    public static string? PickTextFile(Window? owner) =>
        NativeFileDialog.PickFile(HandleOf(owner), "Selecionar arquivo de texto", TextFilters);

    public static string? PickOutputFolder(Window? owner) =>
        NativeFileDialog.PickFolder(HandleOf(owner), "Escolher a pasta de saída");

    private static nint HandleOf(Window? owner) =>
        owner is null ? 0 : WinRT.Interop.WindowNative.GetWindowHandle(owner);
}
