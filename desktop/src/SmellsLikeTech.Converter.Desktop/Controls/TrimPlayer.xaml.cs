using System.Globalization;
using Microsoft.UI.Dispatching;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;
using Windows.Media.Playback;

namespace SmellsLikeTech.Converter.Desktop.Controls;

/// <summary>
/// O player do corte: linha do tempo em hh:mm:ss.mmm, pausa, um quadro para trás e para a
/// frente, velocidade de 0,25x a 2x, e as marcações de início e fim no ponto exato. O mesmo
/// controle aparece no formulário e na tela cheia; os dois usam o mesmo <see cref="MediaPlayer"/>,
/// então posição e velocidade continuam ao trocar. Quem hospeda guarda o trecho e decide o
/// que fazer com as marcações.
/// </summary>
public sealed partial class TrimPlayer : UserControl
{
    private static readonly double[] Velocidades = [0.25, 0.5, 0.75, 1, 1.5, 2];

    private MediaPlayer? player;
    private DispatcherQueueTimer? timer;
    private bool atualizandoLinha;
    private TimeSpan? playUntil;
    private TimeSpan trimStart;
    private TimeSpan? trimEnd;

    public TrimPlayer()
    {
        InitializeComponent();
        foreach (var velocidade in Velocidades)
        {
            SpeedCombo.Items.Add(new ComboBoxItem
            {
                Content = velocidade.ToString("0.##", CultureInfo.GetCultureInfo("pt-BR")) + "x",
                Tag = velocidade
            });
        }

        SpeedCombo.SelectedIndex = Array.IndexOf(Velocidades, 1.0);
        Unloaded += (_, _) => Detach();
    }

    /// <summary>Marcou o início no ponto atual (já arredondado ao milissegundo).</summary>
    public event Action<TimeSpan>? StartMarked;

    /// <summary>Marcou o fim no ponto atual (já arredondado ao milissegundo).</summary>
    public event Action<TimeSpan>? EndMarked;

    /// <summary>Pediu para entrar (ou sair) da tela cheia.</summary>
    public event Action? FullscreenToggled;

    public TimeSpan Position => player?.PlaybackSession.Position ?? TimeSpan.Zero;

    /// <summary>Na tela cheia o vídeo ocupa o espaço que sobra acima dos controles.</summary>
    public void UseFullscreenLayout()
    {
        VideoRow.Height = new GridLength(1, GridUnitType.Star);
        FullscreenButton.Content = "Sair da tela cheia";
    }

    /// <summary>Liga o controle a um player já aberto; sem vídeo, a área de imagem encolhe.</summary>
    public void Attach(MediaPlayer mediaPlayer, bool hasVideo)
    {
        Detach();
        player = mediaPlayer;
        Surface.SetMediaPlayer(mediaPlayer);
        if (VideoRow.Height.GridUnitType != GridUnitType.Star)
        {
            VideoRow.Height = new GridLength(hasVideo ? 220 : 0);
        }

        FrameBackButton.Visibility = hasVideo ? Visibility.Visible : Visibility.Collapsed;
        FrameForwardButton.Visibility = hasVideo ? Visibility.Visible : Visibility.Collapsed;
        SelectSpeed(mediaPlayer.PlaybackSession.PlaybackRate);

        timer ??= CreateTimer();
        timer.Start();
        Refresh();
    }

    /// <summary>Solta o player sem fechá-lo (quem abriu é quem fecha).</summary>
    public void Detach()
    {
        timer?.Stop();
        playUntil = null;
        Surface.SetMediaPlayer(null);
        player = null;
    }

    /// <summary>O trecho marcado, para "Tocar o trecho" e o resumo embaixo dos botões.</summary>
    public void SetTrim(TimeSpan start, TimeSpan? end)
    {
        trimStart = start;
        trimEnd = end;
        var ate = end ?? Duration();
        TrimText.Text = ate is { } fim && fim > start
            ? $"Trecho: {Long(start)} até {Long(fim)} · {Long(fim - start)}"
            : $"Trecho: de {Long(start)} até o fim";
    }

