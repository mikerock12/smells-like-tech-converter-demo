using Microsoft.UI;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media.Imaging;
using SmellsLikeTech.Converter.Core.Media;
using SmellsLikeTech.Converter.Desktop.Pages;
using SmellsLikeTech.Converter.Composition;
using SmellsLikeTech.Converter.Desktop.Services;
using Windows.UI;

namespace SmellsLikeTech.Converter.Desktop;

public sealed partial class MainWindow : Window
{
    private DispatcherTimer? statusTimer;

    public MainWindow()
    {
        InitializeComponent();

        ExtendsContentIntoTitleBar = true;
        SetTitleBar(AppTitleBar);
        StyleCaptionButtons();

        var icon = BrandInfo.AssetPath("icon.ico");
        if (File.Exists(icon))
        {
            AppWindow.SetIcon(icon);
        }

        TitleIcon.Source = new BitmapImage(BrandInfo.AssetUri("icon-128.png"));
        TitleWordmark.Source = new BitmapImage(BrandInfo.AssetUri("title.png"));

        // Maximizada na primeira abertura; depois, como o usuário deixou. O tamanho de
        // restauração sai da área útil do monitor, não de uma medida fixa.
        WindowPlacement.Apply(AppWindow, App.Services.Settings);

        RootGrid.Loaded += OnLoaded;
        Closed += OnClosed;
    }

    /// <summary>
    /// Com a barra de título própria, os botões minimizar/maximizar/fechar continuam
    /// sendo desenhados pelo sistema: sem estas cores eles ficam invisíveis no fundo escuro.
    /// </summary>
    private void StyleCaptionButtons()
    {
        var titleBar = AppWindow.TitleBar;
        titleBar.ButtonBackgroundColor = Colors.Transparent;
        titleBar.ButtonInactiveBackgroundColor = Colors.Transparent;
        titleBar.ButtonForegroundColor = ColorHelper.FromArgb(0xFF, 0xF3, 0xF3, 0xF5);
        titleBar.ButtonInactiveForegroundColor = ColorHelper.FromArgb(0xFF, 0x8A, 0x8A, 0x95);
        titleBar.ButtonHoverBackgroundColor = ColorHelper.FromArgb(0xFF, 0x24, 0x24, 0x2E);
        titleBar.ButtonHoverForegroundColor = ColorHelper.FromArgb(0xFF, 0xFF, 0xC1, 0x07);
        titleBar.ButtonPressedBackgroundColor = ColorHelper.FromArgb(0xFF, 0x33, 0x33, 0x3F);
        titleBar.ButtonPressedForegroundColor = ColorHelper.FromArgb(0xFF, 0xFF, 0x7A, 0x18);
    }

    private void OnLoaded(object sender, RoutedEventArgs e)
    {
        App.CreateQueueViewModel(DispatcherQueue);
        ContentFrame.Navigate(typeof(HomePage));

        App.QueueViewModel.PropertyChanged += (_, _) => UpdateStatus();
        statusTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(5) };
        statusTimer.Tick += (_, _) => UpdateStatus();
        statusTimer.Start();
        UpdateStatus();
    }

    private void UpdateStatus()
    {
        if (!App.IsReady)
        {
            return;
        }

        QueueSummaryText.Text = App.QueueViewModel.SummaryText;
        var free = App.Services.Paths.FreeDiskBytes();
        DiskSummaryText.Text = free > 0
            ? $"{MediaInfo.FormatBytes(free)} livres em {Path.GetPathRoot(App.Services.Paths.Root)}"
            : App.Services.Paths.Root;
    }

    private void OnNavigationSelectionChanged(NavigationView sender, NavigationViewSelectionChangedEventArgs args)
    {
        if (!App.IsReady || args.SelectedItem is not NavigationViewItem item)
        {
            return;
        }

        var page = (item.Tag as string) switch
        {
            "batch" => typeof(BatchPage),
            "queue" => typeof(QueuePage),
            "speech" => typeof(SpeechPage),
            "history" => typeof(HistoryPage),
            "settings" => typeof(SettingsPage),
            _ => typeof(HomePage)
        };

        if (ContentFrame.CurrentSourcePageType != page)
        {
            ContentFrame.Navigate(page);
        }
    }

    /// <summary>Leva o usuário para a fila depois de enfileirar um job.</summary>
    public void ShowQueue() => Select("queue");

    /// <summary>
    /// Abre a tela de lote já com os arquivos escolhidos. A navegação vem antes da
    /// seleção no menu: assim o item selecionado não dispara uma segunda navegação,
    /// que chegaria sem os caminhos e mostraria a tela vazia.
    /// </summary>
    public void ShowBatch(IReadOnlyList<string> paths)
    {
        ContentFrame.Navigate(typeof(BatchPage), paths);
        Select("batch");
    }

    private void Select(string tag)
    {
        foreach (var item in Navigation.MenuItems.OfType<NavigationViewItem>())
        {
            if (item.Tag as string == tag)
            {
                Navigation.SelectedItem = item;
                return;
            }
        }
    }

    private async void OnClosed(object sender, WindowEventArgs args)
    {
        statusTimer?.Stop();

        // Guarda como a janela estava para a próxima abertura.
        if (App.IsReady)
        {
            try
            {
                var settings = WindowPlacement.Capture(AppWindow, App.Services.Settings);
                await App.Services.SettingsStore.SaveAsync(settings, CancellationToken.None);
            }
            catch (Exception exception) when (exception is Microsoft.Data.Sqlite.SqliteException or IOException)
            {
                // Não conseguir guardar a posição da janela não pode impedir o fechamento.
            }
        }

        await App.ShutdownAsync();
    }
}
