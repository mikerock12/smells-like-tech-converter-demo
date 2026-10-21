using SmellsLikeTech.Converter.Core.Studio;
using Xunit;

namespace SmellsLikeTech.Converter.Tests;

public sealed class StickerCatalogPolicyTests
{
    [Fact]
    public void CotaExpiraEPermiteRetomar()
    {
        var state=new StickerCatalogState(Enumerable.Range(0,100).Select(i=>1000L+i).ToList());
        state=StickerCatalogPolicy.Prepare(state,2000);
        Assert.Equal("limite",state.Reason);
        Assert.Equal(1000+StickerCatalogPolicy.Hour,state.RetryAt);
        state=StickerCatalogPolicy.Prepare(state,state.RetryAt);
        Assert.Null(state.Reason);Assert.Equal(99,state.Attempts.Count);
    }
    [Fact]
    public void ToleraEstadoSemTentativas()
    {
        var state=System.Text.Json.JsonSerializer.Deserialize<StickerCatalogState>("{\"RetryAt\":0}")!;
        state=StickerCatalogPolicy.Prepare(state,2000);
        Assert.Empty(state.Attempts);Assert.Null(state.Reason);
    }
    [Fact]
    public void RespeitaServidorEDiferenciaFalhaDeCota()
    {
        var state=StickerCatalogPolicy.Response(new([]),429,1000,"120");
        Assert.Equal(121000,state.RetryAt);Assert.Equal("limite",state.Reason);
        state=StickerCatalogPolicy.Response(state,503,2000);
        Assert.Equal("indisponivel",state.Reason);Assert.Equal(62000,state.RetryAt);
        state=StickerCatalogPolicy.Response(state,200,3000);
        Assert.Null(state.Reason);Assert.Equal(0,state.RetryAt);Assert.Equal(0,state.Failures);
    }
    [Fact]
    public void RetryAfterAceitaDataENaoAntecipaRetorno()
    {
        var now=DateTimeOffset.Parse("2026-10-20T10:00:00Z").ToUnixTimeMilliseconds();
        Assert.Equal(120000,StickerCatalogPolicy.RetryDelay("Tue, 20 Oct 2026 10:02:00 GMT",now));
        Assert.Equal(60000,StickerCatalogPolicy.RetryDelay("inválido",now));
        Assert.Equal(1000,StickerCatalogPolicy.RetryDelay("0",now));
    }
}
