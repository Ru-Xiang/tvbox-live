package com.github.tvbox.osc.bean;

import androidx.room.Entity;
import androidx.room.PrimaryKey;
import androidx.room.Ignore;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 直播频道数据模型
 * 代表一个具体的电视频道，包含多个播放源地址和测速信息
 */
@Entity(tableName = "live_channels")
public class LiveChannel implements Serializable {

    @PrimaryKey(autoGenerate = true)
    private long id;

    /** 频道名称，如 "CCTV-1 综合" */
    private String channelName;

    /** 频道编号 */
    private String channelNum;

    /** 分组名称，如 "央视", "卫视", "地方台" */
    private String groupName;

    /** 当前正在使用的播放源索引 */
    private int sourceIndex = 0;

    /** EPG 节目 ID，用于获取节目单 */
    private String epgId;

    /** 频道图标 URL */
    private String logoUrl;

    /** 最后一次测速时间戳 */
    private long lastSpeedTestTime;

    /** 当前最优源的速度（KB/s） */
    private double bestSpeed;

    /** 是否收藏 */
    private boolean favorite;

    /** 是否被加入黑名单（屏蔽，不显示也不参与测速/计数） */
    private boolean blacklisted;

    /** 排序权重 */
    private int sortWeight;

    /** 播放源URL列表（JSON序列化存储） */
    private String sourceUrlsJson;

    /** 各源对应的速度列表（JSON序列化存储） */
    private String speedsJson;

    /** 来源标识：iptv-api / manual / local */
    private String sourceOrigin;

    /** 上次播放时间 */
    private long lastPlayTime;

    /** 当前最优源检测到的分辨率（如 "1920x1080"），无则为空 */
    private String bestResolution;

    /** 当前最优源的综合质量评分（0-100） */
    private int bestQualityScore;

    @Ignore
    private List<String> sourceUrls;

    @Ignore
    private List<Double> speeds;

    /**
     * 线路 URL 去重索引（与 sourceUrls 同步维护）。
     * 热门频道跨数十个源可累积上万条线路，用 List#contains 去重会退化为 O(n²)，
     * 这里用 HashSet 做 O(1) 判断。仅作索引用途，不参与序列化。
     */
    @Ignore
    private transient java.util.HashSet<String> urlSet;

    @Ignore
    private String currentEpgInfo;

    public LiveChannel() {
        this.sourceUrls = new ArrayList<>();
        this.speeds = new ArrayList<>();
    }

    @Ignore
    public LiveChannel(String channelName, String groupName) {
        this();
        this.channelName = channelName;
        this.groupName = groupName;
    }

    // ============ 业务方法 ============

    /** 获取当前播放源URL */
    public String getCurrentSourceUrl() {
        if (sourceUrls != null && sourceIndex >= 0 && sourceIndex < sourceUrls.size()) {
            return sourceUrls.get(sourceIndex);
        }
        return null;
    }

    /** 切换到下一个源 */
    public boolean switchToNextSource() {
        if (sourceUrls != null && sourceIndex < sourceUrls.size() - 1) {
            sourceIndex++;
            return true;
        }
        return false;
    }

    /** 切换到上一个源 */
    public boolean switchToPrevSource() {
        if (sourceIndex > 0) {
            sourceIndex--;
            return true;
        }
        return false;
    }

    /**
     * 单频道线路数硬上限。
     *
     * <p>测速需要覆盖全部线路，因此解析阶段不按用户的"最大线路数"裁剪；
     * 但热门频道（如 CCTV-1）跨数十个源可累积上万条线路，
     * 会让内存峰值与测速耗时失控（低内存盒子直接 OOM）。
     * 这里设一个远高于实际需要、又能防止失控的上限。
     */
    public static final int MAX_SOURCE_URLS = 200;

    /** 添加播放源（O(1) 去重，超过硬上限后忽略） */
    public void addSourceUrl(String url) {
        if (url == null || url.isEmpty()) return;
        if (sourceUrls == null) {
            sourceUrls = new ArrayList<>();
        }
        if (sourceUrls.size() >= MAX_SOURCE_URLS) return;
        if (urlSet == null) {
            // 懒初始化并与现有列表对齐（反序列化/setSourceUrls 后 urlSet 可能为空）
            urlSet = new java.util.HashSet<>(Math.max(16, sourceUrls.size() * 2));
            urlSet.addAll(sourceUrls);
        }
        if (urlSet.add(url)) {
            sourceUrls.add(url);
            if (speeds == null) speeds = new ArrayList<>();
            speeds.add(0.0);
        }
    }

    /** 获取源数量 */
    public int getSourceCount() {
        return sourceUrls != null ? sourceUrls.size() : 0;
    }

    /**
     * 将指定 URL 的线路置顶（移动到列表首位），speeds 同步移动。
     * 置顶后当前播放源索引复位到 0。
     *
     * @return 是否发生了移动
     */
    public boolean moveSourceToTop(String url) {
        if (url == null || sourceUrls == null) return false;
        int idx = sourceUrls.indexOf(url);
        if (idx <= 0) return false; // 不存在或已在首位
        String u = sourceUrls.remove(idx);
        sourceUrls.add(0, u);
        if (speeds != null && idx < speeds.size()) {
            Double s = speeds.remove(idx);
            speeds.add(0, s);
        }
        sourceIndex = 0;
        return true;
    }

