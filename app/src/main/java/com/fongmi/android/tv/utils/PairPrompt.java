package com.fongmi.android.tv.utils;

import android.content.Context;
import android.text.InputType;
import android.widget.EditText;
import android.widget.FrameLayout;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Device;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * 跨设备配对提示：输入对端电视上显示的 6 位配对码。
 */
public class PairPrompt {

    public static void show(Context context, Device device, Runnable onPaired) {
        if (context == null || device == null) return;
        EditText input = new EditText(context);
        input.setHint(R.string.pair_code_hint);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setSingleLine(true);
        int padding = (int) (24 * context.getResources().getDisplayMetrics().density);
        FrameLayout container = new FrameLayout(context);
        container.setPadding(padding, padding / 2, padding, 0);
        container.addView(input);
        new MaterialAlertDialogBuilder(context)
                .setTitle(context.getString(R.string.pair_title, device.getName()))
                .setMessage(R.string.pair_message)
                .setView(container)
                .setPositiveButton(R.string.pair_confirm, (dialog, which) -> {
                    String code = input.getText().toString();
                    Task.execute(() -> {
                        boolean paired = PeerAuth.pair(device, code);
                        App.post(() -> {
                            if (paired) onPaired.run();
                            else Notify.show(R.string.pair_failed);
                        });
                    });
                })
                .setNegativeButton(R.string.dialog_negative, null)
                .show();
    }
}
