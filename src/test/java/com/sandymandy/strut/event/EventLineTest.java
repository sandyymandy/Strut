package com.sandymandy.strut.event;

import com.sandymandy.strut.StrutConstants;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import com.sandymandy.strut.event.type.EventLine;

/**
 * The grammar of the custom instruction channel, on its own.
 */
class EventLineTest {
	@Test
	@DisplayName("a namespaced type id followed by text is a type and its payload")
	void namespacedLine() {
		EventLine.Line line = EventLine.parse("mymod:inflate 2.5").get(0);

		assertNull(line.error());
		assertEquals(Identifier.of("mymod", "inflate"), line.type());
		assertEquals("2.5", line.payload());
	}

	@Test
	@DisplayName("the payload keeps its inner spacing but loses the padding around it")
	void payloadIsStrippedNotCollapsed() {
		assertEquals("a  b", EventLine.parse("  mymod:say    a  b   ").get(0).payload());
	}

	@Test
	@DisplayName("a type with no payload has an empty one")
	void noPayloadLine() {
		EventLine.Line line = EventLine.parse("mymod:fade_to_black").get(0);

		assertNull(line.error());
		assertEquals("", line.payload());
	}

	@Test
	@DisplayName("a bare word is a strut:marker named by the word, lower-cased")
	void bareWordIsAMarker() {
		EventLine.Line line = EventLine.parse("becomeBare").get(0);

		assertNull(line.error());
		assertEquals(Identifier.of(StrutConstants.MODID, "marker"), line.type());
		assertEquals("becomebare", line.payload());
	}

	@Test
	@DisplayName("a bare word cannot carry a payload - naming a type is how an event gets one")
	void bareWordWithPayloadIsRejected() {
		EventLine.Line line = EventLine.parse("swap now").get(0);

		assertNotNull(line.error());
		assertNull(line.type());
		assertTrue(line.error().contains("bare marker name takes no payload"), line.error());
	}

	@Test
	@DisplayName("lines split on any line ending and blank lines are skipped")
	void linesSplitAndBlanksAreSkipped() {
		List<EventLine.Line> lines = EventLine.parse("swap\r\n\n  \nmymod:strike\nend");

		assertEquals(3, lines.size());
		assertEquals("swap", lines.get(0).payload());
		assertEquals(Identifier.of("mymod", "strike"), lines.get(1).type());
		assertEquals("end", lines.get(2).payload());
	}

	@Test
	@DisplayName("an empty or all-blank keyframe has no events")
	void emptyTextHasNoLines() {
		assertTrue(EventLine.parse("").isEmpty());
		assertTrue(EventLine.parse("  \n \t\n").isEmpty());
	}

	@Test
	@DisplayName("an invalid type id or marker name is an error line, not an exception")
	void invalidTokensAreErrorLines() {
		assertNotNull(EventLine.parse("Bad Namespace:x").get(0).error());
		assertNotNull(EventLine.parse("sw@p").get(0).error());
	}

	@Test
	@DisplayName("legacy comma lists are not split - a bare 'a, b' is an error, not two markers")
	void commaListsAreNotGuessedAt() {
		EventLine.Line line = EventLine.parse("lipsound, strike").get(0);

		assertNotNull(line.error(), "splitting 'lipsound, strike' is exactly the string parsing typed events remove");
	}
}
