using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;
using Microsoft.UI.Xaml.Shapes;
using SmellsLikeTech.Converter.Desktop.Services;
using Path = System.IO.Path;
using Microsoft.UI.Xaml.Media;
using SmellsLikeTech.Converter.Core.Music;
using SmellsLikeTech.Converter.Engine.Music;

namespace SmellsLikeTech.Converter.Desktop.Pages;

/// <summary>Os painéis de baixo: mixer, efeitos, gravação, separação e exportação.</summary>
public sealed partial class MusicPage
{
    private void BuildPanels()
    {
        if (project is null) return;
        var selected = Math.Max(0, Panels.SelectedIndex);
        Panels.TabItems.Clear();
        stripMeters.Clear();
        Panels.TabItems.Add(Tab("Mixer", MixerView()));
        Panels.TabItems.Add(Tab("Efeitos", EffectsView()));
        Panels.TabItems.Add(Tab("Gravação", RecordingView()));
        Panels.TabItems.Add(Tab("Separar por IA", SeparationView()));
        Panels.TabItems.Add(Tab("Exportar", ExportView()));
        Panels.SelectedIndex = Math.Min(selected, Panels.TabItems.Count - 1);
    }

    private static TabViewItem Tab(string header, UIElement content)
    {
        var item = new TabViewItem { Header = header, IsClosable = false, Content = new ScrollViewer { Content = content, Padding = new Thickness(10), HorizontalScrollBarVisibility = ScrollBarVisibility.Auto } };
        AutomationProperties.SetName(item, header);
        return item;
    }

    private static TextBlock Label(string text) => new() { Text = text, FontSize = 12, Foreground = (Brush)Application.Current.Resources["BrandTextMutedBrush"], TextWrapping = TextWrapping.Wrap };

    private static string Db(double v) => v <= MusicRules.MinGainDb ? "−∞" : $"{(v > 0 ? "+" : "")}{v:0.0}";

    // ==================== mixer ====================

    private UIElement MixerView()
    {
        var row = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        foreach (var track in project!.Tracks.Where(t => t.Hidden != true)) row.Children.Add(Strip(track.Id, track.Name, track.GainDb, track.Pan, track.Effects.Count, track.Mute, track.Solo, isTrack: true, track));
        foreach (var bus in project.Buses) row.Children.Add(Strip(bus.Id, bus.Name, bus.GainDb, bus.Pan, bus.Effects.Count, bus.Mute, false, isTrack: false, null));
        row.Children.Add(Strip("master", "Master", project.Master.GainDb, 0, project.Master.Effects.Count, false, false, isTrack: false, null));
        var addBus = new Button { Content = "+ Barramento", VerticalAlignment = VerticalAlignment.Top };
        addBus.Click += (_, _) => Edit(p => p.Buses.Add(new MusicBus { Id = NewId("bus"), Name = $"Bus {p.Buses.Count + 1}" }));
        row.Children.Add(addBus);
        return row;
    }

    private UIElement Strip(string id, string name, double gainDb, double pan, int effects, bool mute, bool solo, bool isTrack, MusicTrack? track)
    {
        var panel = new StackPanel { Width = 150, Spacing = 4, Padding = new Thickness(8), CornerRadius = new CornerRadius(8), Background = (Brush)Application.Current.Resources["BrandSurfaceAltBrush"] };
        AutomationProperties.SetName(panel, $"Canal {name}");
        panel.Children.Add(new TextBlock { Text = name, FontWeight = Microsoft.UI.Text.FontWeights.SemiBold, TextTrimming = TextTrimming.CharacterEllipsis });
        void Set(Action<MusicProject, double> setter, double value, string key) => Edit(p => setter(p, value), key, false);
        var buttons = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 3 };
        if (id != "master")
        {
            var m = new ToggleButton { Content = "M", IsChecked = mute, Padding = new Thickness(6, 1, 6, 1) };
            m.Click += (_, _) => Edit(p => { if (isTrack) Track(p, id).Mute = m.IsChecked == true; else p.Buses.First(b => b.Id == id).Mute = m.IsChecked == true; });
            buttons.Children.Add(m);
        }

        if (isTrack)
        {
            var s = new ToggleButton { Content = "S", IsChecked = solo, Padding = new Thickness(6, 1, 6, 1) };
            s.Click += (_, _) => Edit(p => Track(p, id).Solo = s.IsChecked == true);
            buttons.Children.Add(s);
        }

