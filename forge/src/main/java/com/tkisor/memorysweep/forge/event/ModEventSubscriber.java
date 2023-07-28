package com.tkisor.memorysweep.forge.event;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.logging.LogUtils;
import com.tkisor.memorysweep.MemorySweep;
import com.tkisor.memorysweep.config.ModConfig;
import com.tkisor.memorysweep.forge.MemorySweepForge;
import com.tkisor.memorysweep.util.GCUtil;
import com.tkisor.memorysweep.thread.SmartGCThread;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLDedicatedServerSetupEvent;

import java.io.File;

public class ModEventSubscriber {
    @Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.FORGE, modid = MemorySweep.MOD_ID, value = Dist.CLIENT)
    public static class ClientEvent {
        public static boolean canClean = true;

        @SubscribeEvent
        public static void playerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
            var player = event.getEntity();
            var serverPlayer = (ServerPlayer) player;

            MemorySweepForge.ForgeEvent.player = serverPlayer;
            if (!ModConfig.get().memorySweep) return;

            if (!canClean) return;
            new SmartGCThread() {
                @Override
                public void onStarted(GCUtil gcUtil) {
                    if (ModConfig.get().baseCfg.silent) return;
                    serverPlayer.sendSystemMessage(Component.literal(gcUtil.getSmartGCStartText()), true);
                }

                @Override
                public void onSuccess(GCUtil gcUtil) {
                    LogUtils.getLogger().info("MemorySweep: OnSuccess smartGC for playerLoggedIn.");
                    if (ModConfig.get().baseCfg.silent) return;
                    serverPlayer.sendSystemMessage(Component.literal(gcUtil.getSmartGCEndText()), true);
                }

                @Override
                public void onFailed(GCUtil gcUtil) {
                    LogUtils.getLogger().info("MemorySweep: OnFailed smartGC for playerLoggedIn.");
                    if (ModConfig.get().baseCfg.silent) return;
                    serverPlayer.sendSystemMessage(Component.literal(gcUtil.getSmartGCFailedText()), true);
                }
            }.start();
            canClean = false;
        }

        // 玩家退出世界
        @SubscribeEvent
        public static void playerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
            MemorySweepForge.ForgeEvent.player = null;
        }

        @SubscribeEvent
        public static void registerCommand(RegisterCommandsEvent event) {
            CommandDispatcher<CommandSourceStack> command = event.getDispatcher();
            command.register(Commands.literal("memorysweep").executes((CommandContext<CommandSourceStack> memorysweep) -> {
                return 0;
            }));
        }
    }
}
