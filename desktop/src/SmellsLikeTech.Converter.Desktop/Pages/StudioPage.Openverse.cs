using System.Net;
using System.Security.Cryptography;
using System.Text.Json;
using System.Text.Json.Nodes;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Automation;
using SmellsLikeTech.Converter.Core.Studio;
using SmellsLikeTech.Converter.Desktop.Services;
using Windows.Media.Core;
using Windows.Media.Playback;
using Windows.Storage;

namespace SmellsLikeTech.Converter.Desktop.Pages;

public sealed partial class StudioPage
{
    private const string OpenverseEndpoint = "https://converter.smellsliketech.com.br/api/estudio/openverse";
    private const int MaxOpenverseBytes = 80 * 1024 * 1024;
    private static readonly HttpClient OpenverseHttp = new(new HttpClientHandler { AllowAutoRedirect = false, UseCookies = false });

    private FrameworkElement OpenverseControls()
    {
        var button = Action("Músicas grátis");
        button.Click += async (_, _) =>
        {
            try { await ShowOpenverseAsync(); }
            catch (Exception e) { ShowStatus(e.Message, InfoBarSeverity.Error); }
        };
        return button;
    }

    private FrameworkElement OpenverseCredits()
    {
        var button = Action("Salvar créditos do Openverse");
        button.Click += async (_, _) =>
        {
            var tracks = project?.Audios.Where(a => a.Openverse is { ValueKind: JsonValueKind.Object }).Select(a => a.Openverse!.Value).ToArray();
            if (tracks is null || tracks.Length == 0 || FileDialogs.PickOutputFolder(App.MainWindow) is not { } folder) return;
            try
            {
                var file = Path.Combine(folder, $"creditos-openverse-{DateTime.Now:yyyyMMdd-HHmmss}-{Guid.NewGuid().ToString("N")[..6]}.json");
                await File.WriteAllTextAsync(file, JsonSerializer.Serialize(new { faixas = tracks, fonte = "Openverse", endosso = false }, new JsonSerializerOptions { WriteIndented = true }));
                ShowStatus($"Créditos salvos em {file}", InfoBarSeverity.Success);
            }
            catch (Exception e) { ShowStatus(e.Message, InfoBarSeverity.Error); }
        };
        return button;
    }

    private void EditOpenverseAudioSegment(string id, bool split)
    {
        var audio = project?.Audios.FirstOrDefault(a => a.Id == id);
        if (audio is null) return;
        var at = audio.At ?? 0;
        var end = at + audio.End - audio.Start;
        var cursor = TimeSlider.Value;
        var newId = NewId("m");
        if (split)
        {
            if (cursor - at < .05 || end - cursor < .05)
            {
                ShowStatus("Posicione o cursor dentro do trecho para dividir.", InfoBarSeverity.Warning);
                return;
            }
            var cut = audio.Start + cursor - at;
            var first = audio with { End = cut, FadeOut = 0 };
            var second = audio with { Id = newId, Start = cut, At = cursor, FadeIn = 0 };
            openId = newId;
            ChangeAndRefresh(p => p with { Audios = p.Audios.SelectMany(a => a.Id == id ? new[] { first, second } : new[] { a }).ToList() });
        }
        else
        {
            if (end >= Total)
            {
                ShowStatus("O trecho já chega ao fim do vídeo.", InfoBarSeverity.Warning);
                return;
            }
            var copy = audio with { Id = newId, At = end, End = Math.Min(audio.End, audio.Start + Total - end) };
            openId = newId;
            ChangeAndRefresh(p => p with { Audios = [.. p.Audios, copy] });
        }
    }

    private static async Task<JsonElement> OpenverseJsonAsync(string query, CancellationToken token)
    {
        using var response = await OpenverseHttp.GetAsync($"{OpenverseEndpoint}?{query}", token);
        using var json = JsonDocument.Parse(await response.Content.ReadAsStreamAsync(token));
        if (!response.IsSuccessStatusCode)
        {
            var message = json.RootElement.TryGetProperty("erro", out var error) ? error.GetString() : null;
            throw new IOException(message ?? "A biblioteca musical está indisponível.");
        }
        return json.RootElement.Clone();
    }

