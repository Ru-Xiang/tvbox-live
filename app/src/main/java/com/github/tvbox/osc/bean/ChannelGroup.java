package com.github.tvbox.osc.bean;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * 频道分组数据模型
 * 表示一个频道组，如 "央视频道"、"卫视频道"、"地方频道" 等
 *
 * <p><b>性能说明：</b>添加频道时需要去重，但分组内频道可达上千个，
 * 用 List#contains（触发 LiveChannel.equals 的字符串比较）会退化为 O(n²)。
 * 因此内部维护一个 HashSet 索引做 O(1) 去重判断。
 */
public class ChannelGroup implements Serializable {

    /** 分组名称 */
    private String groupName;

    /** 分组内的频道列表 */
    private List<LiveChannel> channels;

    /**
     * 频道去重索引（与 channels 同步维护，懒初始化）。
     * 仅作索引用途，不参与序列化。
     */
    private transient HashSet<LiveChannel> channelSet;

    /** 分组图标 */
    private String groupIcon;

    /** 排序序号 */
    private int sortOrder;

    /** 分组索引（在列表中的位置） */
    private int groupIndex;

    /** 是否展开 */
    private boolean expanded = true;

    public ChannelGroup() {
        this.channels = new ArrayList<>();
    }

    public ChannelGroup(String groupName) {
        this();
        this.groupName = groupName;
    }

    public ChannelGroup(String groupName, int sortOrder) {
        this(groupName);
        this.sortOrder = sortOrder;
    }

    /**
     * 获取去重索引，懒初始化并与当前 channels 对齐。
     * （setChannels / 反序列化后索引会被置空，此处按需重建）
     */
    private HashSet<LiveChannel> indexSet() {
        if (channels == null) channels = new ArrayList<>();
        if (channelSet == null || channelSet.size() != channels.size()) {
            channelSet = new HashSet<>(Math.max(16, channels.size() * 2));
            channelSet.addAll(channels);
        }
        return channelSet;
    }

    /** 添加频道到分组（会把频道的分组名设置为本分组名） */
    public void addChannel(LiveChannel channel) {
        if (channel == null) return;
        // 先设分组名再入索引：LiveChannel 的 equals/hashCode 依赖 groupName，
        // 若先入索引再改名会导致索引中的哈希失效。
        channel.setGroupName(this.groupName);
        if (indexSet().add(channel)) {
            channels.add(channel);
        }
    }

    /**
     * 添加频道引用但不修改其原始分组名
     * 用于构建"收藏"等虚拟分组，保证频道的稳定 key 不被破坏
     */
    public void addChannelRef(LiveChannel channel) {
        if (channel == null) return;
        if (indexSet().add(channel)) {
            channels.add(channel);
        }
    }

    /** 获取分组中的频道数量 */
    public int getChannelCount() {
        return channels != null ? channels.size() : 0;
    }

    /** 根据频道名称查找频道 */
    public LiveChannel findChannelByName(String name) {
        if (name == null || channels == null) return null;
        for (LiveChannel channel : channels) {
            if (name.equals(channel.getChannelName())) {
                return channel;
            }
        }
        return null;
    }

    /**
     * 移除无效频道（没有播放源的）。
     * 注意：不使用 Collection#removeIf（API 24），以兼容 Android 5.x/6.0 设备。
     */
    public void removeInvalidChannels() {
        if (channels == null) return;
        java.util.Iterator<LiveChannel> it = channels.iterator();
        while (it.hasNext()) {
            LiveChannel ch = it.next();
            if (ch == null || ch.getSourceCount() == 0) it.remove();
        }
        // 列表已变化，失效索引（下次添加时按新列表重建）
        channelSet = null;
    }

    // ============ Getter / Setter ============

    public String getGroupName() { return groupName; }
    public void setGroupName(String groupName) { this.groupName = groupName; }

    public List<LiveChannel> getChannels() {
        if (channels == null) channels = new ArrayList<>();
        return channels;
    }

    /**
     * 获取频道列表的<b>线程安全快照</b>，供 UI 层使用。
     *
     * <p>后台线程（源刷新、测速后的去重与线路偏好应用）会对 {@link #channels}
     * 做结构性修改（{@code Iterator.remove()} 等）。UI 线程若直接
     * {@code new ArrayList<>(group.getChannels())}，拷贝构造器内部遍历时会抛
     * {@link java.util.ConcurrentModificationException}——且该调用通常发生在
     * 焦点回调里，不受 onKeyDown 的 try/catch 保护，会直接闪退。
     *
     * <p>这里用「索引遍历 + 失败重试 + 兜底」三重防御：索引遍历不依赖迭代器的
     * modCount 校验，即使中途元素被移除最多只会读到 null 或短少几项，不会抛异常。
     *
     * @return 永不为 null 的新列表；元素中已剔除 null
     */
    public List<LiveChannel> snapshotChannels() {
        List<LiveChannel> src = channels;
        if (src == null) return new ArrayList<>();
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                int n = src.size();
                List<LiveChannel> copy = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    // 每次都重新读 size，后台线程可能已缩短列表
                    if (i >= src.size()) break;
                    LiveChannel ch = src.get(i);
                    if (ch != null) copy.add(ch);
                }
                return copy;
            } catch (Throwable ignore) {
                // 与后台修改撞上了，重试一次
            }
        }
        return new ArrayList<>();
    }
    /**
     * 设置频道列表。为保证后续 remove/removeAll/add 等原地修改安全，
     * 统一用可变 ArrayList 包装（防止传入不可变列表导致 UnsupportedOperationException）。
     */
    public void setChannels(List<LiveChannel> channels) {
        this.channels = channels == null ? new ArrayList<>() : new ArrayList<>(channels);
        // 列表被整体替换，失效索引
        this.channelSet = null;
    }

    public String getGroupIcon() { return groupIcon; }
    public void setGroupIcon(String groupIcon) { this.groupIcon = groupIcon; }

    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }

    public int getGroupIndex() { return groupIndex; }
    public void setGroupIndex(int groupIndex) { this.groupIndex = groupIndex; }

    public boolean isExpanded() { return expanded; }
    public void setExpanded(boolean expanded) { this.expanded = expanded; }

    @Override
    public String toString() {
        return "ChannelGroup{" +
                "groupName='" + groupName + '\'' +
                ", channelCount=" + getChannelCount() +
                '}';
    }
}
