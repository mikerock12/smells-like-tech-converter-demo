using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using SmellsLikeTech.Converter.Core;
using SmellsLikeTech.Converter.Core.Media;
using SmellsLikeTech.Converter.Core.Studio;
using SmellsLikeTech.Converter.Desktop.Services;

namespace SmellsLikeTech.Converter.Desktop.Pages;

public sealed partial class StudioPage
{
    private static FrameworkElement TransformControls(Func<StudioTransform?> read, Action<StudioTransform> change, bool allowRemoval=true,double duration=3)
    {
        var body=new StackPanel {Spacing=8};
        var t=read()??new StudioTransform();
        void Control(string title,double value,double min,double max,double step,Func<StudioTransform,double,StudioTransform> update) =>
            body.Children.Add(Slider(title,value,min,max,step,v=>v.ToString("0.#",PtBr),v=>{var before=read()??new StudioTransform();change(StudioAnimation.ChangeBase(before,update(before,v)));},null));
        Control("Giro no plano (Z): {0}°",t.Rotation,-360,360,.1,(v,n)=>v with {Rotation=n});
        Control("Inclinar para frente/trás (X): {0}°",t.RotationX,-180,180,.1,(v,n)=>v with {RotationX=n});
        Control("Inclinar para esquerda/direita (Y): {0}°",t.RotationY,-180,180,.1,(v,n)=>v with {RotationY=n});
        Control("Posição horizontal: {0}%",t.X*100,-200,300,.1,(v,n)=>v with {X=n/100});
        Control("Posição vertical: {0}%",t.Y*100,-200,300,.1,(v,n)=>v with {Y=n/100});
        Control("Tamanho: {0}%",t.Scale*100,5,600,.1,(v,n)=>v with {Scale=n/100});
        Control("Opacidade: {0}%",t.Opacity*100,0,100,.1,(v,n)=>v with {Opacity=n/100});
        var mirrorX=new ToggleSwitch {Header="Espelhar horizontal",IsOn=t.MirrorX};
        mirrorX.Toggled+=(_,_)=>change((read()??new StudioTransform()) with {MirrorX=mirrorX.IsOn}); body.Children.Add(mirrorX);
        var mirrorY=new ToggleSwitch {Header="Espelhar vertical",IsOn=t.MirrorY};
        mirrorY.Toggled+=(_,_)=>change((read()??new StudioTransform()) with {MirrorY=mirrorY.IsOn}); body.Children.Add(mirrorY);
        body.Children.Add(Choice("Mesclagem",[("normal","Normal"),("multiplicar","Multiplicar"),("clarear","Clarear"),("sobrepor","Sobrepor")],t.Blend,
            id=>change((read()??new StudioTransform()) with {Blend=id})));
        body.Children.Add(Choice("Filtro desta camada",[("original","Original"),("vivido","Vívido"),("pb","Preto e branco"),("sepia","Sépia"),("vintage","Retrô"),("frio","Frio"),("quente","Quente"),("negativo","Negativo")],t.Filter,
            id=>change((read()??new StudioTransform()) with {Filter=id})));
        Control("Brilho: {0}%",t.Brightness*100,-100,100,.1,(v,n)=>v with {Brightness=n/100});
        Control("Contraste: {0}%",t.Contrast*100,-80,100,.1,(v,n)=>v with {Contrast=n/100});
        Control("Saturação: {0}%",t.Saturation*100,-100,100,.1,(v,n)=>v with {Saturation=n/100});
        if(allowRemoval) {
            var remove=new ToggleSwitch {Header="Remover o fundo desta mídia",IsOn=t.RemoveBackground};
            remove.Toggled+=(_,_)=>change((read()??new StudioTransform()) with {RemoveBackground=remove.IsOn});body.Children.Add(remove);
            body.Children.Add(Muted("Processado no dispositivo. Vídeos são recortados quadro a quadro e podem demorar."));
        }
        body.Children.Add(AnimationControls(read,change,duration));
        return new Expander {Header="Transformar e filtrar",Content=body,HorizontalAlignment=HorizontalAlignment.Stretch,HorizontalContentAlignment=HorizontalAlignment.Stretch};
    }

