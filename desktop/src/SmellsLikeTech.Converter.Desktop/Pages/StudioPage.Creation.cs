using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using SmellsLikeTech.Converter.Composition;
using SmellsLikeTech.Converter.Core.Studio;
using SmellsLikeTech.Converter.Desktop.Services;

namespace SmellsLikeTech.Converter.Desktop.Pages;

public sealed partial class StudioPage
{
    private async void OnOpenProject(object sender,RoutedEventArgs e)
    {
        var path=NativeFileDialog.PickFile(WinRT.Interop.WindowNative.GetWindowHandle(App.MainWindow),"Abrir projeto salvo",[new("Projeto do estúdio",["sltstudio","zip"])]);
        if(path is null) return;
        OpenProjectButton.IsEnabled=false;
        try {
            var directory=Path.Combine(App.Services.Paths.Root,"estudio","importados",Guid.NewGuid().ToString("N"));
            var loaded=await Task.Run(()=>{using var stream=File.OpenRead(path);return StudioArchive.Open(stream,directory);});
            var newInfos=new Dictionary<string,SmellsLikeTech.Converter.Core.Media.MediaInfo>(StringComparer.OrdinalIgnoreCase);
            foreach(var file in StudioCreation.Files(loaded)) newInfos[file]=await App.Services.Inspector.InspectAsync(file,CancellationToken.None);
            ReleasePlayer();project=loaded;ForgetHistory();infos.Clear();foreach(var pair in newInfos) infos[pair.Key]=pair.Value;
            TimeSlider.Value=0;openId=null;choosingOutput=false;WizardPanel.Tag=null;Save();ShowCurrentStep();
            ShowStatus("Projeto aberto com as mídias incluídas.",InfoBarSeverity.Success);
        } catch(Exception exception) {ShowStatus(exception.Message,InfoBarSeverity.Error);}
        finally {OpenProjectButton.IsEnabled=true;}
    }

