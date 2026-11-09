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
                var (left, right) = await Task.Run(() => MusicMixer.Render(project, sources, start, frames, id, metronome: project.Metronome.InExport), token);
                MusicMeasures? measures = null;
                double? gain = null;
                if (id is null)
                {
                    progress?.Report(("Masterizando", (i + 0.8) / targets.Count));
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

    private static string SafeName(string text)
    {
        var clean = new string([.. text.Normalize(System.Text.NormalizationForm.FormD).Where(c => char.IsAsciiLetterOrDigit(c) || c is ' ' or '-' or '_')]).Trim().Replace(' ', '-');
        return clean.Length == 0 ? "musica" : clean.Length > 60 ? clean[..60] : clean;
    }

    public string ModelPath => Path.Combine(modelsRoot, "musica", MusicSeparation.ModelFile);

    public bool ModelReady => File.Exists(ModelPath) && new FileInfo(ModelPath).Length == MusicSeparation.ModelBytes;

    /// <summary>Baixa o modelo (78 MB) na primeira vez e confere o SHA-256 antes de usar.</summary>
    public async Task EnsureModelAsync(HttpClient http, IProgress<double>? progress, CancellationToken token)
    {
        if (ModelReady) return;
        Directory.CreateDirectory(Path.GetDirectoryName(ModelPath)!);
        var temp = ModelPath + ".baixando";
        using (var response = await http.GetAsync(MusicSeparation.ModelUrl, HttpCompletionOption.ResponseHeadersRead, token))
        {
            response.EnsureSuccessStatusCode();
            var total = response.Content.Headers.ContentLength ?? MusicSeparation.ModelBytes;
            await using var input = await response.Content.ReadAsStreamAsync(token);
            await using var output = File.Create(temp);
            var buffer = new byte[1 << 16];
            long received = 0;
            int read;
            while ((read = await input.ReadAsync(buffer, token)) > 0)
            {
                await output.WriteAsync(buffer.AsMemory(0, read), token);
                received += read;
                progress?.Report(received / (double)total);
            }
        }

        await using (var check = File.OpenRead(temp))
        {
            var hash = Convert.ToHexStringLower(await SHA256.HashDataAsync(check, token));
            if (hash != MusicSeparation.ModelSha256)
            {
                check.Close();
                File.Delete(temp);
                throw new ConversionException("model_mismatch", "O modelo baixado não confere (SHA-256 diferente) e foi descartado.");
            }
        }

        File.Move(temp, ModelPath, overwrite: true);
    }

    /// <summary>
    /// Separa um WAV da taxa do projeto: converte para 44,1 kHz, roda o modelo em trechos
    /// (memoria limitada), escreve uma faixa por WAV e volta para a taxa do projeto.
    /// Devolve os caminhos, na ordem do modo.
    /// </summary>
    public async Task<IReadOnlyList<(MusicStemDefinition Stem, string Path)>> SeparateAsync(string input, string outputFolder, string mode, int rate, IProgress<(string Stage, double Fraction)>? progress, CancellationToken token)
    {
        if (!ModelReady) throw new ConversionException("model_missing", "O modelo de separação não está baixado.");
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
                    mode, new Progress<double>(f => progress?.Report(("Separando as faixas", f))), token);
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
}
