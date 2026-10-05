using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using SmellsLikeTech.Converter.Desktop.ViewModels;

namespace SmellsLikeTech.Converter.Desktop.Pages;

public sealed partial class QueuePage : Page
{
    public QueuePage()
    {
        InitializeComponent();
        Loaded += OnLoaded;
        Unloaded += OnUnloaded;
    }

    public QueueViewModel Queue => App.QueueViewModel;

    private void OnLoaded(object sender, RoutedEventArgs e)
    {
        Queue.PropertyChanged += OnQueueChanged;
        UpdateHeader();
    }

    private void OnUnloaded(object sender, RoutedEventArgs e) => Queue.PropertyChanged -= OnQueueChanged;

    private void OnQueueChanged(object? sender, System.ComponentModel.PropertyChangedEventArgs e) => UpdateHeader();

    private void UpdateHeader()
    {
        SummaryText.Text = Queue.SummaryText;
        PauseButton.Content = Queue.IsPaused ? "Retomar fila" : "Pausar fila";
    }

    private void OnTogglePause(object sender, RoutedEventArgs e)
    {
        Queue.TogglePause();
        UpdateHeader();
    }

    private void OnClearFinished(object sender, RoutedEventArgs e) => Queue.ClearFinished();

    private void OnCancelJob(object sender, RoutedEventArgs e)
    {
        if (sender is Button { Tag: Guid id })
        {
            Queue.Cancel(id);
        }
    }

    private void OnMoveUp(object sender, RoutedEventArgs e)
    {
        if (sender is Button { Tag: Guid id })
        {
            Queue.Move(id, -1);
        }
    }

    private void OnMoveDown(object sender, RoutedEventArgs e)
    {
        if (sender is Button { Tag: Guid id })
        {
            Queue.Move(id, 1);
        }
    }

    private void OnOpenResult(object sender, RoutedEventArgs e)
    {
        // O caminho é lido do job no momento do clique. Antes vinha por binding e ficava
        // nulo: quando o item entra na lista o job ainda não terminou, e o botão só
        // aparece depois - o valor antigo continuava valendo e o clique não fazia nada.
        if (sender is not Button { Tag: Guid id })
        {
            return;
        }

        var job = Queue.Jobs.FirstOrDefault(item => item.Id == id);
        if (job?.FirstOutput is { } caminho)
        {
            ShellHelper.RevealInExplorer(caminho);
        }
    }

    private void OnOpenOutputFolder(object sender, RoutedEventArgs e) =>
        ShellHelper.OpenFolder(App.Services.OutputDirectory);
}
