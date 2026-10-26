package com.sandymandy.strut.animation.attachment;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

/**
 * One entity-declared attachment: a vanilla {@code Item} or {@code Block} riding a bone's world
 * matrix. It covers a held item or a block stuck to a bone - not cameras, passengers, particles, sound origins or nameplates,
 * which stay future work
 * <p>
 * Exactly one of {@link #item()}/{@link #block()} is expected to be set - a caller builds one or
 * the other through {@code EntityAttachments}' own typed setters, never both
 * <p>
 * {@link #item()} is a plain {@link Identifier}, not a full {@code ItemStack} - deliberately: a full
 * stack's own {@code PacketCodec} needs a {@code RegistryByteBuf} (components can reference
 * registries), which every other packet in this codebase avoids by staying on plain
 * {@code PacketByteBuf}. An attachment is a <em>visual</em> - "this bone holds an axe" - not
 * inventory state, so losing NBT/component fidelity (enchantment glint aside, which the renderer can
 * still ask the constructed stack for) costs nothing it actually needs. {@code itemCount}
 * exists only because {@code ItemStack}'s own rendering reads it for stack-size-sensitive models;
 * it is not gameplay-meaningful here
 *
 * @param sequence Monotonic per slot, per entity - the same delayed-packet guard
 *                 {@code BoneOverride#sequence()} provides, spent by {@link ClientAttachmentState#applyDelta}
 */
public record Attachment(String bone, @Nullable Identifier item, int itemCount, @Nullable Identifier block,
						  float offsetX, float offsetY, float offsetZ,
						  float rotX, float rotY, float rotZ,
						  float scaleX, float scaleY, float scaleZ, long sequence, int hand) {
	/** How an item is drawn: as the game draws an item with no holder, with none of its display transform */
	public static final int HAND_NONE = 0;
	/** As the game draws the item a player holds in the right hand: the item model's own third-person transform, so a tool sits in the hand the way it does for a player */
	public static final int HAND_RIGHT = 1;
	/** As {@link #HAND_RIGHT} for the left hand */
	public static final int HAND_LEFT = 2;

	public Attachment(String bone, @Nullable Identifier item, int itemCount, @Nullable Identifier block, float offsetX, float offsetY, float offsetZ, float rotX, float rotY, float rotZ,
			float scaleX, float scaleY, float scaleZ, long sequence) {
		this(bone, item, itemCount, block, offsetX, offsetY, offsetZ, rotX, rotY, rotZ, scaleX, scaleY, scaleZ, sequence, HAND_NONE);
	}

	public static final PacketCodec<PacketByteBuf, Attachment> STREAM_CODEC = PacketCodec.of(
			(attachment, buf) -> {
				buf.writeString(attachment.bone);
				buf.writeBoolean(attachment.item != null);

				if (attachment.item != null) {
					buf.writeIdentifier(attachment.item);
					buf.writeVarInt(attachment.itemCount);
				}

				buf.writeBoolean(attachment.block != null);

				if (attachment.block != null)
					buf.writeIdentifier(attachment.block);

				buf.writeFloat(attachment.offsetX);
				buf.writeFloat(attachment.offsetY);
				buf.writeFloat(attachment.offsetZ);
				buf.writeFloat(attachment.rotX);
				buf.writeFloat(attachment.rotY);
				buf.writeFloat(attachment.rotZ);
				buf.writeFloat(attachment.scaleX);
				buf.writeFloat(attachment.scaleY);
				buf.writeFloat(attachment.scaleZ);
				buf.writeVarLong(attachment.sequence);
				buf.writeByte(attachment.hand);
			},
			buf -> {
				String bone = buf.readString();
				Identifier item = buf.readBoolean() ? buf.readIdentifier() : null;
				int itemCount = item != null ? buf.readVarInt() : 1;
				Identifier block = buf.readBoolean() ? buf.readIdentifier() : null;
				float offsetX = buf.readFloat();
				float offsetY = buf.readFloat();
				float offsetZ = buf.readFloat();
				float rotX = buf.readFloat();
				float rotY = buf.readFloat();
				float rotZ = buf.readFloat();
				float scaleX = buf.readFloat();
				float scaleY = buf.readFloat();
				float scaleZ = buf.readFloat();
				long sequence = buf.readVarLong();
				int hand = buf.readByte();

				return new Attachment(bone, item, itemCount, block, offsetX, offsetY, offsetZ, rotX, rotY, rotZ, scaleX, scaleY, scaleZ, sequence, hand);
			});
}
