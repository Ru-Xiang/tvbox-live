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

        int port = ConfigHttpServer.DEFAULT_PORT;
        try {
            stopServer();
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
                });
            });
            configServer.start(SOCKET_TIMEOUT, false);
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

            dialog = new AlertDialog.Builder(activity)
                    .setView(view)
                    .setPositiveButton("完成", null)
                    .setOnDismissListener(d -> {
                        // 用户主动关闭时同样释放服务器与二维码位图
                        stopServer();
                        releaseQrBitmap();
                    })
                    .create();
            dialog.show();
        } catch (Throwable e) {
            // inflate/show 失败（InflateException、BadToken、OOM 等）时不崩溃，并释放服务器
            Timber.e(e, "显示扫码配置弹窗失败");
            dialog = null;
            stopServer();
            safeToast("无法显示扫码界面");
        }
    }

    /** Activity 是否仍可用于弹窗展示 */
    private boolean isActivityAlive() {
        return activity != null && !activity.isFinishing() && !activity.isDestroyed();
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
        if (configServer != null) {
            try {
                configServer.stop();
            } catch (Exception ignored) {
            }
            configServer = null;
        }
    }
}
