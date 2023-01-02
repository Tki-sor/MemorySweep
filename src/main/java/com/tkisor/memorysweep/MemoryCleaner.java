package com.tkisor.memorysweep;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.logging.LogUtils;
import net.minecraft.Util;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.TranslatableComponent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;


@Mod.EventBusSubscriber
public class MemoryCleaner {

    public static long cleanTime = 0;
    public static long usageTime = 0;

    @SubscribeEvent
    public static void registerCommand(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> command = event.getDispatcher();
        command.register(Commands.literal("memorysweep").executes((CommandContext<CommandSourceStack> memorysweep) -> {
            new Thread(() -> {
                memorysweep.getSource().sendSuccess(new TranslatableComponent(MemorySweep.MODID + ".gc.start"), false);
                LogUtils.getLogger().info("Memory Sweep start.");
                System.gc();
                try {
                    Thread.sleep(1200L);
                } catch (InterruptedException ignored) {
                }
                System.gc();
                memorysweep.getSource().sendSuccess(new TranslatableComponent(MemorySweep.MODID + ".gc.end"), false);
                LogUtils.getLogger().info("Memory Sweep end.");
            }).run();
            return 0;
        }));
    }

    @SubscribeEvent
    public static void serverTickEvent(TickEvent.ServerTickEvent event) {
        if (Config.MEMORY_SWEEP_TIME.get() > 0) {
            if (cleanTime == 0) {
                cleanTime = System.currentTimeMillis();
            }
            if ((System.currentTimeMillis() - cleanTime) > (long) Config.MEMORY_SWEEP_TIME.get() * 60 * 1000) {
                cleanTime = System.currentTimeMillis();
                new Thread(MemoryCleaner::memorycleaner).run();
            }
        }

        if (!(Config.MEMORY_USAGE.get() == 100 || Config.MEMORY_USAGE.get() == 0)) {
            Runtime runtime = Runtime.getRuntime();
            if ((System.currentTimeMillis() - usageTime) / 1000 > 120) {
                double memoryusage = (double) (runtime.totalMemory() - runtime.freeMemory()) / runtime.totalMemory();
                if (memoryusage > (double) Config.MEMORY_USAGE.get() / 100 && usageTime > 20 * 20 * 60) {
                    new Thread(MemoryCleaner::memorycleaner).run();
                    usageTime = System.currentTimeMillis();
                }
            }
        }
    }

    private static void memorycleaner() {
        ServerLifecycleHooks.getCurrentServer().getPlayerList().broadcastMessage(new TranslatableComponent(MemorySweep.MODID + ".gc.start"), ChatType.SYSTEM, Util.NIL_UUID);
        LogUtils.getLogger().info("Memory Sweep start.");
        System.gc();
        try {
            Thread.sleep(1200L);
        } catch (InterruptedException ignored) {
        }
        System.gc();
        ServerLifecycleHooks.getCurrentServer().getPlayerList().broadcastMessage(new TranslatableComponent(MemorySweep.MODID + ".gc.end"), ChatType.SYSTEM, Util.NIL_UUID);
        LogUtils.getLogger().info("Memory Sweep end.");
    }
}
