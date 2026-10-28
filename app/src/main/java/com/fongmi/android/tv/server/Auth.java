package com.fongmi.android.tv.server;

import android.text.TextUtils;

import com.github.catvod.utils.Prefers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import fi.iki.elonen.NanoHTTPD.IHTTPSession;

/**
 * 局域网访问鉴权。
 *
 * <p>本机回环请求直接放行；其余来源必须携带配对令牌（请求头、查询参数或 Cookie），
 * 否则只能访问只读白名单端点。
 *
 * <p>令牌长期有效，配对码为 6 位数字、30 分钟轮换一次，且同一个来源连续失败 5 次会被锁定 10 分钟。
 */
public class Auth {

    public static final String HEADER = "x-aitv-token";
    public static final String PARAM = "token";
    public static final String COOKIE = "aitv_token";

    private static final String TOKEN_KEY = "server_auth_token";
    private static final String CODE_KEY = "server_pair_code";
    private static final String CODE_TIME_KEY = "server_pair_code_at";

    private static final long CODE_TTL = 30 * 60 * 1000L;
    private static final long LOCK_TTL = 10 * 60 * 1000L;
    private static final int MAX_FAILS = 5;

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();

    private static class Loader {

        static final Auth INSTANCE = new Auth();
    }

    public static Auth get() {
        return Loader.INSTANCE;
    }

    private static class Attempt {

        int fails;
        long at;
    }

    public synchronized String token() {
        String token = Prefers.getString(TOKEN_KEY);
        if (token.length() < 32) {
            token = randomHex();
            Prefers.put(TOKEN_KEY, token);
        }
        return token;
    }

    public synchronized String code() {
        String code = Prefers.getString(CODE_KEY);
        long at = Prefers.getLong(CODE_TIME_KEY);
        if (code.length() != 6 || System.currentTimeMillis() - at > CODE_TTL) return refresh();
        return code;
    }

    public synchronized String refresh() {
        String code = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
        Prefers.put(CODE_KEY, code);
        Prefers.put(CODE_TIME_KEY, System.currentTimeMillis());
        return code;
    }

    public String cookie() {
        return COOKIE + "=" + token() + "; Path=/; Max-Age=31536000; HttpOnly; SameSite=Lax";
    }

    /**
     * 吊销所有已配对设备：令牌与配对码同时重建，已下发的请求头和 Cookie 立即失效。
     */
    public synchronized void reset() {
        Prefers.remove(TOKEN_KEY);
        Prefers.remove(CODE_KEY);
        Prefers.remove(CODE_TIME_KEY);
        attempts.clear();
        token();
        code();
    }

    public boolean allow(IHTTPSession session) {
        return loopback(session) || paired(session) || isPublic(session.getUri().trim());
    }

    public boolean paired(IHTTPSession session) {
        String candidate = value(session);
        return !TextUtils.isEmpty(candidate) && verify(candidate);
    }

    public boolean locked(String ip) {
        Attempt attempt = attempts.get(key(ip));
        if (attempt == null) return false;
        synchronized (attempt) {
            if (attempt.fails < MAX_FAILS) return false;
            if (System.currentTimeMillis() - attempt.at <= LOCK_TTL) return true;
            attempt.fails = 0;
            return false;
        }
    }

    /**
     * @return 配对成功返回令牌，失败返回 null。
     */
    public String pair(String ip, String candidate) {
        if (TextUtils.isEmpty(candidate) || !verifyCode(candidate)) {
            fail(ip);
            return null;
        }
        succeed(ip);
        return token();
    }

    public boolean verifyCode(String candidate) {
        return candidate != null && candidate.length() == 6 && same(candidate, code());
    }

    public boolean verify(String candidate) {
        return candidate != null && candidate.length() >= 32 && same(candidate, token());
    }

    private static boolean same(String left, String right) {
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    private void fail(String ip) {
        Attempt attempt = attempts.computeIfAbsent(key(ip), k -> new Attempt());
        synchronized (attempt) {
            long now = System.currentTimeMillis();
            if (now - attempt.at > LOCK_TTL) attempt.fails = 0;
            attempt.fails++;
            attempt.at = now;
        }
        if (attempts.size() > 256) attempts.clear();
    }

    private void succeed(String ip) {
        attempts.remove(key(ip));
    }

    private static String key(String ip) {
        return ip == null ? "" : ip;
    }

    private String value(IHTTPSession session) {
        String header = session.getHeaders().get(HEADER);
        if (!TextUtils.isEmpty(header)) return header;
        String param = session.getParms().get(PARAM);
        if (!TextUtils.isEmpty(param)) return param;
        return cookieValue(session);
    }

    private String cookieValue(IHTTPSession session) {
        String header = session.getHeaders().get("cookie");
        if (TextUtils.isEmpty(header)) return null;
        for (String item : header.split(";")) {
            String[] pair = item.trim().split("=", 2);
            if (pair.length == 2 && COOKIE.equals(pair[0])) return pair[1];
        }
        return null;
    }

    private static final String[] ASSETS = {".css", ".js", ".map", ".png", ".jpg", ".jpeg", ".gif", ".svg", ".ico", ".webp", ".woff", ".woff2", ".ttf", ".html"};

    /**
     * 免鉴权的只读端点：设备发现、直播配置、媒体文件、代理、解析页、静态资源。
     * 这些要么必须被外部播放器/投屏设备直接访问，要么不含敏感状态。
     */
    private static boolean isPublic(String url) {
        if (url.equals("/pair") || url.equals("/device") || url.equals("/tvbus")) return true;
        if (url.equals("/file") || url.startsWith("/file/")) return true;
        if (url.startsWith("/image/") || url.startsWith("/parse") || url.startsWith("/proxy")) return true;
        if (url.equals("/assistant/info")) return true;
        // 静态资源（网页控制台外壳除外，未配对时要显示配对页）
        if (url.equals("/") || url.equals("/index.html")) return false;
        for (String asset : ASSETS) if (url.endsWith(asset)) return true;
        return false;
    }

    public static boolean loopback(IHTTPSession session) {
        String address = session.getRemoteIpAddress();
        if (address == null) return false;
        return address.startsWith("127.") || address.equals("::1") || address.equals("0:0:0:0:0:0:0:1");
    }

    private String randomHex() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        StringBuilder builder = new StringBuilder(32);
        for (byte value : bytes) builder.append(String.format(Locale.ROOT, "%02x", value));
        return builder.toString();
    }
}
