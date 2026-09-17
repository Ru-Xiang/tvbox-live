package com.github.tvbox.osc.ui.adapter;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.LiveChannel;

import java.util.ArrayList;
import java.util.List;

/**
 * 频道列表适配器（直播频道列表右侧栏）
 *
 * 支持：
 * - 显示台标、频道名、源数量、测速等级
 * - 收藏星标
 * - 当前播放高亮
 * - 单击播放、长按弹出收藏/黑名单菜单
 */
public class ChannelListAdapter extends RecyclerView.Adapter<ChannelListAdapter.ChannelViewHolder> {

    public interface OnChannelActionListener {
        void onChannelClick(int position, LiveChannel channel);
        void onChannelLongClick(int position, LiveChannel channel);
    }

    private final List<LiveChannel> channels = new ArrayList<>();
    private int playingIndex = -1;
    private OnChannelActionListener listener;
    private RecyclerView attachedView;

    /** 台标解码后的最大边长（px），限制位图内存占用 */
    private static final int LOGO_SIZE_PX = 96;

    public void setOnChannelActionListener(OnChannelActionListener listener) {
        this.listener = listener;
    }

    @Override
    public void onAttachedToRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onAttachedToRecyclerView(recyclerView);
        this.attachedView = recyclerView;
    }

    @Override
    public void onDetachedFromRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onDetachedFromRecyclerView(recyclerView);
        if (this.attachedView == recyclerView) this.attachedView = null;
    }

    public void setChannels(List<LiveChannel> data) {
        // 分组列表 item 获得焦点会同步触发本方法刷新频道列表；若此时 RecyclerView
        // 正处于布局/滚动计算中，直接 notifyDataSetChanged 会抛
        // "Cannot call this method while RecyclerView is computing a layout or scrolling"
        // 或 "Inconsistency detected"。因此在计算布局时改为 post 到下一帧执行。
        if (isBusy()) {
            final List<LiveChannel> snapshot = data == null ? null : new ArrayList<>(data);
            attachedView.post(() -> applyChannels(snapshot));
        } else {
            applyChannels(data);
        }
    }

    /** RecyclerView 是否正处于布局计算或滚动中（此时禁止调用 notify*） */
    private boolean isBusy() {
        return attachedView != null
                && (attachedView.isComputingLayout()
                || attachedView.getScrollState() != RecyclerView.SCROLL_STATE_IDLE);
    }

    private void applyChannels(List<LiveChannel> data) {
        channels.clear();
        if (data != null) channels.addAll(data);
        // 数据整体替换后，旧的播放高亮位置可能越界，重置以免 setPlayingIndex 误刷新
        if (playingIndex >= channels.size()) playingIndex = -1;
        notifyDataSetChanged();
    }

    /**
     * 更新播放中高亮项。
     *
     * <p>与 {@link #setChannels} 同样必须避开布局/滚动期：列表惯性滚动中按 OK 选台时，
     * RecyclerView 处于 SCROLL_STATE_SETTLING，此时 notifyItemChanged 会抛
     * IllegalStateException（断言对「滚动中」同样生效，不能只判 isComputingLayout）。
     */
    public void setPlayingIndex(int index) {
        final int old = playingIndex;
        playingIndex = index;
        if (isBusy()) {
            final int target = index;
            attachedView.post(() -> notifyHighlightChanged(old, target));
            return;
        }
        notifyHighlightChanged(old, index);
    }

    /** 刷新旧/新高亮项，下标越界时跳过（数据可能已在 post 期间变化） */
    private void notifyHighlightChanged(int old, int target) {
        try {
            if (old >= 0 && old < channels.size()) notifyItemChanged(old);
            if (target >= 0 && target < channels.size()) notifyItemChanged(target);
        } catch (Exception ignore) {
            try { notifyDataSetChanged(); } catch (Exception ignored) {}
        }
    }

    public LiveChannel getItem(int position) {
        if (position < 0 || position >= channels.size()) return null;
        return channels.get(position);
    }

    @NonNull
    @Override
    public ChannelViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_channel, parent, false);
        final ChannelViewHolder holder = new ChannelViewHolder(view);

        // 监听器只在创建 ViewHolder 时设置一次。
        // 若放在 onBindViewHolder 中，每次绑定都会新建 3 个 lambda 实例，
        // 列表快速滑动时产生大量临时对象，加重低内存设备的 GC 压力。
        view.setOnFocusChangeListener((v, hasFocus) -> animateFocus(v, hasFocus));
        view.setOnClickListener(v -> {
            int pos = holder.getAdapterPosition();
            LiveChannel c = getItem(pos);
            if (listener != null && c != null) {
                listener.onChannelClick(pos, c);
            }
        });
        view.setOnLongClickListener(v -> {
            int pos = holder.getAdapterPosition();
            LiveChannel c = getItem(pos);
            if (listener != null && c != null) {
                listener.onChannelLongClick(pos, c);
                return true;
            }
            return false;
        });
        return holder;
    }

    @Override
    public void onBindViewHolder(@NonNull ChannelViewHolder holder, int position) {
        if (position < 0 || position >= channels.size()) return;
        LiveChannel channel = channels.get(position);
        if (channel == null) return;

        holder.tvName.setText(channel.getChannelName() != null ? channel.getChannelName() : "");
        holder.tvSub.setText(channel.getSourceCount() + " 个源");

        // 当前播放高亮
        holder.itemView.setSelected(position == playingIndex);

        // 频道级收藏已移除，不再显示星标
        holder.ivFavorite.setVisibility(View.GONE);

        // 测速等级
        double speed = channel.getBestSpeed();
        if (speed > 0) {
            holder.tvSpeed.setVisibility(View.VISIBLE);
            holder.tvSpeed.setText(speedLevel(speed));
        } else {
            holder.tvSpeed.setVisibility(View.GONE);
        }

        // 台标：Glide 在 Activity 已 destroy 时会抛 IllegalArgumentException
        try {
            // 优先使用按频道名缓存的本地台标，没有才走远程（并在后台缓存供下次复用）
            Object logoSource = com.github.tvbox.osc.util.LogoCache.resolve(
                    holder.ivLogo.getContext(), channel.getChannelName(), channel.getLogoUrl());
            if (logoSource != null) {
                // 限制解码尺寸并用 RGB_565，显著降低台标位图内存占用（低内存电视更稳）
                Glide.with(holder.ivLogo.getContext())
                        .load(logoSource)
                        .override(LOGO_SIZE_PX, LOGO_SIZE_PX)
                        .format(com.bumptech.glide.load.DecodeFormat.PREFER_RGB_565)
                        .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                        .placeholder(R.drawable.ic_launcher)
                        .error(R.drawable.ic_launcher)
                        .into(holder.ivLogo);
            } else {
                holder.ivLogo.setImageResource(R.drawable.ic_launcher);
            }
        } catch (Exception ignore) {
            holder.ivLogo.setImageResource(R.drawable.ic_launcher);
        }
    }

    /** Apple 风格聚焦：平滑放大 + 抬升 */
    static void animateFocus(View v, boolean hasFocus) {
        if (v == null) return;
        try {
            float scale = hasFocus ? 1.04f : 1f;
            v.animate().scaleX(scale).scaleY(scale)
                    .translationZ(hasFocus ? 12f : 0f)
                    .setDuration(220)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f))
                    .start();
        } catch (Exception ignore) {}
    }

    private String speedLevel(double speed) {
        if (speed >= 1000) return "极速";
        if (speed >= 500) return "流畅";
        if (speed >= 200) return "正常";
        if (speed >= 50) return "较慢";
        return "卡顿";
    }

    @Override
    public int getItemCount() {
        return channels.size();
    }

    static class ChannelViewHolder extends RecyclerView.ViewHolder {
        final ImageView ivLogo;
        final ImageView ivFavorite;
        final TextView tvName;
        final TextView tvSub;
        final TextView tvSpeed;

        ChannelViewHolder(@NonNull View itemView) {
            super(itemView);
            ivLogo = itemView.findViewById(R.id.iv_channel_logo);
            ivFavorite = itemView.findViewById(R.id.iv_favorite);
            tvName = itemView.findViewById(R.id.tv_channel_name);
            tvSub = itemView.findViewById(R.id.tv_channel_sub);
            tvSpeed = itemView.findViewById(R.id.tv_channel_speed);
        }
    }
}
