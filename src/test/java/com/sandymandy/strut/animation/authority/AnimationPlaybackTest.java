package com.sandymandy.strut.animation.authority;

import com.sandymandy.strut.loading.binary.StrutBinaryFormats.LoopMode;
import com.sandymandy.strut.loading.binary.StrutBinaryFormats;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The authoritative clock, on its own
 * <p>
 * The property every one of these is really testing is that sampling is a pure function of the
 * current time - so a client that has been watching for an hour and one that started a moment ago
 * get the same answer for the same tick.
 */
class AnimationPlaybackTest {
	private static final double EPSILON = 1.0e-9;

	private static AnimationPlayback playback(long startGameTick, float startOffset, float rate, LoopMode loop) {
		return new AnimationPlayback("base", "test.clip", startGameTick, startOffset, rate, loop, 1);
	}

	@Test
	void aNonLoopingClipEndsAtAKnownGameTimeAndALoopNever() {
		AnimationPlayback once = new AnimationPlayback("base", "c", 100, 0f, 1f, StrutBinaryFormats.LoopMode.PLAY_ONCE, 1);
		AnimationPlayback hold = new AnimationPlayback("base", "c", 100, 10f, 2f, StrutBinaryFormats.LoopMode.HOLD_ON_LAST_FRAME, 1);
		AnimationPlayback loop = new AnimationPlayback("base", "c", 100, 0f, 1f, StrutBinaryFormats.LoopMode.LOOP, 1);

		assertEquals(140.0, once.endGameTime(40), 1e-9);
		assertFalse(once.hasEnded(40, 139));
		assertTrue(once.hasEnded(40, 140));
		assertEquals(115.0, hold.endGameTime(40), 1e-9, "starts 10 ticks in, at twice the speed: 30 clip ticks take 15");
		assertTrue(Double.isNaN(loop.endGameTime(40)));
		assertFalse(loop.hasEnded(40, 1_000_000));
		assertTrue(Double.isNaN(new AnimationPlayback("base", "c", 100, 0f, 0f, StrutBinaryFormats.LoopMode.PLAY_ONCE, 1).endGameTime(40)), "frozen never ends");
	}

	@Test
	void changingTheRateMidClipMovesTheEndAndTheNewPlaybackSaysWhere() {
		ServerAnimationState state = new ServerAnimationState();

		state.play("base", "c", 100, 0f, 1f, StrutBinaryFormats.LoopMode.PLAY_ONCE);
		assertEquals(140.0, state.get("base").endGameTime(40), 1e-9);

		// ten ticks in, twice as fast: the 30 clip ticks left take 15 game ticks
		state.setRate("base", 2f, 110);
		assertEquals(125.0, state.get("base").endGameTime(40), 1e-9);

		// and slowed to half at 120 (10 + 20 = 30 clip ticks done, 10 left, 20 game ticks)
		state.setRate("base", 0.5f, 120);
		assertEquals(140.0, state.get("base").endGameTime(40), 1e-9);
		assertTrue(state.get("base").hasEnded(40, 140));
	}

	@Test
	@DisplayName("elapsed ticks count from the start tick at the playback rate")
	void elapsedCountsFromTheStartTick() {
		AnimationPlayback playback = playback(100, 0, 1, LoopMode.LOOP);

		assertEquals(0, playback.elapsedTicks(100, 0), EPSILON, "no time has passed at the start tick");
		assertEquals(5, playback.elapsedTicks(105, 0), EPSILON);
		assertEquals(5.5, playback.elapsedTicks(105, 0.5f), EPSILON, "partial tick carries through");
	}

	@Test
	@DisplayName("the start offset shifts where in the clip playback begins")
	void startOffsetShiftsTheClip() {
		AnimationPlayback playback = playback(100, 12, 1, LoopMode.LOOP);

		assertEquals(12, playback.elapsedTicks(100, 0), EPSILON);
		assertEquals(17, playback.elapsedTicks(105, 0), EPSILON);
	}

	@Test
	@DisplayName("the playback rate scales clip time against game time")
	void rateScalesClipTime() {
		assertEquals(10, playback(0, 0, 2, LoopMode.LOOP).elapsedTicks(5, 0), EPSILON, "double rate, double progress");
		assertEquals(2.5, playback(0, 0, 0.5f, LoopMode.LOOP).elapsedTicks(5, 0), EPSILON);
	}

	@Test
	@DisplayName("a rate of zero freezes the clip at its start offset")
	void zeroRateFreezes() {
		AnimationPlayback frozen = playback(100, 7, 0, LoopMode.LOOP);

		assertEquals(7, frozen.elapsedTicks(100, 0), EPSILON);
		assertEquals(7, frozen.elapsedTicks(100_000, 0.75f), EPSILON, "still frozen an hour later");
	}

