using SmellsLikeTech.Converter.Core.Media;
using SmellsLikeTech.Converter.Core.Options;
using SmellsLikeTech.Converter.Engine.FFmpeg;
using Xunit;

namespace SmellsLikeTech.Converter.Tests;

public class FfmpegPlannerTests
{
    private static FfmpegOptions Engine(params string[] encoders) =>
        FfmpegOptions.Resolve("ffmpeg", "ffprobe", encoders, HardwareAcceleration.Cpu);

    private static MediaInfo FullHdVideo => new()
    {
        Path = @"C:\temp\show.mp4",
        FileName = "show.mp4",
        Extension = "mp4",
        Kind = MediaKind.Video,
        SizeBytes = 100_000_000,
        Duration = TimeSpan.FromMinutes(2),
        Width = 1920,
        Height = 1080,
        VideoStreams = [new VideoStreamInfo(0, "h264", 1920, 1080, 30, 5_000_000, "yuv420p", 0)],
        AudioStreams = [new AudioStreamInfo(1, "aac", 48_000, 2, 128_000, null)]
    };

    [Fact]
    public void ExtractAudio_MapeiaFaixaEDefineCodec()
    {
        var options = new ExtractAudioOptions { OutputFormat = "mp3", AudioBitrateKbps = 192 };
        var command = FfmpegCommandPlanner.ExtractAudio(Engine(), "in.mp4", "out.mp3", options, absoluteStreamIndex: 1);
        var arguments = command.Arguments;

        Assert.Contains("-map", arguments);
        Assert.Contains("0:1", arguments);
        Assert.Contains("-vn", arguments);
        Assert.Contains("libmp3lame", arguments);
        Assert.Contains("192k", arguments);
        // Metadados do arquivo original não vão junto com o áudio extraído.
        Assert.Contains("-map_metadata", arguments);
        Assert.Equal("out.mp3", arguments[^1]);
    }

    [Fact]
    public void ExtractAudio_ComNormalizacao_AplicaLoudnorm()
    {
        var options = new ExtractAudioOptions { OutputFormat = "wav", Normalize = true, Channels = 1, SampleRateHz = 16_000 };
        var arguments = FfmpegCommandPlanner.ExtractAudio(Engine(), "in.mp4", "out.wav", options, 1).Arguments;

        Assert.Contains(arguments, argument => argument.StartsWith("loudnorm", StringComparison.Ordinal));
        Assert.Contains("pcm_s16le", arguments);
        Assert.Contains("16000", arguments);
        Assert.Contains("1", arguments);
    }

    [Fact]
    public void ExtractAudio_ComCorte_UsaSsAntesDoInput()
    {
        var options = new ExtractAudioOptions
        {
            OutputFormat = "mp3",
            TrimStart = TimeSpan.FromSeconds(30),
            TrimDuration = TimeSpan.FromSeconds(15)
        };
        var arguments = FfmpegCommandPlanner.ExtractAudio(Engine(), "in.mp4", "out.mp3", options, 1).Arguments;

        var ssIndex = arguments.ToList().IndexOf("-ss");
        var inputIndex = arguments.ToList().IndexOf("-i");
        Assert.True(ssIndex >= 0 && ssIndex < inputIndex);
        Assert.Contains("30", arguments);
        Assert.Contains("15", arguments);
    }

    [Fact]
    public void ConvertVideo_9x16ComFundoDesfocado_GeraGrafoComOverlay()
    {
        var options = new VideoConvertOptions
        {
            OutputFormat = "mp4",
            AspectRatio = AspectRatioMode.Vertical9x16,
            Fit = FitMode.Blur,
            CustomWidth = 1080,
            CustomHeight = 1920
        };

        var filters = FfmpegCommandPlanner.BuildVideoFilterGraph(options, FullHdVideo);

        Assert.NotNull(filters);
        Assert.Contains("split", filters);
        Assert.Contains("gblur", filters);
        Assert.Contains("overlay", filters);
        Assert.Contains("1080:1920", filters);
    }

    [Fact]
    public void ConvertVideo_Recortar_UsaCropDepoisDoScale()
    {
        var options = new VideoConvertOptions { AspectRatio = AspectRatioMode.Square1x1, Fit = FitMode.Crop };
        var filters = FfmpegCommandPlanner.BuildVideoFilterGraph(options, FullHdVideo);

        Assert.NotNull(filters);
        Assert.Contains("force_original_aspect_ratio=increase", filters);
        Assert.Contains("crop=1080:1080", filters);
    }

    [Fact]
    public void ConvertVideo_Ajustar_AplicaPadComCorDeFundo()
    {
        var options = new VideoConvertOptions
        {
            AspectRatio = AspectRatioMode.Wide16x9,
            Fit = FitMode.Contain,
            BackgroundColor = "#101820",
            TargetHeight = 720
        };
        var filters = FfmpegCommandPlanner.BuildVideoFilterGraph(options, FullHdVideo);

        Assert.NotNull(filters);
        Assert.Contains("pad=1280:720", filters);
        Assert.Contains("0x101820", filters);
    }

    [Fact]
    public void ConvertVideo_CopiarStream_NaoGeraFiltros()
    {
        var options = new VideoConvertOptions { VideoCodec = VideoCodecChoice.CopyStream };
        Assert.Null(FfmpegCommandPlanner.BuildVideoFilterGraph(options, FullHdVideo));
    }

