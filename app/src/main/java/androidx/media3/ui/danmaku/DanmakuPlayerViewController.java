package androidx.media3.ui.danmaku;

import android.net.Uri;
import android.view.ViewGroup;

import androidx.annotation.Nullable;
import androidx.media3.ui.PlayerView;

import okhttp3.OkHttpClient;

/**
 * Compatibility controller for the legacy danmaku view.
 *
 * <p>Wraps {@link DanmakuController} + {@link DanmakuView} so the newer app code can bind the
 * overlay to a PlayerView, apply a config/source and send live comments.
 */
public final class DanmakuPlayerViewController implements AutoCloseable {

    private final DanmakuController controller = new DanmakuController();

    @Nullable
    private DanmakuView view;
    @Nullable
    private DanmakuConfig config;
    @Nullable
    private Uri dataSource;
    private boolean enabled = true;

    public void bind(PlayerView playerView) {
        if (playerView == null) return;
        if (view == null) {
            view = new DanmakuView(playerView.getContext());
            playerView.addView(view, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            controller.setView(view);
            if (config != null) controller.setConfig(config);
            if (dataSource != null) controller.setDataSource(dataSource);
            controller.setEnabled(enabled);
        }
        controller.setPlayer(playerView.getPlayer());
    }

    public void setOkHttpClient(OkHttpClient client) {
        controller.setOkHttpClient(client);
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (view != null) controller.setEnabled(enabled);
    }

    public void setConfig(DanmakuConfig config) {
        this.config = config;
        if (view != null) controller.setConfig(config);
    }

    public void setDataSource(@Nullable Uri dataSource) {
        this.dataSource = dataSource;
        if (view != null) controller.setDataSource(dataSource);
    }

    public void sendNow(String text) {
        controller.sendNow(text);
    }

    @Override
    public void close() {
        controller.release();
        DanmakuView current = view;
        view = null;
        if (current == null) return;
        if (current.getParent() instanceof ViewGroup group) group.removeView(current);
        current.release();
    }
}
