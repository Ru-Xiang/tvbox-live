package com.github.tvbox.osc.util;

/**
 * Hawk 配置常量定义
 * 扩展 TVBoxOS 原有配置，增加直播源和测速相关配置项
 */
public class HawkConfig {

    // ============ 原 TVBoxOS 配置项 ============
    public static final String API_URL = "api_url";
    public static final String PLAY_TYPE = "play_type";
    public static final String IJK_CODEC = "ijk_codec";
    public static final String DOH_URL = "doh_url";
    public static final String SEARCH_VIEW = "search_view";
    public static final String HOME_REC = "home_rec";

    // ============ 直播源配置 ============

    /**
     * IP版本偏好：all / ipv4 / ipv6
     * ipv4 -> 仅生成并使用 IPv4 线路列表；ipv6 -> 仅 IPv6；all -> 混合不区分。
     */
    public static final String IPTV_IP_VERSION = "iptv_ip_version";

    /** 是否启用自动更新直播源 */
    public static final String IPTV_AUTO_UPDATE = "iptv_auto_update";

    /** 自动更新间隔（小时） */
    public static final String IPTV_UPDATE_INTERVAL = "iptv_update_interval";

    /** 上次更新直播源的时间 */
    public static final String IPTV_LAST_UPDATE_TIME = "iptv_last_update_time";

    /** 自定义直播源URL列表（JSON数组） */
    public static final String IPTV_CUSTOM_SOURCES = "iptv_custom_sources";

    /** 是否启用 EPG 节目单 */
    public static final String IPTV_ENABLE_EPG = "iptv_enable_epg";

    /** EPG 数据接口地址（diyp/百川 JSON 接口，形如 https://host/?ch=频道&date=日期） */
    public static final String IPTV_EPG_URL = "iptv_epg_url";

    /** 是否启用频道列表模板（按模板过滤并排序频道） */
    public static final String IPTV_TEMPLATE_ENABLED = "iptv_template_enabled";

    /** 频道列表模板地址（TXT 格式：分组,#genre# + 频道名行） */
    public static final String IPTV_TEMPLATE_URL = "iptv_template_url";

    /** 单频道最多保留线路（源）数，0 表示不限制 */
    public static final String IPTV_URLS_LIMIT = "iptv_urls_limit";

    /** 订阅列表地址（每行一个源 URL，整体作为聚合源） */
    public static final String IPTV_SUBSCRIBE_LIST_URL = "iptv_subscribe_list_url";

    /** 频道改名（别名）规则地址 */
    public static final String IPTV_ALIAS_URL = "iptv_alias_url";

    /**
     * 多仓链接列表（JSON 数组字符串，每项一个多仓/单仓配置地址）。
     *
     * <p>与「自定义源」分开存储：多仓链接属于用户显式指定的仓库地址，
     * 不参与"连续未入选自动删除"清理；展开出的直播源与订阅列表、
     * 自定义源一起合并聚合。
     */
    public static final String IPTV_MULTI_REPO_URLS = "iptv_multi_repo_urls";

    /** 自定义/订阅源连续未入选次数统计（JSON: {url:count}） */
    public static final String IPTV_SOURCE_FAIL_COUNT = "iptv_source_fail_count";

    // ============ 应用更新 ============

    /** 上次检查应用更新的时间（毫秒） */
    public static final String UPDATE_LAST_CHECK_TIME = "update_last_check_time";

    /** 用户选择"跳过"的版本号，该版本不再提示 */
    public static final String UPDATE_SKIP_VERSION = "update_skip_version";

    /** 更新用的中转镜像前缀（如 https://gh-proxy.com/ ），留空则自动依次尝试内置镜像 */
    public static final String UPDATE_MIRROR_PREFIX = "update_mirror_prefix";

    /** 自定义源连续未入选达到该次数则自动删除 */
    public static final int MAX_SOURCE_FAIL_COUNT = 3;

    // ============ 测速相关配置 ============

    /** 是否启用自动测速 */
    public static final String SPEED_TEST_ENABLED = "speed_test_enabled";

