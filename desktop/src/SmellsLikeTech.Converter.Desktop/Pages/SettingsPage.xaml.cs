using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media.Imaging;
using SmellsLikeTech.Converter.Core;
using SmellsLikeTech.Converter.Core.Licensing;
using SmellsLikeTech.Converter.Core.Media;
using SmellsLikeTech.Converter.Core.Options;
using SmellsLikeTech.Converter.Composition;
using SmellsLikeTech.Converter.Desktop.Services;
using SmellsLikeTech.Converter.Desktop.ViewModels;
using SmellsLikeTech.Converter.Engine.Documents;
using SmellsLikeTech.Converter.Engine.Speech;
using SmellsLikeTech.Converter.Infrastructure.Configuration;
using SmellsLikeTech.Converter.Infrastructure.Storage;
using Windows.Storage.Pickers;

namespace SmellsLikeTech.Converter.Desktop.Pages;

public sealed partial class SettingsPage : Page
{
    private CancellationTokenSource? downloadCancellation;

    public SettingsPage()
    {
        InitializeComponent();
        Loaded += OnLoaded;
        Unloaded += (_, _) => downloadCancellation?.Cancel();
    }

    private void OnLoaded(object sender, RoutedEventArgs e)
    {
        var services = App.Services;
        var settings = services.Settings;

        RootBox.Text = services.Paths.Root;
        OutputBox.Text = services.OutputDirectory;
        FfmpegBox.Text = settings.FfmpegPath;
        FfprobeBox.Text = settings.FfprobePath;

        ConcurrencyBox.Value = settings.MaxConcurrentJobs;
        TimeoutBox.Value = settings.JobTimeoutMinutes;
        DiskReserveBox.Value = settings.MinimumFreeDiskGb;
        TtsLimitBox.Value = settings.MaxTextToSpeechCharacters;
        TranscriptionLimitBox.Value = settings.MaxTranscriptionMinutes;
        FailedRetentionBox.Value = settings.FailedJobRetentionHours;
        DeleteTempCheck.IsChecked = settings.DeleteWorkspaceOnSuccess;
        KeepFailedCheck.IsChecked = settings.KeepWorkspaceOnFailure;

        AccelerationCombo.ItemsSource = new List<Choice<HardwareAcceleration>>
        {
            new("Automático", HardwareAcceleration.Automatic),
            new("Somente CPU", HardwareAcceleration.Cpu),
            new("NVIDIA (NVENC)", HardwareAcceleration.Nvidia),
            new("AMD (AMF)", HardwareAcceleration.Amd),
            new("Intel (QSV)", HardwareAcceleration.Intel)
        };
        AccelerationCombo.SelectedIndex = (int)settings.Acceleration;

        AboutIcon.Source = new BitmapImage(BrandInfo.AssetUri("icon-256.png"));
        VersionText.Text = $"Versão {BrandInfo.Version} · aplicativo local para Windows";
        ModelsFolderText.Text = $"Os modelos ficam em {services.Models.ModelsDirectory} e são baixados sob demanda.";

        UpdateEngineStatus();
        UpdateHardware();
        RefreshModels();
        UpdateLicense();
    }

    private void UpdateLicense()
    {
        LicenseStatusText.Text = LicenseGate.Describe();
        LicenseBox.Text = App.Services.Settings.LicenseKey;
    }

    private async void OnActivateLicense(object sender, RoutedEventArgs e)
    {
        var key = LicenseBox.Text.Trim();
        if (string.IsNullOrEmpty(key))
        {
            ShowLicense("Cole a chave antes de ativar.", InfoBarSeverity.Warning);
            return;
        }

        var verification = await App.Services.ActivateLicenseAsync(key, CancellationToken.None);
        switch (verification.Status)
        {
            case LicenseStatus.Valid when verification.Claims is { UnlocksDesktop: true } claims:
                ShowLicense($"Licença ativada. Obrigado, {claims.Email}!", InfoBarSeverity.Success);
                break;
            case LicenseStatus.Valid:
                ShowLicense("Essa chave é do plano Pro do site e do plugin. O aplicativo precisa da licença vitalícia (ou do pacote completo).", InfoBarSeverity.Warning);
                break;
            case LicenseStatus.BadSignature:
                ShowLicense("Essa chave não foi emitida por nós, ou foi alterada. Confira se copiou inteira.", InfoBarSeverity.Error);
                break;
            default:
                ShowLicense("Isso não parece uma chave. Ela começa com SLT1. e tem três partes separadas por ponto.", InfoBarSeverity.Error);
                break;
        }

        UpdateLicense();
    }

