namespace SmellsLikeTech.Converter.Core;

/// <summary>
/// Erro de conversao com codigo estavel. O codigo e usado em logs, historico e,
/// futuramente, no contrato de erro do worker de nuvem; a mensagem e exibida ao usuario.
/// </summary>
public sealed class ConversionException : Exception
{
    public ConversionException(string code, string message, Exception? innerException = null)
        : base(message, innerException)
    {
        Code = code;
    }

    public string Code { get; }
}
