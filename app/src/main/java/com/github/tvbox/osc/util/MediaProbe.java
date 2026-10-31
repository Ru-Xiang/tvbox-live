package com.github.tvbox.osc.util;

import com.github.tvbox.osc.bean.SpeedTestResult;
import com.google.android.exoplayer2.util.NalUnitUtil;

/**
 * 从媒体流开头的一段字节里探测画面信息：有没有视频轨、视频分辨率。
 *
 * <p>测速时顺带抓取的样本（HLS 首个分片 / 直链开头最多 512 KB）交给这里解析，用于剔除
 * "只有声音没有画面"的黑屏线路和低分辨率线路。支持：
 * <ul>
 *   <li>MPEG-TS（188 字节包，兼容 192 字节的 M2TS）：PAT → PMT 判断有无视频轨，
 *       再从 H.264/HEVC 视频 PES 里找 SPS 解析分辨率</li>
 *   <li>FLV：遍历 tag，从 AVC/HEVC 序列头（含 Enhanced RTMP 扩展头）解析 SPS，
 *       没有序列头时退回 onMetaData 里的 width/height</li>
 *   <li>纯 ADTS（AAC）音频流（可带 ID3 头，常见于纯音频 HLS 分片）：判为没有视频</li>
 * </ul>
 * 其它格式（fMP4 等）一律返回未知。纯 Java 实现，任何异常都吞掉，不会抛出。
 */
public final class MediaProbe {

    private MediaProbe() {}

    /** 探测结果；宽高未知时为 0 */
    public static final class Result {
        public int width;
        public int height;
        public int videoState = SpeedTestResult.VIDEO_UNKNOWN;

        public boolean hasSize() {
            return width > 0 && height > 0;
        }
    }

    // TS 流类型：视频
    private static final int ST_MPEG1_VIDEO = 0x01;
    private static final int ST_MPEG2_VIDEO = 0x02;
    private static final int ST_MPEG4_VIDEO = 0x10;
    private static final int ST_H264 = 0x1B;
    private static final int ST_HEVC = 0x24;

    /** PMT 里最多记录的视频 PID 数（只解析第一个 H.264/HEVC 的 SPS） */
    private static final int MAX_PMT_PIDS = 8;

    public static Result probe(byte[] data, int len) {
        Result res = new Result();
        try {
            if (data == null || len <= 0) return res;
            len = Math.min(len, data.length);
            if (len >= 9 && data[0] == 'F' && data[1] == 'L' && data[2] == 'V') {
                probeFlv(data, len, res);
                return res;
            }
            int stride = 188;
            int start = findTsSync(data, len, 188, 0);
            if (start < 0) {
                // M2TS：每包前多 4 字节时间戳，同步字节在包内偏移 4
                stride = 192;
                start = findTsSync(data, len, 192, 4);
            }
            if (start >= 0) {
                probeTs(data, len, start, stride, res);
                return res;
            }
            if (looksLikeAdts(data, len)) {
                res.videoState = SpeedTestResult.VIDEO_MISSING;
            }
        } catch (Throwable ignore) {
            res.width = 0;
            res.height = 0;
            res.videoState = SpeedTestResult.VIDEO_UNKNOWN;
        }
        return res;
    }

    // ============ MPEG-TS ============

    /**
     * 找 TS 同步位置：连续 3 个包（样本不足时 2 个）的同步字节 0x47 对齐才认。
     * 返回第一个包的同步字节下标；syncOffset 为同步字节在包内的偏移（M2TS 为 4）。
     */
    private static int findTsSync(byte[] d, int len, int stride, int syncOffset) {
        int maxStart = Math.min(len, stride * 2);
        for (int i = syncOffset; i < maxStart; i++) {
            if (d[i] != 0x47) continue;
            int need = len >= i + stride * 2 + 1 ? 3 : 2;
            if (len < i + stride + 1) return -1;
            boolean ok = true;
            for (int k = 1; k < need; k++) {
                if (d[i + stride * k] != 0x47) {
                    ok = false;
                    break;
                }
            }
            if (ok) return i;
        }
        return -1;
    }

