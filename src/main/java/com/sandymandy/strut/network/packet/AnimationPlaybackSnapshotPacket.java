package com.sandymandy.strut.network.packet;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.entity.player.PlayerEntity;
import org.jetbrains.annotations.Nullable;
import com.sandymandy.strut.StrutConstants;
import com.sandymandy.strut.animation.authority.AnimationPlayback;
import com.sandymandy.strut.animation.authority.ClientAnimationStates;
import com.sandymandy.strut.animation.authority.SnapshotReason;

import java.util.List;
import java.util.function.Consumer;

/**
 * Every controller's current {@link AnimationPlayback} for one animatable, sent when a player
 * starts tracking it
 * <p>
 * Sent by {@code ServerAnimationStates} on {@code EntityTrackingEvents#START_TRACKING}.
 * Received here into {@link ClientAnimationStates}.
 * 
 */
public record AnimationPlaybackSnapshotPacket(int entityId, boolean isReplacedEntity, SnapshotReason reason,
											  List<AnimationPlayback> playbacks) implements MultiloaderPacket {
	public static final Id<AnimationPlaybackSnapshotPacket> TYPE = new Id<>(StrutConstants.id("animation_playback_snapshot"));
	public static final PacketCodec<PacketByteBuf, AnimationPlaybackSnapshotPacket> CODEC = PacketCodec.tuple(
			PacketCodecs.VAR_INT, AnimationPlaybackSnapshotPacket::entityId,
			PacketCodecs.BOOL, AnimationPlaybackSnapshotPacket::isReplacedEntity,
			PacketCodecs.indexed(SnapshotReason::fromWire, SnapshotReason::ordinal), AnimationPlaybackSnapshotPacket::reason,
			AnimationPlayback.STREAM_CODEC.collect(PacketCodecs.toList()), AnimationPlaybackSnapshotPacket::playbacks,
			AnimationPlaybackSnapshotPacket::new);

	@Override
	public Id<? extends CustomPayload> getId() {
		return TYPE;
	}

	@Override
	public void receiveMessage(@Nullable PlayerEntity sender, Consumer<Runnable> workQueue) {
		workQueue.accept(() -> ClientAnimationStates.forEntity(this.entityId).applySnapshot(this.playbacks, this.reason));
	}
}
