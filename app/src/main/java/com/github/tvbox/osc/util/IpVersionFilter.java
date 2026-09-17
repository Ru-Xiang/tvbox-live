package com.github.tvbox.osc.util;

import com.github.tvbox.osc.bean.ChannelGroup;
import com.github.tvbox.osc.bean.LiveChannel;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import timber.log.Timber;

/**
 * IP 版本线路过滤工具（作用于测速前的整体订阅源）。
 *
 * 按用户选择的 IP 版本，对整体订阅源解析出的所有频道线路进行过滤：
 * - all（自动）：自适应本机网络能力——IPv6 可出网时 IPv4/IPv6 混合统一排序；
 *   否则自动剔除 IPv6 线路，避免测速配额浪费在必定失败的线路上；
 * - ipv4：仅保留 IPv4 地址或域名的线路，剔除 IPv6 直连；
 * - ipv6：仅保留 IPv6 可用（IPv6 字面量或有 AAAA 记录）的线路。
 *
 * <p><b>性能要点（测速链路敏感）：</b>
 * 目标为 IPv4 时，域名一律直接保留，<b>不做任何 DNS 解析</b>——域名几乎必然可通过
 * IPv4 访问，而全量订阅源往往有成千上万个域名，逐个解析会严重阻塞测速前的过滤，
 * 且解析失败还会让过滤大面积失效。
 * 只有目标为 IPv6 时才真正需要解析域名（确认有无 AAAA 记录），此时做并发预取。
 *
 * <p>判定依据为 URL 中的主机部分：形如 http://[xxxx:xxxx::x]:port/ 的方括号地址视为 IPv6；
 * 纯 IPv4 点分十进制视为 IPv4；域名在 IPv6 模式下通过 DNS 解析其 A/AAAA 记录判断。
 * 解析结果带缓存（失败结果有短暂 TTL，允许网络恢复后重试）；解析失败时保守保留该线路。
 */
public final class IpVersionFilter {

    public static final String IPV4 = "ipv4";
    public static final String IPV6 = "ipv6";
    public static final String ALL = "all";

    // 纯 IPv4 点分十进制
    private static final Pattern IPV4_PATTERN =
            Pattern.compile("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$");

    /** 域名 -> 解析结果 */
    private static final ConcurrentHashMap<String, DnsEntry> DNS_CACHE =
            new ConcurrentHashMap<>();

    /** DNS 缓存条数上限，防止超大列表导致内存增长 */
    private static final int MAX_DNS_CACHE = 3000;

    /** 解析失败结果的缓存时长：过期后允许重新解析，避免一次网络抖动导致长期失效 */
    private static final long UNKNOWN_TTL_MS = 60L * 1000L;

    /** IPv6 模式下预解析域名的数量上限，避免上千域名拖慢测速前的过滤 */
    private static final int MAX_PREFETCH_HOSTS = 500;

    /** IPv6 模式下预解析的总超时（毫秒），超时未完成的域名按保守策略保留 */
    private static final long PREFETCH_TIMEOUT_MS = 8000L;

    /** 域名解析结果 */
    private static final class DnsEntry {
        final boolean v4;
        final boolean v6;
        /** false 表示解析失败/未知，按保守策略保留线路 */
        final boolean resolved;
        final long time;

        DnsEntry(boolean v4, boolean v6, boolean resolved, long time) {
            this.v4 = v4;
            this.v6 = v6;
            this.resolved = resolved;
            this.time = time;
        }
    }

    private IpVersionFilter() {}

