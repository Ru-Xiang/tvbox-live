package com.github.tvbox.osc.util;

import com.github.tvbox.osc.App;
import com.github.tvbox.osc.bean.LiveChannel;
import com.github.tvbox.osc.bean.SpeedTestResult;
import com.orhanobut.hawk.Hawk;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import timber.log.Timber;

/**
 * 线路画质过滤规则（黑屏 / 低分辨率），测速引擎、ChannelManager、播放页共用一份口径。
 *
 * <p>另外记录播放时观察到的线路画质：实际分辨率（比测速时从清单/码流头部解析的更准）与
 * "黑屏"标记（持续全黑或始终出不了画面）。测速结果每轮被新结果覆盖，这些观察结果单独存放，
 * 供下一轮测速结束时据此剔除线路。
 */
public final class LineQuality {

    private LineQuality() {}

    /** 黑屏标记的有效期：过期后视为未知（线路可能已恢复），由下次播放重新判断 */
    public static final long BLACK_TTL_MS = 7L * 24 * 3600 * 1000;

    private static final int MAX_ENTRIES = 4000;
    private static final String FILE_NAME = "line_quality.json";

    private static final class Obs {
        long blackAt;
        int height;
    }

    private static final Map<String, Obs> STORE = new LinkedHashMap<String, Obs>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Obs> eldest) {
            return size() > MAX_ENTRIES;
        }

        // 键去掉回看元数据（见 StreamUrl.withoutMeta）：线路补上回看模板后黑屏/分辨率记录仍然对得上
        @Override
        public Obs get(Object key) {
            return super.get(norm(key));
        }

        @Override
        public Obs put(String key, Obs value) {
            return super.put(StreamUrl.withoutMeta(key), value);
        }

        @Override
        public Obs remove(Object key) {
            return super.remove(norm(key));
        }

        @Override
        public boolean containsKey(Object key) {
            return super.containsKey(norm(key));
        }

        private Object norm(Object key) {
            return key instanceof String ? StreamUrl.withoutMeta((String) key) : key;
        }
    };
    private static boolean loaded;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "LineQualityIO");
        t.setDaemon(true);
        return t;
    });

    // ============ 规则 ============

    /** 是否启用黑屏线路过滤 */
    public static boolean filterBlackEnabled() {
        try {
            return Hawk.get(HawkConfig.SPEED_FILTER_BLACK, true);
        } catch (Throwable t) {
            return true;
        }
    }

    /** 最低分辨率（画面高度），0 = 不限 */
    public static int minHeight() {
        try {
            Integer v = Hawk.get(HawkConfig.SPEED_MIN_RESOLUTION, HawkConfig.DEFAULT_SPEED_MIN_RESOLUTION);
            return v != null && v > 0 ? v : 0;
        } catch (Throwable t) {
            return HawkConfig.DEFAULT_SPEED_MIN_RESOLUTION;
        }
    }

    /**
     * 广播 / 电台类频道：本来就没有画面，不做黑屏与分辨率过滤。
     * 按分组名与频道名判断。
     */
    public static boolean isAudioChannel(LiveChannel channel) {
        if (channel == null) return false;
        return looksAudio(channel.getGroupName()) || looksAudio(channel.getChannelName());
    }

    private static boolean looksAudio(String s) {
        if (s == null || s.isEmpty()) return false;
        String l = s.toLowerCase(Locale.ROOT);
        return l.contains("广播") || l.contains("电台") || l.contains("radio") || l.contains("音频")
                || l.contains("fm") || l.contains("收音");
    }

    /**
     * 把 "1920x1080" 换算成 16:9 等效高度：宽银幕 1920x800 按宽度算 1080，避免被当成低分辨率。
     * 解析不了返回 0（未知）。
     */
    public static int equivalentHeight(String resolution) {
        if (resolution == null) return 0;
        try {
            String[] parts = resolution.toLowerCase(Locale.ROOT).split("[x×*]");
            if (parts.length != 2) return 0;
            int w = Integer.parseInt(parts[0].trim());
            int h = Integer.parseInt(parts[1].trim());
            return equivalentHeight(w, h);
        } catch (Exception e) {
            return 0;
        }
    }

    public static int equivalentHeight(int width, int height) {
        if (width <= 0 || height <= 0) return 0;
        return Math.max(height, (int) Math.round(width * 9.0 / 16.0));
    }

    /** 已知的线路画面高度（16:9 等效）：取播放观察与测速结果中较高者（任一来源证明够清晰即不算低分辨率，避免旧观察值误删已升级的线路）；0 表示未知 */
    public static int knownHeight(String url, SpeedTestResult r) {
        int observed = observedHeight(url);
        int tested = r != null ? equivalentHeight(r.getResolution()) : 0;
        return Math.max(observed, tested);
    }

    /**
     * 是否为低分辨率线路。留 5% 余量：1920x1036、1440x1072 这类非标准尺寸不算低于 1080。
     * 分辨率未知的线路不算。
     */
    public static boolean isLowResolution(String url, SpeedTestResult r) {
        int min = minHeight();
        if (min <= 0) return false;
        int h = knownHeight(url, r);
        return h > 0 && h < min * 0.95;
    }

    /** 是否为黑屏线路：测速确认没有视频轨，或播放时被标记为黑屏（有效期内） */
    public static boolean isBlackLine(String url, SpeedTestResult r) {
        if (!filterBlackEnabled()) return false;
        if (r != null && r.isVideoMissing()) return true;
        return isMarkedBlack(url);
    }

    // ============ 播放时的观察记录 ============

    /** 播放时确认黑屏 */
    public static void markBlack(String url) {
        if (url == null) return;
        synchronized (STORE) {
            ensureLoaded();
            Obs e = STORE.get(url);
            if (e == null) {
                e = new Obs();
                STORE.put(url, e);
            }
            e.blackAt = System.currentTimeMillis();
        }
        saveAsync();
    }

    /** 播放时确认有正常画面：清除旧的黑屏标记 */
    public static void clearBlack(String url) {
        if (url == null) return;
        boolean changed = false;
        synchronized (STORE) {
            ensureLoaded();
            Obs e = STORE.get(url);
            if (e != null && e.blackAt > 0) {
                e.blackAt = 0;
                changed = true;
            }
        }
        if (changed) saveAsync();
    }

    public static boolean isMarkedBlack(String url) {
        if (url == null) return false;
        synchronized (STORE) {
            ensureLoaded();
            Obs e = STORE.get(url);
            return e != null && e.blackAt > 0
                    && System.currentTimeMillis() - e.blackAt < BLACK_TTL_MS;
        }
    }

    /** 播放时观察到的实际分辨率 */
    public static void recordResolution(String url, int width, int height) {
        int h = equivalentHeight(width, height);
        if (url == null || h <= 0) return;
        boolean changed;
        synchronized (STORE) {
            ensureLoaded();
            Obs e = STORE.get(url);
            if (e == null) {
                e = new Obs();
                STORE.put(url, e);
            }
            changed = e.height != h;
            e.height = h;
        }
        if (changed) saveAsync();
    }

    public static int observedHeight(String url) {
        if (url == null) return 0;
        synchronized (STORE) {
            ensureLoaded();
            Obs e = STORE.get(url);
            return e != null ? e.height : 0;
        }
    }

    // ============ 持久化 ============

    private static File file() {
        try {
            App app = App.getInstance();
            File dir = app != null ? app.getFilesDir() : null;
            return dir != null ? new File(dir, FILE_NAME) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 调用方已持有 STORE 锁 */
    private static void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        File f = file();
        if (f == null || !f.isFile()) return;
        try {
            InputStream in = new FileInputStream(f);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            } finally {
                try { in.close(); } catch (Exception ignore) {}
            }
            JSONArray arr = new JSONArray(bos.toString("UTF-8"));
            long now = System.currentTimeMillis();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String u = o.optString("u", null);
                if (u == null || u.isEmpty()) continue;
                Obs e = new Obs();
                e.blackAt = o.optLong("b", 0);
                if (e.blackAt > 0 && now - e.blackAt >= BLACK_TTL_MS) e.blackAt = 0;
                e.height = o.optInt("h", 0);
                if (e.blackAt == 0 && e.height <= 0) continue;
                STORE.put(u, e);
            }
        } catch (Throwable t) {
            Timber.w(t, "读取线路画质记录失败");
        }
    }

    private static void saveAsync() {
        try {
            IO.execute(LineQuality::save);
        } catch (Throwable ignore) {
        }
    }

    private static void save() {
        File f = file();
        if (f == null) return;
        try {
            JSONArray arr = new JSONArray();
            synchronized (STORE) {
                for (Map.Entry<String, Obs> me : STORE.entrySet()) {
                    Obs e = me.getValue();
                    if (e.blackAt == 0 && e.height <= 0) continue;
                    JSONObject o = new JSONObject();
                    o.put("u", me.getKey());
                    if (e.blackAt > 0) o.put("b", e.blackAt);
                    if (e.height > 0) o.put("h", e.height);
                    arr.put(o);
                }
            }
            File tmp = new File(f.getPath() + ".tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                out.write(arr.toString().getBytes("UTF-8"));
                out.flush();
            } finally {
                try { out.close(); } catch (Exception ignore) {}
            }
            if (!tmp.renameTo(f)) {
                f.delete();
                if (!tmp.renameTo(f)) tmp.delete();
            }
        } catch (Throwable t) {
            Timber.w(t, "保存线路画质记录失败");
        }
    }
}
