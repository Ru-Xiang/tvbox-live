package com.github.tvbox.osc.bean;

import java.io.Serializable;

/**
 * 测速结果数据模型
 * 记录单个播放源的测速信息
 */
public class SpeedTestResult implements Serializable {

    /** 测试的URL */
    private String url;

    /** 对应的频道名称 */
    private String channelName;

    /** 下载速度（KB/s） */
    private double speed;

    /** 延迟（ms） */
    private long latency;

    /** 是否可用 */
    private boolean available;

    /** 分辨率信息（如 "1920x1080"） */
    private String resolution;

    /** 测试时间戳 */
    private long testTime;

    /** 测试状态：0=未测试, 1=测试中, 2=测试完成, 3=测试失败 */
    private int status;

    /** 错误信息 */
    private String errorMsg;

    /** 连接超时时间 */
    private long connectTimeout;

    /** HLS 播放列表声明的目标码率（bps），未知为 0 */
    private long bandwidth;

    /** 编码格式（如 avc1.640028,mp4a.40.2），HLS 播放列表 CODECS 属性 */
    private String codecs;

    /** 是否为重试后成功（仅用于日志/统计） */
    private boolean retried;

    /** 综合质量评分（0-100，越高越好），由 StreamQualityScorer 计算 */
    private int qualityScore;

    /** 失败原因分类：TIMEOUT / HTTP_ERROR / DNS / OTHER，用于智能重试判定 */
    private String failureType;

    public SpeedTestResult() {
        this.testTime = System.currentTimeMillis();
        this.status = 0;
    }

    public SpeedTestResult(String url, String channelName) {
        this();
        this.url = url;
        this.channelName = channelName;
    }

    /** 判断是否为高清源 */
    public boolean isHD() {
        if (resolution == null) return false;
        try {
            String[] parts = resolution.split("x");
            if (parts.length == 2) {
                int width = Integer.parseInt(parts[0].trim());
                return width >= 1280;
            }
        } catch (Exception ignored) {}
        return false;
    }

    /** 判断速度是否合格（>=200KB/s 认为可正常播放） */
    public boolean isSpeedQualified() {
        return available && speed >= 200;
    }

    /** 获取速度等级描述 */
    public String getSpeedLevel() {
        if (!available) return "不可用";
        if (speed >= 1000) return "极速";
        if (speed >= 500) return "流畅";
        if (speed >= 200) return "正常";
        if (speed >= 50) return "较慢";
        return "卡顿";
    }

    // ============ Getter / Setter ============

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getChannelName() { return channelName; }
    public void setChannelName(String channelName) { this.channelName = channelName; }

    public double getSpeed() { return speed; }
    public void setSpeed(double speed) { this.speed = speed; }

    public long getLatency() { return latency; }
    public void setLatency(long latency) { this.latency = latency; }

    public boolean isAvailable() { return available; }
    public void setAvailable(boolean available) { this.available = available; }

    public String getResolution() { return resolution; }
    public void setResolution(String resolution) { this.resolution = resolution; }

    public long getTestTime() { return testTime; }
    public void setTestTime(long testTime) { this.testTime = testTime; }

    public int getStatus() { return status; }
    public void setStatus(int status) { this.status = status; }

    public String getErrorMsg() { return errorMsg; }
    public void setErrorMsg(String errorMsg) { this.errorMsg = errorMsg; }

    public long getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(long connectTimeout) { this.connectTimeout = connectTimeout; }

    public long getBandwidth() { return bandwidth; }
    public void setBandwidth(long bandwidth) { this.bandwidth = bandwidth; }

    public String getCodecs() { return codecs; }
    public void setCodecs(String codecs) { this.codecs = codecs; }

    public boolean isRetried() { return retried; }
    public void setRetried(boolean retried) { this.retried = retried; }

    public int getQualityScore() { return qualityScore; }
    public void setQualityScore(int qualityScore) { this.qualityScore = qualityScore; }

    public String getFailureType() { return failureType; }
    public void setFailureType(String failureType) { this.failureType = failureType; }

    /** 是否为超时失败（用于智能重试判定） */
    public boolean isTimeoutFailure() {
        return !available && "TIMEOUT".equals(failureType);
    }

    @Override
    public String toString() {
        return "SpeedTestResult{" +
                "url='" + url + '\'' +
                ", speed=" + speed + " KB/s" +
                ", latency=" + latency + " ms" +
                ", available=" + available +
                ", level=" + getSpeedLevel() +
                '}';
    }
}