	@Test
	@DisplayName("a negative rate runs the clip backwards")
	void negativeRateRunsBackwards() {
		assertEquals(-5, playback(100, 0, -1, LoopMode.LOOP).elapsedTicks(105, 0), EPSILON);
	}

	@Test
	@DisplayName("a looping clip of no length runs on, and one that does not loop stays at its start")
	void aClipOfNoLengthRunsOnWhenItLoops() {
		assertEquals(40, playback(0, 0, 1, LoopMode.LOOP).sampleTick(0, 40, 0), EPSILON, "a clip driven by molang alone has no end to wrap at");
		assertEquals(0, playback(0, 0, 1, LoopMode.PLAY_ONCE).sampleTick(0, 40, 0), EPSILON);
	}

	@Test
	@DisplayName("a looping clip wraps, and keeps wrapping arbitrarily far out")
	void loopWraps() {
		AnimationPlayback playback = playback(0, 0, 1, LoopMode.LOOP);

		assertEquals(5, playback.sampleTick(20, 5, 0), EPSILON);
		assertEquals(0, playback.sampleTick(20, 20, 0), EPSILON, "the end of one loop is the start of the next");
		assertEquals(5, playback.sampleTick(20, 25, 0), EPSILON);
		assertEquals(5, playback.sampleTick(20, 1_000_005, 0), EPSILON, "fifty thousand loops later, same frame");
	}

	/**
	 * The late-joiner property, stated directly: two playbacks of the same clip started at the same
	 * tick must sample identically at any given moment, however long each observer has been
	 * watching - because neither answer depends on the observer at all
	 */
	@Test
	@DisplayName("a late joiner samples the same frame as someone who watched from the start")
	void lateJoinerSeesTheSameFrame() {
		AnimationPlayback shared = playback(1000, 0, 1, LoopMode.LOOP);

		double watchedFromTheStart = shared.sampleTick(20, 1234, 0.25f);
		double joinedJustNow = shared.sampleTick(20, 1234, 0.25f);

		assertEquals(watchedFromTheStart, joinedJustNow, EPSILON);
		assertEquals((1234 - 1000 + 0.25) % 20, watchedFromTheStart, EPSILON);
	}

	@Test
	@DisplayName("a reversed looping clip wraps up to the end rather than clamping at zero")
	void reversedLoopWrapsUpward() {
		AnimationPlayback reversed = playback(100, 0, -1, LoopMode.LOOP);

		assertEquals(15, reversed.sampleTick(20, 105, 0), EPSILON, "-5 wraps to 15 in a 20-tick clip");
		assertEquals(0, reversed.sampleTick(20, 120, 0), EPSILON, "exactly one loop back is the start again");
	}

	@Test
	@DisplayName("a non-looping clip clamps at each end instead of wrapping")
	void nonLoopingClamps() {
		for (LoopMode mode : new LoopMode[] {LoopMode.PLAY_ONCE, LoopMode.HOLD_ON_LAST_FRAME}) {
			AnimationPlayback playback = playback(0, 0, 1, mode);

			assertEquals(5, playback.sampleTick(20, 5, 0), EPSILON, mode + " runs normally mid-clip");
			assertEquals(20, playback.sampleTick(20, 999, 0), EPSILON, mode + " holds at the end");
			assertEquals(0, playback(100, 0, -1, mode).sampleTick(20, 200, 0), EPSILON, mode + " holds at the start");
		}
	}

	/**
	 * A clip with no length has no frame to sample. Dividing by it would produce NaN, which would
	 * propagate silently through every downstream transform rather than failing anywhere visible
	 */
	@Test
	@DisplayName("a clip of no length that does not loop samples zero rather than dividing by it")
	void zeroLengthClipIsSafe() {
		AnimationPlayback playback = playback(0, 0, 1, LoopMode.PLAY_ONCE);

		assertEquals(0, playback.sampleTick(0, 50, 0), EPSILON);
		assertEquals(0, playback.sampleTick(-1, 50, 0), EPSILON);
	}

	/**
	 * Re-basing is how the server changes a rate without the clip jumping: it records where the
	 * clip had reached as the new offset and restarts the clock from now, so the sample is
	 * continuous across the change
	 */
	@Test
	@DisplayName("re-basing onto the current sample point keeps the clip continuous across a rate change")
	void rebasingIsContinuous() {
		AnimationPlayback before = playback(0, 0, 1, LoopMode.LOOP);
		double atChange = before.elapsedTicks(30, 0);

		AnimationPlayback after = new AnimationPlayback(before.controller(), before.animation(),
				30, (float)atChange, 2, before.loopMode(), before.sequence() + 1);

		assertEquals(atChange, after.elapsedTicks(30, 0), EPSILON, "no jump at the moment of the change");
		assertEquals(atChange + 2, after.elapsedTicks(31, 0), EPSILON, "and it runs at the new rate after it");
	}
}
