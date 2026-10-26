package com.sandymandy.strut.animation.attachment;

import io.netty.buffer.Unpooled;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link Attachment#STREAM_CODEC} is hand-written, not {@code PacketCodec.tuple} - the same reason
 * {@code BoneOverrideCodecTest} exists for {@code BoneOverride#STREAM_CODEC}: a hand-written codec
 * needs its own round-trip coverage rather than relying on the tuple helper's own correctness
 */
class AttachmentCodecTest {
	@Test
	@DisplayName("round-trips an item attachment, including itemCount")
	void roundTripsAnItemAttachment() {
		Attachment original = new Attachment("hand_r", Identifier.of("minecraft", "diamond_sword"), 3, null,
				0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 1.1f, 1.2f, 1.3f, 42L);

		assertEquals(original, roundTrip(original));
	}

	@Test
	@DisplayName("round-trips how a held item is drawn")
	void roundTripsTheHandAnItemIsDrawnIn() {
		Attachment original = new Attachment("hand_r", Identifier.of("minecraft", "bow"), 1, null, 0f, 0f, 0f, 0f, 0f, 0f, 1f, 1f, 1f, 7L, Attachment.HAND_LEFT);

		assertEquals(original, roundTrip(original));
		assertEquals(Attachment.HAND_NONE, roundTrip(new Attachment("b", Identifier.of("minecraft", "bow"), 1, null, 0f, 0f, 0f, 0f, 0f, 0f, 1f, 1f, 1f, 7L)).hand());
	}

	@Test
	@DisplayName("round-trips a block attachment, with item left null")
	void roundTripsABlockAttachment() {
		// itemCount is only ever written/read alongside a non-null item (see Attachment's own
		// STREAM_CODEC) - meaningless when item is null, so the decoded value always comes back as
		// the field's own default (1) regardless of what the original happened to hold there
		Attachment original = new Attachment("head", null, 1, Identifier.of("minecraft", "diamond_block"),
				0f, 0f, 0f, 0f, 0f, 0f, 1f, 1f, 1f, 7L);
		Attachment decoded = roundTrip(original);

		assertNull(decoded.item());
		assertEquals(Identifier.of("minecraft", "diamond_block"), decoded.block());
		assertEquals(original, decoded);
	}

	@Test
	@DisplayName("round-trips a negative rotation and a large sequence")
	void roundTripsNegativeRotationAndLargeSequence() {
		Attachment original = new Attachment("hand_l", Identifier.of("minecraft", "torch"), 1, null,
				0f, 0f, 0f, -1.5f, -3.14159f, 0f, 1f, 1f, 1f, 987_654_321L);

		assertEquals(original, roundTrip(original));
	}

	private static Attachment roundTrip(Attachment original) {
		PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());

		Attachment.STREAM_CODEC.encode(buf, original);

		return Attachment.STREAM_CODEC.decode(buf);
	}
}
