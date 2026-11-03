using System.Text.RegularExpressions;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media.Imaging;
using SmellsLikeTech.Converter.Composition;
using SmellsLikeTech.Converter.Core.Jobs;
using SmellsLikeTech.Converter.Core.Studio;
using SmellsLikeTech.Converter.Desktop.Services;
using SmellsLikeTech.Converter.Desktop.ViewModels;
using Windows.Media.Core;
using Windows.Media.Playback;

namespace SmellsLikeTech.Converter.Desktop.Pages;

/// <summary>
/// O fim do fluxo do estúdio: o X oferece salvar/sair, sair sem salvar e começar novo; o
/// botão Salvar abre projeto ou exportação; quando a fila termina, o resultado abre num
/// player aqui mesmo, com a pasta e o feedback obrigatório por e-mail (converter@).
/// </summary>
public sealed partial class StudioPage
{
    private const string Correio = "converter@smellsliketech.com.br";

    private string? lastResult;

    private static bool EmailValido(string texto) =>
        Regex.IsMatch(texto.Trim(), @"^[a-z0-9._%+-]+@[a-z0-9.-]+\.[a-z]{2,}$", RegexOptions.IgnoreCase);

    // ==================== sair pelo X ====================

    private async void OnCloseProject(object sender, RoutedEventArgs e)
    {
        if (project is null)
        {
            return;
        }

        var painel = new StackPanel { Spacing = 8 };
        painel.Children.Add(Muted("Salve uma cópia portátil antes de sair, descarte o projeto atual ou comece outro sem salvar."));
        var dialogo = new ContentDialog
        {
            Title = "O que fazer com o projeto?",
            Content = painel,
            CloseButtonText = "Cancelar",
            XamlRoot = XamlRoot
        };
        var salvarESair = Action("Salvar projeto e sair");
        salvarESair.IsEnabled = HasProjectContent;
        salvarESair.Click += async (_, _) =>
        {
            dialogo.Hide();
            if (await SaveProjectWithMediaAsync())
            {
                DiscardProject();
                (App.MainWindow as MainWindow)?.Open("start");
            }
        };
        var sair = new Button { Content = "Sair sem salvar", HorizontalAlignment = HorizontalAlignment.Stretch };
        sair.Click += (_, _) =>
        {
            dialogo.Hide();
            DiscardProject();
            (App.MainWindow as MainWindow)?.Open("start");
        };
        var novo = new Button { Content = "Começar um novo sem salvar", HorizontalAlignment = HorizontalAlignment.Stretch };
        novo.Click += (_, _) =>
        {
            dialogo.Hide();
            DiscardProject();
        };
        painel.Children.Add(salvarESair);
        painel.Children.Add(sair);
        painel.Children.Add(novo);
        await dialogo.ShowAsync();
    }

    /// <summary>Descarta o projeto atual e volta ao assistente de destino.</summary>
    private void DiscardProject()
    {
        ReleasePlayer();
        ReleasePreviewPlayer();
        project = null;
        lastResult = null;
        ForgetHistory();
        infos.Clear();
        choosingOutput = false;
        WizardPanel.Tag = null;
        try
        {
            File.Delete(ProjectFile);
        }
        catch (IOException)
        {
        }

        PreviewImage.Source = null;
        ShowCurrentStep();
    }

    // ==================== salvar ====================

    private async void OnSaveProjectMenu(object sender, RoutedEventArgs e)
    {
        if (!HasProjectContent)
        {
            ShowStatus("Adicione um vídeo, foto, texto ou áudio antes de salvar o projeto.", InfoBarSeverity.Informational);
            return;
        }

        await SaveProjectWithMediaAsync();
    }

    /// <summary>
    /// Sair do estúdio (outra tela do menu ou fechar o aplicativo) sempre pergunta: salvar o
    /// projeto com as mídias, sair sem salvar ou continuar editando. Devolve se pode sair.
    /// </summary>
    public async Task<bool> ConfirmLeaveAsync()
    {
        if (!HasProjectContent)
        {
            return true;
        }

        var dialogo = new ContentDialog
        {
            Title = "Salvar o projeto antes de sair?",
            Content = Muted("O projeto é salvo com as mídias num arquivo .sltstudio, que abre de novo no Windows, no site e no Android. Sem salvar, ele é descartado. Uma exportação em andamento continua na fila."),
            PrimaryButtonText = "Salvar e sair",
            SecondaryButtonText = "Sair sem salvar",
            CloseButtonText = "Continuar editando",
            DefaultButton = ContentDialogButton.Primary,
            XamlRoot = XamlRoot
        };
        switch (await dialogo.ShowAsync())
        {
            case ContentDialogResult.Primary:
                if (!await SaveProjectWithMediaAsync())
                {
                    return false;
                }

                DiscardProject();
                return true;
            case ContentDialogResult.Secondary:
                DiscardProject();
                return true;
            default:
                return false;
        }
    }

