package com.sandymandy.strut.event;

import com.sandymandy.strut.StrutConstants;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import com.sandymandy.strut.event.type.EventAuthority;
import com.sandymandy.strut.event.type.EventLine;

/**
 * The modifier list at the start of an instruction line - how an author sets
 * where one event runs and whether it fires in reverse, from the text box Blockbench already gives them
 */
class EventLineModifierTest {
	private static EventLine.Line only(String text) {
		List<EventLine.Line> lines = EventLine.parse(text);

		assertEquals(1, lines.size(), text);

		return lines.get(0);
	}

	@Test
	@DisplayName("a line with no modifiers asks for nothing, leaving the type's own defaults in charge")
	void noModifiersAskForNothing() {
		EventLine.Line line = only("mymod:strike");

		assertNull(line.authority());
		assertFalse(line.reverse());
	}

	@Test
	@DisplayName("server, client and both each set the authority")
	void authorityModifiers() {
		assertEquals(EventAuthority.SERVER, only("[server] mymod:strike").authority());
		assertEquals(EventAuthority.CLIENT, only("[client] mymod:strike").authority());
		assertEquals(EventAuthority.BOTH, only("[both] mymod:strike").authority());
	}

	@Test
	@DisplayName("reverse asks to fire when playback crosses the event backwards, and sets no authority")
	void reverseModifier() {
		EventLine.Line line = only("[reverse] mymod:strike");

		assertTrue(line.reverse());
		assertNull(line.authority());
	}

	@Test
	@DisplayName("modifiers combine, separated by commas, spaces or both, in any order and case")
	void modifiersCombine() {
		for (String text : new String[] {"[server, reverse] mymod:strike", "[reverse server] mymod:strike", "[ SERVER ,  Reverse ] mymod:strike",
				"[reverse,server]mymod:strike"}) {
			EventLine.Line line = only(text);

			assertEquals(EventAuthority.SERVER, line.authority(), text);
			assertTrue(line.reverse(), text);
			assertEquals(Identifier.of("mymod", "strike"), line.type(), text);
		}
	}

	@Test
	@DisplayName("modifiers and a payload together: the payload starts after the type id, untouched")
	void modifiersWithPayload() {
		EventLine.Line line = only("[both, reverse] mymod:inflate 2.5");

		assertEquals(Identifier.of("mymod", "inflate"), line.type());
		assertEquals("2.5", line.payload());
		assertEquals(EventAuthority.BOTH, line.authority());
		assertTrue(line.reverse());
	}

	@Test
	@DisplayName("a bare marker word takes modifiers too")
	void bareMarkerWithModifiers() {
		EventLine.Line line = only("[server] Swap_End");

		assertEquals(Identifier.of(StrutConstants.MODID, "marker"), line.type());
		assertEquals("swap_end", line.payload());
		assertEquals(EventAuthority.SERVER, line.authority());
	}

	@Test
	@DisplayName("brackets in a payload are payload, not modifiers - only a bracket at the very start of a line counts")
	void bracketsInAPayloadAreLeftAlone() {
		EventLine.Line line = only("mymod:say [server] hello");

		assertEquals("[server] hello", line.payload());
		assertNull(line.authority(), "the modifier-looking text after the type id must not change how the event is delivered");
	}

	@Test
	@DisplayName("each line of a keyframe has its own modifiers")
	void modifiersArePerLine() {
		List<EventLine.Line> lines = EventLine.parse("[server] mymod:strike\n[client] mymod:whoosh\nswap");

		assertEquals(EventAuthority.SERVER, lines.get(0).authority());
		assertEquals(EventAuthority.CLIENT, lines.get(1).authority());
		assertNull(lines.get(2).authority());
	}

	@Test
	@DisplayName("two different authorities on one line conflict; naming the same one twice is harmless")
	void conflictingAuthorities() {
		EventLine.Line conflict = only("[server, client] mymod:strike");

		assertNotNull(conflict.error());
		assertTrue(conflict.error().contains("conflicting"), conflict.error());

		assertNull(only("[server, server] mymod:strike").error());
	}

	@Test
	@DisplayName("an unknown modifier names itself and lists the valid ones")
	void unknownModifier() {
		EventLine.Line line = only("[servr] mymod:strike");

		assertNotNull(line.error());
		assertTrue(line.error().contains("servr"), line.error());
		assertTrue(line.error().contains("server, client, both and reverse"), line.error());
	}

	@Test
	@DisplayName("malformed modifier lists are errors, never exceptions")
	void malformedLists() {
		assertTrue(only("[server mymod:strike").error().contains("closing ]"));
		assertTrue(only("[] mymod:strike").error().contains("empty modifier list"));
		assertTrue(only("[  ] mymod:strike").error().contains("empty modifier list"));
		assertTrue(only("[server]").error().contains("nothing after"));
		assertTrue(only("[server]   ").error().contains("nothing after"));
	}
}
