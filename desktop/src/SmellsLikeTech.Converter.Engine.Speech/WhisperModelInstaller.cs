using SmellsLikeTech.Converter.Core;
using SmellsLikeTech.Converter.Core.Abstractions;

namespace SmellsLikeTech.Converter.Engine.Speech;

/// <summary>
/// Baixa um modelo Whisper sob demanda, sempre por acao explicita do usuario na tela
/// de configuracoes. Grava primeiro em .part e so renomeia ao concluir.
/// </summary>
public sealed class WhisperModelInstaller(WhisperModelCatalog catalog, IConverterLog log)
{
    public async Task InstallAsync(
        string modelId,
        IProgress<double>? progress,
        CancellationToken cancellationToken)
    {
        var descriptor = catalog.Descriptor(modelId)
            ?? throw new ConversionException("unsupported_model", "Modelo desconhecido.");

        Directory.CreateDirectory(catalog.ModelsDirectory);
        var destination = catalog.PathFor(descriptor);
        var temporary = destination + ".part";

        using var client = new HttpClient { Timeout = TimeSpan.FromHours(2) };
        try
        {
            using var response = await client.GetAsync(
                descriptor.DownloadUrl,
                HttpCompletionOption.ResponseHeadersRead,
                cancellationToken);
            response.EnsureSuccessStatusCode();

            var total = response.Content.Headers.ContentLength ?? descriptor.ApproximateBytes;
            await using (var source = await response.Content.ReadAsStreamAsync(cancellationToken))
            await using (var target = File.Create(temporary))
            {
                var buffer = new byte[256 * 1024];
                long received = 0;
                int read;
                while ((read = await source.ReadAsync(buffer, cancellationToken)) > 0)
                {
                    await target.WriteAsync(buffer.AsMemory(0, read), cancellationToken);
                    received += read;
                    progress?.Report(total > 0 ? Math.Clamp((double)received / total, 0, 1) : 0);
                }
            }

            File.Move(temporary, destination, overwrite: true);
            log.Write(LogChannel.Speech, $"Modelo Whisper '{modelId}' instalado.");
        }
        catch (HttpRequestException exception)
        {
            TryDelete(temporary);
            throw new ConversionException("model_download_failed", "Não foi possível baixar o modelo.", exception);
        }
        catch (OperationCanceledException)
        {
            TryDelete(temporary);
            throw;
        }
    }

    public void Remove(string modelId)
    {
        var descriptor = catalog.Descriptor(modelId);
        if (descriptor is null)
        {
            return;
        }

        TryDelete(catalog.PathFor(descriptor));
        log.Write(LogChannel.Speech, $"Modelo Whisper '{modelId}' removido.");
    }

    private static void TryDelete(string path)
    {
        try
        {
            File.Delete(path);
        }
        catch (IOException)
        {
            // Arquivo em uso: sera substituido na proxima instalacao.
        }
        catch (UnauthorizedAccessException)
        {
            // Sem permissao para remover.
        }
    }
}
