using Microsoft.UI.Xaml.Controls;
using Windows.Media.Core;
using Windows.Media.Playback;

namespace SmellsLikeTech.Converter.Desktop.Services;

internal static class PreviewMedia
{
    public static void Close(MediaPlayerElement element, MediaPlayer player)
    {
        MediaSource? source = null;
        try
        {
            source = player.Source as MediaSource;
            if (player.PlaybackSession.CanPause) player.Pause();
            element.Source = null;
        }
        catch (Exception e) { System.Diagnostics.Debug.WriteLine(e); }
        finally
        {
            // O controle ainda recebe eventos do player até ser desconectado.
            try { element.SetMediaPlayer(null); }
            catch (Exception e) { System.Diagnostics.Debug.WriteLine(e); }
            try { player.Dispose(); }
            catch (Exception e) { System.Diagnostics.Debug.WriteLine(e); }
            try { source?.Dispose(); }
            catch (Exception e) { System.Diagnostics.Debug.WriteLine(e); }
        }
    }
}
