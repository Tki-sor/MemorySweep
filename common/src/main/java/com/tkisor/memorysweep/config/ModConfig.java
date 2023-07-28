package com.tkisor.memorysweep.config;

import com.tkisor.memorysweep.MemorySweep;
import me.shedaniel.autoconfig.AutoConfig;
import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.annotation.Config;
import me.shedaniel.cloth.clothconfig.shadowed.blue.endless.jankson.Comment;

@Config(name = MemorySweep.MOD_ID)
public class ModConfig implements ConfigData {
    public static ModConfig get() {
        return AutoConfig.getConfigHolder(ModConfig.class).getConfig();
    }

    public void save() {
        AutoConfig.getConfigHolder(ModConfig.class).save();
    }
    @Comment("Whether to enable MemorySweep.")
    public boolean memorySweep = true;

    @Comment("""

            Base Config.
            """)
    public BaseCfg baseCfg = new BaseCfg();

    public static class BaseCfg {
        /**
         * 是否启用智能GC<p>
         * 如果启用，在内存清理时，会根据各种条件判断是否需要进行GC<p>
         * 否则，只要达到任意的内存清理条件，就会进行GC<p>
         */
        @Comment("""

                Whether to enable smart GC.
                If enabled, when cleaning memory, it will judge whether to perform GC according to various conditions.
                Otherwise, as long as any memory cleaning condition is reached, GC will be performed.
                """)
        public Boolean smartGC = true;
        /**
         * 是否启用自动内存清理<p>
         */
        @Comment("""

                Whether to enable auto memory cleaning.
                """)
        public Boolean autoSweep = true;
        /**
         * 内存清理间隔时间
         * 单位：秒
         */
        @Comment("""

                The interval time of memory cleaning.
                Unit: seconds.
                """)
        public int sweepInterval = 600;
        /**
         * 触发内存清理的最小内存占用率
         * 根据十秒内的平均值计算
         * 单位：百分比
         */
        @Comment("""

                The minimum memory occupancy rate that triggers memory cleaning.
                Calculated based on the average value in ten seconds.
                Unit: percentage.
                """)
        public int minMemoryUsage = 75;
        /**
         * 静默进行内存清理
         * 这将不会显示内存清理信息
         * 但仍会在日志中显示
         */
        @Comment("""

                Silent memory cleaning.
                This will not display memory cleaning information.
                But it will still be displayed in the log.
                """)
        public Boolean silent = false;
    }

}
