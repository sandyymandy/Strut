package com.sandymandy.strut.network.packet;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import org.jetbrains.annotations.Nullable;
import com.sandymandy.strut.StrutConstants;
import com.sandymandy.strut.animation.authority.ServerAnimationStates;
import com.sandymandy.strut.animation.authority.SnapshotReason;

import java.util.function.Consumer;

/**
 * Client to server: "I have no idea what {@code entityId} should be playing - please tell me"
 * <p>
 * The one
 * server-bound packet - every other packet in {@code StrutNetworking} is
 * client-bound. Answered with a full {@link ServerAnimationStates#sendSnapshotTo} to the requesting
 * player alone, the same shape a start-tracking snapshot already has, just addressed to one player
 * on demand instead of triggered by a Fabric event
 * <p>
 * {@code entityId} is trusted as given - a request for an entity id the requester is not actually
 * tracking simply finds no {@code ServerAnimationState} (or finds one but the sender was never
 * added as a tracker, so the snapshot goes to a player harmlessly holding state for an entity they
 * cannot see) rather than needing its own validation here
 */
public record AnimationResyncRequestPacket(int entityId) implements MultiloaderPacket {
	public static final Id<AnimationResyncRequestPacket> TYPE = new Id<>(StrutConstants.id("animation_resync_request"));
	public static final PacketCodec<PacketByteBuf, AnimationResyncRequestPacket> CODEC = PacketCodec.tuple(
			PacketCodecs.VAR_INT, AnimationResyncRequestPacket::entityId,
			AnimationResyncRequestPacket::new);

	@Override
	public Id<? extends CustomPayload> getId() {
		return TYPE;
	}

	@Override
	public void receiveMessage(@Nullable PlayerEntity sender, Consumer<Runnable> workQueue) {
		workQueue.accept(() -> {
			if (!(sender instanceof ServerPlayerEntity player))
				return;

			Entity entity = player.getWorld().getEntityById(this.entityId);

			if (entity != null)
				ServerAnimationStates.sendSnapshotTo(entity, player, true, SnapshotReason.RESYNC);
		});
	}
}
