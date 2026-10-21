package androidx.media3.exoplayer.trackselection;

import android.content.Context;

/**
 * Compatibility track-selector factory for the secondary text renderer.
 *
 * <p>Delegates to the selector factory supplied by the caller (which applies the app's decode
 * preferences). Falls back to a plain {@link DefaultTrackSelector} when no delegate exists.
 */
public class SecondaryTextTrackSelector extends DefaultTrackSelector {

    public SecondaryTextTrackSelector(Context context) {
        super(context);
    }

    public static final class Factory implements TrackSelector.Factory {

        private final TrackSelector.Factory delegate;

        public Factory(TrackSelector.Factory delegate) {
            this.delegate = delegate;
        }

        @Override
        public TrackSelector createTrackSelector(Context context) {
            return delegate == null ? new SecondaryTextTrackSelector(context) : delegate.createTrackSelector(context);
        }
    }
}