    private static void probeTs(byte[] d, int len, int start, int stride, Result res) {
        int pmtPid = -1;
        boolean pmtParsed = false;
        boolean pmtHasVideo = false;
        boolean pmtOnlyNonVideo = false;
        int videoPid = -1;
        boolean hevc = false;

        // 视频 ES 拼接缓冲：只在确定了 H.264/HEVC PID 后按需分配，上限为样本大小
        byte[] es = null;
        int esLen = 0;
        boolean pesStarted = false;

        for (int p = start; p + 188 <= len; p += stride) {
            if (d[p] != 0x47) {
                // 丢同步（中间夹了残缺包）：往后找下一个与后一包对齐的同步字节
                int next = -1;
                for (int i = p + 1; i + stride < len; i++) {
                    if (d[i] == 0x47 && d[i + stride] == 0x47) {
                        next = i;
                        break;
                    }
                }
                if (next < 0) break;
                p = next - stride;
                continue;
            }
            int b1 = d[p + 1] & 0xFF;
            int b3 = d[p + 3] & 0xFF;
            boolean pusi = (b1 & 0x40) != 0;
            int pid = ((b1 & 0x1F) << 8) | (d[p + 2] & 0xFF);
            int afc = (b3 >> 4) & 0x03;
            if ((afc & 0x01) == 0) continue; // 无负载
            int pos = p + 4;
            if ((afc & 0x02) != 0) {
                pos += 1 + (d[pos] & 0xFF);
            }
            int end = p + 188;
            if (pos >= end) continue;

            if (pid == 0 && pmtPid < 0 && pusi) {
                pmtPid = parsePat(d, pos, end);
                continue;
            }
            if (pid == pmtPid && !pmtParsed && pusi) {
                int[] types = new int[MAX_PMT_PIDS];
                int[] pids = new int[MAX_PMT_PIDS];
                // 返回值：解析到的 ES 数；负数表示段不完整（跨包），此时不下"无视频"的结论
                int n = parsePmt(d, pos, end, types, pids);
                int count = Math.min(Math.abs(n), MAX_PMT_PIDS);
                if (count == 0 && n >= 0) continue;
                pmtParsed = true;
                boolean allNonVideo = n > 0;
                for (int i = 0; i < count; i++) {
                    int t = types[i];
                    if (isVideoType(t)) {
                        pmtHasVideo = true;
                        if (videoPid < 0 && (t == ST_H264 || t == ST_HEVC)) {
                            videoPid = pids[i];
                            hevc = t == ST_HEVC;
                        }
                    }
                    if (!isNonVideoType(t)) allNonVideo = false;
                }
                pmtOnlyNonVideo = !pmtHasVideo && allNonVideo;
                if (pmtOnlyNonVideo) break; // 没有视频轨，无需再看
                if (videoPid < 0) break;   // 有视频但不是 H.264/HEVC，解析不了分辨率
                continue;
            }
            if (pid == videoPid && videoPid >= 0) {
                if (pusi) {
                    // PES 头：00 00 01 sid len(2) flags(2) hdrLen(1)
                    if (pos + 9 > end || d[pos] != 0 || d[pos + 1] != 0 || d[pos + 2] != 1) continue;
                    pos += 9 + (d[pos + 8] & 0xFF);
                    pesStarted = true;
                    if (pos >= end) continue;
                } else if (!pesStarted) {
                    continue; // 从 PES 中间开始的数据缺少开头，跳过
                }
                if (es == null) es = new byte[Math.min(len, 512 * 1024)];
                int n = Math.min(end - pos, es.length - esLen);
                if (n <= 0) break;
                System.arraycopy(d, pos, es, esLen, n);
                esLen += n;
            }
        }

        if (es != null && esLen > 0 && findSpsInAnnexB(es, esLen, hevc, res)) {
            res.videoState = SpeedTestResult.VIDEO_PRESENT;
            return;
        }
        if (pmtHasVideo) {
            res.videoState = SpeedTestResult.VIDEO_PRESENT;
        } else if (pmtOnlyNonVideo) {
            res.videoState = SpeedTestResult.VIDEO_MISSING;
        }
    }

    /** 解析 PAT，返回第一个节目的 PMT PID；失败返回 -1 */
    private static int parsePat(byte[] d, int pos, int end) {
        pos += 1 + (d[pos] & 0xFF); // pointer_field
        if (pos + 8 > end || (d[pos] & 0xFF) != 0x00) return -1;
        int sectionLen = ((d[pos + 1] & 0x0F) << 8) | (d[pos + 2] & 0xFF);
        int secEnd = Math.min(end, pos + 3 + sectionLen - 4); // 去掉 CRC
        for (int i = pos + 8; i + 4 <= secEnd; i += 4) {
            int program = ((d[i] & 0xFF) << 8) | (d[i + 1] & 0xFF);
            int pid = ((d[i + 2] & 0x1F) << 8) | (d[i + 3] & 0xFF);
            if (program != 0) return pid; // program 0 是 NIT
        }
        return -1;
    }

