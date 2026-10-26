package com.sandymandy.strut.network.packet;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.entity.player.PlayerEntity;
import org.jetbrains.annotations.Nullable;
import com.sandymandy.strut.StrutConstants;
import com.sandymandy.strut.animation.override.BoneOverride;
import com.sandymandy.strut.animation.override.ClientBoneOverrideStates;
import com.sandymandy.strut.animation.override.OverrideKey;

import java.util.function.Consumer;

/**
 * One bone override's change for one entity, sent to everyone already tracking it - the
 * override-shaped twin of {@link AnimationPlaybackDeltaPacket}
 * <p>
 * {@code override} is null for a clear, the same reason {@code AnimationPlaybackDeltaPacket}'s own
 * {@code playback} field can be - see that record's own doc comment. {@code sequence} is carried
 * explicitly rather than read off {@code override.sequence()} for the identical reason: a clear has
 * no {@link BoneOverride} to read one from
 * <p>
 * Sent by {@code ServerAnimationStates} whenever {@code ServerBoneOverrideState#drainDirty()}
 * reports a change, folded into the same per-world-tick sweep that already drains animation deltas.
 * Received here into {@link ClientBoneOverrideStates}, where the sequence guard
 * {@code ClientBoneOverrideState#applyDelta} enforces the ordering
 */
public record BoneOverrideDeltaPacket(int entityId, boolean isReplacedEntity, OverrideKey key,
									   long sequence, @Nullable BoneOverride override) implements MultiloaderPacket {
	public static final Id<BoneOverrideDeltaPacket> TYPE = new Id<>(StrutConstants.id("bone_override_delta"));
	public static final PacketCodec<PacketByteBuf, BoneOverrideDeltaPacket> CODEC = PacketCodec.of(
			(packet, buf) -> {
				buf.writeVarInt(packet.entityId);
				buf.writeBoolean(packet.isReplacedEntity);
				OverrideKey.STREAM_CODEC.encode(buf, packet.key);
				buf.writeVarLong(packet.sequence);
				buf.writeBoolean(packet.override != null);

				if (packet.override != null)
					BoneOverride.STREAM_CODEC.encode(buf, packet.override);
			},
			buf -> new BoneOverrideDeltaPacket(buf.readVarInt(), buf.readBoolean(), OverrideKey.STREAM_CODEC.decode(buf),
					buf.readVarLong(), buf.readBoolean() ? BoneOverride.STREAM_CODEC.decode(buf) : null));

	@Override
	public Id<? extends CustomPayload> getId() {
		return TYPE;
	}

	@Override
	public void receiveMessage(@Nullable PlayerEntity sender, Consumer<Runnable> workQueue) {
		workQueue.accept(() ->
				ClientBoneOverrideStates.forEntity(this.entityId).applyDelta(this.key, this.sequence, this.override));
	}
}
