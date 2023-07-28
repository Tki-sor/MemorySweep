package com.tkisor.memorysweep.task;

import java.util.TimerTask;
import java.util.Timer;
import java.util.concurrent.CountDownLatch;

public class MemoryUsageTask extends TimerTask {
    private long totalMemory;
    private long maxMemory;
    private double averageUsage;
    private int count;
    private final CountDownLatch latch;

    public MemoryUsageTask(CountDownLatch latch) {
        totalMemory = 0;
        maxMemory = 0;
        averageUsage = 0;
        count = 0;
        this.latch = latch; // 初始化CountDownLatch对象
    }

    public double getAverageUsage() {
        return averageUsage;
    }

    @Override
    public void run() {
        long usedMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        totalMemory += usedMemory;
        maxMemory += Runtime.getRuntime().maxMemory();
        count++;

        if (count >= 10) {
            averageUsage = (double) totalMemory / (double) maxMemory;
            this.cancel(); // 取消任务
            latch.countDown(); // 通知主线程任务已完成
        }
    }
}