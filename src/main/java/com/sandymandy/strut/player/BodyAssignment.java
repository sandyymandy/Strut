package com.sandymandy.strut.player;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.util.Identifier;

/**
 * Which body a player has, and the one string a mod can use to tell its variants apart (a character id, a colour: whatever the body's code reads,
 * empty if nothing). This is the value that is synced to every client that can see the player
 */
public record BodyAssignment(Identifier body, String variant) {
	public static final Codec<BodyAssignment> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Identifier.CODEC.fieldOf("body").forGetter(BodyAssignment::body),
			Codec.STRING.optionalFieldOf("variant", "").forGetter(BodyAssignment::variant)
	).apply(instance, BodyAssignment::new));

	public static final PacketCodec<RegistryByteBuf, BodyAssignment> PACKET_CODEC = PacketCodec.tuple(
			Identifier.PACKET_CODEC, BodyAssignment::body,
			PacketCodecs.STRING, BodyAssignment::variant,
			BodyAssignment::new);
}
