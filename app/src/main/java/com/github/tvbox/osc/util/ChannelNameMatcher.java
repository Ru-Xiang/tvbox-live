package com.github.tvbox.osc.util;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 频道名模糊匹配工具
 *
 * 通过归一化把不同写法的同一频道映射到同一个 key，用于：
 * 1. 合并去重（CCTV-1 / CCTV1 综合 / 央视一套 → 同一频道）
 * 2. 频道列表模板匹配（模板里的频道名匹配实际源里的频道）
 *
 * 归一化规则：
 * - 全角转半角、去空白与常见分隔符
 * - 去画质后缀（高清/超清/HD/FHD/UHD/标清/SD/蓝光 等）
 * - CCTV 系列统一为 CCTVn（含中文数字、"中央n套/央视n套"）
 * - 别名映射（如 CCTV1综合 → CCTV1 的归一）
 *
 * <p><b>性能说明：</b>本类是源聚合 / 模板匹配 / EPG 关联的最热函数，
 * 上千频道 × 数十源时调用量可达百万级。因此：
 * 1. 所有正则均预编译为 static final Pattern，避免 String#replaceAll 每次重新编译；
 * 2. 归一化结果用 LRU 缓存记忆，重复频道名直接命中缓存。
 */
public class ChannelNameMatcher {

    private ChannelNameMatcher() {}

    /** 中文数字 → 阿拉伯数字 */
    private static final Map<Character, String> CN_NUM = new HashMap<>();
    /** 直接别名映射（归一化后再比较） */
    private static final Map<String, String> ALIAS = new HashMap<>();

    // ============ 预编译正则（避免 replaceAll 每次重新编译） ============
    private static final Pattern P_QUALITY_WORDS = Pattern.compile(
            "(超高清|超清|高清|蓝光|标清|流畅|备用|测试|轮播|HEVC|H265|H264|265|264)");
    private static final Pattern P_QUALITY_ABBR = Pattern.compile("(?<![A-Z])(FHD|UHD|HD|SD)(?![A-Z])");
    private static final Pattern P_RESOLUTION = Pattern.compile("\\b(4K|8K|1080P?|720P?|50FPS|60FPS)\\b");
    private static final Pattern P_PAREN = Pattern.compile("\\((.*?)\\)");
    private static final Pattern P_BRACKET = Pattern.compile("\\[(.*?)\\]");
    private static final Pattern P_SEPARATOR = Pattern.compile("[\\s\\-_·•·.,，、|]");
    private static final Pattern P_CCTV_ALIAS = Pattern.compile("(中央电视台|央视|中央)");
    private static final Pattern P_CCTV_NUM = Pattern.compile("CCTV(\\d+)套?");
    private static final Pattern P_CCTV_PLUS = Pattern.compile("CCTV(\\d+)\\+");

    /** 归一化结果 LRU 缓存上限：频道名种类有限，足以覆盖全量订阅源 */
    private static final int MAX_CACHE = 4096;

