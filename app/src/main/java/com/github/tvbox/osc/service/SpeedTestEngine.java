package com.github.tvbox.osc.service;

import com.github.tvbox.osc.App;
import com.github.tvbox.osc.api.ChannelManager;
import com.github.tvbox.osc.bean.LiveChannel;
import com.github.tvbox.osc.bean.SpeedTestResult;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.ProtocolFilter;
import com.github.tvbox.osc.util.StreamQualityScorer;
import com.github.tvbox.osc.util.StreamUrl;
import com.orhanobut.hawk.Hawk;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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

    /**
     * 是否有测速轮次在进行（含"已登记、等待上一轮收尾后才真正开始"的轮次）。
     * 只允许当前轮次（currentRound）清除，旧轮次收尾时不得改动它。
     */
    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    /** 保护 currentRound / isRunning 的切换，保证"登记新一轮"与"旧一轮收尾清标记"互斥 */
    private final Object roundLock = new Object();

    /** 当前（最新登记的）测速轮次；null 表示没有测速 */
    private volatile Round currentRound;

    /**
     * 控制线程：串行执行"等待上一轮收尾 → 收集线路 → 启动新一轮"。
     * testChannels 可能在主线程（Service.onStartCommand / 拉源回调）被调用，
     * 等待旧一轮的 join 不能放在调用线程上，否则遥控器按键无响应甚至 ANR。
     * 单线程保证连续多次"重新测速"按调用顺序处理。
     */
    private final ExecutorService controlExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "SpeedTestControl");
        t.setDaemon(true);
        return t;
    });

    /** 重新测速时最多等待上一轮收尾的时间；超时后新一轮照常开始（各轮状态已隔离，不会互相干扰） */
    private static final long PREV_ROUND_WAIT_MS = 5000;

    /**
     * 单轮测速的全部可变状态。
     * 以前线程池 / 取消标志 / 计数器都是引擎共享字段，"重新测速"时新一轮会把旧一轮
     * 的取消标志重置为 false（旧一轮随后误报"测速完成"），旧一轮的 finally 又会关掉
     * 新一轮的线程池并清除 isRunning。现在每轮各持一份，旧一轮只能碰自己的状态。
     */
    private static final class Round {
        final SpeedTestCallback callback;
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final AtomicInteger completed = new AtomicInteger(0);
        /**
         * 被新一轮顶替。顶替后仍会派发一次 onCancelled（而不是静默结束）：
         * 新一轮可能由另一个入口发起（如 Service 的一轮被 Worker 顶替），旧入口若收不到终态回调，
         * 前台通知、UI 进度条、Worker 的 latch 都会一直挂着。
         * 同一入口"重新测速"引起的闪跳由回调方自行判断（如 Service 用 activeCallback 区分）。
         */
        volatile boolean superseded;
        volatile ExecutorService pool;
        volatile Thread runner;
        volatile int total;
        /** 本轮开始时间：testTime 早于它的缓存结果不是本轮测的（本轮未测），不能据此删除/排序 */
        volatile long startTime;
        /**
         * 本轮已进入收尾（排序/过滤/生成本地列表）。之后才结束的迟到任务不再写缓存和频道：
         * 它们赶不上排序与本地列表，写进去只会让"已完成"的一轮数据前后不一致。
         * 读写都在 synchronized(round) 内，与任务写结果互斥。
         */
        boolean finished;

        Round(SpeedTestCallback callback) {
            this.callback = callback;
        }
    }

    /** 测速结果缓存最大条数（LRU，防止长时间运行后内存无界增长） */
    private static final int MAX_RESULT_CACHE = 4000;

    /** 单轮测速最多测试的线路总数（按频道数均摊到每个频道） */
    private static final int MAX_TEST_LINES = 2000;

    /**
     * 失败分类：无法用 HTTP 测速（RTSP/RTMP 等流式协议）。
     * 这类线路仍可播放，测速后不应作为"不可用线路"被移除。
     */
    public static final String UNTESTABLE = "UNTESTABLE";

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
     * 每次保存都要全量重建最多 SPEED_TEST_RESULT_CACHE_MAX（3000）条 JSON 并写 Hawk，
     * 阈值过小会在一轮测速（可达 2000 条线路）中触发数百次全量序列化，
     * 在低内存盒子上造成明显的 GC 压力与卡顿。
     */
    private static final int PERSIST_ITEM_INTERVAL = 200;
    /** 增量持久化触发阈值：每 N 毫秒最多保存一次（避免 Hawk 写入抖动） */
    private static final long PERSIST_TIME_INTERVAL_MS = 30000;

    /** 最近一轮完整跑完的测速开始时间（见 getLastRoundStartedAt），持久化在 Hawk */
    private volatile long lastRoundStartedAt;

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
        try {
            Long v = Hawk.get(HawkConfig.SPEED_TEST_LAST_ROUND_START, 0L);
            lastRoundStartedAt = v != null ? v : 0L;
        } catch (Throwable ignore) {
            lastRoundStartedAt = 0L;
        }
    }

    /**
     * 最近一轮<b>完整跑完</b>（未取消、未被顶替）的测速开始时间（epoch ms），从未完成过则为 0。
     * 在该轮 onAllComplete 回调之前更新并持久化，冷启动后仍可用。
     *
     * <p>testTime 早于它的缓存结果不是这一轮测出来的（超出每频道配额、上一轮残留、从 Hawk 恢复），
     * 调用方在过滤/排序/选最佳线路时应把它们当作"未测"，绝不能当作失败。
     * 只在完整跑完时更新：进行中或被取消的一轮不会让已完成那一轮的结果突然"过期"。
     */
    public long getLastRoundStartedAt() {
        return lastRoundStartedAt;
    }

    /** 测速缓存单独存放的文件名（位于应用私有 files 目录） */
    private static final String CACHE_FILE_NAME = "speed_test_cache.json";

    /**
     * 测速缓存文件。3000 条结果约 300KB：放在 Hawk 共用的 SharedPreferences 里，
     * 冷启动要在主线程连同解密一起解析，且之后任何一次设置写入（Hawk 用 commit 同步写）都要整份重写这个大 XML。
     * 单独成文件后只在测速持久化时写、冷启动读一次。拿不到 Context（如单元测试）时返回 null，退回 Hawk。
     */
    private static File cacheFile() {
        try {
            App app = App.getInstance();
            if (app == null) return null;
            File dir = app.getFilesDir();
            return dir != null ? new File(dir, CACHE_FILE_NAME) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String readFileUtf8(File f) throws java.io.IOException {
        InputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream((int) Math.max(1024, Math.min(f.length(), 4L << 20)));
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            return bos.toString("UTF-8");
        } finally {
            try { in.close(); } catch (Exception ignore) {}
        }
    }

    /** 先写临时文件再改名：写到一半被杀/断电时旧文件仍完整，不会留下半截 JSON */
    private static void writeFileAtomic(File f, String content) throws java.io.IOException {
        File tmp = new File(f.getPath() + ".tmp");
        FileOutputStream out = new FileOutputStream(tmp);
        try {
            out.write(content.getBytes("UTF-8"));
            out.flush();
            try { out.getFD().sync(); } catch (Exception ignore) {}
        } finally {
            try { out.close(); } catch (Exception ignore) {}
        }
        if (!tmp.renameTo(f)) {
            // 个别文件系统不允许覆盖式改名：先删旧文件再改名
            f.delete();
            if (!tmp.renameTo(f)) {
                tmp.delete();
                throw new java.io.IOException("rename failed: " + f);
            }
        }
    }

    /** 旧版本（≤1.2.4）存在 Hawk 里的缓存是否还未迁走；迁移成功写入文件后删除 Hawk 中的旧键 */
    private volatile boolean legacyHawkCachePresent = false;

    /** 加载持久化的测速结果缓存（冷启动即可使用历史评分/最佳源判断）：优先读独立文件，没有则读 1.2.4 存在 Hawk 中的旧数据 */
    private void loadCacheFromHawk() {
        try {
            String json = null;
            File f = cacheFile();
            if (f != null && f.isFile()) {
                try {
                    json = readFileUtf8(f);
                } catch (Throwable t) {
                    Timber.w(t, "读取测速缓存文件失败");
                }
            }
            if (json == null || json.isEmpty()) {
                json = Hawk.get(HawkConfig.SPEED_TEST_RESULT_CACHE, "");
                legacyHawkCachePresent = f != null && json != null && !json.isEmpty();
            }
            if (json == null || json.isEmpty()) return;
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                // 1.2.5 起用短键名存储（3000 条时体积小一半）；兼容 1.2.4 及以前的长键名
                boolean compact = o.has("u");
                String url = compact ? optStr(o, "u") : optStr(o, "url");
                if (url == null || url.isEmpty()) continue;
                String name = compact ? optStr(o, "n") : optStr(o, "channelName");
                SpeedTestResult r = new SpeedTestResult(url, name != null ? name : "");
                r.setSpeed(o.optDouble(compact ? "s" : "speed", 0));
                // 缺字段按"未知延迟"(-1) 处理，而不是 0（0 现在表示同毫秒内响应，评分满分）
                r.setLatency(o.optLong(compact ? "l" : "latency", -1));
                r.setAvailable(o.optBoolean(compact ? "a" : "available", false));
                r.setResolution(optStr(o, compact ? "r" : "resolution"));
                r.setTestTime(o.optLong(compact ? "t" : "testTime", 0));
                r.setStatus(o.optInt(compact ? "st" : "status", r.isAvailable() ? 2 : 3));
                r.setBandwidth(o.optLong(compact ? "b" : "bandwidth", 0));
                r.setCodecs(optStr(o, compact ? "c" : "codecs"));
                r.setQualityScore(o.optInt(compact ? "q" : "qualityScore", 0));
                r.setFailureType(optStr(o, compact ? "f" : "failureType"));
                r.setHls(o.optBoolean("h", false));
                resultCache.put(url, r);
            }
            Timber.d("从持久化恢复测速缓存: %d 条", resultCache.size());
        } catch (Exception e) {
            Timber.w(e, "加载测速缓存失败");
        }
    }

    /** 读可选字符串：缺失/JSON null 返回 null（AOSP 的 optString(key, null) 遇到 JSON null 会返回 "null"） */
    private static String optStr(JSONObject o, String key) {
        if (o == null || !o.has(key) || o.isNull(key)) return null;
        String v = o.optString(key, null);
        return v == null || v.isEmpty() ? null : v;
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
            // 排序只用于超限时保留最新，未超限时可跳过，省掉一次全量排序。
            // 上限（3000）大于单轮线路上限（2000），按 testTime 保留最新即可保证最近一整轮完整落盘
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
                if (r == null || r.getUrl() == null) continue;
                // 短键名 + 省略默认值：3000 条结果要整体写进 SharedPreferences，体积直接影响冷启动内存
                JSONObject o = new JSONObject();
                o.put("u", r.getUrl());
                if (r.getChannelName() != null) o.put("n", r.getChannelName());
                if (r.getSpeed() != 0) o.put("s", Math.round(r.getSpeed() * 10) / 10.0);
                o.put("l", r.getLatency());
                if (r.isAvailable()) o.put("a", true);
                if (r.getResolution() != null) o.put("r", r.getResolution());
                o.put("t", r.getTestTime());
                o.put("st", r.getStatus());
                if (r.getBandwidth() > 0) o.put("b", r.getBandwidth());
                if (r.getCodecs() != null) o.put("c", r.getCodecs());
                if (r.getQualityScore() != 0) o.put("q", r.getQualityScore());
                if (r.getFailureType() != null) o.put("f", r.getFailureType());
                if (r.isHls()) o.put("h", true);
                arr.put(o);
            }
            String out = arr.toString();
            File f = cacheFile();
            if (f != null) {
                writeFileAtomic(f, out);
                if (legacyHawkCachePresent) {
                    // 迁移完成：删掉 SharedPreferences 里的旧大键，之后的设置写入不再拖着它
                    legacyHawkCachePresent = false;
                    try { Hawk.delete(HawkConfig.SPEED_TEST_RESULT_CACHE); } catch (Throwable ignore) {}
                }
            } else {
                Hawk.put(HawkConfig.SPEED_TEST_RESULT_CACHE, out);
            }
        } catch (Throwable e) {
            // 全量 JSON 序列化在低内存盒子上可能 OOM（Error 而非 Exception），也必须兜住，
            // 否则会打断测速线程的收尾（终态回调 / 清除运行标记）
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
     * 对多个频道的所有源进行测速。
     *
     * <p>本方法不阻塞调用线程：已有测速在进行时，旧一轮被标记为"被顶替"并取消，
     * 等待旧一轮收尾与启动新一轮都在控制线程上完成（调用方可能是主线程）。
     * 被顶替的旧一轮在收尾后会收到一次 onCancelled（新一轮的回调已登记之后）。
     * 回调可能在控制线程或测速线程上触发。
     */
    public void testChannels(List<LiveChannel> channels, SpeedTestCallback callback) {
        final Round round = new Round(callback);
        final Round prev;
        // 同步登记新一轮：调用返回后 isRunning() 立即为 true（Service / EPG 延迟加载依赖这一点），
        // 且旧一轮收尾时发现自己已不是 currentRound，不会清除运行标记
        synchronized (roundLock) {
            prev = currentRound;
            currentRound = round;
            isRunning.set(true);
        }
        if (prev != null) {
            // 已有测速在进行：顶替并取消它，然后从头开始新一轮
            // （用户再次点击"立即测速"时应重新开始，而不是被拒绝）
            Timber.i("已有测速任务在运行，取消后重新开始");
            prev.superseded = true;
            prev.cancelled.set(true);
        }

        if (channels == null || channels.isEmpty()) {
            finishRound(round);
            if (callback != null) {
                try {
                    callback.onError("没有需要测速的频道");
                } catch (Throwable ignore) {
                    Timber.w(ignore, "onError 回调异常");
                }
            }
            return;
        }

        try {
            controlExecutor.execute(() -> runRound(prev, round, channels));
        } catch (Throwable t) {
            // 控制线程不可用（线程创建失败等）：按启动失败处理，释放运行标记
            failStart(round, t);
        }
    }

    /** 控制线程：等待上一轮收尾后启动本轮 */
    private void runRound(Round prev, Round round, List<LiveChannel> channels) {
        try {
            if (prev != null) {
                waitRoundFinished(prev, PREV_ROUND_WAIT_MS);
            }
            // 等待期间本轮已被取消/顶替：不再启动
            if (round.cancelled.get()) {
                finishRound(round);
                // 被顶替同样派发 onCancelled：发起方可能不是新一轮的发起方，需要据此收尾
                if (round.callback != null) {
                    try {
                        round.callback.onCancelled(0, 0);
                    } catch (Throwable cbErr) {
                        Timber.w(cbErr, "onCancelled 回调异常");
                    }
                }
                return;
            }
            startTestRound(channels, round);
        } catch (Throwable t) {
            failStart(round, t);
        }
    }

    /**
     * 等待上一轮的测速线程结束（仅在控制线程上调用）。
     * 被顶替的轮次会在 500ms 级别内停止等待在途任务并跳过排序，通常很快结束；
     * 超时后不再等待——各轮状态已隔离，旧一轮无法再影响新一轮。
     */
    private void waitRoundFinished(Round prev, long maxWaitMs) {
        Thread t = prev.runner;
        if (t == null || t == Thread.currentThread()) return;
        try {
            t.join(maxWaitMs);
        } catch (InterruptedException ignore) {
            Thread.currentThread().interrupt();
        }
        if (t.isAlive()) {
            Timber.w("上一轮测速 %d ms 内未结束，直接开始新一轮", maxWaitMs);
        }
    }

    /** 启动阶段失败：释放本轮的运行标记与线程池并回调 onError */
    private void failStart(Round round, Throwable t) {
        // 启动阶段异常（如线程池/线程创建失败、收集线路时异常）也必须释放运行标记，
        // 否则 isRunning 永远为 true，之后再也无法测速
        Timber.e(t, "启动测速失败");
        shutdownPool(round.pool);
        finishRound(round);
        if (round.callback == null) return;
        if (round.superseded) {
            // 已被新一轮顶替：按"已取消"收尾，不报错误
            notifyCancelledQuietly(round.callback, round.completed.get(), round.total);
            return;
        }
        try {
            round.callback.onError("启动测速失败: " + String.valueOf(t.getMessage()));
        } catch (Throwable ignore) {
            Timber.w(ignore, "onError 回调异常");
        }
    }

    /** 派发 onCancelled，回调异常不外泄 */
    private static void notifyCancelledQuietly(SpeedTestCallback callback, int completed, int total) {
        if (callback == null) return;
        try {
            callback.onCancelled(completed, total);
        } catch (Throwable cbErr) {
            Timber.w(cbErr, "onCancelled 回调异常");
        }
    }

    /** 本轮结束：只有仍是当前轮次时才清除运行标记（旧一轮不能清掉新一轮的标记） */
    private void finishRound(Round round) {
        synchronized (roundLock) {
            if (currentRound == round) {
                currentRound = null;
                isRunning.set(false);
            }
        }
    }

    /**
     * 等待单个任务结束。按短间隔轮询，以便本轮被取消后能尽快停止等待，
     * 而不是对每个在途任务都等满 awaitMs。
     *
     * @return false 表示因本轮被取消而放弃等待
     */
    private static boolean awaitTask(Future<?> future, long awaitMs, AtomicBoolean cancelled) {
        long deadline = System.currentTimeMillis() + awaitMs;
        while (true) {
            if (cancelled.get()) return false;
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) {
                Timber.w("测速任务超时");
                return true;
            }
            try {
                future.get(Math.min(left, 500L), TimeUnit.MILLISECONDS);
                return true;
            } catch (TimeoutException te) {
                // 继续轮询
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            } catch (Exception e) {
                Timber.w("测速任务失败");
                return true;
            }
        }
    }

    private static boolean isFinished(Round round) {
        synchronized (round) {
            return round.finished;
        }
    }

    /** 收集线路、创建本轮线程池并启动本轮测速线程（调用方已登记本轮并置位 isRunning） */
    private void startTestRound(List<LiveChannel> channels, final Round round) {
        final SpeedTestCallback callback = round.callback;
        // 收集所有需要测试的URL。
        // 为控制单轮测速时长，总线路数以 MAX_TEST_LINES 为上限，按频道数均摊每个频道的线路配额，
        // 保证每个频道至少测1 条，不会整段跳过后面的频道。
        int channelCount = channels.size();
        int perChannel = Math.max(1, MAX_TEST_LINES / Math.max(1, channelCount));
        List<SpeedTestTask> tasks = new ArrayList<>();
        for (LiveChannel channel : channels) {
            if (channel == null) continue;
            // 取一次线路列表快照，避免 size 与 get 之间列表被替换/修改
            List<String> sourceUrls = channel.getSourceUrls();
            if (sourceUrls == null) continue;
            int size = Math.min(sourceUrls.size(), perChannel);
            for (int i = 0; i < size; i++) {
                String url = sourceUrls.get(i);
                if (url != null && !url.isEmpty()) {
                    tasks.add(new SpeedTestTask(channel, url, i));
                }
            }
        }

        final int totalCount = tasks.size();
        round.total = totalCount;
        Timber.i("开始测速: %d 个频道, %d 个源(每频道上限 %d)", channelCount, totalCount, perChannel);

        if (totalCount == 0) {
            finishRound(round);
            if (round.superseded) {
                notifyCancelledQuietly(callback, 0, 0);
            } else if (callback != null) {
                callback.onError("没有可测速的线路");
            }
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

        // 本轮私有线程池：runner 只向它提交、finally 只关闭它，绝不碰其它轮次的线程池
        final ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        round.pool = pool;
        final AtomicBoolean cancelled = round.cancelled;
        final AtomicInteger completedCount = round.completed;

        // 等待上限：每次尝试最多 预算 + 看门狗宽限，再加重试次数，留出余量
        int retryCfg = Math.max(0, Hawk.get(HawkConfig.SPEED_TEST_RETRY, HawkConfig.DEFAULT_SPEED_TEST_RETRY));
        long perAttemptMs = Math.max(2, timeout) * 1000L + WATCHDOG_GRACE_MS + 2000L;
        final long awaitMs = Math.max(((long) timeout * (MAX_SEGMENT_SAMPLE + 1) + 10) * 1000L,
                (retryCfg + 1) * perAttemptMs + 5000L);

        Thread runner = new Thread(() -> {
            long startTime = System.currentTimeMillis();
            // 先于任何任务提交记录：本轮任务写入的 testTime 一定 >= startTime
            round.startTime = startTime;
            List<SpeedTestResult> allResults = Collections.synchronizedList(new ArrayList<>());
            List<Future<?>> futures = new ArrayList<>();

            try {
                for (SpeedTestTask task : tasks) {
                    if (cancelled.get()) break;

                    Future<?> future = pool.submit(() -> {
                        // 单个任务完全隔离：任何异常都不允许扩散到线程池，避免"一个源坏了拖垮整轮测速"
                        try {
                            if (cancelled.get()) return;

                            // 首次测速
                            SpeedTestResult result = performSpeedTest(task.url, task.channel.getChannelName(), timeout);

                            // 智能重试（对齐 ISEP：仅对超时失败自动重试，避免对确定无效源浪费资源）
                            int maxRetry = Hawk.get(HawkConfig.SPEED_TEST_RETRY, HawkConfig.DEFAULT_SPEED_TEST_RETRY);
                            int retry = 0;
                            while (retry < maxRetry && !cancelled.get() && result.isTimeoutFailure()) {
                                retry++;
                                Timber.d("超时重试 %d/%d: %s", retry, maxRetry, task.url);
                                result = performSpeedTest(task.url, task.channel.getChannelName(), timeout);
                                result.setRetried(true);
                            }

                            // 本轮已取消/被顶替：结果不再写入缓存与频道。
                            // 收尾时线程池会被 shutdownNow 中断，被中断的请求会被误判为失败，
                            // 若写入缓存会让新一轮/线路过滤把好线路当成不可用
                            if (cancelled.get()) return;

                            // 计算综合评分
                            try { StreamQualityScorer.score(result); } catch (Exception ignore) {}

                            synchronized (round) {
                                // 本轮已进入收尾：迟到的结果赶不上排序与本地列表，不再写入
                                if (cancelled.get() || round.finished) return;

                                allResults.add(result);

                                // 更新频道速度信息
                                try {
                                    // 与 LiveChannel 的写时复制重排/删除方法同锁（均 synchronized 在频道对象上）。
                                    // 测速期间用户可能置顶/删除线路导致下标漂移，下标不匹配时按 URL 重新定位
                                    synchronized (task.channel) {
                                        List<String> urls = task.channel.getSourceUrls();
                                        List<Double> sp = task.channel.getSpeeds();
                                        if (urls != null && sp != null) {
                                            int idx = task.sourceIndex;
                                            if (idx < 0 || idx >= urls.size() || !task.url.equals(urls.get(idx))) {
                                                idx = urls.indexOf(task.url);
                                            }
                                            if (idx >= 0 && idx < sp.size()) {
                                                sp.set(idx, result.getSpeed());
                                            }
                                        }
                                    }
                                } catch (Exception ignore) {}

                                // 缓存结果并触发增量持久化（中途取消/崩溃也能保留已完成的部分结果）
                                resultCache.put(task.url, result);
                            }
                            try { persistCacheIncremental(); } catch (Exception ignore) {}

                            int completed = completedCount.incrementAndGet();
                            // 取消后 runner 不再等待在途任务、可能已派发 onCancelled；
                            // 写缓存/持久化期间被取消的任务不能再回调进度，否则进度事件晚于终态事件，
                            // UI 会重新出现一条不会消失的进度条
                            if (callback != null && !cancelled.get() && !isFinished(round)) {
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
                            // 与正常路径同一判断：本轮已收尾（onAllComplete 已发出）后不能再报进度
                            if (callback != null && !cancelled.get() && !isFinished(round)) {
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
                // 这里给出充足的等待上限，避免误判超时。
                // 本轮被取消后立即停止等待：排队中的任务会在开头检查取消标志直接返回，
                // 在途任务的结果也不再写入（见任务内检查），无需逐个等满超时
                for (Future<?> future : futures) {
                    if (!awaitTask(future, awaitMs, cancelled)) break;
                }
                // 进入收尾：此后结束的任务不再写入（见任务内检查）
                synchronized (round) {
                    round.finished = true;
                }

                long totalTime = System.currentTimeMillis() - startTime;

                // 断网/网络异常的一轮：几乎所有线路都因 DNS/连接/超时失败。此时的"不可用"是网络问题
                // 而非线路问题，照常剔除会把各频道里恰好测通的那一两条之外的好线路全删掉
                // （ChannelManager 会跳过写库，但内存里的剔除要到下次冷启动重载数据库才能恢复）。
                // 判定口径与 ChannelManager#isNetworkDownRound 共用，两边结论一致
                boolean downRound = false;
                try {
                    downRound = isNetworkDownRound(allResults);
                } catch (Throwable ignore) {
                }
                if (downRound) {
                    Timber.w("本轮测速疑似网络不可用，跳过剔除不可用线路，且不记为一次成功测速");
                }
                // 用户置顶的线路即使测速失败也不剔除（用户确认能播，测速可能因缺 UA/Referer 或阈值误判）
                ChannelManager channelManager = null;
                try {
                    channelManager = ChannelManager.getInstance(App.getInstance());
                } catch (Throwable ignore) {
                }

                // 被新一轮顶替：新一轮会重新测速并重排，这里跳过全量排序/过滤，
                // 既让本线程尽快结束，也避免与新一轮同时改动频道线路
                if (!round.superseded) {
                    // 更新频道的最佳速度/评分/分辨率，并按配置模式排序
                    boolean sortSources = Hawk.get(HawkConfig.SPEED_SORT_SOURCES, true);
                    int sortMode = Hawk.get(HawkConfig.SPEED_SORT_MODE, HawkConfig.SORT_MODE_QUALITY_SCORE);
                    boolean filterUnavailable = Hawk.get(HawkConfig.SPEED_FILTER_UNAVAILABLE, true);
                    for (LiveChannel channel : channels) {
                        if (filterUnavailable && !downRound) {
                            try { removeUnavailableSources(channel, startTime, channelManager); } catch (Exception ignore) {}
                        }
                        updateChannelBestMetrics(channel, startTime);
                        if (sortSources) {
                            if (sortMode == HawkConfig.SORT_MODE_QUALITY_SCORE) {
                                sortChannelByScores(channel, startTime);
                            } else {
                                channel.sortSourcesBySpeed();
                            }
                        }
                        channel.setLastSpeedTestTime(System.currentTimeMillis());
                    }
                }

                // 只有完整跑完的一轮才记录测速时间：中途取消若也记录，
                // "周期性打开时提示"会在整个周期内不再提醒，设置页也显示"上次测速: 刚刚"
                boolean roundCancelled = cancelled.get() || round.superseded;
                if (!roundCancelled) {
                    // 断网轮次不记"上次测速时间"：否则"周期性打开时提示"整个周期不再提醒，
                    // 设置页也显示"刚刚测过"，而这一轮的结果实际并未被采用。
                    // lastRoundStartedAt 照常更新（契约是"最近一轮完整跑完的开始时间"），
                    // ChannelManager 据此识别断网轮次并自行回退到上一正常轮
                    if (!downRound) {
                        Hawk.put(HawkConfig.SPEED_TEST_LAST_TIME, System.currentTimeMillis());
                    }
                    // 先于 onAllComplete 更新：回调里生成本地列表时据此区分本轮结果与旧结果
                    lastRoundStartedAt = startTime;
                    try {
                        Hawk.put(HawkConfig.SPEED_TEST_LAST_ROUND_START, startTime);
                    } catch (Throwable ignore) {}
                }
                // 持久化缓存（取消时也保存已完成的部分结果；finally 中还会兜底再存一次）
                persistCacheToHawk();

                // 回调前对结果做快照拷贝，避免调用方遍历时后台仍有 in-flight 任务写入导致
                // ConcurrentModificationException；回调本身用 try/catch 包裹，防止调用方异常
                // 影响引擎收尾（persist/shutdown）。
                List<SpeedTestResult> snapshot;
                synchronized (allResults) {
                    snapshot = new ArrayList<>(allResults);
                }
                try {
                    if (roundCancelled) {
                        // 被顶替也派发 onCancelled：新一轮可能来自其它入口，旧入口需要收尾
                        // （停服务 / 撤通知 / 释放 latch）；同入口重测的闪跳由回调方判断规避
                        if (callback != null) {
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

                Timber.i("测速%s: %d/%d, 耗时 %d ms", roundCancelled ? "结束(已取消)" : "完成",
                        completedCount.get(), totalCount, totalTime);

            } catch (Throwable e) {
                // 捕获 Throwable：收尾阶段（全量排序 / JSON 序列化）在低内存盒子上可能 OOM，
                // 若只捕获 Exception，终态回调永远不触发，前台通知与 UI 进度条会一直卡住
                Timber.e(e, "测速引擎异常");
                try {
                    if (round.superseded) {
                        // 已被顶替：按"已取消"收尾即可
                        notifyCancelledQuietly(callback, completedCount.get(), totalCount);
                    } else if (callback != null) {
                        callback.onError("测速异常: " + String.valueOf(e.getMessage()));
                    }
                } catch (Throwable ignore) {
                    Timber.w(ignore, "onError 回调异常");
                }
            } finally {
                // 无论正常完成 / 取消 / 异常，都强制把已完成的结果落盘，避免丢失
                try {
                    persistCacheToHawk();
                } catch (Throwable ex) {
                    Timber.w(ex, "最终持久化测速缓存失败");
                } finally {
                    // 放在内层 finally：即便持久化再次 OOM，也一定释放运行标记与本轮线程池
                    finishRound(round);
                    shutdownPool(pool);
                }
            }
        }, "SpeedTestRunner");
        round.runner = runner;
        runner.start();
    }

    /**
     * 取消正在进行的测速
     */
    public void cancel() {
        Round r = currentRound;
        if (r != null) {
            r.cancelled.set(true);
            Timber.i("用户取消测速");
        }
    }

    /**
     * 仅当当前轮次由 callback 发起时才取消。
     * 供后台 Worker 等入口在超时时使用，避免误取消其它入口（如用户手动）后来发起的测速。
     *
     * @return 是否取消了某一轮
     */
    public boolean cancel(SpeedTestCallback callback) {
        Round r = currentRound;
        if (r != null && callback != null && r.callback == callback) {
            r.cancelled.set(true);
            Timber.i("取消指定测速轮次");
            return true;
        }
        return false;
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
            File f = cacheFile();
            if (f != null && f.exists() && !f.delete()) {
                // 删不掉就写空数组，保证下次冷启动不会读回旧结果
                writeFileAtomic(f, "[]");
            }
        } catch (Exception e) {
            Timber.w(e, "清除测速缓存文件失败");
        }
        legacyHawkCachePresent = false;
        try {
            Hawk.delete(HawkConfig.SPEED_TEST_RESULT_CACHE);
        } catch (Exception e) {
            Timber.w(e, "清除持久化测速缓存失败");
        }
    }

    // ============ 私有方法 ============

    // ============ 测速核心（参照 Guovin/iptv-api 实现）============

    /** 最少测量时长（毫秒） */
    private static final long MIN_MEASURE_MS = 1000;
    /** 稳定性判断窗口（最近 N 个瞬时速度采样） */
    private static final int STABILITY_WINDOW = 4;
    /** 稳定性波动阈值（窗口极差/均值低于此值即认为稳定） */
    private static final double STABILITY_THRESHOLD = 0.15;
    /** 至少下载字节数；直链在此之前就读到流结束，说明是错误页/短文件而不是直播流 */
    private static final long MIN_BYTES = 64 * 1024;
    /** 单个 HLS 媒体列表最多采样的分片数 */
    private static final int MAX_SEGMENT_SAMPLE = 3;
    /** 手动跟随重定向的最大跳数 */
    private static final int MAX_REDIRECTS = 8;
    /** 预算用完后看门狗强制断开连接前的宽限（毫秒） */
    private static final long WATCHDOG_GRACE_MS = 500;
    /**
     * 直播直链（测量窗口结束时仍在持续出数据）的速度下限（KB/s）。
     * 直播流按码率实时推送，测得的速度就是码率本身而不是带宽：128kbps 的广播只有 16 KB/s，
     * 低码率标清台也常低于 50 KB/s。对这类线路只要求"跟得上实时推送"，阈值取 min(用户阈值, 下限)。
     */
    private static final double LIVE_VIDEO_FLOOR_KBPS = 24;
    private static final double LIVE_AUDIO_FLOOR_KBPS = 8;
    /** HLS 分片正文总耗时短于此值时（几个包就读完）不足以算吞吐，改用含首包等待的总耗时 */
    private static final long MIN_SEGMENT_BODY_MS = 50;

    /** 测速失败分类：无异常但没测到数据 / 低于速度阈值（都不触发超时重试） */
    private static final String ZERO_SPEED = "ZERO_SPEED";
    private static final String BELOW_MIN = "BELOW_MIN";

    /**
     * 看门狗：单条线路预算用完后强制断开它当前的连接。
     * 连接/读取超时只约束单次阻塞调用，一条 HLS 线路要串行发 4~5 个请求，
     * 每个都等满超时的话，5 秒的预算会拖成 30 秒以上。
     */
    private static final ScheduledThreadPoolExecutor WATCHDOG = createWatchdog();

    private static ScheduledThreadPoolExecutor createWatchdog() {
        ScheduledThreadPoolExecutor ex = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "SpeedTestWatchdog");
            t.setDaemon(true);
            return t;
        });
        // 绝大多数线路按时结束、看门狗被取消：取消即移出队列，避免 2000 条线路的任务堆在队列里
        ex.setRemoveOnCancelPolicy(true);
        return ex;
    }

    /** 单次测速尝试的请求上下文：线路自带请求头、预算截止时间、当前连接（供看门狗断开） */
    private static final class Probe {
        final Map<String, String> headers;
        final long deadline;
        volatile HttpURLConnection active;
        volatile boolean expired;

        Probe(Map<String, String> headers, long deadline) {
            this.headers = headers != null ? headers : Collections.<String, String>emptyMap();
            this.deadline = deadline;
        }

        void expire() {
            expired = true;
            HttpURLConnection c = active;
            if (c != null) {
                try {
                    c.disconnect();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    /** 跟随重定向后的最终响应 */
    private static final class Response {
        final HttpURLConnection conn;
        final int code;
        /** 重定向后的最终地址（手动跟随时 conn.getURL() 就是它；HLS 相对地址以它为基准） */
        final String finalUrl;

        Response(HttpURLConnection conn, int code, String finalUrl) {
            this.conn = conn;
            this.code = code;
            this.finalUrl = finalUrl;
        }
    }

    /** 抓取到的文本与其最终地址 */
    private static final class TextResult {
        final String text;
        final String finalUrl;

        TextResult(String text, String finalUrl) {
            this.text = text;
            this.finalUrl = finalUrl;
        }
    }

    /** 直链测量结果 */
    private static final class StreamStat {
        double speed;
        long bytes;
        /** 读到了流结束（-1）；直播流不会在测量窗口内结束 */
        boolean eof;
        long elapsedMs;
    }

    /** 单个分片的下载结果 */
    private static final class SegmentStat {
        long bytes;
        /** 响应头到达后读正文的耗时（不含连接/首包等待） */
        long bodyMs;
        long totalMs;
        int httpCode;
        boolean timeout;
        Exception error;
    }

    /** measure 的附带结论：直链是否为直播流（决定速度阈值怎么用） */
    private static final class MeasureInfo {
        boolean liveDirect;
        boolean audio;
        long icyBrKbps;
    }

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
     * @param urlStr         线路原始串（可带 '$标签' 与 '|请求头'），结果仍以它为键
     * @param timeoutSeconds 单源测速总预算（秒）
     */
    private SpeedTestResult performSpeedTest(String urlStr, String channelName, int timeoutSeconds) {
        SpeedTestResult result = new SpeedTestResult(urlStr, channelName);
        result.setStatus(1);

        int budget = Math.max(2, timeoutSeconds);
        long deadline = System.currentTimeMillis() + budget * 1000L;
        // 请求只用真实地址，并带上线路自己的请求头（UA/Referer 等），与播放器发出的请求一致；
        // 否则按 UA/Referer 放行的源会"测速失败但能播"或反过来
        String url = cleanUrl(urlStr);

        // 仅 HTTP(S) 可下载测速；RTSP/RTMP 是流式协议，无法用 HTTP 拉取片段测速。
        // 标记为"不可测速"而非"不可用"，这类线路仍可播放，不应在测速后被移除。
        if (!ProtocolFilter.isSpeedTestable(url)) {
            result.setAvailable(false);
            result.setStatus(3);
            result.setFailureType(UNTESTABLE);
            result.setErrorMsg("流式协议，跳过测速");
            result.setTestTime(System.currentTimeMillis());
            return result;
        }

        Probe probe = new Probe(StreamUrl.headers(urlStr), deadline);
        ScheduledFuture<?> watchdog = scheduleWatchdog(probe);
        try {
            MeasureInfo info = measure(url, probe, result);
            // 可用性判定：速度须 > 0，且不低于配置的最低速度阈值（KB/s）。
            // 阈值为 0 时不做下限过滤，仅要求能测得速度。
            // 直播直链测得的是码率而非带宽，阈值按 effectiveMinSpeed 放宽（见 LIVE_*_FLOOR_KBPS）。
            int minSpeed = Hawk.get(HawkConfig.SPEED_MIN_THRESHOLD, HawkConfig.DEFAULT_SPEED_MIN_THRESHOLD);
            double effMin = effectiveMinSpeed(minSpeed, info);
            boolean available = result.getSpeed() > 0
                    && (effMin <= 0 || result.getSpeed() >= effMin);
            result.setAvailable(available);
            result.setStatus(available ? 2 : 3);
            if (!available) {
                // 无异常的不可用也记下原因，便于排查；两者都不是 TIMEOUT，不会触发重试
                if (result.getSpeed() > 0) {
                    result.setFailureType(BELOW_MIN);
                    result.setErrorMsg("速度低于阈值 " + Math.round(effMin) + " KB/s");
                } else {
                    result.setFailureType(ZERO_SPEED);
                    result.setErrorMsg("未测到有效数据");
                }
            }
            Timber.d("测速完成: %s - %.1f KB/s (阈值 %.0f), 延迟 %d ms, %s%s",
                    channelName, result.getSpeed(), effMin, result.getLatency(),
                    result.getResolution() != null ? result.getResolution() : "?",
                    result.isHls() ? " HLS" : "");
        } catch (Exception e) {
            result.setAvailable(false);
            result.setStatus(3);
            if (result.getLatency() <= 0) result.setLatency(-1);
            result.setErrorMsg(e.getMessage());
            // 被看门狗断开的连接抛的是 "Socket closed" 之类，本质是预算超时
            result.setFailureType(probe.expired ? "TIMEOUT" : classifyFailure(e));
            Timber.d("测速失败: %s - %s (%s)", channelName, e.getMessage(), result.getFailureType());
        } finally {
            if (watchdog != null) watchdog.cancel(false);
            result.setTestTime(System.currentTimeMillis());
        }
        return result;
    }

    private static ScheduledFuture<?> scheduleWatchdog(final Probe probe) {
        try {
            long delay = Math.max(0, probe.deadline - System.currentTimeMillis()) + WATCHDOG_GRACE_MS;
            return WATCHDOG.schedule(probe::expire, delay, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            // 看门狗不可用时退化为只靠连接/读取超时，不影响测速本身
            return null;
        }
    }

    /**
     * 实际使用的速度下限。
     * HLS 分片与有限长度的文件按带宽下载，用用户阈值；
     * 直播直链（窗口结束时仍在出数据）测得的是码率，只要求跟得上实时推送：
     * 有 icy-br 声明时取其 90%，音频流 8 KB/s，视频流 24 KB/s，且不超过用户阈值。
     */
    private static double effectiveMinSpeed(int minSpeed, MeasureInfo info) {
        if (minSpeed <= 0 || info == null || !info.liveDirect) return minSpeed;
        double floor;
        if (info.icyBrKbps > 0) {
            floor = Math.max(4, info.icyBrKbps / 8.0 * 0.9);
        } else {
            floor = info.audio ? LIVE_AUDIO_FLOOR_KBPS : LIVE_VIDEO_FLOOR_KBPS;
        }
        return Math.min(minSpeed, floor);
    }

    /** 将异常归类为 TIMEOUT / HTTP_ERROR / DNS / OTHER，用于智能重试策略 */
    private String classifyFailure(Exception e) {
        if (e == null) return "OTHER";
        if (isTimeout(e)) return "TIMEOUT";
        String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        if (msg.contains("http ")) return "HTTP_ERROR";
        if (msg.contains("unknownhost") || msg.contains("unable to resolve")) return "DNS";
        return "OTHER";
    }

    private static boolean isTimeout(Exception e) {
        if (e instanceof SocketTimeoutException) return true;
        String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        return msg.contains("timeout") || msg.contains("timed out");
    }

    /**
     * 打开首个请求：测延迟、嗅探是否为 m3u8；
     * 若为 HLS 则解析并采样测分片，否则按直链测速。
     */
    private MeasureInfo measure(String url, Probe probe, SpeedTestResult result) throws Exception {
        MeasureInfo info = new MeasureInfo();
        Response resp = null;
        InputStream in = null;
        try {
            long t0 = System.currentTimeMillis();
            resp = open(url, probe);
            // 延迟 = 发出请求到最终（跟随重定向后）响应头到达，与播放器起播前的等待一致
            long delay = System.currentTimeMillis() - t0;
            result.setLatency(delay);
            result.setConnectTimeout(delay);

            if (!isOk(resp.code)) {
                result.setLatency(-1);
                throw new Exception("HTTP " + resp.code);
            }

            HttpURLConnection conn = resp.conn;
            String contentType = conn.getContentType();
            String finalUrl = resp.finalUrl;

            // 计时从响应头到达开始：预读的 head 字节连同读取它的耗时一起计入。
            // 以前先读 8KB 再开始计时，几 KB 的错误页一次读完会算出上万 KB/s 的虚高速度
            long bodyStart = System.currentTimeMillis();
            in = conn.getInputStream();

            // 嗅探首块，判断是否为 m3u8 文本
            byte[] head = new byte[8192];
            int headLen = Math.max(0, in.read(head));
            boolean isM3u8 = looksLikeM3u8(contentType, finalUrl)
                    || (headLen > 0 && startsWithExtm3u(head, headLen));

            if (isM3u8) {
                // 记下嗅探结论：无 .m3u8 扩展名的 HLS 线路，播放器据此按 HLS 打开
                result.setHls(true);
                // 读取完整播放列表文本（很小），再去测真实分片
                String playlist = readRemainingAsText(head, headLen, in, probe.deadline);
                closeQuietly(in);
                in = null;
                disconnectQuietly(resp.conn);
                resp = null;
                measureHls(finalUrl, playlist, probe, result);
            } else {
                // 200 但返回的是 HTML/JSON 错误页（"token 过期"之类）：不是音视频流
                if (headLen > 0 && looksLikeErrorPage(contentType, head, headLen)) {
                    throw new Exception("返回的不是音视频流（错误页/文本）");
                }
                // 直链：用当前已建立的流继续测速（含已读的 head 字节及其耗时）
                StreamStat st = measureStream(in, headLen, bodyStart, probe);
                if (st.eof && st.bytes < MIN_BYTES) {
                    // 直播流不会这么快结束；短 body 只能是错误页/占位文件
                    throw new Exception("流提前结束（仅 " + (st.bytes / 1024) + " KB），不是有效的直播流");
                }
                result.setSpeed(st.speed);
                // 窗口结束时仍在出数据、且没有声明长度 → 直播流（速度≈码率）；
                // 读完了或声明了长度 → 有限文件（速度≈带宽），沿用用户阈值
                info.liveDirect = !st.eof && st.elapsedMs >= MIN_MEASURE_MS && conn.getContentLength() <= 0;
                String ct = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
                info.audio = ct.startsWith("audio/")
                        || conn.getHeaderField("icy-name") != null
                        || conn.getHeaderField("icy-br") != null;
                info.icyBrKbps = parseLeadingLong(conn.getHeaderField("icy-br"));
            }
        } finally {
            closeQuietly(in);
            if (resp != null) disconnectQuietly(resp.conn);
        }
        return info;
    }

    /**
     * HLS 测速：解析播放列表 → 若为主列表选最高带宽变体 → 采样下载 TS 分片 → 计算吞吐
     */
    private void measureHls(String playlistUrl, String content, Probe probe, SpeedTestResult result) throws Exception {
        content = stripBom(content);
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
                    if (remainingMs(probe.deadline) <= 0) {
                        throw new SocketTimeoutException("测速预算耗尽（未取到媒体列表）");
                    }
                    TextResult media = fetchText(best.url, probe);
                    // 变体地址常被 302 到 CDN 边缘节点：相对分片地址必须相对重定向后的最终地址解析，
                    // 与播放器一致，否则分片全部请求到错误的主机/路径
                    playlistUrl = media.finalUrl != null ? media.finalUrl : best.url;
                    content = media.text;
                }
            }
        }

        List<String> segments = parseSegments(playlistUrl, content);
        if (segments.isEmpty()) {
            throw new Exception("m3u8 无可用分片");
        }

        List<String> sampled = sampleSegments(segments, MAX_SEGMENT_SAMPLE);

        long bodyBytes = 0;
        long bodyMs = 0;
        long totalMs = 0;
        boolean timedOut = false;
        int lastHttp = 0;
        Exception lastErr = null;
        for (String seg : sampled) {
            if (remainingMs(probe.deadline) <= 0) {
                timedOut = true;
                break;
            }
            SegmentStat r = downloadSegment(seg, probe);
            if (r.bytes > 0) {
                bodyBytes += r.bytes;
                bodyMs += r.bodyMs;
                totalMs += r.totalMs;
            } else {
                // 没拿到数据的分片不计耗时，一个卡住的分片不拉低本来正常的线路
                if (r.timeout) timedOut = true;
                if (r.httpCode > 0) lastHttp = r.httpCode;
                if (r.error != null) lastErr = r.error;
            }
        }

        if (bodyBytes > 0) {
            // 只按正文耗时算吞吐（不含每个分片的建连/首包等待），与直链口径一致；
            // 正文太快读完不足以计时，才退回含首包等待的总耗时
            long ms = bodyMs >= MIN_SEGMENT_BODY_MS ? bodyMs : totalMs;
            if (ms <= 0) ms = 1;
            result.setSpeed((bodyBytes / 1024.0) / (ms / 1000.0));
            return;
        }
        // 采样分片全部失败：把原因抛出去交给 classifyFailure，超时才能触发重试
        if (timedOut || probe.expired) throw new SocketTimeoutException("HLS 分片下载超时");
        if (lastHttp > 0) throw new Exception("HTTP " + lastHttp + "（分片）");
        if (lastErr != null) throw new Exception("分片下载失败: " + lastErr.getMessage());
        result.setSpeed(0);
    }

    /**
     * 用"稳定性窗口提前退出"测量一个字节流的下载速度（KB/s）。
     *
     * @param prefixLen 已经预读的字节数（计入总量）
     * @param start     计时起点（响应头到达时刻，早于预读，预读耗时也计入）
     */
    private StreamStat measureStream(InputStream in, int prefixLen, long start, Probe probe) throws Exception {
        StreamStat st = new StreamStat();
        byte[] buffer = new byte[16 * 1024];
        long totalBytes = Math.max(0, prefixLen);
        long lastTime = start;
        long lastBytes = 0;
        List<Double> samples = new ArrayList<>();

        while (true) {
            if (System.currentTimeMillis() >= probe.deadline) break;

            int n;
            try {
                n = in.read(buffer);
            } catch (java.io.IOException e) {
                // 看门狗在预算用完后断开了连接：已收到的数据照常计速
                if (probe.expired && totalBytes > 0) break;
                throw e;
            }
            if (n == -1) {
                st.eof = true;
                break;
            }
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
        st.bytes = totalBytes;
        st.elapsedMs = totalTime;
        st.speed = (totalTime > 0 && totalBytes > 0) ? (totalBytes / 1024.0) / (totalTime / 1000.0) : 0;
        return st;
    }

    /** 下载单个分片（在剩余预算内尽量读完） */
    private SegmentStat downloadSegment(String segUrl, Probe probe) {
        SegmentStat st = new SegmentStat();
        Response resp = null;
        InputStream in = null;
        long start = System.currentTimeMillis();
        long bodyStart = -1;
        try {
            resp = open(segUrl, probe);
            if (!isOk(resp.code)) {
                st.httpCode = resp.code;
                return st;
            }
            bodyStart = System.currentTimeMillis();
            in = resp.conn.getInputStream();
            byte[] buffer = new byte[16 * 1024];
            while (System.currentTimeMillis() < probe.deadline) {
                int n = in.read(buffer);
                if (n == -1) break;
                st.bytes += n;
            }
        } catch (Exception e) {
            // 已读到的部分照常计入；没读到数据时由 measureHls 按超时/其它原因上报
            st.error = e;
            st.timeout = probe.expired || isTimeout(e);
        } finally {
            closeQuietly(in);
            if (resp != null) disconnectQuietly(resp.conn);
            long end = System.currentTimeMillis();
            st.totalMs = end - start;
            st.bodyMs = bodyStart >= 0 ? end - bodyStart : 0;
        }
        return st;
    }

    // ============ HLS / HTTP 辅助 ============

    private static boolean isOk(int code) {
        return code == HttpURLConnection.HTTP_OK || code == HttpURLConnection.HTTP_PARTIAL;
    }

    private static boolean isRedirect(int code) {
        return code == 301 || code == 302 || code == 303 || code == 307 || code == 308;
    }

    /**
     * 发起请求并手动跟随重定向（最多 MAX_REDIRECTS 跳）。
     * Android 的 HttpURLConnection 不跟随 http↔https 之间的重定向，而播放器允许跨协议重定向，
     * 自动跟随会让 CDN/鉴权网关常见的 "302 → https" 线路被误判为 HTTP 302 失败并删除。
     * 每一跳都重新带上线路请求头，并受同一预算约束。
     */
    private Response open(String url, Probe probe) throws Exception {
        String cur = url;
        for (int hop = 0; ; hop++) {
            HttpURLConnection conn = openConnection(cur, probe);
            int code;
            try {
                code = conn.getResponseCode();
            } catch (Exception e) {
                disconnectQuietly(conn);
                throw e;
            }
            if (isRedirect(code) && hop < MAX_REDIRECTS) {
                String loc = conn.getHeaderField("Location");
                String next = null;
                if (loc != null && !loc.trim().isEmpty()) {
                    try {
                        next = new URL(new URL(cur), loc.trim()).toString();
                    } catch (Exception ignore) {
                        next = null;
                    }
                }
                // 没有 Location / 跳到非 http(s)：按原状态码失败
                if (next != null && ProtocolFilter.isSpeedTestable(next)) {
                    disconnectQuietly(conn);
                    cur = next;
                    continue;
                }
            }
            return new Response(conn, code, cur);
        }
    }

    private HttpURLConnection openConnection(String url, Probe probe) throws Exception {
        if (url == null || url.isEmpty()) throw new Exception("empty url");
        long remainMs = remainingMs(probe.deadline);
        if (probe.expired || remainMs <= 0) {
            throw new SocketTimeoutException("测速预算耗尽");
        }
        // 超时不再设 2 秒下限：预算快用完时仍给满 2 秒，会让单条线路的耗时远超配置的测速时长
        int connectTimeout = (int) Math.max(1, Math.min(8000, remainMs));
        int readTimeout = (int) Math.max(1, Math.min(15000, remainMs));
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
        // 重定向由 open() 手动跟随（自动跟随不支持跨协议）
        conn.setInstanceFollowRedirects(false);
        // 默认 UA 与播放器一致；线路自带的请求头（User-Agent / Referer / Cookie 等）随后覆盖
        conn.setRequestProperty("User-Agent", StreamUrl.DEFAULT_USER_AGENT);
        conn.setRequestProperty("Accept", "*/*");
        for (Map.Entry<String, String> e : probe.headers.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) continue;
            try {
                conn.setRequestProperty(e.getKey(), e.getValue());
            } catch (Exception ignore) {
                // 非法请求头名/值：跳过该项，不影响测速
            }
        }
        probe.active = conn;
        return conn;
    }

    /** 抓取文本（用于获取媒体播放列表），限制大小；同时返回重定向后的最终地址 */
    private TextResult fetchText(String url, Probe probe) throws Exception {
        Response resp = null;
        InputStream in = null;
        try {
            resp = open(url, probe);
            if (!isOk(resp.code)) {
                throw new Exception("HTTP " + resp.code);
            }
            in = resp.conn.getInputStream();
            String text = readRemainingAsText(null, 0, in, probe.deadline);
            return new TextResult(text, resp.finalUrl);
        } finally {
            closeQuietly(in);
            if (resp != null) disconnectQuietly(resp.conn);
        }
    }

    /** 把已读 head 与剩余流合并读为文本（用于 m3u8 列表，体积很小），去掉 UTF-8 BOM */
    private String readRemainingAsText(byte[] head, int headLen, InputStream in, long deadline) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        if (head != null && headLen > 0) bos.write(head, 0, headLen);
        byte[] buffer = new byte[8192];
        int n;
        // m3u8 一般 < 1MB，限制读取上限避免误把大文件当列表
        while (bos.size() < 1024 * 1024 && System.currentTimeMillis() < deadline
                && (n = in.read(buffer)) != -1) {
            bos.write(buffer, 0, n);
        }
        return stripBom(bos.toString("UTF-8"));
    }

    /** Windows / PHP 生成的列表常带 UTF-8 BOM：不去掉的话 #EXTM3U 识别失败，首行还会被当成一个分片 */
    private static String stripBom(String s) {
        if (s != null && s.startsWith("\uFEFF")) return s.substring(1);
        return s;
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
        String[] lines = stripBom(content).split("\\r?\\n");
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
                    // 缺 URI 的残缺条目：遇到下一个变体级标签就放弃本条，
                    // 否则本条的码率/分辨率会安到下一个变体的地址上（外层循环会正常处理下一条）
                    if (next.startsWith("#EXT-X-STREAM-INF")
                            || next.startsWith("#EXT-X-I-FRAME-STREAM-INF")
                            || next.startsWith("#EXT-X-MEDIA:")) {
                        break;
                    }
                    // 其它注释/标签（如 #EXT-X-PROGRAM-ID、厂商私有标签）可以合法地夹在中间
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

    /**
     * 解析 #EXT-X-STREAM-INF 行中的属性值（去除引号）。
     * 属性名必须紧跟在 ':' 或 ',' 之后，否则 CODECS 会匹配到 SUPPLEMENTAL-CODECS、
     * BANDWIDTH 会匹配到 AVERAGE-BANDWIDTH。
     */
    private String parseAttr(String line, String key) {
        try {
            Pattern p = Pattern.compile("(?:^|[:,])\\s*" + Pattern.quote(key) + "=(\"[^\"]*\"|[^,]*)",
                    Pattern.CASE_INSENSITIVE);
            Matcher m = p.matcher(line);
            if (m.find()) {
                String v = m.group(1).trim();
                if (v.startsWith("\"") && v.endsWith("\"") && v.length() >= 2) {
                    v = v.substring(1, v.length() - 1);
                }
                return v.isEmpty() ? null : v;
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** 取峰值 BANDWIDTH 选变体；没有时才退回 AVERAGE-BANDWIDTH */
    private long parseBandwidth(String streamInfLine) {
        long bw = parseLeadingLong(parseAttr(streamInfLine, "BANDWIDTH"));
        if (bw > 0) return bw;
        return parseLeadingLong(parseAttr(streamInfLine, "AVERAGE-BANDWIDTH"));
    }

    /** 解析字符串开头的数字（如 "128"、"128,128"），失败返回 0 */
    private static long parseLeadingLong(String s) {
        if (s == null) return 0;
        String t = s.trim();
        int end = 0;
        while (end < t.length() && end < 18 && Character.isDigit(t.charAt(end))) end++;
        if (end == 0) return 0;
        try {
            return Long.parseLong(t.substring(0, end));
        } catch (Exception e) {
            return 0;
        }
    }

    /** 解析媒体播放列表中的分片 URL（绝对化） */
    private List<String> parseSegments(String baseUrl, String content) {
        List<String> segments = new ArrayList<>();
        for (String raw : content.split("\\r?\\n")) {
            String line = stripBom(raw.trim());
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
        if (ref == null) return null;
        String lower = ref.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")) return ref;
        try {
            URI base = URI.create(baseUrl);
            // "http://host" 这种空路径基址，URI.resolve 会拼成 "http://hostseg.ts"
            if (base.getRawPath() == null || base.getRawPath().isEmpty()) {
                base = URI.create(baseUrl + "/");
            }
            return base.resolve(ref).toString();
        } catch (Exception e) {
            // 地址里有空格、'|'、'{' 等 URI 不接受的字符：退回更宽松的 java.net.URL 解析
            // （与播放器的宽松解析一致），不能直接返回相对地址，否则请求必然失败
            try {
                return new URL(new URL(baseUrl), ref).toString();
            } catch (Exception e2) {
                return ref;
            }
        }
    }

    private boolean looksLikeM3u8(String contentType, String url) {
        if (contentType != null) {
            String ct = contentType.toLowerCase(Locale.ROOT);
            if (ct.contains("mpegurl") || ct.contains("vnd.apple.mpegurl")) return true;
        }
        String u = url.toLowerCase(Locale.ROOT);
        int q = u.indexOf('?');
        String path = q >= 0 ? u.substring(0, q) : u;
        return path.endsWith(".m3u8") || path.endsWith(".m3u");
    }

    /** 跳过开头的 UTF-8 BOM（EF BB BF）与空白，返回第一个有效字节的位置 */
    private static int skipBomAndSpace(byte[] data, int len) {
        int i = 0;
        if (len >= 3 && (data[0] & 0xFF) == 0xEF && (data[1] & 0xFF) == 0xBB && (data[2] & 0xFF) == 0xBF) {
            i = 3;
        }
        while (i < len && (data[i] == ' ' || data[i] == '\t' || data[i] == '\r' || data[i] == '\n')) i++;
        return i;
    }

    private boolean startsWithExtm3u(byte[] data, int len) {
        // 在字节层面跳过 BOM：String.trim() 不会去掉 U+FEFF，带 BOM 的列表以前识别不出来
        int i = skipBomAndSpace(data, len);
        byte[] tag = {'#', 'E', 'X', 'T', 'M', '3', 'U'};
        if (len - i < tag.length) return false;
        for (int k = 0; k < tag.length; k++) {
            if (data[i + k] != tag[k]) return false;
        }
        return true;
    }

    /** 开头是否为常见音视频容器的魔数（TS / FLV / MP4 / MP3 / AAC / Ogg / MKV） */
    private static boolean hasMediaMagic(byte[] d, int len) {
        if (len >= 1 && (d[0] & 0xFF) == 0x47) return true;                        // MPEG-TS 同步字节
        if (len >= 3 && d[0] == 'F' && d[1] == 'L' && d[2] == 'V') return true;     // FLV
        if (len >= 3 && d[0] == 'I' && d[1] == 'D' && d[2] == '3') return true;     // MP3/AAC 带 ID3
        if (len >= 2 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xE0) == 0xE0) return true; // MPEG 音频 / ADTS 帧头
        if (len >= 4 && d[0] == 'O' && d[1] == 'g' && d[2] == 'g' && d[3] == 'S') return true; // Ogg
        if (len >= 4 && (d[0] & 0xFF) == 0x1A && (d[1] & 0xFF) == 0x45
                && (d[2] & 0xFF) == 0xDF && (d[3] & 0xFF) == 0xA3) return true;    // MKV/WebM
        if (len >= 8 && d[4] == 'f' && d[5] == 't' && d[6] == 'y' && d[7] == 'p') return true; // MP4
        // 分片 MP4（fMP4 直播常以 styp/moof 开头，没有 ftyp）
        if (len >= 8 && ((d[4] == 's' && d[5] == 't' && d[6] == 'y' && d[7] == 'p')
                || (d[4] == 'm' && d[5] == 'o' && d[6] == 'o' && d[7] == 'f'))) return true;
        // MPEG-PS 打包头 00 00 01 BA
        if (len >= 4 && d[0] == 0 && d[1] == 0 && (d[2] & 0xFF) == 0x01 && (d[3] & 0xFF) == 0xBA) return true;
        return false;
    }

    /**
     * 开头是否基本是文本：跳过 BOM/空白后前 512 字节里可打印 ASCII / 空白 / UTF-8 多字节占 90% 以上。
     * 二进制流（中途切入的 TS、裸 H.264、PS 等）的高位字节和控制字符远多于此。
     */
    private static boolean looksLikeText(byte[] d, int len) {
        int start = skipBomAndSpace(d, len);
        int end = Math.min(len, start + 512);
        int n = end - start;
        if (n <= 0) return false;
        int text = 0;
        for (int i = start; i < end; i++) {
            int b = d[i] & 0xFF;
            if (b == '\t' || b == '\n' || b == '\r' || (b >= 0x20 && b < 0x7F)) {
                text++;
            } else if (b >= 0xC2 && b <= 0xF4) {
                // UTF-8 多字节序列（中文错误页）：首字节 + 后续 0x80-0xBF 都算文本
                int need = b >= 0xF0 ? 3 : (b >= 0xE0 ? 2 : 1);
                int k = 1;
                while (k <= need && i + k < end && ((d[i + k] & 0xFF) & 0xC0) == 0x80) k++;
                if (k == need + 1 || i + k >= end) {
                    text += k;
                    i += k - 1;
                }
            }
        }
        return text * 10 >= n * 9;
    }

    /**
     * 直链返回内容是否为错误页/文本：首个有效字符是 '<'（HTML/XML）、'{' / '['（JSON），
     * 或 Content-Type 为 html/json/xml、开头不是任何音视频魔数且内容基本是文本。
     * 以内容为主判断，避免误杀 Content-Type 配错但内容正常的流：
     * PHP 代理常把 fMP4 / 中途切入的 TS / PS 流标成 text/html，只凭 Content-Type 会把能播的线路删掉。
     */
    private static boolean looksLikeErrorPage(String contentType, byte[] head, int len) {
        if (hasMediaMagic(head, len)) return false;
        int i = skipBomAndSpace(head, len);
        if (i < len) {
            byte b = head[i];
            if (b == '<' || b == '{' || b == '[') return true;
        }
        String ct = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (!looksLikeText(head, len)) return false;
        return ct.startsWith("text/html") || ct.startsWith("application/json")
                || ct.startsWith("text/xml") || ct.startsWith("application/xml");
    }

    /** 清洗 URL：去掉 '$描述' 与 '|请求头'，得到真正要请求的地址（规则见 StreamUrl） */
    private String cleanUrl(String url) {
        String u = StreamUrl.playUrl(url);
        return u == null ? "" : u;
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

    private static void disconnectQuietly(HttpURLConnection conn) {
        if (conn != null) {
            try {
                conn.disconnect();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 更新频道最佳指标：最快速度、最优分辨率、最高综合评分。
     * 综合评分从测速缓存里查询同 URL 的结果。
     */
    private void updateChannelBestMetrics(LiveChannel channel, long roundStart) {
        if (channel.getSourceUrls() == null || channel.getSourceUrls().isEmpty()) return;

        double bestSpeed = 0;
        int bestScore = 0;
        String bestResolution = null;
        for (String url : channel.getSourceUrls()) {
            SpeedTestResult r = resultCache.get(url);
            // 只看本轮测出的结果：本轮没测到的线路，旧评分可能早已失效
            if (r == null || r.getTestTime() < roundStart) continue;
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
     * 未在本轮测速结果中出现的 URL（未测试）保持不变：缓存里 testTime 早于本轮开始的结果
     * （超出每频道配额没测到、上一轮残留、从 Hawk 恢复的旧失败）一律视为未测，不据此删除。
     *
     * <p>注意这里"保留全部不可用线路"只是<b>保留数据</b>，便于下轮测速重试与
     * 线路管理界面查看；该频道在展示列表与本地节目列表中仍会被隐藏
     * （见 ChannelManager#hasPlayableSource / #pickBestSource），
     * 因此不会出现"点进去只能黑屏"的频道。
     */
    private void removeUnavailableSources(LiveChannel channel, long roundStart, ChannelManager channelManager) {
        if (channel == null || channel.getSourceUrls() == null || channel.getSourceUrls().isEmpty()) return;
        List<String> urls = new ArrayList<>(channel.getSourceUrls());
        List<String> toRemove = new ArrayList<>();
        int tested = 0;
        for (String url : urls) {
            SpeedTestResult r = resultCache.get(url);
            // 只认本轮的结果：旧失败可能是几天前的网络波动，线路早已恢复
            if (r == null || r.getTestTime() < roundStart) continue;
            // UNTESTABLE（RTSP/RTMP 等流式协议）只是无法测速，线路本身可能可播，不能删
            if (UNTESTABLE.equals(r.getFailureType())) continue;
            tested++;
            if (!r.isAvailable()) toRemove.add(url);
        }
        if (toRemove.isEmpty()) return;
        // "全部失败"按本轮实际测到的线路算（置顶线路的失败也计入；置顶线路在下面删除时再跳过）：本轮测到的全挂了，多半是网络临时波动，一条都不删
        if (toRemove.size() >= tested) {
            Timber.w("频道 %s 本轮测到的线路全部不可用，保留原有线路避免误删", channel.getChannelName());
            return;
        }
        int removed = 0;
        for (String url : toRemove) {
            // 用户置顶的线路不删：用户确认能播，测速可能因缺请求头/阈值误判；删掉后置顶偏好随之静默失效
            if (channelManager != null) {
                try {
                    if (channelManager.isSourcePinned(channel, url)) continue;
                } catch (Throwable ignore) {
                }
            }
            if (channel.removeSource(url)) removed++;
        }
        if (removed > 0) Timber.d("频道 %s 移除 %d 条不可用线路", channel.getChannelName(), removed);
    }

    /**
     * 本轮是否疑似网络不可用（统计本轮结果，同一 URL 只算一次，不含无法测速的线路），
     * 判定规则复用 {@link ChannelManager#isNetworkDownRound}：没有任何可用线路，
     * 或可用不足 2% 且 90% 以上的失败是网络类（DNS/连接/超时）。
     */
    private static boolean isNetworkDownRound(List<SpeedTestResult> results) {
        List<SpeedTestResult> snapshot;
        synchronized (results) {
            snapshot = new ArrayList<>(results);
        }
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        long tested = 0, ok = 0, netFail = 0;
        for (SpeedTestResult r : snapshot) {
            if (r == null || r.getUrl() == null || !seen.add(r.getUrl())) continue;
            if (UNTESTABLE.equals(r.getFailureType())) continue;
            tested++;
            if (r.isAvailable()) {
                ok++;
            } else if (ChannelManager.isNetworkFailure(r.getFailureType())) {
                netFail++;
            }
        }
        return ChannelManager.isNetworkDownRound(new long[]{tested, ok, netFail, 0});
    }

    /**
     * 按综合评分对频道的线路重排。
     * 只用本轮结果：本轮可用的按评分在前；本轮未测（含旧结果）与不可测速的记 0 保持相对顺序；
     * 本轮测得不可用的记 -1 排到最后。旧的 90 分不会压过本轮刚测的 70 分。
     */
    private void sortChannelByScores(LiveChannel channel, long roundStart) {
        if (channel.getSourceUrls() == null) return;
        List<Integer> scores = new ArrayList<>();
        for (String url : channel.getSourceUrls()) {
            SpeedTestResult r = resultCache.get(url);
            int score;
            if (r == null || r.getTestTime() < roundStart || UNTESTABLE.equals(r.getFailureType())) {
                score = 0;
            } else if (r.isAvailable()) {
                score = Math.max(0, r.getQualityScore());
            } else {
                score = -1;
            }
            scores.add(score);
        }
        channel.sortSourcesByScores(scores);
    }

    /** 关闭指定轮次的线程池（只关自己那一轮的，绝不经共享字段关到别的轮次） */
    private static void shutdownPool(ExecutorService pool) {
        try {
            if (pool != null && !pool.isShutdown()) {
                pool.shutdownNow();
            }
        } catch (Throwable ignore) {}
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
