using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using SmellsLikeTech.Converter.Core;
using SmellsLikeTech.Converter.Core.Jobs;
using SmellsLikeTech.Converter.Core.Studio;
using SmellsLikeTech.Converter.Desktop.Services;

namespace SmellsLikeTech.Converter.Desktop.Pages;

public sealed partial class StudioPage
{
    private void FinishingControls(StackPanel panel, StudioClip clip, int index)
    {
        if(!clip.IsImage) panel.Children.Add(Slider("Velocidade: {0}×",clip.Speed,.25,4,.05,v=>v.ToString("0.##",PtBr),
            v=>Change(p=>WithClip(p,clip.Id,c=>c with {Speed=v})),()=>{}));
        if(index>=project!.Clips.Count-1) return;
        var choices=new[]{("corte","Corte seco"),("fade","Fade pelo preto"),("dissolver","Dissolver"),("deslizar","Deslizar"),("zoom","Zoom")};
        var combo=new ComboBox {Header="Transição para o próximo clipe",HorizontalAlignment=HorizontalAlignment.Stretch};
        foreach(var (id,title) in choices) combo.Items.Add(new ComboBoxItem {Content=title,Tag=id});
        combo.SelectedIndex=Math.Max(0,Array.FindIndex(choices,c=>c.Item1==(clip.Transition?.Kind??"corte")));
        combo.SelectionChanged+=(_,_)=>Change(p=>WithClip(p,clip.Id,c=>c with {Transition=new((combo.SelectedItem as ComboBoxItem)?.Tag as string??"corte",c.Transition?.Duration??.5)}));
        panel.Children.Add(combo);
        panel.Children.Add(Slider("Duração da transição: {0} s",clip.Transition?.Duration??.5,.1,5,.1,v=>v.ToString("0.#",PtBr),
            v=>Change(p=>WithClip(p,clip.Id,c=>c with {Transition=new(c.Transition?.Kind??"corte",v)})),()=>{}));
        panel.Children.Add(Muted("A duração é limitada à metade de cada clipe vizinho. O áudio acompanha a transição."));
    }

    private StackPanel CaptionControls()
    {
        var panel=new StackPanel {Spacing=8};
        var installed=App.Services.Models.ListModels().Where(m=>m.IsInstalled).ToList();
        panel.Children.Add(Muted("Legendas automáticas do áudio final. Depois de alterar cortes, velocidade ou transições, gere novamente. Revise os textos e o estilo antes de exportar."));
        var models=new ComboBox {Header="Modelo para legendar",HorizontalAlignment=HorizontalAlignment.Stretch};
        foreach(var m in installed) models.Items.Add(new ComboBoxItem {Content=m.DisplayName,Tag=m.Id});
        if(installed.Count>0) models.SelectedIndex=Math.Max(0,installed.FindIndex(m=>m.Id=="small"));
        panel.Children.Add(models);
        var button=Action("Gerar legendas automáticas");button.IsEnabled=project!.Clips.Count>0&&installed.Count>0;
        button.Click+=async (_,_)=>{
            if(!LicenseGate.CanConvert(out var message)) {ShowStatus(message,InfoBarSeverity.Warning);return;}
            var original=project; if(original is null) return;
            using var cancellation=new CancellationTokenSource();
            var status=new TextBlock {Text="Montando o áudio e transcrevendo…",TextWrapping=TextWrapping.Wrap};
            var dialog=new ContentDialog {Title="Legendas automáticas",Content=status,CloseButtonText="Cancelar",XamlRoot=XamlRoot};
            dialog.CloseButtonClick+=(_,_)=>cancellation.Cancel();
            _=dialog.ShowAsync();button.IsEnabled=false;
            try {
                var progress=new Progress<JobProgress>(p=>status.Text=$"Gerando legendas… {Math.Round((p.Percent??0)*100)}%");
                var captions=await App.Services.Studio.GenerateCaptionsAsync(original,Inputs(),Path.Combine(App.Services.Paths.Temp,"estudio-legendas"),
                    (models.SelectedItem as ComboBoxItem)?.Tag as string??"base",progress,cancellation.Token);
                if(!ReferenceEquals(project,original)) throw new ConversionException("project_changed","O projeto mudou. Gere as legendas novamente.");
                ChangeAndRefresh(p=>p with {Texts=[..p.Texts.Where(t=>!t.Id.StartsWith("legenda-")),..captions]});
                ShowStatus($"{captions.Count} trechos adicionados. Revise os textos antes de exportar.",InfoBarSeverity.Success);
            } catch(OperationCanceledException) {} catch(Exception e) {ShowStatus(e.Message,InfoBarSeverity.Error);}
            finally {dialog.Hide();button.IsEnabled=true;}
        };
        panel.Children.Add(button);
        if(installed.Count==0) panel.Children.Add(Warning("Baixe um modelo do Whisper em Configurações → Modelos."));
        var style=new ComboBox {Header="Estilo de todas as legendas",HorizontalAlignment=HorizontalAlignment.Stretch};
        foreach(var s in new[]{"Branca com contorno","Amarela com contorno","Branca com caixa"}) style.Items.Add(s);
        style.SelectionChanged+=(_,_)=>ChangeAndRefresh(p=>p with {Texts=p.Texts.Select(t=>t.Id.StartsWith("legenda-")?t with {Color=style.SelectedIndex==1?"#FFD54A":"#FFFFFF",Outline="#000000",Box=style.SelectedIndex==2?"#000000B3":null}:t).ToList()});
        panel.Children.Add(style);
        return panel;
    }
}
