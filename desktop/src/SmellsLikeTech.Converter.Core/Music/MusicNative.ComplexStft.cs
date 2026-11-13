using System.Runtime.InteropServices;
using Microsoft.Win32.SafeHandles;

namespace SmellsLikeTech.Converter.Core.Music;

public static partial class MusicNative
{
    public static bool HasComplexStft => IsAvailable && (Capabilities() & (1u << 8)) != 0;

    private sealed class ComplexStftHandle : SafeHandleZeroOrMinusOneIsInvalid
    {
        public ComplexStftHandle() : base(true) { }

        protected override bool ReleaseHandle()
        {
            DestroyComplexStft(handle);
            return true;
        }
    }

    [LibraryImport(Library, EntryPoint = "slt_complex_stft_create")]
    private static partial ComplexStftHandle CreateComplexStft(uint window, uint hop, uint length);
    [LibraryImport(Library, EntryPoint = "slt_complex_stft_destroy")]
    private static partial void DestroyComplexStft(nint stft);
    [LibraryImport(Library, EntryPoint = "slt_complex_stft_size")]
    private static partial uint ComplexStftSize(ComplexStftHandle stft);
    [LibraryImport(Library, EntryPoint = "slt_complex_stft_analyze")]
    private static unsafe partial int AnalyzeComplexStft(ComplexStftHandle stft, float* left, float* right,
        uint length, float* spectrum, uint spectrumCount);
    [LibraryImport(Library, EntryPoint = "slt_complex_stft_synthesize")]
    private static unsafe partial int SynthesizeComplexStft(ComplexStftHandle stft, float* spectrum, float* mask,
        uint spectrumCount, uint zeroDc, float* left, float* right, uint length);

    /// <summary>STFT centrada para espectros e máscaras em ordem quadro, bin, canal, real/imaginário.</summary>
    public sealed class ComplexStft : IDisposable
    {
        private readonly ComplexStftHandle handle;

        public ComplexStft(uint window, uint hop, uint length)
        {
            if (!HasComplexStft) throw new PlatformNotSupportedException("STFT complexa nativa indisponível.");
            handle = CreateComplexStft(window, hop, length);
            if (handle.IsInvalid) throw new ArgumentException("Configuração de STFT complexa inválida.");
            Samples = checked((int)length);
            SpectrumSize = checked((int)ComplexStftSize(handle));
        }

        public int Samples { get; }
        public int SpectrumSize { get; }

        public unsafe void Analyze(ReadOnlySpan<float> left, ReadOnlySpan<float> right, Span<float> spectrum)
        {
            if (left.Length != Samples || right.Length != Samples || spectrum.Length != SpectrumSize ||
                left.Overlaps(spectrum) || right.Overlaps(spectrum))
                throw new ArgumentException("Buffers de análise inválidos.");
            fixed (float* l = left, r = right, x = spectrum)
                Conferir(AnalyzeComplexStft(handle, l, r, (uint)Samples, x, (uint)SpectrumSize));
        }

        public unsafe void Synthesize(ReadOnlySpan<float> spectrum, ReadOnlySpan<float> mask, Span<float> left,
            Span<float> right, bool zeroDc = false)
        {
            if (spectrum.Length != SpectrumSize || (!mask.IsEmpty && mask.Length != SpectrumSize) ||
                left.Length != Samples || right.Length != Samples || left.Overlaps(right) ||
                spectrum.Overlaps(left) || spectrum.Overlaps(right) || mask.Overlaps(left) || mask.Overlaps(right))
                throw new ArgumentException("Buffers de síntese inválidos.");
            fixed (float* x = spectrum, m = mask, l = left, r = right)
                Conferir(SynthesizeComplexStft(handle, x, m, (uint)SpectrumSize, zeroDc ? 1u : 0u, l, r, (uint)Samples));
        }

        public void Dispose() => handle.Dispose();
    }
}
