using System.Text.Json;
using System.Text.Json.Serialization;
using SmellsLikeTech.Converter.Core.Jobs;

namespace SmellsLikeTech.Converter.Bridge.Contracts;

/// <summary>
/// O que o site ve. Nomes em portugues porque este contrato e lido do outro lado por
/// codigo do site, e nao por outro programa .NET.
/// </summary>
public static class BridgeJson
{
    public static JsonSerializerOptions Options { get; } = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
        Converters = { new JsonStringEnumConverter(JsonNamingPolicy.CamelCase) }
    };
}

/// <summary>Resposta de /v1/ola: quem sou, o que sei fazer e com o que conto nesta maquina.</summary>
public sealed record ApresentacaoResposta
{
    public string Produto { get; init; } = "Smells Like Tech Converter";
    public string Papel { get; init; } = "plugin";
    public required string Versao { get; init; }
    public required int Protocolo { get; init; }
    public required MaquinaResumo Maquina { get; init; }
    public required IReadOnlyList<OperacaoResumo> Operacoes { get; init; }
    public required string PastaDeSaida { get; init; }
}

/// <summary>O hardware que o plugin encontrou — o motivo de ele existir.</summary>
public sealed record MaquinaResumo
{
    public required string Processador { get; init; }
    public required int Nucleos { get; init; }
    public required string PlacaDeVideo { get; init; }
    public required IReadOnlyList<string> AceleradoresDeVideo { get; init; }
    public required bool FfmpegPresente { get; init; }
    public required IReadOnlyList<string> ModelosDeTranscricao { get; init; }
    public required IReadOnlyList<string> VozesInstaladas { get; init; }
    public IReadOnlyList<string> IdsDasVozes { get; init; } = [];
}

/// <summary>Uma operacao oferecida, com o motivo caso esteja indisponivel.</summary>
public sealed record OperacaoResumo
{
    public required string Id { get; init; }
    public required string Titulo { get; init; }
    public required string Descricao { get; init; }
    public required IReadOnlyList<string> Entradas { get; init; }
    public required IReadOnlyList<string> Saidas { get; init; }
    public required bool Disponivel { get; init; }
    public string? Impedimento { get; init; }
}

/// <summary>Retrato de um job, no formato que o site consome.</summary>
public sealed record TrabalhoResposta
{
    public required Guid Id { get; init; }
    public required string Operacao { get; init; }
    public required string Nome { get; init; }
    public required string Estado { get; init; }
    public required string Etapa { get; init; }
    public required bool Encerrado { get; init; }
    public double? Progresso { get; init; }
    public string? Mensagem { get; init; }
    public string? Erro { get; init; }
    public string? CodigoDoErro { get; init; }
    public double? SegundosRestantes { get; init; }
    public double? Velocidade { get; init; }
    public long BytesDeEntrada { get; init; }
    public long BytesDeSaida { get; init; }
    public required IReadOnlyList<ArquivoResposta> Arquivos { get; init; }

    public static TrabalhoResposta De(JobSnapshot snapshot) => new()
    {
        Id = snapshot.Id,
        Operacao = snapshot.Operation,
        Nome = snapshot.DisplayName,
        Estado = snapshot.Status.Display(),
        Etapa = snapshot.Stage.Display(),
        Encerrado = snapshot.Status.IsTerminal(),
        Progresso = snapshot.Progress,
        Mensagem = snapshot.Message,
        Erro = snapshot.ErrorMessage,
        CodigoDoErro = snapshot.ErrorCode,
        SegundosRestantes = snapshot.Eta?.TotalSeconds,
        Velocidade = snapshot.SpeedFactor,
        BytesDeEntrada = snapshot.InputBytes,
        BytesDeSaida = snapshot.OutputBytes,
        Arquivos = [.. snapshot.OutputFiles.Select((caminho, indice) => ArquivoResposta.De(caminho, indice))]
    };
}

/// <summary>Um arquivo produzido. O caminho completo fica na maquina; o site recebe so o nome.</summary>
public sealed record ArquivoResposta
{
    public required int Indice { get; init; }
    public required string Nome { get; init; }
    public required long Bytes { get; init; }

    public static ArquivoResposta De(string caminho, int indice) => new()
    {
        Indice = indice,
        Nome = Path.GetFileName(caminho),
        Bytes = TamanhoDe(caminho)
    };

    private static long TamanhoDe(string caminho)
    {
        try
        {
            return new FileInfo(caminho).Length;
        }
        catch (IOException)
        {
            return 0;
        }
    }
}

/// <summary>Criacao de job a partir de um arquivo que ja esta no disco desta maquina.</summary>
public sealed record TrabalhoPorCaminhoPedido
{
    public required string Caminho { get; init; }
    public required JsonElement Opcoes { get; init; }
    public string? NomeDeSaida { get; init; }

    /// <summary>Arquivos alem do primeiro, para juntar PDF e imagens para PDF.</summary>
    public IReadOnlyList<string>? Caminhos { get; init; }
}

/// <summary>Resultado do seletor nativo de arquivos.</summary>
public sealed record ArquivoEscolhido
{
    public required string Caminho { get; init; }
    public required string Nome { get; init; }
    public required long Bytes { get; init; }
}

public sealed record ErroResposta(string Codigo, string Mensagem);
