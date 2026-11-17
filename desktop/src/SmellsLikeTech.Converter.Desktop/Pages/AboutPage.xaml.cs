using System.Diagnostics;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Documents;
using Microsoft.UI.Xaml.Media;
using SmellsLikeTech.Converter.Core.Music;
using SmellsLikeTech.Converter.Desktop.Services;

namespace SmellsLikeTech.Converter.Desktop.Pages;

/// <summary>
/// Sobre: a versão instalada, quem desenvolve, as licenças (do aplicativo e dos componentes de
/// código aberto) e "Procurar atualização", que baixa o instalador conferido pelo SHA-256 e,
/// com o clique da pessoa, instala e reabre.
/// </summary>
public sealed partial class AboutPage : Page
{
    private readonly TextBlock updateStatus = new() { TextWrapping = TextWrapping.Wrap };
    private readonly ProgressBar updateProgress = new() { Maximum = 1, Width = 360, Visibility = Visibility.Collapsed, HorizontalAlignment = HorizontalAlignment.Left };
    private readonly Button check = new() { Content = "Procurar atualização" };
    private readonly Button download = new() { Visibility = Visibility.Collapsed };
    private readonly Button install = new() { Content = "Instalar e reiniciar", Visibility = Visibility.Collapsed };
    private AppRelease? release;
    private string? installer;
    private CancellationTokenSource? work;

    public AboutPage()
    {
        InitializeComponent();
        var root = new StackPanel { Spacing = 14, Padding = new Thickness(32, 24, 32, 32), MaxWidth = 820, HorizontalAlignment = HorizontalAlignment.Left };
        root.Children.Add(new TextBlock { Text = "Sobre", Style = (Style)Application.Current.Resources["TitleTextBlockStyle"] });

        root.Children.Add(Card("Smells Like Tech Converter",
            $"Versão {AppUpdater.Current} · Windows {(Environment.Is64BitProcess ? "x64" : "x86")}",
            MusicNative.IsAvailable ? $"Núcleo de áudio nativo: ABI {MusicNative.AbiVersion >> 16}.{MusicNative.AbiVersion & 0xFFFF}" : "Núcleo de áudio nativo: indisponível (usando o motor gerenciado)",
            $"Licença deste computador: {LicenseGate.Describe()}"));

        root.Children.Add(Card("Desenvolvedor",
            "Smells Like Tech Informática",
            "Site: converter.smellsliketech.com.br",
            "Contato e suporte: converter@smellsliketech.com.br"));
        root.Children.Add(Links(("Abrir o site", AppUpdater.Site), ("Termos de uso", AppUpdater.Site + "/termos"), ("Privacidade", AppUpdater.Site + "/privacidade")));

        root.Children.Add(Card("Licenças",
            "O uso do aplicativo segue os termos e a licença ativada em Configurações → Licença.",
            "O código próprio dos clientes (site, Android, Windows e plugin) é distribuído sob a GNU GPL versão 3 ou posterior; os fontes de cada versão ficam no repositório público de demonstração.",
            "Componentes de terceiros mantêm as próprias licenças: FFmpeg (LGPL/GPL), ONNX Runtime (MIT), Spleeter e RoFormer Mega 53 (MIT), Kokoro e Sherpa-ONNX (Apache 2.0), eSpeak-NG (GPL 3), Whisper.cpp (MIT), SDK VST3 da Steinberg (MIT), Apollo do restaurador (CC BY-SA 4.0, baixado só se você usar o reparo de compressão por IA) e os plugins VST3 da biblioteca, cada um com o seu aviso."));
        var licenses = Path.Combine(AppContext.BaseDirectory, "Licenses");
        root.Children.Add(Links(("Fontes (GPL) no GitHub", "https://github.com/mikerock12/smells-like-tech-converter-demo"),
            ("Avisos de terceiros", Directory.Exists(licenses) ? licenses : AppContext.BaseDirectory)));

        var update = new StackPanel { Spacing = 8 };
        update.Children.Add(new TextBlock { Text = "Atualização", Style = (Style)Application.Current.Resources["SubtitleTextBlockStyle"] });
        update.Children.Add(new TextBlock { Text = "Procura a versão publicada no site. O instalador é baixado só quando você pede e conferido pelo SHA-256; seus arquivos, projetos e modelos ficam.", TextWrapping = TextWrapping.Wrap, Foreground = Muted });
        check.Click += async (_, _) => await CheckAsync();
        download.Click += async (_, _) => await DownloadAsync();
        install.Click += (_, _) => { if (installer is not null) AppUpdater.InstallAndRestart(installer); };
        AutomationProperties.SetName(check, "Procurar atualização");
        update.Children.Add(new StackPanel { Orientation = Orientation.Horizontal, Spacing = 10, Children = { check, download, install } });
        update.Children.Add(updateProgress);
        update.Children.Add(updateStatus);
        root.Children.Add(update);

        Content = new ScrollViewer { Content = root };
        Unloaded += (_, _) => work?.Cancel();
    }

