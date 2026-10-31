package com.github.tvbox.osc.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.LiveChannel;
import com.github.tvbox.osc.bean.SpeedTestResult;

import org.greenrobot.eventbus.EventBus;

import java.util.ArrayList;
import java.util.List;

import timber.log.Timber;

/**
 * 后台测速服务
 *
 * 以 Android 前台服务方式运行，在后台对直播源进行测速
 * 支持以下触发方式：
 * 1. 应用启动时自动触发
 * 2. 应用退出时触发
 * 3. 定时周期触发
 * 4. 手动触发
 * 
 * 通过 EventBus 广播测速进度和结果
 */
public class BackgroundSpeedTestService extends Service {

    private static final String CHANNEL_ID = "speed_test_channel";
    private static final String CHANNEL_NAME = "直播源测速";
    private static final int NOTIFICATION_ID = 10086;

    /**
     * 通知最小刷新间隔（毫秒）。
     * 测速线路可达数千条、回调来自并发线程，过于频繁地刷新通知会触发
     * MIUI 等系统的通知限流，导致抛异常或 ANR。
     */
    private static final long NOTIFY_INTERVAL_MS = 800L;

    /** 上次通知刷新时间，用于节流 */
    private long lastNotifyTime;

    public static final String ACTION_START_TEST = "com.github.tvbox.osc.ACTION_START_SPEED_TEST";
    public static final String ACTION_STOP_TEST = "com.github.tvbox.osc.ACTION_STOP_SPEED_TEST";

    /** 是否在测速前先联网重新拉取全量源（在服务内部执行，避免在后台再启动前台服务） */
    public static final String EXTRA_RELOAD = "extra_reload_sources";

    /**
     * 待测速频道列表的静态持有。
     * 避免通过 Intent 序列化大列表导致 TransactionTooLargeException（Binder 1MB 限制）。
     */
    private static volatile List<LiveChannel> pendingChannels;

    /**
     * 最近一次进程内兜底测速的回调。进程内"重新测速"顶替旧一轮时，旧一轮也会收到 onCancelled，
     * 用它判断是否已有更新的进程内轮次，避免 UI 先收到"已取消"再看到新一轮进度（闪跳）。
     */
    private static volatile SpeedTestEngine.SpeedTestCallback inProcessCallback;

    private SpeedTestEngine speedTestEngine;
    private NotificationManager notificationManager;

    /**
     * 本服务当前驱动的测速回调（null 表示本服务没有在测速）。
     * 用于区分"本服务发起的测速"与其它入口（进程内兜底 / Worker）发起的测速：
     * onDestroy 只取消前者；重复启动导致的 stopSelf 也不会误取消正在进行的测速。
     * 用回调对象而不是 boolean，是因为"重新测速"会在旧一轮未回调前开始新一轮，
     * 只有当前这一轮的回调才允许清除标记。
     */
    private volatile SpeedTestEngine.SpeedTestCallback activeCallback;

    /**
     * 用户已取消 / 服务已销毁。
     * 拉源阶段取消时引擎尚未开始测速，cancel() 拦不住稍后到来的拉源回调；
     * 测速取消后在途任务也仍会回调进度。若不拦截，会在已销毁的服务上继续测速，
     * 并重新贴出一条无法划掉的"正在测速"常驻通知。
     */
    private volatile boolean stopped;

    /**
     * 最近一次 onStartCommand 收到的 startId。
     * 旧一轮的收尾路径用 stopSelfResult(latestStartId) 结束服务：若期间又来了新的开始指令，
     * 系统会因 startId 不是最新而拒绝停止，避免经 onDestroy 置 stopped 让新一轮被静默丢弃。
     */
    private volatile int latestStartId;

    /**
     * 尚未回调 onReady 的拉源请求数（仅在 reloadLock 内读写）。
     * 用户在旧一轮测速中再次点击"立即测速"时，新请求要先拉源数十秒；旧一轮若在此期间结束，
     * 不能停止服务，也不能向 UI 广播终态，否则新请求被静默吞掉、UI 只显示旧一轮的"完成"。
     */
    private int pendingReloads;

    /** 拉源请求代次：ACTION_STOP_TEST 时递增，使被取消的旧拉源回调不会误减新请求的计数 */
    private int reloadGeneration;

    private final Object reloadLock = new Object();

