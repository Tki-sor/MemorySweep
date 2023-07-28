package com.tkisor.memorysweep.util;

import com.tkisor.memorysweep.config.ModConfig;
import com.tkisor.memorysweep.task.MemoryUsageTask;
import net.minecraft.locale.Language;

import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.text.DecimalFormat;
import java.util.List;
import java.util.Timer;
import java.util.concurrent.CountDownLatch;

public class GCUtil {
    private final DecimalFormat df = new DecimalFormat("#.0");
    private final Language language = Language.getInstance();
    private final String smartGCStartText = language.getOrDefault("memorysweep.gc.start");
    private final String smartGCEndText = language.getOrDefault("memorysweep.gc.end");
    private String smartGCCauseText = "null";
    private final String smartGCFailedText = language.getOrDefault("memorysweep.gc.failed");
    private double averageUsage = -1;
    private boolean isGC = false;

    public GCUtil() {
    }

    public void gc() {
        System.gc();
        isGC = true;
    }

    /**
     * 智能GC判断逻辑：
     * 1. ZGC，不GC
     * 2. 总内存小于2G，直接GC
     * 2. 剩余内存小于0.5G，直接GC
     * 3. 系统内存使用率大于80%，直接GC
     */
    public void smartGC(CountDownLatch latch) {
        SystemInfo info = new SystemInfo();
        double usageFreeGB = info.getMemoryUsageFreeGB();
        double usageMaxGB = info.getMemoryUsageMaxGB();
        double usageUsedGB = info.getMemoryUsageUsedGB();
        double memoryUsage = info.getMemoryUsage();

        try {
            Thread.sleep(4000);
        } catch (Exception ignored) {
        }

        if (hasJvmParam("-XX:+UseZGC")) {
            smartGCCauseText = language.getOrDefault("memorysweep.gc.failed.cause.is-zgc");
            isGC = false;
            latch.countDown();
            return;
        }

//        if (usageMaxGB > 15.0) {
//            smartGCCauseText = "总内存大于15G";
//            isGC = false;
//            latch.countDown();
//            return;
//        }

        if (usageMaxGB < 2.0 || usageFreeGB < 0.5) {
            gc();
            latch.countDown();
            return;
        }

        if (memoryUsage > 0.9) {
            gc();
            latch.countDown();
            return;
        }

        var timer = new Timer();
        var task = new MemoryUsageTask(latch);
        timer.scheduleAtFixedRate(task, 0, 1000);

        try {
            latch.await(); // 等待任务完成
        } catch (InterruptedException e) {
            timer.cancel(); // 取消定时器
        }

        averageUsage = task.getAverageUsage(); // 获取平均占用内存
        if (averageUsage > ModConfig.get().baseCfg.minMemoryUsage/100.0) {
            gc();
        } else {
            smartGCCauseText = language.getOrDefault("memorysweep.gc.failed.cause.not-gc");
            isGC = false;
        }
        timer.cancel(); // 取消定时器
        latch.countDown(); // 通知主线程任务已完成
    }

    public String getSmartGCStartText() {
        return smartGCStartText.formatted(df.format(new SystemInfo().getMemoryUsageUsedGB()));
    }

    public String getSmartGCEndText() {
        return smartGCEndText.formatted(df.format(new SystemInfo().getMemoryUsageUsedGB()));
    }

    public String getSmartGCFailedText() {
        return smartGCFailedText.formatted(smartGCCauseText);
    }

    public boolean getIsGC() {
        return isGC;
    }

    public double getAverageUsage() {
        return averageUsage;
    }

    /**
     * 判断是否存在特定的JVM参数
     */
    public Boolean hasJvmParam(String jvmParam) {
        RuntimeMXBean runtimeMxBean = ManagementFactory.getRuntimeMXBean();
        List<String> inputArguments = runtimeMxBean.getInputArguments();
        for (String inputArgument : inputArguments) {
            if (inputArgument.contains(jvmParam)) {
                return true;
            }
        }
        return false;
    }
}
