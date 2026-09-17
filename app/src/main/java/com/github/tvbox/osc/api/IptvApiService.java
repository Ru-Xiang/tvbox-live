package com.github.tvbox.osc.api;

import com.github.tvbox.osc.bean.ChannelGroup;
import com.github.tvbox.osc.bean.LiveChannel;
import com.github.tvbox.osc.util.ChannelNameMatcher;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.IpVersionFilter;
import com.github.tvbox.osc.api.LiveSourceParser;
import com.orhanobut.hawk.Hawk;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.json.JSONObject;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import timber.log.Timber;

/**
 * 直播源聚合服务
 *
 * 直播源来自"订阅列表 + 自定义源"合并去重后的整体订阅源，
 * 内容格式自动识别（TXT / M3U），并按所选 IP 版本过滤线路。
 */
public class IptvApiService {

    private static volatile IptvApiService instance;
    private final OkHttpClient httpClient;
    private final LiveSourceParser parser;
    private final AliasService aliasService = new AliasService();

    /**
     * 是否已有一次聚合任务在执行中。
     *
     * 聚合过程是数十秒的大批量 HTTP 拉取 + 解析，用户连点"刷新直播源"或
     * 播放页与设置页同时触发时会创建多个线程并行跑同样的活，
     * 在低内存盒子上直接叠加内存峰值导致 OOM。这里做并发拦截，同一时刻只跑一个。
     */
    private final java.util.concurrent.atomic.AtomicBoolean fetching =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** 展开多仓订阅时的子仓并发下载数（子仓多且常有失效地址，需并发+限流） */
    private static final int REPO_FETCH_CONCURRENCY = 8;
    /** 单个子仓下载的最长等待时间（秒） */
    private static final long REPO_FETCH_TIMEOUT_SEC = 12L;
    /** 单个子仓配置的最大读取字节数，防止超大 JSON 撑爆低内存设备 */
    private static final int MAX_REPO_BYTES = 2 * 1024 * 1024;
    /** 单个源地址被识别为 TVBox 配置时，最多内联展开抓取的直播源个数 */
    private static final int MAX_INLINE_LIVES = 5;

    /**
     * 低内存设备判定阈值：堆上限 ≤ 128MB 视为低内存（多数 1GB RAM 的电视盒子都在此列）。
     *
     * <p>这类设备在聚合数十个外部源、每个源内容动辄数 MB 时，堆峰值极易触顶；
     * 一旦触顶，系统会在拉源阶段直接杀掉进程，表现为"正在获取直播源"时闪退，
     * <b>且没有 Java 崩溃日志</b>（属于系统级杀进程，不是未捕获异常）。
     */
    private static final long LOW_MEMORY_HEAP_THRESHOLD = 128L * 1024 * 1024;

    /** 单个直播源正文最大字节数：正常设备 / 低内存设备 */
    private static final int MAX_SOURCE_BYTES = 8 * 1024 * 1024;
    private static final int MAX_SOURCE_BYTES_LOW = 2 * 1024 * 1024;

    /** 仓库/子仓配置最大字节数：低内存设备进一步收紧 */
    private static final int MAX_REPO_BYTES_LOW = 512 * 1024;

    /** 低内存设备上子仓并发展开的最大线程数（正常设备用 {@link #REPO_FETCH_CONCURRENCY}） */
    private static final int REPO_FETCH_CONCURRENCY_LOW = 3;

    /** 获取状态回调接口 */
    public interface FetchCallback {
        void onSuccess(List<ChannelGroup> groups, int totalChannels);
        void onError(String errorMsg);
        void onProgress(int current, int total, String message);
    }

    private IptvApiService() {
        // 复用全局共享客户端，避免每个服务各自持有一套连接池与 Dispatcher 线程池
        httpClient = com.github.tvbox.osc.util.HttpClients.shared();
        parser = new LiveSourceParser();
    }

    public static IptvApiService getInstance() {
        if (instance == null) {
            synchronized (IptvApiService.class) {
                if (instance == null) {
                    instance = new IptvApiService();
                }
            }
        }
        return instance;
    }

