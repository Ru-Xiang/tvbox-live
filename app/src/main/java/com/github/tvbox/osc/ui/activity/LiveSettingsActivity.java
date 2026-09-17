package com.github.tvbox.osc.ui.activity;

import com.github.tvbox.osc.util.ToastUtil;

import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ChannelManager;
import com.github.tvbox.osc.service.BackgroundSpeedTestService;
import com.github.tvbox.osc.service.SpeedTestEngine;
import com.github.tvbox.osc.ui.dialog.ScanConfigDialog;
import com.github.tvbox.osc.util.ActivityCallbacks;
import com.github.tvbox.osc.util.HawkConfig;
import com.orhanobut.hawk.Hawk;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.List;

import timber.log.Timber;

/**
 * 直播设置界面
 *
 * 专为安卓电视适配的设置界面，支持遥控器操作
 * 包含以下设置组：
 * 1. IPTV-API 直播源配置
 * 2. 测速策略配置
 * 3. 播放器设置
 * 4. 界面显示设置
 */
public class LiveSettingsActivity extends AppCompatActivity {

    // ============ 直播源设置 ============
    private Spinner spIpVersion;
    private CheckBox cbAutoUpdate;
    private SeekBar sbUpdateInterval;
    private TextView tvUpdateIntervalValue;
    private Button btnRefreshSource;
    private Button btnAddCustomSource;
    private Button btnScanConfig;
    private Button btnMultiRepoConfig;
    private TextView tvMultiRepoSummary;
    private TextView tvLastUpdateTime;
    private CheckBox cbEnableEpg;
    private SeekBar sbUrlsLimit;
    private TextView tvUrlsLimitValue;
    private CheckBox cbTemplateEnabled;

    // ============ 测速设置 ============
    private CheckBox cbSpeedTestEnabled;
    private RadioGroup rgSpeedTestMode;
    private RadioButton rbPeriodicPrompt;
    private RadioButton rbManualOnly;
    private SeekBar sbSpeedTestInterval;
    private TextView tvSpeedTestIntervalValue;
    private SeekBar sbSpeedTestTimeout;
    private TextView tvTimeoutValue;
    private SeekBar sbConcurrency;
    private TextView tvConcurrencyValue;
    private CheckBox cbSortBySpeed;
    private CheckBox cbFilterUnavailable;
    private SeekBar sbMinSpeed;
    private TextView tvMinSpeedValue;
    private Button btnStartSpeedTest;
    private Button btnClearSpeedCache;
    private TextView tvLastSpeedTestTime;

    // ============ 黑名单 ============
    private TextView tvBlacklistCount;
    private Button btnClearBlacklist;

    // ============ 扫码配置 ============
    private ScanConfigDialog scanConfigDialog;

    // ============ 应用更新 ============
    private TextView tvUpdateState;
    private Button btnCheckUpdate;

    // ============ 播放器设置 ============
    private Spinner spPlayerType;
    private Spinner spScaleMode;
    private CheckBox cbShowChannelInfo;
    private SeekBar sbInfoDuration;
    private CheckBox cbShowSpeedInfo;
    private SeekBar sbPlayTimeout;
    private TextView tvPlayTimeoutValue;

    // ============ 系统 ============
    private CheckBox cbBootStartup;

    // ============ 测速进度（右上角） ============
    private View speedTestBar;
    private ProgressBar speedTestProgress;
    private TextView tvSpeedTestInfo;

