using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using SmellsLikeTech.Converter.Core.Studio;

namespace SmellsLikeTech.Converter.Desktop.Pages;

public sealed partial class StudioPage
{
    private static FrameworkElement AnimationControls(Func<StudioTransform?> read,Action<StudioTransform> change,double duration)
    {
        var body=new StackPanel {Spacing=8};
        var rows=new StackPanel {Spacing=8};
        StudioTransform Current()=>read()??new();
        void Draw() {
            rows.Children.Clear();
            var t=Current();
            for(var i=0;i<t.Keyframes.Count;i++) {
                var index=i;var q=t.Keyframes[i];
                var row=new StackPanel {Spacing=4};
                row.Children.Add(Muted($"Quadro em {q.Time.ToString("0.##",PtBr)} s"));
                void Update(Func<StudioKeyframe,StudioKeyframe> update) {
                    var current=Current();change(current with {Keyframes=current.Keyframes.Select((a,j)=>j==index?update(a):a).ToList()});
                }
                foreach(var field in new[]{"Horizontal","Vertical","Zoom"}) {
                    var input=new NumberBox {Header=field,Minimum=field=="Zoom"?.05:-2,Maximum=field=="Zoom"?6:3,SmallChange=.01,
                        Value=field=="Horizontal"?q.X:field=="Vertical"?q.Y:q.Scale};
                    input.ValueChanged+=(_,e)=>{if(double.IsFinite(e.NewValue)) Update(a=>field=="Horizontal"?a with {X=e.NewValue}:field=="Vertical"?a with {Y=e.NewValue}:a with {Scale=e.NewValue});};
                    row.Children.Add(input);
                }
                row.Children.Add(Choice("Interpolação",[("linear","Linear"),("suave","Suave")],q.Curve,id=>Update(a=>a with {Curve=id})));
                var remove=Action("Remover quadro");remove.Click+=(_,_)=>{change(Current() with {Keyframes=Current().Keyframes.Where((_,j)=>j!=index).ToList()});Draw();};row.Children.Add(remove);
                rows.Children.Add(row);
            }
        }
        body.Children.Add(Choice("Máscara",[("nenhuma","Sem máscara"),("circulo","Círculo"),("oval","Oval"),("arredondada","Cantos arredondados")],Current().Mask,id=>change(Current() with {Mask=id})));
        var toward=Action("Ken Burns: aproximar");toward.Click+=(_,_)=>{change(StudioAnimation.KenBurns(Current(),duration));Draw();};body.Children.Add(toward);
        var away=Action("Ken Burns: afastar");away.Click+=(_,_)=>{change(StudioAnimation.KenBurns(Current(),duration,true));Draw();};body.Children.Add(away);
        var time=new NumberBox {Header="Tempo local do novo quadro (s)",Minimum=0,Maximum=Math.Max(.1,duration),Value=0,SmallChange=.1};body.Children.Add(time);
        var add=Action("Gravar posição e tamanho neste tempo");add.Click+=(_,_)=>{
            var t=Current();if(t.Keyframes.Count>=100||!double.IsFinite(time.Value)) return;
            change(t with {Keyframes=t.Keyframes.Where(q=>q.Time!=time.Value).Append(new StudioKeyframe(time.Value,t.X,t.Y,t.Scale)).OrderBy(q=>q.Time).ToList()});Draw();
        };body.Children.Add(add);
        body.Children.Add(Muted("Tempos desde o início desta mídia na composição. Os quadros interpolam posição e zoom após a perspectiva."));
        body.Children.Add(rows);Draw();
        var clear=Action("Remover animação");clear.Click+=(_,_)=>{change(Current() with {Keyframes=[]});Draw();};body.Children.Add(clear);
        return new Expander {Header="Recorte e animação",Content=body,HorizontalAlignment=HorizontalAlignment.Stretch,HorizontalContentAlignment=HorizontalAlignment.Stretch};
    }
}
