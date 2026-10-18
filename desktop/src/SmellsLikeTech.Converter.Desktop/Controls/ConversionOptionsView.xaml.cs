using System.Globalization;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using SmellsLikeTech.Converter.Core;
using SmellsLikeTech.Converter.Core.Media;
using SmellsLikeTech.Converter.Core.Operations;
using SmellsLikeTech.Converter.Core.Options;
using SmellsLikeTech.Converter.Desktop.ViewModels;
using SmellsLikeTech.Converter.Engine.Documents;
using Windows.Media.Core;
using Windows.Media.Playback;

namespace SmellsLikeTech.Converter.Desktop.Controls;

/// <summary>
/// Os campos de opção de todas as conversões, em um controle só. Quem usa diz qual é a
/// operação; o controle mostra apenas os campos daquela operação, devolve as opções
/// montadas em <see cref="Build"/> e aceita um conjunto pronto em <see cref="Apply"/>.
/// Nenhuma conversão acontece aqui — isto é formulário, não motor.
/// </summary>
public sealed partial class ConversionOptionsView : UserControl
{
    private OperationDescriptor? operation;
    private bool suppressSelection;

    public ConversionOptionsView()
    {
        InitializeComponent();
        PopulateStaticChoices();
        Unloaded += (_, _) => ClearTrimPreview();
    }

    /// <summary>
    /// Operação atualmente exibida, ou nula enquanto nada foi escolhido. Interna de
    /// propósito: propriedade pública em UserControl entra na tabela de tipos do XAML, e
    /// o gerador tenta construir um <see cref="OperationDescriptor"/> vazio — que não existe.
    /// </summary>
    internal OperationDescriptor? Operation => operation;

    /// <summary>
    /// Prepara o formulário para uma operação. <paramref name="sample"/> é o arquivo que
    /// dita os padrões sugeridos. Com <paramref name="allowTrackChoice"/> falso, a escolha
    /// de faixa de áudio some: no lote configurado de uma vez, cada arquivo tem as suas
    /// faixas, e oferecer "Faixa 2" para todos seria prometer o que nem todos têm.
    /// </summary>
    internal void ShowFor(
        OperationDescriptor descriptor,
        MediaInfo? sample,
        JobOptions? options = null,
        bool allowTrackChoice = true)
    {
        operation = descriptor;
        var values = options ?? descriptor.DefaultOptions(sample);

        PopulateFormats(descriptor, values);
        ShowPanelsFor(descriptor.Id);
        PopulateAudioTracks(allowTrackChoice ? sample : null);
        Apply(values);
        ShowTrimPreview(sample);
    }

