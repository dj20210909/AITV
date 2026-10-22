package com.aitv.assistant;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimal HTTP client for the AITV LAN API.
 *
 * <p>Supports both the legacy 5.6.x endpoints (/device, /action, /media) and the newer 5.7.x
 * endpoints (/remote, /remote/info). No Origin header is sent, so the app's same-origin check
 * accepts native requests.
 */
public final class AitvClient {

    private static final String USER_AGENT = "AITVAssistant/0.1";

    private AitvClient() {
    }

    public static AitvDevice probe(String host, int port) {
        String base = "http://" + host + ":" + port;
        JSONObject device = getJson(base + "/device", 900);
        if (device == null) return null;
        String uuid = device.optString("uuid", "");
        String name = device.optString("name", "");
        if (uuid.isEmpty() && name.isEmpty()) return null;

        JSONObject remoteInfo = getJson(base + "/remote/info", 900);
        boolean modern = remoteInfo != null;
        String version = modern ? remoteInfo.optString("version", "") : "";
        String variant = modern ? remoteInfo.optString("variant", "") : "";

        boolean assistant = false;
        if (!modern || version.isEmpty()) {
            JSONObject info = getJson(base + "/assistant/info", 1200);
            if (info != null) {
                assistant = true;
                if (version.isEmpty()) version = info.optString("version", "");
                if (variant.isEmpty()) variant = info.optString("variant", "");
            }
        }
        if (variant.isEmpty()) variant = device.optInt("type", 0) == 0 ? "leanback" : "mobile";
        int type = device.optInt("type", 0);
        return new AitvDevice(uuid, name.isEmpty() ? host : name, base, type, version, variant, modern, assistant);
    }

    /** GET /remote (new) or /media (legacy). */
    public static JSONObject playback(AitvDevice device) {
        if (device.modern) {
            JSONObject state = getJson(device.baseUrl + "/remote", 1800);
            if (state != null) return state;
        }
        return getJson(device.baseUrl + "/media", 1800);
    }

