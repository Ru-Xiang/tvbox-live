package com.github.tvbox.osc;

import android.app.Application;
import android.content.Context;
import android.os.Looper;

import androidx.multidex.MultiDex;
import androidx.work.WorkManager;

import com.github.tvbox.osc.util.HawkConfig;
import com.orhanobut.hawk.Hawk;

import timber.log.Timber;

/**
 * TVBoxOS-Live 应用入口
 * 
 * 初始化全局组件：
 * 1. Hawk 配置存储
 * 2. Timber 日志
 */
public class App extends Application {

    private static App instance;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        // MultiDex 必须在 attachBaseContext 中安装才能生效
        MultiDex.install(this);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;

        // 安装全局兜底：拦截所有未捕获异常，避免任何角落的 bug 导致整个 App 闪退
        installGlobalCrashHandler();

        // 初始化持久化运行日志（闪退后可从配置页回看"死前最后一刻"）
        com.github.tvbox.osc.util.RunLog.init(this);

        try {
            // 初始化日志
            Timber.plant(new Timber.DebugTree());
        } catch (Exception e) {
            // 日志系统失败不应影响启动
        }

        try {
            // 初始化 Hawk 配置存储
            Hawk.init(this).build();
            initDefaultConfig();
            migrateConfig();
        } catch (Exception e) {
            Timber.w(e, "Hawk 初始化失败，将使用默认值");
        }

        try {
            // 测速改为手动或"周期性打开时提示"，取消任何已注册的后台周期任务
            cancelPeriodicSpeedTest();
        } catch (Exception e) {
            Timber.w(e, "取消周期测速任务失败");
        }

