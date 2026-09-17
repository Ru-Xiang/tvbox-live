package com.github.tvbox.osc.util;

import java.util.concurrent.TimeUnit;

import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;

/**
 * 全局共享的 OkHttp 客户端。
 *
 * <p><b>为什么要共享：</b>每个 {@link OkHttpClient} 实例都会自带独立的
 * {@link ConnectionPool}（默认 5 个空闲连接保活 5 分钟）、Dispatcher 线程池
 * （最多 64 个并发请求线程）和响应缓存。项目里 {@code IptvApiService} 与
 * {@code EpgService} 各建一个，等于把这些资源整整翻倍——在只有 100~200MB
 * 可用堆的电视盒子上，这是没必要的常驻开销。
 *
 * <p>OkHttp 官方推荐全应用共享单个实例；需要不同超时的场景用
 * {@link OkHttpClient#newBuilder()} 派生，派生出的客户端会<b>复用</b>
 * 同一套连接池与线程池，不会重复分配。
 */
public final class HttpClients {

    /** 空闲连接保活数量：电视端同时访问的域名有限，5 个足够且不浪费 */
    private static final int MAX_IDLE_CONNECTIONS = 5;
    private static final long KEEP_ALIVE_MINUTES = 5L;

    private static volatile OkHttpClient shared;

    private HttpClients() {}

    /**
     * 获取共享的基础客户端（连接 15s / 读取 30s / 写入 15s）。
     * 需要其它超时配置时请用 {@link #newBuilder(int, int)} 派生。
     */
    public static OkHttpClient shared() {
        if (shared == null) {
            synchronized (HttpClients.class) {
                if (shared == null) {
                    shared = new OkHttpClient.Builder()
                            .connectTimeout(15, TimeUnit.SECONDS)
                            .readTimeout(30, TimeUnit.SECONDS)
                            .writeTimeout(15, TimeUnit.SECONDS)
                            .followRedirects(true)
                            .followSslRedirects(true)
                            .connectionPool(new ConnectionPool(
                                    MAX_IDLE_CONNECTIONS, KEEP_ALIVE_MINUTES, TimeUnit.MINUTES))
                            .retryOnConnectionFailure(true)
                            .build();
                }
            }
        }
        return shared;
    }

    /**
     * 基于共享客户端派生一个自定义超时的客户端。
     * 派生实例复用同一套连接池与线程池，开销极小。
     *
     * @param connectSeconds 连接超时（秒）
     * @param readSeconds    读取超时（秒）
     */
    public static OkHttpClient newBuilder(int connectSeconds, int readSeconds) {
        return shared().newBuilder()
                .connectTimeout(connectSeconds, TimeUnit.SECONDS)
                .readTimeout(readSeconds, TimeUnit.SECONDS)
                .build();
    }

    /**
     * 释放空闲连接（内存紧张时调用）。
     * 进行中的请求不受影响，连接池会在后续请求时按需重建。
     */
    public static void evictIdleConnections() {
        try {
            OkHttpClient c = shared;
            if (c != null) c.connectionPool().evictAll();
        } catch (Throwable ignore) {
        }
    }
}
