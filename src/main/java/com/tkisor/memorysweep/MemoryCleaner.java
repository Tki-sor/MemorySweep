package com.tkisor.memorysweep;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.command.CommandSource;
import net.minecraft.command.Commands;
import net.minecraft.test.TestLogger;
import net.minecraft.util.Util;
import net.minecraft.util.text.ChatType;
import net.minecraft.util.text.TranslationTextComponent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.server.ServerLifecycleHooks;

@Mod.EventBusSubscriber
public class MemoryCleaner {

    public static long cleanTime = 0;
    public static long usageTime = 0;
    public static boolean canClean = false;

    @SubscribeEvent
    public static void registerCommand(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSource> command = event.getDispatcher();
        command.register(Commands.literal("memorysweep").executes((CommandContext<CommandSource> memorysweep) -> {
            new Thread(() -> {
                if (Config.COMMAND_TEST.get()) {
                    memorysweep.getSource().sendSuccess(new TranslationTextComponent(MemorySweep.MODID + ".gc.start"), false);
                }
                System.gc();
                try {
                    Thread.sleep(1200L);
                } catch (InterruptedException ignored) {
                }
                System.gc();
                if (Config.COMMAND_TEST.get()) {
                    memorysweep.getSource().sendSuccess(new TranslationTextComponent(MemorySweep.MODID + ".gc.end"), false);
                }
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
            if ((System.currentTimeMillis() - cleanTime) > (long) Config.MEMORY_SWEEP_TIME.get() * 60 * 1000) {
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
        if (Config.AUTOMATIC_MEMORY_CLEANER_TEST.get()) {
            ServerLifecycleHooks.getCurrentServer().getPlayerList().broadcastMessage(new TranslationTextComponent(MemorySweep.MODID + ".gc.start"), ChatType.SYSTEM, Util.NIL_UUID);
        }
        System.gc();
        try {
            Thread.sleep(1200L);
        } catch (InterruptedException ignored) {
        }
        System.gc();
        if (Config.AUTOMATIC_MEMORY_CLEANER_TEST.get()) {
            ServerLifecycleHooks.getCurrentServer().getPlayerList().broadcastMessage(new TranslationTextComponent(MemorySweep.MODID + ".gc.end"), ChatType.SYSTEM, Util.NIL_UUID);
        }
    }
}
