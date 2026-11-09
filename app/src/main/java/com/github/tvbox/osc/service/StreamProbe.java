package com.github.tvbox.osc.service;

import android.net.Uri;
import android.os.SystemClock;
import android.util.Base64;

import androidx.annotation.Nullable;

import com.github.tvbox.osc.App;
import com.github.tvbox.osc.bean.SpeedTestResult;
import com.github.tvbox.osc.player.MulticastDataSource;
import com.github.tvbox.osc.util.MulticastUrl;
import com.github.tvbox.osc.util.StreamUrl;
import com.google.android.exoplayer2.upstream.DataSpec;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.antmedia.rtmp_client.RtmpClient;
import timber.log.Timber;

/**
 * 非 HTTP 线路的可用性探测（1.3.1）：RTMP、组播直连（RTP / 裸 UDP）、RTSP。
 *
 * <p>以前这几类线路测速时直接记为"不可测速"，能不能播只有播放时超时换线才知道。
 * 现在用与播放相同的通道真正连一次：
 * <ul>
 *   <li><b>RTMP</b>：用播放器同一个 librtmp 客户端（extension-rtmp 依赖的 RtmpClient）建连并读一个窗口，
 *       得到可用性、码率；读到的 FLV 交给 MediaProbe 得到有无视频轨与分辨率。收发超时由 librtmp 的
 *       socket 超时约束（Linux 下发送超时同样约束 connect），不会把测速线程卡死；</li>
 *   <li><b>组播直连</b>：用播放用的 {@link MulticastDataSource} 加入组播收一个窗口的包。组播收不到包
 *       多半是本机不在 IPTV 专网，与线路好坏分不清，所以<b>只有成功才下结论</b>，其余一律"无法判断"；</li>
 *   <li><b>RTSP</b>：发 DESCRIBE（同一连接上完成 Basic / Digest 认证，跟随一次重定向），
 *       确认服务器在线、流存在、SDP 里有播放器支持的编码。拿不到速度，结果记为"可用、速度未知"。</li>
 * </ul>
 * 结论分三种：可用；明确不可用（连不上、超时、流不存在等，按普通失败处理）；
 * 无法判断（应答看不懂、认证方式不支持、编码播放器不支持、组播收不到等）——记为"不可测速"，不删除。
 */
public final class StreamProbe {

    private StreamProbe() {}

    /** 拉流探测的读取窗口（从建连完成算起，毫秒） */
    private static final long READ_WINDOW_MS = 2500;
    /** 窗口开头这段时间的数据不计入码率：服务器常先突发一个 GOP 缓存，会把码率算高 */
    private static final long BURST_SKIP_MS = 500;
    /** 计算码率至少要跨这么久的数据（不足按"流提前结束"处理） */
    private static final long MIN_DATA_SPAN_MS = 1000;
    /** 读够这么多字节就提前结束（高码率流）；样本另有上限 */
    private static final long READ_MAX_BYTES = 4L * 1024 * 1024;
    /** 至少读到这么多才算"在出数据"：排除只回了握手/元数据就没下文的情况（低码率电台 2.5 秒也有十几 KB） */
    private static final long MIN_OK_BYTES = 4 * 1024;
    /** MediaProbe 样本上限（与 HTTP 测速一致） */
    private static final int SAMPLE_MAX = 512 * 1024;
    /** 成功结论的复用时长（单频道测速等不经过调度器的调用方复用；调度器的实测阶段直接用验证结论） */
    private static final long CACHE_TTL_MS = 90_000L;
    /** 组播探测排队最多等多久：等不到就"无法判断"，不长期占着测速线程 */
    private static final long MULTICAST_QUEUE_WAIT_MS = 6000;

    /** 组播直连探测同一时间只做一条 */
    private static final Semaphore MULTICAST_PERMIT = new Semaphore(1, true);
    /**
     * 最近一次组播探测一个包都没收到的时刻（elapsedRealtime）。不在 IPTV 专网时所有组播都收不到，
     * 这段时间内其余组播线路直接"无法判断"，不再逐条排队把测速线程拖住
     */
    private static volatile long multicastSilentAt = -1L;
    private static final long MULTICAST_SILENT_TTL_MS = 60_000L;

