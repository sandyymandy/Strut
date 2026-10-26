package com.sandymandy.strut.network.packet;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.entity.player.PlayerEntity;
import org.jetbrains.annotations.Nullable;
import com.sandymandy.strut.StrutConstants;
import com.sandymandy.strut.animation.override.BoneOverride;
import com.sandymandy.strut.animation.override.ClientBoneOverrideStates;
import com.sandymandy.strut.animation.override.OverrideKey;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Every bone override currently set on one entity, sent when a player starts tracking it - the
 * override-shaped twin of {@link AnimationPlaybackSnapshotPacket}
 * <p>
 * Sent by {@code ServerAnimationStates#sendSnapshotTo}, folded into the same call that already
 * sends the animation snapshot - see that method's own doc comment on why both ride the same
 * start-tracking/resync trigger rather than a separate one of their own
 */
public record BoneOverrideSnapshotPacket(int entityId, boolean isReplacedEntity,
										  List<Entry> overrides) implements MultiloaderPacket {
	/** One {@link OverrideKey}/{@link BoneOverride} pair - the wire shape {@link Map#entrySet()} does not have one of */
	public record Entry(OverrideKey key, BoneOverride override) {
		public static final PacketCodec<PacketByteBuf, Entry> STREAM_CODEC = PacketCodec.of(
				(entry, buf) -> {
					OverrideKey.STREAM_CODEC.encode(buf, entry.key);
					BoneOverride.STREAM_CODEC.encode(buf, entry.override);
				},
				buf -> new Entry(OverrideKey.STREAM_CODEC.decode(buf), BoneOverride.STREAM_CODEC.decode(buf)));
	}

	public static final Id<BoneOverrideSnapshotPacket> TYPE = new Id<>(StrutConstants.id("bone_override_snapshot"));
	public static final PacketCodec<PacketByteBuf, BoneOverrideSnapshotPacket> CODEC = PacketCodec.tuple(
			PacketCodecs.VAR_INT, BoneOverrideSnapshotPacket::entityId,
			PacketCodecs.BOOL, BoneOverrideSnapshotPacket::isReplacedEntity,
			Entry.STREAM_CODEC.collect(PacketCodecs.toList()), BoneOverrideSnapshotPacket::overrides,
			BoneOverrideSnapshotPacket::new);

	@Override
	public Id<? extends CustomPayload> getId() {
		return TYPE;
	}

	@Override
	public void receiveMessage(@Nullable PlayerEntity sender, Consumer<Runnable> workQueue) {
		workQueue.accept(() -> {
			Map<OverrideKey, BoneOverride> asMap = new HashMap<>();

			for (Entry entry : this.overrides) {
				asMap.put(entry.key, entry.override);
			}

			ClientBoneOverrideStates.forEntity(this.entityId).applySnapshot(asMap);
		});
	}
}
