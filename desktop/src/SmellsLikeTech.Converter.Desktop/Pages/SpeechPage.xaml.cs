using System.Globalization;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using SmellsLikeTech.Converter.Core;
using SmellsLikeTech.Converter.Core.Abstractions;
using SmellsLikeTech.Converter.Core.Jobs;
using SmellsLikeTech.Converter.Core.Media;
using SmellsLikeTech.Converter.Core.Options;
using SmellsLikeTech.Converter.Composition;
using SmellsLikeTech.Converter.Desktop.Services;
using SmellsLikeTech.Converter.Desktop.ViewModels;
using Windows.Media.Core;
using Windows.Media.Playback;
using Windows.Storage.Pickers;

namespace SmellsLikeTech.Converter.Desktop.Pages;

/// <summary>
/// Text-to-Speech com as vozes realmente instaladas no Windows. A interface só
/// oferece IDs anunciados pelo motor - nunca um nome de voz digitado.
/// </summary>
public sealed partial class SpeechPage : Page
{
    private IReadOnlyList<SpeechVoice> voices = [];
    private string outputDirectory = string.Empty;
    private MediaPlayer? previewPlayer;

    public SpeechPage()
    {
        InitializeComponent();
        Loaded += OnLoaded;
        Unloaded += OnUnloaded;
    }

    private void OnLoaded(object sender, RoutedEventArgs e)
    {
        outputDirectory = App.Services.OutputDirectory;
        OutputFolderText.Text = outputDirectory;

        voices = App.Services.TtsEngine.ListVoices();
        VoiceCombo.ItemsSource = voices
            .Select(voice => new Choice<string>($"{voice.DisplayName} · {voice.Language}", voice.Id))
            .ToList();

        var preferred = App.Services.Settings.DefaultTtsVoiceId;
        var preferredIndex = voices.ToList().FindIndex(voice => voice.Id == preferred);
        VoiceCombo.SelectedIndex = preferredIndex >= 0 ? preferredIndex : voices.Count > 0 ? 0 : -1;

        VoiceWarningBar.IsOpen = voices.Count == 0;
        GenerateButton.IsEnabled = voices.Count > 0;
        PreviewButton.IsEnabled = voices.Count > 0;

        FormatCombo.ItemsSource = FormatCatalog.AudioOutputFormats
            .Select(format => new Choice<string>(format.ToUpperInvariant(), format))
            .ToList();
        FormatCombo.SelectedIndex = 0;

        List<Choice<int?>> bitrates =
        [
            new("Automático", null),
            .. AudioAllowlist.Bitrates.Select(bitrate => new Choice<int?>($"{bitrate} kbps", bitrate))
        ];
        BitrateCombo.ItemsSource = bitrates;
        BitrateCombo.SelectedIndex = 0;

        UpdateCharacterCount();
    }

    private void OnUnloaded(object sender, RoutedEventArgs e) => ReleasePreview();

    private void OnTextChanged(object sender, TextChangedEventArgs e) => UpdateCharacterCount();

    private void UpdateCharacterCount()
    {
        var limit = App.Services.Settings.MaxTextToSpeechCharacters;
        var length = TextInput.Text.Length;
        CharacterCountText.Text = $"{length:N0} de {limit:N0} caracteres";
        CharacterCountText.Opacity = length > limit ? 1 : 0.75;
    }

    private void OnRateChanged(object sender, Microsoft.UI.Xaml.Controls.Primitives.RangeBaseValueChangedEventArgs e)
    {
        if (RateValueText is not null)
        {
            RateValueText.Text = ((int)e.NewValue).ToString("+0;-0;0", CultureInfo.CurrentCulture);
        }
    }

    private void OnVolumeChanged(object sender, Microsoft.UI.Xaml.Controls.Primitives.RangeBaseValueChangedEventArgs e)
    {
        if (VolumeValueText is not null)
        {
            VolumeValueText.Text = $"{(int)e.NewValue}%";
        }
    }

    private async void OnLoadTextFile(object sender, RoutedEventArgs e)
    {
        try
        {
            var caminho = FileDialogs.PickTextFile(App.MainWindow);
            if (caminho is null)
            {
                return;
            }

            var info = new FileInfo(caminho);
            if (info.Length > 8 * 1024 * 1024)
            {
                ShowStatus("Arquivo de texto grande demais (limite de 8 MB).", InfoBarSeverity.Error);
                return;
            }

            TextInput.Text = await File.ReadAllTextAsync(caminho, System.Text.Encoding.UTF8);
            UpdateCharacterCount();
        }
        catch (Exception exception)
        {
            App.Services.Log.Error("text_file_open_failed", "Não foi possível abrir o arquivo de texto.", exception);
            ShowStatus("Não foi possível ler o arquivo de texto.", InfoBarSeverity.Error);
        }
    }

