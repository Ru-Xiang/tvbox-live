package com.github.tvbox.osc.receiver;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import androidx.core.app.NotificationCompat;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.ui.activity.LivePlayActivity;
import com.github.tvbox.osc.util.HawkConfig;
import com.orhanobut.hawk.Hawk;

import timber.log.Timber;

/**
 * 开机自启接收器。
 *
 * 仅当用户在设置中开启「开机自动启动」时，才在系统启动完成后拉起直播界面。
 * 默认关闭，不影响未开启该功能的用户。
 *
 * 说明：部分定制系统（尤其国产电视/盒子）还需在系统的「自启动管理」白名单中
 * 允许本应用；此外 Android 10+ 对后台启动 Activity 有限制（需授予「显示在其他应用上层」），
 * 被拦截时系统不抛异常。因此 Android 10+ 且未授予该权限时仍先直接 startActivity（部分 ROM 白名单下可成功），
 * 同时额外发一条高优先级通知兜底：带全屏 Intent（系统允许的后台启动豁免，多数设备会直接拉起界面），
 * 否则用户点击通知即可打开直播。日志只记录实际做了什么，不谎称启动成功。
 */
public class BootReceiver extends BroadcastReceiver {

    /** 稍作延迟再启动，等待系统与网络初始化完成，提高成功率 */
    private static final long START_DELAY_MS = 6000L;

    /** 开机自启通知渠道（高优先级，独立于测速的低优先级渠道） */
    private static final String BOOT_CHANNEL_ID = "boot_startup_channel";
    public static final int BOOT_NOTIFICATION_ID = 10087;
    /** 通知自动消失时间：开机提示没有长期保留的意义 */
    private static final long BOOT_NOTIFICATION_TIMEOUT_MS = 10 * 60 * 1000L;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        String action = intent.getAction();
        if (action == null) return;

