using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Imaging;
using SmellsLikeTech.Converter.Core;
using SmellsLikeTech.Converter.Core.Abstractions;
using SmellsLikeTech.Converter.Core.Jobs;
using SmellsLikeTech.Converter.Core.Media;
using SmellsLikeTech.Converter.Core.Operations;
using SmellsLikeTech.Converter.Core.Presets;
using SmellsLikeTech.Converter.Desktop.Services;
using SmellsLikeTech.Converter.Desktop.ViewModels;
using Windows.ApplicationModel.DataTransfer;
using Windows.Storage;

namespace SmellsLikeTech.Converter.Desktop.Pages;

/// <summary>
/// Tela principal: detecta o arquivo, mostra somente as operações compatíveis
/// e monta o job. Nenhuma conversão acontece aqui - quem executa é a fila.
/// Vários arquivos de uma vez seguem para a tela de lote.
/// </summary>
public sealed partial class HomePage : Page
{
    private MediaInfo? currentFile;
    private OperationDescriptor? currentOperation;
    private string outputDirectory = string.Empty;
    private bool suppressSelection;

    public HomePage()
    {
        InitializeComponent();
        Loaded += OnLoaded;
    }

    private void OnLoaded(object sender, RoutedEventArgs e)
    {
        MascotImage.Source = new BitmapImage(BrandInfo.AssetUri("mascot.png"));
        outputDirectory = App.Services.OutputDirectory;
        OutputFolderText.Text = outputDirectory;
        UpdateEngineStatus();
    }

    private void UpdateEngineStatus()
    {
        var services = App.Services;
        var encoders = services.Hardware.HardwareEncoders.Count > 0
            ? string.Join(", ", services.Hardware.HardwareEncoders.Take(3))
            : "somente CPU";

        EngineStatusText.Text = services.FfmpegAvailable
            ? $"FFmpeg pronto · aceleração: {encoders}"
            : "FFmpeg não encontrado no PATH. Configure o caminho em Configurações.";
    }

    // ==================== Entrada de arquivo ====================

    private void OnDragOver(object sender, DragEventArgs e)
    {
        if (!e.DataView.Contains(StandardDataFormats.StorageItems))
        {
            return;
        }

        e.AcceptedOperation = DataPackageOperation.Copy;
        e.DragUIOverride.Caption = "Soltar para detectar";
        e.DragUIOverride.IsCaptionVisible = true;
        e.Handled = true;
        DropZone.Background = new SolidColorBrush(Microsoft.UI.ColorHelper.FromArgb(0x22, 0xFF, 0x7A, 0x18));
    }

    private void OnDragLeave(object sender, DragEventArgs e) => ResetDropZone();

    private void ResetDropZone() =>
        DropZone.Background = new SolidColorBrush(Microsoft.UI.ColorHelper.FromArgb(0xFF, 0x0B, 0x0B, 0x10));

    private async void OnDrop(object sender, DragEventArgs e)
    {
        // O evento sobe da área pontilhada para a página: tratar uma vez só.
        e.Handled = true;
        ResetDropZone();

        var adiar = e.GetDeferral();
        try
        {
            if (!e.DataView.Contains(StandardDataFormats.StorageItems))
            {
                ShowStatus("Esse conteúdo não é um arquivo do computador.", InfoBarSeverity.Warning);
                return;
            }

            var items = await e.DataView.GetStorageItemsAsync();
            var files = items.OfType<StorageFile>().Select(file => file.Path).ToList();
            if (files.Count == 0)
            {
                ShowStatus("Solte um arquivo (pastas ainda não são aceitas).", InfoBarSeverity.Warning);
                return;
            }

            App.Services.Log.Write(
                LogChannel.App,
                $"Arquivos soltos na janela: {files.Count} · {string.Join(", ", files.Select(Path.GetExtension).Distinct())}");
            await AcceptAsync(files);
        }
        catch (Exception exception)
        {
            // Falhar em silêncio aqui dá a impressão de aplicativo travado.
            App.Services.Log.Error("drop_failed", "Não foi possível ler o arquivo arrastado.", exception);
            ShowStatus("Não foi possível ler o arquivo arrastado. Tente pelo botão Selecionar arquivo.", InfoBarSeverity.Error);
        }
        finally
        {
            adiar.Complete();
        }
    }

    private async void OnPickFile(object sender, RoutedEventArgs e)
    {
        await PickAsync(sender as Button, () =>
        {
            var caminho = FileDialogs.PickInputFile(App.MainWindow);
            return caminho is null ? [] : new List<string> { caminho };
        });
    }

