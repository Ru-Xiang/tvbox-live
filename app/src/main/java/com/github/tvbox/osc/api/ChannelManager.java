package com.github.tvbox.osc.api;

import android.content.Context;

import com.github.tvbox.osc.bean.ChannelGroup;
import com.github.tvbox.osc.bean.LiveChannel;
import com.github.tvbox.osc.bean.SpeedTestResult;
import com.github.tvbox.osc.cache.LiveChannelDatabase;
import com.github.tvbox.osc.service.SpeedTestEngine;
import com.github.tvbox.osc.util.HawkConfig;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.orhanobut.hawk.Hawk;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import timber.log.Timber;

/**
 * 频道管理器
 * 
 * 核心管理类，负责：
 * 1. 直播源的获取、更新、缓存
 * 2. 频道分组和搜索
 * 3. 收藏频道管理
 * 4. 频道源与测速引擎的协作
 * 5. 频道数据的持久化和恢复
 */
public class ChannelManager {

    private static volatile ChannelManager instance;

    private final Context context;
    private final IptvApiService iptvApiService;
    private final SpeedTestEngine speedTestEngine;
    private final LiveChannelDatabase database;
    private final Gson gson;
    private final ExecutorService ioExecutor;

    /**
     * 原始频道分组列表（来自直播源，未过滤）。
     * volatile：后台 IO 线程整体替换该引用，主线程读取，需保证可见性。
     */
    private volatile List<ChannelGroup> rawGroups;

    /**
     * 对外展示的频道分组列表。
     * volatile：rebuildDisplayGroups 在 IO 线程整体替换该引用，UI 线程遍历读取。
     * 调用方必须"只取一次引用"再遍历，不可在循环条件与取值处分别调用 getter，
     * 否则可能一次读到旧列表、一次读到新的更短列表而越界。
     */
    private volatile List<ChannelGroup> channelGroups;

    /** 线路黑名单：channelKey -> 被拉黑的线路 URL 列表（仅该频道内生效） */
    private final Map<String, List<String>> sourceBlacklist;

    /** 线路自定义顺序：channelKey -> URL 顺序列表（置顶/排序后持久化） */
    private final Map<String, List<String>> sourceOrder;

    /** 当前选中的分组索引 */
    private int currentGroupIndex = 0;

    /** 当前选中的频道索引 */
    private int currentChannelIndex = 0;

    /** 频道管理回调 */
    public interface ChannelCallback {
        void onChannelsLoaded(List<ChannelGroup> groups);
        void onChannelsUpdated(List<ChannelGroup> groups, int newCount);
        void onError(String errorMsg);
    }

    private ChannelManager(Context context) {
        this.context = context.getApplicationContext();
        this.iptvApiService = IptvApiService.getInstance();
        this.speedTestEngine = SpeedTestEngine.getInstance();
        this.database = LiveChannelDatabase.getInstance(context);
        this.gson = new Gson();
        // 守护线程 + 低优先级：本类是永久单例，线程池从不 shutdown，
        // 非守护线程会阻止进程退出；写库/持久化也不应与播放抢 CPU。
        this.ioExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ChannelManagerIO");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        this.rawGroups = new ArrayList<>();
        this.channelGroups = new ArrayList<>();
        this.sourceBlacklist = loadSourceMap(HawkConfig.LIVE_SOURCE_BLACKLIST);
        this.sourceOrder = loadSourceMap(HawkConfig.LIVE_SOURCE_ORDER);
    }

    /** 从 Hawk 加载 channelKey -> List<url> 映射（用于线路黑名单/顺序） */
    private Map<String, List<String>> loadSourceMap(String hawkKey) {
        try {
            String json = Hawk.get(hawkKey, null);
            if (json != null && !json.isEmpty()) {
                Map<String, List<String>> map = gson.fromJson(json,
                        new TypeToken<Map<String, List<String>>>() {}.getType());
                if (map != null) return new HashMap<>(map);
            }
        } catch (Exception e) {
            Timber.w(e, "加载线路映射失败: %s", hawkKey);
        }
        return new HashMap<>();
    }

    public static ChannelManager getInstance(Context context) {
        if (instance == null) {
            synchronized (ChannelManager.class) {
                if (instance == null) {
                    instance = new ChannelManager(context);
                }
            }
        }
        return instance;
    }

    // ============ 频道加载 ============

    /**
     * 初始化加载频道
     * 优先从本地缓存加载，若缓存过期或不存在则从网络获取
     */
    public void loadChannels(ChannelCallback callback) {
        ioExecutor.execute(() -> {
            try {
                // 先从本地数据库加载
                List<ChannelGroup> cachedGroups = loadFromDatabase();
                // 已存在测速生成的本地列表时，默认直接使用它（下次打开仍可用），
                // 不因自动更新周期而重新联网覆盖，保证优先使用最佳线路本地列表。
                boolean hasLocal = hasLocalPlaylist();
                if (!cachedGroups.isEmpty() && (hasLocal || !iptvApiService.needsUpdate())) {
                    rawGroups = cachedGroups;
                    rebuildDisplayGroups();
                    Timber.d("从%s加载: %d 个分组",
                            hasLocal ? "本地测速列表" : "缓存", cachedGroups.size());
                    if (callback != null) callback.onChannelsLoaded(channelGroups);
                    return;
                }

                // 数据库为空但本地列表仍在（如数据库被清理/迁移失败）时，
                // 从持久化的本地播放列表文件恢复，保证“下次打开仍可复用”。
                if (cachedGroups.isEmpty() && hasLocal) {
                    List<ChannelGroup> restored = restoreFromLocalPlaylist();
                    if (restored != null && !restored.isEmpty()) {
                        rawGroups = restored;
                        rebuildDisplayGroups();
                        saveToDatabase(restored);
                        Timber.i("数据库为空，已从本地播放列表恢复: %d 个分组", restored.size());
                        if (callback != null) callback.onChannelsLoaded(channelGroups);
                        return;
                    }
                }

                // 从网络获取
                Timber.d("缓存过期或不存在，从网络获取...");
                fetchAndUpdateChannels(callback);

            } catch (Throwable e) {
                Timber.e(e, "加载频道失败");
                if (callback != null) {
                    try { callback.onError("加载频道失败: " + e.getMessage()); } catch (Throwable ignore) {}
                }
            }
        });
    }