    /** 登记一次拉源请求，返回其代次 */
    private int beginReload() {
        synchronized (reloadLock) {
            pendingReloads++;
            return reloadGeneration;
        }
    }

    /** 拉源请求结束（onReady 到来）；代次已过期（期间被用户取消）时不计数 */
    private void endReload(int generation) {
        synchronized (reloadLock) {
            if (generation == reloadGeneration && pendingReloads > 0) pendingReloads--;
        }
    }

    /** 是否还有新的拉源请求在途（此时旧一轮收尾不得停止服务 / 广播终态） */
    private boolean hasPendingReload() {
        synchronized (reloadLock) {
            return pendingReloads > 0;
        }
    }

    /** 用户显式取消：作废所有在途拉源请求 */
    private void resetReloads() {
        synchronized (reloadLock) {
            pendingReloads = 0;
            reloadGeneration++;
        }
    }

    /**
     * 旧一轮结束但仍有新请求在拉源：保持前台与"准备中"状态，
     * 让 UI 继续显示新请求的准备进度，而不是旧一轮的终态。
     */
    private void keepAliveForPendingReload() {
        com.github.tvbox.osc.util.RunLog.i("旧一轮测速已结束，新的测速请求仍在拉源，保持服务运行");
        // 绕过节流：旧一轮刚刷过"测速完成"，不重置会被节流吞掉，通知停在"完成"
        synchronized (this) {
            lastNotifyTime = 0;
        }
        updateNotification("正在获取最新全量线路...", 0, 0);
        postSpeedTestEvent(new SpeedTestPrepareEvent("正在获取最新全量线路...", 0, 0));
    }

    /** 本服务是否正在驱动一轮测速 */
    private boolean isTesting() {
        return activeCallback != null && speedTestEngine != null && speedTestEngine.isRunning();
    }

    // ============ 事件类 ============

    /** 测速进度事件 */
    public static class SpeedTestProgressEvent {
        public final int current;
        public final int total;
        public final String channelName;
        public final SpeedTestResult latestResult;

        public SpeedTestProgressEvent(int current, int total, String channelName, SpeedTestResult latestResult) {
            this.current = current;
            this.total = total;
            this.channelName = channelName;
            this.latestResult = latestResult;
        }
    }

    /** 测速完成事件 */
    public static class SpeedTestCompleteEvent {
        public final List<SpeedTestResult> results;
        public final long totalTime;
        public final boolean cancelled;

        public SpeedTestCompleteEvent(List<SpeedTestResult> results, long totalTime, boolean cancelled) {
            this.results = results;
            this.totalTime = totalTime;
            this.cancelled = cancelled;
        }
    }

    /**
     * 单个频道测速完成事件：该频道的测速值与线路排序已更新，界面可只刷新这一个频道。
     * channel 与展示列表共用同一对象（展示分组按引用持有原始频道）。
     */
    public static class SpeedTestChannelEvent {
        public final LiveChannel channel;
        public final int completedChannels;
        public final int totalChannels;

        public SpeedTestChannelEvent(LiveChannel channel, int completedChannels, int totalChannels) {
            this.channel = channel;
            this.completedChannels = completedChannels;
            this.totalChannels = totalChannels;
        }
    }

    /** 测速错误事件 */
    public static class SpeedTestErrorEvent {
        public final String errorMsg;

        public SpeedTestErrorEvent(String errorMsg) {
            this.errorMsg = errorMsg;
        }
    }

    /**
     * 测速<b>准备阶段</b>（联网拉取全量线路）的进度事件。
     *
     * 这一阶段可能持续数十秒且线路总数未知，与真正的测速进度分开广播，
     * 让 UI 能显示"正在获取源..."而不是停在空白进度条上。
     */
    public static class SpeedTestPrepareEvent {
        public final String message;
        public final int current;
        public final int total;

        public SpeedTestPrepareEvent(String message, int current, int total) {
            this.message = message;
            this.current = current;
            this.total = total;
        }
    }

    // ============ 生命周期 ============

