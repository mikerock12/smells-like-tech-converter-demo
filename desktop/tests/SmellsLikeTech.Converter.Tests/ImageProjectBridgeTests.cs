using System.Net.Http.Json;
using System.Text.Json;
using ImageMagick;
using SmellsLikeTech.Converter.Bridge;
using SmellsLikeTech.Converter.Bridge.Hosting;
using SmellsLikeTech.Converter.Bridge.Tray;
using SmellsLikeTech.Converter.Composition;
using SmellsLikeTech.Converter.Engine.Image;
using SmellsLikeTech.Converter.Infrastructure.Storage;
using Xunit;

namespace SmellsLikeTech.Converter.Tests;

public sealed class ImageProjectBridgeTests
{
    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task ProjetoAndroidEnviadoPorHttpProduzJxlComAlfa(bool recortar)
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
                TestHttpAsync(services, ui, recortar).GetAwaiter().GetResult();
            });
        }
        finally
        {
            Microsoft.Data.Sqlite.SqliteConnection.ClearAllPools();
            for (int tentativa = 0; ; tentativa++)
            {
                try { root.Delete(true); break; }
                catch (IOException) when (tentativa < 5) { await Task.Delay(100 * (tentativa + 1)); }
            }
        }
    }

    private static async Task TestHttpAsync(AppServices services, UiThread ui, bool recortar)
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
        var projectBytes = await File.ReadAllBytesAsync(fixture, token);
        if (recortar)
        {
            using var input = new MemoryStream(projectBytes);
            var project = ImageProject.Open(input);
            project.Crop(8, 4, 12, 10);
            using var output = new MemoryStream();
            project.Save(output);
            projectBytes = output.ToArray();
        }
        content.Add(new ByteArrayContent(projectBytes), "arquivo", "editor-android.sltimage");
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
        Assert.Equal(recortar ? 12u : 32u, image.Width);
        Assert.Equal(recortar ? 10u : 24u, image.Height);
        using var pixels = image.GetPixels();
        Assert.Equal(new byte[] { 36, 156, 214, 255 }, pixels.GetPixel(0, 0).ToArray());
        Assert.Equal(new byte[4], pixels.GetPixel((int)image.Width - 1, (int)image.Height - 1).ToArray());
    }
}
