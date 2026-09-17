package com.github.tvbox.osc.api;

import android.text.TextUtils;
import android.util.Xml;

import com.github.tvbox.osc.bean.EpgProgram;
import com.github.tvbox.osc.util.HawkConfig;
import com.orhanobut.hawk.Hawk;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import timber.log.Timber;

/**
 * EPG 节目单服务
 *
 * 使用 diyp / 百川 JSON 接口（应用最广），形如：
 *   https://host/?ch=频道名&date=2024-01-01
 * 返回示例：
 *   {"date":"2024-01-01","channel_name":"CCTV1",
 *    "epg_data":[{"start":"06:00","end":"07:00","title":"朝闻天下"}, ...]}
 *
 * 结果按频道名 + 日期缓存，避免频繁请求。
 */
public class EpgService {

    private static volatile EpgService instance;

    private final OkHttpClient client;
    private final ExecutorService executor;

    /** 单频道节目缓存最大条数（LRU，防止长期运行后无限增长） */
    private static final int MAX_CHANNEL_CACHE = 24;

    /**
     * 频道节目缓存（LRU）。键为 频道名@日期。
     * 用 LinkedHashMap accessOrder 实现，超过上限自动淘汰最久未使用项，避免内存无界增长。
     */
    private final Map<String, List<EpgProgram>> cache =
            java.util.Collections.synchronizedMap(
                    new java.util.LinkedHashMap<String, List<EpgProgram>>(32, 0.75f, true) {
                        @Override
                        protected boolean removeEldestEntry(Map.Entry<String, List<EpgProgram>> eldest) {
                            return size() > MAX_CHANNEL_CACHE;
                        }
                    });

    /**
     * XMLTV 解析结果缓存。
     *
     * <p>内存中<b>只保留当前源频道</b>的节目：解析时用当前频道列表做白名单过滤，
     * 整份节目单里其它上千个频道的数据不会进入内存。
     *
     * <p>同时配合本地磁盘缓存，超过 {@link #CACHE_TTL_MS} 才重新下载。
     */
    private volatile XmltvData xmltvCache;
    private volatile String xmltvCacheKey;
    private volatile long xmltvCacheAt;

    /** XMLTV 解压后最大读取字节数（超出即截断，防止超大文件 OOM） */
    private static final int MAX_XMLTV_BYTES = 8 * 1024 * 1024;

    /** 单次解析最多保留的节目条数上限（内存保护） */
    private static final int MAX_PROGRAMMES = 20000;

    /** EPG 缓存有效期：6 小时内不重新下载 */
    private static final long CACHE_TTL_MS = 6 * 60 * 60 * 1000L;

    /** 本地缓存目录与文件名 */
    private static final String CACHE_DIR = "epg";
    private static final String CACHE_FILE = "epg_cache.txt";

    /** 缓存文件字段分隔符（正文中不会出现的控制字符） */
    private static final String SEP = "\u0001";

    /**
     * 解析后的 XMLTV 数据。
     *以"频道归一化key@yyyyMMdd"为索引，查询为 O(1)，且无需再保存频道名映射表。
     */
    private static class XmltvData {
        final Map<String, List<EpgProgram>> byKeyDate = new HashMap<>();
        int programmeCount;
    }

    /** 限制最多读取 maxBytes 字节的输入流，超过后即视为流结束 */
    private static class LimitedInputStream extends java.io.FilterInputStream {
        private final long maxBytes;
        private long read;

        LimitedInputStream(InputStream in, long maxBytes) {
            super(in);
            this.maxBytes = maxBytes;
        }

        @Override
        public int read() throws java.io.IOException {
            if (read >= maxBytes) return -1;
            int b = super.read();
            if (b >= 0) read++;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws java.io.IOException {
            if (read >= maxBytes) return -1;
            long remaining = maxBytes - read;
            if (len > remaining) len = (int) remaining;
            int n = super.read(b, off, len);
            if (n > 0) read += n;
            return n;
        }
    }

    public interface EpgCallback {
        void onEpgLoaded(List<EpgProgram> programs, EpgProgram current);
        void onEpgError(String msg);
    }

