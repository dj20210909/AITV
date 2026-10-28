package com.fongmi.android.tv.utils;

import android.text.TextUtils;

import com.fongmi.android.tv.bean.Device;
import com.fongmi.android.tv.server.Auth;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Prefers;

import org.json.JSONObject;

import okhttp3.Call;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 跨设备（AITV ↔ AITV）配对令牌。
 *
 * <p>电视端开启配对鉴权后，{@code /action} 只接受携带令牌的请求。发起方（手机版或另一台电视）
 * 用对端显示的 6 位配对码换一次令牌并保存在本地，之后的同步/投屏请求自动带上。
 */
public class PeerAuth {

    private static final String PREFIX = "peer_token_";
    private static final long TIMEOUT = 8000;

    public static String get(Device device) {
        return Prefers.getString(PREFIX + key(device));
    }

    public static void put(Device device, String token) {
        if (TextUtils.isEmpty(token)) Prefers.remove(PREFIX + key(device));
        else Prefers.put(PREFIX + key(device), token);
    }

    public static void clear(Device device) {
        Prefers.remove(PREFIX + key(device));
    }

    /**
     * 构造带令牌的 POST 请求；没有令牌时退化为普通请求（对端会返回 401，由调用方引导配对）。
     */
    public static Call call(OkHttpClient client, Device device, String url, RequestBody body) {
        Request.Builder builder = new Request.Builder().url(url).post(body);
        String token = get(device);
        if (!TextUtils.isEmpty(token)) builder.header(Auth.HEADER, token);
        return client.newCall(builder.build());
    }

    /**
     * 用对端显示的 6 位配对码换取长期令牌。
     *
     * @return 配对成功（令牌已保存）返回 true。
     */
    public static boolean pair(Device device, String code) {
        if (device == null || TextUtils.isEmpty(code)) return false;
        String base = device.getIp();
        if (TextUtils.isEmpty(base)) return false;
        if (!base.startsWith("http")) base = "http://" + base;
        try {
            FormBody body = new FormBody.Builder().add("code", code.trim()).build();
            Request request = new Request.Builder().url(base + "/pair").post(body).build();
            try (Response response = OkHttp.client(TIMEOUT).newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) return false;
                String token = new JSONObject(response.body().string()).optString("token");
                if (TextUtils.isEmpty(token)) return false;
                put(device, token);
                return true;
            }
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    private static String key(Device device) {
        String uuid = device.getUuid();
        return TextUtils.isEmpty(uuid) ? device.getIp() : uuid;
    }
}
