package com.fongmi.android.tv.server;

import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.bean.Device;
import com.fongmi.android.tv.server.impl.Process;
import com.fongmi.android.tv.server.process.Action;
import com.fongmi.android.tv.server.process.Assistant;
import com.fongmi.android.tv.server.process.Cache;
import com.fongmi.android.tv.server.process.Image;
import com.fongmi.android.tv.server.process.Local;
import com.fongmi.android.tv.server.process.Media;
import com.fongmi.android.tv.server.process.Pair;
import com.fongmi.android.tv.server.process.Parse;
import com.fongmi.android.tv.server.process.Proxy;
import com.github.catvod.utils.Asset;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

public class Nano extends NanoHTTPD {

    private static final String INDEX = "index.html";
    private static final String PAIR = "pair.html";

    private List<Process> process;

    public Nano(int port) {
        super(port);
        addProcess();
    }

    private void addProcess() {
        process = new ArrayList<>();
        process.add(new Assistant());
        process.add(new Action());
        process.add(new Cache());
        process.add(new Image());
        process.add(new Local());
        process.add(new Media());
        process.add(new Pair());
        process.add(new Parse());
        process.add(new Proxy());
    }

    public static Response ok() {
        return ok("OK");
    }

    public static Response ok(String text) {
        return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, text);
    }

    public static Response error(String text) {
        return error(Response.Status.INTERNAL_ERROR, text);
    }

    public static Response error(Response.Status status, String text) {
        return newFixedLengthResponse(status, MIME_PLAINTEXT, text);
    }

    @Override
    public Response serve(IHTTPSession session) {
        String url = session.getUri().trim();
        Map<String, String> files = new HashMap<>();
        if (session.getMethod() == Method.POST) parse(session, files);
        if (!Auth.get().allow(session)) return deny(session, url);
        return authorize(session, dispatch(session, url, files));
    }

    private Response dispatch(IHTTPSession session, String url, Map<String, String> files) {
        if (url.startsWith("/tvbus")) return ok(LiveConfig.getResp());
        if (url.startsWith("/device")) return ok(Device.get().toString());
        for (Process process : process) if (process.isRequest(session, url)) return process.doResponse(session, url, files);
        return getAssets(url.substring(1));
    }

    /**
     * 未配对时的响应：浏览器导航给出配对页，其它客户端直接 401。
     */
    private Response deny(IHTTPSession session, String url) {
        if (session.getMethod() == Method.GET && acceptsHtml(session)) return getAssets(PAIR);
        return error(Response.Status.UNAUTHORIZED, "unauthorized");
    }

    /**
     * 通过 URL 携带令牌首次访问时，顺手种下 Cookie，让网页控制台的后续请求继续生效。
     */
    private Response authorize(IHTTPSession session, Response response) {
        if (response == null) return error(Response.Status.INTERNAL_ERROR, "empty response");
        if (Auth.get().verify(session.getParms().get(Auth.PARAM))) response.addHeader("Set-Cookie", Auth.get().cookie());
        return response;
    }

    private boolean acceptsHtml(IHTTPSession session) {
        String accept = session.getHeaders().get("accept");
        if (accept == null) return true;
        if (accept.contains("text/html")) return true;
        // curl 等命令行客户端默认只发 */*，也给它们配对页方便排查
        return accept.trim().equals("*/*");
    }

    private void parse(IHTTPSession session, Map<String, String> files) {
        try {
            String ct = session.getHeaders().get("content-type");
            if (ct != null) session.getHeaders().put("content-type", ct.replace("multipart/form-data", "multipart/form-data; charset=utf-8"));
            session.parseBody(files);
        } catch (Exception ignored) {
        }
    }

    private Response getAssets(String path) {
        try {
            if (path.isEmpty()) path = INDEX;
            InputStream is = Asset.open(path);
            return newFixedLengthResponse(Response.Status.OK, getMimeTypeForFile(path), is, -1);
        } catch (Exception e) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_HTML, null, 0);
        }
    }
}
