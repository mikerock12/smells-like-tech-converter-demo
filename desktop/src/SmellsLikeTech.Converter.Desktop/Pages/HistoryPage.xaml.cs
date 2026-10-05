using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using SmellsLikeTech.Converter.Core.Media;
using SmellsLikeTech.Converter.Desktop.ViewModels;

namespace SmellsLikeTech.Converter.Desktop.Pages;

public sealed partial class HistoryPage : Page
{
    public HistoryPage()
    {
        InitializeComponent();
        Loaded += async (_, _) => await LoadAsync();
    }

    private async Task LoadAsync()
    {
        var entries = await App.Services.History.RecentAsync(200, CancellationToken.None);
        HistoryList.ItemsSource = entries.Select(entry => new HistoryItemViewModel(entry)).ToList();

        var completed = entries.Count(entry => entry.Status == Core.Jobs.JobStatus.Completed);
        var processed = entries.Sum(entry => entry.InputBytes);
        SummaryText.Text = entries.Count == 0
            ? "Nenhuma conversão registrada ainda."
            : $"{entries.Count} registro(s) · {completed} concluída(s) · {MediaInfo.FormatBytes(processed)} processados";
    }

    private async void OnRefresh(object sender, RoutedEventArgs e) => await LoadAsync();

    private async void OnClear(object sender, RoutedEventArgs e)
    {
        var dialog = new ContentDialog
        {
            XamlRoot = XamlRoot,
            Title = "Limpar histórico",
            Content = "Os registros de conversões anteriores serão apagados. Os arquivos convertidos não são afetados.",
            PrimaryButtonText = "Limpar",
            CloseButtonText = "Cancelar",
            DefaultButton = ContentDialogButton.Close
        };

        if (await dialog.ShowAsync() == ContentDialogResult.Primary)
        {
            await App.Services.History.ClearAsync(CancellationToken.None);
            await LoadAsync();
        }
    }

    private void OnOpenResult(object sender, RoutedEventArgs e)
    {
        if (sender is Button { Tag: string path })
        {
            ShellHelper.RevealInExplorer(path);
        }
    }
}
