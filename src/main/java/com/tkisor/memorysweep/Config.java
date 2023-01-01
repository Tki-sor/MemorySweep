package com.tkisor.memorysweep;

import net.minecraftforge.common.ForgeConfigSpec;

public class Config {
    public static ForgeConfigSpec COMMON_CONFIG;
    public static ForgeConfigSpec.IntValue MEMORY_SWEEP_TIME;

    static {
        ForgeConfigSpec.Builder COMMON_BUILDER = new ForgeConfigSpec.Builder();
        COMMON_BUILDER.comment("General settings").push("general");
        MEMORY_SWEEP_TIME = COMMON_BUILDER.comment("Set memory sweep time.The unit is minutes.")
                .defineInRange("memory sweep time", 15, 0, Integer.MAX_VALUE);
        COMMON_BUILDER.pop();
        COMMON_CONFIG = COMMON_BUILDER.build();
    }
}
