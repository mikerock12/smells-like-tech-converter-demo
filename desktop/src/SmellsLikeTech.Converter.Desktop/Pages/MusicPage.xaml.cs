using Microsoft.UI;
using Microsoft.UI.Input;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Shapes;
using SmellsLikeTech.Converter.Composition;
using SmellsLikeTech.Converter.Core.Music;
using SmellsLikeTech.Converter.Desktop.Services;
using SmellsLikeTech.Converter.Engine.Music;
using Windows.Foundation;
using Windows.UI;
using Path = System.IO.Path;

namespace SmellsLikeTech.Converter.Desktop.Pages;

/// <summary>
/// Criação musical no Windows: gravação multipista ouvindo as outras faixas (WASAPI), mixer,
/// efeitos, separação por IA e exportação. Mesmo projeto (.sltmusic) e mesmo mixer do site
/// e do Android (Core/Music). A licença vale como no resto do app: teste de 7 dias, depois a
/// chave; nada de limite de projetos, faixas ou exportações.
/// </summary>
public sealed partial class MusicPage : Page
{
    private const int Rate = MusicRules.DefaultSampleRate;
    private const double HeaderWidth = 270;
    private const double RowHeight = 128;

    private MusicAudioEngine? engine;
    private MusicProject? project;
    private readonly Stack<string> undo = new();
    private readonly Stack<string> redo = new();
    private string? mergeKey;
    private DateTime mergeAt;
    private readonly Dictionary<string, float[]> peaks = [];
    private readonly DispatcherTimer timer = new() { Interval = TimeSpan.FromMilliseconds(33) };
    private DispatcherTimer? saveTimer;
    private string? selectedTrack;
    private string? selectedClip;
    private string effectsTarget = "master";
    private double zoom = 60;
    private bool snap = true;
    private bool dirty;
    private bool leaveConfirmed;
    private CancellationTokenSource? work;
    private CancellationTokenSource? prepareWork;
    private readonly Dictionary<string, IMusicSource> transformedSources = [];
    private readonly Dictionary<string, MusicAnalysis> analyses = [];

    // Elementos atualizados pelo relógio.
    private TextBlock? positionText;
    private TextBlock? timeText;
    private Rectangle? playhead;
    private Canvas? lanesCanvas;
    private ScrollViewer? lanesScroll;
    private ScrollViewer? headersScroll;
    private Button? playButton;
    private Button? recordButton;
    private readonly Dictionary<string, ProgressBar> trackMeters = [];
    private readonly Dictionary<string, (Rectangle L, Rectangle R)> stripMeters = [];

    public MusicPage()
    {
        InitializeComponent();
        timer.Tick += (_, _) => Tick();
        Loaded += (_, _) => ShowStart();
        Unloaded += (_, _) => Close();
    }

    private static MusicLibrary Library => App.Services.MusicLibrary;
    private static MusicTools Tools => App.Services.MusicTools;

    /// <summary>Saindo da página com gravação: para e guarda a tomada.</summary>
    private async void Close()
    {
        timer.Stop();
        if (project is not null)
        {
            if (App.Services.Settings.MusicAutoSave) SaveNow();
            else if (dirty && !leaveConfirmed && await AskToSave() == ContentDialogResult.Primary) SaveNow();
        }
        prepareWork?.Cancel();
        engine?.Dispose();
        engine = null;
    }

    private async Task<ContentDialogResult> AskToSave() => await new ContentDialog
    {
        Title = "Salvar o projeto?",
        Content = "Suas últimas alterações ainda não foram salvas neste computador.",
        PrimaryButtonText = "Salvar e sair",
        SecondaryButtonText = "Sair sem salvar",
        CloseButtonText = "Continuar editando",
        XamlRoot = XamlRoot ?? App.MainWindow?.Content?.XamlRoot
    }.ShowAsync();

    public bool HasUnsavedChanges => project is not null && dirty && !App.Services.Settings.MusicAutoSave;

    public async Task<bool> ConfirmLeaveAsync()
    {
        if (project is null) return true;
        if (App.Services.Settings.MusicAutoSave) { SaveNow(); return true; }
        if (!dirty) return true;
        var choice = await AskToSave();
        if (choice == ContentDialogResult.None) return false;
        if (choice == ContentDialogResult.Primary) SaveNow();
        leaveConfirmed = true;
        return true;
    }

    // ==================== tela inicial ====================

    private void ShowStart()
    {
        EditorView.Visibility = Visibility.Collapsed;
        StartView.Visibility = Visibility.Visible;
        StartPanel.Children.Clear();
        StartPanel.Children.Add(new TextBlock { Text = "CRIAÇÃO MUSICAL", Style = (Style)Application.Current.Resources["SectionTitleStyle"] });
        StartPanel.Children.Add(new TextBlock { Text = "Grave, mixe e separe faixas", FontSize = 28, FontWeight = Microsoft.UI.Text.FontWeights.SemiBold });
        StartPanel.Children.Add(Muted("Grave uma faixa ouvindo as outras, importe áudios, separe voz, bateria e baixo por IA, aplique efeitos e exporte em WAV, FLAC, MP3, AAC ou Opus. Tudo fica neste computador. Use fones com fio ou uma interface de áudio: com fone Bluetooth o atraso varia e a gravação sai fora do tempo."));
        if (!LicenseGate.CanConvert(out var license)) StartPanel.Children.Add(new InfoBar { IsOpen = true, Severity = InfoBarSeverity.Warning, Message = license, IsClosable = false });
        else if (!string.IsNullOrEmpty(license)) StartPanel.Children.Add(Muted(license));
        StartPanel.Children.Add(new TextBlock { Text = "COMEÇAR UM PROJETO", Style = (Style)Application.Current.Resources["SectionTitleStyle"] });
        var templates = new VariableSizedWrapGrid { Orientation = Orientation.Horizontal, ItemWidth = 260 };
        foreach (var template in MusicRules.Templates)
        {
            var button = Card(template.Label, string.Join(", ", template.Tracks) + (template.Buses.Length > 0 ? $" · barramentos: {string.Join(", ", template.Buses)}" : ""));
            AutomationProperties.SetName(button, $"Modelo {template.Label}");
            button.Click += (_, _) => Create(template.Id);
            templates.Children.Add(button);
        }

        var open = Card("Abrir um .sltmusic", "Projeto salvo no site, no Windows ou no Android.");
        AutomationProperties.SetName(open, "Abrir projeto .sltmusic");
        open.Click += (_, _) => OpenArchive();
        templates.Children.Add(open);
        StartPanel.Children.Add(templates);
        var autoSave = new ToggleSwitch { Header = "Salvar automaticamente", IsOn = App.Services.Settings.MusicAutoSave };
        ToolTipService.SetToolTip(autoSave, "Desligado: pergunta se você quer salvar ao sair do projeto.");
        autoSave.Toggled += async (_, _) => await App.Services.ApplySettingsAsync(App.Services.Settings with { MusicAutoSave = autoSave.IsOn }, CancellationToken.None);
        StartPanel.Children.Add(autoSave);
        var saved = Library.List();
        if (saved.Count > 0)
        {
            StartPanel.Children.Add(new TextBlock { Text = "PROJETOS NESTE COMPUTADOR", Style = (Style)Application.Current.Resources["SectionTitleStyle"] });
            var list = new VariableSizedWrapGrid { Orientation = Orientation.Horizontal, ItemWidth = 260 };
            foreach (var item in saved)
            {
                var button = Card(item.Title, $"{item.Tracks} {(item.Tracks == 1 ? "faixa" : "faixas")} · {item.Updated:g}");
                AutomationProperties.SetName(button, $"Abrir {item.Title}");
                button.Click += (_, _) => Open(Library.Load(item.Id));
                var delete = new MenuFlyoutItem { Text = "Apagar este projeto" };
                delete.Click += async (_, _) =>
                {
                    if (await Confirm($"Apagar “{item.Title}” e as gravações dele deste computador? Não dá para desfazer."))
                    {
                        Library.Delete(item.Id);
                        ShowStart();
                    }
                };
                button.ContextFlyout = new MenuFlyout { Items = { delete } };
                list.Children.Add(button);
            }

            StartPanel.Children.Add(list);
        }
    }

