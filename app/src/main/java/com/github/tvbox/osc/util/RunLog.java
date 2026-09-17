package com.github.tvbox.osc.util;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 轻量持久化运行日志。
 *
 * <p><b>为什么需要它：</b>电视端只有 logcat（进程被杀即彻底消失），而闪退往往是
 * "低内存盒子上跑到一半被系统杀掉"或"某个角落抛出未捕获异常"。没有留痕就永远只能靠猜。
 *
 * <p>本类把拉源 / 测速全过程的关键步骤 + <b>堆内存水位</b>写入 filesDir/logs/run.log
 * （滚动覆盖，体积可控），配合配置页的「运行日志」即可在闪退后立刻看到"死前最后一刻"：
 * <ul>
 *   <li>日志在某一行戛然而止且堆几乎耗尽 → 系统 OOM 杀进程；</li>
 *   <li>日志正常推进但存在 crash-*.txt → Java 异常崩溃。</li>
 * </ul>
 */
public final class RunLog {

    private static final String DIR = "logs";
    private static final String FILE_NAME = "run.log";
    /** 单文件上限，超出即从头重写（低内存设备不宜留太多 IO 垃圾） */
    private static final long MAX_BYTES = 256 * 1024;

    private static final Object LOCK = new Object();
    private static volatile File logFile;

    private RunLog() {
    }

    public static void init(android.content.Context ctx) {
        if (ctx == null) return;
        try {
            File dir = new File(ctx.getFilesDir(), DIR);
            if (!dir.exists() && !dir.mkdirs()) return;
            logFile = new File(dir, FILE_NAME);
            i("=== 应用启动 ===");
        } catch (Throwable ignore) {
            // 日志不可用不得影响主流程
        }
    }

    /** 记录一行日志（含线程名与堆内存水位） */
    public static void i(String msg) {
        synchronized (LOCK) {
            try {
                File f = logFile;
                if (f == null) return;
                if (f.exists() && f.length() > MAX_BYTES) {
                    // 滚动：直接清空重写
                    try (FileOutputStream trunc = new FileOutputStream(f, false)) {
                        trunc.write("--- 日志已满，重新开始 ---\n".getBytes("UTF-8"));
                    }
                }
                Runtime rt = Runtime.getRuntime();
                long usedKb = (rt.totalMemory() - rt.freeMemory()) / 1024;
                long freeKb = (rt.maxMemory() - rt.totalMemory() + rt.freeMemory()) / 1024;
                long maxKb = rt.maxMemory() / 1024;
                String line = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date())
                        + " [" + Thread.currentThread().getName() + "]"
                        + " heap " + usedKb + "K used / " + freeKb + "K free (max " + maxKb + "K)"
                        + " | " + msg + "\n";
                try (OutputStreamWriter w = new OutputStreamWriter(
                        new FileOutputStream(f, true), "UTF-8")) {
                    w.write(line);
                    w.flush();
                }
            } catch (Throwable ignore) {
                // 磁盘满 / IO 异常一律忽略
            }
        }
    }

    public static File getLogFile() {
        File f = logFile;
        return f;
    }
}
