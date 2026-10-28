package com.fongmi.android.tv.server.process;

import com.fongmi.android.tv.server.Auth;
import com.fongmi.android.tv.server.Nano;
import com.fongmi.android.tv.server.impl.Process;
import com.google.gson.JsonObject;

import java.util.Map;

import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Response;

/**
 * 配对端点：用电视上显示的 6 位配对码换取长期令牌。
 *
 * <p>GET/POST /pair?code=123456 → {"token":"..."} 并下发 Cookie。
 * 同一来源连续失败 5 次会被锁定 10 分钟。
 */
public class Pair implements Process {

    private static final String PATH = "/pair";

    @Override
    public boolean isRequest(IHTTPSession session, String url) {
        return url.equals(PATH);
    }

    @Override
    public Response doResponse(IHTTPSession session, String url, Map<String, String> files) {
        String ip = session.getRemoteIpAddress();
        if (Auth.get().locked(ip)) return fail("too many attempts");
        String token = Auth.get().pair(ip, session.getParms().get("code"));
        if (token == null) return fail("invalid code");
        JsonObject json = new JsonObject();
        json.addProperty("token", token);
        Response response = Nano.ok(json.toString());
        response.addHeader("Set-Cookie", Auth.get().cookie());
        return response;
    }

    private Response fail(String message) {
        JsonObject json = new JsonObject();
        json.addProperty("error", message);
        return Nano.error(Response.Status.FORBIDDEN, json.toString());
    }
}
