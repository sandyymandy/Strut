package com.sandymandy.strut.animation.authority;

import com.sandymandy.strut.loading.binary.StrutBinaryFormats.LoopMode;
import io.netty.buffer.Unpooled;
import net.minecraft.network.PacketByteBuf;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link AnimationPlayback#STREAM_CODEC} is hand-written rather than {@code PacketCodec.tuple}
 * (which this record's seven fields overrun), so it gets its own round-trip coverage rather than
 * relying on the tuple helper's own correctness
 */
class AnimationPlaybackCodecTest {
	@Test
	@DisplayName("round-trips every field, including a negative rate and a large sequence")
	void roundTripsEveryField() {
		AnimationPlayback original = new AnimationPlayback("attack", "swing_1", 123_456_789L,
				3.5f, -2.25f, LoopMode.HOLD_ON_LAST_FRAME, 987_654_321L);

		PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());

		AnimationPlayback.STREAM_CODEC.encode(buf, original);
		AnimationPlayback decoded = AnimationPlayback.STREAM_CODEC.decode(buf);

		assertEquals(original, decoded);
	}

	@Test
	@DisplayName("round-trips each LoopMode")
	void roundTripsEveryLoopMode() {
		for (LoopMode mode : LoopMode.values()) {
			AnimationPlayback original = new AnimationPlayback("c", "a", 0, 0, 1, mode, 0);
			PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());

			AnimationPlayback.STREAM_CODEC.encode(buf, original);

			assertEquals(mode, AnimationPlayback.STREAM_CODEC.decode(buf).loopMode());
		}
	}
}
