package com.github.tvbox.osc.player;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.TransferListener;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 按URI 协议分发的数据源工厂。
 *
 * <p>ExoPlayer 的 {@code DataSource.Factory#createDataSource()} 在创建时拿不到 URI，
 * 因此无法在工厂层面按协议选择实现。本类返回一个包装 DataSource，在 {@link DataSource#open}
 * 时根据 {@code DataSpec} 的 scheme 选择真正的底层数据源：
 *
 * <ul>
 *   <li>{@code rtmp://} / {@code rtmpe://} 等 → RtmpDataSource（extension-rtmp 提供）</li>
 *   <li>其它（http/https/file/content…） → 传入的默认数据源</li>
 * </ul>
 *
 * <p>注意：{@code rtsp://} 不经过 DataSource，ExoPlayer 会直接创建 RtspMediaSource，
 * 只要 exoplayer-rtsp 模块在 classpath 上即可，无需在此处理。
 */
public class SchemeDataSourceFactory implements DataSource.Factory {

    private final DataSource.Factory defaultFactory;
    @Nullable
    private final DataSource.Factory rtmpFactory;

    /**
     * @param defaultFactory 默认数据源工厂（一般为 DefaultHttpDataSource.Factory）
     */
    public SchemeDataSourceFactory(DataSource.Factory defaultFactory) {
        this.defaultFactory = defaultFactory;
        this.rtmpFactory = createRtmpFactory();
    }

    /**
     * 反射创建 RtmpDataSource.Factory：即使将来移除 extension-rtmp 依赖也不会崩溃，
     * 只是退化为不支持 RTMP。
     */
    @Nullable
    private static DataSource.Factory createRtmpFactory() {
        try {
            Class<?> clazz = Class.forName("com.google.android.exoplayer2.ext.rtmp.RtmpDataSource$Factory");
            Object factory = clazz.getDeclaredConstructor().newInstance();
            return (DataSource.Factory) factory;
        } catch (Throwable t) {
            return null;
        }
    }

    @NonNull
    @Override
    public DataSource createDataSource() {
        return new SchemeDispatchingDataSource(
                defaultFactory.createDataSource(),
                rtmpFactory != null ? rtmpFactory.createDataSource() : null);
    }

    /** 在 open() 时按协议选择底层数据源的包装实现 */
    private static class SchemeDispatchingDataSource implements DataSource {

        private final DataSource defaultSource;
        @Nullable
        private final DataSource rtmpSource;
        private final List<TransferListener> pendingListeners = new ArrayList<>();

        @Nullable
        private DataSource active;

        SchemeDispatchingDataSource(DataSource defaultSource, @Nullable DataSource rtmpSource) {
            this.defaultSource = defaultSource;
            this.rtmpSource = rtmpSource;
        }

        @Override
        public void addTransferListener(@NonNull TransferListener transferListener) {
            //尚未确定使用哪个数据源，先记录，open() 时一并注册到两者
            pendingListeners.add(transferListener);
            defaultSource.addTransferListener(transferListener);
            if (rtmpSource != null) {
                rtmpSource.addTransferListener(transferListener);
            }
        }

        @Override
        public long open(@NonNull DataSpec dataSpec) throws IOException {
            active = select(dataSpec.uri);
            return active.open(dataSpec);
        }

        private DataSource select(@Nullable Uri uri) {
            String scheme = uri != null ? uri.getScheme() : null;
            if (scheme != null && rtmpSource != null) {
                String s = scheme.toLowerCase(Locale.ROOT);
                if (s.startsWith("rtmp")) {
                    return rtmpSource;
                }
            }
            return defaultSource;
        }

        @Override
        public int read(@NonNull byte[] buffer, int offset, int length) throws IOException {
            if (active == null) throw new IOException("DataSource 未打开");
            return active.read(buffer, offset, length);
        }

        @Nullable
        @Override
        public Uri getUri() {
            return active != null ? active.getUri() : null;
        }

        @NonNull
        @Override
        public Map<String, List<String>> getResponseHeaders() {
            return active != null ? active.getResponseHeaders() : java.util.Collections.emptyMap();
        }

        @Override
        public void close() throws IOException {
            if (active != null) {
                try {
                    active.close();
                } finally {
                    active = null;
                }
            }
        }
    }
}
