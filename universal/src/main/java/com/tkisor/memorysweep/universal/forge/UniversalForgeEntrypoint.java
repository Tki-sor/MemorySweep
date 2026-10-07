package com.tkisor.memorysweep.universal.forge;

import com.tkisor.memorysweep.universal.NativeConfiguration;
import com.tkisor.memorysweep.universal.UniversalRuntime;
import net.minecraftforge.fml.common.Mod;

@Mod("memorysweep")
public final class UniversalForgeEntrypoint {
    public UniversalForgeEntrypoint() {
        NativeConfiguration.register("forge");
        UniversalRuntime.start();
    }
}
