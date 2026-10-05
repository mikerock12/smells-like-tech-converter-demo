using System.Runtime.InteropServices;

namespace SmellsLikeTech.Converter.Infrastructure.Hardware;

/// <summary>
/// Quais placas de vídeo existem nesta máquina — de verdade.
///
/// A primeira versão lia as descrições de driver em
/// <c>HKLM\SYSTEM\CurrentControlSet\Control\Class\{4d36e968-…}</c>, e isso estava errado:
/// ali fica registrado todo driver que já foi instalado, presente ou não. Uma máquina que
/// um dia teve uma placa dedicada continuava anunciando essa placa para sempre, e o
/// usuário via na tela um hardware que não tem.
///
/// O DXGI é a fonte certa: ele enumera os adaptadores que o Windows realmente pode usar
/// para desenhar agora. É a mesma lista que um jogo ou um editor de vídeo consulta.
/// </summary>
public static class GpuInventory
{
    /// <summary>Adaptador de software da Microsoft: existe em toda máquina e não é placa.</summary>
    private const uint AdaptadorDeSoftware = 2;

    // O runner virtual da CI anuncia Basic Render mesmo sem o bit de software.
    public static bool IsSoftware(uint flags, string? name) =>
        (flags & AdaptadorDeSoftware) != 0 ||
        (name?.Contains("Basic Render", StringComparison.OrdinalIgnoreCase) ?? false);

    /// <summary>Placas presentes, na ordem em que o Windows as apresenta.</summary>
    public static IReadOnlyList<string> Present()
    {
        var placas = new List<string>();

        nint fabrica = 0;
        try
        {
            var iid = typeof(IDXGIFactory1).GUID;
            if (CreateDXGIFactory1(ref iid, out fabrica) != 0 || fabrica == 0)
            {
                return placas;
            }

            var factory = (IDXGIFactory1)Marshal.GetObjectForIUnknown(fabrica);
            try
            {
                for (uint indice = 0; ; indice++)
                {
                    // DXGI_ERROR_NOT_FOUND encerra a lista.
                    if (factory.EnumAdapters1(indice, out var adaptador) != 0 || adaptador is null)
                    {
                        break;
                    }

                    try
                    {
                        adaptador.GetDesc1(out var descricao);

                        var nome = descricao.Description?.Trim();
                        var eSoftware = IsSoftware(descricao.Flags, nome);

                        if (!eSoftware && !string.IsNullOrEmpty(nome) && !placas.Contains(nome))
                        {
                            placas.Add(nome);
                        }
                    }
                    finally
                    {
                        Marshal.ReleaseComObject(adaptador);
                    }
                }
            }
            finally
            {
                Marshal.ReleaseComObject(factory);
            }
        }
        catch (Exception excecao) when (excecao is COMException or InvalidCastException or DllNotFoundException
                                            or EntryPointNotFoundException)
        {
            // Sem DXGI utilizável. Melhor devolver vazio — e a interface dizer "não
            // identificada" — do que voltar a chutar um nome que pode não existir.
            return [];
        }

        return placas;
    }

    [DllImport("dxgi.dll", ExactSpelling = true)]
    private static extern int CreateDXGIFactory1(ref Guid riid, out nint factory);

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct AdapterDesc1
    {
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 128)]
        public string Description;

        public uint VendorId;
        public uint DeviceId;
        public uint SubSysId;
        public uint Revision;
        public nuint DedicatedVideoMemory;
        public nuint DedicatedSystemMemory;
        public nuint SharedSystemMemory;
        public long AdapterLuid;
        public uint Flags;
    }

    // A ordem dos métodos espelha a vtable COM: não reordenar, não remover, não pular.
    [ComImport]
    [Guid("770aae78-f26f-4dba-a829-253c83d1b387")]
    [InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IDXGIFactory1
    {
        // IDXGIObject
        void SetPrivateData(ref Guid name, uint dataSize, nint data);
        void SetPrivateDataInterface(ref Guid name, nint unknown);
        void GetPrivateData(ref Guid name, ref uint dataSize, nint data);
        void GetParent(ref Guid riid, out nint parent);

        // IDXGIFactory
        [PreserveSig] int EnumAdapters(uint index, out nint adapter);
        void MakeWindowAssociation(nint window, uint flags);
        void GetWindowAssociation(out nint window);
        void CreateSwapChain(nint device, nint description, out nint swapChain);
        void CreateSoftwareAdapter(nint module, out nint adapter);

        // IDXGIFactory1
        [PreserveSig] int EnumAdapters1(uint index, out IDXGIAdapter1 adapter);
        [PreserveSig] bool IsCurrent();
    }

    [ComImport]
    [Guid("29038f61-3839-4626-91fd-086879011a05")]
    [InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IDXGIAdapter1
    {
        // IDXGIObject
        void SetPrivateData(ref Guid name, uint dataSize, nint data);
        void SetPrivateDataInterface(ref Guid name, nint unknown);
        void GetPrivateData(ref Guid name, ref uint dataSize, nint data);
        void GetParent(ref Guid riid, out nint parent);

        // IDXGIAdapter
        [PreserveSig] int EnumOutputs(uint index, out nint output);
        void GetDesc(out nint description);
        void CheckInterfaceSupport(ref Guid name, out long umdVersion);

        // IDXGIAdapter1
        void GetDesc1(out AdapterDesc1 description);
    }
}
