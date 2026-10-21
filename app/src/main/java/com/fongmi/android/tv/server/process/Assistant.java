package com.fongmi.android.tv.server.process;

import android.content.Intent;
import android.text.Html;

import androidx.media3.common.C;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.api.config.WallConfig;
import com.fongmi.android.tv.bean.Channel;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.bean.Group;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Live;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.server.Nano;
import com.fongmi.android.tv.server.impl.Process;
import com.fongmi.android.tv.utils.Util;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Method;
import fi.iki.elonen.NanoHTTPD.Response;

/**
 * LAN assistant endpoints.
 *
 * <p>GET /assistant/info returns app/device/live-config summary.<br>
 * GET /assistant/live?group=&keyword= returns live groups and channels (no stream URLs).<br>
 * GET /assistant/sites lists configured VOD sites.<br>
 * GET /assistant/search?word=&site=&quick= searches VOD sites in parallel.<br>
 * POST /assistant/config pushes a VOD/live/wall config URL.<br>
 * POST /assistant/vod/open opens a VOD detail page on the TV.
 *
 * <p>Search/config responses do not expose play URLs, headers, cookies or DRM material. The
 * control endpoints are meant for a trusted LAN assistant app.
 */
public class Assistant implements Process {

    private static final int MAX_CHANNELS = 5000;

    @Override
    public boolean isRequest(IHTTPSession session, String url) {
        return url.equals("/assistant/info")
                || url.equals("/assistant/live")
                || url.equals("/assistant/config")
                || url.equals("/assistant/configs")
                || url.equals("/assistant/config/activate")
                || url.equals("/assistant/config/delete")
                || url.equals("/assistant/sites")
                || url.equals("/assistant/search")
                || url.equals("/assistant/vod/detail")
                || url.equals("/assistant/vod/resolve")
                || url.equals("/assistant/vod/play")
                || url.equals("/assistant/vod/open");
    }

