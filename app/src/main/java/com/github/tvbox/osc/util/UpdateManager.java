package com.github.tvbox.osc.util;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.core.content.FileProvider;

import com.github.tvbox.osc.BuildConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import com.orhanobut.hawk.Hawk;

import timber.log.Timber;

/**
 * 基于 GitHub Releases 的应用更新器。
 *
 * <p>流程：取 latest release → 挑出 .apk asset → 版本号比对 → 下载 → FileProvider 唤起安装器。
 * <b>约定</b>：Release tag 用 {@code 1.2.1} 或 {@code v1.2.1}，与 versionName 同数列。
 */
public final class UpdateManager {

    public static final String GITHUB_OWNER = "Ru-Xiang";
    public static final String GITHUB_REPO = "tvbox-live";

    private static final String LATEST_RELEASE_API =
            "https://api.github.com/repos/" + GITHUB_OWNER + "/" + GITHUB_REPO + "/releases/latest";

    /** 自动检查最小间隔（GitHub 匿名 API 有限频，不宜频繁） */
    private static final long AUTO_CHECK_INTERVAL_MS = 12 * 60 * 60 * 1000L;

    private static final Pattern NUM = Pattern.compile("(\\d+)");

    private UpdateManager() {
    }

    public static final class UpdateInfo {
        public String tagName;
        public String version;
        public String releaseTitle;
        public String releaseNotes;
        public String apkUrl;
        public String apkName;
        public long sizeBytes;
        public String htmlUrl;
    }

    public interface UpdateCallback {
        /** 发现新版本（主线程） */
        void onUpdate(UpdateInfo info);

        /** 已是最新（主线程） */
        void onLatest();

        /** 检查失败（主线程） */
        void onError(String message);
    }

    public interface DownloadListener {
        void onProgress(int percent);

        void onFinished(File apkFile);

        void onFailed(String message);
    }

    // ============ 检查 ============

    public static boolean shouldAutoCheck() {
        long last = Hawk.get(HawkConfig.UPDATE_LAST_CHECK_TIME, 0L);
        return System.currentTimeMillis() - last > AUTO_CHECK_INTERVAL_MS;
    }