    /**
     * 按 IP 版本原地过滤分组内所有频道的线路。
     * 过滤后线路为空的频道会被移除；线路为空的分组也会被移除。
     *
     * @param groups    待过滤分组（会被原地修改）
     * @param ipVersion all / ipv4 / ipv6
     */
    public static void filter(List<ChannelGroup> groups, String ipVersion) {
        if (groups == null || groups.isEmpty()) return;

        // 自动模式：按本机真实 IPv6 能力自适应。
        // 注意不能只看"网卡上有无 IPv6 地址"，还要看能否真正出网：
        // 有地址无出口路由时，IPv6 线路放进测速只会全部超时并挤占配额。
        boolean auto = (ipVersion == null || ALL.equalsIgnoreCase(ipVersion));
        if (auto) {
            if (NetworkUtil.hasIpv6Connectivity() && NetworkUtil.hasIpv6Internet()) {
                Timber.i("IP 版本自动：本机 IPv6 可用，使用 IPv4/IPv6 混合线路");
                return;
            }
            Timber.i("IP 版本自动：本机 IPv6 不可用，将剔除 IPv6 线路");
            ipVersion = IPV4;
        }

        boolean wantIpv6 = IPV6.equalsIgnoreCase(ipVersion);

        // 只有"仅保留 IPv6"时才需要解析域名（确认 AAAA 记录）；
        // 目标为 IPv4 时域名默认可用，直接跳过 DNS，避免阻塞测速前的过滤。
        if (wantIpv6) {
            try {
                prefetchDns(groups);
            } catch (Throwable t) {
                Timber.w(t, "DNS 预解析失败，未解析域名按保守策略保留");
            }
        }

        int removedLines = 0;
        int removedChannels = 0;
        int removedGroups = 0;

        try {
            Iterator<ChannelGroup> gIt = groups.iterator();
            while (gIt.hasNext()) {
                ChannelGroup group = gIt.next();
                if (group == null) {
                    gIt.remove();
                    continue;
                }
                List<LiveChannel> channels = group.getChannels();
                if (channels == null) {
                    gIt.remove();
                    removedGroups++;
                    continue;
                }

                Iterator<LiveChannel> cIt = channels.iterator();
                while (cIt.hasNext()) {
                    LiveChannel ch = cIt.next();
                    if (ch == null) {
                        cIt.remove();
                        continue;
                    }
                    List<String> urls = ch.getSourceUrls();
                    if (urls == null || urls.isEmpty()) {
                        cIt.remove();
                        removedChannels++;
                        continue;
                    }

                    // 惰性建立新列表：绝大多数频道并不需要剔除任何线路，
                    // 避免为每个频道都复制 urls/speeds 两份集合（全量源下可省下大量临时对象）。
                    List<String> kept = null;
                    List<Double> keptSpeeds = null;
                    List<Double> speeds = ch.getSpeeds();
                    boolean hasSpeeds = speeds != null && speeds.size() == urls.size();

                    for (int i = 0; i < urls.size(); i++) {
                        String url = urls.get(i);
                        if (matches(url, wantIpv6)) {
                            if (kept != null) {
                                kept.add(url);
                                if (hasSpeeds) keptSpeeds.add(speeds.get(i));
                            }
                            continue;
                        }
                        // 首次遇到需剔除的线路时才真正建立新列表，并补上前面已保留的部分
                        if (kept == null) {
                            kept = new ArrayList<>(urls.size());
                            keptSpeeds = new ArrayList<>(urls.size());
                            for (int j = 0; j < i; j++) {
                                kept.add(urls.get(j));
                                if (hasSpeeds) keptSpeeds.add(speeds.get(j));
                            }
                        }
                        removedLines++;
                    }

                    // 没有任何线路被剔除：保持原样，不重置 speeds / sourceIndex
                    if (kept == null) continue;

                    if (kept.isEmpty()) {
                        cIt.remove();
                        removedChannels++;
                    } else {
                        ch.setSourceUrls(kept);
                        ch.setSpeeds(hasSpeeds ? keptSpeeds : new ArrayList<>());
                        ch.setSourceIndex(0);
                    }
                }

                if (channels.isEmpty()) {
                    gIt.remove();
                    removedGroups++;
                }
            }
            Timber.i("IP 版本过滤(%s)：剔除 %d 条线路，移除 %d 个无可用线路频道、%d 个空分组",
                    wantIpv6 ? "ipv6" : "ipv4", removedLines, removedChannels, removedGroups);
        } catch (Throwable t) {
            // 过滤失败不应影响后续测速/展示，保持原始数据即可
            Timber.w(t, "IP 版本过滤异常，跳过过滤");
        }
    }

