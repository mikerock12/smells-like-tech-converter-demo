using System.Text.Json;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using SmellsLikeTech.Converter.Core;
using SmellsLikeTech.Converter.Core.Music;
using SmellsLikeTech.Converter.Desktop.Pages;

namespace SmellsLikeTech.Converter.Desktop.Controls;

/// <summary>
/// O que gerar e como soar, antes de separar (ferramentas experimentais do conversor): o
/// modelo, os arquivos do .zip (cada um junta as faixas marcadas), o formato, o volume final e
/// os ajustes de cada faixa — os mesmos efeitos, cadeias prontas e VST3 do estúdio musical.
/// No vídeo, o que fica no áudio. O pedido sai em JSON (<see cref="Json"/>), igual ao do site.
/// </summary>
public sealed class StemPackageEditor : UserControl
{
    private MusicStemPackageRequest request = MusicStemPackage.ReadRequest(null);
    private bool video;
    private string effectsTarget = "vozes";
    private readonly StackPanel root = new() { Spacing = 12 };

    public StemPackageEditor() => Content = root;

    /// <summary>O pedido como está na tela, pronto para as opções do job.</summary>
    public string Json => MusicStemPackage.ToJson(request);

    public IReadOnlyList<string> Problems => video ? [] : MusicStemPackage.Problems(request.Model, request.Groups);

    public void Load(string? json, bool forVideo)
    {
        video = forVideo;
        try
        {
            request = MusicStemPackage.ReadRequest(json, defaultKeep: forVideo ? "resto" : null);
        }
        catch (ConversionException)
        {
            request = MusicStemPackage.ReadRequest(null, defaultKeep: forVideo ? "resto" : null);
        }

        if (video) effectsTarget = "master";
        Build();
    }

    private static TextBlock Label(string text, bool strong = false) => new()
    {
        Text = text, TextWrapping = TextWrapping.Wrap, FontSize = strong ? 14 : 12,
        FontWeight = strong ? Microsoft.UI.Text.FontWeights.SemiBold : Microsoft.UI.Text.FontWeights.Normal,
        Foreground = strong ? null : (Brush)Application.Current.Resources["BrandTextMutedBrush"],
    };

    private static string NewId(string prefix) => $"{prefix}-{Guid.NewGuid():N}"[..(prefix.Length + 9)];

    private void Change(Action<MusicStemPackageRequest> edit, bool rebuild = true)
    {
        edit(request);
        if (rebuild) Build();
    }

    private MusicStemPackageModel Model => MusicStemPackage.Model(request.Model)!;

