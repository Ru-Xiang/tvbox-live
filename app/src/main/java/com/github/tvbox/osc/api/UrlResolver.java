package com.github.tvbox.osc.api;

import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.util.Locale;
import java.util.regex.Pattern;

import timber.log.Timber;

/**
 * URL 解析与规范化工具（面向 TVBox 仓库配置）。
 *
 * <p>仓库配置里的地址写法非常不统一，需要统一成可直接请求的绝对地址：
 * <ul>
 *   <li>相对路径：{@code "./lib/iptv.m3u"}、{@code "lib/iptv.m3u"}、{@code "../x.m3u"}、{@code "/lib/iptv.m3u"}</li>
 *   <li>协议相对：{@code "//host/path"}</li>
 *   <li>脏数据：{@code "https://https://raw.github..."}（双 scheme，实测存在）</li>
 *   <li>代理前缀嵌套：{@code "https://gh-proxy.com/https://raw..."}（合法，须保留）</li>
 *   <li>查询参数式代理：{@code "https://proxy.x/get?url=https://raw.../box.json"}</li>
 *   <li>TVBox 本地代理：{@code "proxy://do=live&type=txt&ext=<base64>"}，ext 里是真正的直播源地址</li>
 *   <li>大小写不规范的 scheme：{@code "HTTP://host/x"}</li>
 * </ul>
 */
final class UrlResolver {

    private UrlResolver() {}

    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** 带 "://" 的任意 scheme（clan:// file:// assets:// proxy:// 等），用于识别"不是相对路径" */
    private static final Pattern SCHEME = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*://");

    /** TVBox 本地代理的 http 写法：http://127.0.0.1:9978/proxy?do=live&ext=... */
    private static final Pattern LOOPBACK_PROXY = Pattern.compile(
            "^https?://(127\\.0\\.0\\.1|localhost)(:\\d+)?/proxy\\?.*", Pattern.CASE_INSENSITIVE);

    /**
     * 把可能是相对路径的地址解析为绝对地址。
     *
     * @param url  原始地址，可为 null / 空 / 相对路径
     * @param base 所属配置文件的 URL，用于解析相对路径
     * @return 绝对 http(s) 地址；无法解析或非 http(s) 时返回 null
     */
    static String resolve(String url, String base) {
        return resolve(url, base, true);
    }

