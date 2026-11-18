using System.Text.Json.Nodes;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using SmellsLikeTech.Converter.Core.Music;

namespace SmellsLikeTech.Converter.Desktop.Controls;

/// <summary>
/// Restaurar áudio com IA (experimental): tipo de gravação, intensidade, formato e, no
/// Avançado, cada etapa. O pedido sai em JSON (<see cref="Json"/>), igual ao do site e do Android.
/// </summary>
public sealed class AudioRestoreEditor : UserControl
{
    private AudioRestoration.Options options = AudioRestoration.ReadOptions(null);
    private readonly StackPanel root = new() { Spacing = 10 };

    public AudioRestoreEditor() => Content = root;

    /// <summary>O pedido com o formato escolhido na lista de formatos da tela (wav, flac ou mp3).</summary>
    public string JsonFor(string extension)
    {
        options = options with { Format = extension switch { "flac" => "flac", "mp3" => "mp3", _ => options.Format == "wav-32f" ? "wav-32f" : "wav-24" } };
        return Json;
    }

    public string Json
    {
        get
        {
            var etapas = new JsonObject();
            foreach (var (id, valor) in options.Steps) etapas[id] = valor;
            return new JsonObject
            {
                ["perfil"] = options.Profile, ["intensidade"] = options.Intensity, ["preservarAmbiencia"] = options.PreserveAmbience,
                ["formato"] = options.Format, ["etapas"] = etapas, ["relatorio"] = options.Report, ["ia"] = options.Ai, ["manterVolume"] = options.KeepVolume,
            }.ToJsonString();
        }
    }

    public void Load(string? json)
    {
        try { options = AudioRestoration.ReadOptions(json); }
        catch (Core.ConversionException) { options = AudioRestoration.ReadOptions(null); }
        Build();
    }

    private static TextBlock Label(string text) => new()
    {
        Text = text, TextWrapping = TextWrapping.Wrap, FontSize = 12,
        Foreground = (Brush)Application.Current.Resources["BrandTextMutedBrush"],
    };

