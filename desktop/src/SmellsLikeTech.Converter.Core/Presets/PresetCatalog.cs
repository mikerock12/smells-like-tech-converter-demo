using SmellsLikeTech.Converter.Core.Media;
using SmellsLikeTech.Converter.Core.Options;

namespace SmellsLikeTech.Converter.Core.Presets;

public sealed record ConversionPreset
{
    public required string Id { get; init; }
    public required string Name { get; init; }
    public required string Description { get; init; }
    public required MediaKind InputKind { get; init; }
    public required JobOptions Options { get; init; }
    public bool IsBuiltIn { get; init; } = true;
}

/// <summary>Presets de fabrica. Presets do usuario ficam no SQLite e sao somados a estes.</summary>
public static class PresetCatalog
{
    public static IReadOnlyList<ConversionPreset> BuiltIn { get; } =
    [
        new ConversionPreset
        {
            Id = "video.reels",
            Name = "Instagram Reels",
            Description = "1080x1920 · 9:16 · H.264 · AAC",
            InputKind = MediaKind.Video,
            Options = new VideoConvertOptions
            {
                OutputFormat = "mp4",
                VideoCodec = VideoCodecChoice.H264,
                CustomWidth = 1080,
                CustomHeight = 1920,
                AspectRatio = AspectRatioMode.Vertical9x16,
                Fit = FitMode.Blur,
                Quality = 70,
                FrameRate = 30,
                Audio = AudioTrackAction.Reencode,
                AudioBitrateKbps = 128
            }
        },
        new ConversionPreset
        {
            Id = "video.youtube",
            Name = "YouTube 1080p",
            Description = "1920x1080 · 16:9 · H.264 · AAC",
            InputKind = MediaKind.Video,
            Options = new VideoConvertOptions
            {
                OutputFormat = "mp4",
                VideoCodec = VideoCodecChoice.H264,
                TargetHeight = 1080,
                AspectRatio = AspectRatioMode.Wide16x9,
                Fit = FitMode.Contain,
                Quality = 78,
                Audio = AudioTrackAction.Reencode,
                AudioBitrateKbps = 192
            }
        },
        new ConversionPreset
        {
            Id = "video.whatsapp",
            Name = "WhatsApp",
            Description = "720p · compressão alta · MP4 H.264",
            InputKind = MediaKind.Video,
            Options = new VideoConvertOptions
            {
                OutputFormat = "mp4",
                VideoCodec = VideoCodecChoice.H264,
                TargetHeight = 720,
                Quality = 45,
                Audio = AudioTrackAction.Reencode,
                AudioBitrateKbps = 96
            }
        },
        new ConversionPreset
        {
            Id = "image.web",
            Name = "Web Image",
            Description = "WEBP · 1920px · 80%",
            InputKind = MediaKind.Image,
            Options = new ImageConvertOptions
            {
                OutputFormat = "webp",
                ResizeMode = ImageResizeMode.Width,
                Width = 1920,
                Quality = 80,
                StripMetadata = true
            }
        },
        new ConversionPreset
        {
            Id = "image.square",
            Name = "Post 1:1",
            Description = "JPG · 1080x1080 · recorte central",
            InputKind = MediaKind.Image,
            Options = new ImageConvertOptions
            {
                OutputFormat = "jpg",
                ResizeMode = ImageResizeMode.Exact,
                Width = 1080,
                Height = 1080,
                AspectRatio = AspectRatioMode.Square1x1,
                Fit = FitMode.Crop,
                Quality = 88
            }
        },
        new ConversionPreset
        {
            Id = "audio.mp3",
            Name = "MP3 192 kbps",
            Description = "Trilha em MP3 estéreo",
            InputKind = MediaKind.Video,
            Options = new ExtractAudioOptions
            {
                OutputFormat = "mp3",
                AudioBitrateKbps = 192
            }
        },
        new ConversionPreset
        {
            Id = "audio.transcricao",
            Name = "Áudio para transcrever",
            Description = "WAV mono 16 kHz normalizado",
            InputKind = MediaKind.Video,
            Options = new ExtractAudioOptions
            {
                OutputFormat = "wav",
                SampleRateHz = 16_000,
                Channels = 1,
                Normalize = true
            }
        }
    ];

    public static IReadOnlyList<ConversionPreset> For(MediaKind kind) =>
        [.. BuiltIn.Where(preset => preset.InputKind == kind)];
}
