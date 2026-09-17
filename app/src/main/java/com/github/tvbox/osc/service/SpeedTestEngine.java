package com.github.tvbox.osc.service;

import com.github.tvbox.osc.bean.LiveChannel;
import com.github.tvbox.osc.bean.SpeedTestResult;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.StreamQualityScorer;
import com.orhanobut.hawk.Hawk;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import timber.log.Timber;

/**
 * 直播源测速引擎
 * 
 * 功能：
 * 1. 并发测速 - 多线程同时测试多个源
 * 2. 增量测速 - 仅测试未测试或过期的源
 * 3. 速度评估 - 通过下载数据计算实际带宽
 * 4. 延迟检测 - 测量连接延迟
 * 5. 可用性检测 - 验证源是否可用
 * 6. 支持取消 - 可随时取消正在进行的测速
 */
public class SpeedTestEngine {

    private static volatile SpeedTestEngine instance;

    private ExecutorService executorService;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicBoolean isCancelled = new AtomicBoolean(false);
    private final AtomicInteger completedCount = new AtomicInteger(0);
    private int totalCount = 0;

    /** 测速结果缓存最大条数（LRU，防止长时间运行后内存无界增长） */
    private static final int MAX_RESULT_CACHE = 4000;

    /** 单轮测速最多测试的线路总数（按频道数均摊到每个频道） */
    private static final int MAX_TEST_LINES = 2000;

    /**
     * 失败分类：无法用 HTTP 测速（RTSP/RTMP 等流式协议）。
     * 这类线路仍可播放，测速后不应作为"不可用线路"被移除。
     */
    public static final String UNTESTABLE = "UNTESTABLE";

    /** 当前测速工作线程（用于"重新测速"时等待上一轮结束） */
    private volatile Thread runnerThread;

    /** 是否处于"取消旧任务以重启新一轮"状态：此时不派发 onCancelled，避免UI 误判为已结束 */
    private volatile boolean restarting;

    /**
     * 测速结果缓存（LRU，按访问顺序淘汰最久未用项）。
     * 相比无界 Map，可避免超大订阅源多轮测速后内存持续攀升。
     */
    private final java.util.Map<String, SpeedTestResult> resultCache =
            java.util.Collections.synchronizedMap(
                    new java.util.LinkedHashMap<String, SpeedTestResult>(256, 0.75f, true) {
                        @Override
                        protected boolean removeEldestEntry(
                                java.util.Map.Entry<String, SpeedTestResult> eldest) {
                            return size() > MAX_RESULT_CACHE;
                        }
                    });

    /** 上次持久化时间戳（节流控制） */
    private volatile long lastPersistTime = 0;
    /** 上次持久化后新增/更新的结果数（用于按条数增量触发） */
    private final AtomicInteger dirtyCount = new AtomicInteger(0);
    /**
     * 增量持久化触发阈值：每 N 条结果保存一次。
     *
     * 每次保存都要全量重建最多 SPEED_TEST_RESULT_CACHE_MAX 条 JSON 并写 Hawk，
     * 阈值过小会在一轮测速（可达 2000 条线路）中触发数百次全量序列化，
     * 在低内存盒子上造成明显的 GC 压力与卡顿。
     */
    private static final int PERSIST_ITEM_INTERVAL = 50;
    /** 增量持久化触发阈值：每 N 毫秒最多保存一次（避免 Hawk 写入抖动） */
    private static final long PERSIST_TIME_INTERVAL_MS = 10000;

    /** 测速回调接口 */
    public interface SpeedTestCallback {
        /** 单个源测速完成 */
        void onItemComplete(SpeedTestResult result, int current, int total);
        /** 所有测速完成 */
        void onAllComplete(List<SpeedTestResult> results, long totalTime);
        /** 测速出错 */
        void onError(String errorMsg);
        /** 测速进度更新 */
        void onProgress(int current, int total, String currentChannel);
        /** 测速被取消 */
        void onCancelled(int completed, int total);
    }

    private SpeedTestEngine() {
        loadCacheFromHawk();
    }