    @Override
    public void onCreate() {
        super.onCreate();
        speedTestEngine = SpeedTestEngine.getInstance();
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createNotificationChannel();
        Timber.d("后台测速服务已创建");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        latestStartId = startId;
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        String action = intent.getAction();

        if (ACTION_STOP_TEST.equals(action)) {
            // 先置位再取消：拉源阶段的回调看到 stopped 后不再启动测速
            stopped = true;
            // 显式取消胜过一切：在途拉源请求一并作废，其稍后到来的 onReady 看到 stopped 后跳过
            resetReloads();
            // 按"本服务是否驱动着一轮"判断，而不是引擎是否在跑：拉源阶段若有其它入口（Worker）
            // 的一轮在跑，cancel() 取消的是那一轮，其回调不发 EventBus，UI 仍需要这里补发
            boolean ownsRound = isTesting();
            speedTestEngine.cancel();
            if (!ownsRound) {
                // 拉源阶段取消：本服务没有轮次可派发 onCancelled，这里补发"已取消"，
                // 否则设置页/播放页会一直停在"正在获取最新全量线路..."
                postSpeedTestEvent(new SpeedTestCompleteEvent(new ArrayList<>(), 0, true));
            }
            safeStopSelf(true);
            return START_NOT_STICKY;
        }

        if (ACTION_START_TEST.equals(action)) {
            // 同一实例上收到新的开始指令（stopSelf 尚未销毁本实例）：恢复可用
            stopped = false;
            // 立即进入前台（本方法在用户点击时同步触发，此刻 App 处于前台，
            // 从而规避“在后台启动前台服务被拒”的问题）。startForeground 异常不致崩溃。
            try {
                startForeground(NOTIFICATION_ID, createNotification("正在准备测速...", 0, 0));
                com.github.tvbox.osc.util.RunLog.i("startForeground 成功");
            } catch (Throwable t) {
                com.github.tvbox.osc.util.RunLog.i("startForeground 失败: " + t);
                Timber.w(t, "startForeground 失败，继续在后台执行测速");
            }

            boolean reload = intent.getBooleanExtra(EXTRA_RELOAD, false);
            com.github.tvbox.osc.util.RunLog.i("收到测速指令 reload=" + reload);
            if (reload) {
                // 在已启动的前台服务内联网拉取全量源，再测速，避免耗时网络后再启动前台服务
                updateNotification("正在获取最新全量线路...", 0, 0);
                // 拉源阶段可能持续数十秒，必须把进度广播给 UI，
                // 否则设置页/播放页的右上角进度条会长时间停在初始状态，看起来像"卡死没反应"
                postSpeedTestEvent(new SpeedTestPrepareEvent("正在获取最新全量线路...", 0, 0));
                // 登记在途拉源：旧一轮若在拉源期间结束，不得停止服务（见 hasPendingReload）
                final int reloadGen = beginReload();
                // 出队只能发生一次：各出口（无线路/异常/已取消/新一轮登记完成）都可能触发
                final java.util.concurrent.atomic.AtomicBoolean reloadReleased =
                        new java.util.concurrent.atomic.AtomicBoolean(false);
                final Runnable releaseReload = () -> {
                    if (reloadReleased.compareAndSet(false, true)) endReload(reloadGen);
                };
                try {
                    com.github.tvbox.osc.api.ChannelManager
                            .getInstance(getApplicationContext())
                            .reloadForSpeedTest(new com.github.tvbox.osc.api.ChannelManager.SpeedTestReloadCallback() {
                                @Override
                                public void onReady(ArrayList<LiveChannel> channels) {
                                    // 不能一进来就出队：新一轮登记 activeCallback 之前若旧一轮恰好结束，
                                    // 会因"无在途拉源"而 stopSelfResult(latestStartId) 成功，经 onDestroy 取消刚启动的新一轮。
                                    // 因此在新一轮登记后（performSpeedTest 内）或各收尾出口之前再出队
                                    try {
                                        com.github.tvbox.osc.util.RunLog.i("测速准备 onReady: "
                                                + (channels == null ? 0 : channels.size()) + " 个频道");
                                        if (stopped) {
                                            // 拉源期间用户已取消（或服务已销毁）：不再在已停止的服务上测速。
                                            // "已取消"事件已在 ACTION_STOP_TEST 中补发，拉源结果已由 ChannelManager 落库
                                            com.github.tvbox.osc.util.RunLog.i("测速已取消，跳过拉源后的测速");
                                            return;
                                        }
                                        if (channels == null || channels.isEmpty()) {
                                            releaseReload.run();
                                            postSpeedTestEvent(new SpeedTestErrorEvent("没有可测速的线路"));
                                            stopSelfIfIdle();
                                        } else {
                                            // 直接复用已构建好的列表，避免低内存设备上再多一份全量拷贝导致 OOM
                                            performSpeedTest(channels, releaseReload);
                                        }
                                    } catch (Throwable t) {
                                        // 测速准备收尾阶段任何异常（含 OOM）都不能逃逸到拉源线程，否则直接闪退
                                        Timber.e(t, "测速准备完成回调异常");
                                        releaseReload.run();
                                        postSpeedTestEvent(new SpeedTestErrorEvent("准备测速失败: " + t.getMessage()));
                                        stopSelfIfIdle();
                                    } finally {
                                        // 兜底（如已取消提前 return）：保证计数不泄漏
                                        releaseReload.run();
                                    }
                                }

                                @Override
                                public void onProgress(int current, int total, String message) {
                                    // 已取消：不再刷新通知 / 广播准备进度，避免 UI 重新出现进度条
                                    if (stopped) return;
                                    updateNotification(message, current, total);
                                    postSpeedTestEvent(
                                            new SpeedTestPrepareEvent(message, current, total));
                                }
                            });
                } catch (Throwable t) {
                    // 拉源请求同步抛出（如线程池拒绝、OOM）时 onReady 永远不会来：
                    // 必须出队，否则 pendingReloads 泄漏，服务永远停不下来、通知卡在"正在获取..."
                    Timber.e(t, "发起拉源失败");
                    com.github.tvbox.osc.util.RunLog.i("发起拉源失败: " + t);
                    releaseReload.run();
                    postSpeedTestEvent(new SpeedTestErrorEvent("获取线路失败: " + t.getMessage()));
                    stopSelfIfIdle();
                }
                return START_NOT_STICKY;
            }

            // 从静态持有获取频道数据
            List<LiveChannel> channels = pendingChannels;
            pendingChannels = null;

            if (channels == null || channels.isEmpty()) {
                Timber.w("没有频道数据，停止服务");
                stopSelfIfIdle();
                return START_NOT_STICKY;
            }

            performSpeedTest(channels);
        }

        return START_NOT_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // 之后到来的进度回调不得再贴通知（服务已不在前台，贴出的常驻通知无人能移除）
        stopped = true;
        try {
            // 只取消本服务发起的测速：其它入口（进程内兜底测速等）发起的测速不受服务销毁影响
            if (activeCallback != null && speedTestEngine != null && speedTestEngine.isRunning()) {
                speedTestEngine.cancel();
            }
            activeCallback = null;
        } catch (Throwable t) {
            Timber.w(t, "取消测速失败");
        }
        Timber.d("后台测速服务已销毁");
    }

