using System.Collections.ObjectModel;
using Microsoft.UI.Dispatching;
using SmellsLikeTech.Converter.Core.Jobs;
using SmellsLikeTech.Converter.Core.Media;
using SmellsLikeTech.Converter.Core.Queue;

namespace SmellsLikeTech.Converter.Desktop.ViewModels;

/// <summary>Um job da fila visto pela interface.</summary>
public sealed class JobItemViewModel(JobSnapshot snapshot) : ObservableObject
{
    private JobSnapshot snapshot = snapshot;

    public Guid Id => snapshot.Id;

    public JobSnapshot Snapshot
    {
        get => snapshot;
        set
        {
            snapshot = value;
            Raise(nameof(Snapshot));
            Raise(nameof(DisplayName));
            Raise(nameof(StatusText));
            Raise(nameof(DetailText));
            Raise(nameof(ProgressValue));
            Raise(nameof(IsIndeterminate));
            Raise(nameof(IsRunning));
            Raise(nameof(IsPending));
            Raise(nameof(CanCancel));
            Raise(nameof(HasOutput));
            Raise(nameof(FirstOutput));
            Raise(nameof(StatusGlyph));
        }
    }

    public string DisplayName => snapshot.DisplayName;

    public string StatusText => snapshot.Status switch
    {
        JobStatus.Failed => $"Falhou · {snapshot.ErrorMessage ?? snapshot.ErrorCode ?? "erro"}",
        JobStatus.Completed => $"Concluído · {snapshot.Message ?? snapshot.OptionsSummary}",
        JobStatus.Cancelled => "Cancelado",
        JobStatus.Queued => "Aguardando na fila",
        _ => $"{snapshot.Status.Display()} · {snapshot.Stage.Display()}"
    };

    public string DetailText
    {
        get
        {
            var parts = new List<string> { snapshot.OptionsSummary };

            if (snapshot.Status == JobStatus.Processing)
            {
                if (snapshot.Progress is { } progress)
                {
                    parts.Add($"{progress * 100:0}%");
                }

                if (snapshot.SpeedFactor is { } speed)
                {
                    parts.Add($"{speed:0.0}x");
                }

                if (snapshot.Eta is { } eta)
                {
                    parts.Add($"faltam {MediaInfo.FormatDuration(eta)}");
                }
            }
            else if (snapshot.Status == JobStatus.Completed)
            {
                if (snapshot.Elapsed is { } elapsed)
                {
                    parts.Add($"em {elapsed.TotalSeconds:0.0}s");
                }

                if (snapshot.OutputBytes > 0)
                {
                    parts.Add(MediaInfo.FormatBytes(snapshot.OutputBytes));
                }
            }

            return string.Join(" · ", parts);
        }
    }

    public string StatusGlyph => snapshot.Status switch
    {
        JobStatus.Completed => "",
        JobStatus.Failed => "",
        JobStatus.Cancelled => "",
        JobStatus.Queued => "",
        _ => ""
    };

    public double ProgressValue => (snapshot.Progress ?? 0) * 100;

    public bool IsIndeterminate =>
        snapshot.Status is JobStatus.Preparing or JobStatus.Processing && snapshot.Progress is null;

    public bool IsRunning => snapshot.Status is JobStatus.Preparing or JobStatus.Processing or JobStatus.Finalizing;

    public bool IsPending => snapshot.Status == JobStatus.Queued;

    public bool CanCancel => !snapshot.Status.IsTerminal();

    public bool HasOutput => snapshot.OutputFiles.Count > 0;

    public string? FirstOutput => snapshot.OutputFiles.FirstOrDefault();
}

/// <summary>
/// Espelha a fila do nucleo em uma colecao observavel, sempre na thread da interface.
/// </summary>
public sealed class QueueViewModel : ObservableObject, IDisposable
{
    private readonly ConversionQueue queue;
    private readonly DispatcherQueue dispatcher;
    private bool isPaused;

    public QueueViewModel(ConversionQueue queue, DispatcherQueue dispatcher)
    {
        this.queue = queue;
        this.dispatcher = dispatcher;
        queue.JobChanged += OnJobChanged;
        queue.QueueChanged += OnQueueChanged;
        Refresh();
    }

    public ObservableCollection<JobItemViewModel> Jobs { get; } = [];

    public bool IsPaused
    {
        get => isPaused;
        private set => Set(ref isPaused, value);
    }

    public int ActiveCount => Jobs.Count(job => job.IsRunning);

    public int PendingCount => Jobs.Count(job => job.IsPending);

    public string SummaryText => IsPaused
        ? $"Fila pausada · {PendingCount} aguardando"
        : ActiveCount > 0
            ? $"{ActiveCount} em andamento · {PendingCount} aguardando"
            : PendingCount > 0
                ? $"{PendingCount} aguardando"
                : "Nada na fila";

    public void TogglePause()
    {
        if (queue.IsPaused)
        {
            queue.Resume();
        }
        else
        {
            queue.Pause();
        }

        IsPaused = queue.IsPaused;
        Raise(nameof(SummaryText));
    }

    public void Cancel(Guid id) => queue.Cancel(id);

    public void Move(Guid id, int offset) => queue.Move(id, offset);

    public void ClearFinished() => queue.ClearFinished();

    private void OnJobChanged(object? sender, JobSnapshot snapshot) =>
        dispatcher.TryEnqueue(() => Apply(snapshot));

    private void OnQueueChanged(object? sender, EventArgs e) =>
        dispatcher.TryEnqueue(Refresh);

    private void Apply(JobSnapshot snapshot)
    {
        var existing = Jobs.FirstOrDefault(job => job.Id == snapshot.Id);
        if (existing is null)
        {
            Jobs.Insert(0, new JobItemViewModel(snapshot));
        }
        else
        {
            existing.Snapshot = snapshot;
        }

        RaiseCounters();
    }

    private void Refresh()
    {
        var current = queue.Snapshot();
        var known = current.Select(snapshot => snapshot.Id).ToHashSet();

        for (var index = Jobs.Count - 1; index >= 0; index--)
        {
            if (!known.Contains(Jobs[index].Id))
            {
                Jobs.RemoveAt(index);
            }
        }

        for (var index = 0; index < current.Count; index++)
        {
            var snapshot = current[index];
            var existing = Jobs.FirstOrDefault(job => job.Id == snapshot.Id);
            if (existing is null)
            {
                Jobs.Insert(Math.Min(index, Jobs.Count), new JobItemViewModel(snapshot));
                continue;
            }

            existing.Snapshot = snapshot;
            var currentIndex = Jobs.IndexOf(existing);
            if (currentIndex != index && index < Jobs.Count)
            {
                Jobs.Move(currentIndex, index);
            }
        }

        IsPaused = queue.IsPaused;
        RaiseCounters();
    }

    private void RaiseCounters()
    {
        Raise(nameof(ActiveCount));
        Raise(nameof(PendingCount));
        Raise(nameof(SummaryText));
    }

    public void Dispose()
    {
        queue.JobChanged -= OnJobChanged;
        queue.QueueChanged -= OnQueueChanged;
    }
}
