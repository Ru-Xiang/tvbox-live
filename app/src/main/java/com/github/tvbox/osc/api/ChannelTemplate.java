package com.github.tvbox.osc.api;

import com.github.tvbox.osc.bean.ChannelGroup;
import com.github.tvbox.osc.bean.LiveChannel;
import com.github.tvbox.osc.util.ChannelNameMatcher;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import timber.log.Timber;

/**
 * 频道列表模板
 *
 * 模板为 TXT 格式（与 iptv-api 的 demo.txt 一致）：
 *   央视,#genre#
 *   CCTV-1
 *   CCTV-2
 *   卫视,#genre#
 *   湖南卫视
 *
 * 作用：
 * 1. 仅保留模板中列出的频道（白名单）
 * 2. 按模板的分组与频道顺序重新组织
 * 3. 用模糊匹配把实际源里的频道关联到模板项（合并多源）
 */
public class ChannelTemplate {

    private static final Pattern GROUP_PATTERN = Pattern.compile("^(.+),\\s*#genre#\\s*$");

    /** 模板条目：分组 + 频道显示名 */
    private static class TemplateItem {
        final String group;
        final String channelName;
        TemplateItem(String group, String channelName) {
            this.group = group;
            this.channelName = channelName;
        }
    }

    private final List<TemplateItem> items = new ArrayList<>();

    public boolean isEmpty() {
        return items.isEmpty();
    }

    /**
     * 解析模板文本
     */
    public static ChannelTemplate parse(String content) {
        ChannelTemplate template = new ChannelTemplate();
        if (content == null || content.isEmpty()) return template;

        String currentGroup = "未分类";
        try (BufferedReader reader = new BufferedReader(new StringReader(content))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                Matcher gm = GROUP_PATTERN.matcher(line);
                if (gm.matches()) {
                    currentGroup = gm.group(1).trim();
                    continue;
                }

                // 频道行：模板可能是"频道名"或"频道名,占位URL"，仅取频道名
                int comma = line.indexOf(',');
                String name = comma > 0 ? line.substring(0, comma).trim() : line;
                if (!name.isEmpty()) {
                    template.items.add(new TemplateItem(currentGroup, name));
                }
            }
        } catch (Exception e) {
            Timber.w(e, "解析频道模板失败");
        }
        return template;
    }

    /**
     * 按模板对原始分组进行过滤与重排，并合并匹配频道的多个源。
     *
     * @param sourceGroups 原始（已合并的）分组列表
     * @param urlsLimit    单频道最多线路数，<=0 表示不限制
     * @return 按模板组织后的分组列表
     */
    public List<ChannelGroup> apply(List<ChannelGroup> sourceGroups, int urlsLimit) {
        // 建立 归一化key -> 实际频道列表 的索引（对入参与元素做空值防御）
        Map<String, List<LiveChannel>> index = new LinkedHashMap<>();
        if (sourceGroups == null) return new ArrayList<>();
        for (ChannelGroup group : sourceGroups) {
            if (group == null || group.getChannels() == null) continue;
            for (LiveChannel ch : group.getChannels()) {
                if (ch == null) continue;
                String key = ChannelNameMatcher.normalizeKey(ch.getChannelName());
                if (key == null || key.isEmpty()) continue;
                List<LiveChannel> list = index.get(key);
                if (list == null) {
                    list = new ArrayList<>();
                    index.put(key, list);
                }
                list.add(ch);
            }
        }

        Map<String, ChannelGroup> resultGroups = new LinkedHashMap<>();
        // 已加入的"分组名 + 频道名"，用于 O(1) 去重，
        // 替代 ChannelGroup#findChannelByName 的线性扫描（模板项可达上千条）
        java.util.Set<String> added = new java.util.HashSet<>();
        for (TemplateItem item : items) {
            String key = ChannelNameMatcher.normalizeKey(item.channelName);
            List<LiveChannel> matched = index.get(key);

            // 模板频道
            ChannelGroup group = resultGroups.get(item.group);
            if (group == null) {
                group = new ChannelGroup(item.group);
                resultGroups.put(item.group, group);
            }
            // 避免同一模板项重复加入
            if (!added.add(item.group + "\u0001" + item.channelName)) continue;

            LiveChannel channel = new LiveChannel(item.channelName, item.group);
            if (matched != null) {
                for (LiveChannel src : matched) {
                    // 继承台标 / EPG id
                    if (channel.getLogoUrl() == null && src.getLogoUrl() != null) {
                        channel.setLogoUrl(src.getLogoUrl());
                    }
                    if (channel.getEpgId() == null && src.getEpgId() != null) {
                        channel.setEpgId(src.getEpgId());
                    }
                    if (src.getSourceUrls() != null) {
                        for (String url : src.getSourceUrls()) {
                            if (urlsLimit > 0 && channel.getSourceCount() >= urlsLimit) break;
                            channel.addSourceUrl(url);
                        }
                    }
                }
            }
            // 仅保留有源的频道（模板里有但实际无源的丢弃）
            if (channel.getSourceCount() > 0) {
                group.addChannel(channel);
            }
        }

        List<ChannelGroup> result = new ArrayList<>();
        int idx = 0;
        for (ChannelGroup g : resultGroups.values()) {
            if (g.getChannelCount() > 0) {
                g.setGroupIndex(idx++);
                result.add(g);
            }
        }
        return result;
    }
}
