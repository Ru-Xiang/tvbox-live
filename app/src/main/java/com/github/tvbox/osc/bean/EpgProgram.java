package com.github.tvbox.osc.bean;

import java.io.Serializable;

/**
 * EPG 单条节目
 */
public class EpgProgram implements Serializable {

    /** 开始时间 HH:mm */
    private String start;
    /** 结束时间 HH:mm */
    private String end;
    /** 节目标题 */
    private String title;

    public EpgProgram() {}

    public EpgProgram(String start, String end, String title) {
        this.start = start;
        this.end = end;
        this.title = title;
    }

    public String getStart() { return start; }
    public void setStart(String start) { this.start = start; }

    public String getEnd() { return end; }
    public void setEnd(String end) { this.end = end; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    /** 判断给定的 HH:mm 时刻是否落在本节目时间段内 */
    public boolean isPlayingAt(String hhmm) {
        if (start == null || end == null || hhmm == null) return false;
        // 处理跨午夜：end < start 视为次日
        if (end.compareTo(start) >= 0) {
            return hhmm.compareTo(start) >= 0 && hhmm.compareTo(end) < 0;
        } else {
            return hhmm.compareTo(start) >= 0 || hhmm.compareTo(end) < 0;
        }
    }

    public String display() {
        return (start != null ? start : "") + " " + (title != null ? title : "");
    }

    /**
     * 计算当前节目的播放进度百分比 (0-100)，仅在 hhmm 落在节目区间内有效，
     * 其余返回：-1 表示未开始，101 表示已结束。
     */
    public int progressAt(String hhmm) {
        if (start == null || end == null || hhmm == null) return -1;
        int s = toMinutes(start);
        int e = toMinutes(end);
        int t = toMinutes(hhmm);
        if (s < 0 || e < 0 || t < 0) return -1;
        // 跨午夜时把 end 加 24h
        if (e < s) {
            e += 24 * 60;
            if (t < s) t += 24 * 60; // 若当前也在午夜后
        }
        if (t < s) return -1;
        if (t >= e) return 101;
        int total = e - s;
        return total <= 0 ? 0 : (int) Math.min(100, Math.max(0, (t - s) * 100L / total));
    }

    /** 节目时长（分钟），跨午夜按次日处理；无法解析返回 0 */
    public int durationMinutes() {
        int s = toMinutes(start);
        int e = toMinutes(end);
        if (s < 0 || e < 0) return 0;
        if (e < s) e += 24 * 60;
        return e - s;
    }

    /** HH:mm → 分钟数；解析失败返回 -1 */
    private static int toMinutes(String hhmm) {
        if (hhmm == null || hhmm.length() < 4) return -1;
        try {
            int colon = hhmm.indexOf(':');
            if (colon <= 0) return -1;
            int h = Integer.parseInt(hhmm.substring(0, colon).trim());
            int m = Integer.parseInt(hhmm.substring(colon + 1).trim());
            if (h < 0 || h > 23 || m < 0 || m > 59) return -1;
            return h * 60 + m;
        } catch (Exception e) {
            return -1;
        }
    }
}
