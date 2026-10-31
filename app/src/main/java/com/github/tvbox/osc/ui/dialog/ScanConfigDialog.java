package com.github.tvbox.osc.ui.dialog;

import com.github.tvbox.osc.util.ToastUtil;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.service.ConfigHttpServer;
import com.github.tvbox.osc.util.NetworkUtil;
import com.github.tvbox.osc.util.QrCodeUtil;

import timber.log.Timber;

/**
 * 扫码配置弹窗封装
 *
 * 启动局域网内置 HTTP 服务器并展示二维码，手机扫码即可在网页编辑配置。
 * 设置页与首次打开的播放页共用，统一管理服务器生命周期。
 */
public class ScanConfigDialog {

    public interface OnConfigSavedCallback {
        void onConfigSaved();
    }

    private final Activity activity;
    private ConfigHttpServer configServer;
    private AlertDialog dialog;
    /** 持有二维码位图引用，关闭时主动回收（480x480 位图约 900KB） */
    private Bitmap qrBitmap;
    /** 持有二维码 ImageView 引用，关闭时解除绑定以便位图回收 */
    private ImageView qrImageView;

    /**
     * 当前持有固定端口 9978 的弹窗（进程内唯一）。
     * 设置页与播放页各自 new 一个 ScanConfigDialog：若后台播放页的首次配置弹窗仍占着端口，
     * 设置页再启动会 BindException，且手机提交会打到后台页的回调。
     * 因此新弹窗启动前先让旧持有者关闭弹窗并停止服务器。仅在主线程读写。
     * 强引用只在服务器运行期间存在（服务器线程本身也间接持有本对象），停止服务器时清空，不额外泄漏。
     */
    private static ScanConfigDialog sActiveOwner;

    /** 本弹窗当前生效的提交回调（被其它弹窗顶替时，供新持有者转发提交） */
    private OnConfigSavedCallback activeCallback;
    /**
     * 被本弹窗顶替的旧持有者（弱引用，不延长后台页面生命周期）。
     * 首次打开的播放页在后台占着端口时，用户从设置页扫码会关闭播放页的欢迎二维码；
     * 若提交只回调设置页，播放页不会重新加载频道，返回后是一个没有二维码也没有频道的空播放页。
     * 因此提交时也转发给旧持有者的回调，保持 v1.2.3 中"手机提交后播放页自动重新加载"的行为。
     */
    private java.lang.ref.WeakReference<ScanConfigDialog> evictedOwnerRef;

    /** NanoHTTPD socket 读取超时（毫秒） */
    private static final int SOCKET_TIMEOUT = 5000;

    public ScanConfigDialog(Activity activity) {
        this.activity = activity;
    }

    /**
     * 显示扫码配置弹窗
     *
     * @param title    弹窗标题（为 null 时使用布局默认标题）
     * @param callback 手机端提交配置后的回调（已在主线程）
     */
    public void show(String title, OnConfigSavedCallback callback) {
        // Activity 已销毁/正在结束时直接放弃，避免 show() 抛 BadTokenException
        if (!isActivityAlive()) {
            Timber.w("Activity 已不可用，取消显示扫码配置弹窗");
            return;
        }
        // 已有弹窗在显示时不重复弹出（防止窗口叠加与泄漏）
        if (dialog != null && dialog.isShowing()) {
            return;
        }

        String ip = NetworkUtil.getLocalIpAddress();
        if (ip == null) {
            safeToast("未获取到局域网地址，请检查网络连接");
            return;
        }

        ScanConfigDialog prevOwner = sActiveOwner;
        ScanConfigDialog evicted = null;
        if (prevOwner != null && prevOwner != this) {
            if (!isActivityStarted()) {
                // 本页面已在后台（如播放页加载完成时用户正在设置页），不抢前台页面的端口
                Timber.w("扫码配置端口已被前台页面占用，后台页面放弃显示");
                return;
            }
            // 前台页面优先：关闭旧持有者的弹窗并释放端口，旧页面的回调也不再收到提交
            Timber.i("扫码配置端口被其它页面占用，先停止旧弹窗的服务器");
            try {
                prevOwner.dismissAndStop();
            } catch (Throwable t) {
                Timber.w(t, "停止旧扫码配置服务器失败");
            }
            if (sActiveOwner == prevOwner) sActiveOwner = null;
            if (prevOwner.activeCallback != null) evicted = prevOwner;
        }

        int port = ConfigHttpServer.DEFAULT_PORT;
        try {
            stopServer();
            // stopServer() 会清空转发目标，需在其后登记
            activeCallback = callback;
            evictedOwnerRef = evicted != null ? new java.lang.ref.WeakReference<>(evicted) : null;
            configServer = new ConfigHttpServer(activity.getApplicationContext(), port);
            configServer.setOnConfigSavedListener(() -> {
                // 回调可能在 Activity 销毁后到达，需再次校验存活
                if (!isActivityAlive()) return;
                activity.runOnUiThread(() -> {
                    if (!isActivityAlive()) return;
                    try {
                        if (callback != null) callback.onConfigSaved();
                    } catch (Throwable t) {
                        Timber.w(t, "配置保存回调异常");
                    }
                    notifyEvictedOwner();
                });
            });
            configServer.start(SOCKET_TIMEOUT, false);
            sActiveOwner = this;
        } catch (Throwable e) {
            Timber.e(e, "启动配置服务器失败");
            stopServer();
            safeToast("启动配置服务失败: " + e.getMessage());
            return;
        }

        try {
            String url = "http://" + ip + ":" + port + "/";
            View view = LayoutInflater.from(activity).inflate(R.layout.dialog_qr_config, null);
            if (title != null) {
                TextView tvTitle = view.findViewById(R.id.tv_qr_title);
                if (tvTitle != null) tvTitle.setText(title);
            }
            TextView tvUrl = view.findViewById(R.id.tv_qr_url);
            if (tvUrl != null) tvUrl.setText(url);
            ImageView ivQr = view.findViewById(R.id.iv_qr_code);
            if (ivQr != null) {
                Bitmap qr = QrCodeUtil.createQrCode(url, 480);
                if (qr != null) {
                    ivQr.setImageBitmap(qr);
                    // 记录引用，关闭弹窗时主动解绑并回收
                    qrImageView = ivQr;
                    qrBitmap = qr;
                }
            }

            // OnDismiss 是异步投递的：dismissAndStop() 后紧接着再次 show() 时，
            // 旧弹窗的回调会晚于新弹窗执行。若不区分归属，会停掉新启动的服务器、
            // 回收新二维码位图（绘制时抛 "trying to use a recycled bitmap"）。
            // 因此只释放属于本弹窗的服务器与位图。
            final ConfigHttpServer ownServer = configServer;
            final Bitmap ownBitmap = qrBitmap;
            dialog = new AlertDialog.Builder(activity)
                    .setView(view)
                    .setPositiveButton("完成", null)
                    .setOnDismissListener(d -> {
                        // 用户主动关闭时同样释放服务器与二维码位图
                        if (configServer == ownServer) stopServer();
                        if (qrBitmap == ownBitmap) releaseQrBitmap();
                    })
                    .create();
            dialog.show();
        } catch (Throwable e) {
            // inflate/show 失败（InflateException、BadToken、OOM 等）时不崩溃，并释放服务器
            Timber.e(e, "显示扫码配置弹窗失败");
            dialog = null;
            releaseQrBitmap();
            stopServer();
            safeToast("无法显示扫码界面");
        }
    }

