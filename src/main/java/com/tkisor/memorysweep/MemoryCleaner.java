package com.tkisor.memorysweep;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;


@Mod.EventBusSubscriber
public class MemoryCleaner {

    public static long cleanTime = 0;

    @SubscribeEvent
    public static void registerCommand(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> command = event.getDispatcher();
        command.register(Commands.literal("memorysweep").executes(
                (CommandContext<CommandSourceStack> memorysweep) -> {
                    Entity entity = memorysweep.getSource().getEntity();

                    new CleanerThread(entity).run();
                    return 0;
                })
        );
    }

    @SubscribeEvent
    public static void serverTickEvent(TickEvent.ServerTickEvent event) {
        if (Config.MEMORY_SWEEP_TIME.get() > 0) {
            if ((System.currentTimeMillis() - cleanTime) > (long) Config.MEMORY_SWEEP_TIME.get() * 60 * 1000) {
                cleanTime = System.currentTimeMillis();
                LocalPlayer player = Minecraft.getInstance().player;
                if (player != null) {
                    new CleanerThread(player).run();
                }
            }
        }
    }
}
