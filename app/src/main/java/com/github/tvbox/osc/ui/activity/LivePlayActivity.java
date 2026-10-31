package com.github.tvbox.osc.ui.activity;

import com.github.tvbox.osc.util.ToastUtil;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
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
import androidx.recyclerview.widget.SimpleItemAnimator;

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
import com.github.tvbox.osc.util.RunLog;
import com.github.tvbox.osc.util.StreamUrl;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory;
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout;
import com.google.android.exoplayer2.ui.StyledPlayerView;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;
import com.google.android.exoplayer2.util.MimeTypes;
import com.google.android.exoplayer2.video.VideoSize;
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
    /**
     * 右下角网速的轮询任务：播放中每 2 秒刷新一次实时吞吐。
     * 必须自我重新投递：原先是 this::updateCornerSpeed 的方法引用，只会执行一次，
     * 网速停在起播后 2 秒的那个值（或一直隐藏）。由 stopCornerSpeedTicker / onPause 终止循环。
     */
    private final Runnable cornerSpeedTask = new Runnable() {
        @Override
        public void run() {
            if (!isAlive() || handler == null) return;
            updateCornerSpeed();
            handler.removeCallbacks(this);
            // 不在前台或播放器已释放时不再续投：STATE_READY 可能在 onPause 之后才到达
            // （设置页盖住时换线/重连完成），否则会在后台每 2 秒空转读 Hawk、刷 View
            if (!resumed || exoPlayer == null) return;
            handler.postDelayed(this, 2000);
        }
    };

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
    /** 当前视频的显示尺寸（已按像素宽高比换算），供"原始"缩放模式使用 */
    private int videoWidth = 0;
    private int videoHeight = 0;

    // ============ 加载优先级控制（播放列表 > 测速 > EPG） ============
    /** 因测速（或测速决策）被推迟加载 EPG 的频道 */
    private LiveChannel deferredEpgChannel;
    /** 是否正处于"测速决策待定"窗口：用户尚未对启动测速提醒作出选择时，EPG 暂缓加载 */
    private boolean speedTestDecisionPending = false;
    /** 测速流程是否进行中（含准备/拉取列表阶段），用于降低 EPG 加载优先级 */
    private boolean speedTestActive = false;
    /** 本轮已测完 / 共需测速的频道数（逐频道完成事件更新，用于进度文字；0 表示尚未收到） */
    private int speedTestChannelsDone = 0;
    private int speedTestChannelsTotal = 0;
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

    /**
     * Activity 是否处于 started（可见）状态：onStart 置 true、onStop 置 false。
     * 异步加载频道 / 换源回调可能在 onStop 之后才到达，此时不能创建播放器，
     * 否则直播声音在设置页或桌面后台继续播放，并长期占用硬件解码器与缓冲内存。
     * 不用 Lifecycle.isAtLeast(STARTED)：ON_START 在 onStart() 返回后才分发，
     * 会挡住 onStart 自身的重新起播。
     */
    private boolean started = false;
    /** onResume 置 true、onPause 置 false：右下角网速轮询只在前台续投 */
    private boolean resumed = false;

    /** 起播后稳定播放这么久才视为"本轮换源成功"，重置换源计数 */
    private static final long STABLE_PLAY_RESET_MS = 15_000L;
    /**
     * 测速后持久化（重排、按上限裁剪、重建展示列表）完成后的主线程回调。
     * 由 ChannelManager 单例持有，onDestroy 时注销；只在主线程执行。
     */
    private final Runnable speedTestPersistedListener = new Runnable() {
        @Override
        public void run() {
            if (!isAlive() || channelManager == null) return;
            refreshChannelListKeepingBrowse();
            resyncPlayingChannel(false);
        }
    };

    /**
     * 稳定播放后重置换源计数与"全部失败"提醒状态。
     * 不在每次 READY 立即重置：READY 后马上又出错的线路会让换源无限循环、永不提醒；
     * 只有稳定播放一段时间后再断流，才按新一轮失败从头计数（单源频道也能重试一次）。
     */
    private final Runnable stablePlayResetRunnable = new Runnable() {
        @Override
        public void run() {
            sourceTryCount = 0;
            allSourcesTimeoutPrompted = false;
            reconnectSameSourceOnce = true;
            // 优先取播放器实际加载的 URL：测速后重排/裁剪可能让频道索引暂时不指向在播线路
            String playing = currentPlayUrl;
            if (playing == null) {
                LiveChannel ch = channelManager != null ? channelManager.getCurrentChannel() : null;
                playing = ch != null ? ch.getCurrentSourceUrl() : null;
            }
            lastStableSourceUrl = playing;
        }
    };

    /**
     * 稳定播放后的首次失败先原线路重连一次再换源：多为瞬时断流，原线路通常还能用；
     * 单源频道原先在播放中断后完全不会重试，直接弹"全部失败"。
     */
    private boolean reconnectSameSourceOnce = false;

    // ============ 断网恢复 ============
    /** 最近一次稳定播放的线路 URL：断网恢复后优先从这条线路重连，而不是停在被轮换走的线路上 */
    private String lastStableSourceUrl;
    /** 是否因断网导致播放失败、正在等待网络恢复后自动重连 */
    private boolean waitingForNetwork = false;
    /** "全部线路失败"弹窗，网络恢复自动重连时需要关闭它 */
    private AlertDialog allSourcesDialog;
    private android.net.ConnectivityManager.NetworkCallback networkCallback;
    /** 网络恢复后的重连任务：延迟执行以等待 DNS/路由就绪，并对网络抖动的多次回调去抖 */
    private final Runnable networkResumeRunnable = this::resumeAfterNetworkBack;
    private static final long NETWORK_RESUME_DELAY_MS = 2_000L;
    /**
     * 网络回调注册失败时的兜底轮询：否则断网后既不换源也不弹窗，只能永远黑屏。
     * 仅在 waitingForNetwork 且 Activity 可见时续投，onStop 中移除。
     */
    private static final long NETWORK_POLL_INTERVAL_MS = 8_000L;
    private final Runnable networkPollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isAlive() || !started || handler == null) return;
            if (!waitingForNetwork || networkCallback != null) return;
            if (isNetworkConnected()) {
                resumeAfterNetworkBack();
            } else {
                handler.removeCallbacks(this);
                handler.postDelayed(this, NETWORK_POLL_INTERVAL_MS);
            }
        }
    };

    /** 频道列表适配器当前展示所基于的分组列表引用（用于发现后台已整体替换列表） */
    private List<ChannelGroup> shownGroups;
    /** 播放器当前实际在播的线路 URL（存储形式，未去 $ 后缀）；线路管理据此判断"删的是不是正在播的线路" */
    private String currentPlayUrl;
    /** 当前播放项是否已按 HLS（显式 mime）加载；为 false 时容器无法识别会再按 HLS 重试一次 */
    private boolean currentItemAsHls;
    /** 本次起播是否仅因测速缓存的"是 HLS"标记才按 HLS 加载（地址本身看不出是 HLS） */
    private boolean currentItemHlsFromCache;
    /** 按缓存 HLS 标记加载失败后，这条地址改按普通流重试，不再采用缓存标记 */
    private String cacheHlsSuppressedUrl;
    /**
     * 起播代次：每次真正起播（setMediaSource）前递增。排队中的换源/超时回调带着发出时的代次，
     * 代次已变（期间已换台/换源）则作废，避免上一个频道的失败把刚切到的频道换走线路
     */
    private int playGen;
    /**
     * 正在打开频道列表并定位到播放频道。期间分组列表的焦点变化（面板显示时系统自动把焦点给分组栏、
     * RecyclerView 整表刷新后的焦点恢复会落到第 0 个分组）不得改动浏览分组，否则会定位到首个分组的首个频道
     */
    private boolean positioningList;
    /** 解除 positioningList 的延时任务（持有引用以便重新打开/关闭列表时取消上一次的） */
    private final Runnable clearPositioning = () -> positioningList = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_live_play);

        handler = new Handler(Looper.getMainLooper());
        channelManager = ChannelManager.getInstance(this);
        speedTestEngine = SpeedTestEngine.getInstance();
        // 已经打开了界面：撤掉开机自启的兜底通知
        try {
            android.app.NotificationManager nm = (android.app.NotificationManager)
                    getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(com.github.tvbox.osc.receiver.BootReceiver.BOOT_NOTIFICATION_ID);
        } catch (Exception ignore) {}
        epgService = EpgService.getInstance();

        initViews();
        initAdapters();
        EventBus.getDefault().register(this);
        // 测速完成事件早于测速后的线路裁剪/列表重建：重建完成后再同步一次列表与在播线路索引
        channelManager.setOnSpeedTestPersistedListener(speedTestPersistedListener);

        loadChannelData();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        // 从设置页返回时缩放模式可能已改变（原先只在 onCreate 应用一次）
        applyScaleMode();
        if (exoPlayer != null && exoPlayer.getPlaybackState() != Player.STATE_IDLE) {
            exoPlayer.setPlayWhenReady(true);
        }
        startCornerSpeedTicker();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        if (exoPlayer != null) {
            exoPlayer.setPlayWhenReady(false);
        }
        stopCornerSpeedTicker();
    }

    @Override
    protected void onStart() {
        super.onStart();
        // 必须在下面的重新起播之前置位：playChannel 会检查该标记
        started = true;
        // 若曾在 onStop 中释放过播放器（或后台期间到达的起播请求被推迟），回到前台时重新起播当前频道
        if (exoPlayer == null && playerReleasedOnStop) {
            playerReleasedOnStop = false;
            LiveChannel channel = channelManager.getCurrentChannel();
            if (channel != null && channel.getCurrentSourceUrl() != null) {
                playChannel(channel);
            }
        }
        // 放在重新起播之后注册：注册时系统会对当前可用网络立即回调一次 onAvailable，
        // 此时 playChannel 已重置失败状态，不会误触发重连
        registerNetworkCallback();
        // 注册失败（部分 ROM 抛异常）且仍在等网：没有回调可依赖，改用轮询兜底
        scheduleNetworkPollIfNeeded();
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
        // 先于 isFinishing 判断清除：之后到达的异步起播/换源回调一律不得创建播放器
        started = false;
        unregisterNetworkCallback();
        if (handler != null) {
            handler.removeCallbacks(networkResumeRunnable);
            handler.removeCallbacks(networkPollRunnable);
            handler.removeCallbacks(stablePlayResetRunnable);
        }
        stopCornerSpeedTicker();
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

    // ============ 断网恢复 ============

    /**
     * 监听网络恢复：断网期间所有线路都会失败，网络回来后若不处理，播放器会一直停在黑屏。
     * 用 registerNetworkCallback(NetworkRequest)（API 21 可用；registerDefaultNetworkCallback 需 API 24）。
     * 部分电视 ROM 会在注册时抛异常，整体 try/catch，失败只是失去自动重连能力。
     */
    private void registerNetworkCallback() {
        if (networkCallback != null) return;
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                    getApplicationContext().getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;
            android.net.ConnectivityManager.NetworkCallback cb =
                    new android.net.ConnectivityManager.NetworkCallback() {
                        @Override
                        public void onAvailable(@NonNull android.net.Network network) {
                            // 回调在 binder 线程，切回主线程处理；延迟去抖，等待网络真正可用
                            Handler h = handler;
                            if (h == null) return;
                            h.post(() -> {
                                if (!isAlive() || !started) return;
                                if (!waitingForNetwork && !allSourcesTimeoutPrompted) return;
                                h.removeCallbacks(networkResumeRunnable);
                                h.postDelayed(networkResumeRunnable, NETWORK_RESUME_DELAY_MS);
                            });
                        }
                    };
            android.net.NetworkRequest request = new android.net.NetworkRequest.Builder()
                    .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build();
            cm.registerNetworkCallback(request, cb);
            networkCallback = cb;
        } catch (Throwable t) {
            Timber.w(t, "注册网络状态监听失败");
            networkCallback = null;
        }
    }

    private void unregisterNetworkCallback() {
        android.net.ConnectivityManager.NetworkCallback cb = networkCallback;
        networkCallback = null;
        if (cb == null) return;
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                    getApplicationContext().getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
            if (cm != null) cm.unregisterNetworkCallback(cb);
        } catch (Throwable t) {
            Timber.w(t, "注销网络状态监听失败");
        }
    }

    /** 当前是否有已连接的网络；取不到状态时按"在线"处理，保持原有换源行为 */
    @SuppressWarnings("deprecation")
    private boolean isNetworkConnected() {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                    getApplicationContext().getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
            if (cm == null) return true;
            // getActiveNetworkInfo 在 API 29 起标记为过时，但 API 21~33 均可用，且是 21 上唯一简单可靠的判断方式
            android.net.NetworkInfo ni = cm.getActiveNetworkInfo();
            if (ni != null && ni.isConnected()) return true;
            // 个别盒子 ROM 的有线网络不会成为"默认网络"：再看一遍所有网络，
            // 避免误判离线而把自动换源整体关掉（getAllNetworks / getNetworkInfo(Network) 均为 API 21）
            android.net.Network[] all = cm.getAllNetworks();
            if (all != null) {
                for (android.net.Network n : all) {
                    android.net.NetworkInfo info = cm.getNetworkInfo(n);
                    if (info != null && info.isConnected()) return true;
                }
            }
            return false;
        } catch (Throwable t) {
            return true;
        }
    }

    /** 没有可用的网络回调时启动兜底轮询（已有回调则什么都不做） */
    private void scheduleNetworkPollIfNeeded() {
        if (handler == null || !started || !waitingForNetwork || networkCallback != null) return;
        handler.removeCallbacks(networkPollRunnable);
        handler.postDelayed(networkPollRunnable, NETWORK_POLL_INTERVAL_MS);
    }

    /** 网络恢复后重连当前频道：优先回到断网前稳定播放的那条线路 */
    private void resumeAfterNetworkBack() {
        if (!isAlive() || !started) return;
        if (!waitingForNetwork && !allSourcesTimeoutPrompted) return;
        if (!isNetworkConnected()) return; // 又断了，等下一次 onAvailable
        LiveChannel ch = channelManager != null ? channelManager.getCurrentChannel() : null;
        if (ch == null) return;
        Timber.i("网络已恢复，自动重连频道: %s", ch.getChannelName());
        waitingForNetwork = false;
        dismissAllSourcesDialog();
        List<String> urls = ch.getSourceUrls();
        int idx = (lastStableSourceUrl != null && urls != null) ? urls.indexOf(lastStableSourceUrl) : -1;
        if (idx >= 0) {
            ch.setSourceIndex(idx);
        }
        ToastUtil.show(this, "网络已恢复，正在重新连接");
        // playChannel 会重置换源计数与"全部失败"提醒状态
        playChannel(ch);
    }

    private void dismissAllSourcesDialog() {
        AlertDialog d = allSourcesDialog;
        allSourcesDialog = null;
        if (d == null) return;
        try {
            if (d.isShowing()) d.dismiss();
        } catch (Throwable ignore) {
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { EventBus.getDefault().unregister(this); } catch (Exception ignore) {}
        try {
            if (channelManager != null) {
                channelManager.clearOnSpeedTestPersistedListener(speedTestPersistedListener);
            }
        } catch (Exception ignore) {}
        try { handler.removeCallbacksAndMessages(null); } catch (Exception ignore) {}
        // 兜底：正常情况下 onStop 已注销
        unregisterNetworkCallback();
        dismissAllSourcesDialog();
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
            disableChangeAnimation(groupListView);
            groupListView.setLayoutAnimation(android.view.animation.AnimationUtils
                    .loadLayoutAnimation(this, R.anim.layout_anim_fall_down));
        }
        if (channelListView != null) {
            channelListView.setLayoutManager(new LinearLayoutManager(this));
            disableChangeAnimation(channelListView);
            channelListView.setLayoutAnimation(android.view.animation.AnimationUtils
                    .loadLayoutAnimation(this, R.anim.layout_anim_fall_down));
        }

        if (channelInfoPanel != null) channelInfoPanel.setVisibility(View.GONE);
        if (channelListPanel != null) channelListPanel.setVisibility(View.GONE);
        if (speedTestBar != null) speedTestBar.setVisibility(View.GONE);
        if (tvInputNum != null) tvInputNum.setVisibility(View.GONE);

        applyScaleMode();
    }

    /**
     * 关闭条目刷新的交叉淡入动画：notifyItemChanged（高亮切换）时默认动画会用新 ViewHolder
     * 替换旧视图，正在聚焦的条目因此丢失焦点，遥控器操作时表现为焦点乱跳。
     */
    private void disableChangeAnimation(RecyclerView rv) {
        if (rv != null && rv.getItemAnimator() instanceof SimpleItemAnimator) {
            ((SimpleItemAnimator) rv.getItemAnimator()).setSupportsChangeAnimations(false);
        }
    }

    private void initAdapters() {
        groupAdapter = new GroupListAdapter();
        channelAdapter = new ChannelListAdapter();

        groupAdapter.setOnGroupActionListener((position, group) -> {
            if (positioningList) {
                // 打开列表定位期间的自动焦点不是用户操作：保持分组高亮在正在定位的分组上
                if (position != browsingGroupIndex) groupAdapter.setSelectedIndex(browsingGroupIndex);
                return;
            }
            // 焦点回到当前浏览的分组（如从频道列表按左键返回）时不重复刷新，
            // 否则频道列表整表重建、滚动位置被重置
            if (position == browsingGroupIndex && channelAdapter.getItemCount() > 0) return;
            browsingGroupIndex = position;
            // 用抗并发快照：后台线程可能正在结构性修改该分组的频道列表，
            // 直接 new ArrayList<>(getChannels()) 会抛 ConcurrentModificationException，
            // 而本回调由焦点变化触发，不在任何 try/catch 保护内
            channelAdapter.setChannels(group != null ? group.snapshotChannels() : null,
                    channelNumberOffset(position));
            // 若浏览的是当前播放分组，高亮正在播放的频道
            channelAdapter.setPlayingIndex(playingIndexInGroup(position));
        });

        channelAdapter.setOnChannelActionListener(new ChannelListAdapter.OnChannelActionListener() {
            @Override
            public void onChannelClick(int position, LiveChannel channel) {
                // 按用户点中的频道对象定位，而不是按 (浏览分组, 下标)：
                // 测速前重载 / 后台刷新可能已整体替换 ChannelManager 的列表，而界面仍显示旧列表，
                // 按旧下标去新列表里取会播放另一个频道（并被记成"上次播放"）。
                LiveChannel target = null;
                // 先按对象 / key（分组+频道名）在当前列表中找到同一频道：同名频道会出现在多个分组
                // （如央视与高清分组都有 CCTV-1、线路不同），只按频道名会播成别的分组的那个
                LiveChannel exact = findChannelInCurrentList(channel);
                if (exact != null && channelManager.syncCurrentTo(exact)) {
                    target = channelManager.getCurrentChannel();
                } else if (channel != null && channelManager.syncCurrentTo(channel)) {
                    // 兜底：key 也找不到（分组被改名等）时退回 ChannelManager 的按名匹配
                    // 取当前列表中的那份对象（可能是按频道名匹配到的新副本）
                    target = channelManager.getCurrentChannel();
                }
                if (target == null) {
                    // 点中的频道已不在当前列表：刷新界面让用户重新选择，不按下标猜
                    Timber.w("点击的频道已不在当前列表: %s",
                            channel != null ? channel.getChannelName() : "null");
                    ToastUtil.show(LivePlayActivity.this, "频道列表已更新，请重新选择");
                    refreshChannelListInPlace();
                    return;
                }
                // playChannel 内部会同步"正在播放"高亮
                playChannel(target);
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
                // 后台静默刷新可能在用户浏览频道列表时到达：列表打开时原地刷新，
                // 保留浏览分组与焦点所在频道，避免跳回播放分组、按 OK 播错台
                a.refreshChannelListKeepingBrowse();
                // 刷新后当前频道可能换成了新对象：对齐线路索引并重拉信息栏节目单
                a.resyncPlayingChannel(true);
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
        shownGroups = groups;
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
        channelAdapter.setChannels(g != null ? g.snapshotChannels() : null,
                channelNumberOffset(groups, browsingGroupIndex));
        channelAdapter.setPlayingIndex(playingIndexInGroup(browsingGroupIndex));
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
        shownGroups = groups;
        if (groups == null) groups = new ArrayList<>();
        groupAdapter.setGroups(groups);

        if (groups.isEmpty()) {
            browsingGroupIndex = 0;
            channelAdapter.setChannels(null);
            channelAdapter.setPlayingIndex(-1);
            return;
        }

        // 关键差异：这里保留用户当前浏览的分组，而不是强制跳回播放分组
        browsingGroupIndex = Math.max(0, Math.min(browsingGroupIndex, groups.size() - 1));
        groupAdapter.setSelectedIndex(browsingGroupIndex);
        ChannelGroup g = groups.get(browsingGroupIndex);
        channelAdapter.setChannels(g != null ? g.snapshotChannels() : null,
                channelNumberOffset(groups, browsingGroupIndex));
        channelAdapter.setPlayingIndex(playingIndexInGroup(browsingGroupIndex));

        restoreChannelFocus(keepChannelPos);
    }

    /**
     * 频道数据被后台更新（静默刷新 / 测速完成 / 测速前重载）后的列表刷新入口：
     * 列表面板打开时原地刷新并保持浏览位置；未打开时按原逻辑刷新（下次打开本来也会重新定位）。
     */
    private void refreshChannelListKeepingBrowse() {
        if (isChannelListVisible) {
            refreshChannelListInPlace();
        } else {
            refreshGroupAdapter();
        }
    }

    /** 列表打开期间若 ChannelManager 已整体替换了分组列表（如测速前重载），原地刷新界面 */
    private void refreshChannelListIfReplaced() {
        if (!isChannelListVisible || channelManager == null) return;
        if (channelManager.getChannelGroups() == shownGroups) return;
        Timber.d("频道列表已在后台替换，原地刷新界面");
        refreshChannelListInPlace();
    }

    /**
     * 面板可见时的原地刷新：保留用户正在浏览的分组（按分组名在新列表中重新定位）与
     * 焦点所在的频道（按频道 key / 名称重新定位），不强制跳回播放分组。
     *
     * <p>不能直接用 {@link #refreshGroupAdapter()}：它把浏览分组重置为播放分组并整表替换，
     * 而 RecyclerView 焦点仍停在原下标，换成了别的分组的频道，用户按 OK 会播错台。
     * 也不能用 {@link #refreshGroupAdapterKeepingFocus(int)}：焦点在分组栏（pos&lt;0）时它会退回
     * refreshGroupAdapter，同样重置分组。
     */
    private void refreshChannelListInPlace() {
        if (groupAdapter == null || channelAdapter == null) return;
        if (!isChannelListVisible) {
            refreshGroupAdapter();
            return;
        }
        try {
            // 1) 记录刷新前的浏览位置
            final boolean focusInChannels = isFocusIn(channelListView);
            final boolean focusInGroups = !focusInChannels && isFocusIn(groupListView);
            int focusedPos = focusInChannels ? focusedChannelPosition() : -1;
            LiveChannel focused = channelAdapter.getItem(focusedPos);
            String focusedKey = focused != null ? focused.getKey() : null;
            String focusedName = focused != null ? focused.getChannelName() : null;
            String browsingName = groupNameAt(shownGroups, browsingGroupIndex);

            // 2) 换数据，但保留浏览分组
            List<ChannelGroup> groups = channelManager.getChannelGroups();
            shownGroups = groups;
            if (groups == null) groups = new ArrayList<>();
            groupAdapter.setGroups(groups);
            if (groups.isEmpty()) {
                browsingGroupIndex = 0;
                channelAdapter.setChannels(null);
                channelAdapter.setPlayingIndex(-1);
                return;
            }
            int target = indexOfGroupByName(groups, browsingName);
            if (target < 0) target = browsingGroupIndex; // 分组已不存在：按原下标就近（下面会钳位）
            browsingGroupIndex = Math.max(0, Math.min(target, groups.size() - 1));
            groupAdapter.setSelectedIndex(browsingGroupIndex);
            ChannelGroup g = groups.get(browsingGroupIndex);
            List<LiveChannel> chs = g != null ? g.snapshotChannels() : null;
            channelAdapter.setChannels(chs, channelNumberOffset(groups, browsingGroupIndex));
            channelAdapter.setPlayingIndex(playingIndexInGroup(browsingGroupIndex));

            // 3) 还原焦点。整表刷新后 RecyclerView 会把焦点恢复到"原下标"，
            //    那里可能已是别的频道，所以这里主动把焦点移到同一频道的新位置
            if (focusInChannels) {
                int count = chs != null ? chs.size() : 0;
                if (count > 0) {
                    int newPos = indexOfChannel(chs, focusedKey, focusedName);
                    if (newPos < 0) newPos = focusedPos;
                    final int p = Math.max(0, Math.min(newPos, count - 1));
                    // post：setChannels 在列表繁忙时会把替换推迟一帧，焦点移动必须排在其后
                    channelListView.post(() -> {
                        if (!isAlive() || !isChannelListVisible) return;
                        focusChannelItem(p);
                    });
                    restoreChannelFocus(p);
                }
            } else if (focusInGroups && groupListView != null) {
                final int gp = browsingGroupIndex;
                groupListView.post(() -> {
                    if (!isAlive() || !isChannelListVisible) return;
                    focusGroupItem(gp);
                });
            }
        } catch (Throwable t) {
            // 刷新失败不应影响播放；退回原有整表刷新
            Timber.w(t, "原地刷新频道列表失败");
            refreshGroupAdapter();
        }
    }

    /** 当前焦点所在频道条目的 adapter 位置；焦点不在频道列表时返回 -1 */
    private int focusedChannelPosition() {
        if (channelListView == null) return -1;
        try {
            View f = getCurrentFocus();
            if (f == null) return -1;
            // 焦点可能落在条目的子 View 上，先找到所属条目
            View item = channelListView.findContainingItemView(f);
            return item != null ? channelListView.getChildAdapterPosition(item) : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    private static String groupNameAt(List<ChannelGroup> groups, int index) {
        try {
            if (groups == null || index < 0 || index >= groups.size()) return null;
            ChannelGroup g = groups.get(index);
            return g != null ? g.getGroupName() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static int indexOfGroupByName(List<ChannelGroup> groups, String name) {
        if (groups == null || name == null) return -1;
        for (int i = 0; i < groups.size(); i++) {
            ChannelGroup g = groups.get(i);
            if (g != null && name.equals(g.getGroupName())) return i;
        }
        return -1;
    }

    /**
     * 在 ChannelManager 当前列表中查找与 {@code clicked} 对应的频道对象：
     * 先按对象引用，再按 getKey()（分组+频道名）；都找不到返回 null，由调用方决定是否按名兜底。
     */
    private LiveChannel findChannelInCurrentList(LiveChannel clicked) {
        if (clicked == null || channelManager == null) return null;
        List<ChannelGroup> groups = channelManager.getChannelGroups();
        if (groups == null) return null;
        String key = clicked.getKey();
        LiveChannel byKey = null;
        try {
            for (int g = 0; g < groups.size(); g++) {
                ChannelGroup group = groups.get(g);
                if (group == null) continue;
                List<LiveChannel> chs = group.snapshotChannels();
                for (int c = 0; c < chs.size(); c++) {
                    LiveChannel ch = chs.get(c);
                    if (ch == clicked) return ch;
                    if (byKey == null && key != null && key.equals(ch.getKey())) byKey = ch;
                }
            }
        } catch (Exception e) {
            Timber.w(e, "按 key 查找点击的频道异常");
        }
        return byKey;
    }

    /** 在频道快照中按 key（优先）或频道名查找位置 */
    private static int indexOfChannel(List<LiveChannel> chs, String key, String name) {
        if (chs == null || (key == null && name == null)) return -1;
        if (key != null) {
            for (int i = 0; i < chs.size(); i++) {
                LiveChannel c = chs.get(i);
                if (c != null && key.equals(c.getKey())) return i;
            }
        }
        if (name != null) {
            for (int i = 0; i < chs.size(); i++) {
                LiveChannel c = chs.get(i);
                if (c != null && name.equals(c.getChannelName())) return i;
            }
        }
        return -1;
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

    /** 指定分组首个频道的全局序号（与数字键换台 switchToChannelByNum 的编号一致，从 1 开始） */
    private int channelNumberOffset(int groupIndex) {
        return channelNumberOffset(channelManager.getChannelGroups(), groupIndex);
    }

    private static int channelNumberOffset(List<ChannelGroup> groups, int groupIndex) {
        int offset = 1;
        if (groups == null) return offset;
        try {
            for (int g = 0; g < groupIndex && g < groups.size(); g++) {
                ChannelGroup group = groups.get(g);
                // 与 switchToChannelByNum 一致：空分组不占编号
                if (group != null) offset += group.getChannelCount();
            }
        } catch (Exception ignore) {
            // 后台线程替换列表导致的越界：编号仅用于展示，失败不影响操作
        }
        return offset;
    }

    // ============ 播放控制 ============

    private void playChannel(LiveChannel channel) {
        if (channel == null || !isAlive()) return;

        // 关键：把"当前频道"同步到本次要播放的频道。
        // 否则从频道列表长按菜单（播放此线路/置顶）直接播放非当前频道时，
        // 后续播放超时换源、上下切源仍以旧的"当前频道"为准，会切走播成别的频道。
        boolean synced = channelManager.syncCurrentTo(channel);
        // 传入的可能是列表被刷新/重载替换前的旧对象（弹窗、长按菜单持有的引用）：
        // 改为播放管理器现在指向的同一频道对象，否则换源/重排/高亮作用在两个不同对象上；
        // 旧对象上选定的线路按 URL 带过去
        LiveChannel resolved = synced ? channelManager.getCurrentChannel() : null;
        if (resolved != null && resolved != channel) {
            String chosen = channel.getCurrentSourceUrl();
            if (chosen != null) resolved.syncSourceIndexTo(chosen);
            channel = resolved;
        }

        // 同步频道列表的"正在播放"高亮，避免菜单播放后标记停留在旧频道
        try {
            if (channelAdapter != null) {
                channelAdapter.setPlayingIndex(playingIndexInGroup(browsingGroupIndex));
            }
        } catch (Exception ignore) {
        }

        // 已进入后台（onStop 之后）：异步加载完成 / 重载 / 换源回调才到达。
        // 只记录当前频道，不创建播放器，交给 onStart 回到前台时起播
        if (!started) {
            Timber.d("界面不可见，推迟起播: %s", channel.getChannelName());
            playerReleasedOnStop = true;
            return;
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
        RunLog.i("换台: " + channel.getChannelName() + " 线路 " + (channel.getSourceIndex() + 1)
                + "/" + channel.getSourceCount());
        // 用户主动切换频道：重置本频道的换源尝试计数和提醒状态
        sourceTryCount = 0;
        allSourcesTimeoutPrompted = false;
        reconnectSameSourceOnce = false;
        waitingForNetwork = false;
        if (handler != null) handler.removeCallbacks(networkResumeRunnable);
        // 换台后旧频道的"全部失败"弹窗已无意义（且其"重试本频道"会切回旧频道）
        dismissAllSourcesDialog();
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
        // 与 ProtocolFilter 保持同一套白名单：http(s)/rtsp(s)/rtmp(s)/file/content 可播，
        // rtp/udp/igmp/p2p 等视为无效线路直接跳过，避免换源时白等超时
        return com.github.tvbox.osc.util.ProtocolFilter.isSupported(u);
    }

    /**
     * 构建按协议分发的媒体源工厂，带上指定的 UA 与请求头。
     * 允许跨协议重定向（http<->https）、放宽超时、默认使用通用浏览器 UA
     * （部分 IPTV 源会拒绝 ExoPlayer 默认 UA），这些差异是导致"其他播放器能播、ExoPlayer 播不了"的常见原因。
     */
    private DefaultMediaSourceFactory createMediaSourceFactory(String userAgent,
                                                               java.util.Map<String, String> requestHeaders) {
        DefaultHttpDataSource.Factory httpDataSourceFactory = new DefaultHttpDataSource.Factory()
                .setUserAgent(userAgent)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(15000)
                .setAllowCrossProtocolRedirects(true)
                .setKeepPostFor302Redirects(true);
        if (requestHeaders != null && !requestHeaders.isEmpty()) {
            httpDataSourceFactory.setDefaultRequestProperties(requestHeaders);
        }
        return new DefaultMediaSourceFactory(this)
                // 按协议分发：rtmp:// 走 RtmpDataSource，其余走 HTTP 数据源。
                // rtsp:// 由 DefaultMediaSourceFactory 直接创建 RtspMediaSource，无需数据源支持。
                .setDataSourceFactory(
                        new com.github.tvbox.osc.player.SchemeDataSourceFactory(httpDataSourceFactory))
                // 单次读取失败后在放弃前多重试几次，抵抗瞬时网络抖动
                .setLoadErrorHandlingPolicy(
                        new com.google.android.exoplayer2.upstream.DefaultLoadErrorHandlingPolicy(
                                LOAD_ERROR_RETRY_COUNT));
    }

    /**
     * 是否显式按 HLS 播放。ExoPlayer 只凭路径末尾 ".m3u8" 推断 HLS，
     * 而 tv.php?id=1、/live/cctv1、.m3u 这类网关地址实际返回 #EXTM3U 播放列表时会被当成普通文件流，
     * 报"无法识别的容器"黑屏换源——测速按内容嗅探判为 HLS 且速度最快，结果最快的线路反而播不了。
     * 依据：测速缓存标记为 HLS、路径以 .m3u8/.m3u 结尾、或查询串里带 m3u8（如 ?type=m3u8）。
     * 不按"没有扩展名"一律当 HLS：不少无扩展名的网关返回的是 FLV/TS，强制 HLS 反而播不了。
     */
    private boolean shouldPlayAsHlsByUrl(Uri uri) {
        String scheme = uri.getScheme();
        if (scheme == null) return false;
        scheme = scheme.toLowerCase(java.util.Locale.ROOT);
        // 只对 HTTP(S) 生效：rtsp/rtmp 设了 HLS mime 会被错误地交给 HLS 解析
        if (!scheme.equals("http") && !scheme.equals("https")) return false;
        String path = uri.getPath();
        String p = path != null ? path.toLowerCase(java.util.Locale.ROOT) : "";
        if (p.endsWith(".m3u8") || p.endsWith(".m3u")) return true;
        // 查询串带 m3u8 只对"路径没写明普通文件扩展名"的地址生效：
        // live.flv?src=cctv1.m3u8、a.ts?from=m3u8 这类代理地址按 1.2.4 的行为走普通流，
        // 否则会被当成 HLS 报"清单格式错误"，而重试只覆盖"普通流→HLS"一个方向
        String query = uri.getEncodedQuery();
        if (query != null && query.toLowerCase(java.util.Locale.ROOT).contains("m3u8")
                && !hasProgressiveExtension(p)) return true;
        return false;
    }

    /** 测速按内容嗅探判为 HLS（只对 HTTP(S) 生效） */
    private boolean isCachedHls(String rawUrl, Uri uri) {
        String scheme = uri.getScheme();
        if (scheme == null) return false;
        scheme = scheme.toLowerCase(java.util.Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) return false;
        try {
            SpeedTestResult r = speedTestEngine != null ? speedTestEngine.getCachedResult(rawUrl) : null;
            return r != null && r.isHls();
        } catch (Throwable ignore) {
            return false;
        }
    }

    /** 运行日志里只记协议+主机+路径，去掉查询串（常含鉴权 token） */
    private static String logUrl(String raw) {
        try {
            Uri u = Uri.parse(StreamUrl.playUrl(raw));
            return u.getScheme() + "://" + u.getHost() + (u.getPath() != null ? u.getPath() : "");
        } catch (Throwable t) {
            return "?";
        }
    }

    /** 路径末段带明确的普通文件流扩展名（.flv/.ts/.mp4 等） */
    private static boolean hasProgressiveExtension(String lowerPath) {
        int slash = lowerPath.lastIndexOf('/');
        String seg = slash >= 0 ? lowerPath.substring(slash + 1) : lowerPath;
        return seg.endsWith(".flv") || seg.endsWith(".ts") || seg.endsWith(".mp4")
                || seg.endsWith(".mkv") || seg.endsWith(".mp3") || seg.endsWith(".aac")
                || seg.endsWith(".m4a") || seg.endsWith(".mov") || seg.endsWith(".webm");
    }

    /** 实际请求地址是否为 HTTP(S)（HLS 只能走 HTTP，rtmp/file/content 按 HLS 重试必然失败） */
    private static boolean isHttpPlayUrl(String rawUrl) {
        String u = StreamUrl.playUrl(rawUrl);
        if (u == null) return false;
        String l = u.toLowerCase(java.util.Locale.ROOT);
        return l.startsWith("http://") || l.startsWith("https://");
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
    /**
     * 频道对象被刷新替换、或线路被测速重排后，让当前频道的索引重新指向播放器实际加载的 URL，
     * 并更新播放提示；reloadEpg 为 true 时按新对象重拉信息栏节目单
     * （EPG 回调按对象身份判断"是否仍是当前频道"，换了对象后旧回调不会再更新信息栏）。
     */
    private void resyncPlayingChannel(boolean reloadEpg) {
        LiveChannel ch = channelManager.getCurrentChannel();
        if (ch == null) return;
        String url = currentPlayUrl;
        if (url != null && ch.syncSourceIndexTo(url)) {
            LiveChannel.setPlayingHint(ch, url);
        }
        if (reloadEpg) loadEpgForChannel(ch);
    }

    private void initPlayer(String url) {
        initPlayer(url, false);
    }

    /**
     * @param forceHls true＝按普通流加载时容器无法识别，同一地址按 HLS 再试一次
     */
    private void initPlayer(String url, boolean forceHls) {
        if (playerView == null || !isAlive()) return;
        // 新一次起播：上一条线路的"稳定播放"计时作废
        if (handler != null) handler.removeCallbacks(stablePlayResetRunnable);
        if (!started) {
            // 后台时（换源回调等直接走到这里）不创建/启动播放器，回到前台由 onStart 重新起播
            cancelPlayTimeout();
            playerReleasedOnStop = true;
            return;
        }
        if (!isPlayableUrl(url)) {
            Timber.w("initPlayer: URL 无效, 触发换源: %s", url);
            postSourceFailure(false);
            return;
        }
        currentPlayUrl = url;
        playGen++;
        // 告知 LiveChannel 当前实际播放的线路：测速排序/置顶重排时据此保持索引指向这条线路
        LiveChannel.setPlayingHint(channelManager.getCurrentChannel(), url);
        if (exoPlayer == null) {
            try {
                // 播放器级的默认工厂（无线路请求头）。实际每次起播都按线路自带的
                // UA/Referer 另建媒体源，见下方 setMediaSource。
                DefaultMediaSourceFactory mediaSourceFactory =
                        createMediaSourceFactory(StreamUrl.DEFAULT_USER_AGENT, null);

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
                            waitingForNetwork = false;
                            // 稳定播放一段时间后重置换源计数（见 stablePlayResetRunnable）
                            if (handler != null) {
                                handler.removeCallbacks(stablePlayResetRunnable);
                                handler.postDelayed(stablePlayResetRunnable, STABLE_PLAY_RESET_MS);
                            }
                            // 起播后开始轮询右下角实时网速
                            startCornerSpeedTicker();
                        } else if (handler != null) {
                            // 缓冲/空闲/结束：未达到稳定播放，不重置计数
                            handler.removeCallbacks(stablePlayResetRunnable);
                        }
                    }

                    @Override
                    public void onVideoSizeChanged(@NonNull VideoSize videoSize) {
                        // 部分流不带像素宽高比（为 0），按方形像素处理，避免算出 0 宽度
                        float par = videoSize.pixelWidthHeightRatio > 0
                                ? videoSize.pixelWidthHeightRatio : 1f;
                        videoWidth = (int) (videoSize.width * par);
                        videoHeight = videoSize.height;
                        if (Hawk.get(HawkConfig.LIVE_PLAYER_SCALE, 0) == SCALE_ORIGINAL) {
                            applyScaleMode();
                        }
                    }

                    @Override
                    public void onPlayerError(@NonNull PlaybackException error) {
                        Timber.w("播放出错: %s", error.getMessage());
                        RunLog.i("播放出错: " + error.getErrorCodeName() + " " + logUrl(currentPlayUrl));
                        cancelPlayTimeout();
                        if (handler != null) handler.removeCallbacks(stablePlayResetRunnable);
                        // 只因测速缓存标记才按 HLS 加载、却报清单/容器解析错误：缓存标记可能已过时
                        // （或网关按请求头返回不同内容），按普通流重试同一地址一次再换源
                        if (currentItemHlsFromCache && handler != null && currentPlayUrl != null
                                && (error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED
                                || error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
                                || error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED)) {
                            final String retryUrl = currentPlayUrl;
                            cacheHlsSuppressedUrl = retryUrl;
                            Timber.i("按测速缓存的 HLS 标记加载失败，改按普通流重试: %s", retryUrl);
                            handler.post(() -> {
                                if (!isAlive()) return;
                                if (!retryUrl.equals(currentPlayUrl)) return;
                                initPlayer(retryUrl, false);
                            });
                            return;
                        }
                        // 未测速的无扩展名 HLS（如 tv.php?id=1）按普通流加载会报"容器无法识别"：
                        // 先按 HLS 重试同一地址一次，再失败才换源。同样异步执行，不在回调里同步重建。
                        if (error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
                                && !currentItemAsHls && handler != null && currentPlayUrl != null
                                // 非 HTTP(S)（rtmp/file/content）按 HLS 重试不可能成功，直接换源，免得低端盒子多等一轮
                                && isHttpPlayUrl(currentPlayUrl)) {
                            final String retryUrl = currentPlayUrl;
                            Timber.i("容器无法识别，按 HLS 重试: %s", retryUrl);
                            handler.post(() -> {
                                if (!isAlive()) return;
                                // 期间已换台/换源：交给新的播放流程，不再重试旧地址
                                if (!retryUrl.equals(currentPlayUrl)) return;
                                initPlayer(retryUrl, true);
                            });
                            return;
                        }
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

            // 去掉 TVBox 风格的 "$标签" 与 "|请求头" 后缀再交给播放器（仅影响本次请求，不改存储的 URL）。
            // 规则与测速共用 StreamUrl，保证两边请求的是同一个地址。
            Uri uri = Uri.parse(StreamUrl.playUrl(url));
            // forceHls 同样只对 HTTP(S) 生效，与 shouldPlayAsHls 的协议限制一致
            boolean ruleHls = (forceHls && isHttpPlayUrl(url)) || shouldPlayAsHlsByUrl(uri);
            boolean cacheHls = !ruleHls && !url.equals(cacheHlsSuppressedUrl) && isCachedHls(url, uri);
            boolean asHls = ruleHls || cacheHls;
            currentItemAsHls = asHls;
            currentItemHlsFromCache = cacheHls;
            RunLog.i("起播: " + logUrl(url) + (asHls ? (cacheHls ? " [HLS/测速]" : " [HLS]") : ""));
            // 直播流设置目标偏移：播放点离直播边缘更远，可容忍更大的网络波动而不卡顿
            MediaItem.Builder itemBuilder = new MediaItem.Builder()
                    .setUri(uri)
                    .setLiveConfiguration(
                            new MediaItem.LiveConfiguration.Builder()
                                    .setTargetOffsetMs(LIVE_TARGET_OFFSET_MS)
                                    .build());
            if (asHls) itemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8);
            MediaItem item = itemBuilder.build();
            // 线路自带的 UA/Referer（#EXTVLCOPT 或 url|User-Agent=..）逐条生效：
            // 服务器常按它们放行，测速带了而播放不带会出现"测速通过但播不了"。
            // 每条线路单独建工厂，不改共享工厂的默认属性，避免上一条线路的请求头串到下一条。
            java.util.Map<String, String> headers = new java.util.LinkedHashMap<>(StreamUrl.headers(url));
            String ua = StreamUrl.DEFAULT_USER_AGENT;
            for (java.util.Iterator<java.util.Map.Entry<String, String>> it = headers.entrySet().iterator(); it.hasNext(); ) {
                java.util.Map.Entry<String, String> e = it.next();
                if ("User-Agent".equalsIgnoreCase(e.getKey())) {
                    // UA 必须走 setUserAgent：DefaultHttpDataSource 会用它覆盖请求属性里的 User-Agent
                    if (e.getValue() != null && !e.getValue().isEmpty()) ua = e.getValue();
                    it.remove();
                }
            }
            exoPlayer.setMediaSource(createMediaSourceFactory(ua, headers).createMediaSource(item));
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
        final int gen = playGen;
        handler.post(() -> {
            if (!isAlive()) return;
            // 排队期间已换台/换源：这是上一次起播的失败，不能拿来换走新线路
            if (gen != playGen) {
                Timber.d("忽略过期的换源请求");
                return;
            }
            if (!started) {
                // onStop 之前已排队的失败回调：不在后台换源/重建播放器，回到前台从当前线路重新起播
                playerReleasedOnStop = true;
                return;
            }
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
        RunLog.i((byTimeout ? "起播超时: " : "线路失败: ") + channel.getChannelName() + " " + logUrl(currentPlayUrl));
        // 线路可能已被测速重排/刷新替换：先把索引对齐到播放器实际在播的 URL，换源才不会重复或跳过线路
        if (currentPlayUrl != null) channel.syncSourceIndexTo(currentPlayUrl);

        int total = channel.getSourceCount();
        if (total <= 0) {
            showAllSourcesTimeoutOnce(channel);
            return;
        }

        // 断网导致的失败不是线路问题：不计入换源次数、不轮换线路（否则会把置顶/最优线路换走，
        // 最后弹出"全部线路失败"并停在黑屏），等网络恢复回调自动重连
        if (!isNetworkConnected()) {
            if (!waitingForNetwork) {
                waitingForNetwork = true;
                Timber.w("网络已断开，暂停换源，等待网络恢复: %s", channel.getChannelName());
                ToastUtil.show(this, "网络已断开，恢复后将自动重连");
            }
            scheduleNetworkPollIfNeeded();
            return;
        }

        if (reconnectSameSourceOnce) {
            reconnectSameSourceOnce = false;
            String cur = channel.getCurrentSourceUrl();
            if (isPlayableUrl(cur)) {
                Timber.w("稳定播放后中断，原线路重连: %s", channel.getChannelName());
                ToastUtil.show(this, "播放中断，正在重连");
                initPlayer(cur);
                return;
            }
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
            dismissAllSourcesDialog();
            // 保存引用：网络恢复自动重连或换台时需要关掉它
            allSourcesDialog = new AlertDialog.Builder(this)
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

    // 缩放模式（与设置页下拉框顺序一致：自适应 / 填充 / 16:9 / 4:3 / 原始）
    private static final int SCALE_FILL = 1;
    private static final int SCALE_16_9 = 2;
    private static final int SCALE_4_3 = 3;
    private static final int SCALE_ORIGINAL = 4;

    /**
     * 应用缩放模式：
     * 自适应=等比完整显示；填充=拉伸铺满；16:9 / 4:3=在居中的固定比例区域内拉伸；
     * 原始=按视频像素尺寸显示（超出屏幕时等比缩小）。
     * 旧实现 16:9 / 4:3 与自适应完全相同，"原始"用 FIXED_WIDTH 实际是按宽铺满，均不符合预期。
     */
    private void applyScaleMode() {
        if (playerView == null || playerContainer == null) return;
        try {
            int scale = Hawk.get(HawkConfig.LIVE_PLAYER_SCALE, 0);
            switch (scale) {
                case SCALE_FILL:
                    setPlayerViewSize(-1, -1, AspectRatioFrameLayout.RESIZE_MODE_FILL);
                    break;
                case SCALE_16_9:
                    setPlayerViewAspect(16f / 9f);
                    break;
                case SCALE_4_3:
                    setPlayerViewAspect(4f / 3f);
                    break;
                case SCALE_ORIGINAL:
                    if (videoWidth > 0 && videoHeight > 0) {
                        final int vw = videoWidth;
                        final int vh = videoHeight;
                        playerContainer.post(() -> {
                            if (!isAlive() || playerContainer == null) return;
                            int cw = playerContainer.getWidth();
                            int ch = playerContainer.getHeight();
                            if (cw <= 0 || ch <= 0) return;
                            float shrink = Math.min(1f, Math.min((float) cw / vw, (float) ch / vh));
                            setPlayerViewSize(Math.round(vw * shrink), Math.round(vh * shrink),
                                    AspectRatioFrameLayout.RESIZE_MODE_FILL);
                        });
                    } else {
                        // 尚未拿到视频尺寸：先按自适应显示，onVideoSizeChanged 后再调整
                        setPlayerViewSize(-1, -1, AspectRatioFrameLayout.RESIZE_MODE_FIT);
                    }
                    break;
                default:
                    setPlayerViewSize(-1, -1, AspectRatioFrameLayout.RESIZE_MODE_FIT);
                    break;
            }
        } catch (Exception e) {
            // 缩放属展示层设置，任何异常都不得影响播放
            Timber.w(e, "应用缩放模式失败");
        }
    }

    /** 在播放区域内居中放置指定宽高比的画面框，画面拉伸填满该框 */
    private void setPlayerViewAspect(final float ratio) {
        // 需要容器实际尺寸，首次进入时尚未完成布局，post 到布局之后计算
        playerContainer.post(() -> {
            if (!isAlive() || playerContainer == null) return;
            int cw = playerContainer.getWidth();
            int ch = playerContainer.getHeight();
            if (cw <= 0 || ch <= 0) return;
            int w = cw;
            int h = Math.round(cw / ratio);
            if (h > ch) {
                h = ch;
                w = Math.round(ch * ratio);
            }
            setPlayerViewSize(w, h, AspectRatioFrameLayout.RESIZE_MODE_FILL);
        });
    }

    /** 设置画面尺寸（-1 表示铺满容器）与 ExoPlayer 缩放方式 */
    private void setPlayerViewSize(int width, int height, int resizeMode) {
        if (playerView == null) return;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                width > 0 ? width : FrameLayout.LayoutParams.MATCH_PARENT,
                height > 0 ? height : FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER);
        playerView.setLayoutParams(lp);
        playerView.setResizeMode(resizeMode);
    }

    private void releasePlayer() {
        currentPlayUrl = null;
        LiveChannel.setPlayingHint(null, null);
        if (handler != null) handler.removeCallbacks(stablePlayResetRunnable);
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
            // 用户按键即结束定位保护：onKeyDown 先于系统的方向键焦点移动执行，
            // 随后由按键引起的分组焦点变化走正常的切换分组逻辑
            positioningList = false;
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
                focusChannelItem(playingIndexInBrowsingGroup());
                return true;
            }
            // 列表条目上显示了全局频道号，数字键应能直接换台：
            // 先收起列表（数字气泡不被面板遮挡，换台后也不会留下过期的高亮/焦点），
            // 后续数字键走下面的常规路径
            if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
                hideChannelList();
                handleNumInput(keyCode - KeyEvent.KEYCODE_0);
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
        // 与 onKeyDown 一致：按键回调里的异常会直接闪退，统一兜底
        try {
            return handleKeyLongPress(keyCode, event);
        } catch (Exception e) {
            Timber.w(e, "长按处理异常 keyCode=%d", keyCode);
            return super.onKeyLongPress(keyCode, event);
        }
    }

    private boolean handleKeyLongPress(int keyCode, KeyEvent event) {
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
        try {
            return handleKeyUp(keyCode, event);
        } catch (Exception e) {
            Timber.w(e, "按键处理异常 keyCode=%d", keyCode);
            return super.onKeyUp(keyCode, event);
        }
    }

    private boolean handleKeyUp(int keyCode, KeyEvent event) {
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
            boolean showSpeed = Hawk.get(HawkConfig.LIVE_SHOW_SPEED_INFO, true);
            // 只显示实时吞吐：没有播放或取不到估算值时直接隐藏，
            // 不再回退到测速缓存值（那个值长期不变，容易被误认为是当前速度）
            Double live = showSpeed ? getCurrentLiveSpeedKbps() : null;

            // 左侧频道列表展开/底部信息栏显示时让位，避免文字压在面板上
            boolean obscured = isChannelListVisible || isInfoPanelVisible();

            if (live == null || live <= 0 || obscured) {
                tvCornerSpeed.setVisibility(View.GONE);
                return;
            }
            tvCornerSpeed.setText(formatSpeedKbps(live));
            tvCornerSpeed.setVisibility(View.VISIBLE);
        } catch (Throwable t) {
            // 网速显示属装饰性 UI，任何异常都不得影响播放
            Timber.w(t, "更新实时网速失败");
        }
    }

    /** 底部频道信息栏是否正在显示（它与网速标签同处底部会重叠） */
    private boolean isInfoPanelVisible() {
        return channelInfoPanel != null && channelInfoPanel.getVisibility() == View.VISIBLE;
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

    /**
     * 当前线路自己的测速值。
     *
     * @return null＝尚未测速；0＝测过但不可用；>0＝该线路的测速值（KB/s）
     */
    private Double getSourceSpeedKbps(LiveChannel ch) {
        try {
            String url = ch.getCurrentSourceUrl();
            // RTSP/RTMP 这类流式协议无法用 HTTP 片段测速，缓存里记的是"不可测速"而非"不可用"。
            // 必须在这里就返回 null（未测速），否则会被当成 0 显示成"不可用"，误导用户以为线路坏了。
            if (url != null && !com.github.tvbox.osc.util.ProtocolFilter.isSpeedTestable(url)) {
                return null;
            }
            if (url != null && speedTestEngine != null) {
                SpeedTestResult r = speedTestEngine.getCachedResult(url);
                // 缓存命中即直接采用：0 也是结论（测过但不可用），
                // 不能因为 <=0 就往下走，否则会被误判成"未测速"。
                // 低于最低速度阈值的线路 available=false 但仍保留测得的速度（排序要用），
                // 显示时必须按"不可用"处理，不能显示成 "30 KB/s" 让人以为能用。
                if (r != null) {
                    if (!r.isAvailable() && !SpeedTestEngine.UNTESTABLE.equals(r.getFailureType())) return 0.0;
                    return r.getSpeed();
                }
            }
            // 2) 按线路下标取该线路自身的测速值。
            //    speeds 与 sourceUrls 严格一一对应（测速时按 sourceIndex 写入，
            //    去重/过滤时也同步裁剪），所以这就是"当前线路"的测速值。
            //
            //    ⚠️ 这里绝不能用 getBestSpeed() 兜底：那是频道内<b>最快线路</b>的速度，
            //    会让每条线路都显示同一个数字，正是要避免的误导。
            java.util.List<Double> speeds = ch.getSpeeds();
            int idx = ch.getSourceIndex();
            if (speeds != null && idx >= 0 && idx < speeds.size()) {
                Double v = speeds.get(idx);
                // 0 也是有效信息：说明这条线路测过但不可用，需与"未测速"区分
                if (v != null) return v;
            }
        } catch (Throwable ignore) {
            // 忽略，按未测速处理
        }
        return null;
    }

    /** 是否为无法用 HTTP 测速的流式协议（RTSP/RTMP 等） */
    private boolean isStreamProtocol(String url) {
        return url != null && !url.trim().isEmpty()
                && !com.github.tvbox.osc.util.ProtocolFilter.isSpeedTestable(url);
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
            // 显示"当前线路"自己的测速值（与右下角一致），而不是频道最快线路的速度，
            // 否则切线路时这里的数字纹丝不动，看着像所有线路速度都一样。
            Double cur = showSpeed ? getSourceSpeedKbps(channel) : null;
            if (cur != null && cur > 0) {
                tvSpeedInfo.setVisibility(View.VISIBLE);
                tvSpeedInfo.setText(String.format("%.0f KB/s", cur));
            } else if (cur != null) {
                // 该线路测过但不可用
                tvSpeedInfo.setVisibility(View.VISIBLE);
                tvSpeedInfo.setText("不可用");
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
        if (channelListView != null) channelListView.removeCallbacks(clearPositioning);
        positioningList = true;
        refreshGroupAdapter();
        // 分组列表滚动到当前浏览的分组（分组较多时它可能不在可见范围）
        if (groupListView != null) groupListView.scrollToPosition(browsingGroupIndex);
        channelListPanel.setVisibility(View.VISIBLE);
        channelListPanel.startAnimation(android.view.animation.AnimationUtils
                .loadAnimation(this, R.anim.panel_slide_in_left));
        // 触发列表逐项进入动画
        if (channelListView.getLayoutAnimation() != null) {
            channelListView.scheduleLayoutAnimation();
        }
        isChannelListVisible = true;
        // 列表盖住左下角，网速标签需要让位
        updateCornerSpeed();
        // 聚焦到正在播放的频道：不在可见范围时 findViewHolderForAdapterPosition 返回 null，
        // 旧实现会退化为聚焦列表本身（落到第一项）。focusChannelItem 会先滚动过去再聚焦。
        // post 一帧：refreshGroupAdapter 可能因 RecyclerView 繁忙把数据替换推迟到下一帧
        channelListView.post(() -> {
            if (!isAlive() || !isChannelListVisible) {
                positioningList = false;
                return;
            }
            // 兜底：浏览分组若仍被改到别处，按播放频道重新定位一次
            if (!channelManager.isCurrentPinned()
                    && browsingGroupIndex != channelManager.getCurrentGroupIndex()) {
                refreshGroupAdapter();
            }
            focusChannelItem(playingIndexInBrowsingGroup());
            // focusChannelItem 可能再 post 一帧滚动后聚焦，RecyclerView 的焦点恢复也在之后的布局中进行：
            // 留出几帧再解除保护，之后的分组焦点变化才是用户操作
            channelListView.postDelayed(clearPositioning, 300);
        });
    }

    /**
     * 指定分组中"正在播放"频道的下标，不在该分组时返回 -1。
     * 正在播放的频道被刷新移除而处于钉住状态时，旧下标已被别的频道占据，同样返回 -1，
     * 避免把无关频道标成正在播放。
     */
    private int playingIndexInGroup(int groupIndex) {
        if (channelManager == null || channelManager.isCurrentPinned()) return -1;
        return groupIndex == channelManager.getCurrentGroupIndex()
                ? channelManager.getCurrentChannelIndex() : -1;
    }

    /** 正在播放的频道在当前浏览分组中的位置；不在该分组则返回 0（首个频道） */
    private int playingIndexInBrowsingGroup() {
        // 钉住的频道已不在列表中：焦点落到首项，而不是占据旧位置的无关频道
        if (channelManager.isCurrentPinned()) return 0;
        return browsingGroupIndex == channelManager.getCurrentGroupIndex()
                ? Math.max(0, channelManager.getCurrentChannelIndex()) : 0;
    }

    private void hideChannelList() {
        if (channelListView != null) channelListView.removeCallbacks(clearPositioning);
        positioningList = false;
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
        // 列表收起后左下角重新可用
        updateCornerSpeed();
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

    /**
     * 安全弹出对话框：Activity 已在关闭/销毁时 show() 会抛 BadTokenException，
     * 异步回调（拉源、测速、换源失败）里弹窗尤其容易撞上。
     *
     * @return 是否成功显示
     */
    private boolean showDialogSafely(AlertDialog dialog) {
        if (dialog == null || !isAlive()) return false;
        try {
            dialog.show();
            return true;
        } catch (Exception e) {
            Timber.w(e, "显示对话框失败");
            return false;
        }
    }

    /** Activity 是否仍处于可安全操作 UI 的存活状态 */
    private boolean isAlive() {
        return !isFinishing() && !isDestroyed();
    }

    private void showExitDialog() {
        if (!isAlive()) return;
        // 仅保留"确定"一个选项；不需要退出时按返回键即可关闭本弹窗
        showDialogSafely(new AlertDialog.Builder(this)
                .setTitle("退出直播")
                .setMessage("确定要退出吗？")
                .setPositiveButton("确定", (dialog, which) -> finish())
                .create());
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

        // "▶" 只标在真正正在播放的那条线路上：长按的可能是列表里任意一个非播放频道
        final boolean isPlayingChannel = isPlayingChannel(channel);
        final String playingUrl = currentPlayUrl;
        int playingIdx = channel.getSourceIndex();
        final int count = urls.size();
        CharSequence[] items = new CharSequence[count];
        for (int i = 0; i < count; i++) {
            boolean isPlaying = isPlayingChannel
                    && (playingUrl != null ? playingUrl.equals(urls.get(i)) : i == playingIdx);
            items[i] = (isPlaying ? "▶ " : "   ") + "线路 " + (i + 1) + "  " + shortUrl(urls.get(i));
        }

        try {
            new AlertDialog.Builder(this)
                    .setTitle(channel.getChannelName() + " · 线路管理（共 " + count + " 条）")
                    .setItems(items, (dialog, which) -> {
                        // 按弹窗展示时的快照取 URL，而不是之后再按行号去取：
                        // 弹窗期间测速完成会重排/裁剪 channel.sourceUrls，行号会指向另一条线路
                        if (which >= 0 && which < urls.size()) {
                            showSingleSourceActionDialog(channel, urls.get(which), which, keepChannelPos);
                        }
                    })
                    .setNegativeButton("关闭", null)
                    .show();
        } catch (Exception e) {
            Timber.w(e, "显示线路管理对话框失败");
        }
    }

    /** 指定频道是否就是正在播放的频道（按对象或 key 匹配：列表中可能是另一份同 key 的对象） */
    private boolean isPlayingChannel(LiveChannel channel) {
        if (channel == null || channelManager == null) return false;
        LiveChannel cur = channelManager.getCurrentChannel();
        if (cur == null) return false;
        if (cur == channel) return true;
        String k = cur.getKey();
        return k != null && k.equals(channel.getKey());
    }

    /**
     * 针对某条线路的操作菜单：置顶 / 删除并拉黑 / 切换播放。
     *
     * @param url          用户点中的线路 URL（展示时的快照）；所有操作都按它在点击时重新定位
     * @param displayIndex 展示时的行号，仅用于标题
     */
    private void showSingleSourceActionDialog(final LiveChannel channel, final String url,
                                              final int displayIndex, final int keepChannelPos) {
        if (!isAlive() || channel == null || url == null) return;

        String[] actions = {"播放此线路", "置顶此线路", "删除并加入黑名单"};
        try {
            new AlertDialog.Builder(this)
                    // 标题带上 URL，便于确认操作的是哪条线路
                    .setTitle("线路 " + (displayIndex + 1) + "  " + shortUrl(url))
                    .setItems(actions, (dialog, which) -> {
                        try {
                            // 点击时重新定位：两次弹窗之间线路列表可能已被测速重排
                            List<String> curUrls = channel.getSourceUrls();
                            int idx = curUrls != null ? curUrls.indexOf(url) : -1;
                            if (idx < 0) {
                                ToastUtil.show(this, "线路列表已更新，请重新选择");
                                refreshGroupAdapterKeepingFocus(keepChannelPos);
                                return;
                            }
                            switch (which) {
                                case 0: // 播放此线路
                                    channel.setSourceIndex(idx);
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
                                case 2: { // 删除并拉黑
                                    // 只有"被删的正是播放器此刻在播的线路"才需要换线：
                                    // 不能只比较 sourceIndex——长按的可能是非播放频道（其 sourceIndex 通常也是 0），
                                    // 测速排序也会把 sourceIndex 归零而不切换播放
                                    LiveChannel cur = channelManager.getCurrentChannel();
                                    boolean playingChannel = isPlayingChannel(channel);
                                    String playing = currentPlayUrl;
                                    if (playing == null && playingChannel && cur != null) {
                                        playing = cur.getCurrentSourceUrl();
                                    }
                                    boolean playingRemoved = playingChannel && url.equals(playing);
                                    channelManager.blacklistSource(channel, url);
                                    if (playingChannel && cur != null && cur != channel) {
                                        // 正在播放的是同 key 的另一份对象：同步移除，避免重连回被拉黑的线路
                                        channelManager.blacklistSource(cur, url);
                                    }
                                    ToastUtil.show(this, "已删除该线路并加入黑名单");
                                    if (channel.getSourceCount() == 0) {
                                        ToastUtil.show(this, "该频道已无可用线路");
                                    } else if (playingRemoved) {
                                        // 正在播放的线路被删除，切到当前索引指向的线路；
                                        // 播放正在播的那份对象，避免"当前频道"被挪到浏览中的副本
                                        playChannel(cur != null ? cur : channel);
                                    }
                                    break;
                                }
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
        // 按实际地址展示（保留 '$标签'）：带 '|User-Agent=..&Referer=..' 的线路结尾都是同一串编码请求头，
        // 直接截原始串会让同频道的几条线路看起来一模一样
        String play = StreamUrl.playUrl(url);
        if (play != null && !play.isEmpty()) {
            String noHeader = url;
            int bar = noHeader.indexOf('|');
            int dollar = noHeader.indexOf('$');
            String label = null;
            if (dollar > 0 && (bar < 0 || dollar < bar)) {
                label = noHeader.substring(dollar + 1, bar > dollar ? bar : noHeader.length()).trim();
            }
            url = (label != null && !label.isEmpty()) ? play + "$" + label : play;
        }
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
        // 弹窗失败时返回 false，调用方会立即放行 EPG，避免节目单被永久推迟
        return showDialogSafely(dialog);
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
        return showDialogSafely(dialog);
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
        speedTestChannelsDone = 0;
        speedTestChannelsTotal = 0;
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
        // 测速前重载会整体替换频道列表但不通知界面：列表打开时原地刷新，
        // 避免用户在旧列表上点选、按旧下标播到别的频道
        refreshChannelListIfReplaced();
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
            String channels = speedTestChannelsTotal > 0
                    ? String.format(java.util.Locale.CHINA, " · 频道 %d/%d",
                    speedTestChannelsDone, speedTestChannelsTotal)
                    : "";
            tvSpeedTestInfo.setText(String.format(java.util.Locale.CHINA, "测速 %d%% (%d/%d)%s %s",
                    percent, event.current, event.total, channels,
                    event.channelName != null ? event.channelName : ""));
        }
        // 仅比较引用，开销可忽略；兜住准备阶段事件之后才完成的列表替换
        refreshChannelListIfReplaced();
    }

    /**
     * 一个频道测完：引擎已原地更新该频道的测速值与线路排序，只刷新它在列表里的条目，
     * 不整表刷新（不打断浏览与焦点）。正在播放的频道被重排后，索引重新对准实际播放的线路。
     */
    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onSpeedTestChannel(BackgroundSpeedTestService.SpeedTestChannelEvent event) {
        if (!isAlive() || event == null || event.channel == null) return;
        speedTestChannelsDone = Math.max(speedTestChannelsDone, event.completedChannels);
        speedTestChannelsTotal = event.totalChannels;
        if (channelAdapter != null) channelAdapter.notifyChannelUpdated(event.channel);
        if (channelManager != null && channelManager.getCurrentChannel() == event.channel) {
            resyncPlayingChannel(false);
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onSpeedTestComplete(BackgroundSpeedTestService.SpeedTestCompleteEvent event) {
        if (!isAlive()) return;
        // 测速结束（含取消）：解除对 EPG 的让路限制并补拉节目单
        speedTestActive = false;
        speedTestActiveSince = 0L;
        speedTestChannelsDone = 0;
        speedTestChannelsTotal = 0;
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
            // 刷新列表以反映最新速度排序；列表打开时原地刷新，保留浏览分组与焦点
            refreshChannelListKeepingBrowse();
            resyncPlayingChannel(false);
        } else {
            // 取消的测速也可能已完成重载：列表打开时同步到新列表
            refreshChannelListIfReplaced();
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
        speedTestChannelsDone = 0;
        speedTestChannelsTotal = 0;
        if (handler != null) handler.removeCallbacks(epgFlushRetry);
        if (speedTestBar != null) speedTestBar.setVisibility(View.GONE);
        ToastUtil.show(this, "测速失败: " + event.errorMsg);
        refreshChannelListIfReplaced();
        flushDeferredEpg();
    }
}