    /** 把手机提交转发给被顶替的旧持有者（如后台的首次配置播放页），使其照常重新加载 */
    private void notifyEvictedOwner() {
        ScanConfigDialog evicted = evictedOwnerRef != null ? evictedOwnerRef.get() : null;
        if (evicted == null || evicted == this) return;
        // 旧持有者若已重新弹出自己的弹窗，会反过来顶替本弹窗，不会走到这里
        OnConfigSavedCallback cb = evicted.activeCallback;
        if (cb == null || !evicted.isActivityAlive()) return;
        try {
            Timber.i("转发扫码配置提交给被顶替的页面");
            cb.onConfigSaved();
        } catch (Throwable t) {
            Timber.w(t, "转发配置保存回调异常");
        }
    }

    /** Activity 是否仍可用于弹窗展示 */
    private boolean isActivityAlive() {
        return activity != null && !activity.isFinishing() && !activity.isDestroyed();
    }

    /** Activity 是否处于前台可见（STARTED 及以上）；无法判断时视为前台，保持旧行为 */
    private boolean isActivityStarted() {
        if (activity instanceof androidx.lifecycle.LifecycleOwner) {
            try {
                return ((androidx.lifecycle.LifecycleOwner) activity).getLifecycle().getCurrentState()
                        .isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED);
            } catch (Throwable ignored) {
            }
        }
        return true;
    }

    private void safeToast(String msg) {
        try {
            if (isActivityAlive()) {
                ToastUtil.showLong(activity, msg);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 关闭弹窗并停止服务器（在 Activity 的 onDestroy 中调用以释放资源） */
    public void dismissAndStop() {
        if (dialog != null && dialog.isShowing()) {
            try {
                dialog.dismiss();
            } catch (Exception ignored) {
            }
        }
        dialog = null;
        releaseQrBitmap();
        stopServer();
    }

    /**
     * 解绑并回收二维码位图。
     * 必须先把 ImageView 的 drawable 置空再 recycle，否则若视图仍被引用
     * 会在下次绘制时抛 "Canvas: trying to use a recycled bitmap"。
     */
    private void releaseQrBitmap() {
        try {
            if (qrImageView != null) {
                qrImageView.setImageDrawable(null);
                qrImageView = null;
            }
        } catch (Throwable ignored) {
        }
        try {
            if (qrBitmap != null && !qrBitmap.isRecycled()) {
                qrBitmap.recycle();
            }
        } catch (Throwable ignored) {
        }
        qrBitmap = null;
    }

    private void stopServer() {
        if (sActiveOwner == this) sActiveOwner = null;
        evictedOwnerRef = null;
        if (configServer != null) {
            try {
                configServer.stop();
            } catch (Exception ignored) {
            }
            configServer = null;
        }
    }
}