    /**
     * 强制从网络刷新频道
     */
    public void refreshChannels(ChannelCallback callback) {
        iptvApiService.fetchAndMergeSources(new IptvApiService.FetchCallback() {
            @Override
            public void onSuccess(List<ChannelGroup> groups, int totalChannels) {
                rawGroups = groups;
                rebuildDisplayGroups();
                // 异步保存到数据库
                saveToDatabase(groups);
                Timber.i("刷新频道完成: %d 个分组, %d 个频道", groups.size(), totalChannels);
                if (callback != null) callback.onChannelsUpdated(channelGroups, totalChannels);
            }

            @Override
            public void onError(String errorMsg) {
                Timber.e("刷新频道失败: %s", errorMsg);
                if (callback != null) callback.onError(errorMsg);
            }

            @Override
            public void onProgress(int current, int total, String message) {
                Timber.d("刷新进度: %d/%d - %s", current, total, message);
            }
        });
    }

    /**
     * 测速前重新拉取全量直播源。
     *
     * 每次测速都强制从"远程订阅列表 + 自定义源"联网获取全量线路（并按 IP 版本、
     * 模板过滤后）刷新 rawGroups，使测速覆盖最新的全部线路；启用模板时，rawGroups
     * 已只含模板内频道，因此不在模板中的频道会被自然跳过。
     *
     * 刷新成功后通过回调返回待测频道的扁平列表；失败时回退到当前内存中的频道列表，
     * 保证测速仍可进行。
     */
    public void reloadForSpeedTest(SpeedTestReloadCallback callback) {
        iptvApiService.fetchAndMergeSources(new IptvApiService.FetchCallback() {
            @Override
            public void onSuccess(List<ChannelGroup> groups, int totalChannels) {
                try {
                    if (groups != null && !groups.isEmpty()) {
                        rawGroups = groups;
                        rebuildDisplayGroups();
                        saveToDatabase(groups);
                    }
                } catch (Throwable t) {
                    Timber.w(t, "测速前刷新源后处理异常");
                }
                notifyReadySafely(callback);
            }

            @Override
            public void onError(String errorMsg) {
                Timber.w("测速前刷新源失败，使用当前列表测速: %s", errorMsg);
                // 刷新失败不阻断测速，回退到当前内存中的频道
                notifyReadySafely(callback);
            }

            @Override
            public void onProgress(int current, int total, String message) {
                if (callback != null) {
                    try {
                        callback.onProgress(current, total, message);
                    } catch (Throwable ignore) {
                        // 进度回调异常不应影响拉源流程
                    }
                }
            }
        });
    }

    /**
     * 安全地把待测速频道列表回传给上层。
     *
     * <p>低内存设备（如小米盒子等电视盒子）汇总全量频道时可能触发
     * {@link OutOfMemoryError}，而它属于 {@link Error} 而非 {@link Exception}。
     * 若在此处逃逸，会一路穿透到拉源线程的 catch 分支并再次触发列表构建，
     * 最终在拉源线程抛出未捕获异常导致<b>进程崩溃</b>（正是"加载测速列表"阶段的闪退）。
     * 因此这里统一兜住 {@link Throwable}，并保证只回调一次。
     */
    private void notifyReadySafely(SpeedTestReloadCallback callback) {
        if (callback == null) return;
        ArrayList<LiveChannel> channels;
        try {
            channels = getAllChannelsFlat();
        } catch (Throwable t) {
            Timber.w(t, "汇总待测速频道失败，回退为空列表");
            channels = new ArrayList<>();
        }
        try {
            callback.onReady(channels);
        } catch (Throwable t) {
            // 下游 onReady（构造测速任务等）同样可能 OOM，绝不能让它逃逸到拉源线程
            Timber.w(t, "测速准备回调执行失败");
        }
    }

    /** 测速前刷新源的回调 */
    public interface SpeedTestReloadCallback {
        void onReady(ArrayList<LiveChannel> channels);
        default void onProgress(int current, int total, String message) {}
    }

    // ============ 频道导航 ============

    /** 获取当前频道分组列表 */
    public List<ChannelGroup> getChannelGroups() {
        return channelGroups;
    }

    /**
     * 获取当前源中所有频道名（去重）。
     *
     * 供 EPG 使用：解析 XMLTV 时只保留这些频道的节目，
     * 避免把整份节目单（可能上千个频道）都读进内存。
     */
    public synchronized java.util.Set<String> getAllChannelNames() {
        java.util.Set<String> names = new java.util.HashSet<>();
        try {
            // 只读一次 volatile 引用，避免三次读取落在不同快照上
            List<ChannelGroup> raw = rawGroups;
            List<ChannelGroup> groups = (raw != null && !raw.isEmpty()) ? raw : channelGroups;
            if (groups == null) return names;
            for (ChannelGroup group : new ArrayList<>(groups)) {
                if (group == null) continue;
                // 用抗并发快照：本方法可能在 EPG 线程执行，而源刷新线程
                // 正对同一分组做结构性修改，增强 for 会抛 ConcurrentModificationException
                for (LiveChannel ch : group.snapshotChannels()) {
                    if (ch == null) continue;
                    String name = ch.getChannelName();
                    if (name != null && !name.trim().isEmpty()) {
                        names.add(name.trim());
                    }
                }
            }
        } catch (Exception e) {
            Timber.w(e, "获取频道名集合失败");
        }
        return names;
    }

    /** 当前选中分组索引 */
    public int getCurrentGroupIndex() {
        return currentGroupIndex;
    }

    /** 当前选中频道索引 */
    public int getCurrentChannelIndex() {
        return currentChannelIndex;
    }

