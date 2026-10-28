package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;

import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.ActivityPushBinding;
import com.fongmi.android.tv.server.Auth;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.QRCode;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Sniffer;
import com.fongmi.android.tv.utils.Util;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class PushActivity extends BaseActivity {

    private ActivityPushBinding mBinding;

    public static void start(Activity activity) {
        start(activity, 2);
    }

    public static void start(Activity activity, int tab) {
        Intent intent = new Intent(activity, PushActivity.class);
        intent.putExtra("tab", tab);
        activity.startActivity(intent);
    }

    private int getTab() {
        return getIntent().getIntExtra("tab", 2);
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityPushBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        mBinding.code.setImageBitmap(QRCode.getBitmap(Server.get().getAddress(getTab()), 250, 1));
        mBinding.info.setText(ResUtil.getString(R.string.push_info, Server.get().getAddress()));
        mBinding.pair.setText(ResUtil.getString(R.string.push_pair, Auth.get().code()));
    }

    @Override
    protected void initEvent() {
        mBinding.code.setOnClickListener(this::onCode);
        mBinding.clip.setOnClickListener(this::onClip);
        mBinding.pair.setOnClickListener(this::onPair);
        mBinding.pair.setOnLongClickListener(this::onReset);
    }

    private void onPair(View view) {
        mBinding.pair.setText(ResUtil.getString(R.string.push_pair, Auth.get().refresh()));
    }

    private boolean onReset(View view) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.pair_reset_title)
                .setMessage(R.string.pair_reset_message)
                .setPositiveButton(R.string.dialog_positive, (dialog, which) -> {
                    Auth.get().reset();
                    mBinding.pair.setText(ResUtil.getString(R.string.push_pair, Auth.get().code()));
                    Notify.show(R.string.pair_reset_done);
                })
                .setNegativeButton(R.string.dialog_negative, null)
                .show();
        return true;
    }

    private void onClip(View view) {
        CharSequence text = Util.getClipText();
        if (!TextUtils.isEmpty(text)) VideoActivity.start(this, Sniffer.getUrl(text.toString()));
    }

    private void onCode(View view) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setData(Uri.parse(Server.get().getAddress(getTab())));
        startActivity(intent);
    }
}
