package com.sandymandy.strut.event;

import com.sandymandy.strut.StrutConstants;
import com.sandymandy.strut.event.builtin.SoundEventPayload;
import com.sandymandy.strut.event.builtin.StrutBuiltinEvents;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.util.Identifier;
import net.minecraft.util.Unit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import com.sandymandy.strut.event.type.EventAuthority;
import com.sandymandy.strut.event.type.EventCategory;
import com.sandymandy.strut.event.type.EventTypeRegistry;
import com.sandymandy.strut.event.type.ReversePolicy;
import com.sandymandy.strut.event.type.StrutEventType;
import com.sandymandy.strut.event.type.StrutEventTypes;

/**
 * Registering event types, and resolving a clip's instruction text against
 * them - the load-time half of the system, where every failure must be a message for the author
 * rather than an exception that stops a reload
 */
class EventTypeRegistryTest {
	private static final Identifier THRUST = Identifier.of("mymod", "strike");
	private static final Identifier CUMFLATE = Identifier.of("mymod", "inflate");

	private static StrutEventType<Unit> strike() {
		return StrutEventType.builder(THRUST, PacketCodec.<PacketByteBuf, Unit>unit(Unit.INSTANCE))
				.category(EventCategory.GAMEPLAY).authority(EventAuthority.SERVER).noPayload(Unit.INSTANCE).build();
	}

	private static StrutEventType<Float> inflate() {
		return StrutEventType.builder(CUMFLATE, PacketCodecs.FLOAT.cast())
				.category(EventCategory.GAMEPLAY).authority(EventAuthority.BOTH).parser(Float::parseFloat).build();
	}

	private static EventTypeRegistry registry() {
		EventTypeRegistry registry = new EventTypeRegistry();

		StrutBuiltinEvents.registerAll(registry);
		registry.register(strike());
		registry.register(inflate());

		return registry;
	}

	@Test
	@DisplayName("a registered type can be looked up by id, with the defaults it was built with")
	void registeredTypesAreFound() {
		StrutEventType<?> type = registry().get(THRUST);

		assertNotNull(type);
		assertEquals(EventAuthority.SERVER, type.defaultAuthority());
		assertEquals(EventCategory.GAMEPLAY, type.category());
		assertEquals(ReversePolicy.SKIP, type.defaultReverse(), "reverse is skip unless a type opts in");
	}

	@Test
	@DisplayName("registering an id twice is a loud error naming it")
	void duplicateIdIsRejected() {
		EventTypeRegistry registry = registry();

		IllegalStateException ex = assertThrows(IllegalStateException.class, () -> registry.register(strike()));

		assertTrue(ex.getMessage().contains("mymod:strike"));
	}

	@Test
	@DisplayName("registering after the freeze is a loud error, and freezing twice is harmless")
	void frozenRegistryRejectsRegistration() {
		EventTypeRegistry registry = registry();

		registry.freeze();
		registry.freeze();

		assertTrue(registry.isFrozen());

		IllegalStateException ex = assertThrows(IllegalStateException.class,
				() -> registry.register(StrutEventType.builder(Identifier.of("mymod", "late"), PacketCodecs.STRING.cast()).build()));

		assertTrue(ex.getMessage().contains("mymod:late"));
	}

	@Test
	@DisplayName("the global registry ships the engine's five types before anyone registers")
	void globalRegistryHasTheEngineTypes() {
		for (String path : List.of("marker", "sound", "particle", "loop", "end"))
			assertNotNull(StrutEventTypes.get(Identifier.of(StrutConstants.MODID, path)), "strut:" + path);
	}

	@Test
	@DisplayName("engine types the author cannot name are marked so, and the rest are authorable")
	void authorability() {
		assertFalse(StrutBuiltinEvents.LOOP.isAuthorable());
		assertFalse(StrutBuiltinEvents.END.isAuthorable());
		assertTrue(StrutBuiltinEvents.MARKER.isAuthorable());
		assertTrue(StrutBuiltinEvents.SOUND.isAuthorable());
		assertTrue(StrutBuiltinEvents.PARTICLE.isAuthorable());
	}

