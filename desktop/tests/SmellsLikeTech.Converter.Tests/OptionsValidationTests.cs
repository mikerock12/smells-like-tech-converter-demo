using SmellsLikeTech.Converter.Core;
using SmellsLikeTech.Converter.Core.Options;
using Xunit;

namespace SmellsLikeTech.Converter.Tests;

public class OptionsValidationTests
{
    [Fact]
    public void ExtractAudio_BitrateEmFormatoSemPerda_EhRejeitado()
    {
        var options = new ExtractAudioOptions { OutputFormat = "wav", AudioBitrateKbps = 192 };
        var exception = Assert.Throws<ConversionException>(options.Validate);
        Assert.Equal("invalid_combination", exception.Code);
    }

    [Fact]
    public void ExtractAudio_BitrateForaDaAllowlist_EhRejeitado()
    {
        var options = new ExtractAudioOptions { OutputFormat = "mp3", AudioBitrateKbps = 999 };
        Assert.Equal("invalid_audio_bitrate", Assert.Throws<ConversionException>(options.Validate).Code);
    }

    [Fact]
    public void ExtractAudio_PadraoEhValido() => new ExtractAudioOptions().Validate();

    [Fact]
    public void VideoConvert_WebmComH264_EhRejeitado()
    {
        var options = new VideoConvertOptions { OutputFormat = "webm", VideoCodec = VideoCodecChoice.H264 };
        Assert.Equal("invalid_combination", Assert.Throws<ConversionException>(options.Validate).Code);
    }

    [Fact]
    public void VideoConvert_CopiarStreamComRedimensionamento_EhRejeitado()
    {
        var options = new VideoConvertOptions { VideoCodec = VideoCodecChoice.CopyStream, TargetHeight = 720 };
        Assert.Equal("invalid_combination", Assert.Throws<ConversionException>(options.Validate).Code);
    }

    [Theory]
    [InlineData(0.1)]
    [InlineData(8)]
    public void VideoConvert_VelocidadeForaDoIntervalo_EhRejeitada(double speed)
    {
        var options = new VideoConvertOptions { Speed = speed };
        Assert.Equal("invalid_speed", Assert.Throws<ConversionException>(options.Validate).Code);
    }

    [Fact]
    public void VideoConvert_ResolucaoPersonalizadaIncompleta_EhRejeitada()
    {
        var options = new VideoConvertOptions { CustomWidth = 1080 };
        Assert.Equal("invalid_resolution", Assert.Throws<ConversionException>(options.Validate).Code);
    }

    [Fact]
    public void VideoConvert_CorInvalida_EhRejeitada()
    {
        var options = new VideoConvertOptions { BackgroundColor = "vermelho" };
        Assert.Equal("invalid_color", Assert.Throws<ConversionException>(options.Validate).Code);
    }

    [Fact]
    public void ImageConvert_SemDimensaoNoModoLargura_EhRejeitado()
    {
        var options = new ImageConvertOptions { ResizeMode = ImageResizeMode.Width };
        Assert.Equal("invalid_resolution", Assert.Throws<ConversionException>(options.Validate).Code);
    }

    [Fact]
    public void ImageConvert_FormatoDesconhecido_EhRejeitado()
    {
        var options = new ImageConvertOptions { OutputFormat = "psd" };
        Assert.Equal("unsupported_output_format", Assert.Throws<ConversionException>(options.Validate).Code);
    }

    [Fact]
    public void Transcribe_ModeloForaDaAllowlist_EhRejeitado()
    {
        var options = new TranscribeOptions { Model = "gigante" };
        Assert.Equal("unsupported_model", Assert.Throws<ConversionException>(options.Validate).Code);
    }

    [Fact]
    public void Transcribe_FormatoInvalido_EhRejeitado()
    {
        var options = new TranscribeOptions { OutputFormat = "docx" };
        Assert.Equal("unsupported_output_format", Assert.Throws<ConversionException>(options.Validate).Code);
    }

    /// <summary>
    /// Voz vazia significa "use a voz padrão do computador", e não é erro. Antes era
    /// recusada, e isso quebrava quem chama sem interface: o site, pelo plugin, não tem
    /// como saber os identificadores técnicos das vozes instaladas.
    /// </summary>
    [Fact]
    public void Synthesize_SemVoz_UsaAVozPadrao()
    {
        var options = new SynthesizeOptions { VoiceId = string.Empty };
        options.Validate();
    }

    [Fact]
    public void Synthesize_VozAbsurdamenteLonga_EhRejeitada()
    {
        var options = new SynthesizeOptions { VoiceId = new string('v', 257) };
        Assert.Equal("unsupported_voice", Assert.Throws<ConversionException>(options.Validate).Code);
    }

    [Theory]
    [InlineData(-20, "unsupported_rate")]
    [InlineData(20, "unsupported_rate")]
    public void Synthesize_VelocidadeForaDoIntervalo_EhRejeitada(int rate, string expectedCode)
    {
        var options = new SynthesizeOptions { VoiceId = "voz", Rate = rate };
        Assert.Equal(expectedCode, Assert.Throws<ConversionException>(options.Validate).Code);
    }

    [Fact]
    public void Synthesize_ResumoNuncaContemTexto()
    {
        var options = new SynthesizeOptions { VoiceId = "voz", Rate = 2, Volume = 80 };
        var summary = options.Summary();

        Assert.Contains("MP3", summary);
        Assert.DoesNotContain("voz", summary, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public void Serializacao_PreservaOperacaoETipo()
    {
        JobOptions original = new VideoConvertOptions { OutputFormat = "mkv", TargetHeight = 720, Quality = 50 };
        var json = JobOptionsJson.Serialize(original);
        var restored = JobOptionsJson.Deserialize(json);

        var video = Assert.IsType<VideoConvertOptions>(restored);
        Assert.Equal("mkv", video.OutputFormat);
        Assert.Equal(720, video.TargetHeight);
        Assert.Equal(50, video.Quality);
    }
}
