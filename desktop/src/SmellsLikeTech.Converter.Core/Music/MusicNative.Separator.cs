using System.Runtime.InteropServices;
using Microsoft.Win32.SafeHandles;

namespace SmellsLikeTech.Converter.Core.Music;

public static partial class MusicNative
{
    [StructLayout(LayoutKind.Sequential)]
    private struct SeparatorConfig
    {
        public uint Size;
        public ulong Length;
        public uint Window, Hop, FramesPerChunk, Bands, Instruments, Outputs, Mask;
    }

    private sealed class SeparatorHandle : SafeHandleZeroOrMinusOneIsInvalid
    {
        public SeparatorHandle() : base(true) { }
        protected override bool ReleaseHandle() { DestroySeparator(handle); return true; }
    }

    [LibraryImport(Library, EntryPoint = "slt_separator_create")]
    private static partial SeparatorHandle CreateSeparator(in SeparatorConfig config);
    [LibraryImport(Library, EntryPoint = "slt_separator_destroy")]
    private static partial void DestroySeparator(nint separator);
    [LibraryImport(Library, EntryPoint = "slt_separator_chunk_count")]
    private static partial uint ChunkCount(SeparatorHandle separator);
    [LibraryImport(Library, EntryPoint = "slt_separator_chunk_window")]
    private static partial int ChunkWindow(SeparatorHandle separator, uint chunk, out long start, out uint samples, out uint frames);
    [LibraryImport(Library, EntryPoint = "slt_separator_analyze")]
    private static unsafe partial int Analyze(SeparatorHandle separator, uint chunk, float* left, float* right,
        uint samples, float* magnitude, uint magnitudeCount);
    [LibraryImport(Library, EntryPoint = "slt_separator_synthesize")]
    private static unsafe partial int Synthesize(SeparatorHandle separator, uint chunk, uint output,
        float* estimates, uint estimateCount, uint* instruments, uint instrumentCount,
        float* left, float* right, uint capacity, out long start, out uint samples);

    /// <summary>STFT e reconstrução fora do tempo real. Um trabalho de inferência por instância.</summary>
    public sealed class Separator : IDisposable
    {
        private readonly SeparatorHandle handle;

        public unsafe Separator(ulong length, uint window, uint hop, uint framesPerChunk, uint bands,
                                uint instruments, uint outputs, bool refined = false)
        {
            if (!IsAvailable) throw new PlatformNotSupportedException("Motor de áudio nativo indisponível.");
            var config = new SeparatorConfig
            {
                Size = (uint)sizeof(SeparatorConfig), Length = length, Window = window, Hop = hop,
                FramesPerChunk = framesPerChunk, Bands = bands, Instruments = instruments, Outputs = outputs,
                Mask = refined ? 1u : 0u,
            };
            handle = CreateSeparator(in config);
            if (handle.IsInvalid) throw new ArgumentException("Configuração de separação inválida.");
        }

        public uint Chunks => ChunkCount(handle);

        public (long Start, int Samples, int Frames) Window(uint chunk)
        {
            Conferir(ChunkWindow(handle, chunk, out var start, out var samples, out var frames));
            return (start, checked((int)samples), checked((int)frames));
        }

        public unsafe void Analyze(uint chunk, ReadOnlySpan<float> left, ReadOnlySpan<float> right, Span<float> magnitude)
        {
            if (left.Length != right.Length || left.Overlaps(magnitude) || right.Overlaps(magnitude))
                throw new ArgumentException("Buffers de análise inválidos.");
            fixed (float* e = left, d = right, m = magnitude)
                Conferir(MusicNative.Analyze(handle, chunk, e, d, (uint)left.Length, m, (uint)magnitude.Length));
        }

        public unsafe (long Start, int Samples) Synthesize(uint chunk, uint output, ReadOnlySpan<float> estimates,
            ReadOnlySpan<uint> instruments, Span<float> left, Span<float> right)
        {
            if (left.Length != right.Length || left.Overlaps(right) || estimates.Overlaps(left) || estimates.Overlaps(right))
                throw new ArgumentException("Buffers de síntese inválidos.");
            fixed (float* m = estimates, e = left, d = right)
            fixed (uint* indices = instruments)
            {
                Conferir(MusicNative.Synthesize(handle, chunk, output, m, (uint)estimates.Length, indices,
                    (uint)instruments.Length, e, d, (uint)left.Length, out var start, out var samples));
                return (start, checked((int)samples));
            }
        }

        public void Dispose() => handle.Dispose();
    }
}