    @Override
    public Response doResponse(IHTTPSession session, String url, Map<String, String> files) {
        try {
            if (url.equals("/assistant/config") || url.equals("/assistant/vod/open")
                    || url.equals("/assistant/vod/play") || url.equals("/assistant/vod/resolve")
                    || url.equals("/assistant/config/activate") || url.equals("/assistant/config/delete")) {
                if (session.getMethod() != Method.POST) return Nano.error("POST required");
                if (url.equals("/assistant/config")) return config(session.getParms());
                if (url.equals("/assistant/config/activate")) return activate(session.getParms());
                if (url.equals("/assistant/config/delete")) return delete(session.getParms());
                if (url.equals("/assistant/vod/play")) return play(session.getParms());
                if (url.equals("/assistant/vod/resolve")) return resolve(session.getParms());
                return open(session.getParms());
            }
            if (url.equals("/assistant/info")) return Nano.ok(info().toString());
            if (url.equals("/assistant/configs")) return Nano.ok(configs().toString());
            if (url.equals("/assistant/sites")) return Nano.ok(sites().toString());
            if (url.equals("/assistant/vod/detail")) return Nano.ok(vodDetail(session.getParms()).toString());
            if (url.equals("/assistant/search")) return Nano.ok(search(session.getParms()).toString());
            return Nano.ok(live(session.getParms()).toString());
        } catch (Throwable e) {
            e.printStackTrace();
            return Nano.error(e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private JsonObject sites() {
        VodConfig.get().ensureLoaded();
        JsonObject root = new JsonObject();
        JsonArray items = new JsonArray();
        for (Site site : VodConfig.get().getSites()) {
            JsonObject item = new JsonObject();
            item.addProperty("key", site.getKey());
            item.addProperty("name", site.getName());
            item.addProperty("searchable", site.isSearchable());
            item.addProperty("quickSearch", site.isQuickSearch());
            item.addProperty("hide", site.isHide());
            items.add(item);
        }
        root.addProperty("home", VodConfig.get().getHome().getKey());
        root.add("sites", items);
        return root;
    }

    private JsonObject search(Map<String, String> params) throws InterruptedException {
        VodConfig.get().ensureLoaded();
        String word = trim(params.get("word"));
        String siteKey = trim(params.get("site"));
        boolean quick = "1".equals(trim(params.get("quick"))) || "true".equalsIgnoreCase(trim(params.get("quick")));
        JsonObject root = new JsonObject();
        root.addProperty("word", word);
        if (word.isEmpty()) {
            root.addProperty("count", 0);
            root.add("sites", new JsonArray());
            root.add("items", new JsonArray());
            return root;
        }
        List<Site> targets = new ArrayList<>();
        for (Site site : VodConfig.get().getSites()) {
            if (!site.isSearchable()) continue;
            if (!siteKey.isEmpty() && !siteKey.equals(site.getKey())) continue;
            if (quick && !site.isQuickSearch()) continue;
            targets.add(site);
        }
        ConcurrentLinkedQueue<Hit> hits = new ConcurrentLinkedQueue<>();
        ConcurrentHashMap<String, Integer> counts = new ConcurrentHashMap<>();
        Set<String> seen = ConcurrentHashMap.newKeySet();
        AtomicInteger total = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(Math.max(1, Math.min(8, targets.size())));
        CountDownLatch latch = new CountDownLatch(targets.size());
        for (Site site : targets) {
            executor.execute(() -> {
                int local = 0;
                try {
                    Result result = SiteApi.searchContent(site, word, quick, "1");
                    for (Vod vod : result.getList()) {
                        if (local >= 60 || total.get() >= 300) break;
                        String id = vod.getId();
                        if (id.isEmpty()) continue;
                        if (!seen.add(site.getKey() + "|" + id)) continue;
                        hits.add(new Hit(site, vod));
                        local++;
                        total.incrementAndGet();
                    }
                } catch (Throwable ignored) {
                } finally {
                    counts.put(site.getKey(), local);
                    latch.countDown();
                }
            });
        }
        latch.await(25, TimeUnit.SECONDS);
        executor.shutdownNow();
        JsonArray siteArray = new JsonArray();
        for (Site site : targets) {
            JsonObject item = new JsonObject();
            item.addProperty("key", site.getKey());
            item.addProperty("name", site.getName());
            item.addProperty("count", counts.getOrDefault(site.getKey(), 0));
            siteArray.add(item);
        }
        JsonArray items = new JsonArray();
        for (Hit hit : hits) {
            JsonObject item = new JsonObject();
            item.addProperty("siteKey", hit.site.getKey());
            item.addProperty("siteName", hit.site.getName());
            item.addProperty("vodId", hit.vod.getId());
            item.addProperty("vodName", hit.vod.getName());
            item.addProperty("vodPic", hit.vod.getPic());
            item.addProperty("vodRemarks", hit.vod.getRemarks());
            item.addProperty("typeName", hit.vod.getTypeName());
            item.addProperty("vodYear", hit.vod.getYear());
            item.addProperty("vodArea", hit.vod.getArea());
            items.add(item);
        }
        root.addProperty("count", items.size());
        root.add("sites", siteArray);
        root.add("items", items);
        return root;
    }

    private Response open(Map<String, String> params) {
        String site = trim(params.get("site"));
        String id = trim(params.get("id"));
        if (site.isEmpty() || id.isEmpty()) return Nano.error("Missing site or id");
        return startVideo(site, id, trim(params.get("name")), trim(params.get("pic")), trim(params.get("mark")));
    }

    private Response play(Map<String, String> params) {
        String site = trim(params.get("site"));
        String id = trim(params.get("id"));
        String flagName = trim(params.get("flag"));
        String episodeName = trim(params.get("episode"));
        if (site.isEmpty() || id.isEmpty()) return Nano.error("Missing site or id");
        if (flagName.isEmpty() || episodeName.isEmpty()) return Nano.error("Missing flag or episode");
        try {
            VodConfig.get().ensureLoaded();
            Result result = SiteApi.detailContent(site, id);
            Vod vod = result.getVod();
            if (result.getList().isEmpty()) return Nano.error("Detail not found");
            Flag targetFlag = null;
            Episode targetEpisode = null;
            List<Flag> flags = vod.getFlags() == null ? List.of() : vod.getFlags();
            for (Flag flag : flags) {
                if (flag == null || !flag.getFlag().equals(flagName)) continue;
                List<Episode> episodes = flag.getEpisodes() == null ? List.of() : flag.getEpisodes();
                for (Episode episode : episodes) {
                    if (episode == null || !episode.getName().equals(episodeName)) continue;
                    targetFlag = flag;
                    targetEpisode = episode;
                    break;
                }
                if (targetEpisode != null) break;
            }
            if (targetFlag == null || targetEpisode == null) return Nano.error("Flag or episode not found");
            String name = trim(params.get("name"));
            String pic = trim(params.get("pic"));
            if (name.isEmpty()) name = vod.getName();
            if (pic.isEmpty()) pic = vod.getPic();
            saveHistory(site, id, name, pic, targetFlag.getFlag(), targetEpisode);
            return startVideo(site, id, name, pic, targetEpisode.getName());
        } catch (Throwable e) {
            return Nano.error(e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private Response resolve(Map<String, String> params) {
        String site = trim(params.get("site"));
        String id = trim(params.get("id"));
        String flagName = trim(params.get("flag"));
        String episodeName = trim(params.get("episode"));
        if (site.isEmpty() || id.isEmpty()) return Nano.error("Missing site or id");
        if (flagName.isEmpty() || episodeName.isEmpty()) return Nano.error("Missing flag or episode");
        try {
            VodConfig.get().ensureLoaded();
            Result detail = SiteApi.detailContent(site, id);
            if (detail.getList().isEmpty()) return Nano.error("Detail not found");
            Vod vod = detail.getVod();
            Flag targetFlag = null;
            Episode targetEpisode = null;
            List<Flag> flags = vod.getFlags() == null ? List.of() : vod.getFlags();
            for (Flag flag : flags) {
                if (flag == null || !flag.getFlag().equals(flagName)) continue;
                List<Episode> episodes = flag.getEpisodes() == null ? List.of() : flag.getEpisodes();
                for (Episode episode : episodes) {
                    if (episode == null || !episode.getName().equals(episodeName)) continue;
                    targetFlag = flag;
                    targetEpisode = episode;
                    break;
                }
                if (targetEpisode != null) break;
            }
            if (targetFlag == null || targetEpisode == null) return Nano.error("Flag or episode not found");
            Result result = SiteApi.playerContent(site, targetFlag.getFlag(), targetEpisode.getUrl());
            String url = result.getRealUrl();
            if (url.isEmpty()) return Nano.error("Empty play url");
            String ip = com.github.catvod.utils.Util.getIp();
            if (!ip.isEmpty()) {
                url = url.replace("://127.0.0.1", "://" + ip).replace("://localhost", "://" + ip);
            }
            JsonObject root = new JsonObject();
            root.addProperty("url", url);
            root.addProperty("title", vod.getName());
            root.addProperty("episode", targetEpisode.getName());
            root.addProperty("flag", targetFlag.getFlag());
            String format = result.getFormat();
            if (format == null || format.isEmpty()) {
                String probe = targetEpisode.getUrl() + " " + url;
                if (probe.contains("m3u8")) format = "hls";
                else if (probe.contains(".mpd")) format = "dash";
            }
            root.addProperty("format", format);
            root.addProperty("parse", result.isUseParse() || result.needParse());
            JsonObject headers = new JsonObject();
            for (Map.Entry<String, String> entry : result.getHeader().entrySet()) {
                headers.addProperty(entry.getKey(), entry.getValue());
            }
            root.add("headers", headers);
            return Nano.ok(root.toString());
        } catch (Throwable e) {
            e.printStackTrace();
            return Nano.error(e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private JsonObject vodDetail(Map<String, String> params) throws Throwable {        String siteKey = trim(params.get("site"));
        String id = trim(params.get("id"));
        if (siteKey.isEmpty() || id.isEmpty()) throw new Exception("Missing site or id");
        VodConfig.get().ensureLoaded();
        Result result = SiteApi.detailContent(siteKey, id);
        if (result.getList().isEmpty()) throw new Exception(trim(result.getMsg()).isEmpty() ? "Detail not found" : result.getMsg());
        Vod vod = result.getVod();
        JsonObject root = new JsonObject();
        root.addProperty("siteKey", siteKey);
        Site site = VodConfig.get().getSite(siteKey);
        root.addProperty("siteName", site.getName());
        root.addProperty("vodId", vod.getId());
        root.addProperty("vodName", vod.getName());
        root.addProperty("vodPic", vod.getPic());
        root.addProperty("vodRemarks", vod.getRemarks());
        root.addProperty("typeName", vod.getTypeName());
        root.addProperty("vodYear", vod.getYear());
        root.addProperty("vodArea", vod.getArea());
        root.addProperty("content", cleanHtml(vod.getContent()));
        JsonArray flags = new JsonArray();
        List<Flag> flagItems = vod.getFlags() == null ? List.of() : vod.getFlags();
        for (Flag flag : flagItems) {
            if (flag == null) continue;
            JsonObject item = new JsonObject();
            item.addProperty("flag", flag.getFlag());
            item.addProperty("show", flag.getShow());
            JsonArray episodes = new JsonArray();
            List<Episode> episodeItems = flag.getEpisodes() == null ? List.of() : flag.getEpisodes();
            for (Episode episode : episodeItems) {
                if (episode == null) continue;
                JsonObject child = new JsonObject();
                child.addProperty("name", episode.getName());
                child.addProperty("desc", episode.getDesc());
                episodes.add(child);
            }
            item.add("episodes", episodes);
            flags.add(item);
        }
        root.add("flags", flags);
        return root;
    }

    private void saveHistory(String site, String id, String name, String pic, String flag, Episode episode) {
        String key = site + AppDatabase.SYMBOL + id + AppDatabase.SYMBOL + VodConfig.getCid();
        History history = History.find(key);
        if (history == null) {
            history = new History();
            history.setKey(key);
            history.setCid(VodConfig.getCid());
        }
        history.setVodName(name);
        history.setVodPic(pic);
        history.setVodFlag(flag);
        history.setVodRemarks(episode.getName());
        history.setEpisodeUrl(episode.getUrl());
        history.setCreateTime(System.currentTimeMillis());
        history.setPosition(C.TIME_UNSET);
        history.setDuration(C.TIME_UNSET);
        history.setOpening(C.TIME_UNSET);
        history.setEnding(C.TIME_UNSET);
        history.save();
    }

    private Response startVideo(String site, String id, String name, String pic, String mark) {
        try {
            Intent intent = new Intent();
            intent.setClassName(App.get(), "com.fongmi.android.tv.ui.activity.VideoActivity");
            intent.putExtra("key", site);
            intent.putExtra("id", id);
            intent.putExtra("name", name);
            intent.putExtra("pic", pic);
            intent.putExtra("mark", mark);
            intent.putExtra("collect", false);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            App.get().startActivity(intent);
            return Nano.ok("OK");
        } catch (Throwable e) {
            return Nano.error(e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private String cleanHtml(String value) {
        if (value == null || value.isEmpty()) return "";
        return Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString().trim();
    }

    private record Hit(Site site, Vod vod) {
    }

    private Response config(Map<String, String> params) throws InterruptedException {
        String text = trim(params.get("text"));
        String name = trim(params.get("name"));
        if (text.isEmpty()) return Nano.error("Missing config URL");
        int type = configType(params);
        Config config = name.isEmpty() ? Config.find(text, type) : Config.find(text, name, type);
        // Persist immediately so the pushed URL is visible in the app's config history,
        // even if the network load is slow or temporarily fails.
        config.setTime(System.currentTimeMillis());
        config.save();
        String error = loadConfig(config);
        if (error != null) return Nano.error(error);
        config.update();
        return Nano.ok("OK");
    }

    private Response activate(Map<String, String> params) throws InterruptedException {
        Config config = findConfig(params);
        if (config == null) return Nano.error("Config not found");
        String error = loadConfig(config);
        if (error != null) return Nano.error(error);
        config.update();
        return Nano.ok("OK");
    }

    private Response delete(Map<String, String> params) {
        Config config = findConfig(params);
        if (config == null) return Nano.error("Config not found");
        if (isCurrent(config)) return Nano.error("Cannot delete current config");
        config.delete();
        return Nano.ok("OK");
    }

    private JsonObject configs() {
        JsonObject root = new JsonObject();
        root.add("vod", configList(0, VodConfig.getUrl()));
        root.add("live", configList(1, LiveConfig.getUrl()));
        root.add("wall", configList(2, WallConfig.getUrl()));
        return root;
    }

    private JsonObject configList(int type, String currentUrl) {
        JsonObject root = new JsonObject();
        JsonArray items = new JsonArray();
        JsonObject current = null;
        for (Config config : Config.getAll(type)) {
            JsonObject item = new JsonObject();
            int id = config.getId();
            boolean active = config.getUrl() != null && config.getUrl().equals(currentUrl);
            item.addProperty("id", id);
            item.addProperty("type", config.getType());
            item.addProperty("name", trim(config.getName()));
            item.addProperty("url", trim(config.getUrl()));
            item.addProperty("time", config.getTime());
            item.addProperty("current", active);
            items.add(item);
            if (active) current = item;
        }
        if (current != null) root.add("current", current);
        root.add("items", items);
        return root;
    }

    private Config findConfig(Map<String, String> params) {
        String id = trim(params.get("id"));
        if (!id.isEmpty()) {
            try {
                Config config = Config.find(Integer.parseInt(id));
                if (config != null) return config;
            } catch (NumberFormatException ignored) {
            }
        }
        String url = trim(params.get("url"));
        if (url.isEmpty()) return null;
        int type = configType(params);
        for (Config config : Config.getAll(type)) {
            if (url.equals(config.getUrl())) return config;
        }
        return null;
    }

    private int configType(Map<String, String> params) {
        int type;
        try {
            type = Integer.parseInt(trim(params.get("type")));
        } catch (NumberFormatException e) {
            type = 0;
        }
        return type < 0 || type > 2 ? 0 : type;
    }

    private boolean isCurrent(Config config) {
        return switch (config.getType()) {
            case 1 -> config.getUrl() != null && config.getUrl().equals(LiveConfig.getUrl());
            case 2 -> config.getUrl() != null && config.getUrl().equals(WallConfig.getUrl());
            default -> config.getUrl() != null && config.getUrl().equals(VodConfig.getUrl());
        };
    }

    private String loadConfig(Config config) throws InterruptedException {
        int type = config.getType();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> error = new AtomicReference<>();
        Callback callback = new Callback() {
            @Override
            public void success() {
                latch.countDown();
            }

            @Override
            public void error(String msg) {
                error.set(msg == null || msg.isEmpty() ? "Config load failed" : msg);
                latch.countDown();
            }
        };
        if (type == 1) LiveConfig.load(config, callback);
        else if (type == 2) WallConfig.load(config, callback);
        else VodConfig.load(config, callback);
        if (!latch.await(20, TimeUnit.SECONDS)) return "Config load timeout";
        return error.get();
    }

    private JsonObject info() {
        Live live = loadLive();
        JsonObject root = new JsonObject();
        root.addProperty("name", "AITV");
        root.addProperty("version", BuildConfig.VERSION_NAME);
        root.addProperty("versionCode", BuildConfig.VERSION_CODE);
        root.addProperty("variant", BuildConfig.FLAVOR);
        root.addProperty("device", Util.getDeviceName());
        root.addProperty("ip", com.github.catvod.utils.Util.getIp());
        JsonObject liveInfo = new JsonObject();
        liveInfo.addProperty("name", live.getName());
        liveInfo.addProperty("groups", live.getGroups().size());
        liveInfo.addProperty("channels", countChannels(live));
        root.add("live", liveInfo);
        JsonArray capabilities = new JsonArray();
        capabilities.add("control");
        capabilities.add("config");
        capabilities.add("configs");
        capabilities.add("configActivate");
        capabilities.add("configDelete");
        capabilities.add("search");
        capabilities.add("live");
        capabilities.add("sites");
        capabilities.add("vodSearch");
        capabilities.add("vodDetail");
        capabilities.add("vodResolve");
        capabilities.add("vodPlay");
        capabilities.add("vodOpen");
        root.add("capabilities", capabilities);
        return root;
    }

    private JsonObject live(Map<String, String> params) {
        Live live = loadLive();
        String groupFilter = trim(params.get("group"));
        String keyword = trim(params.get("keyword")).toLowerCase(Locale.ROOT);
        JsonObject root = new JsonObject();
        root.addProperty("name", live.getName());
        JsonArray groups = new JsonArray();
        int emitted = 0;
        for (Group group : live.getGroups()) {
            if (group.isHidden()) continue;
            if (!groupFilter.isEmpty() && !group.getName().equals(groupFilter)) continue;
            JsonArray channels = new JsonArray();
            for (Channel channel : group.getChannel()) {
                if (emitted >= MAX_CHANNELS) break;
                if (!keyword.isEmpty() && !channel.getName().toLowerCase(Locale.ROOT).contains(keyword)) continue;
                JsonObject item = new JsonObject();
                item.addProperty("name", channel.getName());
                item.addProperty("number", channel.getNumber());
                item.addProperty("logo", channel.getLogo());
                item.addProperty("epg", channel.getEpg());
                channels.add(item);
                emitted++;
            }
            if (channels.size() == 0) continue;
            JsonObject item = new JsonObject();
            item.addProperty("name", group.getName());
            item.add("channels", channels);
            groups.add(item);
        }
        root.add("groups", groups);
        root.addProperty("count", emitted);
        return root;
    }

    private Live loadLive() {
        // Do not trigger a network load from a read-only status request. HomeActivity already
        // loads the live config at startup; if it failed, return the empty in-memory state
        // instead of blocking the HTTP request for the network timeout.
        return LiveConfig.get().getHome();
    }

    private int countChannels(Live live) {
        int count = 0;
        for (Group group : live.getGroups()) count += group.getChannel().size();
        return count;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
