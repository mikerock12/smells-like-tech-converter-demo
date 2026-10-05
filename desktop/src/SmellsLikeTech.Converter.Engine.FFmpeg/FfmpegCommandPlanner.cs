using System.Globalization;
using SmellsLikeTech.Converter.Core;
using SmellsLikeTech.Converter.Core.Media;
using SmellsLikeTech.Converter.Core.Options;
using SmellsLikeTech.Converter.Infrastructure.Execution;

namespace SmellsLikeTech.Converter.Engine.FFmpeg;

/// <summary>
/// Monta as linhas de comando do FFmpeg a partir de opcoes ja validadas.
/// Todo argumento sai daqui: nenhum texto do usuario e concatenado em comando.
/// </summary>
public static class FfmpegCommandPlanner
{
    private static readonly CultureInfo Invariant = CultureInfo.InvariantCulture;

    /// <summary>Janela de contexto entregue ao Whisper de cada vez, em segundos.</summary>
    internal const int WhisperContextSeconds = 30;

    public static CommandSpec ConvertVideo(
        FfmpegOptions engine,
        string inputPath,
        string outputPath,
        VideoConvertOptions options,
        MediaInfo input)
    {
        options.Validate();

        var reencodeAudio = options.Audio == AudioTrackAction.Reencode || Math.Abs(options.Speed - 1.0) > 0.001;
        var arguments = Prologue(inputPath, options.TrimStart, options.TrimDuration);

        var videoFilters = BuildVideoFilterGraph(options, input);
        if (videoFilters is not null)
        {
            arguments.AddRange(["-vf", videoFilters]);
        }

        arguments.AddRange(["-map", "0:v:0"]);
        if (options.Audio != AudioTrackAction.Remove && input.HasAudio)
        {
            arguments.AddRange(["-map", "0:a:0?"]);
        }

        AddVideoEncoding(arguments, options, engine);

        if (options.Audio == AudioTrackAction.Remove || !input.HasAudio)
        {
            arguments.Add("-an");
        }
        else
        {
            var audioFilter = BuildAudioSpeedFilter(options.Speed);
            if (audioFilter is not null)
            {
                arguments.AddRange(["-af", audioFilter]);
            }

            AddVideoContainerAudio(arguments, options, reencodeAudio);
        }

        if (options.OutputFormat is "mp4" or "mov" or "m4v")
        {
            arguments.AddRange(["-movflags", "+faststart"]);
        }

        arguments.Add(outputPath);
        return new CommandSpec(engine.FfmpegPath, arguments);
    }

    /// <summary>Video para audio - extrai a faixa escolhida e codifica no formato pedido.</summary>
    public static CommandSpec ExtractAudio(
        FfmpegOptions engine,
        string inputPath,
        string outputPath,
        ExtractAudioOptions options,
        int absoluteStreamIndex)
    {
        options.Validate();

        var arguments = Prologue(inputPath, options.TrimStart, options.TrimDuration);
        arguments.AddRange(["-map", $"0:{absoluteStreamIndex.ToString(Invariant)}", "-vn", "-sn", "-dn"]);

        if (options.Normalize)
        {
            arguments.AddRange(["-af", "loudnorm=I=-16:LRA=11:TP=-1.5"]);
        }

        AddAudioEncoding(arguments, options.OutputFormat, options.AudioBitrateKbps);
        AddAudioShape(arguments, options.SampleRateHz, options.Channels);
        arguments.AddRange(["-map_metadata", "-1", "-map_chapters", "-1", outputPath]);
        return new CommandSpec(engine.FfmpegPath, arguments);
    }

    public static CommandSpec ConvertAudio(
        FfmpegOptions engine,
        string inputPath,
        string outputPath,
        AudioConvertOptions options)
    {
        options.Validate();

        var arguments = Prologue(inputPath, options.TrimStart, options.TrimDuration);
        arguments.AddRange(["-map", "0:a:0", "-vn", "-sn", "-dn"]);

        if (options.Normalize)
        {
            arguments.AddRange(["-af", "loudnorm=I=-16:LRA=11:TP=-1.5"]);
        }

        AddAudioEncoding(arguments, options.OutputFormat, options.AudioBitrateKbps);
        AddAudioShape(arguments, options.SampleRateHz, options.Channels);
        arguments.AddRange(["-map_metadata", "-1", "-map_chapters", "-1", outputPath]);
        return new CommandSpec(engine.FfmpegPath, arguments);
    }