    /**
     * 解析 PMT 的 ES 列表到 types/pids。返回 ES 数；段超出本包（跨包，未完整解析）时返回其相反数。
     */
    private static int parsePmt(byte[] d, int pos, int end, int[] types, int[] pids) {
        pos += 1 + (d[pos] & 0xFF); // pointer_field
        if (pos + 12 > end || (d[pos] & 0xFF) != 0x02) return 0;
        int sectionLen = ((d[pos + 1] & 0x0F) << 8) | (d[pos + 2] & 0xFF);
        int fullEnd = pos + 3 + sectionLen - 4; // 去掉 CRC
        boolean complete = fullEnd <= end;
        int secEnd = Math.min(end, fullEnd);
        int progInfoLen = ((d[pos + 10] & 0x0F) << 8) | (d[pos + 11] & 0xFF);
        int i = pos + 12 + progInfoLen;
        int n = 0;
        while (i + 5 <= secEnd) {
            int type = d[i] & 0xFF;
            int pid = ((d[i + 1] & 0x1F) << 8) | (d[i + 2] & 0xFF);
            int esInfoLen = ((d[i + 3] & 0x0F) << 8) | (d[i + 4] & 0xFF);
            if (n < types.length) {
                types[n] = type;
                pids[n] = pid;
            } else {
                complete = false; // 记不下的 ES 无法判断类型
            }
            n++;
            i += 5 + esInfoLen;
        }
        return complete ? n : -n;
    }

    private static boolean isVideoType(int t) {
        switch (t) {
            case ST_MPEG1_VIDEO:
            case ST_MPEG2_VIDEO:
            case ST_MPEG4_VIDEO:
            case ST_H264:
            case ST_HEVC:
            case 0x1F: // H.264 SVC
            case 0x20: // H.264 MVC
            case 0x42: // AVS / AVS+（国内 IPTV 常见）
            case 0xD1: // Dirac
            case 0xD2: // AVS2
            case 0xD4: // AVS3
            case 0xEA: // VC-1
                return true;
            default:
                return false;
        }
    }

    /** 确定不是视频的流类型（音频 / 私有数据 / 元数据 / SCTE-35）；未列出的类型一律当作可能是视频 */
    private static boolean isNonVideoType(int t) {
        switch (t) {
            case 0x03: // MPEG-1 音频
            case 0x04: // MPEG-2 音频
            case 0x0F: // AAC ADTS
            case 0x11: // AAC LATM
            case 0x1C: // MPEG-4 原始音频
            case 0x81: // AC-3
            case 0x82: // DTS
            case 0x83: // TrueHD
            case 0x84: // E-AC-3
            case 0x85: // DTS-HD
            case 0x87: // E-AC-3
            case 0x8A: // DTS
            case 0x05: // 私有段
            case 0x06: // 私有 PES（AC-3 / 字幕 / 图文电视）
            case 0x0B:
            case 0x0C:
            case 0x0D: // DSM-CC
            case 0x15: // 元数据
            case 0x86: // SCTE-35
                return true;
            default:
                return false;
        }
    }

    /** 在 Annex-B 字节流（00 00 01 起始码）里找 SPS 并解析分辨率 */
    private static boolean findSpsInAnnexB(byte[] es, int len, boolean hevc, Result res) {
        int i = 0;
        while (i + 4 < len) {
            int sc = nextStartCode(es, i, len);
            if (sc < 0) return false;
            int nal = sc + 3;
            if (nal >= len) return false;
            int type = hevc ? (es[nal] >> 1) & 0x3F : es[nal] & 0x1F;
            if ((hevc && type == 33) || (!hevc && type == 7)) {
                int next = nextStartCode(es, nal, len);
                int nalEnd = next < 0 ? len : next;
                // 下一个起始码前的 0x00 属于下一个 4 字节起始码，去掉
                while (nalEnd > nal && es[nalEnd - 1] == 0) nalEnd--;
                if (parseSps(es, nal, nalEnd, hevc, res)) return true;
            }
            i = nal;
        }
        return false;
    }

