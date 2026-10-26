package com.sandymandy.strut.event.dispatch;

import com.sandymandy.strut.event.timeline.ClipTimeline;
import com.sandymandy.strut.event.timeline.ClipTimelines;
import com.sandymandy.strut.loading.binary.StrutBinaryFormats.LoopMode;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Mapping a playback's clip name to a timeline when the server has no model to
 * say which file it lives in
 */
class ClipResolutionTest {
	private static final Identifier A = Identifier.of("m", "strut/animations/a.strutanim");
	private static final Identifier B = Identifier.of("m", "strut/animations/b.strutanim");

	private static ClipTimeline timeline(double length) {
		return new ClipTimeline(length, LoopMode.LOOP, List.of(), 0L);
	}

	@Test
	@DisplayName("a declared file is used, in preference order")
	void declaredFilesFirst() {
		ClipTimelines timelines = new ClipTimelines(List.of());

		timelines.register(A, "walk", timeline(10));
		timelines.register(B, "walk", timeline(20));

		assertEquals(20, ClipResolution.resolve(timelines, List.of(B, A), "walk").orElseThrow().lengthTicks(), 1e-9);
		assertEquals(10, ClipResolution.resolve(timelines, List.of(A, B), "walk").orElseThrow().lengthTicks(), 1e-9);
	}

	@Test
	@DisplayName("with nothing declared, a clip name unique to one file is found without any hook")
	void uniqueNameNeedsNoDeclaration() {
		ClipTimelines timelines = new ClipTimelines(List.of());

		timelines.register(A, "animation.creature.unique", timeline(10));

		assertEquals(10, ClipResolution.resolve(timelines, List.of(), "animation.creature.unique").orElseThrow().lengthTicks(), 1e-9);
	}

	@Test
	@DisplayName("a clip name two files share is not guessed at, but a declaration settles it")
	void ambiguityNeedsADeclaration() {
		ClipTimelines timelines = new ClipTimelines(List.of());

		timelines.register(A, "walk", timeline(10));
		timelines.register(B, "walk", timeline(20));

		assertTrue(ClipResolution.resolve(timelines, List.of(), "walk").isEmpty());
		assertEquals(20, ClipResolution.resolve(timelines, List.of(B), "walk").orElseThrow().lengthTicks(), 1e-9);
	}

	@Test
	@DisplayName("a declared file that lacks the clip falls back to the name lookup")
	void declaredFileWithoutTheClipFallsBack() {
		ClipTimelines timelines = new ClipTimelines(List.of());

		timelines.register(A, "walk", timeline(10));

		assertEquals(10, ClipResolution.resolve(timelines, List.of(B), "walk").orElseThrow().lengthTicks(), 1e-9);
	}

	@Test
	@DisplayName("a clip nobody has warns once, whether or not a file was declared")
	void missingWarnsOnce() {
		ClipTimelines timelines = new ClipTimelines(List.of());

		assertTrue(ClipResolution.resolve(timelines, List.of(), "ghost").isEmpty());
		assertTrue(ClipResolution.resolve(timelines, List.of(), "ghost").isEmpty());
		assertEquals(1, timelines.warningCount(), "the second lookup did not warn again");

		assertTrue(ClipResolution.resolve(timelines, List.of(A), "spirit").isEmpty());
		assertEquals(2, timelines.warningCount());
	}
}