    public static CommandSpec ToGif(
        FfmpegOptions engine,
        string inputPath,
        string outputPath,
        VideoToGifOptions options)
    {
        options.Validate();

        var arguments = Prologue(inputPath, options.TrimStart, options.TrimDuration);
        var width = options.Width.ToString(Invariant);
        var fps = options.FrameRate.ToString(Invariant);
        arguments.AddRange([
            "-vf",
            $"fps={fps},scale={width}:-2:flags=lanczos,split[paletteSource][gifSource];" +
            "[paletteSource]palettegen=stats_mode=diff[palette];" +
            "[gifSource][palette]paletteuse=dither=bayer:bayer_scale=5:diff_mode=rectangle",
            "-loop", "0",
            "-an", "-sn", "-dn",
            outputPath
        ]);
        return new CommandSpec(engine.FfmpegPath, arguments);
    }

    public static CommandSpec ToFrames(
        FfmpegOptions engine,
        string inputPath,
        string outputPattern,
        VideoToFramesOptions options)
    {
        options.Validate();

        var arguments = Prologue(inputPath, options.TrimStart, options.TrimDuration);
        arguments.AddRange([
            "-vf", $"fps={options.FramesPerSecond.ToString(Invariant)}",
            "-frames:v", options.MaxFrames.ToString(Invariant),
            "-an", "-sn", "-dn"
        ]);

        if (options.ImageFormat == "jpg")
        {
            arguments.AddRange(["-q:v", "3"]);
        }

        arguments.Add(outputPattern);
        return new CommandSpec(engine.FfmpegPath, arguments);
    }

    /// <summary>PCM mono 16 kHz: entrada previsivel para o Whisper.</summary>
    public static CommandSpec PrepareSpeechAudio(
        FfmpegOptions engine,
        string inputPath,
        string outputWavePath,
        int absoluteStreamIndex)
    {
        var arguments = Prologue(inputPath, null, null);
        arguments.AddRange([
            "-map", $"0:{absoluteStreamIndex.ToString(Invariant)}",
            "-vn", "-sn", "-dn",
            "-ac", "1",
            "-ar", "16000",
            "-c:a", "pcm_s16le",
            "-map_metadata", "-1",
            outputWavePath
        ]);
        return new CommandSpec(engine.FfmpegPath, arguments);
    }

    /// <summary>Transcricao com o whisper.cpp embutido no FFmpeg.</summary>
    public static CommandSpec Transcribe(
        FfmpegOptions engine,
        string inputWavePath,
        string destinationPath,
        string modelPath,
        TranscribeOptions options,
        string engineFormat)
    {
        options.Validate();
        if (engineFormat is not ("text" or "srt" or "json"))
        {
            throw new ConversionException("invalid_transcription_format", "Formato interno de transcrição inválido.");
        }

        // Sintaxe de filtro do FFmpeg: nome=opcao=valor:opcao=valor
        var whisper = "whisper=" + string.Join(':',
        [
            "model=" + FfmpegFilterEscaping.QuotePath(modelPath),
            "language=" + options.Language,
            "destination=" + FfmpegFilterEscaping.QuotePath(destinationPath),
            "format=" + engineFormat,
            // Janela de áudio entregue ao modelo por vez. O padrão do filtro é 3 s, curto
            // demais: sem contexto o Whisper troca de idioma no meio da fala e inventa
            // trechos. 30 s é a janela nativa do modelo - transcreve melhor e mais rápido,
            // porque faz uma passada em vez de dezenas.
            "queue=" + WhisperContextSeconds.ToString(Invariant),
            "use_gpu=" + (options.UseGpu ? "true" : "false")
        ]);

        var arguments = Prologue(inputWavePath, null, null);
        arguments.AddRange(["-af", whisper, "-f", "null", "-"]);
        return new CommandSpec(engine.FfmpegPath, arguments);
    }

