using System.Net.Http.Json;
using System.Text.Json;
using ImageMagick;
using SmellsLikeTech.Converter.Bridge;
using SmellsLikeTech.Converter.Bridge.Hosting;
using SmellsLikeTech.Converter.Bridge.Tray;
using SmellsLikeTech.Converter.Composition;
using SmellsLikeTech.Converter.Infrastructure.Storage;
using Xunit;

namespace SmellsLikeTech.Converter.Tests;

public sealed class ImageProjectBridgeTests
{
    [Fact]
    public async Task ProjetoAndroidEnviadoPorHttpProduzJxlComAlfa()
    {
        var root = Directory.CreateTempSubdirectory("slt-image-bridge-");
        try
        {
            await using var services = await AppServices.StartAsync(initialPaths: new ConverterPaths(root.FullName));
            // A caixa de arquivos tem afinidade de thread. O caminho HTTP deste teste usa multipart.
            await Task.Run(() =>
            {
                var context = SynchronizationContext.Current;
                using var ui = new UiThread();
                SynchronizationContext.SetSynchronizationContext(context);
                TestHttpAsync(services, ui).GetAwaiter().GetResult();
            });
        }
        finally
        {
            Microsoft.Data.Sqlite.SqliteConnection.ClearAllPools();
            root.Delete(true);
        }
    }

    private static async Task TestHttpAsync(AppServices services, UiThread ui)
    {
        using var timeout = new CancellationTokenSource(TimeSpan.FromMinutes(2));
        var token = timeout.Token;
        await using var bridge = new BridgeServer(services, new BridgeOptions { Port = 0 }, ui);
        await bridge.IniciarAsync(token);
        using var client = new HttpClient { BaseAddress = bridge.Endereco };
        client.DefaultRequestHeaders.Add(BridgeOptions.RequiredHeader, "1");
        client.DefaultRequestHeaders.Add("Origin", "http://localhost:3000");
        var hello = await client.GetFromJsonAsync<JsonElement>("/v1/ola", token);
        Assert.Contains(hello.GetProperty("operacoes").EnumerateArray(), op =>
            op.GetProperty("id").GetString() == "image.renderProject" && op.GetProperty("disponivel").GetBoolean());
        using var content = new MultipartFormDataContent();
        var fixture = Path.Combine(AppContext.BaseDirectory, "Fixtures", "editor-android.sltimage");
        content.Add(new ByteArrayContent(await File.ReadAllBytesAsync(fixture, token)), "arquivo", "editor-android.sltimage");
        content.Add(new StringContent("""{"operation":"image.renderProject","OutputFormat":"jxl"}"""), "opcoes");
        using var response = await client.PostAsync("/v1/trabalhos", content, token);
        response.EnsureSuccessStatusCode();
        var job = await response.Content.ReadFromJsonAsync<JsonElement>(token);
        var url = "/v1/trabalhos/" + job.GetProperty("id").GetString();
        while (!job.GetProperty("encerrado").GetBoolean())
        {
            await Task.Delay(100, token);
            job = await client.GetFromJsonAsync<JsonElement>(url, token);
        }
        Assert.True(job.GetProperty("arquivos").GetArrayLength() == 1, job.ToString());
        var bytes = await client.GetByteArrayAsync(url + "/arquivo/0", token);
        using var image = new MagickImage(bytes);
        Assert.Equal(MagickFormat.Jxl, image.Format);
        using var pixels = image.GetPixels();
        Assert.Equal(new byte[] { 36, 156, 214, 255 }, pixels.GetPixel(0, 0).ToArray());
        Assert.Equal(new byte[4], pixels.GetPixel(31, 23).ToArray());
    }
}
