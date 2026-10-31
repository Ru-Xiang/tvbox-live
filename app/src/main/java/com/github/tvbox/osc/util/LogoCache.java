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

    /** 正在下载中的频道，避免重复排队 */
    private static final Set<String> inFlight =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    /**
     * 下载失败的频道 → {失败次数, 最近失败时刻(elapsedRealtime)}。
     *
     * 原先失败后立即清除标记，列表每次重新绑定都会再排一次死链：
     * 单线程每个死链要耗 16s 超时，64 格队列被死链塞满，能下载的台标反而被拒绝。
     * 这里按失败次数指数退避（2 分钟起，最长 60 分钟），期间不再请求。
     */
    private static final ConcurrentHashMap<String, long[]> failedAt = new ConcurrentHashMap<>();
    private static final long FAIL_BACKOFF_BASE_MS = 2 * 60 * 1000L;
    private static final long FAIL_BACKOFF_MAX_MS = 60 * 60 * 1000L;

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
            if (local != null && local.isFile() && local.length() > 0
                    && validLocalImage(local)) {
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
        if (inBackoff(key)) return; // 近期失败过，退避期内不再请求
        if (!inFlight.add(key)) return; // 已在处理中

        final Context appCtx = context.getApplicationContext();
        try {
            IO.execute(() -> {
                boolean ok = false;
                try {
                    ok = download(appCtx, key, url);
                } catch (Throwable t) {
                    Timber.d("缓存台标失败: %s", t.getMessage());
                } finally {
                    if (ok) {
                        failedAt.remove(key);
                    } else {
                        recordFailure(key);
                    }
                    inFlight.remove(key);
                }
            });
        } catch (Throwable t) {
            // 队列满被拒绝不是该地址的问题，不记失败，下次绑定仍可排队
            inFlight.remove(key);
        }
    }

    private static boolean inBackoff(String key) {
        long[] f = failedAt.get(key);
        if (f == null) return false;
        long count = Math.max(1L, Math.min(f[0], 6L));
        long wait = Math.min(FAIL_BACKOFF_BASE_MS << (count - 1), FAIL_BACKOFF_MAX_MS);
        return android.os.SystemClock.elapsedRealtime() - f[1] < wait;
    }

    private static void recordFailure(String key) {
        // 有界：频道极多时整体清空，最多多请求一轮，不会无界增长
        if (failedAt.size() >= MAX_MEM_ENTRIES && !failedAt.containsKey(key)) failedAt.clear();
        long[] old = failedAt.get(key);
        long count = old == null ? 1L : old[0] + 1L;
        failedAt.put(key, new long[]{count, android.os.SystemClock.elapsedRealtime()});
    }

    /** @return true 表示本地已有有效台标（新下载或早已存在） */
    private static boolean download(Context context, String key, String url) throws Exception {
        File dir = cacheDir(context);
        if (dir == null) return false;
        File target = new File(dir, key);
        if (target.isFile() && target.length() > 0) return true;

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "okhttp/4.12.0");
            if (conn.getResponseCode() / 100 != 2) return false;
            // 强制门户/错误页常以 200 返回 HTML，存下来会让该频道永久显示破图。
            // 只拦 text/html：部分配置错误的台标站会把真实 PNG/JPEG 标成 text/plain，
            // 真正的判定交给下面的文件头魔数校验
            String ct = conn.getContentType();
            if (ct != null && ct.toLowerCase(java.util.Locale.US).startsWith("text/html")) return false;

            // 先写临时文件再改名，避免中断产生半张损坏图片
            File tmp = new File(dir, key + ".tmp");
            int total = 0;
            byte[] head = new byte[12];
            int headLen = 0;
            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (headLen < head.length && n > 0) {
                        int c = Math.min(head.length - headLen, n);
                        System.arraycopy(buf, 0, head, headLen, c);
                        headLen += c;
                    }
                    total += n;
                    if (total > MAX_FILE_BYTES) {
                        break;
                    }
                    out.write(buf, 0, n);
                }
            }
            if (total <= 0 || total > MAX_FILE_BYTES) {
                tmp.delete();
                return false;
            }
            // 不能只信 Content-Type（很多台标站给 octet-stream），以文件头魔数为准
            if (!isImageHeader(head, headLen)) {
                tmp.delete();
                return false;
            }
            if (!tmp.renameTo(target)) {
                tmp.delete();
                return false;
            }
            // 新下载完成，登记内存标记，下次绑定该频道即可直接命中本地文件
            if (localHit.size() < MAX_MEM_ENTRIES) {
                localHit.put(key, target);
            }
            trimIfNeeded(dir);
            return true;
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Throwable ignore) {}
            }
        }
    }

    /** 按文件头判断是否为 Glide/BitmapFactory 可解码的位图格式 */
    private static boolean isImageHeader(byte[] b, int len) {
        if (b == null || len < 4) return false;
        // PNG
        if ((b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return true;
        // JPEG
        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return true;
        // GIF
        if (b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8') return true;
        // WEBP: "RIFF"....“WEBP”
        if (len >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return true;
        // BMP
        if (b[0] == 'B' && b[1] == 'M') return true;
        // ICO
        return b[0] == 0 && b[1] == 0 && b[2] == 1 && b[3] == 0;
    }

    /**
     * 首次命中本地文件时校验一次文件头（之后走 localHit 不再碰磁盘），
     * 顺带修复旧版本已存下的 HTML 等非图片文件：删掉后改走远程并重新缓存。
     */
    private static boolean validLocalImage(File f) {
        byte[] head = new byte[12];
        int len = 0;
        try (InputStream in = new java.io.FileInputStream(f)) {
            int n;
            while (len < head.length && (n = in.read(head, len, head.length - len)) > 0) {
                len += n;
            }
        } catch (Throwable t) {
            // 读失败不当作坏文件删除，交给 Glide 处理
            return true;
        }
        if (isImageHeader(head, len)) return true;
        try {
            f.delete();
        } catch (Throwable ignore) {
        }
        Timber.d("本地台标不是有效图片，已删除: %s", f.getName());
        return false;
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
            // 用户主动清缓存时也给失败过的台标一次重新下载的机会
            failedAt.clear();
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
