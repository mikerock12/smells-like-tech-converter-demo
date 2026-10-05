using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;

namespace SmellsLikeTech.Converter.Bridge.Hosting;

/// <summary>
/// O mínimo do Windows para a caixa de seleção não nascer escondida.
///
/// O plugin não tem janela: quando a caixa abre, quem está na frente é o navegador. Sem
/// isto, o usuário clica em "escolher arquivo" e parece que nada aconteceu — a caixa
/// está lá atrás, piscando na barra de tarefas.
/// </summary>
internal static class NativeWindows
{
    private const string ClasseDeDialogo = "#32770";

    /// <summary>Acha uma caixa de diálogo deste processo, se houver alguma aberta.</summary>
    public static nint EncontrarCaixaDeDialogo()
    {
        var nossoProcesso = (uint)Environment.ProcessId;
        var encontrada = nint.Zero;

        EnumWindows((janela, _) =>
        {
            if (!IsWindowVisible(janela))
            {
                return true;
            }

            GetWindowThreadProcessId(janela, out var processo);
            if (processo != nossoProcesso)
            {
                return true;
            }

            var classe = new StringBuilder(64);
            GetClassName(janela, classe, classe.Capacity);
            if (!classe.ToString().Equals(ClasseDeDialogo, StringComparison.Ordinal))
            {
                return true;
            }

            encontrada = janela;
            return false;
        }, nint.Zero);

        return encontrada;
    }

    /// <summary>
    /// Traz a janela para a frente, em duas tentativas de força diferente.
    ///
    /// **Ordem de empilhamento primeiro.** <c>SetWindowPos</c> não precisa de permissão
    /// de primeiro plano: marcar a janela como sempre-no-topo e desmarcar em seguida a
    /// coloca acima das outras. É o que garante que a caixa apareça, mesmo que o Windows
    /// negue o foco de teclado.
    ///
    /// **Depois o foco.** <c>SetForegroundWindow</c> é recusado quando quem chama não
    /// está em primeiro plano — e o plugin nunca está, porque não tem janela. O caminho
    /// aceito é grudar a fila de entrada da nossa thread na de quem está na frente,
    /// pedir o foco, e desgrudar.
    ///
    /// Se as duas falharem, resta piscar na barra de tarefas: pouco, mas melhor do que
    /// a pessoa achar que o clique não fez nada.
    /// </summary>
    public static void TrazerParaFrente(nint janela)
    {
        if (janela == nint.Zero)
        {
            return;
        }

        ShowWindow(janela, ShowNormalSemAtivar);

        SetWindowPos(janela, SempreNoTopo, 0, 0, 0, 0, SemMover | SemRedimensionar | Mostrando);
        SetWindowPos(janela, ForaDoTopo, 0, 0, 0, 0, SemMover | SemRedimensionar | Mostrando);
        BringWindowToTop(janela);

        var daFrente = GetForegroundWindow();
        if (daFrente == janela)
        {
            return;
        }

        var nossa = GetCurrentThreadId();
        var deles = GetWindowThreadProcessId(daFrente, out _);
        var grudou = deles != 0 && deles != nossa && AttachThreadInput(nossa, deles, true);
        try
        {
            SetForegroundWindow(janela);
            SetActiveWindow(janela);
        }
        finally
        {
            if (grudou)
            {
                AttachThreadInput(nossa, deles, false);
            }
        }

        if (GetForegroundWindow() != janela)
        {
            var aviso = new FlashInfo
            {
                Tamanho = (uint)Marshal.SizeOf<FlashInfo>(),
                Janela = janela,
                Bandeira = PiscarAteResponder,
                Vezes = uint.MaxValue,
                Intervalo = 0
            };
            FlashWindowEx(ref aviso);
        }
    }

    private const int ShowNormalSemAtivar = 4;
    private static readonly nint SempreNoTopo = -1;
    private static readonly nint ForaDoTopo = -2;
    private const uint SemMover = 0x0002;
    private const uint SemRedimensionar = 0x0001;
    private const uint Mostrando = 0x0040;

    /// <summary>Pisca a barra de tarefas até a janela receber atenção.</summary>
    private const uint PiscarAteResponder = 0x0000000C;

    [StructLayout(LayoutKind.Sequential)]
    private struct FlashInfo
    {
        public uint Tamanho;
        public nint Janela;
        public uint Bandeira;
        public uint Vezes;
        public uint Intervalo;
    }

    [DllImport("user32.dll")]
    private static extern bool SetWindowPos(nint janela, nint depoisDe, int x, int y, int largura, int altura, uint bandeiras);

    [DllImport("user32.dll")]
    private static extern bool ShowWindow(nint janela, int comando);

    [DllImport("user32.dll")]
    private static extern nint SetActiveWindow(nint janela);

    [DllImport("user32.dll")]
    private static extern bool FlashWindowEx(ref FlashInfo info);

    private delegate bool EnumJanela(nint janela, nint parametro);

    [DllImport("user32.dll")]
    private static extern bool EnumWindows(EnumJanela callback, nint parametro);

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern int GetClassName(nint janela, StringBuilder texto, int tamanho);

    [DllImport("user32.dll")]
    private static extern uint GetWindowThreadProcessId(nint janela, out uint processo);

    [DllImport("user32.dll")]
    private static extern bool IsWindowVisible(nint janela);

    [DllImport("user32.dll")]
    private static extern nint GetForegroundWindow();

    [DllImport("user32.dll")]
    private static extern bool SetForegroundWindow(nint janela);

    [DllImport("user32.dll")]
    private static extern bool BringWindowToTop(nint janela);

    [DllImport("user32.dll")]
    private static extern bool AttachThreadInput(uint nossa, uint deles, bool grudar);

    [DllImport("kernel32.dll")]
    private static extern uint GetCurrentThreadId();

    /// <summary>Usado apenas em diagnóstico, quando o log precisa dizer quem estava na frente.</summary>
    public static string NomeDoProcessoEmPrimeiroPlano()
    {
        try
        {
            GetWindowThreadProcessId(GetForegroundWindow(), out var processo);
            return Process.GetProcessById((int)processo).ProcessName;
        }
        catch (Exception excecao) when (excecao is ArgumentException or InvalidOperationException)
        {
            return "desconhecido";
        }
    }
}
