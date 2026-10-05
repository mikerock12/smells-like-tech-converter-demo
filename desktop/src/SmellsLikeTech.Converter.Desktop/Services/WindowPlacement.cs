using Microsoft.UI.Windowing;
using SmellsLikeTech.Converter.Infrastructure.Configuration;
using Windows.Graphics;

namespace SmellsLikeTech.Converter.Desktop.Services;

/// <summary>
/// Liga a política de dimensionamento (<see cref="WindowLayout"/>) ao AppWindow do WinUI.
/// Na primeira execução a janela abre maximizada e encaixada; ao restaurar, volta para um
/// tamanho proporcional ao monitor, nunca para uma medida fixa que pode não caber.
/// </summary>
public static class WindowPlacement
{
    public static void Apply(AppWindow appWindow, AppSettings settings)
    {
        var workArea = WorkAreaOf(appWindow);
        var bounds = WindowLayout.Resolve(settings, workArea);

        // Definir tamanho e posição antes de maximizar faz o Windows guardar esse
        // retângulo como destino do botão "restaurar".
        appWindow.MoveAndResize(new RectInt32(bounds.Left, bounds.Top, bounds.Width, bounds.Height));

        if (settings.WindowMaximized && appWindow.Presenter is OverlappedPresenter presenter)
        {
            presenter.Maximize();
        }
    }

    public static AppSettings Capture(AppWindow appWindow, AppSettings settings)
    {
        var maximized = appWindow.Presenter is OverlappedPresenter { State: OverlappedPresenterState.Maximized };
        var bounds = new WindowBounds(
            appWindow.Position.X,
            appWindow.Position.Y,
            appWindow.Size.Width,
            appWindow.Size.Height);

        return WindowLayout.Capture(settings, maximized, bounds);
    }

    private static WindowBounds WorkAreaOf(AppWindow appWindow)
    {
        var area = DisplayArea.GetFromWindowId(appWindow.Id, DisplayAreaFallback.Primary).WorkArea;
        return new WindowBounds(area.X, area.Y, area.Width, area.Height);
    }
}
