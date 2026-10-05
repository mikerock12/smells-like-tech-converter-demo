using SmellsLikeTech.Converter.Core.Batch;
using SmellsLikeTech.Converter.Core.Media;
using SmellsLikeTech.Converter.Core.Operations;
using Xunit;

namespace SmellsLikeTech.Converter.Tests;

/// <summary>
/// O agrupamento do lote. É ele que decide quantas perguntas a tela faz: um cartão a
/// mais é uma pergunta a mais para quem só queria converter tudo de uma vez.
/// </summary>
public class BatchTests
{
    [Fact]
    public void Group_VariasMusicas_ViramUmGrupoSo()
    {
        var groups = BatchPlanner.Group([Audio("um.mp3"), Audio("dois.mp3"), Audio("tres.mp3")]);

        var group = Assert.Single(groups);
        Assert.Equal(3, group.Count);
        Assert.Equal("3 áudios", group.Title);
        Assert.Equal(OperationIds.AudioConvert, group.DefaultOperation?.Id);
    }

    [Fact]
    public void Group_UmArquivoSo_UsaOSingular()
    {
        var group = Assert.Single(BatchPlanner.Group([Audio("solo.mp3")]));

        Assert.Equal("1 áudio", group.Title);
    }

    [Fact]
    public void Group_TiposDiferentes_GanhamUmCartaoCada()
    {
        var groups = BatchPlanner.Group([Audio("um.mp3"), Pdf("contrato.pdf"), Image("foto.jpg"), Audio("dois.wav")]);

        Assert.Equal(3, groups.Count);
        Assert.Equal([MediaKind.Image, MediaKind.Audio, MediaKind.Pdf], groups.Select(group => group.Kind));
        Assert.Equal(2, groups.Single(group => group.Kind == MediaKind.Audio).Count);
    }

    [Fact]
    public void Group_ExtensoesDiferentesDoMesmoTipo_FicamJuntas()
    {
        var groups = BatchPlanner.Group([Audio("um.mp3"), Audio("dois.wav"), Audio("tres.flac")]);

        var group = Assert.Single(groups);
        Assert.Equal(["FLAC", "MP3", "WAV"], group.Extensions);
    }

    [Fact]
    public void Group_VideoSemTrilhaSonora_NaoEntraNoCartaoDeQuemTem()
    {
        var groups = BatchPlanner.Group([Video("com.mp4", withAudio: true), Video("sem.mp4", withAudio: false)]);

        Assert.Equal(2, groups.Count);

        var mudo = groups.Single(group => group.Note == "sem trilha sonora");
        Assert.DoesNotContain(OperationIds.VideoExtractAudio, mudo.Operations.Select(operation => operation.Id));
        Assert.DoesNotContain(OperationIds.SpeechTranscribe, mudo.Operations.Select(operation => operation.Id));

        var comSom = groups.Single(group => group.Note is null);
        Assert.Contains(OperationIds.VideoExtractAudio, comSom.Operations.Select(operation => operation.Id));
    }

    [Fact]
    public void Group_MesmoArquivoDuasVezes_ContaUmaSo()
    {
        var groups = BatchPlanner.Group([Audio("um.mp3"), Audio("um.mp3")]);

        Assert.Equal(1, Assert.Single(groups).Count);
    }

    [Fact]
    public void Group_SomaOTamanhoDoGrupo()
    {
        var group = Assert.Single(BatchPlanner.Group([Audio("um.mp3", 1_000_000), Audio("dois.mp3", 3_000_000)]));

        Assert.Equal(4_000_000, group.TotalBytes);
        Assert.Contains("MP3", group.Detail);
        Assert.Contains(MediaInfo.FormatBytes(4_000_000), group.Detail);
    }

    [Fact]
    public void Group_SemArquivos_NaoProduzCartao() => Assert.Empty(BatchPlanner.Group([]));

    [Fact]
    public void ConvertibleCount_IgnoraOQueNenhumMotorLocalFaz()
    {
        var groups = BatchPlanner.Group([Audio("um.mp3"), Audio("dois.mp3"), Document("planilha.xlsx")]);

        var documentos = groups.Single(group => group.Kind == MediaKind.Document);
        Assert.False(documentos.HasWork);
        Assert.Equal(2, BatchPlanner.ConvertibleCount(groups));
    }

    [Fact]
    public void SignatureOf_IgualParaOsArquivosDoMesmoCartao()
    {
        Assert.Equal(BatchPlanner.SignatureOf(Audio("um.mp3")), BatchPlanner.SignatureOf(Audio("dois.wav")));
        Assert.NotEqual(BatchPlanner.SignatureOf(Audio("um.mp3")), BatchPlanner.SignatureOf(Pdf("contrato.pdf")));
        Assert.NotEqual(
            BatchPlanner.SignatureOf(Video("com.mp4", withAudio: true)),
            BatchPlanner.SignatureOf(Video("sem.mp4", withAudio: false)));
    }

    /// <summary>
    /// A ordem dos cartões não pode depender da ordem em que os arquivos foram soltos:
    /// a mesma seleção precisa produzir a mesma tela nas duas vezes.
    /// </summary>
    [Fact]
    public void Group_OrdemDeEntradaNaoMudaAOrdemDosCartoes()
    {
        var primeira = BatchPlanner.Group([Pdf("a.pdf"), Audio("b.mp3"), Image("c.png")]);
        var segunda = BatchPlanner.Group([Image("c.png"), Pdf("a.pdf"), Audio("b.mp3")]);

        Assert.Equal(primeira.Select(group => group.Title), segunda.Select(group => group.Title));
    }

    private static MediaInfo Audio(string name, long size = 4_000_000) => new()
    {
        Path = @"C:\temp\" + name,
        FileName = name,
        Extension = Path.GetExtension(name).TrimStart('.'),
        Kind = MediaKind.Audio,
        SizeBytes = size,
        Duration = TimeSpan.FromMinutes(3),
        AudioStreams = [new AudioStreamInfo(0, "mp3", 44_100, 2, 192_000, null)]
    };

    private static MediaInfo Video(string name, bool withAudio) => new()
    {
        Path = @"C:\temp\" + name,
        FileName = name,
        Extension = "mp4",
        Kind = MediaKind.Video,
        SizeBytes = 10_000_000,
        Duration = TimeSpan.FromMinutes(2),
        Width = 1920,
        Height = 1080,
        VideoStreams = [new VideoStreamInfo(0, "h264", 1920, 1080, 30, 4_000_000, "yuv420p", 0)],
        AudioStreams = withAudio ? [new AudioStreamInfo(1, "aac", 48_000, 2, 128_000, "por")] : []
    };

    private static MediaInfo Image(string name) => new()
    {
        Path = @"C:\temp\" + name,
        FileName = name,
        Extension = Path.GetExtension(name).TrimStart('.'),
        Kind = MediaKind.Image,
        SizeBytes = 500_000,
        Width = 1920,
        Height = 1080
    };

    private static MediaInfo Pdf(string name) => new()
    {
        Path = @"C:\temp\" + name,
        FileName = name,
        Extension = "pdf",
        Kind = MediaKind.Pdf,
        SizeBytes = 900_000,
        PageCount = 18
    };

    private static MediaInfo Document(string name) => new()
    {
        Path = @"C:\temp\" + name,
        FileName = name,
        Extension = Path.GetExtension(name).TrimStart('.'),
        Kind = MediaKind.Document,
        SizeBytes = 120_000
    };
}
