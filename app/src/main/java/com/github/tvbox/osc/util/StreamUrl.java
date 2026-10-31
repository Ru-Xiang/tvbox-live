package com.github.tvbox.osc.util;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 线路地址的附加信息解析（解析 / 拉取 / 测速 / 播放共用，规则只在这里维护一份）。
 *
 * <p>直播源里的一条线路地址除了真实 URL，还可能带两类附加信息：
 * <ul>
 *   <li>TVBox 线路标签：{@code http://host/a.m3u8$高清}，'$' 之后是给人看的名字，不能发给服务器；</li>
 *   <li>Kodi 风格请求头：{@code http://host/a.m3u8|User-Agent=okhttp%2F3&Referer=http%3A%2F%2Fx}，
 *       '|' 之后是 {@code 名=值} 以 '&' 连接、值做 URL 编码的请求头。
 *       M3U 的 #EXTVLCOPT、TVBox 配置里每条 live 的 ua/header 都统一转成这种写法附在地址后面，
 *       这样无需改动数据库结构，线路去重、黑名单、置顶也都按同一个串工作。</li>
 * </ul>
 * 两者可以同时出现：{@code url$标签|User-Agent=...}。
 */
public final class StreamUrl {

    private StreamUrl() {}

    /**
     * 播放与测速共用的默认 User-Agent（线路未自带 UA 时使用）。
     * 两边必须一致：部分源按 UA 放行，UA 不同会出现"测速通过但播不了"或反过来。
     */
    public static final String DEFAULT_USER_AGENT = "Mozilla/5.0 (Linux; Android 10) ExoPlayerLib/2.19.1";

    /** '|' 之后必须以 "名=" 开头才视为请求头，避免误伤地址里本身含 '|' 的少数线路 */
    private static final Pattern HEADER_START = Pattern.compile("^[A-Za-z][A-Za-z0-9_-]*=");

    /** 请求头分隔符 '|' 的位置；没有合法请求头时返回 -1 */
    private static int headerSeparator(String raw) {
        int from = 0;
        while (true) {
            int idx = raw.indexOf('|', from);
            if (idx < 0) return -1;
            if (HEADER_START.matcher(raw.substring(idx + 1)).find()) return idx;
            from = idx + 1;
        }
    }

    /**
     * 实际发给服务器 / 播放器的地址：去掉 '$标签' 与 '|请求头'。
     * '$' 规则与旧版一致：截掉第一个 '$'（位置 > 0）之后的内容。
     */
    public static String playUrl(String raw) {
        if (raw == null) return null;
        String u = raw.trim();
        int sep = headerSeparator(u);
        if (sep >= 0) u = u.substring(0, sep);
        int label = u.indexOf('$');
        if (label > 0) u = u.substring(0, label);
        return u.trim();
    }

    /** 解析附带的请求头（保持书写顺序；值做 URL 解码，解码失败保留原文）。没有则返回空 Map。 */
    public static Map<String, String> headers(String raw) {
        if (raw == null) return Collections.emptyMap();
        String u = raw.trim();
        int sep = headerSeparator(u);
        if (sep < 0) return Collections.emptyMap();
        Map<String, String> out = new LinkedHashMap<>();
        for (String pair : u.substring(sep + 1).split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            String name = pair.substring(0, eq).trim();
            if (name.isEmpty()) continue;
            out.put(name, decode(pair.substring(eq + 1).trim()));
        }
        return out;
    }

    /** 附带请求头里的 User-Agent（大小写不敏感），没有返回 null */
    public static String userAgent(String raw) {
        for (Map.Entry<String, String> e : headers(raw).entrySet()) {
            if ("User-Agent".equalsIgnoreCase(e.getKey())) return e.getValue();
        }
        return null;
    }

    /**
     * 给地址追加请求头。地址已带的同名请求头优先（单条线路自己的设置比整份配置的默认 ua 更具体）。
     * headers 为空时原样返回。
     */
    public static String withHeaders(String raw, Map<String, String> headers) {
        if (raw == null || headers == null || headers.isEmpty()) return raw;
        String u = raw.trim();
        Map<String, String> merged = new LinkedHashMap<>(headers(u));
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() == null || e.getKey().trim().isEmpty() || e.getValue() == null) continue;
            boolean exists = false;
            for (String k : merged.keySet()) {
                if (k.equalsIgnoreCase(e.getKey().trim())) { exists = true; break; }
            }
            if (!exists) merged.put(e.getKey().trim(), e.getValue());
        }
        if (merged.isEmpty()) return u;
        int sep = headerSeparator(u);
        String head = sep >= 0 ? u.substring(0, sep) : u;
        StringBuilder sb = new StringBuilder(head).append('|');
        boolean first = true;
        for (Map.Entry<String, String> e : merged.entrySet()) {
            if (!first) sb.append('&');
            first = false;
            sb.append(e.getKey()).append('=').append(encode(e.getValue()));
        }
        return sb.toString();
    }

    private static String encode(String v) {
        try {
            return URLEncoder.encode(v, "UTF-8");
        } catch (Exception e) {
            return v;
        }
    }

    private static String decode(String v) {
        try {
            return URLDecoder.decode(v, "UTF-8");
        } catch (Exception e) {
            return v;
        }
    }
}
