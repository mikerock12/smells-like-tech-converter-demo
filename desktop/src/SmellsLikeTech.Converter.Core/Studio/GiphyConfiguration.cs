using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;

namespace SmellsLikeTech.Converter.Core.Studio;

/// <summary>Ofuscação de cliente, sem promessa de sigilo contra inspeção em execução.</summary>
public static class GiphyConfiguration
{
    public static string Open(string? value)
    {
        if(string.IsNullOrEmpty(value)) return "";
        if(!value.StartsWith("v1.",StringComparison.Ordinal)) return Valid(value)?value:"";
        try {
            var p=value.Split('.').Skip(1).Select(Convert.FromBase64String).ToArray();
            if(p.Length!=5||p[0].Length!=12||p[2].Length!=16||p[3].Length!=32||p[4].Length!=32) return "";
            for(var i=0;i<32;i++) p[3][i]^=p[4][i];
            var clear=new byte[p[1].Length];
            try {
                using var aes=new AesGcm(p[3],16);
                aes.Decrypt(p[0],p[1],p[2],clear);
                var text=Encoding.UTF8.GetString(clear);
                return Valid(text)?text:"";
            } finally {CryptographicOperations.ZeroMemory(clear);CryptographicOperations.ZeroMemory(p[3]);CryptographicOperations.ZeroMemory(p[4]);}
        } catch(Exception e) when(e is FormatException or CryptographicException or ArgumentException) {return "";}
    }
    private static bool Valid(string value)=>Regex.IsMatch(value,"^[A-Za-z0-9_-]{16,256}$",RegexOptions.CultureInvariant);
}
