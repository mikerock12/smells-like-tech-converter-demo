using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using SmellsLikeTech.Converter.Core;
using SmellsLikeTech.Converter.Core.Batch;
using SmellsLikeTech.Converter.Core.Jobs;
using SmellsLikeTech.Converter.Core.Operations;
using SmellsLikeTech.Converter.Core.Options;
using SmellsLikeTech.Converter.Core.Presets;
using SmellsLikeTech.Converter.Desktop.ViewModels;

namespace SmellsLikeTech.Converter.Desktop.Controls;

/// <summary>
/// O cartão de um grupo do lote. Faz uma pergunta só: converter todos do mesmo jeito ou
/// configurar um por um. Nos dois casos os campos vêm do mesmo
/// <see cref="ConversionOptionsView"/>; o que muda é de quem são as opções que ele edita.
/// </summary>
public sealed partial class BatchGroupCard : UserControl
{
    private BatchGroupViewModel model = null!;
    private BatchFileViewModel? editing;
    private JobOptions? groupOptions;
    private OperationDescriptor? groupOperation;
    private bool loading;

    public BatchGroupCard()
    {
        InitializeComponent();
    }

    /// <summary>Interno pelo mesmo motivo do <see cref="ConversionOptionsView.Operation"/>.</summary>
    internal BatchGroupViewModel Model => model;

    internal void Initialize(BatchGroupViewModel group)
    {
        model = group;
        loading = true;

        KindIcon.Glyph = group.Glyph;
        TitleText.Text = group.Title;
        DetailText.Text = group.Detail;
        PlainFileList.ItemsSource = group.Files;
        FileList.ItemsSource = group.Files;
        UpdateFileListHeader();

        var operations = group.Operations.Where(item => item.IsAvailable).ToList();
        OperationCombo.ItemsSource = operations;

        if (operations.Count == 0)
        {
            // Sem motor local não se promete conversão: o cartão fica só mostrando o que entrou.
            ModePanel.Visibility = Visibility.Collapsed;
            OperationPanel.Visibility = Visibility.Collapsed;
            OptionsView.Visibility = Visibility.Collapsed;
            FileListExpander.IsExpanded = true;
            GroupWarningBar.Message = group.Operations.Count == 0
                ? "Nenhuma conversão disponível para este tipo de arquivo."
                : $"As conversões deste tipo entram em um marco futuro ({group.Operations[0].BadgeText}).";
            GroupWarningBar.IsOpen = true;
            loading = false;
            return;
        }

        OperationCombo.SelectedIndex = 0;
        groupOperation = operations[0].Descriptor;
        ShowGroupOptions(null);
        groupOptions = null;
        PopulatePresets();

        loading = false;
    }

    /// <summary>Novos arquivos entraram no lote e caíram neste grupo.</summary>
    internal void Refresh(BatchGroup group)
    {
        var editado = editing?.Path;
        model.Update(group);
        TitleText.Text = model.Title;
        DetailText.Text = model.Detail;
        UpdateFileListHeader();

        if (!model.Individual)
        {
            return;
        }

        // Recompor a lista limpa a seleção; sem isto os campos ficariam editando um
        // arquivo que já saiu da coleção.
        foreach (var file in model.Files.Where(file => file.Options is null))
        {
            file.Operation = groupOperation;
            file.Options = groupOptions;
        }

        var alvo = model.Files.FirstOrDefault(file =>
            string.Equals(file.Path, editado, StringComparison.OrdinalIgnoreCase)) ?? model.Files.FirstOrDefault();

        loading = true;
        FileList.SelectedItem = alvo;
        loading = false;
        Edit(alvo);
    }

    private void UpdateFileListHeader() =>
        FileListHeaderText.Text = model.Files.Count == 1
            ? "Ver o arquivo"
            : $"Ver os {model.Files.Count} arquivos";

    // ==================== Modo ====================