    private StudioLayer FindLayer(string id)=>project!.Layers.First(c=>c.Id==id);
    private static StudioProject WithLayer(StudioProject p,string id,Func<StudioLayer,StudioLayer> change)=>p with {Version=StudioRules.ProjectVersion,Layers=p.Layers.Select(c=>c.Id==id?change(c):c).ToList()};

    private void BuildLayers()
    {
        LayersPanel.Children.Clear();
        LayersPanel.Children.Add(TimelineControls());
        var add=Action("Adicionar mídia por cima"); add.IsEnabled=project!.Clips.Count>0&&project.Layers.Count<12;
        add.Click+=async(_,_)=>{ foreach(var path in FileDialogs.PickStudioMedia(App.MainWindow)) await AddLayerAsync(path); };
        LayersPanel.Children.Add(add);
        var catalog=Action("Stickers e GIF stickers"); catalog.IsEnabled=add.IsEnabled;
        catalog.Click+=async(_,_)=>await ShowStickerCatalogAsync(); LayersPanel.Children.Add(catalog);
        LayersPanel.Children.Add(Choice("Fundo",[("#00000000","Transparente"),("#000000","Preto"),("#FFFFFF","Branco"),("#FF7A18","Laranja"),("#7536E8","Roxo"),("gradiente","Gradiente")],project.Background?.EndColor is null?project.Background?.Color:"gradiente",
            id=>Change(p=>p with {Background=id=="gradiente"?new StudioBackground {Color="#7536E8",EndColor="#FF7A18"}:new StudioBackground {Color=id}})));
        foreach(var layer in project.Layers.OrderBy(c=>c.Order)) LayersPanel.Children.Add(LayerCard(layer));
    }

    private Border LayerCard(StudioLayer layer)
    {
        var body=new StackPanel {Spacing=8};
        body.Children.Add(Header(Path.GetFileName(layer.File),Muted($"{Clock(layer.Start)} → {Clock(layer.End)}"),()=>Toggle(layer.Id),
            ("↓",true,()=>MoveLayer(layer.Id,-1)),("↑",true,()=>MoveLayer(layer.Id,1)),
            ("✕",true,()=>ChangeAndRefresh(p=>p with {Layers=p.Layers.Where(c=>c.Id!=layer.Id).ToList()}))));
        var visible=new ToggleSwitch {Header="Visível",IsOn=layer.Visible};
        visible.Toggled+=(_,_)=>Change(p=>WithLayer(p,layer.Id,c=>c with {Visible=visible.IsOn})); body.Children.Add(visible);
        if(openId!=layer.Id) return Panel(body);
        var back=Action(layer.Order<0?"Trazer para frente do clipe":"Usar como fundo");
        back.Click+=(_,_)=>ChangeAndRefresh(p=>WithLayer(p,layer.Id,c=>c with {Order=c.Order<0?1:-1})); body.Children.Add(back);
        var from=new NumberBox {Header="Entrada livre (s)",Minimum=0,Maximum=86400,SmallChange=.1,Value=layer.Start};
        var to=new NumberBox {Header="Saída livre (s)",Minimum=.1,Maximum=86400,SmallChange=.1,Value=layer.End};
        body.Children.Add(from);body.Children.Add(to);
        var apply=Action("Aplicar intervalo livre");apply.Click+=(_,_)=>{
            if(!double.IsFinite(from.Value)||!double.IsFinite(to.Value)||to.Value<=from.Value) {ShowStatus("Use entrada e saída válidas.",InfoBarSeverity.Warning);return;}
            ChangeAndRefresh(p=>WithLayer(p,layer.Id,c=>c with {Start=from.Value,End=to.Value}));
        };body.Children.Add(apply);
        var split=Action("Dividir faixa no cursor");split.Click+=(_,_)=>{try {var updated=StudioCreation.SplitLayer(project!,layer.Id,TimeSlider.Value,NewId("l"));ChangeAndRefresh(_=>updated);}catch(Exception e){ShowStatus(e.Message,InfoBarSeverity.Warning);}};body.Children.Add(split);
        body.Children.Add(Muted("Faixas podem prolongar a composição. O último quadro principal permanece."));
        body.Children.Add(Slider("Aparece em {0}",layer.Start,0,Math.Max(0,Total-.1),.1,Clock,
            n=>Change(p=>WithLayer(p,layer.Id,c=>c with {Start=n,End=Math.Max(c.End,n+.1)})),null));
        body.Children.Add(Slider("Termina em {0}",layer.End,.1,Total,.1,Clock,
            n=>Change(p=>WithLayer(p,layer.Id,c=>c with {End=Math.Max(n,c.Start+.1)})),null));
        if(layer.Kind!="imagem") {
            var repeat=new ToggleSwitch {Header="Repetir animação",IsOn=layer.Repeat};
            repeat.Toggled+=(_,_)=>Change(p=>WithLayer(p,layer.Id,c=>c with {Repeat=repeat.IsOn})); body.Children.Add(repeat);
            var duration=infos.GetValueOrDefault(layer.File)?.Duration?.TotalSeconds;
            if(duration>.1) body.Children.Add(Slider("Início no arquivo: {0}",layer.Offset,0,duration.Value-.1,.1,Clock,
                n=>Change(p=>WithLayer(p,layer.Id,c=>c with {Offset=n})),null));
        }
        body.Children.Add(TransformControls(()=>FindLayer(layer.Id).Transform,t=>Change(p=>WithLayer(p,layer.Id,c=>c with {Transform=t})),duration:layer.End-layer.Start));
        return Panel(body);
    }

