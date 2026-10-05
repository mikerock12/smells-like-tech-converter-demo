using System.Drawing;
using System.Windows.Forms;

namespace SmellsLikeTech.Converter.Bridge.Tray;

/// <summary>
/// A thread de interface do plugin — que existe mesmo ele não tendo tela.
///
/// A caixa de seleção do Windows é COM de apartamento único e precisa de uma thread com
/// bomba de mensagens rodando. Uma thread nova do pool não serve: a caixa simplesmente
/// não abre e o pedido fica pendurado. Aqui reaproveitamos a mesma thread que já segura
/// o ícone da bandeja.
///
/// A janela invisível existe para ser a <em>dona</em> da caixa. Sem dona, ela nasce atrás
/// do navegador e o usuário acha que o clique não fez nada.
/// </summary>
public sealed class UiThread : IDisposable
{
    private readonly JanelaDona janela = new();

    public UiThread()
    {
        // Força a criação do handle: sem ele não há para onde marshalar nem quem seja dono.
        _ = janela.Handle;
    }

    /// <summary>Handle usado como dono das caixas de diálogo.</summary>
    public nint Owner => janela.Handle;

    /// <summary>
    /// Executa na thread de interface e devolve o resultado sem prender a thread que
    /// chamou — o usuário pode levar minutos para escolher um arquivo.
    /// </summary>
    public Task<T> ExecutarAsync<T>(Func<nint, T> trabalho)
    {
        var conclusao = new TaskCompletionSource<T>(TaskCreationOptions.RunContinuationsAsynchronously);

        if (janela.IsDisposed)
        {
            conclusao.SetException(new ObjectDisposedException(nameof(UiThread)));
            return conclusao.Task;
        }

        janela.BeginInvoke(() =>
        {
            try
            {
                conclusao.TrySetResult(trabalho(janela.Handle));
            }
            catch (Exception excecao)
            {
                conclusao.TrySetException(excecao);
            }
        });

        return conclusao.Task;
    }

    public void Dispose() => janela.Dispose();

    /// <summary>Janela de um pixel, fora da tela, que nunca aparece e nunca vai para a barra.</summary>
    private sealed class JanelaDona : Form
    {
        public JanelaDona()
        {
            FormBorderStyle = FormBorderStyle.FixedToolWindow;
            ShowInTaskbar = false;
            StartPosition = FormStartPosition.Manual;
            Location = new Point(-32_000, -32_000);
            Size = new Size(1, 1);
            Opacity = 0;
        }

        protected override void SetVisibleCore(bool value) => base.SetVisibleCore(false);
    }
}
