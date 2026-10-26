package com.sandymandy.strut.network.packet;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.entity.player.PlayerEntity;
import org.jetbrains.annotations.Nullable;
import com.sandymandy.strut.StrutConstants;
import com.sandymandy.strut.animation.attachment.Attachment;
import com.sandymandy.strut.animation.attachment.ClientAttachmentStates;

import java.util.function.Consumer;

/**
 * One attachment slot's change for one entity, sent to everyone already tracking it - the
 * attachment-shaped twin of {@link BoneOverrideDeltaPacket}
 * <p>
 * {@code attachment} is null for a clear. {@code sequence} is carried explicitly rather than read
 * off {@code attachment.sequence()} for the identical reason {@code BoneOverrideDeltaPacket}'s own
 * field is: a clear has no {@link Attachment} to read one from
 */
public record AttachmentDeltaPacket(int entityId, boolean isReplacedEntity, String slot,
									 long sequence, @Nullable Attachment attachment) implements MultiloaderPacket {
	public static final Id<AttachmentDeltaPacket> TYPE = new Id<>(StrutConstants.id("attachment_delta"));
	public static final PacketCodec<PacketByteBuf, AttachmentDeltaPacket> CODEC = PacketCodec.of(
			(packet, buf) -> {
				buf.writeVarInt(packet.entityId);
				buf.writeBoolean(packet.isReplacedEntity);
				buf.writeString(packet.slot);
				buf.writeVarLong(packet.sequence);
				buf.writeBoolean(packet.attachment != null);

				if (packet.attachment != null)
					Attachment.STREAM_CODEC.encode(buf, packet.attachment);
			},
			buf -> new AttachmentDeltaPacket(buf.readVarInt(), buf.readBoolean(), buf.readString(),
					buf.readVarLong(), buf.readBoolean() ? Attachment.STREAM_CODEC.decode(buf) : null));

	@Override
	public Id<? extends CustomPayload> getId() {
		return TYPE;
	}

	@Override
	public void receiveMessage(@Nullable PlayerEntity sender, Consumer<Runnable> workQueue) {
		workQueue.accept(() ->
				ClientAttachmentStates.forEntity(this.entityId).applyDelta(this.slot, this.sequence, this.attachment));
	}
}