    /// <summary>Opções montadas a partir do que está na tela. Quem chama é que valida.</summary>
    internal JobOptions Build()
    {
        var format = (FormatCombo.SelectedItem as Choice<string>)?.Value ?? "mp4";

        return operation?.Id switch
        {
            OperationIds.ImageConvert => new ImageConvertOptions
            {
                OutputFormat = format,
                ResizeMode = Value(ResizeModeCombo, ImageResizeMode.None),
                Width = (int)ImageWidthBox.Value,
                Height = (int)ImageHeightBox.Value,
                Percent = (int)ImagePercentBox.Value,
                AspectRatio = Value(ImageAspectCombo, AspectRatioMode.Original),
                Fit = Value(ImageFitCombo, FitMode.Crop),
                Quality = (int)ImageQualitySlider.Value,
                Rotation = Value(ImageRotationCombo, RotationDegrees.None),
                FlipHorizontal = ImageFlipHorizontalCheck.IsChecked == true,
                KeepTransparency = KeepTransparencyCheck.IsChecked == true,
                StripMetadata = StripMetadataCheck.IsChecked == true
            },

            OperationIds.VideoConvert => BuildVideoOptions(format),

            OperationIds.VideoExtractAudio => new ExtractAudioOptions
            {
                OutputFormat = format,
                AudioStreamIndex = Value(AudioTrackCombo, 0),
                AudioBitrateKbps = Value(AudioBitrateCombo, (int?)null),
                SampleRateHz = Value(SampleRateCombo, (int?)null),
                Channels = Value(ChannelsCombo, (int?)null),
                Normalize = NormalizeCheck.IsChecked == true,
                TrimStart = TrimTime.Parse(TrimStartBox.Text),
                TrimDuration = TrimDurationFromScreen()
            },

            OperationIds.AudioConvert => new AudioConvertOptions
            {
                OutputFormat = format,
                AudioBitrateKbps = Value(AudioBitrateCombo, (int?)null),
                SampleRateHz = Value(SampleRateCombo, (int?)null),
                Channels = Value(ChannelsCombo, (int?)null),
                Normalize = NormalizeCheck.IsChecked == true,
                TrimStart = TrimTime.Parse(TrimStartBox.Text),
                TrimDuration = TrimDurationFromScreen()
            },

            OperationIds.VideoToGif => new VideoToGifOptions
            {
                FrameRate = Value(GifFpsCombo, 12),
                Width = Value(GifWidthCombo, 480),
                TrimStart = TrimTime.Parse(TrimStartBox.Text),
                TrimDuration = TrimDurationFromScreen() ?? TimeSpan.FromSeconds(10)
            },

            OperationIds.VideoToFrames => new VideoToFramesOptions
            {
                ImageFormat = format,
                FramesPerSecond = Value(FramesRateCombo, 1.0),
                MaxFrames = (int)MaxFramesBox.Value,
                TrimStart = TrimTime.Parse(TrimStartBox.Text),
                TrimDuration = TrimDurationFromScreen()
            },

            OperationIds.SpeechTranscribe => new TranscribeOptions
            {
                OutputFormat = format,
                Language = Value(LanguageCombo, "auto"),
                Model = Value(ModelCombo, "base"),
                UseGpu = UseGpuCheck.IsChecked == true,
                AudioStreamIndex = Value(AudioTrackCombo, 0)
            },

            OperationIds.PdfToDocument => new PdfToDocumentOptions
            {
                OutputFormat = format,
                UseOcrWhenNeeded = PdfOcrCheck.IsChecked == true,
                IncludePageMarks = PdfPageMarksCheck.IsChecked == true,
                OcrLanguage = Value(PdfOcrLanguageCombo, "pt-BR")
            },

            OperationIds.OcrImageToText => new OcrOptions
            {
                OutputFormat = format,
                Language = Value(OcrLanguageCombo, "pt-BR"),
                KeepLineBreaks = OcrLineBreaksCheck.IsChecked == true
            },

            OperationIds.PdfToImage => new PdfToImageOptions
            {
                OutputFormat = format,
                Dpi = Value(PdfDpiCombo, 150),
                MaxPages = (int)PdfMaxPagesBox.Value
            },

            _ => throw new ConversionException("operation_not_available", "Selecione uma conversão disponível.")
        };
    }

    private VideoConvertOptions BuildVideoOptions(string format)
    {
        var resolution = Value(ResolutionCombo, 0);
        var custom = resolution == -1;

        return new VideoConvertOptions
        {
            OutputFormat = format,
            VideoCodec = Value(CodecCombo, VideoCodecChoice.Automatic),
            Acceleration = App.Services.Settings.Acceleration,
            Quality = (int)QualitySlider.Value,
            TargetHeight = resolution > 0 ? resolution : null,
            CustomWidth = custom ? (int)CustomWidthBox.Value : null,
            CustomHeight = custom ? (int)CustomHeightBox.Value : null,
            AspectRatio = Value(AspectCombo, AspectRatioMode.Original),
            Fit = Value(FitCombo, FitMode.Crop),
            FrameRate = Value(FpsCombo, (int?)null),
            Rotation = Value(RotationCombo, RotationDegrees.None),
            FlipHorizontal = FlipHorizontalCheck.IsChecked == true,
            FlipVertical = FlipVerticalCheck.IsChecked == true,
            Speed = Value(SpeedCombo, 1.0),
            Audio = Value(AudioActionCombo, AudioTrackAction.Keep),
            TrimStart = TrimTime.Parse(TrimStartBox.Text),
            TrimDuration = TrimDurationFromScreen()
        };
    }

