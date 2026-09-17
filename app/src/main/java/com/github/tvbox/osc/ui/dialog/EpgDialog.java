package com.github.tvbox.osc.ui.dialog;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.EpgService;
import com.github.tvbox.osc.bean.EpgProgram;
import com.github.tvbox.osc.bean.LiveChannel;
import com.github.tvbox.osc.ui.adapter.EpgListAdapter;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import timber.log.Timber;

/**
 * EPG 节目单弹窗
 *
 * 功能：
 * - 顶部：频道台标 + 频道名 + 分组
 * - 中部：日期切换栏（← 昨天 · 今天/日期 · 明天 →）
 * - 主体：节目列表 RecyclerView（正在播节目高亮 + 进度条 + 徽标）
 * - 遥控器：左右键切换日期，上下键浏览节目，返回键关闭
 *
 * 日期范围：今天前后 3 天。首次打开会滚动到当前节目位置。
 */
public class EpgDialog {

    /** 日期偏移范围：[-3, +3] 天 */
    private static final int MIN_OFFSET = -3;
    private static final int MAX_OFFSET = 3;

    /** 台标解码尺寸上限（px） */
    private static final int LOGO_SIZE_PX = 128;

    private final Activity activity;
    private final EpgService epgService;

    private AlertDialog dialog;
    private LiveChannel channel;
    private int dayOffset = 0; // 0=今天, -1=昨天, 1=明天...

    // UI 引用
    private ImageView ivLogo;
    private TextView tvChannel;
    private TextView tvGroup;
    private TextView tvDate;
    private View btnPrev;
    private View btnNext;
    private RecyclerView rvList;
    private ProgressBar pbLoading;
    private TextView tvEmpty;
    private EpgListAdapter adapter;

    public EpgDialog(Activity activity) {
        this.activity = activity;
        this.epgService = EpgService.getInstance();
    }

    /** 显示指定频道的节目单弹窗 */
    public void show(LiveChannel channel) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        if (channel == null) return;
        // 先关闭上一个弹窗，避免连续触发时旧窗口无引用可关造成 WindowLeaked
        dismiss();
        this.channel = channel;
        this.dayOffset = 0;

        try {
            // inflate 与视图绑定同样纳入异常兜底（InflateException / OOM 等）
            View view = LayoutInflater.from(activity).inflate(R.layout.dialog_epg, null);
            bindViews(view);
            bindHeader(channel);
            bindDateBar();
            setupList();
            setupKeyHandler();

            dialog = new AlertDialog.Builder(activity)
                    .setView(view)
                    .setNegativeButton("关闭", null)
                    .create();
            dialog.setOnKeyListener((d, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                return handleKey(keyCode);
            });
            dialog.show();
        } catch (Throwable e) {
            Timber.w(e, "显示 EPG 弹窗失败");
            dialog = null;
            return;
        }

