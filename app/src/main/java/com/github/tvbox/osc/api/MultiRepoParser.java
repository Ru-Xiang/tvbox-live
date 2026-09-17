package com.github.tvbox.osc.api;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import timber.log.Timber;

/**
 * TVBox 多仓 / 单仓配置解析器。
 *
 * <p>用于把一个「多仓订阅链接」展开为可直接用于测速的直播源列表，共两层结构：
 *
 * <pre>
 * 多仓 (如 https://xmbjm.fh4u.org/dc.txt)
 *   { "urls": [ { "name": "xxx", "url": "https://.../box.json" }, ... ] }
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
 *   <li>{@code lives} 的 url 为相对路径，如 {@code "./lib/iptv.m3u"}，需相对单仓地址解析</li>
 *   <li>{@code lives} 可能是数组、也可能直接是字符串；元素里的直播地址可能嵌在
 *       {@code channels} 子数组中</li>
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

    /**
     * 判断文本是否像 TVBox 的 JSON 配置（多仓或单仓）。
     * 用于在通用解析流程中快速识别，避免把 JSON 当作 M3U/TXT 解析。
     */
    public static boolean looksLikeTvBoxConfig(String content) {
        if (content == null) return false;
        String s = stripBomAndComments(content).trim();
        if (s.isEmpty() || s.charAt(0) != '{') return false;
        return s.contains("\"urls\"") || s.contains("\"lives\"") || s.contains("\"sites\"");
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
            JSONObject root = new JSONObject(stripBomAndComments(content));
            JSONArray urls = root.optJSONArray("urls");
            if (urls == null) return result;

            Set<String> seen = new LinkedHashSet<>();
            for (int i = 0; i < urls.length() && seen.size() < MAX_REPOS; i++) {
                String u = null;
                Object item = urls.opt(i);
                if (item instanceof JSONObject) {
                    u = ((JSONObject) item).optString("url", null);
                } else if (item instanceof String) {
                    u = (String) item;
                }
                String abs = UrlResolver.resolve(u, baseUrl);
                if (abs != null) seen.add(abs);
            }
            result.addAll(seen);
        } catch (Exception e) {
            Timber.w("解析多仓配置失败: %s", e.getMessage());
        }
        return result;
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
        if (content == null) return result;
        try {
            JSONObject root = new JSONObject(stripBomAndComments(content));
            Object lives = root.opt("lives");
            if (lives == null) return result;

            Set<String> seen = new LinkedHashSet<>();
            collectLives(lives, baseUrl, seen);
            result.addAll(seen);
        } catch (Exception e) {
            Timber.w("解析单仓 lives 失败: %s", e.getMessage());
        }
        return result;
    }

    /** 递归收集 lives 节点中的直播地址（兼容数组 / 对象 / 字符串 / 嵌套 channels） */
    private static void collectLives(Object node, String baseUrl, Set<String> out) {
        if (node == null || out.size() >= MAX_LIVES_PER_REPO) return;

        if (node instanceof String) {
            String abs = UrlResolver.resolve((String) node, baseUrl);
            if (abs != null) out.add(abs);
            return;
        }

        if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.length() && out.size() < MAX_LIVES_PER_REPO; i++) {
                collectLives(arr.opt(i), baseUrl, out);
            }
            return;
        }

        if (node instanceof JSONObject) {
            JSONObject obj = (JSONObject) node;
            String u = obj.optString("url", null);
            String abs = UrlResolver.resolve(u, baseUrl);
            if (abs != null) {
                out.add(abs);
                return; // 已取到本节点地址，无需再深入
            }
            // 部分配置把真正的地址放在 channels 子数组里
            Object channels = obj.opt("channels");
            if (channels != null) collectLives(channels, baseUrl, out);
        }
    }

    /**
     * 去掉 UTF-8 BOM 与 JSON 注释。
     *
     * <p>TVBox 配置普遍带 {@code //} 行注释和 {@code /* *\/} 块注释，
     * 标准 JSON 解析器会直接报错。这里做保守清理：
     * 只在<b>引号外</b>的位置识别注释，避免误删 URL 中的 {@code //}。
     */
    static String stripBomAndComments(String raw) {
        if (raw == null) return "";
        String s = raw;
        // UTF-8 BOM：多仓/单仓配置中很常见，不去掉会让 JSONObject 抛异常
        if (!s.isEmpty() && s.charAt(0) == '\uFEFF') {
            s = s.substring(1);
        }

        StringBuilder sb = new StringBuilder(s.length());
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);

            if (inString) {
                sb.append(c);
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }

            if (c == '"') {
                inString = true;
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
