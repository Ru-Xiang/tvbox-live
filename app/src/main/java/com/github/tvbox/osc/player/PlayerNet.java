package com.github.tvbox.osc.player;

import android.net.Uri;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.github.tvbox.osc.util.HttpClients;
import com.github.tvbox.osc.util.StreamUrl;
import com.google.android.exoplayer2.ext.okhttp.OkHttpDataSource;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;
import com.google.android.exoplayer2.upstream.HttpDataSource;

import java.io.IOException;
import java.net.InetAddress;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 播放用的网络栈（P1）。
 *
 * <ul>
 *   <li><b>OkHttp 数据源</b>：与 App 其它请求共用 {@link HttpClients} 的连接池，
 *       HLS 分片、换台回到同一主机都能复用已建好的 TCP/TLS 连接（HttpURLConnection 做不到跨实例复用）。
 *       超时与 1.2.x 的 DefaultHttpDataSource 保持一致（连接 8 秒 / 读 10 秒），可在设置里关掉退回系统栈。</li>
 *   <li><b>预热</b>：频道列表焦点停在某台、或起播后对上下相邻频道，提前做 DNS 解析；
 *       HLS 线路再发一个 HEAD 把连接建好放进连接池。不对 FLV/TS 直播流发请求——
 *       有的网关按连接数限流，空连接可能把正在看的那路挤掉。</li>
 * </ul>
 */
public final class PlayerNet {

    private PlayerNet() {}

    static final int CONNECT_TIMEOUT_MS = 8000;
    static final int READ_TIMEOUT_MS = 10000;

    /** 同一主机预热的最小间隔 */
    private static final long PREWARM_HOST_INTERVAL_MS = 30_000L;
    /** 预热记录上限，防止长时间浏览列表后无限增长 */
    private static final int PREWARM_MEMO_MAX = 64;

    private static volatile OkHttpClient client;
    private static final Map<String, Long> warmedHosts = new LinkedHashMap<String, Long>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > PREWARM_MEMO_MAX;
        }
    };
    /** DNS 预解析用单线程：InetAddress.getAllByName 是阻塞调用，且没必要并发 */
    private static final ExecutorService dnsExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "player-prewarm");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    /** 播放专用客户端：派生自共享客户端（同一连接池与线程池），只改超时 */
    public static OkHttpClient client() {
        OkHttpClient c = client;
        if (c == null) {
            synchronized (PlayerNet.class) {
                c = client;
                if (c == null) {
                    c = HttpClients.shared().newBuilder()
                            .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                            .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                            .followRedirects(true)
                            .followSslRedirects(true)
                            .retryOnConnectionFailure(true)
                            .build();
                    client = c;
                }
            }
        }
        return c;
    }

    /**
     * 按线路自带的 UA / 请求头建 HTTP 数据源工厂。
     *
     * @param useOkHttp false 时用 1.2.x 的 DefaultHttpDataSource（某条线路在 OkHttp 下异常时可退回）
     */
    public static HttpDataSource.Factory httpFactory(boolean useOkHttp, String userAgent,
                                                     @Nullable Map<String, String> headers) {
        // OkHttp 对请求头字符很严格（值只许 \t 与 0x20-0x7E），含中文等字符时 open() 直接抛
        // IllegalArgumentException 且不重试，整条线路判死；这种线路退回系统栈（与 1.2.x、测速一致）
        if (useOkHttp && okHttpSafe(userAgent, headers)) {
            OkHttpDataSource.Factory f = new OkHttpDataSource.Factory(client()).setUserAgent(userAgent);
            if (headers != null && !headers.isEmpty()) f.setDefaultRequestProperties(headers);
            return f;
        }
        DefaultHttpDataSource.Factory f = new DefaultHttpDataSource.Factory()
                .setUserAgent(userAgent)
                .setConnectTimeoutMs(CONNECT_TIMEOUT_MS)
                .setReadTimeoutMs(READ_TIMEOUT_MS)
                .setAllowCrossProtocolRedirects(true)
                .setKeepPostFor302Redirects(true);
        if (headers != null && !headers.isEmpty()) f.setDefaultRequestProperties(headers);
        return f;
    }

    /** UA 与请求头都是 OkHttp 接受的字符 */
    static boolean okHttpSafe(@Nullable String userAgent, @Nullable Map<String, String> headers) {
        if (userAgent != null && !headerValueOk(userAgent)) return false;
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                String k = e.getKey();
                if (k == null || k.isEmpty()) return false;
                for (int i = 0; i < k.length(); i++) {
                    char c = k.charAt(i);
                    if (c <= 0x20 || c >= 0x7F) return false;
                }
                if (e.getValue() != null && !headerValueOk(e.getValue())) return false;
            }
        }
        return true;
    }

    private static boolean headerValueOk(String v) {
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if ((c < 0x20 && c != '\t') || c >= 0x7F) return false;
        }
        return true;
    }

    /**
     * 预热一条线路（任意线程调用，立即返回）。
     *
     * @param rawUrl 线路原串（可带 $标签 / |请求头）
     */
    public static void prewarm(@Nullable String rawUrl) {
        if (rawUrl == null) return;
        final String play = StreamUrl.playUrl(rawUrl);
        if (play == null) return;
        final Uri uri;
        try {
            uri = Uri.parse(play);
        } catch (Throwable t) {
            return;
        }
        String scheme = uri.getScheme();
        final String host = uri.getHost();
        if (scheme == null || host == null || host.isEmpty()) return;
        scheme = scheme.toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) return;
        final String key = scheme + "://" + host + ":" + uri.getPort();
        long now = SystemClock.elapsedRealtime();
        synchronized (warmedHosts) {
            Long last = warmedHosts.get(key);
            if (last != null && now - last < PREWARM_HOST_INTERVAL_MS) return;
            warmedHosts.put(key, now);
        }
        String path = uri.getPath() != null ? uri.getPath().toLowerCase(Locale.ROOT) : "";
        boolean hls = path.endsWith(".m3u8") || path.endsWith(".m3u");
        if (!hls) {
            // 非 HLS：只预解析 DNS（系统会缓存结果），不建连接
            try {
                dnsExecutor.execute(() -> {
                    try {
                        InetAddress.getAllByName(host);
                    } catch (Throwable ignore) {
                    }
                });
            } catch (Throwable ignore) {
            }
            return;
        }
        // HLS：HEAD 一次清单，DNS + TCP + TLS 都做完，连接留在池里给接下来的起播复用
        if (!okHttpSafe(StreamUrl.userAgent(rawUrl), StreamUrl.headers(rawUrl))) return;
        try {
            Request.Builder rb = new Request.Builder().url(play).head();
            String ua = StreamUrl.userAgent(rawUrl);
            rb.header("User-Agent", ua != null && !ua.isEmpty() ? ua : StreamUrl.DEFAULT_USER_AGENT);
            for (Map.Entry<String, String> e : StreamUrl.headers(rawUrl).entrySet()) {
                if ("User-Agent".equalsIgnoreCase(e.getKey())) continue;
                try {
                    rb.header(e.getKey(), e.getValue());
                } catch (Throwable ignore) {
                    // 非法请求头字符：忽略这一项
                }
            }
            client().newCall(rb.build()).enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) {
                    response.close();
                }
            });
        } catch (Throwable ignore) {
        }
    }
}
