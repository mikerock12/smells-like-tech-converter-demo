using Microsoft.Data.Sqlite;
using SmellsLikeTech.Converter.Infrastructure.Storage;

namespace SmellsLikeTech.Converter.Infrastructure.Database;

/// <summary>
/// Banco local SQLite: historico, presets, configuracoes, modelos e ultimos diretorios.
/// </summary>
public sealed class ConverterDatabase
{
    private readonly string connectionString;

    public ConverterDatabase(ConverterPaths paths)
    {
        Directory.CreateDirectory(paths.Root);
        connectionString = new SqliteConnectionStringBuilder
        {
            DataSource = paths.DatabaseFile,
            Mode = SqliteOpenMode.ReadWriteCreate,
            Pooling = true
        }.ToString();
    }

    public async Task<SqliteConnection> OpenAsync(CancellationToken cancellationToken)
    {
        var connection = new SqliteConnection(connectionString);
        await connection.OpenAsync(cancellationToken);
        return connection;
    }

    public async Task InitializeAsync(CancellationToken cancellationToken)
    {
        await using var connection = await OpenAsync(cancellationToken);
        await ExecuteAsync(connection, "PRAGMA journal_mode=WAL;", cancellationToken);
        await ExecuteAsync(connection, "PRAGMA foreign_keys=ON;", cancellationToken);

        await ExecuteAsync(
            connection,
            """
            CREATE TABLE IF NOT EXISTS settings (
                key   TEXT PRIMARY KEY,
                value TEXT NOT NULL
            );
            """,
            cancellationToken);

        await ExecuteAsync(
            connection,
            """
            CREATE TABLE IF NOT EXISTS job_history (
                id              TEXT PRIMARY KEY,
                operation       TEXT NOT NULL,
                display_name    TEXT NOT NULL,
                options_summary TEXT NOT NULL,
                status          INTEGER NOT NULL,
                input_format    TEXT,
                output_format   TEXT,
                input_bytes     INTEGER NOT NULL DEFAULT 0,
                output_bytes    INTEGER NOT NULL DEFAULT 0,
                duration_seconds REAL,
                output_path     TEXT,
                error_code      TEXT,
                error_message   TEXT,
                engine          TEXT,
                created_at      TEXT NOT NULL,
                completed_at    TEXT,
                elapsed_seconds REAL NOT NULL DEFAULT 0
            );
            """,
            cancellationToken);

        await ExecuteAsync(
            connection,
            "CREATE INDEX IF NOT EXISTS ix_job_history_created ON job_history (created_at DESC);",
            cancellationToken);

        await ExecuteAsync(
            connection,
            """
            CREATE TABLE IF NOT EXISTS user_presets (
                id           TEXT PRIMARY KEY,
                name         TEXT NOT NULL,
                description  TEXT NOT NULL,
                input_kind   INTEGER NOT NULL,
                options_json TEXT NOT NULL,
                created_at   TEXT NOT NULL
            );
            """,
            cancellationToken);

        await ExecuteAsync(
            connection,
            """
            CREATE TABLE IF NOT EXISTS recent_folders (
                path    TEXT PRIMARY KEY,
                used_at TEXT NOT NULL
            );
            """,
            cancellationToken);

        await ExecuteAsync(
            connection,
            """
            CREATE TABLE IF NOT EXISTS installed_models (
                id           TEXT PRIMARY KEY,
                path         TEXT NOT NULL,
                size_bytes   INTEGER NOT NULL,
                installed_at TEXT NOT NULL
            );
            """,
            cancellationToken);
    }

    internal static async Task ExecuteAsync(SqliteConnection connection, string sql, CancellationToken cancellationToken)
    {
        await using var command = connection.CreateCommand();
        command.CommandText = sql;
        await command.ExecuteNonQueryAsync(cancellationToken);
    }
}
