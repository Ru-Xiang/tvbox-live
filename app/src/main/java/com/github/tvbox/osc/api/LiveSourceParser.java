package com.github.tvbox.osc.api;

import com.github.tvbox.osc.bean.ChannelGroup;
import com.github.tvbox.osc.bean.LiveChannel;
import com.github.tvbox.osc.util.ChannelDeduper;
import com.github.tvbox.osc.util.StreamUrl;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import timber.log.Timber;

/**
 * 直播源解析器
 * 
 * 支持解析以下格式：
 * 1. M3U/M3U8 格式 - 标准 IPTV 播放列表格式
 * 2. TXT 格式 - iptv-api 输出的纯文本格式
 * 3. 自动识别混合格式
 * 
 * 自动分类规则：
 * - 从 group-title 属性获取分组
 * - 从 TXT 格式的分组标记获取
 * - 根据频道名称关键词智能分类
 */
public class LiveSourceParser {

    /**
     * 单行最大长度。畸形源可能把整份内容压成一行，正则在超长行上会产生
     * 灾难性回溯（近O(n²)）导致长时间卡死/ANR，超过此长度的行直接跳过。
     */
    private static final int MAX_LINE_LENGTH = 8192;

    // M3U 标签正则
    /** EXTINF 行的 "#EXTINF:时长" 前缀；其后的属性与频道名用引号感知的扫描切分 */
    private static final Pattern EXTINF_PREFIX_PATTERN = Pattern.compile(
            "#EXTINF:\\s*-?\\d+"
    );
    private static final Pattern GROUP_TITLE_PATTERN = Pattern.compile(
            "group-title\\s*=\\s*\"([^\"]*)\""
    );
    private static final Pattern TVG_ID_PATTERN = Pattern.compile(
            "tvg-id\\s*=\\s*\"([^\"]*)\""
    );
    private static final Pattern TVG_NAME_PATTERN = Pattern.compile(
            "tvg-name\\s*=\\s*\"([^\"]*)\""
    );
    private static final Pattern TVG_LOGO_PATTERN = Pattern.compile(
            "tvg-logo\\s*=\\s*\"([^\"]*)\""
    );
    // 回看属性：前面必须是行首或空白，"catchup" 才不会误配 "catchup-source"
    private static final Pattern CATCHUP_PATTERN = Pattern.compile(
            "(?:^|\\s)catchup\\s*=\\s*\"([^\"]*)\""
    );
    private static final Pattern CATCHUP_SOURCE_PATTERN = Pattern.compile(
            "(?:^|\\s)catchup-source\\s*=\\s*\"([^\"]*)\""
    );
    private static final Pattern CATCHUP_DAYS_PATTERN = Pattern.compile(
            "(?:^|\\s)catchup-days\\s*=\\s*\"([^\"]*)\""
    );

    /**
     * TVBox 多线路写法 "url1#url2#url3" 的分隔符：只有 '#' 后面紧跟 "协议://" 才算分隔，
     * 普通的 URL 片段（如 ...m3u8#t=10）保持原样。任意协议都拆开（rtp:// 等拆出后由 isValidUrl 丢弃），
     * 否则不支持的协议会粘在前一条线路的地址尾部。
     */
    private static final Pattern MULTI_URL_SEPARATOR = Pattern.compile(
            "#(?=[A-Za-z][A-Za-z0-9+.-]{1,15}://)"
    );

    // TXT 格式分组标记
    private static final Pattern TXT_GROUP_PATTERN = Pattern.compile(
            "^(.+),\\s*#genre#\\s*$"
    );

    // 分类关键词映射
    private static final Map<String, String[]> CATEGORY_KEYWORDS = new LinkedHashMap<>();

