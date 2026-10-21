using System.Globalization;
using System.Net.Http;
using System.Text;
using System.Text.Json;
using ImageMagick;
using SmellsLikeTech.Converter.Core.Studio;

namespace SmellsLikeTech.Converter.Desktop.Services;

public sealed record CatalogSticker(string Id,string Name,string Preview,string Source,string Provider);
public sealed record CatalogPage(string Provider,IReadOnlyList<CatalogSticker> Items,string? Notice=null,long RetryAt=0,int? NextPage=null);

/// <summary>Busca direta no GIPHY; só a cota é persistida. Noto acompanha o instalador.</summary>
public sealed class StickerCatalog
{
    public static StickerCatalog Instance { get; }=new();
    private static readonly HttpClient Http=new(new HttpClientHandler {AllowAutoRedirect=false}) {Timeout=TimeSpan.FromSeconds(25)};
    private readonly SemaphoreSlim gate=new(1,1);
    private StickerCatalogState state=new([]);
    private bool loaded;
    private static string StatePath=>Path.Combine(App.Services.Paths.Root,"catalogos","giphy-editor-cota.json");
    private static string NotoDirectory=>Path.Combine(AppContext.BaseDirectory,"Assets","stickers","noto");
    private static long Now=>DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();

    public async Task<CatalogPage> SearchAsync(string query,int page,CancellationToken token)
    {
        await gate.WaitAsync(token);
        try {
            if(!loaded) {
                try {state=JsonSerializer.Deserialize<StickerCatalogState>(await File.ReadAllTextAsync(StatePath,token))??new([]);} catch(Exception e) when(e is IOException or JsonException or UnauthorizedAccessException) {}
                loaded=true;
            }
            state=StickerCatalogPolicy.Prepare(state,Now);
            var key=Environment.GetEnvironmentVariable("GIPHY_WINDOWS_EDITOR_API_KEY");
            if(string.IsNullOrWhiteSpace(key)) return Noto(query);
            if(state.RetryAt>Now) return Noto(query,StickerCatalogPolicy.Notice(state.Reason),state.RetryAt);
            query=query.Trim(); if(query.Length>100) query=query[..100]; page=Math.Clamp(page,0,20);
            var endpoint=query.Length==0?"trending":"search";
            var url=$"https://api.giphy.com/v1/stickers/{endpoint}?api_key={Uri.EscapeDataString(key)}&limit=24&offset={page*24}&rating=pg"+(query.Length==0?"":$"&lang=pt&q={Uri.EscapeDataString(query)}");
            state=state with {Attempts=[..state.Attempts,Now]}; await SaveAsync();
            var status=0; string? retry=null;
            try {
                using var response=await Http.GetAsync(url,HttpCompletionOption.ResponseHeadersRead,token);
                status=(int)response.StatusCode;
                retry=response.Headers.TryGetValues("Retry-After",out var values)?values.FirstOrDefault():null;
                if(status==200) {
                    using var json=JsonDocument.Parse(await ReadAsync(response,2_000_000,token));
                    var root=json.RootElement;
                    if(root.TryGetProperty("meta",out var meta)&&meta.TryGetProperty("status",out var code)) status=code.GetInt32();
                    if(status==200&&root.TryGetProperty("data",out var data)) {
                        var items=data.EnumerateArray().Select(item=> {
                            var images=item.GetProperty("images"); var preview=images.GetProperty("fixed_height");
                            return new CatalogSticker(item.GetProperty("id").GetString()!,item.GetProperty("title").GetString()??"Sticker do GIPHY",
                                preview.GetProperty("url").GetString()!,images.GetProperty("original").GetProperty("url").GetString()!,"giphy");
                        }).Where(item=>ValidMedia(item.Preview)&&ValidMedia(item.Source)).ToList();
                        var pagination=root.GetProperty("pagination");
                        int? next=page<20&&pagination.GetProperty("offset").GetInt32()+pagination.GetProperty("count").GetInt32()<pagination.GetProperty("total_count").GetInt32()?page+1:null;
                        state=StickerCatalogPolicy.Response(state,200,Now); await SaveAsync();
                        return new("giphy",items,NextPage:next);
                    }
                    if(status==200) status=502;
                }
            } catch(OperationCanceledException) when(token.IsCancellationRequested) {throw;}
            catch(Exception e) when(e is HttpRequestException or TaskCanceledException or JsonException or InvalidOperationException or KeyNotFoundException or IOException) {status=0;}
            state=StickerCatalogPolicy.Response(state,status,Now,retry); await SaveAsync();
            return Noto(query,StickerCatalogPolicy.Notice(state.Reason),state.RetryAt);
        } finally {gate.Release();}
    }
    private async Task SaveAsync() {
        try {Directory.CreateDirectory(Path.GetDirectoryName(StatePath)!);await File.WriteAllTextAsync(StatePath,JsonSerializer.Serialize(state));}
        catch(Exception e) when(e is IOException or UnauthorizedAccessException) {}
    }
    private static string Normalize(string text)=>new string(text.Normalize(NormalizationForm.FormD).Where(c=>CharUnicodeInfo.GetUnicodeCategory(c)!=UnicodeCategory.NonSpacingMark).ToArray()).ToLowerInvariant();
    private static CatalogPage Noto(string query,string? notice=null,long retryAt=0) {
        using var json=JsonDocument.Parse(File.ReadAllText(Path.Combine(NotoDirectory,"catalogo.json")));
        var terms=Normalize(query).Split(' ',StringSplitOptions.RemoveEmptyEntries);
        var items=json.RootElement.EnumerateArray().Where(item=>terms.All(term=>Normalize(item.GetProperty("nome").GetString()+" "+item.GetProperty("termos").GetString()).Contains(term,StringComparison.Ordinal)))
            .Select(item=> {var id=item.GetProperty("codigo").GetString()!;var path=Path.Combine(NotoDirectory,id+".gif");return new CatalogSticker(id,item.GetProperty("nome").GetString()!,path,path,"noto");}).ToList();
        return new("noto",items,notice,retryAt);
    }
    public static bool ValidMedia(string url)=>Uri.TryCreate(url,UriKind.Absolute,out var uri)&&uri.Scheme=="https"&&(uri.Host=="giphy.com"||uri.Host.EndsWith(".giphy.com",StringComparison.OrdinalIgnoreCase));
    private static async Task<byte[]> ReadAsync(HttpResponseMessage response,int max,CancellationToken token) {
        if(response.Content.Headers.ContentLength>max) throw new IOException("O sticker é grande demais.");
        await using var stream=await response.Content.ReadAsStreamAsync(token); using var memory=new MemoryStream(); var buffer=new byte[65536];
        int count; while((count=await stream.ReadAsync(buffer,token))>0) {if(memory.Length+count>max) throw new IOException("O sticker é grande demais.");memory.Write(buffer,0,count);}
        return memory.ToArray();
    }
    public static async Task<string> ImportAsync(CatalogSticker item,bool animated,CancellationToken token) {
        byte[] bytes;
        if(item.Provider=="noto") bytes=await File.ReadAllBytesAsync(item.Source,token);
        else {
            if(!ValidMedia(item.Source)) throw new IOException("Endereço de sticker inválido.");
            using var response=await Http.GetAsync(item.Source,HttpCompletionOption.ResponseHeadersRead,token);
            if(!response.IsSuccessStatusCode) throw new IOException("Não foi possível baixar o sticker. Tente novamente.");
            bytes=await ReadAsync(response,32_000_000,token);
        }
        var folder=Path.Combine(App.Services.Paths.Root,"estudio","midias");Directory.CreateDirectory(folder);
        // O nome do arquivo vira o título da camada: o do sticker, com um sufixo curto para não colidir.
        var name=new string(item.Name.Where(c=>!Path.GetInvalidFileNameChars().Contains(c)).ToArray()).Trim();
        if(name.Length>40) name=name[..40].Trim();
        if(name.Length==0) name="Sticker";
        var path=Path.Combine(folder,$"{name} {Guid.NewGuid().ToString("N")[..6]}"+(animated?".gif":".png"));
        try {
            if(animated) await File.WriteAllBytesAsync(path,bytes,token);
            else await Task.Run(()=>{using var image=new MagickImage(bytes,new MagickReadSettings {FrameIndex=0,FrameCount=1});image.Write(path,MagickFormat.Png32);},token);
            token.ThrowIfCancellationRequested(); return path;
        } catch {File.Delete(path);throw;}
    }
}
