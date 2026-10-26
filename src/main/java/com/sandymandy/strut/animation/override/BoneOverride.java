package com.sandymandy.strut.animation.override;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;

/**
 * One override's full state: its value, when it expires, how it should be weighed against another
 * override of the same property, and where it sits in replication ordering
 * <p>
 * Mirrors {@link com.sandymandy.strut.animation.authority.AnimationPlayback}'s own shape - a single
 * self-contained record carrying everything needed to apply this override on any client, on any
 * frame, without having watched it get set
 *
 * @param value The override's current value
 * @param scope When this override is expected to clear itself - see {@link OverrideScope}
 * @param priority Resolution order when two overrides of the same property compete (a
 *                 server-authoritative one and a client-local one, most often) - higher wins,
 *                 ties broken by {@code EntityBoneOverrides}'s own "local beats synced" rule, not
 *                 this record
 * @param blendWeight How strongly this override's value blends over whatever it is layered on,
 *                    {@code 0}-{@code 1}. Not yet consumed by anything - blending itself will
 *                    resolve into {@code ModelInstance#effectivePose()} - carried now for
 *                    the same reason {@link com.sandymandy.strut.animation.authority.AnimationPlayback#sequence()}
 *                    was carried before the protocol spent it: retrofitting a value into an
 *                    already-shipped wire format is worse than carrying it early
 * @param transitionTicks How long a change to this override takes to visually blend in once
 *                        applied, in client ticks - {@code 0} for instant. Not yet consumed, same
 *                        reasoning as {@code blendWeight}
 * @param sequence Monotonic per {@link OverrideKey}, per animatable - the same delayed-packet guard
 *                 {@code AnimationPlayback#sequence()} provides, spent by
 *                 {@link ClientBoneOverrideState#applyDelta}
 */
public record BoneOverride(OverrideValue value, OverrideScope scope, int priority, float blendWeight,
							float transitionTicks, long sequence) {
	/**
	 * Manual rather than {@link PacketCodec#tuple}, which this record's six fields overrun -
	 * {@code OverrideScope} rides as a single byte via its own ordinal, the same convention
	 * {@link OverrideValue#STREAM_CODEC} already uses for its own shape tag
	 */
	public static final PacketCodec<PacketByteBuf, BoneOverride> STREAM_CODEC = PacketCodec.of(
			(override, buf) -> {
				OverrideValue.STREAM_CODEC.encode(buf, override.value);
				buf.writeByte(override.scope.ordinal());
				buf.writeVarInt(override.priority);
				buf.writeFloat(override.blendWeight);
				buf.writeFloat(override.transitionTicks);
				buf.writeVarLong(override.sequence);
			},
			buf -> new BoneOverride(OverrideValue.STREAM_CODEC.decode(buf), OverrideScope.values()[buf.readByte()],
					buf.readVarInt(), buf.readFloat(), buf.readFloat(), buf.readVarLong()));
}
