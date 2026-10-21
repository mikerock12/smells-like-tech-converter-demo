using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Imaging;
using SmellsLikeTech.Converter.Desktop.Services;

namespace SmellsLikeTech.Converter.Desktop.Pages;

public sealed partial class StudioPage
{
    private async Task ShowStickerCatalogAsync()
    {
        using var lifetime=new CancellationTokenSource();
        CancellationTokenSource? search=null;
        var query=new TextBox {PlaceholderText="Buscar stickers",Header="Stickers e GIF stickers",MaxLength=100};
        var animated=new ToggleSwitch {Header="Importar como GIF animado",IsOn=true};
        var provider=Muted("Abrindo catálogo…"); var notice=Muted("");
        var grid=new GridView {SelectionMode=ListViewSelectionMode.None,IsItemClickEnabled=false,MaxHeight=400};
        var pages=new StackPanel {Orientation=Orientation.Horizontal,Spacing=10};
        var previous=Action("Anterior");var next=Action("Próxima");pages.Children.Add(previous);pages.Children.Add(next);
        previous.IsEnabled=false;next.IsEnabled=false;
        var content=new StackPanel {Spacing=10,Width=480};
        foreach(var element in new UIElement[]{query,animated,provider,notice,grid,pages}) content.Children.Add(element);
        var dialog=new ContentDialog {XamlRoot=XamlRoot,Title="Adicionar sticker",Content=new ScrollViewer {Content=content,VerticalScrollBarVisibility=ScrollBarVisibility.Auto},CloseButtonText="Fechar"};
        var page=0; var importing=false; var revision=0;
        async Task RefreshAsync()
        {
            search?.Cancel();search?.Dispose();search=CancellationTokenSource.CreateLinkedTokenSource(lifetime.Token);
            var token=search.Token; var current=++revision;
            try {
                grid.Items.Clear();provider.Text="Buscando…";next.IsEnabled=false;
                await Task.Delay(550,token);
                do {
                    var result=await StickerCatalog.Instance.SearchAsync(query.Text,page,token);
                    token.ThrowIfCancellationRequested();
                    if(current!=revision) return;
                    provider.Text=result.Provider=="giphy"?"Powered by GIPHY":"Google Noto · CC BY 4.0";
                    notice.Text=result.Notice??"";grid.Items.Clear();
                    previous.IsEnabled=page>0;next.IsEnabled=result.NextPage is not null;
                    foreach(var item in result.Items) {
                        var picture=new Image {Width=112,Height=100,Stretch=Stretch.Uniform,Source=new BitmapImage(new Uri(item.Preview)) {CreateOptions=BitmapCreateOptions.IgnoreImageCache}};
                        var tile=new StackPanel();tile.Children.Add(picture);tile.Children.Add(new TextBlock {Text=item.Name,MaxWidth=112,MaxLines=2,TextWrapping=TextWrapping.Wrap});
                        var button=new Button {Content=tile,Margin=new Thickness(3),IsEnabled=!importing};
                        button.Click+=async(_,_)=> {
                            if(importing) return;
                            importing=true;grid.IsHitTestVisible=false;query.IsEnabled=false;previous.IsEnabled=false;next.IsEnabled=false;
                            notice.Text="Adicionando sticker…";
                            try {
                                var path=await StickerCatalog.ImportAsync(item,animated.IsOn,lifetime.Token);
                                await AddLayerAsync(path);dialog.Hide();
                            } catch(OperationCanceledException) when(lifetime.IsCancellationRequested) {}
                            catch(Exception) {notice.Text="Não foi possível adicionar o sticker. Tente novamente.";}
                            finally {importing=false;grid.IsHitTestVisible=true;query.IsEnabled=true;}
                        };
                        grid.Items.Add(button);
                    }
                    if(result.Items.Count==0) notice.Text="Nenhum sticker encontrado. Tente outro termo.";
                    if(result.RetryAt<=0) break;
                    await Task.Delay(TimeSpan.FromMilliseconds(Math.Max(1000,result.RetryAt-DateTimeOffset.UtcNow.ToUnixTimeMilliseconds())),token);
                } while(!token.IsCancellationRequested);
            } catch(OperationCanceledException) when(token.IsCancellationRequested) {}
            catch(Exception) {if(current==revision) notice.Text="Não foi possível abrir o catálogo. Tente novamente.";}
        }
        query.TextChanged+=async(_,_)=>{page=0;await RefreshAsync();};
        previous.Click+=async(_,_)=>{page=Math.Max(0,page-1);await RefreshAsync();};
        next.Click+=async(_,_)=>{page++;await RefreshAsync();};
        dialog.Opened+=async(_,_)=>await RefreshAsync();
        dialog.Closed+=(_,_)=>{lifetime.Cancel();search?.Cancel();};
        try {await dialog.ShowAsync();} finally {lifetime.Cancel();search?.Cancel();search?.Dispose();}
    }
}
