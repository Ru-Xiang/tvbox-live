package com.github.tvbox.osc.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.EpgProgram;

import java.util.ArrayList;
import java.util.List;

/**
 * EPG 节目单列表适配器
 *
 * 显示：时间列（起始/结束）、节目标题、当前节目高亮 + 进度条、时长/正在播 标签。
 * 只有查看"今天"时才会有当前节目高亮；其它日期全部按普通条目展示。
 */
public class EpgListAdapter extends RecyclerView.Adapter<EpgListAdapter.VH> {

    private final List<EpgProgram> programs = new ArrayList<>();
    /** 当前正在播出的节目在列表中的位置；-1 表示无（非今日或列表为空） */
    private int currentIndex = -1;
    /** 当前节目的播放进度百分比 0-100 */
    private int currentProgress = 0;
    private RecyclerView attachedView;

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

    public void setData(List<EpgProgram> data, int currentIndex, int currentProgress) {
        // EPG 回调可能与遥控器快速滚动同时到达；若此刻 RecyclerView 正在计算布局或滚动，
        // 直接 notifyDataSetChanged 会抛 IllegalStateException，改为 post 到下一帧。
        if (attachedView != null && (attachedView.isComputingLayout()
                || attachedView.getScrollState() != RecyclerView.SCROLL_STATE_IDLE)) {
            final List<EpgProgram> snapshot = data == null ? null : new ArrayList<>(data);
            final int idx = currentIndex;
            final int prog = currentProgress;
            attachedView.post(() -> applyData(snapshot, idx, prog));
        } else {
            applyData(data, currentIndex, currentProgress);
        }
    }

    private void applyData(List<EpgProgram> data, int currentIndex, int currentProgress) {
        programs.clear();
        if (data != null) programs.addAll(data);
        this.currentIndex = currentIndex;
        this.currentProgress = Math.max(0, Math.min(100, currentProgress));
        notifyDataSetChanged();
    }

    public int getCurrentIndex() {
        return currentIndex;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_epg, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        if (position < 0 || position >= programs.size()) return;
        EpgProgram p = programs.get(position);
        if (p == null) return;

        //逐个控件判空，避免布局变体缺少某个 id 时直接 NPE 闪退
        if (h.tvStart != null) h.tvStart.setText(p.getStart() != null ? p.getStart() : "");
        if (h.tvEnd != null) h.tvEnd.setText(p.getEnd() != null ? p.getEnd() : "");
        if (h.tvTitle != null) h.tvTitle.setText(p.getTitle() != null ? p.getTitle() : "");

        boolean isCurrent = position == currentIndex;
        if (h.vNowDot != null) h.vNowDot.setVisibility(isCurrent ? View.VISIBLE : View.INVISIBLE);

        if (isCurrent) {
            h.itemView.setSelected(true);
            if (h.pbProgress != null) {
                h.pbProgress.setVisibility(View.VISIBLE);
                h.pbProgress.setProgress(currentProgress);
            }
            if (h.tvBadge != null) {
                h.tvBadge.setVisibility(View.VISIBLE);
                h.tvBadge.setText("正在播 " + currentProgress + "%");
            }
        } else {
            h.itemView.setSelected(false);
            if (h.pbProgress != null) h.pbProgress.setVisibility(View.GONE);
            int minutes = p.durationMinutes();
            if (h.tvBadge != null) {
                if (minutes > 0) {
                    h.tvBadge.setVisibility(View.VISIBLE);
                    h.tvBadge.setText(formatDuration(minutes));
                } else {
                    h.tvBadge.setVisibility(View.GONE);
                }
            }
        }

        // 聚焦动效复用现有工具
        h.itemView.setOnFocusChangeListener((v, hasFocus)
                -> ChannelListAdapter.animateFocus(v, hasFocus));
    }

    private String formatDuration(int minutes) {
        if (minutes < 60) return minutes + " 分钟";
        int h = minutes / 60;
        int m = minutes % 60;
        return m == 0 ? h + " 小时" : h + " 小时 " + m + " 分";
    }

    @Override
    public int getItemCount() {
        return programs.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final View vNowDot;
        final TextView tvStart;
        final TextView tvEnd;
        final TextView tvTitle;
        final ProgressBar pbProgress;
        final TextView tvBadge;

        VH(@NonNull View itemView) {
            super(itemView);
            vNowDot = itemView.findViewById(R.id.v_now_dot);
            tvStart = itemView.findViewById(R.id.tv_epg_start);
            tvEnd = itemView.findViewById(R.id.tv_epg_end);
            tvTitle = itemView.findViewById(R.id.tv_epg_title);
            pbProgress = itemView.findViewById(R.id.pb_epg_progress);
            tvBadge = itemView.findViewById(R.id.tv_epg_badge);
        }
    }
}
