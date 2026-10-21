package androidx.media3.ui.libass;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import androidx.media3.common.Player;
import androidx.media3.exoplayer.libass.LibassPlaybackSession;
import androidx.media3.exoplayer.libass.LibassSubtitleController;
import androidx.media3.ui.CaptionStyleCompat;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.SubtitleView;

import java.util.function.Consumer;

/**
 * Compatibility view controller for the legacy libass session.
 *
 * <p>The public libass snapshot exposes {@link LibassPlaybackSession#attachSubtitleView} rather than
 * the newer style-aware controller. The view is attached to the PlayerView so the session's clock
 * renderer can draw ASS/SSA subtitles. Advanced style/font overrides are not available in the
 * legacy session and are accepted as no-ops.
 */
public final class LibassPlayerViewController implements AutoCloseable {

    private final LibassPlaybackSession playbackSession;
    private final LibassSubtitleController subtitleController;
    private View subtitleView;

    public LibassPlayerViewController(
            Player player,
            LibassPlaybackSession playbackSession,
            LibassSubtitleController subtitleController) {
        this.playbackSession = playbackSession;
        this.subtitleController = subtitleController;
    }

    public void bind(PlayerView playerView) {
        if (subtitleView != null || playerView == null) return;
        View view = playbackSession.attachSubtitleView(playerView.getContext());
        if (view == null) return;
        subtitleView = view;
        playerView.addView(view, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    public void setStyleOverride(CaptionStyleCompat style, String fontFamily) {
        // Not supported by the legacy session.
    }

    public void setSecondarySubtitleViewConfigurator(Consumer<SubtitleView> configurator) {
        // Secondary subtitles are not supported by the legacy session.
    }

    @Override
    public void close() {
        View view = subtitleView;
        subtitleView = null;
        if (view == null) return;
        ViewParent parent = view.getParent();
        if (parent instanceof ViewGroup group) group.removeView(view);
        playbackSession.detachSubtitleView(view);
    }
}
