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

    /*
     * 线路与速度列表采用"写时复制"：测速线程、数据库线程与主线程会同时访问同一频道，
     * 所有删除/重排都生成新列表后整体替换，读方拿到的旧列表不会被并发修改
     * （避免主线程 IndexOutOfBounds / ConcurrentModification 闪退）。
     * 例外：addSourceUrl 仅在频道发布给界面之前的解析/合并阶段使用，原地追加。
     */
    @Ignore
    private volatile List<String> sourceUrls;

    @Ignore
    private volatile List<Double> speeds;

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
        // 读本地快照：避免判断与取值之间列表被其它线程替换
        List<String> urls = sourceUrls;
        int idx = sourceIndex;
        if (urls != null && idx >= 0 && idx < urls.size()) {
            return urls.get(idx);
        }
        return null;
    }

    /** 切换到下一个源 */
    public boolean switchToNextSource() {
        List<String> urls = sourceUrls;
        if (urls != null && sourceIndex < urls.size() - 1) {
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
        List<String> urls = sourceUrls;
        return urls != null ? urls.size() : 0;
    }

    /**
     * 将指定 URL 的线路置顶（移动到列表首位），speeds 同步移动。
     * 置顶后当前播放源索引复位到 0。
     *
     * @return 是否发生了移动
     */
    public synchronized boolean moveSourceToTop(String url) {
        List<String> urls = sourceUrls;
        if (url == null || urls == null) return false;
        int idx = urls.indexOf(url);
        if (idx <= 0) return false; // 不存在或已在首位
        // 写时复制：生成新列表后整体替换（URL 集合不变，urlSet 无需调整）
        List<String> newUrls = new ArrayList<>(urls);
        newUrls.add(0, newUrls.remove(idx));
        List<Double> sp = speeds;
        if (sp != null && idx < sp.size()) {
            List<Double> newSpeeds = new ArrayList<>(sp);
            newSpeeds.add(0, newSpeeds.remove(idx));
            speeds = newSpeeds;
        }
        sourceUrls = newUrls;
        sourceIndex = 0;
        return true;
    }

    /**
     * 移除指定 URL 的线路（speeds 同步移除），并修正当前播放源索引。
     *
     * @return 是否成功移除
     */
    public synchronized boolean removeSource(String url) {
        List<String> urls = sourceUrls;
        if (url == null || urls == null) return false;
        int idx = urls.indexOf(url);
        if (idx < 0) return false;
        // 写时复制：生成新列表后整体替换
        List<String> newUrls = new ArrayList<>(urls);
        newUrls.remove(idx);
        List<Double> sp = speeds;
        if (sp != null && idx < sp.size()) {
            List<Double> newSpeeds = new ArrayList<>(sp);
            newSpeeds.remove(idx);
            speeds = newSpeeds;
        }
        sourceUrls = newUrls;
        // 去重索引失效，下次 addSourceUrl 时按新列表懒重建（HashSet 非线程安全，不在此原地修改）
        urlSet = null;
        // 修正当前索引，保持指向有效范围
        if (sourceIndex >= newUrls.size()) {
            sourceIndex = Math.max(0, newUrls.size() - 1);
        } else if (idx < sourceIndex) {
            sourceIndex--;
        }
        return true;
    }

    /**
     * 按给定 URL 顺序重排线路（仅重排列表中已存在的 URL，未列出的保持在其后，
     * speeds 同步跟随）。用于恢复用户自定义的线路置顶顺序。
     */
    public synchronized void reorderSourcesBy(java.util.List<String> orderedUrls) {
        // 读本地快照，下面只读旧列表、生成新列表后整体替换（写时复制）
        List<String> sourceUrls = this.sourceUrls;
        List<Double> speeds = this.speeds;
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
        // 顺序未变（每次重建展示列表都会重新应用置顶顺序）：保持当前索引，不打断正在播的线路
        if (newUrls.equals(sourceUrls)) {
            if (this.urlSet == null) this.urlSet = added;
            return;
        }
        int newIndex = remapIndex(sourceUrls, newUrls);
        this.sourceUrls = newUrls;
        this.urlSet = added;
        if (hasSpeeds) this.speeds = newSpeeds;
        sourceIndex = newIndex;
    }

    /*
     * 正在播放的频道与线路提示（由播放界面在真正加载某条线路时调用 setPlayingHint 设置）。
     * 测速排序/重排会作用于所有频道，但只有正在播放的频道需要让索引跟随播放器实际所在的 URL；
     * 其它频道应像 v1.2.3 一样回到新的首条（测速最优/置顶线路），否则下次切到它时会从旧的慢线路开始。
     * 用静态字段而非实例字段：切台时只需覆盖一次，旧频道无需手动清除；static 不参与 Room/序列化。
     */
    private static volatile String sPlayingKey;
    private static volatile String sPlayingUrl;

    /**
     * 记录当前正在播放的频道及其实际加载的线路 URL（播放界面在 initPlayer 时调用；停止播放可传 null 清除）。
     * 设置后，排序/重排仅对该频道按此 URL 重新定位索引，其它频道一律复位到 0。
     */
    public static void setPlayingHint(LiveChannel channel, String url) {
        // 先写 URL 再写 key：读方以 key 匹配为准，避免读到新 key + 旧 URL
        sPlayingUrl = channel == null ? null : url;
        sPlayingKey = channel == null ? null : channel.getKey();
    }

    /**
     * 让当前索引指向给定 URL（用于播放界面按播放器实际加载的 URL 重新同步，例如测速完成或换源前）。
     *
     * @return URL 存在于当前线路列表并已同步时返回 true
     */
    public boolean syncSourceIndexTo(String url) {
        List<String> urls = sourceUrls;
        if (url == null || urls == null) return false;
        int i = urls.indexOf(url);
        if (i < 0) return false;
        sourceIndex = i;
        return true;
    }

    /**
     * 重排后重新定位当前线路索引。
     * <p>已设置播放提示时：仅提示中正在播放的频道按其实际播放 URL 在新列表中定位（含索引 0 的情况），
     * 原线路已不在列表时回落 0；其它频道一律复位到 0（新的最优/置顶线路），与 v1.2.3 一致。
     * <p>未设置播放提示时（兼容旧调用方）：sourceIndex == 0 视为"默认从首条开始"，仍指向新的首条；
     * sourceIndex > 0 说明手动切过线路或已自动换源，按 URL 重新定位，避免"▶"标错行、换源重复/跳过线路。
     */
    private int remapIndex(List<String> oldUrls, List<String> newUrls) {
        String playingKey = sPlayingKey;
        if (playingKey != null) {
            if (!playingKey.equals(getKey())) return 0;
            String playing = sPlayingUrl;
            int ni = playing == null ? -1 : newUrls.indexOf(playing);
            return ni < 0 ? 0 : ni;
        }
        int idx = sourceIndex;
        if (idx <= 0 || oldUrls == null || idx >= oldUrls.size()) return 0;
        int ni = newUrls.indexOf(oldUrls.get(idx));
        return ni < 0 ? 0 : ni;
    }

    /**
     * 获取频道稳定唯一键（分组名 + 频道名）
     * 用于收藏 / 黑名单的持久化，避免依赖不稳定的数据库自增 id
     */
    public String getKey() {
        return (groupName == null ? "" : groupName) + "\u0001" + (channelName == null ? "" : channelName);
    }

    /**
     * 根据速度排序源（最快的排在前面，同速保持原顺序）。
     * 稳定排序，结果与原冒泡排序（仅在严格小于时交换）完全一致；
     * 生成新列表整体替换（写时复制），不在读方持有的列表上原地交换。
     */
    public synchronized void sortSourcesBySpeed() {
        List<String> urls = sourceUrls;
        List<Double> sp = speeds;
        if (urls == null || sp == null || urls.size() != sp.size()) return;
        int n = sp.size();
        // 预取排序键：speeds 元素可能为 null（JSON 反序列化或外部 setSpeeds 传入），统一视为 0 避免拆箱 NPE；
        // NaN 也视为 0，保证比较器满足全序（否则 TimSort 可能抛 "Comparison method violates its general contract"）
        final double[] keys = new double[n];
        for (int i = 0; i < n; i++) {
            double v = speedAt(sp, i);
            keys[i] = Double.isNaN(v) ? 0d : v;
        }
        applyOrder(urls, sp, sortedIndices(n, (a, b) -> {
            // 降序；与原冒泡的 "<" 判定保持一致（-0.0 与 0.0 视为相等，不用 Double.compare）
            double x = keys[a], y = keys[b];
            return x < y ? 1 : (y < x ? -1 : 0);
        }));
    }

    /** 安全读取速度值，null 视为 0（避免 Double 拆箱 NPE） */
    private static double speedAt(List<Double> speeds, int index) {
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
     * 按给定的质量评分数组重排线路（得分越高越靠前，同分保持原顺序）。
     * scores 长度必须与 sourceUrls 相同。speeds 也会同步跟随。
     * 不再修改传入的 scores 列表（调用方均为临时构造的列表）。
     */
    public synchronized void sortSourcesByScores(List<Integer> scores) {
        List<String> urls = sourceUrls;
        if (urls == null || scores == null || urls.size() != scores.size()) return;
        int n = scores.size();
        final int[] keys = new int[n];
        for (int i = 0; i < n; i++) keys[i] = scoreAt(scores, i);
        applyOrder(urls, speeds, sortedIndices(n, (a, b) -> Integer.compare(keys[b], keys[a])));
    }

    /** 生成按比较器排序后的下标序列（Collections.sort 为稳定排序） */
    private static List<Integer> sortedIndices(int n, java.util.Comparator<Integer> cmp) {
        List<Integer> order = new ArrayList<>(n);
        for (int i = 0; i < n; i++) order.add(i);
        java.util.Collections.sort(order, cmp);
        return order;
    }

    /** 按下标序列由旧列表生成新的 urls/speeds 并整体替换（写时复制；URL 集合不变，urlSet 无需调整） */
    private void applyOrder(List<String> urls, List<Double> sp, List<Integer> order) {
        if (urls == null || order.size() != urls.size()) return;
        boolean hasSpeeds = sp != null && sp.size() == urls.size();
        List<String> newUrls = new ArrayList<>(urls.size());
        List<Double> newSpeeds = hasSpeeds ? new ArrayList<>(urls.size()) : null;
        for (int i : order) {
            newUrls.add(urls.get(i));
            if (hasSpeeds) newSpeeds.add(sp.get(i));
        }
        // 测速排序可能作用于正在播放的频道：仅该频道按实际播放 URL 重新定位，其它频道复位到最优线路（见 remapIndex）
        int newIndex = remapIndex(urls, newUrls);
        sourceUrls = newUrls;
        if (hasSpeeds) speeds = newSpeeds;
        sourceIndex = newIndex;
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
