package com.sandymandy.strut.assetsync;

import com.sandymandy.strut.StrutConstants;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

import java.util.List;
import com.sandymandy.strut.assetsync.server.ServerAssetSyncFabric;

/**
 * {@code /strut assets}: the live view of asset sync
 * <ul>
 * <li>{@code /strut assets status} - what is offered, the counters, and who is downloading right now</li>
 * <li>{@code /strut assets list} - the offered files (the first 40)</li>
 * </ul>
 * The {@code strut} root carries the same permission requirement as the other Strut commands: Brigadier merges roots of
 * the same name and keeps the first requirement registered, so they must agree
 */
public final class AssetSyncCommands {
	private static final int LIST_LIMIT = 40;

	private AssetSyncCommands() {}

	/**
	 * Called once from {@link com.sandymandy.strut.Strut#onInitialize()}
	 */
	public static void init() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
				CommandManager.literal(StrutConstants.MODID)
						.requires(source -> source.hasPermissionLevel(2))
						.then(CommandManager.literal("assets")
								.then(CommandManager.literal("status").executes(context -> send(context.getSource(),
										AssetSyncReport.status(StrutAssetSync.currentStore(), StrutAssetSync.isRequired(), ServerAssetSyncFabric.activeSyncs()))))
								.then(CommandManager.literal("list").executes(context -> send(context.getSource(),
										AssetSyncReport.files(StrutAssetSync.currentStore(), LIST_LIMIT)))))));
	}

	private static int send(ServerCommandSource source, List<String> lines) {
		for (String line : lines)
			source.sendFeedback(() -> Text.literal(line), false);

		return lines.size();
	}
}