    public static boolean control(AitvDevice device, String type) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("type", type);
        return control(device, params);
    }

    public static boolean seek(AitvDevice device, long positionMs) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("type", "seek");
        params.put("position", String.valueOf(Math.max(0, positionMs)));
        return control(device, params);
    }

    public static boolean speed(AitvDevice device, float speed) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("type", "speed");
        params.put("speed", String.valueOf(speed));
        return control(device, params);
    }

    private static boolean control(AitvDevice device, Map<String, String> params) {
        String url = device.modern ? device.baseUrl + "/remote" : device.baseUrl + "/action?do=control";
        return postText(url, params, 2500) != null;
    }

    public static boolean pushConfig(AitvDevice device, String text, String name) {
        return pushConfig(device, text, name, 0);
    }

    /**
     * @param type 0 = VOD, 1 = live, 2 = wallpaper. Only used by the /assistant extension;
     *             the legacy /action endpoint always treats the text as a VOD config URL.
     */
    public static boolean pushConfig(AitvDevice device, String text, String name, int type) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("text", text == null ? "" : text);
        if (name != null && !name.isEmpty()) params.put("name", name);
        if (device.assistant) {
            params.put("type", String.valueOf(type));
            String result = postText(device.baseUrl + "/assistant/config", params, 22000);
            return result != null;
        }
        return postText(device.baseUrl + "/action?do=setting", params, 5000) != null;
    }

    public static boolean pushUrl(AitvDevice device, String url) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("url", url == null ? "" : url);
        return postText(device.baseUrl + "/action?do=push", params, 3000) != null;
    }

    /** GET /assistant/configs: saved config history for VOD/live/wallpaper. */
    public static JSONObject configs(AitvDevice device) {
        return getJson(device.baseUrl + "/assistant/configs", 10000);
    }

    /** POST /assistant/config/activate: load a saved config by its Room id. */
    public static boolean activateConfig(AitvDevice device, int id) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("id", String.valueOf(id));
        return postText(device.baseUrl + "/assistant/config/activate", params, 22000) != null;
    }

    /** POST /assistant/config/delete: delete a saved config by its Room id. */
    public static boolean deleteConfig(AitvDevice device, int id) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("id", String.valueOf(id));
        return postText(device.baseUrl + "/assistant/config/delete", params, 5000) != null;
    }

    public static boolean search(AitvDevice device, String word) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("word", word == null ? "" : word);
        return postText(device.baseUrl + "/action?do=search", params, 3000) != null;
    }

    /** GET /assistant/live (this repository's read-only extension). */
    public static JSONObject live(AitvDevice device, String keyword) {
        String path = "/assistant/live";
        if (keyword != null && !keyword.trim().isEmpty()) path += "?keyword=" + encode(keyword.trim());
        return getJson(device.baseUrl + path, 6000);
    }

    /** GET /assistant/sites (configured VOD sources). */
    public static JSONObject sites(AitvDevice device) {
        return getJson(device.baseUrl + "/assistant/sites", 10000);
    }

    /**
     * GET /assistant/search: parallel search across configured VOD sites.
     *
     * @param site  optional site key; empty means all searchable sites
     * @param quick true to use only sites with quickSearch enabled
     */
    public static JSONObject search(AitvDevice device, String word, String site, boolean quick) {
        StringBuilder path = new StringBuilder("/assistant/search?word=").append(encode(word));
        if (site != null && !site.isEmpty()) path.append("&site=").append(encode(site));
        path.append("&quick=").append(quick ? "1" : "0");
        return getJson(device.baseUrl + path, 32000);
    }

    /** POST /assistant/vod/open: open a VOD detail/play page on the TV. */
    public static boolean openVod(AitvDevice device, String site, String id, String name, String pic) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("site", site == null ? "" : site);
        params.put("id", id == null ? "" : id);
        params.put("name", name == null ? "" : name);
        params.put("pic", pic == null ? "" : pic);
        return postText(device.baseUrl + "/assistant/vod/open", params, 5000) != null;
    }

    /** GET /assistant/vod/detail: flags + episode names (no play URLs). */
    public static JSONObject vodDetail(AitvDevice device, String site, String id) {
        String path = "/assistant/vod/detail?site=" + encode(site == null ? "" : site)
                + "&id=" + encode(id == null ? "" : id);
        return getJson(device.baseUrl + path, 30000);
    }

    /** POST /assistant/vod/resolve: resolve a specific flag + episode to a local playable URL. */
    public static JSONObject resolveVod(
            AitvDevice device,
            String site,
            String id,
            String flag,
            String episode
    ) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("site", site == null ? "" : site);
        params.put("id", id == null ? "" : id);
        params.put("flag", flag == null ? "" : flag);
        params.put("episode", episode == null ? "" : episode);
        return postJson(device.baseUrl + "/assistant/vod/resolve", params, 45000);
    }

    /** POST /assistant/vod/play: play a specific source flag + episode on the TV. */
    public static boolean playVod(            AitvDevice device,
            String site,
            String id,
            String name,
            String pic,
            String flag,
            String episode
    ) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("site", site == null ? "" : site);
        params.put("id", id == null ? "" : id);
        params.put("name", name == null ? "" : name);
        params.put("pic", pic == null ? "" : pic);
        params.put("flag", flag == null ? "" : flag);
        params.put("episode", episode == null ? "" : episode);
        return postText(device.baseUrl + "/assistant/vod/play", params, 45000) != null;
    }

    /** Send one virtual remote key (5.7.x). */
    public static boolean sendKey(AitvDevice device, String key) {
        if (!device.modern) return false;
        Map<String, String> params = new LinkedHashMap<>();
        params.put("key", key);
        params.put("action", "press");
        return postText(device.baseUrl + "/remote/key", params, 2500) != null;
    }

    public static JSONObject getJson(String url, int timeoutMs) {
        HttpURLConnection connection = null;
        try {
            connection = open(url, "GET", timeoutMs);
            int code = connection.getResponseCode();
            String text = read(code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream());
            if (code < 200 || code >= 300 || text.isEmpty()) return null;
            return new JSONObject(text);
        } catch (Exception ignored) {
            ignored.printStackTrace();
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    public static JSONObject postJson(String url, Map<String, String> params, int timeoutMs) {
        String text = postText(url, params, timeoutMs);
        if (text == null || text.isEmpty()) return null;
        try {
            return new JSONObject(text);
        } catch (Exception ignored) {
            return null;
        }
    }

    public static String postText(String url, Map<String, String> params, int timeoutMs) {
        HttpURLConnection connection = null;
        try {
            connection = open(url, "POST", timeoutMs);
            byte[] data = encodeParams(params).getBytes(StandardCharsets.UTF_8);
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8");
            connection.setFixedLengthStreamingMode(data.length);
            connection.setDoOutput(true);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(data);
            }
            int code = connection.getResponseCode();
            String text = read(code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream());
            if (code < 200 || code >= 300) return null;
            return text.isEmpty() ? "OK" : text;
        } catch (Exception ignored) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static HttpURLConnection open(String url, String method, int timeoutMs) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(timeoutMs);
        connection.setReadTimeout(timeoutMs);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setRequestProperty("Accept", "application/json, text/plain, */*");
        connection.setInstanceFollowRedirects(true);
        return connection;
    }

    private static String encodeParams(Map<String, String> params) {
        StringBuilder builder = new StringBuilder();
        if (params != null) {
            for (Map.Entry<String, String> entry : params.entrySet()) {
                if (builder.length() > 0) builder.append('&');
                builder.append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));
            }
        }
        return builder.toString();
    }

    public static String encode(String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private static String read(InputStream input) {
        if (input == null) return "";
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = stream.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toString(StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return "";
        }
    }
}
