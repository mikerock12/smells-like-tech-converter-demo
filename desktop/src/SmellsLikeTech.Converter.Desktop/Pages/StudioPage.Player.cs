using System.Diagnostics;
using System.Text.Json;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using SmellsLikeTech.Converter.Core;
using SmellsLikeTech.Converter.Core.Jobs;
using SmellsLikeTech.Converter.Core.Studio;
using Windows.Media.Core;
using Windows.Media.Playback;

namespace SmellsLikeTech.Converter.Desktop.Pages;

/// <summary>
/// O player da janela principal do estúdio: tocar/pausar, ±5 s, quadro a quadro, início e fim,
/// sempre à vista abaixo da prévia. Um vídeo simples toca direto do arquivo; com efeitos, o play
/// monta uma prévia leve (lado maior de 480 px, até 12 quadros/s, x264 ultrafast) com barra,
/// porcentagem e tempo restante, e toca no mesmo lugar. A prévia montada vale até a próxima
/// edição; o recorte do fundo fica em cache e não é refeito quando só o giro ou a cor mudam.
/// </summary>
public sealed partial class StudioPage
{
    private MediaPlayer? previewPlayer;
    private DispatcherQueueTimerWrapper? playerClock;
    private bool playing;
    private bool directPlayback;
    private bool syncingSlider;
    private bool directFailed;
    private double? pendingStart;
    private string? previewVideo;
    private string? previewVideoKey;
    private CancellationTokenSource? previewBuild;

    private static string SpeedFile => Path.Combine(App.Services.Paths.Root, "estudio", "velocidade.json");

    private static string RemovalCacheDirectory => Path.Combine(App.Services.Paths.Temp, "estudio-recortes");

    /// <summary>A prévia montada só vale para o projeto exatamente como estava.</summary>
    private string PreviewKey() => project?.ToJson() ?? "";

    private bool PreviewReady => previewVideo is not null && previewVideoKey == PreviewKey() && File.Exists(previewVideo);

    // ==================== velocidade da máquina ====================

    private static void LoadSpeed()
    {
        try
        {
            if (File.Exists(SpeedFile) && JsonSerializer.Deserialize<StudioSpeed>(File.ReadAllText(SpeedFile)) is { } salva &&
                salva.SecondsPerMegapixelFrame > 0 && salva.SecondsPerMask > 0)
            {
                App.Services.Studio.Speed = salva;
            }
        }
        catch (Exception exception) when (exception is IOException or JsonException or UnauthorizedAccessException)
        {
        }
    }

