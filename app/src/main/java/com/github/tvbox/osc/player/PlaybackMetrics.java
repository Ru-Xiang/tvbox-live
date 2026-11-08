package com.github.tvbox.osc.player;

import android.os.SystemClock;

import com.github.tvbox.osc.util.RunLog;

import timber.log.Timber;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 起播耗时统计（P0 量化基线）。
 *
 * <p>一次起播记三个时间点：
 * <ul>
 *   <li>{@link #begin}：用户按键换台 / 换源，开始起播；</li>
 *   <li>{@link #onFirstFrame}：渲染出第一帧（"按下到第一帧"）；</li>
 *   <li>{@link #onPlaying}：播放器真正开始走（"按下到画面动起来"）。</li>
 * </ul>
 * 每次起播在运行日志里写一行 {@code 起播耗时: 首帧 165ms, 动起来 264ms [HLS] (近20次中位: …)}，
 * 并保留最近 {@link #WINDOW} 次的数据算中位数，方便前后版本、不同设备对比。
 *
 * <p>计时从<b>用户操作</b>（按键换台/换源、进入回看）算起：自动换线（上一条超时/失败）不重新计时，
 * 日志里标出这是第几条线路，这样"第一条线路超时 5 秒、第二条 400ms 起播"记为 5.4 秒而不是 400ms。
 * 已播过的线路断流后的原线路重连不计入中位数（它衡量的是重连，不是换台）。
 *
 * <p>只在主线程调用（ExoPlayer 回调与按键都在主线程）。按起播代次（playGen）区分，
 * 换台后才到达的上一条线路的迟到事件会被丢弃。同一行也写进 logcat（标签 PlaybackMetrics）。
 */
public final class PlaybackMetrics {

    private static final int WINDOW = 20;

    private int gen = -1;
    private long startMs;
    private String label = "";
    private long firstFrameMs = -1;
    private boolean playingLogged;
    /** 本次用户操作以来第几条线路（1 起） */
    private int attempt;
    /** 本代起播是否计入中位数 */
    private boolean record = true;

    private final List<Long> firstFrames = new ArrayList<>();
    private final List<Long> playings = new ArrayList<>();

    /**
     * 开始一代起播。
     *
     * @param userStart true＝用户操作发起（重新计时）；false＝自动换线 / 重试（沿用上次用户操作的起点）
     * @param record    false＝不计入中位数（原线路重连）
     */
    public void begin(int playGen, String label, boolean userStart, boolean record) {
        this.gen = playGen;
        if (userStart || startMs == 0L) {
            this.startMs = SystemClock.elapsedRealtime();
            this.attempt = 1;
        } else {
            this.attempt++;
        }
        this.label = label != null ? label : "";
        this.record = record;
        this.firstFrameMs = -1;
        this.playingLogged = false;
    }

    /** 这一代起播渲染出首帧 */
    public void onFirstFrame(int playGen) {
        if (playGen != gen || firstFrameMs >= 0) return;
        firstFrameMs = SystemClock.elapsedRealtime() - startMs;
    }

    /** 这一代起播开始播放（isPlaying 首次变为 true） */
    public void onPlaying(int playGen) {
        if (playGen != gen || playingLogged) return;
        playingLogged = true;
        long playing = SystemClock.elapsedRealtime() - startMs;
        if (record) {
            add(playings, playing);
            if (firstFrameMs >= 0) add(firstFrames, firstFrameMs);
        }
        String tag = label + (attempt > 1 ? ", 第" + attempt + "条线路" : "") + (record ? "" : ", 重连不计入");
        String line = String.format(Locale.ROOT, "起播耗时: 首帧 %s, 动起来 %dms%s (近%d次中位: 首帧 %s, 动起来 %s)",
                firstFrameMs >= 0 ? firstFrameMs + "ms" : "-", playing,
                tag.isEmpty() ? "" : " [" + tag + "]",
                playings.size(), fmt(median(firstFrames)), fmt(median(playings)));
        RunLog.i(line);
        Timber.tag("PlaybackMetrics").i(line);
    }

    /** 最近若干次"按下到画面动起来"的中位数（毫秒），没有数据返回 -1 */
    public long medianPlayingMs() {
        return median(playings);
    }

    private static void add(List<Long> list, long v) {
        list.add(v);
        if (list.size() > WINDOW) list.remove(0);
    }

    private static long median(List<Long> list) {
        if (list.isEmpty()) return -1;
        List<Long> copy = new ArrayList<>(list);
        Collections.sort(copy);
        return copy.get(copy.size() / 2);
    }

    private static String fmt(long ms) {
        return ms < 0 ? "-" : ms + "ms";
    }
}
