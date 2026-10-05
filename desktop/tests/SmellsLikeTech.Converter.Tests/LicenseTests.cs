using System.Security.Cryptography;
using System.Text;
using SmellsLikeTech.Converter.Core.Licensing;
using SmellsLikeTech.Converter.Core.Options;
using Xunit;

namespace SmellsLikeTech.Converter.Tests;

/// <summary>
/// A chave e assinada pelo site (WebCrypto, ECDSA P-256, r||s) e conferida aqui. Estes
/// testes assinam com um par gerado na hora, no mesmo formato, para provar que os dois
/// lados falam a mesma lingua.
/// </summary>
public class LicenseTests
{
    private static (string PublicKey, string Key) Sign(LicenseClaims claims, ECDsa? signer = null)
    {
        using var ecdsa = signer ?? ECDsa.Create(ECCurve.NamedCurves.nistP256);
        var payload = LicenseVerifier.Canonical(claims);
        var signature = ecdsa.SignData(payload, HashAlgorithmName.SHA256, DSASignatureFormat.IeeeP1363FixedFieldConcatenation);
        var publicKey = LicenseVerifier.ToBase64Url(ecdsa.ExportSubjectPublicKeyInfo());
        var key = $"{LicenseVerifier.Prefix}.{LicenseVerifier.ToBase64Url(payload)}.{LicenseVerifier.ToBase64Url(signature)}";
        return (publicKey, key);
    }

    private static LicenseClaims Claims(string plan = "app", string? expires = null) => new()
    {
        Version = 1,
        Id = "SLT-ABCD-EFGH",
        Plan = plan,
        Email = "cliente@exemplo.com",
        IssuedAt = "2026-09-24T10:00:00.000Z",
        ExpiresAt = expires,
        Machines = 3
    };

    [Fact]
    public void Verify_AceitaChaveAssinadaComAChavePublicaCerta()
    {
        var (publicKey, key) = Sign(Claims());

        var result = LicenseVerifier.Verify(key, publicKey);

        Assert.Equal(LicenseStatus.Valid, result.Status);
        Assert.NotNull(result.Claims);
        Assert.Equal("app", result.Claims!.Plan);
        Assert.True(result.Claims.UnlocksDesktop);
        Assert.False(result.Claims.UnlocksPro);
    }

    [Fact]
    public void Verify_SobreviveAEspacosEQuebrasDeLinha()
    {
        var (publicKey, key) = Sign(Claims());
        var pasted = $" {key[..30]}\r\n{key[30..80]} \n{key[80..]} ";

        Assert.Equal(LicenseStatus.Valid, LicenseVerifier.Verify(pasted, publicKey).Status);
    }

    [Fact]
    public void Verify_RecusaChaveDeOutroPar()
    {
        var (_, key) = Sign(Claims());
        var (otherPublicKey, _) = Sign(Claims());

        Assert.Equal(LicenseStatus.BadSignature, LicenseVerifier.Verify(key, otherPublicKey).Status);
    }

    [Fact]
    public void Verify_RecusaCargaAlterada()
    {
        var (publicKey, key) = Sign(Claims("pro"));
        var parts = key.Split('.');
        var tampered = LicenseVerifier.Canonical(Claims("completo"));
        var forged = $"{parts[0]}.{LicenseVerifier.ToBase64Url(tampered)}.{parts[2]}";

        Assert.Equal(LicenseStatus.BadSignature, LicenseVerifier.Verify(forged, publicKey).Status);
    }

    [Theory]
    [InlineData("")]
    [InlineData("abc")]
    [InlineData("SLT1.abc")]
    [InlineData("SLT2.a.b")]
    [InlineData("SLT1..")]
    public void Verify_RecusaLixoSemExcecao(string text)
    {
        var (publicKey, _) = Sign(Claims());
        Assert.Equal(LicenseStatus.Malformed, LicenseVerifier.Verify(text, publicKey).Status);
    }

    [Fact]
    public void Canonical_BateComOFormatoDoSite()
    {
        var canonical = Encoding.UTF8.GetString(LicenseVerifier.Canonical(Claims("pro", "2026-10-25T10:00:00.000Z")));

        Assert.Equal(
            "{\"v\":1,\"id\":\"SLT-ABCD-EFGH\",\"plano\":\"pro\",\"email\":\"cliente@exemplo.com\",\"emitida\":\"2026-09-24T10:00:00.000Z\",\"expira\":\"2026-10-25T10:00:00.000Z\",\"maquinas\":3}",
            canonical);
    }

    [Fact]
    public void TrialPolicy_SeteDiasDepoisPedeALicenca()
    {
        var first = new DateTimeOffset(2026, 9, 1, 12, 0, 0, TimeSpan.Zero);

        Assert.Equal(DesktopAccess.Trial, TrialPolicy.Evaluate(null, first, first.AddDays(2)).Access);
        Assert.Equal(5, TrialPolicy.Evaluate(null, first, first.AddDays(2)).TrialDaysLeft);
        Assert.Equal(DesktopAccess.TrialExpired, TrialPolicy.Evaluate("", first, first.AddDays(7)).Access);
        Assert.Equal(DesktopAccess.Trial, TrialPolicy.Evaluate(null, first, first.AddDays(-30)).Access);
    }

    [Fact]
    public void TrialPolicy_ChaveProNaoAbreOAplicativo()
    {
        // Assinada por um par de teste, nao pelo par real: o que importa e que uma chave
        // Pro, mesmo valida, nunca conte como licenca do aplicativo.
        var first = new DateTimeOffset(2026, 9, 1, 12, 0, 0, TimeSpan.Zero);
        var (_, key) = Sign(Claims("pro", "2099-01-01T00:00:00.000Z"));

        Assert.NotEqual(DesktopAccess.Licensed, TrialPolicy.Evaluate(key, first, first.AddDays(1)).Access);
    }

    [Theory]
    [InlineData("1-3, 7, 10-12", 12, new[] { 1, 2, 3, 7, 10, 11, 12 })]
    [InlineData("5-3", 10, new int[0])]
    [InlineData("", 3, new[] { 1, 2, 3 })]
    [InlineData("2, 2, 99", 5, new[] { 2 })]
    public void PageRanges_LeFaixasComoAsPessoasEscrevem(string text, int total, int[] expected) =>
        Assert.Equal(expected, PageRanges.Parse(text, total));

    [Theory]
    [InlineData("1-3, 7")]
    [InlineData("")]
    public void PdfSplitOptions_ValidaAsPaginas(string pages)
    {
        var options = new PdfSplitOptions { Mode = "pages", Pages = pages };
        if (string.IsNullOrWhiteSpace(pages))
        {
            Assert.Throws<SmellsLikeTech.Converter.Core.ConversionException>(options.Validate);
        }
        else
        {
            options.Validate();
        }
    }
}