    private DispatcherQueueTimer CreateTimer()
    {
        var novo = DispatcherQueue.CreateTimer();
        novo.Interval = TimeSpan.FromMilliseconds(50);
        novo.Tick += (_, _) => Refresh();
        return novo;
    }

    /// <summary>Move a linha do tempo, troca Tocar/Pausar e para no fim do trecho.</summary>
    private void Refresh()
    {
        if (player is null)
        {
            return;
        }

        var sessao = player.PlaybackSession;
        var posicao = sessao.Position;
        if (playUntil is { } limite && posicao >= limite)
        {
            player.Pause();
            playUntil = null;
        }

        var duracao = Duration() ?? TimeSpan.Zero;
        atualizandoLinha = true;
        Timeline.Maximum = Math.Max(0.001, duracao.TotalSeconds);
        Timeline.Value = Math.Min(posicao.TotalSeconds, Timeline.Maximum);
        atualizandoLinha = false;
        PositionText.Text = $"{Long(posicao)} / {Long(duracao)}";
        PlayButton.Content = sessao.PlaybackState == MediaPlaybackState.Playing ? "Pausar" : "Tocar";
        if (trimEnd is null)
        {
            SetTrim(trimStart, null);
        }
    }

    private TimeSpan? Duration() =>
        player?.PlaybackSession.NaturalDuration is { } duracao && duracao > TimeSpan.Zero ? duracao : null;

    private void OnTimelineChanged(object sender, RangeBaseValueChangedEventArgs e)
    {
        if (!atualizandoLinha && player is not null)
        {
            player.PlaybackSession.Position = TimeSpan.FromSeconds(e.NewValue);
        }
    }

    private void OnPlayPause(object sender, RoutedEventArgs e) => TogglePlay();

    public void TogglePlay()
    {
        if (player is null)
        {
            return;
        }

        if (player.PlaybackSession.PlaybackState == MediaPlaybackState.Playing)
        {
            player.Pause();
            playUntil = null;
        }
        else
        {
            player.Play();
        }
    }

    public void StepBack()
    {
        playUntil = null;
        player?.StepBackwardOneFrame();
    }

    public void StepForward()
    {
        playUntil = null;
        player?.StepForwardOneFrame();
    }

    private void OnFrameBack(object sender, RoutedEventArgs e) => StepBack();

    private void OnFrameForward(object sender, RoutedEventArgs e) => StepForward();

    private void OnSpeedChanged(object sender, SelectionChangedEventArgs e)
    {
        if (player is not null && SpeedCombo.SelectedItem is ComboBoxItem { Tag: double velocidade })
        {
            player.PlaybackSession.PlaybackRate = velocidade;
        }
    }

    private void SelectSpeed(double velocidade)
    {
        var indice = Array.FindIndex(Velocidades, valor => Math.Abs(valor - velocidade) < 0.001);
        SpeedCombo.SelectedIndex = indice >= 0 ? indice : Array.IndexOf(Velocidades, 1.0);
    }

    private void OnMarkStart(object sender, RoutedEventArgs e) => StartMarked?.Invoke(Rounded(Position));

    private void OnMarkEnd(object sender, RoutedEventArgs e) => EndMarked?.Invoke(Rounded(Position));

    private void OnPlayTrim(object sender, RoutedEventArgs e)
    {
        if (player is null)
        {
            return;
        }

        player.PlaybackSession.Position = trimStart;
        playUntil = trimEnd ?? Duration();
        player.Play();
    }

    private void OnFullscreen(object sender, RoutedEventArgs e) => FullscreenToggled?.Invoke();

    private static TimeSpan Rounded(TimeSpan valor) => TimeSpan.FromMilliseconds(Math.Round(valor.TotalMilliseconds));

    /// <summary>"00:01:05.135": horas, minutos, segundos e milissegundos.</summary>
    internal static string Long(TimeSpan valor) =>
        TimeSpan.FromMilliseconds(Math.Round(Math.Max(0, valor.TotalMilliseconds)))
            .ToString(@"hh\:mm\:ss\.fff", CultureInfo.InvariantCulture);
}
