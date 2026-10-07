package com.tkisor.memorysweep.universal.mixin;

import com.tkisor.memorysweep.universal.UniversalRuntime;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.server.MinecraftServer", remap = false)
public final class UniversalServerMixin {
    @Inject(method = {"tickServer", "method_3748", "m_5705_", "func_71217_p", "tick"}, at = @At("HEAD"), require = 0, remap = false)
    private void memorysweep$serverTick(CallbackInfo callbackInfo) {
        UniversalRuntime.serverTick();
    }
}