    private void OnExportMenu(object sender, RoutedEventArgs e) => EditorPivot.SelectedIndex = 4;

    /// <summary>Salva o .sltstudio com as mídias numa pasta escolhida; reabre no site, Windows e Android.</summary>
    private async Task<bool> SaveProjectWithMediaAsync()
    {
        var original = project;
        if (original is null)
        {
            return false;
        }

        var folder = FileDialogs.PickOutputFolder(App.MainWindow);
        if (folder is null)
        {
            return false;
        }

        var file = Path.Combine(folder, $"projeto-{DateTime.Now:yyyyMMdd-HHmmss}-{Guid.NewGuid().ToString("N")[..6]}.sltstudio");
        var created = false;
        try
        {
            await Task.Run(() =>
            {
                using var stream = new FileStream(file, FileMode.CreateNew, FileAccess.Write);
                created = true;
                StudioArchive.Save(original, stream);
            });
            ShowStatus($"Projeto salvo com as mídias em {file}", InfoBarSeverity.Success);
            return true;
        }
        catch (Exception exception)
        {
            if (created)
            {
                File.Delete(file);
            }

            ShowStatus(exception.Message, InfoBarSeverity.Error);
            return false;
        }
    }

    // ==================== resultado da fila ====================

    /// <summary>Espera o job do estúdio sair da fila e, ao concluir, abre o resultado no player.</summary>
    private async void WatchResult(Guid id)
    {
        JobItemViewModel? item = null;
        for (var tentativa = 0; tentativa < 40 && item is null; tentativa++)
        {
            item = App.QueueViewModel.Jobs.FirstOrDefault(j => j.Id == id);
            if (item is null)
            {
                await Task.Delay(250);
            }
        }

        if (item is null)
        {
            return;
        }

        var avisado = false;
        item.PropertyChanged += (_, _) =>
        {
            if (avisado)
            {
                return;
            }

            UpdateExportProgress(item.Snapshot);
            if (item.Snapshot.Status == JobStatus.Completed && item.FirstOutput is { } saida)
            {
                avisado = true;
                HideExportProgress(id);
                SaveSpeed();
                ShowFinalResult(saida);
            }
            else if (item.Snapshot.Status == JobStatus.Failed)
            {
                avisado = true;
                HideExportProgress(id);
                ShowStatus($"A exportação falhou: {item.Snapshot.ErrorMessage ?? item.Snapshot.ErrorCode}", InfoBarSeverity.Error);
            }
            else if (item.Snapshot.Status == JobStatus.Cancelled)
            {
                avisado = true;
                HideExportProgress(id);
                ShowStatus("Exportação cancelada.", InfoBarSeverity.Informational);
            }
        };
    }

    // ==================== andamento da exportação ====================

    private Guid? exportJob;
    private TimeSpan? exportEstimate;
    private DateTime exportStarted;

    private void ShowExportProgress(Guid id, TimeSpan? estimativa)
    {
        exportJob = id;
        exportEstimate = estimativa;
        exportStarted = DateTime.UtcNow;
        ExportProgressBar.Value = 0;
        ExportProgressBar.IsIndeterminate = true;
        ExportProgressText.Text = estimativa is { } e
            ? $"Exportando na qualidade {StudioQuality.Find(exportLevel).Title.ToLowerInvariant()}… tempo estimado: {StudioEstimate.Readable(e)}"
            : "Exportando…";
        ExportProgressPanel.Visibility = Visibility.Visible;
    }

    private void UpdateExportProgress(JobSnapshot snapshot)
    {
        if (exportJob != snapshot.Id)
        {
            return;
        }

        if (snapshot.Status == JobStatus.Queued)
        {
            ExportProgressText.Text = "Exportação aguardando a vez na fila…";
            return;
        }

        var etapa = snapshot.Stage == JobStage.RemovingBackground ? "Removendo o fundo" : "Exportando";
        if (snapshot.Progress is not { } fracao)
        {
            ExportProgressBar.IsIndeterminate = true;
            ExportProgressText.Text = $"{etapa}…";
            return;
        }

        ExportProgressBar.IsIndeterminate = false;
        ExportProgressBar.Value = Math.Round(fracao * 100);
        var resto = snapshot.Eta ?? (exportEstimate is { } e ? e - (DateTime.UtcNow - exportStarted) : null);
        var falta = resto is { TotalSeconds: > 1 } r ? $" · faltam {StudioEstimate.Readable(r)}" : "";
        ExportProgressText.Text = $"{etapa}… {Math.Round(fracao * 100)}%{falta}";
    }

