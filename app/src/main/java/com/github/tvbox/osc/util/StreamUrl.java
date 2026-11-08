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
 *
 * <p>'|' 之后还可以带几个<b>保留键</b>（{@link #RESERVED_KEYS}，目前是回看模板 catchup / catchup-source /
 * catchup-days）：它们是线路的元数据，跟请求头写在一起是为了同样不改数据库结构，
 * 但<b>不会</b>出现在 {@link #headers} 的结果里，也就不会被当成请求头发给服务器，不影响线路去重。
 */
public final class StreamUrl {

    private StreamUrl() {}

    /**
     * 播放与测速共用的默认 User-Agent（线路未自带 UA 时使用）。
     * 两边必须一致：部分源按 UA 放行，UA 不同会出现"测速通过但播不了"或反过来。
     */
    public static final String DEFAULT_USER_AGENT = "Mozilla/5.0 (Linux; Android 10) ExoPlayerLib/2.19.1";

    /** '|' 之后的保留键（小写比较）：线路元数据，不是请求头 */
    public static final String KEY_CATCHUP = "catchup";
    public static final String KEY_CATCHUP_SOURCE = "catchup-source";
    public static final String KEY_CATCHUP_DAYS = "catchup-days";
    private static final java.util.Set<String> RESERVED_KEYS = new java.util.HashSet<>(
            java.util.Arrays.asList(KEY_CATCHUP, KEY_CATCHUP_SOURCE, KEY_CATCHUP_DAYS));

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
     * 组播地址（rtp:// udp://）在设置了 udpxy 代理时改写成代理的 HTTP 地址（见 {@link MulticastUrl}），
     * 测速与播放因此请求的是同一个地址。
     */
    public static String playUrl(String raw) {
        if (raw == null) return null;
        String u = raw.trim();
        int sep = headerSeparator(u);
        if (sep >= 0) u = u.substring(0, sep);
        int label = u.indexOf('$');
        if (label > 0) u = u.substring(0, label);
        return MulticastUrl.rewrite(u.trim());
    }

    /**
     * 解析附带的请求头（保持书写顺序；值做 URL 解码，解码失败保留原文）。没有则返回空 Map。
     * 不含保留键（回看模板等元数据）。
     */
    public static Map<String, String> headers(String raw) {
        Map<String, String> all = params(raw);
        if (all.isEmpty()) return all;
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : all.entrySet()) {
            if (!isReserved(e.getKey())) out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    /** 线路元数据（保留键）的值，没有返回 null。key 用 {@link #KEY_CATCHUP} 等常量 */
    public static String meta(String raw, String key) {
        for (Map.Entry<String, String> e : params(raw).entrySet()) {
            if (e.getKey().equalsIgnoreCase(key)) return e.getValue();
        }
        return null;
    }

    /**
     * 去掉保留键（回看元数据）后的线路串：测速结果、黑屏/分辨率记录等"按线路"存的数据用它做键，
     * 解析时给线路补上或改了回看模板不会让这些记录对不上。没有保留键时原样返回（同一个对象）。
     */
    public static String withoutMeta(String raw) {
        if (raw == null || raw.indexOf('|') < 0) return raw;
        String u = raw.trim();
        int sep = headerSeparator(u);
        if (sep < 0) return raw;
        // 按原文逐对删除保留键，其余请求头一个字节都不动：重建（重新编码）会让键和 1.2.x 存下的串对不上
        StringBuilder kept = new StringBuilder();
        boolean dropped = false;
        for (String pair : u.substring(sep + 1).split("&")) {
            int eq = pair.indexOf('=');
            String name = eq > 0 ? pair.substring(0, eq).trim() : null;
            if (name != null && isReserved(name)) {
                dropped = true;
                continue;
            }
            if (kept.length() > 0) kept.append('&');
            kept.append(pair);
        }
        if (!dropped) return raw;
        String head = u.substring(0, sep);
        return kept.length() == 0 ? head : head + "|" + kept;
    }

    /**
     * 给线路追加保留键（回看元数据），只补缺的键；原有内容（含请求头的原始写法）一个字节都不改，
     * 这样 {@link #withoutMeta} 能还原出加元数据之前的原串。
     */
    public static String appendMeta(String raw, Map<String, String> meta) {
        if (raw == null || meta == null || meta.isEmpty()) return raw;
        String u = raw.trim();
        java.util.Set<String> have = new java.util.HashSet<>();
        for (String k : params(u).keySet()) have.add(k.toLowerCase(java.util.Locale.ROOT));
        StringBuilder add = new StringBuilder();
        for (Map.Entry<String, String> e : meta.entrySet()) {
            String k = e.getKey() == null ? "" : e.getKey().trim();
            if (k.isEmpty() || e.getValue() == null || !isReserved(k)) continue;
            if (!have.add(k.toLowerCase(java.util.Locale.ROOT))) continue;
            if (add.length() > 0) add.append('&');
            add.append(k).append('=').append(encode(e.getValue()));
        }
        if (add.length() == 0) return u;
        return headerSeparator(u) >= 0 ? u + "&" + add : u + "|" + add;
    }

    /** 只取保留键（回看模板等线路元数据），没有返回空 Map。合并重复线路时用来把元数据带过去 */
    public static Map<String, String> reservedMeta(String raw) {
        Map<String, String> all = params(raw);
        if (all.isEmpty()) return all;
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : all.entrySet()) {
            if (isReserved(e.getKey())) out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    private static boolean isReserved(String key) {
        return key != null && RESERVED_KEYS.contains(key.toLowerCase(java.util.Locale.ROOT));
    }

    /** '|' 之后的全部 名=值（请求头 + 保留键） */
    private static Map<String, String> params(String raw) {
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
     * headers 为空时原样返回。地址已带的保留键（回看模板）原样保留。
     * 同样可用来追加保留键（{@link #KEY_CATCHUP} 等），规则相同：已有的优先。
     */
    public static String withHeaders(String raw, Map<String, String> headers) {
        if (raw == null || headers == null || headers.isEmpty()) return raw;
        String u = raw.trim();
        Map<String, String> merged = new LinkedHashMap<>(params(u));
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