    /** 异步检查更新，回调均在主线程 */
    public static void checkForUpdate(UpdateCallback callback) {
        new Thread(() -> {
            UpdateInfo info = null;
            String error = null;
            com.github.tvbox.osc.util.RunLog.i("检查更新: 开始");
            try {
                OkHttpClient client = HttpClients.shared().newBuilder()
                        .connectTimeout(12, TimeUnit.SECONDS)
                        .readTimeout(20, TimeUnit.SECONDS)
                        .build();
                Request req = new Request.Builder()
                        .url(LATEST_RELEASE_API)
                        .header("Accept", "application/vnd.github+json")
                        .header("User-Agent", "TVBoxOS-Live/" + BuildConfig.VERSION_NAME)
                        .get()
                        .build();
                try (Response resp = client.newCall(req).execute()) {
                    int code = resp.code();
                    if (code == 404) {
                        error = "仓库尚无 Release，请先发布一个版本";
                    } else if (!resp.isSuccessful() || resp.body() == null) {
                        error = "检查更新失败（HTTP " + code + "）";
                    } else {
                        info = parseRelease(resp.body().string());
                    }
                }
            } catch (Throwable t) {
                error = "无法连接 GitHub：" + describe(t);
            }

            UpdateInfo finalInfo = info;
            String finalError = error;
            // 无论成功失败都记录本次检查时间，避免失败时反复重试
            Hawk.put(HawkConfig.UPDATE_LAST_CHECK_TIME, System.currentTimeMillis());

            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                if (finalError != null) {
                    com.github.tvbox.osc.util.RunLog.i("检查更新: 失败 " + finalError);
                    if (callback != null) callback.onError(finalError);
                    return;
                }
                if (finalInfo == null || finalInfo.apkUrl == null) {
                    if (callback != null) callback.onError("最新 Release 中没有找到 APK 安装包");
                    return;
                }
                String skipped = Hawk.get(HawkConfig.UPDATE_SKIP_VERSION, "");
                if (finalInfo.version.equals(skipped)) {
                    com.github.tvbox.osc.util.RunLog.i("检查更新: 版本被用户跳过 " + finalInfo.version);
                    if (callback != null) callback.onLatest();
                    return;
                }
                boolean newer = isNewer(finalInfo.version, BuildConfig.VERSION_NAME);
                com.github.tvbox.osc.util.RunLog.i("检查更新: 远端=" + finalInfo.version
                        + " 本地=" + BuildConfig.VERSION_NAME + " 有新版本=" + newer);
                if (newer) {
                    if (callback != null) callback.onUpdate(finalInfo);
                } else if (callback != null) {
                    callback.onLatest();
                }
            });
        }, "update-check").start();
    }

    /** 解析 Release JSON，挑出第一个 .apk 资产 */
    private static UpdateInfo parseRelease(String json) throws Exception {
        JSONObject root = new JSONObject(json);
        UpdateInfo info = new UpdateInfo();
        info.tagName = root.optString("tag_name", "");
        info.releaseTitle = root.optString("name", "");
        info.releaseNotes = root.optString("body", "");
        info.htmlUrl = root.optString("html_url", "");

        JSONArray assets = root.optJSONArray("assets");
        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                String name = a.optString("name", "");
                String url = a.optString("browser_download_url", null);
                if (url != null && name.toLowerCase().endsWith(".apk")) {
                    info.apkName = name;
                    info.apkUrl = url;
                    info.sizeBytes = a.optLong("size", 0L);
                    break;
                }
            }
        }
        info.version = normalizeVersion(info.tagName);
        return info;
    }

    /** 去掉 v 前缀与空白 */
    private static String normalizeVersion(String tag) {
        if (tag == null) return "";
        return tag.trim().replaceFirst("^[vV]", "");
    }

    /**
     * 逐段按数字比较版本号，非数字段（如 -beta）统一视为 0，因此
     * {@code 1.2.0 > 1.2} 不成立，但 {@code 1.2.1 > 1.2.0} 成立。
     */
    static boolean isNewer(String remote, String local) {
        String r = normalizeVersion(remote);
        String l = normalizeVersion(local);
        if (r.isEmpty()) return false;
        String[] rs = r.split("\\.");
        String[] ls = l.split("\\.");
        int len = Math.max(rs.length, ls.length);
        for (int i = 0; i < len; i++) {
            int rv = i < rs.length ? leadingInt(rs[i]) : 0;
            int lv = i < ls.length ? leadingInt(ls[i]) : 0;
            if (rv != lv) return rv > lv;
        }
        return false;
    }

    private static int leadingInt(String seg) {
        Matcher m = NUM.matcher(seg);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (Exception ignore) {
                return 0;
            }
        }
        return 0;
    }

    private static String describe(Throwable t) {
        String msg = t.getMessage();
        if (msg == null || msg.isEmpty()) return t.getClass().getSimpleName();
        return msg.length() > 80 ? msg.substring(0, 80) : msg;
    }

    // ============ 下载 ============

    /** 下载 APK 到应用专属 Download 目录 */
    public static void download(Context ctx, UpdateInfo info, DownloadListener listener) {
        File target = targetFile(ctx, info);
        new Thread(() -> {
            InputStream in = null;
            FileOutputStream out = null;
            try {
                OkHttpClient client = HttpClients.shared().newBuilder()
                        .connectTimeout(15, TimeUnit.SECONDS)
                        .readTimeout(30, TimeUnit.SECONDS)
                        .build();
                Request req = new Request.Builder().url(info.apkUrl)
                        .header("User-Agent", "TVBoxOS-Live/" + BuildConfig.VERSION_NAME)
                        .build();
                try (Response resp = client.newCall(req).execute()) {
                    if (resp.body() == null) throw new java.io.IOException("空响应");
                    long total = resp.body().contentLength();
                    long done = 0L;
                    in = resp.body().byteStream();
                    out = new FileOutputStream(target);
                    byte[] buf = new byte[32768];
                    int n;
                    long lastReport = 0L;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        done += n;
                        long now = System.currentTimeMillis();
                        if (total > 0 && now - lastReport > 150) {
                            lastReport = now;
                            int percent = (int) (done * 100 / total);
                            postProgress(listener, percent);
                        }
                    }
                }
                postProgress(listener, 100);
                postFinished(listener, target);
            } catch (Throwable t) {
                try {
                    target.delete();
                } catch (Exception ignore) {
                }
                postFailed(listener, "下载失败：" + describe(t));
            } finally {
                closeQuietly(in);
                closeQuietly(out);
            }
        }, "update-download").start();
    }

    private static File targetFile(Context ctx, UpdateInfo info) {
        File dir = ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS);
        if (dir == null) dir = ctx.getFilesDir();
        if (!dir.exists() && !dir.mkdirs()) {
            // 目录不可用时仍返回一个可用路径
        }
        String name = "tvbox-live-" + (info.version.isEmpty() ? "update" : info.version) + ".apk";
        return new File(dir, name);
    }

    private static void postProgress(DownloadListener l, int percent) {
        if (l == null) return;
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            try {
                l.onProgress(percent);
            } catch (Throwable ignore) {
            }
        });
    }

    private static void postFinished(DownloadListener l, File f) {
        if (l == null) return;
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            try {
                l.onFinished(f);
            } catch (Throwable ignore) {
            }
        });
    }

    private static void postFailed(DownloadListener l, String msg) {
        if (l == null) return;
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            try {
                l.onFailed(msg);
            } catch (Throwable ignore) {
            }
        });
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Throwable ignore) {
        }
    }

    // ============ 安装 ============

    /**
     * 唤起系统安装器。
     *
     * <p>Android 7.0+ 禁止对外暴露 file:// URI，必须用 FileProvider 生成 content://
     * 并授予读权限；同时需要在 Manifest 中声明 REQUEST_INSTALL_PACKAGES。
     */
    public static void installApk(Context ctx, File apk) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            Uri uri = FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".fileprovider", apk);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent);
        } catch (Throwable t) {
            Timber.w(t, "唤起安装器失败");
            try {
                ToastUtil.show(ctx, "无法打开安装器，请到文件管理器手动安装");
            } catch (Throwable ignore) {
            }
        }
    }
}