    /** 获取当前分组 */
    public ChannelGroup getCurrentGroup() {
        try {
            // 只读一次 volatile 引用，避免后台替换列表后 size 检查与 get 用到不同快照
            List<ChannelGroup> groups = channelGroups;
            if (groups == null || groups.isEmpty()) return null;
            int idx = currentGroupIndex;
            if (idx < 0 || idx >= groups.size()) {
                idx = 0;
                currentGroupIndex = 0;
            }
            return groups.get(idx);
        } catch (Exception e) {
            return null;
        }
    }

    /** 获取当前频道 */
    public LiveChannel getCurrentChannel() {
        try {
            ChannelGroup group = getCurrentGroup();
            if (group == null) return null;
            // 同样只取一次列表引用
            List<LiveChannel> channels = group.getChannels();
            if (channels == null || channels.isEmpty()) return null;
            int idx = currentChannelIndex;
            if (idx < 0 || idx >= channels.size()) {
                idx = 0;
                currentChannelIndex = 0;
            }
            return channels.get(idx);
        } catch (Exception e) {
            return null;
        }
    }

    /** 切换到下一个频道 */
    public synchronized LiveChannel nextChannel() {
        try {
            ChannelGroup group = getCurrentGroup();
            if (group == null) return null;
            List<LiveChannel> channels = group.getChannels();
            if (channels == null) return null;

            currentChannelIndex++;
            if (currentChannelIndex >= channels.size()) {
                // 切换到下一个分组的第一个频道
                currentChannelIndex = 0;
                List<ChannelGroup> groups = channelGroups;
                if (groups == null || groups.isEmpty()) return null;
                currentGroupIndex++;
                if (currentGroupIndex >= groups.size()) {
                    currentGroupIndex = 0;
                }
                group = getCurrentGroup();
                if (group == null) return null;
            }

            LiveChannel channel = getCurrentChannel();
            if (channel != null) {
                saveLastChannel(channel);
            }
            return channel;
        } catch (Exception e) {
            Timber.w(e, "nextChannel 异常");
            return null;
        }
    }

    /** 切换到上一个频道 */
    public synchronized LiveChannel prevChannel() {
        try {
            ChannelGroup group = getCurrentGroup();
            if (group == null || group.getChannels() == null) return null;

            currentChannelIndex--;
            if (currentChannelIndex < 0) {
                // 切换到上一个分组的最后一个频道
                List<ChannelGroup> groups = channelGroups;
                if (groups == null || groups.isEmpty()) return null;
                currentGroupIndex--;
                if (currentGroupIndex < 0) {
                    currentGroupIndex = groups.size() - 1;
                }
                group = getCurrentGroup();
                List<LiveChannel> prevChannels = group == null ? null : group.getChannels();
                if (prevChannels != null && !prevChannels.isEmpty()) {
                    currentChannelIndex = prevChannels.size() - 1;
                } else {
                    currentChannelIndex = 0;
                }
            }

            LiveChannel channel = getCurrentChannel();
            if (channel != null) {
                saveLastChannel(channel);
            }
            return channel;
        } catch (Exception e) {
            Timber.w(e, "prevChannel 异常");
            return null;
        }
    }

    /** 选择指定分组和频道 */
    public synchronized LiveChannel selectChannel(int groupIndex, int channelIndex) {
        try {
            // 只读一次 volatile 引用，避免 size 校验与 get 落在不同快照上
            List<ChannelGroup> groups = channelGroups;
            if (groups == null || groups.isEmpty()) return null;
            if (groupIndex >= 0 && groupIndex < groups.size()) {
                currentGroupIndex = groupIndex;
                ChannelGroup group = groups.get(groupIndex);
                List<LiveChannel> channels = group == null ? null : group.getChannels();
                if (channels != null && channelIndex >= 0 && channelIndex < channels.size()) {
                    currentChannelIndex = channelIndex;
                }
            }
            LiveChannel channel = getCurrentChannel();
            if (channel != null) {
                saveLastChannel(channel);
            }
            return channel;
        } catch (Exception e) {
            Timber.w(e, "selectChannel 异常");
            return null;
        }
    }