    private void MoveLayer(string id,int step)=>ChangeAndRefresh(p=> {
        var selected=p.Layers.FirstOrDefault(c=>c.Id==id); if(selected is null) return p;
        var background=selected.Order<0;
        var layers=p.Layers.Where(c=>(c.Order<0)==background).OrderBy(c=>c.Order).ToList(); var from=layers.FindIndex(c=>c.Id==id);
        if(from<0) return p;
        var to=Math.Clamp(from+step,0,layers.Count-1); var layer=layers[from]; layers.RemoveAt(from); layers.Insert(to,layer);
        var reordered=layers.Select((c,i)=>c with {Order=background?i-layers.Count:i+1}).ToDictionary(c=>c.Id);
        return p with {Layers=p.Layers.Select(c=>reordered.GetValueOrDefault(c.Id,c)).ToList()};
    });

    private async Task AddLayerAsync(string path)
    {
        if(project is null) return;
        if(project.Layers.Count>=12) {ShowStatus("Use até 12 camadas adicionais.",InfoBarSeverity.Warning);return;}
        try {
            var info=await App.Services.Inspector.InspectAsync(path,CancellationToken.None);
            var kind=Path.GetExtension(path).Equals(".gif",StringComparison.OrdinalIgnoreCase)?"gif":info.Kind==MediaKind.Image?"imagem":"video";
            if(kind=="video"&&!info.HasVideo) throw new ConversionException("invalid_layer","Escolha uma imagem, um GIF ou um vídeo para a camada.");
            infos[path]=info;
            var id=NewId("l"); var start=Math.Min(TimeSlider.Value,Math.Max(0,Total-.1));
            openId=id;
            ChangeAndRefresh(p=>p with {Version=StudioRules.ProjectVersion,Layers=[..p.Layers,new StudioLayer {Id=id,File=path,Kind=kind,Start=start,End=Math.Max(start+.1,Total),Order=p.Layers.Select(c=>c.Order).DefaultIfEmpty(0).Max()+1,Transform=new StudioTransform {Scale=.5}}]});
        } catch(ConversionException e) {ShowStatus(e.Message,InfoBarSeverity.Error);}
    }
}
