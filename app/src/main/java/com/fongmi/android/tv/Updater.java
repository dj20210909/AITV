package com.fongmi.android.tv;

import android.text.TextUtils;
import android.view.View;

import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.impl.UpdateListener;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.dialog.UpdateDialog;
import com.fongmi.android.tv.utils.Download;
import com.fongmi.android.tv.utils.FileUtil;
import com.fongmi.android.tv.utils.Github;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Path;

import org.json.JSONObject;

import java.io.File;

/**
 * 检查并安装自有更新渠道（nodeapp 应用商店）发布的新版本。
 *
 * <p>服务端按 flavor + ABI 返回 update.json，字段见 {@link Github}。
 * {@code force=true} 时更新为强制，隐藏取消按钮并禁止关闭。
 */
public class Updater implements Download.Callback, UpdateListener {

    /** 给内容长度留一点余量，避免服务端多给几个字节就判定失败。 */
    private static final long SLACK = 1024 * 1024;

    private Download download;
    private UpdateDialog dialog;
    private long size;
    private boolean mandatory;
    private boolean manual;

    private Updater() {
    }

    public static Updater create() {
        return new Updater();
    }

    private File getFile() {
        return Path.cache("update.apk");
    }

    private String getJson() {
        return Github.getUpdate(BuildConfig.FLAVOR);
    }

    /**
     * 用户主动点击「检查更新」：即使没有新版本也要给出反馈。
     */
    public Updater force() {
        Notify.show(R.string.update_check);
        Setting.putUpdate(true);
        manual = true;
        return this;
    }

    public void start(FragmentActivity activity) {
        if (!Setting.getUpdate()) return;
        Task.execute(() -> doInBackground(activity));
    }

    private void doInBackground(FragmentActivity activity) {
        try {
            JSONObject object = new JSONObject(OkHttp.string(getJson()));
            if (object.optInt("versionCode") <= BuildConfig.VERSION_CODE) {
                App.post(this::onLatest);
                return;
            }
            String url = object.optString("url");
            if (TextUtils.isEmpty(url)) throw new IllegalStateException("update url missing");
            String version = object.optString("versionName");
            String desc = object.optString("updateContent");
            size = object.optLong("size");
            mandatory = object.optBoolean("force");
            download = Download.create(url, getFile()).maxBytes(size > 0 ? size + SLACK : 0);
            App.post(() -> show(activity, version, desc));
        } catch (Exception e) {
            e.printStackTrace();
            App.post(this::onFailed);
        }
    }

    private void onLatest() {
        if (!manual) return;
        manual = false;
        Notify.show(R.string.update_latest);
    }

    private void onFailed() {
        if (!manual) return;
        manual = false;
        Notify.show(R.string.update_failed);
    }

    private void show(FragmentActivity activity, String version, String desc) {
        manual = false;
        dismiss();
        String content = TextUtils.isEmpty(desc) ? ResUtil.getString(R.string.update_version, version) : desc;
        dialog = UpdateDialog.create()
                .title(ResUtil.getString(R.string.update_version, version))
                .desc(content)
                .forced(mandatory)
                .listener(this)
                .show(activity);
    }

    @Override
    public void onConfirm(View view) {
        if (download == null) return;
        view.setEnabled(false);
        download.start(this);
    }

    @Override
    public void onCancel(View view) {
        if (mandatory) return;
        Setting.putUpdate(false);
        if (download != null) download.cancel();
        dismiss();
    }

    private void dismiss() {
        try {
            if (dialog != null) dialog.dismiss();
        } catch (Exception ignored) {
        }
    }

    @Override
    public void progress(int progress) {
        if (dialog != null) dialog.setProgress(progress);
    }

    @Override
    public void error(String msg) {
        // 原始异常信息（HTTP 404 / Download size limit exceeded 之类）只留在日志里，
        // 界面上给用户一句可读的提示。
        android.util.Log.w("Updater", "update failed: " + msg);
        Notify.show(ResUtil.getString(R.string.update_download_failed));
        dismiss();
    }

    @Override
    public void success(File file) {
        if (size > 0 && file.length() != size) {
            Path.clear(file);
            error(ResUtil.getString(R.string.update_broken));
            return;
        }
        FileUtil.openFile(file);
        dismiss();
    }
}
