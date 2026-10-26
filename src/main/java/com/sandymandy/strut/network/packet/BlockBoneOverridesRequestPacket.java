package com.sandymandy.strut.network.packet;

import com.sandymandy.strut.StrutConstants;
import com.sandymandy.strut.animation.override.ServerBlockOverrides;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

/**
 * Client to server: "I have just loaded the block entity at {@code pos}: what overrides does it have?" Answered with a
 * {@link BlockBoneOverrideSnapshotPacket} to that player alone, or with nothing if there are none
 * <p>
 * A player gets a block entity when its chunk is sent, and nothing in the version of Fabric this is built on says when that was, so the client asks
 * when its copy of the block entity loads. A position the player is not near is ignored
 */
public record BlockBoneOverridesRequestPacket(BlockPos pos) implements MultiloaderPacket {
	/** The furthest, in blocks, a player can be from a block and ask about it: past any view distance a server runs */
	private static final double MAX_DISTANCE = 16 * 40;

	public static final Id<BlockBoneOverridesRequestPacket> TYPE = new Id<>(StrutConstants.id("block_bone_overrides_request"));
	public static final PacketCodec<PacketByteBuf, BlockBoneOverridesRequestPacket> CODEC = PacketCodec.tuple(
			BlockPos.PACKET_CODEC, BlockBoneOverridesRequestPacket::pos,
			BlockBoneOverridesRequestPacket::new);

	@Override
	public Id<? extends CustomPayload> getId() {
		return TYPE;
	}

	@Override
	public void receiveMessage(@Nullable PlayerEntity sender, Consumer<Runnable> workQueue) {
		workQueue.accept(() -> {
			if (sender instanceof ServerPlayerEntity player && player.squaredDistanceTo(this.pos.toCenterPos()) <= MAX_DISTANCE * MAX_DISTANCE)
				ServerBlockOverrides.sendTo(player, this.pos);
		});
	}
}
