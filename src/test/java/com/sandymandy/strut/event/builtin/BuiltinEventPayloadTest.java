package com.sandymandy.strut.event.builtin;

import com.sandymandy.strut.loading.binary.StrutBinaryFormats;
import io.netty.buffer.Unpooled;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.util.Identifier;
import net.minecraft.util.Unit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The payloads of the engine's own event types - text form and wire form
 */
class BuiltinEventPayloadTest {
	private static final String SEP = StrutBinaryFormats.EVENT_FIELD_SEPARATOR;

	private static <P> P roundTrip(PacketCodec<PacketByteBuf, P> codec, P value) {
		PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());

		codec.encode(buf, value);

		P decoded = codec.decode(buf);

		assertEquals(0, buf.readableBytes(), "the codec must consume exactly what it wrote");

		return decoded;
	}

	@Test
	@DisplayName("a namespaced sound id is a complete payload with default volume and pitch")
	void soundDefaults() {
		SoundEventPayload payload = SoundEventPayload.parse("minecraft:entity.cow.ambient");

		assertEquals(Identifier.of("minecraft", "entity.cow.ambient"), payload.sound());
		assertEquals(1f, payload.volume());
		assertEquals(1f, payload.pitch());
		assertEquals("", payload.locator());
	}

	@Test
	@DisplayName("volume, pitch and locator are read in order")
	void soundFullForm() {
		SoundEventPayload payload = SoundEventPayload.parse("mymod:cry 0.5 1.25 head");

		assertEquals(0.5f, payload.volume());
		assertEquals(1.25f, payload.pitch());
		assertEquals("head", payload.locator());
	}

	@Test
	@DisplayName("bad sound payloads say what is wrong")
	void soundRejections() {
		assertTrue(assertThrows(IllegalArgumentException.class, () -> SoundEventPayload.parse("")).getMessage().contains("sound id"));
		assertTrue(assertThrows(IllegalArgumentException.class, () -> SoundEventPayload.parse("BAD:x")).getMessage().contains("valid sound id"));
		assertTrue(assertThrows(IllegalArgumentException.class, () -> SoundEventPayload.parse("a:b loud")).getMessage().contains("volume"));
		assertTrue(assertThrows(IllegalArgumentException.class, () -> SoundEventPayload.parse("a:b 1 0")).getMessage().contains("pitch"));
		assertTrue(assertThrows(IllegalArgumentException.class, () -> SoundEventPayload.parse("a:b -1")).getMessage().contains("volume"));
		assertTrue(assertThrows(IllegalArgumentException.class, () -> SoundEventPayload.parse("a:b NaN")).getMessage().contains("finite"));
		assertTrue(assertThrows(IllegalArgumentException.class, () -> SoundEventPayload.parse("a:b 1 1 head extra")).getMessage().contains("at most 4"));
	}

	@Test
	@DisplayName("the sound payload round-trips every field")
	void soundRoundTrip() {
		SoundEventPayload original = SoundEventPayload.ofSound(Identifier.of("mymod", "cry"), 0.75f, 1.5f, "head");

		assertEquals(original, roundTrip(SoundEventPayload.CODEC, original));
	}

	@Test
	@DisplayName("a particle payload reads the legacy separator-delimited triple, so old clips need no conversion")
	void particleLegacyForm() {
		ParticleEventPayload payload = ParticleEventPayload.parse("mod:spark" + SEP + "hand" + SEP + "v.x = 1;");

		assertEquals("mod:spark", payload.effect());
		assertEquals("hand", payload.locator());
		assertEquals("v.x = 1;", payload.script());
	}

	@Test
	@DisplayName("trailing particle fields may be omitted, and empty ones mean absent")
	void particleOptionalFields() {
		assertEquals(new ParticleEventPayload("mod:spark", "", ""), ParticleEventPayload.parse("mod:spark"));
		assertEquals(new ParticleEventPayload("mod:spark", "", "s"), ParticleEventPayload.parse("mod:spark" + SEP + SEP + "s"));
	}

	@Test
	@DisplayName("a particle needs an effect, and at most three fields")
	void particleRejections() {
		assertThrows(IllegalArgumentException.class, () -> ParticleEventPayload.parse(""));
		assertThrows(IllegalArgumentException.class, () -> ParticleEventPayload.parse(SEP + "hand"));
		assertThrows(IllegalArgumentException.class, () -> ParticleEventPayload.parse("a" + SEP + "b" + SEP + "c" + SEP + "d"));
	}

	@Test
	@DisplayName("the particle payload round-trips every field")
	void particleRoundTrip() {
		ParticleEventPayload original = new ParticleEventPayload("mod:spark", "hand", "v.x = 1;");

		assertEquals(original, roundTrip(ParticleEventPayload.CODEC, original));
	}

	@Test
	@DisplayName("marker, loop and end payloads round-trip")
	void smallPayloadsRoundTrip() {
		assertEquals("swap_end", roundTrip(StrutBuiltinEvents.MARKER.codec(), "swap_end"));
		assertEquals(123_456_789_012L, roundTrip(StrutBuiltinEvents.LOOP.codec(), 123_456_789_012L));
		assertEquals(Unit.INSTANCE, roundTrip(StrutBuiltinEvents.END.codec(), Unit.INSTANCE));
	}

	@Test
	@DisplayName("marker names are validated and lower-cased")
	void markerParsing() {
		assertEquals("becomebare", StrutBuiltinEvents.MARKER.parse("  becomeBare "));
		assertThrows(IllegalArgumentException.class, () -> StrutBuiltinEvents.MARKER.parse("two words"));
		assertThrows(IllegalArgumentException.class, () -> StrutBuiltinEvents.MARKER.parse(""));
	}
}
