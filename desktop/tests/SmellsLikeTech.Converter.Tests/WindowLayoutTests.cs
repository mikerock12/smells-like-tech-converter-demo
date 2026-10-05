using SmellsLikeTech.Converter.Infrastructure.Configuration;
using Xunit;

namespace SmellsLikeTech.Converter.Tests;

public class WindowLayoutTests
{
    // Monitores comuns, já descontada a barra de tarefas.
    private static WindowBounds FullHd => new(0, 0, 1920, 1040);
    private static WindowBounds Hd => new(0, 0, 1366, 728);
    private static WindowBounds Quatro4K => new(0, 0, 3840, 2120);
    private static WindowBounds Pequeno => new(0, 0, 1280, 640);

    [Theory]
    [InlineData(1920, 1040)]
    [InlineData(1366, 728)]
    [InlineData(3840, 2120)]
    [InlineData(1280, 640)]
    public void RestoreBounds_SempreCabeNaAreaUtil(int largura, int altura)
    {
        var area = new WindowBounds(0, 0, largura, altura);
        var bounds = WindowLayout.RestoreBoundsFor(area);

        Assert.True(bounds.Width <= area.Width, $"{bounds.Width} > {area.Width}");
        Assert.True(bounds.Height <= area.Height, $"{bounds.Height} > {area.Height}");
        Assert.True(bounds.Left >= area.Left);
        Assert.True(bounds.Top >= area.Top);
        Assert.True(bounds.Right <= area.Right);
        Assert.True(bounds.Bottom <= area.Bottom);
    }

    [Fact]
    public void RestoreBounds_FicaCentralizadaNoMonitor()
    {
        var bounds = WindowLayout.RestoreBoundsFor(FullHd);

        var folgaEsquerda = bounds.Left - FullHd.Left;
        var folgaDireita = FullHd.Right - bounds.Right;
        Assert.InRange(Math.Abs(folgaEsquerda - folgaDireita), 0, 1);
    }

    [Fact]
    public void RestoreBounds_AcompanhaOTamanhoDoMonitor()
    {
        var pequena = WindowLayout.RestoreBoundsFor(Hd);
        var grande = WindowLayout.RestoreBoundsFor(Quatro4K);

        // A mesma janela não pode sair do mesmo tamanho em um monitor HD e em um 4K.
        Assert.True(grande.Width > pequena.Width);
        Assert.True(grande.Height > pequena.Height);
    }

    [Fact]
    public void RestoreBounds_EmMonitorMenorQueOMinimo_NaoUltrapassaATela()
    {
        // Em 1280x640 a altura mínima pretendida (680) não cabe: precisa ceder para a
        // tela em vez de nascer maior que o monitor.
        var bounds = WindowLayout.RestoreBoundsFor(Pequeno);

        Assert.Equal(Pequeno.Height, bounds.Height);
        Assert.InRange(bounds.Width, WindowLayout.MinimumWidth, Pequeno.Width);
        Assert.Equal(0, bounds.Top);
    }

    [Fact]
    public void IsUsable_RetanguloDentroDaTela_EhAceito() =>
        Assert.True(WindowLayout.IsUsable(new WindowBounds(100, 60, 1400, 900), FullHd));

    [Fact]
    public void IsUsable_MaiorQueOMonitorAtual_EhRecusado() =>
        Assert.False(WindowLayout.IsUsable(new WindowBounds(0, 0, 2400, 1300), FullHd));

    [Fact]
    public void IsUsable_ForaDaTela_EhRecusado() =>
        Assert.False(WindowLayout.IsUsable(new WindowBounds(3000, 1500, 1200, 800), FullHd));

    [Fact]
    public void IsUsable_MinusculaDemais_EhRecusada() =>
        Assert.False(WindowLayout.IsUsable(new WindowBounds(10, 10, 200, 120), FullHd));

    [Fact]
    public void Resolve_PrimeiraExecucao_CalculaPelaTela()
    {
        var bounds = WindowLayout.Resolve(new AppSettings(), FullHd);
        Assert.Equal(WindowLayout.RestoreBoundsFor(FullHd), bounds);
    }

    [Fact]
    public void Resolve_ComPosicaoGuardadaValida_Respeita()
    {
        var settings = new AppSettings { WindowLeft = 120, WindowTop = 80, WindowWidth = 1400, WindowHeight = 900 };
        var bounds = WindowLayout.Resolve(settings, FullHd);

        Assert.Equal(new WindowBounds(120, 80, 1400, 900), bounds);
    }

    [Fact]
    public void Resolve_TrocandoParaUmMonitorMenor_RecalculaPelaTelaNova()
    {
        // Janela guardada em um Full HD, aberta depois em um notebook HD.
        var settings = new AppSettings { WindowLeft = 100, WindowTop = 60, WindowWidth = 1700, WindowHeight = 980 };
        var bounds = WindowLayout.Resolve(settings, Hd);

        Assert.NotEqual(new WindowBounds(100, 60, 1700, 980), bounds);
        Assert.True(bounds.Width <= Hd.Width && bounds.Height <= Hd.Height);
    }

    [Fact]
    public void Capture_Maximizada_PreservaORetanguloDeRestauracao()
    {
        var anterior = new AppSettings { WindowLeft = 200, WindowTop = 100, WindowWidth = 1300, WindowHeight = 850 };

        // Maximizada, o retângulo atual é o do monitor inteiro e não deve virar restauração.
        var atualizado = WindowLayout.Capture(anterior, maximized: true, new WindowBounds(0, 0, 1920, 1040));

        Assert.True(atualizado.WindowMaximized);
        Assert.Equal(1300, atualizado.WindowWidth);
        Assert.Equal(850, atualizado.WindowHeight);
    }

    [Fact]
    public void Capture_Restaurada_GuardaOTamanhoAtual()
    {
        var atualizado = WindowLayout.Capture(
            new AppSettings(),
            maximized: false,
            new WindowBounds(300, 150, 1200, 800));

        Assert.False(atualizado.WindowMaximized);
        Assert.Equal(new WindowBounds(300, 150, 1200, 800),
            new WindowBounds(atualizado.WindowLeft, atualizado.WindowTop, atualizado.WindowWidth, atualizado.WindowHeight));
    }

    [Fact]
    public void PadraoDeFabrica_AbreMaximizada()
    {
        var settings = new AppSettings();
        Assert.True(settings.WindowMaximized);
        Assert.False(settings.HasWindowBounds);
    }
}