    /**
     * 本服务没有在测速时才结束服务。
     * 重复点击"测速"时第二次启动可能拿不到频道数据（或拉源失败），此时若直接 stopSelf，
     * 会经 onDestroy 把第一次发起、仍在进行的测速取消掉。
     */
    private void stopSelfIfIdle() {
        if (isTesting()) {
            Timber.d("本服务的测速仍在进行，保持服务运行");
            return;
        }
        if (hasPendingReload()) {
            Timber.d("仍有测速请求在拉源，保持服务运行");
            return;
        }
        safeStopSelf(true, true);
    }

    /** 本轮回调是否仍是本服务当前驱动的测速；是则清除标记 */
    private boolean finishActive(SpeedTestEngine.SpeedTestCallback cb) {
        if (activeCallback == cb) {
            activeCallback = null;
            return true;
        }
        return false;
    }

    /**
     * 测速被取消时，引擎已对已测部分做过线路重排/过滤，写回数据库，
     * 否则下次冷启动从缓存加载时这些变化丢失。
     */
    private static void persistChannelsQuietly(Context ctx) {
        try {
            com.github.tvbox.osc.api.ChannelManager.getInstance(ctx).persistChannels();
        } catch (Throwable e) {
            Timber.w(e, "保存测速后的频道数据失败");
        }
    }

    /** 安全地停止前台状态并结束服务（部分定制系统上这些调用可能抛异常） */
    private void safeStopSelf(boolean removeNotification) {
        safeStopSelf(removeNotification, false);
    }

