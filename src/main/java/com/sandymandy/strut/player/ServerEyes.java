package com.sandymandy.strut.player;

import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where the server takes the eye of a player with an {@link FirstPersonMode#IMMERSIVE} body to be: at the eye bone, which only the client knows, so it sends
 * the offset (see {@code EyeOffsetPacket}). Aiming, what the player hits and what they shoot are then worked out from where they are looking out of
 * <p>
 * The offset is the client's word, so it is only used for a player who has such a body, and only while it is within a few blocks of them: the reach checks
 * of the game stand, measured from there
 */
@ApiStatus.Internal
public final class ServerEyes {
	/** The furthest from the player, in blocks, an offset may be: the same cap the client camera has */
	private static final double MAX_OFFSET = 4.0;

	private static final Map<UUID, Vec3d> OFFSETS = new ConcurrentHashMap<>();

	private ServerEyes() {}

	/** Called once at startup: a player who leaves is forgotten */
	public static void init() {
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> OFFSETS.remove(handler.getPlayer().getUuid()));
	}

	/** Records the eye offset the player sent, or forgets it for null or a value that is not a sane one */
	public static void set(ServerPlayerEntity player, @Nullable Vec3d offset) {
		if (offset == null || !Double.isFinite(offset.lengthSquared()) || offset.length() > MAX_OFFSET)
			OFFSETS.remove(player.getUuid());
		else
			OFFSETS.put(player.getUuid(), offset);
	}

	/**
	 * Where {@code player}'s eye is for what they aim at, or null to leave it where the game puts it: the player has no immersive body, or has sent no offset
	 */
	@Nullable
	public static Vec3d eyePosition(ServerPlayerEntity player) {
		Vec3d offset = OFFSETS.get(player.getUuid());

		if (offset == null)
			return null;

		PlayerBody body = StrutPlayers.body(player);

		// the client only sends an offset while it looks out of an immersive body, which the client may choose for itself, so what the body says is not asked here
		if (body == null)
			return null;

		BodyAnchor anchor = StrutPlayers.anchor(player);

		return (anchor != null ? anchor.position() : player.getPos()).add(offset);
	}
}
