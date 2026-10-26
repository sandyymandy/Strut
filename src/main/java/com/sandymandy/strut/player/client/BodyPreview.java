package com.sandymandy.strut.player.client;

import com.mojang.authlib.GameProfile;
import com.sandymandy.strut.player.PlayerBodies;
import com.sandymandy.strut.player.StrutPlayers;
import java.util.function.Consumer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.OtherClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Draws a body in a screen, the way the inventory draws the player: a player entity that is not in the world, given the body, drawn turned toward the mouse
 */
@ApiStatus.Internal
public final class BodyPreview {
	private static final GameProfile PROFILE = new GameProfile(UUID.nameUUIDFromBytes("strut:body_preview".getBytes()), "preview");

	@Nullable
	private static OtherClientPlayerEntity player;

	private BodyPreview() {}

	/**
	 * Whether {@code entity} is the preview's player, which is not in the world and has no name tag
	 */
	static boolean isPreview(@Nullable Entity entity) {
		return entity != null && entity == player;
	}

	/**
	 * Draws {@code body} (with {@code variant}) in the box {@code x1, y1} to {@code x2, y2}, turned toward the mouse, as the inventory draws the player. Needs a
	 * world to be in: nothing is drawn in the title screen
	 *
	 * @return whether it was drawn
	 */
	public static boolean draw(DrawContext context, Identifier body, String variant, int x1, int y1, int x2, int y2, int size, float mouseX, float mouseY) {
		ClientWorld world = MinecraftClient.getInstance().world;

		if (world == null || PlayerBodies.get(body) == null)
			return false;

		AbstractClientPlayerEntity preview = playerIn(world);

		StrutPlayers.setLocalBody(preview, body, variant);

		// the animations count the player's age in ticks: this one does not tick, so it is told the time
		preview.age = (int) (Util.getMeasuringTimeMs() / 50L);
		preview.setOnGround(true);

		InventoryScreen.drawEntity(context, x1, y1, x2, y2, size, 0.0625f, mouseX, mouseY, preview);

		return true;
	}

	/**
	 * Draws {@code body} (with {@code variant}) in the box, turned to {@code yawDegrees} (0 faces the viewer, 180 shows the back) and tipped to {@code pitchDegrees},
	 * where the other overload follows the mouse: for a screen that lets the player turn the preview. {@code prepare}, if given, runs on the preview player after it
	 * is given the body and before it is drawn, every time: it is the one player for every preview, so set whatever the model reads from it (a client-only flag)
	 * each call, not once
	 *
	 * @return whether it was drawn
	 */
	public static boolean draw(DrawContext context, Identifier body, String variant, int x1, int y1, int x2, int y2, int size, float yawDegrees, float pitchDegrees,
			@Nullable Consumer<AbstractClientPlayerEntity> prepare) {
		ClientWorld world = MinecraftClient.getInstance().world;

		if (world == null || PlayerBodies.get(body) == null)
			return false;

		AbstractClientPlayerEntity preview = playerIn(world);

		StrutPlayers.setLocalBody(preview, body, variant);
		preview.age = (int) (Util.getMeasuringTimeMs() / 50L);
		preview.setOnGround(true);

		if (prepare != null)
			prepare.accept(preview);

		float centreX = (x1 + x2) / 2.0f;
		float centreY = (y1 + y2) / 2.0f;
		// the vanilla way of turning a preview is the body turned from facing the viewer (a turn of 180) and the camera tipped, so the entity is set the same
		float bodyYaw = preview.bodyYaw;
		float prevBodyYaw = preview.prevBodyYaw;
		float yaw = preview.getYaw();
		float pitch = preview.getPitch();
		float prevHeadYaw = preview.prevHeadYaw;
		float headYaw = preview.headYaw;

		preview.bodyYaw = preview.prevBodyYaw = 180.0f + yawDegrees;
		preview.setYaw(180.0f + yawDegrees);
		preview.setPitch(-pitchDegrees);
		preview.headYaw = preview.prevHeadYaw = preview.getYaw();

		float scale = preview.getScale();
		Vector3f offset = new Vector3f(0.0f, preview.getHeight() / 2.0f + 0.0625f * scale, 0.0f);
		Quaternionf turn = new Quaternionf().rotateZ((float) Math.PI);
		Quaternionf tip = new Quaternionf().rotateX(pitchDegrees * ((float) Math.PI / 180.0f));

		turn.mul(tip);
		context.enableScissor(x1, y1, x2, y2);

		try {
			InventoryScreen.drawEntity(context, centreX, centreY, size / scale, offset, turn, tip, preview);
		}
		finally {
			context.disableScissor();
			preview.bodyYaw = bodyYaw;
			preview.prevBodyYaw = prevBodyYaw;
			preview.setYaw(yaw);
			preview.setPitch(pitch);
			preview.prevHeadYaw = prevHeadYaw;
			preview.headYaw = headYaw;
		}

		return true;
	}

	private static OtherClientPlayerEntity playerIn(ClientWorld world) {
		if (player == null || player.getWorld() != world)
			player = new OtherClientPlayerEntity(world, PROFILE);

		return player;
	}
}