    /**
     * @param onlyIfLatest true：轮次收尾路径，用 stopSelfResult(latestStartId)，
     *                     期间若又来了新的开始指令则不停止（新指令会自行收尾）；
     *                     false：显式取消，无条件停止。
     */
    private void safeStopSelf(boolean removeNotification, boolean onlyIfLatest) {
        try {
            stopForeground(removeNotification);
        } catch (Throwable t) {
            Timber.w(t, "stopForeground 失败");
        }
        if (removeNotification) {
            // stopForeground 对非前台实例无效（例如仅为处理"取消"而新建的实例，
            // 或离开前台后又被重新 notify 的通知），显式移除一次
            try {
                if (notificationManager != null) notificationManager.cancel(NOTIFICATION_ID);
            } catch (Throwable t) {
                Timber.w(t, "移除测速通知失败");
            }
        }
        try {
            if (onlyIfLatest) {
                if (!stopSelfResult(latestStartId)) {
                    Timber.d("已有更新的测速指令，保持服务运行");
                }
            } else {
                stopSelf();
            }
        } catch (Throwable t) {
            Timber.w(t, "stopSelf 失败");
        }
    }

    // ============ 测速逻辑 ============

    private void performSpeedTest(List<LiveChannel> channels) {
        performSpeedTest(channels, null);
    }