        Timber.i("TVBoxOS-Live 应用启动");
    }

    /**
     * 安装全局未捕获异常处理器。
     * 主线程崩溃通常无救（Looper 已死），但 Toast + 记录后交给默认处理器至少能保留日志；
     * 子线程未捕获异常在这里被吞掉后仅记录，避免连锁杀 App。
     */
    private void installGlobalCrashHandler() {
        final Thread.UncaughtExceptionHandler defaultHandler =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                com.github.tvbox.osc.util.RunLog.i("!!! 未捕获异常 @thread=" + thread.getName()
                        + " : " + throwable + "\n" + stackTrace(throwable));
            } catch (Throwable ignore) {}
            try {
                writeCrashLog(thread, throwable);
            } catch (Throwable ignore) {}
            // 主线程崩溃仍交给系统默认处理，避免僵尸进程；
            // 子线程崩溃仅记录，不再传递，防止连锁杀 App
            if (thread == Looper.getMainLooper().getThread() && defaultHandler != null) {
                defaultHandler.uncaughtException(thread, throwable);
            }
        });
    }

    /** 把异常堆栈转成字符串（用于写入运行日志） */
    private static String stackTrace(Throwable t) {
        if (t == null) return "";
        try {
            java.io.StringWriter sw = new java.io.StringWriter();
            try (java.io.PrintWriter pw = new java.io.PrintWriter(sw)) {
                t.printStackTrace(pw);
            }
            return sw.toString();
        } catch (Throwable ignore) {
            return String.valueOf(t);
        }
    }

    /**
     * 将崩溃堆栈写入 filesDir/crash/crash-时间戳.txt，最多保留最近 10 个。
     * 便于在无法连接 adb 的电视等设备上取回真实崩溃原因。
     */
    private void writeCrashLog(Thread thread, Throwable throwable) {
        try {
            java.io.File dir = new java.io.File(getFilesDir(), "crash");
            if (!dir.exists()) dir.mkdirs();
            String ts = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                    .format(new java.util.Date());
            java.io.File f = new java.io.File(dir, "crash-" + ts + ".txt");
            try (java.io.PrintWriter pw = new java.io.PrintWriter(new java.io.FileWriter(f))) {
                pw.println("time: " + ts);
                pw.println("thread: " + thread.getName());
                pw.println("model: " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL
                        + " / Android " + android.os.Build.VERSION.RELEASE);
                pw.println();
                throwable.printStackTrace(pw);
            }
            // 仅保留最近 10 个崩溃文件
            java.io.File[] files = dir.listFiles();
            if (files != null && files.length > 10) {
                java.util.Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
                for (int i = 0; i < files.length - 10; i++) files[i].delete();
            }
        } catch (Throwable ignore) {
            // 落盘失败不影响崩溃处理
        }
    }

    public static App getInstance() {
        return instance;
    }

    /**
     * 系统内存紧张时主动释放可重建的缓存，降低被系统杀死或 OOM 的概率。
     */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        try {
            // 注意各常量的数值关系：
            //   RUNNING_MODERATE=5 < RUNNING_LOW=10 < RUNNING_CRITICAL=15
            //   UI_HIDDEN=20 < BACKGROUND=40 < MODERATE=60 < COMPLETE=80
            // UI_HIDDEN 表示"界面已不可见"，并非内存紧张，只需释放图片缓存；
            // 因此必须先单独判断它，否则会被 >= RUNNING_LOW 的分支误当作低内存处理。
            if (level == TRIM_MEMORY_UI_HIDDEN) {
                try {
                    com.bumptech.glide.Glide.get(this).clearMemory();
                } catch (Throwable ignore) {}
                Timber.d("界面已不可见，已清理图片内存缓存");
            } else if (level >= TRIM_MEMORY_RUNNING_LOW) {
                releaseCaches(level >= TRIM_MEMORY_COMPLETE);
            }
        } catch (Throwable t) {
            Timber.w(t, "onTrimMemory 释放缓存失败");
        }
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        try {
            releaseCaches(true);
        } catch (Throwable t) {
            Timber.w(t, "onLowMemory 释放缓存失败");
        }
    }

    /**
     * 释放可重建的内存缓存。
     *
     * @param aggressive 为true 时连同EPG 与测速结果的内存缓存一并清理
     */
    private void releaseCaches(boolean aggressive) {
        // 图片内存缓存优先释放（占用最大且可重建）
        try {
            com.bumptech.glide.Glide.get(this).clearMemory();
        } catch (Throwable ignore) {}
        if (!aggressive) {
            Timber.d("内存偏低，已清理图片内存缓存");
            return;
        }
        try {
            com.github.tvbox.osc.api.EpgService.getInstance().clearCache();
        } catch (Throwable ignore) {}
        // 释放 HTTP 空闲连接：每个保活连接都占用 socket 缓冲区，
        // 内存紧张时没必要为后续请求预留
        try {
            com.github.tvbox.osc.util.HttpClients.evictIdleConnections();
        } catch (Throwable ignore) {}
        // 频道名归一化缓存可完全重建，紧张时一并释放
        try {
            com.github.tvbox.osc.util.ChannelNameMatcher.clearCache();
        } catch (Throwable ignore) {}
        Timber.i("内存紧张，已清理图片、EPG、连接池与频道名缓存");
    }

    /**
     * 设置默认配置值
     */
    private void initDefaultConfig() {
        // 直播源默认值
        putDefault(HawkConfig.IPTV_IP_VERSION, "all");
        putDefault(HawkConfig.IPTV_AUTO_UPDATE, true);
        putDefault(HawkConfig.IPTV_UPDATE_INTERVAL, HawkConfig.DEFAULT_UPDATE_INTERVAL);
        putDefault(HawkConfig.IPTV_ENABLE_EPG, true);
        putDefault(HawkConfig.IPTV_EPG_URL, HawkConfig.DEFAULT_IPTV_EPG_URL);
        putDefault(HawkConfig.IPTV_LOGO_PREFIX, HawkConfig.DEFAULT_IPTV_LOGO_PREFIX);
        putDefault(HawkConfig.IPTV_TEMPLATE_ENABLED, true);
        putDefault(HawkConfig.IPTV_TEMPLATE_URL, "");
        putDefault(HawkConfig.IPTV_URLS_LIMIT, HawkConfig.DEFAULT_URLS_LIMIT);
        putDefault(HawkConfig.IPTV_SUBSCRIBE_LIST_URL, HawkConfig.DEFAULT_SUBSCRIBE_LIST_URL);
        putDefault(HawkConfig.IPTV_ALIAS_URL, HawkConfig.DEFAULT_ALIAS_URL);
        // 多仓链接默认空（JSON 数组字符串，与自定义源同一格式）
        putDefault(HawkConfig.IPTV_MULTI_REPO_URLS, "[]");

        // 测速默认值
        putDefault(HawkConfig.SPEED_TEST_ENABLED, true);
        putDefault(HawkConfig.SPEED_TEST_MODE, HawkConfig.SPEED_MODE_MANUAL); // 默认仅手动
        putDefault(HawkConfig.SPEED_TEST_INTERVAL, HawkConfig.DEFAULT_SPEED_TEST_INTERVAL);
        putDefault(HawkConfig.SPEED_TEST_TIMEOUT, HawkConfig.DEFAULT_SPEED_TEST_TIMEOUT);
        putDefault(HawkConfig.SPEED_TEST_CONCURRENCY, HawkConfig.DEFAULT_SPEED_TEST_CONCURRENCY);
        putDefault(HawkConfig.SPEED_SORT_SOURCES, true);
        putDefault(HawkConfig.SPEED_MIN_THRESHOLD, HawkConfig.DEFAULT_SPEED_MIN_THRESHOLD);
        putDefault(HawkConfig.SPEED_FILTER_UNAVAILABLE, true);

        // 播放器默认值
        putDefault(HawkConfig.LIVE_PLAYER_TYPE, 2); // ExoPlayer
        putDefault(HawkConfig.LIVE_PLAYER_SCALE, 0); // 自适应
        putDefault(HawkConfig.LIVE_SHOW_CHANNEL_INFO, true);
        putDefault(HawkConfig.LIVE_CHANNEL_INFO_DURATION, 5);
        putDefault(HawkConfig.LIVE_SHOW_SPEED_INFO, true);
        // 开机自启默认关闭
        putDefault(HawkConfig.LIVE_BOOT_STARTUP, false);
    }

    /**
     * 仅在未设置时写入默认值
     */
    private <T> void putDefault(String key, T value) {
        if (!Hawk.contains(key)) {
            Hawk.put(key, value);
        }
    }

    /**
     * 配置迁移：
     * 将旧版直连 GitHub 的默认订阅地址迁移为 gh-proxy 加速地址，
     * 使老用户也能用上新的默认远程订阅源列表。
     */
    private void migrateConfig() {
        try {
            String current = Hawk.get(HawkConfig.IPTV_SUBSCRIBE_LIST_URL, "");
            if (HawkConfig.LEGACY_SUBSCRIBE_LIST_URL.equals(current)) {
                Hawk.put(HawkConfig.IPTV_SUBSCRIBE_LIST_URL, HawkConfig.DEFAULT_SUBSCRIBE_LIST_URL);
                Timber.i("订阅列表地址已迁移至 gh-proxy 加速地址");
            }
            // 将旧的 EPG 默认地址迁移到新的 XMLTV 订阅源地址
            String epg = Hawk.get(HawkConfig.IPTV_EPG_URL, "");
            if ("https://live.fanmingming.com".equals(epg)) {
                Hawk.put(HawkConfig.IPTV_EPG_URL, HawkConfig.DEFAULT_IPTV_EPG_URL);
                Timber.i("EPG 地址已迁移至默认订阅源: %s", HawkConfig.DEFAULT_IPTV_EPG_URL);
            }
        } catch (Exception e) {
            Timber.w(e, "配置迁移失败");
        }
    }

    /**
     * 取消后台定时测速任务
     * 新策略下测速仅由用户手动触发，或在打开应用时根据周期弹窗提示，
     * 不再在后台静默执行。
     */
    private void cancelPeriodicSpeedTest() {
        try {
            WorkManager.getInstance(this).cancelUniqueWork("periodic_speed_test");
        } catch (Exception e) {
            Timber.w(e, "取消定时测速任务失败");
        }
    }
}