    /**
     * 移除指定 URL 的线路（speeds 同步移除），并修正当前播放源索引。
     *
     * @return 是否成功移除
     */
    public boolean removeSource(String url) {
        if (url == null || sourceUrls == null) return false;
        int idx = sourceUrls.indexOf(url);
        if (idx < 0) return false;
        sourceUrls.remove(idx);
        if (urlSet != null) urlSet.remove(url);
        if (speeds != null && idx < speeds.size()) {
            speeds.remove(idx);
        }
        // 修正当前索引，保持指向有效范围
        if (sourceIndex >= sourceUrls.size()) {
            sourceIndex = Math.max(0, sourceUrls.size() - 1);
        } else if (idx < sourceIndex) {
            sourceIndex--;
        }
        return true;
    }

    /**
     * 按给定 URL 顺序重排线路（仅重排列表中已存在的 URL，未列出的保持在其后，
     * speeds 同步跟随）。用于恢复用户自定义的线路置顶顺序。
     */
    public void reorderSourcesBy(java.util.List<String> orderedUrls) {
        if (orderedUrls == null || orderedUrls.isEmpty()
                || sourceUrls == null || sourceUrls.isEmpty()) {
            return;
        }
        boolean hasSpeeds = speeds != null && speeds.size() == sourceUrls.size();
        int n = sourceUrls.size();
        // URL -> 原索引，避免下面对每个 orderedUrls 元素做 O(n) 的 indexOf
        java.util.HashMap<String, Integer> indexOf = new java.util.HashMap<>(Math.max(16, n * 2));
        for (int i = 0; i < n; i++) {
            // 重复 URL 只保留首个索引，与原先 indexOf 行为一致
            Integer prev = indexOf.put(sourceUrls.get(i), i);
            if (prev != null) indexOf.put(sourceUrls.get(i), prev);
        }

        List<String> newUrls = new ArrayList<>(n);
        List<Double> newSpeeds = new ArrayList<>(n);
        java.util.HashSet<String> added = new java.util.HashSet<>(Math.max(16, n * 2));

        // 先按自定义顺序放入存在的 URL
        for (String u : orderedUrls) {
            Integer i = indexOf.get(u);
            if (i != null && added.add(u)) {
                newUrls.add(u);
                if (hasSpeeds) newSpeeds.add(speeds.get(i));
            }
        }
        // 其余未在顺序表中的保持原相对顺序追加在后
        for (int i = 0; i < n; i++) {
            String u = sourceUrls.get(i);
            if (added.add(u)) {
                newUrls.add(u);
                if (hasSpeeds) newSpeeds.add(speeds.get(i));
            }
        }
        sourceUrls = newUrls;
        urlSet = added;
        if (hasSpeeds) speeds = newSpeeds;
        sourceIndex = 0;
    }

    /**
     * 获取频道稳定唯一键（分组名 + 频道名）
     * 用于收藏 / 黑名单的持久化，避免依赖不稳定的数据库自增 id
     */
    public String getKey() {
        return (groupName == null ? "" : groupName) + "\u0001" + (channelName == null ? "" : channelName);
    }

    /** 根据速度排序源（最快的排在前面） */
    public void sortSourcesBySpeed() {
        if (sourceUrls == null || speeds == null || sourceUrls.size() != speeds.size()) return;
        // 简单冒泡排序
        for (int i = 0; i < speeds.size() - 1; i++) {
            for (int j = 0; j < speeds.size() - 1 - i; j++) {
                // 用 speedAt 取值：speeds 是 List<Double>，元素可能为 null
                // （JSON 反序列化或外部 setSpeeds 传入），直接比较/赋给 double 会拆箱 NPE
                if (speedAt(j) < speedAt(j + 1)) {
                    // 交换速度
                    Double tempSpeed = speeds.get(j);
                    speeds.set(j, speeds.get(j + 1));
                    speeds.set(j + 1, tempSpeed);
                    // 同时交换URL
                    String tempUrl = sourceUrls.get(j);
                    sourceUrls.set(j, sourceUrls.get(j + 1));
                    sourceUrls.set(j + 1, tempUrl);
                }
            }
        }
        sourceIndex = 0;
    }

    /** 安全读取速度值，null 视为 0（避免 Double 拆箱 NPE） */
    private double speedAt(int index) {
        if (speeds == null || index < 0 || index >= speeds.size()) return 0d;
        Double v = speeds.get(index);
        return v == null ? 0d : v;
    }

    /** 安全读取评分值，null 视为 0（避免 Integer 拆箱 NPE） */
    private static int scoreAt(List<Integer> scores, int index) {
        if (scores == null || index < 0 || index >= scores.size()) return 0;
        Integer v = scores.get(index);
        return v == null ? 0 : v;
    }