	@Test
	@DisplayName("sound and particle stay client authority - a server-played sound would play twice")
	void audioAndVisualAreClientOnly() {
		assertEquals(EventAuthority.CLIENT, StrutBuiltinEvents.SOUND.defaultAuthority());
		assertEquals(EventAuthority.CLIENT, StrutBuiltinEvents.PARTICLE.defaultAuthority());
		assertFalse(StrutBuiltinEvents.SOUND.defaultAuthority().runsOnServer());
	}

	@Test
	@DisplayName("a keyframe with several lines resolves to typed events in line order")
	void severalLinesResolveInOrder() {
		List<EventTypeRegistry.Resolution> resolved = registry().resolveAll("mymod:strike\nmymod:inflate 2.5\nswap_end");

		assertEquals(3, resolved.size());
		assertTrue(resolved.stream().allMatch(EventTypeRegistry.Resolution::isOk));
		assertEquals(THRUST, resolved.get(0).event().type().id());
		assertEquals(2.5f, resolved.get(1).event().payload());
		assertEquals(Identifier.of(StrutConstants.MODID, "marker"), resolved.get(2).event().type().id());
		assertEquals("swap_end", resolved.get(2).event().payload());
	}

	@Test
	@DisplayName("an unknown type is an error message, not an exception, and does not stop the other lines")
	void unknownTypeIsAMessage() {
		List<EventTypeRegistry.Resolution> resolved = registry().resolveAll("nope:missing\nmymod:strike");

		assertFalse(resolved.get(0).isOk());
		assertTrue(resolved.get(0).error().contains("nope:missing"));
		assertTrue(resolved.get(1).isOk(), "one bad line must not poison its neighbours");
	}

	@Test
	@DisplayName("a bad payload names the type and the offending text")
	void badPayloadNamesTheType() {
		EventTypeRegistry.Resolution resolution = registry().resolveAll("mymod:inflate lots").get(0);

		assertFalse(resolution.isOk());
		assertTrue(resolution.error().contains("mymod:inflate"), resolution.error());
		assertTrue(resolution.error().contains("lots"), resolution.error());
	}

	@Test
	@DisplayName("a no-payload type rejects a payload")
	void noPayloadTypeRejectsAPayload() {
		EventTypeRegistry.Resolution resolution = registry().resolveAll("mymod:strike hard").get(0);

		assertFalse(resolution.isOk());
		assertTrue(resolution.error().contains("takes no payload"), resolution.error());
	}

	@Test
	@DisplayName("an engine-only type cannot be authored in a clip")
	void engineOnlyTypeCannotBeAuthored() {
		EventTypeRegistry.Resolution resolution = registry().resolveAll("strut:loop 3").get(0);

		assertFalse(resolution.isOk());
		assertTrue(resolution.error().contains("cannot be authored"), resolution.error());
	}

	@Test
	@DisplayName("authority wire codes: 0 is the type's default, never server")
	void authorityWireCodes() {
		assertNull(EventAuthority.fromWire(0), "0 must mean 'use the type default' so every shipped file keeps its meaning");
		assertEquals(EventAuthority.SERVER, EventAuthority.fromWire(1));
		assertEquals(EventAuthority.CLIENT, EventAuthority.fromWire(2));
		assertEquals(EventAuthority.BOTH, EventAuthority.fromWire(3));
		assertThrows(IllegalArgumentException.class, () -> EventAuthority.fromWire(4));

		for (EventAuthority authority : EventAuthority.values())
			assertEquals(authority, EventAuthority.fromWire(EventAuthority.toWire(authority)));

		assertEquals(0, EventAuthority.toWire(null));
	}

	@Test
	@DisplayName("the sound payload keeps its id unresolved, so an unknown sound decodes fine")
	void soundPayloadDoesNotResolveTheSound() {
		SoundEventPayload payload = SoundEventPayload.parse("mymod:does.not.exist");

		assertEquals(Identifier.of("mymod", "does.not.exist"), payload.sound());
	}
}
