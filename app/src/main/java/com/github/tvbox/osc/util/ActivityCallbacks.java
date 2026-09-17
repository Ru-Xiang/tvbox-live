package com.github.tvbox.osc.util;

import android.app.Activity;

import java.lang.ref.WeakReference;

/**
 * Activity 异步回调的生命周期守卫工具。
 *
 * <p><b>要解决的问题：</b>直播源聚合是一次可长达数分钟的网络任务
 * （订阅列表可展开为数十个源，单源超时 15s 连接 + 30s 读取），
 * 期间回调被后台线程强引用。若回调是 Activity 的匿名内部类，
 * 就形成了引用链：
 *
 * <pre>
 * 后台线程(GC Root) → FetchCallback → Activity 的匿名 ChannelCallback
 *                   → Activity.this → 整个 View 树 + ExoPlayer + Adapter 数据
 * </pre>
 *
 * 用户在加载期间退出页面时，Activity 及其持有的数十 MB 资源在整个任务
 * 结束前都无法回收——在低内存电视盒子上这足以触发 OOM。
 *
 * <p><b>做法：</b>用 {@link WeakReference} 持有 Activity，并在每次回调前
 * 检查其存活状态。Activity 一旦销毁，回调直接变为空操作，
 * 强引用链也随之断开。
 */
public final class ActivityCallbacks {

    private ActivityCallbacks() {}

    /**
     * 判断 Activity 是否仍可安全地接收回调并更新 UI。
     *
     * @return true 表示 Activity 非 null、未 finishing、未 destroyed
     */
    public static boolean isAlive(Activity activity) {
        return activity != null && !activity.isFinishing() && !activity.isDestroyed();
    }

    /**
     * 在 Activity 存活时于主线程执行动作，否则丢弃。
     *
     * <p>注意存活状态需检查两次：提交到主线程时检查一次（快速失败），
     * 真正执行时再检查一次——两个时间点之间 Activity 可能已经销毁。
     *
     * @param ref    Activity 的弱引用
     * @param action 要在主线程执行的动作
     */
    public static void runOnUiThreadIfAlive(WeakReference<? extends Activity> ref, Runnable action) {
        if (ref == null || action == null) return;
        Activity activity = ref.get();
        if (!isAlive(activity)) return;
        activity.runOnUiThread(() -> {
            // 二次校验：从投递到执行之间 Activity 可能已销毁
            Activity a = ref.get();
            if (!isAlive(a)) return;
            try {
                action.run();
            } catch (Throwable t) {
                // UI 回调中的异常不应连锁杀掉 App
                timber.log.Timber.w(t, "UI 回调执行异常");
            }
        });
    }
}
