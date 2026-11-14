using System.Net.Http;
using System.Security.Cryptography;
using Microsoft.ML.OnnxRuntime;
using Microsoft.ML.OnnxRuntime.Tensors;
using SmellsLikeTech.Converter.Core;
using SmellsLikeTech.Converter.Core.Music;
using SmellsLikeTech.Converter.Infrastructure.Execution;

namespace SmellsLikeTech.Converter.Engine.Music;

public sealed record MusicProjectSummary(string Id, string Title, int Tracks, DateTime Updated);

public sealed record MusicExportRequest(string Format, string Master, bool OnlyLoop, bool EachTrack, bool Tail, string? Track = null);

public sealed record MusicExported(string Path, MusicMeasures? Measures, double? GainDb);

/// <summary>
/// Onde a criacao musical do Windows guarda os projetos: &lt;dados&gt;/musica/&lt;id&gt;/projeto.json e
/// midias/&lt;id&gt;.wav (float32 na taxa do projeto). O projeto e gravado num temporario e trocado
/// de uma vez; a midia importada nunca e alterada.
/// </summary>
public sealed class MusicLibrary(string root)
{
    public string Root { get; } = Path.Combine(root, "musica");

    public string Folder(string projectId) => Path.Combine(Root, projectId);

    public string MediaPath(string projectId, string mediaId) => Path.Combine(Folder(projectId), "midias", $"{mediaId}.wav");

    public IReadOnlyList<MusicProjectSummary> List()
    {
        if (!Directory.Exists(Root)) return [];
        var list = new List<MusicProjectSummary>();
        foreach (var folder in Directory.EnumerateDirectories(Root))
        {
            try
            {
                var project = Load(Path.GetFileName(folder));
                list.Add(new MusicProjectSummary(project.Id, project.Title, project.Tracks.Count, File.GetLastWriteTime(Path.Combine(folder, "projeto.json"))));
            }
            catch (Exception e) when (e is IOException or ConversionException or System.Text.Json.JsonException)
            {
            }
        }

        return [.. list.OrderByDescending(p => p.Updated)];
    }

    public MusicProject Load(string id)
    {
        var folder = Folder(id);
        var path = Path.Combine(folder, "projeto.json");
        if (!File.Exists(path) && File.Exists(path + ".tmp")) path += ".tmp";
        return MusicProject.Parse(File.ReadAllText(path));
    }

    public void Save(MusicProject project)
    {
        var folder = Folder(project.Id);
        Directory.CreateDirectory(Path.Combine(folder, "midias"));
        var path = Path.Combine(folder, "projeto.json");
        File.WriteAllText(path + ".tmp", project.ToJson());
        File.Move(path + ".tmp", path, overwrite: true);
    }

    public void Delete(string id) => Directory.Delete(Folder(id), recursive: true);

    /// <summary>WAVs no disco que o projeto nao conhece: tomadas de uma gravacao interrompida.</summary>
    public IReadOnlyList<(string Id, long Frames, int Channels)> LooseMedia(MusicProject project)
    {
        var folder = Path.Combine(Folder(project.Id), "midias");
        if (!Directory.Exists(folder)) return [];
        var known = project.Media.Select(m => m.Id).ToHashSet();
        var loose = new List<(string, long, int)>();
        foreach (var file in Directory.EnumerateFiles(folder, "*.wav"))
        {
            var id = Path.GetFileNameWithoutExtension(file);
            if (known.Contains(id) || new FileInfo(file).Length <= 44) continue;
            try
            {
                using var source = new MappedWavSource(file);
                loose.Add((id, source.Frames, source.Channels));
            }
            catch (Exception e) when (e is IOException or ConversionException)
            {
            }
        }

        return loose;
    }
}

