namespace SmellsLikeTech.Converter.Infrastructure.Configuration;

/// <summary>Retangulo de janela em pixels da tela.</summary>
public readonly record struct WindowBounds(int Left, int Top, int Width, int Height)
{
    public int Right => Left + Width;

    public int Bottom => Top + Height;
}

/// <summary>
/// Decide o tamanho e a posicao da janela a partir da tela real do computador.
/// Fica aqui, fora da interface, para poder ser testado sem abrir o aplicativo.
/// </summary>
public static class WindowLayout
{
    /// <summary>Proporcao da area util ocupada pela janela restaurada.</summary>
    public const double RestoreWidthRatio = 0.82;
    public const double RestoreHeightRatio = 0.86;

    public const int MinimumWidth = 1024;
    public const int MinimumHeight = 680;

    /// <summary>Janela restaurada: proporcional ao monitor e centralizada nele.</summary>
    public static WindowBounds RestoreBoundsFor(WindowBounds workArea)
    {
        var width = Fit((int)(workArea.Width * RestoreWidthRatio), MinimumWidth, workArea.Width);
        var height = Fit((int)(workArea.Height * RestoreHeightRatio), MinimumHeight, workArea.Height);

        return new WindowBounds(
            workArea.Left + ((workArea.Width - width) / 2),
            workArea.Top + ((workArea.Height - height) / 2),
            width,
            height);
    }

    /// <summary>
    /// Um retangulo guardado so vale se ainda couber e continuar visivel: a resolucao
    /// pode ter mudado, ou o monitor onde a janela estava pode nao existir mais.
    /// </summary>
    public static bool IsUsable(WindowBounds bounds, WindowBounds workArea)
    {
        if (bounds.Width < MinimumWidth / 2 || bounds.Height < MinimumHeight / 2)
        {
            return false;
        }

        if (bounds.Width > workArea.Width || bounds.Height > workArea.Height)
        {
            return false;
        }

        // Precisa sobrar barra de titulo dentro da area util para a janela ser arrastavel.
        var visibleWidth = Math.Min(bounds.Right, workArea.Right) - Math.Max(bounds.Left, workArea.Left);
        var visibleHeight = Math.Min(bounds.Bottom, workArea.Bottom) - Math.Max(bounds.Top, workArea.Top);
        return visibleWidth >= MinimumWidth / 2 && visibleHeight >= 120;
    }

    /// <summary>O retangulo guardado quando ainda serve; caso contrario, um novo pela tela.</summary>
    public static WindowBounds Resolve(AppSettings settings, WindowBounds workArea)
    {
        if (!settings.HasWindowBounds)
        {
            return RestoreBoundsFor(workArea);
        }

        var stored = new WindowBounds(
            settings.WindowLeft,
            settings.WindowTop,
            settings.WindowWidth,
            settings.WindowHeight);

        return IsUsable(stored, workArea) ? stored : RestoreBoundsFor(workArea);
    }

    /// <summary>
    /// Guarda o estado da janela. Maximizada, o retangulo atual e o do monitor inteiro:
    /// preserva-se o anterior, que e para onde o botao restaurar deve levar.
    /// </summary>
    public static AppSettings Capture(AppSettings settings, bool maximized, WindowBounds bounds) =>
        maximized
            ? settings with { WindowMaximized = true }
            : settings with
            {
                WindowMaximized = false,
                WindowLeft = bounds.Left,
                WindowTop = bounds.Top,
                WindowWidth = bounds.Width,
                WindowHeight = bounds.Height
            };

    private static int Fit(int value, int minimum, int maximum) =>
        maximum < minimum ? maximum : Math.Clamp(value, minimum, maximum);
}
