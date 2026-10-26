package com.sandymandy.strut.animation.attachment;

import com.mojang.brigadier.CommandDispatcher;
import com.sandymandy.strut.cache.StrutCache;
import com.sandymandy.strut.player.StrutPlayers;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.command.argument.IdentifierArgumentType;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * {@code /strutwear <model> <texture>}: a development tool that puts a Strut model on you as a {@link ModelAttachment} in the slot {@code dev}
 * <ul>
 * <li>{@code /strutwear <model> <texture>}: wear it (the model's id as the game loads it, such as {@code strut:strut/models/entity/test_dummy.strutmodel})</li>
 * <li>{@code /strutwear status}: whether you are drawn by Strut at all, and why each worn model was or was not drawn the last frame</li>
 * <li>{@code /strutwear clear}: take it off</li>
 * </ul>
 * Only a player drawn by Strut can wear a model: one with a body ({@code StrutPlayers}), since the vanilla player model is not a Strut model
 */
public final class WearCommand {
	private static final String SLOT = "dev";

	private WearCommand() {}

	public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
		dispatcher.register(ClientCommandManager.literal("strutwear")
				.then(ClientCommandManager.literal("clear").executes(context -> {
					ClientPlayerEntity player = MinecraftClient.getInstance().player;

					if (player != null)
						ClientModelAttachments.clear(player, SLOT);

					context.getSource().sendFeedback(Text.literal("Took it off"));

					return 1;
				}))
				.then(ClientCommandManager.literal("status").executes(context -> {
					status(context.getSource());

					return 1;
				}))
				.then(ClientCommandManager.argument("model", IdentifierArgumentType.identifier())
						.then(ClientCommandManager.argument("texture", IdentifierArgumentType.identifier()).executes(context -> {
							ClientPlayerEntity player = MinecraftClient.getInstance().player;

							if (player == null)
								return 0;

							Identifier model = context.getArgument("model", Identifier.class);
							Identifier texture = context.getArgument("texture", Identifier.class);

							ClientModelAttachments.set(player, SLOT, model, texture).tint(0xFFFF4040);
							context.getSource().sendFeedback(Text.literal("Wearing " + model + " in red; /strutwear status says whether it is drawn"));
							status(context.getSource());

							return 1;
						}))));
	}

	private static void status(FabricClientCommandSource source) {
		ClientPlayerEntity player = MinecraftClient.getInstance().player;

		if (player == null)
			return;

		if (StrutPlayers.body(player) == null)
			source.sendFeedback(Text.literal("You have no Strut body, so the vanilla player model is drawn and nothing can be worn (/strut body set, or a body from a mod)"));

		ModelAttachment worn = ClientModelAttachments.get(player, SLOT);

		if (worn == null) {
			source.sendFeedback(Text.literal("Nothing is worn"));

			return;
		}

		source.sendFeedback(Text.literal("Model loaded: " + StrutCache.getBakedModels().containsKey(worn.model()) + ". Last frame: " + worn.status()));
	}
}