        // 首次加载今天
        loadDay(0);
    }

    /** 关闭并释放资源（Activity 销毁时调用） */
    public void dismiss() {
        try {
            if (dialog != null && dialog.isShowing()) dialog.dismiss();
        } catch (Throwable ignore) {}
        dialog = null;
        // 清理 RecyclerView 的待执行任务与适配器数据：
        // loadDay 里用 rvList.post 提交过滚动任务，弹窗关闭后这些任务仍会
        // 持有 View 与节目列表；同时节目列表本身可能有数百条，及时释放
        try {
            if (rvList != null) {
                android.os.Handler h = rvList.getHandler();
                if (h != null) h.removeCallbacksAndMessages(null);
                rvList.setAdapter(null);
            }
        } catch (Throwable ignore) {}
        try {
            if (adapter != null) adapter.setData(null, -1, 0);
        } catch (Throwable ignore) {}
        // 断开对频道与视图的引用，避免弹窗对象被 Activity 长期持有时连带滞留
        channel = null;
        rvList = null;
        ivLogo = null;
        adapter = null;
    }

    // ============ 私有方法 ============

    private void bindViews(View root) {
        ivLogo = root.findViewById(R.id.iv_epg_logo);
        tvChannel = root.findViewById(R.id.tv_epg_channel);
        tvGroup = root.findViewById(R.id.tv_epg_group);
        tvDate = root.findViewById(R.id.tv_epg_date);
        btnPrev = root.findViewById(R.id.btn_epg_prev_day);
        btnNext = root.findViewById(R.id.btn_epg_next_day);
        rvList = root.findViewById(R.id.rv_epg_list);
        pbLoading = root.findViewById(R.id.pb_epg_loading);
        tvEmpty = root.findViewById(R.id.tv_epg_empty);
    }

    private void bindHeader(LiveChannel channel) {
        if (tvChannel != null) tvChannel.setText(channel.getChannelName());
        if (tvGroup != null) tvGroup.setText(channel.getGroupName());
        if (ivLogo != null) {
            try {
                if (!TextUtils.isEmpty(channel.getLogoUrl()) && !activity.isFinishing()) {
                    // 限制解码尺寸 + RGB_565，避免大尺寸台标按原图解码占用过多位图内存
                    Glide.with(activity).load(channel.getLogoUrl())
                            .override(LOGO_SIZE_PX, LOGO_SIZE_PX)
                            .format(com.bumptech.glide.load.DecodeFormat.PREFER_RGB_565)
                            .placeholder(R.drawable.ic_launcher)
                            .error(R.drawable.ic_launcher)
                            .into(ivLogo);
                } else {
                    ivLogo.setImageResource(R.drawable.ic_launcher);
                }
            } catch (Exception ignore) {}
        }
    }

    private void bindDateBar() {
        if (btnPrev != null) btnPrev.setOnClickListener(v -> switchDay(-1));
        if (btnNext != null) btnNext.setOnClickListener(v -> switchDay(1));
    }

    private void setupList() {
        adapter = new EpgListAdapter();
        if (rvList != null) {
            rvList.setLayoutManager(new LinearLayoutManager(activity));
            rvList.setAdapter(adapter);
        }
    }

    private void setupKeyHandler() {
        // 由 AlertDialog.setOnKeyListener 统一处理
    }

    /** 处理遥控器按键 */
    private boolean handleKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                switchDay(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                switchDay(1);
                return true;
            default:
                return false;
        }
    }

    /** 切换到相对当前日期偏移的一天 */
    private void switchDay(int delta) {
        int target = dayOffset + delta;
        if (target < MIN_OFFSET) {
            toast("已经是最早可查询的日期");
            return;
        }
        if (target > MAX_OFFSET) {
            toast("已经是最晚可查询的日期");
            return;
        }
        loadDay(target);
    }

    /** 加载指定日期偏移的节目单 */
    private void loadDay(int offset) {
        this.dayOffset = offset;
        updateDateLabel();
        showLoading(true);
        updateEmpty(false, null);

        final String date = EpgService.offsetDate(offset);
        final LiveChannel target = this.channel;
        if (target == null) {
            showLoading(false);
            updateEmpty(true, "无频道");
            return;
        }

        epgService.loadEpg(target.getChannelName(), date, new EpgService.EpgCallback() {
            @Override
            public void onEpgLoaded(List<EpgProgram> programs, EpgProgram current) {
                activity.runOnUiThread(() -> {
                    if (!isAlive()) return;
                    // 弹窗可能已关闭（dismiss 会置空这些字段），避免 NPE
                    if (adapter == null || dialog == null) return;
                    // 只处理仍是当前查看的日期与频道
                    if (dayOffset != offset || channel != target) return;
                    showLoading(false);
                    if (programs == null || programs.isEmpty()) {
                        updateEmpty(true, "该日无节目单");
                        adapter.setData(null, -1, 0);
                        return;
                    }
                    int currentIdx = -1;
                    int currentProgress = 0;
                    if (current != null) {
                        currentIdx = programs.indexOf(current);
                        String nowHm = new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date());
                        int p = current.progressAt(nowHm);
                        if (p >= 0 && p <= 100) currentProgress = p;
                    }
                    adapter.setData(programs, currentIdx, currentProgress);
                    updateEmpty(false, null);

                    // 滚动到当前节目
                    if (currentIdx >= 0 && rvList != null) {
                        rvList.post(() -> {
                            try {
                                RecyclerView.LayoutManager lm = rvList.getLayoutManager();
                                if (lm instanceof LinearLayoutManager) {
                                    ((LinearLayoutManager) lm).scrollToPositionWithOffset(
                                            Math.max(0, adapter.getCurrentIndex() - 1), 0);
                                }
                            } catch (Exception ignore) {}
                        });
                    } else if (rvList != null) {
                        rvList.scrollToPosition(0);
                    }
                });
            }

            @Override
            public void onEpgError(String msg) {
                activity.runOnUiThread(() -> {
                    if (!isAlive()) return;
                    if (adapter == null || dialog == null) return;
                    if (dayOffset != offset || channel != target) return;
                    showLoading(false);
                    adapter.setData(null, -1, 0);
                    updateEmpty(true, msg != null ? msg : "获取失败");
                });
            }
        });
    }

    private void updateDateLabel() {
        if (tvDate == null) return;
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_YEAR, dayOffset);
        String md = new SimpleDateFormat("M 月 d 日", Locale.CHINA).format(c.getTime());
        String weekLabel = weekdayLabel(dayOffset);
        tvDate.setText(md + " · " + weekLabel);
    }

    private String weekdayLabel(int offset) {
        if (offset == 0) return "今天";
        if (offset == -1) return "昨天";
        if (offset == 1) return "明天";
        if (offset == -2) return "前天";
        if (offset == 2) return "后天";
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_YEAR, offset);
        int w = c.get(Calendar.DAY_OF_WEEK);
        String[] cn = {"日", "一", "二", "三", "四", "五", "六"};
        return "周" + cn[Math.max(0, Math.min(6, w - 1))];
    }

    private void showLoading(boolean show) {
        if (pbLoading != null) pbLoading.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show && rvList != null) rvList.setVisibility(View.VISIBLE);
    }

    private void updateEmpty(boolean show, String text) {
        if (tvEmpty != null) {
            tvEmpty.setVisibility(show ? View.VISIBLE : View.GONE);
            if (show && text != null) tvEmpty.setText(text);
        }
        if (rvList != null) rvList.setVisibility(show ? View.INVISIBLE : View.VISIBLE);
    }

    private void toast(String msg) {
        try {
            com.github.tvbox.osc.util.ToastUtil.show(activity, msg);
        } catch (Exception ignore) {}
    }

    private boolean isAlive() {
        return activity != null && !activity.isFinishing() && !activity.isDestroyed();
    }
}
