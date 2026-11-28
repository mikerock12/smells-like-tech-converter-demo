using ImageMagick;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media.Imaging;
using SmellsLikeTech.Converter.Composition;
using SmellsLikeTech.Converter.Desktop.Services;
using SmellsLikeTech.Converter.Engine.Image;
using Microsoft.Windows.Storage.Pickers;

namespace SmellsLikeTech.Converter.Desktop.Pages;

public sealed partial class ImagePage : Page
{
    private ImageProject? project;
    private readonly List<ImageDocument> undo = [];
    private readonly List<ImageDocument> redo = [];
    private bool busy;
    public ImagePage() => InitializeComponent();

    private async Task Work(Func<Task> action)
    {
        if (busy) return;
        busy = true;
        Toolbar.IsEnabled = Inspector.IsEnabled = false;
        Status.Text = "Processando…";
        try { await action(); if (Status.Text == "Processando…") Status.Text = ""; }
        catch (Exception e) { Status.Text = e.Message; }
        finally
        {
            busy = false;
            Toolbar.IsEnabled = true;
            SaveButton.IsEnabled = ExportButton.IsEnabled = Inspector.IsEnabled = project != null;
            UndoButton.IsEnabled = undo.Count > 0;
            RedoButton.IsEnabled = redo.Count > 0;
        }
    }
    private async Task Refresh(int selected)
    {
        var p = project!;
        Layers.ItemsSource = p.Document.Layers.Select(c => c.Nome).ToArray();
        Layers.SelectedIndex = Math.Clamp(selected, -1, p.Document.Layers.Length - 1);
        var data = await Task.Run(() => p.Render(MagickFormat.Png));
        using var stream = new MemoryStream(data).AsRandomAccessStream();
        var bmp = new BitmapImage();
        await bmp.SetSourceAsync(stream);
        Preview.Source = bmp;
        EmptyText.Visibility = Visibility.Collapsed;
    }
    private static void Remember(List<ImageDocument> stack, ImageDocument doc)
    {
        if (stack.Count >= 50) stack.RemoveAt(0);
        stack.Add(doc);
    }
    private async void OpenClick(object sender, RoutedEventArgs e) => await Work(async () =>
    {
        var picker = new FileOpenPicker(App.MainWindow!.AppWindow.Id)
        {
            FileTypeFilter = { ".png", ".jpg", ".jpeg", ".webp", ".jxl", ".sltimage" }
        };
        var path = (await picker.PickSingleFileAsync())?.Path;
        if (path is null) return;
        if (Path.GetExtension(path).Equals(".sltimage", StringComparison.OrdinalIgnoreCase))
        {
            if (project != null && await new ContentDialog { XamlRoot = XamlRoot, Title = "Abrir outro projeto?",
                Content = "Salve o projeto atual para guardar suas alterações.", PrimaryButtonText = "Abrir outro", CloseButtonText = "Voltar" }.ShowAsync() != ContentDialogResult.Primary) return;
            project = await Task.Run(() => { using var f = File.OpenRead(path); return ImageProject.Open(f); });
            undo.Clear();
            redo.Clear();
        }
        else
        {
            if (new FileInfo(path).Length > 64L * 1024 * 1024) throw new IOException("O limite por foto é de 64 MiB.");
            var bytes = await File.ReadAllBytesAsync(path);
            var name = Path.GetFileName(path);
            if (project == null) project = await Task.Run(() => ImageProject.FromPhoto(name, bytes));
            else
            {
                var old = project.Document;
                await Task.Run(() => project.AddPhoto(name, bytes));
                Remember(undo, old);
                redo.Clear();
            }
        }
        await Refresh(project.Document.Layers.Length - 1);
    });
    private void LayerChanged(object sender, SelectionChangedEventArgs e)
    {
        if (project == null || Layers.SelectedIndex is not (>= 0) || Layers.SelectedIndex >= project.Document.Layers.Length) return;
        var c = project.Document.Layers[Layers.SelectedIndex];
        Words.Text = c.Texto ?? "";
        VisibleLayer.IsChecked = c.Visivel;
        PositionX.Value = c.X; PositionY.Value = c.Y; Angle.Value = c.Angulo; LayerScale.Value = c.Escala;
        Exposure.Value = c.Exposicao; Contrast.Value = c.Contraste; Saturation.Value = c.Saturacao; OpacityBox.Value = c.Opacidade;
    }
    private async void CropClick(object sender, RoutedEventArgs e) => await Work(async () =>
    {
        var doc = project!.Document;
        var panel = new StackPanel { Spacing = 4 };
        panel.Children.Add(new TextBlock { Text = $"{doc.Canvas.Width} × {doc.Canvas.Height} px" });
        NumberBox MarginField(string name)
        {
            var box = new NumberBox { Header = name + " (px)", Minimum = 0, Maximum = 16384, Value = 0 };
            panel.Children.Add(box);
            return box;
        }
        var left = MarginField("Esquerda");
        var top = MarginField("Topo");
        var right = MarginField("Direita");
        var bottom = MarginField("Base");
        var dialog = new ContentDialog { XamlRoot = XamlRoot, Title = "Recortar composição", Content = panel,
            PrimaryButtonText = "Aplicar recorte", CloseButtonText = "Cancelar" };
        if (await dialog.ShowAsync() != ContentDialogResult.Primary) return;
        static int Pixels(NumberBox box) => double.IsFinite(box.Value) && box.Value == Math.Truncate(box.Value)
            ? checked((int)box.Value) : throw new InvalidDataException("Digite a margem em pixels inteiros.");
        project.Crop(Pixels(left), Pixels(top), Pixels(right), Pixels(bottom));
        Remember(undo, doc);
        redo.Clear();
        await Refresh(Layers.SelectedIndex);
    });
    private async Task Change(ImageLayer[] layers, int index)
    {
        var old = project!.Document;
        project.Apply(old with { Layers = layers });
        Remember(undo, old);
        redo.Clear();
        await Refresh(index);
    }
    private async void AddText(object sender, RoutedEventArgs e) => await Work(async () =>
    {
        if (string.IsNullOrWhiteSpace(Words.Text)) { Status.Text = "Digite o texto da camada."; return; }
        var layers = project!.Document.Layers;
        await Change([..layers, new ImageLayer("texto", "Texto") { Texto = Words.Text }], layers.Length);
    });
    private async void ApplyLayer(object sender, RoutedEventArgs e) => await Work(async () =>
    {
        var i = Layers.SelectedIndex;
        if (i < 0) return;
        var layers = project!.Document.Layers.ToArray();
        var c = layers[i];
        layers[i] = c with { Texto = c.Tipo == "texto" ? Words.Text : null, Visivel = VisibleLayer.IsChecked == true,
            X = (float)PositionX.Value, Y = (float)PositionY.Value, Angulo = (float)Angle.Value, Escala = (float)LayerScale.Value,
            Opacidade = (float)OpacityBox.Value, Exposicao = (float)Exposure.Value, Contraste = (float)Contrast.Value,
            Saturacao = (float)Saturation.Value };
        await Change(layers, i);
    });
    private Task Move(int step) => Work(async () =>
    {
        var i = Layers.SelectedIndex;
        var layers = project!.Document.Layers.ToArray();
        int next = i + step;
        if (i < 0 || next < 0 || next >= layers.Length) return;
        (layers[i], layers[next]) = (layers[next], layers[i]);
        await Change(layers, next);
    });
    private async void MoveUp(object sender, RoutedEventArgs e) => await Move(1);
    private async void MoveDown(object sender, RoutedEventArgs e) => await Move(-1);
    private Task History(List<ImageDocument> from, List<ImageDocument> to) => Work(async () =>
    {
        if (from.Count == 0) return;
        Remember(to, project!.Document);
        project.Apply(from[^1]);
        from.RemoveAt(from.Count - 1);
        await Refresh(Math.Min(Layers.SelectedIndex, project.Document.Layers.Length - 1));
    });
    private async void Undo(object sender, RoutedEventArgs e) => await History(undo, redo);
    private async void Redo(object sender, RoutedEventArgs e) => await History(redo, undo);
    private async void SaveClick(object sender, RoutedEventArgs e) => await Write(true);
    private async void ExportClick(object sender, RoutedEventArgs e) => await Write(false);
    private Task Write(bool editable) => Work(async () =>
    {
        if (!editable && !LicenseGate.CanConvert(out var message)) { Status.Text = message; return; }
        var format = (string)Format.SelectedItem;
        var ext = editable ? "sltimage" : format.ToLowerInvariant();
        var enlarge = !editable && EnlargeExport.IsChecked == true;
        var upscaler = enlarge ? await SltiModelInstall.LoadAsync() : null;
        var path = await NativeSaveDialog.PickAsync(WinRT.Interop.WindowNative.GetWindowHandle(App.MainWindow),
            editable ? "Salvar projeto" : "Exportar imagem", Path.GetFileNameWithoutExtension(project!.Document.Title) + (enlarge ? "-2x" : ""), ext);
        if (path == null) return;
        var temp = Path.Combine(Path.GetDirectoryName(path)!, Guid.NewGuid().ToString("N") + ".tmp");
        try
        {
            await Task.Run(() =>
            {
                using var dest = new FileStream(temp, FileMode.CreateNew, FileAccess.Write);
                if (editable) project.Save(dest);
                else dest.Write(project.Render(format switch { "PNG" => MagickFormat.Png, "JPEG" => MagickFormat.Jpeg,
                    "WebP" => MagickFormat.WebP, _ => MagickFormat.Jxl }, upscaler));
            });
            File.Move(temp, path, true);
            Status.Text = "Salvo em " + path;
        }
        finally { if (File.Exists(temp)) File.Delete(temp); }
    });
}
