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
import java.util.concurrent.atomic.AtomicReference;

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
     * 记录"上次播放频道"的专用线程：换台发生在主线程，Hawk 写入含序列化与磁盘 IO；
     * 也不与 ioExecutor 上耗时的整表写库排队（否则测速后写库期间换台记录会被延迟很久）。
     */
    private final ExecutorService prefsExecutor;

    /** 待写入的"上次播放频道"；快速连续换台时只落盘最后停留的那个 */
    private final AtomicReference<LiveChannel> pendingLastChannel = new AtomicReference<>();

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

    /**
     * 列表刷新后在新列表中找不到的"正在播放频道"（被订阅删除/改名/移组）。
     *
     * <p>此时若仍沿用旧索引，getCurrentChannel 会指向同位置的另一个频道，
     * 换源/自动切源/EPG 都会作用到错误频道上（画面跳台）。因此暂时钉住正在播放的对象，
     * 直到用户下次主动换台（上下切台/选台/同步/恢复）或它在后续刷新中重新出现。
     */
    private volatile LiveChannel lostPlaying;

    /**
     * 测速后持久化任务重排/裁剪线路并重建展示列表"之后"在主线程回调。
     * SpeedTestCompleteEvent 在持久化任务入队后就已发出，界面在那时的重同步往往早于重建，
     * 看不到裁剪/隐藏后的列表；界面据此回调再同步一次。
     */
    private volatile Runnable speedTestPersistedListener;
    private final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());

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
        this.prefsExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ChannelManagerPrefs");
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
     * 优先从本地缓存加载：有缓存时立即展示（秒开），缓存过期再在后台静默刷新，
     * 刷新成功通过 onChannelsUpdated 通知；无缓存时才阻塞等待网络获取。
     */
    public void loadChannels(ChannelCallback callback) {
        ioExecutor.execute(() -> {
            try {
                // 先从本地数据库加载
                List<ChannelGroup> cachedGroups = loadFromDatabase();
                // 已存在测速生成的本地列表时，默认直接使用它（下次打开仍可用），
                // 不因自动更新周期而重新联网覆盖，保证优先使用最佳线路本地列表。
                boolean hasLocal = hasLocalPlaylist();
                if (!cachedGroups.isEmpty()) {
                    rawGroups = cachedGroups;
                    rebuildDisplayGroups();
                    Timber.d("从%s加载: %d 个分组",
                            hasLocal ? "本地测速列表" : "缓存", cachedGroups.size());
                    if (callback != null) callback.onChannelsLoaded(channelGroups);
                    // 缓存过期（且不是测速生成的本地列表）：界面先用缓存，后台再刷新。
                    // 原先直接阻塞联网，数十个源拉完前一直转圈，断网时还要等全部超时。
                    if (!hasLocal && iptvApiService.needsUpdate()) {
                        Timber.d("缓存已过期，后台刷新直播源...");
                        refreshInBackground(callback);
                    }
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
        refreshChannels(callback, false);
    }

    /**
     * @param background 是否为启动时的后台静默刷新（用户没有主动要求刷新）
     */
    private void refreshChannels(ChannelCallback callback, final boolean background) {
        iptvApiService.fetchAndMergeSources(new IptvApiService.FetchCallback() {
            @Override
            public void onSuccess(List<ChannelGroup> groups, int totalChannels) {
                // 后台静默刷新期间若测速正在进行：不替换 rawGroups。测速对象被换掉后，
                // 测速排序作用在被丢弃的旧对象上、持久化却裁剪新列表，会丢掉最佳线路；
                // 测速自身会联网拉取全量源，这份结果丢弃即可。
                if (background && speedTestEngine.isRunning()) {
                    Timber.w("测速进行中，丢弃本次后台刷新结果（%d 个频道）", totalChannels);
                    return;
                }
                // 网络受损（断网/之前可用的源多数因网络失败）且结果比现有列表更少：
                // 保留现有缓存，不让残缺结果覆盖完整列表与数据库（并复位更新时间，下次启动会重试）。
                // 用户主动刷新同样保护：网络受损时的残缺结果不覆盖完整缓存，并明确告知已保留旧列表。
                // 网络正常时 shouldKeepCurrentOnDegraded 恒为 false，上游缩减/重置线路仍照常替换。
                if (shouldKeepCurrentOnDegraded(groups, totalChannels)) {
                    if (callback != null) {
                        callback.onError(background
                                ? "网络不稳定，多数直播源获取失败，已保留原有频道列表"
                                : "网络不稳定，多数直播源获取失败，已保留原有频道列表，请稍后重试");
                    }
                    return;
                }
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

    /** 后台静默刷新：成功时回调 onChannelsUpdated；失败仅记录日志（界面已在展示缓存） */
    private void refreshInBackground(ChannelCallback callback) {
        refreshChannels(new ChannelCallback() {
            @Override
            public void onChannelsLoaded(List<ChannelGroup> groups) {}

            @Override
            public void onChannelsUpdated(List<ChannelGroup> groups, int newCount) {
                if (callback != null) callback.onChannelsUpdated(groups, newCount);
            }

            @Override
            public void onError(String errorMsg) {
                Timber.w("后台刷新直播源失败，继续使用缓存: %s", errorMsg);
            }
        }, true);
    }

    /**
     * 本次拉源"网络受损"且结果比现有列表更少（频道或线路）时返回 true，调用方应保留现有列表。
     * 现有列表为空时永远返回 false（没有可保留的东西）。
     */
    private boolean shouldKeepCurrentOnDegraded(List<ChannelGroup> groups, int totalChannels) {
        try {
            if (!iptvApiService.isLastFetchDegraded()) return false;
            int oldChannels = getTotalChannelCount();
            if (oldChannels <= 0) return false;
            int newLines = 0;
            if (groups != null) {
                for (ChannelGroup g : groups) {
                    if (g == null) continue;
                    for (LiveChannel ch : g.snapshotChannels()) {
                        if (ch != null) newLines += ch.getSourceCount();
                    }
                }
            }
            int oldLines = getTotalSourceCount();
            if (totalChannels < oldChannels || newLines < oldLines) {
                Timber.w("拉源网络受损，结果(%d 频道/%d 线路)少于现有列表(%d/%d)，保留现有列表",
                        totalChannels, newLines, oldChannels, oldLines);
                // 复位上次更新时间：残缺结果未被采用，下次启动应重新尝试，而不是再等一个更新周期
                try {
                    Hawk.put(HawkConfig.IPTV_LAST_UPDATE_TIME, 0L);
                } catch (Throwable ignore) {
                }
                return true;
            }
        } catch (Throwable t) {
            Timber.w(t, "比较拉源结果与现有列表失败");
        }
        return false;
    }

    /**
     * 把当前原始频道数据（含测速后的线路排序/过滤结果）写回数据库。
     * 供测速服务在测速结束后调用；写库在 IO 线程异步执行。
     */
    public void persistChannels() {
        List<ChannelGroup> groups = rawGroups;
        if (groups != null && !groups.isEmpty()) saveToDatabase(groups);
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
                    if (groups != null && !groups.isEmpty()
                            && !shouldKeepCurrentOnDegraded(groups, totalChannels)) {
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
                if (IptvApiService.ERROR_BUSY.equals(errorMsg)) {
                    // 已有拉源任务在跑（典型：启动时缓存过期触发的后台刷新）。此时内存中的列表
                    // 马上会被它替换，拿旧对象测速会让测速结果作用在被丢弃的对象上。
                    // 等它结束（结果已装入 rawGroups）后再交出待测列表。
                    Timber.w("测速前已有拉源任务在执行，等待其完成后再测速");
                    if (callback != null) {
                        try {
                            callback.onProgress(0, 0, "正在等待当前直播源获取完成...");
                        } catch (Throwable ignore) {
                        }
                    }
                    iptvApiService.runAfterCurrentFetch(() -> notifyReadySafely(callback));
                    return;
                }
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

    /**
     * 正在播放的频道是否处于"钉住"状态（刷新后在新列表中找不到）。
     * 此时 currentGroupIndex/currentChannelIndex 仍是旧位置、已被别的频道占据，
     * 界面高亮"正在播放"时应视为不在列表中（传 -1），否则会把无关频道标成正在播放。
     */
    public boolean isCurrentPinned() {
        return lostPlaying != null;
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
        LiveChannel lost = lostPlaying;
        if (lost != null) return lost;
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
        // 钉住期间旧位置已被"顶上来"的下一个频道占据：直接播放该位置的频道，
        // 再自增会把它跳过
        final boolean wasPinned = lostPlaying != null;
        lostPlaying = null;
        try {
            ChannelGroup group = getCurrentGroup();
            if (group == null) return null;
            List<LiveChannel> channels = group.getChannels();
            if (channels == null) return null;

            if (!wasPinned || currentChannelIndex < 0 || currentChannelIndex >= channels.size()) {
                currentChannelIndex++;
            }
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
        lostPlaying = null;
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
        lostPlaying = null;
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
        // 主动同步到某频道：若找到则以新位置为准；找不到时若正是被钉住的频道则保持钉住
        LiveChannel prevLost = lostPlaying;
        lostPlaying = null;
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
        if (prevLost == target) lostPlaying = prevLost;
        return false;
    }

    /** 恢复上次播放的频道 */
    public LiveChannel restoreLastChannel() {
        lostPlaying = null;
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
        lostPlaying = null;
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
            // 记住正在播放的频道：重建后分组/频道顺序可能变化（刷新源、测速隐藏不可用频道），
            // 仅保留旧索引会让"当前频道"指向别的频道，导致换源/上下切台/EPG 错位
            LiveChannel playing = null;
            String playingUrl = null;
            List<ChannelGroup> oldGroups = channelGroups;
            if (oldGroups != null && !oldGroups.isEmpty()) {
                playing = getCurrentChannel();
                // 先记下正在播放的线路：重建过程中 applySourcePrefs 等可能重排线路并复位 sourceIndex
                if (playing != null) playingUrl = playing.getCurrentSourceUrl();
            }

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
            if (playing != null) relocateCurrent(display, playing, playingUrl);
            if (hidden > 0) {
                Timber.i("展示列表已隐藏 %d 个无可用播放源的频道", hidden);
            }
        } catch (Throwable e) {
            // 低内存设备重建展示列表可能 OOM（Error），必须一并兜住避免闪退
            Timber.w(e, "rebuildDisplayGroups 异常");
        }
    }

    /**
     * 在新列表中重新定位正在播放的频道：先按频道 key（分组+名称）精确匹配，
     * 再按频道名跨分组匹配（与 {@link #syncCurrentTo} 一致，应对模板/去重移组）。
     *
     * <p>定位到新对象时，把"正在播放的线路"按 URL 对应到新对象的 sourceIndex，
     * 让后续自动切源从实际在播的线路继续，而不是从新对象的第 0 条重来。
     * 都找不到时钉住原播放对象（见 {@link #lostPlaying}），不让旧索引指向别的频道。
     */
    private void relocateCurrent(List<ChannelGroup> groups, LiveChannel playing, String playingUrl) {
        String key = playing.getKey();
        String name = playing.getChannelName();
        int foundG = -1, foundC = -1;
        LiveChannel found = null;
        for (int g = 0; g < groups.size() && found == null; g++) {
            ChannelGroup group = groups.get(g);
            if (group == null) continue;
            List<LiveChannel> channels = group.getChannels();
            if (channels == null) continue;
            for (int c = 0; c < channels.size(); c++) {
                LiveChannel ch = channels.get(c);
                if (ch != null && key.equals(ch.getKey())) {
                    found = ch;
                    foundG = g;
                    foundC = c;
                    break;
                }
            }
        }
        if (found == null && name != null) {
            for (int g = 0; g < groups.size() && found == null; g++) {
                ChannelGroup group = groups.get(g);
                if (group == null) continue;
                List<LiveChannel> channels = group.getChannels();
                if (channels == null) continue;
                for (int c = 0; c < channels.size(); c++) {
                    LiveChannel ch = channels.get(c);
                    if (ch != null && name.equals(ch.getChannelName())) {
                        found = ch;
                        foundG = g;
                        foundC = c;
                        break;
                    }
                }
            }
        }
        if (found == null) {
            lostPlaying = playing;
            Timber.w("刷新后的列表中找不到正在播放的频道 %s，保持当前播放对象", name);
            return;
        }
        currentGroupIndex = foundG;
        currentChannelIndex = foundC;
        lostPlaying = null;
        if (playingUrl != null) {
            List<String> urls = found.getSourceUrls();
            int idx = urls == null ? -1 : urls.indexOf(playingUrl);
            if (idx >= 0) found.setSourceIndex(idx);
        }
        if (found != playing && found.getCurrentEpgInfo() == null && playing.getCurrentEpgInfo() != null) {
            found.setCurrentEpgInfo(playing.getCurrentEpgInfo());
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
                // 裁剪前记下当前线路：重排时只有正在播放的频道（播放提示）索引非 0，
                // 一律复位 0 会让它的索引指向播放器并未在播的线路（稳定线路记录/自动换源错位）
                String cur = ch.getCurrentSourceUrl();
                List<String> kept = new ArrayList<>(urls.subList(0, urlsLimit));
                ch.setSourceUrls(kept);
                List<Double> speeds = ch.getSpeeds();
                if (speeds != null && speeds.size() > urlsLimit) {
                    ch.setSpeeds(new ArrayList<>(speeds.subList(0, urlsLimit)));
                }
                int keepIdx = cur == null ? -1 : kept.indexOf(cur);
                ch.setSourceIndex(keepIdx >= 0 ? keepIdx : 0);
                trimmed++;
            }
        }
        if (trimmed > 0) {
            Timber.d("测速后按上限 %d 裁剪了 %d 个频道的线路", urlsLimit, trimmed);
        }
    }

    /**
     * 按测速缓存结果重排 rawGroups 中每个频道的线路：
     * 测速可用的在前（按综合评分或速度，与测速排序模式一致），未测过的居中，测速失败的最后。
     * 稳定排序，同档保持原顺序。用户关闭"测速后排序线路"时不做处理（与测速引擎一致）。
     */
    private synchronized void reorderRawByCachedResults() {
        if (!Hawk.get(HawkConfig.SPEED_SORT_SOURCES, true)) return;
        List<ChannelGroup> groups = rawGroups;
        if (groups == null) return;
        final boolean byScore = Hawk.get(HawkConfig.SPEED_SORT_MODE, HawkConfig.SORT_MODE_QUALITY_SCORE)
                == HawkConfig.SORT_MODE_QUALITY_SCORE;
        for (ChannelGroup group : new ArrayList<>(groups)) {
            if (group == null) continue;
            for (LiveChannel ch : group.snapshotChannels()) {
                if (ch == null) continue;
                List<String> urls = ch.getSourceUrls();
                if (urls == null || urls.size() < 2) continue;
                final int n = urls.size();
                final int[] tier = new int[n];      // 0 可用 / 1 未测(含无法测速) / 2 测速失败
                final double[] key = new double[n]; // 可用线路的排序值（越大越靠前）
                for (int i = 0; i < n; i++) {
                    SpeedTestResult r = speedTestEngine.getCachedResult(urls.get(i));
                    if (r == null || SpeedTestEngine.UNTESTABLE.equals(r.getFailureType())) {
                        tier[i] = 1;
                    } else if (r.isAvailable()) {
                        tier[i] = 0;
                        double v = byScore ? r.getQualityScore() : r.getSpeed();
                        key[i] = Double.isNaN(v) ? 0d : v;
                    } else {
                        tier[i] = 2;
                    }
                }
                List<Integer> order = new ArrayList<>(n);
                for (int i = 0; i < n; i++) order.add(i);
                java.util.Collections.sort(order, (a, b) -> {
                    if (tier[a] != tier[b]) return Integer.compare(tier[a], tier[b]);
                    if (tier[a] == 0) return Double.compare(key[b], key[a]);
                    return 0;
                });
                // 把排序结果转成名次分数交给 LiveChannel（稳定排序，speeds 同步跟随）
                List<Integer> scores = new ArrayList<>(java.util.Collections.nCopies(n, 0));
                for (int rank = 0; rank < n; rank++) scores.set(order.get(rank), n - rank);
                ch.sortSourcesByScores(scores);
            }
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
                // 裁剪前按测速缓存（以 URL 为键）重排当前 rawGroups 的线路：若测速期间
                // rawGroups 被源刷新整体替换，测速引擎排序的是已被丢弃的旧对象，
                // 新对象仍是拉取顺序，直接截断会丢掉测出的最佳线路。对未被替换的情况幂等。
                reorderRawByCachedResults();
                // 测速已覆盖全部线路，此处按“单频道最多线路”只保留最佳的若干条
                // （可用线路已按质量排在前面），控制长期内存与数据库体积
                applyUrlsLimitToRaw();
                // 重建展示列表以反映测速后的线路排序与过滤
                rebuildDisplayGroups();
                // 重建完成后通知界面重同步（列表与播放线路索引），见 speedTestPersistedListener
                notifySpeedTestPersisted();
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

    /** 设置测速后持久化（重建展示列表）完成的主线程回调；传 null 清除 */
    public void setOnSpeedTestPersistedListener(Runnable listener) {
        speedTestPersistedListener = listener;
    }

    /** 仅当当前回调仍是 listener 时清除（避免新界面注册后被旧界面 onDestroy 清掉） */
    public void clearOnSpeedTestPersistedListener(Runnable listener) {
        if (speedTestPersistedListener == listener) speedTestPersistedListener = null;
    }

    private void notifySpeedTestPersisted() {
        final Runnable l = speedTestPersistedListener;
        if (l == null) return;
        mainHandler.post(() -> {
            // 投递期间界面可能已销毁并注销
            if (speedTestPersistedListener != l) return;
            try {
                l.run();
            } catch (Throwable t) {
                Timber.w(t, "测速后持久化完成回调异常");
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

    /**
     * 记录上次播放频道。
     *
     * <p>换台在主线程触发，而 Hawk 写入含序列化与磁盘 IO，因此放到专用后台线程；
     * 连续快速换台时合并为一次，只写最终停留的频道。
     *
     * <p>数据库按"频道名 + 分组"更新播放时间：网络拉取的频道对象 id 恒为 0
     * （写库后 Room 不会回填自增 id），原先按 id 更新永远命中不了任何行。
     */
    private void saveLastChannel(LiveChannel channel) {
        if (channel == null) return;
        // 内存中同步记录，后续整表写库时不会把播放时间覆盖回 0
        channel.setLastPlayTime(System.currentTimeMillis());
        // 已有待执行任务时只替换目标频道，无需再提交
        if (pendingLastChannel.getAndSet(channel) != null) return;
        prefsExecutor.execute(() -> {
            LiveChannel latest = pendingLastChannel.getAndSet(null);
            if (latest == null) return;
            String name = latest.getChannelName();
            String group = latest.getGroupName();
            try {
                if (name != null) {
                    Hawk.put(HawkConfig.LIVE_LAST_CHANNEL, name);
                }
                if (group != null) {
                    Hawk.put(HawkConfig.LIVE_LAST_GROUP, group);
                }
            } catch (Exception e) {
                Timber.w(e, "保存上次频道失败");
            }
            if (name == null || group == null) return;
            try {
                database.channelDao().updateLastPlayTimeByName(name, group, latest.getLastPlayTime());
            } catch (Exception e) {
                Timber.w(e, "更新最后播放时间失败");
            }
        });
    }
}
