package com.github.tvbox.osc.api;

/**
 * URL 解析与规范化工具（面向 TVBox 仓库配置）。
 *
 * <p>仓库配置里的地址写法非常不统一，需要统一成可直接请求的绝对地址：
 * <ul>
 *   <li>相对路径：{@code "./lib/iptv.m3u"}、{@code "lib/iptv.m3u"}、{@code "/lib/iptv.m3u"}</li>
 *   <li>协议相对：{@code "//host/path"}</li>
 *   <li>脏数据：{@code "https://https://raw.github..."}（双 scheme，实测存在）</li>
 *   <li>代理前缀嵌套：{@code "https://gh-proxy.com/https://raw..."}（合法，须保留）</li>
 * </ul>
 */
final class UrlResolver {

    private UrlResolver() {}

    /**
     * 把可能是相对路径的地址解析为绝对地址。
     *
     * @param url  原始地址，可为 null / 空 / 相对路径
     * @param base 所属配置文件的 URL，用于解析相对路径
     * @return 绝对 http(s) 地址；无法解析或非 http(s) 时返回 null
     */
    static String resolve(String url, String base) {
        if (url == null) return null;
        String u = url.trim();
        if (u.isEmpty()) return null;

        u = fixDoubleScheme(u);

        // 已是绝对地址
        if (startsWithHttp(u)) return u;

        // 协议相对地址 //host/path
        if (u.startsWith("//")) {
            String scheme = base != null && base.startsWith("http://") ? "http:" : "https:";
            return scheme + u;
        }

        // 其余按相对路径处理，需要 base 才能解析
        if (base == null || !startsWithHttp(base)) return null;

        try {
            // 用标准库解析，能正确处理 ./ 与 ../
            java.net.URI baseUri = new java.net.URI(base);
            java.net.URI resolved = baseUri.resolve(u);
            String s = resolved.toString();
            return startsWithHttp(s) ? s : null;
        } catch (Exception e) {
            // base 含中文域名等非法字符时 URI 会抛异常，退化为手工拼接
            return manualJoin(u, base);
        }
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
            if (rest.startsWith("https://") || rest.startsWith("http://")) {
                // 递归处理三重及以上的情况
                return fixDoubleScheme(rest);
            }
        }
        return u;
    }

    private static boolean startsWithHttp(String s) {
        return s.startsWith("http://") || s.startsWith("https://");
    }

    /** URI 解析失败时的兜底拼接（处理中文域名等 URI 不接受的情况） */
    private static String manualJoin(String rel, String base) {
        try {
            if (rel.startsWith("/")) {
                // 绝对路径：拼到 base 的主机根部
                int schemeEnd = base.indexOf("://");
                if (schemeEnd < 0) return null;
                int hostEnd = base.indexOf('/', schemeEnd + 3);
                String origin = hostEnd < 0 ? base : base.substring(0, hostEnd);
                return origin + rel;
            }
            // 相对路径：拼到 base 所在目录
            String dir = base;
            int q = dir.indexOf('?');
            if (q >= 0) dir = dir.substring(0, q);
            int slash = dir.lastIndexOf('/');
            if (slash <= "https://".length()) return null;
            dir = dir.substring(0, slash + 1);
            if (rel.startsWith("./")) rel = rel.substring(2);
            return dir + rel;
        } catch (Exception e) {
            return null;
        }
    }
}