    private void OnModeChanged(object sender, RoutedEventArgs e)
    {
        if (loading || model is null)
        {
            return;
        }

        var individual = OneByOneRadio.IsChecked == true;
        if (individual == model.Individual)
        {
            return;
        }

        if (!TryFlush(out var erro))
        {
            // O modo não muda com um campo inválido na tela — e o botão precisa voltar
            // para onde estava, senão ele passa a mostrar um modo que não está valendo.
            ShowWarning(erro);
            loading = true;
            SameForAllRadio.IsChecked = !model.Individual;
            OneByOneRadio.IsChecked = model.Individual;
            loading = false;
            return;
        }

        model.Individual = individual;
        FilePickerPanel.Visibility = individual ? Visibility.Visible : Visibility.Collapsed;
        FileListExpander.Visibility = individual ? Visibility.Collapsed : Visibility.Visible;

        if (individual)
        {
            // Quem já ajustou o grupo não recomeça do zero: cada arquivo herda a escolha
            // do grupo e só depois muda o que quiser.
            foreach (var file in model.Files.Where(file => file.Options is null))
            {
                file.Operation = groupOperation;
                file.Options = groupOptions;
            }

            loading = true;
            FileList.SelectedIndex = 0;
            loading = false;
            Edit(model.Files.FirstOrDefault());
            return;
        }

        editing = null;
        loading = true;
        FileList.SelectedItem = null;
        loading = false;
        ShowOptionsFor(groupOperation, groupOptions);
    }

    private void OnFileChanged(object sender, SelectionChangedEventArgs e)
    {
        if (loading || FileList.SelectedItem is not BatchFileViewModel selecionado || ReferenceEquals(selecionado, editing))
        {
            return;
        }

        if (!TryFlush(out var erro))
        {
            ShowWarning(erro);
            loading = true;
            FileList.SelectedItem = editing;
            loading = false;
            return;
        }

        Edit(selecionado);
    }

    private void Edit(BatchFileViewModel? file)
    {
        editing = file;
        if (file is null)
        {
            return;
        }

        var operation = file.Operation ?? groupOperation;
        ShowOptionsFor(operation, file.Options, file);
    }

    /// <summary>Põe na tela os campos de uma operação, com o arquivo certo por trás.</summary>
    private void ShowOptionsFor(OperationDescriptor? operation, JobOptions? options, BatchFileViewModel? file = null)
    {
        if (operation is null)
        {
            return;
        }

        loading = true;
        SelectOperation(operation);

        if (file is null)
        {
            ShowGroupOptions(options, operation);
        }
        else
        {
            OptionsView.ShowFor(operation, file.File, options);
        }

        PopulatePresets();
        loading = false;
        GroupWarningBar.IsOpen = false;
    }

    /// <summary>
    /// Os campos valendo para o grupo inteiro. O primeiro arquivo entra só como amostra
    /// para os padrões sugeridos — "todos os MP3" continua sugerindo o mesmo formato de
    /// saída que um MP3 sozinho sugeriria.
    /// </summary>
    private void ShowGroupOptions(JobOptions? options, OperationDescriptor? operation = null)
    {
        var alvo = operation ?? groupOperation;
        if (alvo is null)
        {
            return;
        }

        OptionsView.ShowFor(alvo, model.Files.FirstOrDefault()?.File, options, allowTrackChoice: false);
    }

    private void SelectOperation(OperationDescriptor operation)
    {
        if (OperationCombo.ItemsSource is not IEnumerable<OperationItemViewModel> items)
        {
            return;
        }

        var lista = items.ToList();
        var indice = lista.FindIndex(item => item.Id == operation.Id);
        if (indice >= 0)
        {
            OperationCombo.SelectedIndex = indice;
        }
    }

    // ==================== Conversão e presets ====================

    private void OnOperationChanged(object sender, SelectionChangedEventArgs e)
    {
        if (loading || OperationCombo.SelectedItem is not OperationItemViewModel item)
        {
            return;
        }

        // Trocar de conversão zera as opções: os campos de MP3 não valem para SRT.
        if (model.Individual && editing is not null)
        {
            editing.Operation = item.Descriptor;
            editing.Options = null;
            OptionsView.ShowFor(item.Descriptor, editing.File);
            editing.Options = SafeBuild();
        }
        else
        {
            groupOperation = item.Descriptor;
            groupOptions = null;
            ShowGroupOptions(null, item.Descriptor);
        }

        PopulatePresets();
    }

