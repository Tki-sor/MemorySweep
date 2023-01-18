package com.tkisor.memorysweep;

import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber
public class PlayerLoggedIn {
    public static boolean canClean = true;
    @SubscribeEvent
    public static void playerLoggedInEvent(PlayerEvent.PlayerLoggedInEvent event) {
        if (canClean && Config.PLAYER_JOIN_WORLD_SWEEP.get()) {
            new Thread(MemoryCleaner::memorycleaner).start();
            canClean = false;
        }
    }
}