    /** 从 Hawk 加载持久化的测速结果缓存（冷启动即可使用历史评分/最佳源判断） */
    private void loadCacheFromHawk() {
        try {
            String json = Hawk.get(HawkConfig.SPEED_TEST_RESULT_CACHE, "");
            if (json == null || json.isEmpty()) return;
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                SpeedTestResult r = new SpeedTestResult(
                        o.optString("url"), o.optString("channelName"));
                r.setSpeed(o.optDouble("speed", 0));
                r.setLatency(o.optLong("latency", 0));
                r.setAvailable(o.optBoolean("available", false));
                r.setResolution(o.optString("resolution", null));
                r.setTestTime(o.optLong("testTime", 0));
                r.setStatus(o.optInt("status", 0));
                r.setBandwidth(o.optLong("bandwidth", 0));
                r.setCodecs(o.optString("codecs", null));
                r.setQualityScore(o.optInt("qualityScore", 0));
                r.setFailureType(o.optString("failureType", null));
                if (r.getUrl() != null && !r.getUrl().isEmpty()) {
                    resultCache.put(r.getUrl(), r);
                }
            }
            Timber.d("从持久化恢复测速缓存: %d 条", resultCache.size());
        } catch (Exception e) {
            Timber.w(e, "加载测速缓存失败");
        }
    }

    /**
     * 增量持久化：满足 条数阈值 或 时间阈值 时才实际写入 Hawk，
     * 用于测速进行中断点保存，避免中途取消/崩溃丢失结果。
     */
    private void persistCacheIncremental() {
        int dirty = dirtyCount.incrementAndGet();
        long now = System.currentTimeMillis();
        if (lastPersistTime == 0L) {
            // 首次调用时把基准时间设为当前，否则 now-0 必然超过时间阈值，
            // 导致每条结果都触发一次全量落盘
            lastPersistTime = now;
        }
        if (dirty >= PERSIST_ITEM_INTERVAL
                || (now - lastPersistTime) >= PERSIST_TIME_INTERVAL_MS) {
            persistCacheToHawk();
        }
    }

    /** 把当前 resultCache 持久化到 Hawk（LRU 上限 SPEED_TEST_RESULT_CACHE_MAX） */
    private synchronized void persistCacheToHawk() {
        try {
            dirtyCount.set(0);
            lastPersistTime = System.currentTimeMillis();
            // synchronizedMap 的迭代需显式加锁，避免并发写入时 ConcurrentModificationException
            List<SpeedTestResult> list;
            synchronized (resultCache) {
                list = new ArrayList<>(resultCache.values());
            }
            // 排序只用于超限时保留最新，未超限时可跳过，省掉一次全量排序
            int max = HawkConfig.SPEED_TEST_RESULT_CACHE_MAX;
            if (list.size() > max) {
                // 按测试时间降序，超过上限时保留最新
                // 用 Collections.sort 而非 List#sort（后者为 API 24，Android 5/6 会崩）
                java.util.Collections.sort(list, (a, b) -> Long.compare(b.getTestTime(), a.getTestTime()));
            }
            int limit = Math.min(list.size(), max);
            JSONArray arr = new JSONArray();
            for (int i = 0; i < limit; i++) {
                SpeedTestResult r = list.get(i);
                JSONObject o = new JSONObject();
                o.put("url", r.getUrl());
                o.put("channelName", r.getChannelName());
                o.put("speed", r.getSpeed());
                o.put("latency", r.getLatency());
                o.put("available", r.isAvailable());
                if (r.getResolution() != null) o.put("resolution", r.getResolution());
                o.put("testTime", r.getTestTime());
                o.put("status", r.getStatus());
                o.put("bandwidth", r.getBandwidth());
                if (r.getCodecs() != null) o.put("codecs", r.getCodecs());
                o.put("qualityScore", r.getQualityScore());
                if (r.getFailureType() != null) o.put("failureType", r.getFailureType());
                arr.put(o);
            }
            Hawk.put(HawkConfig.SPEED_TEST_RESULT_CACHE, arr.toString());
        } catch (Exception e) {
            Timber.w(e, "持久化测速缓存失败");
        }
    }

    public static SpeedTestEngine getInstance() {
        if (instance == null) {
            synchronized (SpeedTestEngine.class) {
                if (instance == null) {
                    instance = new SpeedTestEngine();
                }
            }
        }
        return instance;
    }

    /**
     * 对单个频道的所有源进行测速
     */
    public void testChannel(LiveChannel channel, SpeedTestCallback callback) {
        if (channel == null || channel.getSourceCount() == 0) {
            if (callback != null) callback.onError("频道无可用源");
            return;
        }

        List<LiveChannel> channels = new ArrayList<>();
        channels.add(channel);
        testChannels(channels, callback);
    }

    /**
     * 对多个频道的所有源进行测速
     */
    public void testChannels(List<LiveChannel> channels, SpeedTestCallback callback) {
        // 已有测速在进行：取消它并等待结束，然后从头开始新一轮
        // （用户再次点击"立即测速"时应重新开始，而不是被拒绝）
        if (isRunning.get()) {
            Timber.i("已有测速任务在运行，取消后重新开始");
            restarting = true;
            cancel();
            Thread prev = runnerThread;
            if (prev != null && prev != Thread.currentThread()) {
                try {
                    prev.join(3000);
                } catch (InterruptedException ignore) {
                    Thread.currentThread().interrupt();
                }
            }
            // 兜底等待运行标志归位，避免两轮测速叠加
            long deadline = System.currentTimeMillis() + 2000;
            while (isRunning.get() && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ignore) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        if (channels == null || channels.isEmpty()) {
            restarting = false;
            if (callback != null) callback.onError("没有需要测速的频道");
            return;
        }

        isRunning.set(true);
        isCancelled.set(false);
        completedCount.set(0);

        // 收集所有需要测试的URL。
        // 为控制单轮测速时长，总线路数以 MAX_TEST_LINES 为上限，按频道数均摊每个频道的线路配额，
        // 保证每个频道至少测1 条，不会整段跳过后面的频道。
        int channelCount = channels.size();
        int perChannel = Math.max(1, MAX_TEST_LINES / Math.max(1, channelCount));
        List<SpeedTestTask> tasks = new ArrayList<>();
        for (LiveChannel channel : channels) {
            if (channel == null || channel.getSourceUrls() == null) continue;
            int size = Math.min(channel.getSourceUrls().size(), perChannel);
            for (int i = 0; i < size; i++) {
                String url = channel.getSourceUrls().get(i);
                if (url != null && !url.isEmpty()) {
                    tasks.add(new SpeedTestTask(channel, url, i));
                }
            }
        }

        totalCount = tasks.size();
        Timber.i("开始测速: %d 个频道, %d 个源(每频道上限 %d)", channelCount, totalCount, perChannel);

        if (totalCount == 0) {
            isRunning.set(false);
            restarting = false;
            if (callback != null) callback.onError("没有可测速的线路");
            return;
        }

        // 获取配置（对并发数做下限/上限兜底，防止 Hawk 脏数据导致
        // Executors.newFixedThreadPool 抛 IllegalArgumentException）
        int concurrencyRaw = Hawk.get(HawkConfig.SPEED_TEST_CONCURRENCY, HawkConfig.DEFAULT_SPEED_TEST_CONCURRENCY);
        // 并发数兜底 + 低内存设备自动降级：每个测速线程都有独立堆栈和下载缓冲区，
        // 低内存机型（小米盒子等）并发过高会叠加内存峰值导致 OOM/闪退。
        final int concurrency = Math.max(1, Math.min(concurrencyRaw, adaptiveConcurrencyCap()));
        int timeoutRaw = Hawk.get(HawkConfig.SPEED_TEST_TIMEOUT, HawkConfig.DEFAULT_SPEED_TEST_TIMEOUT);
        final int timeout = timeoutRaw > 0 ? timeoutRaw : HawkConfig.DEFAULT_SPEED_TEST_TIMEOUT;

        executorService = Executors.newFixedThreadPool(concurrency);
        // 新一轮已就绪，解除重启抑制标志
        restarting = false;

        runnerThread = new Thread(() -> {
            long startTime = System.currentTimeMillis();
            List<SpeedTestResult> allResults = Collections.synchronizedList(new ArrayList<>());
            List<Future<?>> futures = new ArrayList<>();

            try {
                for (SpeedTestTask task : tasks) {
                    if (isCancelled.get()) break;

                    Future<?> future = executorService.submit(() -> {
                        // 单个任务完全隔离：任何异常都不允许扩散到线程池，避免"一个源坏了拖垮整轮测速"
                        try {
                            if (isCancelled.get()) return;

                            // 首次测速
                            SpeedTestResult result = performSpeedTest(task.url, task.channel.getChannelName(), timeout);

                            // 智能重试（对齐 ISEP：仅对超时失败自动重试，避免对确定无效源浪费资源）
                            int maxRetry = Hawk.get(HawkConfig.SPEED_TEST_RETRY, HawkConfig.DEFAULT_SPEED_TEST_RETRY);
                            int retry = 0;
                            while (retry < maxRetry && !isCancelled.get() && result.isTimeoutFailure()) {
                                retry++;
                                Timber.d("超时重试 %d/%d: %s", retry, maxRetry, task.url);
                                result = performSpeedTest(task.url, task.channel.getChannelName(), timeout);
                                result.setRetried(true);
                            }

                            // 计算综合评分
                            try { StreamQualityScorer.score(result); } catch (Exception ignore) {}

                            allResults.add(result);

                            // 更新频道速度信息
                            try {
                                synchronized (task.channel) {
                                    if (task.channel.getSpeeds() != null && task.sourceIndex < task.channel.getSpeeds().size()) {
                                        task.channel.getSpeeds().set(task.sourceIndex, result.getSpeed());
                                    }
                                }
                            } catch (Exception ignore) {}

                            // 缓存结果并触发增量持久化（中途取消/崩溃也能保留已完成的部分结果）
                            resultCache.put(task.url, result);
                            try { persistCacheIncremental(); } catch (Exception ignore) {}

                            int completed = completedCount.incrementAndGet();
                            if (callback != null) {
                                try {
                                    callback.onItemComplete(result, completed, totalCount);
                                    callback.onProgress(completed, totalCount, task.channel.getChannelName());
                                } catch (Exception ex) {
                                    Timber.w(ex, "测速回调异常");
                                }
                            }
                        } catch (Throwable t) {
                            // 兜底：包含 OOM / VirtualMachineError 也不让线程池挂掉
                            Timber.e(t, "测速任务未捕获异常: %s", task.url);
                            int completed = completedCount.incrementAndGet();
                            // 异常任务也要上报进度，否则这些线路的进度永久缺失，
                            // UI 进度条会停在不足 100% 的位置直到测速结束
                            if (callback != null) {
                                try {
                                    callback.onProgress(completed, totalCount,
                                            task.channel != null ? task.channel.getChannelName() : "");
                                } catch (Exception ignore) {}
                            }
                        }
                    });
                    futures.add(future);
                }

                // 等待所有任务完成
                // HLS 单源会嗅探播放列表并采样下载多个分片，整体最长约 timeout*(分片采样+1)，
                // 这里给出充足的等待上限，避免误判超时
                long awaitSeconds = (long) timeout * (MAX_SEGMENT_SAMPLE + 1) + 10;
                for (Future<?> future : futures) {
                    try {
                        future.get(awaitSeconds, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        Timber.w("测速任务超时或失败");
                    }
                }

                long totalTime = System.currentTimeMillis() - startTime;

                // 更新频道的最佳速度/评分/分辨率，并按配置模式排序
                boolean sortSources = Hawk.get(HawkConfig.SPEED_SORT_SOURCES, true);
                int sortMode = Hawk.get(HawkConfig.SPEED_SORT_MODE, HawkConfig.SORT_MODE_QUALITY_SCORE);
                boolean filterUnavailable = Hawk.get(HawkConfig.SPEED_FILTER_UNAVAILABLE, true);
                for (LiveChannel channel : channels) {
                    if (filterUnavailable) {
                        try { removeUnavailableSources(channel); } catch (Exception ignore) {}
                    }
                    updateChannelBestMetrics(channel);
                    if (sortSources) {
                        if (sortMode == HawkConfig.SORT_MODE_QUALITY_SCORE) {
                            sortChannelByScores(channel);
                        } else {
                            channel.sortSourcesBySpeed();
                        }
                    }
                    channel.setLastSpeedTestTime(System.currentTimeMillis());
                }

                // 记录测速时间 & 持久化缓存
                Hawk.put(HawkConfig.SPEED_TEST_LAST_TIME, System.currentTimeMillis());
                persistCacheToHawk();

                // 回调前对结果做快照拷贝，避免调用方遍历时后台仍有 in-flight 任务写入导致
                // ConcurrentModificationException；回调本身用 try/catch 包裹，防止调用方异常
                // 影响引擎收尾（persist/shutdown）。
                List<SpeedTestResult> snapshot;
                synchronized (allResults) {
                    snapshot = new ArrayList<>(allResults);
                }
                try {
                    if (isCancelled.get()) {
                        // 因"重新测速"而取消时不派发 onCancelled，
                        // 否则UI 会先收到"已取消"而隐藏进度，随后新一轮才开始，造成闪跳
                        if (callback != null && !restarting) {
                            callback.onCancelled(completedCount.get(), totalCount);
                        }
                    } else {
                        if (callback != null) {
                            callback.onAllComplete(snapshot, totalTime);
                        }
                    }
                } catch (Throwable cbErr) {
                    Timber.e(cbErr, "测速完成回调执行异常");
                }

                Timber.i("测速完成: %d/%d, 耗时 %d ms", completedCount.get(), totalCount, totalTime);

            } catch (Exception e) {
                Timber.e(e, "测速引擎异常");
                try {
                    if (callback != null) {
                        callback.onError("测速异常: " + e.getMessage());
                    }
                } catch (Throwable ignore) {
                    Timber.w(ignore, "onError 回调异常");
                }
            } finally {
                // 无论正常完成 / 取消 / 异常，都强制把已完成的结果落盘，避免丢失
                try {
                    persistCacheToHawk();
                } catch (Exception ex) {
                    Timber.w(ex, "最终持久化测速缓存失败");
                }
                isRunning.set(false);
                shutdownExecutor();
            }
        }, "SpeedTestRunner");
        runnerThread.start();
    }

    /**
     * 取消正在进行的测速
     */
    public void cancel() {
        if (isRunning.get()) {
            isCancelled.set(true);
            Timber.i("用户取消测速");
        }
    }

    /**
     * 判断是否正在测速
     */
    public boolean isRunning() {
        return isRunning.get();
    }

    /**
     * 判断当前是否已超过测速周期（需要测速）
     */
    public boolean needsSpeedTest() {
        boolean enabled = Hawk.get(HawkConfig.SPEED_TEST_ENABLED, true);
        if (!enabled) return false;

        int mode = Hawk.get(HawkConfig.SPEED_TEST_MODE, HawkConfig.SPEED_MODE_MANUAL);
        // 仅手动模式不主动判断周期
        if (mode == HawkConfig.SPEED_MODE_MANUAL) return false;

        long lastTime = Hawk.get(HawkConfig.SPEED_TEST_LAST_TIME, 0L);
        int intervalHours = Hawk.get(HawkConfig.SPEED_TEST_INTERVAL, HawkConfig.DEFAULT_SPEED_TEST_INTERVAL);
        long intervalMillis = intervalHours * 3600L * 1000L;

        return (System.currentTimeMillis() - lastTime) >= intervalMillis;
    }

    /**
     * 判断打开应用时是否应该弹窗提示用户测速
     * 条件：处于"周期性打开时提示"模式 且 已超过测速周期
     */
    public boolean shouldPromptOnLaunch() {
        boolean enabled = Hawk.get(HawkConfig.SPEED_TEST_ENABLED, true);
        if (!enabled) return false;
        int mode = Hawk.get(HawkConfig.SPEED_TEST_MODE, HawkConfig.SPEED_MODE_MANUAL);
        if (mode != HawkConfig.SPEED_MODE_PERIODIC_PROMPT) return false;
        return needsSpeedTest();
    }

    /**
     * 获取上次测速距今的时间描述
     */
    public String getLastTestTimeDescription() {
        long lastTime = Hawk.get(HawkConfig.SPEED_TEST_LAST_TIME, 0L);
        if (lastTime == 0) return "从未测速";

        long diff = System.currentTimeMillis() - lastTime;
        long minutes = diff / (60 * 1000);
        long hours = minutes / 60;
        long days = hours / 24;

        if (days > 0) return days + " 天前";
        if (hours > 0) return hours + " 小时前";
        if (minutes > 0) return minutes + " 分钟前";
        return "刚刚";
    }

    /**
     * 获取缓存的测速结果
     */
    public SpeedTestResult getCachedResult(String url) {
        return resultCache.get(url);
    }

    /**
     * 清除测速缓存（内存 + 持久化）。
     *
     * 必须同时清除 Hawk 中的持久化结果：否则下次启动时
     * {@link #loadCacheFromHawk()} 会把旧结果重新读回内存，
     * "清除测速缓存"等于没生效——被判定为不可用而隐藏的频道也无法恢复显示。
     */
    public void clearCache() {
        resultCache.clear();
        // 重置增量持久化状态，避免残留的脏计数在下轮测速立即触发落盘
        dirtyCount.set(0);
        lastPersistTime = 0L;
        try {
            Hawk.delete(HawkConfig.SPEED_TEST_RESULT_CACHE);
        } catch (Exception e) {
            Timber.w(e, "清除持久化测速缓存失败");
        }
    }

    // ============ 私有方法 ============

    // ============ 测速核心（参照 Guovin/iptv-api 实现）============

    private static final String USER_AGENT = "okhttp/4.12.0";
    /** 最少测量时长（毫秒） */
    private static final long MIN_MEASURE_MS = 1000;
    /** 稳定性判断窗口（最近 N 个瞬时速度采样） */
    private static final int STABILITY_WINDOW = 4;
    /** 稳定性波动阈值（窗口极差/均值低于此值即认为稳定） */
    private static final double STABILITY_THRESHOLD = 0.15;
    /** 至少下载字节数 */
    private static final long MIN_BYTES = 64 * 1024;
    /** 单个 HLS 媒体列表最多采样的分片数 */
    private static final int MAX_SEGMENT_SAMPLE = 3;

    /**
     * 执行单个 URL 的测速。
     *
     * 关键点（对齐 iptv-api）：
     * 1. 大多数直播源是 HLS(.m3u8) 播放列表，本身只是几 KB 文本，
     *    直接下载它无法反映真实视频码流速度。需解析 m3u8 → 选最高码率变体
     *    → 采样下载其中的 TS 分片，用分片的总字节/总耗时计算真实吞吐。
     * 2. 普通直链则用"稳定性窗口提前退出"测量下载速度。
     * 3. 延迟取首响应（首字节）时间；测速失败/无效记为不可用。
     *
     * @param timeoutSeconds 单源测速总预算（秒）
     */
    private SpeedTestResult performSpeedTest(String urlStr, String channelName, int timeoutSeconds) {
        SpeedTestResult result = new SpeedTestResult(urlStr, channelName);
        result.setStatus(1);

        int budget = Math.max(2, timeoutSeconds);
        long deadline = System.currentTimeMillis() + budget * 1000L;
        String url = cleanUrl(urlStr);

        // 仅 HTTP(S) 可下载测速；RTSP/RTMP 是流式协议，无法用 HTTP 拉取片段测速。
        // 标记为"不可测速"而非"不可用"，这类线路仍可播放，不应在测速后被移除。
        if (!com.github.tvbox.osc.util.ProtocolFilter.isSpeedTestable(url)) {
            result.setAvailable(false);
            result.setStatus(3);
            result.setFailureType(UNTESTABLE);
            result.setErrorMsg("流式协议，跳过测速");
            result.setTestTime(System.currentTimeMillis());
            return result;
        }

        try {
            measure(url, deadline, result);
            // 可用性判定：速度须 > 0，且不低于配置的最低速度阈值（KB/s）。
            // 阈值为 0 时不做下限过滤，仅要求能测得速度。
            int minSpeed = Hawk.get(HawkConfig.SPEED_MIN_THRESHOLD, HawkConfig.DEFAULT_SPEED_MIN_THRESHOLD);
            boolean available = result.getSpeed() > 0
                    && (minSpeed <= 0 || result.getSpeed() >= minSpeed);
            result.setAvailable(available);
            result.setStatus(available ? 2 : 3);
            if (!available && result.getSpeed() > 0) {
                result.setErrorMsg("速度低于阈值 " + minSpeed + " KB/s");
            }
            Timber.d("测速完成: %s - %.1f KB/s (阈值 %d), 延迟 %d ms, %s",
                    channelName, result.getSpeed(), minSpeed, result.getLatency(),
                    result.getResolution() != null ? result.getResolution() : "?");
        } catch (Exception e) {
            result.setAvailable(false);
            result.setStatus(3);
            if (result.getLatency() <= 0) result.setLatency(-1);
            result.setErrorMsg(e.getMessage());
            result.setFailureType(classifyFailure(e));
            Timber.d("测速失败: %s - %s (%s)", channelName, e.getMessage(), result.getFailureType());
        } finally {
            result.setTestTime(System.currentTimeMillis());
        }
        return result;
    }

    /** 将异常归类为 TIMEOUT / HTTP_ERROR / DNS / OTHER，用于智能重试策略 */
    private String classifyFailure(Exception e) {
        if (e == null) return "OTHER";
        if (e instanceof SocketTimeoutException) return "TIMEOUT";
        String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        if (msg.contains("timeout") || msg.contains("timed out")) return "TIMEOUT";
        if (msg.contains("http ")) return "HTTP_ERROR";
        if (msg.contains("unknownhost") || msg.contains("unable to resolve")) return "DNS";
        return "OTHER";
    }

    /**
     * 打开首个请求：测延迟、嗅探是否为 m3u8；
     * 若为 HLS 则解析并采样测分片，否则按直链测速。
     */
    private void measure(String url, long deadline, SpeedTestResult result) throws Exception {
        HttpURLConnection conn = null;
        InputStream in = null;
        try {
            conn = openConnection(url, remainingMs(deadline));

            long t0 = System.currentTimeMillis();
            int code = conn.getResponseCode();
            long delay = System.currentTimeMillis() - t0;
            result.setLatency(delay);
            result.setConnectTimeout(delay);

            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                result.setLatency(-1);
                throw new Exception("HTTP " + code);
            }

            String contentType = conn.getContentType();
            String finalUrl = conn.getURL().toString(); // 已跟随重定向

            in = conn.getInputStream();

            // 嗅探首块，判断是否为 m3u8 文本
            byte[] head = new byte[8192];
            int headLen = in.read(head);
            boolean isM3u8 = looksLikeM3u8(contentType, finalUrl)
                    || (headLen > 0 && startsWithExtm3u(head, headLen));

            if (isM3u8) {
                // 读取完整播放列表文本（很小），再去测真实分片
                String playlist = readRemainingAsText(head, headLen, in, deadline);
                closeQuietly(in);
                conn.disconnect();
                in = null;
                conn = null;
                measureHls(finalUrl, playlist, deadline, result);
            } else {
                // 直链：用当前已建立的流继续测速（含已读的 head 字节）
                double speed = measureStream(in, head, headLen, deadline);
                result.setSpeed(speed);
            }
        } finally {
            closeQuietly(in);
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * HLS 测速：解析播放列表 → 若为主列表选最高带宽变体 → 采样下载 TS 分片 → 计算吞吐
     */
    private void measureHls(String playlistUrl, String content, long deadline, SpeedTestResult result) throws Exception {
        if (content == null || !content.contains("#EXTM3U")) {
            throw new Exception("无效的 m3u8 播放列表");
        }

        // 主播放列表（多码率）：选 BANDWIDTH 最大的变体，再取其媒体列表；同时抽取分辨率/编码
        if (content.contains("#EXT-X-STREAM-INF")) {
            VariantInfo best = pickBestVariantInfo(playlistUrl, content);
            if (best != null) {
                result.setBandwidth(best.bandwidth);
                if (best.resolution != null) result.setResolution(best.resolution);
                if (best.codecs != null) result.setCodecs(best.codecs);
                if (best.url != null) {
                    String mediaPlaylist = fetchText(best.url, deadline);
                    playlistUrl = best.url;
                    content = mediaPlaylist;
                }
            }
        }

        List<String> segments = parseSegments(playlistUrl, content);
        if (segments.isEmpty()) {
            throw new Exception("m3u8 无可用分片");
        }

        List<String> sampled = sampleSegments(segments, MAX_SEGMENT_SAMPLE);

        long totalBytes = 0;
        long totalTimeMs = 0;
        for (String seg : sampled) {
            if (remainingMs(deadline) <= 0) break;
            long[] r = downloadSegment(seg, deadline); // {bytes, timeMs}
            totalBytes += r[0];
            totalTimeMs += r[1];
        }

        if (totalTimeMs > 0 && totalBytes > 0) {
            double speedKBps = (totalBytes / 1024.0) / (totalTimeMs / 1000.0);
            result.setSpeed(speedKBps);
        } else {
            result.setSpeed(0);
        }
    }

    /**
     * 用"稳定性窗口提前退出"测量一个字节流的下载速度（KB/s）。
     *
     * @param prefix    已经预读的字节（计入总量）
     * @param prefixLen 预读长度
     */
    private double measureStream(InputStream in, byte[] prefix, int prefixLen, long deadline) throws Exception {
        byte[] buffer = new byte[16 * 1024];
        long totalBytes = Math.max(0, prefixLen);
        long start = System.currentTimeMillis();
        long lastTime = start;
        long lastBytes = totalBytes;
        List<Double> samples = new ArrayList<>();

        while (true) {
            if (System.currentTimeMillis() >= deadline) break;

            int n = in.read(buffer);
            if (n == -1) break;
            totalBytes += n;

            long now = System.currentTimeMillis();
            long dt = now - lastTime;
            if (dt >= 100) { // 每 ~100ms 采样一次瞬时速度
                double inst = (totalBytes - lastBytes) / 1024.0 / (dt / 1000.0); // KB/s
                samples.add(inst);
                lastTime = now;
                lastBytes = totalBytes;

                long elapsed = now - start;
                if (elapsed >= MIN_MEASURE_MS && totalBytes >= MIN_BYTES && samples.size() >= STABILITY_WINDOW) {
                    List<Double> window = samples.subList(samples.size() - STABILITY_WINDOW, samples.size());
                    double mean = 0;
                    double mn = Double.MAX_VALUE, mx = 0;
                    for (double s : window) {
                        mean += s;
                        mn = Math.min(mn, s);
                        mx = Math.max(mx, s);
                    }
                    mean /= window.size();
                    if (mean > 0 && (mx - mn) / mean < STABILITY_THRESHOLD) {
                        break; // 速度已稳定
                    }
                }
            }
        }

        long totalTime = System.currentTimeMillis() - start;
        if (totalTime <= 0) return 0;
        return (totalBytes / 1024.0) / (totalTime / 1000.0);
    }

    /** 下载单个分片（在剩余预算内尽量读完），返回 {bytes, timeMs} */
    private long[] downloadSegment(String segUrl, long deadline) {
        HttpURLConnection conn = null;
        InputStream in = null;
        long start = System.currentTimeMillis();
        long bytes = 0;
        try {
            conn = openConnection(segUrl, remainingMs(deadline));
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                return new long[]{0, 0};
            }
            in = conn.getInputStream();
            byte[] buffer = new byte[16 * 1024];
            while (System.currentTimeMillis() < deadline) {
                int n = in.read(buffer);
                if (n == -1) break;
                bytes += n;
            }
        } catch (Exception ignored) {
        } finally {
            closeQuietly(in);
            if (conn != null) conn.disconnect();
        }
        long time = System.currentTimeMillis() - start;
        return new long[]{bytes, time};
    }

    // ============ HLS / HTTP 辅助 ============

    private HttpURLConnection openConnection(String url, long remainMs) throws Exception {
        if (url == null || url.isEmpty()) throw new Exception("empty url");
        int connectTimeout = (int) Math.min(8000, Math.max(2000, remainMs));
        int readTimeout = (int) Math.min(15000, Math.max(2000, remainMs));
        URL u;
        try {
            u = new URL(url);
        } catch (Exception e) {
            throw new Exception("malformed url: " + e.getMessage());
        }
        HttpURLConnection conn = (HttpURLConnection) u.openConnection();
        conn.setConnectTimeout(connectTimeout);
        conn.setReadTimeout(readTimeout);
        conn.setRequestMethod("GET");
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setRequestProperty("Accept", "*/*");
        return conn;
    }

    /** 抓取文本（用于获取媒体播放列表），限制大小 */
    private String fetchText(String url, long deadline) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = openConnection(url, remainingMs(deadline));
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                throw new Exception("HTTP " + code);
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream()))) {
                StringBuilder sb = new StringBuilder();
                String line;
                int limit = 0;
                while ((line = reader.readLine()) != null && limit < 5000) {
                    sb.append(line).append('\n');
                    limit++;
                }
                return sb.toString();
            }
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 把已读 head 与剩余流合并读为文本（用于 m3u8 列表，体积很小） */
    private String readRemainingAsText(byte[] head, int headLen, InputStream in, long deadline) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        if (headLen > 0) bos.write(head, 0, headLen);
        byte[] buffer = new byte[8192];
        int n;
        // m3u8 一般 < 1MB，限制读取上限避免误把大文件当列表
        while (bos.size() < 1024 * 1024 && System.currentTimeMillis() < deadline
                && (n = in.read(buffer)) != -1) {
            bos.write(buffer, 0, n);
        }
        return bos.toString("UTF-8");
    }

    /** HLS 变体的元信息（用于测速时抽取分辨率/带宽/编码） */
    private static class VariantInfo {
        String url;
        long bandwidth;
        String resolution;
        String codecs;
    }

    /**
     * 选择主播放列表中带宽最高的变体，并返回其分辨率/编码/URL 等元信息。
     * 用于测速时把 HLS 声明的目标码率与分辨率并入评分。
     */
    private VariantInfo pickBestVariantInfo(String baseUrl, String content) {
        String[] lines = content.split("\\r?\\n");
        long bestBandwidth = -1;
        VariantInfo best = null;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                long bw = parseBandwidth(line);
                String res = parseAttr(line, "RESOLUTION");
                String codecs = parseAttr(line, "CODECS");
                for (int j = i + 1; j < lines.length; j++) {
                    String next = lines[j].trim();
                    if (next.isEmpty() || next.startsWith("#")) continue;
                    if (bw >= bestBandwidth) {
                        bestBandwidth = bw;
                        best = new VariantInfo();
                        best.url = resolveUrl(baseUrl, next);
                        best.bandwidth = bw;
                        best.resolution = res;
                        best.codecs = codecs;
                    }
                    break;
                }
            }
        }
        return best;
    }

    /** 解析 #EXT-X-STREAM-INF 行中的属性值（去除引号） */
    private String parseAttr(String line, String key) {
        try {
            Pattern p = Pattern.compile(key + "=(\"[^\"]*\"|[^,]+)", Pattern.CASE_INSENSITIVE);
            Matcher m = p.matcher(line);
            if (m.find()) {
                String v = m.group(1);
                if (v.startsWith("\"") && v.endsWith("\"") && v.length() >= 2) {
                    v = v.substring(1, v.length() - 1);
                }
                return v;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private long parseBandwidth(String streamInfLine) {
        try {
            int idx = streamInfLine.toUpperCase().indexOf("BANDWIDTH=");
            if (idx < 0) return 0;
            String sub = streamInfLine.substring(idx + "BANDWIDTH=".length());
            StringBuilder num = new StringBuilder();
            for (int i = 0; i < sub.length(); i++) {
                char c = sub.charAt(i);
                if (Character.isDigit(c)) num.append(c);
                else break;
            }
            return num.length() > 0 ? Long.parseLong(num.toString()) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /** 解析媒体播放列表中的分片 URL（绝对化） */
    private List<String> parseSegments(String baseUrl, String content) {
        List<String> segments = new ArrayList<>();
        for (String raw : content.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            segments.add(resolveUrl(baseUrl, line));
        }
        return segments;
    }

    /** 均匀采样分片（对齐 iptv-api 的 sample_segment_urls） */
    private List<String> sampleSegments(List<String> segments, int limit) {
        int total = segments.size();
        if (limit >= total) return segments;
        if (limit == 1) {
            List<String> one = new ArrayList<>();
            one.add(segments.get(total / 2));
            return one;
        }
        List<String> sampled = new ArrayList<>();
        for (int i = 0; i < limit; i++) {
            int idx = (int) Math.round((double) i * (total - 1) / (limit - 1));
            String s = segments.get(idx);
            if (!sampled.contains(s)) sampled.add(s);
        }
        return sampled;
    }

    private String resolveUrl(String baseUrl, String ref) {
        try {
            if (ref.startsWith("http://") || ref.startsWith("https://")) return ref;
            return URI.create(baseUrl).resolve(ref).toString();
        } catch (Exception e) {
            return ref;
        }
    }

    private boolean looksLikeM3u8(String contentType, String url) {
        if (contentType != null) {
            String ct = contentType.toLowerCase();
            if (ct.contains("mpegurl") || ct.contains("vnd.apple.mpegurl")) return true;
        }
        String u = url.toLowerCase();
        int q = u.indexOf('?');
        String path = q >= 0 ? u.substring(0, q) : u;
        return path.endsWith(".m3u8") || path.endsWith(".m3u");
    }

    private boolean startsWithExtm3u(byte[] data, int len) {
        String s = new String(data, 0, Math.min(len, 64)).trim();
        return s.startsWith("#EXTM3U");
    }

    /** 清洗 URL：去除部分源在末尾追加的 $描述 信息 */
    private String cleanUrl(String url) {
        if (url == null) return "";
        url = url.trim();
        int idx = url.indexOf('$');
        if (idx > 0) url = url.substring(0, idx);
        return url;
    }

    private long remainingMs(long deadline) {
        return Math.max(0, deadline - System.currentTimeMillis());
    }

    private void closeQuietly(InputStream in) {
        if (in != null) {
            try {
                in.close();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 更新频道最佳指标：最快速度、最优分辨率、最高综合评分。
     * 综合评分从测速缓存里查询同 URL 的结果。
     */
    private void updateChannelBestMetrics(LiveChannel channel) {
        if (channel.getSourceUrls() == null || channel.getSourceUrls().isEmpty()) return;

        double bestSpeed = 0;
        int bestScore = 0;
        String bestResolution = null;
        for (String url : channel.getSourceUrls()) {
            SpeedTestResult r = resultCache.get(url);
            if (r == null) continue;
            if (r.getSpeed() > bestSpeed) bestSpeed = r.getSpeed();
            if (r.getQualityScore() > bestScore) {
                bestScore = r.getQualityScore();
                if (r.getResolution() != null) bestResolution = r.getResolution();
            }
        }
        // 若评分未命中但 speeds 数组有值，仍保留旧速度回退
        if (bestSpeed == 0 && channel.getSpeeds() != null) {
            for (double s : channel.getSpeeds()) if (s > bestSpeed) bestSpeed = s;
        }
        channel.setBestSpeed(bestSpeed);
        channel.setBestQualityScore(bestScore);
        if (bestResolution != null) channel.setBestResolution(bestResolution);
    }

    /**
     * 移除本轮测速中标记为不可用（available=false）的线路。
     * 保护策略：若全部线路本轮测速均不可用，则不做任何移除，
     * 避免频道变为无源可播（可能是网络临时波动导致全部误判）。
     * 未在本轮测速结果中出现的 URL（未测试）保持不变。
     *
     * <p>注意这里"保留全部不可用线路"只是<b>保留数据</b>，便于下轮测速重试与
     * 线路管理界面查看；该频道在展示列表与本地节目列表中仍会被隐藏
     * （见 ChannelManager#hasPlayableSource / #pickBestSource），
     * 因此不会出现"点进去只能黑屏"的频道。
     */
    private void removeUnavailableSources(LiveChannel channel) {
        if (channel == null || channel.getSourceUrls() == null || channel.getSourceUrls().isEmpty()) return;
        List<String> urls = new ArrayList<>(channel.getSourceUrls());
        List<String> toRemove = new ArrayList<>();
        for (String url : urls) {
            SpeedTestResult r = resultCache.get(url);
            // UNTESTABLE（RTSP/RTMP 等流式协议）只是无法测速，线路本身可能可播，不能删
            if (r != null && !r.isAvailable() && !UNTESTABLE.equals(r.getFailureType())) {
                toRemove.add(url);
            }
        }
        if (toRemove.isEmpty()) return;
        if (toRemove.size() >= urls.size()) {
            Timber.w("频道 %s 全部线路测速不可用，保留原有线路避免无源可播", channel.getChannelName());
            return;
        }
        for (String url : toRemove) {
            channel.removeSource(url);
        }
        Timber.d("频道 %s 移除 %d 条不可用线路", channel.getChannelName(), toRemove.size());
    }

    /** 按综合评分对频道的线路重排 */
    private void sortChannelByScores(LiveChannel channel) {
        if (channel.getSourceUrls() == null) return;
        List<Integer> scores = new ArrayList<>();
        for (String url : channel.getSourceUrls()) {
            SpeedTestResult r = resultCache.get(url);
            scores.add(r != null ? r.getQualityScore() : 0);
        }
        channel.sortSourcesByScores(scores);
    }

    private void shutdownExecutor() {
        try {
            if (executorService != null && !executorService.isShutdown()) {
                executorService.shutdownNow();
            }
        } catch (Exception ignore) {}
        // 释放线程池引用，让其在任务结束后可被 GC 回收，避免低内存设备上残留引用
        executorService = null;
    }

    /**
     * 根据运行时最大堆内存计算并发上限，低内存设备自动降级以控制内存峰值。
     * 小米盒子等 1G/2G 内存机型上，过多线程的堆栈 + 下载缓冲区叠加会触发 OOM。
     */
    private int adaptiveConcurrencyCap() {
        try {
            long maxMemMB = Runtime.getRuntime().maxMemory() / (1024 * 1024);
            if (maxMemMB <= 0) return 32;
            if (maxMemMB <= 96) return 3;
            if (maxMemMB <= 128) return 4;
            if (maxMemMB <= 192) return 6;
            if (maxMemMB <= 256) return 8;
            return 32;
        } catch (Throwable t) {
            return 32;
        }
    }

    /** 内部任务类 */
    private static class SpeedTestTask {
        final LiveChannel channel;
        final String url;
        final int sourceIndex;

        SpeedTestTask(LiveChannel channel, String url, int sourceIndex) {
            this.channel = channel;
            this.url = url;
            this.sourceIndex = sourceIndex;
        }
    }
}
