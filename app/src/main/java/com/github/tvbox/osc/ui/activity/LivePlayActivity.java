package com.github.tvbox.osc.ui.activity;

import com.github.tvbox.osc.util.ToastUtil;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ChannelManager;
import com.github.tvbox.osc.api.EpgService;
import com.github.tvbox.osc.bean.ChannelGroup;
import java.util.ArrayList;
import com.github.tvbox.osc.bean.EpgProgram;
import com.github.tvbox.osc.bean.LiveChannel;
import com.github.tvbox.osc.bean.SpeedTestResult;
import com.github.tvbox.osc.service.BackgroundSpeedTestService;
import com.github.tvbox.osc.service.SpeedTestEngine;
import com.github.tvbox.osc.ui.adapter.ChannelListAdapter;
import com.github.tvbox.osc.ui.adapter.GroupListAdapter;
import com.github.tvbox.osc.ui.dialog.EpgDialog;
import com.github.tvbox.osc.ui.dialog.ScanConfigDialog;
import com.github.tvbox.osc.util.ActivityCallbacks;
import com.github.tvbox.osc.util.HawkConfig;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory;
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout;
import com.google.android.exoplayer2.ui.StyledPlayerView;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;
import com.orhanobut.hawk.Hawk;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.List;

import timber.log.Timber;

/**
 * 直播播放界面
 *
 * 适配安卓电视的直播播放器主界面，支持：
 * 1. 遥控器方向键换台
 * 2. 数字键快速换台
 * 3. 频道列表侧边栏（分组 + 频道，收藏置顶）
 * 4. 频道信息弹窗
 * 5. 测速信息显示
 * 6. 收藏 / 黑名单管理（频道列表长按）
 * 7. ExoPlayer 播放，源失败自动切换下一个源
 * 8. 测速：仅手动 或 周期性打开时提示
 */
public class LivePlayActivity extends AppCompatActivity {

    // ============ 播放缓冲参数（以时延换流畅） ============
    /** 频道信息浮层台标的解码尺寸上限（px），避免大图按原尺寸解码 */
    private static final int INFO_LOGO_SIZE_PX = 160;
    /** 最小缓冲时长：低于此值播放器会持续加载 */
    private static final int MIN_BUFFER_MS = 20_000;
    /** 最大缓冲时长：最多向前缓存这么多内容以吸收网络抖动 */
    private static final int MAX_BUFFER_MS = 60_000;
    /**
     * 首次起播所需缓冲。不宜过大：需要小于"播放超时换源"时间，
     * 否则慢速但可用的源会因迟迟未进入 READY 而被误判为失败并换源。
     */
    private static final int BUFFER_FOR_PLAYBACK_MS = 2_500;
    /** 卡顿后恢复播放所需缓冲：给足余量避免"播一下又卡" */
    private static final int BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 10_000;
    /** 单个数据块读取失败后的重试次数，用于抵抗瞬时抖动 */
    private static final int LOAD_ERROR_RETRY_COUNT = 6;
    /** 直播目标缓冲偏移：让播放点距直播边缘更远，抗抖动更强 */
    private static final long LIVE_TARGET_OFFSET_MS = 20_000L;
    /**
     * 缓冲区字节上限。防止高码率（4K）流在按时长缓冲时占用过多内存，
     * 在 MIUI TV 等低内存设备上引发 OOM。
     */
    private static final int TARGET_BUFFER_BYTES = 24 * 1024 * 1024;

    // ============ UI 组件 ============
    private FrameLayout playerContainer;
    private StyledPlayerView playerView;
    private LinearLayout channelInfoPanel;
    private LinearLayout channelListPanel;
    private RecyclerView groupListView;
    private RecyclerView channelListView;
    private ImageView ivInfoLogo;
    private ImageView ivInfoFavorite;
    private TextView tvChannelName;
    private TextView tvChannelNum;
    private TextView tvSourceInfo;
    private TextView tvSpeedInfo;
    /** 右下角常驻：显示"当前线路"的网速 */
    private TextView tvCornerSpeed;
    private TextView tvEpgInfo;
    private TextView tvInputNum;
    private ProgressBar loadingProgress;
    private LinearLayout speedTestBar;
    private ProgressBar speedTestProgress;
    private TextView tvSpeedTestInfo;

    // ============ 播放器 ============
    private ExoPlayer exoPlayer;

    /**
     * 实时带宽估算器：挂到播放器上后，可在播放中读到当前线路的实时吞吐（bits/s），
     * 供右下角常驻网速显示；未播放时回落为测速缓存值。
     */
    private com.google.android.exoplayer2.upstream.DefaultBandwidthMeter bandwidthMeter;
    /** 右下角网速的轮询任务：播放中每 2 秒刷新一次实时吞吐 */
    private final Runnable cornerSpeedTask = this::updateCornerSpeed;

    // ============ 适配器 ============
    private GroupListAdapter groupAdapter;
    private ChannelListAdapter channelAdapter;

    // ============ 管理器 ============
    private ChannelManager channelManager;
    private SpeedTestEngine speedTestEngine;
    private EpgService epgService;
    private Handler handler;
    private ScanConfigDialog scanConfigDialog;
    private EpgDialog epgDialog;

    // ============ 状态 ============
    private boolean isChannelListVisible = false;
    private boolean dataLoaded = false;
    private boolean speedTestChecked = false;
    private boolean okLongPressed = false;
    private boolean sourceKeyLongPressed = false;
    private final StringBuilder numInputBuilder = new StringBuilder();
    private Runnable numInputRunnable;
    private Runnable hideChannelInfoRunnable;
    /** 频道列表侧边栏中当前浏览的分组索引（与正在播放的分组可能不同） */
    private int browsingGroupIndex = 0;

    // ============ 加载优先级控制（播放列表 > 测速 > EPG） ============
    /** 因测速（或测速决策）被推迟加载 EPG 的频道 */
    private LiveChannel deferredEpgChannel;
    /** 是否正处于"测速决策待定"窗口：用户尚未对启动测速提醒作出选择时，EPG 暂缓加载 */
    private boolean speedTestDecisionPending = false;
    /** 测速流程是否进行中（含准备/拉取列表阶段），用于降低 EPG 加载优先级 */
    private boolean speedTestActive = false;
    /** 测速流程开始时间（elapsedRealtime），用于超时兜底，避免 EPG 被永久阻塞 */
    private long speedTestActiveSince = 0L;
    /** 测速进行中时用于重试补拉 EPG 的 Runnable */
    private final Runnable epgFlushRetry = this::flushDeferredEpg;

    // 数字输入等待时长
    private static final int NUM_INPUT_DELAY = 2000;

    // ============ 播放超时换源 ============
    /** 超时换源定时器 */
    private Runnable playTimeoutRunnable;
    /** 本轮换源尝试已经试过的源数量（用于判断该频道是否所有源都已超时/失败） */
    private int sourceTryCount = 0;
    /** 是否已经为当前频道弹出过"全部超时"提醒，避免重复弹窗 */
    private boolean allSourcesTimeoutPrompted = false;

