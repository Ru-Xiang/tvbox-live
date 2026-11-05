package com.github.tvbox.osc.util;

/**
 * 播放状态的全局快照，供后台任务（测速等）给正在观看的直播让路。
 *
 * <p>由播放页维护：起播/恢复播放时 setPlaying(true)，卡顿缓冲时 setBuffering(true)，
 * 停止/释放播放器时两者都置 false。读取方只做"降速/暂缓"判断，不依赖其精确性。
 */
public final class PlaybackGate {

    private PlaybackGate() {}

    private static volatile boolean playing;
    private static volatile boolean buffering;

    /** 是否有直播正在播放（含卡顿缓冲中） */
    public static boolean isPlaying() {
        return playing;
    }

    /** 正在播放的直播是否卡在缓冲中（此时后台下载应尽量暂缓） */
    public static boolean isBuffering() {
        return playing && buffering;
    }

    public static void setPlaying(boolean value) {
        playing = value;
        if (!value) buffering = false;
    }

    public static void setBuffering(boolean value) {
        buffering = value;
    }
}