    private void PopulatePresets()
    {
        var operation = model.Individual && editing is not null
            ? editing.Operation ?? groupOperation
            : groupOperation;

        var presets = operation is null
            ? []
            : PresetCatalog.For(model.Group.Kind)
                .Where(preset => preset.Options.Operation == operation.Id)
                .Select(preset => new PresetItemViewModel(preset))
                .ToList();

        PresetCombo.ItemsSource = presets;
        PresetCombo.SelectedItem = null;
        PresetPanel.Visibility = presets.Count > 0 ? Visibility.Visible : Visibility.Collapsed;
    }

    private void OnPresetChanged(object sender, SelectionChangedEventArgs e)
    {
        if (loading || PresetCombo.SelectedItem is not PresetItemViewModel item)
        {
            return;
        }

        OptionsView.Apply(item.Preset.Options);
        Store(item.Preset.Options);
    }

    // ==================== Leitura dos campos ====================

    /// <summary>
    /// Guarda o que está na tela antes de mudar de arquivo ou de modo. Um tempo de corte
    /// digitado errado não pode ser engolido em silêncio nem apagar a escolha anterior.
    /// </summary>
    private bool TryFlush(out string? error)
    {
        error = null;
        if (OptionsView.Operation is null || OptionsView.Visibility != Visibility.Visible)
        {
            return true;
        }

        try
        {
            Store(OptionsView.Build());
            return true;
        }
        catch (ConversionException exception)
        {
            error = exception.Message;
            return false;
        }
    }

    private JobOptions? SafeBuild()
    {
        try
        {
            return OptionsView.Build();
        }
        catch (ConversionException)
        {
            return null;
        }
    }

    private void Store(JobOptions options)
    {
        if (model.Individual && editing is not null)
        {
            editing.Operation = OptionsView.Operation;
            editing.Options = options;
            return;
        }

        groupOperation = OptionsView.Operation ?? groupOperation;
        groupOptions = options;
    }

    private void ShowWarning(string? message)
    {
        GroupWarningBar.Message = message ?? "Revise as opções deste grupo.";
        GroupWarningBar.IsOpen = true;
    }

    // ==================== Saída ====================

    /// <summary>
    /// Os jobs deste grupo, já validados. Lança <see cref="ConversionException"/> com o
    /// nome do arquivo problemático — no lote, "opção inválida" sem dizer onde não ajuda.
    /// </summary>
    internal IReadOnlyList<ConversionJob> BuildJobs(string outputDirectory)
    {
        if (!model.HasWork || model.Files.Count == 0)
        {
            return [];
        }

        if (!TryFlush(out var erro))
        {
            ShowWarning(erro);
            throw new ConversionException("invalid_options", $"{model.Title}: {erro}");
        }

        GroupWarningBar.IsOpen = false;
        var jobs = new List<ConversionJob>(model.Files.Count);

        foreach (var file in model.Files)
        {
            var operation = (model.Individual ? file.Operation : groupOperation)
                ?? model.Group.DefaultOperation
                ?? throw new ConversionException("operation_not_available", $"{model.Title}: escolha uma conversão.");

            var options = (model.Individual ? file.Options : groupOptions)
                ?? operation.DefaultOptions(file.File);

            try
            {
                options.Validate();
            }
            catch (ConversionException exception)
            {
                ShowWarning($"{file.Name}: {exception.Message}");
                throw new ConversionException(exception.Code, $"{file.Name}: {exception.Message}");
            }

            jobs.Add(new ConversionJob
            {
                Options = options,
                InputPath = file.File.Path,
                Input = file.File,
                OutputDirectory = outputDirectory,
                OutputBaseName = SafeFileName.FromPath(file.File.Path)
            });
        }

        return jobs;
    }
}
