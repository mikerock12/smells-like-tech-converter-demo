using SmellsLikeTech.Converter.Core;
using SmellsLikeTech.Converter.Core.Jobs;
using SmellsLikeTech.Converter.Core.Media;
using SmellsLikeTech.Converter.Core.Operations;
using Xunit;

namespace SmellsLikeTech.Converter.Tests;

public class CatalogTests
{
    [Theory]
    [InlineData("foto.JPG", MediaKind.Image)]
    [InlineData("show.mp4", MediaKind.Video)]
    [InlineData("entrevista.wav", MediaKind.Audio)]
    [InlineData("roteiro.txt", MediaKind.Text)]
    [InlineData("contrato.pdf", MediaKind.Pdf)]
    [InlineData("planilha.xlsx", MediaKind.Document)]
    [InlineData("arquivo.xyz", MediaKind.Unknown)]
    public void KindOfPath_ReconheceOsTiposEsperados(string fileName, MediaKind expected) =>
        Assert.Equal(expected, FormatCatalog.KindOfPath(fileName));

    [Theory]
    [InlineData("../../evil")]
    [InlineData("mp4;rm")]
    [InlineData("")]
    public void Normalize_RejeitaExtensaoInvalida(string extension) =>
        Assert.Throws<ConversionException>(() => FormatCatalog.Normalize(extension));

    [Fact]
    public void For_VideoComAudio_OfereceExtracaoETranscricao()
    {
        var info = Video(withAudio: true);
        var operations = OperationCatalog.For(info).Select(descriptor => descriptor.Id).ToArray();

        Assert.Contains(OperationIds.VideoConvert, operations);
        Assert.Contains(OperationIds.VideoExtractAudio, operations);
        Assert.Contains(OperationIds.SpeechTranscribe, operations);
    }

    [Fact]
    public void For_VideoSemAudio_NaoOfereceExtracaoNemTranscricao()
    {
        var info = Video(withAudio: false);
        var operations = OperationCatalog.For(info).Select(descriptor => descriptor.Id).ToArray();

        Assert.Contains(OperationIds.VideoConvert, operations);
        Assert.DoesNotContain(OperationIds.VideoExtractAudio, operations);
        Assert.DoesNotContain(OperationIds.SpeechTranscribe, operations);
    }

    [Fact]
    public void For_Imagem_NaoOfereceOperacoesDeVideo()
    {
        var info = new MediaInfo
        {
            Path = @"C:\temp\foto.png",
            FileName = "foto.png",
            Extension = "png",
            Kind = MediaKind.Image,
            SizeBytes = 2048,
            Width = 1920,
            Height = 1080
        };

        var operations = OperationCatalog.For(info).Select(descriptor => descriptor.Id).ToArray();

        Assert.Contains(OperationIds.ImageConvert, operations);
        Assert.DoesNotContain(OperationIds.VideoConvert, operations);
        // Imagem pode ser narrada: o texto dela vem do OCR.
        Assert.Contains(OperationIds.SpeechSynthesize, operations);
    }

    [Fact]
    public void FamilyOf_AgrupaPorPrefixo()
    {
        Assert.Equal("video", OperationIds.FamilyOf(OperationIds.VideoExtractAudio));
        Assert.Equal("speech", OperationIds.FamilyOf(OperationIds.SpeechTranscribe));
    }

    [Theory]
    [InlineData("re:latório?.mp4", "re_latório_.mp4")]
    [InlineData("  ", "arquivo")]
    [InlineData(@"..\..\senha", ".._.._senha")]
    [InlineData("CON", "_CON")]
    public void SafeFileName_RemoveCaracteresPerigosos(string input, string expected) =>
        Assert.Equal(expected, SafeFileName.Sanitize(input));

    [Fact]
    public void UniquePath_NaoSobrescreveArquivoExistente()
    {
        var directory = Path.Combine(Path.GetTempPath(), "slt-tests-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(directory);
        try
        {
            var first = SafeFileName.UniquePath(directory, "video", "mp4");
            File.WriteAllText(first, "x");
            var second = SafeFileName.UniquePath(directory, "video", "mp4");

            Assert.EndsWith("video.mp4", first);
            Assert.EndsWith("video (2).mp4", second);
        }
        finally
        {
            Directory.Delete(directory, recursive: true);
        }
    }

    private static MediaInfo Video(bool withAudio) => new()
    {
        Path = @"C:\temp\show.mp4",
        FileName = "show.mp4",
        Extension = "mp4",
        Kind = MediaKind.Video,
        SizeBytes = 10_000_000,
        Duration = TimeSpan.FromMinutes(3),
        Width = 1920,
        Height = 1080,
        VideoStreams = [new VideoStreamInfo(0, "h264", 1920, 1080, 30, 4_000_000, "yuv420p", 0)],
        AudioStreams = withAudio ? [new AudioStreamInfo(1, "aac", 48_000, 2, 128_000, "por")] : []
    };
}