    /// <summary>Escreve nos campos um conjunto pronto de opções: padrão, preset ou salvo.</summary>
    internal void Apply(JobOptions options)
    {
        suppressSelection = true;

        switch (options)
        {
            case VideoConvertOptions video:
                SelectValue(FormatCombo, video.OutputFormat);
                SelectValue(CodecCombo, video.VideoCodec);
                SelectValue(ResolutionCombo, video.CustomWidth is not null ? -1 : video.TargetHeight ?? 0);
                CustomSizePanel.Visibility = Collapsed(video.CustomWidth is not null);
                CustomWidthBox.Value = video.CustomWidth ?? 1080;
                CustomHeightBox.Value = video.CustomHeight ?? 1920;
                SelectValue(AspectCombo, video.AspectRatio);
                SelectValue(FitCombo, video.Fit);
                SelectValue(FpsCombo, video.FrameRate);
                SelectValue(SpeedCombo, video.Speed);
                SelectValue(RotationCombo, video.Rotation);
                SelectValue(AudioActionCombo, video.Audio);
                QualitySlider.Value = video.Quality;
                QualityValueText.Text = video.Quality.ToString(CultureInfo.CurrentCulture);
                FlipHorizontalCheck.IsChecked = video.FlipHorizontal;
                FlipVerticalCheck.IsChecked = video.FlipVertical;
                TrimStartBox.Text = OptionalTime(video.TrimStart);
                TrimEndBox.Text = OptionalTime(TrimTime.EndFromDuration(video.TrimStart, video.TrimDuration));
                break;

            case ExtractAudioOptions audio:
                SelectValue(FormatCombo, audio.OutputFormat);
                SelectValue(AudioTrackCombo, audio.AudioStreamIndex);
                SelectValue(AudioBitrateCombo, audio.AudioBitrateKbps);
                SelectValue(SampleRateCombo, audio.SampleRateHz);
                SelectValue(ChannelsCombo, audio.Channels);
                NormalizeCheck.IsChecked = audio.Normalize;
                TrimStartBox.Text = OptionalTime(audio.TrimStart);
                TrimEndBox.Text = OptionalTime(TrimTime.EndFromDuration(audio.TrimStart, audio.TrimDuration));
                break;

            case AudioConvertOptions audio:
                SelectValue(FormatCombo, audio.OutputFormat);
                SelectValue(AudioBitrateCombo, audio.AudioBitrateKbps);
                SelectValue(SampleRateCombo, audio.SampleRateHz);
                SelectValue(ChannelsCombo, audio.Channels);
                NormalizeCheck.IsChecked = audio.Normalize;
                TrimStartBox.Text = OptionalTime(audio.TrimStart);
                TrimEndBox.Text = OptionalTime(TrimTime.EndFromDuration(audio.TrimStart, audio.TrimDuration));
                break;

            case ImageConvertOptions image:
                SelectValue(FormatCombo, image.OutputFormat);
                SelectValue(ResizeModeCombo, image.ResizeMode);
                ImageSizePanel.Visibility = Collapsed(image.ResizeMode != ImageResizeMode.None);
                ImageWidthPanel.Visibility = Collapsed(image.ResizeMode is ImageResizeMode.Width or ImageResizeMode.Exact);
                ImageHeightPanel.Visibility = Collapsed(image.ResizeMode is ImageResizeMode.Height or ImageResizeMode.Exact);
                ImagePercentPanel.Visibility = Collapsed(image.ResizeMode == ImageResizeMode.Percent);
                ImageWidthBox.Value = image.Width ?? 1920;
                ImageHeightBox.Value = image.Height ?? 1080;
                ImagePercentBox.Value = image.Percent ?? 50;
                SelectValue(ImageAspectCombo, image.AspectRatio);
                SelectValue(ImageFitCombo, image.Fit);
                SelectValue(ImageRotationCombo, image.Rotation);
                ImageQualitySlider.Value = image.Quality;
                ImageQualityValueText.Text = image.Quality.ToString(CultureInfo.CurrentCulture);
                ImageFlipHorizontalCheck.IsChecked = image.FlipHorizontal;
                KeepTransparencyCheck.IsChecked = image.KeepTransparency;
                StripMetadataCheck.IsChecked = image.StripMetadata;
                break;

            case VideoToGifOptions gif:
                SelectValue(GifFpsCombo, gif.FrameRate);
                SelectValue(GifWidthCombo, gif.Width);
                TrimStartBox.Text = OptionalTime(gif.TrimStart);
                TrimEndBox.Text = OptionalTime(TrimTime.EndFromDuration(gif.TrimStart, gif.TrimDuration));
                break;

            case VideoToFramesOptions frames:
                SelectValue(FormatCombo, frames.ImageFormat);
                SelectValue(FramesRateCombo, frames.FramesPerSecond);
                MaxFramesBox.Value = frames.MaxFrames;
                TrimStartBox.Text = OptionalTime(frames.TrimStart);
                TrimEndBox.Text = OptionalTime(TrimTime.EndFromDuration(frames.TrimStart, frames.TrimDuration));
                break;

            case PdfToDocumentOptions pdf:
                SelectValue(FormatCombo, pdf.OutputFormat);
                SelectValue(PdfOcrLanguageCombo, pdf.OcrLanguage);
                PdfOcrCheck.IsChecked = pdf.UseOcrWhenNeeded;
                PdfPageMarksCheck.IsChecked = pdf.IncludePageMarks;
                break;

            case OcrOptions ocr:
                SelectValue(FormatCombo, ocr.OutputFormat);
                SelectValue(OcrLanguageCombo, ocr.Language);
                OcrLineBreaksCheck.IsChecked = ocr.KeepLineBreaks;
                break;

            case PdfToImageOptions paginas:
                SelectValue(FormatCombo, paginas.OutputFormat);
                SelectValue(PdfDpiCombo, paginas.Dpi);
                PdfMaxPagesBox.Value = paginas.MaxPages;
                break;

            case TranscribeOptions transcribe:
                SelectValue(FormatCombo, transcribe.OutputFormat);
                SelectValue(LanguageCombo, transcribe.Language);
                SelectValue(ModelCombo, App.Services.Models.ResolvePreferred(App.Services.Settings.DefaultWhisperModel));
                UseGpuCheck.IsChecked = transcribe.UseGpu;
                break;
        }

        suppressSelection = false;
    }