    private static TextBlock Muted(string text) => new() { Text = text, TextWrapping = TextWrapping.Wrap, Foreground = (Brush)Application.Current.Resources["BrandTextMutedBrush"], FontSize = 13 };

    private static Button Card(string title, string detail) => new()
    {
        Margin = new Thickness(0, 0, 10, 10),
        HorizontalAlignment = HorizontalAlignment.Stretch,
        HorizontalContentAlignment = HorizontalAlignment.Left,
        Padding = new Thickness(14),
        Content = new StackPanel { Spacing = 4, Children = { new TextBlock { Text = title, FontWeight = Microsoft.UI.Text.FontWeights.SemiBold }, new TextBlock { Text = detail, TextWrapping = TextWrapping.Wrap, FontSize = 12, Foreground = (Brush)Application.Current.Resources["BrandTextMutedBrush"] } } }
    };

    private void Create(string templateId)
    {
        if (!LicenseGate.CanConvert(out var message))
        {
            ShowStart();
            return;
        }

        var template = MusicRules.Templates.First(t => t.Id == templateId);
        var p = MusicRules.FromTemplate(templateId, $"musica-{DateTime.Now:yyyyMMddHHmmss}-{Random.Shared.Next(1000):000}", template.Id == "vazio" ? "Nova música" : template.Label, Rate);
        if (App.Services.Settings.MusicAutoSave) Library.Save(p);
        Open(p);
        dirty = !App.Services.Settings.MusicAutoSave;
        if (dirty) BuildHeader();
        if (!string.IsNullOrEmpty(message)) Status(message, InfoBarSeverity.Informational);
    }

    private void OpenArchive()
    {
        var path = NativeFileDialog.PickFile(WinRT.Interop.WindowNative.GetWindowHandle(App.MainWindow), "Abrir projeto musical", [new("Projeto musical", ["sltmusic", "zip"])]);
        if (path is null) return;
        try
        {
            var temp = Path.Combine(Path.GetTempPath(), $"sltmusic-{Guid.NewGuid():N}");
            var opened = MusicArchive.Open(path, temp);
            var folder = Library.Folder(opened.Id);
            Directory.CreateDirectory(Path.Combine(folder, "midias"));
            foreach (var file in Directory.EnumerateFiles(Path.Combine(temp, "midias"))) File.Move(file, Path.Combine(folder, "midias", Path.GetFileName(file)), true);
            Directory.Delete(temp, true);
            Library.Save(opened);
            Open(opened);
        }
        catch (Exception e) when (e is IOException or Core.ConversionException or InvalidDataException or System.Text.Json.JsonException)
        {
            StartPanel.Children.Insert(0, new InfoBar { IsOpen = true, Severity = InfoBarSeverity.Error, Message = e.Message });
        }
    }

    // ==================== projeto ====================

    private void Open(MusicProject p)
    {
        project = p;
        dirty = false;
        undo.Clear();
        redo.Clear();
        selectedTrack = p.Tracks.FirstOrDefault()?.Id;
        selectedClip = null;
        StartView.Visibility = Visibility.Collapsed;
        EditorView.Visibility = Visibility.Visible;
        try
        {
            engine ??= new MusicAudioEngine(Rate);
            engine.Start();
        }
        catch (Exception e) when (e is System.Runtime.InteropServices.COMException or InvalidOperationException)
        {
            Status($"Não foi possível abrir a saída de áudio: {e.Message}", InfoBarSeverity.Error);
        }

        foreach (var media in p.Media) LoadMedia(media);
        engine?.SetProject(p);
        PrepareTransforms(p);
        BuildHeader();
        BuildTransport();
        BuildTimeline();
        BuildPanels();
        timer.Start();
        var loose = Library.LooseMedia(p);
        if (loose.Count > 0)
        {
            var recover = new Button { Content = "Recuperar" };
            recover.Click += (_, _) => RecoverLoose(loose);
            StatusBar.ActionButton = recover;
            Status($"{loose.Count} {(loose.Count == 1 ? "tomada ficou gravada" : "tomadas ficaram gravadas")} sem entrar no projeto (o aplicativo fechou no meio).", InfoBarSeverity.Warning);
        }
    }

    private void LoadMedia(MusicMedia media)
    {
        var path = Library.MediaPath(project!.Id, media.Id);
        if (!File.Exists(path)) return;
        engine?.SetSource(media.Id, new MappedWavSource(path));
        _ = MappedWavSource.WarmAsync(path);
        _ = Task.Run(() =>
        {
            using var source = new MappedWavSource(path);
            var columns = (int)((source.Frames + 255) / 256);
            var result = new float[columns * 2];
            var buffer = new float[256];
            for (var k = 0; k < columns; k++)
            {
                float min = 0, max = 0;
                for (var c = 0; c < source.Channels; c++)
                {
                    source.Copy(c, k * 256L, 256, buffer);
                    foreach (var v in buffer)
                    {
                        if (v < min) min = v;
                        if (v > max) max = v;
                    }
                }

                result[k * 2] = min;
                result[k * 2 + 1] = max;
            }

            return result;
        }).ContinueWith(t =>
        {
            if (t.IsCompletedSuccessfully) DispatcherQueue.TryEnqueue(() => { peaks[media.Id] = t.Result; BuildLanes(); });
        }, TaskScheduler.Default);
    }

    private void RecoverLoose(IReadOnlyList<(string Id, long Frames, int Channels)> loose)
    {
        Edit(p =>
        {
            var track = MusicRules.NewTrack($"faixa-{Guid.NewGuid():N}"[..20], "Tomadas recuperadas", p.Tracks.Count);
            foreach (var (id, frames, channels) in loose)
            {
                p.Media.Add(new MusicMedia { Id = id, Name = $"{id}.wav", SampleRate = p.SampleRate, Channels = channels, Frames = Math.Max(1, frames) });
                track.Clips.Add(new MusicClip { Id = $"clipe-{Guid.NewGuid():N}"[..22], Media = id, Duration = Math.Max(1, frames), Name = "Recuperada" });
            }

            p.Tracks.Add(track);
        });
        foreach (var (id, _, _) in loose) LoadMedia(project!.Media.First(m => m.Id == id));
        StatusBar.ActionButton = null;
        Status("As tomadas recuperadas estão numa faixa nova, no começo do projeto.", InfoBarSeverity.Success);
    }

    /// <summary>Toda edição passa aqui: guarda o passo, toca e aplica a preferência de salvamento.</summary>
    private void Edit(Action<MusicProject> change, string? merge = null, bool rebuild = true)
    {
        if (project is null) return;
        var now = DateTime.UtcNow;
        if (!(merge is not null && merge == mergeKey && (now - mergeAt).TotalMilliseconds < 800))
        {
            undo.Push(project.ToJson());
            if (undo.Count > 200) TrimUndo();
        }

        mergeKey = merge;
        mergeAt = now;
        redo.Clear();
        change(project);
        Applied(rebuild);
    }

