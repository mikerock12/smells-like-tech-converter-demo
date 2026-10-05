using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Media.Animation;
using Microsoft.UI.Xaml.Media.Imaging;
using SmellsLikeTech.Converter.Composition;
using SmellsLikeTech.Converter.Desktop.Services;
using Windows.Graphics;
using Windows.Media.Core;
using Windows.Media.Playback;

namespace SmellsLikeTech.Converter.Desktop;

/// <summary>
/// Abertura do produto: entra em fade in, toca a vinheta, mostra o carregamento real
/// por etapas e os créditos, e sai em fade out entregando a janela principal.
/// </summary>
public sealed partial class SplashWindow : Window
{
    // A vinheta tem proporção 1264x626; a janela acompanha e sobra espaço para o rodapé.
    private const int WindowWidth = 900;
    private const int WindowHeight = 626;

    /// <summary>Tempo mínimo de exibição para a abertura não "piscar" em máquinas rápidas.</summary>
    private static readonly TimeSpan MinimumDisplay = TimeSpan.FromSeconds(4.2);

    private readonly DateTimeOffset openedAt = DateTimeOffset.Now;
    private MediaPlayer? player;
    private bool finished;

    public SplashWindow()
    {
        InitializeComponent();
        ConfigureWindow();
        RootGrid.Loaded += OnLoaded;
        Closed += (_, _) => ReleasePlayer();
    }

    private void ConfigureWindow()
    {
        var appWindow = AppWindow;
        if (appWindow.Presenter is OverlappedPresenter presenter)
        {
            presenter.SetBorderAndTitleBar(false, false);
            presenter.IsResizable = false;
            presenter.IsAlwaysOnTop = true;
        }

        var icon = BrandInfo.AssetPath("icon.ico");
        if (File.Exists(icon))
        {
            appWindow.SetIcon(icon);
        }

        appWindow.Resize(new SizeInt32(WindowWidth, WindowHeight));
        CenterOnScreen(appWindow);
    }

    private static void CenterOnScreen(AppWindow appWindow)
    {
        var area = DisplayArea.GetFromWindowId(appWindow.Id, DisplayAreaFallback.Primary);
        appWindow.Move(new PointInt32(
            area.WorkArea.X + ((area.WorkArea.Width - appWindow.Size.Width) / 2),
            area.WorkArea.Y + ((area.WorkArea.Height - appWindow.Size.Height) / 2)));
    }

    private async void OnLoaded(object sender, RoutedEventArgs e)
    {
        StartOpeningVideo();
        await FadeAsync(0, 1, TimeSpan.FromMilliseconds(420));

        var progress = new Progress<StartupStage>(stage =>
        {
            StageText.Text = stage.Message;
            AnimateProgress(stage.Progress * 100);
        });

        try
        {
            await App.InitializeServicesAsync(progress);
        }
        catch (Exception exception)
        {
            StageText.Text = $"Falha ao iniciar: {exception.Message}";
            PercentText.Text = "erro";
            StartupProgress.ShowError = true;
            return;
        }

        var remaining = MinimumDisplay - (DateTimeOffset.Now - openedAt);
        if (remaining > TimeSpan.Zero)
        {
            await Task.Delay(remaining);
        }

        await FinishAsync();
    }

    private void StartOpeningVideo()
    {
        var videoPath = BrandInfo.AssetPath("abertura.mp4");
        if (!File.Exists(videoPath))
        {
            ShowPoster();
            return;
        }

        try
        {
            player = new MediaPlayer
            {
                IsMuted = true,
                AutoPlay = true,
                Source = MediaSource.CreateFromUri(new Uri(videoPath))
            };
            player.MediaFailed += (_, _) => DispatcherQueue.TryEnqueue(ShowPoster);
            OpeningVideo.SetMediaPlayer(player);
        }
        catch (Exception exception) when (exception is UnauthorizedAccessException or IOException or ArgumentException)
        {
            // Sem vídeo a abertura continua com a imagem estática.
            ShowPoster();
        }
    }

    private void ShowPoster()
    {
        var poster = BrandInfo.AssetPath("abertura-poster.jpg");
        if (File.Exists(poster))
        {
            PosterImage.Source = new BitmapImage(new Uri(poster));
            PosterImage.Visibility = Visibility.Visible;
        }

        OpeningVideo.Visibility = Visibility.Collapsed;
    }

    private void AnimateProgress(double target)
    {
        var clamped = Math.Clamp(target, 0, 100);
        PercentText.Text = $"{clamped:0}%";

        var animation = new DoubleAnimation
        {
            To = clamped,
            Duration = new Duration(TimeSpan.FromMilliseconds(320)),
            EnableDependentAnimation = true,
            EasingFunction = new CubicEase { EasingMode = EasingMode.EaseOut }
        };

        var storyboard = new Storyboard();
        storyboard.Children.Add(animation);
        Storyboard.SetTarget(animation, StartupProgress);
        Storyboard.SetTargetProperty(animation, "Value");
        storyboard.Begin();
    }

    private async Task FinishAsync()
    {
        if (finished)
        {
            return;
        }

        finished = true;

        // O player de mídia é desligado antes de qualquer outra coisa. Encerrar o
        // pipeline de vídeo junto com a criação da janela principal deixava, de vez em
        // quando, a nova janela sem receber cliques nem arquivos arrastados.
        ReleasePlayer();

        // A abertura fica sempre no topo; sem liberar isso antes, a janela principal
        // nasce atrás dela e o seletor de arquivos abre fora da vista.
        if (AppWindow.Presenter is OverlappedPresenter presenter)
        {
            presenter.IsAlwaysOnTop = false;
        }

        await FadeAsync(1, 0, TimeSpan.FromMilliseconds(380));

        var main = new MainWindow();
        App.RegisterMainWindow(main);
        main.Activate();

        // A abertura só sai depois que a principal terminou de assumir o foco. Fechar as
        // duas no mesmo passo é o que embaralhava a entrada.
        DispatcherQueue.TryEnqueue(Close);
    }

    private Task FadeAsync(double from, double to, TimeSpan duration)
    {
        var completion = new TaskCompletionSource();
        var animation = new DoubleAnimation
        {
            From = from,
            To = to,
            Duration = new Duration(duration),
            EasingFunction = new CubicEase { EasingMode = EasingMode.EaseInOut }
        };

        var storyboard = new Storyboard();
        storyboard.Children.Add(animation);
        Storyboard.SetTarget(animation, RootGrid);
        Storyboard.SetTargetProperty(animation, "Opacity");
        storyboard.Completed += (_, _) =>
        {
            RootGrid.Opacity = to;
            completion.TrySetResult();
        };
        storyboard.Begin();
        return completion.Task;
    }

    private void ReleasePlayer()
    {
        if (player is null)
        {
            return;
        }

        var current = player;
        player = null;

        try
        {
            // Parar e soltar a fonte antes de descartar: o pipeline do Media Foundation
            // encerra sozinho, sem bombear mensagens em cima da janela que está nascendo.
            current.Pause();
            current.Source = null;
            OpeningVideo.SetMediaPlayer(null);
        }
        catch (Exception exception) when (exception is InvalidOperationException or ObjectDisposedException)
        {
            // O player já podia ter sido encerrado pelo próprio sistema.
        }

        current.Dispose();
    }
}
