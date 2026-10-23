using System.Text.Json;
using SmellsLikeTech.Converter.Core.Studio;
using Xunit;
namespace SmellsLikeTech.Converter.Tests;
public class GiphyConfigurationTests
{
    [Fact] public void SharedCipherAndTampering()
    {
        using var doc=JsonDocument.Parse(File.ReadAllText(Path.Combine(AppContext.BaseDirectory,"giphy-caso.json")));
        var root=doc.RootElement;
        var encoded=root.GetProperty("protegido").GetString()!;
        Assert.Equal(root.GetProperty("claro").GetString(),GiphyConfiguration.Open(encoded));
        var p=encoded.Split('.');p[2]=(p[2][0]=='A'?"B":"A")+p[2][1..];
        Assert.Equal("",GiphyConfiguration.Open(string.Join('.',p)));
        Assert.Equal("",GiphyConfiguration.Open("v1.invalido"));
    }
}