/// <summary>Importacao, exportacao, separacao e limpeza da criacao musical (FFmpeg + nucleo comum).</summary>
public sealed class MusicTools(Func<string> ffmpegPath, ProcessRunner runner, string tempRoot, string modelsRoot)
{
    private async Task FfmpegAsync(IReadOnlyList<string> arguments, CancellationToken token)
    {
        Directory.CreateDirectory(tempRoot);
        var result = await runner.RunAsync(new CommandSpec(ffmpegPath(), arguments), tempRoot, tempRoot, cancellationToken: token);
        if (result.ExitCode != 0)
        {
            var tail = result.StandardError.Length > 400 ? result.StandardError[^400..] : result.StandardError;
            throw new ConversionException("ffmpeg_failed", $"O FFmpeg não conseguiu processar o áudio. {tail.Trim()}");
        }
    }

    /// <summary>Decodifica qualquer audio para WAV float32 na taxa do projeto (mono continua mono; mais de 2 canais vira estereo).</summary>
    public async Task<(long Frames, int Channels)> ImportAsync(string input, string output, int rate, CancellationToken token)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(output)!);
        await FfmpegAsync(["-hide_banner", "-nostdin", "-y", "-i", input, "-vn", "-af", "aformat=channel_layouts=mono|stereo", "-ar", rate.ToString(System.Globalization.CultureInfo.InvariantCulture), "-c:a", "pcm_f32le", output], token);
        using var source = new MappedWavSource(output);
        if (source.Frames > (long)rate * 7200) throw new ConversionException("too_long", "O áudio passa de 2 horas.");
        return (source.Frames, source.Channels);
    }

    /// <summary>Reamostra um WAV float com o FFmpeg (soxr quando houver, senão o padrão).</summary>
    public Task ResampleAsync(string input, string output, int rate, CancellationToken token) =>
        FfmpegAsync(["-hide_banner", "-nostdin", "-y", "-i", input, "-ar", rate.ToString(System.Globalization.CultureInfo.InvariantCulture), "-c:a", "pcm_f32le", output], token);

    /// <summary>A mistura (com master) ou cada faixa, no formato pedido.</summary>
    public async Task<IReadOnlyList<MusicExported>> ExportAsync(MusicProject project, Func<MusicMedia, string> mediaPath, MusicExportRequest request, string folder, IProgress<(string Stage, double Fraction)>? progress, CancellationToken token)
    {
        var format = MusicMastering.Formats.FirstOrDefault(f => f.Id == request.Format) ?? throw new ConversionException("invalid_format", "Formato desconhecido.");
        var useLoop = request.OnlyLoop && project.Loop.End > project.Loop.Start;
        var start = useLoop ? project.Loop.Start : 0;
        var end = useLoop ? project.Loop.End : MusicRules.DurationInFrames(project);
        if (end <= start) throw new ConversionException("empty_project", "Não há nada para exportar: grave ou importe uma faixa primeiro.");
        var frames = (int)(end - start + (request.Tail ? MusicMixer.TailInFrames(project) : 0));
        var title = SafeName(project.Title);
        var targets = request.Track is { } only
            ? project.Tracks.Where(t => t.Id == only).Select(t => (Id: (string?)t.Id, Name: $"{title}-{SafeName(t.Name)}")).ToList()
            : request.EachTrack
                ? project.Tracks.Where(t => t.Clips.Any(c => !c.Mute)).Select(t => (Id: (string?)t.Id, Name: $"{title}-{SafeName(t.Name)}")).ToList()
                : [(Id: (string?)null, Name: title)];
        var results = new List<MusicExported>();
        Directory.CreateDirectory(folder);
        for (var i = 0; i < targets.Count; i++)
        {
            token.ThrowIfCancellationRequested();
            var (id, name) = targets[i];
            progress?.Report((id is null ? "Mixando" : $"Mixando {name}", i / (double)targets.Count));
            var sources = new Dictionary<string, IMusicSource>();
            try
            {
                foreach (var media in project.Media) sources[media.Id] = new MappedWavSource(mediaPath(media));
                foreach (var clip in project.Tracks.SelectMany(t => t.Clips).Where(MusicTransform.IsTransformed).GroupBy(MusicTransform.SourceKey).Select(g => g.First()))
                {
                    token.ThrowIfCancellationRequested();
                    if (!sources.TryGetValue(clip.Media, out var original)) continue;
                    var transformed = MusicTransform.Transform(MusicTransform.ReadAll(original), project.SampleRate, clip.Speed ?? 1, clip.Semitones ?? 0, clip.PreservePitch ?? true);
                    sources[MusicTransform.SourceKey(clip)] = new MemoryMusicSource(transformed);
                }
                var (left, right) = await Task.Run(() => RenderExport(project, sources, start, frames, id, token), token);
                MusicMeasures? measures = null;
                double? gain = null;
                if (id is null)
                {
                    progress?.Report(("Masterizando", (i + 0.8) / targets.Count));
                    if (project.Master.Speed is not (null or 1) || project.Master.Semitones is not (null or 0))
                    {
                        var channels = await Task.Run(() => MusicTransform.Transform([left, right], project.SampleRate,
                            project.Master.Speed ?? 1, project.Master.Semitones ?? 0, project.Master.PreservePitch ?? true), token);
                        (left, right) = (channels[0], channels[1]);
                    }
                    var mastered = await Task.Run(() => MusicMastering.Master(left, right, project.SampleRate, request.Master), token);
                    (left, right, measures, gain) = (mastered.Left, mastered.Right, mastered.After, mastered.GainDb);
                }

                progress?.Report(("Codificando", (i + 0.9) / targets.Count));
                var path = Path.Combine(folder, $"{name}.{format.Extension}");
                if (format.Extension == "wav")
                {
                    await using var stream = File.Create(path);
                    MusicMastering.WriteWav(stream, [left, right], project.SampleRate, format.Bits);
                }
                else
                {
                    var temp = Path.Combine(tempRoot, $"export-{Guid.NewGuid():N}.wav");
                    Directory.CreateDirectory(tempRoot);
                    await using (var stream = File.Create(temp)) MusicMastering.WriteWav(stream, [left, right], project.SampleRate, 32);
                    try
                    {
                        string[] codec = format.Id switch
                        {
                            "flac" => ["-c:a", "flac", "-sample_fmt", "s32", "-bits_per_raw_sample", "24"],
                            "mp3" => ["-c:a", "libmp3lame", "-b:a", "320k"],
                            "aac" => ["-c:a", "aac", "-b:a", "256k"],
                            "opus" => ["-c:a", "libopus", "-b:a", "192k"],
                            _ => throw new ConversionException("invalid_format", "Formato desconhecido.")
                        };
                        await FfmpegAsync(["-hide_banner", "-nostdin", "-y", "-i", temp, .. codec, path], token);
                    }
                    finally
                    {
                        File.Delete(temp);
                    }
                }

                results.Add(new MusicExported(path, measures, gain));
            }
            finally
            {
                foreach (var source in sources.Values) (source as IDisposable)?.Dispose();
            }
        }

        progress?.Report(("Pronto", 1));
        return results;
    }

    private static (float[] Left, float[] Right) RenderExport(MusicProject project,
        Dictionary<string, IMusicSource> sources, long start, int frames, string? trackId, CancellationToken token)
    {
        var efeitos = project.Tracks.SelectMany(faixa => faixa.Effects)
            .Concat(project.Buses.SelectMany(barramento => barramento.Effects)).Concat(project.Master.Effects)
            .Where(efeito => efeito.Kind == "vst3" && efeito.Enabled != false).ToArray();
        if (efeitos.Length == 0)
            return MusicMixer.Render(project, sources, start, frames, trackId, metronome: project.Metronome.InExport);

        if (!MusicNative.HasVst3)
            throw new ConversionException("vst3_unavailable", "A exportação deste projeto precisa do host VST3 do Windows.");

        var instalados = MusicNative.ScanPlugins();
        foreach (var efeito in efeitos)
        {
            if (efeito.Extra is not null && efeito.Extra.TryGetValue("caminho", out var caminho) &&
                efeito.Extra.TryGetValue("classe", out var classe) && caminho.ValueKind == System.Text.Json.JsonValueKind.String &&
                classe.ValueKind == System.Text.Json.JsonValueKind.String && instalados.Any(plugin =>
                    string.Equals(plugin.Path, caminho.GetString(), StringComparison.OrdinalIgnoreCase) &&
                    string.Equals(plugin.ClassId, classe.GetString(), StringComparison.OrdinalIgnoreCase))) continue;
            throw new ConversionException("vst3_missing", "Um VST3 ativo do projeto não foi encontrado. Instale o efeito ou desligue-o antes de exportar.");
        }

        using var mixer = new MusicNative.Mixer(project.SampleRate);
        foreach (var (chave, fonte) in sources)
        {
            token.ThrowIfCancellationRequested();
            if (fonte.Frames == 0) continue;
            var canais = MusicTransform.ReadAll(fonte);
            if (canais.Length == 1) mixer.SetSource(chave, canais[0]);
            else mixer.SetSource(chave, canais[0], canais[1]);
        }
        mixer.SetProject(project);
        var left = new float[frames];
        var right = new float[frames];
        const int bloco = 4096;
        for (var offset = 0; offset < frames; offset += bloco)
        {
            token.ThrowIfCancellationRequested();
            var n = Math.Min(bloco, frames - offset);
            mixer.Render(start + offset, left.AsSpan(offset, n), right.AsSpan(offset, n), trackId,
                metronome: project.Metronome.InExport);
        }
        return (left, right);
    }

    private static string SafeName(string text)
    {
        var clean = new string([.. text.Normalize(System.Text.NormalizationForm.FormD).Where(c => char.IsAsciiLetterOrDigit(c) || c is ' ' or '-' or '_')]).Trim().Replace(' ', '-');
        return clean.Length == 0 ? "musica" : clean.Length > 60 ? clean[..60] : clean;
    }

    public string ModelPath => Path.Combine(modelsRoot, "musica", MusicSeparation.ModelFile);
    private string RoFormerPath => Path.Combine(modelsRoot, "musica", MusicRoFormer.FileName);
    private static readonly SemaphoreSlim ModelDownloads = new(1, 1);

    private static string? ExperimentalModelPath => Environment.GetEnvironmentVariable("SLT_MUSIC_ROFORMER_ONNX");
    private static readonly SemaphoreSlim RoFormerSlot = new(1, 1);
    public static bool HasExperimentalRoFormer => MusicNative.HasComplexStft &&
        ExperimentalModelPath is { Length: > 0 } path && File.Exists(path);
    public static string ExperimentalRoFormerId => MusicRoFormer.Id;
    public static IReadOnlyList<MusicStemDefinition> ExperimentalRoFormerStems => MusicRoFormer.Stems;
    public static bool SupportsRoFormer => MusicNative.HasComplexStft;
    public bool ModelInstalled(bool roformer) => Ready(roformer ? RoFormerPath : ModelPath,
        roformer ? MusicRoFormer.Bytes : MusicSeparation.ModelBytes);

    public async Task DeleteModelAsync(bool roformer, CancellationToken token)
    {
        await ModelDownloads.WaitAsync(token);
        try { File.Delete(roformer ? RoFormerPath : ModelPath); }
        finally { ModelDownloads.Release(); }
    }

    public async Task<IReadOnlyList<(MusicStemDefinition Stem, string Path)>> SeparateRoFormerAsync(
        string input, string outputFolder, int rate, IProgress<(string Stage, double Fraction)>? progress, CancellationToken token)
    {
        string? model = ModelInstalled(true) ? RoFormerPath : ExperimentalModelPath;
        if (!MusicNative.HasComplexStft || string.IsNullOrEmpty(model) || !File.Exists(model))
            throw new ConversionException("model_missing", "O RoFormer experimental não está instalado.");
        progress?.Report(("Conferindo o modelo RoFormer", 0));
        await using var modelFile = File.OpenRead(model);
        if (modelFile.Length != MusicRoFormer.Bytes ||
            Convert.ToHexStringLower(await SHA256.HashDataAsync(modelFile, token)) != MusicRoFormer.Sha256)
            throw new ConversionException("model_mismatch", "O arquivo do RoFormer não confere com o SHA-256 esperado.");

        var work = Path.Combine(tempRoot, $"separacao-{Guid.NewGuid():N}");
        Directory.CreateDirectory(work);
        var results = new List<(MusicStemDefinition Stem, string Path)>();
        var entered = false;
        try
        {
            progress?.Report(("Preparando o áudio", 0));
            var at44 = Path.Combine(work, "entrada.wav");
            await FfmpegAsync(["-hide_banner", "-nostdin", "-y", "-i", input, "-vn", "-ac", "2", "-ar", "44100", "-c:a", "pcm_f32le", at44], token);
            progress?.Report(("Aguardando o motor RoFormer", 0));
            await RoFormerSlot.WaitAsync(token);
            entered = true;
            await Task.Run(() =>
            {
                using var source = new MappedWavSource(at44);
                var writers = new List<StreamingWavWriter>();
                try
                {
                    foreach (var stem in MusicRoFormer.Stems)
                        writers.Add(new StreamingWavWriter(Path.Combine(work, $"{stem.Id}-44k.wav"), 2, MusicSeparation.ModelRate));
                    MusicRoFormer.Separate(source, model, writers, progress, token);
                }
                finally
                {
                    foreach (var writer in writers) writer.Dispose();
                }
            }, token);
            RoFormerSlot.Release();
            entered = false;

            Directory.CreateDirectory(outputFolder);
            foreach (var stem in MusicRoFormer.Stems)
            {
                progress?.Report(($"Voltando {stem.Label} à taxa do projeto", 1));
                var output = Path.Combine(outputFolder, $"{stem.Id}-{Guid.NewGuid():N}.wav");
                results.Add((stem, output));
                await ResampleAsync(Path.Combine(work, $"{stem.Id}-44k.wav"), output, rate, token);
            }
            return results;
        }
        catch
        {
            foreach (var (_, path) in results) File.Delete(path);
            throw;
        }
        finally
        {
            if (entered) RoFormerSlot.Release();
            try { Directory.Delete(work, true); } catch (IOException) { }
        }
    }

    public bool ModelReady => ModelInstalled(false);
    private static bool Ready(string path, long bytes) => new FileInfo(path) is { Exists: true } info && info.Length == bytes;

    private static async Task<bool> VerifyModelAsync(string path, CancellationToken token, bool roformer = false)
    {
        if (!File.Exists(path)) return false;
        await using var file = File.OpenRead(path);
        return file.Length == (roformer ? MusicRoFormer.Bytes : MusicSeparation.ModelBytes) &&
            Convert.ToHexStringLower(await SHA256.HashDataAsync(file, token)) == (roformer ? MusicRoFormer.Sha256 : MusicSeparation.ModelSha256);
    }

    /// <summary>Baixa o modelo na primeira vez e confere o SHA-256 antes de usar.</summary>
    public async Task EnsureModelAsync(HttpClient http, IProgress<double>? progress, CancellationToken token, bool roformer = false)
    {
        var path = roformer ? RoFormerPath : ModelPath;
        var bytes = roformer ? MusicRoFormer.Bytes : MusicSeparation.ModelBytes;
        var sha256 = roformer ? MusicRoFormer.Sha256 : MusicSeparation.ModelSha256;
        var url = roformer ? $"https://converter.smellsliketech.com.br/modelos/{MusicRoFormer.FileName}" : MusicSeparation.ModelUrl;
        var temp = path + $".{Guid.NewGuid():N}.baixando";
        await ModelDownloads.WaitAsync(token);
        try
        {
            if (await VerifyModelAsync(path, token, roformer)) return;
            Directory.CreateDirectory(Path.GetDirectoryName(path)!);
            using var response = await http.GetAsync(url, HttpCompletionOption.ResponseHeadersRead, token);
            response.EnsureSuccessStatusCode();
            using var hash = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
            await using (var input = await response.Content.ReadAsStreamAsync(token))
            await using (var output = File.Create(temp))
            {
                var buffer = new byte[1 << 16];
                long received = 0;
                int read;
                while ((read = await input.ReadAsync(buffer, token)) > 0)
                {
                    received += read;
                    if (received > bytes)
                        throw new ConversionException("model_mismatch", "O modelo de separação é maior que o esperado.");
                    await output.WriteAsync(buffer.AsMemory(0, read), token);
                    hash.AppendData(buffer, 0, read);
                    progress?.Report(received / (double)bytes);
                }
                if (received != bytes || Convert.ToHexStringLower(hash.GetHashAndReset()) != sha256)
                    throw new ConversionException("model_mismatch", "O modelo baixado não confere e foi descartado.");
            }
            for (var attempt = 0; ; attempt++)
            {
                token.ThrowIfCancellationRequested();
                try
                {
                    File.Move(temp, path, overwrite: true);
                    break;
                }
                catch (IOException e) when ((e.HResult & 0xffff) is 32 or 33 && attempt < 10)
                {
                    await Task.Delay(TimeSpan.FromMilliseconds(500), token);
                }
            }
        }
        finally
        {
            try { File.Delete(temp); }
            catch (IOException e) when ((e.HResult & 0xffff) is 32 or 33) { }
            finally { ModelDownloads.Release(); }
        }
    }

    /// <summary>
    /// Separa um WAV da taxa do projeto: converte para 44,1 kHz, roda o modelo em trechos
    /// (memoria limitada), escreve uma faixa por WAV e volta para a taxa do projeto.
    /// Devolve os caminhos, na ordem do modo.
    /// </summary>
    public async Task<IReadOnlyList<(MusicStemDefinition Stem, string Path)>> SeparateAsync(string input, string outputFolder, string mode, int rate, IProgress<(string Stage, double Fraction)>? progress, CancellationToken token, bool refined = false)
    {
        if (!await VerifyModelAsync(ModelPath, token))
            throw new ConversionException("model_mismatch", "O modelo de separação está ausente ou não confere.");
        var work = Path.Combine(tempRoot, $"separacao-{Guid.NewGuid():N}");
        Directory.CreateDirectory(work);
        try
        {
            progress?.Report(("Preparando o áudio", 0));
            var at44 = Path.Combine(work, "entrada.wav");
            await FfmpegAsync(["-hide_banner", "-nostdin", "-y", "-i", input, "-ac", "2", "-ar", "44100", "-c:a", "pcm_f32le", at44], token);
            using var source = new MappedWavSource(at44);
            var definition = MusicSeparation.Mode(mode) ?? throw new ConversionException("invalid_mode", "Modo de separação desconhecido.");
            var writers = definition.Stems.Select(s => new StreamingWavWriter(Path.Combine(work, $"{s.Id}-44k.wav"), 2, MusicSeparation.ModelRate)).ToArray();
            var pending = definition.Stems.Select(_ => new float[2][]).ToArray();
            using var options = new SessionOptions { GraphOptimizationLevel = GraphOptimizationLevel.ORT_ENABLE_ALL, IntraOpNumThreads = Math.Max(1, Environment.ProcessorCount - 1) };
            using var session = new InferenceSession(ModelPath, options);
            try
            {
                await MusicSeparation.SeparateAsync(source.Frames, source.Channels,
                    (c, start, n, destination) => source.Copy(c, start, n, destination),
                    (magnitude, ct) => Task.Run(() =>
                    {
                        var tensor = new DenseTensor<float>(magnitude, [1, 2, MusicSeparation.FramesPerChunk, MusicSeparation.Bands]);
                        using var outputs = session.Run([NamedOnnxValue.CreateFromTensor("magnitude", tensor)]);
                        return outputs.First().AsTensor<float>().ToArray();
                    }, ct),
                    (stem, channel, start, samples, n) =>
                    {
                        // A faixa chega canal por canal: guarda o esquerdo e escreve quando vier o direito.
                        var copy = samples[..n];
                        pending[stem][channel] = copy;
                        if (channel == 1)
                        {
                            writers[stem].Write([pending[stem][0], copy], 0, n);
                            pending[stem] = new float[2][];
                        }
                    },
                    mode, new Progress<double>(f => progress?.Report(("Separando as faixas", f))), token, refined);
            }
            finally
            {
                foreach (var writer in writers) writer.Dispose();
            }

            Directory.CreateDirectory(outputFolder);
            var results = new List<(MusicStemDefinition, string)>();
            foreach (var stem in definition.Stems)
            {
                progress?.Report(($"Voltando {stem.Label} à taxa do projeto", 1));
                var output = Path.Combine(outputFolder, $"{stem.Id}-{Guid.NewGuid():N}.wav");
                await ResampleAsync(Path.Combine(work, $"{stem.Id}-44k.wav"), output, rate, token);
                results.Add((stem, output));
            }

            return results;
        }
        finally
        {
            try { Directory.Delete(work, true); } catch (IOException) { }
        }
    }

    /// <summary>Reducao de ruido offline num WAV do projeto; escreve um WAV novo (estereo).</summary>
    public static Task ReduceNoiseAsync(string input, string output, int rate, CancellationToken token) => Task.Run(() =>
    {
        using var source = new MappedWavSource(input);
        using var writer = new StreamingWavWriter(output, 2, rate);
        float[]? leftPart = null;
        MusicSeparation.ReduceNoise(source.Frames, source.Channels,
            (c, start, n, destination) => source.Copy(c, start, n, destination),
            (c, start, samples, n) =>
            {
                token.ThrowIfCancellationRequested();
                if (c == 0) leftPart = samples[..n];
                else writer.Write([leftPart!, samples[..n]], 0, n);
            });
    }, token);

    public static Task RestoreStemAsync(string input, string output, int rate, string instrument, int intensity,
        CancellationToken token) => Task.Run(() =>
    {
        using var source = new MappedWavSource(input);
        var profile = instrument switch { "vozes" => 0u, "bateria" => 1u, "baixo" => 2u, _ => 3u };
        using var restorer = new MusicNative.Restorer((uint)rate, profile, intensity);
        var temp = output + ".restaurando";
        var left = new float[65536];
        var right = new float[left.Length];
        try
        {
            using (var writer = new StreamingWavWriter(temp, 2, rate))
            {
                for (long i = 0; i < source.Frames; i += left.Length)
                {
                    token.ThrowIfCancellationRequested();
                    var n = (int)Math.Min(left.Length, source.Frames - i);
                    source.Copy(0, i, n, left);
                    source.Copy(1, i, n, right);
                    restorer.Process(left.AsSpan(0, n), right.AsSpan(0, n));
                    writer.Write([left, right], 0, n);
                }
            }
            using var processed = new MappedWavSource(temp);
            using var final = new StreamingWavWriter(output, 2, rate);
            var gain = restorer.Gain;
            for (long i = 0; i < processed.Frames; i += left.Length)
            {
                token.ThrowIfCancellationRequested();
                var n = (int)Math.Min(left.Length, processed.Frames - i);
                processed.Copy(0, i, n, left);
                processed.Copy(1, i, n, right);
                for (var j = 0; j < n; j++) { left[j] *= gain; right[j] *= gain; }
                final.Write([left, right], 0, n);
            }
        }
        catch { File.Delete(output); throw; }
        finally { File.Delete(temp); }
    }, token);
}
