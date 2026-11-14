using System.Runtime.InteropServices;

namespace SmellsLikeTech.Converter.Desktop.Services;

/// <summary>
/// Trabalho longo em segundo plano (conversões, separação, exportações): enquanto houver
/// algum, o Windows não suspende o computador — a tela pode apagar e o processamento segue —,
/// e o ícone do app na barra de tarefas mostra o andamento, visível mesmo com o app
/// minimizado ou atrás de outra janela. O título da janela leva a porcentagem.
/// </summary>
public static class BackgroundWork
{
    private static readonly Dictionary<int, (string Title, double Fraction)> active = [];
    private static int next = 1;
    private static nint window;
    private static Action<string?>? setTitle;
    private static ITaskbarList3? taskbar;
    private static Microsoft.UI.Dispatching.DispatcherQueue? dispatcherQueue;
    private static readonly Dictionary<Guid, Token> jobs = [];

    /// <summary>Acompanha a fila de conversões: cada job em andamento conta como trabalho.</summary>
    public static void Follow(SmellsLikeTech.Converter.Core.Queue.ConversionQueue queue)
    {
        queue.JobChanged += (_, job) =>
        {
            lock (jobs)
            {
                var running = job.Status is SmellsLikeTech.Converter.Core.Jobs.JobStatus.Preparing
                    or SmellsLikeTech.Converter.Core.Jobs.JobStatus.Processing or SmellsLikeTech.Converter.Core.Jobs.JobStatus.Finalizing;
                if (running)
                {
                    if (!jobs.TryGetValue(job.Id, out var token)) jobs[job.Id] = token = Begin(job.DisplayName);
                    token.Report(job.Progress ?? 0);
                }
                else if (jobs.Remove(job.Id, out var done)) done.Dispose();
            }
        };
    }

    /// <summary>Liga a janela principal: o progresso vai para o ícone dela na barra de tarefas.</summary>
    public static void Attach(nint hwnd, Action<string?> title)
    {
        window = hwnd;
        setTitle = title;
        dispatcherQueue = Microsoft.UI.Dispatching.DispatcherQueue.GetForCurrentThread();
        try
        {
            taskbar = (ITaskbarList3)new TaskbarList();
            taskbar.HrInit();
        }
        catch (COMException)
        {
            taskbar = null;
        }
    }

    /// <summary>Começa um trabalho; o token encerra ao ser descartado.</summary>
    public static Token Begin(string title)
    {
        lock (active)
        {
            var id = next++;
            active[id] = (title, 0);
            Refresh();
            return new Token(id);
        }
    }

    public static void Report(int id, double fraction, string? title = null)
    {
        lock (active)
        {
            if (!active.TryGetValue(id, out var current)) return;
            active[id] = (title ?? current.Title, Math.Clamp(fraction, 0, 1));
            Refresh();
        }
    }

    private static void End(int id)
    {
        lock (active)
        {
            if (!active.Remove(id)) return;
            Refresh();
        }
    }

    private static void Refresh()
    {
        // ES_CONTINUOUS | ES_SYSTEM_REQUIRED: não dormir; a tela pode apagar normalmente.
        SetThreadExecutionState(active.Count > 0 ? 0x80000000u | 0x00000001u : 0x80000000u);
        var slowest = active.Values.OrderBy(v => v.Fraction).FirstOrDefault();
        var dispatcher = Microsoft.UI.Dispatching.DispatcherQueue.GetForCurrentThread();
        void Apply()
        {
            try
            {
                if (taskbar is not null && window != 0)
                {
                    if (active.Count == 0) taskbar.SetProgressState(window, 0);
                    else
                    {
                        taskbar.SetProgressState(window, slowest.Fraction <= 0 ? 1u : 2u);
                        taskbar.SetProgressValue(window, (ulong)Math.Round(slowest.Fraction * 1000), 1000);
                    }
                }
            }
            catch (COMException)
            {
                // Barra de tarefas indisponível (Explorer reiniciando): o trabalho segue.
            }

            setTitle?.Invoke(active.Count == 0 ? null
                : $"{Math.Floor(slowest.Fraction * 100):0}% · {slowest.Title}{(active.Count > 1 ? $" (+{active.Count - 1})" : "")}");
        }

        if (dispatcher is not null) Apply();
        else dispatcherQueue?.TryEnqueue(Apply);
    }

    public readonly struct Token(int id) : IDisposable
    {
        public int Id => id;
        public void Report(double fraction, string? title = null) => BackgroundWork.Report(id, fraction, title);
        public void Dispose() => End(id);
    }

    [DllImport("kernel32.dll")]
    private static extern uint SetThreadExecutionState(uint flags);

    [ComImport, Guid("56FDF344-FD6D-11d0-958A-006097C9A090"), ClassInterface(ClassInterfaceType.None)]
    private class TaskbarList;

    [ComImport, Guid("ea1afb91-9e28-4b86-90e9-9e9f8a5eefaf"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface ITaskbarList3
    {
        void HrInit();
        void AddTab(nint hwnd);
        void DeleteTab(nint hwnd);
        void ActivateTab(nint hwnd);
        void SetActiveAlt(nint hwnd);
        void MarkFullscreenWindow(nint hwnd, [MarshalAs(UnmanagedType.Bool)] bool fullscreen);
        void SetProgressValue(nint hwnd, ulong completed, ulong total);
        void SetProgressState(nint hwnd, uint flags);
    }
}