    /**
     * 把"当前分组/频道"同步到指定频道对象所在的位置。
     *
     * 用于从频道列表长按菜单等入口直接播放某个频道时：若不同步，
     * 后续依赖 {@link #getCurrentChannel()} 的逻辑（播放超时换源、上下切源、EPG 刷新）
     * 仍指向旧频道，会导致"播放了其他频道"。
     *
     * @return true 表示已定位并同步成功
     */
    public synchronized boolean syncCurrentTo(LiveChannel target) {
        if (target == null) return false;
        // 只取一次引用，两轮匹配都基于同一快照，避免中途被后台替换
        List<ChannelGroup> groups = channelGroups;
        if (groups == null) return false;
        try {
            for (int g = 0; g < groups.size(); g++) {
                ChannelGroup group = groups.get(g);
                if (group == null || group.getChannels() == null) continue;
                List<LiveChannel> chs = group.getChannels();
                for (int c = 0; c < chs.size(); c++) {
                    // 先按对象引用匹配（同一对象），失败再按频道名匹配（跨分组引用同一频道）
                    if (chs.get(c) == target) {
                        currentGroupIndex = g;
                        currentChannelIndex = c;
                        saveLastChannel(target);
                        return true;
                    }
                }
            }
            String name = target.getChannelName();
            if (name != null) {
                for (int g = 0; g < groups.size(); g++) {
                    ChannelGroup group = groups.get(g);
                    if (group == null || group.getChannels() == null) continue;
                    List<LiveChannel> chs = group.getChannels();
                    for (int c = 0; c < chs.size(); c++) {
                        LiveChannel ch = chs.get(c);
                        if (ch != null && name.equals(ch.getChannelName())) {
                            currentGroupIndex = g;
                            currentChannelIndex = c;
                            saveLastChannel(ch);
                            return true;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Timber.w(e, "syncCurrentTo 异常");
        }
        return false;
    }

    /** 恢复上次播放的频道 */
    public LiveChannel restoreLastChannel() {
        try {
            String lastChannelName = Hawk.get(HawkConfig.LIVE_LAST_CHANNEL, "");
            String lastGroupName = Hawk.get(HawkConfig.LIVE_LAST_GROUP, "");

            if (lastChannelName == null || lastChannelName.isEmpty()) return getCurrentChannel();

            List<ChannelGroup> groups = channelGroups;
            if (groups == null || groups.isEmpty()) return null;
            for (int g = 0; g < groups.size(); g++) {
                ChannelGroup group = groups.get(g);
                if (group == null || group.getChannels() == null) continue;
                if (lastGroupName == null || lastGroupName.isEmpty()
                        || lastGroupName.equals(group.getGroupName())) {
                    for (int c = 0; c < group.getChannels().size(); c++) {
                        LiveChannel ch = group.getChannels().get(c);
                        if (ch != null && lastChannelName.equals(ch.getChannelName())) {
                            currentGroupIndex = g;
                            currentChannelIndex = c;
                            return ch;
                        }
                    }
                }
            }
            return getCurrentChannel();
        } catch (Exception e) {
            Timber.w(e, "restoreLastChannel 异常");
            return getCurrentChannel();
        }
    }

    // ============ 频道搜索 ============

    /**
     * 搜索频道（多策略）：
     * 1. 频道名/编号 子串匹配（大小写不敏感）
     * 2. 归一化后子串匹配（CCTV-1 / CCTV1 / 央视一套 等视为同一频道）
     * 3. 拼音首字母匹配（如输入 "HN" 命中 "湖南卫视"）
     */
    public List<LiveChannel> searchChannels(String keyword) {
        List<LiveChannel> results = new ArrayList<>();
        if (keyword == null || keyword.isEmpty()) return results;

        String lowerKeyword = keyword.toLowerCase(java.util.Locale.ROOT);
        String normKeyword = com.github.tvbox.osc.util.ChannelNameMatcher.normalizeKey(keyword);
        // 判断是否为纯字母首字母查询（如 "HN"、"WS"、"CCTV"），是则启用首字母匹配
        boolean initialsQuery = keyword.matches("[A-Za-z0-9]{2,}");

        // 只读一次 volatile 引用；用 HashSet 做 O(1) 去重（原先 results.contains 为 O(n²)）
        List<ChannelGroup> groups = channelGroups;
        if (groups == null) return results;
        java.util.HashSet<LiveChannel> seen = new java.util.HashSet<>();

        try {
            for (ChannelGroup group : new ArrayList<>(groups)) {
                if (group == null) continue;
                // 抗并发快照：后台线程可能正在结构性修改该分组
                for (LiveChannel channel : group.snapshotChannels()) {
                    if (channel == null) continue;
                    String name = channel.getChannelName();
                    if (name == null) continue; // 原先直接 name.toLowerCase() 会 NPE
                    String num = channel.getChannelNum();
                    boolean hit = name.toLowerCase(java.util.Locale.ROOT).contains(lowerKeyword)
                            || (num != null && num.contains(keyword))
                            || (!normKeyword.isEmpty()
                                && com.github.tvbox.osc.util.ChannelNameMatcher.normalizeKey(name).contains(normKeyword))
                            || (initialsQuery
                                && com.github.tvbox.osc.util.PinyinIndex.matchesInitials(name, keyword));
                    if (hit && seen.add(channel)) {
                        results.add(channel);
                    }
                }
            }
        } catch (Exception e) {
            Timber.w(e, "搜索频道异常，返回已匹配结果");
        }
        return results;
    }

    /**
     * 在当前分组内按首字母跳转到下一个匹配频道。
     * 用于遥控器上"数字键 -> 拼音首字母跳转"或字母边栏。
     *
     * @param letter A-Z / 0-9 首字符
     * @return 命中频道，未命中返回 null
     */
    public synchronized LiveChannel jumpToFirstLetter(char letter) {
        char target = Character.toUpperCase(letter);
        ChannelGroup group = getCurrentGroup();
        if (group == null) return null;
        // 用快照：原先在循环外读一次 size 再 list.get(idx)，
        // 后台线程缩短列表时会抛 IndexOutOfBoundsException
        List<LiveChannel> list = group.snapshotChannels();
        int n = list.size();
        if (n == 0) return null;
        int start = (Math.max(-1, currentChannelIndex) + 1) % n;
        for (int i = 0; i < n; i++) {
            int idx = (start + i) % n;
            LiveChannel ch = list.get(idx);
            if (ch == null) continue;
            if (com.github.tvbox.osc.util.PinyinIndex.firstLetter(ch.getChannelName()) == target) {
                currentChannelIndex = idx;
                saveLastChannel(ch);
                return ch;
            }
        }
        return null;
    }

    // ============ 线路（源）级 置顶 / 黑名单（仅当前频道生效） ============

    /**
     * 将指定频道的某条线路置顶，并持久化该频道的线路顺序。
     *
     * @return 是否发生了变更
     */
    public boolean pinSource(LiveChannel channel, String url) {
        if (channel == null || url == null) return false;
        boolean moved = channel.moveSourceToTop(url);
        if (moved) {
            // getSourceUrls() 可能为 null，直接传给 ArrayList 构造器会 NPE
            List<String> urls = channel.getSourceUrls();
            sourceOrder.put(channel.getKey(),
                    urls == null ? new ArrayList<>() : new ArrayList<>(urls));
            persistSourceMap(HawkConfig.LIVE_SOURCE_ORDER, sourceOrder);
        }
        return moved;
    }

    /**
     * 将指定频道的某条线路加入黑名单并从该频道移除（仅该频道内生效）。
     *
     * @return 是否成功移除
     */
    public boolean blacklistSource(LiveChannel channel, String url) {
        if (channel == null || url == null) return false;
        boolean removed = channel.removeSource(url);
        String key = channel.getKey();
        List<String> blocked = sourceBlacklist.get(key);
        if (blocked == null) {
            blocked = new ArrayList<>();
            sourceBlacklist.put(key, blocked);
        }
        if (!blocked.contains(url)) {
            blocked.add(url);
            persistSourceMap(HawkConfig.LIVE_SOURCE_BLACKLIST, sourceBlacklist);
        }
        // 同步更新自定义顺序（移除该 url）
        List<String> order = sourceOrder.get(key);
        if (order != null && order.remove(url)) {
            persistSourceMap(HawkConfig.LIVE_SOURCE_ORDER, sourceOrder);
        }
        return removed;
    }

    /** 判断某频道的某条线路是否被拉黑 */
    public boolean isSourceBlacklisted(LiveChannel channel, String url) {
        if (channel == null || url == null) return false;
        List<String> blocked = sourceBlacklist.get(channel.getKey());
        return blocked != null && blocked.contains(url);
    }

    /** 清空所有频道的线路黑名单与自定义置顶顺序 */
    public void clearSourcePrefs() {
        sourceBlacklist.clear();
        sourceOrder.clear();
        persistSourceMap(HawkConfig.LIVE_SOURCE_BLACKLIST, sourceBlacklist);
        persistSourceMap(HawkConfig.LIVE_SOURCE_ORDER, sourceOrder);
    }

    /**
     * 对单个频道应用已保存的线路顺序与黑名单：
     * 先移除被拉黑的线路，再按自定义顺序重排。
     */
    private void applySourcePrefs(LiveChannel channel) {
        if (channel == null || channel.getSourceUrls() == null) return;
        String key = channel.getKey();
        List<String> blocked = sourceBlacklist.get(key);
        if (blocked != null && !blocked.isEmpty()) {
            for (String url : new ArrayList<>(blocked)) {
                channel.removeSource(url);
            }
        }
        List<String> order = sourceOrder.get(key);
        if (order != null && !order.isEmpty()) {
            channel.reorderSourcesBy(order);
        }
    }

    // ============ 频道统计 ============

    /** 获取总频道数 */
    public int getTotalChannelCount() {
        int count = 0;
        // 只读一次 volatile 引用并判空：rawGroups 在初始化完成前可能为 null
        List<ChannelGroup> groups = rawGroups;
        if (groups == null) return 0;
        try {
            for (ChannelGroup group : new ArrayList<>(groups)) {
                if (group != null) count += group.getChannelCount();
            }
        } catch (Exception e) {
            Timber.w(e, "统计频道数异常");
        }
        return count;
    }

    /** 获取总源数 */
    public int getTotalSourceCount() {
        int count = 0;
        List<ChannelGroup> groups = rawGroups;
        if (groups == null) return 0;
        try {
            for (ChannelGroup group : new ArrayList<>(groups)) {
                if (group == null) continue;
                // 抗并发快照：后台线程可能正在移除频道
                for (LiveChannel channel : group.snapshotChannels()) {
                    if (channel != null) count += channel.getSourceCount();
                }
            }
        } catch (Exception e) {
            Timber.w(e, "统计线路数异常");
        }
        return count;
    }

    /** 获取所有需测速的频道扁平列表（去重） */
    public ArrayList<LiveChannel> getAllChannelsFlat() {
        ArrayList<LiveChannel> allChannels = new ArrayList<>();
        List<ChannelGroup> groups = rawGroups;
        if (groups == null) return allChannels;
        // 用 Set 做 O(1) 去重判断，替代原先 List#contains 的 O(n) 扫描，
        // 频道数量较大时（全量订阅源可能上千个）避免平方级比较拖慢测速启动。
        java.util.HashSet<LiveChannel> seen = new java.util.HashSet<>();
        try {
            // 直接遍历 rawGroups，避免再复制一份分组列表（低内存设备上每份拷贝都可能压垮堆）
            for (ChannelGroup group : groups) {
                if (group == null) continue;
                for (LiveChannel channel : group.snapshotChannels()) {
                    if (channel != null && seen.add(channel)) {
                        allChannels.add(channel);
                    }
                }
            }
        } catch (Throwable e) {
            // 兜住 Error（含 OOM），避免汇总阶段异常逃逸导致闪退
            Timber.w(e, "汇总待测速频道异常");
        }
        return allChannels;
    }

    // ============ 展示列表构建 ============

    /**
     * 重新构建对外展示的分组列表：
     * 直接引用原始分组，并对每个频道应用线路级偏好（黑名单过滤 + 自定义置顶顺序）。
     *
     * <p>无可用播放源的频道不会进入展示列表（判定见 {@link #hasPlayableSource}），
     * 与本地节目列表的生成规则保持一致：测速后明确全部线路不可用的频道直接隐藏，
     * 避免用户点进去只能黑屏。剔除后为空的分组也不会显示。
     */
    public synchronized void rebuildDisplayGroups() {
        try {
            List<ChannelGroup> display = new ArrayList<>();
            List<ChannelGroup> src = rawGroups;
            if (src == null) src = new ArrayList<>();

            int hidden = 0;
            for (ChannelGroup raw : new ArrayList<>(src)) {
                if (raw == null) continue;
                ChannelGroup g = new ChannelGroup(raw.getGroupName());
                g.setSortOrder(raw.getSortOrder());
                List<LiveChannel> channels = raw.getChannels();
                if (channels != null) {
                    for (LiveChannel channel : new ArrayList<>(channels)) {
                        if (channel == null) continue;
                        try { applySourcePrefs(channel); } catch (Exception ignore) {}
                        // 线路偏好应用后再判断可用性（黑名单可能已移除全部可用线路）
                        if (!hasPlayableSource(channel)) {
                            hidden++;
                            continue;
                        }
                        g.addChannelRef(channel);
                    }
                }
                if (g.getChannelCount() > 0) {
                    display.add(g);
                }
            }

            for (int i = 0; i < display.size(); i++) {
                display.get(i).setGroupIndex(i);
            }

            this.channelGroups = display;
            if (hidden > 0) {
                Timber.i("展示列表已隐藏 %d 个无可用播放源的频道", hidden);
            }
        } catch (Throwable e) {
            // 低内存设备重建展示列表可能 OOM（Error），必须一并兜住避免闪退
            Timber.w(e, "rebuildDisplayGroups 异常");
        }
    }

    /**
     * 判断频道是否还有可播放的线路。
     *
     * 规则与 {@link #pickBestSource} 一致：
     * - 有任意线路测速可用 → 可播；
     * - 有 RTSP/RTMP 等无法测速的线路 → 视为可播（不能因测不了就删）；
     * - 全部线路都有测速记录且均失败 → 不可播；
     * - 完全没有测速记录（未测过 / 被配额截断） → 视为可播，避免误删。
     */
    private boolean hasPlayableSource(LiveChannel channel) {
        if (channel == null) return false;
        List<String> urls = channel.getSourceUrls();
        if (urls == null || urls.isEmpty()) return false;

        boolean anyTested = false;
        for (String url : urls) {
            SpeedTestResult r = speedTestEngine.getCachedResult(url);
            if (r == null) continue;
            if (r.isAvailable()) return true;
            if (SpeedTestEngine.UNTESTABLE.equals(r.getFailureType())) return true;
            anyTested = true;
        }
        // 有测速记录且全部失败 → 不可播；否则（无任何记录）保留
        return !anyTested;
    }

    /**
     * 按"单频道最多线路"裁剪 rawGroups 中每个频道的线路。
     *
     * 调用时机：测速完成后。此时线路已按可用性与质量排序，截断即保留最佳的若干条，
     * 既能提供换源备选，又避免超大列表长期占用内存与数据库空间。
     * 注意：下一次测速会重新联网拉取全量线路，不会因此丢失候选。
     */
    private synchronized void applyUrlsLimitToRaw() {
        int urlsLimit = Hawk.get(HawkConfig.IPTV_URLS_LIMIT, HawkConfig.DEFAULT_URLS_LIMIT);
        if (urlsLimit <= 0 || rawGroups == null) return;
        int trimmed = 0;
        for (ChannelGroup group : new ArrayList<>(rawGroups)) {
            if (group == null || group.getChannels() == null) continue;
            for (LiveChannel ch : new ArrayList<>(group.getChannels())) {
                if (ch == null) continue;
                List<String> urls = ch.getSourceUrls();
                if (urls == null || urls.size() <= urlsLimit) continue;
                ch.setSourceUrls(new ArrayList<>(urls.subList(0, urlsLimit)));
                List<Double> speeds = ch.getSpeeds();
                if (speeds != null && speeds.size() > urlsLimit) {
                    ch.setSpeeds(new ArrayList<>(speeds.subList(0, urlsLimit)));
                }
                ch.setSourceIndex(0);
                trimmed++;
            }
        }
        if (trimmed > 0) {
            Timber.d("测速后按上限 %d 裁剪了 %d 个频道的线路", urlsLimit, trimmed);
        }
    }

    private void persistSourceMap(String hawkKey, Map<String, List<String>> map) {
        try {
            Hawk.put(hawkKey, gson.toJson(map));
        } catch (Exception e) {
            Timber.w(e, "保存线路映射失败: %s", hawkKey);
        }
    }

    // ============ 测速后本地播放列表 ============

    /**
     * 测速完成后调用：
     * 1. 依据测速结果重建展示分组（线路已被测速引擎按最佳排序 / 过滤不可用）；
     * 2. 将整体订阅源（默认订阅 + 自定义源）的频道数据持久化到数据库；
     * 3. 为每个频道选出"最佳线路"，生成本地播放列表并持久化（Hawk + 文件）。
     *
     * 该方法在 IO 线程执行，可安全地从测速完成回调中调用。
     */
    public void persistAfterSpeedTest() {
        ioExecutor.execute(() -> {
            try {
                // 测速已覆盖全部线路，此处按“单频道最多线路”只保留最佳的若干条
                // （测速引擎已把可用线路按质量排在前面），控制长期内存与数据库体积
                applyUrlsLimitToRaw();
                // 重建展示列表以反映测速后的线路排序与过滤
                rebuildDisplayGroups();
                // 持久化频道数据（含排序后的线路顺序与速度）
                saveToDatabase(rawGroups);
                // 生成并持久化"最佳线路"本地播放列表
                String playlist = generateLocalPlaylist();
                saveLocalPlaylist(playlist);
                Timber.i("测速后已生成并持久化最佳线路本地播放列表");
            } catch (Throwable e) {
                // 生成/序列化大列表在低内存设备上可能 OOM（Error），需兜住
                Timber.e(e, "测速后持久化本地播放列表失败");
            }
        });
    }

    /**
     * 为每个频道选出最佳线路，生成 TXT 格式的本地播放列表。
     * 格式：分组名,#genre# 起始，随后每行 "频道名,最佳URL"。
     *
     * <p>无可用播放源的频道会被<b>整个剔除</b>，不写入列表；
     * 剔除后若某分组一个频道都不剩，该分组标题也不会出现（headerWritten 惰性写入）。
     */
    public synchronized String generateLocalPlaylist() {
        StringBuilder sb = new StringBuilder();
        List<ChannelGroup> groups = rawGroups;
        if (groups == null) return "";
        int kept = 0;
        int skipped = 0;
        for (ChannelGroup group : new ArrayList<>(groups)) {
            if (group == null || group.getChannels() == null || group.getChannels().isEmpty()) continue;
            String groupName = group.getGroupName() != null ? group.getGroupName() : "未分类";
            boolean headerWritten = false;
            for (LiveChannel channel : new ArrayList<>(group.getChannels())) {
                if (channel == null) continue;
                String name = channel.getChannelName();
                if (name == null || name.trim().isEmpty()) continue;
                String bestUrl = pickBestSource(channel);
                if (bestUrl == null || bestUrl.isEmpty()) {
                    // 该频道所有线路均无可用源：不写入节目列表
                    skipped++;
                    continue;
                }
                if (!headerWritten) {
                    sb.append(groupName).append(",#genre#").append('\n');
                    headerWritten = true;
                }
                sb.append(name).append(',').append(bestUrl).append('\n');
                kept++;
            }
        }
        Timber.i("生成本地播放列表: 保留 %d 个频道，剔除 %d 个无可用源频道", kept, skipped);
        return sb.toString();
    }

    /**
     * 选出频道的最佳线路 URL：
     * 优先取测速缓存中"可用 + 综合评分最高（同分取速度更快）"的线路。
     *
     * <p><b>无可用线路时返回 null</b>，由调用方把该频道从节目列表中剔除：
     * 若回退到"排序后的首个线路"，那些已被测速判定为不可用的频道也会出现在
     * 本地节目列表里，用户点进去只会黑屏/转圈，体验比直接不显示更差。
     *
     * <p>例外：RTSP/RTMP 等协议无法测速（标记 UNTESTABLE），它们没有测速结果
     * 但线路本身可能可播，这类线路仍然保留。同理，若整个频道的所有线路都没有
     * 测速记录（例如被单轮测速的线路配额截断、从未测过），也保留首个线路，
     * 避免因"未测速"而误删实际可用的频道。
     */
    private String pickBestSource(LiveChannel channel) {
        List<String> urls = channel.getSourceUrls();
        if (urls == null || urls.isEmpty()) return null;

        String bestUrl = null;
        int bestScore = Integer.MIN_VALUE;
        double bestSpeed = -1;
        boolean anyTested = false;      // 是否有任意线路存在测速记录
        String untestableUrl = null;    // 无法测速但可能可播的线路（RTSP/RTMP）

        for (String url : urls) {
            SpeedTestResult r = speedTestEngine.getCachedResult(url);
            if (r == null) continue;    // 无测速记录
            if (!r.isAvailable()) {
                // 无法测速的协议不算"测速失败"，记下来作为兜底候选
                if (SpeedTestEngine.UNTESTABLE.equals(r.getFailureType())) {
                    if (untestableUrl == null) untestableUrl = url;
                } else {
                    anyTested = true;   // 明确测出不可用
                }
                continue;
            }
            anyTested = true;
            int score = r.getQualityScore();
            double speed = r.getSpeed();
            if (score > bestScore || (score == bestScore && speed > bestSpeed)) {
                bestScore = score;
                bestSpeed = speed;
                bestUrl = url;
            }
        }

        if (bestUrl != null) return bestUrl;
        // 无可用线路，但有无法测速的协议线路 → 保留它
        if (untestableUrl != null) return untestableUrl;
        // 所有线路都明确测速失败 → 返回 null，调用方剔除该频道
        if (anyTested) return null;
        // 完全没有测速记录（未测过/被配额截断）→ 保留首个线路，避免误删
        return urls.get(0);
    }

    /**
     * 持久化本地播放列表：写入 Hawk（含生成时间）并落盘到 filesDir。
     */
    private void saveLocalPlaylist(String content) {
        if (content == null) content = "";
        try {
            Hawk.put(HawkConfig.LIVE_LOCAL_PLAYLIST, content);
            Hawk.put(HawkConfig.LIVE_LOCAL_PLAYLIST_TIME, System.currentTimeMillis());
        } catch (Exception e) {
            Timber.w(e, "写入本地播放列表到 Hawk 失败");
        }
        try {
            File file = new File(context.getFilesDir(), HawkConfig.LOCAL_PLAYLIST_FILE_NAME);
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(content.getBytes("UTF-8"));
            }
            Timber.d("本地播放列表已落盘: %s", file.getAbsolutePath());
        } catch (Exception e) {
            Timber.w(e, "本地播放列表落盘失败");
        }
    }

    /** 获取已持久化的最佳线路本地播放列表（无则返回空串） */
    public String getLocalPlaylist() {
        try {
            return Hawk.get(HawkConfig.LIVE_LOCAL_PLAYLIST, "");
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 从已持久化的本地播放列表（Hawk 内容，或落盘文件）恢复频道分组。
     * 用于数据库为空但本地列表仍存在时的兜底复用，避免必须联网重新拉取。
     */
    private List<ChannelGroup> restoreFromLocalPlaylist() {
        String content = getLocalPlaylist();
        // Hawk 内容缺失时尝试读取落盘文件
        if (content == null || content.isEmpty()) {
            try {
                File file = new File(context.getFilesDir(), HawkConfig.LOCAL_PLAYLIST_FILE_NAME);
                if (file.exists()) {
                    byte[] buf = new byte[(int) Math.min(file.length(), 8 * 1024 * 1024)];
                    try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
                        int read = fis.read(buf);
                        if (read > 0) content = new String(buf, 0, read, "UTF-8");
                    }
                }
            } catch (Exception e) {
                Timber.w(e, "读取本地播放列表文件失败");
            }
        }
        if (content == null || content.trim().isEmpty()) return null;
        try {
            // 本地列表为 TXT 格式（分组,#genre# + 频道名,URL）
            List<ChannelGroup> restored = new LiveSourceParser().parseTxt(content);
            // 兜底清理历史本地列表中可能残留的不支持协议线路
            com.github.tvbox.osc.util.ProtocolFilter.filter(restored);
            return restored;
        } catch (Exception e) {
            Timber.w(e, "解析本地播放列表失败");
            return null;
        }
    }

    /**
     * 是否已存在测速生成的本地播放列表。
     * 作为"默认使用本地列表 / 否则提醒测速"的判定依据。
     */
    public boolean hasLocalPlaylist() {
        try {
            long time = Hawk.get(HawkConfig.LIVE_LOCAL_PLAYLIST_TIME, 0L);
            String content = Hawk.get(HawkConfig.LIVE_LOCAL_PLAYLIST, "");
            return time > 0 && content != null && !content.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /** 清除已持久化的本地播放列表（Hawk + 落盘文件），清除后下次打开将重新提醒测速 */
    public void clearLocalPlaylist() {
        try {
            Hawk.delete(HawkConfig.LIVE_LOCAL_PLAYLIST);
            Hawk.delete(HawkConfig.LIVE_LOCAL_PLAYLIST_TIME);
        } catch (Exception e) {
            Timber.w(e, "清除本地播放列表(Hawk)失败");
        }
        try {
            File file = new File(context.getFilesDir(), HawkConfig.LOCAL_PLAYLIST_FILE_NAME);
            if (file.exists()) file.delete();
        } catch (Exception e) {
            Timber.w(e, "删除本地播放列表文件失败");
        }
    }

    // ============ 私有方法 ============

    private void fetchAndUpdateChannels(ChannelCallback callback) {
        iptvApiService.fetchAndMergeSources(new IptvApiService.FetchCallback() {
            @Override
            public void onSuccess(List<ChannelGroup> groups, int totalChannels) {
                rawGroups = groups;
                rebuildDisplayGroups();
                saveToDatabase(groups);
                if (callback != null) callback.onChannelsLoaded(channelGroups);
            }

            @Override
            public void onError(String errorMsg) {
                // 网络失败时尝试使用本地缓存
                List<ChannelGroup> cached = loadFromDatabase();
                if (!cached.isEmpty()) {
                    rawGroups = cached;
                    rebuildDisplayGroups();
                    if (callback != null) callback.onChannelsLoaded(channelGroups);
                } else {
                    if (callback != null) callback.onError(errorMsg);
                }
            }

            @Override
            public void onProgress(int current, int total, String message) {}
        });
    }

    private List<ChannelGroup> loadFromDatabase() {
        try {
            List<LiveChannel> channels = database.channelDao().getAllChannels();
            if (channels == null || channels.isEmpty()) return new ArrayList<>();

            // 反序列化 JSON 字段
            for (LiveChannel channel : channels) {
                deserializeChannel(channel);
            }

            // 按分组重新组织
            Map<String, ChannelGroup> groupMap = new LinkedHashMap<>();
            for (LiveChannel channel : channels) {
                String groupName = channel.getGroupName() != null ? channel.getGroupName() : "未分类";
                ChannelGroup group = groupMap.get(groupName);
                if (group == null) {
                    group = new ChannelGroup(groupName);
                    groupMap.put(groupName, group);
                }
                group.addChannel(channel);
            }

            List<ChannelGroup> result = new ArrayList<>(groupMap.values());
            // 清理历史数据中残留的不支持协议线路（旧版本可能已把 rtp/udp 等写入数据库）
            com.github.tvbox.osc.util.ProtocolFilter.filter(result);
            return result;
        } catch (Exception e) {
            Timber.e(e, "从数据库加载失败");
            return new ArrayList<>();
        }
    }

    private void saveToDatabase(List<ChannelGroup> groups) {
        if (groups == null) return;
        // 先在当前线程做一份不可变快照，避免 ioExecutor 执行时源列表被并发修改导致
        // ConcurrentModificationException。
        final List<ChannelGroup> snapshot = new ArrayList<>(groups);
        ioExecutor.execute(() -> {
            try {
                // 用事务包裹 deleteAll + insert，保证原子性；中途异常时数据库回滚，
                // 不会出现“已清空但未写入”的半空状态。
                database.runInTransaction(() -> {
                    database.channelDao().deleteAll();
                    int groupIndex = 0;
                    for (ChannelGroup group : snapshot) {
                        if (group == null) continue;
                        List<LiveChannel> channels = new ArrayList<>(group.getChannels());
                        List<LiveChannel> valid = new ArrayList<>(channels.size());
                        int channelIndex = 0;
                        for (LiveChannel channel : channels) {
                            if (channel == null) continue;
                            // 将“分组顺序 + 分组内频道顺序”编码进 sortWeight，
                            // 使重启后从数据库读取时仍保持与订阅源/模板一致的展示顺序，
                            // 避免大类（节目分类）顺序因按名称排序而改变。
                            channel.setSortWeight(groupIndex * 100000 + channelIndex);
                            channelIndex++;
                            serializeChannel(channel);
                            valid.add(channel);
                        }
                        if (!valid.isEmpty()) {
                            database.channelDao().insertChannels(valid);
                            // 写库完成后立即释放 JSON 字段：内容已落库，
                            // 保留会让每个频道同时持有 List 与 JSON 两份文本，
                            // 低内存设备（如小米盒子）测速时容易因内存峰值过高而闪退。
                            for (LiveChannel channel : valid) {
                                channel.setSourceUrlsJson(null);
                                channel.setSpeedsJson(null);
                            }
                        }
                        groupIndex++;
                    }
                });
                Timber.d("频道数据已保存到数据库");
            } catch (Exception e) {
                Timber.e(e, "保存数据库失败");
            }
        });
    }

    private void serializeChannel(LiveChannel channel) {
        if (channel.getSourceUrls() != null) {
            channel.setSourceUrlsJson(gson.toJson(channel.getSourceUrls()));
        }
        if (channel.getSpeeds() != null) {
            channel.setSpeedsJson(gson.toJson(channel.getSpeeds()));
        }
    }

    private void deserializeChannel(LiveChannel channel) {
        try {
            if (channel.getSourceUrlsJson() != null && !channel.getSourceUrlsJson().isEmpty()) {
                List<String> urls = gson.fromJson(channel.getSourceUrlsJson(),
                        new TypeToken<List<String>>(){}.getType());
                channel.setSourceUrls(urls != null ? urls : new ArrayList<>());
            }
            if (channel.getSpeedsJson() != null && !channel.getSpeedsJson().isEmpty()) {
                List<Double> speeds = gson.fromJson(channel.getSpeedsJson(),
                        new TypeToken<List<Double>>(){}.getType());
                channel.setSpeeds(speeds != null ? speeds : new ArrayList<>());
            }
            // 反序列化完成后释放 JSON 字符串：其内容已存在于 sourceUrls/speeds 列表中，
            // 保留会让每个频道多占一份重复文本（频道多时可观）。写库前会重新生成。
            channel.setSourceUrlsJson(null);
            channel.setSpeedsJson(null);
        } catch (Exception e) {
            Timber.w(e, "反序列化频道数据失败: %s", channel.getChannelName());
        }
    }

    private void saveLastChannel(LiveChannel channel) {
        if (channel == null) return;
        try {
            if (channel.getChannelName() != null) {
                Hawk.put(HawkConfig.LIVE_LAST_CHANNEL, channel.getChannelName());
            }
            if (channel.getGroupName() != null) {
                Hawk.put(HawkConfig.LIVE_LAST_GROUP, channel.getGroupName());
            }
        } catch (Exception e) {
            Timber.w(e, "保存上次频道失败");
        }
        ioExecutor.execute(() -> {
            try {
                database.channelDao().updateLastPlayTime(channel.getId(), System.currentTimeMillis());
            } catch (Exception e) {
                Timber.w(e, "更新最后播放时间失败");
            }
        });
    }
}
