using System.Runtime.InteropServices;

namespace SmellsLikeTech.Converter.Core.Jobs;

/// <summary>
/// Quantas threads dar à inferência (separação de faixas, remoção de fundo). O ONNX Runtime
/// divide cada operação igualmente entre as threads: os dois "processadores lógicos" de um
/// mesmo núcleo (Hyper-Threading/SMT) disputam as mesmas unidades de cálculo, e os núcleos de
/// eficiência dos Intel híbridos (E-cores) atrasam os de desempenho. Por isso a conta usa os
/// núcleos físicos de desempenho, deixando um livre para a interface quando há poucos.
/// <c>SLT_ONNX_THREADS</c> força um número (diagnóstico e medições).
/// </summary>
public static class InferenceProcessors
{
    public static int InferenceThreads()
    {
        if (int.TryParse(Environment.GetEnvironmentVariable("SLT_ONNX_THREADS"), out var forced) && forced > 0)
            return Math.Min(forced, Environment.ProcessorCount);
        var cores = PerformanceCores();
        return Plan(cores, Environment.ProcessorCount);
    }

    /// <summary>A regra, separada da leitura do hardware para ser testada.</summary>
    public static int Plan(int performanceCores, int logicalProcessors)
    {
        if (performanceCores <= 0) return Math.Max(1, logicalProcessors - 1);
        // Até 4 núcleos, um fica para a interface, o áudio e o sistema; acima, todos trabalham.
        return Math.Clamp(performanceCores <= 4 ? performanceCores - 1 : performanceCores, 1, logicalProcessors);
    }

    /// <summary>Núcleos físicos da classe mais rápida (P-cores nos Intel híbridos; todos nos demais).</summary>
    public static int PerformanceCores()
    {
        try
        {
            uint length = 0;
            GetLogicalProcessorInformationEx(0, IntPtr.Zero, ref length);
            if (length == 0) return 0;
            var buffer = Marshal.AllocHGlobal((int)length);
            try
            {
                if (!GetLogicalProcessorInformationEx(0, buffer, ref length)) return 0;
                var classes = new List<byte>();
                for (var offset = 0; offset < length;)
                {
                    var relationship = Marshal.ReadInt32(buffer, offset);
                    var size = Marshal.ReadInt32(buffer, offset + 4);
                    // PROCESSOR_RELATIONSHIP: Flags (byte), EfficiencyClass (byte), ...
                    if (relationship == 0) classes.Add(Marshal.ReadByte(buffer, offset + 9));
                    if (size <= 0) break;
                    offset += size;
                }

                if (classes.Count == 0) return 0;
                var fastest = classes.Max();
                return classes.Count(c => c == fastest);
            }
            finally
            {
                Marshal.FreeHGlobal(buffer);
            }
        }
        catch (Exception e) when (e is EntryPointNotFoundException or DllNotFoundException)
        {
            return 0;
        }
    }

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GetLogicalProcessorInformationEx(int relationship, IntPtr buffer, ref uint length);
}