    /// <summary>Codifica o WAV produzido pelo TTS no formato final escolhido.</summary>
    public static CommandSpec EncodeSynthesizedAudio(
        FfmpegOptions engine,
        string inputWavePath,
        string outputPath,
        SynthesizeOptions options)
    {
        options.Validate();

        var arguments = Prologue(inputWavePath, null, null);
        arguments.AddRange(["-vn", "-sn", "-dn"]);
        AddAudioEncoding(arguments, options.OutputFormat, options.AudioBitrateKbps);
        AddAudioShape(arguments, options.SampleRateHz, options.Channels);
        arguments.AddRange(["-map_metadata", "-1", "-map_chapters", "-1", outputPath]);
        return new CommandSpec(engine.FfmpegPath, arguments);
    }

    /// <summary>Junta varios WAV na ordem informada, sem recodificar.</summary>
    public static CommandSpec ConcatWaves(FfmpegOptions engine, string listFilePath, string outputWavePath) =>
        new(engine.FfmpegPath,
        [
            "-hide_banner", "-nostdin", "-v", "error", "-n",
            "-f", "concat", "-safe", "1",
            "-i", listFilePath,
            "-c", "copy",
            outputWavePath
        ]);

    private static List<string> Prologue(string inputPath, TimeSpan? trimStart, TimeSpan? trimDuration)
    {
        var arguments = new List<string>
        {
            "-hide_banner", "-nostdin", "-v", "error", "-n",
            "-progress", "pipe:1", "-nostats",
            "-protocol_whitelist", "file,pipe"
        };

        if (trimStart is not null)
        {
            arguments.AddRange(["-ss", Seconds(trimStart.Value)]);
        }

        arguments.AddRange(["-i", inputPath]);

        if (trimDuration is not null)
        {
            arguments.AddRange(["-t", Seconds(trimDuration.Value)]);
        }

        return arguments;
    }

    private static string Seconds(TimeSpan value) =>
        value.TotalSeconds.ToString("0.###", Invariant);

