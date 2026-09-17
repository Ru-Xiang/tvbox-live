package com.github.tvbox.osc.service;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.github.tvbox.osc.api.ChannelManager;
import com.github.tvbox.osc.bean.LiveChannel;
import com.github.tvbox.osc.bean.SpeedTestResult;
import com.github.tvbox.osc.util.HawkConfig;
import com.orhanobut.hawk.Hawk;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import timber.log.Timber;

/**
 * 定时测速 Worker
 * 
 * 通过 WorkManager 调度，在后台定期执行直播源测速任务
 * 支持网络状态约束，仅在有网络连接时执行
 */
public class SpeedTestWorker extends Worker {

    public SpeedTestWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        Timber.i("定时测速 Worker 开始执行");

        try {
            // 检查是否启用测速
            boolean enabled = Hawk.get(HawkConfig.SPEED_TEST_ENABLED, true);
            if (!enabled) {
                Timber.d("测速已禁用，跳过");
                return Result.success();
            }

            // 获取频道管理器
            ChannelManager channelManager = ChannelManager.getInstance(getApplicationContext());

            // 同步执行：先重新拉取全量源（自定义源 + 远程订阅源），再对全部线路测速
            CountDownLatch latch = new CountDownLatch(1);
            AtomicBoolean success = new AtomicBoolean(false);

            // reloadForSpeedTest 会联网重新拉取并合并/过滤，回调返回待测的全量频道
            channelManager.reloadForSpeedTest(channels -> {
                if (channels == null || channels.isEmpty()) {
                    Timber.d("重新拉取后无频道数据，跳过测速");
                    latch.countDown();
                    return;
                }
                SpeedTestEngine.getInstance().testChannels(new ArrayList<>(channels),
                        new SpeedTestEngine.SpeedTestCallback() {
                            @Override
                            public void onItemComplete(SpeedTestResult result, int current, int total) {
                                // 后台运行，不需要 UI 更新
                            }

                            @Override
                            public void onAllComplete(List<SpeedTestResult> results, long totalTime) {
                                int available = 0;
                                for (SpeedTestResult r : results) {
                                    if (r.isAvailable()) available++;
                                }
                                Timber.i("定时测速完成: %d/%d 可用, 耗时 %d ms",
                                        available, results.size(), totalTime);
                                // 测速完成后：生成最佳线路本地播放列表并持久化
                                try {
                                    channelManager.persistAfterSpeedTest();
                                } catch (Exception e) {
                                    Timber.w(e, "定时测速后持久化本地播放列表失败");
                                }
                                success.set(true);
                                latch.countDown();
                            }

                            @Override
                            public void onError(String errorMsg) {
                                Timber.e("定时测速失败: %s", errorMsg);
                                latch.countDown();
                            }

                            @Override
                            public void onProgress(int current, int total, String currentChannel) {
                                // 后台运行，不需要 UI 更新
                            }

                            @Override
                            public void onCancelled(int completed, int total) {
                                Timber.w("定时测速被取消: %d/%d", completed, total);
                                latch.countDown();
                            }
                        });
            });

            // 等待完成，最多30分钟
            boolean finished = latch.await(30, TimeUnit.MINUTES);

            if (!finished) {
                Timber.w("定时测速超时");
                SpeedTestEngine.getInstance().cancel();
                return Result.retry();
            }

            return success.get() ? Result.success() : Result.retry();

        } catch (Exception e) {
            Timber.e(e, "定时测速 Worker 异常");
            return Result.failure();
        }
    }
}
