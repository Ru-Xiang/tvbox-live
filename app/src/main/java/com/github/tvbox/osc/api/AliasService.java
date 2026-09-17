package com.github.tvbox.osc.api;

import com.github.tvbox.osc.util.ChannelNameMatcher;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import timber.log.Timber;

/**
 * 频道改名（别名）规则服务
 *
 * 规则文件格式（参照 Ru-Xiang/m3u 的 alias.txt）：
 *   主名,别名1,别名2,...
 * - 以 re: 开头的别名为正则表达式
 * - # 开头为注释，空行用于分组分隔
 *
 * 作用：在聚合/测速之前，把直播源里五花八门的频道名统一改成"主名"，
 * 从而让同一频道的多个源能够正确合并。
 */
public class AliasService {

    /** 单条正则的最大长度（远程规则不可信，过长/复杂正则易触发灾难性回溯） */
    private static final int MAX_REGEX_LENGTH = 120;
    /** 正则规则条数上限（限制匹配开销与内存） */
    private static final int MAX_REGEX_RULES = 500;
    /** 普通别名条数上限 */
    private static final int MAX_PLAIN_RULES = 20000;
    /** 参与正则匹配的频道名最大长度（超长名直接跳过正则，避免回溯放大） */
    private static final int MAX_REGEX_INPUT = 80;

    /**
     * 规则以不可变快照整体替换，避免"一个线程在clear/add、另一个线程在遍历"
     * 造成 ConcurrentModificationException 或 HashMap 结构损坏。
     */
    private volatile Rules rules = new Rules(
            java.util.Collections.<String, String>emptyMap(),
            java.util.Collections.<RegexAlias>emptyList(),
            java.util.Collections.<String, String>emptyMap());

    private static class Rules {
        final Map<String, String> plainAlias;
        final List<RegexAlias> regexAlias;
        final Map<String, String> mainNames;
        final boolean loaded;

        Rules(Map<String, String> plainAlias, List<RegexAlias> regexAlias, Map<String, String> mainNames) {
            this.plainAlias = plainAlias;
            this.regexAlias = regexAlias;
            this.mainNames = mainNames;
            this.loaded = true;
        }
    }

    private static class RegexAlias {
        final Pattern pattern;
        final String mainName;
        RegexAlias(Pattern pattern, String mainName) {
            this.pattern = pattern;
            this.mainName = mainName;
        }
    }

    public boolean isLoaded() {
        Rules r = rules;
        return r.loaded && (!r.plainAlias.isEmpty() || !r.regexAlias.isEmpty());
    }

    /**
     * 解析别名规则文本。解析完成后整体替换快照，期间不影响正在进行的 resolve 调用。
     */
    public void parse(String content) {
        Map<String, String> plain = new HashMap<>();
        List<RegexAlias> regexes = new ArrayList<>();
        Map<String, String> mains = new HashMap<>();

        if (content != null && !content.isEmpty()) {
            try (BufferedReader reader = new BufferedReader(new StringReader(content))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;

                    String[] parts = line.split(",");
                    if (parts.length < 1) continue;
                    String mainName = parts[0].trim();
                    if (mainName.isEmpty()) continue;

                    mains.put(ChannelNameMatcher.normalizeKey(mainName), mainName);

                    for (int i = 1; i < parts.length; i++) {
                        String alias = parts[i].trim();
                        if (alias.isEmpty()) continue;
                        if (alias.startsWith("re:")) {
                            if (regexes.size() >= MAX_REGEX_RULES) continue;
                            String regex = alias.substring(3).trim();
                            // 过滤过长正则，降低灾难性回溯风险
                            if (regex.isEmpty() || regex.length() > MAX_REGEX_LENGTH) continue;
                            try {
                                regexes.add(new RegexAlias(Pattern.compile(regex), mainName));
                            } catch (Throwable e) {
                                Timber.w("无效的别名正则: %s", regex);
                            }
                        } else if (plain.size() < MAX_PLAIN_RULES) {
                            plain.put(ChannelNameMatcher.normalizeKey(alias), mainName);
                        }
                    }
                }
            } catch (Throwable e) {
                Timber.w(e, "解析别名规则失败");
            }
        }

        rules = new Rules(plain, regexes, mains);
        Timber.i("别名规则加载完成: 普通 %d 条, 正则 %d 条", plain.size(), regexes.size());
    }

    /**
     * 把频道名按规则映射为主名；无匹配则返回原名
     */
    public String resolve(String channelName) {
        if (channelName == null || channelName.isEmpty()) return channelName;
        Rules r = rules;
        if (!r.loaded || (r.plainAlias.isEmpty() && r.regexAlias.isEmpty())) return channelName;

        String key = ChannelNameMatcher.normalizeKey(channelName);

        // 1) 本身就是主名
        String hitMain = r.mainNames.get(key);
        if (hitMain != null) return hitMain;

        // 2) 普通别名（归一化匹配）
        String hit = r.plainAlias.get(key);
        if (hit != null) return hit;

        // 3) 正则别名（对原始名匹配）；超长名跳过以避免回溯放大
        if (channelName.length() <= MAX_REGEX_INPUT) {
            for (RegexAlias ra : r.regexAlias) {
                try {
                    if (ra.pattern.matcher(channelName).matches()) {
                        return ra.mainName;
                    }
                } catch (Throwable ignored) {
                    // 恶意正则可能抛 StackOverflowError（属 Error），必须用 Throwable 兜住
                }
            }
        }
        return channelName;
    }
}