    static {
        CATEGORY_KEYWORDS.put("央视", new String[]{
                "CCTV", "cctv", "央视", "中央"
        });
        CATEGORY_KEYWORDS.put("卫视", new String[]{
                "卫视", "湖南", "浙江", "江苏", "东方", "北京", "广东", "深圳",
                "天津", "重庆", "黑龙江", "吉林", "辽宁", "河北", "河南",
                "山东", "山西", "陕西", "四川", "云南", "贵州", "广西",
                "江西", "安徽", "福建", "海南", "甘肃", "青海", "宁夏",
                "新疆", "西藏", "内蒙古", "东南", "厦门"
        });
        CATEGORY_KEYWORDS.put("港澳台", new String[]{
                "凤凰", "TVB", "tvb", "翡翠", "明珠", "香港", "澳门",
                "台湾", "中天", "TVBS", "三立", "民视", "华视", "中视"
        });
        CATEGORY_KEYWORDS.put("体育", new String[]{
                "体育", "足球", "篮球", "SPORT", "sport", "ESPN", "NBA",
                "五星体育", "劲爆体育", "CCTV5", "cctv5"
        });
        CATEGORY_KEYWORDS.put("电影", new String[]{
                "电影", "影院", "大片", "经典", "MOVIE", "movie", "CHC"
        });
        CATEGORY_KEYWORDS.put("新闻", new String[]{
                "新闻", "资讯", "NEWS", "news", "CCTV13", "CCTV-13"
        });
        CATEGORY_KEYWORDS.put("少儿", new String[]{
                "少儿", "卡通", "动画", "儿童", "金鹰卡通", "CCTV14", "CCTV-14"
        });
        CATEGORY_KEYWORDS.put("纪录", new String[]{
                "纪录", "纪实", "探索", "发现", "CCTV9", "CCTV-9"
        });
    }

    /**
     * 解析 M3U 格式的直播源数据
     * 
     * 标准 M3U 格式示例：
     * #EXTM3U
     * #EXTINF:-1 tvg-id="CCTV1" tvg-name="CCTV-1" group-title="央视",CCTV-1 综合
     * http://example.com/live/cctv1.m3u8
     */
    public List<ChannelGroup> parseM3U(String content) {
        Map<String, ChannelGroup> groupMap = new LinkedHashMap<>();
        // "分组名+频道名" → 频道 的索引，避免逐行线性查找退化为 O(n²)
        Map<String, LiveChannel> channelIndex = new java.util.HashMap<>();
        // 频道 → 已收录线路的去重键（见 ChannelDeduper.lineKey），仅解析期间使用
        Map<LiveChannel, Set<String>> lineKeys = new java.util.IdentityHashMap<>();
        List<ChannelGroup> groups = new ArrayList<>();

        if (content == null || content.isEmpty()) return groups;
        content = stripBom(content);

        try (BufferedReader reader = new BufferedReader(new StringReader(content))) {
            String line;
            String currentExtInf = null;
            // 当前条目的 #EXTVLCOPT / #KODIPROP / #EXTHTTP 请求头，遇到地址行后清空。
            // 不在 #EXTINF 处清空：部分列表把 #EXTVLCOPT 写在 #EXTINF 之前。
            Map<String, String> pendingHeaders = new LinkedHashMap<>();
            // #EXTM3U 头上写的回看属性：作为每个频道的默认值（频道自己写了的优先）
            Map<String, String> catchupDefaults = new LinkedHashMap<>();

            while ((line = reader.readLine()) != null) {
                // 畸形源可能把整份内容压成一行（数 MB），正则在其上会产生灾难性回溯导致卡死，
                // 超过长度上限的行直接跳过。
                if (line.length() > MAX_LINE_LENGTH) {
                    Timber.w("跳过超长行(%d 字符)", line.length());
                    currentExtInf = null;
                    continue;
                }
                line = line.trim();

                if (line.isEmpty()) continue;
                // M3U 头：回看默认值只对这个头之后的条目生效（多份列表拼在一起时，后一份不继承前一份的回看设置）
                if (line.startsWith("#EXTM3U")) {
                    catchupDefaults.clear();
                    collectCatchup(line, catchupDefaults);
                    continue;
                }

                // 解析 #EXTINF 行
                if (line.startsWith("#EXTINF")) {
                    currentExtInf = line;
                    continue;
                }

                // 单条线路的请求头：服务器常按 UA/Referer 放行，丢掉后测速 403、线路被误删
                if (line.startsWith("#")) {
                    collectM3UHeaders(line, pendingHeaders);
                    // 跳过其他注释行
                    continue;
                }

                // 这是一个URL行，配合前面的 EXTINF 解析
                if (currentExtInf != null) {
                    boolean added = false;
                    for (String part : splitMultiUrl(line)) {
                        if (!isValidUrl(part)) continue;
                        // 以 "url|User-Agent=..&Referer=.." 形式附在地址上，测速与播放按 StreamUrl 解析
                        parseM3UEntry(currentExtInf, StreamUrl.withHeaders(part, pendingHeaders),
                                catchupDefaults, groupMap, channelIndex, lineKeys);
                        added = true;
                    }
                    if (added) currentExtInf = null;
                }
                pendingHeaders.clear();
            }
        } catch (IOException e) {
            Timber.e(e, "解析 M3U 数据失败");
        }

        // 转换为列表并设置索引
        int index = 0;
        for (ChannelGroup group : groupMap.values()) {
            group.setGroupIndex(index++);
            group.removeInvalidChannels();
            if (group.getChannelCount() > 0) {
                groups.add(group);
            }
        }

        Timber.d("M3U 解析完成: %d 个分组", groups.size());
        return groups;
    }

