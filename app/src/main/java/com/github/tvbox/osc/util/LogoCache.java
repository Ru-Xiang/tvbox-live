package com.github.tvbox.osc.util;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import timber.log.Timber;

/**
 * 台标本地缓存。
 *
 * 策略：按“频道名”在本地保存一份台标图片。
 * - 本地已存在同名台标 → 直接使用本地文件，完全不走网络（省流量、秒出图）；
 * - 本地不存在 → 返回远程地址供加载，同时后台下载并保存，供下次复用。
 *
 * 由于按频道名而非 URL 索引，即使更换了台标地址前缀，本地缓存依然可复用。
 */
public final class LogoCache {

    private static final String DIR_NAME = "logos";
    /** 单个台标最大字节数，超出不缓存（防止异常大图占满存储） */
    private static final int MAX_FILE_BYTES = 512 * 1024;
    /** 本地台标文件数量上限，超出时清理最旧的文件 */
    private static final int MAX_FILES = 800;
    /** 下载超时（毫秒） */
    private static final int TIMEOUT_MS = 8000;

    /** 待下载队列容量上限：超出直接丢弃，避免快速滑动上千频道时堆积任务 */
    private static final int MAX_QUEUE = 64;

    /**
     * 单线程后台下载，避免大量并发抢占带宽影响播放。
     *
     * 使用<b>有界</b>队列：原先的 newSingleThreadExecutor 是无界
     * LinkedBlockingQueue，上千频道的列表滑动会堆积上千个待下载任务，
     * 既占内存又让台标按早已滑走的顺序慢慢下载。
     *
     * 队列满时用 AbortPolicy 抛出 RejectedExecutionException，由 {@link #cacheAsync}
     * 的 catch 分支清除 inFlight 标记——不能用 DiscardPolicy 静默丢弃，
     * 否则任务体不执行、标记永不清除，该台标将永久无法重试。
     */
    private static final ExecutorService IO = new java.util.concurrent.ThreadPoolExecutor(
            1, 1, 30L, java.util.concurrent.TimeUnit.SECONDS,
            new java.util.concurrent.ArrayBlockingQueue<Runnable>(MAX_QUEUE),
            r -> {
                Thread t = new Thread(r, "LogoCache");
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            },
            new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());

    /** 正在下载或已确认失败的频道，避免重复请求 */
    private static final Set<String> inFlight =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    /**
     * "该频道本地台标已存在"的内存标记。
     *
     * {@link #resolve} 在 RecyclerView.onBindViewHolder 里被调用，属列表滑动热路径；
     * 原先每次绑定都要做 File#isFile + File#length 两次磁盘 IO，
     * 在低端盒子上会造成可感知的滑动掉帧。这里把判定结果记在内存中，
     * 之后同一频道直接命中，不再碰磁盘。
     */
    private static final ConcurrentHashMap<String, File> localHit = new ConcurrentHashMap<>();

    /** 内存标记上限，防止频道极多时无界增长 */
    private static final int MAX_MEM_ENTRIES = 2000;

    private LogoCache() {}

    /**
     * 解析台标加载源。
     *
     * @return 本地 {@link File}（优先）、远程 URL 字符串，或 null（无可用台标）
     */
    public static Object resolve(Context context, String channelName, String remoteUrl) {
        if (context == null) return remoteUrl;
        try {
            String key = safeName(channelName);

            // 先查内存标记，命中则完全不碰磁盘
            if (key != null) {
                File cached = localHit.get(key);
                if (cached != null) return cached;
            }

            File local = localFile(context, channelName);
            if (local != null && local.isFile() && local.length() > 0) {
                if (key != null && localHit.size() < MAX_MEM_ENTRIES) {
                    localHit.put(key, local);
                }
                return local;
            }
            if (remoteUrl == null || remoteUrl.trim().isEmpty()) {
                return null;
            }
            // 本地没有则先用远程地址显示，并后台缓存一份供下次复用
            cacheAsync(context, channelName, remoteUrl.trim());
            return remoteUrl;
        } catch (Throwable t) {
            return remoteUrl;
        }
    }