    private void Build()
    {
        root.Children.Clear();
        root.Children.Add(new InfoBar
        {
            IsOpen = true, IsClosable = false, Severity = InfoBarSeverity.Informational, Title = "Experimental",
            Message = "A separação por IA nunca é perfeita e pode levar vários minutos (o RoFormer, bem mais). Tudo roda neste computador.",
        });

        var models = new RadioButtons { Header = "Modelo de separação" };
        foreach (var m in MusicStemPackage.Models) models.Items.Add(new RadioButton { Content = $"{m.Label} — {m.Summary}", Tag = m.Id });
        models.SelectedIndex = MusicStemPackage.Models.ToList().FindIndex(m => m.Id == request.Model);
        models.SelectionChanged += (_, _) =>
        {
            if (models.SelectedItem is not RadioButton { Tag: string id } || id == request.Model) return;
            Change(r =>
            {
                var configuration = MusicStemPackage.Configuration(id);
                configuration.Tracks = [.. configuration.Tracks.Select(t => r.Configuration.Tracks.FirstOrDefault(x => x.Id == t.Id) ?? t)];
                configuration.Master = r.Configuration.Master;
                var groups = video ? [MusicStemPackage.VideoGroup(id, r.Keep ?? "resto")]
                    : r.Groups.Select(g => new MusicStemGroup { Name = g.Name, Stems = [.. g.Stems.Where(s => configuration.Tracks.Any(t => t.Id == s))] })
                        .Where(g => g.Stems.Count > 0).ToList();
                r.Model = id;
                r.Configuration = configuration;
                r.Groups = groups.Count > 0 ? groups : [.. MusicStemPackage.Combinations(id)[0].Groups];
                if (!configuration.Tracks.Any(t => t.Id == effectsTarget) && effectsTarget != "master") effectsTarget = "vozes";
            });
        };
        root.Children.Add(models);

        if (video) BuildVideo();
        else BuildGroups();

        var master = new ComboBox { Header = "Volume final", ItemsSource = MusicMastering.Presets.Select(p => p.Label).ToList(), HorizontalAlignment = HorizontalAlignment.Stretch, MaxWidth = 560 };
        master.SelectedIndex = Math.Max(0, MusicMastering.Presets.ToList().FindIndex(p => p.Id == request.Master));
        master.SelectionChanged += (_, _) => Change(r => r.Master = MusicMastering.Presets[master.SelectedIndex].Id, false);
        root.Children.Add(master);

        root.Children.Add(new Expander
        {
            Header = video ? "Efeitos, equalização e plugins no áudio que fica" : "Efeitos, equalização e plugins de cada faixa",
            HorizontalAlignment = HorizontalAlignment.Stretch, HorizontalContentAlignment = HorizontalAlignment.Stretch,
            IsExpanded = effectsOpen, Content = EffectsView(),
        }.Also(e => { e.Expanding += (_, _) => effectsOpen = true; e.Collapsed += (_, _) => effectsOpen = false; }));
        root.Children.Add(Label(Model.Id == "roformer"
            ? "Pode haver vazamento entre as faixas. Comece por um trecho curto: em CPU de PC, 8 s de áudio levam alguns minutos."
            : MusicSeparation.Warning));
        root.Children.Add(Label("Separar músicas de outras pessoas não muda os direitos autorais delas: use em gravações suas ou que você tem direito de usar."));
    }

    private bool effectsOpen;

    private void BuildVideo()
    {
        var keep = new RadioButtons { Header = "O que fica no áudio do vídeo" };
        keep.Items.Add(new RadioButton { Content = "Tirar a voz e manter o resto (trilha, efeitos)", Tag = "resto" });
        keep.Items.Add(new RadioButton { Content = "Manter só a voz (narração, falas)", Tag = "voz" });
        keep.SelectedIndex = request.Keep == "voz" ? 1 : 0;
        keep.SelectionChanged += (_, _) =>
        {
            if (keep.SelectedItem is not RadioButton { Tag: string id }) return;
            Change(r => { r.Keep = id; r.Groups = [MusicStemPackage.VideoGroup(r.Model, id)]; }, false);
        };
        root.Children.Add(keep);
    }

    private void BuildGroups()
    {
        var combos = MusicStemPackage.Combinations(request.Model);
        var ready = new ComboBox { Header = "Começar de uma combinação pronta", PlaceholderText = "Escolha…", ItemsSource = combos.Select(c => c.Label).ToList(), HorizontalAlignment = HorizontalAlignment.Stretch, MaxWidth = 560 };
        ready.SelectionChanged += (_, _) =>
        {
            if (ready.SelectedIndex < 0) return;
            var chosen = combos[ready.SelectedIndex];
            Change(r => r.Groups = [.. chosen.Groups.Select(g => new MusicStemGroup { Name = g.Name, Stems = [.. g.Stems] })]);
        };
        root.Children.Add(ready);
        root.Children.Add(Label($"Arquivos dentro do .zip ({request.Groups.Count})", strong: true));
        for (var i = 0; i < request.Groups.Count; i++)
        {
            var index = i;
            var group = request.Groups[i];
            var row = new StackPanel { Spacing = 6, Padding = new Thickness(10), CornerRadius = new CornerRadius(8), BorderThickness = new Thickness(1), BorderBrush = (Brush)Application.Current.Resources["BrandStrokeBrush"] };
            var name = new TextBox { Header = $"Arquivo {i + 1}", Text = group.Name, MaxLength = 60, MaxWidth = 420, HorizontalAlignment = HorizontalAlignment.Left };
            name.TextChanged += (_, _) => Change(r => r.Groups[index].Name = name.Text, false);
            var marks = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 4 };
            foreach (var stem in Model.Stems)
            {
                var check = new CheckBox { Content = MusicStemPackage.StemLabel(stem), IsChecked = group.Stems.Contains(stem), MinWidth = 90 };
                check.Click += (_, _) => Change(r =>
                {
                    var g = r.Groups[index];
                    var automatic = g.Name == MusicStemPackage.GroupName(g.Stems, Model);
                    g.Stems = check.IsChecked == true ? [.. Model.Stems.Where(s => s == stem || g.Stems.Contains(s))] : [.. g.Stems.Where(s => s != stem)];
                    if (automatic) g.Name = MusicStemPackage.GroupName(g.Stems, Model);
                });
                marks.Children.Add(check);
            }

            var remove = new Button { Content = "Tirar este arquivo", IsEnabled = request.Groups.Count > 1 };
            AutomationProperties.SetName(remove, $"Tirar o arquivo {group.Name}");
            remove.Click += (_, _) => Change(r => r.Groups.RemoveAt(index));
            row.Children.Add(name);
            row.Children.Add(new ScrollViewer { Content = marks, HorizontalScrollBarVisibility = ScrollBarVisibility.Auto, VerticalScrollBarVisibility = ScrollBarVisibility.Disabled, HorizontalScrollMode = ScrollMode.Auto });
            row.Children.Add(remove);
            root.Children.Add(row);
        }

