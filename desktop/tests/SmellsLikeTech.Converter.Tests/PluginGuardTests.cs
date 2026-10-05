using System.Net;
using System.Text.Json;
using Microsoft.AspNetCore.Http;
using SmellsLikeTech.Converter.Bridge;
using SmellsLikeTech.Converter.Bridge.Contracts;
using SmellsLikeTech.Converter.Bridge.Hosting;
using SmellsLikeTech.Converter.Core.Jobs;
using SmellsLikeTech.Converter.Core.Options;
using Xunit;

namespace SmellsLikeTech.Converter.Tests;

/// <summary>
/// O plugin abre uma porta na maquina do cliente. Estes testes existem para que nenhuma
/// alteracao futura afrouxe isso sem alguem perceber.
/// </summary>
public sealed class PluginGuardTests
{
    private static readonly BridgeOptions Opcoes = new()
    {
        Port = 5199,
        AllowedOrigins = new HashSet<string>(StringComparer.Ordinal)
        {
            "https://converter.smellsliketech.com.br",
            "http://localhost:3000"
        }
    };

    [Fact]
    public async Task RecusaOrigemDesconhecida()
    {
        var contexto = Pedido("GET", "/v1/ola", origem: "https://site-qualquer.example");
        var passou = await ExecutarAsync(contexto);

        Assert.False(passou);
        Assert.Equal(StatusCodes.Status403Forbidden, contexto.Response.StatusCode);
        Assert.Equal("origem_nao_autorizada", await CodigoDoErroAsync(contexto));
    }

    [Fact]
    public async Task AceitaOrigemAutorizadaEDevolveOsCabecalhos()
    {
        var contexto = Pedido("GET", "/v1/ola", origem: "http://localhost:3000");
        var passou = await ExecutarAsync(contexto);

        Assert.True(passou);
        Assert.Equal("http://localhost:3000", contexto.Response.Headers.AccessControlAllowOrigin);
        Assert.Contains("Origin", contexto.Response.Headers.Vary.ToString(), StringComparison.Ordinal);
        Assert.Equal("no-store", contexto.Response.Headers.CacheControl);
        Assert.Equal("nosniff", contexto.Response.Headers.XContentTypeOptions);
    }

    [Fact]
    public async Task RecusaConexaoQueNaoVeioDoProprioComputador()
    {
        var contexto = Pedido("GET", "/v1/ola", origem: "http://localhost:3000");
        contexto.Connection.RemoteIpAddress = IPAddress.Parse("192.168.0.42");

        var passou = await ExecutarAsync(contexto);

        Assert.False(passou);
        Assert.Equal(StatusCodes.Status403Forbidden, contexto.Response.StatusCode);
        Assert.Equal("fora_do_computador", await CodigoDoErroAsync(contexto));
    }

    [Fact]
    public async Task RecusaHostQueNaoSejaLocal()
    {
        // Defesa contra reamarracao de DNS: o atacante aponta um dominio dele para
        // 127.0.0.1, entao a conexao e mesmo local, mas o cabecalho Host o entrega.
        var contexto = Pedido("GET", "/v1/ola", origem: "http://localhost:3000");
        contexto.Request.Host = new HostString("meu-dominio-malicioso.example");

        var passou = await ExecutarAsync(contexto);

        Assert.False(passou);
        Assert.Equal(StatusCodes.Status403Forbidden, contexto.Response.StatusCode);
    }

    [Fact]
    public async Task ExigeCabecalhoProprioNoQueAltera()
    {
        // Um POST multipart passa sem verificacao previa. Sem esta trava, qualquer
        // pagina aberta noutra aba conseguiria disparar conversoes nesta maquina.
        var contexto = Pedido("POST", "/v1/trabalhos", origem: "http://localhost:3000", comCabecalho: false);
        var passou = await ExecutarAsync(contexto);

        Assert.False(passou);
        Assert.Equal(StatusCodes.Status400BadRequest, contexto.Response.StatusCode);
        Assert.Equal("cabecalho_ausente", await CodigoDoErroAsync(contexto));
    }