    /**
     * @param onRegistered 新一轮登记为 activeCallback 之后、真正启动之前执行（用于拉源请求出队）。
     *                     必须在启动前：引擎可能同步回调 onError，若此时仍算"在途拉源"，
     *                     本轮收尾会误以为还有新请求而保持服务，导致服务永远不停
     */
    private void performSpeedTest(List<LiveChannel> channels, @Nullable Runnable onRegistered) {
        SpeedTestEngine.SpeedTestCallback callback = new SpeedTestEngine.SpeedTestCallback() {
            @Override
            public void onItemComplete(SpeedTestResult result, int current, int total) {
                // 已取消/已被本服务的新一轮接管：旧一轮的迟到进度不再广播，避免 UI 进度回跳
                if (activeCallback != this) return;
                // 通过 EventBus 广播进度
                postSpeedTestEvent(new SpeedTestProgressEvent(
                        current, total, result.getChannelName(), result
                ));
            }

            @Override
            public void onAllComplete(List<SpeedTestResult> results, long totalTime) {
                boolean mine = finishActive(this);
                // 更新通知（仅本服务当前驱动的一轮；否则通知已不归这一轮所有）
                if (mine) updateNotification("测速完成", results.size(), results.size());

                // 测速完成后：为整体订阅源每个频道选出最佳线路，
                // 生成本地播放列表并持久化（数据库 + 本地文件）
                try {
                    com.github.tvbox.osc.api.ChannelManager
                            .getInstance(getApplicationContext())
                            .persistAfterSpeedTest();
                } catch (Throwable e) {
                    // 低内存设备上生成本地列表可能 OOM；必须兜住，
                    // 否则完成事件无法广播、UI 进度条会一直卡住
                    Timber.w(e, "测速完成后持久化本地播放列表失败");
                }

                // 广播完成事件。本服务已开始新一轮（activeCallback 为新回调）时不发：
                // 否则 UI 会先显示"完成"再看到新一轮进度，造成闪跳
                // 仍有新请求在拉源时也不发：否则 UI 只看到旧一轮"完成"，新请求像被吞掉
                boolean reloadPending = hasPendingReload();
                if ((mine || activeCallback == null) && !reloadPending) {
                    postSpeedTestEvent(new SpeedTestCompleteEvent(results, totalTime, false));
                }

                // 统计结果
                int available = 0;
                double avgSpeed = 0;
                for (SpeedTestResult r : results) {
                    if (r.isAvailable()) {
                        available++;
                        avgSpeed += r.getSpeed();
                    }
                }
                if (available > 0) avgSpeed /= available;

                Timber.i("后台测速完成: %d/%d 可用, 平均速度 %.1f KB/s, 耗时 %d ms",
                        available, results.size(), avgSpeed, totalTime);

                // 延迟停止服务（已被新一轮接管时不停，否则会经 onDestroy 取消新一轮）
                if (mine && reloadPending) {
                    // 新请求仍在拉源：保持前台与"准备中"状态，由新请求的 onReady 接着测速
                    keepAliveForPendingReload();
                } else if (mine) {
                    // 先脱离前台再贴可划掉的完成通知：前台通知是常驻的，
                    // 直接 stopForeground(false) 会留下一条带"取消"按钮、无法划掉的"测速完成"
                    try {
                        stopForeground(false);
                    } catch (Throwable t) {
                        Timber.w(t, "stopForeground 失败");
                    }
                    postFinishedNotification("测速完成: " + available + "/" + results.size() + " 可用");
                    safeStopSelf(false, true);
                }
            }

            @Override
            public void onError(String errorMsg) {
                boolean mine = finishActive(this);
                postSpeedTestEvent(new SpeedTestErrorEvent(errorMsg));
                if (!mine) return;
                if (hasPendingReload()) {
                    keepAliveForPendingReload();
                } else {
                    safeStopSelf(true, true);
                }
            }

            @Override
            public void onProgress(int current, int total, String currentChannel) {
                // 已取消/已被新一轮接管：在途任务的迟到进度不再刷新通知
                if (activeCallback != this) return;
                updateNotification("正在测速: " + currentChannel, current, total);
            }

            @Override
            public void onChannelComplete(LiveChannel channel, int completedChannels, int totalChannels) {
                // 已取消/已被本服务的新一轮接管：旧一轮的迟到结果不再广播
                if (activeCallback != this) return;
                postSpeedTestEvent(new SpeedTestChannelEvent(channel, completedChannels, totalChannels));
            }

            @Override
            public void onCancelled(int completed, int total) {
                boolean mine = finishActive(this);
                if (!mine && activeCallback != null) {
                    // 被本服务自己的"重新测速"顶替：新一轮仍在进行并会自行广播进度/结果，
                    // 这里静默结束，否则 UI 先收到"已取消"隐藏进度条再重新出现，造成闪跳
                    Timber.d("旧一轮测速已被本服务新一轮接管，静默结束");
                    return;
                }
                // 本服务的一轮被取消，或被其它入口（Worker / 进程内）顶替：都必须收尾，
                // 否则前台"正在测速"通知与 UI 进度条会一直挂着
                // 取消前已完成的部分同样会重排/过滤线路，一并保存
                persistChannelsQuietly(getApplicationContext());
                if (mine && hasPendingReload()) {
                    // 新请求仍在拉源：不广播"已取消"（否则 UI 收起进度条），保持服务等待新一轮
                    keepAliveForPendingReload();
                    return;
                }
                postSpeedTestEvent(new SpeedTestCompleteEvent(new ArrayList<>(), 0, true));
                if (mine) safeStopSelf(true, true);
            }
        };
        // 先登记再启动：引擎可能同步回调 onError（如无可测线路）
        activeCallback = callback;
        if (onRegistered != null) onRegistered.run();
        speedTestEngine.testChannels(channels, callback);
        // onReady 在拉源线程上调用，与主线程的 ACTION_STOP_TEST / onDestroy 存在竞态：
        // 若在上面登记之前已被取消/销毁，cancel() 当时拦不住这一轮。此时本轮已登记，
        // 补一次按回调取消，本轮会以 onCancelled 收尾并结束服务
        if (stopped) {
            com.github.tvbox.osc.util.RunLog.i("测速启动时服务已停止，取消本轮");
            speedTestEngine.cancel(callback);
        }
    }

    // ============ 通知管理 ============