        var fx = new Button { Content = effects > 0 ? $"FX {effects}" : "FX", Padding = new Thickness(6, 1, 6, 1) };
        fx.Click += (_, _) => { effectsTarget = id; BuildPanels(); Panels.SelectedIndex = 1; };
        buttons.Children.Add(fx);
        panel.Children.Add(buttons);
        var faderRow = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 6, Height = 194 };
        var fader = new Slider { Orientation = Orientation.Vertical, Minimum = MusicRules.MinGainDb, Maximum = MusicRules.MaxGainDb, StepFrequency = 0.5, Value = gainDb, Height = 130 };
        AutomationProperties.SetName(fader, $"Volume do canal {name}");
        var value = new TextBlock { Text = $"{Db(gainDb)} dB", FontSize = 11, FontFamily = new FontFamily("Consolas") };
        fader.ValueChanged += (_, e) =>
        {
            value.Text = $"{Db(e.NewValue)} dB";
            Set((p, v) =>
            {
                if (id == "master") p.Master.GainDb = v;
                else if (isTrack) Track(p, id).GainDb = v;
                else p.Buses.First(b => b.Id == id).GainDb = v;
            }, e.NewValue, $"vol-{id}");
        };
        Rectangle Bar() => new() { Width = 6, Height = 0, VerticalAlignment = VerticalAlignment.Bottom, Fill = new SolidColorBrush(Windows.UI.Color.FromArgb(255, 67, 160, 71)) };
        var l = Bar();
        var r = Bar();
        stripMeters[id] = (l, r);
        faderRow.Children.Add(Fine(fader, MusicRules.StepGainDb, false, $"volume do canal {name}", vertical: true));
        faderRow.Children.Add(new Grid { Width = 6, Height = 130, Background = new SolidColorBrush(Microsoft.UI.Colors.Black), Children = { l } });
        faderRow.Children.Add(new Grid { Width = 6, Height = 130, Background = new SolidColorBrush(Microsoft.UI.Colors.Black), Children = { r } });
        panel.Children.Add(faderRow);
        panel.Children.Add(value);
        if (id != "master")
        {
            var panSlider = new Slider { Header = "Pan", Minimum = -100, Maximum = 100, Value = pan };
            panSlider.ValueChanged += (_, e) => Set((p, v) => { if (isTrack) Track(p, id).Pan = v; else p.Buses.First(b => b.Id == id).Pan = v; }, e.NewValue, $"pan-{id}");
            panel.Children.Add(Fine(panSlider, MusicRules.StepPan, false, $"pan de {name}"));
        }

        if (track is not null)
        {
            var left = new Slider { Header = "Ajuste L (dB)", Minimum = MusicRules.MinTrimDb, Maximum = MusicRules.MaxTrimDb, StepFrequency = 0.5, Value = track.LeftTrimDb };
            left.ValueChanged += (_, e) => Set((p, v) => Track(p, id).LeftTrimDb = v, e.NewValue, $"l-{id}");
            var right = new Slider { Header = "Ajuste R (dB)", Minimum = MusicRules.MinTrimDb, Maximum = MusicRules.MaxTrimDb, StepFrequency = 0.5, Value = track.RightTrimDb };
            right.ValueChanged += (_, e) => Set((p, v) => Track(p, id).RightTrimDb = v, e.NewValue, $"r-{id}");
            var output = new ComboBox { Header = "Saída", ItemsSource = new[] { "Master" }.Concat(project!.Buses.Select(b => b.Name)).ToList(), SelectedIndex = track.Output == "master" ? 0 : project.Buses.FindIndex(b => b.Id == track.Output) + 1 };
            output.SelectionChanged += (_, _) => Edit(p => Track(p, id).Output = output.SelectedIndex <= 0 ? "master" : p.Buses[output.SelectedIndex - 1].Id, rebuild: false);
            panel.Children.Add(Fine(left, MusicRules.StepTrimDb, false, $"ajuste do canal esquerdo de {name}"));
            panel.Children.Add(Fine(right, MusicRules.StepTrimDb, false, $"ajuste do canal direito de {name}"));
            panel.Children.Add(output);
            foreach (var bus in project.Buses)
            {
                var send = track.Sends.FirstOrDefault(s => s.Bus == bus.Id);
                var level = new Slider { Header = $"→ {bus.Name}", Minimum = -61, Maximum = 6, Value = send?.LevelDb ?? -61 };
                level.ValueChanged += (_, e) => Edit(p =>
                {
                    var t = Track(p, id);
                    var existing = t.Sends.FirstOrDefault(s => s.Bus == bus.Id);
                    if (e.NewValue <= -61) t.Sends.RemoveAll(s => s.Bus == bus.Id);
                    else if (existing is null) t.Sends.Add(new MusicSend { Bus = bus.Id, LevelDb = e.NewValue });
                    else existing.LevelDb = e.NewValue;
                }, $"envio-{id}-{bus.Id}", false);
                var pre = new CheckBox { Content = "Pré-fader", IsChecked = send?.PreFader == true };
                pre.Click += (_, _) => Edit(p =>
                {
                    var existing = Track(p, id).Sends.FirstOrDefault(s => s.Bus == bus.Id);
                    if (existing is not null) existing.PreFader = pre.IsChecked == true;
                }, rebuild: false);
                panel.Children.Add(Fine(level, MusicRules.StepSendDb, false, $"envio de {name} para {bus.Name}"));
                panel.Children.Add(pre);
            }
        }

        return panel;
    }

    // ==================== efeitos ====================

    private List<MusicEffect> ChainOf(MusicProject p, string target) =>
        target == "master" ? p.Master.Effects : p.Tracks.FirstOrDefault(t => t.Id == target)?.Effects ?? p.Buses.FirstOrDefault(b => b.Id == target)?.Effects ?? p.Master.Effects;

    private UIElement EffectsView()
    {
        var panel = new StackPanel { Spacing = 10 };
        var targets = project!.Tracks.Select(t => (Id: t.Id, Name: $"Faixa: {t.Name}")).Concat(project.Buses.Select(b => (Id: b.Id, Name: $"Barramento: {b.Name}"))).Append((Id: "master", Name: "Master")).ToList();
        var target = new ComboBox { Header = "Cadeia de", ItemsSource = targets.Select(t => t.Name).ToList(), SelectedIndex = Math.Max(0, targets.FindIndex(t => t.Id == effectsTarget)), HorizontalAlignment = HorizontalAlignment.Stretch, MaxWidth = 560 };
        target.SelectionChanged += (_, _) => { effectsTarget = targets[target.SelectedIndex].Id; BuildPanels(); };
        var add = new ComboBox { Header = "Acrescentar", PlaceholderText = "Escolha um efeito…", ItemsSource = MusicDsp.Catalog.Select(d => $"{d.Group}: {d.Label}").ToList(), HorizontalAlignment = HorizontalAlignment.Stretch, MaxWidth = 560 };
        AutomationProperties.SetName(add, "Acrescentar efeito");
        add.SelectionChanged += (_, _) =>
        {
            if (add.SelectedIndex < 0) return;
            var kind = MusicDsp.Catalog[add.SelectedIndex].Kind;
            Edit(p => ChainOf(p, effectsTarget).Add(MusicDsp.NewEffect(kind, NewId("fx"))));
        };
        // Um embaixo do outro: um nome de faixa longo não empurra o "Acrescentar" para fora da tela.
        panel.Children.Add(new StackPanel { Spacing = 8, Children = { target, add } });
        var chain = ChainOf(project, effectsTarget);
        if (chain.Count == 0) panel.Children.Add(Label("Nenhum efeito nesta cadeia. A ordem conta: o sinal passa de cima para baixo. Os efeitos não têm atraso (latência zero)."));
        for (var i = 0; i < chain.Count; i++)
        {
            var effect = chain[i];
            var index = i;
            var definition = MusicDsp.Definition(effect.Kind);
            if (definition is null) continue;
            var box = new StackPanel { Spacing = 4, Padding = new Thickness(10), CornerRadius = new CornerRadius(8), BorderThickness = new Thickness(1), BorderBrush = (Brush)Application.Current.Resources["BrandStrokeBrush"], Opacity = effect.IsEnabled ? 1 : 0.6 };
            var enabled = new CheckBox { Content = definition.Label, IsChecked = effect.IsEnabled };
            enabled.Click += (_, _) => Edit(p => ChainOf(p, effectsTarget)[index].Enabled = enabled.IsChecked == true);
            var up = new Button { Content = "↑", IsEnabled = i > 0 };
            up.Click += (_, _) => Edit(p => { var c = ChainOf(p, effectsTarget); (c[index - 1], c[index]) = (c[index], c[index - 1]); });
            var down = new Button { Content = "↓", IsEnabled = i < chain.Count - 1 };
            down.Click += (_, _) => Edit(p => { var c = ChainOf(p, effectsTarget); (c[index + 1], c[index]) = (c[index], c[index + 1]); });
            var remove = new Button { Content = "✕" };
            AutomationProperties.SetName(remove, $"Remover {definition.Label}");
            remove.Click += (_, _) => Edit(p => ChainOf(p, effectsTarget).RemoveAt(index));
            box.Children.Add(new StackPanel { Orientation = Orientation.Horizontal, Spacing = 6, Children = { enabled, up, down, remove } });
            var grid = new VariableSizedWrapGrid { Orientation = Orientation.Horizontal, ItemWidth = 280 };
            foreach (var parameter in definition.Parameters)
            {
                var slider = new Slider { Header = $"{parameter.Label}{(parameter.Unit.Length > 0 ? $" ({parameter.Unit})" : "")}", Minimum = parameter.Min, Maximum = parameter.Max, StepFrequency = parameter.Max - parameter.Min > 100 ? 1 : parameter.Max - parameter.Min > 10 ? 0.1 : 0.01, Value = MusicDsp.Param(effect, parameter.Id), Margin = new Thickness(0, 0, 12, 0) };
                AutomationProperties.SetName(slider, $"{definition.Label}: {parameter.Label}");
                slider.ValueChanged += (_, e) => Edit(p => ChainOf(p, effectsTarget)[index].Set(parameter.Id, e.NewValue), $"{effect.Id}-{parameter.Id}", false);
                var (step, relative) = MusicRules.StepFor(parameter.Min, parameter.Max, parameter.Unit);
                grid.Children.Add(Fine(slider, step, relative, $"{definition.Label}: {parameter.Label}"));
            }

            box.Children.Add(grid);
            panel.Children.Add(box);
        }

        return panel;
    }

    // ==================== gravação ====================

    private UIElement RecordingView()
    {
        var panel = new StackPanel { Spacing = 8, MaxWidth = 900, HorizontalAlignment = HorizontalAlignment.Left };
        IReadOnlyList<MusicDevice> inputs;
        try
        {
            inputs = MusicAudioEngine.Inputs();
        }
        catch (System.Runtime.InteropServices.COMException)
        {
            inputs = [];
        }

        var device = new ComboBox { Header = "Entrada", ItemsSource = new[] { "Padrão do sistema" }.Concat(inputs.Select(i => i.Name)).Append("Som do computador (loopback)").ToList(), SelectedIndex = 0, MinWidth = 320 };
        AutomationProperties.SetName(device, "Dispositivo de entrada");
        var channels = new ComboBox { Header = "Canais", ItemsSource = new[] { "Mono", "Estéreo" }, SelectedIndex = 0 };
        var open = new Button { Content = engine?.Input is null ? "Usar esta entrada" : "Trocar a entrada" };
        AutomationProperties.SetName(open, "Usar esta entrada");
        var info = Label(engine?.Input is { } current ? $"{current.Name} · {current.Channels} canal(is) · {current.DeviceRate} Hz no dispositivo" : "Nenhuma entrada aberta.");
        open.Click += (_, _) =>
        {
            try
            {
                var loopback = device.SelectedIndex == inputs.Count + 1;
                var chosen = device.SelectedIndex is > 0 && !loopback ? inputs[device.SelectedIndex - 1].Id : null;
                var result = loopback ? engine!.OpenLoopbackInput() : engine!.OpenInput(chosen, channels.SelectedIndex + 1);
                info.Text = $"{result.Name} · {result.Channels} canal(is) · {result.DeviceRate} Hz no dispositivo";
                open.Content = "Trocar a entrada";
            }
            catch (Exception e) when (e is System.Runtime.InteropServices.COMException or ArgumentException or InvalidOperationException)
            {
                info.Text = $"Não foi possível abrir: {e.Message}";
            }
        };
        panel.Children.Add(new StackPanel { Orientation = Orientation.Horizontal, Spacing = 10, Children = { device, channels, open } });
        panel.Children.Add(info);
        panel.Children.Add(Label("Use fones com fio ou uma interface de áudio. Com fone Bluetooth o atraso varia. Sem fones, deixe o MON desligado para não dar microfonia. A gravação é sempre seca: os efeitos só tocam, não entram no arquivo gravado. O aplicativo grava em WASAPI compartilhado; ASIO e plugins VST3 de terceiros não estão nesta versão."));
        var latency = project!.Latency.Measured
            ? $"Latência medida: {(project.Latency.Input + project.Latency.Output) * 1000 / Rate:0.0} ms de ida e volta."
            : engine?.Input is not null ? $"A tomada é alinhada pelo relógio do dispositivo: os {engine.OutputBufferMs:0} ms de buffer do Windows já são descontados. Calibre para descontar também o atraso dos conversores." : "Abra a entrada para gravar.";
        panel.Children.Add(Label(latency));
        var calibrate = new Button { Content = "Calibrar com cliques" };
        AutomationProperties.SetName(calibrate, "Calibrar latência");
        calibrate.Click += async (_, _) => await CalibrateAsync();
        var adjust = new NumberBox { Header = "Correção manual (ms)", Value = Math.Round((project.Latency.Adjust ?? 0) * 1000.0 / Rate, 1), SmallChange = 0.5, SpinButtonPlacementMode = NumberBoxSpinButtonPlacementMode.Compact };
        adjust.ValueChanged += (_, e) => { if (!double.IsNaN(e.NewValue)) Edit(p => p.Latency.Adjust = MusicRules.Round(e.NewValue / 1000 * Rate), "ajuste", false); };
        var monitor = new CheckBox { Content = "Ouvir a entrada com o transporte parado" };
        monitor.Click += (_, _) => engine?.MonitorWhileStopped(monitor.IsChecked == true);
        panel.Children.Add(new StackPanel { Orientation = Orientation.Horizontal, Spacing = 10, Children = { calibrate, adjust, monitor } });
        var punch = new CheckBox { Content = "Gravar só entre a entrada e a saída do punch (região do loop)", IsChecked = project.Punch.Enabled, IsEnabled = project.Loop.End > project.Loop.Start };
        punch.Click += (_, _) => Edit(p => { p.Punch.Enabled = punch.IsChecked == true; p.Punch.In = p.Loop.Start; p.Punch.Out = p.Loop.End; }, rebuild: false);
        var metronomeVolume = new Slider { Header = "Volume do metrônomo (dB)", Minimum = -40, Maximum = 6, Value = project.Metronome.VolumeDb };
        metronomeVolume.ValueChanged += (_, e) => Edit(p => p.Metronome.VolumeDb = e.NewValue, "metro", false);
        var inExport = new CheckBox { Content = "Metrônomo também na exportação", IsChecked = project.Metronome.InExport };
        inExport.Click += (_, _) => Edit(p => p.Metronome.InExport = inExport.IsChecked == true, rebuild: false);
        var marker = new Button { Content = "+ Marcador no cursor" };
        marker.Click += (_, _) => Edit(p => p.Markers.Add(new MusicMarker { Id = NewId("marca"), Frame = engine?.Position ?? 0, Name = $"Marca {p.Markers.Count + 1}" }));
        panel.Children.Add(new StackPanel { Orientation = Orientation.Horizontal, Spacing = 10, Children = { punch, Fine(metronomeVolume, MusicRules.StepMetronomeDb, false, "volume do metrônomo", width: 300), inExport, marker } });
        return panel;
    }

    private async Task CalibrateAsync()
    {
        if (engine?.Input is null)
        {
            Status("Abra a entrada antes de calibrar.", InfoBarSeverity.Warning);
            return;
        }

        Status("Calibrando: deixe o microfone perto do alto-falante (ou um cabo da saída para a entrada) e fique em silêncio por 3 segundos.", InfoBarSeverity.Informational);
        long[] pulses = [12000, 36000, 60000, 84000];
        var (recorded, aligned) = await engine.CaptureClicksAsync(pulses, 2700);
        engine.SetProject(project!);
        var measured = MusicRules.MeasureLatency(recorded, [.. pulses.Select(p => p - aligned)], Rate);
        if (measured is null)
        {
            Status("Não deu para ouvir os cliques de volta. Com fones a calibração não funciona: use a estimativa ou a correção manual.", InfoBarSeverity.Warning);
            return;
        }

        Edit(p => p.Latency = new MusicLatency { Input = measured.Value, Output = 0, Measured = true, Adjust = p.Latency.Adjust });
        Status($"Latência de ida e volta medida: {measured.Value * 1000.0 / Rate:0.0} ms. As próximas tomadas entram compensadas.", InfoBarSeverity.Success);
    }

    // ==================== separação ====================

    private UIElement SeparationView()
    {
        var panel = new StackPanel { Spacing = 8, MaxWidth = 900, HorizontalAlignment = HorizontalAlignment.Left };
        var clips = project!.Tracks.SelectMany(t => t.Clips.Where(c => !c.Mute).Select(c => (Track: t, Clip: c))).ToList();
        if (clips.Count == 0)
        {
            panel.Children.Add(Label("Importe ou grave um áudio primeiro."));
            return panel;
        }

        // Uma opção por clipe: a faixa em cima, o clipe e a duração embaixo; nomes longos são cortados.
        var source = new RadioButtons { Header = "1. Escolha o áudio", SelectedIndex = 0, MaxWidth = 620, HorizontalAlignment = HorizontalAlignment.Left };
        foreach (var (t, c) in clips)
        {
            var text = new StackPanel { MaxWidth = 560 };
            text.Children.Add(new TextBlock { Text = t.Name, FontWeight = Microsoft.UI.Text.FontWeights.SemiBold, TextTrimming = TextTrimming.CharacterEllipsis });
            text.Children.Add(new TextBlock { Text = $"{c.Name ?? "Clipe"} · {c.Duration / (double)Rate:0.0} s", FontSize = 12, TextTrimming = TextTrimming.CharacterEllipsis, Foreground = (Brush)Application.Current.Resources["BrandTextMutedBrush"] });
            ToolTipService.SetToolTip(text, $"{t.Name} — {c.Name ?? "Clipe"}");
            source.Items.Add(text);
        }

        var mode = new RadioButtons { Header = "2. Em quantas faixas", SelectedIndex = 1, ItemsSource = MusicSeparation.Modes.Select(m => $"{m.Stems.Count} faixas: {m.Label}").ToList() };
        var names = Label("");
        void ShowNames() => names.Text = "As faixas novas entram com o nome do que são: " + string.Join(", ", MusicSeparation.Modes[Math.Max(0, mode.SelectedIndex)].Stems.Select(st => MusicRules.SeparatedTrackName(st.Label, "…"))) + ".";
        ShowNames();
        mode.SelectionChanged += (_, _) => ShowNames();
        var clean = new ToggleSwitch { Header = "Reduzir ruído nas faixas separadas", IsOn = false };
        var cleanHelp = Label("Tira o chiado e o ruído constante que a separação costuma deixar em cada faixa (mais útil na voz). Deixe desligado se a música original já é limpa.");
        var progress = new ProgressBar { Maximum = 1, Width = 320, Visibility = Visibility.Collapsed };
        var stage = Label("");
        var run = new Button { Content = "Separar", Style = (Style)Application.Current.Resources["BrandActionButtonStyle"] };
        AutomationProperties.SetName(run, "Separar faixas");
        var cancel = new Button { Content = "Cancelar", Visibility = Visibility.Collapsed };
        cancel.Click += (_, _) => work?.Cancel();
        run.Click += async (_, _) =>
        {
            var (track, clip) = clips[Math.Max(0, source.SelectedIndex)];
            var modeId = MusicSeparation.Modes[Math.Max(0, mode.SelectedIndex)].Id;
            work = new CancellationTokenSource();
            run.IsEnabled = false;
            cancel.Visibility = progress.Visibility = Visibility.Visible;
            try
            {
                if (!Tools.ModelReady)
                {
                    stage.Text = "Baixando o modelo (78 MB, só na primeira vez)";
                    await Tools.EnsureModelAsync(App.Services.Http, new Progress<double>(f => progress.Value = f), work.Token);
                }

                var folder = Path.Combine(Library.Folder(project.Id), "midias");
                var stems = (await Tools.SeparateAsync(Library.MediaPath(project.Id, clip.Media), folder, modeId, Rate,
                    new Progress<(string Stage, double Fraction)>(p => { stage.Text = p.Stage; progress.Value = p.Fraction; }), work.Token)).ToList();
                if (clean.IsOn)
                {
                    // Cada faixa separada passa pela redução de ruído antes de entrar no projeto.
                    for (var k = 0; k < stems.Count; k++)
                    {
                        var (stem, raw) = stems[k];
                        stage.Text = $"Reduzindo o ruído: {stem.Label}";
                        progress.Value = (k + 0.5) / stems.Count;
                        var cleaned = Path.Combine(folder, $"{Path.GetFileNameWithoutExtension(raw)}-limpa.wav");
                        await MusicTools.ReduceNoiseAsync(raw, cleaned, Rate, work.Token);
                        File.Delete(raw);
                        stems[k] = (stem, cleaned);
                    }
                }
                var added = new List<MusicMedia>();
                Edit(p =>
                {
                    var i = p.Tracks.FindIndex(t => t.Id == track.Id);
                    p.Tracks[i].Mute = true;
                    var origin = p.Media.FirstOrDefault(m => m.Id == clip.Media);
                    for (var k = 0; k < stems.Count; k++)
                    {
                        var (stem, path) = stems[k];
                        var mediaId = NewId("stem");
                        File.Move(path, Library.MediaPath(p.Id, mediaId), true);
                        using var wav = new MappedWavSource(Library.MediaPath(p.Id, mediaId));
                        var media = new MusicMedia { Id = mediaId, Name = $"{stem.Label}.wav", SampleRate = Rate, Channels = 2, Frames = wav.Frames, Origin = origin?.Id, Instrument = stem.Id, Model = MusicSeparation.ModelId, ModelVersion = "1" };
                        p.Media.Add(media);
                        added.Add(media);
                        var newTrack = MusicRules.NewTrack(NewId("faixa"), MusicRules.SeparatedTrackName(stem.Label, track.Name), i + k + 1);
                        var c = clip.Copy();
                        c.Id = NewId("clipe");
                        c.Media = mediaId;
                        c.Name = stem.Label;
                        newTrack.Clips.Add(c);
                        p.Tracks.Insert(i + k + 1, newTrack);
                    }

                    p.Separations.Add(new MusicSeparationRecord { Id = NewId("sep"), Media = clip.Media, Mode = modeId, Model = MusicSeparation.ModelId, Tracks = [.. added.Select(m => m.Id)] });
                });
                foreach (var media in added) LoadMedia(media);
                Status($"Pronto: {stems.Count} faixas novas abaixo de “{track.Name}”, que ficou muda como referência.{(clean.IsOn ? " O ruído de fundo foi reduzido em cada uma." : "")}", InfoBarSeverity.Success);
            }
            catch (OperationCanceledException)
            {
                Status("Separação cancelada.", InfoBarSeverity.Informational);
            }
            catch (Exception e) when (e is Core.ConversionException or IOException or System.Net.Http.HttpRequestException or Microsoft.ML.OnnxRuntime.OnnxRuntimeException)
            {
                Status(e.Message, InfoBarSeverity.Error);
            }
            finally
            {
                run.IsEnabled = true;
                cancel.Visibility = progress.Visibility = Visibility.Collapsed;
            }
        };
        panel.Children.Add(source);
        panel.Children.Add(mode);
        panel.Children.Add(names);
        panel.Children.Add(clean);
        panel.Children.Add(cleanHelp);
        panel.Children.Add(new StackPanel { Orientation = Orientation.Horizontal, Spacing = 10, Children = { run, cancel } });
        panel.Children.Add(progress);
        panel.Children.Add(stage);
        panel.Children.Add(Label(MusicSeparation.Warning));
        panel.Children.Add(Label($"Modelo: Spleeter 4 faixas (Deezer), licença {MusicSeparation.ModelLicense}. Roda neste computador; o áudio não sai daqui. {(Tools.ModelReady ? "O modelo já está baixado." : "O modelo (78 MB) é baixado na primeira separação, conferido pelo SHA-256.")}"));
        panel.Children.Add(Label("Separar músicas de outras pessoas não muda os direitos autorais delas: use em gravações suas ou que você tem direito de usar."));
        return panel;
    }

    /// <summary>
    /// Barra com − e + nas pontas: a barra para o gesto largo, os botões para o passo fino
    /// (MusicRules.FineStep, encaixado na grade do passo). Na vertical, o + fica em cima.
    /// </summary>
    private static FrameworkElement Fine(Slider slider, double step, bool relative, string name, bool vertical = false, double width = 0)
    {
        if (!relative) slider.StepFrequency = step;
        AutomationProperties.SetName(slider, name);
        Button Step(string text, int direction, string verb)
        {
            var button = new Button { Content = text, Padding = new Thickness(8, 0, 8, 2), MinWidth = 30, VerticalAlignment = vertical ? VerticalAlignment.Center : VerticalAlignment.Bottom, HorizontalAlignment = HorizontalAlignment.Center };
            AutomationProperties.SetName(button, $"{verb} {name}");
            button.Click += (_, _) => slider.Value = MusicRules.FineStep(slider.Value, direction, step, slider.Minimum, slider.Maximum, relative);
            return button;
        }

        var minus = Step("−", -1, "Diminuir");
        var plus = Step("+", 1, "Aumentar");
        if (vertical) return new StackPanel { Spacing = 4, Children = { plus, slider, minus } };
        var grid = new Grid { ColumnSpacing = 4 };
        if (width > 0) grid.Width = width;
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        Grid.SetColumn(slider, 1);
        Grid.SetColumn(plus, 2);
        grid.Children.Add(minus);
        grid.Children.Add(slider);
        grid.Children.Add(plus);
        return grid;
    }

    // ==================== exportação ====================

    private UIElement ExportView()
    {
        var panel = new StackPanel { Spacing = 8, MaxWidth = 900, HorizontalAlignment = HorizontalAlignment.Left };
        var selectedName = project!.Tracks.FirstOrDefault(t => t.Id == selectedTrack)?.Name;
        var what = new ComboBox { Header = "O quê", ItemsSource = new List<string> { "A mistura, com o master", "Cada faixa num arquivo (stems, sem o master)" }.Concat(selectedName is null ? [] : [$"Só a faixa “{selectedName}”"]).ToList(), SelectedIndex = 0 };
        var format = new ComboBox { Header = "Formato", ItemsSource = MusicMastering.Formats.Select(f => f.Label).ToList(), SelectedIndex = 0 };
        var master = new ComboBox { Header = "Master", ItemsSource = MusicMastering.Presets.Select(p => p.Label).ToList(), SelectedIndex = 0 };
        var range = new ComboBox { Header = "Trecho", ItemsSource = new[] { "O projeto inteiro", "Só a região do loop" }, SelectedIndex = 0 };
        var tail = new CheckBox { Content = "Incluir a cauda do reverb e do delay", IsChecked = true };
        var run = new Button { Content = "Exportar", Style = (Style)Application.Current.Resources["BrandActionButtonStyle"] };
        AutomationProperties.SetName(run, "Exportar música");
        var progress = new ProgressBar { Maximum = 1, Width = 320, Visibility = Visibility.Collapsed };
        var result = Label("");
        run.Click += async (_, _) =>
        {
            if (!LicenseGate.CanConvert(out var license))
            {
                Status(license, InfoBarSeverity.Warning);
                return;
            }

            run.IsEnabled = false;
            progress.Visibility = Visibility.Visible;
            try
            {
                var request = new MusicExportRequest(MusicMastering.Formats[format.SelectedIndex].Id, MusicMastering.Presets[master.SelectedIndex].Id, range.SelectedIndex == 1, what.SelectedIndex == 1, tail.IsChecked == true, what.SelectedIndex == 2 ? selectedTrack : null);
                var folder = Path.Combine(App.Services.OutputDirectory, "Criação musical");
                var files = await Tools.ExportAsync(project, m => Library.MediaPath(project.Id, m.Id), request, folder, new Progress<(string Stage, double Fraction)>(p => { result.Text = p.Stage; progress.Value = p.Fraction; }), CancellationToken.None);
                result.Text = string.Join(Environment.NewLine, files.Select(f => $"{f.Path}{(f.Measures is { } m ? $" · {(double.IsFinite(m.Lufs) ? $"{m.Lufs:0.0} LUFS" : "silêncio")} · pico real {m.TruePeakDb:0.0} dBTP" : "")}"));
                Status($"Exportado em {folder}", InfoBarSeverity.Success);
            }
            catch (Exception e) when (e is Core.ConversionException or IOException)
            {
                result.Text = e.Message;
            }
            finally
            {
                run.IsEnabled = true;
                progress.Visibility = Visibility.Collapsed;
            }
        };
        panel.Children.Add(new StackPanel { Orientation = Orientation.Horizontal, Spacing = 10, Children = { what, format, master, range } });
        panel.Children.Add(new StackPanel { Orientation = Orientation.Horizontal, Spacing = 10, Children = { tail, run, progress } });
        panel.Children.Add(result);
        panel.Children.Add(Label("WAV de 16 e 24 bits leva dither; o float32 não. FLAC, MP3, AAC e Opus saem pelo FFmpeg. Nenhum alvo de volume é imposto: escolha o master pela finalidade e confira a medida."));
        return panel;
    }
}
