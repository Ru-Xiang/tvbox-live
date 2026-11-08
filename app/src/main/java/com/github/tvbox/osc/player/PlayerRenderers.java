package com.github.tvbox.osc.player;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.audio.AudioSink;
import com.google.android.exoplayer2.audio.DefaultAudioSink;
import com.google.android.exoplayer2.mediacodec.MediaCodecInfo;
import com.google.android.exoplayer2.mediacodec.MediaCodecSelector;
import com.google.android.exoplayer2.mediacodec.MediaCodecUtil;
import com.google.android.exoplayer2.util.MimeTypes;

import java.util.ArrayList;
import java.util.List;

import timber.log.Timber;

/**
 * 播放器的解码器 / 音频输出配置（P3）。
 *
 * <ul>
 *   <li><b>视频只用硬解</b>：过滤掉 OMX.google.* / c2.android.* 这类纯软件视频解码器。
 *       设备硬解不了（典型是老盒子上的 4K HEVC 10-bit）时不偷偷退回软解拖垮 CPU，
 *       而是让这条线路按"解码不了"处理并提示，见 {@link #lastMissingHwMime()}。
 *       音频不受影响（音频软解开销很小）。</li>
 *   <li><b>FFmpeg 音频兜底</b>：系统没有 MP2 / AC3 / E-AC3 / DTS 解码器时用内置 FFmpeg 解；
 *       有系统解码器时仍优先系统（EXTENSION_RENDERER_MODE_ON）。</li>
 *   <li><b>音频直通</b>：开启时按 HDMI 上报的能力把 AC3/E-AC3 原样送给功放/电视；
 *       关闭则一律解成 PCM（功放不支持、或直通有杂音时用）。</li>
 *   <li><b>解码器回退</b>：首选解码器初始化失败时换同类的下一个解码器（仍只在允许的范围内）。</li>
 * </ul>
 */
public final class PlayerRenderers {

    private PlayerRenderers() {}

    /** 最近一次因"只有软解"而被过滤空的视频 MIME（主线程/播放线程都可能写，volatile 即可） */
    private static volatile String lastMissingHwMime;

    /** 最近一次被"只用硬解"过滤空的视频格式（如 video/hevc），没有为 null */
    @Nullable
    public static String lastMissingHwMime() {
        return lastMissingHwMime;
    }

    public static void clearMissingHwMime() {
        lastMissingHwMime = null;
    }

    public static DefaultRenderersFactory create(Context context, boolean hwVideoOnly,
                                                 boolean ffmpegAudio, boolean passthrough) {
        DefaultRenderersFactory f = new DefaultRenderersFactory(context) {
            @Nullable
            @Override
            @SuppressWarnings("deprecation")
            protected AudioSink buildAudioSink(@NonNull Context ctx, boolean enableFloatOutput,
                                               boolean enableAudioTrackPlaybackParams,
                                               boolean enableOffload) {
                if (passthrough) {
                    return super.buildAudioSink(ctx, enableFloatOutput,
                            enableAudioTrackPlaybackParams, enableOffload);
                }
                // 不传 Context：DefaultAudioSink 不再读取 HDMI 能力，按"无直通"处理，全部解成 PCM
                return new DefaultAudioSink.Builder()
                        .setEnableFloatOutput(enableFloatOutput)
                        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                        .build();
            }
        };
        f.setEnableDecoderFallback(true);
        f.setExtensionRendererMode(ffmpegAudio
                ? DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
                : DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF);
        if (hwVideoOnly) f.setMediaCodecSelector(HW_VIDEO_ONLY);
        return f;
    }

    /** 视频去掉纯软件解码器，其余（音频等）原样 */
    private static final MediaCodecSelector HW_VIDEO_ONLY = (mimeType, secure, tunneling) -> {
        List<MediaCodecInfo> all = MediaCodecUtil.getDecoderInfos(mimeType, secure, tunneling);
        if (!MimeTypes.isVideo(mimeType) || all.isEmpty()) return all;
        List<MediaCodecInfo> hw = new ArrayList<>(all.size());
        for (MediaCodecInfo info : all) {
            if (!info.softwareOnly) hw.add(info);
        }
        if (hw.isEmpty()) {
            lastMissingHwMime = mimeType;
            Timber.i("只用硬解：%s 没有硬件解码器（软解 %d 个已跳过）", mimeType, all.size());
        }
        return hw;
    };

    /** 给提示用的格式名：video/hevc → H.265 等 */
    public static String friendlyName(@Nullable String mime) {
        if (mime == null) return "该视频格式";
        switch (mime) {
            case MimeTypes.VIDEO_H265:
                return "H.265/HEVC";
            case MimeTypes.VIDEO_H264:
                return "H.264";
            case MimeTypes.VIDEO_MPEG2:
                return "MPEG-2";
            case MimeTypes.VIDEO_AV1:
                return "AV1";
            case MimeTypes.VIDEO_VP9:
                return "VP9";
            default:
                return mime;
        }
    }
}
