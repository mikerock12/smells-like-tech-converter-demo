using System.Collections.ObjectModel;
using SmellsLikeTech.Converter.Core.Batch;
using SmellsLikeTech.Converter.Core.Media;
using SmellsLikeTech.Converter.Core.Operations;
using SmellsLikeTech.Converter.Core.Options;

namespace SmellsLikeTech.Converter.Desktop.ViewModels;

/// <summary>
/// Um arquivo dentro de um grupo do lote. Guarda a escolha individual de quem decidiu
/// configurar um por um; enquanto ninguem mexe, ele segue a escolha do grupo.
/// </summary>
public sealed class BatchFileViewModel(MediaInfo file) : ObservableObject
{
    private OperationDescriptor? operation;
    private JobOptions? options;

    internal MediaInfo File { get; } = file;

    public string Path => File.Path;

    public string Name => File.FileName;

    public string Detail
    {
        get
        {
            var parts = new List<string> { File.Extension.ToUpperInvariant(), File.SizeDisplay };
            if (File.Duration is { } duration)
            {
                parts.Add(MediaInfo.FormatDuration(duration));
            }

            if (File.Width is > 0 && File.Height is > 0)
            {
                parts.Add($"{File.Width}x{File.Height}");
            }

            return string.Join(" · ", parts);
        }
    }

    internal OperationDescriptor? Operation
    {
        get => operation;
        set
        {
            operation = value;
            Raise(nameof(Summary));
        }
    }

    internal JobOptions? Options
    {
        get => options;
        set
        {
            options = value;
            Raise(nameof(Summary));
        }
    }

    /// <summary>O que este arquivo vai virar, na linguagem do resumo do job.</summary>
    public string Summary => options is null
        ? "segue o grupo"
        : $"{operation?.Title ?? options.Operation} · {options.Summary()}";
}

/// <summary>
/// Um cartao do lote: os arquivos que aceitam as mesmas conversoes e a escolha feita
/// para eles - a mesma para todos, ou uma para cada.
/// </summary>
public sealed class BatchGroupViewModel : ObservableObject
{
    private bool individual;

    public BatchGroupViewModel(BatchGroup group)
    {
        Group = group;
        Signature = BatchPlanner.SignatureOf(group.Files[0]);
        Files = [.. group.Files.Select(file => new BatchFileViewModel(file))];
        Operations = [.. group.Operations.Select(descriptor => new OperationItemViewModel(descriptor))];
    }

    internal BatchGroup Group { get; private set; }

    /// <summary>Identidade do grupo entre uma soltura de arquivos e a seguinte.</summary>
    public string Signature { get; }

    public ObservableCollection<BatchFileViewModel> Files { get; }

    internal IReadOnlyList<OperationItemViewModel> Operations { get; }

    public bool Individual
    {
        get => individual;
        set => Set(ref individual, value);
    }

    public string Title => Group.Title;

    public string Detail => Group.Detail;

    public bool HasWork => Group.HasWork;

    /// <summary>
    /// O mesmo icone que a tela de um arquivo usa. Sao glifos da area privada da fonte
    /// Segoe Fluent Icons: em editor ou terminal que nao os desenhe eles parecem uma
    /// aspa vazia, e uma copia distraida apaga o icone sem quebrar a compilacao.
    /// </summary>
    public string Glyph => Group.Kind switch
    {
        MediaKind.Image => "",
        MediaKind.Video => "",
        MediaKind.Audio => "",
        MediaKind.Pdf => "",
        _ => ""
    };

    /// <summary>
    /// Absorve o grupo recalculado depois de novos arquivos entrarem, preservando o que
    /// ja tinha sido escolhido para cada arquivo que continua na lista.
    /// </summary>
    internal void Update(BatchGroup group)
    {
        Group = group;

        var known = Files.ToDictionary(file => file.Path, StringComparer.OrdinalIgnoreCase);
        Files.Clear();
        foreach (var file in group.Files)
        {
            Files.Add(known.TryGetValue(file.Path, out var existing) ? existing : new BatchFileViewModel(file));
        }

        Raise(nameof(Title));
        Raise(nameof(Detail));
    }

}
