namespace SmellsLikeTech.Converter.Core.Studio;

public static class StudioImmediatePreview
{
    public static bool Accept(StudioProject? project)
    {
        if (project is null || project.Clips.Count != 1) return false;
        var clip = project.Clips[0];
        if (clip.Kind != "video" || clip.Fit is not ("caber" or "preencher")) return false;
        if (project.Layers.Any(l => l.Visible) || project.Texts.Count > 0 || project.Audios.Count > 0 || StudioRules.HasTransitions(project)) return false;
        if (project.Background?.EndColor is not null || (clip.Volume ?? 1) is < 0 or > 1 || StudioRules.ClipSpeed(clip) != 1) return false;
        return clip.Transform is null || IsDefault(clip.Transform);
    }

    private static bool IsDefault(StudioTransform t) =>
        t.X == .5 && t.Y == .5 && t.Scale == 1 && t.Rotation == 0 && t.RotationX == 0 && t.RotationY == 0 &&
        !t.MirrorX && !t.MirrorY && t.Opacity == 1 && t.Blend == "normal" && t.Filter == "original" &&
        t.Brightness == 0 && t.Contrast == 0 && t.Saturation == 0 && !t.RemoveBackground &&
        t.Mask == "nenhuma" && t.Keyframes.Count == 0 && t.AnimationTime == 0;
}
