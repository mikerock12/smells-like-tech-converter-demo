using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Navigation;
using SmellsLikeTech.Converter.Core;
using SmellsLikeTech.Converter.Core.Abstractions;
using SmellsLikeTech.Converter.Core.Batch;
using SmellsLikeTech.Converter.Core.Jobs;
using SmellsLikeTech.Converter.Core.Media;
using SmellsLikeTech.Converter.Desktop.Controls;
using SmellsLikeTech.Converter.Desktop.Services;
using SmellsLikeTech.Converter.Desktop.ViewModels;
using Windows.ApplicationModel.DataTransfer;
using Windows.Storage;

namespace SmellsLikeTech.Converter.Desktop.Pages;

/// <summary>
/// Conversão em lote. Os arquivos entram todos juntos, são agrupados por tipo e cada
/// grupo responde uma pergunta só: o mesmo para todos, ou um por um. A página monta os
/// jobs e entrega para a mesma fila de sempre — não existe fila separada para lote.
/// </summary>
public sealed partial class BatchPage : Page
{
    private readonly List<MediaInfo> files = [];
    private readonly List<BatchGroupCard> cards = [];
    private string outputDirectory = string.Empty;

    public BatchPage()
    {
        InitializeComponent();

        // Ir até a fila e voltar não pode apagar um lote que a pessoa acabou de montar.
        NavigationCacheMode = NavigationCacheMode.Required;
        Loaded += OnLoaded;
    }

    private void OnLoaded(object sender, RoutedEventArgs e)
    {
        if (string.IsNullOrEmpty(outputDirectory))
        {
            outputDirectory = App.Services.OutputDirectory;
        }

        OutputFolderText.Text = outputDirectory;
    }

    protected override async void OnNavigatedTo(NavigationEventArgs e)
    {
        base.OnNavigatedTo(e);

        if (e.Parameter is IReadOnlyList<string> paths && paths.Count > 0)
        {
            await AddAsync(paths);
        }
    }

    // ==================== Entrada de arquivos ====================

    private void OnDragOver(object sender, DragEventArgs e)
    {
        if (!e.DataView.Contains(StandardDataFormats.StorageItems))
        {
            return;
        }

        e.AcceptedOperation = DataPackageOperation.Copy;
        e.DragUIOverride.Caption = "Soltar para adicionar ao lote";
        e.DragUIOverride.IsCaptionVisible = true;
        e.Handled = true;
        DropZone.Background = new SolidColorBrush(Microsoft.UI.ColorHelper.FromArgb(0x22, 0xFF, 0x7A, 0x18));
    }

    private void OnDragLeave(object sender, DragEventArgs e) => ResetDropZone();

    private void ResetDropZone() =>
        DropZone.Background = new SolidColorBrush(Microsoft.UI.ColorHelper.FromArgb(0xFF, 0x0B, 0x0B, 0x10));

