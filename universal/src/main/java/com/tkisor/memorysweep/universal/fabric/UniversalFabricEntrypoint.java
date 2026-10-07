package com.tkisor.memorysweep.universal.fabric;

import com.tkisor.memorysweep.universal.UniversalRuntime;
import net.fabricmc.api.ModInitializer;

public final class UniversalFabricEntrypoint implements ModInitializer {
    @Override
    public void onInitialize() {
        UniversalRuntime.start();
    }
}