    /**
     * 测速模式：
     * 0 = 周期性打开时提示（超过设定周期未测速时，在打开应用时弹窗提示用户）
     * 1 = 仅手动（仅在设置中手动触发）
     */
    public static final String SPEED_TEST_MODE = "speed_test_mode";

    /** 测速模式：周期性打开时提示 */
    public static final int SPEED_MODE_PERIODIC_PROMPT = 0;

    /** 测速模式：仅手动 */
    public static final int SPEED_MODE_MANUAL = 1;

    /** 测速周期（小时），当 mode=2 时生效 */
    public static final String SPEED_TEST_INTERVAL = "speed_test_interval";

    /** 上次测速时间 */
    public static final String SPEED_TEST_LAST_TIME = "speed_test_last_time";

    /** 测速超时时间（秒） */
    public static final String SPEED_TEST_TIMEOUT = "speed_test_timeout";

    /** 测速并发数 */
    public static final String SPEED_TEST_CONCURRENCY = "speed_test_concurrency";

    /** 是否按速度排序频道源 */
    public static final String SPEED_SORT_SOURCES = "speed_sort_sources";

    /** 最低可接受速度（KB/s），低于此值的源会被标记为不可用 */
    public static final String SPEED_MIN_THRESHOLD = "speed_min_threshold";

    /**
     * 测速完成后是否自动移除不可用（available=false）的线路。
     * 保护策略：若一个频道所有线路均测速失败，则不移除任何线路，
     * 避免频道变为无源可播。
     */
    public static final String SPEED_FILTER_UNAVAILABLE = "speed_filter_unavailable";

    /** 测速失败超时重试次数（对齐 ISEP：仅对超时失败自动重试） */
    public static final String SPEED_TEST_RETRY = "speed_test_retry";

    /** 默认失败超时重试次数 */
    public static final int DEFAULT_SPEED_TEST_RETRY = 1;

    /**
     * 源排序模式：
     * 0 = 按纯下载速度（旧模式）
     * 1 = 按综合质量评分（速度 + 分辨率 + 延迟 + 编码）
     */
    public static final String SPEED_SORT_MODE = "speed_sort_mode";
    public static final int SORT_MODE_SPEED_ONLY = 0;
    public static final int SORT_MODE_QUALITY_SCORE = 1;

    /** 测速结果持久化 JSON（url -> 序列化后的结果） */
    public static final String SPEED_TEST_RESULT_CACHE = "speed_test_result_cache";

    /** 测速结果缓存最大条数（LRU 上限） */
    public static final int SPEED_TEST_RESULT_CACHE_MAX = 500;

    /**
     * 测速后生成的"最佳线路本地播放列表"内容（TXT 格式：分组,#genre# + 频道名,最佳URL）。
     * 由整体订阅源（默认订阅 + 自定义源）测速后每个频道取最佳线路生成并持久化。
     */
    public static final String LIVE_LOCAL_PLAYLIST = "live_local_playlist";

    /** 最佳线路本地播放列表生成时间戳 */
    public static final String LIVE_LOCAL_PLAYLIST_TIME = "live_local_playlist_time";

    /** 最佳线路本地播放列表落盘文件名（位于 App filesDir 下） */
    public static final String LOCAL_PLAYLIST_FILE_NAME = "best_playlist.txt";

    /**
     * 频道去重策略：
     * 0 = 按名称（默认，模糊匹配）
     * 1 = 按 URL（同 URL 视为同一线路）
     * 2 = 按 名称 + URL（同名频道同 URL 才视为重复线路）
     */
    public static final String CHANNEL_DEDUP_MODE = "channel_dedup_mode";
    public static final int DEDUP_MODE_NAME = 0;
    public static final int DEDUP_MODE_URL = 1;
    public static final int DEDUP_MODE_NAME_AND_URL = 2;

    // ============ 直播播放器配置 ============

    /** 直播默认播放器类型：0=系统, 1=IJK, 2=ExoPlayer */
    public static final String LIVE_PLAYER_TYPE = "live_player_type";

