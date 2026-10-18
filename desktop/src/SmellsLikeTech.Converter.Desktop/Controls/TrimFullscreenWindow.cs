using Microsoft.UI;
using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Windows.Media.Playback;
using Windows.System;

namespace SmellsLikeTech.Converter.Desktop.Controls;

/// <summary>
/// A prévia do corte em tela cheia: o vídeo grande e os mesmos controles embaixo. Usa o mesmo
/// <see cref="MediaPlayer"/> do formulário, então sair da tela cheia continua do mesmo ponto.
/// Esc sai; as setas andam um quadro; espaço toca ou pausa.
/// </summary>
internal sealed class TrimFullscreenWindow : Window
{
    public TrimFullscreenWindow(MediaPlayer mediaPlayer, bool hasVideo, string titulo)
    {
        Title = titulo;
        Player = new TrimPlayer();
        Player.UseFullscreenLayout();

        var raiz = new Grid
        {
            Background = new SolidColorBrush(Colors.Black),
            Padding = new Thickness(24),
            RequestedTheme = ElementTheme.Dark
        };
        raiz.Children.Add(Player);
        raiz.KeyDown += OnKeyDown;
        Content = raiz;

        Player.Loaded += (_, _) =>
        {
            Player.Attach(mediaPlayer, hasVideo);
            raiz.Focus(FocusState.Programmatic);
        };
        Player.FullscreenToggled += Close;
        AppWindow.SetPresenter(AppWindowPresenterKind.FullScreen);
    }

    public TrimPlayer Player { get; }

    private void OnKeyDown(object sender, KeyRoutedEventArgs e)
    {
        switch (e.Key)
        {
            case VirtualKey.Escape:
                Close();
                break;
            case VirtualKey.Left:
                Player.StepBack();
                break;
            case VirtualKey.Right:
                Player.StepForward();
                break;
            case VirtualKey.Space:
                Player.TogglePlay();
                break;
            default:
                return;
        }

        e.Handled = true;
    }
}
