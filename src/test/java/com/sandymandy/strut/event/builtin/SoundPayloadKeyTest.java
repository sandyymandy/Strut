package com.sandymandy.strut.event.builtin;

import io.netty.buffer.Unpooled;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A sound payload is either a sound id or a binding key - the difference between a
 * clip that says which sound and one that says which <i>cue</i>, leaving the sound to the character playing it
 */
class SoundPayloadKeyTest {
	@Test
	@DisplayName("a bare word is a binding key, lower-cased - it is not read as a minecraft: sound")
	void bareWordIsAKey() {
		SoundEventPayload payload = SoundEventPayload.parse("anal_startMSG1");

		assertTrue(payload.isBindingKey());
		assertEquals("anal_startmsg1", payload.key());
		assertNull(payload.sound());
	}

	@Test
	@DisplayName("a namespaced word is a sound id, not a key")
	void namespacedWordIsASound() {
		SoundEventPayload payload = SoundEventPayload.parse("mymod:ava.giggle");

		assertFalse(payload.isBindingKey());
		assertEquals(Identifier.of("mymod", "ava.giggle"), payload.sound());
		assertNull(payload.key());
	}

	@Test
	@DisplayName("every real key from a consumer mod's scenes parses as a key")
	void realKeysParse() {
		for (String key : new String[] {"anal_startMSG1", "doggyintroMSG5", "lipsound", "pound", "bjiMSG11", "cowcreaturestartMSG0",
				"carry_introMSG1", "missionary_slowMSG1", "bedRustle", "doggyMoan", "orgasm1"}) {
			SoundEventPayload payload = SoundEventPayload.parse(key);

			assertEquals(key.toLowerCase(), payload.key(), key);
		}
	}

	@Test
	@DisplayName("volume, pitch and locator follow a key just as they follow a sound")
	void modifiersFollowAKey() {
		SoundEventPayload payload = SoundEventPayload.parse("lipsound 0.5 1.5 head");

		assertEquals("lipsound", payload.key());
		assertEquals(0.5f, payload.volume());
		assertEquals(1.5f, payload.pitch());
		assertEquals("head", payload.locator());
	}

	@Test
	@DisplayName("a comma list is not a key - the old 'a, b' habit is an error, not two guessed cues")
	void commaListsAreRejected() {
		assertThrows(IllegalArgumentException.class, () -> SoundEventPayload.parse("lipsound, strike"));
		assertThrows(IllegalArgumentException.class, () -> SoundEventPayload.parse("lipsound,"));
	}

	@Test
	@DisplayName("a key with characters no key may have says so, and points at how to write a sound id")
	void invalidKeyExplainsItself() {
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> SoundEventPayload.parse("bad!key"));

		assertTrue(ex.getMessage().contains("binding key"), ex.getMessage());
		assertTrue(ex.getMessage().contains("needs a namespace"), ex.getMessage());
	}

	@Test
	@DisplayName("a payload names exactly one of a sound and a key")
	void exactlyOne() {
		assertThrows(IllegalArgumentException.class, () -> new SoundEventPayload(null, null, 1, 1, ""));
		assertThrows(IllegalArgumentException.class, () -> new SoundEventPayload(Identifier.of("a", "b"), "k", 1, 1, ""));
	}

	@Test
	@DisplayName("both forms round-trip over the wire")
	void roundTrips() {
		for (SoundEventPayload original : new SoundEventPayload[] {SoundEventPayload.ofKey("Anal_Start", 0.5f, 2f, "head"),
				SoundEventPayload.ofSound(Identifier.of("mymod", "cry"), 1f, 1f, "")}) {
			PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());

			SoundEventPayload.CODEC.encode(buf, original);

			assertEquals(original, SoundEventPayload.CODEC.decode(buf));
			assertEquals(0, buf.readableBytes());
		}
	}
}