    /** 从 from 开始找 00 00 01，返回第一个 00 的下标；没有返回 -1 */
    private static int nextStartCode(byte[] d, int from, int len) {
        for (int i = from; i + 2 < len; i++) {
            if ((d[i + 2] & 0xFF) > 1) {
                i += 2; // 快速跳过：第三字节 >1 时，i..i+2 都不可能是起始码开头
                continue;
            }
            if (d[i] == 0 && d[i + 1] == 0 && d[i + 2] == 1) return i;
        }
        return -1;
    }

    /** 解析 SPS（nal 指向 NAL 头，end 为不含的结束下标）；ExoPlayer 解析时已按裁剪区计算宽高 */
    private static boolean parseSps(byte[] d, int nal, int end, boolean hevc, Result res) {
        if (end - nal < 4) return false;
        try {
            int w, h;
            if (hevc) {
                NalUnitUtil.H265SpsData sps = NalUnitUtil.parseH265SpsNalUnit(d, nal, end);
                w = sps.width;
                h = sps.height;
            } else {
                NalUnitUtil.SpsData sps = NalUnitUtil.parseSpsNalUnit(d, nal, end);
                w = sps.width;
                h = sps.height;
            }
            // 合理范围外的值视为解析错误（截断/损坏的 SPS）
            if (w < 16 || h < 16 || w > 8192 || h > 8192) return false;
            res.width = w;
            res.height = h;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ============ FLV ============

    private static void probeFlv(byte[] d, int len, Result res) {
        int flags = d[4] & 0xFF;
        boolean headerVideo = (flags & 0x01) != 0;
        int headerSize = readInt32(d, 5);
        if (headerSize < 9 || headerSize > len) headerSize = 9;
        int pos = headerSize + 4; // 跳过 PreviousTagSize0
        boolean videoSeen = false;
        boolean audioSeen = false;
        int metaW = 0, metaH = 0;
        while (pos + 11 <= len) {
            int type = d[pos] & 0x1F;
            int size = ((d[pos + 1] & 0xFF) << 16) | ((d[pos + 2] & 0xFF) << 8) | (d[pos + 3] & 0xFF);
            int body = pos + 11;
            int bodyEnd = body + size;
            if (size < 0 || bodyEnd > len) {
                // 截断的 tag：序列头通常在开头，截断的只可能是普通帧，类型已能判断
                if (type == 9) videoSeen = true;
                else if (type == 8) audioSeen = true;
                break;
            }
            if (type == 9) {
                videoSeen = true;
                if (size > 0 && parseFlvVideoTag(d, body, bodyEnd, res)) {
                    res.videoState = SpeedTestResult.VIDEO_PRESENT;
                    return;
                }
            } else if (type == 8) {
                audioSeen = true;
            } else if (type == 18 && metaW == 0 && size > 0) {
                int[] wh = parseFlvMetaSize(d, body, bodyEnd);
                if (wh != null) {
                    metaW = wh[0];
                    metaH = wh[1];
                }
            }
            pos = bodyEnd + 4; // PreviousTagSize
        }
        if (videoSeen) {
            res.videoState = SpeedTestResult.VIDEO_PRESENT;
            if (metaW > 0 && metaH > 0) {
                res.width = metaW;
                res.height = metaH;
            }
        } else if (!headerVideo && audioSeen) {
            res.videoState = SpeedTestResult.VIDEO_MISSING;
        }
    }

    /** 解析视频 tag 的序列头（AVC 7 / HEVC 12 / Enhanced RTMP 扩展头），成功解析出分辨率返回 true */
    private static boolean parseFlvVideoTag(byte[] d, int body, int end, Result res) {
        int b0 = d[body] & 0xFF;
        if ((b0 & 0x80) != 0) {
            // Enhanced RTMP：低 4 位为 PacketType，0 = SequenceStart；随后 4 字节 FourCC
            if ((b0 & 0x0F) != 0 || body + 5 > end) return false;
            int fourcc = readInt32(d, body + 1);
            if (fourcc == 0x61766331) return parseAvcc(d, body + 5, end, res);      // 'avc1'
            if (fourcc == 0x68766331) return parseHvcc(d, body + 5, end, res);      // 'hvc1'
            return false;
        }
        int codec = b0 & 0x0F;
        if (body + 5 > end || d[body + 1] != 0) return false; // 只看 AVCPacketType 0（序列头）
        if (codec == 7) return parseAvcc(d, body + 5, end, res);
        if (codec == 12) return parseHvcc(d, body + 5, end, res);
        return false;
    }

    /** AVCDecoderConfigurationRecord：取第一个 SPS */
    private static boolean parseAvcc(byte[] d, int pos, int end, Result res) {
        if (pos + 8 > end) return false;
        int numSps = d[pos + 5] & 0x1F;
        int i = pos + 6;
        for (int k = 0; k < numSps && i + 2 <= end; k++) {
            int n = ((d[i] & 0xFF) << 8) | (d[i + 1] & 0xFF);
            int nal = i + 2;
            if (nal + n > end) return false;
            if (parseSps(d, nal, nal + n, false, res)) return true;
            i = nal + n;
        }
        return false;
    }

    /** HEVCDecoderConfigurationRecord：在 NAL 数组里找 SPS（类型 33） */
    private static boolean parseHvcc(byte[] d, int pos, int end, Result res) {
        if (pos + 23 > end) return false;
        int numArrays = d[pos + 22] & 0xFF;
        int i = pos + 23;
        for (int a = 0; a < numArrays && i + 3 <= end; a++) {
            int nalType = d[i] & 0x3F;
            int numNalus = ((d[i + 1] & 0xFF) << 8) | (d[i + 2] & 0xFF);
            i += 3;
            for (int k = 0; k < numNalus && i + 2 <= end; k++) {
                int n = ((d[i] & 0xFF) << 8) | (d[i + 1] & 0xFF);
                int nal = i + 2;
                if (nal + n > end) return false;
                if (nalType == 33 && parseSps(d, nal, nal + n, true, res)) return true;
                i = nal + n;
            }
        }
        return false;
    }

    /** onMetaData 里的 width/height（AMF0 数值）；找不到返回 null */
    private static int[] parseFlvMetaSize(byte[] d, int body, int end) {
        double w = findAmfNumber(d, body, end, "width");
        double h = findAmfNumber(d, body, end, "height");
        if (w >= 16 && h >= 16 && w <= 8192 && h <= 8192) {
            return new int[]{(int) w, (int) h};
        }
        return null;
    }

    /** 查找 AMF0 属性：2 字节长度 + 名称 + 类型 0x00（number）+ 8 字节 double */
    private static double findAmfNumber(byte[] d, int from, int end, String name) {
        int n = name.length();
        for (int i = from; i + 2 + n + 9 <= end; i++) {
            if (d[i] != 0 || (d[i + 1] & 0xFF) != n) continue;
            boolean match = true;
            for (int k = 0; k < n; k++) {
                if (d[i + 2 + k] != name.charAt(k)) {
                    match = false;
                    break;
                }
            }
            if (!match || d[i + 2 + n] != 0x00) continue;
            long bits = 0;
            for (int k = 0; k < 8; k++) bits = (bits << 8) | (d[i + 3 + n + k] & 0xFF);
            double v = Double.longBitsToDouble(bits);
            return Double.isNaN(v) ? 0 : v;
        }
        return 0;
    }

    // ============ ADTS ============

    /** 纯 AAC ADTS 流（可带 ID3v2 头）：从开头起连续 3 帧的帧头都对得上才认 */
    private static boolean looksLikeAdts(byte[] d, int len) {
        int pos = 0;
        if (len >= 10 && d[0] == 'I' && d[1] == 'D' && d[2] == '3') {
            int size = ((d[6] & 0x7F) << 21) | ((d[7] & 0x7F) << 14) | ((d[8] & 0x7F) << 7) | (d[9] & 0x7F);
            pos = 10 + size;
            if ((d[5] & 0x10) != 0) pos += 10; // footer
        }
        int frames = 0;
        while (frames < 3) {
            if (pos + 7 > len) return false;
            if ((d[pos] & 0xFF) != 0xFF || (d[pos + 1] & 0xF6) != 0xF0) return false;
            int frameLen = ((d[pos + 3] & 0x03) << 11) | ((d[pos + 4] & 0xFF) << 3) | ((d[pos + 5] & 0xE0) >> 5);
            if (frameLen < 7) return false;
            pos += frameLen;
            frames++;
        }
        return true;
    }

    private static int readInt32(byte[] d, int i) {
        return ((d[i] & 0xFF) << 24) | ((d[i + 1] & 0xFF) << 16) | ((d[i + 2] & 0xFF) << 8) | (d[i + 3] & 0xFF);
    }
}