    /**
     * 从指定URL获取直播源
     *
     * @param url    数据源URL
     * @param format 期望格式（m3u/txt/auto），实际会自动识别内容格式
     * @return 频道分组列表
     */
    public List<ChannelGroup> fetchFromUrl(String url, String format) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", "TVBoxOS-Live/1.0")
                .header("Accept", "*/*")
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("请求失败: HTTP " + response.code() + " " + response.message());
            }

            // 有上限地读取响应体，避免个别源返回超大/异常内容导致 OOM（尤其在低内存电视上）
            String body = readBodyLimited(response, sourceBodyLimit());
            if (body.isEmpty()) {
                throw new IOException("返回数据为空");
            }

            Timber.d("获取到直播源数据，大小: %d 字节", body.length());

            // 兜底：若该地址实际是 TVBox 单仓配置（订阅列表里混入的情况），
            // 直接按 M3U/TXT 解析会得到 0 个频道。这里就地展开其 lives
            // 并抓取第一个可用的直播源，避免整条源白白浪费。
            if (MultiRepoParser.looksLikeTvBoxConfig(body)) {
                List<ChannelGroup> fromRepo = fetchFromTvBoxConfig(body, url);
                if (!fromRepo.isEmpty()) return fromRepo;
                // 展开失败则继续按普通格式尝试解析
            }

            // 自动识别格式
            String detectedFormat = autoDetectFormat(body, format);
            return parseContent(body, detectedFormat);
        }
    }

    /**
     * 把 TVBox 配置地址就地展开并抓取其中的直播源。
     *
     * <p>用于「某个源地址其实是仓库配置」的兜底场景。只抓取前若干个 lives
     * 地址并合并，避免单个配置无限展开拖慢整体加载。
     */
    private List<ChannelGroup> fetchFromTvBoxConfig(String body, String baseUrl) {
        List<ChannelGroup> merged = new ArrayList<>();
        try {
            List<String> lives = MultiRepoParser.extractLiveUrls(body, baseUrl);
            if (lives.isEmpty()) return merged;
            int limit = Math.min(lives.size(), MAX_INLINE_LIVES);
            for (int i = 0; i < limit; i++) {
                String liveUrl = lives.get(i);
                try {
                    String content = fetchTextLimited(liveUrl, repoBodyLimit());
                    if (content == null || content.trim().isEmpty()) continue;
                    List<ChannelGroup> groups =
                            parseContent(content, autoDetectFormat(content, "auto"));
                    if (!groups.isEmpty()) mergeGroups(merged, groups, liveUrl);
                } catch (Exception e) {
                    Timber.d("内联直播源获取失败(%s): %s", liveUrl, e.getMessage());
                }
            }
            if (!merged.isEmpty()) {
                Timber.i("源 %s 为 TVBox 配置，内联展开得到 %d 个分组", baseUrl, merged.size());
            }
        } catch (Exception e) {
            Timber.w(e, "内联展开 TVBox 配置失败");
        }
        return merged;
    }

    // ============ 低内存自适应 ============

    /** 是否低内存设备（堆上限 ≤ 128MB，如多数 1GB RAM 的电视盒子） */
    private static boolean isLowMemoryDevice() {
        return Runtime.getRuntime().maxMemory() <= LOW_MEMORY_HEAP_THRESHOLD;
    }

    /** 单个直播源正文的读取上限：低内存设备显著收紧 */
    private int sourceBodyLimit() {
        return isLowMemoryDevice() ? MAX_SOURCE_BYTES_LOW : MAX_SOURCE_BYTES;
    }

    /** 仓库/子仓配置的读取上限：低内存设备显著收紧 */
    private int repoBodyLimit() {
        return isLowMemoryDevice() ? MAX_REPO_BYTES_LOW : MAX_REPO_BYTES;
    }

    /**
     * 堆内存是否吃紧。
     *
     * <p>聚合数十个外部源时，已解析的频道与线路会持续驻留堆上，可用空间单调下降。
     * 等到真正 OOM 时已经来不及（且系统级杀进程不会有崩溃日志），
     * 因此在每个源处理前主动探测，触线即提前收尾，宁可少拉几个源也要保住进程。
     */
    private boolean isHeapCritical() {
        Runtime rt = Runtime.getRuntime();
        long max = rt.maxMemory();
        long available = max - rt.totalMemory() + rt.freeMemory();
        return available < Math.min(8L * 1024 * 1024, max / 8);
    }

    /** 有上限地读取响应体为字符串（UTF-8），超过 maxBytes 即截断，防止 OOM */
    private String readBodyLimited(Response response, int maxBytes) throws IOException {
        if (response.body() == null) return "";
        try (java.io.InputStream in = response.body().byteStream()) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                if (bos.size() + n > maxBytes) {
                    bos.write(buf, 0, maxBytes - bos.size());
                    Timber.w("源内容过大，已截断至 %d MB", maxBytes / 1024 / 1024);
                    break;
                }
                bos.write(buf, 0, n);
            }
            return bos.toString("UTF-8");
        }
    }

    /**
     * 下载文本内容并限制最大读取字节数。
     *
     * <p>用于抓取子仓配置：多仓里的子仓 JSON 大小不可控（实测有 100KB+ 的），
     * 且部分地址会返回网页或大文件，必须限流。
     * 单个子仓使用更短的超时，避免失效地址拖慢整体展开。
     */
    private String fetchTextLimited(String url, int maxBytes) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", "okhttp/4.9.0")
                .build();
        // 子仓用独立的短超时：派生自共享客户端，复用连接池，开销极小
        OkHttpClient client = httpClient.newBuilder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code());
            }
            return readBodyLimited(response, maxBytes);
        }
    }

    /**
     * 从多个来源聚合直播源
     * 直播源来自"订阅列表 + 多仓链接 + 自定义源"合并的整体订阅源
     */
    public void fetchAndMergeSources(FetchCallback callback) {
        // 并发拦截：已有任务在跑时直接回调错误，不再开新线程
        if (!fetching.compareAndSet(false, true)) {
            Timber.w("已有直播源聚合任务在执行，忽略本次请求");
            if (callback != null) {
                try { callback.onError("正在获取直播源，请稍候"); } catch (Throwable ignore) {}
            }
            return;
        }
        Thread worker = new Thread(() -> {
            try {
                com.github.tvbox.osc.util.RunLog.i("fetchAndMergeSources: 开始");
                // 0) 加载频道改名（别名）规则，解析阶段即按规则改名
                loadAliasRules(callback);

                List<ChannelGroup> allGroups = new ArrayList<>();

                // 记录每个"可删除源"（自定义源 / 订阅源）产出的 URL 集合，用于入选判定
                Map<String, Set<String>> sourceUrlMap = new LinkedHashMap<>();

                // 自定义源（其中的多仓链接会被展开为具体直播源）
                List<String> customUrls = expandCustomSources(
                        parseCustomSourceUrls(Hawk.get(HawkConfig.IPTV_CUSTOM_SOURCES, "")), callback);
                com.github.tvbox.osc.util.RunLog.i("自定义源展开完成: " + customUrls.size() + " 个");
                // 多仓链接（配置页/手机配置页显式指定，其中的子仓会被展开为具体直播源）
                List<String> multiRepoUrls = expandCustomSources(
                        parseCustomSourceUrls(Hawk.get(HawkConfig.IPTV_MULTI_REPO_URLS, "")), callback);
                com.github.tvbox.osc.util.RunLog.i("多仓链接展开完成: " + multiRepoUrls.size() + " 个");
                // 订阅列表展开的源
                List<String> subscribeUrls = loadSubscribeList(callback);
                com.github.tvbox.osc.util.RunLog.i("订阅列表展开完成: " + subscribeUrls.size() + " 个");

                // 所有"外部源"（自定义 + 多仓 + 订阅），它们都参与连续未入选自动删除统计
                List<String> externalUrls = new ArrayList<>();
                externalUrls.addAll(customUrls);
                for (String u : multiRepoUrls) {
                    if (!externalUrls.contains(u)) externalUrls.add(u);
                }
                for (String u : subscribeUrls) {
                    if (!externalUrls.contains(u)) externalUrls.add(u);
                }

                com.github.tvbox.osc.util.RunLog.i("待拉取外部源合计: " + externalUrls.size() + " 个"
                        + "（低内存设备=" + isLowMemoryDevice() + "，单源上限="
                        + sourceBodyLimit() / 1024 + "KB）");
                int totalSteps = externalUrls.size();
                int step = 0;

                // 直播源仅来自外部源（自定义 + 订阅列表）合并的整体订阅源，
                // 内容格式自动识别（TXT / M3U），无需再从 IPTV-API 拉取
                for (String srcUrl : externalUrls) {
                    // 内存兜底阀：可用堆触线时提前收尾，用已拉到的源继续后续流程。
                    // 否则在低内存盒子上会一路拉到被系统杀掉，表现为"正在获取直播源"时闪退。
                    if (step > 0 && isHeapCritical()) {
                        Timber.w("可用内存不足，提前结束拉源（已处理 %d/%d 个源）", step, totalSteps);
                        try {
                            if (callback != null) {
                                callback.onProgress(totalSteps, totalSteps, "内存不足，已提前结束拉源");
                            }
                        } catch (Throwable ignore) {
                        }
                        break;
                    }
                    // 进度按"即将处理第 step+1 个"展示，与总数对齐更直观。
                    // 消息里不再重复计数：UI 会自行附加 (current/total)
                    if (callback != null) {
                        callback.onProgress(step + 1, totalSteps, "正在获取直播源");
                    }
                    try {
                        com.github.tvbox.osc.util.RunLog.i("拉源 [" + (step + 1) + "/" + totalSteps + "] "
                                + srcUrl);
                        List<ChannelGroup> srcGroups = fetchFromUrl(srcUrl, "auto");
                        // 收集该源贡献的所有 URL
                        Set<String> urls = new HashSet<>();
                        for (ChannelGroup g : srcGroups) {
                            for (LiveChannel ch : g.getChannels()) {
                                if (ch.getSourceUrls() != null) urls.addAll(ch.getSourceUrls());
                            }
                        }
                        sourceUrlMap.put(srcUrl, urls);
                        mergeGroups(allGroups, srcGroups, srcUrl);
                    } catch (Exception e) {
                        Timber.w(e, "获取源失败: %s", srcUrl);
                        sourceUrlMap.put(srcUrl, new HashSet<>()); // 拉取失败视为未入选
                    }
                    step++;
                }

                // 应用频道列表模板（过滤+排序+合并多源），否则仅按最大线路数裁剪
                List<ChannelGroup> finalGroups = applyTemplateAndLimit(allGroups, callback, totalSteps);

                // 应用频道去重策略（按 名称 / URL / 名称+URL）
                int dedupMode = Hawk.get(HawkConfig.CHANNEL_DEDUP_MODE, HawkConfig.DEDUP_MODE_NAME);
                com.github.tvbox.osc.util.ChannelDeduper.dedup(finalGroups, dedupMode);

                // 剔除播放器不支持的协议（rtp/udp/igmp/rtsp/rtmp 等）。
                // 必须在测速前执行：这些线路必然播放失败，不应占用测速配额与列表位置。
                com.github.tvbox.osc.util.ProtocolFilter.filter(finalGroups);

                // 按 IP 版本过滤线路：
                // ipv4 -> 仅保留 IPv4/域名线路；ipv6 -> 仅保留 IPv6 线路；all -> 混合不过滤。
                // 过滤在测速前进行，使测速与生成的本地列表仅包含所选 IP 类型的线路。
                String ipVersion = Hawk.get(HawkConfig.IPTV_IP_VERSION, IpVersionFilter.ALL);
                com.github.tvbox.osc.util.IpVersionFilter.filter(finalGroups, ipVersion);

                // 为缺少台标的频道自动生成 logo 地址（前缀 + 频道名 + .png）
                applyLogoPrefix(finalGroups);

                // 统计最终保留的所有线路 URL
                Set<String> finalUrls = new HashSet<>();
                int totalChannels = 0;
                for (ChannelGroup group : finalGroups) {
                    totalChannels += group.getChannelCount();
                    for (LiveChannel ch : group.getChannels()) {
                        if (ch.getSourceUrls() != null) finalUrls.addAll(ch.getSourceUrls());
                    }
                }

                // 更新连续未入选计数，并自动删除连续 N 次失败的自定义源
                updateSourceFailCounts(customUrls, subscribeUrls, sourceUrlMap, finalUrls);

                Hawk.put(HawkConfig.IPTV_LAST_UPDATE_TIME, System.currentTimeMillis());

                if (callback != null) {
                    try {
                        callback.onProgress(totalSteps, totalSteps, "全部加载完成");
                    } catch (Throwable ignore) {
                        Timber.w("拉源完成进度回调异常");
                    }
                    // onSuccess 单独兜住：消费方（如测速准备阶段汇总全量频道）在低内存
                    // 设备上可能抛 OOM；若落到外层 catch 会再次回调 onError 并重建列表，
                    // 造成二次 OOM 逃逸到本线程，直接导致进程崩溃。这里就地消化，避免重复回调。
                    try {
                        callback.onSuccess(finalGroups, totalChannels);
                    } catch (Throwable t) {
                        Timber.e(t, "处理直播源结果回调异常");
                    }
                }
            } catch (Throwable e) {
                // 覆盖 OOM 等 Error，避免拉源失败连锁杀 App
                Timber.e(e, "聚合直播源失败");
                com.github.tvbox.osc.util.RunLog.i("fetchAndMergeSources: 失败 " + e);
                if (callback != null) {
                    // 必须兜住 Throwable：仅 catch(Exception) 时 Error 会逃逸到
                    // "IptvFetch" 线程成为未捕获异常，直接导致 App 崩溃。
                    try { callback.onError("获取直播源失败: " + e.getMessage()); } catch (Throwable ignore) {}
                }
            } finally {
                // 无论成功失败都必须复位，否则后续刷新会被永久拦截
                fetching.set(false);
            }
        }, "IptvFetch");
        // 设为守护线程：进程退出时不被这个长耗时任务拖住
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * 判断是否需要更新直播源
     */
    public boolean needsUpdate() {
        boolean autoUpdate = Hawk.get(HawkConfig.IPTV_AUTO_UPDATE, true);
        if (!autoUpdate) return false;

        long lastUpdate = Hawk.get(HawkConfig.IPTV_LAST_UPDATE_TIME, 0L);
        int intervalHours = Hawk.get(HawkConfig.IPTV_UPDATE_INTERVAL, HawkConfig.DEFAULT_UPDATE_INTERVAL);
        long intervalMillis = intervalHours * 3600L * 1000L;

        return (System.currentTimeMillis() - lastUpdate) >= intervalMillis;
    }

    // ============ 私有方法 ============

    /**
     * 自动识别直播源格式
     */
    private String autoDetectFormat(String content, String defaultFormat) {
        // "auto" 表示完全自动识别，无法判定时兜底为 txt
        if (defaultFormat == null || "auto".equals(defaultFormat)) defaultFormat = "txt";
        if (content == null || content.isEmpty()) return defaultFormat;
        String trimmed = content.trim();

        // M3U 格式以 #EXTM3U 开头
        if (trimmed.startsWith("#EXTM3U")) {
            return "m3u";
        }

        // 包含 #EXTINF 标签也是 M3U
        if (trimmed.contains("#EXTINF")) {
            return "m3u";
        }

        // TXT 格式通常包含逗号分隔的频道名和URL
        if (trimmed.contains(",#genre#") || trimmed.matches("(?s).*\\w+,https?://.*")) {
            return "txt";
        }

        return defaultFormat;
    }

    /**
     * 解析直播源内容，并按别名规则对频道改名
     */
    private List<ChannelGroup> parseContent(String content, String format) {
        List<ChannelGroup> groups = "m3u".equals(format)
                ? parser.parseM3U(content) : parser.parseTxt(content);
        applyAliasRename(groups);
        return groups;
    }

    /** 对解析出的频道按别名规则改名（改名后同名频道在合并阶段会被聚合） */
    private void applyAliasRename(List<ChannelGroup> groups) {
        if (!aliasService.isLoaded() || groups == null) return;
        for (ChannelGroup group : groups) {
            for (LiveChannel ch : group.getChannels()) {
                String newName = aliasService.resolve(ch.getChannelName());
                if (newName != null && !newName.equals(ch.getChannelName())) {
                    ch.setChannelName(newName);
                }
            }
        }
    }

    /**
     * 合并频道分组
     * 相同名称的分组会合并频道，相同名称的频道会合并播放源
     *
     * <p><b>性能说明：</b>本方法在每个外部源上都会调用一次。原先用"线性扫描分组 +
     * 线性扫描分组内频道并对每个频道做模糊匹配"的方式，复杂度为
     * O(源数 × 分组内频道数²) 且每次比较都触发频道名归一化，
     * 上千频道时会产生百万级比较导致加载卡死。
     * 现改为用两级 HashMap 索引（分组名 → 分组、归一化频道名 → 频道），
     * 使单次合并降为 O(待合并频道数)。
     */
    private void mergeGroups(List<ChannelGroup> target, List<ChannelGroup> source, String origin) {
        if (source == null || source.isEmpty()) return;

        // 分组名 → 目标分组
        Map<String, ChannelGroup> groupIndex = new java.util.HashMap<>(Math.max(16, target.size() * 2));
        for (ChannelGroup g : target) {
            if (g == null || g.getGroupName() == null) continue;
            if (!groupIndex.containsKey(g.getGroupName())) {
                groupIndex.put(g.getGroupName(), g);
            }
        }

        // 目标分组 → (归一化频道名 → 频道)，按需构建并在合并过程中持续复用
        Map<ChannelGroup, Map<String, LiveChannel>> channelIndexCache =
                new java.util.HashMap<>(Math.max(16, target.size() * 2));

        for (ChannelGroup srcGroup : source) {
            if (srcGroup == null) continue;
            ChannelGroup existingGroup = srcGroup.getGroupName() == null
                    ? null : groupIndex.get(srcGroup.getGroupName());

            if (existingGroup == null) {
                // 新分组，标记来源
                for (LiveChannel ch : srcGroup.getChannels()) {
                    if (ch != null) ch.setSourceOrigin(origin);
                }
                target.add(srcGroup);
                if (srcGroup.getGroupName() != null) {
                    groupIndex.put(srcGroup.getGroupName(), srcGroup);
                }
                continue;
            }

            // 合并到已有分组（用归一化名索引关联同一频道）
            Map<String, LiveChannel> channelIndex = channelIndexCache.get(existingGroup);
            if (channelIndex == null) {
                channelIndex = buildChannelIndex(existingGroup);
                channelIndexCache.put(existingGroup, channelIndex);
            }

            for (LiveChannel srcChannel : srcGroup.getChannels()) {
                if (srcChannel == null) continue;
                String key = ChannelNameMatcher.normalizeKey(srcChannel.getChannelName());
                LiveChannel existingChannel = key.isEmpty() ? null : channelIndex.get(key);
                if (existingChannel == null) {
                    srcChannel.setSourceOrigin(origin);
                    existingGroup.addChannel(srcChannel);
                    if (!key.isEmpty()) channelIndex.put(key, srcChannel);
                } else {
                    // 合并播放源
                    List<String> urls = srcChannel.getSourceUrls();
                    if (urls != null) {
                        for (String url : urls) {
                            existingChannel.addSourceUrl(url);
                        }
                    }
                }
            }
        }
    }

    /** 构建"归一化频道名 → 频道"索引（同名冲突时保留首个，与原线性查找行为一致） */
    private Map<String, LiveChannel> buildChannelIndex(ChannelGroup group) {
        List<LiveChannel> channels = group.getChannels();
        Map<String, LiveChannel> index = new java.util.HashMap<>(Math.max(16, channels.size() * 2));
        for (LiveChannel ch : channels) {
            if (ch == null) continue;
            String key = ChannelNameMatcher.normalizeKey(ch.getChannelName());
            if (key.isEmpty()) continue;
            if (!index.containsKey(key)) index.put(key, ch);
        }
        return index;
    }

    /**
     * 为缺少台标的频道自动生成 logo 地址：前缀 + 频道名 + ".png"。
     * 已带 tvg-logo 的频道保持不变；前缀为空则跳过。
     */
    private void applyLogoPrefix(List<ChannelGroup> groups) {
        if (groups == null || groups.isEmpty()) return;
        String prefix = Hawk.get(HawkConfig.IPTV_LOGO_PREFIX, HawkConfig.DEFAULT_IPTV_LOGO_PREFIX);
        if (prefix == null || prefix.trim().isEmpty()) return;
        final String base = prefix.trim().endsWith("/") ? prefix.trim() : prefix.trim() + "/";
        int filled = 0;
        for (ChannelGroup group : groups) {
            if (group == null || group.getChannels() == null) continue;
            for (LiveChannel ch : group.getChannels()) {
                if (ch == null) continue;
                String logo = ch.getLogoUrl();
                if (logo != null && !logo.trim().isEmpty()) continue;
                String name = ch.getChannelName();
                if (name == null || name.trim().isEmpty()) continue;
                try {
                    String encoded = java.net.URLEncoder.encode(name.trim(), "UTF-8")
                            .replace("+", "%20");
                    ch.setLogoUrl(base + encoded + ".png");
                    filled++;
                } catch (Exception ignore) {
                    ch.setLogoUrl(base + name.trim() + ".png");
                    filled++;
                }
            }
        }
        Timber.i("已为 %d 个频道自动生成台标 logo", filled);
    }

    /**
     * 应用频道列表模板与最大线路数限制。
     * - 若启用模板：从模板地址拉取并按模板过滤/排序/合并多源（内部已应用线路数限制）
     * - 否则：仅对每个频道按最大线路数裁剪
     */
    private List<ChannelGroup> applyTemplateAndLimit(List<ChannelGroup> groups, FetchCallback callback, int totalSteps) {
        boolean templateEnabled = Hawk.get(HawkConfig.IPTV_TEMPLATE_ENABLED, true);
        String templateUrl = Hawk.get(HawkConfig.IPTV_TEMPLATE_URL, "");

        if (templateEnabled) {
            try {
                if (callback != null) callback.onProgress(totalSteps, totalSteps, "正在应用频道模板...");
                String content = loadTemplateContent(templateUrl);
                ChannelTemplate template = ChannelTemplate.parse(content);
                if (!template.isEmpty()) {
                    // 传0 表示不限制线路数：测速需要覆盖全部线路才能找出最佳，
                    // “单频道最多线路”仅在展示阶段(rebuildDisplayGroups)生效。
                    List<ChannelGroup> result = template.apply(groups, 0);
                    Timber.i("已应用频道模板: %d 个分组", result.size());
                    return result;
                }
                Timber.w("频道模板为空，忽略");
            } catch (Exception e) {
                Timber.w(e, "获取/应用频道模板失败，使用原始列表");
            }
        }

        // 不在此处按urlsLimit 裁剪：保留全部线路供测速使用
        return groups;
    }

    /**
     * 加载模板内容：
     * - 地址为空 / "demo" / "default" → 使用内置 assets 模板
     * - http(s) 链接 → 远程拉取
     * - 其它（本地内容）→ 直接当作模板文本
     */
    private String loadTemplateContent(String templateUrl) throws IOException {
        String url = templateUrl == null ? "" : templateUrl.trim();
        if (url.isEmpty() || "demo".equalsIgnoreCase(url) || "default".equalsIgnoreCase(url)) {
            return readAssetTemplate();
        }
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return fetchPlainText(url);
        }
        // 兜底：当作内置模板
        return readAssetTemplate();
    }

    /** 读取内置 assets 模板 */
    private String readAssetTemplate() throws IOException {
        // 用 try-with-resources 确保异常路径下 AssetManager 的文件描述符也能释放：
        // 原实现把 is.close() 放在正常路径上，read/write 抛异常时描述符会永久泄漏
        try (java.io.InputStream is = com.github.tvbox.osc.App.getInstance()
                .getAssets().open("demo_template.txt")) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
            return bos.toString("UTF-8");
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            // 保留原始异常作为 cause，便于定位真实失败原因
            throw new IOException("读取内置模板失败: " + e.getMessage(), e);
        }
    }

    /**
     * 加载频道改名（别名）规则
     */
    private void loadAliasRules(FetchCallback callback) {
        String aliasUrl = Hawk.get(HawkConfig.IPTV_ALIAS_URL, HawkConfig.DEFAULT_ALIAS_URL);
        if (aliasUrl == null || aliasUrl.trim().isEmpty()) {
            com.github.tvbox.osc.util.RunLog.i("loadAliasRules: 未配置改名规则地址，跳过");
            return;
        }
        try {
            // total=0 表示"总数未知"，避免 UI 把 (0/1) 当作有效进度显示，
            // 导致随后进入拉源阶段时进度条从满格跳回小比例
            if (callback != null) callback.onProgress(0, 0, "正在加载频道改名规则...");
            com.github.tvbox.osc.util.RunLog.i("loadAliasRules: 开始下载 " + aliasUrl.trim());
            String content = fetchPlainText(aliasUrl.trim());
            com.github.tvbox.osc.util.RunLog.i("loadAliasRules: 下载完成 "
                    + (content == null ? 0 : content.length()) + " 字符，开始解析");
            aliasService.parse(content);
            com.github.tvbox.osc.util.RunLog.i("loadAliasRules: 解析完成");
        } catch (Exception e) {
            Timber.w(e, "加载别名规则失败，跳过改名");
            com.github.tvbox.osc.util.RunLog.i("loadAliasRules: 失败(可忽略) " + e);
        }
    }

    /**
     * 加载订阅列表并展开为源 URL 列表（每行一个 URL）
     */
    private List<String> loadSubscribeList(FetchCallback callback) {
        List<String> urls = new ArrayList<>();
        String listUrl = Hawk.get(HawkConfig.IPTV_SUBSCRIBE_LIST_URL, HawkConfig.DEFAULT_SUBSCRIBE_LIST_URL);
        if (listUrl == null || listUrl.trim().isEmpty()) return urls;
        try {
            // total=0 表示"总数未知"（此刻还不知道订阅列表里有多少个源）
            if (callback != null) callback.onProgress(0, 0, "正在加载订阅列表...");
            String content = fetchPlainText(listUrl.trim());

            // 订阅列表本身可能是 TVBox 多仓/单仓 JSON，需要先展开出直播源地址
            if (MultiRepoParser.looksLikeTvBoxConfig(content)) {
                List<String> expanded = expandTvBoxConfig(content, listUrl.trim(), callback);
                for (String u : expanded) {
                    if (!urls.contains(u)) urls.add(u);
                }
                Timber.i("订阅列表(多仓)展开完成: %d 个直播源", urls.size());
                return urls;
            }

            // 纯文本列表：每行一个直播源地址
            for (String raw : content.split("\\r?\\n")) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                // 修正可能存在的 Markdown 转义残留
                line = line.replace("\\_", "_");
                if (line.startsWith("http://") || line.startsWith("https://")) {
                    if (!urls.contains(line)) urls.add(line);
                }
            }
            Timber.i("订阅列表加载完成: %d 个源", urls.size());
        } catch (Exception e) {
            Timber.w(e, "加载订阅列表失败");
        }
        return urls;
    }

    /**
     * 把 TVBox 配置展开为直播源地址列表。
     *
     * <p>支持两种入口：
     * <ul>
     *   <li><b>多仓</b>（含 {@code urls} 字段）：逐个下载子仓配置，再从各自的
     *       {@code lives} 中提取直播源地址；</li>
     *   <li><b>单仓</b>（直接含 {@code lives}）：直接提取。</li>
     * </ul>
     *
     * <p>子仓下载采用并发 + 独立超时：多仓常包含数十个子仓，其中不少已失效，
     * 串行下载会让"获取订阅列表"这一步卡住数分钟。单个子仓失败只跳过它，
     * 不影响其余子仓。
     *
     * @param content 配置正文
     * @param baseUrl 配置自身地址，用于解析相对路径
     */
    private List<String> expandTvBoxConfig(String content, String baseUrl, FetchCallback callback) {
        List<String> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        // 入口本身就是单仓：直接取 lives
        for (String u : MultiRepoParser.extractLiveUrls(content, baseUrl)) {
            seen.add(u);
        }

        List<String> repoUrls = MultiRepoParser.extractRepoUrls(content, baseUrl);
        if (repoUrls.isEmpty()) {
            result.addAll(seen);
            return result;
        }

        Timber.i("多仓配置包含 %d 个子仓，开始并发展开", repoUrls.size());
        if (callback != null) {
            callback.onProgress(0, 0, "正在展开多仓订阅(" + repoUrls.size() + " 个仓库)");
        }

        // 低内存设备降低并发：并发下载会让 N 份配置正文同时驻留堆上，是拉源阶段的主要峰值来源
        int maxThreads = isLowMemoryDevice() ? REPO_FETCH_CONCURRENCY_LOW : REPO_FETCH_CONCURRENCY;
        int threads = Math.min(maxThreads, Math.max(1, repoUrls.size()));
        ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "RepoFetch");
            t.setDaemon(true);
            return t;
        });
        // 子仓解析结果按提交顺序收集，保证每次展开的源顺序稳定
        List<Future<List<String>>> futures = new ArrayList<>(repoUrls.size());
        try {
            for (final String repoUrl : repoUrls) {
                futures.add(pool.submit(() -> {
                    try {
                        String body = fetchTextLimited(repoUrl, repoBodyLimit());
                        return MultiRepoParser.extractLiveUrls(body, repoUrl);
                    } catch (Throwable t) {
                        // 失效子仓很常见，降级为 debug 日志避免刷屏
                        Timber.d("子仓获取失败(%s): %s", repoUrl, t.getMessage());
                        return new ArrayList<String>();
                    }
                }));
            }
            for (Future<List<String>> f : futures) {
                try {
                    List<String> lives = f.get(REPO_FETCH_TIMEOUT_SEC, TimeUnit.SECONDS);
                    if (lives != null) seen.addAll(lives);
                } catch (Exception e) {
                    // 单个子仓超时/异常不影响整体
                    f.cancel(true);
                }
            }
        } finally {
            pool.shutdownNow();
        }

        result.addAll(seen);
        Timber.i("多仓展开得到 %d 个直播源", result.size());
        return result;
    }

    /**
     * 更新外部源的连续未入选计数；自定义源连续达到上限则自动删除。
     *
     * @param customUrls    用户自定义源（可被自动删除）
     * @param subscribeUrls 订阅列表源（仅统计，不删除原始列表，删除无意义）
     * @param sourceUrlMap  每个源产出的 URL 集合
     * @param finalUrls     最终保留的所有线路 URL
     */
    private void updateSourceFailCounts(List<String> customUrls, List<String> subscribeUrls,
                                        Map<String, Set<String>> sourceUrlMap, Set<String> finalUrls) {
        JSONObject counts;
        try {
            counts = new JSONObject(Hawk.get(HawkConfig.IPTV_SOURCE_FAIL_COUNT, "{}"));
        } catch (Exception e) {
            counts = new JSONObject();
        }

        Set<String> customSet = new HashSet<>(customUrls);
        List<String> toRemove = new ArrayList<>();

        // 遍历所有外部源，判断是否"入选"（其产出 URL 与最终结果有交集）
        Set<String> allExternal = new HashSet<>(sourceUrlMap.keySet());
        for (String src : allExternal) {
            boolean selected = false;
            Set<String> produced = sourceUrlMap.get(src);
            if (produced != null) {
                for (String u : produced) {
                    if (finalUrls.contains(u)) { selected = true; break; }
                }
            }

            if (selected) {
                counts.remove(src); // 入选则清零
            } else {
                int c = counts.optInt(src, 0) + 1;
                try { counts.put(src, c); } catch (Exception ignored) {}
                // 仅对用户自定义源执行自动删除（订阅列表为远程文件，本地删除无意义）
                if (customSet.contains(src) && c >= HawkConfig.MAX_SOURCE_FAIL_COUNT) {
                    toRemove.add(src);
                }
            }
        }

        // 执行自动删除
        if (!toRemove.isEmpty()) {
            List<String> remain = new ArrayList<>();
            for (String u : customUrls) {
                if (!toRemove.contains(u)) remain.add(u);
            }
            Hawk.put(HawkConfig.IPTV_CUSTOM_SOURCES, buildCustomSourcesJson(remain));
            for (String u : toRemove) counts.remove(u);
            Timber.i("自动删除连续 %d 次未入选的自定义源: %s",
                    HawkConfig.MAX_SOURCE_FAIL_COUNT, toRemove);
        }

        Hawk.put(HawkConfig.IPTV_SOURCE_FAIL_COUNT, counts.toString());
    }

    /** 把自定义源列表序列化为 JSON 数组字符串 */
    private String buildCustomSourcesJson(List<String> urls) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < urls.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(urls.get(i).replace("\"", "")).append("\"");
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * 拉取纯文本（用于频道模板）
     */
    private String fetchPlainText(String url) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", "TVBoxOS-Live/1.0")
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("模板请求失败: HTTP " + response.code());
            }
            return response.body().string();
        }
    }

    /**
     * 展开自定义源中的 TVBox 多仓/单仓链接。
     *
     * <p>用户可能把一个多仓地址（如 {@code https://xmbjm.fh4u.org/dc.txt}）直接加为自定义源。
     * 这类地址本身不含频道，直接当直播源解析会得到 0 个频道。
     * 这里先探测内容：
     * <ul>
     *   <li>是 TVBox 配置 → 展开为其中的直播源地址，替换掉原地址；</li>
     *   <li>不是 → 原样保留，交给既有流程按 TXT/M3U 解析。</li>
     * </ul>
     *
     * <p>探测请求的响应会被直接丢弃（后续 {@code fetchFromUrl} 会重新下载），
     * 但仅对少量自定义源执行，且共享连接池，代价可接受。
     */
    private List<String> expandCustomSources(List<String> customUrls, FetchCallback callback) {
        if (customUrls == null || customUrls.isEmpty()) return new ArrayList<>();
        List<String> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        for (String url : customUrls) {
            if (url == null || url.trim().isEmpty()) continue;
            String u = url.trim();
            try {
                String probe = fetchTextLimited(u, repoBodyLimit());
                if (MultiRepoParser.looksLikeTvBoxConfig(probe)) {
                    List<String> expanded = expandTvBoxConfig(probe, u, callback);
                    if (!expanded.isEmpty()) {
                        Timber.i("自定义源 %s 为 TVBox 配置，展开出 %d 个直播源", u, expanded.size());
                        seen.addAll(expanded);
                        continue;
                    }
                    // 展开不出直播源（例如只有 sites 没有 lives）：保留原地址，
                    // 让后续流程按普通源再试一次，避免误丢用户配置
                }
            } catch (Exception e) {
                // 探测失败不影响原地址使用，后续 fetchFromUrl 会重试并记录失败
                Timber.d("自定义源探测失败(%s): %s", u, e.getMessage());
            }
            seen.add(u);
        }
        result.addAll(seen);
        return result;
    }

    /**
     * 解析自定义源URL列表
     * 优先按标准 JSON 数组解析，兜底再走简单字符串切分。
     */
    private List<String> parseCustomSourceUrls(String json) {
        List<String> urls = new ArrayList<>();
        if (json == null || json.isEmpty()) return urls;

        // 优先按标准 JSON 数组解析（能正确处理带逗号/引号的 URL）
        try {
            org.json.JSONArray arr = new org.json.JSONArray(json.trim());
            for (int i = 0; i < arr.length(); i++) {
                String url = arr.optString(i, "").trim();
                if (!url.isEmpty() && (url.startsWith("http://") || url.startsWith("https://"))) {
                    if (!urls.contains(url)) urls.add(url);
                }
            }
            if (!urls.isEmpty()) return urls;
        } catch (Exception e) {
            // fall through to naive split
        }

        // 兜底：简单切分
        try {
            String s = json.trim();
            if (s.startsWith("[") && s.endsWith("]")) {
                s = s.substring(1, s.length() - 1);
            }
            for (String part : s.split(",")) {
                if (part == null) continue;
                String url = part.trim().replace("\"", "").replace("'", "");
                if (!url.isEmpty() && (url.startsWith("http://") || url.startsWith("https://"))) {
                    if (!urls.contains(url)) urls.add(url);
                }
            }
        } catch (Exception e) {
            Timber.w(e, "解析自定义源URL失败");
        }

        return urls;
    }
}
