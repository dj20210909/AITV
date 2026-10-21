package com.aitv.assistant;

import java.util.Locale;

/** A discovered AITV instance. */
public final class AitvDevice {

    public final String uuid;
    public final String name;
    public final String baseUrl;
    public final int type;
    public final String version;
    public final String variant;
    public final boolean modern;
    public final boolean assistant;

    public AitvDevice(String uuid, String name, String baseUrl, int type, String version,
                      String variant, boolean modern, boolean assistant) {
        this.uuid = uuid;
        this.name = name;
        this.baseUrl = baseUrl;
        this.type = type;
        this.version = version;
        this.variant = variant;
        this.modern = modern;
        this.assistant = assistant;
    }

    public String typeLabel() {
        return type == 0 ? "电视" : "手机";
    }

    public String meta() {
        return typeLabel() + " · " + baseUrl;
    }

    public String key() {
        String id = uuid == null || uuid.isEmpty() ? baseUrl : uuid;
        return id.toLowerCase(Locale.ROOT);
    }
}
