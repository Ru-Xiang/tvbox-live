package com.github.tvbox.osc.util;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.Collections;
import java.util.List;

import timber.log.Timber;

/**
 * 网络工具：获取设备局域网 IPv4 地址
 * 同时兼容 WiFi 与有线网络（很多电视盒子使用以太网）
 */
public class NetworkUtil {

    private NetworkUtil() {}

    /**
     * 获取本机局域网 IPv4 地址（站点内网地址优先）
     *
     * @return 形如 192.168.x.x 的地址；获取不到返回 null
     */
    public static String getLocalIpAddress() {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            String fallback = null;
            for (NetworkInterface ni : interfaces) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (addr.isLoopbackAddress() || !(addr instanceof Inet4Address)) continue;
                    String ip = addr.getHostAddress();
                    if (ip == null) continue;
                    // 优先返回站点内网地址
                    if (addr.isSiteLocalAddress()) {
                        return ip;
                    }
                    if (fallback == null) fallback = ip;
                }
            }
            return fallback;
        } catch (Exception e) {
            Timber.e(e, "获取本机 IP 失败");
            return null;
        }
    }

    /**
     * 本机是否具备可用的 IPv6 网络能力。
     *
     * 判定标准：存在已启用的非回环网卡，且其上绑定了 <b>全局</b> IPv6 地址。
     * 链路本地地址（fe80::/10）与站点本地地址不算，因为它们无法访问公网 IPv6 直播源。
     *
     * @return true 表示可以播放 IPv6 线路
     */
    public static boolean hasIpv6Connectivity() {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface ni : interfaces) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (!(addr instanceof java.net.Inet6Address)) continue;
                    if (addr.isLoopbackAddress()
                            || addr.isLinkLocalAddress()
                            || addr.isSiteLocalAddress()
                            || addr.isAnyLocalAddress()) {
                        continue;
                    }
                    Timber.d("检测到可用 IPv6 地址: %s", addr.getHostAddress());
                    return true;
                }
            }
        } catch (Exception e) {
            Timber.w(e, "检测 IPv6 能力失败，按不支持处理");
        }
        return false;
    }

    /** IPv6 出网探测目标的 IPv6 字面量（阿里公共 DNS，直接用字面量避免依赖 DNS 本身） */
    private static final String IPV6_PROBE_HOST = "2400:3200::1";
    private static final int IPV6_PROBE_PORT = 53;
    private static final int IPV6_PROBE_TIMEOUT_MS = 1200;
    /** 探测结果缓存时长：避免每次过滤/测速都重复探测 */
    private static final long IPV6_PROBE_TTL_MS = 5L * 60L * 1000L;

    private static volatile Boolean ipv6Internet;
    private static volatile long ipv6InternetTime;

    /**
     * 本机 IPv6 是否<b>真正可出网</b>。
     *
     * <p>{@link #hasIpv6Connectivity()} 只能证明网卡上有全局 IPv6 地址，但很多网络
     * （家庭宽带、部分盒子/运营商网络）会分配 IPv6 地址却没有 IPv6 出口路由。
     * 这种情况下放进测速的 IPv6 线路会全部超时，白白占用测速配额、拖慢测速。
     *
     * <p>这里做一次轻量 TCP 探测确认出网能力，结果缓存 5 分钟；
     * 探测失败按"IPv6 不可用"处理（只会让自动模式退化为 IPv4，属安全降级）。
     *
     * @return true 表示 IPv6 可出网，IPv6 线路值得参与测速
     */
    public static boolean hasIpv6Internet() {
        long now = System.currentTimeMillis();
        Boolean cached = ipv6Internet;
        if (cached != null && (now - ipv6InternetTime) < IPV6_PROBE_TTL_MS) {
            return cached;
        }
        boolean ok = probeIpv6();
        ipv6Internet = ok;
        ipv6InternetTime = now;
        Timber.d("IPv6 出网探测结果: %s", ok);
        return ok;
    }

    private static boolean probeIpv6() {
        Socket socket = null;
        try {
            InetAddress addr = InetAddress.getByName(IPV6_PROBE_HOST);
            if (!(addr instanceof java.net.Inet6Address)) return false;
            socket = new Socket();
            socket.connect(new InetSocketAddress(addr, IPV6_PROBE_PORT), IPV6_PROBE_TIMEOUT_MS);
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    /** 清空 IPv6 探测缓存（网络切换后可调用，下次重新探测） */
    public static void clearIpv6ProbeCache() {
        ipv6Internet = null;
        ipv6InternetTime = 0L;
    }
}
