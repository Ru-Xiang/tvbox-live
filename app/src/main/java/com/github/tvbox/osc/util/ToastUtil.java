package com.github.tvbox.osc.util;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.TextView;
import android.widget.Toast;

import timber.log.Timber;

/**
 * 安全的 Toast 工具。
 *
 * <p><b>它要解决的问题：</b>部分定制 ROM（实测 小米盒子 MiBOX4 / Android 6.0.1）上，
 * {@code Toast.makeText(activity, ...)} 会崩溃：
 *
 * <pre>
 * android.view.InflateException: Error inflating class TextView
 *   at Toast.makeText(Toast.java:263)
 *   at AppCompatTextView.&lt;init&gt;
 *   at AppCompatTextHelper.updateTypefaceAndStyle
 * Caused by: java.lang.ArrayIndexOutOfBoundsException: length=16; index=1546
 *   at android.content.res.StringBlock.get
 * </pre>
 *
 * <p>成因：Toast 用 <b>Activity</b> 上下文膨胀系统布局时，会被 AppCompat 的 View 工厂接管，
 * 生成 {@code AppCompatTextView} 并读取字体相关属性；而这些设备的资源表存在缺陷，
 * 取字符串时下标越界 → 主线程抛异常 → App 闪退。
 *
 * <p><b>规避手段：</b>
 * <ol>
 *   <li>统一改用 <b>Application 上下文</b>：它没有安装 AppCompat 的 View 工厂，
 *       膨胀出来的是系统原生 TextView，根本不会走到崩溃分支；</li>
 *   <li>仍用 try/catch 兜住，失败时退化为<strong>代码构建</strong>的 Toast 视图
 *       （直接 {@code new TextView(context)}，不经过 XML 膨胀，与该 ROM 缺陷完全无关）。</li>
 * </ol>
 *
 * <p>这样即使某条 Toast 显示不出来，也绝不会再连带整个应用闪退。
 */
public final class ToastUtil {

    private ToastUtil() {
    }

    public static void show(Context ctx, CharSequence msg) {
        show(ctx, msg, Toast.LENGTH_SHORT);
    }

    public static void showLong(Context ctx, CharSequence msg) {
        show(ctx, msg, Toast.LENGTH_LONG);
    }

    public static void show(Context ctx, CharSequence msg, int duration) {
        if (ctx == null || msg == null) return;
        Context app = ctx.getApplicationContext();
        if (app == null) app = ctx;
        final Context finalCtx = app;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            showInternal(finalCtx, msg, duration);
        } else {
            new Handler(Looper.getMainLooper()).post(() -> showInternal(finalCtx, msg, duration));
        }
    }

    private static void showInternal(Context ctx, CharSequence msg, int duration) {
        try {
            Toast.makeText(ctx, msg, duration).show();
        } catch (Throwable t) {
            Timber.w(t, "原生 Toast 创建失败，退化为自建视图");
            try {
                showCustom(ctx, msg, duration);
            } catch (Throwable ignore) {
                Timber.w("自建 Toast 也失败，放弃本次提示");
            }
        }
    }

    /** 用代码构建 Toast 视图（不经 XML 膨胀，规避 AppCompat 工厂与 ROM 资源表缺陷） */
    private static void showCustom(Context ctx, CharSequence msg, int duration) {
        float density = ctx.getResources().getDisplayMetrics().density;
        int pad = (int) (14 * density);

        TextView tv = new TextView(ctx);
        tv.setText(msg);
        tv.setTextColor(0xFFFFFFFF);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tv.setPadding(pad * 2, pad, pad * 2, pad);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xE6000000);
        bg.setCornerRadius(pad * 1.2f);
        tv.setBackground(bg);

        Toast toast = new Toast(ctx);
        toast.setView(tv);
        toast.setDuration(duration);
        toast.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM, 0, (int) (64 * density));
        toast.show();
    }
}