    /**
     * 解析 TXT 格式的直播源数据
     * 
     * iptv-api TXT 格式示例：
     * 央视,#genre#
     * CCTV-1 综合,http://example.com/live/cctv1.m3u8
     * CCTV-2 财经,http://example.com/live/cctv2.m3u8
     * 
     * 卫视,#genre#
     * 湖南卫视,http://example.com/live/hunan.m3u8
     */
    public List<ChannelGroup> parseTxt(String content) {
        Map<String, ChannelGroup> groupMap = new LinkedHashMap<>();
        // "分组名+频道名" → 频道 的索引，避免逐行线性查找退化为 O(n²)
        Map<String, LiveChannel> channelIndex = new java.util.HashMap<>();
        // 频道 → 已收录线路的去重键（见 ChannelDeduper.lineKey），仅解析期间使用
        Map<LiveChannel, Set<String>> lineKeys = new java.util.IdentityHashMap<>();
        List<ChannelGroup> groups = new ArrayList<>();

        if (content == null || content.isEmpty()) return groups;
        // UTF-8 BOM 不会被 trim() 去掉，残留会让首个分组/频道名带不可见前缀
        content = stripBom(content);

        String currentGroupName = "未分类";

        try (BufferedReader reader = new BufferedReader(new StringReader(content))) {
            String line;

            while ((line = reader.readLine()) != null) {
                // 同上：跳过异常超长行，避免正则回溯卡死
                if (line.length() > MAX_LINE_LENGTH) {
                    Timber.w("跳过超长行(%d 字符)", line.length());
                    continue;
                }
                line = line.trim();
                if (line.isEmpty()) continue;

                // 检查是否为分组标记行
                Matcher groupMatcher = TXT_GROUP_PATTERN.matcher(line);
                if (groupMatcher.matches()) {
                    currentGroupName = groupMatcher.group(1).trim();
                    Timber.d("检测到频道分组: %s", currentGroupName);
                    continue;
                }

                // 解析频道行：频道名,URL
                int commaIndex = line.indexOf(',');
                if (commaIndex > 0 && commaIndex < line.length() - 1) {
                    String channelName = line.substring(0, commaIndex).trim();
                    String url = line.substring(commaIndex + 1).trim();

                    if (!channelName.isEmpty()) {
                        // "url1#url2#url3" 是 TVBox 常见的多线路写法：拆成独立线路（各自保留 $标签），
                        // 否则只有第一条被测速/播放，其余备用线路全部丢失
                        for (String part : splitMultiUrl(url)) {
                            if (isValidUrl(part)) {
                                addChannelToGroup(groupMap, channelIndex, lineKeys, currentGroupName,
                                        fixMissingPlus(channelName, null, null, part), part, null, null);
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            Timber.e(e, "解析 TXT 数据失败");
        }

        // 转换并过滤
        int index = 0;
        for (ChannelGroup group : groupMap.values()) {
            group.setGroupIndex(index++);
            group.removeInvalidChannels();
            if (group.getChannelCount() > 0) {
                groups.add(group);
            }
        }

        Timber.d("TXT 解析完成: %d 个分组", groups.size());
        return groups;
    }

    /**
     * 智能解析 - 自动识别格式并解析
     */
    public List<ChannelGroup> autoParse(String content) {
        if (content == null || content.isEmpty()) {
            return new ArrayList<>();
        }

        content = stripBom(content);
        String trimmed = content.trim();

        // 判断是否为 M3U 格式
        if (trimmed.startsWith("#EXTM3U") || trimmed.contains("#EXTINF")) {
            return parseM3U(content);
        }

        // 默认按 TXT 格式解析
        return parseTxt(content);
    }

    /**
     * 对频道名称进行智能分类
     * 当数据源未提供分组信息时，根据频道名称关键词自动归类
     */
    public String autoClassifyChannel(String channelName) {
        if (channelName == null) return "其他";

        for (Map.Entry<String, String[]> entry : CATEGORY_KEYWORDS.entrySet()) {
            for (String keyword : entry.getValue()) {
                if (channelName.contains(keyword)) {
                    return entry.getKey();
                }
            }
        }
        return "其他";
    }

    /**
     * 标准化频道名称
     * 统一格式，方便合并去重
     *
     * <p>正则全部预编译：本方法对源文件的每一行都会调用（单源可达上万行），
     * 用 String#replaceAll 会在每行重复编译 6 个 Pattern。
     */
    public String normalizeChannelName(String name) {
        if (name == null) return "";
        // 统一大小写
        name = name.trim();
        if (name.isEmpty()) return "";
        // 替换常见变体
        name = P_NAME_CCTV.matcher(name).replaceAll("CCTV-$1");
        name = P_NAME_HIGH_DEF.matcher(name).replaceAll("");
        name = P_NAME_HD.matcher(name).replaceAll("");
        name = P_NAME_STD_DEF.matcher(name).replaceAll("");
        name = P_NAME_SD.matcher(name).replaceAll("");
        name = P_NAME_SPACES.matcher(name).replaceAll(" ");
        return name;
    }

    // 频道名标准化用的预编译正则
    private static final Pattern P_NAME_CCTV = Pattern.compile("(?i)cctv-?(\\d+)");
    private static final Pattern P_NAME_HIGH_DEF = Pattern.compile("(?i)\\s*高清\\s*$");
    private static final Pattern P_NAME_HD = Pattern.compile("(?i)\\s*HD\\s*$");
    private static final Pattern P_NAME_STD_DEF = Pattern.compile("(?i)\\s*标清\\s*$");
    private static final Pattern P_NAME_SD = Pattern.compile("(?i)\\s*SD\\s*$");
    private static final Pattern P_NAME_SPACES = Pattern.compile("\\s+");

    /** 有"+"频道的 CCTV 台号：只有 CCTV-5 有对应的 CCTV-5+，其它台号不能凭地址里的"p"加号 */
    private static final Pattern P_CCTV_NUM_KEY = Pattern.compile("^CCTV(5)$");

    /**
     * 纠正源里漏写"+"的 CCTV-5+ 这类频道：显示名归一化后是 CCTVn，
     * 但线路地址或 tvg-id / tvg-name 明确指向 CCTVn+ 时，按 CCTVn+ 收录。
     *
     * <p>实际源里常见：显示名"CCTV-5"、tvg-id="CCTV-5+"、地址 .../cdrmldcctv5plus_1/index.m3u8。
     * 不纠正的话这些 5+ 线路会并进 CCTV-5；它们多是 CDN 线路、速度快，测速后还会成为 CCTV-5 的首选，
     * 看 CCTV-5 实际放的是 5+。
     *
     * <p>判定优先级：地址写明是 n+（cctv5plus / cctv5p / cctv5+ / cctv5%2B）→ 纠正；
     * 否则 tvg-id 或 tvg-name 指向 n+、且地址没有写明是普通 n 台 → 纠正。
     */
    static String fixMissingPlus(String channelName, String tvgId, String tvgName, String url) {
        if (channelName == null) return null;
        Matcher m = P_CCTV_NUM_KEY.matcher(com.github.tvbox.osc.util.ChannelNameMatcher.normalizeKey(channelName));
        if (!m.matches()) return channelName;
        String n = m.group(1);
        String plusKey = "CCTV" + n + "+";

        String play = url == null ? "" : StreamUrl.playUrl(url).toLowerCase(Locale.ROOT);
        Pattern numInUrl = Pattern.compile("cctv-?0?" + n + "(?![0-9])(plus|p(?![a-z])|\\+|%2b)?");
        boolean urlPlus = false, urlPlain = false;
        Matcher um = numInUrl.matcher(play);
        while (um.find()) {
            if (um.group(1) != null) urlPlus = true;
            else urlPlain = true;
        }
        boolean tvgPlus = plusKey.equals(com.github.tvbox.osc.util.ChannelNameMatcher.normalizeKey(tvgId))
                || plusKey.equals(com.github.tvbox.osc.util.ChannelNameMatcher.normalizeKey(tvgName));
        if (urlPlus || (tvgPlus && !urlPlain)) {
            return "CCTV-" + n + "+";
        }
        return channelName;
    }

    // ============ 私有方法 ============

    /** 去掉开头的 UTF-8 BOM（U+FEFF） */
    private static String stripBom(String s) {
        if (s != null && !s.isEmpty() && s.charAt(0) == '\ufeff') return s.substring(1);
        return s;
    }

    /** 拆分 "url1#url2" 多线路写法；不含分隔符时原样返回单元素列表 */
    static List<String> splitMultiUrl(String field) {
        List<String> out = new ArrayList<>();
        if (field == null) return out;
        for (String part : MULTI_URL_SEPARATOR.split(field)) {
            String p = part.trim();
            if (!p.isEmpty()) out.add(p);
        }
        return out;
    }

    /**
     * 收集 M3U 注释行里的单条线路请求头：
     * #EXTVLCOPT:http-user-agent= / http-referrer= / http-referer= / http-origin=，
     * #KODIPROP:inputstream.adaptive.stream_headers=名=值&名=值（值为 URL 编码），
     * #EXTHTTP:{"User-Agent":"...","Referer":"..."}。同名请求头后出现的覆盖先出现的。
     */
    private static void collectM3UHeaders(String line, Map<String, String> out) {
        String upper = line.length() > 16 ? line.substring(0, 16).toUpperCase(Locale.ROOT) : line.toUpperCase(Locale.ROOT);
        if (upper.startsWith("#EXTVLCOPT:")) {
            String opt = line.substring("#EXTVLCOPT:".length()).trim();
            int eq = opt.indexOf('=');
            if (eq <= 0) return;
            String name = opt.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            String value = stripQuotes(opt.substring(eq + 1).trim());
            if (value.isEmpty()) return;
            if (name.equals("http-user-agent")) {
                putHeader(out, "User-Agent", value);
            } else if (name.equals("http-referrer") || name.equals("http-referer")) {
                putHeader(out, "Referer", value);
            } else if (name.equals("http-origin")) {
                putHeader(out, "Origin", value);
            }
        } else if (upper.startsWith("#KODIPROP:")) {
            String prop = line.substring("#KODIPROP:".length()).trim();
            int eq = prop.indexOf('=');
            if (eq <= 0) return;
            String name = prop.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            if (!name.equals("inputstream.adaptive.stream_headers")
                    && !name.equals("inputstream.adaptive.manifest_headers")) return;
            for (String pair : prop.substring(eq + 1).split("&")) {
                int e2 = pair.indexOf('=');
                if (e2 <= 0) continue;
                String k = pair.substring(0, e2).trim();
                String v = decodeQuietly(pair.substring(e2 + 1).trim());
                if (!k.isEmpty() && !v.isEmpty()) putHeader(out, k, v);
            }
        } else if (upper.startsWith("#EXTHTTP:")) {
            String json = line.substring("#EXTHTTP:".length()).trim();
            try {
                org.json.JSONObject obj = new org.json.JSONObject(json);
                Iterator<String> keys = obj.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    // 只接受字符串值；AOSP 的 optString 对 null 会返回 "null"，故先判类型
                    Object v = obj.opt(k);
                    if (k != null && !k.trim().isEmpty() && v instanceof String && !((String) v).isEmpty()) {
                        putHeader(out, k.trim(), (String) v);
                    }
                }
            } catch (Exception e) {
                Timber.w("忽略无法解析的 #EXTHTTP: %s", json);
            }
        }
    }

    /** 按名称大小写不敏感地写入请求头（后写覆盖） */
    private static void putHeader(Map<String, String> out, String name, String value) {
        for (Iterator<String> it = out.keySet().iterator(); it.hasNext(); ) {
            if (it.next().equalsIgnoreCase(name)) it.remove();
        }
        out.put(name, value);
    }

    private static String stripQuotes(String v) {
        if (v.length() >= 2 && v.charAt(0) == '"' && v.charAt(v.length() - 1) == '"') {
            return v.substring(1, v.length() - 1).trim();
        }
        return v;
    }

    private static String decodeQuietly(String v) {
        try {
            return URLDecoder.decode(v, "UTF-8");
        } catch (Exception e) {
            return v;
        }
    }

    /**
     * 从 #EXTM3U / #EXTINF 的属性里取回看设置（catchup / catchup-source / catchup-days），
     * 写入 out（键为 StreamUrl 的保留键）。只覆盖本行写了的项。
     */
    private void collectCatchup(String attributes, Map<String, String> out) {
        String type = extractAttribute(attributes, CATCHUP_PATTERN);
        String source = extractAttribute(attributes, CATCHUP_SOURCE_PATTERN);
        String days = extractAttribute(attributes, CATCHUP_DAYS_PATTERN);
        if (type != null && !type.trim().isEmpty()) out.put(StreamUrl.KEY_CATCHUP, type.trim());
        if (source != null && !source.trim().isEmpty()) out.put(StreamUrl.KEY_CATCHUP_SOURCE, source.trim());
        if (days != null && !days.trim().isEmpty()) out.put(StreamUrl.KEY_CATCHUP_DAYS, days.trim());
    }

    private void parseM3UEntry(String extInfLine, String url, Map<String, String> catchupDefaults,
                               Map<String, ChannelGroup> groupMap,
                               Map<String, LiveChannel> channelIndex,
                               Map<LiveChannel, Set<String>> lineKeys) {
        Matcher prefix = EXTINF_PREFIX_PATTERN.matcher(extInfLine);
        if (!prefix.find()) return;
        int start = prefix.end();

        // 按"引号外的第一个逗号"切分属性与频道名：属性值里常带逗号
        // （如 tvg-logo="https://img/w_100,h_100/x.png"、group-title="央视,高清"），
        // 原惰性正则会在引号内的逗号处截断，导致频道名变成乱码、分组丢失，被模板整条丢弃。
        // 取第一个而不是最后一个：频道名本身可能含逗号，但几乎不会含引号。
        int sep = -1;
        boolean inQuotes = false;
        for (int i = start; i < extInfLine.length(); i++) {
            char c = extInfLine.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                sep = i;
                break;
            }
        }
        // 引号不配对（畸形行）：退回旧行为，取时长之后的第一个逗号
        if (sep < 0) sep = extInfLine.indexOf(',', start);
        if (sep < 0) return;

        String attributes = extInfLine.substring(start, sep).trim();
        String channelName = extInfLine.substring(sep + 1).trim();
        if (channelName.isEmpty()) return;

        // 提取分组名称
        String groupName = extractAttribute(attributes, GROUP_TITLE_PATTERN);
        if (groupName == null || groupName.isEmpty()) {
            groupName = autoClassifyChannel(channelName);
        }

        // 提取其他属性
        String epgId = extractAttribute(attributes, TVG_ID_PATTERN);
        String logoUrl = extractAttribute(attributes, TVG_LOGO_PATTERN);

        // 标准化频道名称
        channelName = normalizeChannelName(channelName);
        channelName = fixMissingPlus(channelName, epgId, extractAttribute(attributes, TVG_NAME_PATTERN), url);

        // 回看模板以保留键附在线路地址后（不是请求头，不影响去重）：频道自己的属性优先，其次是 #EXTM3U 头上的默认值
        Map<String, String> catchup = new LinkedHashMap<>();
        collectCatchup(attributes, catchup);
        for (Map.Entry<String, String> e : catchupDefaults.entrySet()) {
            if (!catchup.containsKey(e.getKey())) catchup.put(e.getKey(), e.getValue());
        }
        if (!catchup.isEmpty()) url = StreamUrl.appendMeta(url, catchup);

        addChannelToGroup(groupMap, channelIndex, lineKeys, groupName, channelName, url, epgId, logoUrl);
    }

    private String extractAttribute(String attributes, Pattern pattern) {
        if (attributes == null) return null;
        Matcher matcher = pattern.matcher(attributes);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * 把一条线路加入对应分组的对应频道。
     *
     * @param channelIndex "分组名+频道名" → 频道 的索引。原实现用
     *        {@link ChannelGroup#findChannelByName} 线性扫描，单个源上万行时
     *        会退化为 O(n²)；这里改为 O(1) 查找。
     */
    private void addChannelToGroup(Map<String, ChannelGroup> groupMap,
                                    Map<String, LiveChannel> channelIndex,
                                    Map<LiveChannel, Set<String>> lineKeys,
                                    String groupName, String channelName,
                                    String url, String epgId, String logoUrl) {
        // 获取或创建分组
        ChannelGroup group = groupMap.get(groupName);
        if (group == null) {
            group = new ChannelGroup(groupName, groupMap.size());
            groupMap.put(groupName, group);
        }

        // 查找已有频道（O(1)）
        String key = groupName + "\u0001" + channelName;
        LiveChannel channel = channelIndex.get(key);
        if (channel == null) {
            channel = new LiveChannel(channelName, groupName);
            if (epgId != null) channel.setEpgId(epgId);
            if (logoUrl != null) channel.setLogoUrl(logoUrl);
            group.addChannel(channel);
            channelIndex.put(key, channel);
        }

        // 添加播放源。同一路流只是 '$标签' 不同（"$电信"/"$联通"）只收第一条：
        // addSourceUrl 按原串去重拦不住，重复项会被测两次、多占线路名额
        Set<String> keys = lineKeys.get(channel);
        if (keys == null) {
            keys = new HashSet<>();
            lineKeys.put(channel, keys);
        }
        if (keys.add(ChannelDeduper.lineKey(url)) || !StreamUrl.reservedMeta(url).isEmpty()) {
            // 重复线路带回看元数据时也交给 addSourceUrl：它按 lineKey 去重，并把元数据补到先到的那条上
            channel.addSourceUrl(url);
        }
    }

    /**
     * 只接受播放器真正能播放的协议：HTTP(S)、RTSP(S)、RTMP(S)、file、content。
     * rtp/udp/igmp/p2p 等内核无法播放，在解析阶段就丢弃，
     * 避免进入频道列表后浪费测速配额、拖慢换源。
     *
     * <p>判定统一走 {@link com.github.tvbox.osc.util.ProtocolFilter}，与测速、播放处保持一致。
     */
    private boolean isValidUrl(String url) {
        return com.github.tvbox.osc.util.ProtocolFilter.isSupported(url);
    }
}
