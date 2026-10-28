using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Shapes;
using SmellsLikeTech.Converter.Core.Studio;
using Windows.Foundation;

namespace SmellsLikeTech.Converter.Desktop.Pages;

/// <summary>
/// O que veio do criador de figurinhas do Android: arrastar na prévia com guias que encaixam e
/// piscam (StudioGuides, a mesma regra do site), a roda do mouse para o tamanho, e desfazer/refazer.
/// O projeto continua salvo a cada mudança, então sair do estúdio não perde nada.
/// </summary>
public sealed partial class StudioPage
{
    private readonly List<StudioProject> undoSteps = [];
    private readonly List<StudioProject> redoSteps = [];
    private DateTime lastChangeAt;
    private Microsoft.UI.Dispatching.DispatcherQueueTimer? guidesTimer;
    private int guidesTicks;

    // ==================== desfazer e refazer ====================

    /// <summary>Guarda o projeto de antes. Mudanças a menos de 600 ms (um arraste) viram um passo só.</summary>
    private void Remember(StudioProject before)
    {
        var now = DateTime.UtcNow;
        if ((now - lastChangeAt).TotalMilliseconds > 600)
        {
            undoSteps.Add(before);
            if (undoSteps.Count > 100) undoSteps.RemoveAt(0);
        }
        lastChangeAt = now;
        redoSteps.Clear();
        UpdateUndoButtons();
    }

    private void ForgetHistory()
    {
        undoSteps.Clear();
        redoSteps.Clear();
        lastChangeAt = default;
        UpdateUndoButtons();
    }

    private void UpdateUndoButtons()
    {
        UndoButton.IsEnabled = undoSteps.Count > 0;
        RedoButton.IsEnabled = redoSteps.Count > 0;
        UndoButton.Visibility = RedoButton.Visibility = project is null ? Visibility.Collapsed : Visibility.Visible;
    }

    private void Step(bool back)
    {
        var from = back ? undoSteps : redoSteps;
        var to = back ? redoSteps : undoSteps;
        if (project is null || from.Count == 0) return;
        to.Add(project);
        project = from[^1];
        from.RemoveAt(from.Count - 1);
        lastChangeAt = default;
        Save();
        TimeSlider.Maximum = Math.Max(0.1, Total);
        TotalText.Text = Clock(Total);
        RefreshPanels();
        RequestPreview();
        UpdateUndoButtons();
    }

    private void OnUndo(object sender, RoutedEventArgs e) => Step(back: true);

    private void OnRedo(object sender, RoutedEventArgs e) => Step(back: false);

    /// <summary>Ctrl+Z, Ctrl+Y e Ctrl+Shift+Z. Dentro de uma caixa de texto vale o desfazer da caixa.</summary>
    private void OnUndoAccelerator(KeyboardAccelerator sender, KeyboardAcceleratorInvokedEventArgs args)
    {
        if (FocusManager.GetFocusedElement(XamlRoot) is TextBox or NumberBox or RichEditBox) return;
        args.Handled = true;
        var redo = sender.Key == Windows.System.VirtualKey.Y || sender.Modifiers.HasFlag(Windows.System.VirtualKeyModifiers.Shift);
        Step(back: !redo);
    }

    // ==================== guias ====================

    private StudioMeasures FrameMeasures => project is null ? default : new(project.Width, project.Height, project.Width, project.Height);

    private StudioMeasures LayerMeasures(StudioLayer layer) =>
        project is not null && infos.TryGetValue(layer.File, out var info) && info.Width is > 0 && info.Height is > 0
            ? new(info.Width.Value, info.Height.Value, project.Width, project.Height)
            : default;

    /// <summary>As caixas das outras camadas visíveis no cursor: as guias também encostam nelas.</summary>
    private IReadOnlyList<StudioBox> LayerReferences(string id)
    {
        if (project is null) return [];
        var now = TimeSlider.Value;
        return project.Layers.Where(c => c.Id != id && c.Visible && c.Start <= now && now <= c.End)
            .Select(c => StudioGuides.Box(LayerMeasures(c), c.Transform ?? new StudioTransform()))
            .OfType<StudioBox>().ToList();
    }

    /// <summary>A área que a imagem da prévia ocupa (Stretch=Uniform), em pixels da tela.</summary>
    private Rect PreviewRect()
    {
        if (project is null || PreviewArea.ActualWidth <= 0 || PreviewArea.ActualHeight <= 0) return default;
        var aspect = (double)project.Width / project.Height;
        var width = Math.Min(PreviewArea.ActualWidth, PreviewArea.ActualHeight * aspect);
        var height = width / aspect;
        return new Rect((PreviewArea.ActualWidth - width) / 2, (PreviewArea.ActualHeight - height) / 2, width, height);
    }

