package com.github.tvbox.osc.util;

/**
 * 从收到的媒体字节里增量解析时间戳，算出"收到了多少秒的内容"（1.3.2）。
 *
 * <p>测速用它把各种线路统一成<b>实时倍率</b> = 收到的内容时长 ÷ 花掉的时间：
 * HLS 分片按带宽下载，倍率常在 3–10×；直播直链 / RTMP / 组播按实时推送，健康的约 1×，
 * 开头有 GOP 缓存突发的会更高；低于 1× 就是跟不上、会卡。不同线路之间可以直接比较，
 * 不再拿"下载带宽"和"码率"两种不同的 KB/s 混在一起排序。
 *
 * <p>支持：
 * <ul>
 *   <li>MPEG-TS（188 字节包）：PES 头里的 PTS（90kHz，33 位回绕处理），优先视频轨，没有视频时用音频轨；</li>
 *   <li>FLV（HTTP-FLV 与 librtmp 输出的 FLV）：tag 时间戳（毫秒），同样优先视频。</li>
 * </ul>
 * 其它格式（fMP4、纯 ADTS 等）返回未知。只保留常数大小的状态，可以喂任意长的流；任何异常都吞掉。
 */
public final class MediaClock {

    private static final int MODE_UNKNOWN = 0;
    private static final int MODE_TS = 1;
    private static final int MODE_FLV = 2;
    private static final int MODE_NONE = 3;

    /** 格式判断最多看这么多字节 */
    private static final int DETECT_MAX = 4096;

    private int mode = MODE_UNKNOWN;
    private final byte[] detect = new byte[DETECT_MAX];
    private int detectLen;

    // ---- TS ----
    private final byte[] pkt = new byte[188];
    private int pktLen;
    private int videoPid = -1;
    private int audioPid = -1;
    private final Track tsVideo = new Track(90_000.0, 1L << 33);
    private final Track tsAudio = new Track(90_000.0, 1L << 33);

    // ---- FLV ----
    /** 文件头剩余要跳过的字节（含 PreviousTagSize0） */
    private long flvSkip = -1;
    private final byte[] tagHdr = new byte[11];
    private int tagHdrLen;
    private final byte[] flvHead = new byte[9];
    private int flvHeadLen;
    private final Track flvVideo = new Track(1000.0, 0);
    private final Track flvAudio = new Track(1000.0, 0);

    /**
     * 一条轨道的内容进度（处理回绕与跳变）。
     *
     * <p>不用 max−min：一个离群的时间戳（FLV 序列头 ts=0 后面跟着绝对时间、流中途切换节目）
     * 会让"内容时长"变成几万秒。改为只累计向前的、合理的步进：
     * 比已见最大值大、且不超过 {@link #MAX_STEP_SECONDS} 的才算；回退（B 帧重排、重复）不算，
     * 大跳变当作不连续点，只重新定基准。最后补上一个平均帧间隔：N 帧只有 N−1 个间隔，
     * 不补的话正好 1× 的流会被算成略低于 1×。
     */
    private static final class Track {
        /** 两个相邻时间戳之间最多这么多秒算正常步进 */
        static final double MAX_STEP_SECONDS = 2.0;

        final double ticksPerSecond;
        final long wrap;
        final long maxStep;
        long last = -1;
        long offset;
        long runMax = Long.MIN_VALUE;
        long progress;
        int steps;

        Track(double ticksPerSecond, long wrap) {
            this.ticksPerSecond = ticksPerSecond;
            this.wrap = wrap;
            this.maxStep = (long) (MAX_STEP_SECONDS * ticksPerSecond);
        }

        void add(long ts) {
            if (wrap > 0 && last >= 0 && ts + offset < last - wrap / 2) offset += wrap; // 回绕
            long v = ts + offset;
            last = v;
            if (runMax == Long.MIN_VALUE) {
                runMax = v;
                return;
            }
            long d = v - runMax;
            if (d < -maxStep) {          // 大幅回跳（时间戳重置、拼接点）：重新定基准，之后照常累计
                runMax = v;
                return;
            }
            if (d <= 0) return;          // 小幅回退（B 帧重排）或重复
            if (d <= maxStep) {
                progress += d;
                steps++;
            }                            // 否则：不连续点，只重新定基准
            runMax = v;
        }

