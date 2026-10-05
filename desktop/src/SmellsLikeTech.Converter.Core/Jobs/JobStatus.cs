namespace SmellsLikeTech.Converter.Core.Jobs;

/// <summary>Ciclo de vida do job.</summary>
public enum JobStatus
{
    Queued = 0,
    Preparing = 1,
    Processing = 2,
    Finalizing = 3,
    Completed = 4,
    Failed = 5,
    Cancelled = 6
}

/// <summary>Etapa operacional dentro do ciclo de vida - usada para progresso e diagnostico.</summary>
public enum JobStage
{
    Waiting = 0,
    Validating = 1,
    Probing = 2,
    PreparingInput = 3,
    PreprocessingAudio = 4,
    Encoding = 5,
    Transcribing = 6,
    Synthesizing = 7,
    WritingOutput = 8,
    Cleaning = 9,
    Done = 10
}

public static class JobStateNames
{
    public static string Display(this JobStatus status) => status switch
    {
        JobStatus.Queued => "Aguardando",
        JobStatus.Preparing => "Preparando",
        JobStatus.Processing => "Convertendo",
        JobStatus.Finalizing => "Finalizando",
        JobStatus.Completed => "Concluído",
        JobStatus.Failed => "Falhou",
        JobStatus.Cancelled => "Cancelado",
        _ => status.ToString()
    };

    public static string Display(this JobStage stage) => stage switch
    {
        JobStage.Waiting => "na fila",
        JobStage.Validating => "validando",
        JobStage.Probing => "lendo o arquivo",
        JobStage.PreparingInput => "preparando entrada",
        JobStage.PreprocessingAudio => "preparando áudio",
        JobStage.Encoding => "codificando",
        JobStage.Transcribing => "transcrevendo",
        JobStage.Synthesizing => "sintetizando voz",
        JobStage.WritingOutput => "gravando saída",
        JobStage.Cleaning => "limpando temporários",
        JobStage.Done => "pronto",
        _ => stage.ToString()
    };

    public static bool IsTerminal(this JobStatus status) =>
        status is JobStatus.Completed or JobStatus.Failed or JobStatus.Cancelled;
}
