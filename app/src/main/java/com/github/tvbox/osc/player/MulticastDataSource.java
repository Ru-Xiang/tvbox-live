package com.github.tvbox.osc.player;

import android.content.Context;
import android.net.Uri;
import android.net.wifi.WifiManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.github.tvbox.osc.util.MulticastUrl;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.upstream.BaseDataSource;
import com.google.android.exoplayer2.upstream.DataSourceException;
import com.google.android.exoplayer2.upstream.DataSpec;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;

import timber.log.Timber;

/**
 * 组播直连数据源（P2）：直接加入 {@code rtp://} / {@code udp://} 组播组收 TS 流，不需要 udpxy。
 *
 * <p>每个 UDP 包自动识别：
 * <ul>
 *   <li>首字节 0x47：裸 UDP 承载的 TS，原样交出；</li>
 *   <li>RTP v2 头（首字节高两位 10）：剥掉 12 字节固定头 + CSRC + 扩展头 + 填充，交出里面的 TS。</li>
 * </ul>
 * 交给 ExoPlayer 的就是一条连续的 TS 字节流，后面由 TsExtractor 解封装，和 HTTP-TS 走同一条路。
 *
 * <p>不做 RTP 乱序重排：局域网组播几乎不乱序，丢包由 TS 解封装层容错。
 * Wi-Fi 下需要持有 MulticastLock，否则很多网卡会把组播包过滤掉。
 */
public final class MulticastDataSource extends BaseDataSource {

    /** 单个 UDP 包上限：常见 7×188+12 = 1328 字节，留足余量 */
    private static final int MAX_PACKET = 65536;
    /** 收不到包多久算断流（毫秒） */
    private static final int SOCKET_TIMEOUT_MS = 8000;
    /** 内核接收缓冲：4K 组播 30+ Mbps，默认缓冲在主线程卡一下就会丢包 */
    private static final int RECEIVE_BUFFER = 4 * 1024 * 1024;

    private final Context appContext;
    private final byte[] packetBuffer = new byte[MAX_PACKET];
    private final DatagramPacket packet = new DatagramPacket(packetBuffer, MAX_PACKET);

    @Nullable private Uri uri;
    @Nullable private MulticastSocket socket;
    @Nullable private InetAddress group;
    @Nullable private WifiManager.MulticastLock multicastLock;
    private boolean opened;
    /** 当前包里尚未交出的有效负载 [offset, offset+remaining) */
    private int payloadOffset;
    private int payloadRemaining;

    public MulticastDataSource(Context context) {
        super(/* isNetwork= */ true);
        this.appContext = context.getApplicationContext();
    }

    @Override
    public long open(@NonNull DataSpec dataSpec) throws IOException {
        uri = dataSpec.uri;
        String hostPort = MulticastUrl.groupAndPort(uri.toString());
        if (hostPort == null) {
            throw new DataSourceException(new IOException("组播地址格式不对: " + uri),
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED);
        }
        int colon = hostPort.lastIndexOf(':');
        String host = hostPort.substring(0, colon);
        int port;
        try {
            port = Integer.parseInt(hostPort.substring(colon + 1));
        } catch (NumberFormatException e) {
            throw new DataSourceException(e, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED);
        }
        transferInitializing(dataSpec);
        try {
            // udp://@:1234：不加入任何组，只在本机端口收单播
            group = host.isEmpty() ? null : InetAddress.getByName(host);
            boolean multicast = group != null && group.isMulticastAddress();
            if (multicast && isSsmGroup(group) && MulticastUrl.sourceAddress(uri.toString()) != null) {
                // 232.0.0.0/8 源地址特定组播：java.net 在 API 33 以下没有按源加入的接口，
                // 普通 join 收不到包，与其干等 8 秒超时不如直接说明
                throw new IOException("组播直连暂不支持指定源地址的组播(SSM)，请在设置里配置 udpxy 代理");
            }
            MulticastSocket s;
            if (multicast) {
                // 绑定到组地址而不是通配地址：同端口上别的组（本机其它程序加入的、上一个还没关的加载器）
                // 的包不会混进来，否则 TS 解封装会收到两个频道交错的包
                s = new MulticastSocket((SocketAddress) null);
                socket = s; // 先登记，bind 失败时 closeQuietly 才能关掉它
                s.setReuseAddress(true);
                try {
                    s.bind(new InetSocketAddress(group, port));
                } catch (IOException bindToGroupFailed) {
                    s.bind(new InetSocketAddress(port));
                }
            } else {
                s = new MulticastSocket(port);
            }
            socket = s;
            s.setSoTimeout(SOCKET_TIMEOUT_MS);
            try {
                s.setReceiveBufferSize(RECEIVE_BUFFER);
            } catch (Exception ignore) {
            }
            acquireLock();
            if (multicast) s.joinGroup(group);
        } catch (IOException e) {
            closeQuietly();
            throw new DataSourceException(e, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED);
        }
        opened = true;
        payloadOffset = 0;
        payloadRemaining = 0;
        transferStarted(dataSpec);
        return C.LENGTH_UNSET;
    }