    private void createNotificationChannel() {
        // MIUI 等定制系统在通知受限时可能抛异常，这里整体兜住，失败仅退化为无通知
        try {
            if (notificationManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW
                );
                channel.setDescription("直播源后台测速通知");
                channel.setShowBadge(false);
                notificationManager.createNotificationChannel(channel);
            }
        } catch (Throwable t) {
            Timber.w(t, "创建通知渠道失败（可能被系统限制）");
        }
    }

    private Notification createNotification(String text, int current, int total) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("直播源测速")
                .setContentText(text)
                // 使用应用自带的单色通知图标：部分 MIUI 版本对自适应启动图标或
                // android.R.drawable.* 的跨进程引用会报 "Bad notification for startForeground"
                .setSmallIcon(R.drawable.ic_notification)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setAutoCancel(false);

        if (total > 0) {
            builder.setProgress(total, current, false);
        } else {
            builder.setProgress(0, 0, true);
        }

        // 添加取消按钮（PendingIntent 构建失败不影响通知本身）
        try {
            Intent stopIntent = new Intent(this, BackgroundSpeedTestService.class);
            stopIntent.setAction(ACTION_STOP_TEST);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, flags);
            builder.addAction(R.drawable.ic_notification, "取消", stopPendingIntent);
        } catch (Throwable t) {
            Timber.w(t, "构建取消操作失败，忽略");
        }

        return builder.build();
    }

    /**
     * 更新通知。
     *
     * <p>做了两层保护：
     * <ul>
     *   <li><b>节流</b>：测速线路可达数千条且回调来自并发线程，若每条都刷新通知，
     *       MIUI 等系统的通知限流会抛异常甚至 ANR。此处限制最快{@link #NOTIFY_INTERVAL_MS} 一次。</li>
     *   <li><b>异常兜底</b>：notify() 在通知被禁用/受限时可能抛 SecurityException 等，一律吞掉。</li>
     * </ul>
     */
    private synchronized void updateNotification(String text, int current, int total) {
        // 已取消/已销毁：服务不在前台，此时贴出的常驻通知没有任何路径能移除
        if (stopped) return;
        boolean isFinal = total > 0 && current >= total;
        long now = System.currentTimeMillis();
        if (!isFinal && now - lastNotifyTime < NOTIFY_INTERVAL_MS) {
            return;
        }
        lastNotifyTime = now;
        try {
            if (notificationManager == null) return;
            notificationManager.notify(NOTIFICATION_ID, createNotification(text, current, total));
        } catch (Throwable t) {
            Timber.w(t, "更新通知失败（可能被系统限制），继续测速");
        }
    }

    /**
     * 测速完成通知：可划掉、点击后自动消失，不带进度条和"取消"按钮。
     * 调用前须已 stopForeground，否则系统仍把它当作前台常驻通知。
     */
    private void postFinishedNotification(String text) {
        try {
            if (notificationManager == null || stopped) return;
            Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("直播源测速")
                    .setContentText(text)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setOngoing(false)
                    .setAutoCancel(true)
                    .build();
            notificationManager.notify(NOTIFICATION_ID, n);
        } catch (Throwable t) {
            Timber.w(t, "更新完成通知失败（可能被系统限制）");
        }
    }

    // ============ 静态工具方法 ============

    /**
     * 启动测速：先联网拉取全量源（远程订阅 + 自定义），再对全部线路测速。
     *
     * 应在前台（用户点击时）调用，服务会立即进入前台，随后在服务内部完成联网拉取，
     * 避免“先联网再启动前台服务”在 Android 12+ 后台启动被拒的问题。
     * 若前台服务启动失败（部分 OEM 限制），自动回退到进程内直接测速。
     */
    public static void startSpeedTestWithReload(Context context) {
        Intent intent = new Intent(context, BackgroundSpeedTestService.class);
        intent.setAction(ACTION_START_TEST);
        intent.putExtra(EXTRA_RELOAD, true);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Throwable t) {
            Timber.w(t, "启动前台测速服务失败，改为进程内测速");
            final Context appCtx = context.getApplicationContext();
            postSpeedTestEvent(new SpeedTestPrepareEvent("正在获取最新全量线路...", 0, 0));
            com.github.tvbox.osc.api.ChannelManager.getInstance(appCtx)
                    .reloadForSpeedTest(new com.github.tvbox.osc.api.ChannelManager.SpeedTestReloadCallback() {
                        @Override
                        public void onReady(ArrayList<LiveChannel> channels) {
                            try {
                                runInProcess(appCtx, channels);
                            } catch (Throwable t) {
                                Timber.e(t, "进程内测速启动异常");
                                postSpeedTestEvent(new SpeedTestErrorEvent("准备测速失败: " + t.getMessage()));
                            }
                        }

                        @Override
                        public void onProgress(int current, int total, String message) {
                            postSpeedTestEvent(
                                    new SpeedTestPrepareEvent(message, current, total));
                        }
                    });
        }
    }

    /**
     * 启动后台测速服务（使用已准备好的频道列表）
     */
    public static void startSpeedTest(Context context, ArrayList<LiveChannel> channels) {
        // 通过静态持有传递，避免 Intent 序列化大列表导致的崩溃
        pendingChannels = channels;

        Intent intent = new Intent(context, BackgroundSpeedTestService.class);
        intent.setAction(ACTION_START_TEST);

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Throwable t) {
            Timber.w(t, "启动前台测速服务失败，改为进程内测速");
            pendingChannels = null;
            runInProcess(context.getApplicationContext(), channels);
        }
    }

    /**
     * 进程内直接测速（前台服务不可用时的兜底路径）。
     * 不依赖 Service 生命周期，仍通过 EventBus 广播进度/结果并持久化最佳线路本地列表。
     */
    private static void runInProcess(Context appCtx, List<LiveChannel> channels) {
        if (channels == null || channels.isEmpty()) {
            postSpeedTestEvent(new SpeedTestErrorEvent("没有可测速的线路"));
            return;
        }
        SpeedTestEngine.SpeedTestCallback callback = new SpeedTestEngine.SpeedTestCallback() {
            @Override
            public void onItemComplete(SpeedTestResult result, int current, int total) {
                postSpeedTestEvent(new SpeedTestProgressEvent(
                        current, total, result.getChannelName(), result));
            }

            @Override
            public void onAllComplete(List<SpeedTestResult> results, long totalTime) {
                try {
                    com.github.tvbox.osc.api.ChannelManager.getInstance(appCtx).persistAfterSpeedTest();
                } catch (Throwable e) {
                    Timber.w(e, "进程内测速完成后持久化失败");
                }
                postSpeedTestEvent(new SpeedTestCompleteEvent(results, totalTime, false));
            }

            @Override
            public void onError(String errorMsg) {
                postSpeedTestEvent(new SpeedTestErrorEvent(errorMsg));
            }

            @Override
            public void onProgress(int current, int total, String currentChannel) {
                // 无前台通知，忽略
            }

            @Override
            public void onChannelComplete(LiveChannel channel, int completedChannels, int totalChannels) {
                if (inProcessCallback != this) return;
                postSpeedTestEvent(new SpeedTestChannelEvent(channel, completedChannels, totalChannels));
            }

            @Override
            public void onCancelled(int completed, int total) {
                SpeedTestEngine.SpeedTestCallback latest = inProcessCallback;
                if (latest != null && latest != this) {
                    // 被更新的进程内一轮顶替：新一轮会自行广播进度/结果，这里静默结束
                    return;
                }
                // 取消前已完成的部分同样会重排/过滤线路，一并保存
                persistChannelsQuietly(appCtx);
                postSpeedTestEvent(new SpeedTestCompleteEvent(new ArrayList<>(), 0, true));
            }
        };
        // 先登记再启动：引擎可能同步回调（如无可测线路）
        inProcessCallback = callback;
        SpeedTestEngine.getInstance().testChannels(channels, callback);
    }

    /**
     * 停止后台测速服务
     */
    /**
     * 是否有测速任务处于"准备 / 测速中"（含拉取全量线路阶段，此时引擎尚未 isRunning）。
     * 由所有测速事件的统一出口维护：准备/进度事件置忙，完成/错误事件置闲；
     * 设置页在 onStop 期间会注销 EventBus 错过终态事件，回到前台时据此决定是否收起进度条。
     */
    private static volatile boolean sBusy = false;

    public static boolean isBusy() {
        return sBusy;
    }

    /** 测速事件统一出口：先更新忙闲状态再广播，保证订阅方收到事件时 isBusy() 已是最新值 */
    private static void postSpeedTestEvent(Object event) {
        if (event instanceof SpeedTestPrepareEvent || event instanceof SpeedTestProgressEvent
                || event instanceof SpeedTestChannelEvent) {
            sBusy = true;
        } else if (event instanceof SpeedTestCompleteEvent || event instanceof SpeedTestErrorEvent) {
            sBusy = false;
        }
        EventBus.getDefault().post(event);
    }

    public static void stopSpeedTest(Context context) {
        Intent intent = new Intent(context, BackgroundSpeedTestService.class);
        intent.setAction(ACTION_STOP_TEST);
        try {
            context.startService(intent);
        } catch (Throwable t) {
            // 应用在后台时 startService 会抛 IllegalStateException（后台启动限制）；
            // 直接取消引擎即可，正在运行的服务会经 onCancelled 自行结束
            Timber.w(t, "停止测速服务失败，直接取消测速");
            SpeedTestEngine.getInstance().cancel();
        }
    }
}
