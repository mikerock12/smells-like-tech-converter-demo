using System.Collections.Concurrent;
using SmellsLikeTech.Converter.Core.Jobs;
using SmellsLikeTech.Converter.Core.Queue;

namespace SmellsLikeTech.Converter.Bridge.Hosting;

/// <summary>
/// Memoria curta dos jobs criados pelo site.
///
/// A fila do produto ja sabe executar e informar progresso; o que falta aqui e guardar
/// o ultimo retrato de cada job para responder a uma consulta a qualquer momento, e
/// apagar o arquivo que o navegador enviou assim que ele deixa de ser necessario.
/// </summary>
public sealed class JobRegistry : IDisposable
{
    private readonly ConversionQueue queue;
    private readonly ConcurrentDictionary<Guid, JobSnapshot> retratos = new();
    private readonly ConcurrentDictionary<Guid, List<string>> enviados = new();

    public JobRegistry(ConversionQueue queue)
    {
        this.queue = queue;
        queue.JobChanged += AoMudar;
    }

    /// <summary>Disparado a cada mudanca de qualquer job.</summary>
    public event EventHandler<JobSnapshot>? Changed;

    /// <summary>Anota um arquivo temporario recebido do navegador, para remover no fim. Pode haver varios por job.</summary>
    public void RegistrarEnvio(Guid jobId, string arquivoTemporario)
    {
        var lista = enviados.GetOrAdd(jobId, _ => []);
        lock (lista)
        {
            lista.Add(arquivoTemporario);
        }
    }

    public JobSnapshot? Encontrar(Guid id) =>
        retratos.TryGetValue(id, out var retrato) ? retrato : queue.Snapshot().FirstOrDefault(item => item.Id == id);

    public IReadOnlyList<JobSnapshot> Todos() => queue.Snapshot();

    private void AoMudar(object? remetente, JobSnapshot retrato)
    {
        retratos[retrato.Id] = retrato;

        if (retrato.Status.IsTerminal() && enviados.TryRemove(retrato.Id, out var temporarios))
        {
            string[] copia;
            lock (temporarios)
            {
                copia = [.. temporarios];
            }

            foreach (var temporario in copia)
            {
                ApagarSilenciosamente(temporario);
            }
        }

        Changed?.Invoke(this, retrato);
    }

    /// <summary>
    /// O arquivo temporario ja cumpriu o papel; se a remocao falhar por estar em uso,
    /// a limpeza de retencao do produto pega depois. Nao vale derrubar nada por isso.
    /// </summary>
    private static void ApagarSilenciosamente(string caminho)
    {
        try
        {
            if (File.Exists(caminho))
            {
                File.Delete(caminho);
            }

            var pasta = Path.GetDirectoryName(caminho);
            if (pasta is not null && Directory.Exists(pasta) && Directory.GetFileSystemEntries(pasta).Length == 0)
            {
                Directory.Delete(pasta);
            }
        }
        catch (IOException)
        {
        }
        catch (UnauthorizedAccessException)
        {
        }
    }

    public void Dispose() => queue.JobChanged -= AoMudar;
}