    private static Brush Muted => (Brush)Application.Current.Resources["BrandTextMutedBrush"];

    private static FrameworkElement Card(string title, params string[] lines)
    {
        var panel = new StackPanel { Spacing = 4 };
        panel.Children.Add(new TextBlock { Text = title, Style = (Style)Application.Current.Resources["SubtitleTextBlockStyle"] });
        foreach (var line in lines) panel.Children.Add(new TextBlock { Text = line, TextWrapping = TextWrapping.Wrap, IsTextSelectionEnabled = true });
        return new Border
        {
            Child = panel, Padding = new Thickness(16), CornerRadius = new CornerRadius(8), BorderThickness = new Thickness(1),
            BorderBrush = (Brush)Application.Current.Resources["BrandStrokeBrush"],
        };
    }

    private static FrameworkElement Links(params (string Text, string Target)[] links)
    {
        var row = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 12 };
        foreach (var (text, target) in links)
        {
            var link = new HyperlinkButton { Content = text };
            link.Click += (_, _) => Process.Start(new ProcessStartInfo(target) { UseShellExecute = true });
            row.Children.Add(link);
        }
        return row;
    }

    private async Task CheckAsync()
    {
        work?.Cancel();
        work = new CancellationTokenSource();
        check.IsEnabled = false;
        download.Visibility = install.Visibility = Visibility.Collapsed;
        updateStatus.Text = "Procurando…";
        try
        {
            release = await AppUpdater.LatestAsync(App.Services.Http, work.Token);
            if (!AppUpdater.IsNewer(release))
            {
                updateStatus.Text = $"Você já tem a versão mais recente ({AppUpdater.Current}).";
                return;
            }
            updateStatus.Text = $"Versão {release.Version} disponível (publicada em {release.Published}).";
            download.Content = $"Baixar a {release.Version} ({release.Bytes / 1048576.0:0} MB)";
            download.Visibility = Visibility.Visible;
        }
        catch (Exception e) when (e is System.Net.Http.HttpRequestException or InvalidDataException or System.Text.Json.JsonException or KeyNotFoundException or TaskCanceledException)
        {
            updateStatus.Text = $"Não foi possível procurar agora: {e.Message}";
        }
        finally
        {
            check.IsEnabled = true;
        }
    }

    private async Task DownloadAsync()
    {
        if (release is null) return;
        work?.Cancel();
        work = new CancellationTokenSource();
        download.IsEnabled = check.IsEnabled = false;
        updateProgress.Visibility = Visibility.Visible;
        using var background = BackgroundWork.Begin("Baixando a atualização");
        try
        {
            installer = await AppUpdater.DownloadAsync(App.Services.Http, release, new Progress<double>(f =>
            {
                updateProgress.Value = f;
                updateStatus.Text = $"Baixando · {f:P0}";
                background.Report(f);
            }), work.Token);
            updateStatus.Text = $"Instalador da {release.Version} baixado e conferido (SHA-256). Ao instalar, o aplicativo fecha, atualiza e abre de novo.";
            download.Visibility = Visibility.Collapsed;
            install.Visibility = Visibility.Visible;
        }
        catch (Exception e) when (e is System.Net.Http.HttpRequestException or InvalidDataException or IOException or TaskCanceledException)
        {
            updateStatus.Text = $"O download não terminou: {e.Message}";
        }
        finally
        {
            download.IsEnabled = check.IsEnabled = true;
            updateProgress.Visibility = Visibility.Collapsed;
        }
    }
}