    private EpgService() {
        // 从全局共享客户端派生，复用同一套连接池与线程池，仅覆盖超时配置
        client = com.github.tvbox.osc.util.HttpClients.newBuilder(8, 10);
        // 设为守护线程 + 低优先级：本服务是永久单例、executor 从不 shutdown，
        // 非守护线程会阻止进程正常退出；EPG 解析也不应与播放/UI 抢 CPU。
        executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "EpgService");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
    }

    public static EpgService getInstance() {
        if (instance == null) {
            synchronized (EpgService.class) {
                if (instance == null) instance = new EpgService();
            }
        }
        return instance;
    }

    /**
     * 异步加载某频道今日节目单
     */
    public void loadEpg(String channelName, EpgCallback callback) {
        loadEpg(channelName, todayDate(), callback);
    }

    /**
     * 异步加载某频道指定日期的节目单。
     *
     * @param date 日期，格式 yyyy-MM-dd
     */
    public void loadEpg(String channelName, String date, EpgCallback callback) {
        boolean enabled = Hawk.get(HawkConfig.IPTV_ENABLE_EPG, true);
        if (!enabled || TextUtils.isEmpty(channelName)) {
            if (callback != null) callback.onEpgError("EPG 未启用");
            return;
        }
        if (TextUtils.isEmpty(date)) date = todayDate();

        final String queryDate = date;
        final String cacheKey = channelName + "@" + queryDate;

        List<EpgProgram> cached = cache.get(cacheKey);
        if (cached != null) {
            if (callback != null) callback.onEpgLoaded(cached, findCurrentForDate(cached, queryDate));
            return;
        }

        executor.execute(() -> {
            try {
                List<EpgProgram> programs = fetchEpg(channelName, queryDate);
                if (programs.isEmpty()) {
                    if (callback != null) callback.onEpgError("暂无节目单");
                    return;
                }
                cache.put(cacheKey, programs);
                if (callback != null) callback.onEpgLoaded(programs, findCurrentForDate(programs, queryDate));
            } catch (Throwable e) {
                Timber.w(e, "加载 EPG 失败: %s @ %s", channelName, queryDate);
                if (callback != null) {
                    try { callback.onEpgError("EPG 获取失败"); } catch (Exception ignore) {}
                }
            }
        });
    }

