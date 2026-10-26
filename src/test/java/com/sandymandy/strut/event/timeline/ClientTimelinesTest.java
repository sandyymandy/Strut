package com.sandymandy.strut.event.timeline;

import com.sandymandy.strut.loading.binary.StrutBinaryFormats.LoopMode;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The client's own timelines, kept apart from the server's
 */
class ClientTimelinesTest {
	private static final Identifier FILE = Identifier.of("testns", "strut/animations/entity/thing.strutanim");

	private static ClipTimeline timeline(double length) {
		return new ClipTimeline(length, LoopMode.LOOP, List.of(), 0L);
	}

	@AfterEach
	void clearClientTimelines() {
		ClipTimelines.setClientTimelines(Map.of());
	}

	@Test
	@DisplayName("the client's timelines are found by file and clip once a reload has set them")
	void setTimelinesAreFound() {
		ClipTimelines.setClientTimelines(Map.of(FILE, Map.of("walk", timeline(20))));

		assertEquals(20, ClipTimelines.client().find(FILE, "walk").orElseThrow().lengthTicks(), 1e-9);
		assertEquals(20, ClipTimelines.client().findUnique("walk").orElseThrow().lengthTicks(), 1e-9);
	}

	@Test
	@DisplayName("a reload replaces the client's timelines, so a clip a pack no longer has stops being found")
	void reloadReplaces() {
		ClipTimelines.setClientTimelines(Map.of(FILE, Map.of("walk", timeline(20))));
		ClipTimelines.setClientTimelines(Map.of(FILE, Map.of("run", timeline(10))));

		assertTrue(ClipTimelines.client().find(FILE, "walk").isEmpty());
		assertTrue(ClipTimelines.client().find(FILE, "run").isPresent());
	}

	@Test
	@DisplayName("a reload forgets which missing clips were already warned about")
	void reloadResetsWarnings() {
		ClipTimelines client = ClipTimelines.client();

		assertTrue(client.warnMissingOnce(FILE, "ghost"));
		assertFalse(client.warnMissingOnce(FILE, "ghost"));

		ClipTimelines.setClientTimelines(Map.of());

		assertTrue(client.warnMissingOnce(FILE, "ghost"), "a fixed pack that breaks again should warn again");
	}

	@Test
	@DisplayName("the client instance is separate from the server's: a code registration on one is invisible to the other")
	void clientAndServerAreSeparate() {
		ClipTimelines server = new ClipTimelines(List.of());

		ClipTimelines.client().register(FILE, "only_on_client", timeline(5));

		assertTrue(ClipTimelines.client().find(FILE, "only_on_client").isPresent());
		assertTrue(server.find(FILE, "only_on_client").isEmpty());
	}
}
