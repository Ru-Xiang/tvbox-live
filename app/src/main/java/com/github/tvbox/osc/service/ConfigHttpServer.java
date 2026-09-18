package com.github.tvbox.osc.service;

import com.github.tvbox.osc.util.HawkConfig;
import com.orhanobut.hawk.Hawk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;
import timber.log.Timber;

/**
 * 扫码配置内置 HTTP 服务器
 *
 * 在局域网内提供一个网页表单，手机扫码后即可在浏览器中编辑：
 * - IP 版本 / 自定义源 / 订阅列表 / 自动更新与间隔
 * - 测速时机 / 测速周期 / 超时 / 并发 / 最低速度
 *
 * 提交后直接写回 Hawk 配置，并通过回调通知 TV 端刷新界面。
 */
public class ConfigHttpServer extends NanoHTTPD {

    /** 默认监听端口 */
    public static final int DEFAULT_PORT = 9978;

    public interface OnConfigSavedListener {
        void onConfigSaved();
    }

    /** 运行日志文件名（位于 filesDir/logs/，与崩溃日志不同目录） */
    private static final String RUN_LOG_NAME = "run.log";

    private OnConfigSavedListener listener;
    private final android.content.Context appContext;

    public ConfigHttpServer(android.content.Context context, int port) {
        super(port);
        this.appContext = context != null ? context.getApplicationContext() : null;
    }

    public void setOnConfigSavedListener(OnConfigSavedListener listener) {
        this.listener = listener;
    }

