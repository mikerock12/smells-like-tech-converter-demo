using System.Runtime.InteropServices;
using System.Text;
using Microsoft.Win32.SafeHandles;

namespace SmellsLikeTech.Converter.Core.Music;

public static partial class MusicNative
{
    public sealed record PluginInfo(string Path, string ClassId, string Name, string Vendor, string Version);

    [StructLayout(LayoutKind.Sequential)]
    private unsafe struct PluginInfoNative
    {
        public fixed byte Path[1024];
        public fixed byte ClassId[33];
        public fixed byte Name[128];
        public fixed byte Vendor[128];
        public fixed byte Version[64];
    }

    public static bool HasVst3 => IsAvailable && (Capabilities() & (1u << 7)) != 0;

    [LibraryImport(Library, EntryPoint = "slt_plugin_scan", StringMarshalling = StringMarshalling.Utf8)]
    private static unsafe partial int ScanVst3(string? path, PluginInfoNative* infos, uint capacity, out uint count);

    public static unsafe IReadOnlyList<PluginInfo> ScanPlugins(string? path = null)
    {
        if (!HasVst3) return [];
        if (path?.Contains('\0') == true) throw new ArgumentException("Caminho VST3 inválido.", nameof(path));
        var dados = new PluginInfoNative[64];
        uint quantidade;
        fixed (PluginInfoNative* buffer = dados) Conferir(ScanVst3(path, buffer, (uint)dados.Length, out quantidade));
        if (quantidade > dados.Length)
        {
            if (quantidade > 4096) throw new InvalidOperationException("Quantidade de plugins VST3 acima do limite.");
            dados = new PluginInfoNative[quantidade];
            fixed (PluginInfoNative* buffer = dados) Conferir(ScanVst3(path, buffer, (uint)dados.Length, out quantidade));
        }
        var resultado = new List<PluginInfo>();
        fixed (PluginInfoNative* buffer = dados)
        {
            for (var i = 0; i < Math.Min(quantidade, (uint)dados.Length); i++)
            {
                var p = buffer + i;
                resultado.Add(new PluginInfo(Texto(p->Path, 1024), Texto(p->ClassId, 33),
                    Texto(p->Name, 128), Texto(p->Vendor, 128), Texto(p->Version, 64)));
            }
        }
        return resultado;
    }

    private static unsafe string Texto(byte* dados, int capacidade)
    {
        var texto = new ReadOnlySpan<byte>(dados, capacidade);
        var fim = texto.IndexOf((byte)0);
        return Encoding.UTF8.GetString(fim < 0 ? texto : texto[..fim]);
    }
}