    @Override
    public int read(@NonNull byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) return 0;
        MulticastSocket s = socket;
        if (s == null) throw new IOException("组播数据源未打开");
        while (payloadRemaining == 0) {
            try {
                s.receive(packet);
            } catch (SocketTimeoutException e) {
                throw new DataSourceException(e, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT);
            } catch (IOException e) {
                throw new DataSourceException(e, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED);
            }
            extractPayload(packet.getLength());
        }
        int n = Math.min(length, payloadRemaining);
        System.arraycopy(packetBuffer, payloadOffset, buffer, offset, n);
        payloadOffset += n;
        payloadRemaining -= n;
        bytesTransferred(n);
        return n;
    }

    /** 232.0.0.0/8：IPv4 源地址特定组播（SSM）地址段 */
    private static boolean isSsmGroup(InetAddress addr) {
        byte[] b = addr.getAddress();
        return b.length == 4 && (b[0] & 0xFF) == 232;
    }

    /** 从包里找出 TS 负载（裸 TS 或 RTP 封装），结果写进 payloadOffset / payloadRemaining */
    private void extractPayload(int len) {
        payloadOffset = 0;
        payloadRemaining = 0;
        if (len <= 0) return;
        byte[] b = packetBuffer;
        int first = b[0] & 0xFF;
        if (first == 0x47) {
            payloadRemaining = len;
            return;
        }
        if ((first & 0xC0) != 0x80 || len < 12) {
            // 既不是 TS 也不是 RTP v2：原样交出，交给 TS 解封装层重新找同步字节
            payloadRemaining = len;
            return;
        }
        int csrcCount = first & 0x0F;
        boolean extension = (first & 0x10) != 0;
        boolean padding = (first & 0x20) != 0;
        int header = 12 + 4 * csrcCount;
        if (extension) {
            if (len < header + 4) return;
            int extWords = ((b[header + 2] & 0xFF) << 8) | (b[header + 3] & 0xFF);
            header += 4 + 4 * extWords;
        }
        int end = len;
        if (padding) end -= b[len - 1] & 0xFF;
        if (end <= header) return;
        payloadOffset = header;
        payloadRemaining = end - header;
    }

    @Nullable
    @Override
    public Uri getUri() {
        return uri;
    }

    @Override
    public void close() {
        uri = null;
        closeQuietly();
        payloadOffset = 0;
        payloadRemaining = 0;
        if (opened) {
            opened = false;
            transferEnded();
        }
    }

    private void closeQuietly() {
        MulticastSocket s = socket;
        socket = null;
        if (s != null) {
            try {
                if (group != null && group.isMulticastAddress()) s.leaveGroup(group);
            } catch (Exception ignore) {
            }
            try {
                s.close();
            } catch (Exception ignore) {
            }
        }
        group = null;
        releaseLock();
    }

    private void acquireLock() {
        try {
            WifiManager wm = (WifiManager) appContext.getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return;
            WifiManager.MulticastLock lock = wm.createMulticastLock("tvbox-live-multicast");
            lock.setReferenceCounted(false);
            lock.acquire();
            multicastLock = lock;
        } catch (Throwable t) {
            // 有线网络或缺权限时拿不到锁不影响收流
            Timber.d(t, "MulticastLock 获取失败");
        }
    }

    private void releaseLock() {
        WifiManager.MulticastLock lock = multicastLock;
        multicastLock = null;
        if (lock != null) {
            try {
                if (lock.isHeld()) lock.release();
            } catch (Throwable ignore) {
            }
        }
    }
}
