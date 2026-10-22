using SmellsLikeTech.Converter.Core;
using SmellsLikeTech.Converter.Core.Options;
using SmellsLikeTech.Converter.Core.Studio;
using Xunit;

namespace SmellsLikeTech.Converter.Tests;

public class StudioCaptionsTests
{
    [Fact]
    public void TemposDoAudioFinalSaoPreservadosERecortados()
    {
        var captions=StudioCaptions.ParseSrt("1\n00:00:00,250 --> 00:00:01,500\n<b>Olá</b>\n\n2\n00:00:02,000 --> 00:00:05,000\nMundo",3);
        Assert.Equal(2,captions.Count);
        Assert.Equal((.25,1.5,"Olá"),(captions[0].Start,captions[0].End,captions[0].Text));
        Assert.Equal(3,captions[1].End);
        Assert.Equal(.85,captions[0].Y);
        Assert.Throws<ConversionException>(()=>StudioCaptions.ParseSrt("",3));
    }
    [Fact]
    public void SrtEValidadoNoContratoDoPlugin()
    {
        var p=StudioRules.NewProject("personalizado","texto") with {Clips=[new StudioClip {Id="c",File="v.mp4",Start=0,End=2}]};
        var options=new StudioExportOptions {Project=p,TranscriptionFormat="srt"};options.Validate();
        Assert.Equal("srt",options.OutputExtension);
        Assert.Throws<ConversionException>(()=>(options with {TranscriptionFormat="../../arquivo"}).Validate());
    }
}