    /**
     * 按给定的质量评分数组重排线路（得分越高越靠前）。
     * scores 长度必须与 sourceUrls 相同。speeds 也会同步跟随。
     */
    public void sortSourcesByScores(List<Integer> scores) {
        if (sourceUrls == null || scores == null || sourceUrls.size() != scores.size()) return;
        boolean hasSpeeds = speeds != null && speeds.size() == sourceUrls.size();
        int n = scores.size();
        // 简单冒泡（源数量通常 <= 10，性能足够）
        for (int i = 0; i < n - 1; i++) {
            for (int j = 0; j < n - 1 - i; j++) {
                // scores/speeds 元素均可能为 null，统一走安全取值避免拆箱 NPE
                if (scoreAt(scores, j) < scoreAt(scores, j + 1)) {
                    Integer ts = scores.get(j);
                    scores.set(j, scores.get(j + 1));
                    scores.set(j + 1, ts);
                    String tu = sourceUrls.get(j);
                    sourceUrls.set(j, sourceUrls.get(j + 1));
                    sourceUrls.set(j + 1, tu);
                    if (hasSpeeds) {
                        Double td = speeds.get(j);
                        speeds.set(j, speeds.get(j + 1));
                        speeds.set(j + 1, td);
                    }
                }
            }
        }
        sourceIndex = 0;
    }

    // ============ Getter / Setter ============

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public String getChannelName() { return channelName; }
    public void setChannelName(String channelName) { this.channelName = channelName; }

    public String getChannelNum() { return channelNum; }
    public void setChannelNum(String channelNum) { this.channelNum = channelNum; }

    public String getGroupName() { return groupName; }
    public void setGroupName(String groupName) { this.groupName = groupName; }

    public int getSourceIndex() { return sourceIndex; }
    public void setSourceIndex(int sourceIndex) { this.sourceIndex = sourceIndex; }

    public String getEpgId() { return epgId; }
    public void setEpgId(String epgId) { this.epgId = epgId; }

    public String getLogoUrl() { return logoUrl; }
    public void setLogoUrl(String logoUrl) { this.logoUrl = logoUrl; }

    public long getLastSpeedTestTime() { return lastSpeedTestTime; }
    public void setLastSpeedTestTime(long lastSpeedTestTime) { this.lastSpeedTestTime = lastSpeedTestTime; }

    public double getBestSpeed() { return bestSpeed; }
    public void setBestSpeed(double bestSpeed) { this.bestSpeed = bestSpeed; }

    public boolean isFavorite() { return favorite; }
    public void setFavorite(boolean favorite) { this.favorite = favorite; }

    public boolean isBlacklisted() { return blacklisted; }
    public void setBlacklisted(boolean blacklisted) { this.blacklisted = blacklisted; }

    public int getSortWeight() { return sortWeight; }
    public void setSortWeight(int sortWeight) { this.sortWeight = sortWeight; }

    public String getSourceUrlsJson() { return sourceUrlsJson; }
    public void setSourceUrlsJson(String sourceUrlsJson) { this.sourceUrlsJson = sourceUrlsJson; }

    public String getSpeedsJson() { return speedsJson; }
    public void setSpeedsJson(String speedsJson) { this.speedsJson = speedsJson; }

    public String getSourceOrigin() { return sourceOrigin; }
    public void setSourceOrigin(String sourceOrigin) { this.sourceOrigin = sourceOrigin; }

    public long getLastPlayTime() { return lastPlayTime; }
    public void setLastPlayTime(long lastPlayTime) { this.lastPlayTime = lastPlayTime; }

    public String getBestResolution() { return bestResolution; }
    public void setBestResolution(String bestResolution) { this.bestResolution = bestResolution; }

    public int getBestQualityScore() { return bestQualityScore; }
    public void setBestQualityScore(int bestQualityScore) { this.bestQualityScore = bestQualityScore; }

    public List<String> getSourceUrls() { return sourceUrls; }

    /**
     * 替换整个线路列表。
     * 必须同时失效 urlSet 索引，否则后续 addSourceUrl 的去重判断会与实际列表不一致
     * （urlSet 会在下次 addSourceUrl 时按新列表懒重建）。
     */
    public void setSourceUrls(List<String> sourceUrls) {
        this.sourceUrls = sourceUrls;
        this.urlSet = null;
    }

    public List<Double> getSpeeds() { return speeds; }
    public void setSpeeds(List<Double> speeds) { this.speeds = speeds; }

    public String getCurrentEpgInfo() { return currentEpgInfo; }
    public void setCurrentEpgInfo(String currentEpgInfo) { this.currentEpgInfo = currentEpgInfo; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        LiveChannel that = (LiveChannel) o;
        return channelName != null && channelName.equals(that.channelName)
                && groupName != null && groupName.equals(that.groupName);
    }

    @Override
    public int hashCode() {
        int result = channelName != null ? channelName.hashCode() : 0;
        result = 31 * result + (groupName != null ? groupName.hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        return "LiveChannel{" +
                "channelName='" + channelName + '\'' +
                ", groupName='" + groupName + '\'' +
                ", sourceCount=" + getSourceCount() +
                ", bestSpeed=" + bestSpeed +
                '}';
    }
}