    private static async Task DownloadOpenverseAsync(JsonElement item, Uri uri, string file, Action<long> progress, CancellationToken token)
    {
        using var request = new HttpRequestMessage(HttpMethod.Get, uri);
        request.Headers.UserAgent.ParseAdd("SmellsLikeTechConverter/1.0 (Windows; https://converter.smellsliketech.com.br)");
        using var response = await OpenverseHttp.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, token);
        if (response.StatusCode != HttpStatusCode.OK) throw new IOException("A origem da faixa está indisponível.");
        if (response.Content.Headers.ContentLength > MaxOpenverseBytes) throw new IOException("O arquivo excede o limite de 80 MiB.");
        var mime = response.Content.Headers.ContentType?.MediaType ?? "";
        if (!mime.StartsWith("audio/", StringComparison.OrdinalIgnoreCase) && mime is not ("application/ogg" or "application/octet-stream"))
            throw new IOException("A origem devolveu um arquivo que não é áudio.");
        await using var input = await response.Content.ReadAsStreamAsync(token);
        await using var output = File.Create(file);
        using var hash = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
        var buffer = new byte[65536];
        long total = 0;
        int read;
        while ((read = await input.ReadAsync(buffer, token)) > 0)
        {
            total += read;
            if (total > MaxOpenverseBytes) throw new IOException("O arquivo excede o limite de 80 MiB.");
            await output.WriteAsync(buffer.AsMemory(0, read), token);
            hash.AppendData(buffer, 0, read);
            progress(total);
        }
        if (!Convert.ToHexString(hash.GetHashAndReset()).Equals(item.GetProperty("sha256").GetString(), StringComparison.OrdinalIgnoreCase))
            throw new IOException("O arquivo da fonte mudou e precisa de nova revisão.");
    }

    private async Task ShowOpenverseAsync()
    {
        var category = new ComboBox { Header = "Categoria", ItemsSource = new[] { "Músicas", "Efeitos sonoros" }, SelectedIndex = 0 };
        var term = new TextBox { Header = "Pesquisar", MaxLength = 200 };
        var search = Action("Buscar");
        var previous = Action("Anterior");
        var next = Action("Próxima");
        var cancel = Action("Cancelar operação");
        cancel.IsEnabled = false;
        var status = Muted("");
        var results = new StackPanel { Spacing = 10 };
        MediaPlayer? player = null;
        MediaSource? source = null;
        var play = Action("Pausar");
        var seek = new Slider { Minimum = 0, Maximum = 1, StepFrequency = .1, SmallChange = 1, LargeChange = 5, IsEnabled = false };
        AutomationProperties.SetName(seek, "Posição da prévia");
        var clock = Muted("0:00 / 0:00");
        var previewControls = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        previewControls.Children.Add(play);
        previewControls.Children.Add(clock);
        var media = new StackPanel { Visibility = Visibility.Collapsed };
        media.Children.Add(previewControls);
        media.Children.Add(seek);
        var dispatcher = DispatcherQueue;
        var timer = dispatcher.CreateTimer();
        timer.Interval = TimeSpan.FromMilliseconds(200);
        bool updatingSeek = false;
        var controls = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        foreach (var control in new[] { search, previous, next, cancel }) controls.Children.Add(control);
        var body = new StackPanel { Spacing = 10, MinWidth = 360 };
        body.Children.Add(Muted("Busca de músicas disponibilizada com o uso da API Openverse. O Smells Like Tech Converter é independente e não é endossado nem certificado pelo Openverse."));
        body.Children.Add(category);
        body.Children.Add(term);
        body.Children.Add(controls);
        body.Children.Add(status);
        body.Children.Add(media);
        body.Children.Add(new ScrollViewer { Content = results, MaxHeight = 350 });
        var dialog = new ContentDialog { Title = "Músicas grátis", Content = body, CloseButtonText = "Fechar", XamlRoot = XamlRoot };
        CancellationTokenSource? operation = null;
        TaskCompletionSource? finishing = null;
        string? previewFile = null;
        var closed = false;
        int page = 1;
        bool hasNext = false;
        string Category() => category.SelectedIndex == 0 ? "music" : "sound_effect";

        static void DeleteTemporary(string? path)
        {
            if (path is null) return;
            try { File.Delete(path); }
            catch (IOException) { }
            catch (UnauthorizedAccessException) { }
        }

        void StopPreview()
        {
            timer.Stop();
            media.Visibility = Visibility.Collapsed;
            seek.IsEnabled = false;
            var previousPlayer = player;
            var previousSource = source;
            player = null;
            source = null;
            try
            {
                if (previousPlayer is not null)
                {
                    previousPlayer.MediaFailed -= OnMediaFailed;
                    try
                    {
                        if (previousPlayer.PlaybackSession.CanPause) previousPlayer.Pause();
                        previousPlayer.Source = null;
                    }
                    finally { previousPlayer.Dispose(); }
                }
            }
            finally
            {
                try { previousSource?.Dispose(); }
                finally { DeleteTemporary(previewFile); previewFile = null; }
            }
        }

        void PreviewFailed()
        {
            try { StopPreview(); }
            catch (Exception e) { System.Diagnostics.Debug.WriteLine(e); }
            if (!closed) status.Text = "Não foi possível ouvir a faixa.";
        }

        void OnMediaFailed(MediaPlayer sender, MediaPlayerFailedEventArgs _) => dispatcher.TryEnqueue(() =>
        {
            if (!closed && ReferenceEquals(player, sender)) PreviewFailed();
        });

        static string TimeLabel(TimeSpan time) => time.ToString(@"m\:ss");
        timer.Tick += (_, _) =>
        {
            if (closed || player is null) return;
            try
            {
                var session = player.PlaybackSession;
                var length = session.NaturalDuration;
                var position = session.Position;
                updatingSeek = true;
                seek.Maximum = Math.Max(.1, length.TotalSeconds);
                seek.Value = Math.Clamp(position.TotalSeconds, 0, seek.Maximum);
                seek.IsEnabled = session.CanSeek && length > TimeSpan.Zero;
                clock.Text = $"{TimeLabel(position)} / {TimeLabel(length)}";
                play.Content = session.PlaybackState is MediaPlaybackState.Playing or MediaPlaybackState.Buffering ? "Pausar" : "Reproduzir";
            }
            catch (Exception) { PreviewFailed(); }
            finally { updatingSeek = false; }
        };
        play.Click += (_, _) =>
        {
            if (closed || player is null) return;
            try
            {
                var session = player.PlaybackSession;
                if (session.PlaybackState is MediaPlaybackState.Playing or MediaPlaybackState.Buffering) player.Pause();
                else
                {
                    if (session.CanSeek && session.NaturalDuration > TimeSpan.Zero && session.Position >= session.NaturalDuration)
                        session.Position = TimeSpan.Zero;
                    player.Play();
                }
            }
            catch (Exception) { PreviewFailed(); }
        };
        seek.ValueChanged += (_, e) =>
        {
            if (updatingSeek || !seek.IsEnabled || closed || player is null) return;
            try { player.PlaybackSession.Position = TimeSpan.FromSeconds(e.NewValue); }
            catch (Exception) { PreviewFailed(); }
        };

        void UpdateButtons()
        {
            search.IsEnabled = operation is null;
            previous.IsEnabled = operation is null && page > 1;
            next.IsEnabled = operation is null && hasNext;
            cancel.IsEnabled = operation is not null;
            category.IsEnabled = operation is null;
            term.IsEnabled = operation is null;
            results.IsHitTestVisible = operation is null;
        }

        async Task RunAsync(Func<CancellationToken, Task> action, int seconds)
        {
            if (operation is not null || closed) return;
            using var current = new CancellationTokenSource(TimeSpan.FromSeconds(seconds));
            operation = current;
            var done = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
            finishing = done;
            UpdateButtons();
            try { await action(current.Token); }
            catch (OperationCanceledException) { if (!closed) status.Text = "Operação cancelada ou tempo de resposta excedido."; }
            catch (Exception e) { if (!closed) status.Text = e.Message; }
            finally
            {
                operation = null;
                finishing = null;
                done.SetResult();
                if (!closed) UpdateButtons();
            }
        }

        Task ObtainAsync(string id, string action) => RunAsync(async token =>
        {
            var insert = action == "adicionar";
            var download = action == "baixar";
            var destination = download ? FileDialogs.PickOutputFolder(App.MainWindow) : null;
            if (download && destination is null) return;
            if (!insert && !download) StopPreview();
            var original = project;
            var position = Math.Max(0, Math.Min(TimeSlider.Value, Total - 1d / (original?.Fps ?? 30)));
            status.Text = "Conferindo a faixa…";
            var item = await OpenverseJsonAsync($"category={Category()}&id={id}", token);
            if (item.GetProperty("estado").GetString() != "APPROVED" || item.GetProperty("id").GetString() != id)
                throw new IOException("A faixa precisa de nova revisão.");
            var uri = new Uri(item.GetProperty("midia").GetString()!);
            var extension = Path.GetExtension(uri.AbsolutePath).ToLowerInvariant();
            if (uri.Scheme != "https" || !uri.IsDefaultPort || uri.UserInfo.Length != 0 || uri.Host != "upload.wikimedia.org" ||
                !uri.AbsolutePath.StartsWith("/wikipedia/commons/", StringComparison.Ordinal) ||
                extension is not (".mp3" or ".wav" or ".ogg" or ".oga"))
                throw new IOException("A origem ou o formato da faixa é incompatível.");
            var folder = Path.Combine(insert ? App.Services.Paths.Root : App.Services.Paths.Temp, "estudio", "openverse");
            Directory.CreateDirectory(folder);
            var title = item.GetProperty("titulo").GetString() ?? "Áudio";
            var safe = string.Concat(title.Take(70).Select(c => Path.GetInvalidFileNameChars().Contains(c) ? '_' : c)).Trim(' ', '.');
            if (safe.Length == 0) safe = "Áudio";
            var file = Path.Combine(folder, $"{safe}-{Guid.NewGuid():N}{extension}");
            var pending = Path.Combine(folder, $"{Guid.NewGuid():N}.parcial{extension}");
            var keep = false;
            string? wav = null;
            string? partialDownload = null;
            try
            {
                await DownloadOpenverseAsync(item, uri, pending, bytes => status.Text = $"Baixando áudio · {bytes / 1048576d:0.0} MiB", token);
                var inspected = await App.Services.Inspector.InspectAsync(pending, token);
                var duration = inspected.Duration?.TotalSeconds ?? 0;
                if (!inspected.HasAudio || !double.IsFinite(duration) || duration is <= 0 or > 900)
                    throw new IOException("Formato incompatível ou duração fora do limite de 15 minutos.");
                token.ThrowIfCancellationRequested();
                File.Move(pending, file);
                if (insert)
                {
                    if (original is null || !ReferenceEquals(original, project))
                        throw new IOException("O projeto mudou durante a importação. Tente novamente.");
                    var origin = JsonNode.Parse(item.GetRawText())!.AsObject();
                    origin["importadoEm"] = DateTimeOffset.UtcNow.ToString("O");
                    var length = Total > 0 ? Math.Min(duration, Total - position) : duration;
                    var audio = new StudioAudio
                    {
                        Id = NewId("ov"), File = file, At = position, Start = 0, End = length,
                        Volume = .8, FadeIn = 0, FadeOut = Math.Min(1, length),
                        Openverse = JsonSerializer.SerializeToElement(origin),
                    };
                    infos[file] = inspected;
                    openId = audio.Id;
                    try { ChangeAndRefresh(p => p with { Audios = [.. p.Audios, audio] }); }
                    finally { keep = project?.Audios.Any(a => a.Id == audio.Id) == true; }
                    status.Text = $"Adicionado ao projeto: {title}";
                    ShowStatus("Faixa adicionada à linha do tempo.", InfoBarSeverity.Success);
                }
                else if (destination is not null)
                {
                    partialDownload = Path.Combine(destination, $".{Guid.NewGuid():N}.parcial");
                    await using (var input = File.OpenRead(file))
                    await using (var output = new FileStream(partialDownload, FileMode.CreateNew))
                        await input.CopyToAsync(output, token);
                    token.ThrowIfCancellationRequested();
                    var saved = Path.Combine(destination, safe + extension);
                    if (File.Exists(saved)) saved = Path.Combine(destination, $"{safe}-{Guid.NewGuid():N}{extension}");
                    File.Move(partialDownload, saved);
                    status.Text = $"Baixado: {Path.GetFileName(saved)}";
                }
                else
                {
                    wav = Path.ChangeExtension(file, ".previa.wav");
                    await App.Services.MusicTools.ImportAsync(file, wav, 48000, token);
                    var storage = await StorageFile.GetFileFromPathAsync(wav);
                    token.ThrowIfCancellationRequested();
                    StopPlayback(keepPosition: true);
                    try
                    {
                        player = new MediaPlayer { AudioCategory = MediaPlayerAudioCategory.Media };
                        player.MediaFailed += OnMediaFailed;
                        source = MediaSource.CreateFromStorageFile(storage);
                        player.Source = source;
                        previewFile = wav;
                        wav = null;
                        seek.Value = 0;
                        clock.Text = $"0:00 / {TimeLabel(TimeSpan.FromSeconds(duration))}";
                        play.Content = "Pausar";
                        media.Visibility = Visibility.Visible;
                        player.Play();
                        timer.Start();
                    }
                    catch
                    {
                        PreviewFailed();
                        throw;
                    }
                    status.Text = title;
                }
            }
            finally
            {
                DeleteTemporary(pending);
                DeleteTemporary(wav);
                DeleteTemporary(partialDownload);
                if (!keep) DeleteTemporary(file);
            }
        }, 65);

        Task SearchAsync(int target) => RunAsync(async token =>
        {
            StopPreview();
            results.Children.Clear();
            status.Text = "Buscando faixas…";
            hasNext = false;
            var result = await OpenverseJsonAsync($"category={Category()}&page={target}&q={Uri.EscapeDataString(term.Text.Trim())}", token);
            token.ThrowIfCancellationRequested();
            page = target;
            hasNext = result.GetProperty("proxima").GetBoolean();
            var items = result.GetProperty("resultados");
            status.Text = items.GetArrayLength() == 0 ? "Nenhuma faixa encontrada. Tente outro termo." : $"Página {page}";
            foreach (var item in items.EnumerateArray())
            {
                var id = item.GetProperty("id").GetString()!;
                var state = item.GetProperty("estado").GetString();
                var row = new StackPanel { Spacing = 4 };
                row.Children.Add(Muted(item.GetProperty("titulo").GetString() ?? "Áudio"));
                var creator = item.GetProperty("autor").GetString();
                row.Children.Add(Muted(string.Join(" · ", new[] { creator, item.GetProperty("fonte").GetString(), state switch
                {
                    "APPROVED" => "CC0 revisada",
                    "BLOCKED" => "Indisponível",
                    "RECHECK" => "Aguardando nova revisão",
                    _ => "Licença em verificação",
                }}.Where(t => !string.IsNullOrEmpty(t)))));
                var links = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 6 };
                links.Children.Add(new HyperlinkButton { Content = "Origem", NavigateUri = new Uri(item.GetProperty("origem").GetString()!) });
                links.Children.Add(new HyperlinkButton { Content = "CC0 1.0", NavigateUri = new Uri(item.GetProperty("licenca").GetString()!) });
                if (state == "APPROVED")
                {
                    var listen = Action("Ouvir");
                    listen.Click += async (_, _) => await ObtainAsync(id, "ouvir");
                    var add = Action("Adicionar ao projeto");
                    add.IsEnabled = project is not null;
                    add.Click += async (_, _) => await ObtainAsync(id, "adicionar");
                    var download = Action("Baixar");
                    download.Click += async (_, _) => await ObtainAsync(id, "baixar");
                    var actions = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 6 };
                    foreach (var button in new[] { listen, add, download }) actions.Children.Add(button);
                    row.Children.Add(actions);
                }
                row.Children.Add(new Expander { Header = "Créditos e licença", Content = links });
                results.Children.Add(row);
            }
        }, 18);

        cancel.Click += (_, _) => operation?.Cancel();
        search.Click += async (_, _) => await SearchAsync(1);
        previous.Click += async (_, _) => await SearchAsync(page - 1);
        next.Click += async (_, _) => await SearchAsync(page + 1);
        category.SelectionChanged += async (_, _) => await SearchAsync(1);
        dialog.Opened += async (_, _) => await SearchAsync(1);
        try
        {
            UpdateButtons();
            await dialog.ShowAsync();
        }
        finally
        {
            closed = true;
            operation?.Cancel();
            try { StopPreview(); }
            finally { if (finishing is { } done) await done.Task; }
        }
    }
}
