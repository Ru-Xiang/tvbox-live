package com.github.tvbox.osc.util;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;

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

    /**
     * GitHub 在国内往往连不上/极慢，这里内置若干<b>公开中转镜像</b>作为兜底。
     * 用法是把镜像前缀拼在原始 URL 前（{@code 前缀 + https://...}）。
     * 空串表示直连。检查与下载分别维护列表，逐个尝试直到成功。
     */
    private static final String[] API_MIRRORS = {
            "",                               // 直连 api.github.com
            "https://gh-proxy.org/",          // 默认镜像，国内表现较好
            "https://gh-proxy.com/",
            "https://ghproxy.net/",
            "https://ghfast.top/",
            "https://mirror.ghproxy.com/",
            "https://gh.llkk.cc/",
    };

    /** APK 下载用的中转镜像（原理同上，拼在 github.com 下载链接前） */
    private static final String[] DOWNLOAD_MIRRORS = {
            "",                               // 直连
            "https://gh-proxy.org/",          // 默认镜像
            "https://gh-proxy.com/",
            "https://ghfast.top/",
            "https://mirror.ghproxy.com/",
            "https://hub.gitmirror.com/",
            "https://gh.llkk.cc/",
    };

    /** 单个镜像的超时上限，避免整轮尝试拖太久 */
    private static final int PER_MIRROR_TIMEOUT_SEC = 8;

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
            // 并发探测所有候选源（自定义镜像 / 直连 / 内置镜像），取最先成功的一个。
            // 串行会让超时累加（7 个源 × 8s = 56s），并发后总耗时≈单次超时，体验好得多。
            java.util.List<String> prefixes = apiCandidates();
            java.util.concurrent.atomic.AtomicBoolean notFound = new java.util.concurrent.atomic.AtomicBoolean(false);
            java.util.concurrent.atomic.AtomicReference<String> lastErr = new java.util.concurrent.atomic.AtomicReference<>(null);

            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(
                    Math.min(prefixes.size(), 5));
            java.util.List<java.util.concurrent.Future<UpdateInfo>> futures = new java.util.ArrayList<>();
            for (String prefix : prefixes) {
                futures.add(pool.submit(() -> fetchRelease(prefix + LATEST_RELEASE_API, notFound, lastErr)));
            }
            String hitPrefix = null;
            for (int i = 0; i < futures.size(); i++) {
                try {
                    UpdateInfo r = futures.get(i).get();
                    if (r != null) {
                        info = r;
                        hitPrefix = prefixes.get(i);
                        break;
                    }
                } catch (Throwable ignore) {
                }
            }
            // 已拿到结果，其余探测无意义，立即中断
            pool.shutdownNow();
            com.github.tvbox.osc.util.RunLog.i("检查更新: 命中 " + label(hitPrefix));

            if (info == null) {
                if (notFound.get()) {
                    error = "仓库尚无 Release，请先发布一个版本";
                } else {
                    error = "无法连接 GitHub（已并发尝试直连与 " + (API_MIRRORS.length - 1)
                            + " 个镜像）：" + lastErr.get();
                }
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

    /**
     * 从单个地址拉取 Release 信息，失败或 404 时返回 null。
     *
     * @param notFound 命中 404 时置位（说明仓库/Release 确实不存在，换镜像也没意义）
     * @param lastErr  记录最后一次错误，供最终提示使用
     */
    private static UpdateInfo fetchRelease(String url,
                                           java.util.concurrent.atomic.AtomicBoolean notFound,
                                           java.util.concurrent.atomic.AtomicReference<String> lastErr) {
        try {
            OkHttpClient client = HttpClients.shared().newBuilder()
                    .connectTimeout(PER_MIRROR_TIMEOUT_SEC, TimeUnit.SECONDS)
                    .readTimeout(PER_MIRROR_TIMEOUT_SEC, TimeUnit.SECONDS)
                    .build();
            Request req = new Request.Builder()
                    .url(url)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "TVBoxOS-Live/" + BuildConfig.VERSION_NAME)
                    .get()
                    .build();
            try (Response resp = client.newCall(req).execute()) {
                int code = resp.code();
                if (code == 404) {
                    notFound.set(true);
                    return null;
                }
                if (!resp.isSuccessful() || resp.body() == null) {
                    lastErr.set("HTTP " + code);
                    return null;
                }
                return parseRelease(resp.body().string());
            }
        } catch (Throwable t) {
            lastErr.set(describe(t));
            return null;
        }
    }

    /** 检查更新的候选源：用户自定义镜像优先，其次直连与内置镜像 */
    private static java.util.List<String> apiCandidates() {
        java.util.List<String> list = new java.util.ArrayList<>();
        String custom = normalizeMirror(Hawk.get(HawkConfig.UPDATE_MIRROR_PREFIX, ""));
        if (!custom.isEmpty()) list.add(custom);
        for (String m : API_MIRRORS) list.add(m);
        return list;
    }

    /** 下载的候选链接：自定义镜像 → 直连 → 内置镜像 */
    private static java.util.List<String> downloadCandidates(String apkUrl) {
        java.util.List<String> list = new java.util.ArrayList<>();
        String custom = normalizeMirror(Hawk.get(HawkConfig.UPDATE_MIRROR_PREFIX, ""));
        if (!custom.isEmpty()) list.add(custom + apkUrl);
        list.add(apkUrl);
        for (String m : DOWNLOAD_MIRRORS) {
            if (m.isEmpty()) continue;
            list.add(m + apkUrl);
        }
        return list;
    }

    /** 规范化用户填写的镜像：补全 https:// 与结尾斜杠 */
    private static String normalizeMirror(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.isEmpty()) return "";
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://" + s;
        if (!s.endsWith("/")) s = s + "/";
        return s;
    }

    /** 日志用的可读来源名 */
    private static String label(String prefix) {
        return prefix.isEmpty() ? "直连" : "镜像 " + prefix;
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

    /**
     * 下载 APK 到应用专属 Download 目录。
     * 直连失败时自动改用中转镜像重试（国内网络下 github.com 下载经常超时）。
     */
    public static void download(Context ctx, UpdateInfo info, DownloadListener listener) {
        File target = targetFile(ctx, info);
        new Thread(() -> {
            String lastErr = null;
            for (String url : downloadCandidates(info.apkUrl)) {
                try {
                    downloadOne(url, target, listener);
                    com.github.tvbox.osc.util.RunLog.i("更新下载: 成功 " + url);
                    postProgress(listener, 100);
                    postFinished(listener, target);
                    return;
                } catch (Throwable t) {
                    lastErr = describe(t);
                    com.github.tvbox.osc.util.RunLog.i("更新下载: 失败 " + url + " → " + lastErr);
                    try {
                        target.delete();
                    } catch (Exception ignore) {
                    }
                }
            }
            postFailed(listener, "下载失败（已尝试直连与镜像）：" + lastErr);
        }, "update-download").start();
    }

    /** 从单个地址下载到目标文件；失败抛异常由上层换源重试 */
    private static void downloadOne(String url, File target, DownloadListener listener) throws Exception {
        OkHttpClient client = HttpClients.shared().newBuilder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
        Request req = new Request.Builder().url(url)
                .header("User-Agent", "TVBoxOS-Live/" + BuildConfig.VERSION_NAME)
                .build();
        InputStream in = null;
        FileOutputStream out = null;
        try (Response resp = client.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                throw new java.io.IOException("HTTP " + resp.code());
            }
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
                    postProgress(listener, (int) (done * 100 / total));
                }
            }
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }
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
            if (apk == null || !apk.exists()) {
                ToastUtil.show(ctx, "安装包不存在，请重新下载");
                return;
            }
            String path = apk.getAbsolutePath();
            com.github.tvbox.osc.util.RunLog.i("准备安装 APK: " + path);

            // Android 8+ 需要"允许安装未知应用"授权，未授权则先引导去设置页
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    && !ctx.getPackageManager().canRequestPackageInstalls()) {
                com.github.tvbox.osc.util.RunLog.i("缺少未知来源安装授权，跳转设置页");
                try {
                    Intent i = new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + ctx.getPackageName()));
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(i);
                    ToastUtil.show(ctx, "请开启「允许安装未知应用」，然后回到文件管理器安装：" + path);
                } catch (Throwable t) {
                    fallbackManual(ctx, apk, "无法打开授权设置页");
                }
                return;
            }

            boolean ok = tryInstall(ctx, apk);
            if (!ok) {
                // 最后再试一次系统安装专用 Action：部分电视 ROM 的 PackageInstaller
                // 只注册了它，而不接 ACTION_VIEW
                ok = tryInstallWithAction(ctx, apk, Intent.ACTION_INSTALL_PACKAGE);
            }
            if (ok) {
                com.github.tvbox.osc.util.RunLog.i("已唤起安装器");
            } else {
                fallbackManual(ctx, apk, "未找到可用的安装器");
            }
        } catch (Throwable t) {
            Timber.w(t, "唤起安装器失败");
            fallbackManual(ctx, apk, "安装失败：" + describe(t));
        }
    }

    /**
     * 依次尝试不同 URI 方案唤起安装器。
     *
     * <p><b>关键点：</b>Android 7.0（API 24）是分水岭——
     * 7.0+ 禁止对外暴露 file://（会抛 FileUriExposedException），必须用 FileProvider 的 content://；
     * 而 7.0 以下的老安装器（如 Android 6 的小米盒子）多数<b>只认 file://</b>，
     * 给 content:// 会因为没有应用接得住而直接抛出 ActivityNotFoundException。
     * 这里按版本选主方案，再交叉回退一次以兼容行为相反的定制 ROM。
     */
    private static boolean tryInstall(Context ctx, File apk) {
        boolean nougat = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N;
        if (launchInstall(ctx, apk, nougat)) return true;
        // 交叉回退：部分 ROM 的接受能力与版本不匹配
        return launchInstall(ctx, apk, !nougat);
    }

    /**
     * @param useFileProvider true 用 content://（Android 7+ 必需），false 用 file://（老系统必需）
     */
    private static boolean launchInstall(Context ctx, File apk, boolean useFileProvider, String action) {
        try {
            Uri uri = useFileProvider
                    ? FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".fileprovider", apk)
                    : Uri.fromFile(apk);
            Intent intent = new Intent(action);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            // 先查有没有应用能接这个 Intent，避免 startActivity 直接抛
            // ActivityNotFoundException（那正是"无法打开安装器"的真正来源）
            java.util.List<android.content.pm.ResolveInfo> list =
                    ctx.getPackageManager().queryIntentActivities(intent, 0);
            if (list == null || list.isEmpty()) {
                com.github.tvbox.osc.util.RunLog.i("无应用可处理安装 Intent，方案="
                        + (useFileProvider ? "content://" : "file://"));
                return false;
            }
            ctx.startActivity(intent);
            return true;
        } catch (Throwable t) {
            com.github.tvbox.osc.util.RunLog.i("唤起安装器异常，方案="
                    + (useFileProvider ? "content://" : "file://") + " → " + describe(t));
            return false;
        }
    }

    /** 用指定 Action（如系统专用的 ACTION_INSTALL_PACKAGE）再试一次 */
    private static boolean tryInstallWithAction(Context ctx, File apk, String action) {
        boolean nougat = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N;
        return launchInstall(ctx, apk, nougat, action)
                || launchInstall(ctx, apk, !nougat, action);
    }

    /** @param useFileProvider true 用 content://（Android 7+ 必需），false 用 file://（老系统必需） */
    private static boolean launchInstall(Context ctx, File apk, boolean useFileProvider) {
        return launchInstall(ctx, apk, useFileProvider, Intent.ACTION_VIEW);
    }

    /** 兜底提示：给出真实文件路径，方便手工安装 */
    private static void fallbackManual(Context ctx, File apk, String reason) {
        String path = apk == null ? "" : apk.getAbsolutePath();
        com.github.tvbox.osc.util.RunLog.i("安装失败: " + reason + " path=" + path);
        try {
            ToastUtil.show(ctx, reason + "，文件已保存到：\n" + path);
        } catch (Throwable ignore) {
        }
    }
}