    internal static string? BuildVideoFilterGraph(VideoConvertOptions options, MediaInfo input)
    {
        if (options.VideoCodec == VideoCodecChoice.CopyStream)
        {
            return null;
        }

        var filters = new List<string>();

        switch (options.Rotation)
        {
            case RotationDegrees.Clockwise90:
                filters.Add("transpose=1");
                break;
            case RotationDegrees.Half180:
                filters.Add("transpose=1");
                filters.Add("transpose=1");
                break;
            case RotationDegrees.CounterClockwise270:
                filters.Add("transpose=2");
                break;
        }

        if (options.FlipHorizontal)
        {
            filters.Add("hflip");
        }

        if (options.FlipVertical)
        {
            filters.Add("vflip");
        }

        if (options.FrameRate is not null)
        {
            filters.Add($"fps={options.FrameRate.Value.ToString(Invariant)}");
        }

        if (Math.Abs(options.Speed - 1.0) > 0.001)
        {
            filters.Add($"setpts={(1 / options.Speed).ToString("0.####", Invariant)}*PTS");
        }

        var geometry = BuildGeometryFilter(options, input);
        if (geometry is not null)
        {
            filters.Add(geometry);
        }

        return filters.Count == 0 ? null : string.Join(',', filters);
    }

    private static string? BuildGeometryFilter(VideoConvertOptions options, MediaInfo input)
    {
        var color = FfmpegFilterEscaping.Color(options.BackgroundColor);
        var size = ResolveTargetSize(options, input);

        if (size is null)
        {
            // Sem proporcao nova: no maximo um redimensionamento por altura.
            return options.TargetHeight is int height
                ? $"scale=-2:{Even(height).ToString(Invariant)}"
                : null;
        }

        var (width, height2) = size.Value;
        var w = width.ToString(Invariant);
        var h = height2.ToString(Invariant);

        return options.Fit switch
        {
            FitMode.Crop => $"scale={w}:{h}:force_original_aspect_ratio=increase,crop={w}:{h}",
            FitMode.Contain =>
                $"scale={w}:{h}:force_original_aspect_ratio=decrease,pad={w}:{h}:(ow-iw)/2:(oh-ih)/2:color={color}",
            FitMode.Blur =>
                $"split[blurSource][mainSource];" +
                $"[blurSource]scale={w}:{h}:force_original_aspect_ratio=increase,crop={w}:{h},gblur=sigma=24[blurred];" +
                $"[mainSource]scale={w}:{h}:force_original_aspect_ratio=decrease[main];" +
                "[blurred][main]overlay=(W-w)/2:(H-h)/2",
            _ => $"scale={w}:{h}"
        };
    }

    /// <summary>Resolucao final em pixels pares, quando ha proporcao ou tamanho alvo.</summary>
    internal static (int Width, int Height)? ResolveTargetSize(VideoConvertOptions options, MediaInfo input)
    {
        if (options.CustomWidth is int customWidth && options.CustomHeight is int customHeight)
        {
            return (Even(customWidth), Even(customHeight));
        }

        var ratio = options.AspectRatio.Ratio();
        var sourceWidth = input.Width ?? 0;
        var sourceHeight = input.Height ?? 0;

        if (options.TargetHeight is int targetHeight)
        {
            if (ratio is not null)
            {
                return (Even(targetHeight * ratio.Value.Width / ratio.Value.Height), Even(targetHeight));
            }

            if (sourceWidth > 0 && sourceHeight > 0)
            {
                var scaled = (int)Math.Round((double)sourceWidth * targetHeight / sourceHeight);
                return (Even(scaled), Even(targetHeight));
            }

            return null;
        }

        if (ratio is null || sourceWidth <= 0 || sourceHeight <= 0)
        {
            return null;
        }

        // Mantem a maior dimensao possivel do original dentro da nova proporcao.
        var byHeight = (double)sourceHeight * ratio.Value.Width / ratio.Value.Height;
        return byHeight <= sourceWidth
            ? (Even((int)Math.Round(byHeight)), Even(sourceHeight))
            : (Even(sourceWidth), Even((int)Math.Round((double)sourceWidth * ratio.Value.Height / ratio.Value.Width)));
    }

    private static int Even(int value) => Math.Max(2, value % 2 == 0 ? value : value - 1);

    private static string? BuildAudioSpeedFilter(double speed)
    {
        if (Math.Abs(speed - 1.0) <= 0.001)
        {
            return null;
        }

        // atempo aceita 0,5x a 2x por instancia; fora disso encadeia.
        var remaining = speed;
        var stages = new List<string>();
        while (remaining > 2.0 && stages.Count < 4)
        {
            stages.Add("atempo=2.0");
            remaining /= 2.0;
        }

        while (remaining < 0.5 && stages.Count < 4)
        {
            stages.Add("atempo=0.5");
            remaining /= 0.5;
        }

        stages.Add($"atempo={remaining.ToString("0.####", Invariant)}");
        return string.Join(',', stages);
    }

    private static void AddVideoEncoding(List<string> arguments, VideoConvertOptions options, FfmpegOptions engine)
    {
        if (options.VideoCodec == VideoCodecChoice.CopyStream)
        {
            arguments.AddRange(["-c:v", "copy"]);
            return;
        }

        var codec = options.VideoCodec == VideoCodecChoice.Automatic
            ? options.OutputFormat == "webm" ? VideoCodecChoice.Vp9 : VideoCodecChoice.H264
            : options.VideoCodec;

        var acceleration = options.Acceleration == HardwareAcceleration.Automatic
            ? engine.Preference
            : options.Acceleration;

        var encoder = SelectEncoder(codec, acceleration, engine);
        arguments.AddRange(["-c:v", encoder]);

        if (options.VideoBitrateKbps is int kbps)
        {
            arguments.AddRange(["-b:v", $"{kbps.ToString(Invariant)}k"]);
        }
        else
        {
            AddQualityArguments(arguments, encoder, options.Quality);
        }

        arguments.AddRange(["-pix_fmt", "yuv420p"]);
    }

    internal static string SelectEncoder(VideoCodecChoice codec, HardwareAcceleration acceleration, FfmpegOptions engine)
    {
        var suffix = acceleration switch
        {
            HardwareAcceleration.Nvidia => "nvenc",
            HardwareAcceleration.Amd => "amf",
            HardwareAcceleration.Intel => "qsv",
            _ => null
        };

        if (suffix is not null)
        {
            var accelerated = codec switch
            {
                VideoCodecChoice.H264 => $"h264_{suffix}",
                VideoCodecChoice.H265 => $"hevc_{suffix}",
                VideoCodecChoice.Av1 => $"av1_{suffix}",
                _ => null
            };

            if (accelerated is not null && engine.Supports(accelerated))
            {
                return accelerated;
            }
        }

        // Fallback sempre existe em CPU.
        return codec switch
        {
            VideoCodecChoice.H265 => "libx265",
            VideoCodecChoice.Vp9 => "libvpx-vp9",
            VideoCodecChoice.Av1 => "libsvtav1",
            _ => "libx264"
        };
    }

    private static void AddQualityArguments(List<string> arguments, string encoder, int quality)
    {
        if (encoder.EndsWith("_nvenc", StringComparison.Ordinal))
        {
            arguments.AddRange(["-rc", "vbr", "-cq", Crf(quality, 51, 12).ToString(Invariant), "-b:v", "0"]);
            return;
        }

        if (encoder.EndsWith("_qsv", StringComparison.Ordinal))
        {
            arguments.AddRange(["-global_quality", Crf(quality, 51, 12).ToString(Invariant)]);
            return;
        }

        if (encoder.EndsWith("_amf", StringComparison.Ordinal))
        {
            var qp = Crf(quality, 51, 12).ToString(Invariant);
            arguments.AddRange(["-rc", "cqp", "-qp_i", qp, "-qp_p", qp]);
            return;
        }

        switch (encoder)
        {
            case "libvpx-vp9":
                arguments.AddRange(["-crf", Crf(quality, 63, 15).ToString(Invariant), "-b:v", "0"]);
                break;
            case "libsvtav1":
                arguments.AddRange(["-crf", Crf(quality, 63, 20).ToString(Invariant), "-preset", "8"]);
                break;
            case "libx265":
                arguments.AddRange(["-crf", Crf(quality, 51, 16).ToString(Invariant), "-preset", "medium"]);
                break;
            default:
                arguments.AddRange(["-crf", Crf(quality, 51, 14).ToString(Invariant), "-preset", "medium"]);
                break;
        }
    }

    /// <summary>Qualidade 0-100 da interface vira o CRF/CQ do encoder (menor = melhor).</summary>
    internal static int Crf(int quality, int worst, int best)
    {
        var clamped = Math.Clamp(quality, 0, 100);
        return (int)Math.Round(worst - ((worst - best) * (clamped / 100.0)), MidpointRounding.AwayFromZero);
    }

    private static void AddVideoContainerAudio(List<string> arguments, VideoConvertOptions options, bool reencode)
    {
        var isWebm = options.OutputFormat == "webm";
        if (!reencode && !isWebm)
        {
            arguments.AddRange(["-c:a", "copy"]);
            return;
        }

        var bitrate = (options.AudioBitrateKbps ?? 160).ToString(Invariant);
        arguments.AddRange(isWebm
            ? ["-c:a", "libopus", "-b:a", $"{bitrate}k"]
            : ["-c:a", "aac", "-b:a", $"{bitrate}k"]);
    }

    private static void AddAudioEncoding(List<string> arguments, string format, int? bitrateKbps)
    {
        var defaultBitrate = format switch
        {
            "opus" => 128,
            "ogg" => 160,
            _ => 192
        };
        var bitrate = (bitrateKbps ?? defaultBitrate).ToString(Invariant);

        switch (format)
        {
            case "mp3":
                arguments.AddRange(["-c:a", "libmp3lame", "-b:a", $"{bitrate}k"]);
                break;
            case "aac":
            case "m4a":
                arguments.AddRange(["-c:a", "aac", "-b:a", $"{bitrate}k"]);
                break;
            case "opus":
                arguments.AddRange(["-c:a", "libopus", "-b:a", $"{bitrate}k", "-vbr", "on"]);
                break;
            case "ogg":
                arguments.AddRange(["-c:a", "libvorbis", "-b:a", $"{bitrate}k"]);
                break;
            case "wav":
                arguments.AddRange(["-c:a", "pcm_s16le"]);
                break;
            case "flac":
                arguments.AddRange(["-c:a", "flac"]);
                break;
            default:
                throw new ConversionException("unsupported_output_format", $"Formato de áudio não suportado: '{format}'.");
        }
    }

    private static void AddAudioShape(List<string> arguments, int? sampleRateHz, int? channels)
    {
        if (sampleRateHz is not null)
        {
            arguments.AddRange(["-ar", sampleRateHz.Value.ToString(Invariant)]);
        }

        if (channels is not null)
        {
            arguments.AddRange(["-ac", channels.Value.ToString(Invariant)]);
        }
    }
}
