package com.tkisor.memorysweep;

import com.tkisor.memorysweep.config.ModConfig;
import me.shedaniel.autoconfig.AutoConfig;
import me.shedaniel.autoconfig.serializer.JanksonConfigSerializer;

public class MemorySweep {
    public static final String MOD_ID = "memorysweep";

    public static void init() {
        AutoConfig.register(ModConfig.class, JanksonConfigSerializer::new);
    }
}