    /** 结论里的失败分类（与 SpeedTestEngine 的 TIMEOUT / DNS / OTHER / HTTP_ERROR 口径一致） */
    static final String FAIL_TIMEOUT = "TIMEOUT";
    static final String FAIL_DNS = "DNS";
    static final String FAIL_OTHER = "OTHER";
    /** 服务器明确回答"流不存在 / 被拒绝"：不是网络故障（不计入断网判定、不重试） */
    static final String FAIL_STREAM = "HTTP_ERROR";

    /** 探测结论 */
    static final class Outcome {
        /** 可用 */
        boolean ok;
        /** 无法判断：按"不可测速"处理，不删除 */
        boolean inconclusive;
        String failureType;
        String errorMsg;
        long latencyMs = -1;
        /** 码率 KB/s；&lt;0 表示不知道（RTSP） */
        double speedKBps = -1;
        /** 分辨率（拉流探测由 MediaProbe 从样本解析；RTSP 取 SDP 里声明的），未知为 null */
        String resolution;
        int videoState = SpeedTestResult.VIDEO_UNKNOWN;
        /** 建连阶段失败的类型（TIMEOUT / DNS / REFUSED），供死主机熔断；连上了或不是建连失败为 null */
        String connectFailKind;
        /** 建连成功（供熔断记"主机可达"） */
        boolean connected;
        long time;
    }

