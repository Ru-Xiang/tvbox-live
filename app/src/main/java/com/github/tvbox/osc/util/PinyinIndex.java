package com.github.tvbox.osc.util;

import java.util.HashMap;
import java.util.Map;

/**
 * 简易汉字首字母/拼音索引
 *
 * 对齐 ISEP 使用 pypinyin 的思路：为频道名生成首字母缩写，
 * 用于按字母快速跳转（例如 "H" 跳到"湖南卫视"，"C" 跳到"CCTV"）。
 *
 * 说明：Android 环境不便引入完整的拼音库，这里内置常见汉字
 * 首字母映射（按 GB2312 区位排序的粗粒度分段近似），
 * 未命中时回退到英文/数字首字母。对于绝大多数电视频道名（CCTV、卫视、
 * 常见地名）已足够可用。
 */
public final class PinyinIndex {

    private PinyinIndex() {}

    /** 常见首字母映射（覆盖高频频道名字词） */
    private static final Map<Character, Character> COMMON = new HashMap<>();

    static {
        // 央视 / CCTV 相关
        put('中', 'Z'); put('央', 'Y'); put('电', 'D'); put('视', 'S'); put('台', 'T');
        // 卫视 / 省份地名
        put('卫', 'W'); put('湖', 'H'); put('浙', 'Z'); put('江', 'J'); put('苏', 'S');
        put('东', 'D'); put('方', 'F'); put('北', 'B'); put('京', 'J'); put('广', 'G');
        put('深', 'S'); put('圳', 'Z'); put('天', 'T'); put('津', 'J'); put('重', 'C');
        put('庆', 'Q'); put('黑', 'H'); put('龙', 'L'); put('吉', 'J'); put('林', 'L');
        put('辽', 'L'); put('宁', 'N'); put('河', 'H'); put('南', 'N'); put('山', 'S');
        put('东', 'D'); put('西', 'X'); put('陕', 'S'); put('四', 'S'); put('川', 'C');
        put('云', 'Y'); put('贵', 'G'); put('州', 'Z'); put('广', 'G'); put('西', 'X');
        put('江', 'J'); put('安', 'A'); put('徽', 'H'); put('福', 'F'); put('建', 'J');
        put('海', 'H'); put('甘', 'G'); put('肃', 'S'); put('青', 'Q'); put('宁', 'N');
        put('夏', 'X'); put('新', 'X'); put('疆', 'J'); put('西', 'X'); put('藏', 'Z');
        put('内', 'N'); put('蒙', 'M'); put('古', 'G'); put('厦', 'X'); put('门', 'M');
        // 常见频道相关
        put('凤', 'F'); put('凰', 'H'); put('翡', 'F'); put('翠', 'C'); put('明', 'M');
        put('珠', 'Z'); put('香', 'X'); put('港', 'G'); put('澳', 'A'); put('台', 'T');
        put('湾', 'W'); put('华', 'H'); put('民', 'M');
        // 内容类
        put('新', 'X'); put('闻', 'W'); put('体', 'T'); put('育', 'Y'); put('电', 'D');
        put('影', 'Y'); put('剧', 'J'); put('乐', 'L'); put('音', 'Y'); put('少', 'S');
        put('儿', 'E'); put('纪', 'J'); put('录', 'L'); put('军', 'J'); put('事', 'S');
        put('国', 'G'); put('际', 'J'); put('综', 'Z'); put('合', 'H'); put('财', 'C');
        put('经', 'J'); put('农', 'N'); put('业', 'Y'); put('社', 'S'); put('会', 'H');
        put('法', 'F'); put('教', 'J'); put('科', 'K'); put('戏', 'X'); put('曲', 'Q');
        put('奥', 'A'); put('林', 'L'); put('匹', 'P'); put('克', 'K');
    }

    private static void put(char ch, char letter) {
        COMMON.put(ch, letter);
    }

    /**
     * 取频道名的首字母（A-Z / 0-9 / '#'）。
     * '#' 表示无法识别的字符。
     */
    public static char firstLetter(String name) {
        if (name == null || name.isEmpty()) return '#';
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            char letter = letterOf(ch);
            if (letter != '#') return letter;
        }
        return '#';
    }

    /**
     * 生成频道名的首字母缩写（每个汉字/单词取首字母）。
     * 例如："湖南卫视" -> "HNWS"，"CCTV-1 综合" -> "CCTV1ZH"
     */
    public static String abbreviation(String name) {
        if (name == null || name.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        boolean lastAlpha = false;
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (Character.isDigit(ch)) {
                sb.append(ch);
                lastAlpha = false;
            } else if (isAsciiLetter(ch)) {
                if (!lastAlpha) sb.append(Character.toUpperCase(ch));
                lastAlpha = true;
            } else {
                char letter = letterOf(ch);
                if (letter != '#') sb.append(letter);
                lastAlpha = false;
            }
        }
        return sb.toString();
    }

    /** 判断频道名是否匹配给定的首字母序列（不区分大小写，忽略非字母数字） */
    public static boolean matchesInitials(String name, String query) {
        if (query == null || query.isEmpty()) return true;
        String abbr = abbreviation(name);
        String q = query.toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        return !q.isEmpty() && abbr.contains(q);
    }

    private static char letterOf(char ch) {
        if (Character.isDigit(ch)) return ch;
        if (isAsciiLetter(ch)) return Character.toUpperCase(ch);
        Character mapped = COMMON.get(ch);
        return mapped != null ? mapped : '#';
    }

    private static boolean isAsciiLetter(char ch) {
        return (ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z');
    }
}
