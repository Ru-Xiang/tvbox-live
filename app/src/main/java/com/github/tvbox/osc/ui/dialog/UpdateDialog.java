package com.github.tvbox.osc.ui.dialog;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import com.github.tvbox.osc.util.ToastUtil;
import com.github.tvbox.osc.util.UpdateManager;
import com.orhanobut.hawk.Hawk;

import java.io.File;

/**
 * 新版本提示对话框（代码构建布局，适配遥控器焦点导航）。
 *
 * <p>内容：版本号 → Release 说明 → 下载进度 → 「立即更新 / 跳过此版本 / 稍后」。
 * 下载完成后直接唤起系统安装器。
 */
public final class UpdateDialog {

    private UpdateDialog() {
    }

    public static void show(Context ctx, UpdateManager.UpdateInfo info) {
        try {
            if (ctx instanceof android.app.Activity) {
                android.app.Activity act = (android.app.Activity) ctx;
                if (act.isFinishing() || act.isDestroyed()) return;
            }
            new Builder(ctx, info).show();
        } catch (Throwable t) {
            // 弹窗失败绝不能影响主流程
            try {
                ToastUtil.show(ctx, "更新弹窗显示失败，请稍后重试");
            } catch (Throwable ignore) {
            }
        }
    }

    private static final class Builder {
        private final Context ctx;
        private final UpdateManager.UpdateInfo info;
        private final Dialog dialog;
        private final Button btnInstall;
        private final Button btnSkip;
        private final Button btnLater;
        private final ProgressBar progress;
        private final TextView tvState;
        private boolean downloading = false;

        Builder(Context ctx, UpdateManager.UpdateInfo info) {
            this.ctx = ctx;
            this.info = info;

            int pad = dp(26);
            LinearLayout root = new LinearLayout(ctx);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackgroundColor(0xF21A1A2E);
            root.setPadding(pad, pad, pad, dp(20));

            TextView title = new TextView(ctx);
            title.setText("发现新版本 " + info.version);
            title.setTextSize(20);
            title.setTextColor(Color.WHITE);
            title.getPaint().setFakeBoldText(true);
            root.addView(title, lp(dp(0)));

            TextView sub = new TextView(ctx);
            sub.setText("当前版本 " + com.github.tvbox.osc.BuildConfig.VERSION_NAME + " · " + sizeText(info.sizeBytes));
            sub.setTextSize(13);
            sub.setTextColor(0xFF9C9CC4);
            sub.setPadding(0, dp(6), 0, 0);
            root.addView(sub, lp(dp(0)));

            String notes = (info.releaseNotes == null || info.releaseNotes.trim().isEmpty())
                    ? "（本次发布未提供更新说明）" : info.releaseNotes.trim();
            TextView tvNotes = new TextView(ctx);
            tvNotes.setText(notes);
            tvNotes.setTextSize(14);
            tvNotes.setTextColor(0xFFD8D8EC);
            tvNotes.setLineSpacing(dp(3), 1.05f);
            tvNotes.setVerticalScrollBarEnabled(true);

            ScrollView scroll = new ScrollView(ctx);
            scroll.addView(tvNotes);
            LinearLayout.LayoutParams slp = lp(dp(0));
            slp.height = dp(190);
            slp.topMargin = dp(14);
            scroll.setLayoutParams(slp);
            root.addView(scroll);

            progress = new ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal);
            progress.setMax(100);
            progress.setProgress(0);
            progress.setVisibility(android.view.View.GONE);
            LinearLayout.LayoutParams plp = lp(dp(8));
            plp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            plp.topMargin = dp(16);
            progress.setLayoutParams(plp);
            root.addView(progress);

            tvState = new TextView(ctx);
            tvState.setText("");
            tvState.setTextSize(12);
            tvState.setTextColor(0xFF9C9CC4);
            tvState.setPadding(0, dp(8), 0, 0);
            tvState.setVisibility(android.view.View.GONE);
            root.addView(tvState, lp(ViewGroup.LayoutParams.MATCH_PARENT));

            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(20), 0, 0);
            row.setLayoutParams(lp(ViewGroup.LayoutParams.WRAP_CONTENT));

            btnInstall = button("立即更新", true);
            btnSkip = button("跳过此版本", false);
            btnLater = button("稍后", false);
            row.addView(btnInstall);
            row.addView(btnSkip, rowLp());
            row.addView(btnLater, rowLp());
            root.addView(row);

            btnInstall.setOnClickListener(v -> startDownload());
            btnSkip.setOnClickListener(v -> {
                Hawk.put(com.github.tvbox.osc.util.HawkConfig.UPDATE_SKIP_VERSION, info.version);
                ToastUtil.show(ctx, "已跳过该版本");
                dismiss();
            });
            btnLater.setOnClickListener(v -> dismiss());

            dialog = new Dialog(ctx);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            dialog.setContentView(root);
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                dialog.getWindow().setLayout(dp(560), ViewGroup.LayoutParams.WRAP_CONTENT);
            }
            dialog.setCancelable(true);
        }

        void show() {
            dialog.show();
            if (btnInstall != null) btnInstall.requestFocus();
        }

        private void dismiss() {
            try {
                if (dialog != null && dialog.isShowing()) dialog.dismiss();
            } catch (Throwable ignore) {
            }
        }

        private void startDownload() {
            if (downloading) return;
            downloading = true;
            btnInstall.setEnabled(false);
            btnInstall.setFocusable(false);
            progress.setVisibility(android.view.View.VISIBLE);
            progress.setProgress(0);
            tvState.setVisibility(android.view.View.VISIBLE);
            tvState.setText("正在下载...");

            UpdateManager.download(ctx, info, new UpdateManager.DownloadListener() {
                @Override
                public void onProgress(int percent) {
                    progress.setProgress(percent);
                    tvState.setText("正在下载... " + percent + "%");
                }

                @Override
                public void onFinished(File apkFile) {
                    tvState.setText("下载完成，正在唤起安装器");
                    dismiss();
                    UpdateManager.installApk(ctx, apkFile);
                }

                @Override
                public void onFailed(String message) {
                    downloading = false;
                    btnInstall.setEnabled(true);
                    btnInstall.setFocusable(true);
                    btnInstall.requestFocus();
                    progress.setVisibility(android.view.View.GONE);
                    tvState.setText(message);
                }
            });
        }

        private Button button(String text, boolean primary) {
            Button b = new Button(ctx);
            b.setText(text);
            b.setTextSize(15);
            b.setAllCaps(false);
            b.setFocusable(true);
            b.setPadding(dp(22), dp(11), dp(22), dp(11));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                b.setBackground(ctx.getDrawable(primary ? com.github.tvbox.osc.R.drawable.btn_primary
                        : com.github.tvbox.osc.R.drawable.btn_secondary));
            }
            b.setTextColor(primary ? Color.WHITE : 0xFFD8D8EC);
            return b;
        }

        private LinearLayout.LayoutParams lp(int height) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, height == dp(0) ? ViewGroup.LayoutParams.WRAP_CONTENT : height);
            return p;
        }

        private LinearLayout.LayoutParams rowLp() {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            p.setMargins(dp(12), 0, 0, 0);
            return p;
        }

        private static int dp(int v) {
            return (int) (v * android.content.res.Resources.getSystem().getDisplayMetrics().density + 0.5f);
        }

        private static String sizeText(long bytes) {
            if (bytes <= 0) return "";
            return String.format("%.1f MB", bytes / 1024.0 / 1024.0);
        }
    }
}
