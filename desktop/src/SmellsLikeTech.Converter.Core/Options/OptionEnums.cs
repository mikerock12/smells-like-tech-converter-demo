namespace SmellsLikeTech.Converter.Core.Options;

public enum AspectRatioMode
{
    Original = 0,
    Wide16x9 = 1,
    Vertical9x16 = 2,
    Square1x1 = 3,
    Classic4x3 = 4,
    Photo3x2 = 5,
    Ultra21x9 = 6,
    Custom = 7
}

/// <summary>Como o conteudo se encaixa quando a proporcao muda.</summary>
public enum FitMode
{
    /// <summary>Preenche e corta o excedente.</summary>
    Crop = 0,

    /// <summary>Mantem tudo e adiciona barras.</summary>
    Contain = 1,

    /// <summary>Mantem tudo e usa copia ampliada e desfocada como fundo.</summary>
    Blur = 2,

    /// <summary>Deforma para caber. Existe, mas nunca e o padrao.</summary>
    Stretch = 3
}

public enum RotationDegrees
{
    None = 0,
    Clockwise90 = 90,
    Half180 = 180,
    CounterClockwise270 = 270
}

public enum HardwareAcceleration
{
    Automatic = 0,
    Cpu = 1,
    Nvidia = 2,
    Amd = 3,
    Intel = 4
}

public enum VideoCodecChoice
{
    Automatic = 0,
    H264 = 1,
    H265 = 2,
    Vp9 = 3,
    Av1 = 4,
    CopyStream = 5
}

public enum AudioTrackAction
{
    Keep = 0,
    Remove = 1,
    Reencode = 2
}

public enum ImageResizeMode
{
    None = 0,
    Width = 1,
    Height = 2,
    Percent = 3,
    Exact = 4
}

public static class AspectRatioModes
{
    public static (int Width, int Height)? Ratio(this AspectRatioMode mode) => mode switch
    {
        AspectRatioMode.Wide16x9 => (16, 9),
        AspectRatioMode.Vertical9x16 => (9, 16),
        AspectRatioMode.Square1x1 => (1, 1),
        AspectRatioMode.Classic4x3 => (4, 3),
        AspectRatioMode.Photo3x2 => (3, 2),
        AspectRatioMode.Ultra21x9 => (21, 9),
        _ => null
    };

    public static string Display(this AspectRatioMode mode) => mode switch
    {
        AspectRatioMode.Original => "Original",
        AspectRatioMode.Wide16x9 => "16:9",
        AspectRatioMode.Vertical9x16 => "9:16",
        AspectRatioMode.Square1x1 => "1:1",
        AspectRatioMode.Classic4x3 => "4:3",
        AspectRatioMode.Photo3x2 => "3:2",
        AspectRatioMode.Ultra21x9 => "21:9",
        _ => "Personalizada"
    };

    public static string Display(this FitMode mode) => mode switch
    {
        FitMode.Crop => "Recortar",
        FitMode.Contain => "Ajustar",
        FitMode.Blur => "Fundo desfocado",
        _ => "Esticar"
    };
}
