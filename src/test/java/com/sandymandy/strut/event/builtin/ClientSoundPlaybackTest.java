package com.sandymandy.strut.event.builtin;

import com.sandymandy.strut.event.client.ClientEventContext;
import com.sandymandy.strut.event.type.EventAuthority;
import com.sandymandy.strut.event.type.ParsedEvent;
import com.sandymandy.strut.event.binding.EventBindings;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What can be checked about the playback handlers without a world. The parts that need
 * a real entity (positioning a sound, reading its binding chain) are verified live; the lookup, picking and
 * action logic they call is covered by the binding tests
 */
class ClientSoundPlaybackTest {
	private static ClientEventContext contextWithNoEntity() {
		return new ClientEventContext(null, 1, new UUID(1, 1), "base", 1, 0, 0, 10.0, 10,
				new ParsedEvent<>(StrutBuiltinEvents.MARKER, "swap"), EventAuthority.BOTH, false);
	}

	@Test
	@DisplayName("a marker with no binding files loaded returns before touching the entity - so a game without bindings pays nothing")
	void markerWithoutBindingsIsFree() {
		assertTrue(EventBindings.sets().isEmpty(), "this test assumes no other test loaded global binding sets");

		assertDoesNotThrow(() -> ClientSoundPlayback.onMarker(contextWithNoEntity(), "swap"));
	}

	@Test
	@DisplayName("the built-in marker type's client handler is that same early-out")
	void markerTypeUsesIt() {
		assertDoesNotThrow(() -> StrutBuiltinEvents.MARKER.runClientHandler(contextWithNoEntity(), "swap"));
	}

	@Test
	@DisplayName("the engine's sound and marker types both have a client handler; particle deliberately has none")
	void handlersAreWiredWhereIntended() {
		// runClientHandler on a type with no handler is a no-op, so 'has a handler' is observable as 'throws on a
		// context with no entity' for a sound (which needs one) and 'does not throw' for a particle (which has none)
		ClientEventContext noEntity = contextWithNoEntity();

		assertThrows(NullPointerException.class,
				() -> StrutBuiltinEvents.SOUND.runClientHandler(noEntity, SoundEventPayload.ofSound(Identifier.of("a", "b"), 1, 1, "")));
		assertDoesNotThrow(() -> StrutBuiltinEvents.PARTICLE.runClientHandler(noEntity, new ParticleEventPayload("a:b", "", "")));
	}
}
