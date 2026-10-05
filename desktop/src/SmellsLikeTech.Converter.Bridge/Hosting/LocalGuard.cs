using System.Net;
using System.Text.Json;
using Microsoft.AspNetCore.Http;
using SmellsLikeTech.Converter.Bridge.Contracts;

namespace SmellsLikeTech.Converter.Bridge.Hosting;

/// <summary>
/// A porta de entrada do plugin, e a unica coisa que separa a maquina do cliente do
/// resto da internet.
///
/// Tres travas, em ordem:
/// 1. so aceita conexao vinda do proprio computador;
/// 2. so responde a origens que autorizamos, e recusa qualquer outra antes de executar
///    qualquer coisa — nao basta o navegador esconder a resposta;
/// 3. exige um cabecalho proprio nas rotas que mudam algo, para que o navegador seja
///    obrigado a pedir permissao antes (e ali a origem e conferida).
/// </summary>
public sealed class LocalGuard(BridgeOptions options)
{
    public async Task InvokeAsync(HttpContext context, Func<Task> next)
    {
        context.Response.Headers.CacheControl = "no-store";
        context.Response.Headers.XContentTypeOptions = "nosniff";

        if (!VeioDoProprioComputador(context))
        {
            await RecusarAsync(context, HttpStatusCode.Forbidden, "fora_do_computador",
                "O plugin só atende pedidos feitos neste computador.");
            return;
        }

        var origem = context.Request.Headers.Origin.ToString();
        var temOrigem = !string.IsNullOrEmpty(origem);
        var origemAutorizada = temOrigem && options.AllowedOrigins.Contains(origem);

        if (temOrigem && !origemAutorizada)
        {
            await RecusarAsync(context, HttpStatusCode.Forbidden, "origem_nao_autorizada",
                "Este site não tem permissão para usar o plugin.");
            return;
        }

        if (origemAutorizada)
        {
            context.Response.Headers.AccessControlAllowOrigin = origem;
            context.Response.Headers.Append("Vary", "Origin");
            context.Response.Headers.AccessControlExposeHeaders = "Content-Disposition";

            // Chrome exige esta confirmacao para uma pagina publica alcancar a rede local.
            if (string.Equals(
                    context.Request.Headers["Access-Control-Request-Private-Network"],
                    "true",
                    StringComparison.OrdinalIgnoreCase))
            {
                context.Response.Headers["Access-Control-Allow-Private-Network"] = "true";
            }
        }

        if (HttpMethods.IsOptions(context.Request.Method))
        {
            await ResponderVerificacaoPreviaAsync(context, origemAutorizada);
            return;
        }

        if (MudaAlgo(context.Request.Method)
            && !context.Request.Headers.ContainsKey(BridgeOptions.RequiredHeader))
        {
            await RecusarAsync(context, HttpStatusCode.BadRequest, "cabecalho_ausente",
                $"Pedidos que alteram algo precisam do cabeçalho {BridgeOptions.RequiredHeader}.");
            return;
        }

        await next();
    }

    private static bool MudaAlgo(string metodo) => !HttpMethods.IsGet(metodo) && !HttpMethods.IsHead(metodo);

    /// <summary>
    /// Kestrel ja escuta so em loopback, mas isso confere o endereco real da conexao:
    /// se algum dia alguem trocar a configuracao de escuta, esta trava continua de pe.
    /// </summary>
    private static bool VeioDoProprioComputador(HttpContext context)
    {
        var remoto = context.Connection.RemoteIpAddress;
        if (remoto is not null && !IPAddress.IsLoopback(remoto))
        {
            return false;
        }

        var host = context.Request.Host.Host;
        return string.Equals(host, "127.0.0.1", StringComparison.OrdinalIgnoreCase)
            || string.Equals(host, "localhost", StringComparison.OrdinalIgnoreCase)
            || string.Equals(host, "[::1]", StringComparison.OrdinalIgnoreCase)
            || string.Equals(host, "::1", StringComparison.OrdinalIgnoreCase);
    }

    private static async Task ResponderVerificacaoPreviaAsync(HttpContext context, bool origemAutorizada)
    {
        if (!origemAutorizada)
        {
            context.Response.StatusCode = StatusCodes.Status403Forbidden;
            return;
        }

        var metodo = context.Request.Headers.AccessControlRequestMethod.ToString();
        if (metodo is not ("GET" or "POST"))
        {
            context.Response.StatusCode = StatusCodes.Status405MethodNotAllowed;
            return;
        }

        context.Response.Headers.AccessControlAllowMethods = "GET, POST, OPTIONS";
        context.Response.Headers.AccessControlAllowHeaders = $"Content-Type, {BridgeOptions.RequiredHeader}";
        context.Response.Headers.AccessControlMaxAge = "600";
        context.Response.StatusCode = StatusCodes.Status204NoContent;
    }

    /// <summary>
    /// Escreve a recusa na mao, sem passar pelos ajudantes do framework.
    ///
    /// Aqueles resolvem servicos pelo contexto, e esta e a primeira coisa que roda em
    /// qualquer pedido: nao pode depender de nada estar montado para conseguir dizer nao.
    /// </summary>
    private static async Task RecusarAsync(HttpContext context, HttpStatusCode status, string codigo, string mensagem)
    {
        context.Response.StatusCode = (int)status;
        context.Response.ContentType = "application/json; charset=utf-8";

        var corpo = JsonSerializer.SerializeToUtf8Bytes(new ErroResposta(codigo, mensagem), BridgeJson.Options);
        await context.Response.Body.WriteAsync(corpo);
    }
}
