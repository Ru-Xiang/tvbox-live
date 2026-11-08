package com.github.tvbox.osc.util;

import androidx.annotation.Nullable;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 回看（catchup）地址生成（P2）。
 *
 * <p>线路的回看方式来自 M3U 属性，解析时以保留键附在线路地址后面（见 {@link StreamUrl#KEY_CATCHUP}）：
 * <pre>
 * #EXTINF:-1 catchup="append" catchup-source="?playseek=${(b)yyyyMMddHHmmss}-${(e)yyyyMMddHHmmss}",CCTV-1
 * </pre>
 * 支持的 catchup 类型：
 * <ul>
 *   <li>{@code append}：直播地址 + catchup-source（'?' 会按需换成 '&'）；</li>
 *   <li>{@code default}（或只写了 catchup-source）：catchup-source 就是完整的回看地址模板；</li>
 *   <li>{@code shift} / {@code timeshift}：直播地址追加 {@code utc={utc}&lutc={lutc}}；</li>
 *   <li>{@code flussonic} / {@code fs}：…/index.m3u8 → …/archive-{utc}-{duration}.m3u8，
 *       …/mpegts → …/timeshift_abs-{utc}.ts；</li>
 *   <li>没有任何属性、但地址里有 {@code /PLTV/}（华为 IPTV 常见写法）：换成 {@code /TVOD/} 并追加
 *       {@code playseek=开始-结束}。</li>
 * </ul>
 * 模板占位符：
 * <ul>
 *   <li>{@code ${(b)yyyyMMddHHmmss}} / {@code ${(e)…}}：开始 / 结束时间，Java 日期格式，本地时区；
 *       格式末尾写 {@code :utc} 则用 UTC，如 {@code ${(b)yyyyMMddHHmmss:utc}}；</li>
 *   <li>{@code {start}} {@code {utc}}：开始（Unix 秒）；{@code {end}} {@code {utcend}}：结束；
 *       {@code {lutc}} {@code {now}} {@code {timestamp}}：当前；{@code {duration}}：时长（秒）；{@code {offset}}：开始距今（秒）。
 *       以上都可以写成 {@code ${name}}；</li>
 *   <li>Kodi 写法 {@code {utc:YmdHMS}} / {@code {utcend:Y-m-d H:M:S}}：UTC，Y m d H M S 分别是年月日时分秒。</li>
 * </ul>
 */
public final class CatchupUrl {

    private CatchupUrl() {}

    private static final Pattern BE_FORMAT = Pattern.compile("\\$\\{\\((b|e)\\)([^}]+)\\}");
    private static final Pattern KODI_FORMAT = Pattern.compile("\\{(utc|utcend|lutc):([^}]+)\\}");
    /** 简单占位符：{name} 与 ${name} 两种写法一起处理，避免 ${x} 只换掉 {x} 后残留 '$' */
    private static final Pattern SIMPLE = Pattern.compile(
            "\\$?\\{(start|end|utc|utcend|lutc|now|timestamp|duration|offset)\\}");

    /** 这条线路能否回看（与 {@link #build} 同一口径：能生成回看地址才算支持） */
    public static boolean supports(@Nullable String rawLine) {
        if (rawLine == null) return false;
        long now = System.currentTimeMillis();
        return build(rawLine, now - 3_600_000L, now - 1_800_000L) != null;
    }

    /**
     * 生成回看地址（带上原线路的请求头，回看服务器通常按同样的 UA/Referer 放行）。
     *
     * @param rawLine 直播线路原串
     * @param startMs 回看开始时间（毫秒）
     * @param endMs   节目结束时间（毫秒）
     * @return 回看地址；线路不支持回看返回 null
     */
    @Nullable
    public static String build(@Nullable String rawLine, long startMs, long endMs) {
        if (rawLine == null || endMs <= startMs) return null;
        String live = StreamUrl.playUrl(rawLine);
        if (live == null) return null;
        String type = StreamUrl.meta(rawLine, StreamUrl.KEY_CATCHUP);
        String source = StreamUrl.meta(rawLine, StreamUrl.KEY_CATCHUP_SOURCE);
        type = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
        if ("disabled".equals(type)) return null;
        if (isMulticastLine(rawLine)) {
            // 组播线路：append/shift/flussonic 加在 rtp:// 或 udpxy 地址上不会生效（播出来还是直播），
            // 只接受 catchup-source 写成完整 http(s) 地址的情况
            if (!notEmpty(source)) return null;
            String src = source.trim();
            if (!src.regionMatches(true, 0, "http://", 0, 7) && !src.regionMatches(true, 0, "https://", 0, 8)) {
                return null;
            }
            String url = escapeLabel(fill(src, startMs, endMs, System.currentTimeMillis()));
            return StreamUrl.withHeaders(url, StreamUrl.headers(rawLine));
        }

        String template;
        if (type.equals("append")) {
            if (!notEmpty(source)) return null;
            template = appendQuery(live, source.trim());
        } else if (type.equals("shift") || type.equals("timeshift")) {
            template = appendQuery(live, "?utc={utc}&lutc={lutc}");
        } else if (type.equals("flussonic") || type.equals("flussonic-hls") || type.equals("fs")
                || type.equals("flussonic-ts")) {
            template = flussonic(live);
            if (template == null) return null;
        } else if (notEmpty(source)) {
            // default / vod / 其它：catchup-source 是完整地址；相对写法（以 ? 或 & 开头）按 append 处理
            String src = source.trim();
            template = (src.startsWith("?") || src.startsWith("&")) ? appendQuery(live, src) : src;
        } else if (live.contains("/PLTV/") || live.contains("/TVOD/")) {
            template = appendQuery(live.replace("/PLTV/", "/TVOD/"),
                    "?playseek=${(b)yyyyMMddHHmmss}-${(e)yyyyMMddHHmmss}");
        } else {
            return null;
        }

        String url = escapeLabel(fill(template, startMs, endMs, System.currentTimeMillis()));
        // 请求头（不含回看元数据本身）沿用直播线路的
        return StreamUrl.withHeaders(url, StreamUrl.headers(rawLine));
    }

    /** 填充模板占位符 */
    static String fill(String template, long startMs, long endMs, long nowMs) {
        String s = template;
        Matcher m = BE_FORMAT.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            long t = "b".equals(m.group(1)) ? startMs : endMs;
            m.appendReplacement(sb, Matcher.quoteReplacement(formatJava(m.group(2), t)));
        }
        m.appendTail(sb);
        s = sb.toString();

        m = KODI_FORMAT.matcher(s);
        sb = new StringBuffer();
        while (m.find()) {
            String which = m.group(1);
            long t = which.equals("utc") ? startMs : which.equals("utcend") ? endMs : nowMs;
            m.appendReplacement(sb, Matcher.quoteReplacement(formatKodi(m.group(2), t)));
        }
        m.appendTail(sb);
        s = sb.toString();

        final long start = startMs / 1000L;
        final long end = endMs / 1000L;
        final long now = nowMs / 1000L;
        m = SIMPLE.matcher(s);
        sb = new StringBuffer();
        while (m.find()) {
            long v;
            switch (m.group(1)) {
                case "start":
                case "utc":
                    v = start;
                    break;
                case "end":
                case "utcend":
                    v = end;
                    break;
                case "duration":
                    v = end - start;
                    break;
                case "offset":
                    v = Math.max(0L, now - start);
                    break;
                default: // lutc / now / timestamp
                    v = now;
            }
            m.appendReplacement(sb, String.valueOf(v));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * 填好的地址里如果还剩 '$'（不认识的占位符等），转成 %24：
     * StreamUrl.playUrl 会把 '$' 之后当成 TVBox 线路标签截掉，地址就被截断了
     */
    private static String escapeLabel(String url) {
        return url.indexOf('$') >= 0 ? url.replace("$", "%24") : url;
    }

    /** 线路本身是 rtp:// / udp:// 组播（看原串，不看 playUrl——有 udpxy 时 playUrl 已改写成 http） */
    private static boolean isMulticastLine(String rawLine) {
        String u = rawLine.trim();
        int bar = u.indexOf('|');
        if (bar >= 0) u = u.substring(0, bar);
        return MulticastUrl.isMulticast(u);
    }

    private static String formatJava(String fmt, long t) {
        boolean utc = false;
        String f = fmt;
        if (f.endsWith(":utc") || f.endsWith(":UTC")) {
            utc = true;
            f = f.substring(0, f.length() - 4);
        }
        try {
            SimpleDateFormat sdf = new SimpleDateFormat(f, Locale.ROOT);
            sdf.setTimeZone(utc ? TimeZone.getTimeZone("UTC") : TimeZone.getDefault());
            return sdf.format(new Date(t));
        } catch (IllegalArgumentException e) {
            return String.valueOf(t / 1000L);
        }
    }

    /** Kodi 风格：Y m d H M S 换成年月日时分秒，其余字符原样（UTC） */
    private static String formatKodi(String fmt, long t) {
        java.util.Calendar c = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.ROOT);
        c.setTimeInMillis(t);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < fmt.length(); i++) {
            char ch = fmt.charAt(i);
            switch (ch) {
                case 'Y': out.append(String.format(Locale.ROOT, "%04d", c.get(java.util.Calendar.YEAR))); break;
                case 'm': out.append(String.format(Locale.ROOT, "%02d", c.get(java.util.Calendar.MONTH) + 1)); break;
                case 'd': out.append(String.format(Locale.ROOT, "%02d", c.get(java.util.Calendar.DAY_OF_MONTH))); break;
                case 'H': out.append(String.format(Locale.ROOT, "%02d", c.get(java.util.Calendar.HOUR_OF_DAY))); break;
                case 'M': out.append(String.format(Locale.ROOT, "%02d", c.get(java.util.Calendar.MINUTE))); break;
                case 'S': out.append(String.format(Locale.ROOT, "%02d", c.get(java.util.Calendar.SECOND))); break;
                default: out.append(ch);
            }
        }
        return out.toString();
    }

    /** 给地址追加查询参数：suffix 以 '?' 开头而地址已有查询串时换成 '&' */
    private static String appendQuery(String url, String suffix) {
        if (suffix.startsWith("?") && url.contains("?")) return url + "&" + suffix.substring(1);
        if (suffix.startsWith("&") && !url.contains("?")) return url + "?" + suffix.substring(1);
        if (!suffix.startsWith("?") && !suffix.startsWith("&")) {
            return url + (url.contains("?") ? "&" : "?") + suffix;
        }
        return url + suffix;
    }

    /** Flussonic：按直播地址推出回看模板；推不出来返回 null */
    @Nullable
    private static String flussonic(String live) {
        int q = live.indexOf('?');
        String path = q >= 0 ? live.substring(0, q) : live;
        String query = q >= 0 ? live.substring(q) : "";
        int slash = path.lastIndexOf('/');
        if (slash < 0) return null;
        String dir = path.substring(0, slash);
        String file = path.substring(slash + 1);
        if (file.endsWith(".m3u8")) {
            return dir + "/archive-{utc}-{duration}.m3u8" + query;
        }
        if (file.equals("mpegts") || file.endsWith(".ts")) {
            return dir + "/timeshift_abs-{utc}.ts" + query;
        }
        return null;
    }

    private static boolean notEmpty(@Nullable String s) {
        return s != null && !s.trim().isEmpty();
    }
}