    /** 是否在 onStop 中主动释放过播放器（用于 onStart 判断需不需要重新起播） */
    private boolean playerReleasedOnStop = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_live_play);

        handler = new Handler(Looper.getMainLooper());
        channelManager = ChannelManager.getInstance(this);
        speedTestEngine = SpeedTestEngine.getInstance();
        epgService = EpgService.getInstance();

        initViews();
        initAdapters();
        EventBus.getDefault().register(this);

        loadChannelData();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (exoPlayer != null && exoPlayer.getPlaybackState() != Player.STATE_IDLE) {
            exoPlayer.setPlayWhenReady(true);
        }
        startCornerSpeedTicker();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (exoPlayer != null) {
            exoPlayer.setPlayWhenReady(false);
        }
        stopCornerSpeedTicker();
    }

    @Override
    protected void onStart() {
        super.onStart();
        // 若曾在 onStop 中释放过播放器，回到前台时重新起播当前频道
        if (exoPlayer == null && playerReleasedOnStop) {
            playerReleasedOnStop = false;
            LiveChannel channel = channelManager.getCurrentChannel();
            if (channel != null && channel.getCurrentSourceUrl() != null) {
                playChannel(channel);
            }
        }
    }

    /**
     * 进入后台时彻底释放播放器。
     *
     * 直播场景下后台继续持有解码器没有意义：ExoPlayer 会一直占用硬件解码器、
     * Surface 与数十 MB 的缓冲区。MIUI/电视盒子等低内存系统在后台会强行回收这些
     * 资源，再切回前台时播放器处于非法状态，极易闪退。
     * 这里主动释放，并在 onStart 中重新起播。
     */
    @Override
    protected void onStop() {
        super.onStop();
        if (isFinishing()) return; // 正在退出，交给 onDestroy 统一释放
        if (exoPlayer != null) {
            playerReleasedOnStop = true;
            // 释放前取消挂起的超时换源任务，避免后台误触发换源
            if (playTimeoutRunnable != null && handler != null) {
                handler.removeCallbacks(playTimeoutRunnable);
            }
            releasePlayer();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { EventBus.getDefault().unregister(this); } catch (Exception ignore) {}
        try { handler.removeCallbacksAndMessages(null); } catch (Exception ignore) {}
        // RecyclerView.post 用的是 View 自带 Handler，不受上面的 handler 影响，
        // 通过 ViewTreeObserver 所在的 Handler 统一清空该 View 的待执行任务
        try {
            if (groupListView != null) {
                groupListView.getHandler().removeCallbacksAndMessages(null);
            }
        } catch (Exception ignore) {}
        try {
            if (channelListView != null) {
                channelListView.getHandler().removeCallbacksAndMessages(null);
            }
        } catch (Exception ignore) {}
        if (scanConfigDialog != null) scanConfigDialog.dismissAndStop();
        if (epgDialog != null) {
            epgDialog.dismiss();
            epgDialog = null;
        }
        releasePlayer();
    }

    // ============ 初始化 ============

    private void initViews() {
        playerContainer = findViewById(R.id.player_container);
        playerView = findViewById(R.id.player_view);
        channelInfoPanel = findViewById(R.id.channel_info_panel);
        channelListPanel = findViewById(R.id.channel_list_panel);
        groupListView = findViewById(R.id.rv_group_list);
        channelListView = findViewById(R.id.rv_channel_list);
        ivInfoLogo = findViewById(R.id.iv_info_logo);
        ivInfoFavorite = findViewById(R.id.iv_info_favorite);
        tvChannelName = findViewById(R.id.tv_channel_name);
        tvChannelNum = findViewById(R.id.tv_channel_num);
        tvSourceInfo = findViewById(R.id.tv_source_info);
        tvSpeedInfo = findViewById(R.id.tv_speed_info);
        tvCornerSpeed = findViewById(R.id.tv_corner_speed);
        tvEpgInfo = findViewById(R.id.tv_epg_info);
        tvInputNum = findViewById(R.id.tv_input_num);
        loadingProgress = findViewById(R.id.loading_progress);
        speedTestBar = findViewById(R.id.speed_test_bar);
        speedTestProgress = findViewById(R.id.speed_test_progress);
        tvSpeedTestInfo = findViewById(R.id.tv_speed_test_info);

        if (groupListView != null) {
            groupListView.setLayoutManager(new LinearLayoutManager(this));
            groupListView.setLayoutAnimation(android.view.animation.AnimationUtils
                    .loadLayoutAnimation(this, R.anim.layout_anim_fall_down));
        }
        if (channelListView != null) {
            channelListView.setLayoutManager(new LinearLayoutManager(this));
            channelListView.setLayoutAnimation(android.view.animation.AnimationUtils
                    .loadLayoutAnimation(this, R.anim.layout_anim_fall_down));
        }

        if (channelInfoPanel != null) channelInfoPanel.setVisibility(View.GONE);
        if (channelListPanel != null) channelListPanel.setVisibility(View.GONE);
        if (speedTestBar != null) speedTestBar.setVisibility(View.GONE);
        if (tvInputNum != null) tvInputNum.setVisibility(View.GONE);

        applyScaleMode();
    }

    private void initAdapters() {
        groupAdapter = new GroupListAdapter();
        channelAdapter = new ChannelListAdapter();

        groupAdapter.setOnGroupActionListener((position, group) -> {
            browsingGroupIndex = position;
            // 用抗并发快照：后台线程可能正在结构性修改该分组的频道列表，
            // 直接 new ArrayList<>(getChannels()) 会抛 ConcurrentModificationException，
            // 而本回调由焦点变化触发，不在任何 try/catch 保护内
            channelAdapter.setChannels(group != null ? group.snapshotChannels() : null);
            // 若浏览的是当前播放分组，高亮正在播放的频道
            channelAdapter.setPlayingIndex(
                    position == channelManager.getCurrentGroupIndex()
                            ? channelManager.getCurrentChannelIndex() : -1);
        });

        channelAdapter.setOnChannelActionListener(new ChannelListAdapter.OnChannelActionListener() {
            @Override
            public void onChannelClick(int position, LiveChannel channel) {
                LiveChannel selected = channelManager.selectChannel(browsingGroupIndex, position);
                if (selected != null) {
                    playChannel(selected);
                    channelAdapter.setPlayingIndex(position);
                }
                hideChannelList();
            }

            @Override
            public void onChannelLongClick(int position, LiveChannel channel) {
                // 长按频道：管理该频道的线路（置顶 / 删除拉黑）
                // 传入 position，线路增删后据此把光标还原到该频道上
                showSourceManageDialog(channel, position);
            }
        });

        if (groupListView != null) groupListView.setAdapter(groupAdapter);
        if (channelListView != null) channelListView.setAdapter(channelAdapter);
    }

    private void loadChannelData() {
        showLoading(true);
        channelManager.loadChannels(new LoadChannelsCallback(this));
    }

    /**
     * 频道加载回调。
     *
     * 必须是<b>静态</b>类 + 弱引用：源聚合可持续数分钟，期间后台线程强引用本回调。
     * 若用匿名内部类会隐式持有 Activity，用户中途退出时整个 View 树与 ExoPlayer
     * 都无法回收，在低内存盒子上足以触发 OOM。
     */
    private static class LoadChannelsCallback implements ChannelManager.ChannelCallback {
        private final java.lang.ref.WeakReference<LivePlayActivity> ref;

        LoadChannelsCallback(LivePlayActivity activity) {
            this.ref = new java.lang.ref.WeakReference<>(activity);
        }

        @Override
        public void onChannelsLoaded(List<ChannelGroup> groups) {
            ActivityCallbacks.runOnUiThreadIfAlive(ref, () -> {
                LivePlayActivity a = ref.get();
                if (a == null) return;
                a.showLoading(false);
                a.dataLoaded = true;
                if (groups == null || groups.isEmpty()) {
                    // 首次打开 / 尚未配置：自动展示扫码配置二维码
                    a.showScanConfigForFirstRun();
                    return;
                }
                // 加载优先级：播放列表(已就绪) > 测速 > EPG。
                // 先让 EPG 为"启动测速决策"让路，播放频道后再处理测速提醒；
                // 若无需用户决策则立即放行 EPG，否则等弹窗关闭或测速结束再补拉。
                a.speedTestDecisionPending = true;
                a.refreshGroupAdapter();
                LiveChannel channel = a.channelManager.restoreLastChannel();
                if (channel != null) {
                    a.playChannel(channel);
                }
                Timber.i("频道加载完成: %d 个分组, %d 个频道",
                        groups.size(), a.channelManager.getTotalChannelCount());
                boolean promptShowing = a.checkSpeedTestOnLaunch();
                a.speedTestDecisionPending = promptShowing;
                if (!promptShowing) {
                    a.flushDeferredEpg();
                }
            });
        }

        @Override
        public void onChannelsUpdated(List<ChannelGroup> groups, int newCount) {
            ActivityCallbacks.runOnUiThreadIfAlive(ref, () -> {
                LivePlayActivity a = ref.get();
                if (a == null) return;
                a.showLoading(false);
                a.refreshGroupAdapter();
                ToastUtil.show(a, "已更新 " + newCount + " 个频道");
            });
        }

        @Override
        public void onError(String errorMsg) {
            ActivityCallbacks.runOnUiThreadIfAlive(ref, () -> {
                LivePlayActivity a = ref.get();
                if (a == null) return;
                a.showLoading(false);
                a.dataLoaded = true;
                a.showError(errorMsg);
                // 无可用频道时同样引导用手机扫码配置
                List<ChannelGroup> current = a.channelManager.getChannelGroups();
                if (current == null || current.isEmpty()) {
                    a.showScanConfigForFirstRun();
                }
            });
        }
    }

    /**
     * 首次打开或尚无可用直播源时，自动弹出扫码配置二维码，
     * 引导用户用手机完成配置；手机提交后自动重新加载频道。
     */
    private void showScanConfigForFirstRun() {
        if (scanConfigDialog == null) {
            scanConfigDialog = new ScanConfigDialog(this);
        }
        scanConfigDialog.show("欢迎使用 · 请用手机扫码配置直播源", () -> {
            ToastUtil.show(this, "配置已接收，正在重新加载直播源...");
            // 配置变更后强制从网络刷新
            channelManager.refreshChannels(new ReloadChannelsCallback(this));
        });
    }

    /** 扫码配置完成后的重新加载回调（静态类 + 弱引用，理由同 LoadChannelsCallback） */
    private static class ReloadChannelsCallback implements ChannelManager.ChannelCallback {
        private final java.lang.ref.WeakReference<LivePlayActivity> ref;

        ReloadChannelsCallback(LivePlayActivity activity) {
            this.ref = new java.lang.ref.WeakReference<>(activity);
        }

        @Override
        public void onChannelsLoaded(List<ChannelGroup> groups) {
            ActivityCallbacks.runOnUiThreadIfAlive(ref, () -> {
                LivePlayActivity a = ref.get();
                if (a != null) a.handleReloadedChannels(groups);
            });
        }

        @Override
        public void onChannelsUpdated(List<ChannelGroup> groups, int newCount) {
            ActivityCallbacks.runOnUiThreadIfAlive(ref, () -> {
                LivePlayActivity a = ref.get();
                if (a != null) a.handleReloadedChannels(groups);
            });
        }

        @Override
        public void onError(String errorMsg) {
            ActivityCallbacks.runOnUiThreadIfAlive(ref, () -> {
                LivePlayActivity a = ref.get();
                if (a != null) a.showError("刷新失败: " + errorMsg);
            });
        }
    }

    private void handleReloadedChannels(List<ChannelGroup> groups) {
        if (groups == null || groups.isEmpty()) {
            showError("仍未获取到直播源，请检查配置");
            return;
        }
        if (scanConfigDialog != null) scanConfigDialog.dismissAndStop();
        refreshGroupAdapter();
        LiveChannel channel = channelManager.restoreLastChannel();
        if (channel != null) playChannel(channel);
        ToastUtil.show(this, "直播源加载完成");
    }

    private void refreshGroupAdapter() {
        // 只取一次引用：getChannelGroups() 返回的是 ChannelManager 的 volatile 字段，
        // 后台线程会整体替换它，分别读取会导致校验与取值落在不同快照上
        List<ChannelGroup> groups = channelManager.getChannelGroups();
        if (groups == null) groups = new ArrayList<>();
        groupAdapter.setGroups(groups);

        if (groups.isEmpty()) {
            // 无分组时清空频道列表，避免残留上一批数据
            browsingGroupIndex = 0;
            channelAdapter.setChannels(null);
            channelAdapter.setPlayingIndex(-1);
            return;
        }

        int playingGroup = channelManager.getCurrentGroupIndex();
        browsingGroupIndex = Math.max(0, Math.min(playingGroup, groups.size() - 1));
        groupAdapter.setSelectedIndex(browsingGroupIndex);
        ChannelGroup g = groups.get(browsingGroupIndex);
        channelAdapter.setChannels(g != null ? g.snapshotChannels() : null);
        channelAdapter.setPlayingIndex(
                browsingGroupIndex == playingGroup ? channelManager.getCurrentChannelIndex() : -1);
    }

    /**
     * 线路（源）增删后的列表刷新：把光标保持在操作前所在的那个频道上。
     *
     * <p>与 {@link #refreshGroupAdapter()} 的差异：
     * <ol>
     *   <li>不把浏览分组重置回"正在播放"的分组——用户可能在别的分组里管理线路，
     *       一旦重置整个频道列表会跳走，光标自然也就丢了；</li>
     *   <li>刷新后把焦点还给 {@code keepChannelPos} 对应的频道项。列表整体重建
     *       （notifyDataSetChanged）会回收并重建 ViewHolder，原焦点视图被销毁，
     *       表现为"删除线路后光标消失/跳走"。</li>
     * </ol>
     *
     * @param keepChannelPos 操作前光标所在频道的下标；&lt;0 表示操作前光标不在频道列表中，
     *                       此时与 {@link #refreshGroupAdapter()} 行为完全一致
     */
    private void refreshGroupAdapterKeepingFocus(int keepChannelPos) {
        if (keepChannelPos < 0) {
            // 光标本来就不在列表里（例如遥控器长按左/右键管理当前频道），无需保持
            refreshGroupAdapter();
            return;
        }

        List<ChannelGroup> groups = channelManager.getChannelGroups();
        if (groups == null) groups = new ArrayList<>();
        groupAdapter.setGroups(groups);

        if (groups.isEmpty()) {
            browsingGroupIndex = 0;
            channelAdapter.setChannels(null);
            channelAdapter.setPlayingIndex(-1);
            return;
        }

        int playingGroup = channelManager.getCurrentGroupIndex();
        // 关键差异：这里保留用户当前浏览的分组，而不是强制跳回播放分组
        browsingGroupIndex = Math.max(0, Math.min(browsingGroupIndex, groups.size() - 1));
        groupAdapter.setSelectedIndex(browsingGroupIndex);
        ChannelGroup g = groups.get(browsingGroupIndex);
        channelAdapter.setChannels(g != null ? g.snapshotChannels() : null);
        channelAdapter.setPlayingIndex(
                browsingGroupIndex == playingGroup ? channelManager.getCurrentChannelIndex() : -1);

        restoreChannelFocus(keepChannelPos);
    }

    /**
     * 把焦点重新放回频道列表第 {@code position} 项。
     *
     * <p>列表重建后必须等布局完成才能 requestFocus，而
     * {@link com.github.tvbox.osc.ui.adapter.ChannelListAdapter#setChannels} 在
     * RecyclerView 繁忙时还会把刷新 post 到下一帧，单次延迟可能落在布局之前，
     * 因此按递增延迟重试几次，拿到焦点即停。
     */
    private void restoreChannelFocus(final int position) {
        if (channelListView == null || position < 0) return;
        final long[] delays = {0L, 120L, 300L};
        for (long delay : delays) {
            channelListView.postDelayed(() -> {
                try {
                    if (!isAlive() || !isChannelListVisible) return;
                    if (isFocusIn(channelListView)) return; // 焦点已经回来了，不打扰
                    focusChannelItem(position);
                } catch (Throwable t) {
                    Timber.w(t, "还原频道列表焦点失败");
                }
            }, delay);
        }
    }

    // ============ 播放控制 ============

    private void playChannel(LiveChannel channel) {
        if (channel == null || !isAlive()) return;

        // 关键：把"当前频道"同步到本次要播放的频道。
        // 否则从频道列表长按菜单（播放此线路/置顶）直接播放非当前频道时，
        // 后续播放超时换源、上下切源仍以旧的"当前频道"为准，会切走播成别的频道。
        channelManager.syncCurrentTo(channel);

        // 同步频道列表的"正在播放"高亮，避免菜单播放后标记停留在旧频道
        try {
            if (channelAdapter != null) {
                channelAdapter.setPlayingIndex(
                        browsingGroupIndex == channelManager.getCurrentGroupIndex()
                                ? channelManager.getCurrentChannelIndex() : -1);
            }
        } catch (Exception ignore) {
        }

        String url = channel.getCurrentSourceUrl();
        if (!isPlayableUrl(url)) {
            // 当前源无效：尝试自动切到下一个有效源，避免直接 Toast 卡在无源状态
            Timber.w("频道 %s 当前源无效: %s", channel.getChannelName(), url);
            if (skipToNextValidSource(channel)) {
                url = channel.getCurrentSourceUrl();
            } else {
                ToastUtil.show(this, "该频道无可用源");
                return;
            }
        }

        Timber.d("播放频道: %s, 源: %s", channel.getChannelName(), url);
        // 用户主动切换频道：重置本频道的换源尝试计数和提醒状态
        sourceTryCount = 0;
        allSourcesTimeoutPrompted = false;
        showChannelInfo(channel);
        initPlayer(url);
        loadEpgForChannel(channel);
    }

    /**
     * 检查 URL 是否可用于 ExoPlayer 播放：非空且是合法协议。
     * 无效 URL 直接交给 Uri.parse/setMediaItem 会抛异常导致崩溃。
     */
    private boolean isPlayableUrl(String url) {
        if (url == null) return false;
        String u = url.trim();
        if (u.isEmpty()) return false;
        // 与 ProtocolFilter 保持同一套白名单：rtp/udp/igmp/rtsp/rtmp 等当前内核无法播放，
        // 视为无效线路直接跳过，避免换源时白等超时
        return com.github.tvbox.osc.util.ProtocolFilter.isSupported(u);
    }

    /**
     * 从当前索引起向后寻找一个可播放的源，找到则把 sourceIndex 指向它。
     * 找不到返回 false。
     */
    private boolean skipToNextValidSource(LiveChannel channel) {
        if (channel == null || channel.getSourceUrls() == null) return false;
        int start = channel.getSourceIndex();
        int total = channel.getSourceCount();
        for (int i = 0; i < total; i++) {
            int idx = (start + i) % total;
            String u = channel.getSourceUrls().get(idx);
            if (isPlayableUrl(u)) {
                channel.setSourceIndex(idx);
                return true;
            }
        }
        return false;
    }

    /** 加载当前频道节目单，更新信息栏当前节目 */
    private void loadEpgForChannel(final LiveChannel channel) {
        if (channel == null) return;
        if (!Hawk.get(HawkConfig.IPTV_ENABLE_EPG, true)) {
            channel.setCurrentEpgInfo(null);
            return;
        }
        // 加载优先级：播放列表 > 测速 > EPG。
        // 测速（或启动测速决策）期间暂缓节目单拉取，避免与测速争抢带宽/CPU；
        // 待测速结束或用户完成决策后再补拉（见 flushDeferredEpg）。
        if (speedTestDecisionPending
                || speedTestActive
                || (speedTestEngine != null && speedTestEngine.isRunning())) {
            deferredEpgChannel = channel;
            Timber.d("测速进行中，暂缓加载 EPG: %s", channel.getChannelName());
            return;
        }
        epgService.loadEpg(channel.getChannelName(), new EpgService.EpgCallback() {
            @Override
            public void onEpgLoaded(List<EpgProgram> programs, EpgProgram current) {
                runOnUiThread(() -> {
                    if (current != null) {
                        channel.setCurrentEpgInfo("正在播放: " + current.display());
                    } else {
                        channel.setCurrentEpgInfo(null);
                    }
                    // 若该频道仍是当前频道且信息栏可见，刷新 EPG 文本
                    if (channel == channelManager.getCurrentChannel()
                            && channelInfoPanel != null
                            && channelInfoPanel.getVisibility() == View.VISIBLE
                            && tvEpgInfo != null) {
                        String epg = channel.getCurrentEpgInfo();
                        tvEpgInfo.setVisibility(epg != null && !epg.isEmpty() ? View.VISIBLE : View.GONE);
                        if (epg != null) tvEpgInfo.setText(epg);
                    }
                });
            }

            @Override
            public void onEpgError(String msg) {
                runOnUiThread(() -> channel.setCurrentEpgInfo(null));
            }
        });
    }

    /**
     * 放行被测速推迟的 EPG 加载。
     * 若仍处于"测速决策待定"窗口或测速正在运行，则保持推迟；
     * 由测速结束/取消/失败，或用户完成测速决策（关闭提醒弹窗）后触发。
     */
    private void flushDeferredEpg() {
        LiveChannel channel = deferredEpgChannel;
        if (channel == null) return;
        if (speedTestDecisionPending) return; // 用户尚未对测速提醒作出选择
        // 超时兜底：测速长时间无进展（如服务被系统回收），解除让路，避免 EPG 永久不加载
        if (speedTestActive && speedTestActiveSince > 0
                && android.os.SystemClock.elapsedRealtime() - speedTestActiveSince > 5 * 60 * 1000L) {
            speedTestActive = false;
            speedTestActiveSince = 0L;
        }
        if (speedTestActive || (speedTestEngine != null && speedTestEngine.isRunning())) {
            // 测速进行中：继续让路，并安排稍后重试（测速结束事件同样会触发补拉）
            if (handler != null && isAlive()) {
                handler.removeCallbacks(epgFlushRetry);
                handler.postDelayed(epgFlushRetry, 20000L);
            }
            return;
        }
        if (handler != null) handler.removeCallbacks(epgFlushRetry);
        deferredEpgChannel = null;
        if (!isAlive()) return;
        // 测速会重建频道列表，优先对当前正在播放的频道补拉 EPG
        LiveChannel current = channelManager != null ? channelManager.getCurrentChannel() : null;
        loadEpgForChannel(current != null ? current : channel);
    }

    /**
     * 显示当前频道完整节目单弹窗（新版：日期切换 + 高亮 + 进度条）
     */
    private void showEpgDialog() {
        LiveChannel channel = channelManager.getCurrentChannel();
        if (channel == null) return;
        if (!Hawk.get(HawkConfig.IPTV_ENABLE_EPG, true)) {
            ToastUtil.show(this, "未启用 EPG 节目单");
            return;
        }
        if (!isAlive()) return;
        try {
            if (epgDialog == null) epgDialog = new EpgDialog(this);
            epgDialog.show(channel);
        } catch (Exception e) {
            Timber.w(e, "显示 EPG 弹窗失败");
        }
    }

    /**
     * 初始化 / 切换 ExoPlayer 播放源。
     * 所有 ExoPlayer 调用做异常保护，失败后异步（post 到主线程）触发换源，
     * 避免 ExoPlayer 在 onPlayerError 中同步递归调用 setMediaItem 引发状态混乱与崩溃。
     */
    private void initPlayer(String url) {
        if (playerView == null || !isAlive()) return;
        if (!isPlayableUrl(url)) {
            Timber.w("initPlayer: URL 无效, 触发换源: %s", url);
            postSourceFailure(false);
            return;
        }
        if (exoPlayer == null) {
            try {
                // 自定义 HTTP 数据源：允许跨协议重定向（http<->https）、放宽超时、
                // 使用通用浏览器 UA（部分 IPTV 源会拒绝 ExoPlayer 默认 UA），
                // 这些差异是导致"其他播放器能播、ExoPlayer 播不了"的常见原因。
                DefaultHttpDataSource.Factory httpDataSourceFactory = new DefaultHttpDataSource.Factory()
                        .setUserAgent("Mozilla/5.0 (Linux; Android 10) ExoPlayerLib/2.19.1")
                        .setConnectTimeoutMs(15000)
                        .setReadTimeoutMs(15000)
                        .setAllowCrossProtocolRedirects(true)
                        .setKeepPostFor302Redirects(true);
                DefaultMediaSourceFactory mediaSourceFactory = new DefaultMediaSourceFactory(this)
                        // 按协议分发：rtmp:// 走 RtmpDataSource，其余走 HTTP 数据源。
                        // rtsp:// 由 DefaultMediaSourceFactory 直接创建 RtspMediaSource，无需数据源支持。
                        .setDataSourceFactory(
                                new com.github.tvbox.osc.player.SchemeDataSourceFactory(httpDataSourceFactory))
                        // 单次读取失败后在放弃前多重试几次，抵抗瞬时网络抖动
                        .setLoadErrorHandlingPolicy(
                                new com.google.android.exoplayer2.upstream.DefaultLoadErrorHandlingPolicy(
                                        LOAD_ERROR_RETRY_COUNT));

                // 大缓冲策略：以牺牲直播时延为代价换取抗抖动能力。
                // 同时用字节上限兜住内存：低码率流由时长目标决定缓冲量，
                // 高码率(4K)流则由 TARGET_BUFFER_BYTES 封顶，避免在 MIUI TV 等
                // 低内存设备上因缓冲 60 秒高码率视频而 OOM。
                com.google.android.exoplayer2.DefaultLoadControl loadControl =
                        new com.google.android.exoplayer2.DefaultLoadControl.Builder()
                                .setBufferDurationsMs(
                                        MIN_BUFFER_MS,
                                        MAX_BUFFER_MS,
                                        BUFFER_FOR_PLAYBACK_MS,
                                        BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS)
                                .setTargetBufferBytes(TARGET_BUFFER_BYTES)
                                .setPrioritizeTimeOverSizeThresholds(false)
                                .build();

                // 接入带宽估算器：右下角常驻网速显示需要它提供播放中的实时吞吐
                bandwidthMeter = new com.google.android.exoplayer2.upstream.DefaultBandwidthMeter
                        .Builder(this).build();
                exoPlayer = new ExoPlayer.Builder(this)
                        .setMediaSourceFactory(mediaSourceFactory)
                        .setLoadControl(loadControl)
                        .setBandwidthMeter(bandwidthMeter)
                        .build();
                playerView.setPlayer(exoPlayer);
                exoPlayer.addListener(new Player.Listener() {
                    @Override
                    public void onPlaybackStateChanged(int state) {
                        showLoading(state == Player.STATE_BUFFERING);
                        if (state == Player.STATE_READY) {
                            // 成功起播，取消超时换源定时器
                            cancelPlayTimeout();
                            // 起播后开始轮询右下角实时网速
                            startCornerSpeedTicker();
                        }
                    }

                    @Override
                    public void onPlayerError(@NonNull PlaybackException error) {
                        Timber.w("播放出错: %s", error.getMessage());
                        cancelPlayTimeout();
                        // 异步换源，避免同步递归调用 setMediaItem 引发 IllegalStateException
                        postSourceFailure(false);
                    }
                });
            } catch (Exception e) {
                Timber.e(e, "ExoPlayer 创建失败");
                return;
            }
        }

        try {
            cancelPlayTimeout();
            // 切换前先停止上一次播放，确保状态干净
            try { exoPlayer.stop(); } catch (Exception ignore) {}
            try { exoPlayer.clearMediaItems(); } catch (Exception ignore) {}

            Uri uri = Uri.parse(url);
            // 直播流设置目标偏移：播放点离直播边缘更远，可容忍更大的网络波动而不卡顿
            MediaItem item = new MediaItem.Builder()
                    .setUri(uri)
                    .setLiveConfiguration(
                            new MediaItem.LiveConfiguration.Builder()
                                    .setTargetOffsetMs(LIVE_TARGET_OFFSET_MS)
                                    .build())
                    .build();
            exoPlayer.setMediaItem(item);
            exoPlayer.prepare();
            exoPlayer.setPlayWhenReady(true);
            startPlayTimeout();
            // 切源后立刻刷新右下角网速（显示新线路）
            updateCornerSpeed();
        } catch (Exception e) {
            Timber.e(e, "播放器初始化失败: %s", url);
            cancelPlayTimeout();
            postSourceFailure(false);
        }
    }

    /**
     * 把源失败处理异步 post 到主线程，避免任何同步递归调用带来的
     * ExoPlayer 状态混乱和栈溢出；相邻的多次失败也会因排队而串行执行。
     */
    private void postSourceFailure(final boolean byTimeout) {
        if (!isAlive() || handler == null) return;
        handler.post(() -> {
            if (!isAlive()) return;
            handleSourceFailure(byTimeout);
        });
    }

    // ============ 超时换源 ============

    /** 启动播放超时定时器：在设定时间内未成功起播则自动换源 */
    private void startPlayTimeout() {
        int timeoutSec = Hawk.get(HawkConfig.LIVE_PLAY_TIMEOUT, HawkConfig.DEFAULT_LIVE_PLAY_TIMEOUT);
        if (timeoutSec <= 0) return; // 0 表示关闭超时换源
        playTimeoutRunnable = () -> {
            Timber.w("播放超时(%ds)，自动换源", timeoutSec);
            // 同样走异步换源通道，保持所有失败路径一致
            postSourceFailure(true);
        };
        handler.postDelayed(playTimeoutRunnable, timeoutSec * 1000L);
    }

    /** 取消播放超时定时器 */
    private void cancelPlayTimeout() {
        if (playTimeoutRunnable != null) {
            handler.removeCallbacks(playTimeoutRunnable);
            playTimeoutRunnable = null;
        }
    }

    /**
     * 处理源失败（播放错误或超时）：自动切换到下一个源；
     * 当该频道所有源都已尝试过仍不可用时，弹窗提醒用户手动测速检测线路。
     *
     * @param byTimeout true=超时触发，false=播放错误触发
     */
    private void handleSourceFailure(boolean byTimeout) {
        LiveChannel channel = channelManager.getCurrentChannel();
        if (channel == null) return;

        int total = channel.getSourceCount();
        if (total <= 0) {
            showAllSourcesTimeoutOnce(channel);
            return;
        }

        sourceTryCount++;

        // 还有未尝试的源 且 尝试次数未超过源总数：跳到下一个"可播放"的源
        if (sourceTryCount < total && advanceToNextPlayableSource(channel)) {
            ToastUtil.show(this, (byTimeout ? "源连接超时，切换到源 " : "源不可用，切换到源 ")
                            + (channel.getSourceIndex() + 1) + "/" + total);
            initPlayer(channel.getCurrentSourceUrl());
            return;
        }

        // 所有源均已尝试仍不可用：提醒手动测速检测线路（每个频道只提醒一次）
        showAllSourcesTimeoutOnce(channel);
    }

    /**
     * 循环切到下一个"URL 合法"的源。跳过 null / 空 / 不认识协议的条目，
     * 保证 initPlayer 收到的都是可解析的 URL；
     * 若一圈下来都没有合法源则返回 false。
     */
    private boolean advanceToNextPlayableSource(LiveChannel channel) {
        if (channel == null || channel.getSourceCount() <= 0) return false;
        int total = channel.getSourceCount();
        for (int i = 0; i < total; i++) {
            if (!channel.switchToNextSource()) {
                // 已到末尾，回到 0 号继续检查（有可能上一次是从中间开始播的）
                channel.setSourceIndex(0);
            }
            String u = channel.getCurrentSourceUrl();
            if (isPlayableUrl(u)) return true;
        }
        return false;
    }

    /** 弹窗只弹一次，且允许当前频道有 0 源的场景友好提示 */
    private void showAllSourcesTimeoutOnce(LiveChannel channel) {
        if (!allSourcesTimeoutPrompted) {
            allSourcesTimeoutPrompted = true;
            showAllSourcesTimeoutDialog(channel);
        }
    }

    /** 频道所有源均超时/不可用时，弹窗引导手动测速检测线路 */
    private void showAllSourcesTimeoutDialog(LiveChannel channel) {
        // 用 isAlive() 而非仅 isFinishing()：本弹窗由播放超时的 Handler 延迟回调触发，
        // Activity 已 destroyed 时 show() 会抛 BadTokenException
        if (!isAlive()) return;
        String name = channel != null ? channel.getChannelName() : "当前频道";
        try {
            new AlertDialog.Builder(this)
                    .setTitle("线路均无法播放")
                    .setMessage("频道「" + name + "」的全部 "
                            + (channel != null ? channel.getSourceCount() : 0)
                            + " 条线路均连接超时或不可用。\n是否立即测速检测线路可用性？")
                    .setPositiveButton("立即测速", (dialog, which) -> startBackgroundSpeedTest())
                    .setNegativeButton("重试本频道", (dialog, which) -> {
                        if (channel != null) {
                            channel.setSourceIndex(0);
                            // 直接从 0 号源试起，playChannel 内会自动跳过无效源
                            playChannel(channel);
                        }
                    })
                    .setNeutralButton("取消", null)
                    .show();
        } catch (Throwable t) {
            Timber.w(t, "显示线路超时弹窗失败");
        }
    }

    private void applyScaleMode() {
        if (playerView == null) return;
        int scale = Hawk.get(HawkConfig.LIVE_PLAYER_SCALE, 0);
        switch (scale) {
            case 1: // 填充
                playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FILL);
                break;
            case 4: // 原始
                playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH);
                break;
            default: // 自适应 / 16:9 / 4:3 均使用 fit
                playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
                break;
        }
    }

    private void releasePlayer() {
        if (exoPlayer != null) {
            try {
                exoPlayer.stop();
            } catch (Exception ignore) {}
            try {
                exoPlayer.release();
            } catch (Exception e) {
                Timber.w(e, "ExoPlayer release 异常");
            }
            exoPlayer = null;
        }
    }

    // ============ 遥控器按键处理 ============

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        try {
            return handleKeyDown(keyCode, event);
        } catch (Exception e) {
            Timber.w(e, "按键处理异常 keyCode=%d", keyCode);
            return super.onKeyDown(keyCode, event);
        }
    }

    private boolean handleKeyDown(int keyCode, KeyEvent event) {
        // 频道列表显示时，方向键交给列表处理
        if (isChannelListVisible) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                hideChannelList();
                return true;
            }
            // 左右键在“分组目录”与“频道列表”之间做定向切换：
            // 向左：从频道列表回到该频道所属的分组（而非按屏幕位置就近选中别的分组）
            // 向右：从分组进入该分组自己的频道列表
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && isFocusIn(channelListView)) {
                focusGroupItem(browsingGroupIndex);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && isFocusIn(groupListView)) {
                focusChannelItem(browsingGroupIndex == channelManager.getCurrentGroupIndex()
                        ? Math.max(0, channelManager.getCurrentChannelIndex()) : 0);
                return true;
            }
            return super.onKeyDown(keyCode, event);
        }

        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                switchToPrevChannel();
                return true;

            case KeyEvent.KEYCODE_DPAD_DOWN:
                switchToNextChannel();
                return true;

            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                // 长按左/右：打开线路管理；短按：切换上/下一个源（在 onKeyUp 处理短按）
                if (event != null) {
                    event.startTracking();
                }
                return true;

            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                // 长按 OK 显示节目单，短按打开频道列表（在 onKeyUp 处理短按）
                if (event != null) {
                    event.startTracking();
                }
                return true;

            case KeyEvent.KEYCODE_GUIDE:
            case KeyEvent.KEYCODE_PROG_GREEN:
                // 部分遥控器的节目单 / 绿键
                showEpgDialog();
                return true;

            case KeyEvent.KEYCODE_MENU:
                openSettings();
                return true;

            case KeyEvent.KEYCODE_BACK:
                showExitDialog();
                return true;

            case KeyEvent.KEYCODE_INFO:
                showCurrentChannelInfo();
                return true;

            case KeyEvent.KEYCODE_0:
            case KeyEvent.KEYCODE_1:
            case KeyEvent.KEYCODE_2:
            case KeyEvent.KEYCODE_3:
            case KeyEvent.KEYCODE_4:
            case KeyEvent.KEYCODE_5:
            case KeyEvent.KEYCODE_6:
            case KeyEvent.KEYCODE_7:
            case KeyEvent.KEYCODE_8:
            case KeyEvent.KEYCODE_9:
                handleNumInput(keyCode - KeyEvent.KEYCODE_0);
                return true;

            default:
                break;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyLongPress(int keyCode, KeyEvent event) {
        if (isChannelListVisible) {
            return super.onKeyLongPress(keyCode, event);
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            // 长按 OK：显示节目单
            okLongPressed = true;
            showEpgDialog();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            // 长按左/右：打开当前频道线路管理（列表未显示，无需还原光标）
            sourceKeyLongPressed = true;
            showSourceManageDialog(channelManager.getCurrentChannel(), -1);
            return true;
        }
        return super.onKeyLongPress(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (isChannelListVisible) {
            return super.onKeyUp(keyCode, event);
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (okLongPressed) {
                okLongPressed = false; // 长按已处理
                return true;
            }
            // 短按 OK：打开频道列表
            toggleChannelList();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            if (sourceKeyLongPressed) {
                sourceKeyLongPressed = false;
                return true;
            }
            switchToPrevSource();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (sourceKeyLongPressed) {
                sourceKeyLongPressed = false;
                return true;
            }
            switchToNextSource();
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    // ============ 频道切换 ============

    private void switchToNextChannel() {
        LiveChannel channel = channelManager.nextChannel();
        if (channel != null) playChannel(channel);
    }

    private void switchToPrevChannel() {
        LiveChannel channel = channelManager.prevChannel();
        if (channel != null) playChannel(channel);
    }

    private void switchToNextSource() {
        LiveChannel channel = channelManager.getCurrentChannel();
        if (channel != null && channel.switchToNextSource()) {
            playChannel(channel);
            ToastUtil.show(this, "切换到源 " + (channel.getSourceIndex() + 1)
                    + "/" + channel.getSourceCount());
        }
    }

    private void switchToPrevSource() {
        LiveChannel channel = channelManager.getCurrentChannel();
        if (channel != null && channel.switchToPrevSource()) {
            playChannel(channel);
            ToastUtil.show(this, "切换到源 " + (channel.getSourceIndex() + 1)
                    + "/" + channel.getSourceCount());
        }
    }

    /** 数字键快速换台（按全局序号） */
    private void handleNumInput(int num) {
        // 限制最长 5 位，避免 Integer.parseInt 溢出与 StringBuilder 无限累积
        if (numInputBuilder.length() >= 5) {
            numInputBuilder.setLength(0);
        }
        numInputBuilder.append(num);
        if (tvInputNum != null) {
            tvInputNum.setVisibility(View.VISIBLE);
            tvInputNum.setText(numInputBuilder.toString());
        }

        if (numInputRunnable != null) {
            handler.removeCallbacks(numInputRunnable);
        }

        numInputRunnable = () -> {
            try {
                int channelNum = Integer.parseInt(numInputBuilder.toString());
                switchToChannelByNum(channelNum);
            } catch (Throwable t) {
                // 不能只捕 NumberFormatException：switchToChannelByNum 内部涉及
                // 列表遍历与播放器操作，任何异常从 Handler 回调逃逸都会直接闪退
                Timber.w(t, "数字换台失败");
            }
            numInputBuilder.setLength(0);
            if (tvInputNum != null) tvInputNum.setVisibility(View.GONE);
        };

        handler.postDelayed(numInputRunnable, NUM_INPUT_DELAY);
    }

    private void switchToChannelByNum(int num) {
        // 只取一次引用并做快照式遍历：getChannelGroups() 返回的是 ChannelManager 内部
        // 字段，后台线程的 rebuildDisplayGroups() 会整体替换该列表。
        // 若在循环条件与取值处分别调用两次 getter，可能一次读到旧列表、一次读到新的更短列表，
        // 从而抛 IndexOutOfBoundsException。
        List<ChannelGroup> groups = channelManager.getChannelGroups();
        if (groups == null || groups.isEmpty()) return;

        int index = 0;
        for (int g = 0; g < groups.size(); g++) {
            ChannelGroup group = groups.get(g);
            if (group == null) continue;
            // 用抗并发快照遍历：后台线程可能正在移除该分组内的频道，
            // 若沿用「循环外读一次 size」的写法，会在 selectChannel(g, c) 处越界
            List<LiveChannel> channels = group.snapshotChannels();
            for (int c = 0; c < channels.size(); c++) {
                index++;
                if (index == num) {
                    LiveChannel channel = channelManager.selectChannel(g, c);
                    if (channel != null) playChannel(channel);
                    return;
                }
            }
        }
        ToastUtil.show(this, "未找到频道 " + num);
    }

    // ============ UI 显示 ============

    // ============ 右下角常驻网速 ============

    /**
     * 刷新右下角"当前线路"网速显示。
     *
     * <p>取值优先级：<b>播放中的实时吞吐</b>（带宽估算器）→ 该线路的测速缓存 → 频道最优速度。
     * 播放中每 2 秒轮询一次，让实时吞吐能持续更新。
     */
    private void updateCornerSpeed() {
        if (tvCornerSpeed == null || !isAlive()) return;
        try {
            LiveChannel ch = channelManager != null ? channelManager.getCurrentChannel() : null;
            boolean showSpeed = Hawk.get(HawkConfig.LIVE_SHOW_SPEED_INFO, true);
            if (!showSpeed || ch == null) {
                tvCornerSpeed.setVisibility(View.GONE);
                return;
            }

            Double live = getCurrentLiveSpeedKbps();
            String speedText;
            if (live != null) {
                // 实时吞吐加 ↓ 前缀，与测速值区分
                speedText = "↓ " + formatSpeedKbps(live);
            } else {
                Double cached = getSourceSpeedKbps(ch);
                if (cached == null) {
                    // 尚未测速：只显示线路序号，不给误导性数字
                    tvCornerSpeed.setText("线路 " + (ch.getSourceIndex() + 1)
                            + "/" + Math.max(1, ch.getSourceCount()) + " · 未测速");
                    tvCornerSpeed.setTextColor(getResources().getColor(R.color.text_tertiary));
                    tvCornerSpeed.setVisibility(View.VISIBLE);
                    return;
                }
                speedText = formatSpeedKbps(cached);
            }

            tvCornerSpeed.setText("线路 " + (ch.getSourceIndex() + 1) + "/"
                    + Math.max(1, ch.getSourceCount()) + " · " + speedText);
            tvCornerSpeed.setTextColor(getResources().getColor(R.color.accent_green));
            tvCornerSpeed.setVisibility(View.VISIBLE);
        } catch (Throwable t) {
            // 网速显示属装饰性 UI，任何异常都不得影响播放
            Timber.w(t, "更新右下角网速失败");
        }
    }

    /** 播放中的实时吞吐（KB/s）；无播放或估算不可用时返回 null */
    private Double getCurrentLiveSpeedKbps() {
        if (bandwidthMeter == null || exoPlayer == null) return null;
        if (!exoPlayer.isPlaying()) return null;
        try {
            long bitsPerSec = bandwidthMeter.getBitrateEstimate();
            if (bitsPerSec <= 0) return null;
            return bitsPerSec / 8.0 / 1024.0; // bits/s → KB/s
        } catch (Throwable ignore) {
            return null;
        }
    }

    /** 当前线路的已知速度：优先该线路自身的测速缓存，其次频道最优速度 */
    private Double getSourceSpeedKbps(LiveChannel ch) {
        try {
            String url = ch.getCurrentSourceUrl();
            if (url != null && speedTestEngine != null) {
                SpeedTestResult r = speedTestEngine.getCachedResult(url);
                if (r != null && r.isAvailable() && r.getSpeed() > 0) {
                    return r.getSpeed();
                }
            }
            java.util.List<Double> speeds = ch.getSpeeds();
            int idx = ch.getSourceIndex();
            if (speeds != null && idx >= 0 && idx < speeds.size()) {
                Double v = speeds.get(idx);
                if (v != null && v > 0) return v;
            }
            if (ch.getBestSpeed() > 0) return ch.getBestSpeed();
        } catch (Throwable ignore) {
            // 忽略，按未测速处理
        }
        return null;
    }

    /** KB/s → 人类可读文本 */
    private String formatSpeedKbps(double kbps) {
        if (kbps >= 1024) {
            return String.format("%.2f MB/s", kbps / 1024.0);
        }
        return String.format("%.0f KB/s", kbps);
    }

    /** 开始轮询实时吞吐（每 2 秒），也用于播放状态变化后立刻刷新 */
    private void startCornerSpeedTicker() {
        if (handler == null) return;
        handler.removeCallbacks(cornerSpeedTask);
        updateCornerSpeed();
        handler.postDelayed(cornerSpeedTask, 2000);
    }

    private void stopCornerSpeedTicker() {
        if (handler == null) return;
        handler.removeCallbacks(cornerSpeedTask);
    }

    private void showChannelInfo(LiveChannel channel) {
        if (channelInfoPanel == null || channel == null || !isAlive()) return;

        // 信息栏铺满底部且同样位于右下区域，显示期间先让位，避免与右下角网速重叠
        if (tvCornerSpeed != null) tvCornerSpeed.setVisibility(View.GONE);

        if (tvChannelName != null) tvChannelName.setText(channel.getChannelName());
        if (tvChannelNum != null) tvChannelNum.setText(channel.getGroupName());
        if (tvSourceInfo != null) {
            tvSourceInfo.setText("源 " + (channel.getSourceIndex() + 1) + "/" + channel.getSourceCount());
        }
        if (ivInfoFavorite != null) {
            // 频道级收藏已移除，隐藏该星标
            ivInfoFavorite.setVisibility(View.GONE);
        }
        if (ivInfoLogo != null) {
            try {
                if (channel.getLogoUrl() != null && !channel.getLogoUrl().isEmpty() && isAlive()) {
                    // 限制解码尺寸并用 RGB_565：远程台标可能是大尺寸 PNG，
                    // 按原尺寸解码会白白占用数 MB 位图内存
                    Glide.with(this).load(channel.getLogoUrl())
                            .override(INFO_LOGO_SIZE_PX, INFO_LOGO_SIZE_PX)
                            .format(com.bumptech.glide.load.DecodeFormat.PREFER_RGB_565)
                            .placeholder(R.drawable.ic_launcher)
                            .error(R.drawable.ic_launcher)
                            .into(ivInfoLogo);
                } else {
                    ivInfoLogo.setImageResource(R.drawable.ic_launcher);
                }
            } catch (Exception e) {
                Timber.w(e, "加载台标失败");
            }
        }
        if (tvSpeedInfo != null) {
            boolean showSpeed = Hawk.get(HawkConfig.LIVE_SHOW_SPEED_INFO, true);
            if (showSpeed && channel.getBestSpeed() > 0) {
                tvSpeedInfo.setVisibility(View.VISIBLE);
                tvSpeedInfo.setText(String.format("%.0f KB/s", channel.getBestSpeed()));
            } else {
                tvSpeedInfo.setVisibility(View.GONE);
            }
        }
        if (tvEpgInfo != null) {
            String epg = channel.getCurrentEpgInfo();
            tvEpgInfo.setVisibility(epg != null && !epg.isEmpty() ? View.VISIBLE : View.GONE);
            if (epg != null) tvEpgInfo.setText(epg);
        }

        boolean showInfo = Hawk.get(HawkConfig.LIVE_SHOW_CHANNEL_INFO, true);
        if (!showInfo) {
            channelInfoPanel.setVisibility(View.GONE);
            return;
        }

        if (channelInfoPanel.getVisibility() != View.VISIBLE) {
            channelInfoPanel.setVisibility(View.VISIBLE);
            channelInfoPanel.startAnimation(android.view.animation.AnimationUtils
                    .loadAnimation(this, R.anim.info_slide_up));
        }

        if (hideChannelInfoRunnable != null) {
            handler.removeCallbacks(hideChannelInfoRunnable);
        }
        int duration = Hawk.get(HawkConfig.LIVE_CHANNEL_INFO_DURATION, 5) * 1000;
        hideChannelInfoRunnable = this::hideChannelInfoAnimated;
        handler.postDelayed(hideChannelInfoRunnable, duration);
    }

    private void hideChannelInfoAnimated() {
        if (channelInfoPanel == null || channelInfoPanel.getVisibility() != View.VISIBLE) return;
        try {
            android.view.animation.Animation out = android.view.animation.AnimationUtils
                    .loadAnimation(this, R.anim.info_slide_down);
            out.setAnimationListener(new android.view.animation.Animation.AnimationListener() {
                @Override public void onAnimationStart(android.view.animation.Animation a) {}
                @Override public void onAnimationRepeat(android.view.animation.Animation a) {}
                @Override public void onAnimationEnd(android.view.animation.Animation a) {
                    try {
                        if (channelInfoPanel != null) channelInfoPanel.setVisibility(View.GONE);
                    } catch (Exception ignore) {}
                    // 信息栏收起后，右下角网速恢复常驻显示
                    updateCornerSpeed();
                }
            });
            channelInfoPanel.startAnimation(out);
        } catch (Exception e) {
            // 动画加载失败直接隐藏
            channelInfoPanel.setVisibility(View.GONE);
            updateCornerSpeed();
        }
    }

    private void showCurrentChannelInfo() {
        LiveChannel channel = channelManager.getCurrentChannel();
        if (channel != null) showChannelInfo(channel);
    }

    private void toggleChannelList() {
        if (isChannelListVisible) {
            hideChannelList();
        } else {
            showChannelList();
        }
    }

    /** 判断当前焦点是否位于指定列表内部 */
    private boolean isFocusIn(RecyclerView list) {
        if (list == null) return false;
        View focused = getCurrentFocus();
        while (focused != null) {
            if (focused == list) return true;
            android.view.ViewParent p = focused.getParent();
            focused = (p instanceof View) ? (View) p : null;
        }
        return false;
    }

    /**
     * 把焦点定向移动到分组目录中的指定分组（频道所属的上级目录），
     * 而不是按屏幕位置就近选中其它分组。
     */
    private void focusGroupItem(int position) {
        if (groupListView == null) return;
        try {
            int count = groupAdapter != null ? groupAdapter.getItemCount() : 0;
            if (count <= 0) {
                groupListView.requestFocus();
                return;
            }
            final int pos = Math.max(0, Math.min(position, count - 1));
            RecyclerView.ViewHolder vh = groupListView.findViewHolderForAdapterPosition(pos);
            if (vh != null) {
                vh.itemView.requestFocus();
            } else {
                // 目标未在可见区域时，先滚动到该位置再聚焦
                groupListView.scrollToPosition(pos);
                groupListView.post(() -> {
                    RecyclerView.ViewHolder v = groupListView.findViewHolderForAdapterPosition(pos);
                    if (v != null) v.itemView.requestFocus();
                    else groupListView.requestFocus();
                });
            }
        } catch (Exception e) {
            Timber.w(e, "聚焦分组失败");
            groupListView.requestFocus();
        }
    }

    /** 把焦点定向移动到频道列表中的指定频道（当前分组的下级列表） */
    private void focusChannelItem(int position) {
        if (channelListView == null) return;
        try {
            int count = channelAdapter != null ? channelAdapter.getItemCount() : 0;
            if (count <= 0) {
                channelListView.requestFocus();
                return;
            }
            final int pos = Math.max(0, Math.min(position, count - 1));
            RecyclerView.ViewHolder vh = channelListView.findViewHolderForAdapterPosition(pos);
            if (vh != null) {
                vh.itemView.requestFocus();
            } else {
                channelListView.scrollToPosition(pos);
                channelListView.post(() -> {
                    RecyclerView.ViewHolder v = channelListView.findViewHolderForAdapterPosition(pos);
                    if (v != null) v.itemView.requestFocus();
                    else channelListView.requestFocus();
                });
            }
        } catch (Exception e) {
            Timber.w(e, "聚焦频道失败");
            channelListView.requestFocus();
        }
    }

    private void showChannelList() {
        if (channelListPanel == null || !dataLoaded) return;
        refreshGroupAdapter();
        channelListPanel.setVisibility(View.VISIBLE);
        channelListPanel.startAnimation(android.view.animation.AnimationUtils
                .loadAnimation(this, R.anim.panel_slide_in_left));
        // 触发列表逐项进入动画
        if (channelListView.getLayoutAnimation() != null) {
            channelListView.scheduleLayoutAnimation();
        }
        isChannelListVisible = true;
        // 聚焦到频道列表，方便遥控器操作
        channelListView.post(() -> {
            RecyclerView.ViewHolder vh = channelListView.findViewHolderForAdapterPosition(
                    Math.max(0, channelManager.getCurrentChannelIndex()));
            if (vh != null) {
                vh.itemView.requestFocus();
            } else {
                channelListView.requestFocus();
            }
        });
    }

    private void hideChannelList() {
        if (channelListPanel == null) return;
        try {
            android.view.animation.Animation out = android.view.animation.AnimationUtils
                    .loadAnimation(this, R.anim.panel_slide_out_left);
            out.setAnimationListener(new android.view.animation.Animation.AnimationListener() {
                @Override public void onAnimationStart(android.view.animation.Animation a) {}
                @Override public void onAnimationRepeat(android.view.animation.Animation a) {}
                @Override public void onAnimationEnd(android.view.animation.Animation a) {
                    try {
                        if (channelListPanel != null) channelListPanel.setVisibility(View.GONE);
                    } catch (Exception ignore) {}
                }
            });
            channelListPanel.startAnimation(out);
        } catch (Exception e) {
            channelListPanel.setVisibility(View.GONE);
        }
        isChannelListVisible = false;
    }

    private void showLoading(boolean show) {
        if (loadingProgress != null) {
            loadingProgress.setVisibility(show ? View.VISIBLE : View.GONE);
        }
    }

    private void showError(String message) {
        if (isFinishing() || isDestroyed()) return;
        ToastUtil.showLong(this, message);
    }

    /** Activity 是否仍处于可安全操作 UI 的存活状态 */
    private boolean isAlive() {
        return !isFinishing() && !isDestroyed();
    }

    private void showExitDialog() {
        if (!isAlive()) return;
        // 仅保留"确定"一个选项；不需要退出时按返回键即可关闭本弹窗
        new AlertDialog.Builder(this)
                .setTitle("退出直播")
                .setMessage("确定要退出吗？")
                .setPositiveButton("确定", (dialog, which) -> finish())
                .show();
    }

    private void openSettings() {
        Intent intent = new Intent(this, LiveSettingsActivity.class);
        startActivity(intent);
    }

    // ============ 线路（源）管理：置顶 / 删除拉黑 ============

    /**
     * 弹出指定频道的线路列表，可对单条线路置顶或删除并加入黑名单（仅该频道内生效）。
     *
     * @param keepChannelPos 弹窗前光标所在频道的下标（见 {@link #refreshGroupAdapterKeepingFocus(int)}）；
     *                       &lt;0 表示弹窗前光标不在频道列表中
     */
    private void showSourceManageDialog(final LiveChannel channel, final int keepChannelPos) {
        if (!isAlive()) return;
        if (channel == null) {
            ToastUtil.show(this, "当前无频道");
            return;
        }
        final List<String> urls = channel.getSourceUrls();
        if (urls == null || urls.isEmpty()) {
            ToastUtil.show(this, "该频道无可用线路");
            return;
        }

        int playingIdx = channel.getSourceIndex();
        final int count = urls.size();
        CharSequence[] items = new CharSequence[count];
        for (int i = 0; i < count; i++) {
            boolean isPlaying = i == playingIdx;
            items[i] = (isPlaying ? "▶ " : "   ") + "线路 " + (i + 1) + "  " + shortUrl(urls.get(i));
        }

        try {
            new AlertDialog.Builder(this)
                    .setTitle(channel.getChannelName() + " · 线路管理（共 " + count + " 条）")
                    .setItems(items, (dialog, which) -> {
                        if (which >= 0 && which < channel.getSourceCount()) {
                            showSingleSourceActionDialog(channel, which, keepChannelPos);
                        }
                    })
                    .setNegativeButton("关闭", null)
                    .show();
        } catch (Exception e) {
            Timber.w(e, "显示线路管理对话框失败");
        }
    }

    /** 针对某条线路的操作菜单：置顶 / 删除并拉黑 / 切换播放 */
    private void showSingleSourceActionDialog(final LiveChannel channel, final int sourceIndex,
                                              final int keepChannelPos) {
        if (!isAlive() || channel == null) return;
        List<String> urls = channel.getSourceUrls();
        if (urls == null || sourceIndex < 0 || sourceIndex >= urls.size()) return;
        final String url = urls.get(sourceIndex);

        String[] actions = {"播放此线路", "置顶此线路", "删除并加入黑名单"};
        try {
            new AlertDialog.Builder(this)
                    .setTitle("线路 " + (sourceIndex + 1))
                    .setItems(actions, (dialog, which) -> {
                        try {
                            switch (which) {
                                case 0: // 播放此线路
                                    channel.setSourceIndex(sourceIndex);
                                    playChannel(channel);
                                    break;
                                case 1: // 置顶
                                    if (channelManager.pinSource(channel, url)) {
                                        ToastUtil.show(this, "已置顶该线路");
                                        // 置顶后从首条线路开始播放
                                        playChannel(channel);
                                    } else {
                                        ToastUtil.show(this, "该线路已在首位");
                                    }
                                    break;
                                case 2: // 删除并拉黑
                                    boolean playingRemoved = sourceIndex == channel.getSourceIndex();
                                    channelManager.blacklistSource(channel, url);
                                    ToastUtil.show(this, "已删除该线路并加入黑名单");
                                    if (channel.getSourceCount() == 0) {
                                        ToastUtil.show(this, "该频道已无可用线路");
                                    } else if (playingRemoved) {
                                        // 正在播放的线路被删除，切到当前索引指向的线路
                                        playChannel(channel);
                                    }
                                    break;
                                default:
                                    break;
                            }
                            // 刷新列表并保持光标停留在原来的频道上（不跳回播放分组）
                            refreshGroupAdapterKeepingFocus(keepChannelPos);
                        } catch (Throwable t) {
                            // 对话框点击回调中的异常会直接逃逸到 Looper 导致闪退
                            Timber.w(t, "线路操作失败");
                        }
                    })
                    .show();
        } catch (Throwable t) {
            Timber.w(t, "显示线路操作菜单失败");
        }
    }

    /** 截短 URL 用于展示 */
    private String shortUrl(String url) {
        if (url == null) return "";
        if (url.length() <= 42) return url;
        return url.substring(0, 20) + "..." + url.substring(url.length() - 18);
    }

    // ============ 测速相关 ============

    /**
     * 启动时的测速检查。
     *
     * @return 是否弹出了需要用户决策的测速对话框（用于决定 EPG 何时放行）
     */
    private boolean checkSpeedTestOnLaunch() {
        if (speedTestChecked) return false;
        speedTestChecked = true;
        // 尚无测速生成的本地列表：提醒用户测速以生成最佳线路本地列表
        if (!channelManager.hasLocalPlaylist()) {
            return showGenerateLocalListPrompt();
        }
        // 已有本地列表：按测速周期策略提醒
        if (speedTestEngine.shouldPromptOnLaunch()) {
            return showSpeedTestPrompt();
        }
        return false;
    }

    /**
     * 尚未生成本地列表时的提醒：引导用户对整体订阅源测速，
     * 生成并持久化最佳线路本地列表，下次打开即可默认使用。
     *
     * @return 是否实际弹出了对话框
     */
    private boolean showGenerateLocalListPrompt() {
        if (!isAlive()) return false;
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("尚未生成本地列表")
                .setMessage("当前没有测速生成的本地播放列表。\n"
                        + "是否立即对整体订阅源测速，找出最佳线路并生成本地列表？\n"
                        + "（测速在后台运行，不影响观看；结果会持久化，下次打开可直接使用）")
                .setPositiveButton("立即测速", (d, which) -> startBackgroundSpeedTest())
                .setNegativeButton("稍后再说", null)
                .create();
        // EPG 优先级最低：等用户完成测速决策（开始测速或关闭弹窗）后再加载节目单
        setOnDismissFlushEpg(dialog);
        dialog.show();
        return true;
    }

    /** @return 是否实际弹出了对话框 */
    private boolean showSpeedTestPrompt() {
        if (!isAlive()) return false;
        String lastTestDesc = speedTestEngine.getLastTestTimeDescription();
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("直播源测速提醒")
                .setMessage("上次测速: " + lastTestDesc
                        + "\n已超过设定的测速周期，是否立即测速？"
                        + "\n（测速将在后台运行，不影响观看）")
                .setPositiveButton("立即测速", (d, which) -> startBackgroundSpeedTest())
                .setNegativeButton("稍后再说", null)
                .setNeutralButton("不再提醒", (d, which) ->
                        Hawk.put(HawkConfig.SPEED_TEST_MODE, HawkConfig.SPEED_MODE_MANUAL))
                .create();
        setOnDismissFlushEpg(dialog);
        dialog.show();
        return true;
    }

    /**
     * 测速提醒弹窗关闭后，尝试放行被推迟的 EPG。
     * 延迟片刻再尝试，给后台测速留出启动时间，避免与服务启动竞态；
     * 若测速确已启动，flushDeferredEpg 会自行跳过，改由测速结束事件补拉。
     */
    private void setOnDismissFlushEpg(AlertDialog dialog) {
        if (dialog == null) return;
        dialog.setOnDismissListener(d -> {
            speedTestDecisionPending = false;
            if (handler != null) {
                handler.postDelayed(this::flushDeferredEpg, 1200);
            } else {
                flushDeferredEpg();
            }
        });
    }

    private void startBackgroundSpeedTest() {
        // 标记测速流程开始：EPG 加载让路，直到测速结束/失败/取消
        speedTestActive = true;
        speedTestActiveSince = android.os.SystemClock.elapsedRealtime();
        speedTestDecisionPending = false;
        if (speedTestBar != null) {
            speedTestBar.setVisibility(View.VISIBLE);
        }
        // 拉取源阶段先在右上角给出反馈，避免无进度显示
        if (speedTestProgress != null) {
            speedTestProgress.setMax(1);
            speedTestProgress.setProgress(0);
        }
        if (tvSpeedTestInfo != null) {
            tvSpeedTestInfo.setText("正在获取全量线路...");
        }
        // 立即（前台状态下）启动前台服务；联网拉取全量源（远程订阅+自定义，含模板过滤）
        // 与测速均在服务内部完成，避免耗时网络后再启动前台服务被系统拒绝
        BackgroundSpeedTestService.startSpeedTestWithReload(this);
    }

    // ============ EventBus 事件处理 ============

    /**
     * 测速准备阶段（联网拉取全量线路）进度。
     * 这一阶段耗时可达数十秒，需要文字反馈，否则用户会以为卡死。
     */
    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onSpeedTestPrepare(BackgroundSpeedTestService.SpeedTestPrepareEvent event) {
        if (!isAlive()) return;
        // 测速准备阶段开始（如从设置页触发）：EPG 继续让路
        if (!speedTestActive) {
            speedTestActiveSince = android.os.SystemClock.elapsedRealtime();
        }
        speedTestActive = true;
        if (speedTestBar != null) speedTestBar.setVisibility(View.VISIBLE);
        if (speedTestProgress != null) {
            if (event.total > 0) {
                speedTestProgress.setMax(event.total);
                speedTestProgress.setProgress(event.current);
            } else {
                // 总数未知：保持 0 进度，避免进度条看起来已完成
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
        if (!isAlive()) return;
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
        if (!isAlive()) return;
        // 测速结束（含取消）：解除对 EPG 的让路限制并补拉节目单
        speedTestActive = false;
        speedTestActiveSince = 0L;
        // 测速结果已写入缓存，立刻刷新右下角网速（此时能取到各线路的真实速度）
        updateCornerSpeed();
        if (handler != null) handler.removeCallbacks(epgFlushRetry);
        if (speedTestBar != null) speedTestBar.setVisibility(View.GONE);
        if (!event.cancelled) {
            int available = 0;
            int total = 0;
            if (event.results != null) {
                total = event.results.size();
                for (SpeedTestResult r : event.results) {
                    if (r != null && r.isAvailable()) available++;
                }
            }
            ToastUtil.show(this, String.format("测速完成: %d/%d 个源可用",
                    available, total));
            // 刷新列表以反映最新速度排序
            refreshGroupAdapter();
        }
        // 测速结束后，补拉此前让路给测速的节目单（EPG 优先级最低，最后加载）
        flushDeferredEpg();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onSpeedTestError(BackgroundSpeedTestService.SpeedTestErrorEvent event) {
        if (!isAlive()) return;
        // 测速失败：同样解除对 EPG 的让路限制并补拉节目单
        speedTestActive = false;
        speedTestActiveSince = 0L;
        if (handler != null) handler.removeCallbacks(epgFlushRetry);
        if (speedTestBar != null) speedTestBar.setVisibility(View.GONE);
        ToastUtil.show(this, "测速失败: " + event.errorMsg);
        flushDeferredEpg();
    }
}
