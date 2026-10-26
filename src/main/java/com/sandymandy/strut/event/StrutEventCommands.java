package com.sandymandy.strut.event;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.sandymandy.strut.StrutConstants;
import com.sandymandy.strut.event.binding.EventBindings;
import com.sandymandy.strut.event.dispatch.EventStats;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.entity.Entity;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import org.jetbrains.annotations.Nullable;

/**
 * {@code /strut events}: the live view of the event system
 * <ul>
 * <li>{@code /strut events stats} - cumulative counts of what fired and what was unbound</li>
 * <li>{@code /strut events trace <entity> [on|off]} - a chat line for every event delivered on that entity,
 *     naming its type, payload, playback sequence, loop and exact game time</li>
 * </ul>
 * Built for verification: a working event and one that silently never ran look identical from the outside, so a
 * live test needs to <i>see</i> occurrences as they happen. Server-side, so it works on a dedicated server too
 * <p>
 * The {@code strut} root is declared here with the same permission requirement as the one in the example
 * command: Brigadier merges same-named roots and keeps whichever requirement registered first, so the two
 * must agree or the command's access would depend on load order
 */
public final class StrutEventCommands {
	private StrutEventCommands() {}

	/**
	 * Called once from {@link com.sandymandy.strut.Strut#onInitialize()}
	 */
	public static void init() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
				CommandManager.literal(StrutConstants.MODID)
						.requires(source -> source.hasPermissionLevel(2))
						.then(CommandManager.literal("events")
								.then(CommandManager.literal("stats").executes(context -> stats(context.getSource())))
								.then(CommandManager.literal("trace")
										.then(CommandManager.argument("entity", EntityArgumentType.entity())
												.executes(context -> trace(context.getSource(), EntityArgumentType.getEntity(context, "entity"), null))
												.then(CommandManager.literal("on").executes(context ->
														trace(context.getSource(), EntityArgumentType.getEntity(context, "entity"), true)))
												.then(CommandManager.literal("off").executes(context ->
														trace(context.getSource(), EntityArgumentType.getEntity(context, "entity"), false))))))));
	}

	private static int stats(ServerCommandSource source) {
		source.sendFeedback(() -> Text.literal("Strut events (cumulative since launch): server delivered " + EventStats.serverDelivered()
				+ ", client delivered " + EventStats.clientDelivered() + ", unbound sound keys " + EventStats.unboundKeys()
				+ ", capped sweeps " + EventStats.truncatedSweeps() + ", binding sets loaded " + EventBindings.sets().size()
				+ ", entities traced " + StrutEvents.trace().tracedCount()), false);

		return 1;
	}

	/**
	 * @param on True to start, false to stop, null to toggle
	 */
	private static int trace(ServerCommandSource source, Entity entity, @Nullable Boolean on) throws CommandSyntaxException {
		boolean start = on != null ? on : !StrutEvents.trace().isTracing(entity.getId());

		if (start) {
			StrutEvents.trace().start(entity.getId(), line -> source.sendFeedback(() -> Text.literal(line), false));
			source.sendFeedback(() -> Text.literal("Tracing events on " + entity.getName().getString() + " (#" + entity.getId()
					+ "). Server-side events only; the client's are on the F9 overlay."), false);
		}
		else {
			StrutEvents.trace().stop(entity.getId());
			source.sendFeedback(() -> Text.literal("Stopped tracing " + entity.getName().getString() + " (#" + entity.getId() + ")."), false);
		}

		return 1;
	}
}