    private static void SaveSpeed()
    {
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(SpeedFile)!);
            File.WriteAllText(SpeedFile, JsonSerializer.Serialize(App.Services.Studio.Speed));
        }
        catch (Exception exception) when (exception is IOException or UnauthorizedAccessException)
        {
        }
    }

    // ==================== estado dos controles ====================

    private void UpdatePlayerInfo()
    {
        var temConteudo = project is not null && project.Clips.Count > 0;
        foreach (var botao in new[] { PlayButton, PlayerStartButton, PlayerBackButton, PlayerFrameBackButton, PlayerFrameForwardButton, PlayerForwardButton, PlayerEndButton })
        {
            botao.IsEnabled = temConteudo;
        }

        PlayButton.Content = playing ? "❚❚ Pausar" : previewBuild is not null ? "Montando…" : "▶ Reproduzir";
        if (!temConteudo)
        {
            PlayerInfoText.Text = "";
            return;
        }

        if (previewBuild is not null)
        {
            return;
        }

        if (!directFailed && StudioImmediatePreview.Accept(project))
        {
            PlayerInfoText.Text = "Reprodução direta do vídeo, sem esperar.";
        }
        else if (PreviewReady)
        {
            PlayerInfoText.Text = "Prévia leve pronta (baixa resolução): é só reproduzir.";
        }
        else
        {
            var estimativa = StudioEstimate.Estimate(project!, StudioQuality.Preview, App.Services.Studio.Speed);
            PlayerInfoText.Text = $"Com efeitos, o play monta antes uma prévia leve (baixa resolução): {StudioEstimate.Readable(estimativa)}.";
        }
    }

    // ==================== botões ====================

    private void OnPlayAccelerator(KeyboardAccelerator sender, KeyboardAcceleratorInvokedEventArgs args)
    {
        args.Handled = true;
        OnPlayPause(sender, new RoutedEventArgs());
    }

    private async void OnPlayPause(object sender, RoutedEventArgs e)
    {
        if (project is null || project.Clips.Count == 0)
        {
            return;
        }

        if (playing)
        {
            StopPlayback(keepPosition: true);
            return;
        }

        if (previewBuild is not null)
        {
            return;
        }

        var inicio = TimeSlider.Value >= Total - 0.05 ? 0 : TimeSlider.Value;
        if (!directFailed && StudioImmediatePreview.Accept(project))
        {
            StartDirect(inicio);
            return;
        }

        if (!PreviewReady && !await BuildPreviewVideoAsync())
        {
            return;
        }

        StartRendered(inicio);
    }

    private void OnPlayerStart(object sender, RoutedEventArgs e) => Jump(0);

    private void OnPlayerEnd(object sender, RoutedEventArgs e) => Jump(Total);

    private void OnPlayerBack(object sender, RoutedEventArgs e) => Jump(TimeSlider.Value - 5);

    private void OnPlayerForward(object sender, RoutedEventArgs e) => Jump(TimeSlider.Value + 5);

    private void OnPlayerFrameBack(object sender, RoutedEventArgs e) => StepFrames(-1);

    private void OnPlayerFrameForward(object sender, RoutedEventArgs e) => StepFrames(1);

    /// <summary>Quadro a quadro pausa a reprodução: é para conferir um ponto exato.</summary>
    private void StepFrames(int quadros)
    {
        if (project is null)
        {
            return;
        }

        if (playing)
        {
            StopPlayback(keepPosition: true);
        }

        Jump(Math.Round(TimeSlider.Value * project.Fps + quadros) / project.Fps);
    }

    /// <summary>Muda o ponto; tocando, o player continua do novo ponto.</summary>
    private void Jump(double segundos)
    {
        var alvo = Math.Clamp(segundos, 0, Math.Max(0, Total - (project is null ? 0 : 1.0 / project.Fps)));
        TimeSlider.Value = alvo;
        if (playing)
        {
            SeekPlayer(alvo);
        }
    }

    // ==================== reprodução ====================

    private MediaPlayer Player()
    {
        if (previewPlayer is null)
        {
            previewPlayer = new MediaPlayer { AutoPlay = false };
            previewPlayer.MediaEnded += (_, _) => DispatcherQueue.TryEnqueue(() => StopPlayback(keepPosition: false));
            // A posição só vale depois que a mídia abre; antes disso o player ignora o pedido.
            previewPlayer.MediaOpened += (_, _) => DispatcherQueue.TryEnqueue(() =>
            {
                if (pendingStart is { } inicio)
                {
                    pendingStart = null;
                    SeekPlayer(inicio);
                }
            });
            previewPlayer.MediaFailed += (_, _) => DispatcherQueue.TryEnqueue(() =>
            {
                if (directPlayback)
                {
                    // O decodificador do Windows não abriu o arquivo: a prévia leve resolve.
                    directFailed = true;
                    StopPlayback(keepPosition: true);
                    ShowStatus("Este vídeo não toca direto aqui; o play vai montar uma prévia leve.", InfoBarSeverity.Informational);
                    UpdatePlayerInfo();
                }
                else
                {
                    StopPlayback(keepPosition: true);
                    ShowStatus("Não foi possível tocar a prévia.", InfoBarSeverity.Warning);
                }
            });
            PreviewPlayer.SetMediaPlayer(previewPlayer);
        }

        playerClock ??= new DispatcherQueueTimerWrapper(DispatcherQueue, TimeSpan.FromMilliseconds(100), SyncSlider, repeating: true);
        return previewPlayer;
    }

    /// <summary>Coloca o player na proporção do projeto, com a cor de fundo dele.</summary>
    private void SizePlayer(Stretch esticar, Windows.UI.Color fundo)
    {
        var escala = 480.0 / Math.Max(project!.Width, project.Height);
        PlayerFrame.Width = project.Width * escala;
        PlayerFrame.Height = project.Height * escala;
        PlayerFrame.Background = new SolidColorBrush(fundo);
        PreviewPlayer.Stretch = esticar;
    }

    private void StartDirect(double inicio)
    {
        var clip = project!.Clips[0];
        var player = Player();
        directPlayback = true;
        SizePlayer(clip.Fit == "preencher" ? Stretch.UniformToFill : Stretch.Uniform, ParseColor(project.Background?.Color is { Length: >= 7 } cor ? cor[..7] : "#000000"));
        player.Volume = clip.Muted == true ? 0 : Math.Clamp(clip.Volume ?? 1, 0, 1);
        player.Source = MediaSource.CreateFromUri(new Uri(clip.File));
        BeginPlayback(inicio);
    }

    private void StartRendered(double inicio)
    {
        var player = Player();
        directPlayback = false;
        SizePlayer(Stretch.Uniform, Microsoft.UI.Colors.Black);
        player.Volume = 1;
        player.Source = MediaSource.CreateFromUri(new Uri(previewVideo!));
        BeginPlayback(inicio);
    }

    private void BeginPlayback(double inicio)
    {
        playing = true;
        PlayerBox.Visibility = Visibility.Visible;
        PreviewImage.Visibility = Visibility.Collapsed;
        GuidesCanvas.Visibility = Visibility.Collapsed;
        pendingStart = inicio;
        previewPlayer!.Play();
        playerClock!.Start();
        UpdatePlayerInfo();
    }

    private void SeekPlayer(double segundos)
    {
        if (previewPlayer is null)
        {
            return;
        }

        if (pendingStart is not null)
        {
            // Ainda abrindo: o novo ponto vale quando a mídia abrir.
            pendingStart = segundos;
            return;
        }

        var clip = project?.Clips.FirstOrDefault();
        var deslocamento = directPlayback && clip is not null ? clip.Start ?? 0 : 0;
        previewPlayer.PlaybackSession.Position = TimeSpan.FromSeconds(Math.Max(0, segundos + deslocamento));
    }

    /// <summary>O controle deslizante acompanha o player; no fim do trecho do clipe, para.</summary>
    private void SyncSlider()
    {
        if (!playing || previewPlayer is null || project is null || pendingStart is not null)
        {
            return;
        }

        var clip = project.Clips.FirstOrDefault();
        var deslocamento = directPlayback && clip is not null ? clip.Start ?? 0 : 0;
        var tempo = previewPlayer.PlaybackSession.Position.TotalSeconds - deslocamento;
        if (tempo >= Total - 0.02)
        {
            StopPlayback(keepPosition: false);
            return;
        }

        syncingSlider = true;
        try
        {
            TimeSlider.Value = Math.Clamp(tempo, 0, TimeSlider.Maximum);
        }
        finally
        {
            syncingSlider = false;
        }
    }

    /// <summary>Para o player e volta ao quadro parado (no ponto atual ou no fim).</summary>
    private void StopPlayback(bool keepPosition)
    {
        var estava = playing;
        playing = false;
        pendingStart = null;
        playerClock?.Stop();
        if (previewPlayer is not null)
        {
            previewPlayer.Pause();
            previewPlayer.Source = null;
        }

        PlayerBox.Visibility = Visibility.Collapsed;
        PreviewImage.Visibility = Visibility.Visible;
        GuidesCanvas.Visibility = Visibility.Visible;
        if (estava && !keepPosition)
        {
            TimeSlider.Value = 0;
        }

        UpdatePlayerInfo();
        if (estava)
        {
            RequestPreview();
        }
    }

    private void ReleasePreviewPlayer()
    {
        previewBuild?.Cancel();
        StopPlayback(keepPosition: true);
        PreviewPlayer.SetMediaPlayer(null);
        previewPlayer?.Dispose();
        previewPlayer = null;
        ForgetPreviewVideo();
    }

    private void ForgetPreviewVideo()
    {
        if (previewVideo is not null)
        {
            TryDeleteFolder(Path.GetDirectoryName(previewVideo)!);
        }

        previewVideo = null;
        previewVideoKey = null;
    }

    // ==================== prévia leve ====================

    private void OnCancelPreviewBuild(object sender, RoutedEventArgs e) => previewBuild?.Cancel();

    /// <summary>
    /// Monta a prévia leve com o mesmo motor da exportação. A barra mostra a etapa (recorte do
    /// fundo ou composição), a porcentagem e o tempo restante, medido pelo próprio andamento.
    /// </summary>
    private async Task<bool> BuildPreviewVideoAsync()
    {
        var atual = project!;
        var chave = PreviewKey();
        var pequeno = StudioQuality.Preview.Apply(atual) with { Output = "video" };
        var pasta = Path.Combine(App.Services.Paths.Temp, "estudio-previa", Guid.NewGuid().ToString("N"));
        var arquivo = Path.Combine(pasta, "previa.mp4");
        var cancelamento = previewBuild = new CancellationTokenSource();
        var estimativa = StudioEstimate.Estimate(atual, StudioQuality.Preview, App.Services.Studio.Speed);
        var relogio = Stopwatch.StartNew();
        PreviewProgressBar.Value = 0;
        PreviewProgressBar.IsIndeterminate = false;
        PreviewProgressText.Text = $"Montando a prévia leve… {StudioEstimate.Readable(estimativa)}";
        PreviewProgressPanel.Visibility = Visibility.Visible;
        PlayerInfoText.Text = "A prévia é em baixa resolução, só para ver como os efeitos ficam. A exportação sai na qualidade escolhida.";
        UpdatePlayerInfo();
        try
        {
            await Task.Run(() => App.Services.Studio.RenderAsync(pequeno, Inputs(), pasta, pasta, arquivo, StudioQuality.Preview.VideoQuality, progresso =>
            {
                if (progresso.Percent is not { } p)
                {
                    return;
                }

                DispatcherQueue.TryEnqueue(() =>
                {
                    if (previewBuild != cancelamento)
                    {
                        return;
                    }

                    PreviewProgressBar.Value = Math.Round(p * 100);
                    var etapa = progresso.Stage == JobStage.RemovingBackground ? "Removendo o fundo" : "Montando a prévia leve";
                    PreviewProgressText.Text = $"{etapa}… {Math.Round(p * 100)}% · {Remaining(relogio.Elapsed, p, estimativa)}";
                });
            }, cancellationToken: cancelamento.Token, cacheDirectory: RemovalCacheDirectory, level: StudioQuality.Preview));
            if (cancelamento.IsCancellationRequested)
            {
                TryDeleteFolder(pasta);
                return false;
            }

            ForgetPreviewVideo();
            previewVideo = arquivo;
            previewVideoKey = chave;
            SaveSpeed();
            return PreviewKey() == chave;
        }
        catch (OperationCanceledException)
        {
            TryDeleteFolder(pasta);
            return false;
        }
        catch (ConversionException exception)
        {
            TryDeleteFolder(pasta);
            ShowStatus($"Não deu para montar a prévia: {exception.Message}", InfoBarSeverity.Warning);
            return false;
        }
        finally
        {
            if (previewBuild == cancelamento)
            {
                previewBuild = null;
                PreviewProgressPanel.Visibility = Visibility.Collapsed;
            }

            cancelamento.Dispose();
            UpdatePlayerInfo();
        }
    }

    /// <summary>"faltam cerca de 20 s": pela estimativa no começo, depois pelo próprio andamento.</summary>
    private static string Remaining(TimeSpan passado, double fracao, TimeSpan estimativa)
    {
        var resto = fracao > .05 && passado.TotalSeconds > 2
            ? TimeSpan.FromSeconds(passado.TotalSeconds * (1 - fracao) / fracao)
            : estimativa - passado;
        return resto.TotalSeconds <= 1 ? "terminando" : $"faltam {StudioEstimate.Readable(resto)}";
    }
}