    [Fact]
    public async Task DeixaPassarQuandoOCabecalhoVem()
    {
        var contexto = Pedido("POST", "/v1/trabalhos", origem: "http://localhost:3000");
        Assert.True(await ExecutarAsync(contexto));
    }

    [Fact]
    public async Task LeituraNaoPrecisaDoCabecalho()
    {
        var contexto = Pedido("GET", "/v1/trabalhos", origem: "http://localhost:3000", comCabecalho: false);
        Assert.True(await ExecutarAsync(contexto));
    }

    [Fact]
    public async Task VerificacaoPreviaAutorizaARedePrivada()
    {
        var contexto = Pedido("OPTIONS", "/v1/trabalhos", origem: "https://converter.smellsliketech.com.br");
        contexto.Request.Headers.AccessControlRequestMethod = "POST";
        contexto.Request.Headers["Access-Control-Request-Private-Network"] = "true";

        var passou = await ExecutarAsync(contexto);

        Assert.False(passou);
        Assert.Equal(StatusCodes.Status204NoContent, contexto.Response.StatusCode);
        Assert.Equal("true", contexto.Response.Headers["Access-Control-Allow-Private-Network"]);
        Assert.Contains(
            BridgeOptions.RequiredHeader,
            contexto.Response.Headers.AccessControlAllowHeaders.ToString(),
            StringComparison.Ordinal);
    }

    [Fact]
    public async Task VerificacaoPreviaDeOrigemEstranhaNaoRevelaNada()
    {
        var contexto = Pedido("OPTIONS", "/v1/trabalhos", origem: "https://site-qualquer.example");
        contexto.Request.Headers.AccessControlRequestMethod = "POST";

        await ExecutarAsync(contexto);

        Assert.Equal(StatusCodes.Status403Forbidden, contexto.Response.StatusCode);
        Assert.True(StringValuesVazio(contexto.Response.Headers.AccessControlAllowOrigin));
    }

    [Fact]
    public async Task RecusaMetodoForaDoCombinado()
    {
        var contexto = Pedido("OPTIONS", "/v1/trabalhos", origem: "http://localhost:3000");
        contexto.Request.Headers.AccessControlRequestMethod = "DELETE";

        await ExecutarAsync(contexto);

        Assert.Equal(StatusCodes.Status405MethodNotAllowed, contexto.Response.StatusCode);
    }

    [Theory]
    [InlineData("https://exemplo.com.br", true)]
    [InlineData("http://localhost:4000", true)]
    [InlineData("https://exemplo.com.br/caminho", false)]
    [InlineData("https://exemplo.com.br?a=1", false)]
    [InlineData("https://usuario@exemplo.com.br", false)]
    [InlineData("ftp://exemplo.com.br", false)]
    [InlineData("*", false)]
    [InlineData("", false)]
    public void SoAceitaOrigemCompleta(string candidato, bool esperado)
    {
        Assert.Equal(esperado, BridgeOptions.TryNormalizeOrigin(candidato, out _));
    }

    [Fact]
    public void OrigensPadraoNaoIncluemCuringaNemHttpDeProducao()
    {
        var padrao = BridgeOptions.FromEnvironment().AllowedOrigins;

        Assert.Contains("https://converter.smellsliketech.com.br", padrao);
        Assert.DoesNotContain("*", padrao);
        Assert.DoesNotContain("http://converter.smellsliketech.com.br", padrao);
        Assert.All(padrao, origem => Assert.DoesNotContain("/", origem.AsSpan(8).ToString(), StringComparison.Ordinal));
    }

    // ==================== ajudantes ====================

    private static DefaultHttpContext Pedido(
        string metodo,
        string caminho,
        string origem,
        bool comCabecalho = true)
    {
        var contexto = new DefaultHttpContext();
        contexto.Request.Method = metodo;
        contexto.Request.Path = caminho;
        contexto.Request.Host = new HostString("127.0.0.1", 5199);
        contexto.Request.Headers.Origin = origem;
        contexto.Connection.RemoteIpAddress = IPAddress.Loopback;
        contexto.Response.Body = new MemoryStream();

        if (comCabecalho)
        {
            contexto.Request.Headers[BridgeOptions.RequiredHeader] = "1";
        }

        return contexto;
    }