        double span() {
            if (steps < 1 || progress <= 0) return 0;
            double s = progress / ticksPerSecond;
            return s + s / steps;
        }

        boolean seen() {
            return runMax != Long.MIN_VALUE;
        }
    }

    /** 是否已经解析到至少一个时间戳（从这时起才开始给实时倍率计时） */
    public boolean hasTimestamp() {
        if (mode == MODE_TS) return tsVideo.seen() || tsAudio.seen();
        if (mode == MODE_FLV) return flvVideo.seen() || flvAudio.seen();
        return false;
    }

    /** 喂一段数据 */
    public void feed(byte[] b, int off, int len) {
        try {
            if (len <= 0 || mode == MODE_NONE) return;
            if (mode == MODE_UNKNOWN) {
                int n = Math.min(len, DETECT_MAX - detectLen);
                System.arraycopy(b, off, detect, detectLen, n);
                detectLen += n;
                detectMode();
                if (mode == MODE_UNKNOWN) {
                    if (detectLen >= DETECT_MAX) mode = MODE_NONE;
                    return;
                }
                // 判断出格式：先处理已缓存的数据，再处理本次剩下没放进缓存的部分
                byte[] head = detect;
                int headLen = detectLen;
                detectLen = 0;
                dispatch(head, 0, headLen);
                if (n < len) dispatch(b, off + n, len - n);
                return;
            }
            dispatch(b, off, len);
        } catch (Throwable ignore) {
            mode = MODE_NONE;
        }
    }

    /** 收到的内容时长（秒）；判断不出返回 0 */
    public double spanSeconds() {
        if (mode == MODE_TS) {
            double v = tsVideo.span();
            return v > 0 ? v : tsAudio.span();
        }
        if (mode == MODE_FLV) {
            double v = flvVideo.span();
            return v > 0 ? v : flvAudio.span();
        }
        return 0;
    }

    private void detectMode() {
        if (detectLen >= 3 && detect[0] == 'F' && detect[1] == 'L' && detect[2] == 'V') {
            mode = MODE_FLV;
            return;
        }
        // TS：找一个 0x47，且 188 字节后还是 0x47（再后一个也是，更可靠）
        for (int i = 0; i + 376 < detectLen && i < 188; i++) {
            if (detect[i] == 0x47 && detect[i + 188] == 0x47 && detect[i + 376] == 0x47) {
                mode = MODE_TS;
                // 丢掉同步字节之前的垃圾
                if (i > 0) {
                    System.arraycopy(detect, i, detect, 0, detectLen - i);
                    detectLen -= i;
                }
                return;
            }
        }
    }

    private void dispatch(byte[] b, int off, int len) {
        if (mode == MODE_TS) feedTs(b, off, len);
        else if (mode == MODE_FLV) feedFlv(b, off, len);
    }

    // ============ TS ============

    private void feedTs(byte[] b, int off, int len) {
        int end = off + len;
        int i = off;
        while (i < end) {
            if (pktLen == 0) {
                // 包起点必须是同步字节；丢失同步时逐字节找回
                if (b[i] != 0x47) {
                    i++;
                    continue;
                }
            }
            int n = Math.min(188 - pktLen, end - i);
            System.arraycopy(b, i, pkt, pktLen, n);
            pktLen += n;
            i += n;
            if (pktLen == 188) {
                parseTsPacket(pkt);
                pktLen = 0;
            }
        }
    }

