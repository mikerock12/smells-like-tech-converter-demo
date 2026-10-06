using System.ComponentModel;
using System.Runtime.InteropServices;
using Microsoft.Win32.SafeHandles;
using SmellsLikeTech.Converter.Core.Abstractions;

namespace SmellsLikeTech.Converter.Infrastructure.Execution;

/// <summary>Impede suspensão por inatividade, não mantém a tela acesa nem impede bloqueio.</summary>
public sealed class WindowsExecutionLease : IDisposable
{
    private SafeFileHandle? handle;
    private readonly bool executionRequired;

    private WindowsExecutionLease(SafeFileHandle handle, bool executionRequired)
    {
        this.handle = handle;
        this.executionRequired = executionRequired;
    }

    public static WindowsExecutionLease? TryAcquire(IConverterLog log)
    {
        if (!OperatingSystem.IsWindows()) return null;
        var reason = Marshal.StringToHGlobalUni("Smells Like Tech: conversão local em andamento");
        SafeFileHandle? request = null;
        try
        {
            var context = new ReasonContext { Flags = 1, Reason = new ReasonUnion { Simple = reason } };
            request = PowerCreateRequest(ref context);
            if (request.IsInvalid || !PowerSetRequest(request, 1))
                throw new Win32Exception(Marshal.GetLastWin32Error());
            var execution = PowerSetRequest(request, 3);
            if (!execution) log.Write(LogChannel.App, "A proteção de execução não está disponível; usando proteção contra suspensão por inatividade.");
            var lease = new WindowsExecutionLease(request, execution);
            request = null;
            return lease;
        }
        catch (Win32Exception error)
        {
            log.Write(LogChannel.App, $"Proteção contra suspensão indisponível (Windows {error.NativeErrorCode}); a conversão continua.");
            return null;
        }
        finally
        {
            request?.Dispose();
            Marshal.FreeHGlobal(reason);
        }
    }

    public void Dispose()
    {
        var request = Interlocked.Exchange(ref handle, null);
        if (request is null) return;
        if (executionRequired) PowerClearRequest(request, 3);
        PowerClearRequest(request, 1);
        request.Dispose();
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct ReasonContext
    {
        public uint Version;
        public uint Flags;
        public ReasonUnion Reason;
    }
    [StructLayout(LayoutKind.Explicit)]
    private struct ReasonUnion
    {
        [FieldOffset(0)] public IntPtr Simple;
        [FieldOffset(0)] public DetailedReason Detailed;
    }
    [StructLayout(LayoutKind.Sequential)]
    private struct DetailedReason
    {
        public IntPtr Module;
        public uint Id;
        public uint Count;
        public IntPtr Strings;
    }
    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern SafeFileHandle PowerCreateRequest(ref ReasonContext context);
    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool PowerSetRequest(SafeFileHandle handle, int type);
    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool PowerClearRequest(SafeFileHandle handle, int type);
}