    private static String resolve(String url, String base, boolean allowUnwrap) {
        if (url == null) return null;
        String u = url.trim();
        if (u.isEmpty()) return null;

        // 先统一 scheme 大小写再修双 scheme："HTTPS://https://x" 也要能被修正
        u = lowerHttpScheme(u);
        u = fixDoubleScheme(u);

        // TVBox 本地代理：真正的直播源地址 Base64 编码在 ext 参数里。
        // 只解一层，解出来的仍是代理地址时不再递归，防止恶意配置无限嵌套
        if (isProxyForm(u)) {
            String inner = allowUnwrap ? unwrapProxy(u) : null;
            if (inner == null) {
                Timber.d("无法解析代理直播地址，已跳过: %s", u);
                return null;
            }
            return resolve(inner, base, false);
        }

        // 已是绝对地址
        if (startsWithHttp(u)) return u;

        // 协议相对地址 //host/path
        if (u.startsWith("//")) {
            String scheme = base != null && base.trim().regionMatches(true, 0, "http://", 0, 7) ? "http:" : "https:";
            return scheme + u;
        }

        // clan:// file:// assets:// 等本地 scheme：本应用无法加载，丢弃但留日志便于排查"源去哪了"
        if (SCHEME.matcher(u).find()) {
            Timber.d("不支持的直播地址协议，已跳过: %s", u);
            return null;
        }

        // 其余按相对路径处理，需要 base 才能解析
        if (base == null) return null;
        String b = lowerHttpScheme(base.trim());
        if (!startsWithHttp(b)) return null;
        int hash = b.indexOf('#');
        if (hash >= 0) b = b.substring(0, hash);

        try {
            return joinRelative(u, b);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 相对路径拼接（按 TVBox 的字符串拼接语义，不用 java.net.URI）。
     *
     * <p>URI.resolve 会做路径规范化，把 gh-proxy 写法里的 {@code /https://raw...} 压成
     * {@code /https:/raw...}（多数代理直接 404），且对查询参数式代理会整段丢掉被代理的地址。
     * TVBox 本身是 {@code base.substring(0, lastIndexOf('/') + 1) + 相对路径}，两种代理写法都能用。
     */
    private static String joinRelative(String rel, String base) {
        if (rel.startsWith("/")) {
            // 绝对路径：拼到 base 的主机根部
            return origin(base) + rel;
        }
        if (rel.startsWith("?")) {
            int q = base.indexOf('?');
            return (q >= 0 ? base.substring(0, q) : base) + rel;
        }
        String dir = directoryOf(base);
        // 查询参数式代理里被代理地址之后的其余参数（如 &token=1）属于代理本身，拼接后原样保留
        String tail = proxyQueryTail(base, dir);
        int floor = floorOf(dir);
        String r = rel;
        while (true) {
            if (r.startsWith("./")) {
                r = r.substring(2);
            } else if (r.startsWith("../")) {
                r = r.substring(3);
                // 弹出一级目录，但不越过主机名 / 被代理地址的主机名（否则代理前缀被破坏）
                int prev = dir.lastIndexOf('/', dir.length() - 2);
                if (prev + 1 >= floor) dir = dir.substring(0, prev + 1);
            } else if (r.equals(".") || r.equals("..")) {
                r = "";
            } else {
                break;
            }
        }
        return dir + r + tail;
    }

    /** dir 取自查询参数里的被代理地址时，返回该地址之后的 '&...' 参数；否则返回空串 */
    private static String proxyQueryTail(String base, String dir) {
        int q = base.indexOf('?');
        if (q < 0 || dir.length() <= q) return "";
        int amp = base.indexOf('&', dir.length());
        if (amp < 0) return "";
        int hash = base.indexOf('#', amp);
        return hash >= 0 ? base.substring(amp, hash) : base.substring(amp);
    }

    /** scheme://host（不含末尾斜杠） */
    private static String origin(String base) {
        int hostStart = base.indexOf("://") + 3;
        int end = base.length();
        for (int i = hostStart; i < base.length(); i++) {
            char c = base.charAt(i);
            if (c == '/' || c == '?' || c == '#') { end = i; break; }
        }
        return base.substring(0, end);
    }

    /**
     * base 所在"目录"（以 '/' 结尾）。
     *
     * <p>查询参数里嵌了完整 http 地址（{@code get?url=https://raw/u/r/box.json}）时，
     * 目录取被代理地址的目录，结果仍经同一个代理访问；否则忽略查询参数，取路径目录。
     */
    private static String directoryOf(String base) {
        int q = base.indexOf('?');
        if (q >= 0) {
            String query = base.substring(q);
            int m = indexOfHttp(query, 0);
            if (m >= 0) {
                int schemeLen = query.regionMatches(true, m, "https://", 0, 8) ? 8 : 7;
                int end = query.length();
                int amp = query.indexOf('&', m);
                if (amp >= 0) end = amp;
                String embedded = query.substring(m, end);
                int hostEnd = embedded.indexOf('/', schemeLen);
                int ls = embedded.lastIndexOf('/');
                // 只有被代理地址就是查询本身（?https://...），或它指向一个文件（末段带扩展名，如 box.json）
                // 时才按代理处理；?from=https://b.com/ 这类无关参数仍按路径目录解析，免得相对地址拼进查询里
                String last = ls >= 0 ? embedded.substring(ls + 1) : "";
                int hash = last.indexOf('#');
                if (hash >= 0) last = last.substring(0, hash);
                boolean fileLike = last.indexOf('.') > 0;
                boolean wholeQuery = m == 1;
                if (hostEnd >= 0 && ls >= hostEnd && (fileLike || wholeQuery)) {
                    return base.substring(0, q + m + ls + 1);
                }
            }
            base = base.substring(0, q);
        }
        int hostStart = base.indexOf("://") + 3;
        int ls = base.lastIndexOf('/');
        if (ls < hostStart) return base + "/";
        return base.substring(0, ls + 1);
    }

    /** "../" 回退的下限：外层主机根之后，或路径里最后一个被代理地址的主机根之后 */
    private static int floorOf(String dir) {
        int hostStart = dir.indexOf("://") + 3;
        int floor = dir.indexOf('/', hostStart) + 1;
        int from = hostStart;
        while (true) {
            int m = indexOfHttp(dir, from);
            if (m < 0) break;
            int schemeLen = dir.regionMatches(true, m, "https://", 0, 8) ? 8 : 7;
            int slash = dir.indexOf('/', m + schemeLen);
            if (slash < 0) break;
            floor = Math.max(floor, slash + 1);
            from = m + schemeLen;
        }
        return floor;
    }

    private static int indexOfHttp(String s, int from) {
        String lower = s.toLowerCase(Locale.ROOT);
        int a = lower.indexOf("http://", from);
        int b = lower.indexOf("https://", from);
        if (a < 0) return b;
        if (b < 0) return a;
        return Math.min(a, b);
    }

    /** 只把 http(s) scheme 部分转为小写，主机/路径/查询保持原样 */
    private static String lowerHttpScheme(String u) {
        if (u.regionMatches(true, 0, "https://", 0, 8) && !u.startsWith("https://")) {
            return "https://" + u.substring(8);
        }
        if (u.regionMatches(true, 0, "http://", 0, 7) && !u.startsWith("http://")) {
            return "http://" + u.substring(7);
        }
        return u;
    }

    /**
     * 修正 {@code "https://https://host/path"} 这类双 scheme 脏数据。
     *
     * <p>注意不能简单地截断到最后一个 scheme：
     * {@code "https://gh-proxy.com/https://raw.githubusercontent.com/..."} 是合法的
     * 代理写法，必须原样保留。二者的区别在于——脏数据的第二个 scheme
     * <b>紧跟在第一个 scheme 之后</b>，中间没有真实主机名。
     */
    private static String fixDoubleScheme(String u) {
        for (String scheme : new String[]{"https://", "http://"}) {
            if (!u.startsWith(scheme)) continue;
            String rest = u.substring(scheme.length());
            if (rest.regionMatches(true, 0, "https://", 0, 8) || rest.regionMatches(true, 0, "http://", 0, 7)) {
                // 递归处理三重及以上的情况
                return fixDoubleScheme(lowerHttpScheme(rest));
            }
        }
        return u;
    }

    private static boolean startsWithHttp(String s) {
        return s.regionMatches(true, 0, "http://", 0, 7) || s.regionMatches(true, 0, "https://", 0, 8);
    }

    /** 是否 TVBox 本地代理写法（proxy://... 或 http://127.0.0.1:端口/proxy?...） */
    static boolean isProxyForm(String u) {
        if (u == null) return false;
        return u.regionMatches(true, 0, "proxy://", 0, 8) || LOOPBACK_PROXY.matcher(u).matches();
    }

    /**
     * 从 TVBox 本地代理地址里取出真正的直播源地址。
     *
     * <p>{@code proxy://do=live&type=txt&ext=aHR0cHM6Ly9...}：ext 可能是 URL 编码过的，
     * 也可能直接是 http 地址或 Base64。解不出 http / 相对路径时返回 null（例如 ext 是爬虫 JSON）。
     */
    static String unwrapProxy(String u) {
        if (u == null) return null;
        String params;
        if (u.regionMatches(true, 0, "proxy://", 0, 8)) {
            params = u.substring(8);
            int q = params.indexOf('?');
            if (q >= 0) params = params.substring(q + 1);
        } else {
            int q = u.indexOf('?');
            if (q < 0) return null;
            params = u.substring(q + 1);
        }
        String ext = null;
        for (String p : params.split("&")) {
            if (p.regionMatches(true, 0, "ext=", 0, 4)) {
                ext = p.substring(4);
                break;
            }
        }
        if (ext == null || ext.trim().isEmpty()) return null;
        ext = ext.trim();
        if (ext.indexOf('%') >= 0) {
            try {
                // '+' 在 Base64 里是合法字符，URLDecoder 会把它变成空格，先保护起来
                ext = URLDecoder.decode(ext.replace("+", "%2B"), "UTF-8").trim();
            } catch (Exception ignore) {
                // 解码失败按原文继续尝试 Base64
            }
        }
        if (startsWithHttp(ext)) return ext;
        String decoded = decodeBase64Text(ext);
        if (decoded == null) return null;
        decoded = decoded.trim();
        if (startsWithHttp(decoded) || decoded.startsWith("./") || decoded.startsWith("../")
                || decoded.startsWith("//")) {
            return decoded;
        }
        return null;
    }

    /**
     * 宽松的 Base64 → UTF-8 文本解码：去空白、补齐 '='、标准与 URL 安全字母表都接受。
     * 含非 Base64 字符时直接返回 null（不做无谓的解码，M3U/TXT 正文首字符就会被拒）。
     * 使用 android.util.Base64（java.util.Base64 需要 API 26）。
     */
    static String decodeBase64Text(String s) {
        byte[] bytes = decodeBase64(s);
        return bytes == null ? null : new String(bytes, UTF8);
    }

    static byte[] decodeBase64(String s) {
        if (s == null) return null;
        // 先做廉价的逐字符检查：首个非法字符即放弃，避免对大段普通文本做无谓的解码
        int len = s.length();
        StringBuilder sb = new StringBuilder(Math.min(len, 1 << 16));
        boolean urlSafe = false;
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') continue;
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '+' || c == '/' || c == '=' || c == '-' || c == '_';
            if (!ok) return null;
            if (c == '-' || c == '_') urlSafe = true;
            sb.append(c);
        }
        // 去掉末尾填充后重新按长度补齐（部分配置省略或多写了 '='）
        int end = sb.length();
        while (end > 0 && sb.charAt(end - 1) == '=') end--;
        if (end == 0) return null;
        String core = sb.substring(0, end);
        if (core.indexOf('=') >= 0) return null;
        if (core.length() % 4 == 1) return null;
        StringBuilder padded = new StringBuilder(core);
        while (padded.length() % 4 != 0) padded.append('=');
        try {
            int flags = android.util.Base64.NO_WRAP | (urlSafe ? android.util.Base64.URL_SAFE : 0);
            return android.util.Base64.decode(padded.toString(), flags);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
