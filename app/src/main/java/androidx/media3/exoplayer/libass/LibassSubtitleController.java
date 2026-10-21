package androidx.media3.exoplayer.libass;

import androidx.annotation.Nullable;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.exoplayer.text.SecondaryTextOutput;
import androidx.media3.exoplayer.trackselection.SecondaryTextTrackSelector;

import java.util.Collections;
import java.util.List;

/**
 * Compatibility shim for the secondary-subtitle controller.
 *
 * <p>The public libass snapshot only exposes a single subtitle pipeline. Secondary (dual) subtitle
 * selection is therefore kept as inert state; the primary subtitle path continues to work through
 * the libass clock renderer.
 */
public final class LibassSubtitleController implements AutoCloseable {

    @Nullable
    private TrackSelectionOverride primarySelection;
    @Nullable
    private TrackSelectionOverride secondarySelection;
    private boolean secondarySuppressed = true;

    public LibassSubtitleController(
            Player player,
            LibassPlaybackSession playbackSession,
            SecondaryTextTrackSelector.Factory trackSelectorFactory,
            SecondaryTextOutput textOutput) {
        // The legacy session does not expose secondary text tracks. Keep the reference alive so
        // callers which own the session can still release it in the normal order.
    }

    @Nullable
    public TrackSelectionOverride getPrimaryTextTrackSelectionOverride() {
        return primarySelection;
    }

    @Nullable
    public TrackSelectionOverride getSecondaryTextTrackSelectionOverride() {
        return secondarySelection;
    }

    public List<TrackSelectionOverride> getSecondaryTextTrackSelectionOverrides() {
        return secondarySelection == null ? Collections.emptyList() : List.of(secondarySelection);
    }

    public boolean isSecondaryTextTrackSuppressed() {
        return secondarySuppressed;
    }

    public void setSecondaryTextTrackSelectionOverride(@Nullable TrackSelectionOverride selection) {
        secondarySelection = selection;
        secondarySuppressed = selection == null;
    }

    public void setSecondaryTextTrackAutoSelectionEnabled(boolean enabled) {
        // No automatic secondary track selection in the legacy pipeline.
        if (enabled) secondarySuppressed = secondarySelection == null;
    }

    @Override
    public void close() {
        primarySelection = null;
        secondarySelection = null;
        secondarySuppressed = true;
    }
}
