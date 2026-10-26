package com.sandymandy.strut.network.packet;

import com.sandymandy.strut.StrutConstants;
import com.sandymandy.strut.animation.override.BoneOverride;
import com.sandymandy.strut.animation.override.ClientBoneOverrideStates;
import com.sandymandy.strut.animation.override.OverrideKey;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Every bone override currently set on the block entity at {@code pos}: the block-entity twin of {@link BoneOverrideSnapshotPacket}. It replaces
 * whatever the client held for that block, so a change and the answer to a late request are the same packet, and an empty list is how a clear arrives
 */
public record BlockBoneOverrideSnapshotPacket(BlockPos pos, List<BoneOverrideSnapshotPacket.Entry> overrides) implements MultiloaderPacket {
	public static final Id<BlockBoneOverrideSnapshotPacket> TYPE = new Id<>(StrutConstants.id("block_bone_override_snapshot"));
	public static final PacketCodec<PacketByteBuf, BlockBoneOverrideSnapshotPacket> CODEC = PacketCodec.tuple(
			BlockPos.PACKET_CODEC, BlockBoneOverrideSnapshotPacket::pos,
			BoneOverrideSnapshotPacket.Entry.STREAM_CODEC.collect(PacketCodecs.toList()), BlockBoneOverrideSnapshotPacket::overrides,
			BlockBoneOverrideSnapshotPacket::new);

	@Override
	public Id<? extends CustomPayload> getId() {
		return TYPE;
	}

	@Override
	public void receiveMessage(@Nullable PlayerEntity sender, Consumer<Runnable> workQueue) {
		workQueue.accept(() -> {
			Map<OverrideKey, BoneOverride> asMap = new HashMap<>();

			for (BoneOverrideSnapshotPacket.Entry entry : this.overrides)
				asMap.put(entry.key(), entry.override());

			ClientBoneOverrideStates.forBlock(this.pos).applySnapshot(asMap);
		});
	}
}
