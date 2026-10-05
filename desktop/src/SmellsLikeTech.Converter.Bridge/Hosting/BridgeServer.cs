using System.Net;
using System.Text;
using System.Text.Json;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Routing;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using SmellsLikeTech.Converter.Bridge.Contracts;
using SmellsLikeTech.Converter.Bridge.Tray;
using SmellsLikeTech.Converter.Composition;
using SmellsLikeTech.Converter.Core;
using SmellsLikeTech.Converter.Core.Abstractions;
using SmellsLikeTech.Converter.Core.Jobs;
using SmellsLikeTech.Converter.Core.Media;
using SmellsLikeTech.Converter.Core.Operations;
using SmellsLikeTech.Converter.Core.Options;

namespace SmellsLikeTech.Converter.Bridge.Hosting;

/// <summary>
/// O servidor que o site enxerga.
///
/// Ele nao converte nada por conta propria: traduz pedido do navegador em job e entrega
/// para a mesma fila que o aplicativo desktop usa. Todo o trabalho pesado — FFmpeg,
/// Whisper, SAPI, ImageMagick, PDF e OCR — e exatamente o mesmo codigo.
/// </summary>
public sealed class BridgeServer(AppServices servicos, BridgeOptions opcoes, UiThread interfaceDoUsuario)
    : IAsyncDisposable
{
    /// <summary>Versao do contrato. O site recusa conversar com um numero que nao conhece.</summary>
    public const int Protocolo = 1;

    private readonly JobRegistry registro = new(servicos.Queue);
    private WebApplication? aplicacao;

    public async Task IniciarAsync(CancellationToken cancellationToken)
    {
        var construtor = WebApplication.CreateSlimBuilder();
        construtor.Logging.ClearProviders();
        construtor.WebHost.ConfigureKestrel(servidor =>
        {
            // So loopback. Nunca 0.0.0.0: a maquina do cliente nao vira servidor da rede.
            servidor.Listen(IPAddress.Loopback, opcoes.Port);
            servidor.Limits.MaxRequestBodySize = opcoes.MaxUploadBytes + (16L * 1024 * 1024);
        });
        construtor.Services.AddRouting();

        var guarda = new LocalGuard(opcoes);
        var app = construtor.Build();
        app.Use(guarda.InvokeAsync);

        MapearRotas(app);

        aplicacao = app;
        await app.StartAsync(cancellationToken);
    }

    private void MapearRotas(WebApplication app)
    {
        app.MapGet("/v1/ola", Apresentar);
        app.MapGet("/v1/trabalhos", () => Json(registro.Todos().Select(TrabalhoResposta.De)));
        app.MapGet("/v1/trabalhos/{id:guid}", (Guid id) => EncontrarOuErro(id, TrabalhoResposta.De));
        app.MapGet("/v1/trabalhos/{id:guid}/eventos", TransmitirEventos);
        app.MapGet("/v1/trabalhos/{id:guid}/arquivo/{indice:int}", Baixar);

        // O cast e necessario: sem ele o compilador liga o metodo como RequestDelegate,
        // que devolve Task e joga o IResult fora — a resposta sairia vazia.
        app.MapPost("/v1/trabalhos", (Delegate)CriarPorEnvio);
        app.MapPost("/v1/trabalhos/caminho", (Delegate)CriarPorCaminho);
        app.MapPost("/v1/trabalhos/{id:guid}/cancelar", Cancelar);
        app.MapPost("/v1/escolher", (Delegate)EscolherArquivo);
        app.MapPost("/v1/abrir-pasta", AbrirPasta);
    }

    // ==================== apresentacao ====================

    private IResult Apresentar()
    {
        var hardware = servicos.Hardware;
        var modelos = servicos.Models.ListModels()
            .Where(modelo => modelo.IsInstalled)
            .Select(modelo => modelo.Id)
            .ToArray();
        var vozes = servicos.TtsEngine.ListVoices().Select(voz => voz.DisplayName).ToArray();
        var ffmpeg = servicos.FfmpegAvailable;

        return Json(new ApresentacaoResposta
        {
            Versao = typeof(BridgeServer).Assembly.GetName().Version?.ToString(3) ?? "0.1.0",
            Protocolo = Protocolo,
            PastaDeSaida = servicos.OutputDirectory,
            Maquina = new MaquinaResumo
            {
                Processador = hardware.CpuName,
                Nucleos = hardware.LogicalCores,
                PlacaDeVideo = hardware.Gpus.FirstOrDefault() ?? "não identificada",
                AceleradoresDeVideo = hardware.HardwareEncoders,
                FfmpegPresente = ffmpeg,
                ModelosDeTranscricao = modelos,
                VozesInstaladas = vozes,
                IdsDasVozes = servicos.TtsEngine.ListVoices().Select(voz => voz.Id).ToArray()
            },
            Operacoes = [.. OperationCatalog.All.Select(operacao => Descrever(operacao, ffmpeg, modelos, vozes))]
        });
    }

    /// <summary>
    /// Uma operacao so aparece como disponivel quando esta maquina tem o que ela precisa.
    /// Prometer transcricao sem modelo instalado seria empurrar o erro para o usuario.
    /// </summary>
    private static OperacaoResumo Descrever(
        OperationDescriptor operacao,
        bool ffmpeg,
        IReadOnlyList<string> modelos,
        IReadOnlyList<string> vozes)
    {
        string? impedimento = null;

        if (!operacao.Available)
        {
            impedimento = operacao.Milestone is null ? "ainda não implementada" : $"prevista para o {operacao.Milestone}";
        }
        else if (PrecisaDeFfmpeg(operacao.Id) && !ffmpeg)
        {
            impedimento = "o FFmpeg não foi encontrado neste computador";
        }
        else if (operacao.Id == OperationIds.SpeechTranscribe && modelos.Count == 0)
        {
            impedimento = "nenhum modelo de transcrição instalado";
        }
        else if (operacao.Id == OperationIds.SpeechSynthesize && vozes.Count == 0)
        {
            impedimento = "pacote de vozes Kokoro-82M ausente; reinstale o plugin completo";
        }

        return new OperacaoResumo
        {
            Id = operacao.Id,
            Titulo = operacao.Title,
            Descricao = operacao.Description,
            Entradas = [.. operacao.InputKinds.Select(tipo => tipo.ToString().ToLowerInvariant())],
            Saidas = operacao.OutputFormats,
            Disponivel = impedimento is null,
            Impedimento = impedimento
        };
    }

    private static bool PrecisaDeFfmpeg(string operacao) =>
        operacao is OperationIds.VideoConvert
            or OperationIds.VideoExtractAudio
            or OperationIds.VideoToGif
            or OperationIds.VideoToFrames
            or OperationIds.AudioConvert
            or OperationIds.SpeechTranscribe
            or OperationIds.SpeechSynthesize;

    // ==================== criacao de jobs ====================

    /// <summary>Arquivo que veio pelo navegador: e gravado em disco antes de virar job.</summary>
    private async Task<IResult> CriarPorEnvio(HttpContext contexto)
    {
        var recepcao = Path.Combine(servicos.Paths.Temp, "recebidos", Guid.NewGuid().ToString("N"));

        try
        {
            var envio = await UploadReader.LerAsync(
                contexto.Request, recepcao, opcoes.MaxUploadBytes, contexto.RequestAborted);

            var id = await EnfileirarAsync(
                envio.Caminho,
                envio.OpcoesJson,
                envio.NomeDeSaida ?? Path.GetFileNameWithoutExtension(envio.NomeOriginal),
                contexto.RequestAborted,
                envio.CaminhosExtras);

            registro.RegistrarEnvio(id, envio.Caminho);
            foreach (var extra in envio.CaminhosExtras)
            {
                registro.RegistrarEnvio(id, extra);
            }
            return Json(TrabalhoResposta.De(registro.Encontrar(id)!), StatusCodes.Status202Accepted);
        }
        catch (ConversionException excecao)
        {
            return Erro(excecao.Code, excecao.Message, StatusCodes.Status400BadRequest);
        }
        catch (OperationCanceledException)
        {
            return Erro("envio_cancelado", "O envio foi interrompido.", StatusCodesExtra.ClienteDesistiu);
        }
    }

    /// <summary>
    /// Arquivo que ja esta no disco desta maquina, escolhido pelo seletor nativo.
    /// Nada e copiado: e o caminho mais rapido para video grande.
    /// </summary>
    private async Task<IResult> CriarPorCaminho(HttpContext contexto)
    {
        try
        {
            var pedido = await JsonSerializer.DeserializeAsync<TrabalhoPorCaminhoPedido>(
                contexto.Request.Body, BridgeJson.Options, contexto.RequestAborted);

            if (pedido is null || string.IsNullOrWhiteSpace(pedido.Caminho))
            {
                return Erro("caminho_ausente", "Informe o caminho do arquivo.", StatusCodes.Status400BadRequest);
            }

            var extras = pedido.Caminhos ?? [];
            foreach (var caminho in extras.Prepend(pedido.Caminho))
            {
                if (!File.Exists(caminho))
                {
                    return Erro("arquivo_nao_encontrado", "O arquivo não existe mais neste computador.",
                        StatusCodes.Status404NotFound);
                }
            }

            var id = await EnfileirarAsync(
                pedido.Caminho,
                pedido.Opcoes.GetRawText(),
                pedido.NomeDeSaida ?? SafeFileName.FromPath(pedido.Caminho),
                contexto.RequestAborted,
                extras);

            return Json(TrabalhoResposta.De(registro.Encontrar(id)!), StatusCodes.Status202Accepted);
        }
        catch (JsonException)
        {
            return Erro("json_invalido", "O corpo do pedido não é um JSON válido.", StatusCodes.Status400BadRequest);
        }
        catch (ConversionException excecao)
        {
            return Erro(excecao.Code, excecao.Message, StatusCodes.Status400BadRequest);
        }
    }

    private async Task<Guid> EnfileirarAsync(
        string caminho,
        string opcoesJson,
        string nomeDeSaida,
        CancellationToken cancellationToken,
        IReadOnlyList<string>? caminhosExtras = null)
    {
        var opcoesDoJob = JobOptionsJson.Deserialize(opcoesJson)
            ?? throw new ConversionException("opcoes_invalidas", "As opções enviadas não formam uma conversão válida.");
        opcoesDoJob.Validate();

        MediaInfo entrada;
        try
        {
            entrada = await servicos.Inspector.InspectAsync(caminho, cancellationToken);
        }
        catch (ConversionException)
        {
            throw;
        }
        catch (Exception)
        {
            throw new ConversionException("arquivo_ilegivel", "Não foi possível ler este arquivo.");
        }

        var operacao = OperationCatalog.Find(opcoesDoJob.Operation);
        if (operacao is not null && !operacao.Accepts(entrada))
        {
            throw new ConversionException(
                "entrada_incompativel",
                $"“{operacao.Title}” não se aplica a este arquivo.");
        }

        // Os arquivos extras (juntar PDF, imagens para PDF) passam pela mesma inspecao
        // que o primeiro: um nome .pdf nao prova nada.
        var extras = new List<string>();
        foreach (var extra in caminhosExtras ?? [])
        {
            MediaInfo informacao;
            try
            {
                informacao = await servicos.Inspector.InspectAsync(extra, cancellationToken);
            }
            catch (Exception excecao) when (excecao is not ConversionException)
            {
                throw new ConversionException("arquivo_ilegivel", $"Não foi possível ler “{Path.GetFileName(extra)}”.");
            }

            if (operacao is not null && !operacao.Accepts(informacao))
            {
                throw new ConversionException("entrada_incompativel", $"“{operacao.Title}” não se aplica a “{Path.GetFileName(extra)}”.");
            }

            extras.Add(extra);
        }

        var job = new ConversionJob
        {
            Options = opcoesDoJob,
            InputPath = caminho,
            Input = entrada,
            ExtraInputPaths = extras,
            OutputDirectory = servicos.OutputDirectory,
            OutputBaseName = SafeFileName.Sanitize(nomeDeSaida)
        };

        return servicos.Queue.Enqueue(job);
    }

    // ==================== acompanhamento ====================

    /// <summary>
    /// Progresso ao vivo. Enquanto o FFmpeg trabalha, cada mudanca vira uma linha aqui —
    /// e a barra do site anda junto com a da maquina.
    /// </summary>
    private async Task TransmitirEventos(HttpContext contexto, Guid id)
    {
        var retrato = registro.Encontrar(id);
        if (retrato is null)
        {
            contexto.Response.StatusCode = StatusCodes.Status404NotFound;
            return;
        }

        contexto.Response.Headers.ContentType = "text/event-stream";
        contexto.Response.Headers["X-Accel-Buffering"] = "no";

        var fila = new Queue<JobSnapshot>();
        var aviso = new SemaphoreSlim(0);

        void AoMudar(object? remetente, JobSnapshot mudanca)
        {
            if (mudanca.Id != id)
            {
                return;
            }

            lock (fila)
            {
                fila.Enqueue(mudanca);
            }

            aviso.Release();
        }

        registro.Changed += AoMudar;
        try
        {
            await EscreverEventoAsync(contexto, retrato);

            while (!retrato.Status.IsTerminal() && !contexto.RequestAborted.IsCancellationRequested)
            {
                await aviso.WaitAsync(TimeSpan.FromSeconds(15), contexto.RequestAborted);

                JobSnapshot? proximo = null;
                lock (fila)
                {
                    while (fila.Count > 0)
                    {
                        proximo = fila.Dequeue();
                    }
                }

                // Sem novidade em quinze segundos: manda um comentario para a conexao
                // nao ser derrubada por inatividade.
                if (proximo is null)
                {
                    await contexto.Response.WriteAsync(": ping\n\n", contexto.RequestAborted);
                    await contexto.Response.Body.FlushAsync(contexto.RequestAborted);
                    continue;
                }

                retrato = proximo;
                await EscreverEventoAsync(contexto, retrato);
            }
        }
        catch (OperationCanceledException)
        {
            // O site fechou a conexao: normal.
        }
        finally
        {
            registro.Changed -= AoMudar;
            aviso.Dispose();
        }
    }

    private static async Task EscreverEventoAsync(HttpContext contexto, JobSnapshot retrato)
    {
        var corpo = JsonSerializer.Serialize(TrabalhoResposta.De(retrato), BridgeJson.Options);
        await contexto.Response.WriteAsync($"data: {corpo}\n\n", contexto.RequestAborted);
        await contexto.Response.Body.FlushAsync(contexto.RequestAborted);
    }

    private IResult Cancelar(Guid id) =>
        servicos.Queue.Cancel(id)
            ? Results.NoContent()
            : Erro("trabalho_nao_encontrado", "Este trabalho não está mais na fila.", StatusCodes.Status404NotFound);

    private IResult Baixar(Guid id, int indice)
    {
        var retrato = registro.Encontrar(id);
        if (retrato is null)
        {
            return Erro("trabalho_nao_encontrado", "Trabalho desconhecido.", StatusCodes.Status404NotFound);
        }

        if (indice < 0 || indice >= retrato.OutputFiles.Count)
        {
            return Erro("arquivo_nao_encontrado", "Este trabalho não produziu esse arquivo.",
                StatusCodes.Status404NotFound);
        }

        var caminho = retrato.OutputFiles[indice];
        if (!File.Exists(caminho))
        {
            return Erro("arquivo_removido", "O arquivo não está mais no disco.", StatusCodes.Status410Gone);
        }

        return Results.File(caminho, TipoDoArquivo(caminho), Path.GetFileName(caminho), enableRangeProcessing: true);
    }

    // ==================== integracao com o Windows ====================

    /// <summary>
    /// Abre a caixa de selecao do proprio Windows.
    ///
    /// E o que o navegador nao consegue fazer: devolver o caminho do arquivo no disco.
    /// Com ele, um video de dez gigabytes entra na fila instantaneamente, sem copia.
    /// </summary>
    private async Task<IResult> EscolherArquivo(HttpContext contexto)
    {
        string? escolhido;

        // Um plugin sem tela so tem o log para contar o que aconteceu. Este caminho passa
        // por COM, por thread de interface e por uma janela que ninguem ve: quando falha,
        // sem estas linhas nao ha por onde comecar.
        servicos.Log.Write(LogChannel.App, "Seletor: pedido recebido.");

        // A caixa precisa ser trazida para a frente ENQUANTO esta aberta.
        //
        // Quem abre e um processo sem janela nenhuma, e o primeiro plano pertence ao
        // navegador: o Windows deixa a caixa nascer atras de tudo. E como PickFile e
        // modal e so retorna depois que a pessoa fecha, nao adianta empurrar a janela
        // para frente ali — nesse ponto ela ja se foi. Por isso um vigia em paralelo.
        using var vigia = new CancellationTokenSource(TimeSpan.FromSeconds(20));
        var aparecer = Task.Run(() => TrazerACaixaParaFrenteAsync(vigia.Token), CancellationToken.None);

        try
        {
            escolhido = await interfaceDoUsuario.ExecutarAsync(dona =>
            {
                servicos.Log.Write(LogChannel.App, $"Seletor: abrindo na thread de interface (dona {dona:X}).");
                var caminho = NativeFileDialog.PickFile(dona, "Escolha o arquivo para converter", FiltrosDeEntrada);
                servicos.Log.Write(LogChannel.App, $"Seletor: fechou · {(caminho is null ? "cancelado" : "escolhido")}.");
                return caminho;
            });
        }
        catch (ObjectDisposedException)
        {
            return Erro("plugin_encerrando", "O plugin está sendo desligado.",
                StatusCodes.Status503ServiceUnavailable);
        }
        catch (Exception excecao) when (excecao is System.Runtime.InteropServices.COMException or InvalidOperationException)
        {
            servicos.Log.Error("seletor_falhou", "A caixa de seleção não pôde ser aberta.", excecao);
            return Erro("seletor_falhou", "Não foi possível abrir a caixa de seleção do Windows.",
                StatusCodes.Status500InternalServerError);
        }
        finally
        {
            await vigia.CancelAsync();
            await aparecer;
        }

        if (escolhido is null)
        {
            return Results.NoContent();
        }

        var informacao = new FileInfo(escolhido);
        return Json(new ArquivoEscolhido
        {
            Caminho = escolhido,
            Nome = informacao.Name,
            Bytes = informacao.Length
        });
    }

    private IResult AbrirPasta()
    {
        try
        {
            var pasta = servicos.OutputDirectory;
            Directory.CreateDirectory(pasta);
            using var processo = System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo
            {
                FileName = pasta,
                UseShellExecute = true
            });
            return Results.NoContent();
        }
        catch (Exception excecao) when (excecao is IOException or System.ComponentModel.Win32Exception)
        {
            return Erro("pasta_nao_abriu", "Não foi possível abrir a pasta de resultados.",
                StatusCodes.Status500InternalServerError);
        }
    }

    /// <summary>
    /// Procura a caixa recém-aberta e a coloca por cima, sem parar de tentar.
    ///
    /// Roda numa thread à parte porque a thread de interface fica presa no laço modal da
    /// própria caixa. Insiste algumas vezes: entre o pedido e a janela existir passam
    /// alguns décimos, e o Windows pode reposicionar a janela depois de criada.
    /// </summary>
    private async Task TrazerACaixaParaFrenteAsync(CancellationToken cancellationToken)
    {
        var apareceu = false;

        try
        {
            while (!cancellationToken.IsCancellationRequested)
            {
                await Task.Delay(120, cancellationToken);

                var caixa = NativeWindows.EncontrarCaixaDeDialogo();
                if (caixa == nint.Zero)
                {
                    continue;
                }

                // Empurrado pela thread que é dona da janela, e não por uma do pool: o
                // laço modal da própria caixa despacha este trabalho, e o Windows é menos
                // avesso a dar o foco para quem já é dono da janela.
                await interfaceDoUsuario.ExecutarAsync(_ =>
                {
                    NativeWindows.TrazerParaFrente(caixa);
                    return true;
                });

                if (!apareceu)
                {
                    apareceu = true;
                    servicos.Log.Write(LogChannel.App, $"Seletor: caixa {caixa:X} trazida para a frente.");
                }

                // Continua vigiando: se outra janela roubar o topo, ela volta.
                await Task.Delay(600, cancellationToken);
            }
        }
        catch (OperationCanceledException)
        {
            if (!apareceu)
            {
                servicos.Log.Write(LogChannel.App, "Seletor: encerrado sem achar a caixa.");
            }
        }
    }

    private static IReadOnlyList<FileDialogFilter> FiltrosDeEntrada { get; } =
    [
        new("Todos os arquivos suportados", [
            .. FormatCatalog.VideoFormats,
            .. FormatCatalog.AudioFormats,
            .. FormatCatalog.ImageFormats,
            "pdf"
        ]),
        new("Vídeo", FormatCatalog.VideoFormats),
        new("Áudio", FormatCatalog.AudioFormats),
        new("Imagem", FormatCatalog.ImageFormats),
        new("PDF", ["pdf"]),
        new("Todos os arquivos", [])
    ];

    private static string TipoDoArquivo(string caminho) => Path.GetExtension(caminho).ToLowerInvariant() switch
    {
        ".mp3" => "audio/mpeg",
        ".wav" => "audio/wav",
        ".ogg" or ".opus" => "audio/ogg",
        ".m4a" or ".aac" => "audio/mp4",
        ".flac" => "audio/flac",
        ".mp4" or ".m4v" => "video/mp4",
        ".mkv" => "video/x-matroska",
        ".webm" => "video/webm",
        ".mov" => "video/quicktime",
        ".gif" => "image/gif",
        ".png" => "image/png",
        ".jpg" or ".jpeg" => "image/jpeg",
        ".webp" => "image/webp",
        ".txt" => "text/plain; charset=utf-8",
        ".md" => "text/markdown; charset=utf-8",
        ".srt" => "application/x-subrip",
        ".vtt" => "text/vtt; charset=utf-8",
        ".html" => "text/html; charset=utf-8",
        ".pdf" => "application/pdf",
        ".docx" => "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        _ => "application/octet-stream"
    };

    // ==================== utilidades ====================

    private IResult EncontrarOuErro<T>(Guid id, Func<JobSnapshot, T> projetar)
    {
        var retrato = registro.Encontrar(id);
        return retrato is null
            ? Erro("trabalho_nao_encontrado", "Trabalho desconhecido.", StatusCodes.Status404NotFound)
            : Json(projetar(retrato));
    }

    private static IResult Json<T>(T valor, int status = StatusCodes.Status200OK) =>
        Results.Json(valor, BridgeJson.Options, statusCode: status);

    private static IResult Erro(string codigo, string mensagem, int status) =>
        Results.Json(new ErroResposta(codigo, mensagem), BridgeJson.Options, statusCode: status);

    public async ValueTask DisposeAsync()
    {
        registro.Dispose();
        if (aplicacao is not null)
        {
            await aplicacao.StopAsync(TimeSpan.FromSeconds(5));
            await aplicacao.DisposeAsync();
        }
    }
}

/// <summary>Codigo que o ASP.NET nao define, mas que o site entende como "cliente desistiu".</summary>
internal static class StatusCodesExtra
{
    public const int ClienteDesistiu = 499;
}