    private void HideExportProgress(Guid id)
    {
        if (exportJob == id)
        {
            exportJob = null;
            ExportProgressPanel.Visibility = Visibility.Collapsed;
        }
    }

    private void OnExportShowQueue(object sender, RoutedEventArgs e) => (App.MainWindow as MainWindow)?.ShowQueue();

    private void OnExportCancel(object sender, RoutedEventArgs e)
    {
        if (exportJob is { } id)
        {
            App.Services.Queue.Cancel(id);
        }
    }

    /// <summary>O resultado toca aqui mesmo; dá para abrir a pasta ou mandar o feedback obrigatório.</summary>
    private void ShowFinalResult(string caminho)
    {
        var raiz = App.MainWindow?.Content?.XamlRoot;
        if (raiz is null)
        {
            return;
        }

        lastResult = caminho;
        var painel = new StackPanel { Spacing = 10, MinWidth = 420 };
        painel.Children.Add(Muted(Path.GetFileName(caminho)));

        MediaPlayer? player = null;
        var extensao = Path.GetExtension(caminho).ToLowerInvariant();
        if (extensao is ".png" or ".gif" or ".jpg" or ".jpeg" or ".webp")
        {
            painel.Children.Add(new Image { Source = new BitmapImage(new Uri(caminho)), MaxHeight = 420, Stretch = Microsoft.UI.Xaml.Media.Stretch.Uniform });
        }
        else if (extensao is not ".txt")
        {
            player = new MediaPlayer();
            var elemento = new MediaPlayerElement { AreTransportControlsEnabled = true, AutoPlay = true, MaxHeight = 420 };
            elemento.SetMediaPlayer(player);
            player.Source = MediaSource.CreateFromUri(new Uri(caminho));
            painel.Children.Add(elemento);
        }

        var abrir = new Button { Content = "Abrir a pasta", HorizontalAlignment = HorizontalAlignment.Stretch };
        abrir.Click += (_, _) => ShellHelper.RevealInExplorer(caminho);
        painel.Children.Add(abrir);

        painel.Children.Add(new TextBlock { Text = "Conte como foi", FontWeight = Microsoft.UI.Text.FontWeights.SemiBold });
        painel.Children.Add(Muted("Nome, e-mail e feedback são obrigatórios. A mensagem abre no seu app de e-mail."));
        var nome = new TextBox { Header = "Seu nome" };
        var email = new TextBox { Header = "Seu e-mail" };
        var mensagem = new TextBox { Header = "Seu feedback", AcceptsReturn = true, TextWrapping = TextWrapping.Wrap, MinHeight = 70 };
        var enviar = Action("Enviar feedback");
        enviar.IsEnabled = false;
        void Validar() => enviar.IsEnabled = !string.IsNullOrWhiteSpace(nome.Text) && EmailValido(email.Text) && !string.IsNullOrWhiteSpace(mensagem.Text);
        nome.TextChanged += (_, _) => Validar();
        email.TextChanged += (_, _) => Validar();
        mensagem.TextChanged += (_, _) => Validar();
        enviar.Click += async (_, _) =>
        {
            var assunto = Uri.EscapeDataString($"Feedback do estúdio — {nome.Text.Trim()}");
            var corpo = Uri.EscapeDataString($"Nome: {nome.Text.Trim()}\nE-mail: {email.Text.Trim()}\n\n{mensagem.Text.Trim()}");
            await Windows.System.Launcher.LaunchUriAsync(new Uri($"mailto:{Correio}?subject={assunto}&body={corpo}"));
        };
        painel.Children.Add(nome);
        painel.Children.Add(email);
        painel.Children.Add(mensagem);
        painel.Children.Add(enviar);

        var dialogo = new ContentDialog
        {
            Title = "Pronto!",
            Content = new ScrollViewer { Content = painel, MaxHeight = 640, VerticalScrollBarVisibility = ScrollBarVisibility.Auto },
            CloseButtonText = "Fechar",
            XamlRoot = raiz
        };
        dialogo.Closed += (_, _) => player?.Dispose();
        _ = dialogo.ShowAsync();
    }
}
