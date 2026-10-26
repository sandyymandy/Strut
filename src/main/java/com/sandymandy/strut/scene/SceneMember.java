package com.sandymandy.strut.scene;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;

import java.util.UUID;

/**
 * What an entity in a scene knows of it, on the server and on every client that sees the entity (it is a synced data attachment): the scene, the role, the kind
 * of scene and where it is. A client reads it for a camera, a HUD or a rule, with {@link StrutScenes#of}
 *
 * @param scene the scene's id
 * @param role the participant's role name
 * @param type the kind of scene a mod gave it
 * @param yaw the way the scene faces, in degrees
 */
public record SceneMember(UUID scene, String role, Identifier type, double x, double y, double z, float yaw) {
	public static final PacketCodec<RegistryByteBuf, SceneMember> PACKET_CODEC = PacketCodec.of((member, buf) -> {
		buf.writeUuid(member.scene);
		buf.writeString(member.role);
		buf.writeIdentifier(member.type);
		buf.writeDouble(member.x);
		buf.writeDouble(member.y);
		buf.writeDouble(member.z);
		buf.writeFloat(member.yaw);
	}, buf -> new SceneMember(buf.readUuid(), buf.readString(), buf.readIdentifier(), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readFloat()));

	/** The scene's origin */
	public Vec3d origin() {
		return new Vec3d(this.x, this.y, this.z);
	}
}
