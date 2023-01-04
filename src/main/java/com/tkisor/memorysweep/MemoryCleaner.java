package com.tkisor.memorysweep;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

@Mod.EventBusSubscriber
public class MemoryCleaner {

    public static long cleanTime = 0;
    public static long usageTime = 0;
    public static boolean canClean = false;

    @SubscribeEvent
    public static void registerCommand(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> command = event.getDispatcher();
        command.register(Commands.literal("memorysweep").executes((CommandContext<CommandSourceStack> memorysweep) -> {
            new Thread(() -> {
                memorysweep.getSource().sendSuccess(Component.translatable(MemorySweep.MODID + ".gc.start"), false);
                LogUtils.getLogger().info("Memory Sweep start.");
                System.gc();
                try {
                    Thread.sleep(1200L);
                } catch (InterruptedException ignored) {
                }
                System.gc();
                memorysweep.getSource().sendSuccess(Component.translatable(MemorySweep.MODID + ".gc.end"), false);
                LogUtils.getLogger().info("Memory Sweep end.");
            }).start();
            return 0;
        }));
    }

    @SubscribeEvent
    public static void serverTickEvent(TickEvent.ServerTickEvent event) {
        if (Config.MEMORY_SWEEP_TIME.get() > 0) {
            if (cleanTime == 0) {
                cleanTime = System.currentTimeMillis();
            }
            if((System.currentTimeMillis() - cleanTime) > (long) Config.MEMORY_SWEEP_TIME.get() * 60 * 1000) {
                canClean = true;
            }
        }

        if (!(Config.MEMORY_USAGE.get() == 100 || Config.MEMORY_USAGE.get() == 0)) {
            Runtime runtime = Runtime.getRuntime();
            if ((System.currentTimeMillis() - usageTime) > (long) 2 * 60 * 1000) {
                double memoryusage = (double) (runtime.totalMemory() - runtime.freeMemory()) / runtime.totalMemory();
                if (memoryusage > (double) Config.MEMORY_USAGE.get() / 100) {
                    canClean = true;
                }
            }
        }

        if (canClean) {
            new Thread(MemoryCleaner::memorycleaner).start();
            usageTime = System.currentTimeMillis();
            cleanTime = System.currentTimeMillis();
            canClean = false;
        }
    }

    private static void memorycleaner() {
        ServerLifecycleHooks.getCurrentServer().getPlayerList().broadcastSystemMessage(Component.translatable(MemorySweep.MODID + ".gc.start"), true);
        LogUtils.getLogger().info("Memory Sweep start.");
        System.gc();
        try {
            Thread.sleep(1200L);
        } catch (InterruptedException ignored) {
        }
        System.gc();
        ServerLifecycleHooks.getCurrentServer().getPlayerList().broadcastSystemMessage(Component.translatable(MemorySweep.MODID + ".gc.end"), true);
        LogUtils.getLogger().info("Memory Sweep end.");
    }
}
