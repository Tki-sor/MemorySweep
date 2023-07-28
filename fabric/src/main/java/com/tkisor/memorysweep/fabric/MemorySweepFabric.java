package com.tkisor.memorysweep.fabric;

import com.tkisor.memorysweep.MemorySweep;
import net.fabricmc.api.ModInitializer;

public class MemorySweepFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        MemorySweep.init();
    }
}