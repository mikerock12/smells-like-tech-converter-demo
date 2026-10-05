using SmellsLikeTech.Converter.Core.Licensing;

namespace SmellsLikeTech.Converter.Desktop.Services;

/// <summary>
/// A unica porta entre "clicar em converter" e a fila. Licenciado ou em teste passa;
/// com o teste vencido, a conversao nao entra e a tela explica onde ativar a chave.
/// </summary>
public static class LicenseGate
{
    public const string PurchaseUrl = "https://converter.smellsliketech.com.br/precos";

    public static bool CanConvert(out string message)
    {
        var access = App.Services.EvaluateDesktopAccess();
        switch (access.Access)
        {
            case DesktopAccess.Licensed:
                message = string.Empty;
                return true;
            case DesktopAccess.Trial:
                message = access.TrialDaysLeft == 1
                    ? "Último dia de teste. Depois disso, o aplicativo pede a licença em Configurações → Licença."
                    : $"Teste: faltam {access.TrialDaysLeft} dias. A licença vitalícia é ativada em Configurações → Licença.";
                return true;
            default:
                message = "Os 7 dias de teste acabaram. Compre a licença vitalícia em converter.smellsliketech.com.br/precos e cole a chave em Configurações → Licença.";
                return false;
        }
    }

    /// <summary>Uma linha para a tela de configuracoes.</summary>
    public static string Describe()
    {
        var access = App.Services.EvaluateDesktopAccess();
        return access.Access switch
        {
            DesktopAccess.Licensed when access.Claims is { } claims =>
                $"Licença vitalícia ativa · pedido {claims.Id} · {claims.Email}",
            DesktopAccess.Trial => $"Em teste: faltam {access.TrialDaysLeft} dia(s). Depois disso o aplicativo pede a chave.",
            _ => "Teste encerrado. Cole a chave da licença vitalícia para voltar a converter."
        };
    }
}