    // ==================== Preenchimento ====================

    private void PopulateFormats(OperationDescriptor descriptor, JobOptions defaults)
    {
        var formats = descriptor.OutputFormats
            .Select(format => new Choice<string>(format.ToUpperInvariant(), format))
            .ToList();

        suppressSelection = true;
        FormatCombo.ItemsSource = formats;
        FormatCombo.SelectedIndex = Math.Max(0, formats.FindIndex(choice =>
            string.Equals(choice.Value, defaults.OutputExtension, StringComparison.OrdinalIgnoreCase)));
        suppressSelection = false;
    }

    private void ShowPanelsFor(string operationId)
    {
        VideoPanel.Visibility = Collapsed(operationId is OperationIds.VideoConvert);
        AudioPanel.Visibility = Collapsed(operationId is OperationIds.VideoExtractAudio or OperationIds.AudioConvert);
        ImagePanel.Visibility = Collapsed(operationId is OperationIds.ImageConvert);
        GifPanel.Visibility = Collapsed(operationId is OperationIds.VideoToGif);
        FramesPanel.Visibility = Collapsed(operationId is OperationIds.VideoToFrames);
        TranscribePanel.Visibility = Collapsed(operationId is OperationIds.SpeechTranscribe);
        PdfPanel.Visibility = Collapsed(operationId is OperationIds.PdfToDocument);
        OcrPanel.Visibility = Collapsed(operationId is OperationIds.OcrImageToText);
        PdfImagePanel.Visibility = Collapsed(operationId is OperationIds.PdfToImage);
        TrimPanel.Visibility = Collapsed(operationId is OperationIds.VideoConvert
            or OperationIds.VideoExtractAudio
            or OperationIds.AudioConvert
            or OperationIds.VideoToGif
            or OperationIds.VideoToFrames);
    }

    private void PopulateAudioTracks(MediaInfo? sample)
    {
        var tracks = sample?.AudioStreams ?? [];
        AudioTrackPanel.Visibility = Collapsed(tracks.Count > 1);
        if (tracks.Count <= 1)
        {
            AudioTrackCombo.ItemsSource = null;
            return;
        }

        var items = tracks
            .Select((stream, index) => new Choice<int>(
                $"Faixa {index + 1} · {stream.Codec}{(stream.Language is null ? string.Empty : $" ({stream.Language})")}",
                index))
            .ToList();

        AudioTrackCombo.ItemsSource = items;
        AudioTrackCombo.SelectedIndex = 0;
    }

    private static Visibility Collapsed(bool visible) => visible ? Visibility.Visible : Visibility.Collapsed;

