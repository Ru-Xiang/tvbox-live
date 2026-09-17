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
 *   下载速度（Speed）   45%
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

    /**
     * 计算综合质量评分（0-100 整数），并把结果写入 result.qualityScore。
     * 不可用的源直接返回 0 分。
     */
    public static int score(SpeedTestResult result) {
        if (result == null || !result.isAvailable()) {
            if (result != null) result.setQualityScore(0);
            return 0;
        }
        double s = scoreSpeed(result.getSpeed()) * W_SPEED
                + scoreResolution(result.getResolution()) * W_RES
                + scoreLatency(result.getLatency()) * W_LATENCY
                + scoreCodec(result.getCodecs()) * W_CODEC;
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
        if (latencyMs <= 0) return 50; // 未知
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
