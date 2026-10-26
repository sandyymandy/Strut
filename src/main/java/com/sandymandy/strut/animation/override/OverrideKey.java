package com.sandymandy.strut.animation.override;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;

/**
 * A bone name paired with which property of it is being overridden - what
 * {@link ServerBoneOverrideState}/{@link ClientBoneOverrideState} key their maps by
 * <p>
 * By bone <em>name</em>, not a resolved index - a dedicated server never loads
 * {@code StrutCache}'s client-only baked models (see {@code StrutClient#onInitializeClient}'s own
 * {@code ResourceType.CLIENT_RESOURCES} registration), so the server side of this system cannot
 * resolve a name to an index even if it wanted to. Resolution against a real bone list happens only
 * where a real {@code BakedStrutModel} is actually in hand - client-side, at
 * {@code ModelInstance#effectivePose()}
 */
public record OverrideKey(String bone, OverrideProperty property) {
	public static final PacketCodec<PacketByteBuf, OverrideKey> STREAM_CODEC = PacketCodec.of(
			(key, buf) -> {
				buf.writeString(key.bone);
				buf.writeByte(key.property.ordinal());
			},
			buf -> new OverrideKey(buf.readString(), OverrideProperty.values()[buf.readByte()]));
}
