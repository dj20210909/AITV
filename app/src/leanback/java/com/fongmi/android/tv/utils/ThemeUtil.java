package com.fongmi.android.tv.utils;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.setting.Setting;

/**
 * 主题换肤：把用户选择的主题色映射成对应的 Theme.App.Xxx。
 *
 * <p>用固定的主题资源而不是 DynamicColors 运行时取色，避免 Activity 主题被替换后
 * AppCompat 校验失败（"You need to use a Theme.AppCompat theme"）。
 */
public class ThemeUtil {

    public static int style() {
        return styleOf(Setting.getThemeColor());
    }

    public static int styleOf(int color) {
        return switch (color) {
            case 0xFF6750A4 -> R.style.Theme_App_Purple;
            case 0xFF3949AB -> R.style.Theme_App_Indigo;
            case 0xFF1E88E5 -> R.style.Theme_App_Blue;
            case 0xFF00ACC1 -> R.style.Theme_App_Cyan;
            case 0xFF00897B -> R.style.Theme_App_Teal;
            case 0xFF43A047 -> R.style.Theme_App_Green;
            case 0xFF7CB342 -> R.style.Theme_App_LightGreen;
            case 0xFFFB8C00 -> R.style.Theme_App_Orange;
            case 0xFFE53935 -> R.style.Theme_App_Red;
            case 0xFFD81B60 -> R.style.Theme_App_Pink;
            case 0xFF8E24AA -> R.style.Theme_App_DeepPurple;
            case 0xFF6D4C41 -> R.style.Theme_App_Brown;
            default -> R.style.Theme_App;
        };
    }
}
