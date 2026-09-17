package com.github.tvbox.osc.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

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
 * 允许本应用；此外 Android 10+ 对后台启动 Activity 有限制，因此启动失败时
 * 只记录日志而不抛异常。
 */
public class BootReceiver extends BroadcastReceiver {

    /** 稍作延迟再启动，等待系统与网络初始化完成，提高成功率 */
    private static final long START_DELAY_MS = 6000L;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        String action = intent.getAction();
        if (action == null) return;

        // 兼容各类开机广播（含部分厂商的快速开机广播）
        boolean isBootAction = Intent.ACTION_BOOT_COMPLETED.equals(action)
                || "android.intent.action.LOCKED_BOOT_COMPLETED".equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)
                || "com.htc.intent.action.QUICKBOOT_POWERON".equals(action);
        if (!isBootAction) return;

        try {
            // Hawk 可能尚未初始化（接收器可先于 Application 完成初始化时序）
            if (!Hawk.isBuilt()) {
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
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                Intent launch = new Intent(appCtx, LivePlayActivity.class);
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                appCtx.startActivity(launch);
                Timber.i("开机自启：已拉起直播界面");
            } catch (Throwable t) {
                // Android 10+ 后台启动 Activity 受限，或被系统自启管理拦截
                Timber.w(t, "开机自启失败（可能被系统限制，请在自启动管理中允许本应用）");
            }
        }, START_DELAY_MS);
    }
}
