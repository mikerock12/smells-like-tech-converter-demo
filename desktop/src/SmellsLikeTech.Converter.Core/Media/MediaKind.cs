namespace SmellsLikeTech.Converter.Core.Media;

public enum MediaKind
{
    Unknown = 0,
    Image = 1,
    Video = 2,
    Audio = 3,
    Pdf = 4,
    Document = 5,
    Text = 6
}

public static class MediaKindNames
{
    public static string Display(this MediaKind kind) => kind switch
    {
        MediaKind.Image => "Imagem",
        MediaKind.Video => "Vídeo",
        MediaKind.Audio => "Áudio",
        MediaKind.Pdf => "PDF",
        MediaKind.Document => "Documento",
        MediaKind.Text => "Texto",
        _ => "Desconhecido"
    };
}
