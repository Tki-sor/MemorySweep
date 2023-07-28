package com.tkisor.memorysweep.util;

import com.sun.management.OperatingSystemMXBean;

import java.lang.management.ManagementFactory;

public class SystemInfo {
    private final OperatingSystemMXBean osBean = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    private final long memoryUsageMax = osBean.getTotalMemorySize();
    private final long memoryUsageUsed = osBean.getTotalMemorySize() - osBean.getFreeMemorySize();
    private final long memoryUsageFree = osBean.getFreeMemorySize();


    public long getMemoryUsageMax() {
        return memoryUsageMax;
    }

    public long getMemoryUsageUsed() {
        return memoryUsageUsed;
    }

    public long getMemoryUsageFree() {
        return memoryUsageFree;
    }

    public double getMemoryUsageMaxGB() {
        return (double) memoryUsageMax / (double) (1024 * 1024 * 1024);
    }

    public double getMemoryUsageUsedGB() {
        return (double) memoryUsageUsed / (double) (1024 * 1024 * 1024);
    }

    public double getMemoryUsageFreeGB() {
        return (double) memoryUsageFree / (double) (1024 * 1024 * 1024);
    }

    public double getMemoryUsageMaxMB() {
        return (double) memoryUsageMax / (double) (1024 * 1024);
    }

    public double getMemoryUsageUsedMB() {
        return (double) memoryUsageUsed / (double) (1024 * 1024);
    }

    public double getMemoryUsageFreeMB() {
        return (double) memoryUsageFree / (double) (1024 * 1024);
    }

    // 内存使用率
    public double getMemoryUsage() {
        return (double) memoryUsageUsed / (double) memoryUsageMax;
    }

    public String formatMemorySize(long size) {
        long kb = size / 1024;
        if (kb < 1024) {
            return kb + "KB";
        } else if (kb < 1024 * 1024) {
            return String.format("%.2f", (double) kb / 1024) + "MB";
        } else {
            return String.format("%.2f", (double) kb / (1024 * 1024)) + "GB";
        }
    }
}
