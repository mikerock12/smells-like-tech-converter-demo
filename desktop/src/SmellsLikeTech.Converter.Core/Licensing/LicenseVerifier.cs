using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace SmellsLikeTech.Converter.Core.Licensing;

/// <summary>A carga de uma chave de acesso, como o site a emite.</summary>
public sealed record LicenseClaims
{
    [JsonPropertyName("v")]
    public int Version { get; init; }

    /// <summary>Numero do pedido, "SLT-XXXX-XXXX".</summary>
    [JsonPropertyName("id")]
    public string Id { get; init; } = string.Empty;

    /// <summary>"pro", "app" ou "completo".</summary>
    [JsonPropertyName("plano")]
    public string Plan { get; init; } = string.Empty;

    [JsonPropertyName("email")]
    public string Email { get; init; } = string.Empty;

    [JsonPropertyName("emitida")]
    public string IssuedAt { get; init; } = string.Empty;

    /// <summary>ISO 8601, ou nulo quando vitalicia.</summary>
    [JsonPropertyName("expira")]
    public string? ExpiresAt { get; init; }

    [JsonPropertyName("maquinas")]
    public int Machines { get; init; }

    /// <summary>O aplicativo abre com "app" ou "completo". "pro" e do site e do plugin.</summary>
    public bool UnlocksDesktop => Plan is "app" or "completo";

    public bool UnlocksPro => Plan is "pro" or "completo";

    public bool IsExpired(DateTimeOffset now) =>
        ExpiresAt is not null
        && DateTimeOffset.TryParse(ExpiresAt, null, System.Globalization.DateTimeStyles.AssumeUniversal, out var expires)
        && expires < now;
}

public enum LicenseStatus
{
    /// <summary>Formato irreconhecivel: nao comeca com SLT1., falta parte, JSON estranho.</summary>
    Malformed = 0,

    /// <summary>Forma certa, assinatura errada: nao foi emitida por nos ou foi alterada.</summary>
    BadSignature = 1,

    Valid = 2
}

public sealed record LicenseVerification(LicenseStatus Status, LicenseClaims? Claims);

/// <summary>
/// Verifica chaves de acesso sem consultar servidor nenhum.
///
/// A chave e "SLT1.&lt;carga&gt;.&lt;assinatura&gt;": carga em JSON (base64url) e assinatura
/// ECDSA P-256 sobre SHA-256, em formato bruto r||s (64 bytes) — exatamente o que o
/// WebCrypto do site produz e o que <see cref="ECDsa.VerifyData(byte[], byte[], HashAlgorithmName)"/>
/// espera por padrao. A chave publica e a mesma que o site embute; a privada so o
/// Worker tem. Assim o aplicativo funciona sem internet, como prometido, e uma chave
/// nao pode ser alterada sem deixar de valer.
/// </summary>
public static class LicenseVerifier
{
    public const string Prefix = "SLT1";

    /// <summary>
    /// Chave publica (SPKI, base64url) do par gerado em 24/09/2026 com `npm run licencas:gerar`.
    /// E a mesma de packages/licenca/chave-publica.mjs; um teste do site confere que as duas
    /// batem. A privada correspondente vive so no segredo do Worker.
    /// </summary>
    public const string PublicKeyBase64Url = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEw7i4eDfAptC3Fl8WTK9sEz7-w8LATA7-gJbf0eo0_WEax7hi5Jp1o5gIghd7tCHzrMNUAycDGeiLD5yG9ojZ0A";

    private static readonly JsonSerializerOptions Json = new() { PropertyNameCaseInsensitive = false };

    public static LicenseVerification Verify(string? key) => Verify(key, PublicKeyBase64Url);

    public static LicenseVerification Verify(string? key, string publicKeyBase64Url)
    {
        var compact = new string((key ?? string.Empty).Where(character => !char.IsWhiteSpace(character)).ToArray());
        var parts = compact.Split('.');
        if (parts.Length != 3 || parts[0] != Prefix)
        {
            return new LicenseVerification(LicenseStatus.Malformed, null);
        }

        byte[] payload;
        byte[] signature;
        try
        {
            payload = FromBase64Url(parts[1]);
            signature = FromBase64Url(parts[2]);
        }
        catch (FormatException)
        {
            return new LicenseVerification(LicenseStatus.Malformed, null);
        }

        if (signature.Length != 64)
        {
            return new LicenseVerification(LicenseStatus.Malformed, null);
        }

        LicenseClaims? claims;
        try
        {
            claims = JsonSerializer.Deserialize<LicenseClaims>(payload, Json);
        }
        catch (JsonException)
        {
            return new LicenseVerification(LicenseStatus.Malformed, null);
        }

        if (claims is null || claims.Version != 1 || string.IsNullOrEmpty(claims.Id) || string.IsNullOrEmpty(claims.Plan))
        {
            return new LicenseVerification(LicenseStatus.Malformed, null);
        }

        // A carga precisa ser exatamente a serializacao canonica do site (chaves em ordem
        // fixa, sem espacos): e sobre esses bytes que a assinatura foi feita.
        if (!payload.AsSpan().SequenceEqual(Canonical(claims)))
        {
            return new LicenseVerification(LicenseStatus.Malformed, null);
        }

        byte[] publicKey;
        try
        {
            publicKey = FromBase64Url(publicKeyBase64Url);
        }
        catch (FormatException)
        {
            return new LicenseVerification(LicenseStatus.BadSignature, null);
        }

        try
        {
            using var ecdsa = ECDsa.Create();
            ecdsa.ImportSubjectPublicKeyInfo(publicKey, out _);
            var valid = ecdsa.VerifyData(payload, signature, HashAlgorithmName.SHA256, DSASignatureFormat.IeeeP1363FixedFieldConcatenation);
            return valid
                ? new LicenseVerification(LicenseStatus.Valid, claims)
                : new LicenseVerification(LicenseStatus.BadSignature, null);
        }
        catch (CryptographicException)
        {
            return new LicenseVerification(LicenseStatus.BadSignature, null);
        }
    }

    /// <summary>Mesma ordem e mesma forma que `serializarCarga` em packages/licenca/index.mjs.</summary>
    public static byte[] Canonical(LicenseClaims claims)
    {
        var builder = new StringBuilder();
        builder.Append("{\"v\":").Append(claims.Version);
        builder.Append(",\"id\":").Append(JsonSerializer.Serialize(claims.Id));
        builder.Append(",\"plano\":").Append(JsonSerializer.Serialize(claims.Plan));
        builder.Append(",\"email\":").Append(JsonSerializer.Serialize(claims.Email));
        builder.Append(",\"emitida\":").Append(JsonSerializer.Serialize(claims.IssuedAt));
        builder.Append(",\"expira\":").Append(claims.ExpiresAt is null ? "null" : JsonSerializer.Serialize(claims.ExpiresAt));
        builder.Append(",\"maquinas\":").Append(claims.Machines);
        builder.Append('}');
        return Encoding.UTF8.GetBytes(builder.ToString());
    }

    public static byte[] FromBase64Url(string text)
    {
        var normalized = text.Replace('-', '+').Replace('_', '/');
        var padded = normalized.PadRight(normalized.Length + (4 - normalized.Length % 4) % 4, '=');
        return Convert.FromBase64String(padded);
    }

    public static string ToBase64Url(ReadOnlySpan<byte> bytes) =>
        Convert.ToBase64String(bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_');
}
