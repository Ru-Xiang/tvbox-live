package com.github.tvbox.osc.api;

import android.content.Context;

import com.github.tvbox.osc.bean.ChannelGroup;
import com.github.tvbox.osc.bean.LiveChannel;
import com.github.tvbox.osc.bean.SpeedTestResult;
import com.github.tvbox.osc.cache.LiveChannelDatabase;
import com.github.tvbox.osc.service.SpeedTestEngine;
import com.github.tvbox.osc.util.ChannelNameMatcher;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.LineQuality;
import com.github.tvbox.osc.util.StreamUrl;
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

    /**
     * 刷新源替换 rawGroups 与测速后持久化（重排/裁剪/写库/生成本地列表）互斥。
     * 刷新回调在拉源线程、持久化在 ioExecutor，两者交错会让刷新结果覆盖已测速排序的列表，
     * 或让持久化裁剪到一半的列表被替换。不用 this：主线程换台方法同步在 this 上，避免被长时间阻塞。
     */
    private final Object rawLock = new Object();

    /** 最近一次由"刷新源"整体替换 rawGroups 的时间（用于识别测速进行中列表被替换） */
    private volatile long rawReplacedAt = 0L;

    /**
     * 因"网络不可用"而未被采用的测速轮次的开始时间（Hawk）。
     * 该轮及之后（直到下一轮开始）写入缓存的失败结果一律视为"未测"，不据此隐藏频道/剔除线路。
     */
    private static final String KEY_NET_ABORTED_ROUND_AT = "live_speed_net_aborted_round_at";

    /**
     * 最近一轮"被采用"（非断网）的测速开始时间（Hawk）。测速引擎在断网轮次仍会把
     * lastRoundStartedAt 记为该轮，若只认引擎的值，上一轮正常测速的结果全被当作旧结果（未测），
     * 之后刷新按拉取顺序重排，已知失效的线路又排回首位。断网轮次期间改以此值作为轮次起点。
     */
    private static final String KEY_LAST_HEALTHY_ROUND_AT = "live_speed_last_healthy_round_at";

    /** 本地列表内容只落盘到文件时，Hawk 中存放的小标记（避免大段 JSON 进 SharedPreferences） */
    private static final String LOCAL_PLAYLIST_FILE_MARKER = "@file:tvbox-live-local-v2";

    /** 不限制单频道线路数时，本地列表每个频道最多写入的线路数（含最佳线路），控制文件体积 */
    private static final int LOCAL_PLAYLIST_MAX_URLS_UNLIMITED = 20;

    /** 读取本地列表文件的大小上限，超出视为损坏（低内存设备上避免一次性读入超大文件 OOM） */
    private static final long LOCAL_PLAYLIST_MAX_FILE_BYTES = 16L * 1024 * 1024;

    /** 生成本地列表时的直播源配置指纹（Hawk），配置变化后本地列表不再阻止刷新 */
    private static final String KEY_LOCAL_PLAYLIST_SRC_FP = "live_local_playlist_src_fp";

    /** 本地列表 v2（JSON）格式标记；旧版为 TXT（分组,#genre# + 名称,URL），读取时兼容 */
    private static final String LOCAL_PLAYLIST_JSON_FORMAT = "tvbox-live-local-v2";

    /** 线路测速状态：可用 / 未测（含无记录、过期、断网轮次的失败） / 无法测速（RTSP 等） / 本轮明确失败 */
    private static final int ST_OK = 0;
    private static final int ST_UNTESTED = 1;
    private static final int ST_UNTESTABLE = 2;
    private static final int ST_FAILED = 3;

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
                // 到更新周期或源配置变更时仍在后台刷新，刷新结果按测速缓存重排后合并（不会丢掉最佳线路的优先级）。
                boolean hasLocal = hasLocalPlaylist();
                if (!cachedGroups.isEmpty()) {
                    rawGroups = cachedGroups;
                    rebuildDisplayGroups();
                    Timber.d("从%s加载: %d 个分组",
                            hasLocal ? "本地测速列表" : "缓存", cachedGroups.size());
                    if (callback != null) callback.onChannelsLoaded(channelGroups);
                    // 缓存过期：界面先用缓存，后台再刷新。
                    // 原先直接阻塞联网，数十个源拉完前一直转圈，断网时还要等全部超时。
                    // 有本地测速列表时同样按更新周期刷新、且源配置改动后立即刷新：否则过期 token/换域名
                    // 的线路与新加的源永远进不来。刷新结果会按测速缓存重排（见 applyRefreshedGroups），
                    // 测速可用的线路仍排在前面，保持"优先使用最佳线路"。
                    if (shouldRefreshOnLaunch()) {
                        Timber.d("缓存已过期或直播源配置已变更，后台刷新直播源...");
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
                        if (shouldRefreshOnLaunch()) refreshInBackground(callback);
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
        // 拉取前记下配置指纹：拉取途中配置又被修改时，记录的是本次拉取实际用的配置，下次启动仍会刷新
        final String fp = sourceConfigFingerprint();
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
                // 替换、按测速缓存重排、重建展示列表并写库（与测速后持久化互斥）
                applyRefreshedGroups(groups, fp);
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
     * 用刷新得到的新分组替换 rawGroups：先按测速缓存（以 URL 为键）重排线路再展示、写库。
     *
     * <p>原先直接按拉取顺序替换并写库：测速判定失效的线路排回第 0 条，且本地列表存在时
     * 启动不再刷新，这份未排序列表会一直沿用。这里不裁剪：刷新可能带来尚未测速的新线路，
     * 裁掉会丢失候选；可用线路靠前、未测居中、失败垫底即可保证优先播放测速最佳线路。
     * 与测速后持久化互斥，避免两者交错覆盖彼此的结果。
     */
    private void applyRefreshedGroups(List<ChannelGroup> groups, String sourceFp) {
        synchronized (rawLock) {
            rawGroups = groups;
            rawReplacedAt = System.currentTimeMillis();
            try {
                reorderRawByCachedResults();
            } catch (Throwable t) {
                Timber.w(t, "刷新后按测速缓存重排线路失败");
            }
            rebuildDisplayGroups();
            // 异步保存到数据库
            saveToDatabase(groups);
            markSourceConfigApplied(sourceFp);
        }
    }

    /**
     * 启动时是否需要后台刷新：到了更新周期，或直播源配置（自定义源/订阅/多仓/模板/IP 版本）
     * 自上次成功刷新或测速后已改变。有本地测速列表时也照此执行（刷新结果会按测速缓存重排）。
     */
    private boolean shouldRefreshOnLaunch() {
        try {
            if (iptvApiService.needsUpdate()) return true;
        } catch (Throwable ignore) {
        }
        return isSourceConfigChanged();
    }

    /** 直播源相关配置的指纹；任何一项改变都会让已缓存的列表（含本地测速列表）失去代表性 */
    private static String sourceConfigFingerprint() {
        String[] keys = {
                HawkConfig.IPTV_CUSTOM_SOURCES,
                HawkConfig.IPTV_SUBSCRIBE_LIST_URL,
                HawkConfig.IPTV_MULTI_REPO_URLS,
                HawkConfig.IPTV_IP_VERSION,
                HawkConfig.IPTV_TEMPLATE_ENABLED,
                HawkConfig.IPTV_TEMPLATE_URL,
                HawkConfig.IPTV_ALIAS_URL,
        };
        StringBuilder sb = new StringBuilder();
        for (String k : keys) {
            Object v = null;
            try {
                // 不带默认值读取：各键类型不同（String/Boolean），带错类型的默认值会 ClassCastException
                v = Hawk.get(k);
            } catch (Throwable ignore) {
            }
            sb.append(k).append('=').append(v == null ? "" : String.valueOf(v)).append('\n');
        }
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(sb.toString().getBytes("UTF-8"));
            StringBuilder hex = new StringBuilder(d.length * 2);
            for (byte b : d) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
            return hex.toString();
        } catch (Throwable t) {
            return Integer.toHexString(sb.toString().hashCode()) + ":" + sb.length();
        }
    }

    /** 当前直播源配置与上次成功刷新/测速时是否不同（从未记录过也视为不同，刷新一次后即记录） */
    private boolean isSourceConfigChanged() {
        try {
            String saved = Hawk.get(KEY_LOCAL_PLAYLIST_SRC_FP, "");
            return saved == null || !saved.equals(sourceConfigFingerprint());
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 记录某份直播源配置已被采用。只在"按该配置拉取的结果真正替换了列表"时调用，
     * fp 取自拉取开始前：测速前拉源失败回退旧列表、或拉取途中配置被改时，都不应记为已采用。
     */
    private void markSourceConfigApplied(String fp) {
        if (fp == null) return;
        try {
            Hawk.put(KEY_LOCAL_PLAYLIST_SRC_FP, fp);
        } catch (Throwable ignore) {
        }
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
        final String fp = sourceConfigFingerprint();
        iptvApiService.fetchAndMergeSources(new IptvApiService.FetchCallback() {
            @Override
            public void onSuccess(List<ChannelGroup> groups, int totalChannels) {
                try {
                    if (groups != null && !groups.isEmpty()
                            && !shouldKeepCurrentOnDegraded(groups, totalChannels)) {
                        // 不按测速缓存重排：测速按每频道配额只测前几条，重排会让上轮失败的线路永远测不到
                        synchronized (rawLock) {
                            rawGroups = groups;
                            rawReplacedAt = System.currentTimeMillis();
                            rebuildDisplayGroups();
                            saveToDatabase(groups);
                            markSourceConfigApplied(fp);
                        }
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

    /**
     * 为单个频道"发现更多线路"：联网拉取全量直播源（与普通刷新同样的合并/IP 版本/模板过滤），
     * 但<b>不替换</b>当前列表，只把拉取结果中同一频道的线路追加到列表里该频道的对象上。
     *
     * <p>匹配规则：先按频道 key（分组+名称）精确匹配，再按归一化频道名（{@link ChannelNameMatcher#isSameChannel}）
     * 跨分组匹配；按出现顺序收集线路，跳过已有的（原始串相同或 {@link com.github.tvbox.osc.util.ChannelDeduper#lineKey}
     * 相同；不带请求头而列表里已有同地址线路的也跳过）以及用户在该频道拉黑的线路。
     * 有新增时整表写库（测速后 persistAfterSpeedTest 还会再写一次）。
     *
     * <p>无可用线路的频道虽不在展示列表中，但仍在 rawGroups 里，因此能找到并追加；
     * 测速后 persistAfterSpeedTest 会重建展示列表，测出可用线路的频道重新出现。
     *
     * <p>最后回调 onReady：只含列表中该频道对象的列表；找不到该频道时为空列表。
     * 拉源失败时不追加，直接交出现有线路测速；已有拉源任务在跑时等它结束后重试一次。
     */
    public void discoverLinesForChannel(LiveChannel target, SpeedTestReloadCallback callback) {
        discoverLinesForChannel(target, callback, true);
    }

    private void discoverLinesForChannel(final LiveChannel target, final SpeedTestReloadCallback callback,
                                         final boolean retryOnBusy) {
        if (target == null) {
            notifySingleReady(callback, null);
            return;
        }
        final String name = target.getChannelName() == null ? "" : target.getChannelName();
        notifyProgressSafely(callback, "正在为「" + name + "」查找更多线路...");
        // 拉源成功会刷新"上次更新时间"，但这次结果并未替换列表：之后照常恢复原值，
        // 否则启动时到期的整表刷新会被这次单频道查找推迟一个周期
        long prev = 0L;
        try {
            Long v = Hawk.get(HawkConfig.IPTV_LAST_UPDATE_TIME, 0L);
            prev = v == null ? 0L : v;
        } catch (Throwable ignore) {
        }
        final long prevUpdateTime = prev;
        try {
            iptvApiService.fetchAndMergeSources(new IptvApiService.FetchCallback() {
                @Override
                public void onSuccess(List<ChannelGroup> groups, int totalChannels) {
                    try {
                        Hawk.put(HawkConfig.IPTV_LAST_UPDATE_TIME, prevUpdateTime);
                    } catch (Throwable ignore) {
                    }
                    LiveChannel resolved = null;
                    int added = 0;
                    try {
                        List<String> candidates = collectLinesFor(groups, target);
                        synchronized (rawLock) {
                            resolved = resolveRawChannel(target);
                            if (resolved != null && !candidates.isEmpty()) {
                                added = appendDiscoveredLines(resolved, candidates);
                            }
                            if (added > 0) saveToDatabase(rawGroups);
                        }
                    } catch (Throwable t) {
                        Timber.w(t, "为频道 %s 查找更多线路失败", name);
                        if (resolved == null) resolved = resolveRawChannelLocked(target);
                    }
                    if (resolved == null) {
                        Timber.w("列表中找不到频道 %s，无法测速", name);
                    } else if (added > 0) {
                        Timber.i("频道 %s 新增 %d 条线路", name, added);
                        notifyProgressSafely(callback, "找到 " + added + " 条新线路，开始测速...");
                    } else {
                        notifyProgressSafely(callback, "没有找到新线路，测速现有线路...");
                    }
                    notifySingleReady(callback, resolved);
                }

                @Override
                public void onError(String errorMsg) {
                    if (IptvApiService.ERROR_BUSY.equals(errorMsg) && retryOnBusy) {
                        // 已有拉源任务在跑（典型：启动时的后台刷新）：它结束时会整体替换列表，
                        // 等它结束后再查找一次，频道按 key/名称对应到新列表中的对象
                        Timber.w("查找更多线路时已有拉源任务在执行，等待其完成后重试");
                        notifyProgressSafely(callback, "正在等待当前直播源获取完成...");
                        iptvApiService.runAfterCurrentFetch(
                                () -> discoverLinesForChannel(target, callback, false));
                        return;
                    }
                    Timber.w("为频道 %s 查找更多线路时拉源失败，测速现有线路: %s", name, errorMsg);
                    notifyProgressSafely(callback, "获取直播源失败，测速现有线路...");
                    notifySingleReady(callback, resolveRawChannelLocked(target));
                }

                @Override
                public void onProgress(int current, int total, String message) {
                    if (callback != null) {
                        try {
                            callback.onProgress(current, total, message);
                        } catch (Throwable ignore) {
                        }
                    }
                }
            });
        } catch (Throwable t) {
            Timber.w(t, "启动查找更多线路失败");
            notifySingleReady(callback, resolveRawChannelLocked(target));
        }
    }

    /** 在拉取结果中收集与 target 同一频道的线路（key 精确匹配的频道在前，其次同名频道），按 lineKey 去重 */
    private static List<String> collectLinesFor(List<ChannelGroup> groups, LiveChannel target) {
        List<String> out = new ArrayList<>();
        if (groups == null || target == null) return out;
        String key = target.getKey();
        String name = target.getChannelName();
        java.util.Set<LiveChannel> used =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<LiveChannel, Boolean>());
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (int pass = 0; pass < 2; pass++) {
            for (ChannelGroup g : new ArrayList<>(groups)) {
                if (g == null) continue;
                for (LiveChannel ch : g.snapshotChannels()) {
                    if (ch == null || used.contains(ch)) continue;
                    boolean match = pass == 0
                            ? key.equals(ch.getKey())
                            : name != null && ChannelNameMatcher.isSameChannel(name, ch.getChannelName());
                    if (!match) continue;
                    used.add(ch);
                    List<String> urls = ch.getSourceUrls();
                    if (urls == null) continue;
                    for (String u : new ArrayList<>(urls)) {
                        if (u == null || u.isEmpty()) continue;
                        if (seen.add(com.github.tvbox.osc.util.ChannelDeduper.lineKey(u))) out.add(u);
                    }
                }
            }
        }
        return out;
    }

    /**
     * 把新发现的线路追加到频道末尾（调用方持有 rawLock）。写时复制：生成新列表整体替换，
     * speeds 同步补 0，当前线路索引不变（只在末尾追加）。
     *
     * @return 新增条数
     */
    private int appendDiscoveredLines(LiveChannel ch, List<String> candidates) {
        synchronized (ch) {
            List<String> old = ch.getSourceUrls();
            if (old == null) old = new ArrayList<>();
            List<Double> oldSpeeds = ch.getSpeeds();
            List<String> urls = new ArrayList<>(old);
            List<Double> speeds = new ArrayList<>(old.size() + 8);
            for (int i = 0; i < old.size(); i++) {
                Double v = oldSpeeds != null && i < oldSpeeds.size() ? oldSpeeds.get(i) : null;
                speeds.add(v == null ? 0d : v);
            }
            java.util.HashSet<String> exact = new java.util.HashSet<>(old);
            java.util.HashSet<String> keys = new java.util.HashSet<>();
            java.util.HashSet<String> plays = new java.util.HashSet<>();
            for (String u : old) {
                if (u == null) continue;
                keys.add(com.github.tvbox.osc.util.ChannelDeduper.lineKey(u));
                plays.add(StreamUrl.playUrl(u));
            }
            int added = 0;
            for (String c : candidates) {
                if (urls.size() >= LiveChannel.MAX_SOURCE_URLS) break;
                if (c == null || c.isEmpty() || exact.contains(c)) continue;
                String k = com.github.tvbox.osc.util.ChannelDeduper.lineKey(c);
                if (keys.contains(k)) continue;
                String play = StreamUrl.playUrl(c);
                // 不带请求头、而列表里已有同地址线路：是那条的子集（与 addSourceUrl 同一口径）
                if (StreamUrl.headers(c).isEmpty() && plays.contains(play)) continue;
                if (isSourceBlacklisted(ch, c)) continue;
                urls.add(c);
                speeds.add(0d);
                exact.add(c);
                keys.add(k);
                plays.add(play);
                added++;
            }
            if (added > 0) {
                int idx = ch.getSourceIndex();
                ch.setSourceUrls(urls);
                ch.setSpeeds(speeds);
                ch.setSourceIndex(idx >= 0 && idx < urls.size() ? idx : 0);
            }
            return added;
        }
    }

    /** 加锁版 {@link #resolveRawChannel} */
    private LiveChannel resolveRawChannelLocked(LiveChannel target) {
        try {
            synchronized (rawLock) {
                return resolveRawChannel(target);
            }
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 把频道对应到 rawGroups 中的对象：先按对象身份，再按 key（分组+名称），最后按频道名。
     * 找不到返回 null（如刷新后已被订阅删除）。
     */
    private LiveChannel resolveRawChannel(LiveChannel target) {
        if (target == null) return null;
        List<ChannelGroup> groups = rawGroups;
        if (groups == null) return null;
        String key = target.getKey();
        String name = target.getChannelName();
        LiveChannel byKey = null, byName = null;
        for (ChannelGroup g : new ArrayList<>(groups)) {
            if (g == null) continue;
            for (LiveChannel ch : g.snapshotChannels()) {
                if (ch == null) continue;
                if (ch == target) return ch;
                if (byKey == null && key.equals(ch.getKey())) byKey = ch;
                if (byName == null && name != null && name.equals(ch.getChannelName())) byName = ch;
            }
        }
        return byKey != null ? byKey : byName;
    }

    /**
     * 按频道 key（{@link LiveChannel#getKey}）在原始列表（含展示列表中已隐藏的无可用线路频道）中查找频道对象。
     * 找不到返回 null。
     */
    public LiveChannel findRawChannelByKey(String key) {
        if (key == null) return null;
        List<ChannelGroup> groups = rawGroups;
        if (groups == null) return null;
        try {
            for (ChannelGroup g : new ArrayList<>(groups)) {
                if (g == null) continue;
                for (LiveChannel ch : g.snapshotChannels()) {
                    if (ch != null && key.equals(ch.getKey())) return ch;
                }
            }
        } catch (Throwable t) {
            Timber.w(t, "按 key 查找频道失败");
        }
        return null;
    }

    private static void notifyProgressSafely(SpeedTestReloadCallback callback, String message) {
        if (callback == null) return;
        try {
            callback.onProgress(0, 0, message);
        } catch (Throwable ignore) {
        }
    }

    /** 交出单频道待测列表（channel 为 null 时为空列表），回调异常不外泄到拉源线程 */
    private static void notifySingleReady(SpeedTestReloadCallback callback, LiveChannel channel) {
        if (callback == null) return;
        ArrayList<LiveChannel> list = new ArrayList<>(1);
        if (channel != null) list.add(channel);
        try {
            callback.onReady(list);
        } catch (Throwable t) {
            Timber.w(t, "单频道测速准备回调执行失败");
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
    /**
     * 只读地取当前频道上/下相邻的频道（不改当前位置），用于起播后预热相邻频道的线路。
     * 只在当前分组内取；到分组边界返回 null（跨分组的情况少，不值得多预热）。
     *
     * @param delta +1=下一个，-1=上一个
     */
    public synchronized LiveChannel peekAdjacent(int delta) {
        try {
            if (lostPlaying != null) return null;
            ChannelGroup group = getCurrentGroup();
            if (group == null) return null;
            List<LiveChannel> channels = group.getChannels();
            if (channels == null) return null;
            int idx = currentChannelIndex + delta;
            if (idx < 0 || idx >= channels.size()) return null;
            return channels.get(idx);
        } catch (Exception e) {
            return null;
        }
    }

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
            // 再按频道 key（分组+名称）匹配：列表被刷新替换后旧对象应落回同一分组的新对象，
            // 不能直接按名称跨分组匹配到别的分组里的同名频道（与 relocateCurrent 的顺序一致）
            String key = target.getKey();
            if (key != null) {
                for (int g = 0; g < groups.size(); g++) {
                    ChannelGroup group = groups.get(g);
                    if (group == null || group.getChannels() == null) continue;
                    List<LiveChannel> chs = group.getChannels();
                    for (int c = 0; c < chs.size(); c++) {
                        LiveChannel ch = chs.get(c);
                        if (ch != null && key.equals(ch.getKey())) {
                            currentGroupIndex = g;
                            currentChannelIndex = c;
                            saveLastChannel(ch);
                            return true;
                        }
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
        // 同步更新自定义顺序：拉黑的正是置顶线路时整条顺序作废。置顶线路按顺序首条推断，
        // 只移除该 url 会让原第二条"继承"置顶保护（不被剔除/裁剪、失败也保留频道），并非用户本意
        List<String> order = sourceOrder.get(key);
        // 顺序表可能是 1.2.4 保存的旧写法（无 '|请求头'），按 sameStoredLine 兼容匹配
        if (order != null && !order.isEmpty() && sameStoredLine(order.get(0), url)) {
            sourceOrder.remove(key);
            persistSourceMap(HawkConfig.LIVE_SOURCE_ORDER, sourceOrder);
        } else if (order != null) {
            boolean changed = false;
            for (java.util.Iterator<String> it = order.iterator(); it.hasNext(); ) {
                if (sameStoredLine(it.next(), url)) {
                    it.remove();
                    changed = true;
                }
            }
            if (changed) persistSourceMap(HawkConfig.LIVE_SOURCE_ORDER, sourceOrder);
        }
        return removed;
    }

    /** 判断某频道的某条线路是否被拉黑 */
    public boolean isSourceBlacklisted(LiveChannel channel, String url) {
        if (channel == null || url == null) return false;
        List<String> blocked = sourceBlacklist.get(channel.getKey());
        if (blocked == null || blocked.isEmpty()) return false;
        if (blocked.contains(url)) return true;
        for (String b : blocked) {
            if (sameStoredLine(b, url)) return true;
        }
        return false;
    }

    /**
     * 保存的线路串（黑名单 / 置顶顺序）与频道里的实际线路是否为同一条。
     *
     * <p>先精确比较；不相等时，若保存的串不带请求头，再按 {@link StreamUrl#playUrl} 比较。
     * 为什么：1.2.4 保存的是 'url' 或 'url$标签'，1.2.5 起带 UA/Referer 的线路原样存为
     * 'url|User-Agent=..'，且同地址不同标签已合并为一条，精确比较会让升级前的置顶/拉黑静默失效。
     * 为什么只在保存的串不带请求头时回退：带请求头的串一定是 1.2.5 保存的，精确匹配就够；
     * 同地址不同请求头的线路被刻意保留为两条（见 ChannelDeduper#lineKey），不能互相"连坐"。
     */
    static boolean sameStoredLine(String stored, String line) {
        if (stored == null || line == null) return false;
        if (stored.equals(line)) return true;
        // 带请求头的保存串：按"地址 + 请求头"比较（只改了 $标签 仍算同一线路；请求头不同则仍区分开）
        if (!StreamUrl.headers(stored).isEmpty()) {
            return com.github.tvbox.osc.util.ChannelDeduper.lineKey(stored)
                    .equals(com.github.tvbox.osc.util.ChannelDeduper.lineKey(line));
        }
        String p = StreamUrl.playUrl(stored);
        return p != null && !p.isEmpty() && p.equals(StreamUrl.playUrl(line));
    }

    /** 把保存的线路串解析为 urls 中的实际线路（规则同 sameStoredLine，精确匹配优先）；找不到返回 null */
    private static String resolveStoredLine(List<String> urls, String stored) {
        if (urls == null || stored == null) return null;
        if (urls.contains(stored)) return stored;
        for (String u : urls) {
            if (u != null && sameStoredLine(stored, u)) return u;
        }
        return null;
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
                if (channel.removeSource(url)) continue;
                // 升级兼容：1.2.4 拉黑的旧写法匹配现在带 '|请求头' 的同一路流（见 sameStoredLine）
                String actual;
                int guard = 0;
                while (guard++ < LiveChannel.MAX_SOURCE_URLS
                        && (actual = resolveStoredLine(channel.getSourceUrls(), url)) != null) {
                    if (!channel.removeSource(actual)) break;
                }
            }
        }
        List<String> order = sourceOrder.get(key);
        if (order != null && !order.isEmpty()) {
            // 升级兼容：顺序表里的旧写法先换成列表中的实际线路，否则重排时找不到而失效
            List<String> urls = channel.getSourceUrls();
            List<String> resolved = new ArrayList<>(order.size());
            for (String o : order) {
                String actual = resolveStoredLine(urls, o);
                resolved.add(actual != null ? actual : o);
            }
            channel.reorderSourcesBy(resolved);
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
            // 每次重建只读一次（Hawk 读取有反序列化开销，不能按频道重复读）
            final long roundStart = currentRoundStart();
            final long abortedAt = netAbortedRoundAt();
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
                        if (!hasPlayableSource(channel, roundStart, abortedAt)) {
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
     * - 有任意线路未测（无记录 / 被配额截断 / 早于最近一轮的旧结果 / 断网轮次的失败） → 视为可播：
     *   原先只要测过的线路都失败就隐藏，配额外从未测过、可能可用的线路被一并丢掉；
     * - 用户置顶的线路 → 视为可播（用户确认能播，测速可能因缺 UA/Referer 误判）；
     * - 黑屏线路不算失败：只剩黑屏线路的频道仍然显示（选线时排在最后兜底）；
     * - 只有全部线路都在最近一轮明确测速失败 → 不可播。
     */
    private boolean hasPlayableSource(LiveChannel channel, long roundStart, long abortedAt) {
        if (channel == null) return false;
        List<String> urls = channel.getSourceUrls();
        if (urls == null || urls.isEmpty()) return false;
        String pinned = pinnedUrl(channel);
        for (String url : urls) {
            if (url != null && url.equals(pinned)) return true;
            if (lineStatus(url, roundStart, abortedAt) != ST_FAILED) return true;
        }
        return false;
    }

    /** 测速引擎记录的最近一轮开始时间（含断网轮次；0 表示未知/从未测速） */
    private long engineRoundStart() {
        try {
            return Math.max(0L, speedTestEngine.getLastRoundStartedAt());
        } catch (Throwable t) {
            return 0L;
        }
    }

    /**
     * 判定新旧结果所用的轮次起点（0 表示不按轮次判断）。
     * 最近一轮是断网轮次（未被采用）时，改用上一轮被采用的测速开始时间：
     * 否则上一轮正常测出的可用/失败结论全部变成"未测"，失效线路会在之后的刷新中重新排到首位。
     * 断网轮次自身的失败仍由 lineStatus 按 abortedAt 视为"未测"。
     * 断网标记只在下一轮被采用时清除，因此连续多轮断网、或断网后新一轮尚未结束时都沿用上一正常轮。
     */
    private long currentRoundStart() {
        if (netAbortedRoundAt() > 0) return lastHealthyRoundAt();
        return engineRoundStart();
    }

    private static long lastHealthyRoundAt() {
        try {
            Long v = Hawk.get(KEY_LAST_HEALTHY_ROUND_AT, 0L);
            return v == null ? 0L : Math.max(0L, v);
        } catch (Throwable t) {
            return 0L;
        }
    }

    /** 因断网而未被采用的测速轮次开始时间（0 表示没有） */
    private static long netAbortedRoundAt() {
        try {
            Long v = Hawk.get(KEY_NET_ABORTED_ROUND_AT, 0L);
            return v == null ? 0L : v;
        } catch (Throwable t) {
            return 0L;
        }
    }

    /**
     * 线路的测速状态（过滤 / 排序 / 选最佳线路共用同一判定，避免三处口径不一致）。
     *
     * <p>早于最近一轮开始时间的结果视为"未测"：该线路本轮没测到（配额/列表被替换），
     * 旧结果既不能当作失败去隐藏/剔除，也不能当作本轮的可用结论。
     * 断网轮次（见 {@link #KEY_NET_ABORTED_ROUND_AT}）写入的失败同样视为"未测"。
     */
    private int lineStatus(String url, long roundStart, long abortedAt) {
        // 按当前设置已经播不了的线路（例如关掉 udpxy / 组播直连后列表里残留的 rtp://）：按失败处理，
        // 不能因为"不可测速"被当成可播的保底线路，让频道留在列表里点进去却没有一条能播
        if (url != null && !com.github.tvbox.osc.util.ProtocolFilter.isSupported(url)) return ST_FAILED;
        SpeedTestResult r = url == null ? null : speedTestEngine.getCachedResult(url);
        if (r == null) return ST_UNTESTED;
        if (roundStart > 0 && r.getTestTime() < roundStart) return ST_UNTESTED;
        if (SpeedTestEngine.UNTESTABLE.equals(r.getFailureType())) return ST_UNTESTABLE;
        if (r.isAvailable()) return ST_OK;
        if (abortedAt > 0 && abortedAt >= roundStart && r.getTestTime() >= abortedAt) return ST_UNTESTED;
        // 当前设置下可播的组播线路测速失败按"未测"算：udpxy 宕机/并发满时所有组播线路一起失败，
        // 与线路坏了分不清；按失败算会让只有组播线路的频道整个从列表和本地播放列表里消失
        if (url != null && com.github.tvbox.osc.util.MulticastUrl.isMulticast(url)) return ST_UNTESTED;
        return ST_FAILED;
    }

    /**
     * 两条"可用"线路的优劣比较（&lt;0 表示 a 更好）：按当前排序模式的主键降序，再按另一指标降序；
     * 全部相等返回 0，由调用方保持原顺序。重排与本地列表选最佳线路共用，保证同分时结论一致。
     */
    private static int compareAvailable(SpeedTestResult a, SpeedTestResult b, boolean byScore) {
        double sa = safe(a.getSpeed()), sb = safe(b.getSpeed());
        int qa = a.getQualityScore(), qb = b.getQualityScore();
        int c = byScore ? Integer.compare(qb, qa) : Double.compare(sb, sa);
        if (c != 0) return c;
        return byScore ? Double.compare(sb, sa) : Integer.compare(qb, qa);
    }

    private static double safe(double v) {
        return Double.isNaN(v) ? 0d : v;
    }

    private static boolean isSortByScore() {
        try {
            return Hawk.get(HawkConfig.SPEED_SORT_MODE, HawkConfig.SORT_MODE_QUALITY_SCORE)
                    == HawkConfig.SORT_MODE_QUALITY_SCORE;
        } catch (Throwable t) {
            return true;
        }
    }

    /** 用户置顶的线路（pinSource 保存的顺序首条）；没有置顶返回 null */
    private String pinnedUrl(LiveChannel channel) {
        if (channel == null) return null;
        List<String> order = sourceOrder.get(channel.getKey());
        if (order == null || order.isEmpty()) return null;
        String stored = order.get(0);
        // 返回频道里实际的那条线路：1.2.4 保存的旧写法（无 '|请求头'）也能对上，
        // 下游一律按 equals/indexOf/contains 比较，无需逐处改；列表里没有时原样返回（补回置顶线路用）
        String actual = resolveStoredLine(channel.getSourceUrls(), stored);
        return actual != null ? actual : stored;
    }

    /**
     * 某线路是否为用户置顶线路。测速剔除不可用线路时应跳过它（用户已确认能播，
     * 测速可能因缺少请求头或阈值误判）。
     */
    public boolean isSourcePinned(LiveChannel channel, String url) {
        return url != null && url.equals(pinnedUrl(channel));
    }

    /**
     * 按"单频道最多线路"裁剪 rawGroups 中每个频道的线路。
     *
     * 调用时机：测速完成后。此时线路已按可用性与质量排序，截断即保留最佳的若干条，
     * 既能提供换源备选，又避免超大列表长期占用内存与数据库空间。
     * 注意：下一次测速会重新联网拉取全量线路，不会因此丢失候选。
     */
    private void applyUrlsLimitToRaw() {
        applyUrlsLimitToRaw(null);
    }

    /** @param only 只裁剪这些频道（按对象身份）；null 表示全部频道 */
    private synchronized void applyUrlsLimitToRaw(java.util.Set<LiveChannel> only) {
        int urlsLimit = Hawk.get(HawkConfig.IPTV_URLS_LIMIT, HawkConfig.DEFAULT_URLS_LIMIT);
        if (urlsLimit <= 0 || rawGroups == null) return;
        int trimmed = 0;
        for (ChannelGroup group : new ArrayList<>(rawGroups)) {
            if (group == null || group.getChannels() == null) continue;
            for (LiveChannel ch : new ArrayList<>(group.getChannels())) {
                if (ch == null) continue;
                if (only != null && !only.contains(ch)) continue;
                List<String> urls = ch.getSourceUrls();
                if (urls == null || urls.size() <= urlsLimit) continue;
                // 裁剪前记下当前线路：重排时只有正在播放的频道（播放提示）索引非 0，
                // 一律复位 0 会让它的索引指向播放器并未在播的线路（稳定线路记录/自动换源错位）
                String cur = ch.getCurrentSourceUrl();
                // 保留下标：前 urlsLimit 条；用户置顶的线路排在上限之外时换掉最后一条保留，
                // 否则置顶线路被裁掉、置顶偏好随之静默失效
                List<Integer> keepIdx0 = new ArrayList<>(urlsLimit);
                for (int i = 0; i < urlsLimit; i++) keepIdx0.add(i);
                String pinned = pinnedUrl(ch);
                int pinnedIdx = pinned == null ? -1 : urls.indexOf(pinned);
                if (pinnedIdx >= urlsLimit) keepIdx0.set(urlsLimit - 1, pinnedIdx);
                // 正在播放的线路排在上限之外时，换掉最后一个非置顶的保留位：
                // 否则卡断时"原线路重连"找不到它，只能换线。上限只有 1 条时首条优先，不做替换
                if (urlsLimit >= 2) {
                    int playingIdx = -1;
                    for (int i = urlsLimit; i < urls.size(); i++) {
                        if (LiveChannel.isPlayingUrl(ch, urls.get(i))) {
                            playingIdx = i;
                            break;
                        }
                    }
                    if (playingIdx >= 0 && !keepIdx0.contains(playingIdx)) {
                        int slot = keepIdx0.get(urlsLimit - 1) == pinnedIdx ? urlsLimit - 2 : urlsLimit - 1;
                        keepIdx0.set(slot, playingIdx);
                    }
                }
                List<String> kept = new ArrayList<>(urlsLimit);
                for (int i : keepIdx0) kept.add(urls.get(i));
                List<Double> speeds = ch.getSpeeds();
                ch.setSourceUrls(kept);
                if (speeds != null && speeds.size() == urls.size()) {
                    List<Double> keptSpeeds = new ArrayList<>(urlsLimit);
                    for (int i : keepIdx0) keptSpeeds.add(speeds.get(i));
                    ch.setSpeeds(keptSpeeds);
                } else if (speeds != null && speeds.size() > urlsLimit) {
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
     * 测速可用的在前（按综合评分或速度，与测速排序模式一致），未测过的居中，黑屏线路其次，测速失败的最后。
     * 稳定排序，同档保持原顺序。用户关闭"测速后排序线路"时不做处理（与测速引擎一致）。
     */
    private void reorderRawByCachedResults() {
        reorderRawByCachedResults(null);
    }

    /** @param only 只重排这些频道（按对象身份）；null 表示全部频道 */
    private synchronized void reorderRawByCachedResults(java.util.Set<LiveChannel> only) {
        if (!Hawk.get(HawkConfig.SPEED_SORT_SOURCES, true)) return;
        List<ChannelGroup> groups = rawGroups;
        if (groups == null) return;
        final boolean byScore = isSortByScore();
        final long roundStart = currentRoundStart();
        final long abortedAt = netAbortedRoundAt();
        for (ChannelGroup group : new ArrayList<>(groups)) {
            if (group == null) continue;
            for (LiveChannel ch : group.snapshotChannels()) {
                if (ch == null) continue;
                if (only != null && !only.contains(ch)) continue;
                List<String> urls = ch.getSourceUrls();
                if (urls == null || urls.size() < 2) continue;
                final int n = urls.size();
                // 0 可用 / 1 未测(含无法测速、旧结果) / 2 黑屏 / 3 测速失败
                final int[] tier = new int[n];
                final SpeedTestResult[] res = new SpeedTestResult[n]; // 可用线路的测速结果
                // 广播/电台类频道本来没有画面，不按黑屏处理
                final boolean audio = LineQuality.isAudioChannel(ch);
                for (int i = 0; i < n; i++) {
                    String url = urls.get(i);
                    int st = lineStatus(url, roundStart, abortedAt);
                    if (st == ST_OK) {
                        tier[i] = 0;
                        res[i] = speedTestEngine.getCachedResult(url);
                        if (res[i] == null) tier[i] = 1; // 读取间隙被清缓存
                    } else if (st == ST_FAILED) {
                        tier[i] = 3;
                    } else {
                        tier[i] = 1;
                    }
                    // 黑屏线路与失败线路一样排到后面（排在失败线路之前，没有别的线路时仍可兜底）
                    if (tier[i] < 2 && !audio && LineQuality.isBlackLine(url, res[i])) tier[i] = 2;
                }
                List<Integer> order = new ArrayList<>(n);
                for (int i = 0; i < n; i++) order.add(i);
                // 同档内与 pickBestSource 同一比较规则（主键 → 次键 → 原顺序），
                // 否则同分时播放的首条与本地列表写入的"最佳线路"不是同一条
                java.util.Collections.sort(order, (a, b) -> {
                    if (tier[a] != tier[b]) return Integer.compare(tier[a], tier[b]);
                    if (tier[a] == 0) return compareAvailable(res[a], res[b], byScore);
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
        persistAfterSpeedTest(null);
    }

    /**
     * @param partialChannelKey 局部轮次（"测速本频道"）时为该频道的 key；整轮测速为 null。
     *                          由调用方（测速服务）按自己这一轮的身份传入，而不是从引擎的
     *                          "最近一次局部轮次"推断——否则整轮收尾期间局部轮次插队完成，
     *                          整轮会被误按局部轮次持久化
     */
    public void persistAfterSpeedTest(String partialChannelKey) {
        SpeedTestEngine.PartialRoundSnapshot snap = null;
        if (partialChannelKey != null) {
            // 在调用线程（本轮 onAllComplete 回调里）取快照：引擎在回调前由同一线程发布，
            // 等到 IO 线程执行时可能已有新一轮开始
            try {
                snap = speedTestEngine.getLastPartialRound();
            } catch (Throwable ignore) {
            }
            if (snap == null || !snap.channelKeys.contains(partialChannelKey)) {
                // 快照不属于本轮（异常竞态）：不做局部重排/裁剪/写库，结果已在测速缓存里，
                // 下次整轮测速会统一持久化。展示列表仍按当前线路重建：引擎已对被测频道做过剔除/排序，
                // 原先因无可用线路被隐藏的频道测出可用线路后应重新出现
                Timber.w("单频道测速完成但轮次快照不匹配，跳过局部持久化: %s", partialChannelKey);
                ioExecutor.execute(() -> {
                    try {
                        synchronized (rawLock) {
                            rebuildDisplayGroups();
                            notifySpeedTestPersisted();
                        }
                    } catch (Throwable e) {
                        Timber.w(e, "重建展示列表失败");
                    }
                });
                return;
            }
        }
        final SpeedTestEngine.PartialRoundSnapshot partial = snap;
        ioExecutor.execute(() -> {
            try {
                // 与刷新源替换 rawGroups 互斥：刷新结果不会在重排/裁剪/写库中途换掉列表，
                // 也不会在持久化之后以未排序的拉取顺序覆盖测速结果（刷新侧同样会按缓存重排）
                synchronized (rawLock) {
                    if (partial != null) {
                        persistAfterPartialSpeedTestLocked(partial);
                    } else {
                        persistAfterSpeedTestLocked();
                    }
                }
            } catch (Throwable e) {
                // 生成/序列化大列表在低内存设备上可能 OOM（Error），需兜住
                Timber.e(e, "测速后持久化本地播放列表失败");
            }
        });
    }

    private void persistAfterSpeedTestLocked() {
        // 用引擎记录的本轮起点判定本轮是否断网（currentRoundStart 在断网标记存在时会回退到上一正常轮）
        final long roundStart = engineRoundStart();
        // 断网/网络异常的一轮：几乎所有线路都因网络错误失败。若照常应用，所有频道都会被判为
        // "全部线路不可用"而隐藏，本地列表被空列表覆盖，且失败结果已落盘，之后每次启动都是空列表。
        // 此时不过滤、不裁剪、不写库、不生成本地列表，并把本轮失败记为"未测"。
        long[] stats = countRoundResults(rawGroups, roundStart);
        if (isNetworkDownRound(stats)) {
            long at = roundStart > 0 ? roundStart : stats[3];
            try {
                // 连续多轮断网：保留最早一轮的起点，前几轮断网写入的失败同样继续视为"未测"
                long prev = netAbortedRoundAt();
                Hawk.put(KEY_NET_ABORTED_ROUND_AT, Math.max(1L, prev > 0 ? Math.min(prev, at) : at));
            } catch (Throwable ignore) {
            }
            restorePinnedSources(roundStart, rawReplacedAt > roundStart);
            // 引擎已按本轮（断网）结果重排：之前最佳的线路因网络失败被排到末尾。
            // 按上一正常轮的结论重排回去（断网失败视为未测），避免会话内与之后刷新都先播失效线路
            try {
                reorderRawByCachedResults();
            } catch (Throwable t) {
                Timber.w(t, "断网轮次后按测速缓存重排线路失败");
            }
            rebuildDisplayGroups();
            notifySpeedTestPersisted();
            Timber.w("本轮测速 %d 条线路中仅 %d 条可用、%d 条因网络错误失败，疑似网络不可用，测速结果未应用",
                    stats[0], stats[1], stats[2]);
            return;
        }
        // 正常的一轮：之前断网轮次的标记失效（其失败结果已早于本轮开始，按"旧结果"处理）
        try {
            Hawk.delete(KEY_NET_ABORTED_ROUND_AT);
            if (roundStart > 0) Hawk.put(KEY_LAST_HEALTHY_ROUND_AT, roundStart);
        } catch (Throwable ignore) {
        }

        // 测速期间 rawGroups 被刷新整体替换：测速引擎的剔除/测速值/排序作用在已被丢弃的旧对象上，
        // 这里按 URL 对当前列表补做一遍（与引擎同一规则），否则失败线路留在列表里、速度不显示
        final boolean swapped = roundStart > 0 && rawReplacedAt > roundStart;
        if (swapped) {
            applyRoundResultsByUrl(roundStart);
        } else {
            // 未被替换：引擎可能已把用户置顶的线路当作不可用删掉，按 URL 补回
            restorePinnedSources(roundStart, false);
        }
        // 裁剪前按测速缓存（以 URL 为键）重排当前 rawGroups 的线路：若测速期间
        // rawGroups 被源刷新整体替换，测速引擎排序的是已被丢弃的旧对象，
        // 新对象仍是拉取顺序，直接截断会丢掉测出的最佳线路。对未被替换的情况幂等。
        reorderRawByCachedResults();
        // 测速已覆盖全部线路，此处按“单频道最多线路”只保留最佳的若干条
        // （可用线路已按质量排在前面，置顶线路始终保留），控制长期内存与数据库体积
        applyUrlsLimitToRaw();
        // 重建展示列表以反映测速后的线路排序与过滤
        rebuildDisplayGroups();
        // 重建完成后通知界面重同步（列表与播放线路索引），见 speedTestPersistedListener
        notifySpeedTestPersisted();
        // 持久化频道数据（含排序后的线路顺序与速度）
        saveToDatabase(rawGroups);
        // 生成并持久化"最佳线路"本地播放列表
        String playlist = generateLocalPlaylist();
        if (playlist == null || playlist.isEmpty()) {
            // 一个可用频道都没有：绝不用空列表覆盖已有的本地列表
            Timber.w("测速后没有可写入本地列表的频道，保留原有本地列表");
            return;
        }
        // 列表在测速中途被替换且有频道一条线路都没测到：内容照常保存（供恢复），
        // 但不刷新"本地列表生成时间"，不把未测速的列表当作测速结果
        boolean stamp = !(swapped && countUntestedChannels(roundStart) > 0);
        saveLocalPlaylist(playlist, stamp);
        Timber.i("测速后已生成并持久化最佳线路本地播放列表%s", stamp ? "" : "（列表中途被刷新，未记为已测速）");
    }

    /**
     * 局部轮次（只测了个别频道，如"测速本频道"）结束后的持久化。
     *
     * <p>与完整一轮的区别：
     * <ul>
     *   <li>不做断网判定，不写/不清 {@link #KEY_NET_ABORTED_ROUND_AT} 与 {@link #KEY_LAST_HEALTHY_ROUND_AT}：
     *       一个频道的结果代表不了整体网络状况（该频道本来就是因为没有可用线路才单独测的）；</li>
     *   <li>按 URL 补用结果、补回置顶线路都以本轮起点 partialStart 为界，只作用于本轮测到的线路；</li>
     *   <li>重排与按上限裁剪只处理<b>本轮实际传入的频道</b>（按快照里的频道 key 对应到当前列表），
     *       其它频道保持原样（刷新后未测的新线路不被裁掉）。不能按"缓存里有本轮结果的线路"倒推频道：
     *       同一线路常被多个频道共享，会把没测过的频道卷进来裁剪；</li>
     *   <li>疑似网络异常（见 {@link SpeedTestEngine.PartialRoundSnapshot#networkAborted}）时不应用结果：
     *       引擎已回滚本轮缓存，这里只按旧缓存把线路顺序排回去，不隐藏频道、不写库、不动本地列表；</li>
     *   <li>本地列表内容照常更新，但不刷新"生成时间"：一次单频道测速不算完成了一轮测速。</li>
     * </ul>
     * 判定新旧结果仍用 {@link #currentRoundStart}（上一完整轮）：局部轮次的结果更新，自然算作该频道的当前结果。
     */
    private void persistAfterPartialSpeedTestLocked(SpeedTestEngine.PartialRoundSnapshot round) {
        final long partialStart = round.startedAt;
        java.util.Set<LiveChannel> tested = resolveChannelsByKeys(round.channelKeys);
        if (round.networkAborted) {
            // 本轮测到的失败是网络问题而非线路问题：逐频道更新已把失败排序作用在频道上，
            // 按回滚后的旧缓存把顺序排回去即可
            if (!tested.isEmpty()) reorderRawByCachedResults(tested);
            rebuildDisplayGroups();
            notifySpeedTestPersisted();
            Timber.w("单频道测速疑似网络异常，结果未应用");
            return;
        }
        // 测速期间列表被刷新整体替换：引擎的剔除/测速值作用在旧对象上，按 URL 对当前列表补做
        // （以本轮起点为界，只有本轮测到的线路参与）
        final boolean swapped = rawReplacedAt > partialStart;
        if (swapped) {
            // 只作用于本轮测到的频道：共享同一线路的其它频道不能被这一两条线路的结果改写
            applyRoundResultsByUrl(partialStart, tested);
        } else {
            restorePinnedSources(partialStart, false);
        }
        if (!tested.isEmpty()) {
            reorderRawByCachedResults(tested);
            applyUrlsLimitToRaw(tested);
        }
        rebuildDisplayGroups();
        notifySpeedTestPersisted();
        saveToDatabase(rawGroups);
        String playlist = generateLocalPlaylist();
        if (playlist == null || playlist.isEmpty()) {
            Timber.w("单频道测速后没有可写入本地列表的频道，保留原有本地列表");
            return;
        }
        saveLocalPlaylist(playlist, false);
        Timber.i("单频道测速后已更新 %d 个频道的线路并持久化", tested.size());
    }

    /** 按频道 key（{@link LiveChannel#getKey()}）在 rawGroups 中解析频道对象集合（按对象身份去重） */
    private java.util.Set<LiveChannel> resolveChannelsByKeys(java.util.Set<String> keys) {
        java.util.Set<LiveChannel> out =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<LiveChannel, Boolean>());
        if (keys == null || keys.isEmpty()) return out;
        List<ChannelGroup> groups = rawGroups;
        if (groups == null) return out;
        for (ChannelGroup g : new ArrayList<>(groups)) {
            if (g == null) continue;
            for (LiveChannel ch : g.snapshotChannels()) {
                if (ch != null && keys.contains(ch.getKey())) out.add(ch);
            }
        }
        return out;
    }

    /**
     * 统计 groups 中各线路在本轮（roundStart 之后）的测速结果。
     *
     * @return [已测(不含无法测速), 可用, 网络类失败(DNS/连接/超时), 最早测速时间]
     */
    private long[] countRoundResults(List<ChannelGroup> groups, long roundStart) {
        long tested = 0, ok = 0, netFail = 0, minTime = Long.MAX_VALUE;
        if (groups == null) return new long[]{0, 0, 0, 0};
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (ChannelGroup g : new ArrayList<>(groups)) {
            if (g == null) continue;
            for (LiveChannel ch : g.snapshotChannels()) {
                if (ch == null || ch.getSourceUrls() == null) continue;
                for (String url : ch.getSourceUrls()) {
                    if (url == null || !seen.add(url)) continue;
                    SpeedTestResult r = speedTestEngine.getCachedResult(url);
                    if (r == null || r.getTestTime() < roundStart) continue;
                    if (SpeedTestEngine.UNTESTABLE.equals(r.getFailureType())) continue;
                    tested++;
                    if (r.getTestTime() > 0 && r.getTestTime() < minTime) minTime = r.getTestTime();
                    if (r.isAvailable()) {
                        ok++;
                    } else if (isNetworkFailure(r.getFailureType())) {
                        netFail++;
                    }
                }
            }
        }
        return new long[]{tested, ok, netFail, minTime == Long.MAX_VALUE ? 0 : minTime};
    }

    /** DNS 解析失败 / 连接失败等（OTHER）/ 超时：断网或网络异常时线路全部表现为这几类 */
    public static boolean isNetworkFailure(String failureType) {
        return "DNS".equals(failureType) || "OTHER".equals(failureType) || "TIMEOUT".equals(failureType);
    }

    /**
     * 本轮是否疑似网络不可用：一条可用线路都没有；或可用不足 2% 且 90% 以上的失败是网络类错误。
     * 网络正常时总有相当比例的线路可用，不会触发。
     */
    public static boolean isNetworkDownRound(long[] stats) {
        long tested = stats[0], ok = stats[1], netFail = stats[2];
        if (tested <= 0) return false;
        if (ok == 0) return true;
        return ok * 50 < tested && netFail * 10 >= tested * 9;
    }

    /**
     * 列表在测速中途被替换时，按 URL 把本轮结果补用到当前 rawGroups：
     * 剔除本轮明确失败的线路（与测速引擎同一规则：全部失败时保留；置顶线路不剔除）
     * 及黑屏 / 低分辨率线路（见 {@link #removeLowQualitySources}），
     * 并写入各线路速度与频道最佳速度/评分/分辨率。
     */
    private void applyRoundResultsByUrl(long roundStart) {
        applyRoundResultsByUrl(roundStart, null);
    }

    /** @param only 只处理这些频道（按对象身份）；null 表示全部频道 */
    private void applyRoundResultsByUrl(long roundStart, java.util.Set<LiveChannel> only) {
        List<ChannelGroup> groups = rawGroups;
        if (groups == null) return;
        boolean filter = true;
        try {
            filter = Hawk.get(HawkConfig.SPEED_FILTER_UNAVAILABLE, true);
        } catch (Throwable ignore) {
        }
        long now = System.currentTimeMillis();
        for (ChannelGroup g : new ArrayList<>(groups)) {
            if (g == null) continue;
            for (LiveChannel ch : g.snapshotChannels()) {
                if (ch == null) continue;
                if (only != null && !only.contains(ch)) continue;
                synchronized (ch) {
                    List<String> urls = ch.getSourceUrls();
                    if (urls == null || urls.isEmpty()) continue;
                    boolean anyTested = false;
                    if (filter) {
                        String pinned = pinnedUrl(ch);
                        List<String> toRemove = new ArrayList<>();
                        // 与引擎 removeUnavailableSources 同一口径："全部失败"按本轮实际测到的线路算
                        // （不含无法测速/未测/旧结果）。原先按全部线路算，测到的都失败但还有一条未测时
                        // 会把测到的全删掉，比未替换列表的正常路径更激进
                        int tested = 0, failed = 0;
                        for (String url : urls) {
                            if (url == null) continue;
                            int st = lineStatus(url, roundStart, 0L);
                            if (st != ST_OK && st != ST_FAILED) continue;
                            // 当前设置下可播的组播线路测速失败不删：udpxy 代理宕机 / 并发满了时所有组播线路
                            // 一起失败，与线路本身坏了分不清（设置关掉后残留的组播线路仍按失败删除）
                            if (st == ST_FAILED && com.github.tvbox.osc.util.MulticastUrl.isMulticast(url)
                                    && com.github.tvbox.osc.util.ProtocolFilter.isSupported(url)) continue;
                            tested++;
                            if (st != ST_FAILED) continue;
                            failed++;
                            // 置顶线路与正在播放的线路不剔除（与引擎 removeUnavailableSources 一致）
                            if (!url.equals(pinned) && !LiveChannel.isPlayingUrl(ch, url)) toRemove.add(url);
                        }
                        if (!toRemove.isEmpty() && failed < tested) {
                            for (String url : toRemove) ch.removeSource(url);
                        }
                    }
                    // 画质过滤（黑屏 / 低分辨率）有各自的开关，不受"过滤不可用线路"影响
                    removeLowQualitySources(ch, roundStart);
                    urls = ch.getSourceUrls();
                    List<Double> speeds = new ArrayList<>(urls.size());
                    List<Double> oldSpeeds = ch.getSpeeds();
                    double bestSpeed = 0;
                    int bestScore = 0;
                    String bestResolution = null;
                    for (int i = 0; i < urls.size(); i++) {
                        SpeedTestResult r = speedTestEngine.getCachedResult(urls.get(i));
                        boolean current = r != null && r.getTestTime() >= roundStart;
                        double old = oldSpeeds != null && i < oldSpeeds.size() && oldSpeeds.get(i) != null
                                ? oldSpeeds.get(i) : 0d;
                        speeds.add(current ? safe(r.getSpeed()) : old);
                        if (!current) continue;
                        anyTested = true;
                        if (r.getSpeed() > bestSpeed) bestSpeed = r.getSpeed();
                        if (r.getQualityScore() > bestScore) {
                            bestScore = r.getQualityScore();
                            if (r.getResolution() != null) bestResolution = r.getResolution();
                        }
                    }
                    ch.setSpeeds(speeds);
                    if (anyTested) {
                        ch.setBestSpeed(bestSpeed);
                        ch.setBestQualityScore(bestScore);
                        if (bestResolution != null) ch.setBestResolution(bestResolution);
                        ch.setLastSpeedTestTime(now);
                    }
                }
            }
        }
    }

    /**
     * 剔除黑屏 / 低分辨率线路（与测速引擎同一规则，调用方持有频道锁）：
     * 只判断本轮测得可用的线路；本轮测得不可用的不判断、也不算正常线路；未测/不可测速/旧结果不判断；
     * 置顶线路不合格时计入不合格（不算正常线路），但不删除；
     * 广播/电台类频道不判断；分辨率未知不算低分辨率；
     * 频道里须至少留下一条本轮实测可用且画质合格的线路，否则一条都不按画质删。
     */
    private void removeLowQualitySources(LiveChannel ch, long roundStart) {
        if (!LineQuality.filterBlackEnabled() && LineQuality.minHeight() <= 0) return;
        if (LineQuality.isAudioChannel(ch)) return;
        List<String> urls = ch.getSourceUrls();
        if (urls == null || urls.isEmpty()) return;
        String pinned = pinnedUrl(ch);
        List<String> lowQuality = new ArrayList<>();
        int good = 0;
        for (String url : urls) {
            if (url == null) continue;
            // 与引擎同一口径：只判断本轮测得可用的线路
            if (lineStatus(url, roundStart, 0L) != ST_OK) continue;
            SpeedTestResult r = speedTestEngine.getCachedResult(url);
            if (LineQuality.isBlackLine(url, r) || LineQuality.isLowResolution(url, r)) {
                // 置顶线路也计入不合格（不算正常线路），删除时再跳过
                lowQuality.add(url);
            } else {
                good++;   // 本轮实测可用且画质合格
            }
        }
        if (lowQuality.isEmpty()) return;
        if (good == 0) {
            Timber.w("频道 %s 没有本轮实测可用且画质合格的线路，保留黑屏/低分辨率线路", ch.getChannelName());
            return;
        }
        int black = 0, low = 0;
        for (String url : lowQuality) {
            if (url.equals(pinned)) continue;   // 置顶线路不删
            if (LiveChannel.isPlayingUrl(ch, url)) continue;   // 正在播放的线路不删（与引擎一致）
            boolean isBlack = LineQuality.isBlackLine(url, speedTestEngine.getCachedResult(url));
            if (ch.removeSource(url)) {
                if (isBlack) black++;
                else low++;
            }
        }
        if (black + low > 0) Timber.d("频道 %s 移除黑屏线路 %d 条、低分辨率线路 %d 条", ch.getChannelName(), black, low);
    }

    /**
     * 把被测速剔除的用户置顶线路补回频道首位。
     * 仅当列表未被替换、且该线路本轮确实测过（说明测速开始时它就在列表里，是被测速删掉的，
     * 而不是上游已下线）时补回；被拉黑的线路不会补回（拉黑时已从置顶顺序中移除）。
     */
    private void restorePinnedSources(long roundStart, boolean swapped) {
        if (swapped || roundStart <= 0 || sourceOrder.isEmpty()) return;
        List<ChannelGroup> groups = rawGroups;
        if (groups == null) return;
        int restored = 0;
        for (ChannelGroup g : new ArrayList<>(groups)) {
            if (g == null) continue;
            for (LiveChannel ch : g.snapshotChannels()) {
                if (ch == null) continue;
                String pinned = pinnedUrl(ch);
                if (pinned == null || isSourceBlacklisted(ch, pinned)) continue;
                SpeedTestResult r = speedTestEngine.getCachedResult(pinned);
                if (r == null || r.getTestTime() < roundStart) continue;
                synchronized (ch) {
                    List<String> urls = ch.getSourceUrls();
                    if (urls == null || urls.contains(pinned)) continue;
                    List<String> newUrls = new ArrayList<>(urls.size() + 1);
                    newUrls.add(pinned);
                    newUrls.addAll(urls);
                    List<Double> sp = ch.getSpeeds();
                    List<Double> newSpeeds = new ArrayList<>(urls.size() + 1);
                    newSpeeds.add(safe(r.getSpeed()));
                    if (sp != null && sp.size() == urls.size()) {
                        newSpeeds.addAll(sp);
                    } else {
                        newSpeeds.addAll(java.util.Collections.nCopies(urls.size(), 0d));
                    }
                    int idx = Math.max(0, ch.getSourceIndex());
                    ch.setSourceUrls(newUrls);
                    ch.setSpeeds(newSpeeds);
                    // 插到首位后原索引整体后移一位，保持指向原来在播的线路
                    ch.setSourceIndex(urls.isEmpty() ? 0 : Math.min(idx, urls.size() - 1) + 1);
                    restored++;
                }
            }
        }
        if (restored > 0) Timber.i("已补回 %d 条被测速剔除的置顶线路", restored);
    }

    /** 当前 rawGroups 中所有线路都没有本轮测速结果的频道数 */
    private int countUntestedChannels(long roundStart) {
        List<ChannelGroup> groups = rawGroups;
        if (groups == null) return 0;
        int n = 0;
        for (ChannelGroup g : new ArrayList<>(groups)) {
            if (g == null) continue;
            for (LiveChannel ch : g.snapshotChannels()) {
                if (ch == null || ch.getSourceUrls() == null || ch.getSourceUrls().isEmpty()) continue;
                boolean any = false;
                for (String url : ch.getSourceUrls()) {
                    SpeedTestResult r = speedTestEngine.getCachedResult(url);
                    if (r != null && r.getTestTime() >= roundStart) {
                        any = true;
                        break;
                    }
                }
                if (!any) n++;
            }
        }
        return n;
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
     * 为每个频道选出最佳线路，生成本地播放列表（v2 JSON 格式）。
     *
     * <p>旧版写 TXT（"频道名,最佳URL"）：频道名含逗号（如 "CCTV-1,综合"）恢复时被拆坏，
     * 且只留一条线路、丢掉 EPG id 与台标。这里改为 JSON，字符串全部转义，每个频道写入：
     * 名称、编号、EPG id、台标，以及最佳线路在首位、其余未明确失败的备用线路（已按上限裁剪）。
     * 读取时仍兼容旧 TXT（见 {@link #restoreFromLocalPlaylist}）。
     *
     * <p>无可用播放源的频道会被<b>整个剔除</b>，不写入列表；剔除后为空的分组也不写入。
     * 一个频道都没有时返回空串（调用方据此不覆盖已有本地列表）。
     */
    public synchronized String generateLocalPlaylist() {
        List<ChannelGroup> groups = rawGroups;
        if (groups == null) return "";
        final long roundStart = currentRoundStart();
        final long abortedAt = netAbortedRoundAt();
        final boolean byScore = isSortByScore();
        int urlsLimit = HawkConfig.DEFAULT_URLS_LIMIT;
        try {
            urlsLimit = Hawk.get(HawkConfig.IPTV_URLS_LIMIT, HawkConfig.DEFAULT_URLS_LIMIT);
        } catch (Throwable ignore) {
        }
        final int maxUrls = urlsLimit > 0 ? urlsLimit : LOCAL_PLAYLIST_MAX_URLS_UNLIMITED;
        StringBuilder sb = new StringBuilder();
        sb.append("{\"format\":").append(org.json.JSONObject.quote(LOCAL_PLAYLIST_JSON_FORMAT))
                .append(",\"groups\":[");
        int kept = 0;
        int skipped = 0;
        boolean firstGroup = true;
        for (ChannelGroup group : new ArrayList<>(groups)) {
            if (group == null || group.getChannels() == null || group.getChannels().isEmpty()) continue;
            String groupName = group.getGroupName() != null ? group.getGroupName() : "未分类";
            boolean headerWritten = false;
            for (LiveChannel channel : new ArrayList<>(group.getChannels())) {
                if (channel == null) continue;
                String name = channel.getChannelName();
                if (name == null || name.trim().isEmpty()) continue;
                String bestUrl = pickBestSource(channel, roundStart, abortedAt, byScore);
                if (bestUrl == null || bestUrl.isEmpty()) {
                    // 该频道所有线路均无可用源：不写入节目列表
                    skipped++;
                    continue;
                }
                if (!headerWritten) {
                    if (!firstGroup) sb.append(',');
                    firstGroup = false;
                    sb.append("{\"name\":").append(org.json.JSONObject.quote(groupName))
                            .append(",\"channels\":[");
                    headerWritten = true;
                } else {
                    sb.append(',');
                }
                sb.append("{\"name\":").append(org.json.JSONObject.quote(name));
                appendOptional(sb, "num", channel.getChannelNum());
                appendOptional(sb, "epg", channel.getEpgId());
                appendOptional(sb, "logo", channel.getLogoUrl());
                sb.append(",\"urls\":[").append(org.json.JSONObject.quote(bestUrl));
                // 备用线路：保持当前顺序（已按测速排序），跳过明确失败的（置顶线路除外）。
                // 线路数已按上限裁剪；"不限制"时另设上限，避免大列表生成数 MB 的文件
                String pinned = pinnedUrl(channel);
                List<String> urls = channel.getSourceUrls();
                if (urls != null) {
                    int written = 1;
                    // 置顶线路尚未写入时为它留一个名额，保证它总在列表里
                    boolean pinnedPending = pinned != null && !bestUrl.equals(pinned) && urls.contains(pinned);
                    for (String url : urls) {
                        if (url == null || url.isEmpty() || url.equals(bestUrl)) continue;
                        boolean isPinned = url.equals(pinned);
                        if (!isPinned && lineStatus(url, roundStart, abortedAt) == ST_FAILED) continue;
                        if (!isPinned && maxUrls - written - (pinnedPending ? 1 : 0) <= 0) continue;
                        sb.append(',').append(org.json.JSONObject.quote(url));
                        written++;
                        if (isPinned) pinnedPending = false;
                    }
                }
                sb.append("]}");
                kept++;
            }
            if (headerWritten) sb.append("]}");
        }
        sb.append("]}");
        Timber.i("生成本地播放列表: 保留 %d 个频道，剔除 %d 个无可用源频道", kept, skipped);
        return kept > 0 ? sb.toString() : "";
    }

    private static void appendOptional(StringBuilder sb, String key, String value) {
        if (value == null || value.isEmpty()) return;
        sb.append(",\"").append(key).append("\":").append(org.json.JSONObject.quote(value));
    }

    /**
     * 选出频道的最佳线路 URL：
     * 优先取"本轮测速可用"的线路，比较规则与线路重排一致（见 {@link #compareAvailable}：
     * 当前排序模式的主键 → 另一指标 → 原顺序），保证同分时与播放的首条是同一条。
     *
     * <p>没有可用线路时依次回退：无法测速的协议线路（RTSP/RTMP）与未测线路
     * （无记录、被单轮配额截断、早于本轮的旧结果、断网轮次的失败）中列表里靠前的一条
     * （与重排后的顺序一致：两者同属一档、保持原顺序，本地列表首条与 App 内首条相同）→ 黑屏线路（测速无视频轨或播放时标记黑屏；
     * 广播/电台类频道不按黑屏处理）→ 用户置顶线路。
     * <b>只有全部线路都在本轮明确失败时返回 null</b>，由调用方把该频道从节目列表中剔除，
     * 避免用户点进去只能黑屏/转圈。原先只要测过的线路都失败就剔除整个频道，
     * 配额外从未测过、可能可用的线路被一并丢掉。
     */
    private String pickBestSource(LiveChannel channel, long roundStart, long abortedAt, boolean byScore) {
        List<String> urls = channel.getSourceUrls();
        if (urls == null || urls.isEmpty()) return null;

        String bestUrl = null;
        SpeedTestResult best = null;
        // 无法测速但可能可播的线路（RTSP/RTMP）或本轮未测到的线路：取列表中靠前的一条
        String fallbackUrl = null;
        String blackUrl = null;         // 黑屏线路（能连上但没画面），最后才用
        // 广播/电台类频道本来没有画面，不按黑屏处理
        final boolean audio = LineQuality.isAudioChannel(channel);

        for (String url : urls) {
            int st = lineStatus(url, roundStart, abortedAt);
            if (st == ST_OK) {
                SpeedTestResult r = speedTestEngine.getCachedResult(url);
                if (r == null) {
                    if (!audio && LineQuality.isBlackLine(url, null)) {
                        if (blackUrl == null) blackUrl = url;
                    } else if (fallbackUrl == null) {
                        fallbackUrl = url;
                    }
                    continue;
                }
                if (!audio && LineQuality.isBlackLine(url, r)) {
                    if (blackUrl == null) blackUrl = url;
                    continue;
                }
                // 严格更优才替换：完全相同时保留列表中靠前的一条，与稳定排序一致
                if (best == null || compareAvailable(r, best, byScore) < 0) {
                    best = r;
                    bestUrl = url;
                }
            } else if (st == ST_UNTESTABLE || st == ST_UNTESTED) {
                if (!audio && LineQuality.isBlackLine(url, null)) {
                    // 播放时被标记为黑屏
                    if (blackUrl == null) blackUrl = url;
                } else if (fallbackUrl == null) {
                    fallbackUrl = url;
                }
            }
        }

        if (bestUrl != null) return bestUrl;
        // 无可用线路，但有无法测速的协议线路或本轮未测到的线路 → 保留它，避免误删
        if (fallbackUrl != null) return fallbackUrl;
        // 只剩黑屏线路 → 仍然保留（可能只是暂时没画面），不因此隐藏频道
        if (blackUrl != null) return blackUrl;
        // 用户置顶的线路（用户确认能播）→ 保留
        String pinned = pinnedUrl(channel);
        if (pinned != null && urls.contains(pinned)) return pinned;
        // 所有线路都在本轮明确测速失败 → 返回 null，调用方剔除该频道
        return null;
    }

    /**
     * 持久化本地播放列表：写入 Hawk 并落盘到 filesDir。
     *
     * @param stamp 是否刷新"生成时间"。列表在测速中途被替换、含未测速频道时传 false：
     *              内容照常保存（供数据库丢失时恢复），但不当作一次完整测速的结果。
     */
    private void saveLocalPlaylist(String content, boolean stamp) {
        if (content == null) content = "";
        // 完整内容只落盘到文件，Hawk 里只放小标记：v2 JSON 带全部备用线路，大列表可达 MB 级，
        // 而 Hawk 整个存在一份 SharedPreferences XML 里，启动时整份解析、任何 Hawk.put 都整份重写，
        // 低内存盒子上会明显卡顿/占内存。先写临时文件再改名，写到一半断电不会留下半截 JSON。
        boolean onDisk = false;
        try {
            File file = new File(context.getFilesDir(), HawkConfig.LOCAL_PLAYLIST_FILE_NAME);
            File tmp = new File(context.getFilesDir(), HawkConfig.LOCAL_PLAYLIST_FILE_NAME + ".tmp");
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(content.getBytes("UTF-8"));
                try {
                    fos.getFD().sync();
                } catch (Throwable ignore) {
                }
            }
            if (!tmp.renameTo(file)) {
                // 个别文件系统不允许覆盖式改名：删掉旧文件再改名
                file.delete();
                if (!tmp.renameTo(file)) throw new java.io.IOException("rename failed");
            }
            onDisk = true;
            Timber.d("本地播放列表已落盘: %s", file.getAbsolutePath());
        } catch (Exception e) {
            Timber.w(e, "本地播放列表落盘失败");
        }
        try {
            // 落盘失败（如存储已满）时退回把内容存进 Hawk，保证仍能恢复
            Hawk.put(HawkConfig.LIVE_LOCAL_PLAYLIST, onDisk ? LOCAL_PLAYLIST_FILE_MARKER : content);
            if (stamp) Hawk.put(HawkConfig.LIVE_LOCAL_PLAYLIST_TIME, System.currentTimeMillis());
        } catch (Exception e) {
            Timber.w(e, "写入本地播放列表到 Hawk 失败");
        }
    }

    /** 获取已持久化的最佳线路本地播放列表（无则返回空串）。内容只在文件中时读文件 */
    public String getLocalPlaylist() {
        String content = "";
        try {
            content = Hawk.get(HawkConfig.LIVE_LOCAL_PLAYLIST, "");
        } catch (Exception ignore) {
        }
        if (content == null || content.isEmpty() || LOCAL_PLAYLIST_FILE_MARKER.equals(content)) {
            String fromFile = readLocalPlaylistFile();
            return fromFile != null ? fromFile : "";
        }
        return content;
    }

    /**
     * 完整读取本地列表文件。原先单次 read 且截断到 8MB：JSON 被截断/短读后整体解析失败。
     * 这里循环读到 EOF；超过上限视为异常文件直接放弃（不读半截）。
     */
    private String readLocalPlaylistFile() {
        try {
            File file = new File(context.getFilesDir(), HawkConfig.LOCAL_PLAYLIST_FILE_NAME);
            if (!file.exists()) return null;
            long len = file.length();
            if (len <= 0) return null;
            if (len > LOCAL_PLAYLIST_MAX_FILE_BYTES) {
                Timber.w("本地播放列表文件过大(%d 字节)，放弃读取", len);
                return null;
            }
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream((int) len);
            byte[] buf = new byte[64 * 1024];
            try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
                int n;
                while ((n = fis.read(buf)) != -1) {
                    bos.write(buf, 0, n);
                    if (bos.size() > LOCAL_PLAYLIST_MAX_FILE_BYTES) return null;
                }
            }
            return bos.size() > 0 ? new String(bos.toByteArray(), "UTF-8") : null;
        } catch (Throwable e) {
            // 含低内存下的 OOM
            Timber.w(e, "读取本地播放列表文件失败");
            return null;
        }
    }

    /**
     * 从已持久化的本地播放列表（Hawk 内容，或落盘文件）恢复频道分组。
     * 用于数据库为空但本地列表仍存在时的兜底复用，避免必须联网重新拉取。
     */
    private List<ChannelGroup> restoreFromLocalPlaylist() {
        // Hawk 内容为文件标记或缺失时读取落盘文件（见 getLocalPlaylist）
        String content = getLocalPlaylist();
        if (content == null || content.trim().isEmpty()) return null;
        try {
            // v2 为 JSON（含编号/EPG id/台标/备用线路）；旧版为 TXT 格式（分组,#genre# + 频道名,URL）
            List<ChannelGroup> restored = content.trim().startsWith("{")
                    ? parseLocalPlaylistJson(content)
                    : new LiveSourceParser().parseTxt(content);
            if (restored == null) return null;
            // 兜底清理历史本地列表中可能残留的不支持协议线路
            com.github.tvbox.osc.util.ProtocolFilter.filter(restored);
            return restored;
        } catch (Exception e) {
            Timber.w(e, "解析本地播放列表失败");
            return null;
        }
    }

    /**
     * 解析 v2（JSON）本地列表。格式不符时返回 null。
     * 频道名原样保留（不做名称标准化），保证置顶/拉黑等按"分组+名称"保存的偏好仍能匹配。
     */
    static List<ChannelGroup> parseLocalPlaylistJson(String content) throws org.json.JSONException {
        org.json.JSONObject root = new org.json.JSONObject(content.trim());
        if (!LOCAL_PLAYLIST_JSON_FORMAT.equals(jsonString(root, "format"))) return null;
        org.json.JSONArray groups = root.optJSONArray("groups");
        List<ChannelGroup> result = new ArrayList<>();
        if (groups == null) return result;
        for (int gi = 0; gi < groups.length(); gi++) {
            org.json.JSONObject go = groups.optJSONObject(gi);
            if (go == null) continue;
            String groupName = jsonString(go, "name");
            if (groupName == null || groupName.isEmpty()) groupName = "未分类";
            ChannelGroup group = new ChannelGroup(groupName, result.size());
            org.json.JSONArray chs = go.optJSONArray("channels");
            if (chs == null) continue;
            for (int ci = 0; ci < chs.length(); ci++) {
                org.json.JSONObject co = chs.optJSONObject(ci);
                if (co == null) continue;
                String name = jsonString(co, "name");
                if (name == null || name.trim().isEmpty()) continue;
                LiveChannel ch = new LiveChannel(name, groupName);
                String num = jsonString(co, "num");
                if (num != null) ch.setChannelNum(num);
                String epg = jsonString(co, "epg");
                if (epg != null) ch.setEpgId(epg);
                String logo = jsonString(co, "logo");
                if (logo != null) ch.setLogoUrl(logo);
                org.json.JSONArray urls = co.optJSONArray("urls");
                if (urls != null) {
                    for (int ui = 0; ui < urls.length(); ui++) {
                        // 先判类型：AOSP 的 optString 对 null 值会返回 "null"
                        Object u = urls.opt(ui);
                        if (u instanceof String) ch.addSourceUrl((String) u);
                    }
                }
                if (ch.getSourceCount() > 0) group.addChannel(ch);
            }
            if (group.getChannelCount() > 0) {
                group.setGroupIndex(result.size());
                result.add(group);
            }
        }
        return result;
    }

    /** 读取字符串字段；缺失或非字符串（含 JSON null）返回 null */
    private static String jsonString(org.json.JSONObject o, String key) {
        Object v = o.opt(key);
        return v instanceof String ? (String) v : null;
    }

    /**
     * 是否已存在测速生成的本地播放列表。
     * 作为"默认使用本地列表 / 否则提醒测速"的判定依据。
     */
    public boolean hasLocalPlaylist() {
        try {
            long time = Hawk.get(HawkConfig.LIVE_LOCAL_PLAYLIST_TIME, 0L);
            if (time <= 0) return false;
            // 只读 Hawk 中的小标记 + 文件是否存在，不加载整份列表内容（启动/界面会多次调用）
            String content = Hawk.get(HawkConfig.LIVE_LOCAL_PLAYLIST, "");
            if (content == null || content.isEmpty()) return false;
            if (!LOCAL_PLAYLIST_FILE_MARKER.equals(content)) return true; // 旧版或落盘失败时的内嵌内容
            File file = new File(context.getFilesDir(), HawkConfig.LOCAL_PLAYLIST_FILE_NAME);
            return file.exists() && file.length() > 0;
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
            File tmp = new File(context.getFilesDir(), HawkConfig.LOCAL_PLAYLIST_FILE_NAME + ".tmp");
            if (tmp.exists()) tmp.delete();
        } catch (Exception e) {
            Timber.w(e, "删除本地播放列表文件失败");
        }
    }

    // ============ 私有方法 ============

    private void fetchAndUpdateChannels(ChannelCallback callback) {
        final String fp = sourceConfigFingerprint();
        iptvApiService.fetchAndMergeSources(new IptvApiService.FetchCallback() {
            @Override
            public void onSuccess(List<ChannelGroup> groups, int totalChannels) {
                applyRefreshedGroups(groups, fp);
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