    private void Build()
    {
        root.Children.Clear();
        root.Children.Add(new InfoBar
        {
            IsOpen = true, IsClosable = false, Severity = InfoBarSeverity.Informational, Title = "Experimental",
            Message = "Processado neste computador. A restauração estima e limpa; não recria o master original nem devolve o que a compressão ou a gravação cortaram. O original não muda.",
        });
        if (!MusicNative.HasAudioRestoration)
            root.Children.Add(new InfoBar { IsOpen = true, IsClosable = false, Severity = InfoBarSeverity.Warning, Title = "Núcleo de áudio ausente", Message = "Reinstale o aplicativo para usar o restaurador." });

        var profiles = new RadioButtons { Header = "Tipo de gravação" };
        foreach (var p in AudioRestoration.Profiles) profiles.Items.Add(new RadioButton { Content = $"{p.Label} — {p.Summary}", Tag = p.Id });
        profiles.SelectedIndex = AudioRestoration.Profiles.ToList().FindIndex(p => p.Id == options.Profile);
        profiles.SelectionChanged += (_, _) =>
        {
            if (profiles.SelectedItem is RadioButton { Tag: string id } && id != options.Profile)
            {
                options = options with { Profile = id };
                Build();
            }
        };
        root.Children.Add(profiles);

        if (options.Profile == "manual")
        {
            foreach (var (id, label) in AudioRestoration.Steps)
            {
                var slider = new Slider { Header = $"{label} ({options.Steps[id]})", Minimum = id == "brilho" ? -100 : 0, Maximum = 100, StepFrequency = 5, Value = options.Steps[id], MaxWidth = 560, HorizontalAlignment = HorizontalAlignment.Stretch };
                slider.ValueChanged += (_, e) =>
                {
                    var steps = options.Steps.ToDictionary(kv => kv.Key, kv => kv.Value);
                    steps[id] = (int)e.NewValue;
                    options = options with { Steps = steps };
                    slider.Header = $"{label} ({(int)e.NewValue})";
                };
                AutomationProperties.SetName(slider, label);
                root.Children.Add(slider);
            }
        }
        else
        {
            var intensity = new Slider { Header = $"Intensidade ({options.Intensity}%)", Minimum = 0, Maximum = 100, StepFrequency = 5, Value = options.Intensity, MaxWidth = 560, HorizontalAlignment = HorizontalAlignment.Stretch };
            intensity.ValueChanged += (_, e) => { options = options with { Intensity = (int)e.NewValue }; intensity.Header = $"Intensidade ({options.Intensity}%)"; };
            root.Children.Add(intensity);
        }
        if (options.Profile == "live")
        {
            var ambience = new Slider { Header = $"Preservar plateia e ambiência ({options.PreserveAmbience}%)", Minimum = 0, Maximum = 100, StepFrequency = 5, Value = options.PreserveAmbience, MaxWidth = 560, HorizontalAlignment = HorizontalAlignment.Stretch };
            ambience.ValueChanged += (_, e) => { options = options with { PreserveAmbience = (int)e.NewValue }; ambience.Header = $"Preservar plateia e ambiência ({options.PreserveAmbience}%)"; };
            root.Children.Add(ambience);
        }

        if (options.Profile != "manual")
        {
            var ai = new CheckBox { Content = "Reparo de compressão por IA (Apollo, experimental): recria os agudos que o MP3/AAC cortou, mantendo os graves do original", IsChecked = options.Ai };
            ai.Checked += (_, _) => { options = options with { Ai = true }; Build(); };
            ai.Unchecked += (_, _) => { options = options with { Ai = false }; Build(); };
            root.Children.Add(ai);
        }
        if (options.Ai || (options.Profile == "manual" && options.Steps.GetValueOrDefault("compressao") > 0)) root.Children.Add(ApolloModel());

        root.Children.Add(Label("O formato vem da lista acima (WAV 24 bits, FLAC 24 bits ou MP3 320 kbps). Salvar em WAV ou FLAC não melhora a qualidade por si: só evita perder mais na hora de gravar."));
        var keep = new CheckBox { Content = "Manter o volume original (o restaurado sai no mesmo volume da entrada, sem passar de −1 dBFS de pico: mais alto parece melhor sem ser)", IsChecked = options.KeepVolume };
        keep.Checked += (_, _) => options = options with { KeepVolume = true };
        keep.Unchecked += (_, _) => options = options with { KeepVolume = false };
        root.Children.Add(keep);
        var report = new CheckBox { Content = "Salvar também o relatório técnico (.json com o diagnóstico e as etapas; sem o áudio)", IsChecked = options.Report };
        report.Checked += (_, _) => options = options with { Report = true };
        report.Unchecked += (_, _) => options = options with { Report = false };
        root.Children.Add(report);
        root.Children.Add(Label("O diagnóstico e as etapas aplicadas aparecem na fila quando terminar."));
    }

    /// <summary>O modelo Apollo neste computador: baixar (conferido pelo SHA-256) ou excluir, como os de separação.</summary>
    private StackPanel ApolloModel()
    {
        var tools = App.Services.MusicTools;
        var panel = new StackPanel { Spacing = 6 };
        var status = Label("");
        var action = new Button();
        void Refresh()
        {
            var installed = tools.ApolloInstalled;
            status.Text = $"Modelo Apollo ({Engine.Music.MusicTools.ApolloBytes / 1_000_000} MB): {(installed ? "baixado neste computador" : "ainda não baixado (baixa sozinho na primeira vez)")}. Lento: cerca de 7 vezes a duração do áudio.";
            action.Content = installed ? "Excluir modelo" : "Baixar agora";
            action.IsEnabled = true;
        }
        action.Click += async (_, _) =>
        {
            action.IsEnabled = false;
            try
            {
                if (tools.ApolloInstalled) await tools.DeleteApolloAsync(CancellationToken.None);
                else await tools.EnsureApolloAsync(App.Services.Http, new Progress<double>(f => status.Text = $"Baixando o modelo Apollo… {f:P0}"), CancellationToken.None);
            }
            catch (Exception e) when (e is IOException or HttpRequestException or Core.ConversionException or UnauthorizedAccessException)
            {
                status.Text = e.Message;
                action.IsEnabled = true;
                return;
            }
            Refresh();
        };
        Refresh();
        panel.Children.Add(status);
        panel.Children.Add(action);
        panel.Children.Add(Label(Engine.Music.MusicTools.ApolloAttribution));
        return panel;
    }
}
