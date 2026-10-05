using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.WebUtilities;
using Microsoft.Net.Http.Headers;
using SmellsLikeTech.Converter.Core;
using SmellsLikeTech.Converter.Core.Jobs;

namespace SmellsLikeTech.Converter.Bridge.Hosting;

/// <summary>Arquivo(s) recebido(s) do navegador, ja gravado(s) em disco. O primeiro e a entrada principal.</summary>
public sealed record EnvioRecebido(string Caminho, string NomeOriginal, string OpcoesJson, string? NomeDeSaida, IReadOnlyList<string> CaminhosExtras);

/// <summary>
/// Le o envio do navegador direto para o disco, em pedacos.
///
/// Nao usa o leitor de formulario pronto de proposito: aquele carrega o corpo inteiro
/// antes de entregar, e aqui pode chegar um video de gigabytes. Este atravessa o fluxo
/// gravando conforme chega, sem nunca ter o arquivo todo na memoria.
/// </summary>
public static class UploadReader
{
    public static async Task<EnvioRecebido> LerAsync(
        HttpRequest request,
        string pastaDestino,
        long limiteDeBytes,
        CancellationToken cancellationToken)
    {
        var fronteira = LerFronteira(request);
        var leitor = new MultipartReader(fronteira, request.Body);

        string? caminho = null;
        var extras = new List<string>();
        var nomeOriginal = "arquivo";
        string? opcoes = null;
        string? nomeDeSaida = null;

        try
        {
            for (var secao = await leitor.ReadNextSectionAsync(cancellationToken);
                 secao is not null;
                 secao = await leitor.ReadNextSectionAsync(cancellationToken))
            {
                if (!ContentDispositionHeaderValue.TryParse(secao.ContentDisposition, out var disposicao))
                {
                    continue;
                }

                var campo = disposicao.Name.Value?.Trim('"') ?? string.Empty;

                if (disposicao.FileName.HasValue || disposicao.FileNameStar.HasValue)
                {
                    // Varios arquivos so fazem sentido para juntar PDF e imagens para PDF; o
                    // engine confere. Aqui cada um e gravado com nome proprio, sem colisao.
                    if (extras.Count >= 200)
                    {
                        throw new ConversionException("envio_invalido", "Envie no máximo 200 arquivos por vez.");
                    }

                    var informado = (disposicao.FileNameStar.Value ?? disposicao.FileName.Value ?? "arquivo").Trim('"');
                    var nome = Path.GetFileName(informado);
                    var gravado = await GravarAsync(secao, pastaDestino, $"{(caminho is null ? 0 : extras.Count + 1):D3}-{nome}", limiteDeBytes, cancellationToken);
                    if (caminho is null)
                    {
                        nomeOriginal = nome;
                        caminho = gravado;
                    }
                    else
                    {
                        extras.Add(gravado);
                    }

                    continue;
                }

                var texto = await secao.ReadAsStringAsync(cancellationToken);
                if (string.Equals(campo, "opcoes", StringComparison.Ordinal))
                {
                    opcoes = texto;
                }
                else if (string.Equals(campo, "nomeDeSaida", StringComparison.Ordinal))
                {
                    nomeDeSaida = texto;
                }
            }
        }
        catch
        {
            var gravados = new List<string>(extras);
            if (caminho is not null)
            {
                gravados.Add(caminho);
            }

            foreach (var gravado in gravados)
            {
                if (File.Exists(gravado))
                {
                    File.Delete(gravado);
                }
            }

            throw;
        }

        if (caminho is null)
        {
            throw new ConversionException("arquivo_ausente", "Nenhum arquivo veio no envio.");
        }

        if (string.IsNullOrWhiteSpace(opcoes))
        {
            File.Delete(caminho);
            throw new ConversionException("opcoes_ausentes", "O campo 'opcoes' é obrigatório.");
        }

        return new EnvioRecebido(caminho, nomeOriginal, opcoes, nomeDeSaida, extras);
    }

    private static string LerFronteira(HttpRequest request)
    {
        if (!MediaTypeHeaderValue.TryParse(request.ContentType, out var tipo)
            || !tipo.MediaType.Equals("multipart/form-data", StringComparison.OrdinalIgnoreCase))
        {
            throw new ConversionException("formato_invalido", "O envio precisa ser multipart/form-data.");
        }

        var fronteira = HeaderUtilities.RemoveQuotes(tipo.Boundary).Value;
        if (string.IsNullOrWhiteSpace(fronteira))
        {
            throw new ConversionException("formato_invalido", "O envio veio sem delimitador.");
        }

        return fronteira;
    }

    private static async Task<string> GravarAsync(
        MultipartSection secao,
        string pastaDestino,
        string nomeOriginal,
        long limiteDeBytes,
        CancellationToken cancellationToken)
    {
        Directory.CreateDirectory(pastaDestino);

        // O nome vem do navegador: passa pelo saneador antes de virar caminho em disco.
        var seguro = SafeFileName.FromPath(nomeOriginal);
        var extensao = Path.GetExtension(nomeOriginal);
        var caminho = Path.Combine(pastaDestino, seguro + extensao);

        await using var destino = new FileStream(
            caminho, FileMode.Create, FileAccess.Write, FileShare.None, 128 * 1024, useAsync: true);

        var buffer = new byte[128 * 1024];
        long total = 0;

        while (true)
        {
            var lidos = await secao.Body.ReadAsync(buffer, cancellationToken);
            if (lidos == 0)
            {
                break;
            }

            total += lidos;
            if (total > limiteDeBytes)
            {
                await destino.DisposeAsync();
                File.Delete(caminho);
                throw new ConversionException(
                    "arquivo_grande_demais",
                    "Este arquivo é grande demais para subir pelo navegador. Use o botão de escolher pelo computador.");
            }

            await destino.WriteAsync(buffer.AsMemory(0, lidos), cancellationToken);
        }

        if (total == 0)
        {
            await destino.DisposeAsync();
            File.Delete(caminho);
            throw new ConversionException("arquivo_vazio", "O arquivo enviado está vazio.");
        }

        return caminho;
    }
}