    /** 今天日期字符串 yyyy-MM-dd */
    public static String todayDate() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(new Date());
    }

    /** 相对今天前后若干天的日期字符串（如 -1=昨天，+1=明天） */
    public static String offsetDate(int dayOffset) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.add(java.util.Calendar.DAY_OF_YEAR, dayOffset);
        return new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(c.getTime());
    }

    /** 只有当查询日期是今日时才认定"当前节目"，其它日期都不高亮 */
    private EpgProgram findCurrentForDate(List<EpgProgram> programs, String date) {
        if (!todayDate().equals(date)) return null;
        return findCurrent(programs);
    }

    /** 找出当前正在播出的节目 */
    public EpgProgram findCurrent(List<EpgProgram> programs) {
        if (programs == null || programs.isEmpty()) return null;
        String now = new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date());
        for (EpgProgram p : programs) {
            if (p.isPlayingAt(now)) return p;
        }
        return null;
    }

    public void clearCache() {
        cache.clear();
        xmltvCache = null;
        xmltvCacheKey = null;
        xmltvCacheAt = 0;
    }

    /**
     * 清除 EPG 本地磁盘缓存，下次会重新下载。
     * 注意：{@link #clearCache()} 只清内存（供低内存回收使用），不删本地文件，
     * 以便内存回收后仍能从本地快速恢复、不必联网。
     */
    public void clearDiskCache() {
        try {
            java.io.File f = cacheFile();
            if (f != null && f.exists() && f.delete()) {
                Timber.i("已清除 EPG 本地缓存");
            }
        } catch (Throwable t) {
            Timber.w(t, "清除 EPG 本地缓存失败");
        }
        clearCache();
    }

    // ============ 私有 ============

    private List<EpgProgram> fetchEpg(String channelName, String date) throws Exception {
        String base = Hawk.get(HawkConfig.IPTV_EPG_URL, HawkConfig.DEFAULT_IPTV_EPG_URL);
        if (TextUtils.isEmpty(base)) base = HawkConfig.DEFAULT_IPTV_EPG_URL;
        base = base.trim();

        // XMLTV 源：地址通常以 .xml / .xml.gz 结尾，直接解析整份 XMLTV
        String lower = base.toLowerCase();
        if (lower.endsWith(".xml") || lower.endsWith(".xml.gz") || lower.endsWith(".gz")) {
            return fetchXmltv(base, channelName, date);
        }

        // 否则按 diyp JSON 接口请求
        String diypBase = base.replaceAll("/+$", "");
        String url = diypBase + "/?ch=" + encode(channelName) + "&date=" + date;
        String body = httpGet(url);
        List<EpgProgram> diyp = parseDiyp(body);
        if (!diyp.isEmpty()) return diyp;

        // diyp 无结果且返回内容像 XMLTV，则尝试按 XMLTV 解析
        if (body != null && body.contains("<tv")) {
            return parseXmltvString(body, channelName, date);
        }
        return diyp;
    }

    /** 拉取并解析 XMLTV 源（仅保留当前源频道，配合 6 小时磁盘缓存） */
    private List<EpgProgram> fetchXmltv(String url, String channelName, String date) throws Exception {
        XmltvData data = getOrBuildXmltv(url);
        return queryFromXmltv(data, channelName, date);
    }

    /**
     * 获取或构建 XMLTV 数据，三级来源依次尝试：
     * <ol>
     *   <li><b>内存</b>：同一URL 且未超过 6 小时 → 直接复用</li>
     *   <li><b>磁盘</b>：本地缓存文件未过期且频道集合未变 → 读本地，<b>不联网</b>（重启后依然可用）</li>
     *   <li><b>网络</b>：下载 + 流式解析（按当前频道过滤）+ 写入磁盘缓存</li>
     * </ol>
     */
    private XmltvData getOrBuildXmltv(String url) throws Exception {
        // 当前源频道的归一化 key 白名单：只有这些频道的节目会进入内存
        Set<String> wantedKeys = currentChannelKeys();
        String key = url + "|" + wantedKeys.size() + "|" + keysFingerprint(wantedKeys);

        // 1) 内存缓存
        // 只读一次引用：clearCache() 由主线程的 onTrimMemory 调用，
        // 若分别读取 xmltvCache 两次，可能第一次非空、第二次已被置空而返回 null
        XmltvData cached = xmltvCache;
        if (cached != null && key.equals(xmltvCacheKey)
                && System.currentTimeMillis() - xmltvCacheAt < CACHE_TTL_MS) {
            return cached;
        }

        // 2)磁盘缓存（未过期且频道集合一致）
        XmltvData fromDisk = loadFromDisk(key);
        if (fromDisk != null) {
            xmltvCache = fromDisk;
            xmltvCacheKey = key;
            xmltvCacheAt = diskCacheTime();
            Timber.i("EPG 使用本地缓存: %d 条节目（未超过 6 小时，不联网）", fromDisk.programmeCount);
            return fromDisk;
        }

        // 3) 联网下载并解析
        okhttp3.Request request = new okhttp3.Request.Builder()
                .url(url)
                .header("User-Agent", "okhttp/4.12.0")
                .header("Accept-Encoding", "gzip")
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new Exception("HTTP " + response.code());
            }
            InputStream in = response.body().byteStream();
            String enc = response.header("Content-Encoding", "");
            if (url.toLowerCase(Locale.ROOT).endsWith(".gz") || (enc != null && enc.contains("gzip"))) {
                in = new java.util.zip.GZIPInputStream(in);
            }
            // 硬性限制读取字节数（解压后），避免超大 XMLTV 撑爆内存导致主线程后续分配 OOM。
            in = new LimitedInputStream(in, MAX_XMLTV_BYTES);
            // 流式解析：边读边丢弃非当前源频道的节目，内存只留下需要的部分
            XmltvData data = parseXmltvStream(in, wantedKeys);
            xmltvCache = data;
            xmltvCacheKey = key;
            xmltvCacheAt = System.currentTimeMillis();
            saveToDisk(key, data);
            Timber.i("EPG 已下载解析: %d 条节目（仅当前源 %d 个频道）",
                    data.programmeCount, wantedKeys.size());
            return data;
        }
    }

    /** 当前源频道名的归一化 key 集合；为空表示不过滤（尚未加载频道列表时） */
    private Set<String> currentChannelKeys() {
        Set<String> keys = new HashSet<>();
        try {
            com.github.tvbox.osc.App app = com.github.tvbox.osc.App.getInstance();
            if (app == null) return keys;
            Set<String> names = ChannelManager.getInstance(app).getAllChannelNames();
            for (String n : names) {
                String k = com.github.tvbox.osc.util.ChannelNameMatcher.normalizeKey(n);
                if (k != null && !k.isEmpty()) keys.add(k);
            }
        } catch (Throwable t) {
            Timber.w(t, "获取当前频道集合失败，EPG 将不做频道过滤");
        }
        return keys;
    }

    /** 频道集合指纹：集合变化时使缓存失效，避免新频道查不到节目 */
    private String keysFingerprint(Set<String> keys) {
        int h = 0;
        for (String k : keys) h += k.hashCode();
        return Integer.toHexString(h);
    }

    // ============ 磁盘缓存 ============

    private java.io.File cacheFile() {
        try {
            com.github.tvbox.osc.App app = com.github.tvbox.osc.App.getInstance();
            if (app == null) return null;
            java.io.File dir = new java.io.File(app.getFilesDir(), CACHE_DIR);
            if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory()) return null;
            return new java.io.File(dir, CACHE_FILE);
        } catch (Throwable t) {
            return null;
        }
    }

    private long diskCacheTime() {
        java.io.File f = cacheFile();
        return f != null && f.isFile() ? f.lastModified() : 0L;
    }

    /**
     * 读取本地缓存。仅当文件存在、未超过 6 小时、且首行的 key 与当前一致时才返回。
     */
    private XmltvData loadFromDisk(String key) {
        java.io.File f = cacheFile();
        if (f == null || !f.isFile() || f.length() == 0) return null;
        if (System.currentTimeMillis() - f.lastModified() >= CACHE_TTL_MS) {
            Timber.d("EPG 本地缓存已超过 6 小时，需要更新");
            return null;
        }
        Set<String> allowedDates = allowedDateWindow();
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"))) {
            String header = reader.readLine();
            if (header == null || !header.equals(key)) {
                Timber.d("EPG 本地缓存与当前源不匹配，需要重新解析");
                return null;
            }
            XmltvData data = new XmltvData();
            String line;
            while ((line = reader.readLine()) != null) {
                String[] p = line.split(SEP, -1);
                if (p.length < 5) continue;
                // 跳过已滑出日期窗口的旧数据
                if (!allowedDates.contains(p[1])) continue;
                String mapKey = p[0] + "@" + p[1];
                List<EpgProgram> list = data.byKeyDate.get(mapKey);
                if (list == null) {
                    list = new ArrayList<>();
                    data.byKeyDate.put(mapKey, list);
                }
                list.add(new EpgProgram(p[2], p[3], p[4]));
                data.programmeCount++;
            }
            return data.programmeCount > 0 ? data : null;
        } catch (Throwable t) {
            Timber.w(t, "读取 EPG 本地缓存失败");
            return null;
        }
    }

    /** 写入本地缓存（先写临时文件再改名，避免中断产生损坏文件） */
    private void saveToDisk(String key, XmltvData data) {
        java.io.File f = cacheFile();
        if (f == null || data == null || data.byKeyDate.isEmpty()) return;
        java.io.File tmp = new java.io.File(f.getAbsolutePath() + ".tmp");
        try (java.io.BufferedWriter w = new java.io.BufferedWriter(
                new java.io.OutputStreamWriter(new java.io.FileOutputStream(tmp), "UTF-8"))) {
            w.write(key);
            w.write("\n");
            for (Map.Entry<String, List<EpgProgram>> e : data.byKeyDate.entrySet()) {
                String mapKey = e.getKey();
                int at = mapKey.lastIndexOf('@');
                if (at <= 0) continue;
                String chKey = mapKey.substring(0, at);
                String date = mapKey.substring(at + 1);
                for (EpgProgram p : e.getValue()) {
                    if (p == null) continue;
                    w.write(chKey);
                    w.write(SEP);
                    w.write(date);
                    w.write(SEP);
                    w.write(nz(p.getStart()));
                    w.write(SEP);
                    w.write(nz(p.getEnd()));
                    w.write(SEP);
                    // 标题里去掉分隔符与换行，保证一行一条记录
                    w.write(nz(p.getTitle()).replace(SEP, " ").replace("\n", " ").replace("\r", " "));
                    w.write("\n");
                }
            }
            w.flush();
        } catch (Throwable t) {
            Timber.w(t, "写入 EPG 本地缓存失败");
            try { tmp.delete(); } catch (Throwable ignore) {}
            return;
        }
        try {
            if (f.exists() && !f.delete()) {
                Timber.w("删除旧 EPG 缓存失败");
            }
            if (!tmp.renameTo(f)) {
                tmp.delete();
            }
        } catch (Throwable t) {
            Timber.w(t, "替换 EPG 缓存文件失败");
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** 解析 XMLTV 文本（用于 diyp 接口回退返回 XMLTV 的小体积场景，不缓存） */
    private List<EpgProgram> parseXmltvString(String xml, String channelName, String date) {
        if (xml == null || xml.isEmpty()) return new ArrayList<>();
        try {
            // 单频道回退场景：只保留该频道
            Set<String> only = new HashSet<>();
            String k = com.github.tvbox.osc.util.ChannelNameMatcher.normalizeKey(channelName);
            if (k != null && !k.isEmpty()) only.add(k);
            XmltvData data = parseXmltvStream(new ByteArrayInputStream(xml.getBytes("UTF-8")), only);
            return queryFromXmltv(data, channelName, date);
        } catch (Throwable e) {
            Timber.w(e, "解析 XMLTV 文本失败");
            return new ArrayList<>();
        }
    }

    /**
     * 用 XmlPullParser 流式解析 XMLTV。
     *
     * <p>两重裁剪保证内存占用最小：
     * <ul>
     *   <li><b>频道</b>：只保留 {@code wantedKeys} 中的频道（即当前源里存在的频道）；
     *       其它频道的 programme 直接丢弃，不进内存。{@code wantedKeys} 为空时不做频道过滤。</li>
     *   <li><b>日期</b>：只保留日期窗口（昨天~明天）内的节目。</li>
     * </ul>
     *
     * <p>属性顺序无关（channel/start/stop 任意顺序均可），兼容 51zmt 等各类 XMLTV。
     */
    private XmltvData parseXmltvStream(InputStream in, Set<String> wantedKeys) throws Exception {
        XmltvData data = new XmltvData();
        Set<String> allowedDates = allowedDateWindow();
        boolean filterChannels = wantedKeys != null && !wantedKeys.isEmpty();
        // XMLTV 中<channel> 段在前，先建立 频道id -> 归一化key 的映射（只含需要的频道）
        Map<String, String> idToKey = new HashMap<>();

        XmlPullParser parser = Xml.newPullParser();
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        parser.setInput(in, null); // 自动识别编码

        String curChannelId = null;
        String curMatchedKey = null;
        String pStart = null, pStop = null, pChannel = null, pDate = null;
        boolean keepProg = false;

        try {
            int event = parser.getEventType();
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    String tag = parser.getName();
                    if ("channel".equalsIgnoreCase(tag)) {
                        curChannelId = parser.getAttributeValue(null, "id");
                        curMatchedKey = null;
                    } else if ("display-name".equalsIgnoreCase(tag) && curChannelId != null) {
                        String txt = safeNextText(parser);
                        if (txt != null && !txt.trim().isEmpty() && curMatchedKey == null) {
                            String k = com.github.tvbox.osc.util.ChannelNameMatcher
                                    .normalizeKey(txt.trim());
                            if (k != null && !k.isEmpty()
                                    && (!filterChannels || wantedKeys.contains(k))) {
                                curMatchedKey = k;
                            }
                        }
                    } else if ("programme".equalsIgnoreCase(tag)) {
                        pStart = parser.getAttributeValue(null, "start");
                        pStop = parser.getAttributeValue(null, "stop");
                        pChannel = parser.getAttributeValue(null, "channel");
                        pDate = (pStart != null && pStart.length() >= 8) ? pStart.substring(0, 8) : null;
                        // 频道不在白名单内则整条跳过，连title 都不读取
                        keepProg = pChannel != null && pDate != null
                                && allowedDates.contains(pDate)
                                && idToKey.containsKey(pChannel)
                                && data.programmeCount < MAX_PROGRAMMES;
                    } else if ("title".equalsIgnoreCase(tag) && keepProg) {
                        String title = safeNextText(parser);
                        if (title != null && !title.trim().isEmpty()) {
                            String chKey = idToKey.get(pChannel);
                            String k = chKey + "@" + pDate;
                            List<EpgProgram> list = data.byKeyDate.get(k);
                            if (list == null) {
                                list = new ArrayList<>();
                                data.byKeyDate.put(k, list);
                            }
                            list.add(new EpgProgram(hhmm(pStart), hhmm(pStop), title.trim()));
                            data.programmeCount++;
                        }
                        keepProg = false; // 每条 programme 只取首个 title
                    }
                } else if (event == XmlPullParser.END_TAG) {
                    String tag = parser.getName();
                    if ("channel".equalsIgnoreCase(tag)) {
                        if (curChannelId != null && curMatchedKey != null) {
                            idToKey.put(curChannelId, curMatchedKey);
                        }
                        curChannelId = null;
                        curMatchedKey = null;
                    } else if ("programme".equalsIgnoreCase(tag)) {
                        keepProg = false;
                        pStart = pStop = pChannel = pDate = null;
                    }
                }
                event = parser.next();
            }
        } catch (Throwable t) {
            // 截断/畸形导致解析中断时，返回已解析的部分，避免整份丢失或崩溃
            Timber.w("XMLTV 解析提前结束(可能被截断): %s，已解析 %d 频道/%d 节目",
                    t.getMessage(), idToKey.size(), data.programmeCount);
        }
        return data;
    }

    /**
     * 从已解析的 XMLTV 数据中取指定频道当天的节目。
     * 直接用归一化 key 命中，O(1)，无需再遍历所有频道做模糊匹配。
     */
    private List<EpgProgram> queryFromXmltv(XmltvData data, String channelName, String date) {
        List<EpgProgram> result = new ArrayList<>();
        if (data == null || channelName == null || date == null) return result;
        String key = com.github.tvbox.osc.util.ChannelNameMatcher.normalizeKey(channelName);
        if (key == null || key.isEmpty()) return result;
        String compact = date.replace("-", "");
        List<EpgProgram> ps = data.byKeyDate.get(key + "@" + compact);
        if (ps != null) result.addAll(ps);
        return result;
    }

    /** 允许保留的日期窗口（昨天 ~ 明天）的 yyyyMMdd 集合，窗口越小内存占用越低 */
    private Set<String> allowedDateWindow() {
        Set<String> set = new HashSet<>();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd", Locale.CHINA);
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.add(java.util.Calendar.DAY_OF_YEAR, -1);
        for (int i = 0; i < 3; i++) {
            set.add(sdf.format(c.getTime()));
            c.add(java.util.Calendar.DAY_OF_YEAR, 1);
        }
        return set;
    }

    private String safeNextText(XmlPullParser p) {
        try {
            return p.nextText();
        } catch (Exception e) {
            return null;
        }
    }

    private String httpGet(String url) throws Exception {
        okhttp3.Request request = new okhttp3.Request.Builder()
                .url(url)
                .header("User-Agent", "okhttp/4.12.0")
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new Exception("HTTP " + response.code());
            }
            return response.body().string();
        }
    }

    private List<EpgProgram> parseDiyp(String json) {
        List<EpgProgram> list = new ArrayList<>();
        try {
            JSONObject obj = new JSONObject(json);
            JSONArray arr = obj.optJSONArray("epg_data");
            if (arr == null) return list;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject item = arr.optJSONObject(i);
                if (item == null) continue;
                String start = item.optString("start", "");
                String end = item.optString("end", "");
                String title = item.optString("title", item.optString("desc", ""));
                if (!title.isEmpty()) {
                    list.add(new EpgProgram(start, end, title));
                }
            }
        } catch (Exception e) {
            Timber.w(e, "解析 EPG JSON 失败");
        }
        return list;
    }

    /** 从 XMLTV 时间 "20240101060000 +0800" 取 HH:mm */
    private String hhmm(String t) {
        if (t == null || t.length() < 12) return "";
        return t.substring(8, 10) + ":" + t.substring(10, 12);
    }

    private String encode(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }
}