        var add = new Button { Content = "+ Arquivo", IsEnabled = request.Groups.Count < MusicStemPackage.MaxFiles };
        add.Click += (_, _) => Change(r => r.Groups.Add(new MusicStemGroup { Name = $"Arquivo {r.Groups.Count + 1}" }));
        root.Children.Add(add);
        var problems = Problems;
        if (problems.Count > 0) root.Children.Add(new InfoBar { IsOpen = true, IsClosable = false, Severity = InfoBarSeverity.Warning, Message = string.Join(" ", problems) });

        var formats = MusicMastering.Formats.Where(f => f.Id is "wav-24" or "wav-16" or "flac" or "mp3" or "aac" or "opus").ToList();
        var format = new ComboBox { Header = "Formato dos arquivos", ItemsSource = formats.Select(f => f.Label).ToList(), HorizontalAlignment = HorizontalAlignment.Stretch, MaxWidth = 560 };
        format.SelectedIndex = Math.Max(0, formats.FindIndex(f => f.Id == request.Format));
        format.SelectionChanged += (_, _) => Change(r => r.Format = formats[format.SelectedIndex].Id, false);
        root.Children.Add(format);
    }

    // ==================== efeitos (os do estúdio musical) ====================

    private List<MusicEffect> ChainOf(MusicStemPackageRequest r, string target) =>
        target == "master" ? r.Configuration.Master.Effects : r.Configuration.Tracks.FirstOrDefault(t => t.Id == target)?.Effects ?? r.Configuration.Master.Effects;

    private UIElement EffectsView()
    {
        var panel = new StackPanel { Spacing = 10 };
        panel.Children.Add(Label(video
            ? "Escolha “Master” para aplicar no áudio inteiro que fica no vídeo, ou uma faixa para mexer só nela."
            : "Cada faixa leva os próprios efeitos para todos os arquivos em que entra. O Master vale para todos os arquivos."));
        var targets = request.Configuration.Tracks.Select(t => (Id: t.Id, Name: $"Faixa: {t.Name}")).Append((Id: "master", Name: "Master")).ToList();
        var target = new ComboBox { Header = "Cadeia de", ItemsSource = targets.Select(t => t.Name).ToList(), SelectedIndex = Math.Max(0, targets.FindIndex(t => t.Id == effectsTarget)), HorizontalAlignment = HorizontalAlignment.Stretch, MaxWidth = 560 };
        target.SelectionChanged += (_, _) => { effectsTarget = targets[target.SelectedIndex].Id; Build(); };
        panel.Children.Add(target);

        if (effectsTarget != "master" && request.Configuration.Tracks.FirstOrDefault(t => t.Id == effectsTarget) is { } track)
        {
            var volume = new Slider { Header = "Volume da faixa (dB)", Minimum = MusicRules.MinGainDb, Maximum = MusicRules.MaxGainDb, Value = track.GainDb };
            volume.ValueChanged += (_, e) => Change(r => r.Configuration.Tracks.First(t => t.Id == effectsTarget).GainDb = e.NewValue, false);
            var pan = new Slider { Header = "Pan (esquerda ← → direita)", Minimum = -100, Maximum = 100, Value = track.Pan };
            pan.ValueChanged += (_, e) => Change(r => r.Configuration.Tracks.First(t => t.Id == effectsTarget).Pan = e.NewValue, false);
            panel.Children.Add(new StackPanel { Orientation = Orientation.Horizontal, Spacing = 16, Children =
            {
                MusicPage.Fine(volume, MusicRules.StepGainDb, false, $"volume de {track.Name}", width: 300),
                MusicPage.Fine(pan, MusicRules.StepPan, false, $"pan de {track.Name}", width: 300),
            } });
        }

        var add = new ComboBox { Header = "Acrescentar", PlaceholderText = "Escolha um efeito…", ItemsSource = MusicDsp.Catalog.Select(d => $"{d.Group}: {d.Label}").ToList(), HorizontalAlignment = HorizontalAlignment.Stretch, MaxWidth = 560 };
        AutomationProperties.SetName(add, "Acrescentar efeito");
        add.SelectionChanged += (_, _) =>
        {
            if (add.SelectedIndex < 0) return;
            var kind = MusicDsp.Catalog[add.SelectedIndex].Kind;
            Change(r => ChainOf(r, effectsTarget).Add(MusicDsp.NewEffect(kind, NewId("fx"))));
        };
        panel.Children.Add(add);
        panel.Children.Add(ReadyChains());

        var chain = ChainOf(request, effectsTarget);
        if (chain.Count == 0) panel.Children.Add(Label("Nenhum efeito nesta cadeia. A ordem conta: o sinal passa de cima para baixo."));
        for (var i = 0; i < chain.Count; i++)
        {
            var effect = chain[i];
            var index = i;
            var definition = MusicDsp.Definition(effect.Kind);
            if (definition is null && effect.Kind != "vst3") continue;
            var title = definition?.Label ?? (effect.Extra?.TryGetValue("nome", out var nome) == true && nome.ValueKind == JsonValueKind.String ? nome.GetString() ?? "VST3" : "VST3");
            var box = new StackPanel { Spacing = 4, Padding = new Thickness(10), CornerRadius = new CornerRadius(8), BorderThickness = new Thickness(1), BorderBrush = (Brush)Application.Current.Resources["BrandStrokeBrush"], Opacity = effect.IsEnabled ? 1 : 0.6 };
            var enabled = new CheckBox { Content = title, IsChecked = effect.IsEnabled };
            enabled.Click += (_, _) => Change(r => ChainOf(r, effectsTarget)[index].Enabled = enabled.IsChecked == true);
            var up = new Button { Content = "↑", IsEnabled = i > 0 };
            up.Click += (_, _) => Change(r => { var c = ChainOf(r, effectsTarget); (c[index - 1], c[index]) = (c[index], c[index - 1]); });
            var down = new Button { Content = "↓", IsEnabled = i < chain.Count - 1 };
            down.Click += (_, _) => Change(r => { var c = ChainOf(r, effectsTarget); (c[index + 1], c[index]) = (c[index], c[index + 1]); });
            var remove = new Button { Content = "✕" };
            AutomationProperties.SetName(remove, $"Remover {title}");
            remove.Click += (_, _) => Change(r => ChainOf(r, effectsTarget).RemoveAt(index));
            box.Children.Add(new StackPanel { Orientation = Orientation.Horizontal, Spacing = 6, Children = { enabled, up, down, remove } });
            var grid = new VariableSizedWrapGrid { Orientation = Orientation.Horizontal, ItemWidth = 280 };
            foreach (var parameter in definition?.Parameters ?? [])
            {
                var slider = new Slider { Header = $"{parameter.Label}{(parameter.Unit.Length > 0 ? $" ({parameter.Unit})" : "")}", Minimum = parameter.Min, Maximum = parameter.Max, Value = MusicDsp.Param(effect, parameter.Id), Margin = new Thickness(0, 0, 12, 0) };
                slider.ValueChanged += (_, e) => Change(r => ChainOf(r, effectsTarget)[index].Set(parameter.Id, e.NewValue), false);
                var (step, relative) = MusicRules.StepFor(parameter.Min, parameter.Max, parameter.Unit);
                if (relative) slider.StepFrequency = parameter.Max - parameter.Min > 100 ? 1 : 0.01;
                grid.Children.Add(MusicPage.Fine(slider, step, relative, $"{title}: {parameter.Label}"));
            }

            box.Children.Add(grid);
            panel.Children.Add(box);
        }

        return panel;
    }

    private UIElement ReadyChains()
    {
        using var stream = typeof(MusicDsp).Assembly.GetManifestResourceStream("musica-efeitos.json")
            ?? throw new InvalidOperationException("Catálogo de efeitos indisponível.");
        using var catalog = JsonDocument.Parse(stream);
        var chains = new StackPanel { Spacing = 6 };
        string? group = null;
        foreach (var item in catalog.RootElement.GetProperty("cadeias").EnumerateArray())
        {
            var next = item.GetProperty("grupo").GetString();
            if (group != next) chains.Children.Add(Label(group = next ?? "Efeitos", strong: true));
            var effects = item.GetProperty("efeitos").Clone();
            var button = new Button { Content = item.GetProperty("nome").GetString() };
            button.Click += (_, _) => Change(r =>
            {
                var chain = ChainOf(r, effectsTarget);
                foreach (var step in effects.EnumerateArray())
                {
                    var kind = step.GetProperty("tipo").GetString()!;
                    var effect = MusicDsp.NewEffect(kind, NewId("fx"));
                    foreach (var parameter in MusicDsp.Definition(kind)!.Parameters)
                        if (step.TryGetProperty(parameter.Id, out var value)) effect.Set(parameter.Id, value.GetDouble());
                    chain.Add(effect);
                }
            });
            chains.Children.Add(button);
        }

        var plugins = new StackPanel { Spacing = 8 };
        if (MusicNative.HasVst3)
        {
            var installed = new ComboBox { Header = "Efeitos VST3 instalados", PlaceholderText = "Busque plugins VST3" };
            var scan = new Button { Content = "Buscar plugins instalados" };
            var addPlugin = new Button { Content = "Adicionar à cadeia", IsEnabled = false };
            var info = Label("");
            scan.Click += async (_, _) =>
            {
                scan.IsEnabled = false;
                try
                {
                    var found = await Task.Run(() => MusicNative.ScanPlugins());
                    installed.ItemsSource = found.Select(p => new ComboBoxItem { Content = p.Name, Tag = p }).ToList();
                    info.Text = found.Count == 0 ? "Nenhum efeito VST3 encontrado em Arquivos comuns\\VST3." : $"{found.Count} efeito(s) encontrado(s).";
                }
                catch (InvalidOperationException e) { info.Text = e.Message; }
                finally { scan.IsEnabled = true; }
            };
            installed.SelectionChanged += (_, _) => addPlugin.IsEnabled = installed.SelectedItem is ComboBoxItem;
            addPlugin.Click += (_, _) =>
            {
                if (installed.SelectedItem is not ComboBoxItem { Tag: MusicNative.PluginInfo item }) return;
                Change(r => ChainOf(r, effectsTarget).Add(new MusicEffect
                {
                    Id = NewId("fx"), Kind = "vst3", Enabled = true,
                    Extra = new Dictionary<string, JsonElement>
                    {
                        ["caminho"] = JsonSerializer.SerializeToElement(item.Path),
                        ["classe"] = JsonSerializer.SerializeToElement(item.ClassId),
                        ["nome"] = JsonSerializer.SerializeToElement(item.Name),
                    },
                }));
            };
            plugins.Children.Add(scan);
            plugins.Children.Add(installed);
            plugins.Children.Add(addPlugin);
            plugins.Children.Add(info);
        }
        else plugins.Children.Add(Label("O host VST3 não está disponível nesta instalação."));

        return new StackPanel
        {
            Spacing = 8,
            Children =
            {
                new Expander { Header = "Cadeias prontas", Content = chains },
                new Expander { Header = "Plugins VST3", Content = plugins },
            },
        };
    }
}

internal static class FrameworkElementExtensions
{
    public static T Also<T>(this T element, Action<T> configure)
    {
        configure(element);
        return element;
    }
}
