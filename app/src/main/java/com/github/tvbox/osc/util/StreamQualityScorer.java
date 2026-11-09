package com.github.tvbox.osc.util;

import com.github.tvbox.osc.bean.SpeedTestResult;

/**
 * 流质量综合评分器
 *
 * 对齐 ISEP 项目 stream_quality_scorer.py 的评分思路：
 * 一个直播源的"好坏"不仅取决于下载速度，还与分辨率、延迟、
 * 编码格式相关。本类将各维度归一化到 0-100 分并加权求和，
 * 得到综合质量评分，用于源排序、最佳源选择、清理策略等。
 *
 * 权重分配（总和 = 100）：
 *   速度（Speed）       45%  —— 1.3.2 起有实时倍率时按实时倍率打分（见 scoreRealtime），没有才按 KB/s
 *   分辨率（Resolution） 25%
 *   延迟（Latency）      20%
 *   编码格式（Codec）    10%
 *
 * 参考取值：
 *   Speed:   0 ~ 2000 KB/s（>=2000 KB/s 满分）
 *   Res:     0 ~ 3840 (宽度像素) 满分
 *   Latency: 0 (100ms 内满分), 3000ms 归零
 *   Codec:   H.265/HEVC/AV1 优先；H.264 中位；MPEG2 较低
 */
public final class StreamQualityScorer {

    private StreamQualityScorer() {}

    // 权重
    private static final double W_SPEED = 0.45;
    private static final double W_RES = 0.25;
    private static final double W_LATENCY = 0.20;
    private static final double W_CODEC = 0.10;

    // 归一化基准（速度 KB/s）
    private static final double SPEED_MAX_KBPS = 2000.0;
    private static final double SPEED_MIN_KBPS = 50.0;

    // 分辨率宽度基准
    private static final int RES_MAX_WIDTH = 3840; // 4K
    private static final int RES_HD_WIDTH = 1280;  // 720P

    // 延迟基准（ms）
    private static final long LATENCY_BEST = 100;
    private static final long LATENCY_WORST = 3000;

    /** 未知分辨率 / 编码的得分（直链拿不到这些元信息） */
    private static final double RES_UNKNOWN = 40;
    private static final double CODEC_UNKNOWN = 50;

    /**
     * 计算综合质量评分（0-100 整数），并把结果写入 result.qualityScore。
     * 不可用的源直接返回 0 分。
     */
    public static int score(SpeedTestResult result) {
        if (result == null || !result.isAvailable()) {
            if (result != null) result.setQualityScore(0);
            return 0;
        }
        double res = scoreResolution(result.getResolution());
        double codec = scoreCodec(result.getCodecs());
        // HLS 声明了码率（BANDWIDTH）时，用实测速度检验它跟不跟得上：
        // 跟不上的线路实际会卡顿 / 只能降到低码率，它声明的 1080P/HEVC 并不兑现，
        // 分辨率与编码的加分按"实测速度 / 所需速度"折算回"未知"水平，
        // 否则 60 KB/s 的 1080P 线路会排在 300 KB/s 的流畅直链前面。
        // 速度足够（ratio=1）或未声明码率（直链、媒体列表）时评分与以前完全一致。
        long bandwidth = result.getBandwidth();
        double rt = result.getRealtime();
        if (bandwidth > 0 || rt > 0) {
            // 跟得上的程度：优先用实测的实时倍率，没有才用"实测速度 / 声明码率"
            double needKBps = bandwidth / 8.0 / 1024.0;
            // 实时倍率 ≥0.9 视为跟得上（测量误差内），不再重复扣分辨率 / 编码的分
            double ratio = rt > 0 ? (rt >= 0.9 ? 1 : rt / 0.9)
                    : needKBps > 0 ? Math.max(0, Math.min(1, result.getSpeed() / needKBps)) : 1;
            if (res > RES_UNKNOWN) res = RES_UNKNOWN + (res - RES_UNKNOWN) * ratio;
            if (codec > CODEC_UNKNOWN) codec = CODEC_UNKNOWN + (codec - CODEC_UNKNOWN) * ratio;
        }
        double speedScore = speedComponent(result);
        double s = speedScore * W_SPEED
                + res * W_RES
                + scoreLatency(result.getLatency()) * W_LATENCY
                + codec * W_CODEC;
        int score = (int) Math.round(Math.max(0, Math.min(100, s)));
        result.setQualityScore(score);
        return score;
    }

    /** 仅根据下载速度与 HLS 声明带宽估算评分（无 SpeedTestResult 场景，用于快速比较） */
    public static double estimate(double speedKBps, long bandwidthBps, String resolution) {
        double effSpeed = Math.max(speedKBps, bandwidthBps / 8.0 / 1024.0);
        return scoreSpeed(effSpeed) * (W_SPEED + W_CODEC)
                + scoreResolution(resolution) * W_RES
                + 60 * W_LATENCY; // 未知延迟给中位值
    }

