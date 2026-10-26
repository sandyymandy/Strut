package com.sandymandy.strut.player.client;

import com.sandymandy.strut.network.packet.EyeOffsetPacket;
import com.sandymandy.strut.player.BodyAnchor;
import com.sandymandy.strut.player.FirstPersonMode;
import com.sandymandy.strut.player.PlayerBody;
import com.sandymandy.strut.player.StrutPlayers;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * First person for a body in {@link FirstPersonMode#IMMERSIVE}: whether the local player is looking out of their own body, and where the camera goes
 * <p>
 * The renderer records, each frame it draws the local player, where the eye bone is relative to the player (as it is drawn, with the body turned and the
 * animation applied). The camera is then put at the player's interpolated position plus that offset. The offset is from the frame before the one the
 * camera is placed for, since the camera is placed before the world is drawn, which is not seen at the speeds a body animates at
 */
@ApiStatus.Internal
public final class FirstPersonBody {
	/** The furthest, in blocks, the camera may be from where the player really is: past it the eye bone is broken, and the vanilla camera is kept */
	private static final double MAX_OFFSET = 4.0;

	@Nullable
	private static Vec3d eyeOffset;

	private FirstPersonBody() {}

	/**
	 * The body of {@code entity} if it is the local player, in first person, and has an {@link FirstPersonMode#IMMERSIVE} body; else null
	 */
	@Nullable
	public static PlayerBody immersiveBodyOf(@Nullable Entity entity) {
		if (!(entity instanceof AbstractClientPlayerEntity player))
			return null;

		MinecraftClient client = MinecraftClient.getInstance();

		if (client.getCameraEntity() != player || !client.options.getPerspective().isFirstPerson())
			return null;

		PlayerBody body = StrutPlayers.body(player);

		return body != null && StrutPlayersClient.firstPersonOf(player, body) == FirstPersonMode.IMMERSIVE ? body : null;
	}

	/**
	 * Records where the eye bone is relative to the player, as drawn this frame
	 */
	static void recordEyeOffset(@Nullable Vec3d offset) {
		eyeOffset = offset != null && Double.isFinite(offset.lengthSquared()) && offset.length() <= MAX_OFFSET ? offset : null;
	}

	/**
	 * Where the camera goes for {@code player} this frame, or null to leave it where the game put it
	 *
	 * @param partialTick how far into the tick this frame is
	 */
	@Nullable
	public static Vec3d cameraPosition(AbstractClientPlayerEntity player, float partialTick) {
		if (eyeOffset == null || immersiveBodyOf(player) == null)
			return null;

		BodyAnchor anchor = StrutPlayers.anchor(player);

		// the offset is from where the body is drawn: the player's place, or the anchor
		Vec3d base = anchor != null ? anchor.position() : new Vec3d(MathHelper.lerp(partialTick, player.prevX, player.getX()),
				MathHelper.lerp(partialTick, player.prevY, player.getY()),
				MathHelper.lerp(partialTick, player.prevZ, player.getZ()));

		return base.add(eyeOffset);
	}

	/**
	 * Where the player's eye is for what is aimed and shot: at the eye bone, at the player's place as of the last tick. Null to leave it where the game puts it
	 */
	@Nullable
	public static Vec3d eyePosition(Entity entity) {
		if (eyeOffset == null || !(entity instanceof AbstractClientPlayerEntity player) || immersiveBodyOf(player) == null)
			return null;

		BodyAnchor anchor = StrutPlayers.anchor(player);

		return (anchor != null ? anchor.position() : player.getPos()).add(eyeOffset);
	}

	/**
	 * Where a bone is relative to the player, in the world's axes
	 *
	 * @param boneInModel the bone's transform in the model, taken to its pivot: its origin is where the bone is in the model
	 * @param modelTransform the transform the model was drawn with (the player's position, the body turned to face, scaled)
	 * @param entityTransform the transform at the player's own position alone, which the model's was built on
	 */
	static Vec3d offsetOfBone(Matrix4f boneInModel, Matrix4f modelTransform, Matrix4f entityTransform) {
		Vector4f bone = modelTransform.transform(boneInModel.transform(new Vector4f(0, 0, 0, 1)));
		Vector4f origin = entityTransform.transform(new Vector4f(0, 0, 0, 1));

		return new Vec3d(bone.x - origin.x, bone.y - origin.y, bone.z - origin.z);
	}

	/** What the server was last told: an offset that has not moved is not sent again */
	@Nullable
	private static Vec3d sent;
	private static boolean sentSomething;
	private static int sinceSent;

	/**
	 * Called every client tick: tells the server where the eye bone is while the local player looks out of an immersive body, when it has moved (and now and
	 * then regardless), and that it is gone when they stop
	 */
	public static void sendEyeToServer(MinecraftClient client) {
		if (client.player == null || client.getNetworkHandler() == null || !ClientPlayNetworking.canSend(EyeOffsetPacket.TYPE))
			return;

		Vec3d offset = eyeOffset != null && immersiveBodyOf(client.player) != null ? eyeOffset : null;

		if (offset == null) {
			if (sentSomething) {
				ClientPlayNetworking.send(new EyeOffsetPacket(false, 0f, 0f, 0f));
				sentSomething = false;
				sent = null;
			}

			return;
		}

		if (sentSomething && sent != null && sent.squaredDistanceTo(offset) < 1.0e-4 && ++sinceSent < 40)
			return;

		sinceSent = 0;
		sent = offset;
		sentSomething = true;
		ClientPlayNetworking.send(new EyeOffsetPacket(true, (float) offset.x, (float) offset.y, (float) offset.z));
	}

	/**
	 * Forgets the recorded offset: the player has no immersive body now
	 */
	static void clear() {
		eyeOffset = null;
	}
}
