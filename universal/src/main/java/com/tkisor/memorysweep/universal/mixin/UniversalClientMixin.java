package com.tkisor.memorysweep.universal.mixin;

import com.tkisor.memorysweep.universal.UniversalRuntime;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.client.Minecraft")
public final class UniversalClientMixin {
    @Inject(method = {"runTick", "tick", "gameLoop", "run"}, at = @At("HEAD"), require = 0)
    private void memorysweep$clientTick(CallbackInfo callbackInfo) {
        UniversalRuntime.clientTick();
    }
}