    /**
     * 速度项得分（0–100），评分与"按速度"排序共用同一个值，保证两种排序口径一致、比较满足全序。
     * <ul>
     *   <li>HLS 有实时倍率：按余量打分（{@link #scoreRealtime}，1× 刚好 50 分，3× 满分）；</li>
     *   <li>推流类（直链 / RTMP / 组播，服务器按实时速度推）有实时倍率：健康的上限就是 1× 左右，
     *       达到 0.95× 即 90 分，开头突发越大越接近满分；跟不上的扣分与 HLS 相同；</li>
     *   <li>没有实时倍率：按 KB/s；其中 HLS 的 KB/s 是下载带宽，封顶 75 分，不让它仅凭带宽排到最前。</li>
     * </ul>
     */
    public static double speedComponent(SpeedTestResult r) {
        if (r == null) return 0;
        double rt = r.getRealtime();
        if (rt > 0 && !Double.isNaN(rt)) return r.isHls() ? scoreRealtime(rt) : scorePacedRealtime(rt);
        double s = scoreSpeed(r.getSpeed());
        return r.isHls() ? Math.min(s, 75) : s;
    }

    /** 推流类线路的实时倍率打分：≥0.95× 为 90–100 分（突发余量越大越高，2× 封顶），低于 0.95× 迅速扣分 */
    static double scorePacedRealtime(double rt) {
        if (rt <= 0) return 0;
        if (rt < 0.85) return rt / 0.85 * 30;
        if (rt < 0.95) return 30 + (rt - 0.85) / 0.1 * 60;
        return Math.min(100, 90 + (rt - 0.95) / 1.05 * 10);
    }

    /**
     * 实时倍率打分：&lt;0.9 跟不上（0–30）；0.9–1 勉强（30–50）；1× 刚好跟上（50）；
     * 1–3× 余量越大越稳（50–100）；≥3× 满分
     */
    static double scoreRealtime(double rt) {
        if (rt <= 0) return 0;
        // 0.95–1× 在测量误差内（首帧、窗口截断），按刚好跟上算
        if (rt < 0.85) return rt / 0.85 * 30;
        if (rt < 0.95) return 30 + (rt - 0.85) / 0.1 * 20;
        if (rt < 1.0) return 50;
        if (rt >= 3.0) return 100;
        return 50 + (rt - 1.0) / 2.0 * 50;
    }

    private static double scoreSpeed(double kbps) {
        if (kbps <= 0) return 0;
        if (kbps >= SPEED_MAX_KBPS) return 100;
        if (kbps <= SPEED_MIN_KBPS) return kbps / SPEED_MIN_KBPS * 20; // 0~50KB/s 给 0~20 分
        // 50~2000 KB/s 之间线性映射到 20~100
        double t = (kbps - SPEED_MIN_KBPS) / (SPEED_MAX_KBPS - SPEED_MIN_KBPS);
        return 20 + t * 80;
    }

    private static double scoreResolution(String resolution) {
        if (resolution == null || resolution.isEmpty()) return 40; // 未知给中偏低
        try {
            String[] parts = resolution.toLowerCase(java.util.Locale.ROOT).split("[x×*]");
            if (parts.length < 2) return 40;
            int w = Integer.parseInt(parts[0].trim());
            if (w >= RES_MAX_WIDTH) return 100; // 4K
            if (w >= 1920) return 90;           // 1080P
            if (w >= RES_HD_WIDTH) return 75;   // 720P
            if (w >= 854) return 50;            // 480P
            return 30;
        } catch (Exception e) {
            return 40;
        }
    }

    private static double scoreLatency(long latencyMs) {
        // 只有负数（测速失败记 -1）才是"未知"；0 是同一毫秒内就响应的局域网 / udpxy 线路，应得满分
        if (latencyMs < 0) return 50; // 未知
        if (latencyMs <= LATENCY_BEST) return 100;
        if (latencyMs >= LATENCY_WORST) return 0;
        double t = (latencyMs - LATENCY_BEST) / (double) (LATENCY_WORST - LATENCY_BEST);
        return Math.max(0, 100 - t * 100);
    }

    private static double scoreCodec(String codecs) {
        if (codecs == null || codecs.isEmpty()) return 50;
        String c = codecs.toLowerCase(java.util.Locale.ROOT);
        // AV1
        if (c.contains("av01") || c.contains("av1")) return 100;
        // HEVC / H.265
        if (c.contains("hvc1") || c.contains("hev1") || c.contains("h265") || c.contains("hevc")) return 90;
        // H.264
        if (c.contains("avc1") || c.contains("h264")) return 70;
        // MPEG2
        if (c.contains("mp2v") || c.contains("mpeg2")) return 40;
        return 50;
    }
}