    private async void OnPickManyFiles(object sender, RoutedEventArgs e)
    {
        await PickAsync(sender as Button, () => FileDialogs.PickInputFiles(App.MainWindow));
    }

    private async Task PickAsync(Button? botao, Func<IReadOnlyList<string>> escolher)
    {
        if (botao is not null)
        {
            botao.IsEnabled = false;
        }

        try
        {
            var caminhos = escolher();
            if (caminhos.Count > 0)
            {
                await AcceptAsync(caminhos);
            }
        }
        catch (Exception exception)
        {
            App.Services.Log.Error("file_picker_failed", "A janela de seleção de arquivo não pôde ser aberta.", exception);
            ShowStatus(
                "Não foi possível abrir a janela de seleção. Arraste o arquivo para esta tela como alternativa.",
                InfoBarSeverity.Error);
        }
        finally
        {
            if (botao is not null)
            {
                botao.IsEnabled = true;
            }
        }
    }

    /// <summary>Um arquivo fica aqui; vários vão para a tela de lote, que agrupa por tipo.</summary>
    private async Task AcceptAsync(IReadOnlyList<string> paths)
    {
        if (paths.Count == 1)
        {
            await LoadFileAsync(paths[0]);
            return;
        }

        if (App.MainWindow is MainWindow main)
        {
            main.ShowBatch(paths);
        }
    }

    private async Task LoadFileAsync(string path)
    {
        try
        {
            ConvertButton.IsEnabled = false;
            currentFile = await App.Services.Inspector.InspectAsync(path, CancellationToken.None);
        }
        catch (ConversionException exception)
        {
            currentFile = null;
            FileCard.Visibility = Visibility.Collapsed;
            OptionsCard.Visibility = Visibility.Collapsed;
            OperationList.ItemsSource = null;
            ShowStatus(exception.Message, InfoBarSeverity.Error);
            return;
        }

        StatusBar.IsOpen = false;
        ShowFileCard(currentFile);
        PopulateOperations(currentFile);
    }

    private void ShowFileCard(MediaInfo info)
    {
        FileCard.Visibility = Visibility.Visible;
        FileNameText.Text = info.FileName;
        FileKindIcon.Glyph = info.Kind switch
        {
            MediaKind.Image => "",
            MediaKind.Video => "",
            MediaKind.Audio => "",
            MediaKind.Text => "",
            MediaKind.Pdf => "",
            _ => ""
        };

        var meta = new List<string> { info.Kind.Display(), info.Extension.ToUpperInvariant(), info.SizeDisplay };
        if (info.Width is > 0 && info.Height is > 0)
        {
            meta.Add($"{info.Width}x{info.Height}");
        }

        if (info.Duration is { } duration)
        {
            meta.Add(MediaInfo.FormatDuration(duration));
        }

        if (info.Container is not null)
        {
            meta.Add(info.Container);
        }

        FileMetaText.Text = string.Join(" · ", meta);

        var streams = new List<string>();
        foreach (var video in info.VideoStreams)
        {
            var fps = video.FrameRate is { } rate ? $" · {rate:0.##} fps" : string.Empty;
            streams.Add($"Vídeo: {video.Codec} {video.Width}x{video.Height}{fps}");
        }

        foreach (var audio in info.AudioStreams)
        {
            var channels = audio.Channels == 1 ? "mono" : audio.Channels == 2 ? "estéreo" : $"{audio.Channels} canais";
            streams.Add($"Áudio: {audio.Codec} · {audio.SampleRateHz} Hz · {channels}");
        }

        if (info.Kind == MediaKind.Image && info.HasAlpha)
        {
            streams.Add("Possui transparência");
        }

        FileStreamsText.Text = string.Join(Environment.NewLine, streams);
        FileStreamsText.Visibility = streams.Count > 0 ? Visibility.Visible : Visibility.Collapsed;

        FileWarningBar.IsOpen = info.ProbeError is not null;
        FileWarningBar.Message = info.ProbeError ?? string.Empty;
    }