    private ChannelManager channelManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_live_settings);

        channelManager = ChannelManager.getInstance(this);

        initViews();
        loadSettings();
        setupListeners();
        autoCheckAppUpdate();
    }

    private void initViews() {
        // 直播源设置
        spIpVersion = findViewById(R.id.sp_ip_version);
        cbAutoUpdate = findViewById(R.id.cb_auto_update);
        sbUpdateInterval = findViewById(R.id.sb_update_interval);
        tvUpdateIntervalValue = findViewById(R.id.tv_update_interval_value);
        btnRefreshSource = findViewById(R.id.btn_refresh_source);
        btnAddCustomSource = findViewById(R.id.btn_add_custom_source);
        btnScanConfig = findViewById(R.id.btn_scan_config);
        btnMultiRepoConfig = findViewById(R.id.btn_multi_repo_config);
        tvMultiRepoSummary = findViewById(R.id.tv_multi_repo_summary);
        tvLastUpdateTime = findViewById(R.id.tv_last_update_time);
        cbEnableEpg = findViewById(R.id.cb_enable_epg);
        sbUrlsLimit = findViewById(R.id.sb_urls_limit);
        tvUrlsLimitValue = findViewById(R.id.tv_urls_limit_value);
        cbTemplateEnabled = findViewById(R.id.cb_template_enabled);

        // 测速设置
        cbSpeedTestEnabled = findViewById(R.id.cb_speed_test_enabled);
        rgSpeedTestMode = findViewById(R.id.rg_speed_test_mode);
        rbPeriodicPrompt = findViewById(R.id.rb_periodic_prompt);
        rbManualOnly = findViewById(R.id.rb_manual_only);
        sbSpeedTestInterval = findViewById(R.id.sb_speed_test_interval);
        tvSpeedTestIntervalValue = findViewById(R.id.tv_speed_test_interval_value);
        sbSpeedTestTimeout = findViewById(R.id.sb_speed_test_timeout);
        tvTimeoutValue = findViewById(R.id.tv_timeout_value);
        sbConcurrency = findViewById(R.id.sb_concurrency);
        tvConcurrencyValue = findViewById(R.id.tv_concurrency_value);
        cbSortBySpeed = findViewById(R.id.cb_sort_by_speed);
        cbFilterUnavailable = findViewById(R.id.cb_filter_unavailable);
        sbMinSpeed = findViewById(R.id.sb_min_speed);
        tvMinSpeedValue = findViewById(R.id.tv_min_speed_value);
        btnStartSpeedTest = findViewById(R.id.btn_start_speed_test);
        btnClearSpeedCache = findViewById(R.id.btn_clear_speed_cache);
        tvLastSpeedTestTime = findViewById(R.id.tv_last_speed_test_time);

        // 黑名单
        tvBlacklistCount = findViewById(R.id.tv_blacklist_count);
        btnClearBlacklist = findViewById(R.id.btn_clear_blacklist);

        // 播放器设置
        spPlayerType = findViewById(R.id.sp_player_type);
        spScaleMode = findViewById(R.id.sp_scale_mode);
        cbShowChannelInfo = findViewById(R.id.cb_show_channel_info);
        sbInfoDuration = findViewById(R.id.sb_info_duration);
        cbShowSpeedInfo = findViewById(R.id.cb_show_speed_info);
        sbPlayTimeout = findViewById(R.id.sb_play_timeout);
        tvPlayTimeoutValue = findViewById(R.id.tv_play_timeout_value);

        // 系统
        cbBootStartup = findViewById(R.id.cb_boot_startup);

        // 应用更新（GitHub Releases）
        tvUpdateState = findViewById(R.id.tv_update_state);
        btnCheckUpdate = findViewById(R.id.btn_check_update);
        if (tvUpdateState != null) {
            tvUpdateState.setText("当前版本：" + com.github.tvbox.osc.BuildConfig.VERSION_NAME
                    + "（仓库 " + com.github.tvbox.osc.util.UpdateManager.GITHUB_OWNER + "/"
                    + com.github.tvbox.osc.util.UpdateManager.GITHUB_REPO + "）");
        }

        // 测速进度（右上角）
        speedTestBar = findViewById(R.id.speed_test_bar);
        speedTestProgress = findViewById(R.id.speed_test_progress);
        tvSpeedTestInfo = findViewById(R.id.tv_speed_test_info);

        // 初始化 Spinner
        setupSpinners();
    }

    private void setupSpinners() {

        // IP 版本选择
        if (spIpVersion != null) {
            ArrayAdapter<String> ipAdapter = new ArrayAdapter<>(this,
                    android.R.layout.simple_spinner_item, new String[]{"自动", "仅 IPv4", "仅 IPv6"});
            ipAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spIpVersion.setAdapter(ipAdapter);
        }

        // 播放器类型
        if (spPlayerType != null) {
            ArrayAdapter<String> playerAdapter = new ArrayAdapter<>(this,
                    android.R.layout.simple_spinner_item,
                    new String[]{"系统播放器", "IJK 播放器", "ExoPlayer"});
            playerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spPlayerType.setAdapter(playerAdapter);
        }

        // 缩放模式
        if (spScaleMode != null) {
            ArrayAdapter<String> scaleAdapter = new ArrayAdapter<>(this,
                    android.R.layout.simple_spinner_item,
                    new String[]{"自适应", "填充", "16:9", "4:3", "原始"});
            scaleAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spScaleMode.setAdapter(scaleAdapter);
        }
    }

    private void loadSettings() {
        // 直播源设置
        if (spIpVersion != null) {
            String ipVersion = Hawk.get(HawkConfig.IPTV_IP_VERSION, "all");
            int idx = "ipv4".equals(ipVersion) ? 1 : "ipv6".equals(ipVersion) ? 2 : 0;
            spIpVersion.setSelection(idx);
        }
        if (cbAutoUpdate != null) cbAutoUpdate.setChecked(Hawk.get(HawkConfig.IPTV_AUTO_UPDATE, true));
        if (cbEnableEpg != null) cbEnableEpg.setChecked(Hawk.get(HawkConfig.IPTV_ENABLE_EPG, true));
        if (cbTemplateEnabled != null) cbTemplateEnabled.setChecked(Hawk.get(HawkConfig.IPTV_TEMPLATE_ENABLED, true));
        updateMultiRepoSummary();

        int urlsLimit = Hawk.get(HawkConfig.IPTV_URLS_LIMIT, HawkConfig.DEFAULT_URLS_LIMIT);
        if (sbUrlsLimit != null) {
            sbUrlsLimit.setMax(20); // 0=不限制, 1-20
            sbUrlsLimit.setProgress(urlsLimit);
        }
        if (tvUrlsLimitValue != null) {
            tvUrlsLimitValue.setText(urlsLimit == 0 ? "不限制" : urlsLimit + " 条");
        }

        int updateInterval = Hawk.get(HawkConfig.IPTV_UPDATE_INTERVAL, HawkConfig.DEFAULT_UPDATE_INTERVAL);
        if (sbUpdateInterval != null) {
            sbUpdateInterval.setMax(72);
            sbUpdateInterval.setProgress(updateInterval);
        }
        if (tvUpdateIntervalValue != null) tvUpdateIntervalValue.setText(updateInterval + " 小时");

        // 上次更新时间
        long lastUpdate = Hawk.get(HawkConfig.IPTV_LAST_UPDATE_TIME, 0L);
        if (tvLastUpdateTime != null) {
            tvLastUpdateTime.setText(lastUpdate > 0 ? formatTime(lastUpdate) : "从未更新");
        }

        // 测速设置
        if (cbSpeedTestEnabled != null) cbSpeedTestEnabled.setChecked(Hawk.get(HawkConfig.SPEED_TEST_ENABLED, true));

        int mode = Hawk.get(HawkConfig.SPEED_TEST_MODE, HawkConfig.SPEED_MODE_MANUAL);
        if (mode == HawkConfig.SPEED_MODE_PERIODIC_PROMPT) {
            if (rbPeriodicPrompt != null) rbPeriodicPrompt.setChecked(true);
        } else {
            if (rbManualOnly != null) rbManualOnly.setChecked(true);
        }

        int speedTestInterval = Hawk.get(HawkConfig.SPEED_TEST_INTERVAL, HawkConfig.DEFAULT_SPEED_TEST_INTERVAL);
        if (sbSpeedTestInterval != null) {
            sbSpeedTestInterval.setMax(168); // 最长7天
            sbSpeedTestInterval.setProgress(speedTestInterval);
        }
        if (tvSpeedTestIntervalValue != null) tvSpeedTestIntervalValue.setText(speedTestInterval + " 小时");

        if (cbSortBySpeed != null) cbSortBySpeed.setChecked(Hawk.get(HawkConfig.SPEED_SORT_SOURCES, true));
        if (cbFilterUnavailable != null) cbFilterUnavailable.setChecked(Hawk.get(HawkConfig.SPEED_FILTER_UNAVAILABLE, true));

        int timeout = Hawk.get(HawkConfig.SPEED_TEST_TIMEOUT, HawkConfig.DEFAULT_SPEED_TEST_TIMEOUT);
        if (sbSpeedTestTimeout != null) {
            sbSpeedTestTimeout.setMax(30);
            sbSpeedTestTimeout.setProgress(timeout);
        }
        if (tvTimeoutValue != null) tvTimeoutValue.setText(timeout + " 秒");

        int concurrency = Hawk.get(HawkConfig.SPEED_TEST_CONCURRENCY, HawkConfig.DEFAULT_SPEED_TEST_CONCURRENCY);
        if (sbConcurrency != null) {
            sbConcurrency.setMax(20);
            sbConcurrency.setProgress(concurrency);
        }
        if (tvConcurrencyValue != null) tvConcurrencyValue.setText(concurrency + " 线程");

        int minSpeed = Hawk.get(HawkConfig.SPEED_MIN_THRESHOLD, HawkConfig.DEFAULT_SPEED_MIN_THRESHOLD);
        if (sbMinSpeed != null) {
            sbMinSpeed.setMax(500);
            sbMinSpeed.setProgress(minSpeed);
        }
        if (tvMinSpeedValue != null) tvMinSpeedValue.setText(minSpeed + " KB/s");

        // 上次测速时间
        if (tvLastSpeedTestTime != null) {
            tvLastSpeedTestTime.setText(SpeedTestEngine.getInstance().getLastTestTimeDescription());
        }

        // 线路管理说明
        if (tvBlacklistCount != null) {
            tvBlacklistCount.setText("在播放界面长按左/右方向键（或长按频道列表中的频道）可管理该频道线路。");
        }

        // 播放器设置
        if (spPlayerType != null) spPlayerType.setSelection(Hawk.get(HawkConfig.LIVE_PLAYER_TYPE, 2));
        if (spScaleMode != null) spScaleMode.setSelection(Hawk.get(HawkConfig.LIVE_PLAYER_SCALE, 0));
        if (cbShowChannelInfo != null) cbShowChannelInfo.setChecked(Hawk.get(HawkConfig.LIVE_SHOW_CHANNEL_INFO, true));
        if (cbShowSpeedInfo != null) cbShowSpeedInfo.setChecked(Hawk.get(HawkConfig.LIVE_SHOW_SPEED_INFO, true));

        int infoDuration = Hawk.get(HawkConfig.LIVE_CHANNEL_INFO_DURATION, 5);
        if (sbInfoDuration != null) {
            sbInfoDuration.setMax(15);
            sbInfoDuration.setProgress(infoDuration);
        }

        int playTimeout = Hawk.get(HawkConfig.LIVE_PLAY_TIMEOUT, HawkConfig.DEFAULT_LIVE_PLAY_TIMEOUT);
        if (sbPlayTimeout != null) {
            sbPlayTimeout.setMax(60); // 0=关闭, 1-60 秒
            sbPlayTimeout.setProgress(playTimeout);
        }
        if (tvPlayTimeoutValue != null) {
            tvPlayTimeoutValue.setText(playTimeout == 0 ? "关闭" : playTimeout + " 秒");
        }

        // 系统：开机自启（默认关闭）
        if (cbBootStartup != null) {
            cbBootStartup.setChecked(Hawk.get(HawkConfig.LIVE_BOOT_STARTUP, false));
        }
    }

    /**
     * 直播源刷新回调。
     *
     * 必须是<b>静态</b>类 + 弱引用：刷新会触发完整的源聚合，可持续数分钟，
     * 期间后台线程强引用本回调。匿名内部类会隐式持有 Activity，
     * 用户中途退出设置页时 Activity 无法回收。
     *
     * @param showToast 是否需要在完成/失败时给出提示（"重置线路"场景不需要）
     */
    private static class RefreshCallback implements ChannelManager.ChannelCallback {
        private final java.lang.ref.WeakReference<LiveSettingsActivity> ref;
        private final boolean showToast;

        RefreshCallback(LiveSettingsActivity activity, boolean showToast) {
            this.ref = new java.lang.ref.WeakReference<>(activity);
            this.showToast = showToast;
        }

        @Override
        public void onChannelsLoaded(java.util.List<com.github.tvbox.osc.bean.ChannelGroup> groups) {
            // 刷新场景只关心 onChannelsUpdated
        }

        @Override
        public void onChannelsUpdated(java.util.List<com.github.tvbox.osc.bean.ChannelGroup> groups, int newCount) {
            if (!showToast) return;
            ActivityCallbacks.runOnUiThreadIfAlive(ref, () -> {
                LiveSettingsActivity a = ref.get();
                if (a == null) return;
                ToastUtil.show(a, "刷新完成: " + newCount + " 个频道");
                if (a.tvLastUpdateTime != null) {
                    a.tvLastUpdateTime.setText(a.formatTime(System.currentTimeMillis()));
                }
            });
        }

        @Override
        public void onError(String errorMsg) {
            if (!showToast) return;
            ActivityCallbacks.runOnUiThreadIfAlive(ref, () -> {
                LiveSettingsActivity a = ref.get();
                if (a == null) return;
                ToastUtil.show(a, "刷新失败: " + errorMsg);
            });
        }
    }

    private void setupListeners() {
        // 刷新直播源按钮
        if (btnRefreshSource != null) {
            btnRefreshSource.setOnClickListener(v -> {
                saveSettings();
                ToastUtil.show(this, "正在刷新直播源...");
                channelManager.refreshChannels(new RefreshCallback(this, true));
            });
        }

        // 添加自定义源按钮
        if (btnAddCustomSource != null) {
            btnAddCustomSource.setOnClickListener(v -> showAddCustomSourceDialog());
        }

        // 扫码配置按钮
        if (btnScanConfig != null) {
            btnScanConfig.setOnClickListener(v -> showScanConfigDialog());
        }

        // 多仓链接配置按钮
        if (btnMultiRepoConfig != null) {
            btnMultiRepoConfig.setOnClickListener(v -> showMultiRepoDialog());
        }

        // 手动测速按钮
        if (btnStartSpeedTest != null) {
            btnStartSpeedTest.setOnClickListener(v -> {
                saveSettings();
                // 不再拦截"正在测速"：再次点击表示重新测速，
                // 测速引擎会取消当前任务并从头开始
                // 立刻在右上角给出反馈，覆盖联网拉取全量源的空窗期
                if (speedTestBar != null) speedTestBar.setVisibility(View.VISIBLE);
                if (speedTestProgress != null) {
                    speedTestProgress.setMax(1);
                    speedTestProgress.setProgress(0);
                }
                if (tvSpeedTestInfo != null) {
                    tvSpeedTestInfo.setText("正在获取全量线路...");
                }
                // 立即（前台状态下）启动前台服务，联网拉取全量源与测速均在服务内部完成，
                // 避免耗时网络后再启动前台服务被系统拒绝
                BackgroundSpeedTestService.startSpeedTestWithReload(this);
            });
        }

        // 清除测速缓存
        if (btnClearSpeedCache != null) {
            btnClearSpeedCache.setOnClickListener(v -> {
                SpeedTestEngine.getInstance().clearCache();
                Hawk.put(HawkConfig.SPEED_TEST_LAST_TIME, 0L);
                // 同步清除本地列表，清除后下次打开将重新提醒测速
                channelManager.clearLocalPlaylist();
                // 同步清除本地台标缓存，便于更换台标前缀后重新拉取
                com.github.tvbox.osc.util.LogoCache.clear(this);
                // 同步清除 EPG 本地缓存，便于更换 EPG 源后立即生效
                com.github.tvbox.osc.api.EpgService.getInstance().clearDiskCache();
                // 重建展示列表：此前因"全部线路测速失败"而被隐藏的频道，
                // 在缓存清空后应立即恢复显示，而不是等到下次测速
                channelManager.rebuildDisplayGroups();
                ToastUtil.show(this, "测速缓存与本地列表已清除");
                if (tvLastSpeedTestTime != null) tvLastSpeedTestTime.setText("从未测速");
            });
        }

        // 检查应用更新（GitHub Releases）
        if (btnCheckUpdate != null) {
            btnCheckUpdate.setOnClickListener(v -> checkAppUpdate(true));
        }

        // 清空线路黑名单（恢复所有被删除的线路及自定义置顶顺序）
        if (btnClearBlacklist != null) {
            btnClearBlacklist.setOnClickListener(v -> {
                new AlertDialog.Builder(this)
                        .setTitle("重置线路")
                        .setMessage("将清空所有频道的线路黑名单与置顶顺序，并重新刷新直播源，确定吗？")
                        .setPositiveButton("确定", (d, w) -> {
                            channelManager.clearSourcePrefs();
                            ToastUtil.show(this, "已重置，正在刷新...");
                            // 用弱引用回调：即使这里不需要回显结果，匿名内部类也会
                            // 隐式持有 Activity，而刷新任务可能持续数分钟
                            channelManager.refreshChannels(new RefreshCallback(this, false));
                        })
                        .setNegativeButton("取消", null)
                        .show();
            });
        }

        // SeekBar 监听
        setupSeekBarListener(sbUpdateInterval, tvUpdateIntervalValue, " 小时");
        setupSeekBarListener(sbSpeedTestInterval, tvSpeedTestIntervalValue, " 小时");
        setupSeekBarListener(sbSpeedTestTimeout, tvTimeoutValue, " 秒");
        setupSeekBarListener(sbConcurrency, tvConcurrencyValue, " 线程");
        setupSeekBarListener(sbMinSpeed, tvMinSpeedValue, " KB/s");

        // 最大线路数（0 显示"不限制"）
        if (sbUrlsLimit != null) {
            sbUrlsLimit.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (tvUrlsLimitValue != null) {
                        tvUrlsLimitValue.setText(progress == 0 ? "不限制" : progress + " 条");
                    }
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            });
        }

        // 播放超时换源（0 显示"关闭"）
        if (sbPlayTimeout != null) {
            sbPlayTimeout.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (tvPlayTimeoutValue != null) {
                        tvPlayTimeoutValue.setText(progress == 0 ? "关闭" : progress + " 秒");
                    }
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            });
        }

        // 测速启用/禁用联动
        if (cbSpeedTestEnabled != null) {
            cbSpeedTestEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> {
                updateSpeedTestSettingsVisibility(isChecked);
            });
        }
    }

    private void setupSeekBarListener(SeekBar seekBar, TextView valueText, String suffix) {
        if (seekBar == null) return;
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int value = Math.max(1, progress);
                if (valueText != null) valueText.setText(value + suffix);
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }

    private void updateSpeedTestSettingsVisibility(boolean enabled) {
        int visibility = enabled ? View.VISIBLE : View.GONE;
        if (rgSpeedTestMode != null) rgSpeedTestMode.setVisibility(visibility);
        if (sbSpeedTestInterval != null) sbSpeedTestInterval.setVisibility(visibility);
        if (btnStartSpeedTest != null) btnStartSpeedTest.setVisibility(visibility);
    }



    // ============ 多仓链接 ============

    /** 多仓链接数量摘要（显示在「多仓链接配置」按钮右侧） */
    // ============ 应用更新（GitHub Releases）============

    /**
     * 进入设置页时做一次静默检查（每 12 小时最多一次），有更新则直接弹出提示；
     * 手动点击「检查更新」则无视间隔，必定走一次真实请求。
     */
    private void autoCheckAppUpdate() {
        if (!com.github.tvbox.osc.util.UpdateManager.shouldAutoCheck()) return;
        // 延后一小段，避免与页面加载抢 UI 线程
        cbBootStartup.postDelayed(() -> checkAppUpdate(false), 800);
    }

    private void checkAppUpdate(boolean manual) {
        if (isFinishing() || isDestroyed()) return;
        if (tvUpdateState != null) tvUpdateState.setText("正在检查更新...");

        com.github.tvbox.osc.util.UpdateManager.checkForUpdate(
                new com.github.tvbox.osc.util.UpdateManager.UpdateCallback() {
                    @Override
                    public void onUpdate(com.github.tvbox.osc.util.UpdateManager.UpdateInfo info) {
                        if (isFinishing() || isDestroyed()) return;
                        if (tvUpdateState != null) tvUpdateState.setText("发现新版本 " + info.version);
                        if (btnCheckUpdate != null) btnCheckUpdate.setText("下载更新 " + info.version);
                        com.github.tvbox.osc.ui.dialog.UpdateDialog.show(LiveSettingsActivity.this, info);
                    }

                    @Override
                    public void onLatest() {
                        if (isFinishing() || isDestroyed()) return;
                        if (tvUpdateState != null) {
                            tvUpdateState.setText("已是最新版本 " + com.github.tvbox.osc.BuildConfig.VERSION_NAME);
                        }
                        if (manual) ToastUtil.show(LiveSettingsActivity.this, "已是最新版本");
                    }

                    @Override
                    public void onError(String message) {
                        if (isFinishing() || isDestroyed()) return;
                        if (tvUpdateState != null) tvUpdateState.setText("检查更新失败：" + message);
                        if (manual) ToastUtil.show(LiveSettingsActivity.this, message);
                    }
                });
    }

    private void updateMultiRepoSummary() {
        if (tvMultiRepoSummary == null) return;
        int count = parseMultiRepoUrls().size();
        tvMultiRepoSummary.setText(count == 0 ? "未配置多仓链接" : "已配置 " + count + " 个多仓链接");
    }

    /**
     * 读取已保存的多仓链接（JSON 数组字符串 → URL 列表）。
     * 存储格式与「自定义源」一致；解析失败时按行兜底，避免历史/手工数据丢失。
     */
    private List<String> parseMultiRepoUrls() {
        List<String> urls = new ArrayList<>();
        String json = Hawk.get(HawkConfig.IPTV_MULTI_REPO_URLS, "");
        if (json == null || json.trim().isEmpty()) return urls;
        try {
            org.json.JSONArray arr = new org.json.JSONArray(json.trim());
            for (int i = 0; i < arr.length(); i++) {
                String u = arr.optString(i, "").trim();
                if (!u.isEmpty()) urls.add(u);
            }
        } catch (Throwable ignore) {
            for (String line : json.split("\\r?\\n")) {
                String u = line.trim();
                if (u.startsWith("http://") || u.startsWith("https://")) urls.add(u);
            }
        }
        return urls;
    }

    /** 保存多仓链接（每行一个），返回实际保存的条数 */
    private int saveMultiRepoUrls(String multiline) {
        org.json.JSONArray arr = new org.json.JSONArray();
        if (multiline != null) {
            for (String line : multiline.split("\\r?\\n")) {
                String u = line.trim();
                if (u.startsWith("http://") || u.startsWith("https://")) arr.put(u);
            }
        }
        Hawk.put(HawkConfig.IPTV_MULTI_REPO_URLS, arr.toString());
        Timber.i("多仓链接已保存: %d 个", arr.length());
        return arr.length();
    }

    /** 编辑多仓链接：每行一个，支持 TVBox 多仓/单仓配置地址（子仓自动展开） */
    private void showMultiRepoDialog() {
        StringBuilder sb = new StringBuilder();
        for (String u : parseMultiRepoUrls()) {
            if (sb.length() > 0) sb.append("\n");
            sb.append(u);
        }

        EditText input = new EditText(this);
        input.setHint("每行一个多仓链接，例如 https://.../仓库地址.txt");
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_VARIATION_URI);
        input.setMinLines(4);
        input.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        input.setPadding(40, 20, 40, 20);
        input.setText(sb.toString());
        if (input.getText() != null) input.setSelection(input.getText().length());

        new AlertDialog.Builder(this)
                .setTitle("多仓链接（每行一个）")
                .setView(input)
                .setPositiveButton("保存", (dialog, which) -> {
                    int saved = saveMultiRepoUrls(input.getText().toString());
                    updateMultiRepoSummary();
                    ToastUtil.showLong(this, saved == 0
                                    ? "已清空多仓链接"
                                    : "已保存 " + saved + " 个多仓链接，刷新直播源后生效");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showAddCustomSourceDialog() {
        EditText input = new EditText(this);
        input.setHint("请输入直播源URL（支持 M3U/TXT 格式）");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setPadding(40, 20, 40, 20);

        new AlertDialog.Builder(this)
                .setTitle("添加自定义直播源")
                .setView(input)
                .setPositiveButton("添加", (dialog, which) -> {
                    String url = input.getText().toString().trim();
                    if (url.startsWith("http://") || url.startsWith("https://")) {
                        addCustomSource(url);
                        ToastUtil.show(this, "自定义源已添加");
                    } else {
                        ToastUtil.show(this, "请输入有效的HTTP URL");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 添加自定义源。
     *
     * 原实现用字符串拼接手工构造 JSON（substring 去掉尾部 "]" 再追加），
     * 在历史数据为空串或非法 JSON 时会抛 StringIndexOutOfBoundsException，
     * 且 URL 含引号等字符时会产出非法 JSON。这里改为用 JSONArray 正确解析与序列化。
     */
    private void addCustomSource(String url) {
        if (url == null || url.trim().isEmpty()) return;
        final String target = url.trim();
        try {
            String existing = Hawk.get(HawkConfig.IPTV_CUSTOM_SOURCES, "[]");
            org.json.JSONArray arr;
            if (existing == null || existing.trim().isEmpty()) {
                arr = new org.json.JSONArray();
            } else {
                try {
                    arr = new org.json.JSONArray(existing);
                } catch (Exception parseError) {
                    // 历史数据损坏：重建而不是崩溃
                    Timber.w(parseError, "自定义源列表解析失败，重建列表");
                    arr = new org.json.JSONArray();
                }
            }
            // 去重（按去空白后的完整 URL 比较）
            for (int i = 0; i < arr.length(); i++) {
                String item = arr.optString(i, "");
                if (target.equals(item.trim())) return;
            }
            arr.put(target);
            Hawk.put(HawkConfig.IPTV_CUSTOM_SOURCES, arr.toString());
        } catch (Exception e) {
            Timber.w(e, "添加自定义源失败: %s", target);
        }
    }

    private void saveSettings() {
        try {
            saveSettingsInternal();
        } catch (Exception e) {
            Timber.w(e, "保存设置失败");
        }
    }

    private void saveSettingsInternal() {
        // 直播源设置
        if (spIpVersion != null) {
            // 适配器尚未就绪时 getSelectedItemPosition() 会返回 INVALID_POSITION(-1)，
            // 直接用作数组下标会越界（onPause 早于 Spinner 初始化完成时可复现）
            int pos = spIpVersion.getSelectedItemPosition();
            String[] ipVersions = {"all", "ipv4", "ipv6"};
            if (pos >= 0 && pos < ipVersions.length) {
                Hawk.put(HawkConfig.IPTV_IP_VERSION, ipVersions[pos]);
            }
        }
        if (cbAutoUpdate != null) Hawk.put(HawkConfig.IPTV_AUTO_UPDATE, cbAutoUpdate.isChecked());
        if (sbUpdateInterval != null) Hawk.put(HawkConfig.IPTV_UPDATE_INTERVAL, Math.max(1, sbUpdateInterval.getProgress()));
        if (cbEnableEpg != null) Hawk.put(HawkConfig.IPTV_ENABLE_EPG, cbEnableEpg.isChecked());
        if (cbTemplateEnabled != null) Hawk.put(HawkConfig.IPTV_TEMPLATE_ENABLED, cbTemplateEnabled.isChecked());
        if (sbUrlsLimit != null) Hawk.put(HawkConfig.IPTV_URLS_LIMIT, sbUrlsLimit.getProgress());

        // 测速设置
        if (cbSpeedTestEnabled != null) Hawk.put(HawkConfig.SPEED_TEST_ENABLED, cbSpeedTestEnabled.isChecked());

        if (rgSpeedTestMode != null) {
            int mode = HawkConfig.SPEED_MODE_MANUAL;
            int checkedId = rgSpeedTestMode.getCheckedRadioButtonId();
            if (checkedId == R.id.rb_periodic_prompt) mode = HawkConfig.SPEED_MODE_PERIODIC_PROMPT;
            else if (checkedId == R.id.rb_manual_only) mode = HawkConfig.SPEED_MODE_MANUAL;
            Hawk.put(HawkConfig.SPEED_TEST_MODE, mode);
        }

        if (sbSpeedTestInterval != null) Hawk.put(HawkConfig.SPEED_TEST_INTERVAL, Math.max(1, sbSpeedTestInterval.getProgress()));
        if (sbSpeedTestTimeout != null) Hawk.put(HawkConfig.SPEED_TEST_TIMEOUT, Math.max(1, sbSpeedTestTimeout.getProgress()));
        if (sbConcurrency != null) Hawk.put(HawkConfig.SPEED_TEST_CONCURRENCY, Math.max(1, sbConcurrency.getProgress()));
        if (cbSortBySpeed != null) Hawk.put(HawkConfig.SPEED_SORT_SOURCES, cbSortBySpeed.isChecked());
        if (cbFilterUnavailable != null) Hawk.put(HawkConfig.SPEED_FILTER_UNAVAILABLE, cbFilterUnavailable.isChecked());
        if (sbMinSpeed != null) Hawk.put(HawkConfig.SPEED_MIN_THRESHOLD, sbMinSpeed.getProgress());

        // 播放器设置
        // 同样过滤 INVALID_POSITION(-1)，避免把 -1 写入配置导致播放器初始化取到非法值
        if (spPlayerType != null && spPlayerType.getSelectedItemPosition() >= 0) {
            Hawk.put(HawkConfig.LIVE_PLAYER_TYPE, spPlayerType.getSelectedItemPosition());
        }
        if (spScaleMode != null && spScaleMode.getSelectedItemPosition() >= 0) {
            Hawk.put(HawkConfig.LIVE_PLAYER_SCALE, spScaleMode.getSelectedItemPosition());
        }
        if (cbShowChannelInfo != null) Hawk.put(HawkConfig.LIVE_SHOW_CHANNEL_INFO, cbShowChannelInfo.isChecked());
        if (cbShowSpeedInfo != null) Hawk.put(HawkConfig.LIVE_SHOW_SPEED_INFO, cbShowSpeedInfo.isChecked());
        if (sbInfoDuration != null) Hawk.put(HawkConfig.LIVE_CHANNEL_INFO_DURATION, Math.max(1, sbInfoDuration.getProgress()));
        if (sbPlayTimeout != null) Hawk.put(HawkConfig.LIVE_PLAY_TIMEOUT, sbPlayTimeout.getProgress());

        // 系统：开机自启
        if (cbBootStartup != null) Hawk.put(HawkConfig.LIVE_BOOT_STARTUP, cbBootStartup.isChecked());

        Timber.d("设置已保存");
    }

    // ============ 扫码配置 ============

    private void showScanConfigDialog() {
        // 先把当前 UI 设置落盘，避免 onPause 的 saveSettings 覆盖网页提交结果
        saveSettings();
        if (scanConfigDialog == null) {
            scanConfigDialog = new ScanConfigDialog(this);
        }
        scanConfigDialog.show("手机扫码配置", () -> {
            loadSettings();
            ToastUtil.show(this, "手机已提交配置，已更新");
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveSettings();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (scanConfigDialog != null) scanConfigDialog.dismissAndStop();
    }

    @Override
    protected void onStart() {
        super.onStart();
        // 订阅测速进度，用于在右上角展示（与播放页表现一致）
        try {
            if (!EventBus.getDefault().isRegistered(this)) {
                EventBus.getDefault().register(this);
            }
        } catch (Throwable t) {
            Timber.w(t, "注册 EventBus 失败");
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        try {
            if (EventBus.getDefault().isRegistered(this)) {
                EventBus.getDefault().unregister(this);
            }
        } catch (Throwable t) {
            Timber.w(t, "反注册 EventBus 失败");
        }
    }

    // ============ 测速进度事件 ============

    /**
     * 测速准备阶段（联网拉取全量线路）进度。
     * 这一阶段耗时可达数十秒，需要给出文字反馈，否则用户会以为卡死。
     */
    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onSpeedTestPrepare(BackgroundSpeedTestService.SpeedTestPrepareEvent event) {
        if (isFinishing() || isDestroyed()) return;
        if (speedTestBar != null) speedTestBar.setVisibility(View.VISIBLE);
        if (speedTestProgress != null) {
            if (event.total > 0) {
                speedTestProgress.setMax(event.total);
                speedTestProgress.setProgress(event.current);
            } else {
                // 总数未知：显示为无进度状态（0/1），避免进度条显示成已完成
                speedTestProgress.setMax(1);
                speedTestProgress.setProgress(0);
            }
        }
        if (tvSpeedTestInfo != null) {
            String msg = event.message != null && !event.message.isEmpty()
                    ? event.message : "正在准备测速...";
            if (event.total > 0) {
                tvSpeedTestInfo.setText(String.format(java.util.Locale.CHINA,
                        "%s (%d/%d)", msg, event.current, event.total));
            } else {
                tvSpeedTestInfo.setText(msg);
            }
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onSpeedTestProgress(BackgroundSpeedTestService.SpeedTestProgressEvent event) {
        if (isFinishing() || isDestroyed()) return;
        if (speedTestBar != null) speedTestBar.setVisibility(View.VISIBLE);
        if (speedTestProgress != null) {
            speedTestProgress.setMax(Math.max(1, event.total));
            speedTestProgress.setProgress(event.current);
        }
        if (tvSpeedTestInfo != null) {
            int percent = event.total > 0 ? event.current * 100 / event.total : 0;
            tvSpeedTestInfo.setText(String.format(java.util.Locale.CHINA, "测速 %d%% (%d/%d) %s",
                    percent, event.current, event.total,
                    event.channelName != null ? event.channelName : ""));
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onSpeedTestComplete(BackgroundSpeedTestService.SpeedTestCompleteEvent event) {
        if (isFinishing() || isDestroyed()) return;
        if (speedTestBar != null) speedTestBar.setVisibility(View.GONE);
        if (event.cancelled) return;
        int available = 0;
        int total = 0;
        if (event.results != null) {
            total = event.results.size();
            for (com.github.tvbox.osc.bean.SpeedTestResult r : event.results) {
                if (r != null && r.isAvailable()) available++;
            }
        }
        ToastUtil.show(this, String.format(java.util.Locale.CHINA,
                "测速完成: %d/%d 个源可用", available, total));
        // 刷新"上次测速时间"显示
        if (tvLastSpeedTestTime != null) {
            long last = Hawk.get(HawkConfig.SPEED_TEST_LAST_TIME, 0L);
            tvLastSpeedTestTime.setText(last > 0 ? formatTime(last) : "从未测速");
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onSpeedTestError(BackgroundSpeedTestService.SpeedTestErrorEvent event) {
        if (isFinishing() || isDestroyed()) return;
        if (speedTestBar != null) speedTestBar.setVisibility(View.GONE);
        ToastUtil.show(this, "测速失败: " + event.errorMsg);
    }

    private String formatTime(long timeMillis) {
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA);
        return sdf.format(new java.util.Date(timeMillis));
    }
}