    /// <summary>Devolve true quando o pedido chegou ao outro lado da porta.</summary>
    private static async Task<bool> ExecutarAsync(HttpContext contexto)
    {
        var passou = false;
        var guarda = new LocalGuard(Opcoes);
        await guarda.InvokeAsync(contexto, () =>
        {
            passou = true;
            return Task.CompletedTask;
        });

        return passou;
    }

    private static async Task<string?> CodigoDoErroAsync(HttpContext contexto)
    {
        contexto.Response.Body.Position = 0;
        var erro = await JsonSerializer.DeserializeAsync<ErroResposta>(contexto.Response.Body, BridgeJson.Options);
        return erro?.Codigo;
    }

    private static bool StringValuesVazio(Microsoft.Extensions.Primitives.StringValues valores) =>
        valores.Count == 0 || string.IsNullOrEmpty(valores.ToString());
}

/// <summary>O retrato que o site recebe precisa dizer a verdade sobre o job.</summary>
public sealed class PluginContractTests
{
    [Fact]
    public void TraduzOJobParaOFormatoDoSite()
    {
        var retrato = new JobSnapshot
        {
            Id = Guid.NewGuid(),
            Operation = "video.extractAudio",
            DisplayName = "reuniao.mp4",
            OptionsSummary = "MP3 · 192 kbps",
            Status = JobStatus.Processing,
            Stage = JobStage.Encoding,
            Progress = 0.42,
            Eta = TimeSpan.FromSeconds(75),
            SpeedFactor = 9.5,
            InputBytes = 1_000,
            OutputBytes = 0
        };

        var resposta = TrabalhoResposta.De(retrato);

        Assert.Equal("Convertendo", resposta.Estado);
        Assert.Equal("codificando", resposta.Etapa);
        Assert.False(resposta.Encerrado);
        Assert.Equal(0.42, resposta.Progresso);
        Assert.Equal(75, resposta.SegundosRestantes);
        Assert.Empty(resposta.Arquivos);
    }

    [Fact]
    public void MarcaComoEncerradoQuandoTerminaEmErro()
    {
        var retrato = new JobSnapshot
        {
            Id = Guid.NewGuid(),
            Operation = "speech.transcribe",
            DisplayName = "audio.ogg",
            OptionsSummary = "SRT",
            Status = JobStatus.Failed,
            Stage = JobStage.Done,
            ErrorCode = "model_missing",
            ErrorMessage = "Nenhum modelo instalado."
        };

        var resposta = TrabalhoResposta.De(retrato);

        Assert.True(resposta.Encerrado);
        Assert.Equal("Falhou", resposta.Estado);
        Assert.Equal("model_missing", resposta.CodigoDoErro);
        Assert.Equal("Nenhum modelo instalado.", resposta.Erro);
    }

    [Fact]
    public void OSiteEnviaOMesmoJsonDeOpcoesQueOAplicativoUsa()
    {
        // O contrato de opcoes nao e uma segunda linguagem: e o mesmo do produto.
        // Se algum dia divergir, o plugin passa a recusar o que o site manda.
        const string doSite = """
            {"operation":"video.extractAudio","OutputFormat":"mp3","AudioBitrateKbps":192}
            """;

        var opcoes = JobOptionsJson.Deserialize(doSite);

        var extrair = Assert.IsType<ExtractAudioOptions>(opcoes);
        Assert.Equal("mp3", extrair.OutputFormat);
        Assert.Equal(192, extrair.AudioBitrateKbps);
        extrair.Validate();
    }

    [Fact]
    public void OpcoesInventadasNaoViramJob()
    {
        Assert.Null(JobOptionsJson.Deserialize("""{"operation":"apagar.tudo"}"""));
        Assert.Null(JobOptionsJson.Deserialize("nao e json"));
    }
}
