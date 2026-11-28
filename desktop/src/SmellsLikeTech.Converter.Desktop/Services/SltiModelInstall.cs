using System.Net.Http.Headers;
using SmellsLikeTech.Converter.Core.Licensing;
using SmellsLikeTech.Converter.Engine.Image;

namespace SmellsLikeTech.Converter.Desktop.Services;

internal static class SltiModelInstall
{
    private static readonly HttpClient Http = new(new HttpClientHandler { AllowAutoRedirect = false, UseCookies = false });
    public static async Task<SltiUpscaler> LoadAsync()
    {
        if (App.Services.EvaluateDesktopAccess().Access != DesktopAccess.Licensed)
            throw new InvalidOperationException("Ative a licença paga do Windows para usar o ampliador SLTI.");
        string path = Path.Combine(App.Services.Paths.Models, "imagem", SltiUpscaler.Digest + ".json");
        if (File.Exists(path) && new FileInfo(path).Length <= 4096)
        {
            try { return new SltiUpscaler(await File.ReadAllBytesAsync(path)); }
            catch (InvalidDataException) { /* Reinstala o arquivo quando a cópia local divergir. */ }
        }
        using var request = new HttpRequestMessage(HttpMethod.Post,
            "https://converter.smellsliketech.com.br/api/motores/ampliador-slti?cliente=windows");
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer",
            string.Concat(App.Services.Settings.LicenseKey.Where(c => !char.IsWhiteSpace(c))));
        using var response = await Http.SendAsync(request);
        if (!response.IsSuccessStatusCode) throw new IOException("Falha ao instalar o ampliador. Confira sua conexão e a licença do Windows.");
        var bytes = await response.Content.ReadAsByteArrayAsync();
        var model = new SltiUpscaler(bytes);
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        var temp = path + "." + Guid.NewGuid().ToString("N");
        try { await File.WriteAllBytesAsync(temp, bytes); File.Move(temp, path, true); }
        finally { File.Delete(temp); }
        return model;
    }
}
