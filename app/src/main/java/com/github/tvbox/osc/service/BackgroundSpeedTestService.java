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

    private SpeedTestEngine speedTestEngine;
    private NotificationManager notificationManager;

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
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        String action = intent.getAction();

        if (ACTION_STOP_TEST.equals(action)) {
            speedTestEngine.cancel();
            safeStopSelf(true);
            return START_NOT_STICKY;
        }

        if (ACTION_START_TEST.equals(action)) {
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
                EventBus.getDefault().post(new SpeedTestPrepareEvent("正在获取最新全量线路...", 0, 0));
                com.github.tvbox.osc.api.ChannelManager
                        .getInstance(getApplicationContext())
                        .reloadForSpeedTest(new com.github.tvbox.osc.api.ChannelManager.SpeedTestReloadCallback() {
                            @Override
                            public void onReady(ArrayList<LiveChannel> channels) {
                                try {
                                    com.github.tvbox.osc.util.RunLog.i("测速准备 onReady: "
                                            + (channels == null ? 0 : channels.size()) + " 个频道");
                                    if (channels == null || channels.isEmpty()) {
                                        EventBus.getDefault().post(new SpeedTestErrorEvent("没有可测速的线路"));
                                        safeStopSelf(true);
                                    } else {
                                        // 直接复用已构建好的列表，避免低内存设备上再多一份全量拷贝导致 OOM
                                        performSpeedTest(channels);
                                    }
                                } catch (Throwable t) {
                                    // 测速准备收尾阶段任何异常（含 OOM）都不能逃逸到拉源线程，否则直接闪退
                                    Timber.e(t, "测速准备完成回调异常");
                                    EventBus.getDefault().post(new SpeedTestErrorEvent("准备测速失败: " + t.getMessage()));
                                    safeStopSelf(true);
                                }
                            }

                            @Override
                            public void onProgress(int current, int total, String message) {
                                updateNotification(message, current, total);
                                EventBus.getDefault().post(
                                        new SpeedTestPrepareEvent(message, current, total));
                            }
                        });
                return START_NOT_STICKY;
            }

            // 从静态持有获取频道数据
            List<LiveChannel> channels = pendingChannels;
            pendingChannels = null;

            if (channels == null || channels.isEmpty()) {
                Timber.w("没有频道数据，停止服务");
                stopSelf();
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
        try {
            if (speedTestEngine != null && speedTestEngine.isRunning()) {
                speedTestEngine.cancel();
            }
        } catch (Throwable t) {
            Timber.w(t, "取消测速失败");
        }
        Timber.d("后台测速服务已销毁");
    }

    /** 安全地停止前台状态并结束服务（部分定制系统上这些调用可能抛异常） */
    private void safeStopSelf(boolean removeNotification) {
        try {
            stopForeground(removeNotification);
        } catch (Throwable t) {
            Timber.w(t, "stopForeground 失败");
        }
        try {
            stopSelf();
        } catch (Throwable t) {
            Timber.w(t, "stopSelf 失败");
        }
    }

    // ============ 测速逻辑 ============

    private void performSpeedTest(List<LiveChannel> channels) {
        speedTestEngine.testChannels(channels, new SpeedTestEngine.SpeedTestCallback() {
            @Override
            public void onItemComplete(SpeedTestResult result, int current, int total) {
                // 通过 EventBus 广播进度
                EventBus.getDefault().post(new SpeedTestProgressEvent(
                        current, total, result.getChannelName(), result
                ));
            }

            @Override
            public void onAllComplete(List<SpeedTestResult> results, long totalTime) {
                // 更新通知
                updateNotification("测速完成", results.size(), results.size());

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

                // 广播完成事件
                EventBus.getDefault().post(new SpeedTestCompleteEvent(results, totalTime, false));

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

                // 延迟停止服务
                safeStopSelf(false);
            }

            @Override
            public void onError(String errorMsg) {
                EventBus.getDefault().post(new SpeedTestErrorEvent(errorMsg));
                safeStopSelf(true);
            }

            @Override
            public void onProgress(int current, int total, String currentChannel) {
                updateNotification("正在测速: " + currentChannel, current, total);
            }

            @Override
            public void onCancelled(int completed, int total) {
                EventBus.getDefault().post(new SpeedTestCompleteEvent(new ArrayList<>(), 0, true));
                safeStopSelf(true);
            }
        });
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
            EventBus.getDefault().post(new SpeedTestPrepareEvent("正在获取最新全量线路...", 0, 0));
            com.github.tvbox.osc.api.ChannelManager.getInstance(appCtx)
                    .reloadForSpeedTest(new com.github.tvbox.osc.api.ChannelManager.SpeedTestReloadCallback() {
                        @Override
                        public void onReady(ArrayList<LiveChannel> channels) {
                            try {
                                runInProcess(appCtx, channels);
                            } catch (Throwable t) {
                                Timber.e(t, "进程内测速启动异常");
                                EventBus.getDefault().post(new SpeedTestErrorEvent("准备测速失败: " + t.getMessage()));
                            }
                        }

                        @Override
                        public void onProgress(int current, int total, String message) {
                            EventBus.getDefault().post(
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
            EventBus.getDefault().post(new SpeedTestErrorEvent("没有可测速的线路"));
            return;
        }
        SpeedTestEngine.getInstance().testChannels(channels, new SpeedTestEngine.SpeedTestCallback() {
            @Override
            public void onItemComplete(SpeedTestResult result, int current, int total) {
                EventBus.getDefault().post(new SpeedTestProgressEvent(
                        current, total, result.getChannelName(), result));
            }

            @Override
            public void onAllComplete(List<SpeedTestResult> results, long totalTime) {
                try {
                    com.github.tvbox.osc.api.ChannelManager.getInstance(appCtx).persistAfterSpeedTest();
                } catch (Throwable e) {
                    Timber.w(e, "进程内测速完成后持久化失败");
                }
                EventBus.getDefault().post(new SpeedTestCompleteEvent(results, totalTime, false));
            }

            @Override
            public void onError(String errorMsg) {
                EventBus.getDefault().post(new SpeedTestErrorEvent(errorMsg));
            }

            @Override
            public void onProgress(int current, int total, String currentChannel) {
                // 无前台通知，忽略
            }

            @Override
            public void onCancelled(int completed, int total) {
                EventBus.getDefault().post(new SpeedTestCompleteEvent(new ArrayList<>(), 0, true));
            }
        });
    }

    /**
     * 停止后台测速服务
     */
    public static void stopSpeedTest(Context context) {
        Intent intent = new Intent(context, BackgroundSpeedTestService.class);
        intent.setAction(ACTION_STOP_TEST);
        context.startService(intent);
    }
}
