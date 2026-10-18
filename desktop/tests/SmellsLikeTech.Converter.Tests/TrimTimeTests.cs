using SmellsLikeTech.Converter.Core;
using SmellsLikeTech.Converter.Core.Options;
using Xunit;

namespace SmellsLikeTech.Converter.Tests;

public sealed class TrimTimeTests
{
    [Theory]
    [InlineData("0:21", 21)]
    [InlineData(" 1:05 ", 65)]
    [InlineData("1-05", 65)]
    [InlineData("1:05.5", 65.5)]
    [InlineData("1:05,5", 65.5)]
    [InlineData("90", 90)]
    [InlineData("1:02:03", 3723)]
    public void Parse_AceitaOsJeitosDeEscreverTempo(string texto, double segundos)
    {
        Assert.Equal(TimeSpan.FromSeconds(segundos), TrimTime.Parse(texto));
    }

    [Theory]
    [InlineData("")]
    [InlineData("   ")]
    [InlineData(null)]
    public void Parse_VazioEhSemCorte(string? texto)
    {
        Assert.Null(TrimTime.Parse(texto));
    }

    [Theory]
    [InlineData("abc")]
    [InlineData("1:")]
    [InlineData("1:75")]
    [InlineData("1:2:3:4")]
    [InlineData("1.5:30")]
    public void Parse_RecusaOQueNaoEhTempo(string texto)
    {
        var erro = Assert.Throws<ConversionException>(() => TrimTime.Parse(texto));
        Assert.Equal("invalid_trim", erro.Code);
    }

    [Fact]
    public void Format_MostraDecimoSoQuandoPrecisa()
    {
        Assert.Equal("0:21", TrimTime.Format(TimeSpan.FromSeconds(21)));
        Assert.Equal("1:05.5", TrimTime.Format(TimeSpan.FromSeconds(65.52)));
        Assert.Equal("1:02:03", TrimTime.Format(TimeSpan.FromSeconds(3723)));
        Assert.Equal(TimeSpan.FromSeconds(65.5), TrimTime.Parse(TrimTime.Format(TimeSpan.FromSeconds(65.5))));
    }

    [Fact]
    public void FimNaTela_ViraDuracaoNasOpcoes_EVolta()
    {
        var inicio = TimeSpan.FromSeconds(21);
        var fim = TimeSpan.FromSeconds(45.5);

        var duracao = TrimTime.DurationFromEnd(inicio, fim);

        Assert.Equal(TimeSpan.FromSeconds(24.5), duracao);
        Assert.Equal(fim, TrimTime.EndFromDuration(inicio, duracao));
        Assert.Equal(TimeSpan.FromSeconds(30), TrimTime.DurationFromEnd(null, TimeSpan.FromSeconds(30)));
        Assert.Null(TrimTime.DurationFromEnd(inicio, null));
        Assert.Null(TrimTime.EndFromDuration(inicio, null));
    }

    [Fact]
    public void FimAntesDoInicio_EhRecusadoComMensagemClara()
    {
        var erro = Assert.Throws<ConversionException>(() =>
            TrimTime.DurationFromEnd(TimeSpan.FromSeconds(30), TimeSpan.FromSeconds(30)));
        Assert.Equal("invalid_trim", erro.Code);
        Assert.Contains("fim", erro.Message);
    }
}