    private FrameworkElement TimelineControls()
    {
        var panel=new StackPanel {Spacing=5};panel.Children.Add(Muted("Faixas da composição · toque para selecionar e posicionar o cursor naquele ponto"));
        var items=StudioRules.Segments(project!).Select(s=>(s.Id,Name:$"{s.Index+1}. {Path.GetFileName(project!.Clips[s.Index].File)}",s.Start,s.End,Kind:"clipes"))
            .Concat(project!.Layers.OrderBy(c=>c.Order).Select(c=>(c.Id,Name:$"Camada · {Path.GetFileName(c.File)}",c.Start,c.End,Kind:"camadas")))
            .Concat(project.Audios.Select(a=>(a.Id,Name:$"Áudio · {Path.GetFileName(a.File)}",Start:a.At??0,End:(a.At??0)+a.End-a.Start,Kind:"audio")));
        foreach(var item in items) {
            var row=new StackPanel {Spacing=2};row.Children.Add(Muted($"{item.Name} · {Clock(item.Start)}–{Clock(item.End)}"));
            var grid=new Grid {Height=25,Background=new Microsoft.UI.Xaml.Media.SolidColorBrush(Windows.UI.Color.FromArgb(255,38,38,38))};
            grid.ColumnDefinitions.Add(new ColumnDefinition {Width=new GridLength(Math.Max(.0001,item.Start),GridUnitType.Star)});
            grid.ColumnDefinitions.Add(new ColumnDefinition {Width=new GridLength(Math.Max(.0001,item.End-item.Start),GridUnitType.Star)});
            grid.ColumnDefinitions.Add(new ColumnDefinition {Width=new GridLength(Math.Max(.0001,Total-item.End),GridUnitType.Star)});
            var button=Action($"{item.Start.ToString("0.#",PtBr)} s");button.HorizontalAlignment=HorizontalAlignment.Stretch;button.Padding=new Thickness(2,0,2,0);Grid.SetColumn(button,1);
            double? clicked=null;
            button.PointerPressed+=(_,args)=>{if(grid.ActualWidth>0) clicked=args.GetCurrentPoint(grid).Position.X/grid.ActualWidth*Total;};
            button.Click+=(_,_)=>{TimeSlider.Value=Math.Clamp(clicked??item.Start,0,Total);clicked=null;openId=item.Id;EditorPivot.SelectedIndex=item.Kind=="camadas"?1:item.Kind=="audio"?3:0;RefreshPanels();};grid.Children.Add(button);row.Children.Add(grid);panel.Children.Add(row);
        }
        return new Expander {Header="Linha do tempo e faixas",Content=panel,HorizontalAlignment=HorizontalAlignment.Stretch,HorizontalContentAlignment=HorizontalAlignment.Stretch};
    }

    private FrameworkElement CreationControls()
    {
        var panel=new StackPanel {Spacing=8};
        var save=Action("Salvar projeto com mídias");save.IsEnabled=project!.Clips.Count>0;
        save.Click+=async(_,_)=>{
            var original=project;if(original is null)return;
            var folder=FileDialogs.PickOutputFolder(App.MainWindow);if(folder is null)return;
            var file=Path.Combine(folder,$"projeto-{DateTime.Now:yyyyMMdd-HHmmss}-{Guid.NewGuid().ToString("N")[..6]}.sltstudio");
            save.IsEnabled=false;var created=false;
            try {
                await Task.Run(()=>{using var stream=new FileStream(file,FileMode.CreateNew,FileAccess.Write);created=true;StudioArchive.Save(original,stream);});
                ShowStatus($"Projeto salvo com as mídias em {file}",InfoBarSeverity.Success);
            } catch(Exception e) {if(created) File.Delete(file);ShowStatus(e.Message,InfoBarSeverity.Error);}
            finally {save.IsEnabled=true;}
        };panel.Children.Add(save);
        panel.Children.Add(Muted("O arquivo inclui as mídias e pode ser reaberto no site, Windows e Android. Salve antes de iniciar outro projeto."));
        panel.Children.Add(Muted("Fotos em movimento aplica uma aproximação suave (Ken Burns) às fotos da faixa principal; não gera vídeo por IA."));
        panel.Children.Add(Choice("Modelo editável",StudioCreation.Templates,"",id=>{
            try {ChangeAndRefresh(p=>StudioCreation.Apply(p,id));}catch(Exception e) {ShowStatus(e.Message,InfoBarSeverity.Error);}
        }));
        panel.Children.Add(Muted("Contagem regressiva adiciona números editáveis, um por segundo, a partir do cursor. Posicione o cursor antes de adicionar."));
        var count=new NumberBox {Header="Contagem regressiva (1–10 segundos)",Minimum=1,Maximum=10,SmallChange=1,Value=3};panel.Children.Add(count);
        var countdown=Action("Adicionar contagem no cursor");countdown.IsEnabled=project.Clips.Count>0;
        countdown.Click+=(_,_)=>{
            try {if(!double.IsFinite(count.Value)||count.Value!=Math.Truncate(count.Value))throw new InvalidOperationException("Escolha uma quantidade inteira de segundos.");
                var updated=StudioCreation.Countdown(project!,TimeSlider.Value,(int)count.Value);ChangeAndRefresh(_=>updated);
            }catch(Exception e){ShowStatus(e.Message,InfoBarSeverity.Warning);}
        };panel.Children.Add(countdown);
        return new Expander {Header="Projetos, modelos e contagem",Content=panel,HorizontalAlignment=HorizontalAlignment.Stretch,HorizontalContentAlignment=HorizontalAlignment.Stretch};
    }

    private FrameworkElement NarrationControls()
    {
        var panel=new StackPanel {Spacing=8};
        var text=new TextBox {Header="Texto da narração",AcceptsReturn=true,TextWrapping=TextWrapping.Wrap,MaxLength=50000,MinHeight=90};panel.Children.Add(text);
        var voice=new ComboBox {Header="Voz",HorizontalAlignment=HorizontalAlignment.Stretch};
        foreach(var (id,name) in new[]{("pf_dora","Dora"),("pm_alex","Alex"),("pm_santa","Santa")}) voice.Items.Add(new ComboBoxItem {Content=name,Tag=id});
        voice.SelectedIndex=0;panel.Children.Add(voice);
        var speed=new Slider {Header="Velocidade da fala",Minimum=-10,Maximum=10,StepFrequency=1,Value=0};panel.Children.Add(speed);
        panel.Children.Add(Muted("A narração entra no cursor e pode ser cortada, movida e mixada na aba Áudio."));
        var button=Action("Gerar e adicionar narração");button.IsEnabled=project!.Clips.Count>0;panel.Children.Add(button);
        button.Click+=async(_,_)=>{
            if(!LicenseGate.CanConvert(out var message)){ShowStatus(message,InfoBarSeverity.Warning);return;}
            var original=project;if(original is null||string.IsNullOrWhiteSpace(text.Text))return;
            var start=TimeSlider.Value;var content=text.Text;var selected=(voice.SelectedItem as ComboBoxItem)?.Tag as string??"pf_dora";var rate=(int)speed.Value;
            var folder=Path.Combine(App.Services.Paths.Root,"estudio","narracoes");Directory.CreateDirectory(folder);var file=Path.Combine(folder,$"narracao-{Guid.NewGuid():N}.wav");
            using var cancellation=new CancellationTokenSource();
            var status=new TextBlock {Text="Gerando narração Kokoro…",TextWrapping=TextWrapping.Wrap};
            var dialog=new ContentDialog {Title="Narração na linha do tempo",Content=status,CloseButtonText="Cancelar",XamlRoot=XamlRoot};
            dialog.CloseButtonClick+=(_,_)=>cancellation.Cancel();_=dialog.ShowAsync();button.IsEnabled=false;
            try {
                await App.Services.TtsEngine.SynthesizeToWaveAsync(content,selected,rate,100,file,null,cancellation.Token);
                var info=await App.Services.Inspector.InspectAsync(file,cancellation.Token);var duration=info.Duration?.TotalSeconds??throw new IOException("Não foi possível ler a narração.");
                if(!ReferenceEquals(project,original))throw new InvalidOperationException("O projeto mudou durante a narração. Gere novamente.");
                infos[file]=info;var audio=new StudioAudio {Id=NewId("n"),File=file,At=start,Start=0,End=duration,Volume=1};openId=audio.Id;
                ChangeAndRefresh(p=>p with {Version=4,Audios=[..p.Audios,audio]});
                ShowStatus("Narração adicionada à linha do tempo.",InfoBarSeverity.Success);
            }catch(OperationCanceledException){File.Delete(file);}catch(Exception e){File.Delete(file);ShowStatus(e.Message,InfoBarSeverity.Error);}
            finally {dialog.Hide();button.IsEnabled=true;}
        };
        return new Expander {Header="Narrar com Kokoro na linha do tempo",Content=panel,HorizontalAlignment=HorizontalAlignment.Stretch,HorizontalContentAlignment=HorizontalAlignment.Stretch};
    }
}