    @Override
    public Response serve(IHTTPSession session) {
        try {
            // 崩溃日志查看：/crash（列表）、/crash?file=xxx（详情）、POST action=clear（清除）
            String uri = session.getUri() == null ? "/" : session.getUri();
            if (uri.startsWith("/crash")) {
                return serveCrashLogs(session);
            }

            if (Method.POST.equals(session.getMethod())) {
                Map<String, String> files = new HashMap<>();
                session.parseBody(files);
                Map<String, String> params = flatten(session.getParameters());
                saveConfig(params);
                if (listener != null) listener.onConfigSaved();
                return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", successPage());
            }
            return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", formPage());
        } catch (Exception e) {
            Timber.e(e, "配置服务器处理请求失败");
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR,
                    "text/html; charset=utf-8", "<h3>服务器错误: " + esc(e.getMessage()) + "</h3>");
        }
    }

    private Map<String, String> flatten(Map<String, List<String>> parameters) {
        Map<String, String> result = new HashMap<>();
        if (parameters == null) return result;
        for (Map.Entry<String, List<String>> e : parameters.entrySet()) {
            if (e.getValue() != null && !e.getValue().isEmpty()) {
                result.put(e.getKey(), e.getValue().get(0));
            }
        }
        return result;
    }

    // ============ 配置读写 ============

    private void saveConfig(Map<String, String> p) {
        if (p.containsKey("ip_version")) {
            String v = p.get("ip_version");
            Hawk.put(HawkConfig.IPTV_IP_VERSION,
                    ("ipv4".equals(v) || "ipv6".equals(v)) ? v : "all");
        }
        // 复选框未勾选时不会提交，故用是否包含判断
        Hawk.put(HawkConfig.IPTV_AUTO_UPDATE, p.containsKey("auto_update"));

        if (p.containsKey("update_interval")) {
            Hawk.put(HawkConfig.IPTV_UPDATE_INTERVAL, parseInt(p.get("update_interval"), HawkConfig.DEFAULT_UPDATE_INTERVAL, 1, 168));
        }

        if (p.containsKey("custom_sources")) {
            Hawk.put(HawkConfig.IPTV_CUSTOM_SOURCES, toJsonArray(p.get("custom_sources")));
        }
        if (p.containsKey("subscribe_url")) {
            Hawk.put(HawkConfig.IPTV_SUBSCRIBE_LIST_URL, trim(p.get("subscribe_url")));
        }
        if (p.containsKey("alias_url")) {
            Hawk.put(HawkConfig.IPTV_ALIAS_URL, trim(p.get("alias_url")));
        }
        if (p.containsKey("multi_repo_urls")) {
            Hawk.put(HawkConfig.IPTV_MULTI_REPO_URLS, toJsonArray(p.get("multi_repo_urls")));
        }

        // 频道模板与线路数
        Hawk.put(HawkConfig.IPTV_TEMPLATE_ENABLED, p.containsKey("template_enabled"));
        if (p.containsKey("template_url")) {
            Hawk.put(HawkConfig.IPTV_TEMPLATE_URL, trim(p.get("template_url")));
        }
        if (p.containsKey("urls_limit")) {
            Hawk.put(HawkConfig.IPTV_URLS_LIMIT, parseInt(p.get("urls_limit"), HawkConfig.DEFAULT_URLS_LIMIT, 0, 20));
        }
        // EPG 开关与地址（与 TV 端统一）
        Hawk.put(HawkConfig.IPTV_ENABLE_EPG, p.containsKey("enable_epg"));
        if (p.containsKey("epg_url")) {
            Hawk.put(HawkConfig.IPTV_EPG_URL, trim(p.get("epg_url")));
        }
        if (p.containsKey("logo_prefix")) {
            Hawk.put(HawkConfig.IPTV_LOGO_PREFIX, trim(p.get("logo_prefix")));
        }

        // 测速总开关
        Hawk.put(HawkConfig.SPEED_TEST_ENABLED, p.containsKey("speed_enabled"));
        if (p.containsKey("speed_mode")) {
            String m = p.get("speed_mode");
            int mode = "periodic_prompt".equals(m)
                    ? HawkConfig.SPEED_MODE_PERIODIC_PROMPT : HawkConfig.SPEED_MODE_MANUAL;
            Hawk.put(HawkConfig.SPEED_TEST_MODE, mode);
        }
        if (p.containsKey("speed_interval")) {
            Hawk.put(HawkConfig.SPEED_TEST_INTERVAL, parseInt(p.get("speed_interval"), HawkConfig.DEFAULT_SPEED_TEST_INTERVAL, 1, 168));
        }
        if (p.containsKey("speed_timeout")) {
            Hawk.put(HawkConfig.SPEED_TEST_TIMEOUT, parseInt(p.get("speed_timeout"), HawkConfig.DEFAULT_SPEED_TEST_TIMEOUT, 1, 30));
        }
        if (p.containsKey("speed_concurrency")) {
            Hawk.put(HawkConfig.SPEED_TEST_CONCURRENCY, parseInt(p.get("speed_concurrency"), HawkConfig.DEFAULT_SPEED_TEST_CONCURRENCY, 1, 20));
        }
        if (p.containsKey("min_speed")) {
            Hawk.put(HawkConfig.SPEED_MIN_THRESHOLD, parseInt(p.get("min_speed"), HawkConfig.DEFAULT_SPEED_MIN_THRESHOLD, 0, 1000));
        }
        // 按速度排序 / 过滤不可用线路（与 TV 端统一）
        Hawk.put(HawkConfig.SPEED_SORT_SOURCES, p.containsKey("sort_by_speed"));
        Hawk.put(HawkConfig.SPEED_FILTER_UNAVAILABLE, p.containsKey("filter_unavailable"));

        // 播放器设置（与 TV 端统一）
        if (p.containsKey("player_type")) {
            Hawk.put(HawkConfig.LIVE_PLAYER_TYPE, parseInt(p.get("player_type"), 2, 0, 2));
        }
        if (p.containsKey("scale_mode")) {
            Hawk.put(HawkConfig.LIVE_PLAYER_SCALE, parseInt(p.get("scale_mode"), 0, 0, 4));
        }
        Hawk.put(HawkConfig.LIVE_SHOW_CHANNEL_INFO, p.containsKey("show_channel_info"));
        Hawk.put(HawkConfig.LIVE_SHOW_SPEED_INFO, p.containsKey("show_speed_info"));
        if (p.containsKey("info_duration")) {
            Hawk.put(HawkConfig.LIVE_CHANNEL_INFO_DURATION, parseInt(p.get("info_duration"), 5, 1, 15));
        }
        if (p.containsKey("play_timeout")) {
            Hawk.put(HawkConfig.LIVE_PLAY_TIMEOUT, parseInt(p.get("play_timeout"), HawkConfig.DEFAULT_LIVE_PLAY_TIMEOUT, 0, 60));
        }
        // 系统：开机自启
        Hawk.put(HawkConfig.LIVE_BOOT_STARTUP, p.containsKey("boot_startup"));

        // 应用更新：中转镜像（国内加速）
        String mirror = p.get("update_mirror");
        Hawk.put(HawkConfig.UPDATE_MIRROR_PREFIX, mirror == null ? "" : mirror.trim());

        Timber.i("已通过扫码网页更新配置");
    }

    /** 将多行文本转为 JSON 数组字符串（与 IptvApiService 的解析格式一致） */
    private String toJsonArray(String multiline) {
        List<String> urls = new ArrayList<>();
        if (multiline != null) {
            for (String line : multiline.split("\\r?\\n")) {
                String u = line.trim();
                if (u.startsWith("http://") || u.startsWith("https://")) {
                    urls.add(u);
                }
            }
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < urls.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(urls.get(i).replace("\"", "")).append("\"");
        }
        sb.append("]");
        return sb.toString();
    }

    // ============ 网页生成 ============

    private String formPage() {
        String ipVersion = Hawk.get(HawkConfig.IPTV_IP_VERSION, "all");
        boolean autoUpdate = Hawk.get(HawkConfig.IPTV_AUTO_UPDATE, true);
        int updateInterval = Hawk.get(HawkConfig.IPTV_UPDATE_INTERVAL, HawkConfig.DEFAULT_UPDATE_INTERVAL);
        String customSources = jsonToLines(Hawk.get(HawkConfig.IPTV_CUSTOM_SOURCES, ""));
        String subscribeUrl = Hawk.get(HawkConfig.IPTV_SUBSCRIBE_LIST_URL, HawkConfig.DEFAULT_SUBSCRIBE_LIST_URL);
        String aliasUrl = Hawk.get(HawkConfig.IPTV_ALIAS_URL, HawkConfig.DEFAULT_ALIAS_URL);
        String multiRepoUrls = jsonToLines(Hawk.get(HawkConfig.IPTV_MULTI_REPO_URLS, ""));
        String updateMirror = Hawk.get(HawkConfig.UPDATE_MIRROR_PREFIX, "");
        boolean templateEnabled = Hawk.get(HawkConfig.IPTV_TEMPLATE_ENABLED, true);
        String templateUrl = Hawk.get(HawkConfig.IPTV_TEMPLATE_URL, "");
        int urlsLimit = Hawk.get(HawkConfig.IPTV_URLS_LIMIT, HawkConfig.DEFAULT_URLS_LIMIT);
        boolean enableEpg = Hawk.get(HawkConfig.IPTV_ENABLE_EPG, true);
        String epgUrl = Hawk.get(HawkConfig.IPTV_EPG_URL, HawkConfig.DEFAULT_IPTV_EPG_URL);
        String logoPrefix = Hawk.get(HawkConfig.IPTV_LOGO_PREFIX, HawkConfig.DEFAULT_IPTV_LOGO_PREFIX);
        boolean speedEnabled = Hawk.get(HawkConfig.SPEED_TEST_ENABLED, true);
        int speedMode = Hawk.get(HawkConfig.SPEED_TEST_MODE, HawkConfig.SPEED_MODE_MANUAL);
        int speedInterval = Hawk.get(HawkConfig.SPEED_TEST_INTERVAL, HawkConfig.DEFAULT_SPEED_TEST_INTERVAL);
        int speedTimeout = Hawk.get(HawkConfig.SPEED_TEST_TIMEOUT, HawkConfig.DEFAULT_SPEED_TEST_TIMEOUT);
        int speedConcurrency = Hawk.get(HawkConfig.SPEED_TEST_CONCURRENCY, HawkConfig.DEFAULT_SPEED_TEST_CONCURRENCY);
        int minSpeed = Hawk.get(HawkConfig.SPEED_MIN_THRESHOLD, HawkConfig.DEFAULT_SPEED_MIN_THRESHOLD);
        boolean sortBySpeed = Hawk.get(HawkConfig.SPEED_SORT_SOURCES, true);
        boolean filterUnavailable = Hawk.get(HawkConfig.SPEED_FILTER_UNAVAILABLE, true);
        int playerType = Hawk.get(HawkConfig.LIVE_PLAYER_TYPE, 2);
        int scaleMode = Hawk.get(HawkConfig.LIVE_PLAYER_SCALE, 0);
        boolean showChannelInfo = Hawk.get(HawkConfig.LIVE_SHOW_CHANNEL_INFO, true);
        boolean showSpeedInfo = Hawk.get(HawkConfig.LIVE_SHOW_SPEED_INFO, true);
        int infoDuration = Hawk.get(HawkConfig.LIVE_CHANNEL_INFO_DURATION, 5);
        int playTimeout = Hawk.get(HawkConfig.LIVE_PLAY_TIMEOUT, HawkConfig.DEFAULT_LIVE_PLAY_TIMEOUT);
        boolean bootStartup = Hawk.get(HawkConfig.LIVE_BOOT_STARTUP, false);

        return "<!DOCTYPE html><html lang=\"zh-CN\"><head>"
                + "<meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>TVBox 直播 · 配置</title>" + css()
                + "</head><body><div class=\"card\">"
                + "<h1>TVBox 直播 · 手机配置</h1>"
                + "<a class=\"btn\" href=\"/crash\">🛠 崩溃日志（闪退排查）</a>"
                + "<form method=\"post\" action=\"/\">"

                + section("直播源")
                + field("IP 版本（自动=混合；仅 IPv4/仅 IPv6 只保留对应线路）", select("ip_version", new String[][]{
                        {"all", "自动"}, {"ipv4", "仅 IPv4"}, {"ipv6", "仅 IPv6"}}, ipVersion))
                + field("自定义源（每行一个 URL）",
                        "<textarea name=\"custom_sources\" rows=\"4\" placeholder=\"http://...\">" + esc(customSources) + "</textarea>")
                + field("多仓链接（每行一个 URL，支持 TVBox 多仓/单仓配置，子仓自动展开）",
                        "<textarea name=\"multi_repo_urls\" rows=\"3\" placeholder=\"https://.../仓库地址.txt\">" + esc(multiRepoUrls) + "</textarea>")
                + field("订阅列表地址（每行一个源 URL）",
                        input("subscribe_url", "text", esc(subscribeUrl), "https://.../subscibelist.txt"))
                + field("频道改名规则地址（alias）",
                        input("alias_url", "text", esc(aliasUrl), "https://.../alias.txt"))
                + checkbox("auto_update", "自动更新直播源", autoUpdate)
                + field("更新间隔（小时）", input("update_interval", "number", String.valueOf(updateInterval), "12"))

                + section("应用更新")
                + field("GitHub 中转镜像（国内加速，留空自动）",
                        input("update_mirror", "text", esc(updateMirror),
                                com.github.tvbox.osc.util.HawkConfig.DEFAULT_UPDATE_MIRROR))

                + section("频道模板与线路")
                + checkbox("template_enabled", "启用频道列表模板（按模板过滤排序）", templateEnabled)
                + field("模板地址（留空或填 demo 用内置模板，或填 TXT 链接）",
                        input("template_url", "text", esc(templateUrl), "demo"))
                + field("单频道最多线路（0=不限制）",
                        input("urls_limit", "number", String.valueOf(urlsLimit), "5"))

                + section("EPG 节目单")
                + checkbox("enable_epg", "启用 EPG 节目单", enableEpg)
                + field("EPG 地址（diyp JSON 或 XMLTV .xml/.xml.gz）",
                        input("epg_url", "text", esc(epgUrl), "http://epg.51zmt.top:8000/e1.xml"))

                + section("台标 Logo")
                + field("台标地址前缀（自动按 前缀+频道名.png 获取，留空则不自动获取）",
                        input("logo_prefix", "text", esc(logoPrefix), HawkConfig.DEFAULT_IPTV_LOGO_PREFIX))

                + section("测速")
                + checkbox("speed_enabled", "启用测速", speedEnabled)
                + field("测速时机", select("speed_mode", new String[][]{
                        {"manual", "仅手动测速"}, {"periodic_prompt", "周期性：打开时提示"}},
                        speedMode == HawkConfig.SPEED_MODE_PERIODIC_PROMPT ? "periodic_prompt" : "manual"))
                + field("测速周期（小时）", input("speed_interval", "number", String.valueOf(speedInterval), "24"))
                + field("测速超时（秒）", input("speed_timeout", "number", String.valueOf(speedTimeout), "5"))
                + field("并发线程", input("speed_concurrency", "number", String.valueOf(speedConcurrency), "5"))
                + field("最低速度（KB/s，0=不限制）", input("min_speed", "number", String.valueOf(minSpeed), "50"))
                + checkbox("sort_by_speed", "按速度/质量对线路排序", sortBySpeed)
                + checkbox("filter_unavailable", "测速后移除不可用线路", filterUnavailable)

                + section("播放器")
                + field("播放器内核", select("player_type", new String[][]{
                        {"0", "系统播放器"}, {"1", "IJK 播放器"}, {"2", "ExoPlayer"}}, String.valueOf(playerType)))
                + field("画面缩放", select("scale_mode", new String[][]{
                        {"0", "自适应"}, {"1", "填充"}, {"2", "16:9"}, {"3", "4:3"}, {"4", "原始"}}, String.valueOf(scaleMode)))
                + checkbox("show_channel_info", "显示频道信息栏", showChannelInfo)
                + field("信息栏显示时长（秒）", input("info_duration", "number", String.valueOf(infoDuration), "5"))
                + checkbox("show_speed_info", "显示测速信息", showSpeedInfo)
                + field("播放超时换源（秒，0=关闭）", input("play_timeout", "number", String.valueOf(playTimeout), "10"))

                + section("系统")
                + checkbox("boot_startup", "开机自动启动（部分系统还需在自启动管理中允许本应用）", bootStartup)

                + "<button type=\"submit\">保存到电视</button>"
                + "</form></div></body></html>";
    }

    private String successPage() {
        return "<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>已保存</title>" + css() + "</head><body><div class=\"card\">"
                + "<h1>✅ 配置已保存</h1>"
                + "<p class=\"ok\">设置已写入电视端，可返回电视查看。</p>"
                + "<a class=\"btn\" href=\"/\">继续编辑</a>"
                + "</div></body></html>";
    }

    private String css() {
        return "<style>"
                + "*{box-sizing:border-box}"
                + "body{margin:0;background:#121228;color:#eee;font-family:-apple-system,Segoe UI,Roboto,sans-serif;padding:16px}"
                + ".card{max-width:520px;margin:0 auto;background:#1c1c38;border-radius:14px;padding:20px;box-shadow:0 8px 24px rgba(0,0,0,.4)}"
                + "h1{font-size:20px;margin:0 0 16px;color:#fff}"
                + ".sec{margin:18px 0 8px;color:#a78bfa;font-weight:600;border-bottom:1px solid #2a2a45;padding-bottom:6px}"
                + "label{display:block;margin:12px 0 6px;font-size:14px;color:#bbb}"
                + "input,select,textarea{width:100%;padding:10px;border-radius:8px;border:1px solid #33335a;background:#15152c;color:#fff;font-size:15px}"
                + "textarea{resize:vertical}"
                + ".cb{display:flex;align-items:center;margin:14px 0}"
                + ".cb input{width:auto;margin-right:8px;transform:scale(1.3)}"
                + ".cb label{margin:0}"
                + "button{width:100%;margin-top:22px;padding:14px;border:0;border-radius:10px;background:#7c3aed;color:#fff;font-size:17px;font-weight:600}"
                + "button:active{background:#5b21b6}"
                + ".ok{color:#34d399}"
                + ".btn{display:inline-block;margin-top:16px;color:#a78bfa;text-decoration:none}"
                + "</style>";
    }

    private String section(String title) {
        return "<div class=\"sec\">" + esc(title) + "</div>";
    }

    private String field(String label, String control) {
        return "<label>" + esc(label) + "</label>" + control;
    }

    private String input(String name, String type, String value, String placeholder) {
        return "<input name=\"" + name + "\" type=\"" + type + "\" value=\"" + esc(value)
                + "\" placeholder=\"" + esc(placeholder) + "\">";
    }

    private String select(String name, String[][] options, String selected) {
        StringBuilder sb = new StringBuilder("<select name=\"").append(name).append("\">");
        for (String[] opt : options) {
            sb.append("<option value=\"").append(opt[0]).append("\"")
                    .append(opt[0].equals(selected) ? " selected" : "")
                    .append(">").append(esc(opt[1])).append("</option>");
        }
        sb.append("</select>");
        return sb.toString();
    }

    private String checkbox(String name, String label, boolean checked) {
        return "<div class=\"cb\"><input id=\"" + name + "\" name=\"" + name + "\" type=\"checkbox\""
                + (checked ? " checked" : "") + "><label for=\"" + name + "\">" + esc(label) + "</label></div>";
    }

    // ============ 崩溃日志 ============

    /** 崩溃日志目录：filesDir/crash（由 App 的全局崩溃处理器写入） */
    private java.io.File crashDir() {
        if (appContext == null) return new java.io.File("/dev/null");
        return new java.io.File(appContext.getFilesDir(), "crash");
    }

    private Response serveCrashLogs(IHTTPSession session) throws Exception {
        java.io.File dir = crashDir();

        if (Method.POST.equals(session.getMethod())) {
            Map<String, String> bodyFiles = new HashMap<>();
            session.parseBody(bodyFiles);
            Map<String, String> params = flatten(session.getParameters());
            if ("clear".equals(params.get("action"))) {
                int deleted = 0;
                java.io.File[] list = dir.listFiles();
                if (list != null) {
                    for (java.io.File f : list) {
                        if (f.isFile() && f.delete()) deleted++;
                    }
                }
                return page("已清除崩溃日志", "<p class=\"ok\">已删除 " + deleted + " 个崩溃日志文件。</p>"
                        + "<a class=\"btn\" href=\"/crash\">返回崩溃日志</a>");
            }
            return page("崩溃日志", "<p>未知操作。</p><a class=\"btn\" href=\"/crash\">返回</a>");
        }

        Map<String, String> params = flatten(session.getParameters());
        String file = params.get("file");
        if (file != null && !file.isEmpty()) {
            return serveCrashFile(dir, file);
        }
        return crashListPage(dir);
    }

    private Response crashListPage(java.io.File dir) {
        // 运行日志优先：系统 OOM 杀进程时不会有崩溃文件，只能靠它还原"死前最后一刻"
        String runLogLink = "<p><a class=\"btn\" href=\"/crash?file=" + urlEnc(RUN_LOG_NAME)
                + "\">📜 查看运行日志（含每步堆内存，判断闪退原因必看）</a></p>";

        java.io.File[] files = dir.listFiles();
        if (files == null || files.length == 0) {
            return page("崩溃日志", runLogLink
                    + "<p>暂无崩溃日志。若发生闪退后仍为空，说明是系统直接杀进程（多为内存不足），"
                    + "请以运行日志最后几行的堆内存为准。</p>"
                    + "<a class=\"btn\" href=\"/\">返回配置</a>");
        }
        // 文件名为 crash-yyyyMMdd-HHmmss.txt，按名称倒序即按时间倒序
        java.util.Arrays.sort(files, (a, b) -> b.getName().compareTo(a.getName()));

        StringBuilder sb = new StringBuilder();
        sb.append(runLogLink);
        sb.append("<p>共 ").append(files.length).append(" 个崩溃日志（新→旧）。点击查看完整堆栈：</p><ul class=\"crash-list\">");
        for (java.io.File f : files) {
            if (!f.isFile()) continue;
            sb.append("<li><a href=\"/crash?file=").append(urlEnc(f.getName()))
                    .append("\">").append(esc(f.getName())).append("</a>")
                    .append(" <span class=\"size\">").append(f.length() / 1024).append(" KB</span></li>");
        }
        sb.append("</ul>");
        sb.append("<form method=\"post\" action=\"/crash\" onsubmit=\"return confirm('确定清除全部崩溃日志？')\">")
                .append("<input type=\"hidden\" name=\"action\" value=\"clear\">")
                .append("<button type=\"submit\">清除全部崩溃日志</button></form>");
        sb.append("<a class=\"btn\" href=\"/\">返回配置</a>");
        return page("崩溃日志", sb.toString());
    }

    private Response serveCrashFile(java.io.File dir, String name) {
        // 运行日志特殊处理：它不在 crash 目录，而在 logs/run.log
        boolean isRunLog = RUN_LOG_NAME.equals(name);

        java.io.File f = isRunLog
                ? com.github.tvbox.osc.util.RunLog.getLogFile()
                : new java.io.File(dir, name);

        // 名称白名单校验，禁止路径穿越
        if (!isRunLog && !name.matches("^crash-[A-Za-z0-9._-]+\\.txt$")) {
            return page("崩溃日志", "<p>非法文件名。</p><a class=\"btn\" href=\"/crash\">返回</a>");
        }
        try {
            if (f == null || !f.isFile()) {
                return page("崩溃日志", "<p>文件不存在。</p><a class=\"btn\" href=\"/crash\">返回</a>");
            }
            if (!f.getCanonicalPath().startsWith(f.getParentFile().getCanonicalPath()
                    + java.io.File.separator)) {
                return page("崩溃日志", "<p>文件不存在。</p><a class=\"btn\" href=\"/crash\">返回</a>");
            }
        } catch (Exception e) {
            return page("崩溃日志", "<p>读取失败: " + esc(e.getMessage()) + "</p><a class=\"btn\" href=\"/crash\">返回</a>");
        }

        StringBuilder content = new StringBuilder();
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"))) {
            char[] buf = new char[8192];
            int n;
            long total = 0;
            while ((n = reader.read(buf)) > 0 && total < 512 * 1024) {
                content.append(buf, 0, n);
                total += n;
            }
        } catch (Exception e) {
            return page("崩溃日志", "<p>读取失败: " + esc(e.getMessage()) + "</p><a class=\"btn\" href=\"/crash\">返回</a>");
        }

        return page("崩溃日志 · " + name,
                "<pre class=\"crash\">" + esc(content.toString()) + "</pre>"
                        + "<a class=\"btn\" href=\"/crash\">返回列表</a> <a class=\"btn\" href=\"/\">返回配置</a>");
    }

    /** 通用页面骨架（复用配置页样式） */
    private Response page(String title, String body) {
        String html = "<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>" + esc(title) + "</title>" + css()
                + "<style>ul.crash-list{list-style:none;padding:0}ul.crash-list li{padding:10px 8px;"
                + "border-bottom:1px solid #2a2a45}ul.crash-list a{color:#a78bfa;text-decoration:none;"
                + "font-family:monospace}ul.crash-list .size{color:#666;font-size:12px}"
                + "pre.crash{background:#15152c;border:1px solid #33335a;border-radius:8px;"
                + "padding:12px;overflow-x:auto;font-size:12px;line-height:1.5;white-space:pre-wrap;"
                + "word-break:break-all;max-height:70vh;overflow-y:auto}</style>"
                + "</head><body><div class=\"card\">"
                + "<h1>" + esc(title) + "</h1>"
                + body
                + "</div></body></html>";
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html);
    }

    // ============ 工具 ============

    private static String urlEnc(String s) {
        try {
            return java.net.URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private String jsonToLines(String json) {
        if (json == null || json.isEmpty()) return "";
        json = json.trim();
        if (json.startsWith("[") && json.endsWith("]")) {
            json = json.substring(1, json.length() - 1);
        }
        StringBuilder sb = new StringBuilder();
        for (String part : json.split(",")) {
            String u = part.trim().replace("\"", "").replace("'", "");
            if (!u.isEmpty()) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(u);
            }
        }
        return sb.toString();
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static int parseInt(String s, int def, int min, int max) {
        try {
            int v = Integer.parseInt(s.trim());
            if (v < min) v = min;
            if (v > max) v = max;
            return v;
        } catch (Exception e) {
            return def;
        }
    }
}
