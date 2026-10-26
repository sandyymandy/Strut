package com.sandymandy.strut.animation.override;

import io.netty.buffer.Unpooled;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link BoneOverride#STREAM_CODEC}/{@link OverrideValue#STREAM_CODEC}/{@link OverrideKey#STREAM_CODEC}
 * are all hand-written rather than {@code PacketCodec.tuple} - the same reason
 * {@code AnimationPlaybackCodecTest} exists for {@code AnimationPlayback#STREAM_CODEC}: a
 * hand-written codec needs its own round-trip coverage rather than relying on the tuple helper's
 * own correctness
 */
class BoneOverrideCodecTest {
	@Test
	@DisplayName("OverrideKey round-trips bone name and property")
	void overrideKeyRoundTrips() {
		OverrideKey original = new OverrideKey("horn_left", OverrideProperty.UV_OFFSET);
		PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());

		OverrideKey.STREAM_CODEC.encode(buf, original);

		assertEquals(original, OverrideKey.STREAM_CODEC.decode(buf));
	}

	@Test
	@DisplayName("OverrideKey round-trips every property, and the earlier properties keep their wire numbers")
	void overrideKeyRoundTripsEveryProperty() {
		for (OverrideProperty property : OverrideProperty.values()) {
			OverrideKey original = new OverrideKey("bone", property);
			PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());

			OverrideKey.STREAM_CODEC.encode(buf, original);

			assertEquals(original, OverrideKey.STREAM_CODEC.decode(buf), property.name());
		}

		// The wire writes the ordinal: a new constant must be appended, never inserted, or a client and a server a build apart
		// would read each other's properties as different ones
		assertEquals(7, OverrideProperty.TEXTURE.ordinal());
		assertEquals(8, OverrideProperty.TRANSLUCENT.ordinal());
		assertEquals(9, OverrideProperty.OVERLAY_0.ordinal());
		assertEquals(13, OverrideProperty.UV_SIZE.ordinal());
	}

	@Test
	@DisplayName("overlay(layer) and overlayLayer() agree for every layer, and reject a layer out of range")
	void overlayLayerHelpers() {
		for (int layer = 0; layer < OverrideProperty.MAX_OVERLAYS; layer++) {
			assertEquals(layer, OverrideProperty.overlay(layer).overlayLayer());
			assertEquals(OverrideValue.Shape.IDENTIFIER, OverrideProperty.overlay(layer).valueShape());
		}

		assertEquals(-1, OverrideProperty.TEXTURE.overlayLayer());
		assertEquals(-1, OverrideProperty.TRANSLUCENT.overlayLayer());
		Assertions.assertThrows(IllegalArgumentException.class, () -> OverrideProperty.overlay(-1));
		Assertions.assertThrows(IllegalArgumentException.class, () -> OverrideProperty.overlay(OverrideProperty.MAX_OVERLAYS));
	}

	@Test
	@DisplayName("OverrideValue round-trips every shape")
	void overrideValueRoundTripsEveryShape() {
		roundTripsValue(new OverrideValue.BoolValue(true));
		roundTripsValue(new OverrideValue.Vec3Value(1.5f, -2.25f, 0f));
		roundTripsValue(new OverrideValue.Vec2Value(0.25f, -0.75f));
		roundTripsValue(new OverrideValue.ColorValue(0xFF00FF00));
		roundTripsValue(new OverrideValue.TextureValue(Identifier.of("strut", "textures/entity/test_dummy.png")));
	}

	private static void roundTripsValue(OverrideValue original) {
		PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());

		OverrideValue.STREAM_CODEC.encode(buf, original);

		assertEquals(original, OverrideValue.STREAM_CODEC.decode(buf));
	}

	@Test
	@DisplayName("BoneOverride round-trips every field, including a negative transition and a large sequence")
	void boneOverrideRoundTripsEveryField() {
		BoneOverride original = new BoneOverride(new OverrideValue.Vec3Value(1f, 2f, 3f), OverrideScope.CONTROLLER,
				-5, 0.75f, -1f, 987_654_321L);
		PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());

		BoneOverride.STREAM_CODEC.encode(buf, original);

		assertEquals(original, BoneOverride.STREAM_CODEC.decode(buf));
	}

	@Test
	@DisplayName("BoneOverride round-trips every OverrideScope")
	void boneOverrideRoundTripsEveryScope() {
		for (OverrideScope scope : OverrideScope.values()) {
			BoneOverride original = new BoneOverride(new OverrideValue.BoolValue(false), scope, 0, 1f, 0f, 0);
			PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());

			BoneOverride.STREAM_CODEC.encode(buf, original);

			assertEquals(scope, BoneOverride.STREAM_CODEC.decode(buf).scope());
		}
	}
}
