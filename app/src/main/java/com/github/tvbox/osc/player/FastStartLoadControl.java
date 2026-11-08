package com.github.tvbox.osc.player;

import androidx.annotation.NonNull;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.LoadControl;
import com.google.android.exoplayer2.Renderer;
import com.google.android.exoplayer2.Timeline;
import com.google.android.exoplayer2.source.MediaPeriodId;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.trackselection.ExoTrackSelection;
import com.google.android.exoplayer2.upstream.Allocator;

/**
 * 起播快、播起来以后照样攒大缓冲的 LoadControl。
 *
 * <p>ExoPlayer 出第一帧本来就快（实测 150ms 级），慢的是"画面动起来"：
 * {@link DefaultLoadControl} 要先攒够 bufferForPlaybackMs（本项目原来是 2.5 秒）才开始走。
 * 这里把<b>首次起播</b>的门槛单独调低（档位见 {@link #startBufferMs(int)}），
 * 其余全部交给内部的 DefaultLoadControl：
 * <ul>
 *   <li>卡顿后恢复（rebuffering）仍按原来的较大门槛，避免一卡再卡；</li>
 *   <li>min/max 缓冲时长与字节上限不变，起播后继续往 20–60 秒攒，抗抖动能力不变。</li>
 * </ul>
 *
 * <p>门槛按每次起播设置（{@link #setStartBufferMs}）：FLV/TS 这类按实时速度到达的流，
 * 起播后缓冲很难再涨，门槛过低容易刚起播就卡，所以给它们比 HLS 稍高的值。
 */
public final class FastStartLoadControl implements LoadControl {

    private final DefaultLoadControl delegate;
    private volatile long startBufferUs;

    public FastStartLoadControl(DefaultLoadControl delegate, long startBufferMs) {
        this.delegate = delegate;
        this.startBufferUs = startBufferMs * 1000L;
    }

    /** 本次起播的首次门槛（毫秒）；在主线程设置，播放线程读取 */
    public void setStartBufferMs(long ms) {
        this.startBufferUs = Math.max(0L, ms) * 1000L;
    }

    /**
     * 档位 → 首次起播门槛（毫秒）。
     *
     * @param level 0=快速 1=均衡 2=稳定
     * @param progressive true=FLV/TS/组播等实时到达的流（门槛给高一些），false=HLS/DASH 分片流
     */
    public static long startBufferMs(int level, boolean progressive) {
        switch (level) {
            case 2:
                return 2500L; // 1.2.x 的行为
            case 1:
                return progressive ? 1500L : 1000L;
            case 0:
            default:
                return progressive ? 800L : 500L;
        }
    }

    @Override
    public boolean shouldStartPlayback(@NonNull Timeline timeline,
                                       @NonNull MediaPeriodId mediaPeriodId,
                                       long bufferedDurationUs, float playbackSpeed,
                                       boolean rebuffering, long targetLiveOffsetUs) {
        if (!rebuffering && bufferedDurationUs != C.TIME_UNSET
                && bufferedDurationUs >= startBufferUs) {
            return true;
        }
        return delegate.shouldStartPlayback(timeline, mediaPeriodId, bufferedDurationUs,
                playbackSpeed, rebuffering, targetLiveOffsetUs);
    }

    // ---- 其余全部委托 ----

    @Override
    public void onPrepared() {
        delegate.onPrepared();
    }

    @Override
    public void onTracksSelected(@NonNull Timeline timeline,
                                 @NonNull MediaPeriodId mediaPeriodId,
                                 @NonNull Renderer[] renderers,
                                 @NonNull TrackGroupArray trackGroups,
                                 @NonNull ExoTrackSelection[] trackSelections) {
        delegate.onTracksSelected(timeline, mediaPeriodId, renderers, trackGroups, trackSelections);
    }

    @Override
    public void onStopped() {
        delegate.onStopped();
    }

    @Override
    public void onReleased() {
        delegate.onReleased();
    }

    @NonNull
    @Override
    public Allocator getAllocator() {
        return delegate.getAllocator();
    }

    @Override
    public long getBackBufferDurationUs() {
        return delegate.getBackBufferDurationUs();
    }

    @Override
    public boolean retainBackBufferFromKeyframe() {
        return delegate.retainBackBufferFromKeyframe();
    }

    @Override
    public boolean shouldContinueLoading(long playbackPositionUs, long bufferedDurationUs,
                                         float playbackSpeed) {
        return delegate.shouldContinueLoading(playbackPositionUs, bufferedDurationUs, playbackSpeed);
    }
}
