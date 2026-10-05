namespace SmellsLikeTech.Converter.Core.Licensing;

/// <summary>Em que pe o aplicativo esta: licenciado, em teste, ou com o teste vencido.</summary>
public enum DesktopAccess
{
    Licensed = 0,
    Trial = 1,
    TrialExpired = 2
}

public sealed record DesktopAccessState(DesktopAccess Access, int TrialDaysLeft, LicenseClaims? Claims);

/// <summary>
/// O aplicativo e pago, com licenca vitalicia, e comeca com sete dias de teste completo:
/// sem marca d'agua e sem funcao escondida. Depois disso, pede a chave.
/// </summary>
public static class TrialPolicy
{
    public const int TrialDays = 7;

    public static DesktopAccessState Evaluate(string? licenseKey, DateTimeOffset firstRunAt, DateTimeOffset now)
    {
        if (!string.IsNullOrWhiteSpace(licenseKey))
        {
            var verification = LicenseVerifier.Verify(licenseKey);
            if (verification.Status == LicenseStatus.Valid && verification.Claims is { UnlocksDesktop: true } claims
                && !claims.IsExpired(now))
            {
                return new DesktopAccessState(DesktopAccess.Licensed, 0, claims);
            }
        }

        var elapsed = now - firstRunAt;
        var daysLeft = TrialDays - (int)Math.Floor(elapsed.TotalDays);

        // Relogio voltado no tempo nao rende teste infinito: o primeiro uso e a data mais antiga que vale.
        if (elapsed < TimeSpan.Zero)
        {
            daysLeft = TrialDays;
        }

        return daysLeft > 0
            ? new DesktopAccessState(DesktopAccess.Trial, daysLeft, null)
            : new DesktopAccessState(DesktopAccess.TrialExpired, 0, null);
    }
}
