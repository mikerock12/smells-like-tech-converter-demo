using SmellsLikeTech.Converter.Infrastructure.Hardware;
using Xunit;

namespace SmellsLikeTech.Converter.Tests;

/// <summary>
/// O produto mostra na tela o hardware que encontrou, e isso vira argumento de venda
/// ("usando o seu computador"). Anunciar uma placa que a pessoa não tem destrói a
/// confiança na frase inteira — inclusive na parte que é verdade.
/// </summary>
public sealed class HardwareTests
{
    [Theory]
    [InlineData(0u, "Microsoft Basic Render Driver", true)]
    [InlineData(2u, "Outro adaptador de software", true)]
    [InlineData(0u, "NVIDIA GeForce", false)]
    [InlineData(0u, "Intel Graphics", false)]
    public void IdentificaSoftwareSemDependerDoHardwareDaMaquina(uint flags, string nome, bool esperado)
        => Assert.Equal(esperado, GpuInventory.IsSoftware(flags, nome));

    [Fact]
    public void NaoAnunciaOAdaptadorDeSoftwareComoPlaca()
    {
        // "Microsoft Basic Render Driver" existe em toda máquina e não é placa nenhuma.
        Assert.All(
            GpuInventory.Present(),
            placa => Assert.DoesNotContain("Basic Render", placa, StringComparison.OrdinalIgnoreCase));
    }

    [Fact]
    public void OsNomesChegamLimposOuAListaVemVazia()
    {
        var placas = GpuInventory.Present();

        Assert.All(placas, placa =>
        {
            Assert.False(string.IsNullOrWhiteSpace(placa));
            Assert.Equal(placa.Trim(), placa);
        });

        // Sem duplicatas: a mesma placa não pode aparecer duas vezes.
        Assert.Equal(placas.Count, placas.Distinct(StringComparer.Ordinal).Count());
    }

    [Fact]
    public async Task NaoLeMaisAsDescricoesDeDriverDoRegistro()
    {
        // Esta é a regressão que interessa. A fonte antiga era
        // HKLM\...\Control\Class\{4d36e968-…}, onde fica registrado todo driver já
        // instalado — presente ou não. Numa máquina que um dia teve placa dedicada, ela
        // continuava sendo anunciada para sempre. Se alguém voltar a ler dali, quebra aqui.
        var fonte = await File.ReadAllTextAsync(CaminhoDo("HardwareProbe.cs"));

        Assert.DoesNotContain("4d36e968", fonte, StringComparison.OrdinalIgnoreCase);
        Assert.DoesNotContain("DriverDesc", fonte, StringComparison.Ordinal);
        Assert.Contains("GpuInventory.Present()", fonte, StringComparison.Ordinal);
    }

    private static string CaminhoDo(string arquivo)
    {
        var pasta = AppContext.BaseDirectory;
        for (var subida = 0; subida < 8 && pasta is not null; subida++)
        {
            var candidato = Path.Combine(
                pasta, "src", "SmellsLikeTech.Converter.Infrastructure", "Hardware", arquivo);
            if (File.Exists(candidato))
            {
                return candidato;
            }

            pasta = Path.GetDirectoryName(pasta);
        }

        throw new FileNotFoundException($"Não encontrei {arquivo} a partir de {AppContext.BaseDirectory}.");
    }
}