    /** 后台下载并保存台标 */
    private static void cacheAsync(Context context, String channelName, String url) {
        final String key = safeName(channelName);
        if (key == null || key.isEmpty()) return;
        if (!inFlight.add(key)) return; // 已在处理中

        final Context appCtx = context.getApplicationContext();
        try {
            IO.execute(() -> {
                try {
                    download(appCtx, key, url);
                } catch (Throwable t) {
                    Timber.d("缓存台标失败: %s", t.getMessage());
                } finally {
                    // 无论成功失败都移除标记：失败后下次仍可重试（但不会在本次列表刷新中反复请求）
                    inFlight.remove(key);
                }
            });
        } catch (Throwable t) {
            inFlight.remove(key);
        }
    }

    private static void download(Context context, String key, String url) throws Exception {
        File dir = cacheDir(context);
        if (dir == null) return;
        File target = new File(dir, key);
        if (target.isFile() && target.length() > 0) return;

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "okhttp/4.12.0");
            if (conn.getResponseCode() / 100 != 2) return;

            // 先写临时文件再改名，避免中断产生半张损坏图片
            File tmp = new File(dir, key + ".tmp");
            int total = 0;
            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    total += n;
                    if (total > MAX_FILE_BYTES) {
                        break;
                    }
                    out.write(buf, 0, n);
                }
            }
            if (total <= 0 || total > MAX_FILE_BYTES) {
                tmp.delete();
                return;
            }
            if (!tmp.renameTo(target)) {
                tmp.delete();
                return;
            }
            // 新下载完成，登记内存标记，下次绑定该频道即可直接命中本地文件
            if (localHit.size() < MAX_MEM_ENTRIES) {
                localHit.put(key, target);
            }
            trimIfNeeded(dir);
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Throwable ignore) {}
            }
        }
    }

    /** 文件数超上限时删除最旧的一批 */
    private static void trimIfNeeded(File dir) {
        try {
            File[] files = dir.listFiles();
            if (files == null || files.length <= MAX_FILES) return;
            Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            int removeCount = files.length - MAX_FILES;
            for (int i = 0; i < removeCount; i++) {
                String name = files[i].getName();
                files[i].delete();
                // 文件已删除，必须同步移除内存标记，否则 resolve 会返回不存在的文件
                localHit.remove(name);
            }
        } catch (Throwable ignore) {
        }
    }

    private static File localFile(Context context, String channelName) {
        String key = safeName(channelName);
        if (key == null || key.isEmpty()) return null;
        File dir = cacheDir(context);
        return dir == null ? null : new File(dir, key);
    }

    private static File cacheDir(Context context) {
        try {
            File dir = new File(context.getApplicationContext().getFilesDir(), DIR_NAME);
            if (!dir.exists() && !dir.mkdirs()) {
                return dir.isDirectory() ? dir : null;
            }
            return dir;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 频道名 → 安全的文件名（避免路径穿越与非法字符） */
    private static String safeName(String channelName) {
        if (channelName == null) return null;
        String s = channelName.trim();
        if (s.isEmpty()) return null;
        StringBuilder sb = new StringBuilder(s.length() + 4);
        for (int i = 0; i < s.length() && sb.length() < 80; i++) {
            char c = s.charAt(i);
            if (c == '/' || c == '\\' || c == ':' || c == '*' || c == '?'
                    || c == '"' || c == '<' || c == '>' || c == '|' || c ==0) {
                sb.append('_');
            } else {
                sb.append(c);
            }
        }
        return sb.length() == 0 ? null : sb.toString() + ".img";
    }

    /** 清空本地台标缓存 */
    public static void clear(Context context) {
        try {
            // 先清内存标记，避免清盘后仍返回已删除的文件
            localHit.clear();
            File dir = cacheDir(context);
            if (dir == null) return;
            File[] files = dir.listFiles();
            if (files == null) return;
            for (File f : files) f.delete();
            Timber.i("已清空本地台标缓存");
        } catch (Throwable ignore) {
        }
    }
}
