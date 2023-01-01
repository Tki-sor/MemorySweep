package com.tkisor.memorysweep;

import net.minecraft.network.chat.TranslatableComponent;
import net.minecraft.world.entity.Entity;

public class CleanerThread implements Runnable {
    public Entity player = null;

    public CleanerThread(Entity player) {
        this.player = player;
    }

    @Override
    public void run() {
        if(player != null) {
            player.sendMessage(new TranslatableComponent(
                            MemorySweep.MODID + ".gc.start"), player.getUUID());
        }
        System.gc();
        try {
            Thread.sleep(1200L);
        } catch (InterruptedException ignored) {
        }
        System.gc();
        if(player != null) {
            player.sendMessage(new TranslatableComponent(
                            MemorySweep.MODID + ".gc.end"), player.getUUID());
        }
    }
}
