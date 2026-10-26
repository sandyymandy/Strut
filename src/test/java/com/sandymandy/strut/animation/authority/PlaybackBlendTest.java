package com.sandymandy.strut.animation.authority;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * When a server-driven controller blends into a new clip (the client's part of a transition): the server only plays clips, the client decides how it looks
 */
class PlaybackBlendTest {
	@Test
	@DisplayName("the first clip a controller sees does not blend, and neither does the same clip again")
	void firstAndSameClipsDoNotBlend() {
		PlaybackBlend<String> blend = new PlaybackBlend<>();

		assertEquals(-1, blend.observe("idle", 100, 4, () -> "pose"), "nothing to blend from");
		assertEquals(-1, blend.observe("idle", 101, 4, () -> "pose"));
		// a rate change or a seek is the same clip
		assertEquals(-1, blend.observe("idle", 102, 4, () -> "pose"));
		assertNull(blend.from());
	}

	@Test
	@DisplayName("a different clip blends from the pose captured when the change was seen, over the controller's length, and then stops")
	void aNewClipBlendsFromTheCapturedPose() {
		PlaybackBlend<String> blend = new PlaybackBlend<>();
		AtomicInteger captures = new AtomicInteger();

		blend.observe("idle", 100, 4, () -> "unused");

		assertEquals(0.0, blend.observe("strike", 110.25, 4, () -> {
			captures.incrementAndGet();

			return "idle pose";
		}), 1e-9, "counted from when the change was seen, not from the server's start tick");
		assertEquals("idle pose", blend.from());
		assertEquals(1.5, blend.observe("strike", 111.75, 4, () -> "later"), 1e-9);
		assertEquals("idle pose", blend.from(), "the same pose all the way through");
		assertEquals(3.9, blend.observe("strike", 114.15, 4, () -> "later"), 1e-9);
		assertEquals(-1, blend.observe("strike", 114.25, 4, () -> "later"), "four ticks done");
		assertNull(blend.from());
		assertEquals(1, captures.get(), "the pose is taken once, on the frame the blend starts");
	}

	@Test
	@DisplayName("a transition length of 0 is a cut, and a clip that changes in the middle of a blend starts a new one from the pose then")
	void zeroLengthCutsAndAChangeMidBlendRestarts() {
		PlaybackBlend<String> cut = new PlaybackBlend<>();

		cut.observe("a", 0, 0, () -> "p");
		assertEquals(-1, cut.observe("b", 10, 0, () -> "p"));

		PlaybackBlend<String> blend = new PlaybackBlend<>();

		blend.observe("a", 0, 4, () -> "p");
		assertEquals(0.0, blend.observe("b", 10, 4, () -> "a to b halfway"), 1e-9);
		assertEquals(2.0, blend.observe("b", 12, 4, () -> "x"), 1e-9);
		assertEquals(0.0, blend.observe("c", 12, 4, () -> "pose mid blend"), 1e-9);
		assertEquals("pose mid blend", blend.from());
	}

	@Test
	@DisplayName("a stopped controller forgets its clip, so what plays next is a first clip")
	void stoppingForgets() {
		PlaybackBlend<String> blend = new PlaybackBlend<>();

		blend.observe("a", 0, 4, () -> "p");
		blend.reset();
		assertEquals(-1, blend.observe("b", 5, 4, () -> "p"));
	}
}
