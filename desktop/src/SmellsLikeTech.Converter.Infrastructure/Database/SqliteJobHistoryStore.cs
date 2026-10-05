using System.Globalization;
using Microsoft.Data.Sqlite;
using SmellsLikeTech.Converter.Core.Abstractions;
using SmellsLikeTech.Converter.Core.Jobs;

namespace SmellsLikeTech.Converter.Infrastructure.Database;

/// <summary>
/// Historico em SQLite. Guarda apenas metadados: nome do arquivo, formatos, tamanhos e tempos.
/// Conteudo de midia, texto de sintese e transcricao nunca sao gravados aqui.
/// </summary>
public sealed class SqliteJobHistoryStore(ConverterDatabase database) : IJobHistoryStore
{
    public async Task RecordAsync(JobHistoryEntry entry, CancellationToken cancellationToken)
    {
        await using var connection = await database.OpenAsync(cancellationToken);
        await using var command = connection.CreateCommand();
        command.CommandText =
            """
            INSERT INTO job_history (
                id, operation, display_name, options_summary, status, input_format, output_format,
                input_bytes, output_bytes, duration_seconds, output_path, error_code, error_message,
                engine, created_at, completed_at, elapsed_seconds)
            VALUES (
                $id, $operation, $displayName, $optionsSummary, $status, $inputFormat, $outputFormat,
                $inputBytes, $outputBytes, $durationSeconds, $outputPath, $errorCode, $errorMessage,
                $engine, $createdAt, $completedAt, $elapsedSeconds)
            ON CONFLICT(id) DO UPDATE SET
                status = excluded.status,
                output_bytes = excluded.output_bytes,
                output_path = excluded.output_path,
                error_code = excluded.error_code,
                error_message = excluded.error_message,
                completed_at = excluded.completed_at,
                elapsed_seconds = excluded.elapsed_seconds;
            """;

        command.Parameters.AddWithValue("$id", entry.Id.ToString("N"));
        command.Parameters.AddWithValue("$operation", entry.Operation);
        command.Parameters.AddWithValue("$displayName", entry.DisplayName);
        command.Parameters.AddWithValue("$optionsSummary", entry.OptionsSummary);
        command.Parameters.AddWithValue("$status", (int)entry.Status);
        command.Parameters.AddWithValue("$inputFormat", (object?)entry.InputFormat ?? DBNull.Value);
        command.Parameters.AddWithValue("$outputFormat", (object?)entry.OutputFormat ?? DBNull.Value);
        command.Parameters.AddWithValue("$inputBytes", entry.InputBytes);
        command.Parameters.AddWithValue("$outputBytes", entry.OutputBytes);
        command.Parameters.AddWithValue("$durationSeconds", (object?)entry.DurationSeconds ?? DBNull.Value);
        command.Parameters.AddWithValue("$outputPath", (object?)entry.OutputPath ?? DBNull.Value);
        command.Parameters.AddWithValue("$errorCode", (object?)entry.ErrorCode ?? DBNull.Value);
        command.Parameters.AddWithValue("$errorMessage", (object?)entry.ErrorMessage ?? DBNull.Value);
        command.Parameters.AddWithValue("$engine", (object?)entry.Engine ?? DBNull.Value);
        command.Parameters.AddWithValue("$createdAt", Iso(entry.CreatedAt));
        command.Parameters.AddWithValue(
            "$completedAt",
            entry.CompletedAt is null ? DBNull.Value : Iso(entry.CompletedAt.Value));
        command.Parameters.AddWithValue("$elapsedSeconds", entry.ElapsedSeconds);

        await command.ExecuteNonQueryAsync(cancellationToken);
    }

    public async Task<IReadOnlyList<JobHistoryEntry>> RecentAsync(int limit, CancellationToken cancellationToken)
    {
        await using var connection = await database.OpenAsync(cancellationToken);
        await using var command = connection.CreateCommand();
        command.CommandText =
            """
            SELECT id, operation, display_name, options_summary, status, input_format, output_format,
                   input_bytes, output_bytes, duration_seconds, output_path, error_code, error_message,
                   engine, created_at, completed_at, elapsed_seconds
            FROM job_history
            ORDER BY created_at DESC
            LIMIT $limit;
            """;
        command.Parameters.AddWithValue("$limit", Math.Clamp(limit, 1, 1000));

        var entries = new List<JobHistoryEntry>();
        await using var reader = await command.ExecuteReaderAsync(cancellationToken);
        while (await reader.ReadAsync(cancellationToken))
        {
            entries.Add(new JobHistoryEntry
            {
                Id = Guid.ParseExact(reader.GetString(0), "N"),
                Operation = reader.GetString(1),
                DisplayName = reader.GetString(2),
                OptionsSummary = reader.GetString(3),
                Status = (JobStatus)reader.GetInt32(4),
                InputFormat = Nullable(reader, 5),
                OutputFormat = Nullable(reader, 6),
                InputBytes = reader.GetInt64(7),
                OutputBytes = reader.GetInt64(8),
                DurationSeconds = reader.IsDBNull(9) ? null : reader.GetDouble(9),
                OutputPath = Nullable(reader, 10),
                ErrorCode = Nullable(reader, 11),
                ErrorMessage = Nullable(reader, 12),
                Engine = Nullable(reader, 13),
                CreatedAt = DateTimeOffset.Parse(reader.GetString(14), CultureInfo.InvariantCulture),
                CompletedAt = reader.IsDBNull(15)
                    ? null
                    : DateTimeOffset.Parse(reader.GetString(15), CultureInfo.InvariantCulture),
                ElapsedSeconds = reader.GetDouble(16)
            });
        }

        return entries;
    }

    public async Task ClearAsync(CancellationToken cancellationToken)
    {
        await using var connection = await database.OpenAsync(cancellationToken);
        await ConverterDatabase.ExecuteAsync(connection, "DELETE FROM job_history;", cancellationToken);
    }

    private static string? Nullable(SqliteDataReader reader, int ordinal) =>
        reader.IsDBNull(ordinal) ? null : reader.GetString(ordinal);

    private static string Iso(DateTimeOffset value) =>
        value.ToString("o", CultureInfo.InvariantCulture);
}
