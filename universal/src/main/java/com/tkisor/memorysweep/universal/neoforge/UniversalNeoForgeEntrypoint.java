package com.tkisor.memorysweep.universal.neoforge;

import com.tkisor.memorysweep.universal.NativeConfiguration;
import com.tkisor.memorysweep.universal.UniversalRuntime;
import net.neoforged.fml.common.Mod;

@Mod("memorysweep")
public final class UniversalNeoForgeEntrypoint {
    public UniversalNeoForgeEntrypoint() {
        NativeConfiguration.register("neoforge");
        UniversalRuntime.start();
    }
}
