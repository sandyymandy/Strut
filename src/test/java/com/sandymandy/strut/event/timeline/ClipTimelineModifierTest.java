package com.sandymandy.strut.event.timeline;

import com.sandymandy.strut.event.type.EventAuthority;
import com.sandymandy.strut.event.type.EventTypeRegistry;
import com.sandymandy.strut.event.type.ReversePolicy;
import com.sandymandy.strut.event.type.StrutEventType;
import com.sandymandy.strut.event.builtin.StrutBuiltinEvents;
import com.sandymandy.strut.event.cursor.EventCursor;
import com.sandymandy.strut.loading.binary.StrutBinaryFormats.LoopMode;
import com.sandymandy.strut.loading.binary.raw.RawBinaryAnimation.RawClip;
import com.sandymandy.strut.loading.binary.raw.RawBinaryAnimation.RawEvent;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.util.Identifier;
import net.minecraft.util.Unit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Line modifiers reaching the timeline - authority precedence, the reverse flag,
 * and what each does downstream
 */
class ClipTimelineModifierTest {
	private static EventTypeRegistry registry() {
		EventTypeRegistry registry = new EventTypeRegistry();

		StrutBuiltinEvents.registerAll(registry);
		registry.register(StrutEventType.builder(Identifier.of("mymod", "strike"), PacketCodec.<PacketByteBuf, Unit>unit(Unit.INSTANCE))
				.authority(EventAuthority.SERVER).noPayload(Unit.INSTANCE).build());
		registry.register(StrutEventType.builder(Identifier.of("mymod", "whoosh"), PacketCodec.<PacketByteBuf, Unit>unit(Unit.INSTANCE))
				.authority(EventAuthority.CLIENT).noPayload(Unit.INSTANCE).build());

		return registry;
	}

	private static ClipTimeline build(int authorityByte, String instruction, List<String> warnings) {
		RawClip clip = new RawClip("c", 1f, LoopMode.LOOP, List.of(), List.of(new RawEvent(0.5f, "instruction", instruction, authorityByte)));

		return ClipTimelineFactory.fromClip(clip, "f.strutanim", registry(), warnings::add);
	}

	private static ClipTimeline build(int authorityByte, String instruction) {
		return build(authorityByte, instruction, new ArrayList<>());
	}

	@Test
	@DisplayName("with no modifier and a zero authority byte, the type's default decides")
	void typeDefaultWins() {
		assertEquals(EventAuthority.SERVER, build(0, "mymod:strike").events().get(0).authority());
		assertEquals(EventAuthority.CLIENT, build(0, "mymod:whoosh").events().get(0).authority());
		assertEquals(EventAuthority.BOTH, build(0, "swap").events().get(0).authority());
	}

	@Test
	@DisplayName("a line modifier overrides the type's default")
	void lineModifierOverridesTheType() {
		assertEquals(EventAuthority.CLIENT, build(0, "[client] mymod:strike").events().get(0).authority());
		assertEquals(EventAuthority.SERVER, build(0, "[server] mymod:whoosh").events().get(0).authority());
	}

	@Test
	@DisplayName("the entry's authority byte overrides the type's default when the line says nothing")
	void authorityByteOverridesTheType() {
		assertEquals(EventAuthority.BOTH, build(3, "mymod:strike").events().get(0).authority());
	}

	@Test
	@DisplayName("a line modifier outranks the entry's authority byte - the closer to the author's hand, the higher it ranks")
	void lineModifierOutranksTheByte() {
		assertEquals(EventAuthority.CLIENT, build(3, "[client] mymod:strike").events().get(0).authority());
	}

	@Test
	@DisplayName("lines of one keyframe are resolved independently")
	void linesResolveIndependently() {
		ClipTimeline timeline = build(0, "[client] mymod:strike\nmymod:strike\n[both] mymod:whoosh");

		assertEquals(List.of(EventAuthority.CLIENT, EventAuthority.SERVER, EventAuthority.BOTH),
				timeline.events().stream().map(TimelineEvent::authority).toList());
	}

	@Test
	@DisplayName("reverse fires in reverse; without it an event does not")
	void reverseFlag() {
		assertEquals(ReversePolicy.FIRE, build(0, "[reverse] mymod:strike").events().get(0).reverse());
		assertEquals(ReversePolicy.SKIP, build(0, "mymod:strike").events().get(0).reverse());
	}

	@Test
	@DisplayName("the reverse flag reaches the cursor: a reverse line fires backwards, a plain line is passed over")
	void reverseReachesTheCursor() {
		ClipTimeline timeline = build(0, "[reverse] mymod:strike\nswap");

		List<Integer> fired = EventCursor.resumingAt(20).advance(timeline.schedule(), 0).occurrences().stream()
				.map(occurrence -> occurrence.eventIndex()).toList();

		assertEquals(List.of(0), fired, "only the [reverse] line fires when playback runs back over the keyframe");
	}

	@Test
	@DisplayName("a bad modifier warns with the file, clip and time, and skips only that line")
	void badModifierWarnsAndSkipsOnlyItsLine() {
		List<String> warnings = new ArrayList<>();

		ClipTimeline timeline = build(0, "[servr] mymod:strike\nmymod:whoosh", warnings);

		assertEquals(1, warnings.size(), warnings.toString());
		assertTrue(warnings.get(0).contains("f.strutanim") && warnings.get(0).contains("\"c\"") && warnings.get(0).contains("servr"), warnings.get(0));
		assertEquals(1, timeline.events().size(), "the line after the bad one still made it");
		assertEquals("mymod:whoosh", timeline.events().get(0).parsed().type().id().toString());
	}

	@Test
	@DisplayName("the content hash covers modifiers: changing where or how an event fires changes the hash")
	void hashCoversModifiers() {
		long plain = build(0, "mymod:strike").contentHash();

		assertNotEquals(plain, build(0, "[client] mymod:strike").contentHash(), "authority");
		assertNotEquals(plain, build(0, "[reverse] mymod:strike").contentHash(), "reverse");
		assertEquals(plain, build(0, "[server] mymod:strike").contentHash(),
				"naming the type's own default explicitly fires identically, so it hashes identically");
	}
}