    /**
     * 判断一条线路 URL 是否为 IPv6 直连（仅按字面量判断，不做 DNS 解析）。
     * 依据：主机部分被方括号包裹（形如 [2409:8c00::1]），或主机本身是无方括号的 IPv6 字面量。
     */
    public static boolean isIpv6Url(String url) {
        if (url == null || url.isEmpty()) return false;
        String host = extractHost(url);
        return isIpv6Host(host);
    }

    private static boolean isIpv6Host(String host) {
        if (host == null || host.isEmpty()) return false;
        // 方括号形式一定是 IPv6（含未闭合的畸形写法，与 isIpLiteral 判定保持一致）
        if (host.charAt(0) == '[') return true;
        // 纯 IPv4 或域名不是 IPv6
        if (IPV4_PATTERN.matcher(host).matches()) return false;
        // 无方括号但含多个冒号，视为 IPv6 字面量
        return host.indexOf(':') != host.lastIndexOf(':');
    }

    private static boolean isIpLiteral(String host) {
        if (host == null || host.isEmpty()) return false;
        if (host.charAt(0) == '[') return true;
        if (IPV4_PATTERN.matcher(host).matches()) return true;
        return host.indexOf(':') != host.lastIndexOf(':');
    }

    /**
     * 判断某条线路在目标 IP 版本下是否可用。
     *
     * <p>主机是 IP 字面量时直接判断；是域名时：
     * <ul>
     *   <li>目标 IPv4：直接保留，不做 DNS 解析（域名几乎均有 A 记录，解析只会拖慢过滤）；</li>
     *   <li>目标 IPv6：查询解析结果，需有 AAAA 记录才保留；解析失败则保守保留。</li>
     * </ul>
     */
    private static boolean matches(String url, boolean wantIpv6) {
        String host = extractHost(url);
        if (host == null || host.isEmpty()) {
            // 无法识别主机：目标 IPv6 时无法确认其支持 IPv6，剔除；
            // 目标 IPv4 时保留，避免误删可用源。
            return !wantIpv6;
        }

        // IP 字面量：直接判断，无需解析
        if (isIpLiteral(host)) {
            return isIpv6Host(host) == wantIpv6;
        }

        // 域名
        if (!wantIpv6) {
            return true;
        }

        DnsEntry entry = resolveCapabilities(host);
        if (entry == null || !entry.resolved) return true; // 解析失败：保守保留
        if (!entry.v4 && !entry.v6) return true;           // 无有效记录：保守保留
        return entry.v6;
    }

    /**
     * 获取域名的地址族能力（带缓存）。
     * 解析成功的结果长期缓存；解析失败的结果只在 {@link #UNKNOWN_TTL_MS} 内复用，
     * 过期后允许重新解析，避免网络瞬时故障让过滤长期失效。
     */
    private static DnsEntry resolveCapabilities(String host) {
        String key = host.toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();
        DnsEntry cached = DNS_CACHE.get(key);
        if (cached != null) {
            if (cached.resolved) return cached;
            if (now - cached.time < UNKNOWN_TTL_MS) return cached;
        }
        try {
            InetAddress[] addrs = InetAddress.getAllByName(host);
            boolean v4 = false, v6 = false;
            if (addrs != null) {
                for (InetAddress a : addrs) {
                    if (a instanceof java.net.Inet6Address) v6 = true;
                    else if (a instanceof java.net.Inet4Address) v4 = true;
                }
            }
            DnsEntry entry = new DnsEntry(v4, v6, true, now);
            putCache(key, entry);
            return entry;
        } catch (Throwable t) {
            // 解析失败也短暂缓存，避免同一域名在本次过滤中被反复超时查询
            DnsEntry entry = new DnsEntry(false, false, false, now);
            putCache(key, entry);
            return entry;
        }
    }

