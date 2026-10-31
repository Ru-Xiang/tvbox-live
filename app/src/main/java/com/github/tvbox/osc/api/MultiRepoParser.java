package com.github.tvbox.osc.api;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import timber.log.Timber;

/**
 * TVBox 多仓 / 单仓配置解析器。
 *
 * <p>用于把一个「多仓订阅链接」展开为可直接用于测速的直播源列表，共两层结构：
 *
 * <pre>
 * 多仓 (如 https://xmbjm.fh4u.org/dc.txt)
 *   { "urls": [ { "name": "xxx", "url": "https://.../box.json" }, ... ] }
 *   或 { "storeHouse": [ { "sourceName": "xxx", "sourceUrl": "https://.../multi.json" } ] }
 *          │
 *          └─→ 单仓 (box.json)
 *                { "lives": [ { "name": "xxx", "type": 0, "url": "./lib/iptv.m3u" }, ... ] }
 *                                                                      │
 *                                                                      └─→ 最终直播源地址
 * </pre>
 *
 * <p>实际数据中存在大量不规范情况，本类均做了兼容：
 * <ul>
 *   <li>UTF-8 BOM 开头（会让 {@link JSONObject} 直接解析失败）</li>
 *   <li>JSON 中夹带 {@code //} 与 {@code /* *\/} 注释（TVBox 配置的常见写法）</li>
 *   <li>整份配置 Base64 编码，或藏在图片末尾（{@code 8位字母**} 标记之后是 Base64）</li>
 *   <li>{@code lives} 的 url 为相对路径，如 {@code "./lib/iptv.m3u"}，需相对单仓地址解析</li>
 *   <li>{@code lives} 可能是数组、也可能直接是字符串；元素里的直播地址可能嵌在
 *       {@code channels} 子数组中</li>
 *   <li>旧格式 lives：{@code [{"group":"redirect","channels":[{"name":"x","urls":["proxy://...ext=..."]}]}]}</li>
 *   <li>脏数据，如 {@code "https://https://raw..."} 这样的双 scheme</li>
 * </ul>
 *
 * <p>本类只负责「解析出直播源地址」，不负责下载频道内容——
 * 解析结果会并入整体订阅源，由既有流程统一拉取与测速。
 */
public final class MultiRepoParser {

    private MultiRepoParser() {}

    /** 单个多仓配置里最多展开的仓库数，防止异常配置拖垮加载 */
    private static final int MAX_REPOS = 60;
    /** 单个仓库里最多提取的直播源数 */
    private static final int MAX_LIVES_PER_REPO = 40;
    /** 旧格式 lives 里直接写出的频道线路上限 */
    private static final int MAX_INLINE_LINES = 5000;
    /** JSON 嵌套深度上限：AOSP JSONTokener 递归解析，过深的恶意/垃圾配置会 StackOverflowError */
    private static final int MAX_JSON_DEPTH = 200;
    /** lives 节点递归层数上限（正常配置不超过 6 层） */
    private static final int MAX_LIVES_DEPTH = 16;

    /** 图片配置的标记：8 个字母（或 0）紧跟 "**"，之后是 Base64（FongMi/TVBox 约定） */
    private static final Pattern IMAGE_MARKER = Pattern.compile("[A-Za-z0]{8}\\*\\*");

    /**
     * 单仓 lives 中的一条直播源，连同它的请求参数。
     *
     * <p>{@link #headers} 已包含 ua（键为 User-Agent），拉取该直播源的列表时要带上，
     * 并附到它产出的每条线路上（见 {@code StreamUrl.withHeaders}），播放与测速才能用同一套请求头。
     */
    public static final class LiveEntry {
        /** 直播源（播放列表）绝对地址；旧格式直接写出的频道为虚拟地址，见 {@link #inlineTxt} */
        public final String url;
        public final String name;
        /** 配置里的 epg 模板（如 http://epg.x/?ch={name}&date={date}），可能为 null */
        public final String epg;
        /** 请求头（含 User-Agent），无则为空 Map */
        public final Map<String, String> headers;
        /** 非 null 时表示频道已直接写在配置里（TXT 格式），无需再下载 {@link #url} */
        public final String inlineTxt;

