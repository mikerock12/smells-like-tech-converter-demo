namespace SmellsLikeTech.Converter.Desktop.Services;

/// <summary>Dados fixos da marca e da empresa, usados na abertura e na tela Sobre.</summary>
public static class BrandInfo
{
    public const string ProductName = "Smells Like Tech Converter";
    public const string Author = "Criado por Maicon Nunes";
    public const string Company = "Smells Like Tech Informática";
    public const string Cnpj = "CNPJ 30.054.253/0001-09";
    public const string SiteDisplay = "www.smellsliketech.com.br";
    public const string SiteUrl = "https://www.smellsliketech.com.br";
    public const string Tagline = "Converta arquivos com velocidade, qualidade e tecnologia.";

    public static string Version =>
        typeof(BrandInfo).Assembly.GetName().Version?.ToString(3) ?? "0.1.0";

    /// <summary>Caminho absoluto de um arquivo da pasta Assets, ao lado do executável.</summary>
    public static string AssetPath(string fileName) =>
        Path.Combine(AppContext.BaseDirectory, "Assets", fileName);

    public static Uri AssetUri(string fileName) => new(AssetPath(fileName));
}
