package com.sandymandy.strut.armour;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.text.Text;
import net.minecraft.util.hit.EntityHitResult;

/**
 * {@code /strutarmour}: says what armour the player, or the entity they are looking at, has resolved to: the wearer profile and set, and for each slot the
 * item, the look that matched, the model, the texture and what it hides. A tool for the author of a set, to see why a piece looks as it does or is not there
 */
public final class ArmourCommand {
	private ArmourCommand() {}

	public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
		dispatcher.register(ClientCommandManager.literal("strutarmour").executes(context -> {
			MinecraftClient client = MinecraftClient.getInstance();
			Entity target = client.player;

			if (client.crosshairTarget instanceof EntityHitResult hit)
				target = hit.getEntity();

			if (target == null)
				return 0;

			context.getSource().sendFeedback(Text.literal(target.getName().getString() + ":"));

			for (String line : ArmourClient.describe(target))
				context.getSource().sendFeedback(Text.literal(line));

			return 1;
		}));
	}
}
