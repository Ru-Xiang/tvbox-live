package com.github.tvbox.osc.api;

import com.github.tvbox.osc.bean.ChannelGroup;
import com.github.tvbox.osc.bean.LiveChannel;
import com.github.tvbox.osc.util.ChannelNameMatcher;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.IpVersionFilter;
import com.github.tvbox.osc.util.StreamUrl;
import com.github.tvbox.osc.api.LiveSourceParser;
import com.orhanobut.hawk.Hawk;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

import org.json.JSONArray;
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

    /**
     * 外部源并发拉取数（滑动窗口大小）：正常设备 / 低内存设备。
     * 合并仍按源的原始顺序进行，窗口只决定"最多领先合并游标几个源"，
     * 因此同时驻留堆上的已下载未合并结果最多为窗口大小个，低内存设备收紧到 2。
     */
    private static final int SOURCE_FETCH_CONCURRENCY = 6;
    private static final int SOURCE_FETCH_CONCURRENCY_LOW = 2;

    /**
     * 单次 HTTP 调用（含读完响应体）的整体超时（秒）。
     *
     * <p>只作为"涓流服务器永远读不完"的兜底：真正的卡死检测靠 readTimeout（无数据到达即触发）。
     * 原先 60s 在 6 路并发分带宽时，会把慢速主机上数 MB 的有效大源硬生生掐断；
     * 且这类超时不计入自动删除（见 {@link #isTotalTimeout}）。
     */
    private static final long CALL_TIMEOUT_SEC = 300L;

    /** 嵌套多仓（多仓的子仓又是多仓 / storeHouse）最多展开的层数 */
    private static final int MAX_REPO_DEPTH = 2;
    /** 一次展开（含各层子仓）累计下载的子仓数上限，防止嵌套多仓把请求数放大成成百上千 */
    private static final int MAX_TOTAL_REPOS = 100;

    /** 探测源类型时先读取的字节数：足以判断是不是 JSON / Base64 / 图片配置 */
    private static final int PROBE_SNIFF_BYTES = 16 * 1024;
    /** 探测阶段的整体等待上限（毫秒）；超时未完成的探测按普通源保留 */
    private static final long PROBE_PHASE_TIMEOUT_MS = 25_000L;
    /** 单个探测请求的整体超时（秒） */
    private static final long PROBE_CALL_TIMEOUT_SEC = 30L;
    /** 图片配置（配置藏在图片末尾）最多读取的总字节数；超过上限部分只保留尾部 */
    private static final long IMAGE_MAX_TOTAL_BYTES = 8L * 1024 * 1024;

    /** 未设置 ua 时拉取播放列表的默认 UA（与旧版一致） */
    private static final String SOURCE_FETCH_UA = "TVBoxOS-Live/1.0";
    private static final String REPO_FETCH_UA = "okhttp/4.9.0";

    /**
     * 本轮展开得到的 lives 条目参数（地址 → ua/header/内联频道）。
     * 聚合任务同一时刻只有一个（见 {@link #fetching}），每轮开始时清空。
     * 用旁路表而不是把请求头编进源地址：源地址同时是失败计数、健康集合的键，必须保持稳定。
     */
    // 声明为 ConcurrentMap：putIfAbsent 绑定到 ConcurrentMap 接口（API 1 起即有）；
    // 声明成 Map 会编译成 Java 8 的 Map.putIfAbsent 默认方法，Android 7.0 以下直接 NoSuchMethodError
    private final java.util.concurrent.ConcurrentMap<String, MultiRepoParser.LiveEntry> liveMeta = new ConcurrentHashMap<>();

    /**
     * 本轮探测时明确连不上（DNS 失败 / 拒绝连接 / 无路由）的地址。
     * 拉源阶段仍会拨号一次（瞬时故障可能已恢复），只是不再让 OkHttp 换路由重试；
     * 连接超时不记：探测的连接超时（8s）比拉源（15s）短，慢主机在拉源阶段仍可能连上。
     */
    private final Map<String, IOException> probeConnectFailures = new ConcurrentHashMap<>();

    /** 本轮探测确认为 TVBox 配置的原始地址（无论是否展开出直播源），不参与自动删除 */
    private final Set<String> probedConfigUrls = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    /** 本轮探测确认为 TVBox 配置、但展开不出任何直播源的原始地址 */
    private final Set<String> probedEmptyConfigs = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    /** 并发拦截时回调的错误信息；ChannelManager 据此识别"忙"并排队等待当前任务结束 */
    public static final String ERROR_BUSY = "正在获取直播源，请稍候";

    /**
     * 当前聚合任务结束（fetching 复位）后需要执行的回调。
     * 由 {@link #runAfterCurrentFetch} 注册，在拉源线程 finally 中统一执行。
     */
    private final List<Runnable> afterFetchActions = new ArrayList<>();

    /**
     * 最近一次聚合是否"网络受损"：拉源期间设备处于断网状态，或"上次拉取成功的源"
     * 本轮超过一半因网络原因（DNS/连接/超时）失败。
     * 典型场景是开机自启时 Wi-Fi 尚未连好、或刷新中途断网。
     * 供 ChannelManager 判断是否用这份残缺结果覆盖完整缓存。
     *
     * <p>不能按"全部展开源的失败比例"判定：公共聚合订阅/多仓展开出的数百个子源里
     * 大部分主机本就已失效（DNS 失败/拒绝连接），网络正常时也会过半失败，
     * 那样每轮都会被误判为受损（新结果永远不被采用、每次启动都全量重拉、失效域名永不清理）。
     */
    private volatile boolean lastFetchDegraded = false;

    /**
     * 上次拉取成功的外部源（URL 的 hashCode 十六进制，逗号分隔）。
     * 只有"之前能拉到、这次却因网络失败"才说明网络本身出了问题；一直失效的源不参与判定。
     * 存哈希而非完整 URL：订阅展开后成功源可达数百个，完整 URL 每轮写入 Hawk 开销偏大。
     */
    private static final String KEY_HEALTHY_SOURCES = "iptv_healthy_source_hashes";

    /**
     * TXT 频道行特征片段："名称,http(s)://"。
     * 用 find() 定位即可；原 matches("(?s).*\\w+,https?://.*") 需整体匹配，
     * 在数 MB 文本上 .* 回溯极慢。
     */
    private static final Pattern TXT_LINE_PATTERN = Pattern.compile("\\w,https?://");

    /** 获取状态回调接口 */
    public interface FetchCallback {
        void onSuccess(List<ChannelGroup> groups, int totalChannels);
        void onError(String errorMsg);
        void onProgress(int current, int total, String message);
    }

    private IptvApiService() {
        // 复用全局共享客户端，避免每个服务各自持有一套连接池与 Dispatcher 线程池。
        // 派生实例只额外加整体超时兜底：connect/read 超时只约束单次等待，
        // 慢速服务器持续"涓流"返回数据时永远不会触发，拉源线程会被无限期挂住。
        // 派生客户端复用同一套连接池与线程池；不直接改共享客户端，避免影响 EPG 等大文件下载。
        httpClient = com.github.tvbox.osc.util.HttpClients.shared().newBuilder()
                .callTimeout(CALL_TIMEOUT_SEC, TimeUnit.SECONDS)
                .build();
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
        MultiRepoParser.LiveEntry meta = url == null ? null : liveMeta.get(url);
        // 旧格式 lives 直接写出的频道：内容已在内存里，不发请求
        if (meta != null && meta.inlineTxt != null) {
            return parseContent(meta.inlineTxt, "txt");
        }
        // 探测阶段明确连不上的主机：仍按正常超时拨号一次（不直接判死，避免瞬时故障被计入未入选），
        // 只关掉连接失败重试，少等一轮
        OkHttpClient client = httpClient;
        if (url != null && probeConnectFailures.containsKey(url)) {
            client = httpClient.newBuilder().retryOnConnectionFailure(false).build();
        }
        // 探测阶段已确认是展开不出直播源的配置：不再重复下载展开，也不计入未入选
        if (url != null && probedEmptyConfigs.contains(url)) {
            throw new ConfigNoLivesException("TVBox 配置未包含可用直播源: " + url);
        }
        Map<String, String> headers = meta != null ? meta.headers : Collections.<String, String>emptyMap();

        Body fetched;
        try (Response response = client.newCall(buildRequest(url, headers, SOURCE_FETCH_UA)).execute()) {
            if (!response.isSuccessful()) {
                throw new SourceContentException("请求失败: HTTP " + response.code() + " " + response.message());
            }
            // 有上限地读取响应体，避免个别源返回超大/异常内容导致 OOM（尤其在低内存电视上）
            fetched = readBody(response, sourceBodyLimit(), false);
        }
        String body = fetched.text;
        if (body.isEmpty()) {
            throw new SourceContentException("返回数据为空");
        }

        Timber.d("获取到直播源数据，大小: %d 字节", body.length());

        // 兜底：若该地址实际是 TVBox 配置（订阅列表里混入、或探测超时未展开的情况），
        // 直接按 M3U/TXT 解析会得到 0 个频道。这里就地展开其 lives（多仓则先展开子仓）并抓取。
        if (MultiRepoParser.looksLikeTvBoxConfig(body)) {
            String cfg = MultiRepoParser.normalizeConfig(body);
            body = null; // 先释放原始正文（可能是图片），内联抓取期间不再占堆
            // 相对地址按重定向后的最终地址解析（短链 / dc.txt 跳转到 raw 的情况）
            List<ChannelGroup> fromRepo = fetchFromTvBoxConfig(cfg, fetched.finalUrl != null ? fetched.finalUrl : url);
            if (!fromRepo.isEmpty()) return fromRepo;
            // 展开失败则继续按普通格式尝试解析（少数以 '{' 开头的 TXT 列表会被误判为配置）
            List<ChannelGroup> asPlaylist = parseContent(cfg, autoDetectFormat(cfg, format));
            if (!asPlaylist.isEmpty()) {
                applyLineHeaders(asPlaylist, headers);
                return asPlaylist;
            }
            // JSON 配置按 TXT 解析只会得到 0 个频道，且不应算作"源失效"：配置本身是有效的
            throw new ConfigNoLivesException("TVBox 配置未展开出可用直播源: " + url);
        }

        // 自动识别格式
        String detectedFormat = autoDetectFormat(body, format);
        List<ChannelGroup> groups = parseContent(body, detectedFormat);
        // 配置里给这个直播源设置的 ua/header：附到每条线路上，播放与测速才能带同样的请求头
        applyLineHeaders(groups, headers);
        return groups;
    }

    /**
     * TVBox 配置有效但未展开出任何频道（只有 sites、子仓全部失败、只有爬虫直播等）。
     * 与 {@link SourceContentException} 区分：不计入连续未入选，避免用户填写的配置被自动删除。
     */
    static final class ConfigNoLivesException extends IOException {
        ConfigNoLivesException(String msg) {
            super(msg);
        }
    }

    /** 构造请求：配置给出的 ua/header 优先，否则用默认 UA；非法请求头跳过，不让整个请求失败 */
    private static Request buildRequest(String url, Map<String, String> headers, String defaultUa) {
        Request.Builder b = new Request.Builder().url(url).header("Accept", "*/*");
        boolean hasUa = false;
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                try {
                    b.header(e.getKey(), e.getValue());
                    if ("User-Agent".equalsIgnoreCase(e.getKey())) hasUa = true;
                } catch (IllegalArgumentException ignore) {
                    Timber.d("忽略非法请求头: %s", e.getKey());
                }
            }
        }
        if (!hasUa) b.header("User-Agent", defaultUa);
        return b.build();
    }

    /**
     * 给解析出的每条线路附加请求头（StreamUrl 的 "url|User-Agent=.." 写法）；
     * 线路自己已带的同名请求头优先（如 M3U 的 #EXTVLCOPT）。
     */
    private static void applyLineHeaders(List<ChannelGroup> groups, Map<String, String> headers) {
        if (groups == null || headers == null || headers.isEmpty()) return;
        for (ChannelGroup g : groups) {
            if (g == null || g.getChannels() == null) continue;
            for (LiveChannel ch : g.getChannels()) {
                if (ch == null) continue;
                List<String> urls = ch.getSourceUrls();
                if (urls == null || urls.isEmpty()) continue;
                Set<String> out = new LinkedHashSet<>();
                for (String u : urls) {
                    if (u != null) out.add(StreamUrl.withHeaders(u, headers));
                }
                List<Double> speeds = new ArrayList<>(out.size());
                for (int i = 0; i < out.size(); i++) speeds.add(0.0);
                ch.setSourceUrls(new ArrayList<>(out));
                ch.setSpeeds(speeds);
            }
        }
    }

    /**
     * 把 TVBox 配置地址就地展开并抓取其中的直播源。
     *
     * <p>用于「某个源地址其实是仓库配置」的兜底场景。只抓取前若干个 lives
     * 地址并合并，避免单个配置无限展开拖慢整体加载。多仓（urls / storeHouse）先展开子仓。
     *
     * @param cfg 已还原（Base64/图片）的配置正文
     */
    private List<ChannelGroup> fetchFromTvBoxConfig(String cfg, String baseUrl) {
        List<ChannelGroup> merged = new ArrayList<>();
        try {
            List<MultiRepoParser.LiveEntry> lives = MultiRepoParser.extractLiveEntries(cfg, baseUrl);
            if (lives.isEmpty() && !MultiRepoParser.extractRepoUrls(cfg, baseUrl).isEmpty()) {
                // 多仓：没有 lives，展开子仓后再取直播源（订阅列表里直接写多仓链接的情况）
                lives = new ArrayList<>();
                for (String u : expandTvBoxConfig(cfg, baseUrl, null)) {
                    MultiRepoParser.LiveEntry e = liveMeta.get(u);
                    lives.add(e != null ? e : new MultiRepoParser.LiveEntry(u, null, null, null, null));
                }
            }
            cfg = null; // 配置正文不再需要，抓取播放列表前释放
            if (lives.isEmpty()) return merged;
            int limit = Math.min(lives.size(), MAX_INLINE_LIVES);
            for (int i = 0; i < limit; i++) {
                MultiRepoParser.LiveEntry live = lives.get(i);
                try {
                    List<ChannelGroup> groups;
                    if (live.inlineTxt != null) {
                        groups = parseContent(live.inlineTxt, "txt");
                    } else {
                        // 这里下载的是播放列表而不是配置：用直播源的上限，低内存设备上 512KB 会截掉大量频道
                        Body b = fetchText(live.url, sourceBodyLimit(), live.headers);
                        String content = b.text;
                        if (content == null || content.trim().isEmpty()) continue;
                        groups = parseContent(content, autoDetectFormat(content, "auto"));
                        applyLineHeaders(groups, live.headers);
                    }
                    if (!groups.isEmpty()) mergeGroups(merged, groups, live.url);
                } catch (Exception e) {
                    Timber.d("内联直播源获取失败(%s): %s", live.url, e.getMessage());
                }
            }
            if (!merged.isEmpty()) {
                Timber.i("源 %s 为 TVBox 配置，内联展开得到 %d 个分组", baseUrl, merged.size());
            }
        } catch (Exception | StackOverflowError e) {
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

    /** 一次下载的结果：正文、重定向后的最终地址、是否被截断 / 探测时被判定为非配置 */
    static final class Body {
        String text = "";
        String finalUrl;
        boolean truncated;
        /** 探测模式下前若干 KB 已判定不是配置，未继续下载 */
        boolean notConfig;
    }

    /**
     * 有上限地读取响应体。
     *
     * <p>图片配置（JPEG/PNG/GIF/WebP 末尾附带 Base64 配置）的有效内容在<b>尾部</b>，
     * 按普通上限截断会把标记和 Base64 一起丢掉；这类正文只保留最后 maxBytes 字节
     * （总读取量受 {@link #IMAGE_MAX_TOTAL_BYTES} 约束）。
     *
     * @param probe true 时先读 {@link #PROBE_SNIFF_BYTES} 判断像不像配置，不像就立即停止下载
     */
    private Body readBody(Response response, int maxBytes, boolean probe) throws IOException {
        Body result = new Body();
        try {
            result.finalUrl = response.request().url().toString();
        } catch (Exception ignore) {
            // 取不到最终地址时由调用方回退到原地址
        }
        if (response.body() == null) return result;
        try (java.io.InputStream in = response.body().byteStream()) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            byte[] head = new byte[12];
            int headLen = 0;
            boolean image = false;
            boolean decided = !probe;
            long total = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                // 被取消（内存吃紧提前收尾时 cancel(true)）后立即停止下载，不再继续占用堆
                if (Thread.currentThread().isInterrupted()) {
                    throw new java.io.InterruptedIOException("cancelled");
                }
                if (headLen < head.length) {
                    int c = Math.min(head.length - headLen, n);
                    System.arraycopy(buf, 0, head, headLen, c);
                    headLen += c;
                    image = isImageMagic(head, headLen);
                }
                total += n;
                if (!image) {
                    if (bos.size() + n > maxBytes) {
                        bos.write(buf, 0, Math.max(0, maxBytes - bos.size()));
                        result.truncated = true;
                        Timber.w("源内容过大，已截断至 %d KB", maxBytes / 1024);
                        break;
                    }
                    bos.write(buf, 0, n);
                } else {
                    bos.write(buf, 0, n);
                    if (bos.size() >= 2 * maxBytes) keepTail(bos, maxBytes);
                    if (total > IMAGE_MAX_TOTAL_BYTES) {
                        result.truncated = true;
                        Timber.w("图片内容过大，读取 %d KB 后停止", total / 1024);
                        break;
                    }
                }
                if (!decided && bos.size() >= PROBE_SNIFF_BYTES) {
                    decided = true;
                    if (!image && !sniffConfigLike(bos.toByteArray())) {
                        result.notConfig = true;
                        return result;
                    }
                }
            }
            if (image && bos.size() > maxBytes) keepTail(bos, maxBytes);
            if (!decided && !image && !sniffConfigLike(bos.toByteArray())) {
                result.notConfig = true;
                return result;
            }
            // 直接按字节解码不会像 OkHttp body.string() 那样去掉 UTF-8 BOM，
            // 且 String.trim() 不会删 U+FEFF：残留时首个分组/频道名带不可见前缀，
            // 会出现两个同名分组、或频道名与模板对不上被丢弃
            result.text = stripBom(bos.toString("UTF-8"));
            return result;
        }
    }

    /** 只保留缓冲区最后 keep 字节 */
    private static void keepTail(java.io.ByteArrayOutputStream bos, int keep) {
        byte[] all = bos.toByteArray();
        if (all.length <= keep) return;
        bos.reset();
        bos.write(all, all.length - keep, keep);
    }

    /** 文件头是否为常见图片格式（JPEG / PNG / GIF / WebP） */
    private static boolean isImageMagic(byte[] h, int len) {
        if (len >= 3 && (h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8 && (h[2] & 0xFF) == 0xFF) return true;
        if (len >= 4 && (h[0] & 0xFF) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G') return true;
        if (len >= 4 && h[0] == 'G' && h[1] == 'I' && h[2] == 'F' && h[3] == '8') return true;
        return len >= 12 && h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F'
                && h[8] == 'W' && h[9] == 'E' && h[10] == 'B' && h[11] == 'P';
    }

    /**
     * 探测用：正文开头是否可能是 TVBox 配置（JSON / 注释开头的 JSON / 整段 Base64 / 含图片标记）。
     * 普通 M3U（#EXTM3U）与 TXT（名称,地址）在首个字符或首行就会被排除。
     */
    private static boolean sniffConfigLike(byte[] prefix) {
        String s;
        try {
            s = new String(prefix, "UTF-8");
        } catch (Exception e) {
            return true;
        }
        int i = 0;
        while (i < s.length() && (s.charAt(i) == '﻿' || Character.isWhitespace(s.charAt(i)))) i++;
        if (i >= s.length()) return false;
        char c = s.charAt(i);
        if (c == '{' || s.startsWith("//", i) || s.startsWith("/*", i)) return true;
        if (s.indexOf("**") >= 0) return true;
        // 整段 Base64：截取的前缀末尾可能断在多字节字符中间，只检查前面的部分
        int end = Math.max(i, s.length() - 4);
        for (int k = i; k < end; k++) {
            char ch = s.charAt(k);
            boolean ok = (ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9')
                    || ch == '+' || ch == '/' || ch == '=' || ch == '-' || ch == '_' || Character.isWhitespace(ch);
            if (!ok) return false;
        }
        return true;
    }

    /** 去掉字符串开头的 UTF-8 BOM（U+FEFF） */
    static String stripBom(String s) {
        if (s != null && !s.isEmpty() && s.charAt(0) == '\ufeff') return s.substring(1);
        return s;
    }

    /**
     * 源内容层面的失败（HTTP 错误码、返回空内容）：服务器明确给出了"无效"的答复，
     * 与网络不通/超时区分开，前者计入连续未入选，后者在网络受损时不计。
     */
    static final class SourceContentException extends IOException {
        SourceContentException(String msg) {
            super(msg);
        }
    }

    /**
     * 是否为 callTimeout 整体超时 / 被取消：OkHttp 对此抛出非 SocketTimeoutException 的
     * InterruptedIOException。这只说明"源太大或主机慢"，源本身是有效的，绝不计入自动删除。
     */
    private static boolean isTotalTimeout(Throwable t) {
        return t instanceof java.io.InterruptedIOException
                && !(t instanceof java.net.SocketTimeoutException);
    }

    /**
     * 若当前有聚合任务在执行，则在其结束（结果已回调、fetching 已复位）后执行 action；
     * 否则立即在调用线程执行。
     *
     * <p>用于测速准备阶段：被并发拦截时不能拿"即将被后台刷新替换的旧列表"去测速，
     * 否则测速排序作用在随后被丢弃的旧对象上，持久化时却裁剪了未排序的新列表。
     */
    public void runAfterCurrentFetch(Runnable action) {
        if (action == null) return;
        synchronized (afterFetchActions) {
            if (fetching.get()) {
                afterFetchActions.add(action);
                return;
            }
        }
        action.run();
    }

    /** 最近一次聚合是否网络受损（见 {@link #lastFetchDegraded}） */
    public boolean isLastFetchDegraded() {
        return lastFetchDegraded;
    }

    /**
     * 系统是否报告存在已连接的网络。无法判断（无 Context / 异常）时按已连接处理，
     * 只把明确的"断网"作为网络受损信号。
     */
    @SuppressWarnings("deprecation")
    private static boolean isNetworkConnected() {
        try {
            android.content.Context ctx = com.github.tvbox.osc.App.getInstance();
            if (ctx == null) return true;
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                    ctx.getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
            if (cm == null) return true;
            // getActiveNetworkInfo 在 API 21 起可用；API 29 起废弃但仍正常返回
            android.net.NetworkInfo info = cm.getActiveNetworkInfo();
            if (info != null && info.isConnected()) return true;
            // 个别盒子 ROM 的有线网络（以太网）不会成为"默认网络"，只看 active 会把每次拉源都判成断网：
            // 网络受损后不再自动删除失效源、后台刷新结果全被丢弃且每次启动都全量重拉。
            // 因此再遍历所有网络，任一可用即视为在线；只有明确全部断开才返回 false。
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                android.net.Network[] all = cm.getAllNetworks();
                if (all != null) {
                    for (android.net.Network n : all) {
                        if (n == null) continue;
                        android.net.NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                        if (caps != null && caps.hasCapability(
                                android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                            return true;
                        }
                        android.net.NetworkInfo ni = cm.getNetworkInfo(n);
                        if (ni != null && ni.isConnected()) return true;
                    }
                }
            } else {
                android.net.NetworkInfo[] infos = cm.getAllNetworkInfo();
                if (infos != null) {
                    for (android.net.NetworkInfo ni : infos) {
                        if (ni != null && ni.isConnected()) return true;
                    }
                }
            }
            return false;
        } catch (Throwable t) {
            // 无法判断时按在线处理：不单凭这一项把本次拉源标成网络受损
            return true;
        }
    }

    /** 读取"上次拉取成功的源"哈希集合（见 {@link #KEY_HEALTHY_SOURCES}） */
    private static Set<String> loadHealthySources() {
        Set<String> set = new HashSet<>();
        try {
            String s = Hawk.get(KEY_HEALTHY_SOURCES, "");
            if (s != null && !s.isEmpty()) {
                for (String h : s.split(",")) {
                    if (!h.isEmpty()) set.add(h);
                }
            }
        } catch (Throwable t) {
            Timber.w(t, "读取上次成功源集合失败");
        }
        return set;
    }

    /**
     * 写回"上次拉取成功的源"：本轮处理过的以本轮结果为准，未处理的保留旧状态，
     * 不在当前外部源列表中的剔除。
     */
    private static void saveHealthySources(Set<String> before, Set<String> processed,
                                           Set<String> fetchedOk, Set<String> currentUrls) {
        try {
            Set<String> current = new HashSet<>();
            if (currentUrls != null) {
                for (String u : currentUrls) {
                    if (u != null) current.add(Integer.toHexString(u.hashCode()));
                }
            }
            Set<String> result = new LinkedHashSet<>();
            for (String h : before) {
                if (!processed.contains(h) && current.contains(h)) result.add(h);
            }
            result.addAll(fetchedOk);
            StringBuilder sb = new StringBuilder();
            for (String h : result) {
                if (sb.length() > 0) sb.append(',');
                sb.append(h);
            }
            Hawk.put(KEY_HEALTHY_SOURCES, sb.toString());
        } catch (Throwable t) {
            Timber.w(t, "保存成功源集合失败");
        }
    }

    /**
     * 下载文本内容并限制最大读取字节数。
     *
     * <p>用于抓取子仓配置与内联播放列表：多仓里的子仓 JSON 大小不可控（实测有 100KB+ 的），
     * 且部分地址会返回网页或大文件，必须限流。
     * 单个子仓使用更短的超时，避免失效地址拖慢整体展开。
     * 返回值带重定向后的最终地址，相对路径要按它解析。
     */
    private Body fetchText(String url, int maxBytes, Map<String, String> headers) throws IOException {
        Request request = buildRequest(url, headers, REPO_FETCH_UA);
        // 子仓用独立的短超时：派生自共享客户端，复用连接池，开销极小
        OkHttpClient client = httpClient.newBuilder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code());
            }
            return readBody(response, maxBytes, false);
        }
    }

    /**
     * 探测一个源地址是不是 TVBox 配置。
     *
     * <p>只先读前 {@link #PROBE_SNIFF_BYTES}：普通 M3U/TXT 立即停止下载（不再整份下载两遍），
     * 像配置时在同一个响应上继续读完，不发第二次请求。
     *
     * @return 还原后的配置正文与最终地址；不是配置时 text 为空
     */
    private Body probeConfig(String url) throws IOException {
        Request request = buildRequest(url, null, REPO_FETCH_UA);
        OkHttpClient client = httpClient.newBuilder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .callTimeout(PROBE_CALL_TIMEOUT_SEC, TimeUnit.SECONDS)
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                Body b = new Body();
                b.notConfig = true;
                return b;
            }
            Body b = readBody(response, repoBodyLimit(), true);
            if (b.notConfig) return b;
            String cfg = MultiRepoParser.normalizeConfig(b.text);
            if (!MultiRepoParser.looksLikeTvBoxConfig(cfg)) {
                b.notConfig = true;
                b.text = "";
                return b;
            }
            if (b.truncated) Timber.w("配置 %s 超过 %d KB 被截断，可能解析失败", url, repoBodyLimit() / 1024);
            b.text = cfg;
            return b;
        }
    }

    /**
     * 是否"明确连不上主机"类失败（DNS 失败、拒绝连接、无路由）。
     * 不含连接超时：探测用的连接超时比拉源短，慢主机（gh-proxy、国内访问 GitHub raw）拉源时仍可能连上。
     */
    private static boolean isConnectFailure(Throwable t) {
        return t instanceof java.net.UnknownHostException
                || t instanceof java.net.ConnectException
                || t instanceof java.net.NoRouteToHostException;
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
                try { callback.onError(ERROR_BUSY); } catch (Throwable ignore) {}
            }
            return;
        }
        lastFetchDegraded = false;
        Thread worker = new Thread(() -> {
            ExecutorService pool = null;
            try {
                com.github.tvbox.osc.util.RunLog.i("fetchAndMergeSources: 开始");
                // 每轮重新探测/展开：上一轮的 lives 参数与探测结论不能沿用
                liveMeta.clear();
                probeConnectFailures.clear();
                probedConfigUrls.clear();
                probedEmptyConfigs.clear();
                // 0) 加载频道改名（别名）规则，解析阶段即按规则改名
                loadAliasRules(callback);

                List<ChannelGroup> allGroups = new ArrayList<>();
                // 分组名 -> 分组内频道索引，跨所有源复用，避免每合并一个源都重建一次索引
                Map<String, GroupIndex> groupIndex = new HashMap<>();

                // 记录每个"可删除源"（自定义源 / 订阅源）产出的 URL 集合，用于入选判定
                Map<String, Set<String>> sourceUrlMap = new LinkedHashMap<>();

                // 自定义源（其中的多仓链接会被展开为具体直播源）。
                // 保留用户原始填写的列表：自动删除只能作用在原始地址上，绝不能把展开后的
                // 子直播源写回设置（否则用户的多仓链接会被替换成成百上千个固化的子地址）
                List<String> rawCustomUrls =
                        parseCustomSourceUrls(Hawk.get(HawkConfig.IPTV_CUSTOM_SOURCES, ""));
                // 多仓链接（配置页/手机配置页显式指定，其中的子仓会被展开为具体直播源）
                final List<String> rawMultiRepoUrls =
                        parseCustomSourceUrls(Hawk.get(HawkConfig.IPTV_MULTI_REPO_URLS, ""));
                // 三类来源按固定顺序依次展开：同一直播源出现在多处时，ua/header 以先展开的
                // （自定义 > 多仓 > 订阅）为准，结果确定；并行展开会让请求头随网络快慢变化，
                // 并成倍抬高内存峰值（各自的探测池与子仓下载池叠加），故不并行。
                List<String> customUrls = expandCustomSources(rawCustomUrls, callback);
                List<String> multiRepoUrls = expandCustomSources(rawMultiRepoUrls, callback);
                List<String> subscribeUrls = loadSubscribeList(callback);
                com.github.tvbox.osc.util.RunLog.i("自定义源展开完成: " + customUrls.size() + " 个");
                com.github.tvbox.osc.util.RunLog.i("多仓链接展开完成: " + multiRepoUrls.size() + " 个");
                com.github.tvbox.osc.util.RunLog.i("订阅列表展开完成: " + subscribeUrls.size() + " 个");

                // 所有"外部源"（自定义 + 多仓 + 订阅），它们都参与连续未入选自动删除统计
                // 用 Set 去重：订阅/多仓展开后可达数百个地址，List#contains 去重为 O(n²)
                List<String> externalUrls = new ArrayList<>();
                Set<String> externalSet = new HashSet<>();
                for (String u : customUrls) {
                    if (externalSet.add(u)) externalUrls.add(u);
                }
                for (String u : multiRepoUrls) {
                    if (externalSet.add(u)) externalUrls.add(u);
                }
                for (String u : subscribeUrls) {
                    if (externalSet.add(u)) externalUrls.add(u);
                }

                com.github.tvbox.osc.util.RunLog.i("待拉取外部源合计: " + externalUrls.size() + " 个"
                        + "（低内存设备=" + isLowMemoryDevice() + "，单源上限="
                        + sourceBodyLimit() / 1024 + "KB）");
                int totalSteps = externalUrls.size();
                boolean anyFetched = false;
                // 开始拉源时设备是否断网（开机自启时 Wi-Fi/有线尚未就绪的典型信号）
                boolean sawOffline = !isNetworkConnected();
                // 上次拉取成功的源（哈希），以及本轮实际拉取成功的源
                Set<String> healthyBefore = loadHealthySources();
                Set<String> fetchedOk = new HashSet<>();
                Set<String> processedSet = new HashSet<>();
                // "上次成功"的源本轮的处理数 / 因网络原因失败数，用于判定网络受损
                int healthyProcessed = 0;
                int healthyNetFailed = 0;
                // 网络原因（DNS/连接/读超时等）拉取失败的源：仅在本轮网络整体正常时才计入未入选
                List<String> netFailed = new ArrayList<>();
                // 整体超时/被取消的源：只说明源大或主机慢，任何情况下都不计入未入选
                int timeoutFailed = 0;
                // 实际处理过（已取结果）的源数，仅用于日志
                int processed = 0;

                // 直播源仅来自外部源（自定义 + 订阅列表）合并的整体订阅源，
                // 内容格式自动识别（TXT / M3U），无需再从 IPTV-API 拉取。
                //
                // 并发拉取 + 按原顺序合并：串行拉取时每个慢源/失效源都要等满超时，
                // 数十个源累加起来"正在获取直播源"会卡上好几分钟。这里用滑动窗口
                // 最多领先合并游标 window 个源预取，合并顺序与结果仍与串行完全一致；
                // 窗口大小同时限制了"已下载未合并"结果的堆驻留量（低内存设备收紧）。
                int window = isLowMemoryDevice() ? SOURCE_FETCH_CONCURRENCY_LOW : SOURCE_FETCH_CONCURRENCY;
                if (totalSteps > 0) {
                    pool = Executors.newFixedThreadPool(Math.min(window, totalSteps), r -> {
                        Thread t = new Thread(r, "SourceFetch");
                        t.setDaemon(true);
                        return t;
                    });
                }
                List<Future<List<ChannelGroup>>> futures = new ArrayList<>(totalSteps);

                for (int step = 0; step < totalSteps; step++) {
                    // 内存兜底阀：可用堆触线时提前收尾，用已拉到的源继续后续流程。
                    // 否则在低内存盒子上会一路拉到被系统杀掉，表现为"正在获取直播源"时闪退。
                    if (step > 0 && isHeapCritical()) {
                        Timber.w("可用内存不足，提前结束拉源（已处理 %d/%d 个源）", step, totalSteps);
                        // 立即取消已预取但尚未合并的源并关闭线程池：否则它们会在后续模板/去重/写库
                        // 这一内存峰值阶段继续下载解析，且结果一直被 futures 引用，反而加剧 OOM。
                        // 未合并的源不进 sourceUrlMap，不会被记"未入选"。
                        for (int i = step; i < futures.size(); i++) {
                            Future<List<ChannelGroup>> f = futures.get(i);
                            if (f != null) f.cancel(true);
                        }
                        futures.clear();
                        if (pool != null) pool.shutdownNow();
                        try {
                            if (callback != null) {
                                callback.onProgress(totalSteps, totalSteps, "内存不足，已提前结束拉源");
                            }
                        } catch (Throwable ignore) {
                        }
                        break;
                    }
                    // 补满预取窗口；当前源必提交，超前预取前再探测一次内存，吃紧就不再超前
                    while (futures.size() < totalSteps && futures.size() < step + window) {
                        if (futures.size() > step && isHeapCritical()) break;
                        final String fetchUrl = externalUrls.get(futures.size());
                        futures.add(pool.submit(() -> fetchFromUrl(fetchUrl, "auto")));
                    }

                    String srcUrl = externalUrls.get(step);
                    // 进度按"即将处理第 step+1 个"展示，与总数对齐更直观。
                    // 消息里不再重复计数：UI 会自行附加 (current/total)
                    if (callback != null) {
                        callback.onProgress(step + 1, totalSteps, "正在获取直播源");
                    }
                    processed++;
                    String srcHash = Integer.toHexString(srcUrl.hashCode());
                    boolean wasHealthy = healthyBefore.contains(srcHash);
                    processedSet.add(srcHash);
                    if (wasHealthy) healthyProcessed++;
                    try {
                        com.github.tvbox.osc.util.RunLog.i("拉源 [" + (step + 1) + "/" + totalSteps + "] "
                                + srcUrl);
                        // 单个请求受 callTimeout 约束，get() 不会无限期阻塞
                        List<ChannelGroup> srcGroups = futures.get(step).get();
                        // 收集该源贡献的所有 URL
                        Set<String> urls = new HashSet<>();
                        for (ChannelGroup g : srcGroups) {
                            for (LiveChannel ch : g.getChannels()) {
                                if (ch.getSourceUrls() != null) urls.addAll(ch.getSourceUrls());
                            }
                        }
                        sourceUrlMap.put(srcUrl, urls);
                        mergeGroups(allGroups, groupIndex, srcGroups, srcUrl);
                        anyFetched = true;
                        fetchedOk.add(srcHash);
                    } catch (Exception e) {
                        // 拉取线程里的异常（含 OOM 等 Error）被 Future 包装为 ExecutionException
                        Throwable cause = (e instanceof ExecutionException && e.getCause() != null)
                                ? e.getCause() : e;
                        Timber.w(cause, "获取源失败: %s", srcUrl);
                        if (cause instanceof SourceContentException) {
                            // HTTP 错误码 / 空内容：服务器明确答复无效，视为未入选
                            sourceUrlMap.put(srcUrl, new HashSet<>());
                        } else if (cause instanceof ConfigNoLivesException) {
                            // 配置有效但本轮没展开出直播源（子仓失效、只有 sites 等）：
                            // 不是源本身失效，不计入未入选，也不算网络失败
                            Timber.i("配置源本轮未展开出直播源，不计入未入选: %s", srcUrl);
                        } else if (isTotalTimeout(cause)) {
                            // 整体超时：源有效只是大/慢，不计入（否则慢速大源 3 次后被误删）
                            timeoutFailed++;
                            if (wasHealthy) healthyNetFailed++;
                        } else if (cause instanceof IOException) {
                            // 网络原因：先记下，循环结束后视本轮网络状况决定是否计入
                            netFailed.add(srcUrl);
                            if (wasHealthy) healthyNetFailed++;
                            // 失败当下顺带探测一次连通性（开销极小），捕捉刷新中途断网
                            if (!sawOffline && !isNetworkConnected()) sawOffline = true;
                        }
                        // 其余（OOM 等 Error、解析异常）与源本身无关，不计入未入选
                    } finally {
                        // 已合并的源结果立即释放引用，不让 futures 列表持有整份解析结果
                        futures.set(step, null);
                    }
                }

                // 所有源都失败或结果为空（通常是断网）：按错误处理，交给上层回退本地缓存。
                // 否则空结果会经 onSuccess 覆盖数据库缓存，且所有自定义源都会被记一次
                // "未入选"，离线几次后被自动删除。
                if (!anyFetched || allGroups.isEmpty()) {
                    throw new IOException("未获取到任何直播源，请检查网络或源地址");
                }

                // 网络受损判定（开机 Wi-Fi 未就绪、刷新中途断网）：
                //  1) 拉源开始/中途/结束时系统报告断网；或
                //  2) 上次拉取成功的源，本轮超过一半因网络原因失败。
                // 一直失效的源（公共订阅里大量死域名）不在"上次成功"集合中，不会把正常网络误判为受损。
                // 受损时网络失败的源不计"未入选"，避免用户可用的自定义源被连续几次开机误删；
                // 网络正常时网络失败仍照旧计入（保留对失效域名的自动清理）。
                if (!sawOffline && !isNetworkConnected()) sawOffline = true;
                int networkFailures = netFailed.size() + timeoutFailed;
                boolean degraded = sawOffline
                        || (healthyProcessed > 0 && healthyNetFailed * 2 > healthyProcessed);
                lastFetchDegraded = degraded;
                if (degraded) {
                    Timber.w("本轮拉源网络受损（断网=%b，上次成功的源 %d/%d 因网络失败），"
                                    + "%d 个网络失败的源不计入未入选",
                            sawOffline, healthyNetFailed, healthyProcessed, networkFailures);
                    com.github.tvbox.osc.util.RunLog.i("拉源网络受损: offline=" + sawOffline
                            + " healthy=" + healthyNetFailed + "/" + healthyProcessed
                            + " net=" + networkFailures + "/" + processed);
                } else {
                    for (String u : netFailed) sourceUrlMap.put(u, new HashSet<>());
                }
                // 更新"上次成功"集合：本轮处理过的源以本轮结果为准（失败即移出，避免一个永久失效的
                // 旧源让之后每轮都被判为受损）；未处理到的（内存阀提前结束）保留原状态；
                // 已不在源列表中的剔除，防止无限增长
                saveHealthySources(healthyBefore, processedSet, fetchedOk, externalSet);

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

                // 更新连续未入选计数，并自动删除连续 N 次失败的自定义源。
                // 仅用户原始填写、且未被展开为多仓/单仓的地址可被删除（见 rawCustomUrls）
                updateSourceFailCounts(rawCustomUrls, customUrls, sourceUrlMap, finalUrls, externalSet,
                        new HashSet<>(probedConfigUrls));

                // 网络受损且结果被上层弃用时，ChannelManager 会把该时间复位，让下次启动重试
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
                if (pool != null) pool.shutdownNow();
                // 无论成功失败都必须复位，否则后续刷新会被永久拦截。
                // 与 runAfterCurrentFetch 同锁：保证"判断忙 → 排队"与"复位 → 取队列"不会错过
                List<Runnable> pending;
                synchronized (afterFetchActions) {
                    fetching.set(false);
                    pending = new ArrayList<>(afterFetchActions);
                    afterFetchActions.clear();
                }
                for (Runnable r : pending) {
                    try {
                        r.run();
                    } catch (Throwable t) {
                        Timber.w(t, "拉源结束后的排队回调执行失败");
                    }
                }
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
        // 用预编译 find() 定位特征片段，避免 matches("(?s).*...") 在大文本上的整体回溯
        if (trimmed.contains(",#genre#") || TXT_LINE_PATTERN.matcher(trimmed).find()) {
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
        mergeGroups(target, buildGroupIndex(target), source, origin);
    }

    /**
     * 合并频道分组（复用调用方持有的索引）。
     *
     * <p>聚合全部外部源时由 {@link #fetchAndMergeSources} 持有同一份索引贯穿所有源，
     * 不再每合并一个源就把目标列表重新扫描、重新归一化一遍。
     *
     * @param index 分组名 → 分组内频道索引；必须与 target 同步（只经由本方法修改 target）
     */
    private void mergeGroups(List<ChannelGroup> target, Map<String, GroupIndex> index,
                             List<ChannelGroup> source, String origin) {
        if (source == null || source.isEmpty()) return;

        for (ChannelGroup srcGroup : source) {
            if (srcGroup == null) continue;
            String groupName = srcGroup.getGroupName();
            GroupIndex existing = groupName == null ? null : index.get(groupName);

            if (existing == null) {
                // 新分组，标记来源
                for (LiveChannel ch : srcGroup.getChannels()) {
                    if (ch != null) ch.setSourceOrigin(origin);
                }
                target.add(srcGroup);
                if (groupName != null) {
                    index.put(groupName, new GroupIndex(srcGroup));
                }
                continue;
            }

            // 合并到已有分组（精确名 / 归一化名索引关联同一频道）
            for (LiveChannel srcChannel : srcGroup.getChannels()) {
                if (srcChannel == null) continue;
                LiveChannel existingChannel = existing.find(srcChannel.getChannelName());
                if (existingChannel == null) {
                    srcChannel.setSourceOrigin(origin);
                    existing.group.addChannel(srcChannel);
                    existing.add(srcChannel);
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

    /** 为已有目标列表构建分组索引（同名分组保留首个，与原线性查找行为一致） */
    private static Map<String, GroupIndex> buildGroupIndex(List<ChannelGroup> target) {
        Map<String, GroupIndex> index = new HashMap<>(Math.max(16, target.size() * 2));
        for (ChannelGroup g : target) {
            if (g == null || g.getGroupName() == null) continue;
            if (!index.containsKey(g.getGroupName())) {
                index.put(g.getGroupName(), new GroupIndex(g));
            }
        }
        return index;
    }

    /**
     * 单个分组的频道索引：精确频道名 + 归一化 key。
     *
     * <p>查找语义与"逐个 {@link ChannelNameMatcher#isSameChannel} 比较"一致：
     * 先精确同名，再取首个归一化 key 相同的频道（归一化为空的名字只能精确匹配）。
     * 索引在首次查找时才构建（只出现在单个源里的分组无需付出归一化代价），
     * 之后通过 {@link #add} 与分组内容保持同步。
     */
    private static final class GroupIndex {
        final ChannelGroup group;
        private Map<String, LiveChannel> byName;
        private Map<String, LiveChannel> byKey;

        GroupIndex(ChannelGroup group) {
            this.group = group;
        }

        private void ensureBuilt() {
            if (byName != null) return;
            List<LiveChannel> channels = group.getChannels();
            int cap = Math.max(16, channels.size() * 2);
            byName = new HashMap<>(cap);
            byKey = new HashMap<>(cap);
            for (LiveChannel ch : channels) put(ch);
        }

        private void put(LiveChannel ch) {
            if (ch == null) return;
            String name = ch.getChannelName();
            if (name == null || name.isEmpty()) return;
            if (!byName.containsKey(name)) byName.put(name, ch);
            String key = ChannelNameMatcher.normalizeKey(name);
            if (!key.isEmpty() && !byKey.containsKey(key)) byKey.put(key, ch);
        }

        LiveChannel find(String name) {
            if (name == null || name.isEmpty()) return null;
            ensureBuilt();
            LiveChannel exact = byName.get(name);
            if (exact != null) return exact;
            String key = ChannelNameMatcher.normalizeKey(name);
            return key.isEmpty() ? null : byKey.get(key);
        }

        /** 新频道追加到分组后同步索引；索引尚未构建时无需处理（构建时会扫描到） */
        void add(LiveChannel ch) {
            if (byName != null) put(ch);
        }
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
            return fetchPlainText(url, sourceBodyLimit());
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
            String content = fetchPlainText(aliasUrl.trim(), sourceBodyLimit());
            com.github.tvbox.osc.util.RunLog.i("loadAliasRules: 下载完成 "
                    + (content == null ? 0 : content.length()) + " 字符，开始解析");
            aliasService.parse(content);
            com.github.tvbox.osc.util.RunLog.i("loadAliasRules: 解析完成");
        } catch (Throwable e) {
            // 兜住 Error（超大文件 OOM 等）：改名规则失败只跳过改名，不能拖垮整轮拉源
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
            Body fetched = fetchPlain(listUrl.trim(), sourceBodyLimit());
            String content = fetched.text;
            String base = fetched.finalUrl != null ? fetched.finalUrl : listUrl.trim();

            // 订阅列表本身可能是 TVBox 多仓/单仓 JSON（含 Base64 / 图片包装），需要先展开出直播源地址
            if (MultiRepoParser.looksLikeTvBoxConfig(content)) {
                String cfg = MultiRepoParser.normalizeConfig(content);
                content = null;
                List<String> expanded = expandTvBoxConfig(cfg, base, callback);
                Set<String> seen = new LinkedHashSet<>(expanded);
                urls.addAll(seen);
                Timber.i("订阅列表(多仓)展开完成: %d 个直播源", urls.size());
                return urls;
            }

            // 纯文本列表：每行一个直播源地址
            Set<String> lines = new LinkedHashSet<>();
            for (String raw : content.split("\\r?\\n")) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                // 修正可能存在的 Markdown 转义残留
                line = line.replace("\\_", "_");
                if (line.regionMatches(true, 0, "http://", 0, 7) || line.regionMatches(true, 0, "https://", 0, 8)) {
                    lines.add(line);
                }
            }
            content = null;
            // 行里可能是多仓 / 单仓链接：与自定义源一样先并发探测并展开，否则多仓只会得到 0 个频道
            urls.addAll(expandCustomSources(new ArrayList<>(lines), callback));
            Timber.i("订阅列表加载完成: %d 行，展开后 %d 个源", lines.size(), urls.size());
        } catch (Throwable e) {
            // 兜住 Error（超大文件 OOM、畸形配置 StackOverflowError）：订阅列表失败只丢它自己
            Timber.w(e, "加载订阅列表失败");
        }
        return urls;
    }

    /** 一个子仓的展开结果：其中的直播源，以及（嵌套多仓时）其中的下一层子仓 */
    private static final class RepoResult {
        final List<MultiRepoParser.LiveEntry> lives;
        final List<String> subRepos;

        RepoResult(List<MultiRepoParser.LiveEntry> lives, List<String> subRepos) {
            this.lives = lives;
            this.subRepos = subRepos;
        }
    }

    /**
     * 把 TVBox 配置展开为直播源地址列表。
     *
     * <p>支持以下入口：
     * <ul>
     *   <li><b>多仓</b>（含 {@code urls} 或 {@code storeHouse} 字段）：逐个下载子仓配置，再从各自的
     *       {@code lives} 中提取直播源地址；子仓本身又是多仓时再展开一层（最多 {@link #MAX_REPO_DEPTH} 层，
     *       已访问地址去重防止循环引用）；</li>
     *   <li><b>单仓</b>（直接含 {@code lives}）：直接提取。</li>
     * </ul>
     *
     * <p>子仓下载采用并发 + 独立超时：多仓常包含数十个子仓，其中不少已失效，
     * 串行下载会让"获取订阅列表"这一步卡住数分钟。单个子仓失败只跳过它，
     * 不影响其余子仓。每条 lives 的 ua/header 记入 {@link #liveMeta}，拉取该直播源时使用。
     *
     * @param content 配置正文
     * @param baseUrl 配置自身地址（重定向后的最终地址），用于解析相对路径
     */
    private List<String> expandTvBoxConfig(String content, String baseUrl, FetchCallback callback) {
        String cfg = MultiRepoParser.normalizeConfig(content);
        Map<String, MultiRepoParser.LiveEntry> seen = new LinkedHashMap<>();

        // 入口本身就是单仓：直接取 lives
        for (MultiRepoParser.LiveEntry e : MultiRepoParser.extractLiveEntries(cfg, baseUrl)) {
            if (!seen.containsKey(e.url)) seen.put(e.url, e);
        }

        List<String> repoUrls = MultiRepoParser.extractRepoUrls(cfg, baseUrl);
        cfg = null;
        if (!repoUrls.isEmpty()) {
            Set<String> visited = new HashSet<>();
            if (baseUrl != null) visited.add(baseUrl);
            List<String> level = new ArrayList<>();
            for (String u : repoUrls) {
                if (visited.add(u) && level.size() < MAX_TOTAL_REPOS) level.add(u);
            }
            int budget = MAX_TOTAL_REPOS - level.size();
            for (int depth = 1; depth <= MAX_REPO_DEPTH && !level.isEmpty(); depth++) {
                Timber.i("多仓配置第 %d 层包含 %d 个子仓，开始并发展开", depth, level.size());
                if (callback != null) {
                    try {
                        callback.onProgress(0, 0, "正在展开多仓订阅(" + level.size() + " 个仓库)");
                    } catch (Throwable ignore) {
                    }
                }
                List<RepoResult> results = fetchRepos(level, depth < MAX_REPO_DEPTH);
                List<String> next = new ArrayList<>();
                // 按提交顺序合并，保证每次展开的源顺序稳定；下一层结果排在本层之后
                for (RepoResult r : results) {
                    for (MultiRepoParser.LiveEntry e : r.lives) {
                        if (!seen.containsKey(e.url)) seen.put(e.url, e);
                    }
                    for (String s : r.subRepos) {
                        if (budget <= 0) break;
                        if (visited.add(s)) {
                            next.add(s);
                            budget--;
                        }
                    }
                }
                level = next;
            }
        }

        List<String> result = new ArrayList<>(seen.size());
        for (MultiRepoParser.LiveEntry e : seen.values()) {
            result.add(e.url);
            // 同一直播源被多个仓库引用时保留首次出现的参数
            liveMeta.putIfAbsent(e.url, e);
        }
        if (!repoUrls.isEmpty()) Timber.i("多仓展开得到 %d 个直播源", result.size());
        return result;
    }

    /**
     * 并发下载一层子仓并解析。
     *
     * @param collectNested 是否同时提取子仓里的下一层子仓（嵌套多仓）
     * @return 与 repoUrls 一一对应的结果（失败/超时为空结果）
     */
    private List<RepoResult> fetchRepos(List<String> repoUrls, final boolean collectNested) {
        List<RepoResult> out = new ArrayList<>(repoUrls.size());
        // 低内存设备降低并发：并发下载会让 N 份配置正文同时驻留堆上，是拉源阶段的主要峰值来源
        int maxThreads = isLowMemoryDevice() ? REPO_FETCH_CONCURRENCY_LOW : REPO_FETCH_CONCURRENCY;
        int threads = Math.min(maxThreads, Math.max(1, repoUrls.size()));
        ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "RepoFetch");
            t.setDaemon(true);
            return t;
        });
        final RepoResult empty = new RepoResult(Collections.<MultiRepoParser.LiveEntry>emptyList(),
                Collections.<String>emptyList());
        List<Future<RepoResult>> futures = new ArrayList<>(repoUrls.size());
        try {
            for (final String repoUrl : repoUrls) {
                futures.add(pool.submit(() -> {
                    try {
                        Body b = fetchText(repoUrl, repoBodyLimit(), null);
                        // 子仓也可能是 Base64 / 图片包装；相对地址按重定向后的最终地址解析
                        String body = MultiRepoParser.normalizeConfig(b.text);
                        String base = b.finalUrl != null ? b.finalUrl : repoUrl;
                        List<MultiRepoParser.LiveEntry> lives = MultiRepoParser.extractLiveEntries(body, base);
                        List<String> subs = collectNested
                                ? MultiRepoParser.extractRepoUrls(body, base)
                                : Collections.<String>emptyList();
                        if (b.truncated && lives.isEmpty() && subs.isEmpty()) {
                            Timber.w("子仓 %s 超过 %d KB 被截断，未能解析", repoUrl, repoBodyLimit() / 1024);
                        }
                        return new RepoResult(lives, subs);
                    } catch (Throwable t) {
                        // 失效子仓很常见，降级为 debug 日志避免刷屏
                        Timber.d("子仓获取失败(%s): %s", repoUrl, t.getMessage());
                        return empty;
                    }
                }));
            }
            for (Future<RepoResult> f : futures) {
                RepoResult r = empty;
                try {
                    RepoResult got = f.get(REPO_FETCH_TIMEOUT_SEC, TimeUnit.SECONDS);
                    if (got != null) r = got;
                } catch (Exception e) {
                    // 单个子仓超时/异常不影响整体
                    f.cancel(true);
                }
                out.add(r);
            }
        } finally {
            pool.shutdownNow();
        }
        return out;
    }

    /**
     * 更新外部源的连续未入选计数；自定义源连续达到上限则自动删除。
     *
     * <p>只有"用户原始填写、且没有被展开为多仓/单仓配置"的自定义地址可被自动删除：
     * 多仓展开出的子直播源只统计不删除，删除后写回的也是原始列表（重新读取 Hawk 再剔除），
     * 绝不会把展开结果固化进设置。拉取失败但未进入 sourceUrlMap 的源（网络受损、
     * 整体超时、被取消）计数保持不变。
     *
     * @param rawCustomUrls      用户原始填写的自定义源（未展开）
     * @param expandedCustomUrls 展开后的自定义源（原始地址未被展开时原样出现在其中）
     * @param sourceUrlMap       每个源产出的 URL 集合（仅含本轮有明确结论的源）
     * @param finalUrls          最终保留的所有线路 URL
     * @param knownSources       本轮全部外部源地址，用于清理已不存在的源的历史计数
     * @param configUrls         本轮探测确认为 TVBox 配置的原始地址：配置仍能正常下载说明链接有效，
     *                           展开不出直播源多半是子仓/网络问题，绝不自动删除
     */
    private void updateSourceFailCounts(List<String> rawCustomUrls, List<String> expandedCustomUrls,
                                        Map<String, Set<String>> sourceUrlMap, Set<String> finalUrls,
                                        Set<String> knownSources, Set<String> configUrls) {
        JSONObject counts;
        try {
            counts = new JSONObject(Hawk.get(HawkConfig.IPTV_SOURCE_FAIL_COUNT, "{}"));
        } catch (Exception e) {
            counts = new JSONObject();
        }

        // 可删除集合 = 原始自定义源中未被展开（展开后仍原样存在）的地址
        Set<String> expandedSet = new HashSet<>(expandedCustomUrls);
        Set<String> deletable = new HashSet<>();
        for (String u : rawCustomUrls) {
            if (expandedSet.contains(u) && (configUrls == null || !configUrls.contains(u))) deletable.add(u);
        }
        List<String> toRemove = new ArrayList<>();

        // 遍历所有外部源，判断是否"入选"（其产出 URL 与最终结果有交集）。
        // 合并阶段按实际播放地址去重（同一流的不同 $标签 只留一条、无请求头的会被带请求头的同址线路替换），
        // 因此这里同样按 playUrl 比较；只比原始串会把镜像源一直判为"未入选"，3 次后被误删。
        Set<String> finalPlays = new HashSet<>(Math.max(16, finalUrls.size() * 2));
        for (String u : finalUrls) finalPlays.add(StreamUrl.playUrl(u));
        Set<String> allExternal = new HashSet<>(sourceUrlMap.keySet());
        for (String src : allExternal) {
            boolean selected = false;
            Set<String> produced = sourceUrlMap.get(src);
            if (produced != null) {
                for (String u : produced) {
                    if (finalUrls.contains(u) || finalPlays.contains(StreamUrl.playUrl(u))) {
                        selected = true;
                        break;
                    }
                }
            }

            if (selected) {
                counts.remove(src); // 入选则清零
            } else {
                int c = counts.optInt(src, 0) + 1;
                try { counts.put(src, c); } catch (Exception ignored) {}
                // 仅对用户自定义源执行自动删除（订阅列表为远程文件，本地删除无意义）
                if (deletable.contains(src) && c >= HawkConfig.MAX_SOURCE_FAIL_COUNT) {
                    toRemove.add(src);
                }
            }
        }

        // 清理本轮已不存在的源的历史计数，避免多仓子地址不断累积撑大该配置
        if (knownSources != null) {
            List<String> stale = new ArrayList<>();
            java.util.Iterator<String> it = counts.keys();
            while (it.hasNext()) {
                String k = it.next();
                if (!knownSources.contains(k)) stale.add(k);
            }
            for (String k : stale) counts.remove(k);
        }

        // 执行自动删除：基于当前设置重新读取再剔除，不覆盖用户在刷新期间做的修改
        if (!toRemove.isEmpty()) {
            Set<String> removeSet = new HashSet<>(toRemove);
            List<String> remain = new ArrayList<>();
            for (String u : parseCustomSourceUrls(Hawk.get(HawkConfig.IPTV_CUSTOM_SOURCES, ""))) {
                if (!removeSet.contains(u)) remain.add(u);
            }
            Hawk.put(HawkConfig.IPTV_CUSTOM_SOURCES, buildCustomSourcesJson(remain));
            for (String u : toRemove) counts.remove(u);
            Timber.i("自动删除连续 %d 次未入选的自定义源: %s",
                    HawkConfig.MAX_SOURCE_FAIL_COUNT, toRemove);
        }

        Hawk.put(HawkConfig.IPTV_SOURCE_FAIL_COUNT, counts.toString());
    }

    /**
     * 把自定义源列表序列化为 JSON 数组字符串（与 {@link #parseCustomSourceUrls} 对应）。
     *
     * <p>用 JSONArray 生成，正确转义引号/反斜杠等字符；原手拼字符串会直接删掉 URL 里的引号。
     * 设置页 / 手机配置页共用，保证各处写入的格式一致。
     */
    public static String buildCustomSourcesJson(List<String> urls) {
        JSONArray arr = new JSONArray();
        if (urls != null) {
            for (String u : urls) {
                if (u != null) arr.put(u);
            }
        }
        return arr.toString();
    }

    /**
     * 拉取纯文本（用于频道模板、改名规则）。
     * 有上限地读取：地址误指向超大文件 / 无尽流时，body().string() 会把整份读进内存直接 OOM。
     */
    private String fetchPlainText(String url, int maxBytes) throws IOException {
        return fetchPlain(url, maxBytes).text;
    }

    /** 拉取纯文本并带回重定向后的最终地址（订阅列表本身是配置时，相对地址按它解析） */
    private Body fetchPlain(String url, int maxBytes) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", "TVBoxOS-Live/1.0")
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("模板请求失败: HTTP " + response.code());
            }
            return readBody(response, maxBytes, false);
        }
    }

    /**
     * 展开自定义源 / 多仓链接 / 订阅列表行中的 TVBox 多仓/单仓链接。
     *
     * <p>用户可能把一个多仓地址（如 {@code https://xmbjm.fh4u.org/dc.txt}）直接加为源。
     * 这类地址本身不含频道，直接当直播源解析会得到 0 个频道。
     * 这里先探测内容：
     * <ul>
     *   <li>是 TVBox 配置 → 展开为其中的直播源地址，替换掉原地址；</li>
     *   <li>不是 → 原样保留，交给既有流程按 TXT/M3U 解析。</li>
     * </ul>
     *
     * <p>探测并发执行并有整体时限：原先逐个串行探测，每个失效主机都要等满连接超时，
     * 十几个源就能让"正在获取直播源"卡上几分钟。探测只读前 16KB 判断类型，
     * 普通播放列表不会被完整下载两遍。超时未完成的探测按普通源保留，结果仍按原顺序合并。
     */
    private List<String> expandCustomSources(List<String> customUrls, FetchCallback callback) {
        if (customUrls == null || customUrls.isEmpty()) return new ArrayList<>();
        List<String> inputs = new ArrayList<>();
        Set<String> inputSet = new HashSet<>();
        for (String url : customUrls) {
            if (url == null || url.trim().isEmpty()) continue;
            String u = url.trim();
            if (inputSet.add(u)) inputs.add(u);
        }
        Set<String> seen = new LinkedHashSet<>();
        if (inputs.isEmpty()) return new ArrayList<>();

        int window = isLowMemoryDevice() ? SOURCE_FETCH_CONCURRENCY_LOW : SOURCE_FETCH_CONCURRENCY;
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(window, inputs.size()), r -> {
            Thread t = new Thread(r, "SourceProbe");
            t.setDaemon(true);
            return t;
        });
        List<Future<Body>> futures = new ArrayList<>(inputs.size());
        try {
            for (final String u : inputs) {
                if (isObviousPlaylist(u)) {
                    // 扩展名明确是 m3u/m3u8 的不可能是配置，不必探测
                    futures.add(null);
                    continue;
                }
                futures.add(pool.submit(() -> probeConfig(u)));
            }
            if (callback != null) {
                try {
                    callback.onProgress(0, 0, "正在识别源类型...");
                } catch (Throwable ignore) {
                }
            }
            long deadline = System.currentTimeMillis() + PROBE_PHASE_TIMEOUT_MS;
            for (int i = 0; i < inputs.size(); i++) {
                String u = inputs.get(i);
                Future<Body> f = futures.get(i);
                futures.set(i, null);
                Body probe = null;
                if (f != null) {
                    try {
                        // 已完成的探测不受整体时限影响（前面的展开耗时不应让它们作废）
                        long remain = Math.max(0L, deadline - System.currentTimeMillis());
                        probe = f.isDone() ? f.get() : f.get(remain, TimeUnit.MILLISECONDS);
                    } catch (TimeoutException e) {
                        f.cancel(true);
                        Timber.d("自定义源探测超时，按普通源保留(%s)", u);
                    } catch (Exception e) {
                        Throwable cause = (e instanceof ExecutionException && e.getCause() != null)
                                ? e.getCause() : e;
                        // 探测失败不影响原地址使用。明确连不上主机且设备确实在线时记下，
                        // 拉源阶段只拨号一次、不再换路由重试；
                        // 断网时不记（开机 Wi-Fi 未就绪，拉源阶段还应再试一次）
                        if (cause instanceof IOException && isConnectFailure(cause) && isNetworkConnected()) {
                            probeConnectFailures.put(u, (IOException) cause);
                        }
                        Timber.d("自定义源探测失败(%s): %s", u, cause.getMessage());
                    }
                }
                if (probe != null && !probe.notConfig && !probe.text.isEmpty()
                        && MultiRepoParser.parsesAsConfig(probe.text)) {
                    probedConfigUrls.add(u);
                    List<String> expanded = new ArrayList<>();
                    try {
                        String base = probe.finalUrl != null ? probe.finalUrl : u;
                        String cfg = probe.text;
                        probe = null;
                        expanded = expandTvBoxConfig(cfg, base, callback);
                    } catch (Exception | StackOverflowError e) {
                        // 单个畸形配置只回退为原地址，不能中断其余源的展开
                        Timber.w("展开 TVBox 配置失败(%s): %s", u, e);
                    }
                    if (!expanded.isEmpty()) {
                        Timber.i("自定义源 %s 为 TVBox 配置，展开出 %d 个直播源", u, expanded.size());
                        seen.addAll(expanded);
                        continue;
                    }
                    // 展开不出直播源（例如只有 sites 没有 lives、子仓全部失效）：保留原地址，
                    // 拉源阶段直接按"配置无直播源"处理，不计入未入选，避免误删用户配置
                    probedEmptyConfigs.add(u);
                }
                seen.add(u);
            }
        } finally {
            pool.shutdownNow();
        }
        return new ArrayList<>(seen);
    }

    /** 路径扩展名为 .m3u / .m3u8 的地址（忽略查询参数） */
    private static boolean isObviousPlaylist(String url) {
        String p = url;
        int q = p.indexOf('?');
        if (q >= 0) p = p.substring(0, q);
        int h = p.indexOf('#');
        if (h >= 0) p = p.substring(0, h);
        p = p.toLowerCase(java.util.Locale.ROOT);
        return p.endsWith(".m3u") || p.endsWith(".m3u8");
    }

    /**
     * 解析自定义源URL列表（静态，供设置页 / 手机配置页复用）
     * 优先按标准 JSON 数组解析（能正确处理 URL 中的逗号与 "\/" 等转义），兜底再走简单字符串切分。
     */
    public static List<String> parseCustomSourceUrls(String json) {
        List<String> urls = new ArrayList<>();
        if (json == null || json.isEmpty()) return urls;

        // 优先按标准 JSON 数组解析（能正确处理带逗号/引号的 URL）
        try {
            JSONArray arr = new JSONArray(json.trim());
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
