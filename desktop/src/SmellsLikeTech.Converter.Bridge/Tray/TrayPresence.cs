using System.Diagnostics;
using System.Windows.Forms;

namespace SmellsLikeTech.Converter.Bridge.Tray;

/// <summary>
/// A unica presenca visivel do plugin: um icone perto do relogio.
///
/// Ele nao abre janela nem rouba foco. Existe para o cliente saber que o programa esta
/// ligado, ver de onde vem, e conseguir desligar sem procurar no gerenciador de tarefas.
/// </summary>
public sealed class TrayPresence : IDisposable
{
    private readonly NotifyIcon icone;
    private readonly string enderecoDoSite;

    public TrayPresence(string enderecoDoSite, int porta, Func<Task> aoSair)
    {
        this.enderecoDoSite = enderecoDoSite;

        var menu = new ContextMenuStrip();
        menu.Items.Add("Abrir o conversor no navegador", null, (_, _) => Abrir(enderecoDoSite));
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add($"Escutando em 127.0.0.1:{porta}").Enabled = false;
        menu.Items.Add("Pasta dos resultados", null, (_, _) => AoAbrirPasta?.Invoke());
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("Sair", null, async (_, _) =>
        {
            await aoSair();
            Application.Exit();
        });

        icone = new NotifyIcon
        {
            Icon = CarregarIcone(),
            Text = "Smells Like Tech Converter — plugin ligado",
            Visible = true,
            ContextMenuStrip = menu
        };

        icone.DoubleClick += (_, _) => Abrir(enderecoDoSite);
    }

    /// <summary>Ligado pelo programa principal, que sabe qual e a pasta de saida.</summary>
    public Action? AoAbrirPasta { get; set; }

    /// <summary>Aviso curto, usado so quando algo impede o plugin de trabalhar.</summary>
    public void Avisar(string titulo, string mensagem)
    {
        icone.BalloonTipTitle = titulo;
        icone.BalloonTipText = mensagem;
        icone.BalloonTipIcon = ToolTipIcon.Warning;
        icone.ShowBalloonTip(8_000);
    }

    public void MostrarPronto(string processador)
    {
        icone.Text = Encurtar($"Smells Like Tech Converter — pronto · {processador}");
    }

    /// <summary>O texto do icone tem limite de 63 caracteres no Windows.</summary>
    private static string Encurtar(string texto) => texto.Length <= 63 ? texto : texto[..60] + "...";

    private static Icon CarregarIcone()
    {
        var caminho = Path.Combine(AppContext.BaseDirectory, "Assets", "icon.ico");
        if (File.Exists(caminho))
        {
            return new Icon(caminho);
        }

        return SystemIcons.Application;
    }

    private static void Abrir(string endereco)
    {
        try
        {
            using var processo = Process.Start(new ProcessStartInfo { FileName = endereco, UseShellExecute = true });
        }
        catch (Exception excecao) when (excecao is System.ComponentModel.Win32Exception or IOException)
        {
            // Sem navegador padrao configurado: nada a fazer, e nao vale derrubar o plugin.
        }
    }

    public void Dispose()
    {
        icone.Visible = false;
        icone.Dispose();
    }
}