    private static void putCache(String key, DnsEntry entry) {
        if (DNS_CACHE.size() >= MAX_DNS_CACHE) return; // 达上限后不再写入，防止无限增长
        DNS_CACHE.put(key, entry);
    }

    /** 并发预解析待判断的域名，避免过滤过程中逐个串行 DNS 查询（仅 IPv6 模式需要） */
    private static void prefetchDns(List<ChannelGroup> groups) {
        Set<String> hosts = new HashSet<>();
        collectHosts(groups, hosts);
        if (hosts.isEmpty()) return;

        int threads = Math.min(12, Math.max(2, hosts.size()));
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            final CountDownLatch latch = new CountDownLatch(hosts.size());
            for (final String h : hosts) {
                pool.execute(() -> {
                    try {
                        resolveCapabilities(h);
                    } catch (Throwable ignore) {
                    } finally {
                        latch.countDown();
                    }
                });
            }
            // 总体最多等待若干秒，未完成的域名按"保守保留"处理
            latch.await(PREFETCH_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable ignore) {
        } finally {
            pool.shutdownNow();
        }
        Timber.d("DNS 预解析结束: %d 个域名", hosts.size());
    }

    /** 收集所有尚未缓存、且需要 DNS 判断的域名（数量达上限即停止） */
    private static void collectHosts(List<ChannelGroup> groups, Set<String> hosts) {
        for (ChannelGroup group : groups) {
            if (group == null || group.getChannels() == null) continue;
            for (LiveChannel ch : group.getChannels()) {
                if (ch == null || ch.getSourceUrls() == null) continue;
                for (String url : ch.getSourceUrls()) {
                    if (hosts.size() >= MAX_PREFETCH_HOSTS) return;
                    String host = extractHost(url);
                    if (host == null || host.isEmpty() || isIpLiteral(host)) continue;
                    String key = host.toLowerCase(Locale.ROOT);
                    if (!DNS_CACHE.containsKey(key)) hosts.add(host);
                }
            }
        }
    }

    /**
     * 从 URL 中提取主机部分（去掉 scheme、userinfo 与端口）。
     * 支持 {@code scheme://host}、{@code //host} 以及裸 {@code host/path} 形式；
     * IPv6 字面量保留方括号（如 {@code [2409:8c00::1]}）。
     */
    private static String extractHost(String url) {
        if (url == null) return null;
        try {
            String s = url.trim();
            int schemeIdx = s.indexOf("://");
            if (schemeIdx > 0) {
                s = s.substring(schemeIdx + 3);
            } else if (s.startsWith("//")) {
                s = s.substring(2);
            }
            if (s.isEmpty()) return null;

            // 截取 authority：到首个 / ? # 为止
            int end = s.length();
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '/' || c == '?' || c == '#') {
                    end = i;
                    break;
                }
            }
            String authority = s.substring(0, end);

            // 去掉 user:pass@
            int at = authority.lastIndexOf('@');
            if (at >= 0) authority = authority.substring(at + 1);
            if (authority.isEmpty()) return null;

            // IPv6 字面量：[::1] 或 [::1]:8080
            if (authority.charAt(0) == '[') {
                int close = authority.indexOf(']');
                return close > 0 ? authority.substring(0, close + 1) : authority;
            }

            // 去掉端口
            int colon = authority.indexOf(':');
            if (colon > 0) authority = authority.substring(0, colon);

            return authority.isEmpty() ? null : authority;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 清空 DNS 解析缓存与 IPv6 探测缓存（网络环境变化后可调用） */
    public static void clearDnsCache() {
        DNS_CACHE.clear();
        NetworkUtil.clearIpv6ProbeCache();
    }
}
