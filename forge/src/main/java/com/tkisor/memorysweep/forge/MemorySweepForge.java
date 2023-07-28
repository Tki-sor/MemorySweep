package com.tkisor.memorysweep.forge;

import com.mojang.logging.LogUtils;
import com.tkisor.memorysweep.MemorySweep;
import com.tkisor.memorysweep.config.ModConfig;
import com.tkisor.memorysweep.thread.SmartGCThread;
import com.tkisor.memorysweep.util.GCUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLDedicatedServerSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

import java.util.Timer;
import java.util.TimerTask;

@Mod(MemorySweep.MOD_ID)
public class MemorySweepForge {
    public MemorySweepForge() {
        MemorySweep.init();
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        modEventBus.addListener(ForgeEvent::serverSetupEvent);
        modEventBus.addListener(ForgeEvent::clientSetupEvent);
    }
    public static class ForgeEvent {
        public static ServerPlayer player = null;
        public static Logger LOGGER = LogUtils.getLogger();
        public static String platform = "";
        public static void serverSetupEvent(FMLDedicatedServerSetupEvent event) {
            platform = "server";
            taskGC();
        }

        public static void clientSetupEvent(FMLClientSetupEvent event) {
            platform = "client";
            taskGC();
        }

        private static void taskGC() {
            if (!ModConfig.get().memorySweep) return;
            if (ModConfig.get().baseCfg.sweepInterval <= 0) return;
            if (!ModConfig.get().baseCfg.autoSweep) return;

            Timer timer = new Timer();
            TimerTask task = new TimerTask() {
                @Override
                public void run() {
                    smartGC();
                }
            };

            timer.schedule(task, 0, ModConfig.get().baseCfg.sweepInterval * 1000L);
        }

        private static void smartGC() {
            if (player != null) {
                new SmartGCThread() {
                    @Override
                    public void onStarted(GCUtil gcUtil) {
                        player.sendSystemMessage(Component.literal(gcUtil.getSmartGCStartText()), true);
                    }

                    @Override
                    public void onSuccess(GCUtil gcUtil) {
                        LOGGER.info("MemorySweep: OnSuccess smartGC for " + platform);
                        if (ModConfig.get().baseCfg.silent) return;
                        player.sendSystemMessage(Component.literal(gcUtil.getSmartGCEndText()), true);
                    }

                    @Override
                    public void onFailed(GCUtil gcUtil) {
                        LOGGER.info("MemorySweep: OnFailed smartGC for " + platform);
                        if (ModConfig.get().baseCfg.silent) return;
                        player.sendSystemMessage(Component.literal(gcUtil.getSmartGCFailedText()), true);
                    }
                }.start();
            } else {
                new SmartGCThread() {
                    @Override
                    public void onStarted(GCUtil gcUtil) {
                    }

                    @Override
                    public void onSuccess(GCUtil gcUtil) {
                        LOGGER.info("MemorySweep: OnSuccess smartGC for " + platform);
                    }

                    @Override
                    public void onFailed(GCUtil gcUtil) {
                        LOGGER.info("MemorySweep: OnFailed smartGC for " + platform);
                    }
                }.start();
            }
        }
    }
}