    private void parseTsPacket(byte[] p) {
        boolean pusi = (p[1] & 0x40) != 0;
        if (!pusi) return;
        int pid = ((p[1] & 0x1F) << 8) | (p[2] & 0xFF);
        int afc = (p[3] >> 4) & 0x03;
        if (afc == 0 || afc == 2) return; // 没有负载
        int pos = 4;
        if (afc == 3) pos += 1 + (p[4] & 0xFF);
        if (pos + 14 > 188) return;
        // PES 起始码
        if (p[pos] != 0 || p[pos + 1] != 0 || p[pos + 2] != 1) return;
        int streamId = p[pos + 3] & 0xFF;
        boolean video = streamId >= 0xE0 && streamId <= 0xEF;
        boolean audio = (streamId >= 0xC0 && streamId <= 0xDF) || streamId == 0xBD;
        if (!video && !audio) return;
        int ptsDtsFlags = (p[pos + 7] >> 6) & 0x03;
        if ((ptsDtsFlags & 0x02) == 0) return;
        int q = pos + 9;
        long pts = ((long) ((p[q] >> 1) & 0x07) << 30)
                | ((long) (p[q + 1] & 0xFF) << 22)
                | ((long) ((p[q + 2] >> 1) & 0x7F) << 15)
                | ((long) (p[q + 3] & 0xFF) << 7)
                | ((long) ((p[q + 4] >> 1) & 0x7F));
        // 有 DTS 时用 DTS：PTS 在有 B 帧的视频里是重排过的，用它累计会把内容时长算多；DTS 按解码顺序单调递增
        long ts = pts;
        if (ptsDtsFlags == 3 && (p[pos + 8] & 0xFF) >= 10 && q + 9 < 188) {
            int r = q + 5;
            ts = ((long) ((p[r] >> 1) & 0x07) << 30)
                    | ((long) (p[r + 1] & 0xFF) << 22)
                    | ((long) ((p[r + 2] >> 1) & 0x7F) << 15)
                    | ((long) (p[r + 3] & 0xFF) << 7)
                    | ((long) ((p[r + 4] >> 1) & 0x7F));
        }
        // 每种只跟一个 PID（多音轨时只看第一条）
        if (video) {
            if (videoPid < 0) videoPid = pid;
            if (pid == videoPid) tsVideo.add(ts);
        } else {
            if (audioPid < 0) audioPid = pid;
            if (pid == audioPid) tsAudio.add(ts);
        }
    }

    // ============ FLV ============

    private void feedFlv(byte[] b, int off, int len) {
        int end = off + len;
        int i = off;
        // 文件头：9 字节，其中第 5–8 字节是头长度；之后是 4 字节 PreviousTagSize0
        while (flvSkip < 0 && i < end) {
            flvHead[flvHeadLen++] = b[i++];
            if (flvHeadLen == 9) {
                long headerSize = ((flvHead[5] & 0xFFL) << 24) | ((flvHead[6] & 0xFFL) << 16)
                        | ((flvHead[7] & 0xFFL) << 8) | (flvHead[8] & 0xFFL);
                flvSkip = Math.max(0, headerSize - 9) + 4;
            }
        }
        while (i < end) {
            if (flvSkip > 0) {
                long n = Math.min(flvSkip, end - i);
                flvSkip -= n;
                i += (int) n;
                continue;
            }
            int n = Math.min(11 - tagHdrLen, end - i);
            System.arraycopy(b, i, tagHdr, tagHdrLen, n);
            tagHdrLen += n;
            i += n;
            if (tagHdrLen < 11) break;
            tagHdrLen = 0;
            int type = tagHdr[0] & 0x1F;
            long dataSize = ((tagHdr[1] & 0xFFL) << 16) | ((tagHdr[2] & 0xFFL) << 8) | (tagHdr[3] & 0xFFL);
            long ts = ((tagHdr[7] & 0xFFL) << 24) | ((tagHdr[4] & 0xFFL) << 16)
                    | ((tagHdr[5] & 0xFFL) << 8) | (tagHdr[6] & 0xFFL);
            if (type == 9) flvVideo.add(ts);
            else if (type == 8) flvAudio.add(ts);
            else if (type != 18) {
                // 不认识的 tag 类型：多半是解析错位，放弃（返回未知，不给出错误的时长）
                mode = MODE_NONE;
                return;
            }
            flvSkip = dataSize + 4; // 数据 + PreviousTagSize
        }
    }
}
