package com.sandymandy.strut.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.world.World;

/**
 * Helper class for segregating client-side code
 */
public final class ClientUtil {
	/**
	 * Get the player on the client
	 */
	public static PlayerEntity getClientPlayer() {
		return MinecraftClient.getInstance().player;
	}

	/**
	 * Gets the current level on the client
	 */
	public static World getLevel() {
		return MinecraftClient.getInstance().world;
	}
}