    // ==================== Eventos dos campos ====================

    private void OnFormatChanged(object sender, SelectionChangedEventArgs e)
    {
        if (suppressSelection || FormatCombo.SelectedItem is not Choice<string> choice)
        {
            return;
        }

        // Formatos sem perda não usam bitrate.
        var lossless = AudioAllowlist.IsLossless(choice.Value);
        AudioBitrateCombo.IsEnabled = !lossless;
        if (lossless)
        {
            SelectValue(AudioBitrateCombo, (int?)null);
        }
    }

    private void OnResolutionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (ResolutionCombo.SelectedItem is Choice<int> choice)
        {
            CustomSizePanel.Visibility = Collapsed(choice.Value == -1);
        }
    }

    private void OnResizeModeChanged(object sender, SelectionChangedEventArgs e)
    {
        if (ResizeModeCombo.SelectedItem is not Choice<ImageResizeMode> choice)
        {
            return;
        }

        ImageSizePanel.Visibility = Collapsed(choice.Value != ImageResizeMode.None);
        ImageWidthPanel.Visibility = Collapsed(choice.Value is ImageResizeMode.Width or ImageResizeMode.Exact);
        ImageHeightPanel.Visibility = Collapsed(choice.Value is ImageResizeMode.Height or ImageResizeMode.Exact);
        ImagePercentPanel.Visibility = Collapsed(choice.Value == ImageResizeMode.Percent);
    }

    private void OnQualityChanged(object sender, Microsoft.UI.Xaml.Controls.Primitives.RangeBaseValueChangedEventArgs e)
    {
        if (QualityValueText is not null)
        {
            QualityValueText.Text = ((int)e.NewValue).ToString(CultureInfo.CurrentCulture);
        }
    }

    private void OnImageQualityChanged(object sender, Microsoft.UI.Xaml.Controls.Primitives.RangeBaseValueChangedEventArgs e)
    {
        if (ImageQualityValueText is not null)
        {
            ImageQualityValueText.Text = ((int)e.NewValue).ToString(CultureInfo.CurrentCulture);
        }
    }

    // ==================== Escolhas fixas ====================

    private void PopulateStaticChoices()
    {
        CodecCombo.ItemsSource = new List<Choice<VideoCodecChoice>>
        {
            new("Automático", VideoCodecChoice.Automatic),
            new("H.264", VideoCodecChoice.H264),
            new("H.265 / HEVC", VideoCodecChoice.H265),
            new("VP9", VideoCodecChoice.Vp9),
            new("AV1", VideoCodecChoice.Av1),
            new("Copiar stream", VideoCodecChoice.CopyStream)
        };
        CodecCombo.SelectedIndex = 0;

        ResolutionCombo.ItemsSource = new List<Choice<int>>
        {
            new("Original", 0),
            new("2160p (4K)", 2160),
            new("1440p", 1440),
            new("1080p", 1080),
            new("720p", 720),
            new("480p", 480),
            new("360p", 360),
            new("Personalizada", -1)
        };
        ResolutionCombo.SelectedIndex = 0;

        var aspects = new List<Choice<AspectRatioMode>>
        {
            new("Original", AspectRatioMode.Original),
            new("16:9", AspectRatioMode.Wide16x9),
            new("9:16", AspectRatioMode.Vertical9x16),
            new("1:1", AspectRatioMode.Square1x1),
            new("4:3", AspectRatioMode.Classic4x3),
            new("3:2", AspectRatioMode.Photo3x2),
            new("21:9", AspectRatioMode.Ultra21x9)
        };
        AspectCombo.ItemsSource = aspects;
        AspectCombo.SelectedIndex = 0;
        ImageAspectCombo.ItemsSource = new List<Choice<AspectRatioMode>>(aspects);
        ImageAspectCombo.SelectedIndex = 0;

        var fits = new List<Choice<FitMode>>
        {
            new("Recortar", FitMode.Crop),
            new("Ajustar", FitMode.Contain),
            new("Fundo desfocado", FitMode.Blur),
            new("Esticar", FitMode.Stretch)
        };
        FitCombo.ItemsSource = fits;
        FitCombo.SelectedIndex = 0;
        ImageFitCombo.ItemsSource = new List<Choice<FitMode>>(fits);
        ImageFitCombo.SelectedIndex = 0;

        FpsCombo.ItemsSource = new List<Choice<int?>>
        {
            new("Original", null),
            new("24", 24),
            new("25", 25),
            new("30", 30),
            new("50", 50),
            new("60", 60)
        };
        FpsCombo.SelectedIndex = 0;

        SpeedCombo.ItemsSource = new List<Choice<double>>
        {
            new("0,25x", 0.25),
            new("0,5x", 0.5),
            new("Normal", 1.0),
            new("1,5x", 1.5),
            new("2x", 2.0),
            new("4x", 4.0)
        };
        SpeedCombo.SelectedIndex = 2;

        var rotations = new List<Choice<RotationDegrees>>
        {
            new("Sem rotação", RotationDegrees.None),
            new("90° horário", RotationDegrees.Clockwise90),
            new("180°", RotationDegrees.Half180),
            new("90° anti-horário", RotationDegrees.CounterClockwise270)
        };
        RotationCombo.ItemsSource = rotations;
        RotationCombo.SelectedIndex = 0;
        ImageRotationCombo.ItemsSource = new List<Choice<RotationDegrees>>(rotations);
        ImageRotationCombo.SelectedIndex = 0;

        AudioActionCombo.ItemsSource = new List<Choice<AudioTrackAction>>
        {
            new("Manter", AudioTrackAction.Keep),
            new("Remover", AudioTrackAction.Remove),
            new("Recodificar", AudioTrackAction.Reencode)
        };
        AudioActionCombo.SelectedIndex = 0;

        List<Choice<int?>> bitrates =
        [
            new("Automático", null),
            .. AudioAllowlist.Bitrates.Select(bitrate => new Choice<int?>($"{bitrate} kbps", bitrate))
        ];
        AudioBitrateCombo.ItemsSource = bitrates;
        AudioBitrateCombo.SelectedIndex = 0;

        List<Choice<int?>> sampleRates =
        [
            new("Original", null),
            .. AudioAllowlist.SampleRates.Select(rate => new Choice<int?>($"{rate / 1000.0:0.###} kHz", rate))
        ];
        SampleRateCombo.ItemsSource = sampleRates;
        SampleRateCombo.SelectedIndex = 0;

        ChannelsCombo.ItemsSource = new List<Choice<int?>>
        {
            new("Original", null),
            new("Mono", 1),
            new("Estéreo", 2)
        };
        ChannelsCombo.SelectedIndex = 0;

        ResizeModeCombo.ItemsSource = new List<Choice<ImageResizeMode>>
        {
            new("Não redimensionar", ImageResizeMode.None),
            new("Por largura", ImageResizeMode.Width),
            new("Por altura", ImageResizeMode.Height),
            new("Largura e altura", ImageResizeMode.Exact),
            new("Porcentagem", ImageResizeMode.Percent)
        };
        ResizeModeCombo.SelectedIndex = 0;

        GifFpsCombo.ItemsSource = VideoToGifOptions.FrameRates
            .Select(rate => new Choice<int>($"{rate} fps", rate)).ToList();
        GifFpsCombo.SelectedIndex = 2;

        GifWidthCombo.ItemsSource = VideoToGifOptions.Widths
            .Select(width => new Choice<int>($"{width} px", width)).ToList();
        GifWidthCombo.SelectedIndex = 2;

        FramesRateCombo.ItemsSource = VideoToFramesOptions.Rates
            .Select(rate => new Choice<double>(
                rate < 1 ? $"1 a cada {1 / rate:0} s" : $"{rate:0.##} por segundo",
                rate))
            .ToList();
        FramesRateCombo.SelectedIndex = 3;

        LanguageCombo.ItemsSource = TranscribeOptions.Languages
            .Select(pair => new Choice<string>(pair.Value, pair.Key)).ToList();
        LanguageCombo.SelectedIndex = 0;

        // Reconhecimento de texto: só os idiomas realmente instalados no Windows.
        var instalados = WindowsTextRecognizer.AvailableLanguages();
        var idiomasOcr = OcrLanguages.All
            .Where(pair => instalados.Any(tag =>
                tag.Equals(pair.Key, StringComparison.OrdinalIgnoreCase)
                || tag.StartsWith(pair.Key + "-", StringComparison.OrdinalIgnoreCase)))
            .Select(pair => new Choice<string>(pair.Value, pair.Key))
            .ToList();

        if (idiomasOcr.Count == 0)
        {
            idiomasOcr.Add(new Choice<string>("Português (Brasil)", "pt-BR"));
        }

        OcrLanguageCombo.ItemsSource = idiomasOcr;
        OcrLanguageCombo.SelectedIndex = 0;
        PdfOcrLanguageCombo.ItemsSource = new List<Choice<string>>(idiomasOcr);
        PdfOcrLanguageCombo.SelectedIndex = 0;

        OcrStatusText.Text = instalados.Count > 0
            ? $"Reconhecimento do Windows disponível em: {string.Join(", ", instalados)}."
            : "Nenhum pacote de reconhecimento instalado. Adicione em Configurações do Windows › Hora e idioma › Idioma.";

        PdfDpiCombo.ItemsSource = PdfToImageOptions.DpiOptions
            .Select(dpi => new Choice<int>($"{dpi} DPI", dpi)).ToList();
        PdfDpiCombo.SelectedIndex = 2;

        RefreshModelChoices();
    }

    /// <summary>Relê a lista de modelos Whisper: um download em Configurações muda o rótulo.</summary>
    internal void RefreshModelChoices()
    {
        var models = App.Services.Models.ListModels()
            .Select(model => new Choice<string>(
                model.IsInstalled ? $"{model.DisplayName} · instalado" : $"{model.DisplayName} · baixar",
                model.Id))
            .ToList();

        ModelCombo.ItemsSource = models;

        // Se o modelo escolhido nas configurações ainda não foi baixado, cai no melhor
        // que estiver instalado em vez de levar o usuário direto a um erro.
        var preferred = App.Services.Models.ResolvePreferred(App.Services.Settings.DefaultWhisperModel);
        ModelCombo.SelectedIndex = Math.Max(0, models.FindIndex(choice => choice.Value == preferred));
    }

    // ==================== Helpers ====================

    private static T Value<T>(ComboBox combo, T fallback) =>
        combo.SelectedItem is Choice<T> choice ? choice.Value : fallback;

    private static void SelectValue<T>(ComboBox combo, T value)
    {
        if (combo.ItemsSource is not IEnumerable<Choice<T>> items)
        {
            return;
        }

        var list = items.ToList();
        var index = list.FindIndex(choice => EqualityComparer<T>.Default.Equals(choice.Value, value));
        if (index >= 0)
        {
            combo.SelectedIndex = index;
        }
    }

    private static string OptionalTime(TimeSpan? value) => value is { } time ? TrimTime.Format(time) : string.Empty;

    /// <summary>A tela mostra início e fim; as opções guardam início e duração.</summary>
    private TimeSpan? TrimDurationFromScreen() =>
        TrimTime.DurationFromEnd(TrimTime.Parse(TrimStartBox.Text), TrimTime.Parse(TrimEndBox.Text));

    // ---- Prévia do corte -------------------------------------------------------------

    private MediaPlayer? trimPlayer;
    private string? trimPreviewPath;
    private bool trimHasVideo;
    private TrimFullscreenWindow? fullscreen;
    private bool previewEventsWired;

    /// <summary>
    /// Abre o arquivo no player do corte quando a operação tem corte e o arquivo é vídeo ou
    /// áudio. No lote, a prévia é do primeiro arquivo e o corte vale para todos.
    /// </summary>
    private void ShowTrimPreview(MediaInfo? sample)
    {
        if (TrimPanel.Visibility != Visibility.Visible
            || sample is null
            || !(sample.HasVideo || sample.HasAudio)
            || !File.Exists(sample.Path))
        {
            ClearTrimPreview();
            UpdateTrimSummary();
            return;
        }

        if (!previewEventsWired)
        {
            WirePlayerEvents(TrimPreview);
            previewEventsWired = true;
        }

        if (trimPlayer is null || !string.Equals(trimPreviewPath, sample.Path, StringComparison.OrdinalIgnoreCase))
        {
            ClearTrimPreview();
            trimPreviewPath = sample.Path;
            trimHasVideo = sample.HasVideo;
            trimPlayer = new MediaPlayer
            {
                AutoPlay = false,
                Source = MediaSource.CreateFromUri(new Uri(sample.Path))
            };
            TrimPreview.Attach(trimPlayer, trimHasVideo);
        }

        TrimPreviewPanel.Visibility = Visibility.Visible;
        UpdateTrimSummary();
    }

    private void ClearTrimPreview()
    {
        fullscreen?.Close();
        TrimPreview.Detach();
        if (trimPlayer is not null)
        {
            trimPlayer.Pause();
            (trimPlayer.Source as IDisposable)?.Dispose();
            trimPlayer.Dispose();
            trimPlayer = null;
        }

        trimPreviewPath = null;
        TrimPreviewPanel.Visibility = Visibility.Collapsed;
    }

    private void WirePlayerEvents(TrimPlayer alvo)
    {
        alvo.StartMarked += OnStartMarked;
        alvo.EndMarked += OnEndMarked;
        alvo.FullscreenToggled += OnFullscreenToggled;
    }

    private void OnStartMarked(TimeSpan agora)
    {
        TrimStartBox.Text = TrimTime.Format(agora);
        // Início depois do fim marcado: o fim volta a ser o fim do arquivo.
        if (TryParse(TrimEndBox.Text) is { } fim && fim <= agora)
        {
            TrimEndBox.Text = string.Empty;
        }
    }

    private void OnEndMarked(TimeSpan agora)
    {
        if (agora > (TryParse(TrimStartBox.Text) ?? TimeSpan.Zero))
        {
            TrimEndBox.Text = TrimTime.Format(agora);
        }
    }

    /// <summary>
    /// Tela cheia: o mesmo player vai para uma janela própria, com os controles embaixo, e
    /// volta para o formulário ao sair, no mesmo ponto e na mesma velocidade.
    /// </summary>
    private void OnFullscreenToggled()
    {
        if (fullscreen is not null)
        {
            fullscreen.Close();
            return;
        }

        if (trimPlayer is null)
        {
            return;
        }

        var player = trimPlayer;
        TrimPreview.Detach();
        fullscreen = new TrimFullscreenWindow(player, trimHasVideo, $"Prévia do corte — {Path.GetFileName(trimPreviewPath)}");
        // Desliga só o pedido de tela cheia do formulário: dentro da janela, o botão fecha a janela.
        fullscreen.Player.StartMarked += OnStartMarked;
        fullscreen.Player.EndMarked += OnEndMarked;
        fullscreen.Closed += (_, _) =>
        {
            fullscreen.Player.Detach();
            fullscreen = null;
            if (trimPlayer == player)
            {
                TrimPreview.Attach(player, trimHasVideo);
                UpdateTrimSummary();
            }
        };
        fullscreen.Activate();
        UpdateTrimSummary();
    }

    private void OnTrimTextChanged(object sender, TextChangedEventArgs e) => UpdateTrimSummary();

    /// <summary>Resumo do trecho, ou o motivo de o tempo não valer, enquanto a pessoa digita.</summary>
    private void UpdateTrimSummary()
    {
        var inicio = TryParse(TrimStartBox.Text, out var inicioValido);
        var fim = TryParse(TrimEndBox.Text, out var fimValido);
        TrimPreview.SetTrim(inicio ?? TimeSpan.Zero, fim);
        fullscreen?.Player.SetTrim(inicio ?? TimeSpan.Zero, fim);

        if (!inicioValido || !fimValido)
        {
            TrimSummaryText.Text = "Tempo inválido. Use 0:30, 1:05.135 ou 1:02:15.";
            return;
        }

        var de = inicio ?? TimeSpan.Zero;
        if (fim is { } marcado && marcado <= de)
        {
            TrimSummaryText.Text = "O fim do trecho deve ser maior que o início.";
            return;
        }

        TrimSummaryText.Text = inicio is null && fim is null
            ? "Sem corte: o arquivo inteiro."
            : fim is { } ate
                ? $"Trecho: {TrimPlayer.Long(de)} até {TrimPlayer.Long(ate)} · {TrimPlayer.Long(ate - de)}"
                : $"Trecho: de {TrimPlayer.Long(de)} até o fim.";
    }

    private static TimeSpan? TryParse(string? text) => TryParse(text, out _);

    private static TimeSpan? TryParse(string? text, out bool valido)
    {
        try
        {
            valido = true;
            return TrimTime.Parse(text);
        }
        catch (ConversionException)
        {
            valido = false;
            return null;
        }
    }
}
