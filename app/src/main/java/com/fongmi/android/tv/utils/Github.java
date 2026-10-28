package com.fongmi.android.tv.utils;

import android.os.Process;

/**
 * 自有更新渠道：nodeapp 文件共享的「应用商店」。
 *
 * <p>每个 flavor + ABI 组合对应一个 appKey，服务端返回该组合的最新版本：
 * <pre>
 * GET /api/file-sharing/app-store/aitv-leanback-arm64-v8a/update.json
 * </pre>
 * 响应字段：versionCode / versionName / updateContent / url / force / size / fileName / publishedAt。
 */
public class Github {

    public static final String URL = "https://nodeapp.uandj.site/api/file-sharing/app-store";

    public static String getAppKey(String flavor) {
        return "aitv-" + flavor + "-" + (Process.is64Bit() ? "arm64-v8a" : "armeabi-v7a");
    }

    public static String getUpdate(String flavor) {
        return URL + "/" + getAppKey(flavor) + "/update.json";
    }
}
