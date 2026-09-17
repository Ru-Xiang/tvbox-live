package com.github.tvbox.osc.util;

import com.github.tvbox.osc.bean.ChannelGroup;
import com.github.tvbox.osc.bean.LiveChannel;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import timber.log.Timber;

/**
 * 频道去重工具
 *
 * 对齐 ISEP 项目 channel_dedup_service.py 提供的三种去重策略：
 * 1. 按名称：同一归一化名称的频道视为同一频道，源合并到一起。
 *    这是原有 IptvApiService.mergeGroups 已经实现的行为。
 * 2. 按 URL：同一 URL 视为同一线路，即便挂在不同频道下也仅保留其一。
 *    可解决同一线路被误分类到多个频道时的重复。
 * 3. 按 名称 + URL：只有"同名 + 同 URL"才算重复，其它并存。
 *    保守策略，避免误删有效线路。
 *
 * 所有方法都对入参进行原地去重（会修改 ChannelGroup 内部列表）。
 */
public final class ChannelDeduper {

    private ChannelDeduper() {}

    /**
     * 根据配置模式对分组列表进行去重。
     * mode 取值参见 HawkConfig.DEDUP_MODE_*。
     */
    public static void dedup(List<ChannelGroup> groups, int mode) {
        if (groups == null || groups.isEmpty()) return;

        switch (mode) {
            case HawkConfig.DEDUP_MODE_URL:
                dedupByUrl(groups);
                break;
            case HawkConfig.DEDUP_MODE_NAME_AND_URL:
                dedupByNameAndUrl(groups);
                break;
            case HawkConfig.DEDUP_MODE_NAME:
            default:
                // 按名称的合并在 IptvApiService 里已完成，此处仅对每个频道内部线路去重
                dedupChannelSources(groups);
                break;
        }
    }

    /**
     * 对每个频道内部的多条线路做 URL 级去重（保留首次出现）。
     * 无论选择哪种模式都应先执行。
     */
    public static void dedupChannelSources(List<ChannelGroup> groups) {
        for (ChannelGroup group : groups) {
            if (group == null || group.getChannels() == null) continue;
            for (LiveChannel channel : group.getChannels()) {
                dedupChannel(channel);
            }
        }
    }

    /** 对单个频道内部的 URL 去重，speeds 同步跟随。 */
    public static void dedupChannel(LiveChannel channel) {
        if (channel == null || channel.getSourceUrls() == null) return;
        List<String> urls = channel.getSourceUrls();
        if (urls.isEmpty()) return;
        List<Double> speeds = channel.getSpeeds();

        int n = urls.size();
        Set<String> seen = new HashSet<>(Math.max(16, n * 2));
        boolean hasSpeeds = speeds != null && speeds.size() == n;

        // 惰性建列表：绝大多数频道本身没有重复线路（addSourceUrl 已做过去重），
        // 无条件重建会为每个频道白白拷贝两个集合。
        List<String> newUrls = null;
        List<Double> newSpeeds = null;

        for (int i = 0; i < n; i++) {
            String u = urls.get(i);
            boolean keep = u != null && !u.isEmpty() && seen.add(u.trim());
            if (keep) {
                if (newUrls != null) {
                    newUrls.add(u);
                    if (hasSpeeds) newSpeeds.add(speeds.get(i));
                }
                continue;
            }
            // 首次遇到需剔除项，才建立新列表并补齐前面已保留的部分
            if (newUrls == null) {
                newUrls = new ArrayList<>(n);
                newSpeeds = new ArrayList<>(n);
                for (int j = 0; j < i; j++) {
                    newUrls.add(urls.get(j));
                    if (hasSpeeds) newSpeeds.add(speeds.get(j));
                }
            }
        }

        if (newUrls == null) return; // 无重复，保持原样

        channel.setSourceUrls(newUrls);
        if (hasSpeeds) channel.setSpeeds(newSpeeds);
    }

    /**
     * 全局按 URL 去重：跨频道/跨分组扫描所有 URL，同一 URL 只保留首次出现。
     */
    private static void dedupByUrl(List<ChannelGroup> groups) {
        Set<String> seen = new HashSet<>();
        int removed = 0;
        for (ChannelGroup group : groups) {
            if (group == null || group.getChannels() == null) continue;
            for (LiveChannel channel : group.getChannels()) {
                if (channel == null || channel.getSourceUrls() == null) continue;
                List<String> urls = channel.getSourceUrls();
                List<Double> speeds = channel.getSpeeds();
                boolean hasSpeeds = speeds != null && speeds.size() == urls.size();

                List<String> keep = new ArrayList<>();
                List<Double> keepSpeeds = new ArrayList<>();
                for (int i = 0; i < urls.size(); i++) {
                    String u = urls.get(i);
                    if (u == null || u.isEmpty()) continue;
                    if (seen.add(u.trim())) {
                        keep.add(u);
                        if (hasSpeeds) keepSpeeds.add(speeds.get(i));
                    } else {
                        removed++;
                    }
                }
                channel.setSourceUrls(keep);
                // 保持 speeds 与 urls 恒等长，避免后续排序/显示错配
                channel.setSpeeds(hasSpeeds ? keepSpeeds : new ArrayList<>());
            }
            // 移除已被清空的频道（内部用 Iterator，兼容 API 21）
            group.removeInvalidChannels();
        }
        if (removed > 0) Timber.d("按 URL 去重共移除 %d 条重复线路", removed);
    }

    /**
     * 按 "归一化名称 + URL" 去重：只有同名 + 同 URL 才视为重复。
     */
    private static void dedupByNameAndUrl(List<ChannelGroup> groups) {
        Set<String> seen = new HashSet<>();
        int removed = 0;
        for (ChannelGroup group : groups) {
            if (group == null || group.getChannels() == null) continue;
            for (LiveChannel channel : group.getChannels()) {
                if (channel == null || channel.getSourceUrls() == null) continue;
                String key = ChannelNameMatcher.normalizeKey(channel.getChannelName());
                List<String> urls = channel.getSourceUrls();
                List<Double> speeds = channel.getSpeeds();
                boolean hasSpeeds = speeds != null && speeds.size() == urls.size();

                List<String> keep = new ArrayList<>();
                List<Double> keepSpeeds = new ArrayList<>();
                for (int i = 0; i < urls.size(); i++) {
                    String u = urls.get(i);
                    if (u == null || u.isEmpty()) continue;
                    String combined = key + "\u0001" + u.trim();
                    if (seen.add(combined)) {
                        keep.add(u);
                        if (hasSpeeds) keepSpeeds.add(speeds.get(i));
                    } else {
                        removed++;
                    }
                }
                channel.setSourceUrls(keep);
                channel.setSpeeds(hasSpeeds ? keepSpeeds : new ArrayList<>());
            }
            group.removeInvalidChannels();
        }
        if (removed > 0) Timber.d("按名称+URL 去重共移除 %d 条重复线路", removed);
    }
}
