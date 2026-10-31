using System.Globalization;
using Microsoft.UI.Text;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Imaging;
using SmellsLikeTech.Converter.Core;
using SmellsLikeTech.Converter.Core.Jobs;
using SmellsLikeTech.Converter.Core.Media;
using SmellsLikeTech.Converter.Core.Options;
using SmellsLikeTech.Converter.Core.Studio;
using SmellsLikeTech.Converter.Desktop.Controls;
using SmellsLikeTech.Converter.Desktop.Services;
using Windows.Media.Core;
using Windows.Media.Playback;

namespace SmellsLikeTech.Converter.Desktop.Pages;

/// <summary>
/// O estudio no aplicativo: primeiro o destino (rede e formato), depois a saida, e entao o
/// editor com clipes em sequencia, textos, musica e a exportacao pela fila. A previa e um
/// quadro montado pelo proprio motor do estudio (o mesmo da exportacao), em baixa resolucao.
/// O projeto fica salvo na pasta do aplicativo e volta quando a tela abre de novo.
/// </summary>
public sealed partial class StudioPage : Page
{
    private static readonly CultureInfo PtBr = CultureInfo.GetCultureInfo("pt-BR");
    private static readonly string[] Colors = ["#FFFFFF", "#000000", "#FFD54A", "#FF7A18", "#E53935", "#43A047", "#1E88E5", "#8E24AA"];

    private StudioProject? project;
    private readonly Dictionary<string, MediaInfo> infos = new(StringComparer.OrdinalIgnoreCase);
    private string? openId;
    private bool choosingOutput;
    private MediaPlayer? trimPlayer;
    private TrimFullscreenWindow? fullscreen;
    private TrimPlayer? trimControl;
    private bool releasing;
    private CancellationTokenSource? previewCancellation;
    private DispatcherQueueTimerWrapper? previewTimer;

    public StudioPage()
    {
        InitializeComponent();
        Unloaded += (_, _) => ReleasePlayer();
        Loaded += async (_, _) => await LoadAsync();
    }

    private static string ProjectFile => Path.Combine(App.Services.Paths.Root, "estudio", "projeto.json");

    private double Total => project is null ? 0 : StudioRules.TotalDuration(project);

    // ==================== abrir e salvar ====================

    private async Task LoadAsync()
    {
        previewTimer = new DispatcherQueueTimerWrapper(DispatcherQueue, TimeSpan.FromMilliseconds(300), () => _ = RenderPreviewAsync());
        try
        {
            if (File.Exists(ProjectFile))
            {
                project = StudioProject.Parse(await File.ReadAllTextAsync(ProjectFile));
                foreach (var file in Inputs().Where(File.Exists))
                {
                    infos[file] = await App.Services.Inspector.InspectAsync(file, CancellationToken.None);
                }
            }
        }
        catch (Exception exception) when (exception is ConversionException or IOException or System.Text.Json.JsonException)
        {
            project = null;
        }

        ShowCurrentStep();
    }