    private async void OnRemoveLicense(object sender, RoutedEventArgs e)
    {
        await App.Services.RemoveLicenseAsync(CancellationToken.None);
        UpdateLicense();
        ShowLicense("Licença removida deste computador.", InfoBarSeverity.Informational);
    }

    private void ShowLicense(string message, InfoBarSeverity severity)
    {
        LicenseBar.Message = message;
        LicenseBar.Severity = severity;
        LicenseBar.IsOpen = true;
    }

    private void UpdateEngineStatus()
    {
        var services = App.Services;
        var ffmpeg = services.FfmpegAvailable ? "encontrado" : "NÃO encontrado";
        var ffprobe = services.FfprobeAvailable ? "encontrado" : "NÃO encontrado";
        EngineStatusText.Text = $"FFmpeg: {ffmpeg} · FFprobe: {ffprobe} · Imagens: ImageMagick (embutido) · Voz: {services.TtsEngine.Name}";

        var tools = DocumentToolset.Detect();
        DocumentToolsText.Text = "Marcos 6 e 7 — " + string.Join(" · ", tools.Select(tool =>
            $"{tool.DisplayName}: {(tool.IsInstalled ? "instalado" : "ausente")}"));
    }

    private void UpdateHardware()
    {
        var hardware = App.Services.Hardware;
        var gpus = hardware.Gpus.Count > 0 ? string.Join(", ", hardware.Gpus) : "não detectada";
        var encoders = hardware.HardwareEncoders.Count > 0
            ? string.Join(", ", hardware.HardwareEncoders)
            : "nenhum (usará CPU)";

        HardwareText.Text =
            $"CPU: {hardware.CpuName} ({hardware.LogicalCores} núcleos lógicos)" + Environment.NewLine +
            $"Memória disponível: {MediaInfo.FormatBytes(hardware.TotalMemoryBytes)}" + Environment.NewLine +
            $"GPU: {gpus}" + Environment.NewLine +
            $"Encoders de hardware: {encoders}" + Environment.NewLine +
            $"Disco {hardware.DiskRoot}: {MediaInfo.FormatBytes(hardware.FreeDiskBytes)} livres";
    }

    private void RefreshModels()
    {
        var models = App.Services.Models.ListModels();

        ModelList.ItemsSource = models
            .Select(model => new SpeechModelItemViewModel(
                model,
                WhisperModelCatalog.Descriptors.FirstOrDefault(descriptor => descriptor.Id == model.Id)?.Notes ?? string.Empty))
            .ToList();

        var escolhas = models
            .Select(model => new Choice<string>(
                model.IsInstalled ? $"{model.DisplayName} · instalado" : $"{model.DisplayName} · não baixado",
                model.Id))
            .ToList();

        var selecionado = (DefaultModelCombo.SelectedItem as Choice<string>)?.Value
            ?? App.Services.Settings.DefaultWhisperModel;

        DefaultModelCombo.ItemsSource = escolhas;
        DefaultModelCombo.SelectedIndex = Math.Max(0, escolhas.FindIndex(escolha => escolha.Value == selecionado));
    }

    // ==================== Ações ====================

    private void OnOpenRoot(object sender, RoutedEventArgs e) => ShellHelper.OpenFolder(App.Services.Paths.Root);

    private void OnOpenOutput(object sender, RoutedEventArgs e) => ShellHelper.OpenFolder(OutputBox.Text);

    private void OnOpenLogs(object sender, RoutedEventArgs e) => ShellHelper.OpenFolder(App.Services.Paths.Logs);

    private void OnPickOutput(object sender, RoutedEventArgs e)
    {
        try
        {
            var pasta = FileDialogs.PickOutputFolder(App.MainWindow);
            if (pasta is not null)
            {
                OutputBox.Text = pasta;
            }
        }
        catch (Exception exception)
        {
            App.Services.Log.Error("folder_picker_failed", "A escolha de pasta falhou.", exception);
            Show("Não foi possível abrir a escolha de pasta.", InfoBarSeverity.Error);
        }
    }

