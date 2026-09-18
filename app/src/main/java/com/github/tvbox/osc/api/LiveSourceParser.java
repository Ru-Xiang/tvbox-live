package com.github.tvbox.osc.api;

import com.github.tvbox.osc.bean.ChannelGroup;
import com.github.tvbox.osc.bean.LiveChannel;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private static final Pattern EXTINF_PATTERN = Pattern.compile(
            "#EXTINF:\\s*-?\\d+\\s*(.*?)\\s*,\\s*(.+)$"
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
        List<ChannelGroup> groups = new ArrayList<>();

        if (content == null || content.isEmpty()) return groups;

        try (BufferedReader reader = new BufferedReader(new StringReader(content))) {
            String line;
            String currentExtInf = null;

            while ((line = reader.readLine()) != null) {
                // 畸形源可能把整份内容压成一行（数 MB），正则在其上会产生灾难性回溯导致卡死，
                // 超过长度上限的行直接跳过。
                if (line.length() > MAX_LINE_LENGTH) {
                    Timber.w("跳过超长行(%d 字符)", line.length());
                    currentExtInf = null;
                    continue;
                }
                line = line.trim();

                // 跳过空行和 M3U 头
                if (line.isEmpty() || line.equals("#EXTM3U")) continue;

                // 解析 #EXTINF 行
                if (line.startsWith("#EXTINF")) {
                    currentExtInf = line;
                    continue;
                }

                // 跳过其他注释行
                if (line.startsWith("#")) continue;

                // 这是一个URL行，配合前面的 EXTINF 解析
                if (currentExtInf != null && isValidUrl(line)) {
                    parseM3UEntry(currentExtInf, line, groupMap, channelIndex);
                    currentExtInf = null;
                }
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
        List<ChannelGroup> groups = new ArrayList<>();

        if (content == null || content.isEmpty()) return groups;

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

                    if (!channelName.isEmpty() && isValidUrl(url)) {
                        addChannelToGroup(groupMap, channelIndex, currentGroupName, channelName, url, null, null);
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

    // ============ 私有方法 ============

    private void parseM3UEntry(String extInfLine, String url, Map<String, ChannelGroup> groupMap,
                               Map<String, LiveChannel> channelIndex) {
        Matcher infMatcher = EXTINF_PATTERN.matcher(extInfLine);
        if (!infMatcher.find()) return;

        String attributes = infMatcher.group(1);
        String channelName = infMatcher.group(2).trim();

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

        addChannelToGroup(groupMap, channelIndex, groupName, channelName, url, epgId, logoUrl);
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

        // 添加播放源
        channel.addSourceUrl(url);
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
