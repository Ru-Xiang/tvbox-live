package com.github.tvbox.osc.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.ChannelGroup;

import java.util.ArrayList;
import java.util.List;

/**
 * 分组列表适配器（直播频道列表左侧栏）
 */
public class GroupListAdapter extends RecyclerView.Adapter<GroupListAdapter.GroupViewHolder> {

    public interface OnGroupActionListener {
        /** 分组被选中（聚焦或点击） */
        void onGroupSelected(int position, ChannelGroup group);
    }

    private final List<ChannelGroup> groups = new ArrayList<>();
    private int selectedIndex = 0;
    private OnGroupActionListener listener;
    private RecyclerView attachedView;

    public void setOnGroupActionListener(OnGroupActionListener listener) {
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

    public void setGroups(List<ChannelGroup> data) {
        if (isBusy()) {
            final List<ChannelGroup> snapshot = data == null ? null : new ArrayList<>(data);
            attachedView.post(() -> applyGroups(snapshot));
        } else {
            applyGroups(data);
        }
    }

    private void applyGroups(List<ChannelGroup> data) {
        groups.clear();
        if (data != null) groups.addAll(data);
        if (selectedIndex >= groups.size()) selectedIndex = 0;
        notifyDataSetChanged();
    }

    /**
     * 设置当前选中项并刷新前后两项的高亮。
     *
     * <p>必须避开 RecyclerView 的布局/滚动期：本方法由焦点回调触发
     * （遥控器连续按上下时 RecyclerView 常处于 SCROLL_STATE_SETTLING），
     * 此时调用 notifyItemChanged 会抛
     * {@code IllegalStateException: Cannot call this method while RecyclerView is computing a layout or scrolling}。
     * 注意 RecyclerView 的断言对「滚动中」同样生效，因此不能只判 isComputingLayout。
     */
    public void setSelectedIndex(int index) {
        if (index < 0 || index >= groups.size() || index == selectedIndex) return;
        final int old = selectedIndex;
        selectedIndex = index;
        if (isBusy()) {
            final int target = index;
            attachedView.post(() -> notifyHighlightChanged(old, target));
        } else {
            notifyHighlightChanged(old, index);
        }
    }

    /** RecyclerView 是否正处于布局计算或滚动中（此时禁止调用 notify*） */
    private boolean isBusy() {
        return attachedView != null
                && (attachedView.isComputingLayout()
                || attachedView.getScrollState() != RecyclerView.SCROLL_STATE_IDLE);
    }

    /** 刷新旧/新选中项的高亮，下标越界时跳过（数据可能已在 post 期间变化） */
    private void notifyHighlightChanged(int old, int target) {
        try {
            if (old >= 0 && old < groups.size()) notifyItemChanged(old);
            if (target >= 0 && target < groups.size()) notifyItemChanged(target);
        } catch (Exception ignore) {
            // 极端情况下仍可能与布局冲突，退化为整表刷新
            try { notifyDataSetChanged(); } catch (Exception ignored) {}
        }
    }

    public int getSelectedIndex() {
        return selectedIndex;
    }

    @NonNull
    @Override
    public GroupViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_group, parent, false);
        final GroupViewHolder holder = new GroupViewHolder(view);

        // 监听器只设置一次，避免每次绑定都新建 lambda（分组数虽少，但焦点切换会频繁重绑）
        view.setOnClickListener(v -> selectAndNotify(holder.getAdapterPosition()));
        view.setOnFocusChangeListener((v, hasFocus) -> {
            ChannelListAdapter.animateFocus(v, hasFocus);
            if (hasFocus) selectAndNotify(holder.getAdapterPosition());
        });
        return holder;
    }

    @Override
    public void onBindViewHolder(@NonNull GroupViewHolder holder, int position) {
        if (position < 0 || position >= groups.size()) return;
        ChannelGroup group = groups.get(position);
        if (group == null) return;
        holder.tvName.setText(group.getGroupName() != null ? group.getGroupName() : "");
        holder.tvCount.setText(String.valueOf(group.getChannelCount()));
        holder.itemView.setSelected(position == selectedIndex);
    }

    private void selectAndNotify(int position) {
        if (position < 0 || position >= groups.size()) return;
        setSelectedIndex(position);
        if (listener != null) listener.onGroupSelected(position, groups.get(position));
    }

    @Override
    public int getItemCount() {
        return groups.size();
    }

    static class GroupViewHolder extends RecyclerView.ViewHolder {
        final TextView tvName;
        final TextView tvCount;

        GroupViewHolder(@NonNull View itemView) {
            super(itemView);
            tvName = itemView.findViewById(R.id.tv_group_name);
            tvCount = itemView.findViewById(R.id.tv_group_count);
        }
    }
}