    /**
     * 归一化结果缓存（LRU）。访问用 synchronized 包装，
     * 因为源聚合与测速可能在多个线程上并发调用归一化。
     */
    private static final LinkedHashMap<String, String> CACHE =
            new LinkedHashMap<String, String>(512, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > MAX_CACHE;
                }
            };

    static {
        CN_NUM.put('一', "1");
        CN_NUM.put('二', "2");
        CN_NUM.put('三', "3");
        CN_NUM.put('四', "4");
        CN_NUM.put('五', "5");
        CN_NUM.put('六', "6");
        CN_NUM.put('七', "7");
        CN_NUM.put('八', "8");
        CN_NUM.put('九', "9");
        CN_NUM.put('十', "10");

        ALIAS.put("CCTV1综合", "CCTV1");
        ALIAS.put("CCTV2财经", "CCTV2");
        ALIAS.put("CCTV3综艺", "CCTV3");
        ALIAS.put("CCTV4中文国际", "CCTV4");
        ALIAS.put("CCTV5体育", "CCTV5");
        ALIAS.put("CCTV5PLUS", "CCTV5+");
        ALIAS.put("CCTV5体育赛事", "CCTV5+");
        ALIAS.put("CCTV6电影", "CCTV6");
        ALIAS.put("CCTV7国防军事", "CCTV7");
        ALIAS.put("CCTV8电视剧", "CCTV8");
        ALIAS.put("CCTV9纪录", "CCTV9");
        ALIAS.put("CCTV10科教", "CCTV10");
        ALIAS.put("CCTV11戏曲", "CCTV11");
        ALIAS.put("CCTV12社会与法", "CCTV12");
        ALIAS.put("CCTV13新闻", "CCTV13");
        ALIAS.put("CCTV14少儿", "CCTV14");
        ALIAS.put("CCTV15音乐", "CCTV15");
        ALIAS.put("CCTV16奥林匹克", "CCTV16");
        ALIAS.put("CCTV17农业农村", "CCTV17");
    }

    /**
     * 计算频道名归一化匹配 key（带 LRU 缓存）
     */
    public static String normalizeKey(String name) {
        if (name == null || name.isEmpty()) return "";

        synchronized (CACHE) {
            String hit = CACHE.get(name);
            if (hit != null) return hit;
        }

        String result = computeKey(name);

        synchronized (CACHE) {
            CACHE.put(name, result);
        }
        return result;
    }

    /** 真正执行归一化计算（未命中缓存时调用） */
    private static String computeKey(String name) {
        // 用 Locale.ROOT，避免土耳其语等区域下 i/I 转换异常导致匹配失效
        String s = toHalfWidth(name).toUpperCase(java.util.Locale.ROOT);

        // 去掉常见画质/修饰后缀与噪声词
        s = P_QUALITY_WORDS.matcher(s).replaceAll("");
        s = P_QUALITY_ABBR.matcher(s).replaceAll("");
        s = P_RESOLUTION.matcher(s).replaceAll("");
        s = P_PAREN.matcher(s).replaceAll("");
        s = P_BRACKET.matcher(s).replaceAll("");

        // 去分隔符与空白
        s = P_SEPARATOR.matcher(s).replaceAll("");

        // 中文数字转阿拉伯（整体替换，简单处理）
        StringBuilder sb = new StringBuilder(s.length() + 4);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            String mapped = CN_NUM.get(c);
            if (mapped != null) sb.append(mapped);
            else sb.append(c);
        }
        s = sb.toString();

        // 央视/中央 系列统一为 CCTV
        s = P_CCTV_ALIAS.matcher(s).replaceAll("CCTV");
        s = P_CCTV_NUM.matcher(s).replaceAll("CCTV$1");
        s = P_CCTV_PLUS.matcher(s).replaceAll("CCTV$1+");

        // 别名表
        String alias = ALIAS.get(s);
        return alias != null ? alias : s;
    }

    /**
     * 判断两个频道名是否为同一频道（模糊匹配）
     */
    public static boolean isSameChannel(String a, String b) {
        if (a == null || b == null) return false;
        // 原名完全相同则必然同频道，省去两次归一化
        if (a.equals(b)) return !a.isEmpty();
        String ka = normalizeKey(a);
        String kb = normalizeKey(b);
        if (ka.isEmpty() || kb.isEmpty()) return false;
        return ka.equals(kb);
    }

    /** 清空归一化缓存（频道源整体刷新后可调用，释放内存） */
    public static void clearCache() {
        synchronized (CACHE) {
            CACHE.clear();
        }
    }

    /** 全角转半角 */
    private static String toHalfWidth(String input) {
        if (input == null) return "";
        char[] chars = input.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            if (chars[i] == 12288) { // 全角空格
                chars[i] = ' ';
            } else if (chars[i] >= 65281 && chars[i] <= 65374) {
                chars[i] -= 65248;
            }
        }
        return new String(chars);
    }
}
