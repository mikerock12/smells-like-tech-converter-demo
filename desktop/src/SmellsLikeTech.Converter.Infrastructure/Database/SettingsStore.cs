using System.Globalization;
using System.Text.Json;
using SmellsLikeTech.Converter.Core.Media;
using SmellsLikeTech.Converter.Core.Options;
using SmellsLikeTech.Converter.Core.Presets;
using SmellsLikeTech.Converter.Infrastructure.Configuration;

namespace SmellsLikeTech.Converter.Infrastructure.Database;

/// <summary>Configuracoes, presets do usuario e ultimos diretorios usados.</summary>
public sealed class SettingsStore(ConverterDatabase database)
{
    private const string SettingsKey = "app";

    private static readonly JsonSerializerOptions Json = new() { WriteIndented = false };

    public async Task<AppSettings> LoadAsync(CancellationToken cancellationToken)
    {
        await using var connection = await database.OpenAsync(cancellationToken);
        await using var command = connection.CreateCommand();
        command.CommandText = "SELECT value FROM settings WHERE key = $key;";
        command.Parameters.AddWithValue("$key", SettingsKey);

        if (await command.ExecuteScalarAsync(cancellationToken) is not string json)
        {
            return new AppSettings();
        }

        try
        {
            return JsonSerializer.Deserialize<AppSettings>(json, Json) ?? new AppSettings();
        }
        catch (JsonException)
        {
            return new AppSettings();
        }
    }

    public async Task SaveAsync(AppSettings settings, CancellationToken cancellationToken)
    {
        await using var connection = await database.OpenAsync(cancellationToken);
        await using var command = connection.CreateCommand();
        command.CommandText =
            """
            INSERT INTO settings (key, value) VALUES ($key, $value)
            ON CONFLICT(key) DO UPDATE SET value = excluded.value;
            """;
        command.Parameters.AddWithValue("$key", SettingsKey);
        command.Parameters.AddWithValue("$value", JsonSerializer.Serialize(settings, Json));
        await command.ExecuteNonQueryAsync(cancellationToken);
    }

    public async Task<IReadOnlyList<ConversionPreset>> LoadPresetsAsync(CancellationToken cancellationToken)
    {
        await using var connection = await database.OpenAsync(cancellationToken);
        await using var command = connection.CreateCommand();
        command.CommandText =
            "SELECT id, name, description, input_kind, options_json FROM user_presets ORDER BY created_at DESC;";

        var presets = new List<ConversionPreset>();
        await using var reader = await command.ExecuteReaderAsync(cancellationToken);
        while (await reader.ReadAsync(cancellationToken))
        {
            var options = JobOptionsJson.Deserialize(reader.GetString(4));
            if (options is null)
            {
                continue;
            }

            presets.Add(new ConversionPreset
            {
                Id = reader.GetString(0),
                Name = reader.GetString(1),
                Description = reader.GetString(2),
                InputKind = (MediaKind)reader.GetInt32(3),
                Options = options,
                IsBuiltIn = false
            });
        }

        return presets;
    }

    public async Task SavePresetAsync(ConversionPreset preset, CancellationToken cancellationToken)
    {
        await using var connection = await database.OpenAsync(cancellationToken);
        await using var command = connection.CreateCommand();
        command.CommandText =
            """
            INSERT INTO user_presets (id, name, description, input_kind, options_json, created_at)
            VALUES ($id, $name, $description, $inputKind, $optionsJson, $createdAt)
            ON CONFLICT(id) DO UPDATE SET
                name = excluded.name,
                description = excluded.description,
                input_kind = excluded.input_kind,
                options_json = excluded.options_json;
            """;
        command.Parameters.AddWithValue("$id", preset.Id);
        command.Parameters.AddWithValue("$name", preset.Name);
        command.Parameters.AddWithValue("$description", preset.Description);
        command.Parameters.AddWithValue("$inputKind", (int)preset.InputKind);
        command.Parameters.AddWithValue("$optionsJson", JobOptionsJson.Serialize(preset.Options));
        command.Parameters.AddWithValue("$createdAt", DateTimeOffset.Now.ToString("o", CultureInfo.InvariantCulture));
        await command.ExecuteNonQueryAsync(cancellationToken);
    }

    public async Task DeletePresetAsync(string presetId, CancellationToken cancellationToken)
    {
        await using var connection = await database.OpenAsync(cancellationToken);
        await using var command = connection.CreateCommand();
        command.CommandText = "DELETE FROM user_presets WHERE id = $id;";
        command.Parameters.AddWithValue("$id", presetId);
        await command.ExecuteNonQueryAsync(cancellationToken);
    }

    public async Task RememberFolderAsync(string path, CancellationToken cancellationToken)
    {
        if (string.IsNullOrWhiteSpace(path))
        {
            return;
        }

        await using var connection = await database.OpenAsync(cancellationToken);
        await using var command = connection.CreateCommand();
        command.CommandText =
            """
            INSERT INTO recent_folders (path, used_at) VALUES ($path, $usedAt)
            ON CONFLICT(path) DO UPDATE SET used_at = excluded.used_at;
            """;
        command.Parameters.AddWithValue("$path", path);
        command.Parameters.AddWithValue("$usedAt", DateTimeOffset.Now.ToString("o", CultureInfo.InvariantCulture));
        await command.ExecuteNonQueryAsync(cancellationToken);
    }

    public async Task<IReadOnlyList<string>> RecentFoldersAsync(int limit, CancellationToken cancellationToken)
    {
        await using var connection = await database.OpenAsync(cancellationToken);
        await using var command = connection.CreateCommand();
        command.CommandText = "SELECT path FROM recent_folders ORDER BY used_at DESC LIMIT $limit;";
        command.Parameters.AddWithValue("$limit", Math.Clamp(limit, 1, 50));

        var folders = new List<string>();
        await using var reader = await command.ExecuteReaderAsync(cancellationToken);
        while (await reader.ReadAsync(cancellationToken))
        {
            folders.Add(reader.GetString(0));
        }

        return folders;
    }
}
