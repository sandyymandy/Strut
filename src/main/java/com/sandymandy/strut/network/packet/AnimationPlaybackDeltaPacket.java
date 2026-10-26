package com.sandymandy.strut.network.packet;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.entity.player.PlayerEntity;
import org.jetbrains.annotations.Nullable;
import com.sandymandy.strut.StrutConstants;
import com.sandymandy.strut.animation.authority.AnimationPlayback;
import com.sandymandy.strut.animation.authority.ClientAnimationStates;
import com.sandymandy.strut.animation.authority.ServerAnimationState.ChangeKind;

import java.util.function.Consumer;

/**
 * One controller's playback change for one animatable, sent to everyone already tracking it
 * <p>
 * {@code playback} is null for a stop: {@link com.sandymandy.strut.animation.authority.ServerAnimationState#stop}
 * removes a controller's playback entirely rather than leaving a value to send, so there is nothing
 * to carry except which controller it was. {@code controller} is carried explicitly rather than read
 * off {@code playback} for exactly that reason - a stop has no playback to read it from
 * <p>
 * {@code sequence} is carried explicitly too, rather than read off {@code playback.sequence()}, for
 * the identical reason: a stop is a mutation like any other and needs its own place in the ordering
 * (see {@link com.sandymandy.strut.animation.authority.ServerAnimationState#lastSequenceFor}), but
 * has no {@code AnimationPlayback} to read one from. Without a sequence of its own, a delayed stop
 * could cancel a newer {@code play} a client had already correctly applied
 * <p>
 * Sent by {@code ServerAnimationStates} whenever {@code ServerAnimationState#drainDirty()} reports a
 * change. Received here into {@link ClientAnimationStates}, which is where the
 * sequence guard {@code applyDelta} describes actually gets enforced
 */
public record AnimationPlaybackDeltaPacket(int entityId, boolean isReplacedEntity, String controller,
										   long sequence, @Nullable AnimationPlayback playback, ChangeKind kind) implements MultiloaderPacket {
	public static final Id<AnimationPlaybackDeltaPacket> TYPE = new Id<>(StrutConstants.id("animation_playback_delta"));
	public static final PacketCodec<PacketByteBuf, AnimationPlaybackDeltaPacket> CODEC = PacketCodec.of(
			(packet, buf) -> {
				buf.writeVarInt(packet.entityId);
				buf.writeBoolean(packet.isReplacedEntity);
				buf.writeString(packet.controller);
				buf.writeVarLong(packet.sequence);
				buf.writeBoolean(packet.playback != null);
				buf.writeByte(packet.kind.ordinal());

				if (packet.playback != null)
					AnimationPlayback.STREAM_CODEC.encode(buf, packet.playback);
			},
			buf -> {
				int entityId = buf.readVarInt();
				boolean replaced = buf.readBoolean();
				String controller = buf.readString();
				long sequence = buf.readVarLong();
				boolean hasPlayback = buf.readBoolean();
				ChangeKind kind = ChangeKind.fromWire(buf.readByte());

				return new AnimationPlaybackDeltaPacket(entityId, replaced, controller, sequence,
						hasPlayback ? AnimationPlayback.STREAM_CODEC.decode(buf) : null, kind);
			});

	@Override
	public Id<? extends CustomPayload> getId() {
		return TYPE;
	}

	@Override
	public void receiveMessage(@Nullable PlayerEntity sender, Consumer<Runnable> workQueue) {
		workQueue.accept(() ->
				ClientAnimationStates.forEntity(this.entityId).applyDelta(this.controller, this.sequence, this.playback, this.kind));
	}
}
