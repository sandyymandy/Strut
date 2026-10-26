package com.sandymandy.strut.player;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.util.math.Vec3d;

/**
 * Where a player's body is drawn when it is not where the player is: on a bed, at a scene's mark. The player themself does not move
 *
 * @param yaw the way the body faces, in degrees, as a player's yaw
 */
public record BodyAnchor(double x, double y, double z, float yaw) {
	public static final PacketCodec<RegistryByteBuf, BodyAnchor> PACKET_CODEC = PacketCodec.tuple(
			PacketCodecs.DOUBLE, BodyAnchor::x,
			PacketCodecs.DOUBLE, BodyAnchor::y,
			PacketCodecs.DOUBLE, BodyAnchor::z,
			PacketCodecs.FLOAT, BodyAnchor::yaw,
			BodyAnchor::new);

	public Vec3d position() {
		return new Vec3d(this.x, this.y, this.z);
	}
}