    /// <summary>Desenha as linhas e faz piscar três vezes; some depois de um instante.</summary>
    private void ShowGuides(IReadOnlyList<StudioGuide> guides)
    {
        GuidesCanvas.Children.Clear();
        var r = PreviewRect();
        if (r.Width <= 0) return;
        foreach (var guide in guides)
        {
            var line = new Rectangle
            {
                Fill = new SolidColorBrush(guide.Kind == "camada" ? Microsoft.UI.ColorHelper.FromArgb(255, 0x3D, 0xD6, 0xFF) : Microsoft.UI.ColorHelper.FromArgb(255, 0xFF, 0x3D, 0x9A)),
                Width = guide.Vertical ? 2 : r.Width,
                Height = guide.Vertical ? r.Height : 2,
            };
            Canvas.SetLeft(line, guide.Vertical ? r.X + guide.Position * r.Width - 1 : r.X);
            Canvas.SetTop(line, guide.Vertical ? r.Y : r.Y + guide.Position * r.Height - 1);
            GuidesCanvas.Children.Add(line);
        }
        if (guidesTimer is null)
        {
            guidesTimer = DispatcherQueue.CreateTimer();
            guidesTimer.Interval = TimeSpan.FromMilliseconds(150);
            guidesTimer.Tick += (_, _) =>
            {
                guidesTicks++;
                GuidesCanvas.Opacity = guidesTicks < 6 && guidesTicks % 2 == 1 ? 0.3 : 1;
                if (guidesTicks >= 8) { guidesTimer!.Stop(); GuidesCanvas.Children.Clear(); }
            };
        }
        guidesTicks = 0;
        GuidesCanvas.Opacity = 1;
        guidesTimer.Start();
    }

    // ==================== arrastar na prévia ====================

    private string? dragId;
    private Point dragStart;
    private StudioTransform? dragTransform;
    private (double X, double Y) dragText;

    private void OnPreviewPointerPressed(object sender, PointerRoutedEventArgs e)
    {
        if (project is null || openId is not { } id) return;
        dragTransform = project.Clips.FirstOrDefault(c => c.Id == id)?.Transform ?? (project.Clips.Any(c => c.Id == id) ? new StudioTransform() : null);
        dragTransform ??= project.Layers.FirstOrDefault(c => c.Id == id) is { } layer ? layer.Transform ?? new StudioTransform() : null;
        if (dragTransform is null)
        {
            if (project.Texts.FirstOrDefault(t => t.Id == id) is not { } text) return;
            dragText = (text.X, text.Y);
        }
        dragId = id;
        dragStart = e.GetCurrentPoint(PreviewArea).Position;
        PreviewArea.CapturePointer(e.Pointer);
        e.Handled = true;
    }

    private void OnPreviewPointerMoved(object sender, PointerRoutedEventArgs e)
    {
        if (project is null || dragId is not { } id) return;
        var r = PreviewRect();
        if (r.Width <= 0) return;
        var p = e.GetCurrentPoint(PreviewArea).Position;
        var dx = (p.X - dragStart.X) / r.Width;
        var dy = (p.Y - dragStart.Y) / r.Height;
        if (dragTransform is { } start)
        {
            var isLayer = project.Layers.Any(c => c.Id == id);
            var measures = isLayer ? LayerMeasures(FindLayer(id)) : FrameMeasures;
            var aligned = StudioGuides.Align(start with { X = start.X + dx, Y = start.Y + dy }, measures, isLayer ? LayerReferences(id) : null);
            var t = StudioAnimation.ChangeBase(start, aligned.Transform);
            Change(q => isLayer ? WithLayer(q, id, c => c with { Transform = t }) : WithClip(q, id, c => c with { Transform = t }));
            ShowGuides(aligned.Guides);
        }
        else
        {
            var x = Math.Clamp(dragText.X + dx, 0, 1);
            var y = Math.Clamp(dragText.Y + dy, 0, 1);
            var guides = new List<StudioGuide>();
            if (Math.Abs(x - .5) <= StudioGuides.Tolerance) { x = .5; guides.Add(new StudioGuide(true, .5, "centro")); }
            if (Math.Abs(y - .5) <= StudioGuides.Tolerance) { y = .5; guides.Add(new StudioGuide(false, .5, "centro")); }
            Change(q => WithText(q, id, t => t with { X = x, Y = y }));
            ShowGuides(guides);
        }
    }

    private void OnPreviewPointerReleased(object sender, PointerRoutedEventArgs e)
    {
        if (dragId is null) return;
        dragId = null;
        PreviewArea.ReleasePointerCapture(e.Pointer);
        RefreshPanels();
    }

    /// <summary>A roda do mouse muda o tamanho do que está aberto no painel.</summary>
    private void OnPreviewWheel(object sender, PointerRoutedEventArgs e)
    {
        if (project is null || openId is not { } id) return;
        var factor = Math.Exp(e.GetCurrentPoint(PreviewArea).Properties.MouseWheelDelta * 0.0015);
        StudioTransform Zoom(StudioTransform? t)
        {
            var before = t ?? new StudioTransform();
            return StudioAnimation.ChangeBase(before, before with { Scale = Math.Clamp(before.Scale * factor, .05, 6) });
        }
        if (project.Clips.Any(c => c.Id == id)) Change(q => WithClip(q, id, c => c with { Transform = Zoom(c.Transform) }));
        else if (project.Layers.Any(c => c.Id == id)) Change(q => WithLayer(q, id, c => c with { Transform = Zoom(c.Transform) }));
        else if (project.Texts.Any(t => t.Id == id)) Change(q => WithText(q, id, t => t with { Size = Math.Clamp(t.Size * factor, .03, .16) }));
        else return;
        e.Handled = true;
    }
}