    private void PopulateOperations(MediaInfo info)
    {
        var operations = OperationCatalog.For(info)
            .Select(descriptor => new OperationItemViewModel(descriptor))
            .ToList();

        suppressSelection = true;
        OperationList.ItemsSource = operations;
        suppressSelection = false;

        OperationHintText.Text = operations.Count == 0
            ? "Nenhuma conversão disponível para este tipo de arquivo."
            : $"{operations.Count} conversão(ões) compatível(is) com {info.Kind.Display().ToLowerInvariant()}.";

        var first = operations.FirstOrDefault(item => item.IsAvailable);
        if (first is not null)
        {
            OperationList.SelectedItem = first;
        }
        else
        {
            currentOperation = null;
            OptionsCard.Visibility = Visibility.Collapsed;
            ConvertButton.IsEnabled = false;
        }
    }

    // ==================== Operação e opções ====================

    private void OnOperationChanged(object sender, SelectionChangedEventArgs e)
    {
        if (suppressSelection || OperationList.SelectedItem is not OperationItemViewModel item)
        {
            return;
        }

        currentOperation = item.Descriptor;

        if (!item.IsAvailable)
        {
            OptionsCard.Visibility = Visibility.Collapsed;
            PresetCard.Visibility = Visibility.Collapsed;
            ConvertButton.IsEnabled = false;
            ShowStatus(
                $"“{item.Title}” entra em um marco futuro ({item.BadgeText}).",
                InfoBarSeverity.Informational);
            return;
        }

        StatusBar.IsOpen = false;
        OptionsCard.Visibility = Visibility.Visible;
        ConvertButton.IsEnabled = true;

        OptionsView.ShowFor(currentOperation, currentFile);
        PopulatePresets();
    }

    private void PopulatePresets()
    {
        if (currentFile is null || currentOperation is null)
        {
            PresetCard.Visibility = Visibility.Collapsed;
            return;
        }

        var presets = PresetCatalog.For(currentFile.Kind)
            .Where(preset => preset.Options.Operation == currentOperation.Id)
            .Select(preset => new PresetItemViewModel(preset))
            .ToList();

        suppressSelection = true;
        PresetList.ItemsSource = presets;
        PresetList.SelectedItem = null;
        suppressSelection = false;
        PresetCard.Visibility = presets.Count > 0 ? Visibility.Visible : Visibility.Collapsed;
    }

    private void OnPresetChanged(object sender, SelectionChangedEventArgs e)
    {
        if (suppressSelection || PresetList.SelectedItem is not PresetItemViewModel item)
        {
            return;
        }

        OptionsView.Apply(item.Preset.Options);
        ShowStatus($"Preset “{item.Name}” aplicado.", InfoBarSeverity.Success);
    }

    // ==================== Ação ====================

    private async void OnPickOutputFolder(object sender, RoutedEventArgs e)
    {
        try
        {
            var pasta = FileDialogs.PickOutputFolder(App.MainWindow);
            if (pasta is null)
            {
                return;
            }

            outputDirectory = pasta;
            OutputFolderText.Text = outputDirectory;
            await App.Services.SettingsStore.RememberFolderAsync(outputDirectory, CancellationToken.None);
        }
        catch (Exception exception)
        {
            App.Services.Log.Error("folder_picker_failed", "A escolha de pasta falhou.", exception);
            ShowStatus("Não foi possível abrir a escolha de pasta.", InfoBarSeverity.Error);
        }
    }

    private void OnConvert(object sender, RoutedEventArgs e)
    {
        if (currentFile is null || currentOperation is null)
        {
            ShowStatus("Escolha um arquivo primeiro.", InfoBarSeverity.Warning);
            return;
        }

        if (!LicenseGate.CanConvert(out var licenca))
        {
            ShowStatus(licenca, InfoBarSeverity.Warning);
            return;
        }

        try
        {
            var options = OptionsView.Build();
            options.Validate();

            var job = new ConversionJob
            {
                Options = options,
                InputPath = currentFile.Path,
                Input = currentFile,
                OutputDirectory = string.IsNullOrWhiteSpace(outputDirectory)
                    ? App.Services.OutputDirectory
                    : outputDirectory,
                OutputBaseName = SafeFileName.FromPath(currentFile.Path)
            };

            App.Services.Queue.Enqueue(job);
            ShowStatus($"“{currentFile.FileName}” entrou na fila.", InfoBarSeverity.Success);

            if (App.MainWindow is MainWindow main)
            {
                main.ShowQueue();
            }
        }
        catch (ConversionException exception)
        {
            ShowStatus(exception.Message, InfoBarSeverity.Error);
        }
    }

    private void ShowStatus(string message, InfoBarSeverity severity)
    {
        StatusBar.Severity = severity;
        StatusBar.Message = message;
        StatusBar.IsOpen = true;
    }
}