    private void TrimUndo()
    {
        var keep = undo.Take(150).Reverse().ToList();
        undo.Clear();
        foreach (var item in keep) undo.Push(item);
    }

    private void Applied(bool rebuild)
    {
        dirty = true;
        leaveConfirmed = false;
        engine?.SetProject(project!);
        PrepareTransforms(project!);
        if (App.Services.Settings.MusicAutoSave)
        {
            saveTimer ??= CreateSaveTimer();
            saveTimer.Stop();
            saveTimer.Start();
        }
        else saveTimer?.Stop();
        if (rebuild)
        {
            BuildTimeline();
            BuildPanels();
            BuildHeader();
        }
    }

    private DispatcherTimer CreateSaveTimer()
    {
        var t = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(700) };
        t.Tick += (_, _) =>
        {
            t.Stop();
            SaveNow();
        };
        return t;
    }

    private void SaveNow()
    {
        try
        {
            if (project is not null) Library.Save(project);
            dirty = false;
        }
        catch (IOException e)
        {
            Status($"Não salvou: {e.Message}", InfoBarSeverity.Error);
        }
    }

    private async void PrepareTransforms(MusicProject p)
    {
        prepareWork?.Cancel();
        var clips = p.Tracks.SelectMany(t => t.Clips).Where(MusicTransform.IsTransformed).GroupBy(MusicTransform.SourceKey).Select(g => g.First()).ToList();
        if (clips.Count == 0) return;
        prepareWork = new CancellationTokenSource();
        var token = prepareWork.Token;
        try
        {
            await Task.Delay(250, token);
            var prepared = await Task.Run(() => clips.Select(clip =>
            {
                var key = MusicTransform.SourceKey(clip);
                if (transformedSources.TryGetValue(key, out var cached)) return (Key: key, Source: cached);
                using var original = new MappedWavSource(Library.MediaPath(p.Id, clip.Media));
                var channels = MusicTransform.ReadAll(original);
                return (Key: key, Source: (IMusicSource)new MemoryMusicSource(MusicTransform.Transform(channels, p.SampleRate, clip.Speed ?? 1, clip.Semitones ?? 0, clip.PreservePitch ?? true)));
            }).ToList(), token);
            if (token.IsCancellationRequested) return;
            foreach (var (key, source) in prepared)
            {
                transformedSources[key] = source;
                engine?.SetSource(key, source);
            }
        }
        catch (OperationCanceledException) { }
        catch (Exception e) when (e is IOException or Core.ConversionException)
        {
            Status($"Não foi possível preparar tempo e tom: {e.Message}", InfoBarSeverity.Error);
        }
    }

    private void Undo()
    {
        if (project is null || undo.Count == 0) return;
        redo.Push(project.ToJson());
        project = MusicProject.Parse(undo.Pop());
        mergeKey = null;
        Applied(true);
    }

    private void Redo()
    {
        if (project is null || redo.Count == 0) return;
        undo.Push(project.ToJson());
        project = MusicProject.Parse(redo.Pop());
        mergeKey = null;
        Applied(true);
    }

    private void Status(string message, InfoBarSeverity severity)
    {
        StatusBar.Message = message;
        StatusBar.Severity = severity;
        StatusBar.IsOpen = true;
    }

    private static string NewId(string prefix) => $"{prefix}-{Guid.NewGuid():N}"[..Math.Min(prefix.Length + 17, 40)];

    private async Task<bool> Confirm(string message)
    {
        var dialog = new ContentDialog { Title = "Confirmar", Content = message, PrimaryButtonText = "Sim", CloseButtonText = "Não", XamlRoot = XamlRoot };
        return await dialog.ShowAsync() == ContentDialogResult.Primary;
    }

    // ==================== cabeçalho e transporte ====================

    private void BuildHeader()
    {
        HeaderPanel.Children.Clear();
        var back = new Button { Content = "← Projetos" };
        AutomationProperties.SetName(back, "Voltar aos projetos");
        back.Click += async (_, _) =>
        {
            engine?.Stop();
            if (!App.Services.Settings.MusicAutoSave && dirty)
            {
                var choice = await AskToSave();
                if (choice == ContentDialogResult.None) { timer.Start(); return; }
                if (choice == ContentDialogResult.Primary) SaveNow();
            }
            else if (App.Services.Settings.MusicAutoSave) SaveNow();
            timer.Stop();
            project = null;
            ShowStart();
        };
        var title = new TextBox { Text = project!.Title, Width = 280, MaxLength = 80 };
        AutomationProperties.SetName(title, "Nome do projeto");
        title.TextChanged += (_, _) => Edit(p => p.Title = title.Text, "titulo", false);
        var undoButton = new Button { Content = "↶ Desfazer", IsEnabled = undo.Count > 0 };
        undoButton.Click += (_, _) => Undo();
        var redoButton = new Button { Content = "↷ Refazer", IsEnabled = redo.Count > 0 };
        redoButton.Click += (_, _) => Redo();
        var import = new Button { Content = "Importar áudio" };
        AutomationProperties.SetName(import, "Importar áudio");
        import.Click += async (_, _) => await ImportAsync();
        var save = new Button { Content = "Salvar .sltmusic" };
        save.Click += (_, _) => SaveArchive();
        HeaderPanel.Children.Add(back);
        HeaderPanel.Children.Add(title);
        HeaderPanel.Children.Add(undoButton);
        HeaderPanel.Children.Add(redoButton);
        HeaderPanel.Children.Add(import);
        HeaderPanel.Children.Add(save);
        var autoSave = new ToggleSwitch { OnContent = "Salvar automaticamente", OffContent = "Perguntar ao sair", IsOn = App.Services.Settings.MusicAutoSave };
        autoSave.Toggled += async (_, _) =>
        {
            await App.Services.ApplySettingsAsync(App.Services.Settings with { MusicAutoSave = autoSave.IsOn }, CancellationToken.None);
            if (autoSave.IsOn) { SaveNow(); BuildHeader(); }
        };
        HeaderPanel.Children.Add(autoSave);
        HeaderPanel.Children.Add(new TextBlock { Text = dirty ? "Alterações não salvas" : "Salvo neste computador", VerticalAlignment = VerticalAlignment.Center, Foreground = (Brush)Application.Current.Resources["BrandTextMutedBrush"], FontSize = 12 });
    }

    private async Task ImportAsync()
    {
        var files = NativeFileDialog.PickFiles(WinRT.Interop.WindowNative.GetWindowHandle(App.MainWindow), "Importar áudio", [new("Áudio", ["wav", "mp3", "flac", "m4a", "aac", "ogg", "opus", "wma", "aiff", "aif"])]);
        await ImportFilesAsync(files);
    }

    /// <summary>Cada arquivo vira uma faixa nova, no cursor. O original não é tocado.</summary>
    public async Task ImportFilesAsync(IReadOnlyList<string> files)
    {
        if (project is null) return;
        foreach (var file in files)
        {
            Status($"Importando {Path.GetFileName(file)}…", InfoBarSeverity.Informational);
            try
            {
                var mediaId = NewId("midia");
                var (frames, channels) = await Tools.ImportAsync(file, Library.MediaPath(project.Id, mediaId), project.SampleRate, CancellationToken.None);
                var start = engine?.Position ?? 0;
                var media = new MusicMedia { Id = mediaId, Name = Path.GetFileName(file), SampleRate = project.SampleRate, Channels = channels, Frames = frames, Origin = Path.GetFileName(file) };
                Edit(p =>
                {
                    p.Media.Add(media);
                    var name = Path.GetFileNameWithoutExtension(file);
                    var track = MusicRules.NewTrack(NewId("faixa"), name.Length > 60 ? name[..60] : name, p.Tracks.Count);
                    track.Clips.Add(new MusicClip { Id = NewId("clipe"), Media = mediaId, Start = start, Duration = frames, Name = track.Name });
                    p.Tracks.Add(track);
                });
                LoadMedia(media);
                StatusBar.IsOpen = false;
            }
            catch (Exception e) when (e is Core.ConversionException or IOException)
            {
                Status($"Não deu para importar {Path.GetFileName(file)}: {e.Message}", InfoBarSeverity.Error);
            }
        }
    }

    private void SaveArchive()
    {
        if (project is null) return;
        try
        {
            SaveNow();
            var folder = Path.Combine(App.Services.OutputDirectory, "Criação musical");
            Directory.CreateDirectory(folder);
            var path = Path.Combine(folder, $"{string.Concat(project.Title.Split(Path.GetInvalidFileNameChars()))}.sltmusic");
            MusicArchive.Save(project, m => Library.MediaPath(project.Id, m.Id), path);
            Status($"Projeto salvo em {path}", InfoBarSeverity.Success);
        }
        catch (Exception e) when (e is IOException or Core.ConversionException)
        {
            Status(e.Message, InfoBarSeverity.Error);
        }
    }

    private void BuildTransport()
    {
        TransportPanel.Children.Clear();
        var start = new Button { Content = "⏮" };
        AutomationProperties.SetName(start, "Voltar ao início");
        start.Click += (_, _) => engine?.Seek(0);
        playButton = new Button { Content = "▶", Style = (Style)Application.Current.Resources["BrandActionButtonStyle"], MinWidth = 48 };
        AutomationProperties.SetName(playButton, "Tocar");
        playButton.Click += (_, _) => PlayOrStop();
        recordButton = new Button { Content = "● REC", Foreground = new SolidColorBrush(Color.FromArgb(255, 255, 77, 77)) };
        AutomationProperties.SetName(recordButton, "Gravar");
        recordButton.Click += async (_, _) =>
        {
            if (engine?.IsRecording == true) engine.Stop();
            else await RecordAsync();
        };
        var loop = new ToggleButton { Content = "⟲ Loop", IsChecked = project!.Loop.Enabled };
        loop.Click += (_, _) => Edit(p =>
        {
            if (p.Loop.End <= p.Loop.Start)
            {
                p.Loop.Start = 0;
                p.Loop.End = Math.Max(MusicRules.Round(MusicRules.FramesPerBar(p) * 4), MusicRules.DurationInFrames(p));
            }

            p.Loop.Enabled = loop.IsChecked == true;
        });
        var metronome = new ToggleButton { Content = "♩ Metrônomo", IsChecked = project.Metronome.Enabled };
        metronome.Click += (_, _) => Edit(p => p.Metronome.Enabled = metronome.IsChecked == true, rebuild: false);
        var countIn = new ComboBox { Header = "Contagem", ItemsSource = new[] { "sem", "1 compasso", "2 compassos" }, SelectedIndex = (int)Math.Clamp(project.CountIn, 0, 2) };
        countIn.SelectionChanged += (_, _) => Edit(p => p.CountIn = countIn.SelectedIndex, rebuild: false);
        var bpm = new NumberBox { Header = "BPM", Minimum = MusicRules.MinBpm, Maximum = MusicRules.MaxBpm, Value = project.Bpm, SpinButtonPlacementMode = NumberBoxSpinButtonPlacementMode.Compact, Width = 110 };
        bpm.ValueChanged += (_, e) => { if (!double.IsNaN(e.NewValue)) Edit(p => p.Bpm = e.NewValue, "bpm"); };
        string[] meters = ["2/4", "3/4", "4/4", "5/4", "6/8", "7/8", "12/8"];
        var meter = new ComboBox { Header = "Compasso", ItemsSource = meters, SelectedItem = $"{project.Meter[0]}/{project.Meter[1]}" };
        meter.SelectionChanged += (_, _) =>
        {
            var parts = ((string)meter.SelectedItem).Split('/');
            Edit(p => p.Meter = [int.Parse(parts[0]), int.Parse(parts[1])]);
        };
        positionText = new TextBlock { FontFamily = new FontFamily("Consolas"), FontSize = 20, FontWeight = Microsoft.UI.Text.FontWeights.Bold };
        AutomationProperties.SetName(positionText, "Posição");
        timeText = new TextBlock { FontFamily = new FontFamily("Consolas"), FontSize = 12, Foreground = (Brush)Application.Current.Resources["BrandTextMutedBrush"] };
        var zoomSlider = new Slider { Header = "Zoom", Minimum = 10, Maximum = 400, Value = zoom, Width = 140 };
        zoomSlider.ValueChanged += (_, e) => { zoom = e.NewValue; BuildLanes(); };
        var snapBox = new CheckBox { Content = "Encaixar nas batidas", IsChecked = snap };
        snapBox.Click += (_, _) => snap = snapBox.IsChecked == true;
        foreach (var element in new UIElement[] { start, playButton, recordButton, loop, metronome, countIn, bpm, meter, new StackPanel { VerticalAlignment = VerticalAlignment.Center, Children = { positionText, timeText } }, zoomSlider, snapBox })
            TransportPanel.Children.Add(element);
    }

    private void PlayOrStop()
    {
        if (engine is null || project is null) return;
        if (engine.Playing) engine.Stop();
        else engine.Play(engine.Position, project.Metronome.Enabled);
    }

    private async Task RecordAsync()
    {
        if (engine is null || project is null || engine.Playing) return;
        if (!LicenseGate.CanConvert(out var license))
        {
            Status(license, InfoBarSeverity.Warning);
            return;
        }

        var armed = project.Tracks.Where(t => t.Arm).ToList();
        if (armed.Count != 1)
        {
            Status(armed.Count == 0 ? "Arme uma faixa (botão REC da faixa) antes de gravar." : "Grava uma faixa por vez: deixe só uma armada.", InfoBarSeverity.Warning);
            return;
        }

        if (engine.Input is null)
        {
            Status("Escolha a entrada de áudio na aba Gravação antes de gravar.", InfoBarSeverity.Warning);
            Panels.SelectedIndex = 2;
            return;
        }

        var track = armed[0];
        var latency = project.Latency.Measured ? project.Latency : new MusicLatency { Input = engine.Input.LatencyFrames, Output = engine.OutputLatencyFrames, Adjust = project.Latency.Adjust };
        var forPlan = project.Clone();
        forPlan.Latency = latency;
        var plan = MusicRules.RecordingPlan(forPlan, engine.Position);
        var mediaId = NewId("tomada");
        var channels = track.InputChannels == 2 && engine.Input.Channels >= 2 ? 2 : 1;
        var writer = new StreamingWavWriter(Library.MediaPath(project.Id, mediaId), channels, Rate);
        var queue = new System.Collections.Concurrent.BlockingCollection<float[]>();
        var writing = Task.Run(() =>
        {
            foreach (var part in queue.GetConsumingEnumerable()) writer.WriteInterleaved(part);
        });
        recordButton!.Content = "■ Parar";
        Status($"Gravando em {track.Name}: a tomada vai direto para o disco.", InfoBarSeverity.Informational);
        var take = await engine.RecordAsync(plan, channels, project.Metronome.Enabled, queue.Add);
        queue.CompleteAdding();
        await writing;
        writer.Dispose();
        recordButton.Content = "● REC";
        var clip = MusicRules.ClipFromTake(forPlan, NewId("clipe"), mediaId, take.AlignedFrame, writer.Frames, plan);
        if (clip is null)
        {
            Status("A gravação parou antes do ponto de entrada: nada foi acrescentado (o arquivo ficou guardado).", InfoBarSeverity.Warning);
            return;
        }

        var media = new MusicMedia { Id = mediaId, Name = $"{track.Name} — tomada.wav", SampleRate = Rate, Channels = channels, Frames = writer.Frames };
        Edit(p =>
        {
            p.Latency = latency;
            p.Media.Add(media);
            var t = p.Tracks.First(x => x.Id == track.Id);
            clip.Name = $"Tomada {t.Clips.Count + 1}";
            MusicRules.AddTake(t, clip);
        });
        LoadMedia(media);
        StatusBar.IsOpen = false;
    }

    private void Tick()
    {
        if (engine is null || project is null || positionText is null) return;
        var position = engine.Position;
        positionText.Text = MusicRules.Position(project, position).Text;
        timeText!.Text = MusicRules.TimeOfFrame(project, position);
        playButton!.Content = engine.Playing ? "⏹" : "▶";
        AutomationProperties.SetName(playButton, engine.Playing ? "Parar" : "Tocar");
        if (playhead is not null) Canvas.SetLeft(playhead, X(position));
        var meters = engine.Meters;
        foreach (var (id, bar) in trackMeters) bar.Value = meters.TryGetValue(id, out var m) ? Level(Math.Max(m[0], m[1])) : 0;
        foreach (var (id, (l, r)) in stripMeters)
        {
            l.Height = (meters.TryGetValue(id, out var m) ? Level(m[0]) : 0) * 1.3;
            r.Height = (meters.TryGetValue(id, out var n) ? Level(n[1]) : 0) * 1.3;
        }
    }

    private static double Level(float peak) => Math.Clamp((20 * Math.Log10(peak + 1e-9) + 60) / 60 * 100, 0, 100);

    private double X(long frame) => frame / (double)Rate * zoom;

    private long Snap(double frame)
    {
        if (!snap) return Math.Max(0, (long)Math.Round(frame));
        var beat = MusicRules.FramesPerBeat(project!);
        return Math.Max(0, MusicRules.BeatFrame(project!, (long)Math.Round(frame / beat)));
    }

    // ==================== linha do tempo ====================

    private void BuildTimeline()
    {
        TimelineGrid.Children.Clear();
        TimelineGrid.ColumnDefinitions.Clear();
        TimelineGrid.RowDefinitions.Clear();
        TimelineGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(HeaderWidth) });
        TimelineGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        trackMeters.Clear();
        var headers = new StackPanel();
        var add = new Button { Content = "+ Faixa", Margin = new Thickness(6), Height = 24, Padding = new Thickness(8, 0, 8, 0) };
        AutomationProperties.SetName(add, "Nova faixa");
        add.Click += (_, _) => Edit(p => p.Tracks.Add(MusicRules.NewTrack(NewId("faixa"), $"Faixa {p.Tracks.Count + 1}", p.Tracks.Count)));
        headers.Children.Add(new Border { Height = 36, Child = add });
        foreach (var track in project!.Tracks.Where(t => t.Hidden != true)) headers.Children.Add(TrackHeader(track));
        headersScroll = new ScrollViewer { Content = headers, VerticalScrollBarVisibility = ScrollBarVisibility.Hidden, VerticalScrollMode = ScrollMode.Enabled };
        lanesCanvas = new Canvas();
        lanesScroll = new ScrollViewer { Content = lanesCanvas, HorizontalScrollBarVisibility = ScrollBarVisibility.Visible, HorizontalScrollMode = ScrollMode.Enabled, VerticalScrollBarVisibility = ScrollBarVisibility.Auto };
        lanesScroll.ViewChanged += (_, _) => headersScroll.ChangeView(null, lanesScroll.VerticalOffset, null, true);
        Grid.SetColumn(lanesScroll, 1);
        TimelineGrid.Children.Add(headersScroll);
        TimelineGrid.Children.Add(lanesScroll);
        BuildLanes();
        BuildClipPanel();
    }

    private UIElement TrackHeader(MusicTrack track)
    {
        var color = ParseColor(track.Color);
        var panel = new StackPanel { Spacing = 3, Padding = new Thickness(8, 4, 8, 4), Height = RowHeight, BorderThickness = new Thickness(4, 0, 0, 1), BorderBrush = new SolidColorBrush(color), Background = (Brush)Application.Current.Resources[track.Id == selectedTrack ? "BrandSurfaceAltBrush" : "BrandSurfaceBrush"] };
        AutomationProperties.SetName(panel, $"Faixa {track.Name}");
        var name = new TextBox { Text = track.Name, MaxLength = 60, Padding = new Thickness(4, 2, 4, 2), MinHeight = 26 };
        AutomationProperties.SetName(name, $"Nome da faixa {track.Name}");
        name.TextChanged += (_, _) => Edit(p => Track(p, track.Id).Name = name.Text, $"nome-{track.Id}", false);
        name.GotFocus += (_, _) => { selectedTrack = track.Id; };
        var more = new Button { Content = "⋯", Padding = new Thickness(8, 0, 8, 0) };
        AutomationProperties.SetName(more, $"Mais ações de {track.Name}");
        more.Flyout = TrackMenu(track, () => name.Focus(FocusState.Programmatic));
        panel.ContextFlyout = TrackMenu(track, () => name.Focus(FocusState.Programmatic));
        var row1 = new Grid { ColumnDefinitions = { new ColumnDefinition(), new ColumnDefinition { Width = GridLength.Auto } } };
        Grid.SetColumn(more, 1);
        row1.Children.Add(name);
        row1.Children.Add(more);
        ToggleButton Mini(string text, bool on, string label, Action<MusicTrack, bool> set, bool rebuild = false)
        {
            var b = new ToggleButton { Content = text, IsChecked = on, Padding = new Thickness(6, 1, 6, 1), MinWidth = 34, FontSize = 11 };
            AutomationProperties.SetName(b, $"{label} {track.Name}");
            b.Click += (_, _) => Edit(p => set(Track(p, track.Id), b.IsChecked == true), rebuild: rebuild);
            return b;
        }

        var meter = new ProgressBar { Width = 60, Maximum = 100, VerticalAlignment = VerticalAlignment.Center };
        trackMeters[track.Id] = meter;
        var row2 = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 3 };
        row2.Children.Add(Mini("REC", track.Arm, "Armar", (t, v) =>
        {
            foreach (var other in project!.Tracks) other.Arm = false;
            t.Arm = v;
        }, true));
        row2.Children.Add(Mini("MON", track.Monitor, "Monitorar", (t, v) => t.Monitor = v));
        row2.Children.Add(Mini("S", track.Solo, "Solo", (t, v) => t.Solo = v));
        row2.Children.Add(Mini("M", track.Mute, "Mudo", (t, v) => t.Mute = v));
        var fx = new Button { Content = track.Effects.Count > 0 ? $"FX {track.Effects.Count}" : "FX", Padding = new Thickness(6, 1, 6, 1), FontSize = 11 };
        fx.Click += (_, _) => { effectsTarget = track.Id; BuildPanels(); Panels.SelectedIndex = 1; };
        row2.Children.Add(fx);
        row2.Children.Add(meter);
        var volume = new Slider { Minimum = 0, Maximum = 100, StepFrequency = 1, Value = MusicRules.VolumePercent(track.GainDb) };
        volume.ValueChanged += (_, e) => Edit(p => Track(p, track.Id).GainDb = MusicRules.VolumeDb(e.NewValue), $"vol-{track.Id}", false);
        var pan = new Slider { Minimum = -100, Maximum = 100, Value = track.Pan };
        pan.ValueChanged += (_, e) => Edit(p => Track(p, track.Id).Pan = e.NewValue, $"pan-{track.Id}", false);
        var row3 = new StackPanel { Spacing = 0, Children = {
            new Grid { ColumnDefinitions = { new ColumnDefinition { Width = new GridLength(68) }, new ColumnDefinition() }, Children = { new TextBlock { Text = $"Vol {MusicRules.VolumePercent(track.GainDb)}", FontSize = 11, VerticalAlignment = VerticalAlignment.Center }, Column1(Fine(volume, 1, false, $"volume de {track.Name}")) } },
            new Grid { ColumnDefinitions = { new ColumnDefinition { Width = new GridLength(100) }, new ColumnDefinition() }, Children = { new TextBlock { Text = MusicRules.PanText(track.Pan), FontSize = 11, VerticalAlignment = VerticalAlignment.Center }, Column1(Fine(pan, MusicRules.StepPan, false, $"pan de {track.Name}")) } },
        } };
        panel.Children.Add(row1);
        panel.Children.Add(row2);
        panel.Children.Add(row3);
        panel.Tapped += (_, _) => { selectedTrack = track.Id; selectedClip = null; BuildClipPanel(); };
        return panel;
    }

    private MenuFlyout TrackMenu(MusicTrack track, Action? rename = null, string? clipId = null)
    {
        var menu = new MenuFlyout();
        void Item(string text, Action action)
        {
            var item = new MenuFlyoutItem { Text = text };
            item.Click += (_, _) => action();
            menu.Items.Add(item);
        }

        var targets = track.Clips.Where(c => clipId is null || clipId == c.Id).Select(c =>
        {
            var media = project!.Media.Find(m => m.Id == c.Media);
            var restored = media?.Restoration is not null;
            var target = restored ? media!.Origin : project.Media.Find(m => m.Origin == media?.Id && m.Restoration is not null)?.Id;
            return (Clip: c, Target: target, Restored: restored);
        }).Where(x => x.Target is not null).ToArray();
        foreach (var (clip, target, restored) in targets)
        {
            var suffix = targets.Length > 1 ? $" · {clip.Name ?? "Clipe"}" : "";
            Item($"Alternar para versão {(restored ? "bruta" : "restaurada")}{suffix}", () =>
                Edit(p => Track(p, track.Id).Clips.Find(c => c.Id == clip.Id)!.Media = target!));
        }
        if (targets.Length > 0) menu.Items.Add(new MenuFlyoutSeparator());
        Item(track.Mute ? "Desmutar faixa" : "Mutar faixa", () => Edit(p => Track(p, track.Id).Mute = !track.Mute));
        Item(track.Solo ? "Desativar solo" : "Ouvir em solo", () => Edit(p => Track(p, track.Id).Solo = !track.Solo));
        if (rename is not null) Item("Renomear faixa", rename);
        Item("Propriedades", () =>
        {
            selectedTrack = track.Id;
            selectedClip = clipId ?? track.Clips.FirstOrDefault()?.Id;
            BuildLanes();
            BuildClipPanel();
        });
        Item("Duplicar", () => Edit(p =>
        {
            var i = p.Tracks.FindIndex(t => t.Id == track.Id);
            var copy = MusicProject.Parse(p.ToJson()).Tracks[i];
            copy.Id = NewId("faixa");
            copy.Name = $"{track.Name} (cópia)";
            copy.Arm = false;
            copy.Solo = false;
            foreach (var c in copy.Clips) c.Id = NewId("clipe");
            p.Tracks.Insert(i + 1, copy);
        }));
        Item("Mover para cima", () => Edit(p => Move(p, track.Id, -1)));
        Item("Mover para baixo", () => Edit(p => Move(p, track.Id, 1)));
        Item("Filtros e efeitos", () => { effectsTarget = track.Id; BuildPanels(); Panels.SelectedIndex = 1; });
        Item("Criar barramento para ela", () => Edit(p =>
        {
            var bus = new MusicBus { Id = NewId("bus"), Name = $"Bus {track.Name}" };
            p.Buses.Add(bus);
            Track(p, track.Id).Output = bus.Id;
        }));
        Item("Exportar só esta faixa", () => { selectedTrack = track.Id; BuildPanels(); Panels.SelectedIndex = 5; });
        Item("Ocultar", () => Edit(p => Track(p, track.Id).Hidden = true));
        Item("Remover", async () =>
        {
            if (await Confirm($"Remover a faixa “{track.Name}”? As gravações continuam guardadas e voltam com Desfazer.")) Edit(p => p.Tracks.RemoveAll(t => t.Id == track.Id));
        });
        var hidden = project!.Tracks.Where(t => t.Hidden == true).ToList();
        if (hidden.Count > 0)
        {
            menu.Items.Add(new MenuFlyoutSeparator());
            foreach (var h in hidden) Item($"Mostrar {h.Name}", () => Edit(p => Track(p, h.Id).Hidden = false));
        }

        return menu;
    }

    private static void Move(MusicProject p, string id, int delta)
    {
        var i = p.Tracks.FindIndex(t => t.Id == id);
        var j = i + delta;
        if (j < 0 || j >= p.Tracks.Count) return;
        (p.Tracks[i], p.Tracks[j]) = (p.Tracks[j], p.Tracks[i]);
    }

    private static MusicTrack Track(MusicProject p, string id) => p.Tracks.First(t => t.Id == id);

    private static Color ParseColor(string? hex)
    {
        if (hex is not { Length: 7 }) return Color.FromArgb(255, 255, 122, 24);
        return Color.FromArgb(255, Convert.ToByte(hex[1..3], 16), Convert.ToByte(hex[3..5], 16), Convert.ToByte(hex[5..7], 16));
    }

    private void BuildLanes()
    {
        if (lanesCanvas is null || project is null) return;
        lanesCanvas.Children.Clear();
        var total = Math.Max(MusicRules.DurationInFrames(project) + Rate * 30L, Rate * 60L);
        var width = X(total);
        var visible = project.Tracks.Where(t => t.Hidden != true).ToList();
        lanesCanvas.Width = width;
        lanesCanvas.Height = 36 + visible.Count * RowHeight;
        // Régua: compassos.
        var ruler = new Canvas { Width = width, Height = 36, Background = (Brush)Application.Current.Resources["BrandSurfaceAltBrush"] };
        AutomationProperties.SetName(ruler, "Régua");
        var perBar = MusicRules.FramesPerBar(project);
        var step = Math.Max(1, (int)Math.Ceiling(40 / X((long)perBar)));
        for (var i = 0; i * perBar < total; i += step)
        {
            var label = new TextBlock { Text = (i + 1).ToString(System.Globalization.CultureInfo.InvariantCulture), FontSize = 11, Foreground = (Brush)Application.Current.Resources["BrandTextMutedBrush"] };
            Canvas.SetLeft(label, X((long)(i * perBar)) + 3);
            Canvas.SetTop(label, 10);
            ruler.Children.Add(label);
            var line = new Rectangle { Width = 1, Height = 36, Fill = (Brush)Application.Current.Resources["BrandStrokeBrush"] };
            Canvas.SetLeft(line, X((long)(i * perBar)));
            ruler.Children.Add(line);
        }

        if (project.Loop.End > project.Loop.Start)
        {
            var loop = new Rectangle { Width = X(project.Loop.End - project.Loop.Start), Height = 36, Fill = new SolidColorBrush(Color.FromArgb(project.Loop.Enabled ? (byte)64 : (byte)24, 255, 193, 7)) };
            Canvas.SetLeft(loop, X(project.Loop.Start));
            ruler.Children.Insert(0, loop);
        }

        ruler.PointerPressed += (_, e) => engine?.Seek(Snap(e.GetCurrentPoint(ruler).Position.X / zoom * Rate));
        lanesCanvas.Children.Add(ruler);
        for (var r = 0; r < visible.Count; r++)
        {
            var track = visible[r];
            var top = 36 + r * RowHeight;
            var lane = new Rectangle { Width = width, Height = RowHeight - 1, Fill = new SolidColorBrush(Color.FromArgb(track.Mute ? (byte)10 : (byte)18, 255, 255, 255)) };
            lane.ContextFlyout = TrackMenu(track);
            Canvas.SetTop(lane, top);
            lane.PointerPressed += (_, e) =>
            {
                var ponto = e.GetCurrentPoint(lanesCanvas);
                if (!ponto.Properties.IsRightButtonPressed) engine?.Seek(Snap(ponto.Position.X / zoom * Rate));
            };
            lanesCanvas.Children.Add(lane);
            foreach (var clip in track.Clips) lanesCanvas.Children.Add(ClipView(track, clip, top));
        }

        playhead = new Rectangle { Width = 2, Height = lanesCanvas.Height, Fill = (Brush)Application.Current.Resources["BrandOrangeBrush"], IsHitTestVisible = false };
        Canvas.SetLeft(playhead, X(engine?.Position ?? 0));
        lanesCanvas.Children.Add(playhead);
    }

    private Border ClipView(MusicTrack track, MusicClip clip, double top)
    {
        var color = ParseColor(track.Color);
        var width = Math.Max(3, X(clip.Duration));
        var grid = new Grid();
        if (peaks.TryGetValue(clip.Media, out var p))
        {
            var points = new PointCollection();
            var steps = (int)Math.Min(4000, width);
            var height = RowHeight - 30;
            var lows = new List<Point>();
            for (var x = 0; x < steps; x++)
            {
                var from = clip.SourceStart + x / (double)steps * clip.Duration * (clip.Speed ?? 1);
                var to = clip.SourceStart + (x + 1) / (double)steps * clip.Duration * (clip.Speed ?? 1);
                float min = 0, max = 0;
                for (var k = (int)(from / 256); k <= Math.Min(p.Length / 2 - 1, (int)(to / 256)); k++)
                {
                    min = Math.Min(min, p[k * 2]);
                    max = Math.Max(max, p[k * 2 + 1]);
                }

                var px = x * width / steps;
                points.Add(new Point(px, (1 - max) / 2 * height));
                lows.Add(new Point(px, (1 - min) / 2 * height));
            }

            for (var i = lows.Count - 1; i >= 0; i--) points.Add(lows[i]);
            grid.Children.Add(new Polygon { Points = points, Fill = new SolidColorBrush(color), Margin = new Thickness(0, 16, 0, 0) });
        }

        grid.Children.Add(new TextBlock { Text = (clip.Name ?? "Clipe") + (clip.Mute ? " (mudo)" : ""), FontSize = 11, Margin = new Thickness(4, 1, 0, 0) });
        var border = new Border
        {
            Width = width,
            Height = RowHeight - 12,
            CornerRadius = new CornerRadius(6),
            BorderThickness = new Thickness(clip.Id == selectedClip ? 2 : 1),
            BorderBrush = clip.Id == selectedClip ? new SolidColorBrush(Colors.White) : new SolidColorBrush(color),
            Background = new SolidColorBrush(Color.FromArgb(20, 255, 255, 255)),
            Opacity = clip.Mute ? 0.4 : 1,
            Child = grid,
            ContextFlyout = TrackMenu(track, clipId: clip.Id),
        };
        AutomationProperties.SetName(border, $"Clipe {clip.Name ?? clip.Id}");
        Canvas.SetLeft(border, X(clip.Start));
        Canvas.SetTop(border, top + 6);
        double? startX = null;
        var startFrame = clip.Start;
        border.PointerPressed += (_, e) =>
        {
            if (e.GetCurrentPoint(border).Properties.IsRightButtonPressed) return;
            selectedClip = clip.Id;
            selectedTrack = track.Id;
            startX = e.GetCurrentPoint(lanesCanvas).Position.X;
            startFrame = clip.Start;
            border.CapturePointer(e.Pointer);
            e.Handled = true;
        };
        border.PointerMoved += (_, e) =>
        {
            if (startX is null || engine?.IsRecording == true) return;
            var frame = Snap(startFrame + (e.GetCurrentPoint(lanesCanvas).Position.X - startX.Value) / zoom * Rate);
            Canvas.SetLeft(border, X(frame));
        };
        border.PointerReleased += (_, e) =>
        {
            if (startX is null) return;
            var frame = Snap(startFrame + (e.GetCurrentPoint(lanesCanvas).Position.X - startX.Value) / zoom * Rate);
            startX = null;
            border.ReleasePointerCapture(e.Pointer);
            if (frame != clip.Start) Edit(p => p.Tracks.First(t => t.Id == track.Id).Clips.First(c => c.Id == clip.Id).Start = frame);
            else { BuildLanes(); BuildClipPanel(); }
        };
        return border;
    }

    private static FrameworkElement Column1(FrameworkElement element)
    {
        Grid.SetColumn(element, 1);
        return element;
    }

    private void BuildClipPanel()
    {
        ClipPanel.Children.Clear();
        if (project is null) return;
        var track = project.Tracks.FirstOrDefault(t => t.Clips.Any(c => c.Id == selectedClip));
        var clip = track?.Clips.First(c => c.Id == selectedClip);
        if (track is null || clip is null)
        {
            var selected = project.Tracks.FirstOrDefault(t => t.Id == selectedTrack);
            if (selected is null) return;
            ClipPanel.Children.Add(new TextBlock { Text = selected.Name, FontWeight = Microsoft.UI.Text.FontWeights.SemiBold, VerticalAlignment = VerticalAlignment.Center });
            var left = new NumberBox { Header = "Ajuste L (dB)", Minimum = MusicRules.MinTrimDb, Maximum = MusicRules.MaxTrimDb, Value = selected.LeftTrimDb, SmallChange = 0.5, SpinButtonPlacementMode = NumberBoxSpinButtonPlacementMode.Compact };
            left.ValueChanged += (_, e) => { if (!double.IsNaN(e.NewValue)) Edit(p => Track(p, selected.Id).LeftTrimDb = e.NewValue, "ajL", false); };
            var right = new NumberBox { Header = "Ajuste R (dB)", Minimum = MusicRules.MinTrimDb, Maximum = MusicRules.MaxTrimDb, Value = selected.RightTrimDb, SmallChange = 0.5, SpinButtonPlacementMode = NumberBoxSpinButtonPlacementMode.Compact };
            right.ValueChanged += (_, e) => { if (!double.IsNaN(e.NewValue)) Edit(p => Track(p, selected.Id).RightTrimDb = e.NewValue, "ajR", false); };
            var input = new ComboBox { Header = "Entrada", ItemsSource = new[] { "Mono", "Estéreo" }, SelectedIndex = selected.InputChannels == 2 ? 1 : 0 };
            input.SelectionChanged += (_, _) => Edit(p => Track(p, selected.Id).InputChannels = input.SelectedIndex + 1, rebuild: false);
            ClipPanel.Children.Add(left);
            ClipPanel.Children.Add(right);
            ClipPanel.Children.Add(input);
            ClipPanel.Children.Add(new TextBlock { Text = "O pan move a faixa no estéreo; o ajuste L/R corrige o nível de cada canal sozinho.", VerticalAlignment = VerticalAlignment.Center, Foreground = (Brush)Application.Current.Resources["BrandTextMutedBrush"], FontSize = 12 });
            return;
        }

        void ClipEdit(Action<MusicClip> change, string? merge = null) => Edit(p => change(p.Tracks.First(t => t.Id == track.Id).Clips.First(c => c.Id == clip.Id)), merge);
        ClipPanel.Children.Add(new TextBlock { Text = clip.Name ?? "Clipe", FontWeight = Microsoft.UI.Text.FontWeights.SemiBold, VerticalAlignment = VerticalAlignment.Center });
        var start = new NumberBox { Header = "Início (s)", Value = Math.Round(clip.Start / (double)Rate, 3), Minimum = 0, SmallChange = 0.01, SpinButtonPlacementMode = NumberBoxSpinButtonPlacementMode.Compact };
        start.ValueChanged += (_, e) => { if (!double.IsNaN(e.NewValue)) ClipEdit(c => c.Start = Math.Max(0, MusicRules.Round(e.NewValue * Rate)), "inicio"); };
        var gain = new NumberBox { Header = "Ganho (dB)", Value = clip.GainDb, Minimum = -60, Maximum = 24, SmallChange = 0.5, SpinButtonPlacementMode = NumberBoxSpinButtonPlacementMode.Compact };
        gain.ValueChanged += (_, e) => { if (!double.IsNaN(e.NewValue)) ClipEdit(c => c.GainDb = e.NewValue, "ganho"); };
        var fadeIn = new NumberBox { Header = "Entrada suave (ms)", Value = Math.Round(clip.FadeIn * 1000.0 / Rate), Minimum = 0, SpinButtonPlacementMode = NumberBoxSpinButtonPlacementMode.Compact };
        fadeIn.ValueChanged += (_, e) => { if (!double.IsNaN(e.NewValue)) ClipEdit(c => c.FadeIn = Math.Min(c.Duration - c.FadeOut, MusicRules.Round(e.NewValue / 1000 * Rate)), "fadein"); };
        var fadeOut = new NumberBox { Header = "Saída suave (ms)", Value = Math.Round(clip.FadeOut * 1000.0 / Rate), Minimum = 0, SpinButtonPlacementMode = NumberBoxSpinButtonPlacementMode.Compact };
        fadeOut.ValueChanged += (_, e) => { if (!double.IsNaN(e.NewValue)) ClipEdit(c => c.FadeOut = Math.Min(c.Duration - c.FadeIn, MusicRules.Round(e.NewValue / 1000 * Rate)), "fadeout"); };
        var active = new CheckBox { Content = "Tomada ativa", IsChecked = !clip.Mute };
        active.Click += (_, _) => ClipEdit(c => c.Mute = active.IsChecked != true);
        var split = new Button { Content = "Dividir no cursor" };
        split.Click += (_, _) => Split(track.Id, clip.Id);
        var remove = new Button { Content = "Remover clipe" };
        remove.Click += (_, _) =>
        {
            selectedClip = null;
            Edit(p => p.Tracks.First(t => t.Id == track.Id).Clips.RemoveAll(c => c.Id == clip.Id));
        };
        var noise = new Button { Content = "Reduzir ruído deste clipe" };
        ToolTipService.SetToolTip(noise, "Para gravações com chiado, ventilador ou ar-condicionado ao fundo: aprende o ruído nos trechos mais silenciosos e o tira do clipe todo. Numa música já mixada quase nunca ajuda. A original fica guardada (Desfazer volta).");
        noise.Click += async (_, _) =>
        {
            noise.IsEnabled = false;
            Status("Reduzindo o ruído do clipe…", InfoBarSeverity.Informational);
            try
            {
                var mediaId = NewId("limpa");
                await MusicTools.ReduceNoiseAsync(Library.MediaPath(project.Id, clip.Media), Library.MediaPath(project.Id, mediaId), Rate, CancellationToken.None);
                using var wav = new MappedWavSource(Library.MediaPath(project.Id, mediaId));
                var media = new MusicMedia { Id = mediaId, Name = "Sem ruído.wav", SampleRate = Rate, Channels = 2, Frames = wav.Frames, Origin = clip.Media };
                Edit(p =>
                {
                    p.Media.Add(media);
                    var c = p.Tracks.First(t => t.Id == track.Id).Clips.First(x => x.Id == clip.Id);
                    c.Media = mediaId;
                    c.Name = $"{c.Name ?? "Clipe"} (sem ruído)";
                });
                LoadMedia(media);
                Status("Ruído reduzido neste clipe. A gravação original continua guardada: Desfazer volta para ela.", InfoBarSeverity.Success);
            }
            catch (Exception e) when (e is IOException or Core.ConversionException)
            {
                Status(e.Message, InfoBarSeverity.Error);
            }
            finally
            {
                noise.IsEnabled = true;
            }
        };
        foreach (var element in new UIElement[] { start, gain, fadeIn, fadeOut, active, split, noise, remove }) ClipPanel.Children.Add(element);
    }

    private void Split(string trackId, string clipId)
    {
        var cut = engine?.Position ?? 0;
        Edit(p =>
        {
            var t = p.Tracks.First(x => x.Id == trackId);
            var c = t.Clips.First(x => x.Id == clipId);
            if (cut <= c.Start || cut >= c.Start + c.Duration) return;
            var left = cut - c.Start;
            var b = c.Copy();
            b.Id = NewId("clipe");
            b.Start = cut;
            b.SourceStart = c.SourceStart + MusicRules.Round(left * (c.Speed ?? 1));
            b.Duration = c.Duration - left;
            b.FadeIn = 0;
            b.FadeOut = Math.Min(c.FadeOut, b.Duration);
            c.Duration = left;
            c.FadeOut = 0;
            c.FadeIn = Math.Min(c.FadeIn, left);
            t.Clips.Insert(t.Clips.IndexOf(c) + 1, b);
        });
    }

    /// <summary>Atalhos: espaço toca/para, R grava, Ctrl+Z/Ctrl+Y.</summary>
    protected override void OnKeyDown(Microsoft.UI.Xaml.Input.KeyRoutedEventArgs e)
    {
        if (project is not null && e.OriginalSource is not TextBox and not NumberBox)
        {
            var ctrl = InputKeyboardSource.GetKeyStateForCurrentThread(Windows.System.VirtualKey.Control).HasFlag(Windows.UI.Core.CoreVirtualKeyStates.Down);
            switch (e.Key)
            {
                case Windows.System.VirtualKey.Space:
                    PlayOrStop();
                    e.Handled = true;
                    return;
                case Windows.System.VirtualKey.Z when ctrl:
                    Undo();
                    e.Handled = true;
                    return;
                case Windows.System.VirtualKey.Y when ctrl:
                    Redo();
                    e.Handled = true;
                    return;
                case Windows.System.VirtualKey.R when !ctrl:
                    _ = RecordAsync();
                    e.Handled = true;
                    return;
            }
        }

        base.OnKeyDown(e);
    }
}
