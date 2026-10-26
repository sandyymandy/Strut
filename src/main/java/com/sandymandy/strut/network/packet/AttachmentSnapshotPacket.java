package com.sandymandy.strut.network.packet;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.entity.player.PlayerEntity;
import org.jetbrains.annotations.Nullable;
import com.sandymandy.strut.StrutConstants;
import com.sandymandy.strut.animation.attachment.Attachment;
import com.sandymandy.strut.animation.attachment.ClientAttachmentStates;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Every attachment currently set on one entity, sent when a player starts tracking it - the
 * attachment-shaped twin of {@link BoneOverrideSnapshotPacket}
 * <p>
 * Sent by {@code ServerAnimationStates#sendSnapshotTo}, folded into the same call that already sends
 * the animation and bone-override snapshots
 */
public record AttachmentSnapshotPacket(int entityId, boolean isReplacedEntity,
										List<Entry> attachments) implements MultiloaderPacket {
	/** One slot/{@link Attachment} pair - the wire shape {@link Map#entrySet()} does not have one of */
	public record Entry(String slot, Attachment attachment) {
		public static final PacketCodec<PacketByteBuf, Entry> STREAM_CODEC = PacketCodec.of(
				(entry, buf) -> {
					buf.writeString(entry.slot);
					Attachment.STREAM_CODEC.encode(buf, entry.attachment);
				},
				buf -> new Entry(buf.readString(), Attachment.STREAM_CODEC.decode(buf)));
	}

	public static final Id<AttachmentSnapshotPacket> TYPE = new Id<>(StrutConstants.id("attachment_snapshot"));
	public static final PacketCodec<PacketByteBuf, AttachmentSnapshotPacket> CODEC = PacketCodec.tuple(
			PacketCodecs.VAR_INT, AttachmentSnapshotPacket::entityId,
			PacketCodecs.BOOL, AttachmentSnapshotPacket::isReplacedEntity,
			Entry.STREAM_CODEC.collect(PacketCodecs.toList()), AttachmentSnapshotPacket::attachments,
			AttachmentSnapshotPacket::new);

	@Override
	public Id<? extends CustomPayload> getId() {
		return TYPE;
	}

	@Override
	public void receiveMessage(@Nullable PlayerEntity sender, Consumer<Runnable> workQueue) {
		workQueue.accept(() -> {
			Map<String, Attachment> asMap = new HashMap<>();

			for (Entry entry : this.attachments) {
				asMap.put(entry.slot, entry.attachment);
			}

			ClientAttachmentStates.forEntity(this.entityId).applySnapshot(asMap);
		});
	}
}