    [Fact]
    public void ConvertVideo_Velocidade_AjustaVideoEAudio()
    {
        var options = new VideoConvertOptions { Speed = 2.0, Audio = AudioTrackAction.Keep };
        var command = FfmpegCommandPlanner.ConvertVideo(Engine(), "in.mp4", "out.mp4", options, FullHdVideo);

        Assert.Contains(command.Arguments, argument => argument.Contains("setpts=0.5*PTS", StringComparison.Ordinal));
        Assert.Contains(command.Arguments, argument => argument.Contains("atempo=2", StringComparison.Ordinal));
        // Alterar a velocidade obriga a recodificar o áudio.
        Assert.DoesNotContain("copy", command.Arguments);
    }

    [Fact]
    public void ConvertVideo_SemAudioNaEntrada_UsaAn()
    {
        var mute = FullHdVideo with { AudioStreams = [] };
        var command = FfmpegCommandPlanner.ConvertVideo(Engine(), "in.mp4", "out.mp4", new VideoConvertOptions(), mute);

        Assert.Contains("-an", command.Arguments);
    }

    [Theory]
    [InlineData(VideoCodecChoice.H264, "libx264")]
    [InlineData(VideoCodecChoice.H265, "libx265")]
    [InlineData(VideoCodecChoice.Vp9, "libvpx-vp9")]
    [InlineData(VideoCodecChoice.Av1, "libsvtav1")]
    public void SelectEncoder_SemHardware_CaiParaCpu(VideoCodecChoice codec, string expected) =>
        Assert.Equal(expected, FfmpegCommandPlanner.SelectEncoder(codec, HardwareAcceleration.Nvidia, Engine()));

    [Fact]
    public void SelectEncoder_ComNvenc_UsaAceleracao() =>
        Assert.Equal(
            "h264_nvenc",
            FfmpegCommandPlanner.SelectEncoder(VideoCodecChoice.H264, HardwareAcceleration.Nvidia, Engine("h264_nvenc")));

    [Theory]
    [InlineData(100, 14)]
    [InlineData(0, 51)]
    [InlineData(50, 33)]
    public void Crf_MapeiaQualidadeParaCrf(int quality, int expected) =>
        Assert.Equal(expected, FfmpegCommandPlanner.Crf(quality, 51, 14));

    [Fact]
    public void ResolveTargetSize_MantemProporcaoOriginalPorAltura()
    {
        var options = new VideoConvertOptions { TargetHeight = 720 };
        var size = FfmpegCommandPlanner.ResolveTargetSize(options, FullHdVideo);

        Assert.Equal((1280, 720), size);
    }

    [Fact]
    public void ResolveTargetSize_SempreDevolveDimensoesPares()
    {
        var odd = FullHdVideo with { Width = 1921, Height = 1081 };
        var options = new VideoConvertOptions { AspectRatio = AspectRatioMode.Photo3x2 };
        var size = FfmpegCommandPlanner.ResolveTargetSize(options, odd);

        Assert.NotNull(size);
        Assert.Equal(0, size!.Value.Width % 2);
        Assert.Equal(0, size.Value.Height % 2);
    }

    [Fact]
    public void ToGif_UsaPaletaOtimizada()
    {
        var arguments = FfmpegCommandPlanner.ToGif(Engine(), "in.mp4", "out.gif", new VideoToGifOptions()).Arguments;

        Assert.Contains(arguments, argument => argument.Contains("palettegen", StringComparison.Ordinal));
        Assert.Contains(arguments, argument => argument.Contains("paletteuse", StringComparison.Ordinal));
        Assert.Contains("-an", arguments);
    }

    [Fact]
    public void PrepareSpeechAudio_GeraPcmMono16k()
    {
        var arguments = FfmpegCommandPlanner.PrepareSpeechAudio(Engine(), "in.mp4", "out.wav", 1).Arguments;

        Assert.Contains("16000", arguments);
        Assert.Contains("pcm_s16le", arguments);
        Assert.Contains("1", arguments);
    }

    [Fact]
    public void Transcribe_MontaFiltroWhisperComModeloEDestino()
    {
        var options = new TranscribeOptions { Language = "pt", Model = "base", OutputFormat = "srt" };
        var arguments = FfmpegCommandPlanner.Transcribe(
            Engine(), "in.wav", @"C:\jobs\a\transcript.srt", @"C:\models\ggml-base.bin", options, "srt").Arguments;

        var filter = Assert.Single(arguments, argument => argument.StartsWith("whisper=", StringComparison.Ordinal));
        Assert.Contains("language=pt", filter);
        Assert.Contains("format=srt", filter);
        // Caminhos entram escapados, nunca concatenados crus.
        Assert.Contains(@"C\:\\models", filter);

        // Janela de contexto: com o padrao de 3 s o modelo troca de idioma no meio da
        // fala e inventa trechos. Precisa ser a janela nativa de 30 s.
        Assert.Contains("queue=30", filter);
        Assert.DoesNotContain("queue=3:", filter);
    }

    [Fact]
    public void Transcribe_UsaSintaxeDeFiltroValida()
    {
        var options = new TranscribeOptions { Language = "auto", Model = "base", OutputFormat = "txt" };
        var arguments = FfmpegCommandPlanner.Transcribe(
            Engine(), "in.wav", @"C:\jobs\a\t.srt", @"C:\models\ggml-base.bin", options, "srt").Arguments;

        var filter = Assert.Single(arguments, argument => argument.Contains("whisper", StringComparison.Ordinal));

        // "whisper:model=..." nao e um filtro valido; o FFmpeg exige nome=opcao=valor.
        Assert.StartsWith("whisper=model=", filter);
        Assert.DoesNotContain("whisper:", filter);
    }
}
