package androidx.media3.exoplayer.text;

import androidx.media3.common.text.CueGroup;

/**
 * Compatibility no-op secondary text sink.
 *
 * <p>The legacy renderer factory accepts a {@link TextOutput} for a second subtitle renderer. The
 * public media snapshot does not provide the newer secondary-text pipeline, so cues are ignored
 * here while the primary (libass or Media3) subtitle renderer remains active.
 */
public final class SecondaryTextOutput implements TextOutput {

    @Override
    public void onCues(CueGroup cueGroup) {
        // Secondary subtitles are not supported by the legacy pipeline.
    }
}