    private static final Map<String, Outcome> CACHE = new LinkedHashMap<String, Outcome>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Outcome> eldest) {
            return size() > 256;
        }
    };

    /** 这条线路能用本类探测（RTSP / RTMP / 组播直连） */
    public static boolean canProbe(@Nullable String raw) {
        String play = StreamUrl.playUrl(raw);
        if (play == null) return false;
        String l = play.trim().toLowerCase(Locale.ROOT);
        if (l.startsWith("rtsp://")) return true;
        if (l.startsWith("rtmp://") || l.startsWith("rtmpt://")) return true;
        return isMulticastDirect(raw);
    }

    /** 组播直连线路（设置了 udpxy 时 playUrl 已改写成 http，走 HTTP 测速，不算） */
    static boolean isMulticastDirect(@Nullable String raw) {
        String play = StreamUrl.playUrl(raw);
        if (play == null) return false;
        String l = play.trim().toLowerCase(Locale.ROOT);
        return MulticastUrl.isMulticast(l) && MulticastUrl.directEnabled() && MulticastUrl.playable(l);
    }

    /**
     * 探测一条线路。
     *
     * @param raw         线路原串（可带 $标签 / |请求头）
     * @param deadlineMs  本条预算截止时刻（System.currentTimeMillis）
     * @param allowCached true＝可直接用 90 秒内的成功结论
     */
    static Outcome probe(String raw, long deadlineMs, boolean allowCached) {
        String key = StreamUrl.withoutMeta(raw);
        if (allowCached) {
            synchronized (CACHE) {
                Outcome c = CACHE.get(key);
                if (c != null && c.ok && SystemClock.elapsedRealtime() - c.time < CACHE_TTL_MS) return c;
            }
        }
        Outcome o;
        String play = StreamUrl.playUrl(raw);
        String l = play == null ? "" : play.trim().toLowerCase(Locale.ROOT);
        try {
            if (l.startsWith("rtsp://")) {
                o = probeRtsp(play, raw, deadlineMs);
            } else if (l.startsWith("rtmp")) {
                o = probeRtmp(play, deadlineMs);
            } else {
                o = probeMulticast(play, deadlineMs);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            o = inconclusive("探测被取消");
        } catch (Throwable t) {
            o = inconclusive("探测异常: " + msgOf(t));
        }
        o.time = SystemClock.elapsedRealtime();
        if (o.ok) {
            synchronized (CACHE) {
                CACHE.put(key, o);
            }
        }
        return o;
    }

    // ============ RTMP ============

    private static Outcome probeRtmp(String url, long deadlineMs) {
        int timeout = (int) clamp(deadlineMs - System.currentTimeMillis(), 1500, 5000);
        RtmpClient client = new RtmpClient();
        client.setSendTimeout(timeout);
        client.setReceiveTimeout(timeout);
        long t0 = SystemClock.elapsedRealtime();
        try {
            client.open(url, false);
        } catch (RtmpClient.RtmpIOException e) {
            client.close();
            return rtmpOpenFailure(e, SystemClock.elapsedRealtime() - t0, timeout);
        } catch (Throwable t) {
            client.close();
            return inconclusive("RTMP 连接异常: " + msgOf(t));
        }
        long opened = SystemClock.elapsedRealtime();
        try {
            // 读取窗口单独计时：建连本身可能用掉大半预算，不能因此把在推流的线路判成"数据太少"
            Outcome o = readStream((buf, off, len) -> client.read(buf, off, len),
                    opened, readDeadline(deadlineMs), "RTMP");
            o.latencyMs = opened - t0;
            o.connected = true;
            return o;
        } finally {
            client.close();
        }
    }

    private static Outcome rtmpOpenFailure(RtmpClient.RtmpIOException e, long elapsedMs, int timeout) {
        int code = e.errorCode;
        if (code == RtmpClient.RtmpIOException.DNS_NOT_REACHABLE) {
            Outcome o = fail(FAIL_DNS, "RTMP 域名解析失败");
            o.connectFailKind = "DNS";
            return o;
        }
        if (code == RtmpClient.RtmpIOException.SOCKET_CONNECT_FAIL) {
            boolean timedOut = elapsedMs >= timeout - 200;
            Outcome o = fail(timedOut ? FAIL_TIMEOUT : FAIL_OTHER, timedOut ? "RTMP 连接超时" : "RTMP 连接被拒绝");
            o.connectFailKind = timedOut ? "TIMEOUT" : "REFUSED";
            return o;
        }
        if (code == RtmpClient.RtmpIOException.OPEN_CONNECT_STREAM) {
            Outcome o = fail(FAIL_STREAM, "RTMP 流不存在");
            o.connected = true;
            return o;
        }
        if (elapsedMs >= timeout - 200) return fail(FAIL_TIMEOUT, "RTMP 连接超时");
        if (code == RtmpClient.RtmpIOException.HANDSHAKE_FAIL
                || code == RtmpClient.RtmpIOException.HANDSHAKE_CONNECT_FAIL
                || code == RtmpClient.RtmpIOException.RTMP_CONNECT_FAIL) {
            return fail(FAIL_OTHER, "RTMP 握手失败");
        }
        // 其它错误（选项不认识、TLS 不支持等）说不清是线路坏了还是本机不支持：不下结论
        return inconclusive("RTMP 错误 " + code);
    }

    // ============ 组播直连 ============

    private static Outcome probeMulticast(String url, long deadlineMs) throws InterruptedException {
        long silent = multicastSilentAt;
        if (silent >= 0 && SystemClock.elapsedRealtime() - silent < MULTICAST_SILENT_TTL_MS) {
            return inconclusive("组播收不到包（本机可能不在 IPTV 网络）");
        }
        if (!MULTICAST_PERMIT.tryAcquire(MULTICAST_QUEUE_WAIT_MS, TimeUnit.MILLISECONDS)) {
            return inconclusive("组播探测排队超时");
        }
        MulticastDataSource ds = new MulticastDataSource(App.getInstance());
        try {
            // 排到时预算可能已过：组播收包本身要一个窗口，给它重新算预算
            long myDeadline = readDeadline(Math.max(deadlineMs, System.currentTimeMillis()));
            ds.setReceiveTimeoutMs((int) clamp(myDeadline - System.currentTimeMillis(), 1000, 4000));
            long t0 = SystemClock.elapsedRealtime();
            try {
                ds.open(new DataSpec(Uri.parse(url)));
            } catch (IOException e) {
                multicastSilentAt = SystemClock.elapsedRealtime();
                return inconclusive("组播加入失败: " + msgOf(e));
            }
            long opened = SystemClock.elapsedRealtime();
            Outcome o = readStream(ds::read, opened, myDeadline, "组播");
            o.latencyMs = opened - t0;
            // 一个包都没收到：记下"组播不通"，60 秒内其余组播线路不再排队探测；收到过包则清除
            if (o.ok) multicastSilentAt = -1L;
            else if (FAIL_TIMEOUT.equals(o.failureType)) multicastSilentAt = SystemClock.elapsedRealtime();
            // 组播收不到包多半是本机不在 IPTV 专网（或 IGMP 被拦），与线路好坏分不清：不下"不可用"的结论
            if (!o.ok) o.inconclusive = true;
            return o;
        } finally {
            try {
                ds.close();
            } catch (Throwable ignore) {
            }
            MULTICAST_PERMIT.release();
        }
    }

    // ============ 拉流读取（RTMP / 组播共用） ============

    private interface Reader {
        int read(byte[] buf, int off, int len) throws IOException;
    }

    /** 读取窗口的截止时刻：至少给满一个窗口，但不超过本条预算 + 4 秒（不让测速池被拖住） */
    private static long readDeadline(long deadlineMs) {
        long now = System.currentTimeMillis();
        return Math.min(Math.max(deadlineMs, now + READ_WINDOW_MS + 1500), Math.max(deadlineMs, now) + 4000);
    }

    /**
     * 从建连完成（openedAt，elapsedRealtime）起读 READ_WINDOW_MS，算码率并探测画面。
     * <ul>
     *   <li>读满窗口（或字节上限）：成功，码率不计窗口开头 BURST_SKIP_MS 的突发；</li>
     *   <li>窗口没读满就结束（服务器断开、读超时）：数据跨度不足 MIN_DATA_SPAN_MS 的判为"流提前结束"；</li>
     *   <li>一个字节都没有：超时。</li>
     * </ul>
     */
    private static Outcome readStream(Reader reader, long openedAt, long deadlineMs, String what) {
        byte[] buf = new byte[64 * 1024];
        ByteArrayOutputStream sample = new ByteArrayOutputStream(128 * 1024);
        long total = 0;
        long lateBytes = 0;
        long firstDataAt = -1;
        long lastDataAt = -1;
        boolean eof = false;
        boolean windowDone = false;
        String err = null;
        while (true) {
            long now = SystemClock.elapsedRealtime();
            if (now - openedAt >= READ_WINDOW_MS || total >= READ_MAX_BYTES) {
                windowDone = true;
                break;
            }
            if (System.currentTimeMillis() >= deadlineMs) break;
            int n;
            try {
                n = reader.read(buf, 0, buf.length);
            } catch (SocketTimeoutException e) {
                break;
            } catch (IOException e) {
                if (!(e.getCause() instanceof SocketTimeoutException)) err = msgOf(e);
                break;
            } catch (Throwable t) {
                err = msgOf(t);
                break;
            }
            if (n < 0) {
                eof = true;
                break;
            }
            if (n == 0) continue;
            long t = SystemClock.elapsedRealtime();
            if (firstDataAt < 0) firstDataAt = t;
            lastDataAt = t;
            total += n;
            if (t - openedAt >= BURST_SKIP_MS) lateBytes += n;
            if (sample.size() < SAMPLE_MAX) sample.write(buf, 0, Math.min(n, SAMPLE_MAX - sample.size()));
        }
        if (total == 0) {
            if (err != null) return fail(FAIL_OTHER, what + " 读取失败: " + err);
            return fail(FAIL_TIMEOUT, what + (eof ? " 连上后立即断开" : " 连上但没有数据"));
        }
        long dataSpan = lastDataAt - firstDataAt;
        if (!windowDone && dataSpan < MIN_DATA_SPAN_MS) {
            // 只吐了一点缓存就断开 / 卡住：推流已停或鉴权在 play 之后才拒绝
            if (err != null) return fail(FAIL_OTHER, what + " 读取失败: " + err);
            return fail(eof ? FAIL_OTHER : FAIL_TIMEOUT, what + " 流提前结束");
        }
        if (total < MIN_OK_BYTES) {
            return fail(FAIL_OTHER, what + " 数据太少（" + total + " 字节）");
        }
        Outcome o = new Outcome();
        o.ok = true;
        // 码率：优先用跳过开头突发后的数据；跨度不够时用全部数据、按建连起算（宁可算低）
        long lateSpan = lastDataAt - (openedAt + BURST_SKIP_MS);
        if (lateBytes > 0 && lateSpan >= MIN_DATA_SPAN_MS) {
            o.speedKBps = lateBytes / 1024.0 / (lateSpan / 1000.0);
        } else {
            long span = Math.max(MIN_DATA_SPAN_MS, lastDataAt - openedAt);
            o.speedKBps = total / 1024.0 / (span / 1000.0);
        }
        // 当场探测画面信息、不保留样本（成功结论会缓存一段时间，带着 512KB 样本太占内存）
        try {
            byte[] data = sample.toByteArray();
            com.github.tvbox.osc.util.MediaProbe.Result p =
                    com.github.tvbox.osc.util.MediaProbe.probe(data, data.length);
            if (p.hasSize()) o.resolution = p.width + "x" + p.height;
            o.videoState = p.videoState;
        } catch (Throwable ignore) {
        }
        return o;
    }

    // ============ RTSP ============

    private static final Pattern X_DIMENSIONS = Pattern.compile("a=x-dimensions:\\s*(\\d+)\\s*,\\s*(\\d+)");
    private static final Pattern FRAMESIZE = Pattern.compile("a=framesize:\\s*\\d+\\s+(\\d+)-(\\d+)");
    private static final Pattern RTPMAP = Pattern.compile("^a=rtpmap:(\\d+)\\s+([^/\\s]+)", Pattern.CASE_INSENSITIVE);

    /** ExoPlayer 2.19 RTSP 能播的 RTP 负载编码（RtspMediaTrack） */
    private static final Set<String> SUPPORTED_ENCODINGS = new HashSet<>(Arrays.asList(
            "H264", "H265", "MP4V-ES", "H263-1998", "H263-2000", "VP8", "VP9",
            "MPEG4-GENERIC", "MP4A-LATM", "AC3", "AMR", "AMR-WB", "OPUS", "L8", "L16", "PCMA", "PCMU"));

    /** RFC 3551 静态负载类型（没有 rtpmap 时用） */
    private static String staticEncoding(int pt) {
        switch (pt) {
            case 0: return "PCMU";
            case 8: return "PCMA";
            case 14: return "MPA";
            case 26: return "JPEG";
            case 32: return "MPV";
            case 33: return "MP2T";
            default: return null;
        }
    }

    private static Outcome probeRtsp(String url, String raw, long deadlineMs) {
        String ua = StreamUrl.userAgent(raw);
        if (ua == null || ua.isEmpty()) ua = StreamUrl.DEFAULT_USER_AGENT;
        String target = url;
        long t0 = SystemClock.elapsedRealtime();
        RtspResponse r = null;
        // 最多跟随一次重定向
        for (int hop = 0; hop < 2; hop++) {
            // 重定向之后连不上的是边缘节点，不是线路地址里的主机：原主机已应答过，不能把失败记到它头上
            // （否则一个死边缘会让整个网关被熔断，网关下其它正常线路一起被判死）
            final boolean redirected = hop > 0;
            try {
                r = describeSession(target, ua, deadlineMs);
            } catch (UnknownHostException e) {
                Outcome o = fail(FAIL_DNS, "RTSP 域名解析失败");
                if (redirected) o.connected = true; else o.connectFailKind = "DNS";
                return o;
            } catch (ConnectTimeout e) {
                Outcome o = fail(FAIL_TIMEOUT, "RTSP 连接超时");
                if (redirected) o.connected = true; else o.connectFailKind = "TIMEOUT";
                return o;
            } catch (ConnectException | NoRouteToHostException e) {
                Outcome o = fail(FAIL_OTHER, "RTSP 连接被拒绝");
                if (redirected) o.connected = true; else o.connectFailKind = "REFUSED";
                return o;
            } catch (SocketTimeoutException e) {
                Outcome o = fail(FAIL_TIMEOUT, "RTSP 应答超时");
                o.connected = true;
                return o;
            } catch (IOException e) {
                return inconclusive("RTSP 请求失败: " + msgOf(e));
            }
            if (r == null) return inconclusive("RTSP 应答无法识别");
            if ((r.status == 301 || r.status == 302 || r.status == 303 || r.status == 307)
                    && r.location != null && hop == 0) {
                target = r.location;
                continue;
            }
            break;
        }
        Outcome o;
        if (r.status == 200) {
            o = rtspSdpOutcome(r.body);
            if (o.ok) o.latencyMs = SystemClock.elapsedRealtime() - t0;
        } else if (r.status == 401) {
            // 没给账号、认证方式不支持、或带了认证仍被拒（本探测的认证实现不一定覆盖服务器的全部要求）：不下结论
            o = inconclusive(r.authTried ? "RTSP 认证未通过" : "RTSP 需要认证");
        } else if (r.status == 404 || r.status == 454 || r.status == 410 || r.status == 403) {
            // 服务器明确说流不存在 / 禁止访问：线路不可用，但不是网络故障
            o = fail(FAIL_STREAM, "RTSP " + r.status);
        } else {
            // 405/501 等：服务器在线但不接受这种 DESCRIBE 写法，判断不了流是否可播
            o = inconclusive("RTSP " + r.status);
        }
        o.connected = true;
        return o;
    }

    /** 按 SDP 判断播放器能不能播：至少一个媒体段的编码在支持列表里 */
    private static Outcome rtspSdpOutcome(@Nullable String sdp) {
        if (sdp == null || !sdp.contains("m=")) {
            // 没有 SDP 正文：服务器在线、流存在，编码未知
            Outcome o = new Outcome();
            o.ok = true;
            return o;
        }
        boolean supportedVideo = false, supportedAudio = false, anySection = false;
        List<String> unsupported = new ArrayList<>();
        String[] lines = sdp.split("\r?\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (!line.startsWith("m=")) continue;
            anySection = true;
            String[] f = line.substring(2).trim().split("\\s+");
            String media = f.length > 0 ? f[0].toLowerCase(Locale.ROOT) : "";
            Map<Integer, String> rtpmap = new LinkedHashMap<>();
            for (int j = i + 1; j < lines.length && !lines[j].trim().startsWith("m="); j++) {
                Matcher m = RTPMAP.matcher(lines[j].trim());
                if (m.find()) {
                    try {
                        rtpmap.put(Integer.parseInt(m.group(1)), m.group(2).toUpperCase(Locale.ROOT));
                    } catch (NumberFormatException ignore) {
                    }
                }
            }
            boolean ok = false;
            for (int k = 3; k < f.length; k++) {
                int pt;
                try {
                    pt = Integer.parseInt(f[k]);
                } catch (NumberFormatException e) {
                    continue;
                }
                String enc = rtpmap.containsKey(pt) ? rtpmap.get(pt) : staticEncoding(pt);
                if (enc != null && SUPPORTED_ENCODINGS.contains(enc)) {
                    ok = true;
                } else if (enc != null) {
                    unsupported.add(enc);
                }
            }
            if (ok && media.equals("video")) supportedVideo = true;
            if (ok && media.equals("audio")) supportedAudio = true;
        }
        if (anySection && !supportedVideo && !supportedAudio) {
            // 例如运营商 RTSP 的 MP2T over RTP：ExoPlayer 的 RTSP 播不了，但换个播放器也许能播，不下结论
            return inconclusive("RTSP 编码播放器不支持" + (unsupported.isEmpty() ? "" : "（" + unsupported.get(0) + "）"));
        }
        Outcome o = new Outcome();
        o.ok = true;
        if (!supportedVideo && supportedAudio) o.videoState = SpeedTestResult.VIDEO_MISSING;
        Matcher m = X_DIMENSIONS.matcher(sdp);
        if (m.find()) {
            o.resolution = m.group(1) + "x" + m.group(2);
        } else {
            m = FRAMESIZE.matcher(sdp);
            if (m.find()) o.resolution = m.group(1) + "x" + m.group(2);
        }
        return o;
    }

    /** 建连阶段超时（区别于连上之后的应答超时） */
    private static final class ConnectTimeout extends IOException {
        ConnectTimeout(String msg) {
            super(msg);
        }
    }

    private static final class RtspResponse {
        int status;
        String location;
        String authenticate;
        String body;
        /** 这次应答是在带了 Authorization 之后得到的 */
        boolean authTried;
    }

    /**
     * 一次 DESCRIBE 会话：同一个 TCP 连接上先发不带认证的 DESCRIBE，401 时按 WWW-Authenticate 带上认证再发一次
     * （Digest 的 nonce 常与连接绑定，换连接重发会被拒）。服务器在 401 后关闭连接时，才换新连接重发。
     * 应答不是 RTSP 返回 null。
     */
    @Nullable
    private static RtspResponse describeSession(String url, String ua, long deadlineMs) throws IOException {
        Uri u = Uri.parse(url);
        String host = u.getHost();
        if (host == null || host.isEmpty()) throw new IOException("RTSP 地址缺少主机");
        int port = u.getPort() > 0 ? u.getPort() : 554;
        String requestUri = stripUserInfo(url);
        String userInfo = u.getUserInfo(); // 已解码一次（与 ExoPlayer 一致）
        Socket s = connect(host, port, deadlineMs);
        try {
            RtspResponse r = sendDescribe(s, requestUri, ua, null, 1);
            if (r == null || r.status != 401) return r;
            if (userInfo == null || userInfo.isEmpty() || r.authenticate == null) return r;
            String auth = buildAuth(r.authenticate, userInfo, requestUri);
            if (auth == null) return r; // 认证方式不支持
            RtspResponse r2;
            try {
                r2 = sendDescribe(s, requestUri, ua, auth, 2);
            } catch (IOException closed) {
                r2 = null;
            }
            if (r2 == null) {
                // 服务器在 401 后断开了连接：换新连接带认证重发。重连/重发失败（限连接数、限频等）
                // 不能算"主机连不上"——服务器刚应答过：退回第一次的 401（无法判断）
                if (deadlineMs - System.currentTimeMillis() < 1000) return r;
                closeQuietly(s);
                s = null;
                try {
                    s = connect(host, port, deadlineMs);
                    r2 = sendDescribe(s, requestUri, ua, auth, 1);
                } catch (IOException e) {
                    return r;
                }
                if (r2 == null) return r;
            }
            r2.authTried = true;
            return r2;
        } finally {
            closeQuietly(s);
        }
    }

    private static Socket connect(String host, int port, long deadlineMs) throws IOException {
        int timeout = (int) clamp(deadlineMs - System.currentTimeMillis(), 1000, 5000);
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), timeout);
        } catch (SocketTimeoutException e) {
            closeQuietly(s);
            throw new ConnectTimeout("connect timed out");
        } catch (IOException e) {
            closeQuietly(s);
            throw e;
        }
        s.setSoTimeout(timeout);
        return s;
    }

    @Nullable
    private static RtspResponse sendDescribe(Socket s, String requestUri, String ua, @Nullable String auth,
                                             int cseq) throws IOException {
        StringBuilder req = new StringBuilder();
        req.append("DESCRIBE ").append(requestUri).append(" RTSP/1.0\r\n")
                .append("CSeq: ").append(cseq).append("\r\n")
                .append("Accept: application/sdp\r\n")
                .append("User-Agent: ").append(ua).append("\r\n");
        if (auth != null) req.append("Authorization: ").append(auth).append("\r\n");
        req.append("\r\n");
        OutputStream out = s.getOutputStream();
        out.write(req.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();
        InputStream in = s.getInputStream();
        String status = readLine(in);
        if (status == null || !status.startsWith("RTSP/")) return null;
        String[] parts = status.split(" ", 3);
        if (parts.length < 2) return null;
        RtspResponse r = new RtspResponse();
        try {
            r.status = Integer.parseInt(parts[1].trim());
        } catch (NumberFormatException e) {
            return null;
        }
        int contentLength = 0;
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            int c = line.indexOf(':');
            if (c <= 0) continue;
            String name = line.substring(0, c).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(c + 1).trim();
            if (name.equals("content-length")) {
                try {
                    contentLength = Integer.parseInt(value);
                } catch (NumberFormatException ignore) {
                }
            } else if (name.equals("location")) {
                r.location = value;
            } else if (name.equals("www-authenticate")) {
                // 有多个时优先 Digest
                if (r.authenticate == null || value.regionMatches(true, 0, "Digest", 0, 6)) {
                    r.authenticate = value;
                }
            }
        }
        if (contentLength > 0) {
            // 正文必须读完（同一连接上还要发下一个请求）；超过 64KB 的部分读掉丢弃
            byte[] body = new byte[Math.min(contentLength, 64 * 1024)];
            int got = 0;
            int remaining = contentLength;
            byte[] skip = new byte[4096];
            while (remaining > 0) {
                int n = got < body.length ? in.read(body, got, Math.min(body.length - got, remaining))
                        : in.read(skip, 0, Math.min(skip.length, remaining));
                if (n < 0) break;
                if (got < body.length) got += n;
                remaining -= n;
            }
            r.body = new String(body, 0, got, StandardCharsets.UTF_8);
        }
        return r;
    }

    /** 读一行（CRLF 或 LF 结尾），流结束返回 null；单行上限 8KB */
    @Nullable
    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(128);
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 8192) break;
        }
        if (c < 0 && b.size() == 0) return null;
        return b.toString("UTF-8");
    }

    private static String stripUserInfo(String url) {
        Uri u = Uri.parse(url);
        String info = u.getEncodedUserInfo();
        if (info == null || info.isEmpty()) return url;
        return url.replaceFirst(Pattern.quote(info) + "@", "");
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * 按 WWW-Authenticate 生成 Authorization（Basic；Digest MD5，含 qop=auth 与 opaque），不支持返回 null。
     *
     * @param userInfo 已解码的 user:pass（与 ExoPlayer 一样按第一个 ':' 切分）
     */
    @Nullable
    private static String buildAuth(String challenge, String userInfo, String uri) {
        int colon = userInfo.indexOf(':');
        String user = colon >= 0 ? userInfo.substring(0, colon) : userInfo;
        String pass = colon >= 0 ? userInfo.substring(colon + 1) : "";
        if (challenge.regionMatches(true, 0, "Basic", 0, 5)) {
            return "Basic " + Base64.encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8),
                    Base64.NO_WRAP);
        }
        if (!challenge.regionMatches(true, 0, "Digest", 0, 6)) return null;
        String realm = param(challenge, "realm");
        String nonce = param(challenge, "nonce");
        if (realm == null || nonce == null) return null;
        String algorithm = param(challenge, "algorithm");
        if (algorithm != null && !algorithm.equalsIgnoreCase("MD5")) return null;
        String opaque = param(challenge, "opaque");
        String qop = param(challenge, "qop");
        String ha1 = md5(user + ":" + realm + ":" + pass);
        String ha2 = md5("DESCRIBE:" + uri);
        StringBuilder h = new StringBuilder("Digest username=\"").append(user)
                .append("\", realm=\"").append(realm)
                .append("\", nonce=\"").append(nonce)
                .append("\", uri=\"").append(uri).append('"');
        if (qop != null) {
            boolean auth = false;
            for (String q : qop.split(",")) {
                if (q.trim().equalsIgnoreCase("auth")) auth = true;
            }
            if (!auth) return null; // 只有 auth-int：不支持
            String nc = "00000001";
            String cnonce = Long.toHexString(RANDOM.nextLong());
            String response = md5(ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":auth:" + ha2);
            h.append(", qop=auth, nc=").append(nc).append(", cnonce=\"").append(cnonce)
                    .append("\", response=\"").append(response).append('"');
        } else {
            h.append(", response=\"").append(md5(ha1 + ":" + nonce + ":" + ha2)).append('"');
        }
        if (opaque != null) h.append(", opaque=\"").append(opaque).append('"');
        return h.toString();
    }

    /** 认证头里的参数，带引号与不带引号的写法都认（如 algorithm=MD5） */
    @Nullable
    private static String param(String header, String name) {
        Matcher m = Pattern.compile("(?:^|[\\s,])" + name + "\\s*=\\s*(?:\"([^\"]*)\"|([^\\s,]+))",
                Pattern.CASE_INSENSITIVE).matcher(header);
        if (!m.find()) return null;
        return m.group(1) != null ? m.group(1) : m.group(2);
    }

    private static String md5(String s) {
        try {
            byte[] d = MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : d) sb.append(String.format(Locale.ROOT, "%02x", b & 0xFF));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    // ============ 工具 ============

    private static Outcome fail(String type, String msg) {
        Outcome o = new Outcome();
        o.ok = false;
        o.failureType = type;
        o.errorMsg = msg;
        Timber.d("流探测失败: %s (%s)", msg, type);
        return o;
    }

    private static Outcome inconclusive(String msg) {
        Outcome o = fail(FAIL_OTHER, msg);
        o.inconclusive = true;
        return o;
    }

    private static String msgOf(Throwable t) {
        String m = t == null ? null : t.getMessage();
        return m != null && !m.isEmpty() ? m : (t == null ? "未知错误" : t.getClass().getSimpleName());
    }

    private static void closeQuietly(@Nullable Socket s) {
        if (s == null) return;
        try {
            s.close();
        } catch (Throwable ignore) {
        }
    }

    private static long clamp(long v, long min, long max) {
        return Math.max(min, Math.min(max, v));
    }
}
