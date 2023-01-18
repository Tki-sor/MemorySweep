package com.tkisor.memorysweep;

import net.minecraftforge.common.ForgeConfigSpec;

public class Config {
    public static ForgeConfigSpec COMMON_CONFIG;
    public static ForgeConfigSpec.IntValue MEMORY_SWEEP_TIME;
    public static ForgeConfigSpec.IntValue MEMORY_USAGE;
    public static ForgeConfigSpec.IntValue MEMORY_USAGE_TIME;
    public static ForgeConfigSpec.BooleanValue PLAYER_JOIN_WORLD_SWEEP;
    public static ForgeConfigSpec.BooleanValue COMMAND_TEST;
    public static ForgeConfigSpec.BooleanValue AUTOMATIC_MEMORY_CLEANER_TEST;

    static {
        ForgeConfigSpec.Builder COMMON_BUILDER = new ForgeConfigSpec.Builder();
        COMMON_BUILDER.comment("General settings").push("general");
        MEMORY_SWEEP_TIME = COMMON_BUILDER.comment("Set memory sweep time.The unit is minutes.If the value is 0, it will be disabled.")
                .defineInRange("memory sweep time", 15, 0, Integer.MAX_VALUE);
        MEMORY_USAGE = COMMON_BUILDER.comment("Cleaner memory according to the memory usage of the JVM.If the value is 0 or 100, it will be disabled.",
                        "Clean the memory according to the memory usage rate at most once every 2 minutes.",
                        "If triggered frequently, it is still recommended to increase RAM or allocate more RAM.")
                .defineInRange("memory usage", 90, 0, 100);
        MEMORY_USAGE_TIME = COMMON_BUILDER.comment("Time between memory cleanups based on memory usage.",
                        "Please do not make it larger than memory sweep time")
                .defineInRange("memory usage time", 2, 2, Integer.MAX_VALUE);
        PLAYER_JOIN_WORLD_SWEEP = COMMON_BUILDER.comment("Clean memory when players join the world.",
                        "Only the first player is valid.")
                .define("player join world sweep", true);
        COMMAND_TEST = COMMON_BUILDER.comment("Control command send text.")
                .define("command test", true);
        AUTOMATIC_MEMORY_CLEANER_TEST = COMMON_BUILDER.comment("Control automatic memory cleaner send text.")
                .define("automatic memory cleaner test", true);
        COMMON_BUILDER.pop();
        COMMON_CONFIG = COMMON_BUILDER.build();
    }
}