        LiveEntry(String url, String name, String epg, Map<String, String> headers, String inlineTxt) {
            this.url = url;
            this.name = name;
            this.epg = epg;
            this.headers = headers == null ? Collections.<String, String>emptyMap() : headers;
            this.inlineTxt = inlineTxt;
        }

        /** 配置里显式设置的 User-Agent，没有返回 null */
        public String userAgent() {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if ("User-Agent".equalsIgnoreCase(e.getKey())) return e.getValue();
            }
            return null;
        }
    }

    /**
     * 把 Base64 编码 / 藏在图片里的配置还原为 JSON 文本；本身就是 JSON（或无法还原）时原样返回。
     *
     * <p>必须在去注释之前执行：Base64 / 图片二进制里可能出现 "//"，被当成注释会截坏数据。
     * 只接受还原后以 '{' 开头的结果，普通 M3U/TXT 绝不会被改动。
     */
    public static String normalizeConfig(String raw) {
        if (raw == null) return null;
        String s = raw;
        if (!s.isEmpty() && s.charAt(0) == '﻿') s = s.substring(1);
        int i = firstNonWhitespace(s);
        if (i < 0) return s;
        if (s.charAt(i) == '﻿') {
            // 个别配置在空行之后才出现 BOM
            s = s.substring(i + 1);
            i = firstNonWhitespace(s);
            if (i < 0) return s;
        }
        char c = s.charAt(i);
        // 已是 JSON（或以注释开头的 JSON）：不再在里面找 "**"，注释 /** */ 与正则规则里都有
        if (c == '{' || c == '[' || s.startsWith("//", i) || s.startsWith("/*", i)) return s;

        // 图片配置：标记之后是 Base64（个别直接是 JSON）
        if (s.indexOf("**") >= 0) {
            Matcher m = IMAGE_MARKER.matcher(s);
            int tries = 0;
            while (m.find() && tries++ < 8) {
                String dec = decodeConfigCandidate(s.substring(m.end()));
                if (dec != null) return dec;
            }
            int last = s.lastIndexOf("**");
            if (last >= 0) {
                String dec = decodeConfigCandidate(s.substring(last + 2));
                if (dec != null) return dec;
            }
        }

        // 整份 Base64
        String dec = decodeConfigCandidate(i == 0 ? s : s.substring(i));
        return dec != null ? dec : s;
    }

    /** 候选片段若是 JSON 或能 Base64 解码为 JSON，返回 JSON 文本，否则 null */
    private static String decodeConfigCandidate(String cand) {
        int i = firstNonWhitespace(cand);
        if (i < 0) return null;
        if (cand.charAt(i) == '{') return cand.substring(i);
        String text = UrlResolver.decodeBase64Text(cand);
        if (text == null) return null;
        if (!text.isEmpty() && text.charAt(0) == '﻿') text = text.substring(1);
        int j = firstNonWhitespace(text);
        if (j >= 0 && text.charAt(j) == '{') return text.substring(j);
        return null;
    }

    private static int firstNonWhitespace(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isWhitespace(s.charAt(i))) return i;
        }
        return -1;
    }

    /**
     * 判断文本是否像 TVBox 的 JSON 配置（多仓或单仓）。
     * 用于在通用解析流程中快速识别，避免把 JSON 当作 M3U/TXT 解析。
     */
    public static boolean looksLikeTvBoxConfig(String content) {
        if (content == null) return false;
        String n = normalizeConfig(content);
        // 首字符既不是 '{' 也不是注释：一定不是配置。先判断再去注释，避免对数 MB 的 M3U 整份复制
        int i = firstNonWhitespace(n);
        if (i < 0) return false;
        char c = n.charAt(i);
        if (c != '{' && !n.startsWith("//", i) && !n.startsWith("/*", i)) return false;
        String s = stripBomAndComments(n).trim();
        if (s.isEmpty() || s.charAt(0) != '{') return false;
        // AOSP org.json 接受单引号键名，这里一并识别
        for (String key : new String[]{"urls", "lives", "sites", "storeHouse"}) {
            if (s.contains("\"" + key + "\"") || s.contains("'" + key + "'")) return true;
        }
        return false;
    }

    /**
     * 文本能否真正解析为 JSON 配置对象（{@link #looksLikeTvBoxConfig} 只做关键字判断，
     * 少数以 '{' 开头的 TXT 列表也会命中）。
     */
    public static boolean parsesAsConfig(String content) {
        if (content == null) return false;
        try {
            parseRoot(content);
            return true;
        } catch (Exception | StackOverflowError e) {
            return false;
        }
    }

    /** 解析配置根对象：先还原 Base64/图片，再去注释，并拒绝嵌套过深的输入 */
    private static JSONObject parseRoot(String content) throws Exception {
        String stripped = stripBomAndComments(normalizeConfig(content));
        if (nestingTooDeep(stripped)) {
            throw new IllegalArgumentException("JSON 嵌套过深");
        }
        return new JSONObject(stripped);
    }

    /**
     * 从「多仓」配置中提取所有子仓地址。
     *
     * @param content 多仓配置正文
     * @param baseUrl 多仓自身的 URL，用于解析相对路径
     * @return 子仓绝对地址列表（已去重）；非多仓格式时返回空列表
     */
    public static List<String> extractRepoUrls(String content, String baseUrl) {
        List<String> result = new ArrayList<>();
        if (content == null) return result;
        try {
            JSONObject root = parseRoot(content);
            JSONArray urls = root.optJSONArray("urls");
            // storeHouse 写法：{"storeHouse":[{"sourceName":"A","sourceUrl":"https://x/multi.json"}]}
            if (urls == null) urls = root.optJSONArray("storeHouse");
            if (urls == null) return result;

            Set<String> seen = new LinkedHashSet<>();
            collectRepos(urls, baseUrl, seen, 0);
            result.addAll(seen);
        } catch (Exception | StackOverflowError e) {
            // StackOverflowError 不是 Exception：漏掉会一路逃逸到聚合线程，整轮拉源失败
            Timber.w("解析多仓配置失败: %s", e.getMessage());
        }
        return result;
    }

    /** 收集多仓条目；条目本身没有地址但带 urls 子数组（分组写法）时展开一层 */
    private static void collectRepos(JSONArray urls, String baseUrl, Set<String> seen, int depth) {
        for (int i = 0; i < urls.length() && seen.size() < MAX_REPOS; i++) {
            String u = null;
            Object item = urls.opt(i);
            if (item instanceof JSONObject) {
                JSONObject o = (JSONObject) item;
                u = optStr(o, "url");
                if (u == null) u = optStr(o, "sourceUrl");
                if (u == null && depth < 2) {
                    JSONArray sub = o.optJSONArray("urls");
                    if (sub != null) collectRepos(sub, baseUrl, seen, depth + 1);
                }
            } else if (item instanceof String) {
                u = (String) item;
            }
            String abs = UrlResolver.resolve(u, baseUrl);
            if (abs != null) seen.add(abs);
        }
    }

    /**
     * 从「单仓」配置中提取 lives 里的直播源地址。
     *
     * @param content 单仓配置正文
     * @param baseUrl 单仓自身的 URL，用于把 "./lib/iptv.m3u" 解析为绝对地址
     * @return 直播源绝对地址列表（已去重）
     */
    public static List<String> extractLiveUrls(String content, String baseUrl) {
        List<String> result = new ArrayList<>();
        for (LiveEntry e : extractLiveEntries(content, baseUrl)) result.add(e.url);
        return result;
    }

    /**
     * 从「单仓」配置中提取 lives 条目（地址 + ua/header/epg）。
     * 按地址去重，同一地址保留首次出现的参数。
     */
    public static List<LiveEntry> extractLiveEntries(String content, String baseUrl) {
        List<LiveEntry> result = new ArrayList<>();
        if (content == null) return result;
        try {
            JSONObject root = parseRoot(content);
            Object lives = root.opt("lives");
            if (lives == null) return result;

            Map<String, LiveEntry> seen = new LinkedHashMap<>();
            StringBuilder inline = new StringBuilder();
            int[] inlineCount = new int[1];
            collectLives(lives, baseUrl, null, seen, inline, inlineCount, 0);
            result.addAll(seen.values());
            if (inline.length() > 0) {
                // 旧格式里直接写出的频道：用虚拟地址作为"源"的标识，内容已在内存里，不再发请求
                String key = (baseUrl == null ? "inline:" : baseUrl) + "#lives";
                result.add(new LiveEntry(key, null, null, null, inline.toString()));
                Timber.i("配置 lives 内直接包含 %d 条频道线路", inlineCount[0]);
            }
        } catch (Exception | StackOverflowError e) {
            Timber.w("解析单仓 lives 失败: %s", e.getMessage());
        }
        return result;
    }

    /**
     * 递归收集 lives 节点中的直播地址（兼容数组 / 对象 / 字符串 / 嵌套 channels / 旧格式 urls）。
     *
     * @param group 旧格式外层的 group 名（用于旧格式直接写出的频道），可为 null
     */
    private static void collectLives(Object node, String baseUrl, String group, Map<String, LiveEntry> out,
                                     StringBuilder inline, int[] inlineCount, int depth) {
        if (node == null || depth > MAX_LIVES_DEPTH || out.size() >= MAX_LIVES_PER_REPO) return;

        if (node instanceof String) {
            addEntry(out, UrlResolver.resolve((String) node, baseUrl), null, null);
            return;
        }

        if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.length() && out.size() < MAX_LIVES_PER_REPO; i++) {
                collectLives(arr.opt(i), baseUrl, group, out, inline, inlineCount, depth + 1);
            }
            return;
        }

        if (node instanceof JSONObject) {
            JSONObject obj = (JSONObject) node;
            String u = optStr(obj, "url");
            if (u != null) {
                String abs = UrlResolver.resolve(u, baseUrl);
                if (abs != null) {
                    if (isSpider(obj) && !looksLikePlaylistPath(abs)) {
                        // type 3 / 带 api、jar 的是爬虫直播，url 是脚本或参数，按播放列表拉取只会白白失败
                        Timber.d("跳过爬虫类直播源: %s", abs);
                    } else {
                        addEntry(out, abs, obj, null);
                    }
                }
            }
            // 旧格式：频道对象用 "urls" 数组存放地址
            Object urls = obj.opt("urls");
            if (urls != null) {
                collectOldUrls(urls, baseUrl, group, optStr(obj, "name"), out, inline, inlineCount);
            }
            // 部分配置把真正的地址放在 channels 子数组里
            Object channels = obj.opt("channels");
            if (channels != null) {
                String g = optStr(obj, "group");
                collectLives(channels, baseUrl, g != null ? g : group, out, inline, inlineCount, depth + 1);
            }
        }
    }

    /**
     * 旧格式频道的 urls：proxy:// 写法（或 group 为 redirect）的是播放列表地址，
     * 其余是直接可播的线路，按 TXT 写入 inline，由拉源流程直接解析为频道。
     */
    private static void collectOldUrls(Object urls, String baseUrl, String group, String name,
                                       Map<String, LiveEntry> out, StringBuilder inline, int[] inlineCount) {
        List<String> list = new ArrayList<>();
        if (urls instanceof String) {
            list.add((String) urls);
        } else if (urls instanceof JSONArray) {
            JSONArray arr = (JSONArray) urls;
            for (int i = 0; i < arr.length(); i++) {
                Object o = arr.opt(i);
                if (o instanceof String) list.add((String) o);
            }
        } else {
            return;
        }
        boolean redirect = group != null && "redirect".equalsIgnoreCase(group.trim());
        for (String s : list) {
            if (s == null || s.trim().isEmpty()) continue;
            String abs = UrlResolver.resolve(s, baseUrl);
            // UrlResolver 只产出 http(s)（它也用于解析配置/列表地址，不能放宽）；
            // rtsp/rtmp 这类播放器支持的流协议在这里原样保留，否则旧格式里的这些频道会被静默丢弃
            boolean streamProto = false;
            if (abs == null && com.github.tvbox.osc.util.ProtocolFilter.isSupported(s.trim())
                    && s.trim().matches("(?i)^(rtsp|rtmp|rtmpt)://.+")) {
                abs = s.trim();
                streamProto = true;
            }
            // .txt / .m3u（非 .m3u8 流地址）是播放列表文件，与 proxy 写法一样作为直播源下载；
            // rtsp/rtmp 是可直接播放的流，即使在 redirect 分组里也只能作为频道线路，不能当列表去下载
            if (!streamProto && (redirect || UrlResolver.isProxyForm(s.trim())
                    || (abs != null && isPlaylistFile(abs)))) {
                if (out.size() < MAX_LIVES_PER_REPO) addEntry(out, abs, null, name);
                continue;
            }
            if (abs == null || name == null || inlineCount[0] >= MAX_INLINE_LINES) continue;
            String g = group == null ? "默认" : group.replace(",", " ").trim();
            String n = name.replace(",", " ").trim();
            if (n.isEmpty()) continue;
            if (g.isEmpty()) g = "默认";
            // 同一分组连续写入时只输出一次分组行
            String header = g + ",#genre#\n";
            if (!endsWithGroup(inline, g)) inline.append(header);
            inline.append(n).append(',').append(abs.replace("\n", "").replace("\r", "")).append('\n');
            inlineCount[0]++;
        }
    }

    /** inline 中最后一个分组是否就是 g */
    private static boolean endsWithGroup(StringBuilder inline, String g) {
        int idx = inline.lastIndexOf(",#genre#\n");
        if (idx < 0) return false;
        int lineStart = inline.lastIndexOf("\n", idx - 1) + 1;
        return inline.substring(lineStart, idx).equals(g);
    }

    private static void addEntry(Map<String, LiveEntry> out, String abs, JSONObject meta, String fallbackName) {
        if (abs == null || out.containsKey(abs) || out.size() >= MAX_LIVES_PER_REPO) return;
        String name = fallbackName;
        String epg = null;
        Map<String, String> headers = null;
        if (meta != null) {
            String n = optStr(meta, "name");
            if (n != null) name = n;
            epg = optStr(meta, "epg");
            headers = readHeaders(meta);
        }
        out.put(abs, new LiveEntry(abs, name, epg, headers, null));
    }

    /** 读取 live 条目上的 ua / header / referer / origin，统一成请求头 Map */
    private static Map<String, String> readHeaders(JSONObject obj) {
        Map<String, String> h = new LinkedHashMap<>();
        String ua = optStr(obj, "ua");
        if (ua == null) ua = optStr(obj, "userAgent");
        if (ua != null) h.put("User-Agent", ua);
        JSONObject header = obj.optJSONObject("header");
        if (header == null) header = obj.optJSONObject("headers");
        if (header != null) {
            Iterator<String> it = header.keys();
            while (it.hasNext()) {
                String k = it.next();
                Object v = header.opt(k);
                if (k == null || k.trim().isEmpty() || !(v instanceof String)) continue;
                String val = ((String) v).trim();
                if (val.isEmpty()) continue;
                boolean dup = false;
                for (String e : h.keySet()) if (e.equalsIgnoreCase(k.trim())) { dup = true; break; }
                if (!dup) h.put(k.trim(), val);
            }
        }
        String referer = optStr(obj, "referer");
        if (referer != null && !containsKeyIgnoreCase(h, "Referer")) h.put("Referer", referer);
        String origin = optStr(obj, "origin");
        if (origin != null && !containsKeyIgnoreCase(h, "Origin")) h.put("Origin", origin);
        return h;
    }

    private static boolean containsKeyIgnoreCase(Map<String, String> m, String key) {
        for (String k : m.keySet()) if (k.equalsIgnoreCase(key)) return true;
        return false;
    }

    /** type 3 或带 api / jar 字段的是爬虫类直播 */
    private static boolean isSpider(JSONObject obj) {
        // 明确写了 type 0/1（txt/m3u 播放列表）时以 type 为准：不少 type 0 源用 .php 等无扩展名地址，
        // 顺带留着 api/jar 字段，不能因此被当成爬虫丢掉；只有没写 type 时才把 api/jar 当爬虫信号
        if (obj.has("type")) {
            int type = obj.optInt("type", -1);
            if (type == 0 || type == 1) return false;
            if (type == 3) return true;
        }
        return obj.has("api") || obj.has("jar");
    }

    /** 地址路径（忽略查询参数）是否以播放列表扩展名结尾 */
    private static boolean looksLikePlaylistPath(String url) {
        String p = url;
        int q = p.indexOf('?');
        if (q >= 0) p = p.substring(0, q);
        p = p.toLowerCase(Locale.ROOT);
        return p.endsWith(".m3u") || p.endsWith(".m3u8") || p.endsWith(".txt");
    }

    /** 路径以 .txt / .m3u 结尾（不含 .m3u8：那是 HLS 流本身） */
    private static boolean isPlaylistFile(String url) {
        String p = url;
        int q = p.indexOf('?');
        if (q >= 0) p = p.substring(0, q);
        p = p.toLowerCase(Locale.ROOT);
        return p.endsWith(".m3u") || p.endsWith(".txt");
    }

    /**
     * 读取字符串字段：只接受真正的字符串值，去首尾空白，空串视为没有。
     * 不能用 optString(key, null)：AOSP org.json 对 JSON null 返回字符串 "null"，
     * 会被当作相对路径解析成 https://host/dir/null。
     */
    static String optStr(JSONObject o, String key) {
        Object v = o.opt(key);
        if (!(v instanceof String)) return null;
        String s = ((String) v).trim();
        return s.isEmpty() ? null : s;
    }

    /** 引号外的 { [ 嵌套是否超过上限（与去注释规则一致：双引号、单引号字符串内的括号不计） */
    private static boolean nestingTooDeep(String s) {
        int depth = 0;
        char quote = 0;
        boolean escaped = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == quote) quote = 0;
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '{' || c == '[') {
                if (++depth > MAX_JSON_DEPTH) return true;
            } else if (c == '}' || c == ']') {
                if (depth > 0) depth--;
            }
        }
        return false;
    }

    /**
     * 去掉 UTF-8 BOM 与 JSON 注释。
     *
     * <p>TVBox 配置普遍带 {@code //} 行注释和 {@code /* *\/} 块注释，
     * 标准 JSON 解析器会直接报错。这里做保守清理：
     * 只在<b>引号外</b>的位置识别注释，避免误删 URL 中的 {@code //}。
     * 双引号与单引号字符串都要识别：AOSP org.json 接受 {@code 'http://x'} 这种单引号值，
     * 只认双引号时其中的 "//" 会被当成注释，把整行截掉导致整份配置解析失败。
     */
    static String stripBomAndComments(String raw) {
        if (raw == null) return "";
        String s = raw;
        // UTF-8 BOM：多仓/单仓配置中很常见，不去掉会让 JSONObject 抛异常
        if (!s.isEmpty() && s.charAt(0) == '﻿') {
            s = s.substring(1);
        }

        StringBuilder sb = new StringBuilder(s.length());
        char quote = 0;          // 当前字符串的开引号；0 表示不在字符串内
        boolean escaped = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);

            if (quote != 0) {
                sb.append(c);
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == quote) {
                    quote = 0;
                }
                continue;
            }

            if (c == '"' || c == '\'') {
                quote = c;
                sb.append(c);
                continue;
            }

            // 引号外才可能是注释
            if (c == '/' && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                if (next == '/') {
                    int nl = s.indexOf('\n', i);
                    if (nl < 0) break;
                    i = nl - 1;      // 循环末尾 i++ 后正好指向换行符
                    continue;
                }
                if (next == '*') {
                    int end = s.indexOf("*/", i + 2);
                    if (end < 0) break;
                    i = end + 1;
                    continue;
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
