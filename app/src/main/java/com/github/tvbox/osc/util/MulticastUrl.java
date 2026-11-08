package com.github.tvbox.osc.util;

import androidx.annotation.Nullable;

import com.orhanobut.hawk.Hawk;

import java.util.Locale;

/**
 * 组播线路（rtp:// udp://）的处理规则（P2），解析 / 测速 / 播放共用。
 *
 * <p>两种播法，udpxy 优先：
 * <ol>
 *   <li><b>udpxy / msd_lite 代理</b>：设置了代理地址时，把
 *       {@code rtp://239.3.1.241:8000} 改写成 {@code http://<代理>/rtp/239.3.1.241:8000}，
 *       之后就是一条普通的 HTTP-TS 线路，能测速、能走 OkHttp；</li>
 *   <li><b>组播直连</b>：没有代理但开了"组播直连"时，播放器直接加入组播组收流
 *       （{@code player/MulticastDataSource}），盒子必须接在运营商 IPTV 网络上。</li>
 * </ol>
 * 两者都没开时组播线路仍按"不支持的协议"在解析阶段丢弃（与 1.2.x 相同）。
 *
 * <p>设置值缓存在静态字段里：{@link ProtocolFilter#isSupported} 在解析上万条线路时逐条调用，
 * 每次都读 Hawk 太慢。设置页保存后调用 {@link #reload()}。
 */
public final class MulticastUrl {

    private MulticastUrl() {}

    private static volatile boolean loaded;
    /** 规范化后的代理地址（http://host:port，无结尾斜杠），未设置为 null */
    private static volatile String udpxyBase;
    private static volatile boolean direct;

    /** 重新读取设置（设置页保存后、App 启动时调用） */
    public static void reload() {
        try {
            udpxyBase = normalizeBase(Hawk.get(HawkConfig.LIVE_UDPXY, ""));
            direct = Hawk.get(HawkConfig.LIVE_MULTICAST_DIRECT, false);
        } catch (Throwable t) {
            udpxyBase = null;
            direct = false;
        }
        loaded = true;
    }

    private static void ensureLoaded() {
        if (!loaded) reload();
    }

    /** 当前生效的 udpxy 代理地址，未设置返回 null */
    @Nullable
    public static String udpxy() {
        ensureLoaded();
        return udpxyBase;
    }

    public static boolean directEnabled() {
        ensureLoaded();
        return direct;
    }

    /** 是否为组播地址写法（rtp:// 或 udp://，大小写不敏感） */
    public static boolean isMulticast(@Nullable String url) {
        if (url == null) return false;
        String u = url.trim();
        return u.regionMatches(true, 0, "rtp://", 0, 6) || u.regionMatches(true, 0, "udp://", 0, 6);
    }

    /** 组播线路在当前设置下能否播放（有代理或开了直连） */
    public static boolean playable(@Nullable String url) {
        if (!isMulticast(url)) return false;
        ensureLoaded();
        String gp = groupAndPort(url);
        if (gp == null) return false;                      // 没有端口 / 端口非法：代理和直连都播不了
        boolean viaProxy = udpxyBase != null && !gp.startsWith(":"); // udp://@:端口 代理转不了，只能直连
        if (viaProxy) return true;
        // 只能直连：源地址特定组播（SSM）在 API 33 以下加入不了，直连收不到包，按不可播处理
        return direct && !isSsm(url);
    }

    /**
     * 有代理时把组播地址改写成代理的 HTTP 地址；不是组播地址或没有代理时原样返回。
     * 只认 {@code 地址:端口}：{@code rtp://@239.1.1.1:5000}、{@code rtp://10.0.0.1@239.1.1.1:5000}
     * （源地址特定组播，代理只需要组地址）都取 '@' 之后的部分；查询串（如运营商的 fcc 参数）丢弃。
     */
    public static String rewrite(@Nullable String url) {
        if (url == null || !isMulticast(url)) return url;
        ensureLoaded();
        String base = udpxyBase;
        if (base == null) return url;
        String hostPort = groupAndPort(url);
        // udp://@:1234 这种"本机端口收单播"的写法代理转不了，原样保留（只能直连）
        if (hostPort == null || hostPort.startsWith(":")) return url;
        String scheme = url.trim().substring(0, 3).toLowerCase(Locale.ROOT); // rtp / udp
        return base + "/" + scheme + "/" + hostPort;
    }

    /**
     * rtp://源@组:端口 里的源地址（源地址特定组播 SSM），没写返回 null。
     */
    @Nullable
    public static String sourceAddress(String url) {
        String rest = cutSuffix(url);
        int at = rest.lastIndexOf('@');
        if (at <= 0) return null;
        String src = rest.substring(0, at).trim();
        return src.isEmpty() ? null : src;
    }

    /**
     * rtp://[源@]组:端口[/路径][?查询] → "组:端口"；{@code udp://@:1234}（本机端口收单播）→ ":1234"；
     * 格式不对返回 null
     */
    @Nullable
    public static String groupAndPort(String url) {
        String rest = cutSuffix(url);
        int at = rest.lastIndexOf('@');
        if (at >= 0) rest = rest.substring(at + 1);
        rest = rest.trim();
        int colon = rest.lastIndexOf(':');
        if (colon < 0 || colon == rest.length() - 1) return null;
        try {
            int port = Integer.parseInt(rest.substring(colon + 1));
            if (port <= 0 || port > 65535) return null;
        } catch (NumberFormatException e) {
            return null;
        }
        return rest;
    }

    /**
     * 去掉协议头，并在 '/' '?' '#' '$'（TVBox 线路标签）'|'（请求头 / 元数据）处截断。
     * 必须先截断再找 '@'：标签和请求头里也可能出现 '@'
     */
    private static String cutSuffix(String url) {
        String rest = url.trim().substring(6);
        int cut = rest.length();
        for (char c : new char[]{'/', '?', '#', '$', '|'}) {
            int i = rest.indexOf(c);
            if (i >= 0 && i < cut) cut = i;
        }
        return rest.substring(0, cut);
    }

    /** 232.0.0.0/8 源地址特定组播（SSM）且写了源地址 */
    static boolean isSsm(String url) {
        if (sourceAddress(url) == null) return false;
        String gp = groupAndPort(url);
        return gp != null && gp.startsWith("232.");
    }

    /** 用户输入的代理地址规范化：补 http://、去掉结尾斜杠；空或明显不对返回 null */
    @Nullable
    public static String normalizeBase(@Nullable String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        if (!s.regionMatches(true, 0, "http://", 0, 7) && !s.regionMatches(true, 0, "https://", 0, 8)) {
            s = "http://" + s;
        }
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        // 至少要有主机名
        return s.length() > "http://".length() ? s : null;
    }
}