    private async void OnSave(object sender, RoutedEventArgs e)
    {
        var current = App.Services.Settings;
        var settings = current with
        {
            OutputDirectory = OutputBox.Text.Trim(),
            FfmpegPath = string.IsNullOrWhiteSpace(FfmpegBox.Text) ? "ffmpeg" : FfmpegBox.Text.Trim(),
            FfprobePath = string.IsNullOrWhiteSpace(FfprobeBox.Text) ? "ffprobe" : FfprobeBox.Text.Trim(),
            MaxConcurrentJobs = (int)ConcurrencyBox.Value,
            JobTimeoutMinutes = (int)TimeoutBox.Value,
            MinimumFreeDiskGb = (int)DiskReserveBox.Value,
            MaxTextToSpeechCharacters = (int)TtsLimitBox.Value,
            MaxTranscriptionMinutes = (int)TranscriptionLimitBox.Value,
            FailedJobRetentionHours = (int)FailedRetentionBox.Value,
            DeleteWorkspaceOnSuccess = DeleteTempCheck.IsChecked == true,
            KeepWorkspaceOnFailure = KeepFailedCheck.IsChecked == true,
            Acceleration = (AccelerationCombo.SelectedItem as Choice<HardwareAcceleration>)?.Value
                ?? HardwareAcceleration.Automatic,
            DefaultWhisperModel = (DefaultModelCombo.SelectedItem as Choice<string>)?.Value
                ?? current.DefaultWhisperModel
        };

        try
        {
            await App.Services.ApplySettingsAsync(settings, CancellationToken.None);
            UpdateEngineStatus();
            UpdateHardware();
            Show("Configurações salvas.", InfoBarSeverity.Success);
        }
        catch (ConversionException exception)
        {
            Show(exception.Message, InfoBarSeverity.Error);
        }
    }

    private void OnCleanNow(object sender, RoutedEventArgs e)
    {
        var services = App.Services;
        var retention = new RetentionService(services.Paths, services.Log);
        var removed = retention.CleanupTemp(TimeSpan.Zero)
            + retention.CleanupCache(TimeSpan.Zero)
            + retention.CleanupFailedJobs(TimeSpan.Zero);

        Show(
            removed > 0 ? $"{removed} pasta(s) temporária(s) removida(s)." : "Não havia temporários para remover.",
            InfoBarSeverity.Success);
    }

    private async void OnDownloadModel(object sender, RoutedEventArgs e)
    {
        if (sender is not Button { Tag: string modelId })
        {
            return;
        }

        var descriptor = WhisperModelCatalog.Descriptors.FirstOrDefault(item => item.Id == modelId);
        if (descriptor is null)
        {
            return;
        }

        var dialog = new ContentDialog
        {
            XamlRoot = XamlRoot,
            Title = $"Baixar modelo {descriptor.DisplayName}",
            Content = $"Serão baixados aproximadamente {MediaInfo.FormatBytes(descriptor.ApproximateBytes)} " +
                      $"de {new Uri(descriptor.DownloadUrl).Host} para {App.Services.Models.ModelsDirectory}.",
            PrimaryButtonText = "Baixar",
            CloseButtonText = "Cancelar",
            DefaultButton = ContentDialogButton.Close
        };

        if (await dialog.ShowAsync() != ContentDialogResult.Primary)
        {
            return;
        }

        downloadCancellation?.Cancel();
        downloadCancellation = new CancellationTokenSource();
        DownloadProgress.Visibility = Visibility.Visible;
        DownloadProgress.Value = 0;

        var progress = new Progress<double>(value => DownloadProgress.Value = value * 100);

        try
        {
            Show($"Baixando {descriptor.DisplayName}…", InfoBarSeverity.Informational);
            await App.Services.ModelInstaller.InstallAsync(modelId, progress, downloadCancellation.Token);
            Show($"Modelo {descriptor.DisplayName} instalado.", InfoBarSeverity.Success);
        }
        catch (ConversionException exception)
        {
            Show(exception.Message, InfoBarSeverity.Error);
        }
        catch (OperationCanceledException)
        {
            Show("Download cancelado.", InfoBarSeverity.Warning);
        }
        finally
        {
            DownloadProgress.Visibility = Visibility.Collapsed;
            RefreshModels();
        }
    }

    private async void OnRemoveModel(object sender, RoutedEventArgs e)
    {
        if (sender is not Button { Tag: string modelId })
        {
            return;
        }

        var dialog = new ContentDialog
        {
            XamlRoot = XamlRoot,
            Title = "Remover modelo",
            Content = "O arquivo do modelo será apagado do disco. Você poderá baixá-lo novamente quando quiser.",
            PrimaryButtonText = "Remover",
            CloseButtonText = "Cancelar",
            DefaultButton = ContentDialogButton.Close
        };

        if (await dialog.ShowAsync() != ContentDialogResult.Primary)
        {
            return;
        }

        App.Services.ModelInstaller.Remove(modelId);
        RefreshModels();
        Show("Modelo removido.", InfoBarSeverity.Success);
    }

    private void Show(string message, InfoBarSeverity severity)
    {
        StatusBar.Severity = severity;
        StatusBar.Message = message;
        StatusBar.IsOpen = true;
    }
}
