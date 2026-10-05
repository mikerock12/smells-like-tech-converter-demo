using System.Diagnostics;
using System.Windows.Forms;
using SmellsLikeTech.Converter.Bridge.Hosting;
using SmellsLikeTech.Converter.Bridge.Tray;
using SmellsLikeTech.Converter.Composition;
using SmellsLikeTech.Converter.Core.Abstractions;

namespace SmellsLikeTech.Converter.Bridge;

/// <summary>
/// O plugin.
///
/// Liga o hardware desta maquina ao site: o navegador manda o pedido, quem converte e o
/// mesmo motor do aplicativo desktop, usando a mesma placa de video e os mesmos codecs.
/// A diferenca e que aqui nao existe janela — so um icone na bandeja.
/// </summary>
internal static class Program
{
    private const string NomeDoMutex = @"Local\SmellsLikeTechConverterPlugin";
    private const string EnderecoDoSite = "https://converter.smellsliketech.com.br";

    [STAThread]
    private static int Main(string[] argumentos)
    {
        // Duas copias disputando a mesma porta so dariam erro. A segunda avisa e sai.
        using var trava = new Mutex(initiallyOwned: true, NomeDoMutex, out var somosOsPrimeiros);
        if (!somosOsPrimeiros)
        {
            return 0;
        }

        ApplicationConfiguration.Initialize();

        var opcoes = BridgeOptions.FromEnvironment();
        TrayPresence? bandeja = null;
        AppServices? servicos = null;
        BridgeServer? servidor = null;

        // Precisa nascer aqui, nesta thread: e a unica com bomba de mensagens, e e dela
        // que a caixa de selecao do Windows depende para abrir.
        using var interfaceDoUsuario = new UiThread();

        try
        {
            // A partida roda fora desta thread de proposito.
            //
            // Criar a janela dona instalou o contexto de sincronizacao do WinForms; se a
            // espera acontecesse aqui, cada await la dentro tentaria voltar para esta
            // thread — que estaria justamente parada esperando. O programa nunca chegava
            // a abrir a porta.
            var partida = Task.Run(async () =>
            {
                // A composicao e a mesma do aplicativo: banco, pastas, deteccao de
                // hardware, motores e fila. Nada aqui e uma segunda implementacao.
                var montados = await AppServices.StartAsync();
                var porta = new BridgeServer(montados, opcoes, interfaceDoUsuario);
                await porta.IniciarAsync(CancellationToken.None);
                return (montados, porta);
            });

            (servicos, servidor) = partida.GetAwaiter().GetResult();

            var servicosLigados = servicos;
            var servidorLigado = servidor;

            bandeja = new TrayPresence(
                EnderecoDoSite,
                opcoes.Port,
                async () =>
                {
                    await servidorLigado.DisposeAsync();
                    await servicosLigados.DisposeAsync();
                })
            {
                AoAbrirPasta = () => AbrirPasta(servicosLigados.OutputDirectory)
            };

            bandeja.MostrarPronto(servicos.Hardware.CpuName);
            servicos.Log.Write(
                LogChannel.App,
                $"Plugin ligado · 127.0.0.1:{opcoes.Port} · {servicos.Hardware.CpuName}");

            if (!servicos.FfmpegAvailable)
            {
                bandeja.Avisar(
                    "FFmpeg não encontrado",
                    "Vídeo, áudio e transcrição vão ficar indisponíveis até o FFmpeg estar no PATH.");
            }

            if (argumentos.Contains("--abrir-site", StringComparer.OrdinalIgnoreCase))
            {
                AbrirNoNavegador(EnderecoDoSite);
            }

            Application.Run();
            return 0;
        }
        catch (Exception excecao)
        {
            // Sem janela para mostrar o erro: registra e avisa pela bandeja, se ela existir.
            servicos?.Log.Error("plugin_nao_iniciou", "O plugin não conseguiu iniciar.", excecao);
            bandeja?.Avisar("O plugin não iniciou", excecao.Message);
            RegistrarFalhaDeUltimoRecurso(excecao);
            return 1;
        }
        finally
        {
            bandeja?.Dispose();
            servidor?.DisposeAsync().AsTask().GetAwaiter().GetResult();
            servicos?.DisposeAsync().AsTask().GetAwaiter().GetResult();
            trava.ReleaseMutex();
        }
    }

    private static void AbrirPasta(string pasta)
    {
        try
        {
            Directory.CreateDirectory(pasta);
            using var processo = Process.Start(new ProcessStartInfo { FileName = pasta, UseShellExecute = true });
        }
        catch (Exception excecao) when (excecao is IOException or System.ComponentModel.Win32Exception)
        {
        }
    }

    private static void AbrirNoNavegador(string endereco)
    {
        try
        {
            using var processo = Process.Start(new ProcessStartInfo { FileName = endereco, UseShellExecute = true });
        }
        catch (Exception excecao) when (excecao is IOException or System.ComponentModel.Win32Exception)
        {
        }
    }

    /// <summary>
    /// Quando nem o log do produto pode ser aberto — disco cheio, permissao negada — sobra
    /// gravar ao lado do executavel. Sem isto, uma falha na largada seria invisivel.
    /// </summary>
    private static void RegistrarFalhaDeUltimoRecurso(Exception excecao)
    {
        try
        {
            var caminho = Path.Combine(AppContext.BaseDirectory, "plugin-falhou.txt");
            File.WriteAllText(caminho, $"{DateTimeOffset.Now:O}{Environment.NewLine}{excecao}");
        }
        catch (Exception)
        {
        }
    }
}
