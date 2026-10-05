namespace SmellsLikeTech.Converter.Bridge;

/// <summary>
/// Configuracao do plugin. Tudo tem padrao util: quem instala nao precisa configurar nada.
/// </summary>
public sealed record BridgeOptions
{
    /// <summary>
    /// Porta fixa em loopback. Precisa ser previsivel, senao o site nao sabe onde procurar.
    /// Diferente da porta do worker antigo (5088) para os dois poderem conviver.
    /// </summary>
    public const int DefaultPort = 5199;

    /// <summary>Cabecalho obrigatorio nas rotas que mudam algo.</summary>
    /// <remarks>
    /// Defesa contra CSRF. Um POST multipart e considerado "simples" pelo navegador e
    /// escapa da verificacao previa; exigir um cabecalho proprio obriga a verificacao,
    /// e ali a origem e conferida. Sem isto, qualquer site aberto noutra aba poderia
    /// disparar conversoes nesta maquina.
    /// </remarks>
    public const string RequiredHeader = "X-Smells-Like-Tech";

    public int Port { get; init; } = DefaultPort;

    /// <summary>Origens autorizadas a falar com o plugin.</summary>
    public IReadOnlySet<string> AllowedOrigins { get; init; } = DefaultOrigins();

    /// <summary>Tamanho maximo de arquivo enviado pelo navegador.</summary>
    /// <remarks>
    /// Arquivo grande deve entrar pelo seletor nativo, que trabalha com o caminho no disco
    /// e nao copia nada. Este limite vale so para o que sobe pelo navegador.
    /// </remarks>
    public long MaxUploadBytes { get; init; } = 4L * 1024 * 1024 * 1024;

    public static BridgeOptions FromEnvironment()
    {
        var origins = DefaultOrigins();
        var configured = Environment.GetEnvironmentVariable("SMELLSLIKETECH_ORIGENS");
        if (!string.IsNullOrWhiteSpace(configured))
        {
            foreach (var candidate in configured.Split(';', StringSplitOptions.TrimEntries | StringSplitOptions.RemoveEmptyEntries))
            {
                if (TryNormalizeOrigin(candidate, out var origin))
                {
                    origins.Add(origin);
                }
            }
        }

        return new BridgeOptions
        {
            Port = ReadPort(),
            AllowedOrigins = origins
        };
    }

    private static int ReadPort()
    {
        var raw = Environment.GetEnvironmentVariable("SMELLSLIKETECH_PORTA");
        return int.TryParse(raw, out var port) && port is >= 1_024 and <= 65_535 ? port : DefaultPort;
    }

    private static HashSet<string> DefaultOrigins() => new(StringComparer.Ordinal)
    {
        "https://converter.smellsliketech.com.br",
        "https://www.smellsliketech.com.br",
        "https://smellsliketech.com.br",
        // Desenvolvimento do proprio site.
        "http://localhost:3000",
        "http://127.0.0.1:3000"
    };

    /// <summary>Aceita apenas a origem completa: esquema, host e porta. Nada de caminho ou curinga.</summary>
    public static bool TryNormalizeOrigin(string candidate, out string origin)
    {
        origin = string.Empty;
        if (!Uri.TryCreate(candidate, UriKind.Absolute, out var uri)
            || uri.Scheme is not ("http" or "https")
            || string.IsNullOrEmpty(uri.Host)
            || !string.IsNullOrEmpty(uri.UserInfo)
            || !string.IsNullOrEmpty(uri.Query)
            || !string.IsNullOrEmpty(uri.Fragment)
            || uri.AbsolutePath != "/")
        {
            return false;
        }

        origin = uri.GetLeftPart(UriPartial.Authority);
        return true;
    }
}
