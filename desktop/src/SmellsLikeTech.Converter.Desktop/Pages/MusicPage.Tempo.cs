using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;
using Microsoft.UI.Xaml.Media;
using SmellsLikeTech.Converter.Core.Music;
using SmellsLikeTech.Converter.Engine.Music;

namespace SmellsLikeTech.Converter.Desktop.Pages;

public sealed partial class MusicPage
{
    private UIElement TempoPitchView()
    {
        var clips = project!.Tracks.SelectMany(t => t.Clips.Where(c => !c.Mute).Select(c => (Track: t, Clip: c))).ToList();
        if (clips.Count == 0) return Label("Importe ou grave um áudio para ajustar tempo e tom.");
        var selected = Math.Max(0, clips.FindIndex(x => x.Clip.Id == selectedClip));
        var (track, clip) = clips[selected];
        var media = project.Media.First(m => m.Id == clip.Media);
        var panel = new StackPanel { Spacing = 10, MaxWidth = 900, HorizontalAlignment = HorizontalAlignment.Left };

        UIElement Section(string title, params UIElement[] children)
        {
            var content = new StackPanel { Spacing = 8 };
            content.Children.Add(new TextBlock { Text = title, FontWeight = Microsoft.UI.Text.FontWeights.SemiBold });
            foreach (var child in children) content.Children.Add(child);
            return new Border { Padding = new Thickness(12), CornerRadius = new CornerRadius(8), BorderThickness = new Thickness(1), BorderBrush = (Brush)Application.Current.Resources["BrandStrokeBrush"], Background = (Brush)Application.Current.Resources["BrandSurfaceAltBrush"], Child = content };
        }

        var names = clips.Select(x => $"{x.Track.Name} · {x.Clip.Name ?? "Clipe"}").ToList();
        var chosen = new ComboBox { Header = "Clipe para editar ou analisar", ItemsSource = names, SelectedIndex = selected, MinWidth = 420, HorizontalAlignment = HorizontalAlignment.Left };
        chosen.SelectionChanged += (_, _) =>
        {
            if (chosen.SelectedIndex < 0) return;
            selectedClip = clips[chosen.SelectedIndex].Clip.Id;
            selectedTrack = clips[chosen.SelectedIndex].Track.Id;
            BuildPanels();
        };
        panel.Children.Add(chosen);

        var analysisText = Label("Ainda não analisado.");
        if (analyses.TryGetValue(clip.Id, out var existing)) analysisText.Text = $"{(existing.Bpm is { } bpm ? $"{bpm} BPM" : "BPM não identificado")} · {existing.Key ?? "Tonalidade não identificada"}";
        var analyze = new Button { Content = "Identificar BPM e tonalidade", Style = (Style)Application.Current.Resources["BrandActionButtonStyle"] };
        AutomationProperties.SetName(analyze, "Identificar BPM e tonalidade");
        analyze.Click += async (_, _) => await AnalyzeClip(clip, analysisText);
        panel.Children.Add(Section("1. Identificar o áudio", analyze, analysisText, Label("São estimativas feitas neste computador. Em músicas com tempo livre ou muitos instrumentos, confira de ouvido.")));

        var speedValue = Label($"Velocidade: {Math.Round((clip.Speed ?? 1) * 100)}% · {clip.Duration / (double)Rate:0.00} s");
        var speed = new Slider { Minimum = 25, Maximum = 400, StepFrequency = 1, Value = (clip.Speed ?? 1) * 100 };
        AutomationProperties.SetName(speed, $"Velocidade de {clip.Name ?? track.Name}");
        speed.ValueChanged += (_, e) => Edit(p =>
        {
            var t = p.Tracks.First(x => x.Id == track.Id);
            var index = t.Clips.FindIndex(c => c.Id == clip.Id);
            t.Clips[index] = MusicTransform.WithSpeed(t.Clips[index], media, e.NewValue / 100);
        }, $"velocidade-{clip.Id}");
        var preserve = new ToggleSwitch { Header = "Preservar o tom ao mudar a velocidade", IsOn = clip.PreservePitch ?? true };
        preserve.Toggled += (_, _) => Edit(p => p.Tracks.First(x => x.Id == track.Id).Clips.First(c => c.Id == clip.Id).PreservePitch = preserve.IsOn, rebuild: false);
        panel.Children.Add(Section("2. Mais rápida ou mais lenta", speedValue, Fine(speed, 1, false, $"velocidade de {clip.Name ?? track.Name}", width: 500), preserve, Label("Ligado: muda a duração sem mudar a afinação. Desligado: velocidade e tom mudam juntos, como numa fita.")));

        var pitch = new NumberBox { Header = "Transposição (semitons)", Minimum = -12, Maximum = 12, SmallChange = 1, Value = clip.Semitones ?? 0, SpinButtonPlacementMode = NumberBoxSpinButtonPlacementMode.Compact };
        AutomationProperties.SetName(pitch, $"Transposição de {clip.Name ?? track.Name}");
        pitch.ValueChanged += (_, e) => { if (!double.IsNaN(e.NewValue)) Edit(p => p.Tracks.First(x => x.Id == track.Id).Clips.First(c => c.Id == clip.Id).Semitones = e.NewValue, $"tom-{clip.Id}"); };
        panel.Children.Add(Section("3. Subir ou descer a tonalidade", pitch, Label("Uma oitava tem 12 semitons. A duração não muda ao transpor.")));

        var origin = new ComboBox { Header = "Ritmo de referência", ItemsSource = names, SelectedIndex = 0, MinWidth = 420, HorizontalAlignment = HorizontalAlignment.Left };
        var targetIndex = clips.Count > 1 ? 1 : 0;
        var target = new ComboBox { Header = "Clipe que vai mudar", ItemsSource = names, SelectedIndex = targetIndex, MinWidth = 420, HorizontalAlignment = HorizontalAlignment.Left };
        var both = new Button { Content = "Identificar o BPM dos dois" };
        var apply = new Button { Content = "Aplicar o ritmo da referência", Style = (Style)Application.Current.Resources["BrandActionButtonStyle"] };
        AutomationProperties.SetName(apply, "Aplicar o ritmo da referência");
        var matchText = Label("Identifique os dois clipes antes de aplicar.");
        void UpdateMatch()
        {
            var a = clips[Math.Max(0, origin.SelectedIndex)].Clip.Id;
            var b = clips[Math.Max(0, target.SelectedIndex)].Clip.Id;
            var bpmA = analyses.GetValueOrDefault(a)?.Bpm;
            var bpmB = analyses.GetValueOrDefault(b)?.Bpm;
            matchText.Text = $"Referência: {(bpmA is { } x ? $"{x} BPM" : "—")} · destino: {(bpmB is { } y ? $"{y} BPM" : "—")}";
            apply.IsEnabled = a != b && bpmA is not null && bpmB is not null;
        }
        origin.SelectionChanged += (_, _) => UpdateMatch();
        target.SelectionChanged += (_, _) => UpdateMatch();
        both.Click += async (_, _) =>
        {
            both.IsEnabled = false;
            await AnalyzeClip(clips[Math.Max(0, origin.SelectedIndex)].Clip, null);
            await AnalyzeClip(clips[Math.Max(0, target.SelectedIndex)].Clip, null);
            both.IsEnabled = true;
            UpdateMatch();
        };
        apply.Click += (_, _) =>
        {
            var sourceClip = clips[Math.Max(0, origin.SelectedIndex)].Clip;
            var targetPair = clips[Math.Max(0, target.SelectedIndex)];
            var sourceBpm = analyses[sourceClip.Id].Bpm;
            var targetBpm = analyses[targetPair.Clip.Id].Bpm;
            if (sourceBpm is null || targetBpm is null) return;
            var speedToMatch = MusicTransform.SpeedToMatch(sourceBpm.Value, targetBpm.Value);
            Edit(p =>
            {
                var t = p.Tracks.First(x => x.Id == targetPair.Track.Id);
                var index = t.Clips.FindIndex(c => c.Id == targetPair.Clip.Id);
                t.Clips[index] = MusicTransform.WithSpeed(t.Clips[index], p.Media.First(m => m.Id == targetPair.Clip.Media), speedToMatch);
                t.Clips[index].PreservePitch = true;
            });
            Status($"{targetPair.Clip.Name ?? "O clipe"} agora toca a {speedToMatch * 100:0.0}% para acompanhar {sourceBpm} BPM, sem mudar o tom.", InfoBarSeverity.Success);
        };
        UpdateMatch();
        panel.Children.Add(Section("4. Igualar o ritmo de outra faixa", origin, target, both, matchText, apply, Label("O destino é acelerado ou desacelerado com o tom preservado. Escolha qualquer clipe como origem e qualquer outro como destino.")));
        return panel;
    }

    private async Task AnalyzeClip(MusicClip clip, TextBlock? output)
    {
        Status("Identificando BPM e tonalidade…", InfoBarSeverity.Informational);
        try
        {
            var result = await Task.Run(() =>
            {
                using var source = new MappedWavSource(Library.MediaPath(project!.Id, clip.Media));
                return MusicAnalyzer.Analyze(MusicTransform.ReadAll(source), Rate);
            });
            analyses[clip.Id] = result;
            if (output is not null) output.Text = $"{(result.Bpm is { } bpm ? $"{bpm} BPM" : "BPM não identificado")} · {result.Key ?? "Tonalidade não identificada"}";
            Status(result.Bpm is null && result.Key is null ? "Não foi possível estimar este áudio. Tente um trecho com ritmo ou notas mais claros." : "Análise concluída. Confira a estimativa de ouvido antes de usar como referência.", InfoBarSeverity.Success);
        }
        catch (Exception e) when (e is IOException or Core.ConversionException)
        {
            Status(e.Message, InfoBarSeverity.Error);
        }
    }
}