    private void Save()
    {
        if (project is null)
        {
            return;
        }

        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(ProjectFile)!);
            File.WriteAllText(ProjectFile, project.ToJson());
        }
        catch (IOException)
        {
            // Sem salvar, o projeto continua na tela; perde-se só ao fechar o aplicativo.
        }
    }

    /// <summary>Muda o projeto sem redesenhar os paineis (sliders em uso nao perdem o arraste).</summary>
    private void Change(Func<StudioProject, StudioProject> mudanca)
    {
        if (project is null)
        {
            return;
        }

        var antes = project;
        project = mudanca(project);
        if (!ReferenceEquals(antes, project))
        {
            Remember(antes);
        }

        Save();
        TimeSlider.Maximum = Math.Max(0.1, Total);
        TotalText.Text = Clock(Total);
        RequestPreview();
    }

    private void ChangeAndRefresh(Func<StudioProject, StudioProject> mudanca)
    {
        Change(mudanca);
        RefreshPanels();
    }

    private IReadOnlyList<string> Inputs() => project is null
        ? []
        : project.Clips.Select(c => c.File).Concat(project.Audios.Select(a => a.File)).Concat(project.Layers.Select(c => c.File)).Distinct(StringComparer.OrdinalIgnoreCase).ToList();

    // ==================== assistente ====================

    private void ShowCurrentStep()
    {
        var editing = project is not null && !choosingOutput && WizardPanel.Tag as string != "destino";
        WizardView.Visibility = editing ? Visibility.Collapsed : Visibility.Visible;
        EditorView.Visibility = editing ? Visibility.Visible : Visibility.Collapsed;
        DestinationButton.Visibility = project is null ? Visibility.Collapsed : Visibility.Visible;
        NewProjectButton.Visibility = project is null ? Visibility.Collapsed : Visibility.Visible;
        UpdateUndoButtons();
        if (project is not null)
        {
            var preset = StudioRules.FindPreset(project.Destination);
            DestinationTitle.Text = preset is null || preset.Id == "personalizado" ? "Personalizado" : preset.Title;
            DestinationDetail.Text = $"{project.Width}×{project.Height} · trocar o destino";
        }

        if (!editing)
        {
            if (choosingOutput)
            {
                BuildOutputChoice();
            }
            else
            {
                BuildDestinationChoice();
            }

            return;
        }

        TimeSlider.Maximum = Math.Max(0.1, Total);
        TotalText.Text = Clock(Total);
        TimeText.Text = Clock(TimeSlider.Value);
        RefreshPanels();
        RequestPreview();
    }

    private void BuildDestinationChoice()
    {
        WizardPanel.Children.Clear();
        WizardPanel.Children.Add(Title("Para onde é?"));
        WizardPanel.Children.Add(Muted("Escolha a rede e o formato: o estúdio ajusta o tamanho do quadro e avisa se passar do tempo que ela costuma aceitar. Nada é enviado para a internet."));
        foreach (var rede in StudioRules.Presets.Where(p => p.Id != "personalizado").GroupBy(p => p.Network))
        {
            WizardPanel.Children.Add(new TextBlock { Text = rede.Key.ToUpperInvariant(), Style = (Style)Application.Current.Resources["SectionTitleStyle"], Margin = new Thickness(0, 8, 0, 0) });
            var linha = new VariableSizedWrapGrid { Orientation = Orientation.Horizontal, ItemWidth = 250, ItemHeight = 70 };
            foreach (var preset in rede)
            {
                var limite = preset.MaxDuration is { } max ? $" · até {StudioRules.ReadableDuration(max)}" : "";
                var botao = Card(preset.Name, $"{preset.Width}×{preset.Height}{limite}", project?.Destination == preset.Id, Shape(preset.Width, preset.Height));
                botao.Click += (_, _) => ChooseDestination(preset.Id, null, null);
                linha.Children.Add(botao);
            }

            WizardPanel.Children.Add(linha);
        }

        WizardPanel.Children.Add(new TextBlock { Text = "TAMANHO PERSONALIZADO", Style = (Style)Application.Current.Resources["SectionTitleStyle"], Margin = new Thickness(0, 8, 0, 0) });
        var largura = new NumberBox { Header = "Largura (px)", Value = 1080, Minimum = 16, Maximum = 4096, Width = 140 };
        var altura = new NumberBox { Header = "Altura (px)", Value = 1080, Minimum = 16, Maximum = 4096, Width = 140 };
        var usar = new Button { Content = "Usar este tamanho", VerticalAlignment = VerticalAlignment.Bottom };
        usar.Click += (_, _) => ChooseDestination("personalizado", (int)largura.Value, (int)altura.Value);
        var personalizado = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 10 };
        personalizado.Children.Add(largura);
        personalizado.Children.Add(altura);
        personalizado.Children.Add(usar);
        WizardPanel.Children.Add(personalizado);
        if (project is not null)
        {
            var voltar = new Button { Content = "Voltar ao editor" };
            voltar.Click += (_, _) => { WizardPanel.Tag = null; ShowCurrentStep(); };
            WizardPanel.Children.Add(voltar);
        }

        WizardPanel.Tag = project is null ? null : "destino";
    }

    private void ChooseDestination(string id, int? width, int? height)
    {
        var novo = StudioRules.NewProject(id, project?.Output ?? "video", width, height);
        WizardPanel.Tag = null;
        if (project is null)
        {
            project = novo;
            choosingOutput = true;
        }
        else
        {
            project = project with { Destination = novo.Destination, Width = novo.Width, Height = novo.Height, Fps = novo.Fps };
        }

        Save();
        ShowCurrentStep();
    }

    private void BuildOutputChoice()
    {
        WizardPanel.Children.Clear();
        WizardPanel.Children.Add(Title("O que você quer no fim?"));
        WizardPanel.Children.Add(Muted("Dá para trocar depois, na aba Exportar."));
        var grade = new VariableSizedWrapGrid { Orientation = Orientation.Horizontal, ItemWidth = 330, ItemHeight = 84 };
        foreach (var saida in StudioRules.Outputs)
        {
            var botao = Card($"{saida.Title} · {saida.Extension.ToUpperInvariant()}", saida.Description, project?.Output == saida.Id, null);
            botao.Click += (_, _) =>
            {
                project = project! with { Output = saida.Id };
                choosingOutput = false;
                Save();
                ShowCurrentStep();
            };
            grade.Children.Add(botao);
        }

        WizardPanel.Children.Add(grade);
    }

    private void OnChangeDestination(object sender, RoutedEventArgs e)
    {
        WizardPanel.Tag = "destino";
        ReleasePlayer();
        ShowCurrentStep();
    }

    private async void OnNewProject(object sender, RoutedEventArgs e)
    {
        var dialogo = new ContentDialog
        {
            Title = "Começar outro projeto?",
            Content = "O projeto atual será descartado. Os arquivos originais e o que já foi exportado continuam onde estão.",
            PrimaryButtonText = "Começar outro",
            CloseButtonText = "Cancelar",
            XamlRoot = XamlRoot
        };
        if (await dialogo.ShowAsync() != ContentDialogResult.Primary)
        {
            return;
        }

        ReleasePlayer();
        project = null;
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

    // ==================== painéis ====================

    private void RefreshPanels()
    {
        if (project is null)
        {
            return;
        }

        BuildClips();
        BuildLayers();
        BuildTexts();
        BuildAudios();
        BuildExport();
        EmptyText.Visibility = project.Clips.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
    }

    private void BuildClips()
    {
        ReleasePlayer();
        ClipsPanel.Children.Clear();
        ClipsPanel.Children.Add(CreationControls());
        var adicionar = Action("Adicionar vídeos ou fotos");
        adicionar.Click += async (_, _) => await AddMediaAsync();
        ClipsPanel.Children.Add(adicionar);
        if (project!.Clips.Count == 0)
        {
            ClipsPanel.Children.Add(Muted("Os clipes tocam um depois do outro, na ordem da lista. Clique num clipe para cortar, enquadrar e ajustar o som."));
        }

        for (var i = 0; i < project.Clips.Count; i++)
        {
            ClipsPanel.Children.Add(ClipCard(project.Clips[i], i));
        }
    }

    private Border ClipCard(StudioClip clip, int index)
    {
        var nome = infos.TryGetValue(clip.File, out var info) ? info.FileName : Path.GetFileName(clip.File);
        var resumo = Muted(ClipSummary(clip));
        var cabeca = Header($"{index + 1}. {nome}", resumo, () => Toggle(clip.Id),
            ("↑", index > 0, () => Move(clip.Id, -1)),
            ("↓", index < project!.Clips.Count - 1, () => Move(clip.Id, 1)),
            ("✕", true, () => RemoveClip(clip.Id)));
        var corpo = new StackPanel { Spacing = 10 };
        corpo.Children.Add(cabeca);
        if (openId == clip.Id)
        {
            FinishingControls(corpo,clip,index);
            var split=Action("Dividir clipe no cursor");split.Click+=(_,_)=>{try {var updated=StudioCreation.SplitClip(project!,clip.Id,TimeSlider.Value,NewId("c"));ChangeAndRefresh(_=>updated);}catch(Exception e){ShowStatus(e.Message,InfoBarSeverity.Warning);}};corpo.Children.Add(split);
            if (clip.IsImage)
            {
                corpo.Children.Add(Slider($"Na tela por {{0}} s", clip.Duration ?? 3, 0.5, 15, 0.5, v => v.ToString("0.#", PtBr),
                    v => Change(p => WithClip(p, clip.Id, c => c with { Duration = v })), () => resumo.Text = ClipSummary(Find(clip.Id))));
            }
            else
            {
                corpo.Children.Add(Muted("Toque o vídeo e marque o início e o fim do trecho:"));
                var player = trimControl = new TrimPlayer();
                trimPlayer = new MediaPlayer { AutoPlay = false, Source = MediaSource.CreateFromUri(new Uri(clip.File)) };
                player.Attach(trimPlayer, true);
                player.SetTrim(TimeSpan.FromSeconds(clip.Start ?? 0), clip.End is { } fim ? TimeSpan.FromSeconds(fim) : null);
                void Mostrar()
                {
                    var atual = Find(clip.Id);
                    var trecho = (TimeSpan.FromSeconds(atual.Start ?? 0), (TimeSpan?)TimeSpan.FromSeconds(atual.End ?? 0));
                    player.SetTrim(trecho.Item1, trecho.Item2);
                    fullscreen?.Player.SetTrim(trecho.Item1, trecho.Item2);
                    resumo.Text = ClipSummary(atual);
                }

                void Inicio(TimeSpan inicio)
                {
                    var novoInicio = Math.Min(inicio.TotalSeconds, (Find(clip.Id).End ?? double.MaxValue) - StudioRules.MinimumDuration);
                    Change(p => WithClip(p, clip.Id, c => c with { Start = Math.Max(0, novoInicio) }));
                    Mostrar();
                }

                void Fim(TimeSpan final)
                {
                    var novoFim = Math.Max(final.TotalSeconds, (Find(clip.Id).Start ?? 0) + StudioRules.MinimumDuration);
                    Change(p => WithClip(p, clip.Id, c => c with { End = novoFim }));
                    Mostrar();
                }

                player.StartMarked += Inicio;
                player.EndMarked += Fim;
                // Tela cheia: o mesmo player vai para uma janela própria e volta ao sair.
                player.FullscreenToggled += () =>
                {
                    if (fullscreen is not null)
                    {
                        fullscreen.Close();
                        return;
                    }

                    if (trimPlayer is not { } tocador)
                    {
                        return;
                    }

                    player.Detach();
                    var janela = fullscreen = new TrimFullscreenWindow(tocador, true, $"Trecho do clipe — {nome}");
                    janela.Player.StartMarked += Inicio;
                    janela.Player.EndMarked += Fim;
                    janela.Closed += (_, _) =>
                    {
                        janela.Player.Detach();
                        fullscreen = null;
                        if (trimPlayer == tocador && !releasing)
                        {
                            player.Attach(tocador, true);
                            Mostrar();
                        }
                    };
                    janela.Activate();
                    Mostrar();
                };
                corpo.Children.Add(player);
            }

            corpo.Children.Add(Choice("Enquadramento", StudioRules.Fits.Select(f => (f.Id, f.Title)).ToList(), clip.Fit, id =>
            {
                Change(p => WithClip(p, clip.Id, c => c with { Fit = id }));
                resumo.Text = ClipSummary(Find(clip.Id));
            }));
            corpo.Children.Add(Muted(StudioRules.Fits.First(f => f.Id == clip.Fit).Description));
            corpo.Children.Add(TransformControls(() => Find(clip.Id).Transform, t => Change(p => WithClip(p, clip.Id, c => c with { Transform = t })),duration:StudioRules.ClipDuration(clip),
                measures:() => FrameMeasures, fit:id => Change(p => WithClip(p, clip.Id, c => c with { Fit = id }))));
            if (!clip.IsImage)
            {
                var som = new ToggleSwitch { Header = "Som do vídeo", IsOn = clip.Muted != true };
                som.Toggled += (_, _) =>
                {
                    Change(p => WithClip(p, clip.Id, c => c with { Muted = !som.IsOn }));
                    resumo.Text = ClipSummary(Find(clip.Id));
                };
                corpo.Children.Add(som);
                corpo.Children.Add(Slider("Volume: {0}%", clip.Volume ?? 1, 0, 2, 0.05, v => Math.Round(v * 100).ToString(PtBr),
                    v => Change(p => WithClip(p, clip.Id, c => c with { Volume = v })), null));
            }
        }

        return Panel(corpo);
    }

    private void BuildTexts()
    {
        TextsPanel.Children.Clear();
        TextsPanel.Children.Add(CaptionControls());
        var adicionar = Action("Adicionar texto");
        adicionar.IsEnabled = project!.Clips.Count > 0;
        adicionar.Click += (_, _) =>
        {
            var id = NewId("t");
            var inicio = Math.Min(TimeSlider.Value, Math.Max(0, Total - 0.5));
            openId = id;
            ChangeAndRefresh(p => p with
            {
                Texts = [.. p.Texts, new StudioText { Id = id, Text = "Seu texto aqui", Start = inicio, End = Math.Min(Total > 0 ? Total : 3, inicio + 3), Y = 0.15, Size = 0.06, Color = "#FFFFFF", Outline = "#000000", Font = "anton" }]
            });
            EditorPivot.SelectedIndex = 2;
        };
        TextsPanel.Children.Add(adicionar);
        if (project.Clips.Count == 0)
        {
            TextsPanel.Children.Add(Muted("Adicione um vídeo ou foto antes: o texto aparece por cima deles."));
        }

        foreach (var texto in project.Texts)
        {
            TextsPanel.Children.Add(TextCard(texto));
        }
    }

    private Border TextCard(StudioText texto)
    {
        var resumo = Muted($"{Clock(texto.Start)} → {Clock(texto.End)}");
        var cabeca = Header(string.IsNullOrWhiteSpace(texto.Text) ? "(vazio)" : texto.Text, resumo, () => Toggle(texto.Id),
            ("✕", true, () => { if (openId == texto.Id) { openId = null; } ChangeAndRefresh(p => p with { Texts = [.. p.Texts.Where(t => t.Id != texto.Id)] }); }));
        var corpo = new StackPanel { Spacing = 10 };
        corpo.Children.Add(cabeca);
        if (openId != texto.Id)
        {
            return Panel(corpo);
        }

        var caixa = new TextBox { Header = "Texto", Text = texto.Text, AcceptsReturn = true, TextWrapping = TextWrapping.Wrap, MaxLength = 300 };
        caixa.TextChanged += (_, _) => Change(p => WithText(p, texto.Id, t => t with { Text = caixa.Text }));
        corpo.Children.Add(caixa);
        corpo.Children.Add(Choice("Fonte", StudioRules.Fonts.Select(f => (f.Id, f.Name)).ToList(), texto.Font, id => Change(p => WithText(p, texto.Id, t => t with { Font = id }))));

        corpo.Children.Add(Muted("Cor"));
        var cores = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        foreach (var cor in Colors)
        {
            var amostra = new Button
            {
                Width = 30,
                Height = 30,
                CornerRadius = new CornerRadius(15),
                Background = new SolidColorBrush(ParseColor(cor)),
                BorderBrush = (Brush)Application.Current.Resources["BrandStrokeGlowBrush"],
                BorderThickness = new Thickness(string.Equals(texto.Color, cor, StringComparison.OrdinalIgnoreCase) ? 3 : 1)
            };
            Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(amostra, $"Cor {cor}");
            amostra.Click += (_, _) => ChangeAndRefresh(p => WithText(p, texto.Id, t => t with { Color = cor }));
            cores.Children.Add(amostra);
        }

        corpo.Children.Add(cores);
        var contorno = new ToggleSwitch { Header = "Contorno", IsOn = texto.Outline is not null };
        contorno.Toggled += (_, _) => Change(p => WithText(p, texto.Id, t => t with { Outline = contorno.IsOn ? (t.Color.Equals("#000000", StringComparison.OrdinalIgnoreCase) ? "#FFFFFF" : "#000000") : null }));
        corpo.Children.Add(contorno);
        var fundo = new ToggleSwitch { Header = "Caixa de fundo", IsOn = texto.Box is not null };
        fundo.Toggled += (_, _) => Change(p => WithText(p, texto.Id, t => t with { Box = fundo.IsOn ? "#000000AA" : null }));
        corpo.Children.Add(fundo);
        corpo.Children.Add(Slider("Tamanho: {0}% da altura", texto.Size, 0.03, 0.16, 0.005, v => (v * 100).ToString("0.#", PtBr),
            v => Change(p => WithText(p, texto.Id, t => t with { Size = v })), null));
        corpo.Children.Add(Choice("Posição", [("0.15", "Em cima"), ("0.5", "No meio"), ("0.85", "Embaixo")], null, y =>
            ChangeAndRefresh(p => WithText(p, texto.Id, t => t with { Y = double.Parse(y, CultureInfo.InvariantCulture), X = 0.5 }))));
        corpo.Children.Add(Slider("Altura: {0}%", texto.Y, 0, 1, 0.01, v => Math.Round(v * 100).ToString(PtBr),
            v => Change(p => WithText(p, texto.Id, t => t with { Y = v })), null));
        corpo.Children.Add(TransformControls(() => FindText(texto.Id).Transform, t => Change(p => WithText(p, texto.Id, c => c with { Transform = t })), allowRemoval: false,duration:texto.End-texto.Start,
            measures:() => FrameMeasures));
        if (Total > 0)
        {
            corpo.Children.Add(Slider("Aparece em {0}", Math.Min(texto.Start, Total), 0, Total, 0.1, v => Clock(v),
                v => Change(p => WithText(p, texto.Id, t => t with { Start = Math.Min(v, t.End - 0.1) })), () => resumo.Text = $"{Clock(FindText(texto.Id).Start)} → {Clock(FindText(texto.Id).End)}"));
            corpo.Children.Add(Slider("Some em {0}", Math.Min(texto.End, Total), 0, Total, 0.1, v => Clock(v),
                v => Change(p => WithText(p, texto.Id, t => t with { End = Math.Max(v, t.Start + 0.1) })), () => resumo.Text = $"{Clock(FindText(texto.Id).Start)} → {Clock(FindText(texto.Id).End)}"));
            var aqui = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
            var comeca = new Button { Content = "Começa aqui" };
            comeca.Click += (_, _) => ChangeAndRefresh(p => WithText(p, texto.Id, t => t with { Start = TimeSlider.Value, End = Math.Max(t.End, TimeSlider.Value + 0.5) }));
            var termina = new Button { Content = "Termina aqui" };
            termina.Click += (_, _) => ChangeAndRefresh(p => WithText(p, texto.Id, t => t with { End = Math.Max(TimeSlider.Value, t.Start + 0.5) }));
            aqui.Children.Add(comeca);
            aqui.Children.Add(termina);
            corpo.Children.Add(aqui);
        }

        return Panel(corpo);
    }

    private void BuildAudios()
    {
        AudiosPanel.Children.Clear();
        AudiosPanel.Children.Add(NarrationControls());
        var adicionar = Action("Adicionar música ou áudio");
        adicionar.Click += async (_, _) => await AddAudioAsync();
        AudiosPanel.Children.Add(adicionar);
        if (project!.Audios.Count == 0)
        {
            AudiosPanel.Children.Add(Muted("A música toca por cima do som dos vídeos. Escolha o trecho, onde ela entra, o volume e o fade."));
        }

        foreach (var audio in project.Audios)
        {
            AudiosPanel.Children.Add(AudioCard(audio));
        }
    }

    private Border AudioCard(StudioAudio audio)
    {
        var nome = infos.TryGetValue(audio.File, out var info) ? info.FileName : Path.GetFileName(audio.File);
        var duracao = info?.Duration?.TotalSeconds ?? audio.End;
        string Resumo(StudioAudio a) => $"Trecho {Clock(a.Start)}–{Clock(a.End)} · entra em {Clock(a.At ?? 0)} · {Math.Round((a.Volume ?? 1) * 100)}%";
        var resumo = Muted(Resumo(audio));
        var cabeca = Header(nome, resumo, () => Toggle(audio.Id),
            ("✕", true, () => { if (openId == audio.Id) { openId = null; } ChangeAndRefresh(p => p with { Audios = [.. p.Audios.Where(a => a.Id != audio.Id)] }); }));
        var corpo = new StackPanel { Spacing = 10 };
        corpo.Children.Add(cabeca);
        if (openId != audio.Id)
        {
            return Panel(corpo);
        }

        void Atualizar() => resumo.Text = Resumo(project!.Audios.First(a => a.Id == audio.Id));
        corpo.Children.Add(Slider("Começa a música em {0}", audio.Start, 0, duracao, 0.1, v => Clock(v),
            v => Change(p => WithAudio(p, audio.Id, a => a with { Start = Math.Min(v, a.End - 0.1) })), Atualizar));
        corpo.Children.Add(Slider("Termina a música em {0}", audio.End, 0, duracao, 0.1, v => Clock(v),
            v => Change(p => WithAudio(p, audio.Id, a => a with { End = Math.Max(v, a.Start + 0.1) })), Atualizar));
        if (Total > 0)
        {
            corpo.Children.Add(Slider("Entra no vídeo em {0}", Math.Min(audio.At ?? 0, Total), 0, Total, 0.1, v => Clock(v),
                v => Change(p => WithAudio(p, audio.Id, a => a with { At = v })), Atualizar));
        }

        corpo.Children.Add(Slider("Volume: {0}%", audio.Volume ?? 1, 0, 2, 0.05, v => Math.Round(v * 100).ToString(PtBr),
            v => Change(p => WithAudio(p, audio.Id, a => a with { Volume = v })), Atualizar));
        var fades = new List<(string, string)> { ("0", "0 s"), ("1", "1 s"), ("2", "2 s"), ("3", "3 s") };
        corpo.Children.Add(Choice("Fade de entrada", fades, (audio.FadeIn ?? 0).ToString(CultureInfo.InvariantCulture),
            v => Change(p => WithAudio(p, audio.Id, a => a with { FadeIn = double.Parse(v, CultureInfo.InvariantCulture) }))));
        corpo.Children.Add(Choice("Fade de saída", fades, (audio.FadeOut ?? 0).ToString(CultureInfo.InvariantCulture),
            v => Change(p => WithAudio(p, audio.Id, a => a with { FadeOut = double.Parse(v, CultureInfo.InvariantCulture) }))));
        return Panel(corpo);
    }

    private void BuildExport()
    {
        ExportPanel.Children.Clear();
        var atual = project!;
        ExportPanel.Children.Add(Choice("Exportar como", StudioRules.Outputs.Select(o => (o.Id, o.Title)).ToList(), atual.Output,
            id => ChangeAndRefresh(p => p with { Output = id })));
        var saida = StudioRules.FindOutput(atual.Output);
        ExportPanel.Children.Add(Muted($"{saida?.Description} · {atual.Width}×{atual.Height} · {StudioRules.ReadableDuration(Total)}"));

        if (atual.Output == "imagem")
        {
            var quadro = new Button { Content = $"Usar o ponto da prévia ({Clock(TimeSlider.Value)}) · agora em {Clock(atual.Frame)}" };
            quadro.Click += (_, _) => ChangeAndRefresh(p => p with { Frame = TimeSlider.Value });
            ExportPanel.Children.Add(quadro);
        }

        ComboBox? modelos = null;
        if (atual.Output == "texto")
        {
            var instalados = App.Services.Models.ListModels().Where(m => m.IsInstalled).ToList();
            if (instalados.Count == 0)
            {
                ExportPanel.Children.Add(Warning("Para transcrever, baixe um modelo do Whisper em Configurações → Modelos."));
            }
            else
            {
                modelos = new ComboBox { Header = "Modelo de transcrição", HorizontalAlignment = HorizontalAlignment.Stretch };
                foreach (var modelo in instalados)
                {
                    modelos.Items.Add(new ComboBoxItem { Content = modelo.DisplayName, Tag = modelo.Id });
                }

                modelos.SelectedIndex = Math.Max(0, instalados.FindIndex(m => m.Id == "small"));
                ExportPanel.Children.Add(modelos);
            }
        }

        foreach (var problema in StudioRules.Problems(atual))
        {
            ExportPanel.Children.Add(Warning(problema));
        }

        foreach (var aviso in StudioRules.Warnings(atual))
        {
            ExportPanel.Children.Add(Muted("• " + aviso));
        }

        var exportar = Action($"Exportar {saida?.Title.ToLowerInvariant()}");
        exportar.IsEnabled = StudioRules.Problems(atual).Count == 0 && (atual.Output != "texto" || modelos is not null);
        exportar.Click += (_, _) => Export((modelos?.SelectedItem as ComboBoxItem)?.Tag as string ?? "base");
        ExportPanel.Children.Add(exportar);
        ExportPanel.Children.Add(Muted($"O resultado vai para {App.Services.OutputDirectory}, pela fila de conversões."));
    }

    // ==================== ações ====================

    private async Task AddMediaAsync()
    {
        var caminhos = FileDialogs.PickStudioMedia(App.MainWindow);
        if (caminhos.Count == 0)
        {
            return;
        }

        var novos = new List<StudioClip>();
        foreach (var caminho in caminhos)
        {
            try
            {
                var info = await App.Services.Inspector.InspectAsync(caminho, CancellationToken.None);
                infos[caminho] = info;
                if (info.Kind == MediaKind.Image)
                {
                    novos.Add(new StudioClip { Id = NewId("c"), File = caminho, Kind = "imagem", Duration = 3, Fit = "caber-desfocado" });
                }
                else if (info.HasVideo)
                {
                    novos.Add(new StudioClip { Id = NewId("c"), File = caminho, Kind = "video", Start = 0, End = info.Duration?.TotalSeconds ?? 10, Fit = "caber-desfocado", Volume = 1 });
                }
                else
                {
                    ShowStatus($"“{Path.GetFileName(caminho)}” não tem imagem. Músicas entram pela aba Áudio.", InfoBarSeverity.Warning);
                }
            }
            catch (ConversionException exception)
            {
                ShowStatus($"“{Path.GetFileName(caminho)}”: {exception.Message}", InfoBarSeverity.Error);
            }
        }

        ChangeAndRefresh(p => p with { Clips = [.. p.Clips, .. novos] });
    }

    private async Task AddAudioAsync()
    {
        var caminho = FileDialogs.PickStudioAudio(App.MainWindow);
        if (caminho is null)
        {
            return;
        }

        try
        {
            var info = await App.Services.Inspector.InspectAsync(caminho, CancellationToken.None);
            if (!info.HasAudio)
            {
                ShowStatus($"“{info.FileName}” não tem áudio.", InfoBarSeverity.Warning);
                return;
            }

            infos[caminho] = info;
            var duracao = info.Duration?.TotalSeconds ?? 30;
            var id = NewId("m");
            openId = id;
            ChangeAndRefresh(p => p with
            {
                Audios = [.. p.Audios, new StudioAudio { Id = id, File = caminho, At = 0, Start = 0, End = Total > 0 ? Math.Min(duracao, Total) : duracao, Volume = 0.8, FadeIn = 0, FadeOut = 1 }]
            });
        }
        catch (ConversionException exception)
        {
            ShowStatus(exception.Message, InfoBarSeverity.Error);
        }
    }

    private void Export(string modelo)
    {
        if (project is null)
        {
            return;
        }

        try
        {
            if (!LicenseGate.CanConvert(out var licenca))
            {
                ShowStatus(licenca, InfoBarSeverity.Warning);
                return;
            }

            var opcoes = new StudioExportOptions { Project = project, Quality = 80, TranscriptionModel = modelo, TranscriptionLanguage = "auto" };
            opcoes.Validate();
            var entradas = Inputs();
            var job = new ConversionJob
            {
                Options = opcoes,
                InputPath = entradas[0],
                ExtraInputPaths = [.. entradas.Skip(1)],
                OutputDirectory = App.Services.OutputDirectory,
                OutputBaseName = SafeFileName.Sanitize($"estudio_{project.Destination}_{DateTime.Now:yyyyMMdd_HHmmss}")
            };
            App.Services.Queue.Enqueue(job);
            ShowStatus("Exportação enviada para a fila.", InfoBarSeverity.Success);
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

    private void Toggle(string id)
    {
        openId = openId == id ? null : id;
        RefreshPanels();
    }

    private void Move(string id, int passo) => ChangeAndRefresh(p =>
    {
        var lista = p.Clips.ToList();
        var de = lista.FindIndex(c => c.Id == id);
        var para = Math.Clamp(de + passo, 0, lista.Count - 1);
        if (de < 0 || de == para)
        {
            return p;
        }

        var clipe = lista[de];
        lista.RemoveAt(de);
        lista.Insert(para, clipe);
        return p with { Clips = lista };
    });

    private void RemoveClip(string id)
    {
        if (openId == id)
        {
            openId = null;
        }

        ChangeAndRefresh(p => p with { Clips = [.. p.Clips.Where(c => c.Id != id)] });
    }

    // ==================== prévia ====================

    private void OnTimeChanged(object sender, RangeBaseValueChangedEventArgs e)
    {
        TimeText.Text = Clock(e.NewValue);
        RequestPreview();
    }

    private void RequestPreview()
    {
        VideoPreviewButton.Content = StudioImmediatePreview.Accept(project) ? "Reproduzir prévia imediata" : "Gerar prévia com todos os efeitos";
        previewTimer?.Restart();
    }

    /// <summary>Um quadro pequeno montado pelo motor do estudio, igual ao que a exportacao faria.</summary>
    private async Task RenderPreviewAsync()
    {
        if (project is null || project.Clips.Count == 0)
        {
            PreviewImage.Source = null;
            return;
        }

        previewCancellation?.Cancel();
        var cancelamento = previewCancellation = new CancellationTokenSource();
        var escala = Math.Min(1.0, 540.0 / Math.Max(project.Width, project.Height));
        var quadro = project with
        {
            Output = "imagem",
            Frame = Math.Min(TimeSlider.Value, Math.Max(0, Total - 0.04)),
            Width = StudioRules.Even(project.Width * escala),
            Height = StudioRules.Even(project.Height * escala)
        };
        var pasta = Path.Combine(App.Services.Paths.Temp, "estudio-previa", Guid.NewGuid().ToString("N"));
        var arquivo = Path.Combine(pasta, "quadro.png");
        PreviewRing.IsActive = true;
        try
        {
            await App.Services.Studio.RenderAsync(quadro, Inputs(), pasta, pasta, arquivo, 80, _ => { }, cancellationToken: cancelamento.Token);
            if (cancelamento.IsCancellationRequested)
            {
                return;
            }

            var imagem = new BitmapImage();
            using (var leitura = File.OpenRead(arquivo))
            {
                await imagem.SetSourceAsync(leitura.AsRandomAccessStream());
            }

            PreviewImage.Source = imagem;
        }
        catch (OperationCanceledException)
        {
        }
        catch (ConversionException exception)
        {
            if (!cancelamento.IsCancellationRequested)
            {
                ShowStatus($"Não deu para montar a prévia: {exception.Message}", InfoBarSeverity.Warning);
            }
        }
        finally
        {
            if (previewCancellation == cancelamento)
            {
                PreviewRing.IsActive = false;
            }

            TryDeleteFolder(pasta);
        }
    }

    private async void OnPreviewVideo(object sender, RoutedEventArgs e)
    {
        if (project is null || project.Clips.Count == 0)
        {
            return;
        }
        if (StudioImmediatePreview.Accept(project) && await ShowImmediatePreviewAsync(project))
        {
            return;
        }

        var escala = Math.Min(1.0, 480.0 / Math.Max(project.Width, project.Height));
        var pequeno = project with { Output = "video", Width = StudioRules.Even(project.Width * escala), Height = StudioRules.Even(project.Height * escala) };
        var pasta = Path.Combine(App.Services.Paths.Temp, "estudio-previa", Guid.NewGuid().ToString("N"));
        var arquivo = Path.Combine(pasta, "previa.mp4");
        VideoPreviewButton.IsEnabled = false;
        var botaoTexto = VideoPreviewButton.Content;
        try
        {
            await App.Services.Studio.RenderAsync(pequeno, Inputs(), pasta, pasta, arquivo, 50, progresso =>
            {
                if (progresso.Percent is { } p)
                {
                    DispatcherQueue.TryEnqueue(() => VideoPreviewButton.Content = $"Montando a prévia… {Math.Round(p * 100)}%");
                }
            });
            var tocador = new MediaPlayerElement
            {
                Source = MediaSource.CreateFromUri(new Uri(arquivo)),
                AreTransportControlsEnabled = true,
                AutoPlay = true,
                MaxHeight = 560,
                Stretch = Stretch.Uniform
            };
            var dialogo = new ContentDialog { Title = "Prévia", Content = tocador, CloseButtonText = "Fechar", XamlRoot = XamlRoot };
            await dialogo.ShowAsync();
            tocador.MediaPlayer?.Pause();
            tocador.SetMediaPlayer(null);
        }
        catch (ConversionException exception)
        {
            ShowStatus($"Não deu para montar a prévia: {exception.Message}", InfoBarSeverity.Warning);
        }
        finally
        {
            VideoPreviewButton.Content = botaoTexto;
            VideoPreviewButton.IsEnabled = true;
            TryDeleteFolder(pasta);
        }
    }

    private async Task<bool> ShowImmediatePreviewAsync(StudioProject atual)
    {
        var clip = atual.Clips.Single();
        var player = new MediaPlayer { AutoPlay = false, Source = MediaSource.CreateFromUri(new Uri(clip.File)) };
        player.Volume = clip.Muted == true ? 0 : clip.Volume ?? 1;
        var end = clip.End is { } seconds ? TimeSpan.FromSeconds(seconds) : infos.GetValueOrDefault(clip.File)?.Duration;
        player.MediaOpened += (_, _) =>
        {
            player.Position = TimeSpan.FromSeconds(clip.Start ?? 0);
            player.Play();
        };
        player.PlaybackSession.PositionChanged += (_, _) => { if (end is not null && player.Position >= end) player.Pause(); };
        var element = new MediaPlayerElement
        {
            AreTransportControlsEnabled = true,
            MaxHeight = 560,
            Stretch = clip.Fit == "preencher" ? Stretch.UniformToFill : Stretch.Uniform
        };
        element.SetMediaPlayer(player);
        var content = new Grid { Background = new SolidColorBrush(ParseColor(atual.Background?.Color ?? "#000000")) };
        content.Children.Add(element);
        var failed = false;
        var dialog = new ContentDialog { Title = "Prévia imediata", Content = content, CloseButtonText = "Fechar", XamlRoot = XamlRoot };
        player.MediaFailed += (_, _) => { failed = true; dialog.Hide(); };
        await dialog.ShowAsync();
        player.Pause();
        element.SetMediaPlayer(null);
        return !failed;
    }

    // ==================== peças ====================

    private StudioClip Find(string id) => project!.Clips.First(c => c.Id == id);

    private StudioText FindText(string id) => project!.Texts.First(t => t.Id == id);

    private static StudioProject WithClip(StudioProject p, string id, Func<StudioClip, StudioClip> m) =>
        p with { Clips = [.. p.Clips.Select(c => c.Id == id ? m(c) : c)] };

    private static StudioProject WithText(StudioProject p, string id, Func<StudioText, StudioText> m) =>
        p with { Texts = [.. p.Texts.Select(t => t.Id == id ? m(t) : t)] };

    private static StudioProject WithAudio(StudioProject p, string id, Func<StudioAudio, StudioAudio> m) =>
        p with { Audios = [.. p.Audios.Select(a => a.Id == id ? m(a) : a)] };

    private static string ClipSummary(StudioClip c) =>
        $"{(c.IsImage ? "Foto" : "Vídeo")} · {Clock(StudioRules.ClipDuration(c))} · {StudioRules.Fits.FirstOrDefault(f => f.Id == c.Fit)?.Title}{(c.Muted == true ? " · sem som" : "")}";

    private static string NewId(string prefixo) => prefixo + Guid.NewGuid().ToString("N")[..8];

    /// <summary>"01:05,3": minutos, segundos e décimos, como no site e no Android.</summary>
    private static string Clock(double segundos)
    {
        var decimos = (long)Math.Round(Math.Max(0, segundos) * 10, MidpointRounding.AwayFromZero);
        return $"{decimos / 600:00}:{decimos / 10 % 60:00},{decimos % 10}";
    }

    /// <summary>
    /// Solta o player do clipe na ordem certa: fecha a tela cheia sem religar, desliga o controle
    /// e só então descarta o MediaPlayer (descartar com o controle ligado derruba o aplicativo).
    /// </summary>
    private void ReleasePlayer()
    {
        releasing = true;
        try
        {
            fullscreen?.Close();
        }
        finally
        {
            releasing = false;
        }

        fullscreen = null;
        trimControl?.Detach();
        trimControl = null;
        trimPlayer?.Pause();
        trimPlayer?.Dispose();
        trimPlayer = null;
    }

    private static void TryDeleteFolder(string pasta)
    {
        try
        {
            Directory.Delete(pasta, recursive: true);
        }
        catch (Exception exception) when (exception is IOException or UnauthorizedAccessException)
        {
        }
    }

    private void ShowStatus(string mensagem, InfoBarSeverity gravidade)
    {
        StatusBar.Message = mensagem;
        StatusBar.Severity = gravidade;
        StatusBar.IsOpen = true;
    }

    private static TextBlock Title(string texto) => new() { Text = texto, FontSize = 28, FontWeight = FontWeights.SemiBold };

    private static TextBlock Muted(string texto) => new()
    {
        Text = texto,
        FontSize = 12.5,
        TextWrapping = TextWrapping.WrapWholeWords,
        Foreground = (Brush)Application.Current.Resources["BrandTextMutedBrush"]
    };

    private static TextBlock Warning(string texto) => new()
    {
        Text = "• " + texto,
        FontSize = 13,
        TextWrapping = TextWrapping.WrapWholeWords,
        Foreground = (Brush)Application.Current.Resources["BrandYellowBrush"]
    };

    private static Button Action(string texto) => new()
    {
        Content = texto,
        Style = (Style)Application.Current.Resources["BrandActionButtonStyle"]
    };

    private static Border Panel(UIElement conteudo) => new()
    {
        Style = (Style)Application.Current.Resources["CardBorderStyle"],
        Margin = new Thickness(0),
        Padding = new Thickness(14),
        Child = conteudo
    };

    /// <summary>Cartão do assistente; `forma` é o retângulo na proporção do formato.</summary>
    private static Button Card(string titulo, string detalhe, bool ativo, UIElement? forma)
    {
        var textos = new StackPanel { Spacing = 2, VerticalAlignment = VerticalAlignment.Center };
        textos.Children.Add(new TextBlock { Text = titulo, FontSize = 15, FontWeight = FontWeights.SemiBold, TextWrapping = TextWrapping.WrapWholeWords });
        textos.Children.Add(Muted(detalhe));
        var linha = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 12 };
        if (forma is not null)
        {
            linha.Children.Add(forma);
        }

        linha.Children.Add(textos);
        return new Button
        {
            Content = linha,
            Margin = new Thickness(0, 0, 10, 10),
            Padding = new Thickness(14, 10, 14, 10),
            HorizontalAlignment = HorizontalAlignment.Stretch,
            VerticalAlignment = VerticalAlignment.Stretch,
            HorizontalContentAlignment = HorizontalAlignment.Left,
            CornerRadius = new CornerRadius(12),
            Background = (Brush)Application.Current.Resources["BrandPanelBrush"],
            BorderBrush = ativo ? (Brush)Application.Current.Resources["BrandOrangeBrush"] : (Brush)Application.Current.Resources["BrandStrokeBrush"],
            BorderThickness = new Thickness(ativo ? 2 : 1)
        };
    }

    private static Border Shape(int largura, int altura)
    {
        var maior = (double)Math.Max(largura, altura);
        return new Border
        {
            Width = 28 * largura / maior,
            Height = 28 * altura / maior,
            CornerRadius = new CornerRadius(3),
            Background = (Brush)Application.Current.Resources["BrandTextMutedBrush"],
            VerticalAlignment = VerticalAlignment.Center
        };
    }

    /// <summary>Cabeçalho de um item: título e resumo clicáveis, e os botões pequenos à direita.</summary>
    private static Grid Header(string titulo, TextBlock resumo, Action abrir, params (string Rotulo, bool Ativo, Action Fazer)[] botoes)
    {
        var grade = new Grid();
        grade.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        grade.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        var textos = new StackPanel { Spacing = 2 };
        textos.Children.Add(new TextBlock { Text = titulo, FontSize = 14, FontWeight = FontWeights.SemiBold, TextTrimming = TextTrimming.CharacterEllipsis });
        textos.Children.Add(resumo);
        var abrirBotao = new Button
        {
            Content = textos,
            Background = new SolidColorBrush(Microsoft.UI.Colors.Transparent),
            BorderThickness = new Thickness(0),
            Padding = new Thickness(0),
            HorizontalAlignment = HorizontalAlignment.Stretch,
            HorizontalContentAlignment = HorizontalAlignment.Left
        };
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(abrirBotao, titulo);
        abrirBotao.Click += (_, _) => abrir();
        grade.Children.Add(abrirBotao);
        var acoes = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 2 };
        foreach (var (rotulo, ativo, fazer) in botoes)
        {
            var botao = new Button { Content = rotulo, IsEnabled = ativo, Width = 34, Padding = new Thickness(0, 4, 0, 4) };
            Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(botao, rotulo switch { "↑" => $"Subir {titulo}", "↓" => $"Descer {titulo}", _ => $"Remover {titulo}" });
            botao.Click += (_, _) => fazer();
            acoes.Children.Add(botao);
        }

        Grid.SetColumn(acoes, 1);
        grade.Children.Add(acoes);
        return grade;
    }

    /// <summary>Um controle deslizante com o valor no rótulo ("Volume: 80%").</summary>
    private static StackPanel Slider(string rotulo, double valor, double minimo, double maximo, double passo, Func<double, string> formatar, Action<double> mudar, Action? depois)
    {
        var titulo = Muted(string.Format(PtBr, rotulo, formatar(valor)));
        var controle = new Slider { Minimum = minimo, Maximum = Math.Max(minimo + passo, maximo), StepFrequency = passo, Value = Math.Clamp(valor, minimo, Math.Max(minimo + passo, maximo)) };
        controle.ValueChanged += (_, e) =>
        {
            titulo.Text = string.Format(PtBr, rotulo, formatar(e.NewValue));
            mudar(e.NewValue);
            depois?.Invoke();
        };
        // − e + andam um passo: o ajuste fino que o mouse não acerta (como no editor de figurinhas).
        Button Fino(string texto, int sinal)
        {
            var botao = new Button { Content = texto, Width = 32, Padding = new Thickness(0, 2, 0, 4), VerticalAlignment = VerticalAlignment.Center };
            ToolTipService.SetToolTip(botao, sinal < 0 ? "Diminuir um passo" : "Aumentar um passo");
            botao.Click += (_, _) => controle.Value = Math.Clamp(Math.Round((controle.Value + (sinal * passo)) / passo) * passo, controle.Minimum, controle.Maximum);
            return botao;
        }

        var linha = new Grid { ColumnSpacing = 6 };
        linha.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        linha.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        linha.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        var menos = Fino("−", -1);
        var mais = Fino("+", 1);
        Grid.SetColumn(controle, 1);
        Grid.SetColumn(mais, 2);
        linha.Children.Add(menos);
        linha.Children.Add(controle);
        linha.Children.Add(mais);
        var bloco = new StackPanel { Spacing = 0 };
        bloco.Children.Add(titulo);
        bloco.Children.Add(linha);
        return bloco;
    }

    /// <summary>Opções em botões alternáveis, uma marcada por vez.</summary>
    private static StackPanel Choice(string rotulo, IReadOnlyList<(string Id, string Titulo)> opcoes, string? atual, Action<string> escolher)
    {
        var bloco = new StackPanel { Spacing = 6 };
        bloco.Children.Add(Muted(rotulo));
        // A grade usa a mesma largura para todos: a do rotulo mais longo.
        var largura = Math.Min(400, (opcoes.Max(o => o.Titulo.Length) * 7.6) + 34);
        var linha = new VariableSizedWrapGrid { Orientation = Orientation.Horizontal, ItemWidth = largura };
        var botoes = new List<ToggleButton>();
        foreach (var (id, titulo) in opcoes)
        {
            var botao = new ToggleButton { Content = titulo, IsChecked = id == atual, Margin = new Thickness(0, 0, 6, 6), HorizontalAlignment = HorizontalAlignment.Stretch };
            botao.Click += (_, _) =>
            {
                foreach (var outro in botoes)
                {
                    outro.IsChecked = outro == botao;
                }

                escolher(id);
            };
            botoes.Add(botao);
            linha.Children.Add(botao);
        }

        bloco.Children.Add(linha);
        return bloco;
    }

    private static Windows.UI.Color ParseColor(string hex) => Microsoft.UI.ColorHelper.FromArgb(
        0xFF,
        Convert.ToByte(hex.Substring(1, 2), 16),
        Convert.ToByte(hex.Substring(3, 2), 16),
        Convert.ToByte(hex.Substring(5, 2), 16));

    /// <summary>Um temporizador que reinicia a cada pedido: a previa sai depois que a pessoa para de mexer.</summary>
    private sealed class DispatcherQueueTimerWrapper
    {
        private readonly Microsoft.UI.Dispatching.DispatcherQueueTimer timer;

        public DispatcherQueueTimerWrapper(Microsoft.UI.Dispatching.DispatcherQueue fila, TimeSpan espera, Action acao)
        {
            timer = fila.CreateTimer();
            timer.Interval = espera;
            timer.IsRepeating = false;
            timer.Tick += (_, _) => acao();
        }

        public void Restart()
        {
            timer.Stop();
            timer.Start();
        }
    }
}