    private void OnClearText(object sender, RoutedEventArgs e)
    {
        TextInput.Text = string.Empty;
        UpdateCharacterCount();
    }

    private async void OnPreview(object sender, RoutedEventArgs e)
    {
        var voiceId = SelectedVoiceId();
        if (voiceId is null)
        {
            return;
        }

        var text = TextInput.Text.Trim();
        if (text.Length == 0)
        {
            ShowStatus("Escreva um texto para ouvir a amostra.", InfoBarSeverity.Warning);
            return;
        }

        // Amostra curta: só o começo do texto.
        var sample = text.Length > 220 ? text[..220] : text;
        PreviewButton.IsEnabled = false;

        try
        {
            var samplePath = Path.Combine(App.Services.Paths.Cache, $"preview_{Guid.NewGuid():N}.wav");
            Directory.CreateDirectory(App.Services.Paths.Cache);

            await App.Services.TtsEngine.SynthesizeToWaveAsync(
                sample,
                voiceId,
                (int)RateSlider.Value,
                (int)VolumeSlider.Value,
                samplePath,
                null,
                CancellationToken.None);

            ReleasePreview();
            previewPlayer = new MediaPlayer
            {
                AutoPlay = true,
                Source = MediaSource.CreateFromUri(new Uri(samplePath))
            };
        }
        catch (ConversionException exception)
        {
            ShowStatus(exception.Message, InfoBarSeverity.Error);
        }
        finally
        {
            PreviewButton.IsEnabled = true;
        }
    }

    private async void OnPickOutputFolder(object sender, RoutedEventArgs e)
    {
        try
        {
            var pasta = FileDialogs.PickOutputFolder(App.MainWindow);
            if (pasta is null)
            {
                return;
            }

            outputDirectory = pasta;
            OutputFolderText.Text = outputDirectory;
            await App.Services.SettingsStore.RememberFolderAsync(outputDirectory, CancellationToken.None);
        }
        catch (Exception exception)
        {
            App.Services.Log.Error("folder_picker_failed", "A escolha de pasta falhou.", exception);
            ShowStatus("Não foi possível abrir a escolha de pasta.", InfoBarSeverity.Error);
        }
    }

    private void OnGenerate(object sender, RoutedEventArgs e)
    {
        var voiceId = SelectedVoiceId();
        if (voiceId is null)
        {
            ShowStatus("Selecione uma voz Kokoro-82M.", InfoBarSeverity.Warning);
            return;
        }

        var text = TextInput.Text.Trim();
        if (text.Length == 0)
        {
            ShowStatus("Escreva ou cole um texto.", InfoBarSeverity.Warning);
            return;
        }

        try
        {
            var options = new SynthesizeOptions
            {
                OutputFormat = (FormatCombo.SelectedItem as Choice<string>)?.Value ?? "mp3",
                VoiceId = voiceId,
                Rate = (int)RateSlider.Value,
                Volume = (int)VolumeSlider.Value,
                AudioBitrateKbps = (BitrateCombo.SelectedItem as Choice<int?>)?.Value
            };
            options.Validate();

            if (!LicenseGate.CanConvert(out var licenca))
            {
                ShowStatus(licenca, InfoBarSeverity.Warning);
                return;
            }

            var job = new ConversionJob
            {
                Options = options,
                InputText = text,
                OutputDirectory = string.IsNullOrWhiteSpace(outputDirectory)
                    ? App.Services.OutputDirectory
                    : outputDirectory,
                OutputBaseName = $"voz_{DateTime.Now:yyyyMMdd_HHmmss}"
            };

            App.Services.Queue.Enqueue(job);
            ShowStatus($"Síntese de {text.Length:N0} caracteres enviada para a fila.", InfoBarSeverity.Success);

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

    private string? SelectedVoiceId() => (VoiceCombo.SelectedItem as Choice<string>)?.Value;

    private void ShowStatus(string message, InfoBarSeverity severity)
    {
        StatusBar.Severity = severity;
        StatusBar.Message = message;
        StatusBar.IsOpen = true;
    }

    private void ReleasePreview()
    {
        previewPlayer?.Dispose();
        previewPlayer = null;
    }

    private static void InitializeWithWindow(object picker)
    {
        var window = App.MainWindow ?? throw new InvalidOperationException("Janela principal indisponível.");
        var handle = WinRT.Interop.WindowNative.GetWindowHandle(window);
        WinRT.Interop.InitializeWithWindow.Initialize(picker, handle);
    }
}