    private async void OnDrop(object sender, DragEventArgs e)
    {
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
            var paths = items.OfType<StorageFile>().Select(file => file.Path).ToList();
            if (paths.Count == 0)
            {
                ShowStatus("Solte arquivos (pastas ainda não são aceitas).", InfoBarSeverity.Warning);
                return;
            }

            await AddAsync(paths);
        }
        catch (Exception exception)
        {
            App.Services.Log.Error("batch_drop_failed", "Não foi possível ler os arquivos arrastados.", exception);
            ShowStatus("Não foi possível ler os arquivos arrastados. Tente pelo botão Adicionar arquivos.", InfoBarSeverity.Error);
        }
        finally
        {
            adiar.Complete();
        }
    }

    private async void OnPickFiles(object sender, RoutedEventArgs e)
    {
        var botao = sender as Button;
        if (botao is not null)
        {
            botao.IsEnabled = false;
        }

        try
        {
            var caminhos = FileDialogs.PickInputFiles(App.MainWindow);
            if (caminhos.Count > 0)
            {
                await AddAsync(caminhos);
            }
        }
        catch (Exception exception)
        {
            App.Services.Log.Error("file_picker_failed", "A janela de seleção de arquivo não pôde ser aberta.", exception);
            ShowStatus(
                "Não foi possível abrir a janela de seleção. Arraste os arquivos para esta tela como alternativa.",
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

    /// <summary>
    /// Detecta cada arquivo e recalcula os grupos. O que não puder ser lido é listado
    /// pelo nome em vez de derrubar o lote inteiro.
    /// </summary>
    private async Task AddAsync(IReadOnlyList<string> paths)
    {
        var conhecidos = files.Select(file => file.Path).ToHashSet(StringComparer.OrdinalIgnoreCase);
        var recusados = new List<string>();
        var novos = 0;

        foreach (var path in paths)
        {
            if (conhecidos.Contains(path))
            {
                continue;
            }

            try
            {
                var info = await App.Services.Inspector.InspectAsync(path, CancellationToken.None);
                files.Add(info);
                conhecidos.Add(info.Path);
                novos++;
            }
            catch (ConversionException exception)
            {
                recusados.Add($"{Path.GetFileName(path)} — {exception.Message}");
            }
        }

        App.Services.Log.Write(LogChannel.App, $"Lote: {novos} arquivo(s) adicionado(s), {recusados.Count} recusado(s).");

        RejectedBar.IsOpen = recusados.Count > 0;
        RejectedBar.Title = recusados.Count == 1 ? "1 arquivo não entrou" : $"{recusados.Count} arquivos não entraram";
        RejectedBar.Message = string.Join(Environment.NewLine, recusados);

        Rebuild();

        if (novos > 0)
        {
            StatusBar.IsOpen = false;
        }
    }

    /// <summary>
    /// Recalcula os grupos preservando o que já foi configurado: um cartão só é criado
    /// quando aparece um tipo de arquivo que ainda não estava no lote.
    /// </summary>
    private void Rebuild()
    {
        var groups = BatchPlanner.Group(files);
        var existentes = cards.ToDictionary(card => card.Model.Signature, StringComparer.Ordinal);

        cards.Clear();
        GroupsPanel.Children.Clear();

        foreach (var group in groups)
        {
            var assinatura = BatchPlanner.SignatureOf(group.Files[0]);
            if (existentes.TryGetValue(assinatura, out var card))
            {
                card.Refresh(group);
            }
            else
            {
                card = new BatchGroupCard();
                card.Initialize(new BatchGroupViewModel(group));
            }

            cards.Add(card);
            GroupsPanel.Children.Add(card);
        }

        UpdateHeader(groups);
    }

    private void UpdateHeader(IReadOnlyList<BatchGroup> groups)
    {
        var total = files.Count;
        var convertiveis = BatchPlanner.ConvertibleCount(groups);

        EmptyCard.Visibility = total == 0 ? Visibility.Visible : Visibility.Collapsed;
        ClearButton.IsEnabled = total > 0;
        ConvertButton.IsEnabled = convertiveis > 0;
        ConvertButtonText.Text = convertiveis > 0 ? $"CONVERTER TUDO ({convertiveis})" : "CONVERTER TUDO";

        SummaryText.Text = total == 0
            ? "Arraste os arquivos aqui. Eles são agrupados por tipo, e cada grupo escolhe o que fazer."
            : $"{total} arquivo(s) · {groups.Count} grupo(s) · {MediaInfo.FormatBytes(files.Sum(file => file.SizeBytes))}";
    }

    private void OnClear(object sender, RoutedEventArgs e)
    {
        files.Clear();
        RejectedBar.IsOpen = false;
        StatusBar.IsOpen = false;
        Rebuild();
    }

    // ==================== Saída e ação ====================

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

    private void OnConvertAll(object sender, RoutedEventArgs e)
    {
        var destino = string.IsNullOrWhiteSpace(outputDirectory) ? App.Services.OutputDirectory : outputDirectory;
        List<ConversionJob> jobs = [];

        // Monta e valida tudo antes de enfileirar qualquer coisa: meia fila enviada e
        // um erro no meio deixaria o usuário sem saber o que entrou e o que não entrou.
        try
        {
            foreach (var card in cards)
            {
                jobs.AddRange(card.BuildJobs(destino));
            }
        }
        catch (ConversionException exception)
        {
            ShowStatus(exception.Message, InfoBarSeverity.Error);
            return;
        }

        if (jobs.Count == 0)
        {
            ShowStatus("Nenhum arquivo do lote tem conversão disponível.", InfoBarSeverity.Warning);
            return;
        }

        if (!LicenseGate.CanConvert(out var licenca))
        {
            ShowStatus(licenca, InfoBarSeverity.Warning);
            return;
        }

        var enfileirados = 0;
        var falhas = new List<string>();
        foreach (var job in jobs)
        {
            try
            {
                App.Services.Queue.Enqueue(job);
                enfileirados++;
            }
            catch (ConversionException exception)
            {
                falhas.Add($"{job.DisplayName} — {exception.Message}");
            }
        }

        if (falhas.Count > 0)
        {
            ShowStatus(
                $"{enfileirados} na fila. Não entraram: {string.Join("; ", falhas)}",
                InfoBarSeverity.Warning);
            return;
        }

        files.Clear();
        Rebuild();
        ShowStatus($"{enfileirados} arquivo(s) entraram na fila.", InfoBarSeverity.Success);

        if (App.MainWindow is MainWindow main)
        {
            main.ShowQueue();
        }
    }

    private void ShowStatus(string message, InfoBarSeverity severity)
    {
        StatusBar.Severity = severity;
        StatusBar.Message = message;
        StatusBar.IsOpen = true;
    }
}