    /** 直播播放器缩放模式 */
    public static final String LIVE_PLAYER_SCALE = "live_player_scale";

    /** 是否在直播中显示频道信息栏 */
    public static final String LIVE_SHOW_CHANNEL_INFO = "live_show_channel_info";

    /** 频道信息栏显示时长（秒） */
    public static final String LIVE_CHANNEL_INFO_DURATION = "live_channel_info_duration";

    /** 是否显示测速信息 */
    public static final String LIVE_SHOW_SPEED_INFO = "live_show_speed_info";

    /** 上次播放的频道名称 */
    public static final String LIVE_LAST_CHANNEL = "live_last_channel";

    /** 上次播放的分组名称 */
    public static final String LIVE_LAST_GROUP = "live_last_group";

    /** 播放超时换源时间（秒）：单个源在该时长内未成功起播则自动切换下一个源 */
    public static final String LIVE_PLAY_TIMEOUT = "live_play_timeout";

    /** 是否开机自动启动（默认关闭） */
    public static final String LIVE_BOOT_STARTUP = "live_boot_startup";

    // ============ 线路（源）级 置顶 / 黑名单（仅在所属频道内生效） ============

    /**
     * 线路黑名单（Map<channelKey, List<url>>，JSON）。
     * 仅在对应频道内隐藏这些线路 URL，其它频道相同 URL 不受影响。
     */
    public static final String LIVE_SOURCE_BLACKLIST = "live_source_blacklist";

    /**
     * 线路自定义顺序（Map<channelKey, List<url>>，JSON）。
     * 记录被用户置顶/排序后的线路 URL 顺序，加载频道时据此重排。
     */
    public static final String LIVE_SOURCE_ORDER = "live_source_order";

    // ============ 默认值 ============


    /** 默认 EPG 订阅源地址（XMLTV） */
    public static final String DEFAULT_IPTV_EPG_URL = "http://epg.51zmt.top:8000/e1.xml";

    /** 台标 logo 地址前缀（自动按 前缀 + 频道名 + .png 生成台标） */
    public static final String IPTV_LOGO_PREFIX = "iptv_logo_prefix";

    /** 默认台标 logo 地址前缀（经 gh-proxy 加速） */
    public static final String DEFAULT_IPTV_LOGO_PREFIX =
            "https://gh-proxy.org/https://raw.githubusercontent.com/fanmingming/live/refs/heads/main/tv/";

    /** 默认订阅列表地址（经 gh-proxy 加速） */
    public static final String DEFAULT_SUBSCRIBE_LIST_URL =
            "https://gh-proxy.org/https://raw.githubusercontent.com/Ru-Xiang/m3u/refs/heads/main/subscibelist.txt";

    /** 旧版默认订阅列表地址（用于迁移到 gh-proxy 加速地址） */
    public static final String LEGACY_SUBSCRIBE_LIST_URL =
            "https://raw.githubusercontent.com/Ru-Xiang/m3u/refs/heads/main/subscibelist.txt";

    /** 默认频道改名规则地址 */
    public static final String DEFAULT_ALIAS_URL =
            "https://raw.githubusercontent.com/Ru-Xiang/m3u/refs/heads/main/alias.txt";

    /** 默认单频道最多线路数 */
    public static final int DEFAULT_URLS_LIMIT = 5;

    /** 默认测速超时（秒） */
    public static final int DEFAULT_SPEED_TEST_TIMEOUT = 5;

    /** 默认测速并发数 */
    public static final int DEFAULT_SPEED_TEST_CONCURRENCY = 5;

    /** 默认测速周期（小时） */
    public static final int DEFAULT_SPEED_TEST_INTERVAL = 24;

    /** 默认最低速度阈值 */
    public static final int DEFAULT_SPEED_MIN_THRESHOLD = 50;

    /** 默认播放超时换源时间（秒） */
    public static final int DEFAULT_LIVE_PLAY_TIMEOUT = 10;

    /** 默认更新间隔（小时） */
    public static final int DEFAULT_UPDATE_INTERVAL = 12;
}