        // 兼容各类开机广播（含部分厂商的快速开机广播）
        boolean isBootAction = Intent.ACTION_BOOT_COMPLETED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)
                || "com.htc.intent.action.QUICKBOOT_POWERON".equals(action);
        if ("android.intent.action.LOCKED_BOOT_COMPLETED".equals(action)) {
            // 用户存储尚未解锁，Hawk/SharedPreferences 都不可用；解锁后的 BOOT_COMPLETED 会再来一次
            Timber.d("收到 LOCKED_BOOT_COMPLETED，等待解锁后的 BOOT_COMPLETED");
            return;
        }
        if (!isBootAction) return;

        try {
            // Hawk 可能尚未初始化（接收器可先于 Application 完成初始化时序）；
            // 两次 build 都失败时 Hawk.isBuilt() 自身会抛 NPE，视为未构建再试一次
            if (!hawkBuilt()) {
                Hawk.init(context.getApplicationContext()).build();
            }
            boolean enabled = Hawk.get(HawkConfig.LIVE_BOOT_STARTUP, false);
            if (!enabled) {
                Timber.d("开机自启未开启，忽略启动广播");
                return;
            }
        } catch (Throwable t) {
            Timber.w(t, "读取开机自启配置失败，放弃自启");
            return;
        }

        final Context appCtx = context.getApplicationContext();
        // goAsync：延迟期间保持广播处于处理中，进程不会被当作空进程在开机内存紧张时回收
        PendingResult pending = null;
        try {
            pending = goAsync();
        } catch (Throwable ignore) {
        }
        final PendingResult pr = pending;
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                // Android 10+ 后台启动 Activity 被系统拦截时不会抛异常，只在系统日志里警告；
                // 未授予悬浮窗权限时可能被拦截，此时在直接拉起之外再补一条通知
                boolean blocked = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                        && !Settings.canDrawOverlays(appCtx);
                Intent launch = new Intent(appCtx, LivePlayActivity.class);
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                // 无论是否可能被拦截都先直接尝试：不少国产 ROM/盒子对白名单应用放开了后台启动限制，
                // 直接拉起在这些设备上本来就能成功，不能因为"可能被拦截"就不再尝试
                boolean started = false;
                try {
                    appCtx.startActivity(launch);
                    started = true;
                } catch (Throwable t) {
                    Timber.w(t, "开机自启：直接拉起直播界面失败");
                }
                if (blocked) {
                    // 可能被静默拦截：额外发一条通知兜底（TV 上未必显示，仅作补充，不替代直接拉起）
                    boolean posted = postLaunchNotification(appCtx, launch);
                    String msg = (started ? "已请求拉起" : "直接拉起失败")
                            + (posted ? "，并发出兜底通知" : "，兜底通知不可用")
                            + "（Android 10+ 无悬浮窗权限，可能被系统拦截）";
                    Timber.i("开机自启：%s", msg);
                    com.github.tvbox.osc.util.RunLog.i("开机自启: " + msg);
                } else if (started) {
                    Timber.i("开机自启：已请求拉起直播界面");
                    com.github.tvbox.osc.util.RunLog.i("开机自启: 已请求拉起直播界面");
                } else {
                    com.github.tvbox.osc.util.RunLog.i("开机自启: 失败（直接拉起抛出异常，可能被系统自启管理拦截）");
                }
            } catch (Throwable t) {
                // 被系统自启管理拦截等
                Timber.w(t, "开机自启失败（可能被系统限制，请在自启动管理中允许本应用）");
                try {
                    com.github.tvbox.osc.util.RunLog.i("开机自启: 失败 " + t);
                } catch (Throwable ignore) {
                }
            } finally {
                if (pr != null) {
                    try {
                        pr.finish();
                    } catch (Throwable ignore) {
                    }
                }
            }
        }, START_DELAY_MS);
    }

    /**
     * 发出"点击打开直播"的高优先级通知（Android 10+ 后台无法直接启动 Activity 时的替代方案）。
     *
     * 同时设置全屏 Intent：这是系统允许的后台启动 Activity 豁免，多数设备会直接拉起界面；
     * 不支持时（或用户关闭了通知）至少还能在通知栏点击打开。
     *
     * @return 通知是否已成功提交给系统（不代表一定显示：用户可能禁用了通知，Android 13+ 也需通知权限）
     */
    private static boolean postLaunchNotification(Context ctx, Intent launch) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // 渠道重复创建是幂等的；高优先级才会弹出横幅/触发全屏 Intent
                NotificationChannel channel = new NotificationChannel(BOOT_CHANNEL_ID,
                        ctx.getString(R.string.boot_channel_name), NotificationManager.IMPORTANCE_HIGH);
                channel.setDescription(ctx.getString(R.string.boot_channel_desc));
                channel.setShowBadge(false);
                nm.createNotificationChannel(channel);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    && !nm.areNotificationsEnabled()) {
                // Android 13+ 未授予通知权限时 notify 会被静默丢弃，如实返回失败
                return false;
            }
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent pi = PendingIntent.getActivity(ctx, 0, launch, flags);
            Notification n = new NotificationCompat.Builder(ctx, BOOT_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(ctx.getString(R.string.boot_notify_title))
                    .setContentText(ctx.getString(R.string.boot_notify_text))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_REMINDER)
                    .setContentIntent(pi)
                    .setFullScreenIntent(pi, true)
                    .setAutoCancel(true)
                    // 全屏 Intent 直接拉起时不会自动移除通知，到时自动消失，避免残留
                    .setTimeoutAfter(BOOT_NOTIFICATION_TIMEOUT_MS)
                    .build();
            nm.notify(BOOT_NOTIFICATION_ID, n);
            return true;
        } catch (Throwable t) {
            Timber.w(t, "发送开机自启通知失败");
            return false;
        }
    }

    /** Hawk.isBuilt() 在 facade 为 null 时会抛 NPE，这里统一视为未构建 */
    private static boolean hawkBuilt() {
        try {
            return Hawk.isBuilt();
        } catch (Throwable t) {
            return false;
        }
    }
}
