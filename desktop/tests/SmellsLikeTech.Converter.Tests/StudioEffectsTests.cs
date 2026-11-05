using SmellsLikeTech.Converter.Core.Studio;
using Xunit;

namespace SmellsLikeTech.Converter.Tests;

/// <summary>Os mesmos números de tests/estudio-efeitos.test.mjs; a receita é conferida pelos casos do estúdio.</summary>
public sealed class StudioEffectsTests
{
    [Fact]
    public void SemEfeitoNaoMudaNada()
    {
        Assert.Equal("", StudioEffects.Filter(new StudioTransform(), 96, 64, 10, 2, "r"));
        Assert.False(StudioEffects.Has(new StudioTransform()));
        Assert.Equal(new StudioEffectState(0, 0, 1, 1, 0, 1, 0, 1, 0), StudioEffects.State(new StudioTransform(), .5, 2));
    }

    [Fact]
    public void AsJanelasNaoSeInvadem()
    {
        Assert.Equal((4.0, 1.0), StudioEffects.Windows(new StudioTransform { EntryEffect = "fade", EntryDuration = 4, ExitEffect = "cima", ExitDuration = 4 }, 5));
        Assert.Equal((0.0, 0.0), StudioEffects.Windows(new StudioTransform { EntryEffect = "maquina", EntryDuration = 2 }, 5));
        var filter = StudioEffects.Filter(new StudioTransform { EntryEffect = "cima", EntryDuration = 1, ExitEffect = "fade", ExitDuration = 1 }, 96, 64, 10, 5, "r");
        Assert.Contains("split=3", filter);
        Assert.Contains("trim=start=1:end=4,", filter);
        Assert.Contains("concat=n=3", filter);
    }

    [Fact]
    public void EstadoParteDeForaChegaESai()
    {
        var t = new StudioTransform { EntryEffect = "esquerda", EntryDuration = 1, ExitEffect = "zoom", ExitDuration = 1 };
        Assert.Equal(-1, StudioEffects.State(t, 0, 5).Dx);
        Assert.Equal(0, StudioEffects.State(t, 2, 5).Dx);
        Assert.True(StudioEffects.State(t, 4.99, 5).Sx < .1);
        Assert.Equal(0, StudioEffects.State(new StudioTransform { EntryEffect = "desintegrar", EntryDuration = 1 }, 0, 5).Particles);
        Assert.True(StudioEffects.State(new StudioTransform { ExitEffect = "estilhacos", ExitDuration = 1 }, 4.9, 5).Pieces > .5);
        Assert.Equal(Math.PI, StudioEffects.State(new StudioTransform { LoopEffect = "girar" }, 1, 5).Rotation, 9);
    }

    [Fact]
    public void QuadroParadoCongelaOEfeitoNumaTransformacaoComum()
    {
        var t = new StudioTransform { X = .3, Y = .4, Scale = .5, EntryEffect = "cima", EntryDuration = 1, ExitEffect = "fade", ExitDuration = 1 };
        var inicio = StudioEffects.Freeze(t, 0, 5, 100, 100);
        Assert.Equal(-.6, inicio.Y, 9);
        Assert.False(StudioEffects.Has(inicio));
        var meio = StudioEffects.Freeze(t, 2, 5, 100, 100);
        Assert.Equal((.3, .4, .5, 1.0), (meio.X, meio.Y, meio.Scale, meio.Opacity));
        Assert.Equal(.5, StudioEffects.Freeze(t, 4.5, 5, 100, 100).Opacity, 9);
        Assert.Null(StudioEffects.Freeze(new StudioTransform { LoopEffect = "girar" }, 3, 5, 100, 100).Problem());
    }

    [Fact]
    public void MaquinaDeEscreverContaLetrasSemQuebrarEmoji()
    {
        var text = new StudioText { Text = "Olá 🦝!", Start = 1, End = 5, Transform = new StudioTransform { EntryEffect = "maquina", EntryDuration = 1 } };
        Assert.Equal((6, 6, 1.0, 6.0), StudioEffects.TypewriterSteps(text, 30));
        Assert.Equal(1, StudioEffects.VisibleLetters(text, 0, 30));
        Assert.Equal(4, StudioEffects.VisibleLetters(text, .5, 30));
        Assert.Equal(6, StudioEffects.VisibleLetters(text, 2, 30));
    }

    [Fact]
    public void ValoresDesconhecidosSaoRecusados()
    {
        Assert.Equal("Efeito de entrada desconhecido.", new StudioTransform { EntryEffect = "explodir" }.Problem());
        Assert.Equal("Efeito de saída desconhecido.", new StudioTransform { ExitEffect = "maquina" }.Problem());
        Assert.Equal("A duração do efeito vai de 0,1 a 10 segundos.", new StudioTransform { ExitDuration = 20 }.Problem());
        Assert.False(StudioImmediatePreview.Accept(new StudioProject { Width = 64, Height = 64,
            Clips = [new StudioClip { Id = "c", File = "v.mp4", Kind = "video", Start = 0, End = 2, Fit = "caber", Transform = new StudioTransform { LoopEffect = "pulsar" } }] }));
    }
}
