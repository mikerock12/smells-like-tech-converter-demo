using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;

namespace SmellsLikeTech.Converter.Desktop.Pages;

/// <summary>
/// A primeira tela: diz o que o aplicativo faz e leva cada pessoa ao caminho certo. Converter e
/// criar no estudio sao as duas grandes portas; transcrever, narrar e o lote ficam visiveis
/// porque ninguem os acharia dentro de "converter". As mesmas portas do site e do Android.
/// </summary>
public sealed partial class StartPage : Page
{
    private sealed record Caminho(string Icone, string Titulo, string Descricao, bool Destaque, string Destino);

    private static readonly Caminho[] Caminhos =
    [
        new("\uE8AB", "Converter uma mídia", "Troque o formato, comprima, corte ou junte vídeos, áudios, imagens, PDFs e documentos.", true, "home"),
        new("\uE714", "Criar no estúdio", "Monte vídeo, GIF, imagem ou áudio para Instagram, TikTok, YouTube, WhatsApp e outras redes: cortes, textos e música.", true, "studio"),
        new("\uE720", "Transcrever áudio ou vídeo", "A fala vira texto ou legenda com o Whisper, neste computador.", false, "transcribe"),
        new("\uE767", "Narrar um texto", "Texto vira áudio com vozes naturais em português (Kokoro-82M).", false, "speech"),
        new("\uE8B7", "Vários arquivos de uma vez", "Escolha uma pasta ou muitos arquivos e converta tudo em lote.", false, "batch")
    ];

    public StartPage()
    {
        InitializeComponent();
        foreach (var caminho in Caminhos)
        {
            PathsGrid.Children.Add(Cartao(caminho));
        }
    }

    private static Button Cartao(Caminho caminho)
    {
        var icone = new Border
        {
            Width = 44,
            Height = 44,
            HorizontalAlignment = HorizontalAlignment.Left,
            CornerRadius = new CornerRadius(11),
            Background = caminho.Destaque
                ? (Brush)Application.Current.Resources["BrandActionBrush"]
                : (Brush)Application.Current.Resources["BrandSurfaceAltBrush"],
            Child = new FontIcon
            {
                Glyph = caminho.Icone,
                FontSize = 20,
                Foreground = caminho.Destaque
                    ? new SolidColorBrush(Microsoft.UI.ColorHelper.FromArgb(0xFF, 0x0B, 0x0B, 0x0D))
                    : (Brush)Application.Current.Resources["BrandTextBrush"]
            }
        };

        var texto = new StackPanel { Spacing = 6 };
        texto.Children.Add(icone);
        texto.Children.Add(new TextBlock { Text = caminho.Titulo, FontSize = 18, FontWeight = Microsoft.UI.Text.FontWeights.SemiBold });
        texto.Children.Add(new TextBlock
        {
            Text = caminho.Descricao,
            FontSize = 13,
            TextWrapping = TextWrapping.WrapWholeWords,
            Foreground = (Brush)Application.Current.Resources["BrandTextMutedBrush"]
        });

        var botao = new Button
        {
            Content = texto,
            Margin = new Thickness(0, 0, 14, 14),
            Padding = new Thickness(18),
            HorizontalAlignment = HorizontalAlignment.Stretch,
            VerticalAlignment = VerticalAlignment.Stretch,
            HorizontalContentAlignment = HorizontalAlignment.Left,
            VerticalContentAlignment = VerticalAlignment.Top,
            CornerRadius = new CornerRadius(14),
            Background = (Brush)Application.Current.Resources["BrandPanelBrush"],
            BorderBrush = caminho.Destaque
                ? (Brush)Application.Current.Resources["BrandStrokeGlowBrush"]
                : (Brush)Application.Current.Resources["BrandStrokeBrush"],
            BorderThickness = new Thickness(1),
            Tag = caminho.Destino
        };
        AutomationProperties.SetName(botao, caminho.Titulo);
        botao.Click += (_, _) =>
        {
            if (App.MainWindow is MainWindow main)
            {
                main.Open(caminho.Destino);
            }
        };
        return botao;
    }
}
