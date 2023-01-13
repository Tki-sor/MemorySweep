package com.tkisor.memorysweep;

import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;

@Mod(MemorySweep.MODID)
public class MemorySweep {
    public static final String MODID = "memorysweep";
    public MemorySweep() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, Config.COMMON_CONFIG);
    }
}
