using SmellsLikeTech.Converter.Core;
using SmellsLikeTech.Converter.Core.Abstractions;

namespace SmellsLikeTech.Converter.Infrastructure.Storage;

/// <summary>
/// Pasta isolada por job em Temp\&lt;JobId&gt;\{input,work,output,temp}.
/// O nome enviado pelo cliente nunca vira nome de diretorio.
/// </summary>
public sealed class JobWorkspace : IJobWorkspace
{
    private JobWorkspace(string root, Guid jobId)
    {
        Root = Path.GetFullPath(root);
        JobId = jobId;
        JobDirectory = Path.Combine(Root, jobId.ToString("N"));
        InputDirectory = Path.Combine(JobDirectory, "input");
        WorkDirectory = Path.Combine(JobDirectory, "work");
        OutputDirectory = Path.Combine(JobDirectory, "output");
        TempDirectory = Path.Combine(JobDirectory, "temp");
    }

    public string Root { get; }
    public Guid JobId { get; }
    public string JobDirectory { get; }
    public string InputDirectory { get; }
    public string WorkDirectory { get; }
    public string OutputDirectory { get; }
    public string TempDirectory { get; }

    public static JobWorkspace Create(string root, Guid jobId)
    {
        var workspace = new JobWorkspace(root, jobId);
        Directory.CreateDirectory(workspace.Root);
        FileAccessGuard.EnsureNoReparsePoint(workspace.Root);

        if (Directory.Exists(workspace.JobDirectory))
        {
            throw new ConversionException("workspace_exists", "A pasta temporária deste job já existe.");
        }

        Directory.CreateDirectory(workspace.InputDirectory);
        Directory.CreateDirectory(workspace.WorkDirectory);
        Directory.CreateDirectory(workspace.OutputDirectory);
        Directory.CreateDirectory(workspace.TempDirectory);
        return workspace;
    }

    public string WorkPath(string fileName) => Contained(WorkDirectory, fileName);

    public string OutputPath(string fileName) => Contained(OutputDirectory, fileName);

    public string InputPath(string fileName) => Contained(InputDirectory, fileName);

    public bool Contains(string path)
    {
        var relative = Path.GetRelativePath(JobDirectory, Path.GetFullPath(path));
        return relative != ".."
            && !relative.StartsWith(".." + Path.DirectorySeparatorChar, StringComparison.Ordinal)
            && !Path.IsPathRooted(relative);
    }

    public void EnsureContained(string path)
    {
        if (!Contains(path))
        {
            throw new ConversionException("workspace_escape", "O caminho sairia da pasta do job.");
        }
    }

    private string Contained(string directory, string fileName)
    {
        if (fileName.Contains(Path.DirectorySeparatorChar) || fileName.Contains(Path.AltDirectorySeparatorChar))
        {
            throw new ConversionException("workspace_escape", "Nome de arquivo inválido dentro do job.");
        }

        var path = Path.Combine(directory, fileName);
        EnsureContained(path);
        return path;
    }

    /// <summary>
    /// Esperas entre as tentativas de apagar, em milissegundos. Matar o processo externo
    /// nao devolve os handles dele no mesmo instante: o Windows sinaliza a saida e fecha a
    /// tabela de handles logo depois, entao a primeira tentativa de remover o arquivo
    /// parcial ainda esbarra em "em uso por outro processo". Medido em conversao de video
    /// cancelada: a tentativa seguinte, 50 ms depois, ja apaga.
    /// </summary>
    private static readonly int[] RetryDelaysMs = [0, 25, 50, 100, 200, 400, 800];

    public void Dispose() => TryDelete();

    public bool TryDelete() => TryDelete(Thread.Sleep);

    /// <param name="wait">
    /// Como esperar entre uma tentativa e a seguinte. Fora dos testes e sempre
    /// <see cref="Thread.Sleep(int)"/>; os testes usam o gancho para soltar o arquivo preso
    /// exatamente depois de uma tentativa falhar, sem depender do relogio.
    /// </param>
    internal bool TryDelete(Action<int> wait)
    {
        if (!Directory.Exists(JobDirectory))
        {
            return true;
        }

        // Uma pasta que nao tem a forma esperada nao e apagada em hipotese alguma: este
        // metodo remove uma arvore inteira, e o alvo errado aqui custa caro.
        if (!Guid.TryParseExact(Path.GetFileName(JobDirectory), "N", out _)
            || !string.Equals(Path.GetDirectoryName(JobDirectory), Root, StringComparison.OrdinalIgnoreCase)
            || FileAccessGuard.TreeContainsReparsePoint(JobDirectory))
        {
            return false;
        }

        foreach (var delay in RetryDelaysMs)
        {
            if (delay > 0)
            {
                // A espera roda na propria tarefa do job, nunca na thread da interface, e
                // so acontece quando a pasta esta mesmo presa - o caso comum sai na primeira.
                wait(delay);
            }

            try
            {
                Directory.Delete(JobDirectory, recursive: true);
                return true;
            }
            catch (Exception exception) when (exception is IOException or UnauthorizedAccessException)
            {
                // Ainda preso. Tenta de novo depois da proxima espera. Acesso negado tambem
                // pode ser passageiro: o Windows recusa apagar um arquivo enquanto ainda ha
                // uma secao mapeada dele em memoria (processo morto que mapeou o arquivo,
                // visualizador, gerador de miniatura) e devolve ERROR_ACCESS_DENIED ate o
                // mapeamento sumir. A nova tentativa repete a mesma chamada, com o mesmo
                // usuario: nada de mexer em atributo, dono ou permissao para forcar a
                // remocao. Se o acesso for negado de verdade, as esperas acabam e o metodo
                // devolve falso como no arquivo preso para sempre.
            }
        }

        return false;
    }
}

public sealed class JobWorkspaceFactory(ConverterPaths paths) : IWorkspaceFactory
{
    public IJobWorkspace Create(Guid jobId) => JobWorkspace.Create(paths.Temp, jobId);
}
