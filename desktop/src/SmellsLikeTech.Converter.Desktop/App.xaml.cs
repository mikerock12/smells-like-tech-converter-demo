using Microsoft.UI.Dispatching;
using Microsoft.UI.Xaml;
using SmellsLikeTech.Converter.Composition;
using SmellsLikeTech.Converter.Desktop.Services;
using SmellsLikeTech.Converter.Desktop.ViewModels;

namespace SmellsLikeTech.Converter.Desktop;

public partial class App : Application
{
    private static AppServices? services;
    private static QueueViewModel? queueViewModel;

    public App()
    {
        InitializeComponent();

        // Sem isto, uma falha em manipulador async void some sem deixar rastro e o
        // aplicativo parece apenas "não fazer nada".
        UnhandledException += OnUnhandledException;
    }

    private void OnUnhandledException(object sender, Microsoft.UI.Xaml.UnhandledExceptionEventArgs args)
    {
        if (services is not null)
        {
            services.Log.Error("unhandled_ui_exception", args.Message, args.Exception);
        }

        // A conversão de verdade roda isolada na fila: manter a janela viva é melhor
        // do que derrubar o aplicativo por um erro de interface.
        args.Handled = true;
    }

    /// <summary>Disponível a partir do fim da abertura.</summary>
    public static AppServices Services => services
        ?? throw new InvalidOperationException("Os serviços do aplicativo ainda não foram inicializados.");

    public static QueueViewModel QueueViewModel => queueViewModel
        ?? throw new InvalidOperationException("A fila ainda não foi inicializada.");

    public static bool IsReady => services is not null;

    public static Window? MainWindow { get; private set; }

    protected override void OnLaunched(LaunchActivatedEventArgs args)
    {
        var splash = new SplashWindow();
        splash.Activate();
    }

    internal static async Task InitializeServicesAsync(IProgress<StartupStage>? progress = null)
    {
        services ??= await AppServices.StartAsync(progress);
    }

    internal static void RegisterMainWindow(Window window) => MainWindow = window;

    internal static void CreateQueueViewModel(DispatcherQueue dispatcher)
    {
        queueViewModel ??= new QueueViewModel(Services.Queue, dispatcher);
    }

    internal static async Task ShutdownAsync()
    {
        queueViewModel?.Dispose();
        queueViewModel = null;

        if (services is not null)
        {
            await services.DisposeAsync();
            services = null;
        }
    }
}
