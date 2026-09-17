package com.github.tvbox.osc.util;

import com.github.tvbox.osc.bean.ChannelGroup;
import com.github.tvbox.osc.bean.LiveChannel;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import timber.log.Timber;

/**
 * 播放协议过滤器：剔除播放器无法播放的线路。
 *
 * <p>当前播放内核为 ExoPlayer，已引入 core / hls / dash / rtsp / extension-rtmp 模块，
 * 因此可播放 HTTP(S)（含 HLS、DASH、渐进式）、RTSP、RTMP 以及本地 file / content。
 *
 * <p>以下协议一律不支持，需在<b>测速之前</b>就过滤掉，避免：
 * <ul>
 *   <li>浪费测速配额与时间（这些线路必然失败）</li>
 *   <li>被选入本地播放列表后播放黑屏</li>
 *   <li>换源时反复尝试无效线路，拖慢起播</li>
 * </ul>
 *
 * <table>
 *   <tr><td>rtp:// udp:// igmp://</td><td>组播 IPTV，需 udpxy 之类的代理转成 HTTP 才能播</td></tr>
 *   <tr><td>p2p:// 及其它私有协议</td><td>需专用内核</td></tr>
 * </table>
 */
public final class ProtocolFilter {

    private ProtocolFilter() {}

    /**
     * 判断该线路的协议是否为播放器支持的可播协议（白名单）。
     * 采用白名单而非黑名单：未知/私有协议一律视为不支持，避免漏过新出现的无效协议。
     */
    public static boolean isSupported(String url) {
        if (url == null) return false;
        String u = url.trim();
        if (u.isEmpty()) return false;
        // 统一小写比较协议头，避免 RTP:// 之类的大写写法绕过过滤
        String lower = u.toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("http://")
                || lower.startsWith("https://")
                || lower.startsWith("rtsp://")
                || lower.startsWith("rtmp://")
                || lower.startsWith("rtmps://")
                || lower.startsWith("rtmpe://")
                || lower.startsWith("rtmpt://")
                || lower.startsWith("file://")
                || lower.startsWith("content://");
    }

    /**
     * 该线路能否用 HTTP 方式测速。
     *
     * <p>RTSP/RTMP 是流式协议，无法像HTTP 那样下载片段来测算速度，
     * 因此它们只能"跳过测速"，但<b>不应被当作不可用线路而删除</b>。
     */
    public static boolean isSpeedTestable(String url) {
        if (url == null) return false;
        String lower = url.trim().toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    /**
     * 原地剔除所有不支持协议的线路。
     * 线路被清空的频道、频道被清空的分组都会一并移除。
     *
     * @return 被移除的线路条数
     */
    public static int filter(List<ChannelGroup> groups) {
        if (groups == null || groups.isEmpty()) return 0;
        int removedLines = 0;
        int removedChannels = 0;
        int removedGroups = 0;
        try {
            Iterator<ChannelGroup> gIt = groups.iterator();
            while (gIt.hasNext()) {
                ChannelGroup group = gIt.next();
                if (group == null || group.getChannels() == null) {
                    gIt.remove();
                    continue;
                }
                Iterator<LiveChannel> cIt = group.getChannels().iterator();
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
                    List<String> kept = new ArrayList<>();
                    List<Double> keptSpeeds = new ArrayList<>();
                    List<Double> speeds = ch.getSpeeds();
                    boolean hasSpeeds = speeds != null && speeds.size() == urls.size();
                    for (int i = 0; i < urls.size(); i++) {
                        String url = urls.get(i);
                        if (isSupported(url)) {
                            kept.add(url);
                            if (hasSpeeds) keptSpeeds.add(speeds.get(i));
                        } else {
                            removedLines++;
                        }
                    }
                    if (kept.isEmpty()) {
                        cIt.remove();
                        removedChannels++;
                    } else if (kept.size() != urls.size()) {
                        ch.setSourceUrls(kept);
                        // 保持 speeds 与 urls恒等长，避免排序/显示错配
                        ch.setSpeeds(hasSpeeds ? keptSpeeds : new ArrayList<>());
                        ch.setSourceIndex(0);
                    }
                }
                if (group.getChannels().isEmpty()) {
                    gIt.remove();
                    removedGroups++;
                }
            }
            if (removedLines > 0 || removedChannels > 0) {
                Timber.i("协议过滤：移除 %d 条不支持的线路(rtp/udp/rtsp/rtmp等)，"
                        + "移除 %d 个无可播线路的频道，%d 个空分组",
                        removedLines, removedChannels, removedGroups);
            }
        } catch (Throwable t) {
            // 过滤失败不应影响后续流程，保持原始数据
            Timber.w(t, "协议过滤异常，跳过过滤");
        }
        return removedLines;
    }
}
