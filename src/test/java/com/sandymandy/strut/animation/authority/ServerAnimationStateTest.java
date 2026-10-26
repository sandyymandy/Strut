package com.sandymandy.strut.animation.authority;

import com.sandymandy.strut.loading.binary.StrutBinaryFormats.LoopMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ServerAnimationStateTest {
	@Test
	@DisplayName("play starts a controller playing, retrievable by name")
	void playStartsAController() {
		ServerAnimationState state = new ServerAnimationState();

		state.play("attack", "swing_1", 100, 0, 1, LoopMode.PLAY_ONCE);

		AnimationPlayback playback = state.get("attack");

		assertNotNull(playback);
		assertEquals("attack", playback.controller());
		assertEquals("swing_1", playback.animation());
		assertEquals(100, playback.startGameTick());
	}

	@Test
	@DisplayName("an untouched controller has no playback")
	void untouchedControllerHasNoPlayback() {
		assertNull(new ServerAnimationState().get("idle"));
	}

	@Test
	@DisplayName("stop clears a controller's playback")
	void stopClearsPlayback() {
		ServerAnimationState state = new ServerAnimationState();

		state.play("attack", "swing_1", 100, 0, 1, LoopMode.PLAY_ONCE);
		state.stop("attack");

		assertNull(state.get("attack"));
	}

	@Test
	@DisplayName("stopping a controller that was never playing is a no-op, not an error")
	void stoppingAnUnplayedControllerIsANoOp() {
		ServerAnimationState state = new ServerAnimationState();

		assertDoesNotThrow(() -> state.stop("never_played"));
		assertTrue(state.drainDirty().isEmpty(), "nothing observable changed, so nothing should be dirty");
	}

	/**
	 * A stop leaves no {@link AnimationPlayback} to read a sequence off of, but it is still a
	 * mutation and needs its own place in the ordering - otherwise a delayed stop packet could
	 * arrive after a newer play and incorrectly cancel it. {@link ServerAnimationState#lastSequenceFor}
	 * is the one place that sequence survives to be read from when building the delta for it
	 */
	@Test
	@DisplayName("stop advances the controller's sequence, even with no playback left to carry it")
	void stopAdvancesTheSequence() {
		ServerAnimationState state = new ServerAnimationState();

		state.play("attack", "swing_1", 100, 0, 1, LoopMode.PLAY_ONCE);
		long playSequence = state.get("attack").sequence();

		state.stop("attack");

		assertTrue(state.lastSequenceFor("attack") > playSequence,
				"the stop must get a sequence strictly after the play it is cancelling");
	}

	@Test
	@DisplayName("lastSequenceFor is 0 for a controller nothing has ever touched")
	void lastSequenceForIsZeroWhenUntouched() {
		assertEquals(0, new ServerAnimationState().lastSequenceFor("never_touched"));
	}

	@Test
	@DisplayName("each play, on the same controller, gets a strictly increasing sequence")
	void sequenceIncreasesMonotonically() {
		ServerAnimationState state = new ServerAnimationState();

		state.play("attack", "swing_1", 100, 0, 1, LoopMode.PLAY_ONCE);
		long first = state.get("attack").sequence();

		state.play("attack", "swing_2", 110, 0, 1, LoopMode.PLAY_ONCE);
		long second = state.get("attack").sequence();

		assertTrue(second > first, "sequence " + second + " must beat " + first);
	}

	@Test
	@DisplayName("different controllers on the same instance sequence independently")
	void controllersSequenceIndependently() {
		ServerAnimationState state = new ServerAnimationState();

		state.play("attack", "swing_1", 100, 0, 1, LoopMode.PLAY_ONCE);
		state.play("attack", "swing_2", 110, 0, 1, LoopMode.PLAY_ONCE);
		state.play("idle", "breathe", 100, 0, 1, LoopMode.LOOP);

		assertEquals(1, state.get("idle").sequence(), "a controller's own sequence starts fresh, unaffected by another controller's history");
	}

	/**
	 * setRate's whole point is that the clip does not jump - see AnimationPlayback's own javadoc on
	 * why re-basing is what keeps it continuous. This pins that the state layer actually re-bases
	 * rather than just swapping the rate field in place, which would retroactively rewrite how the
	 * clip got to where it already is
	 */
	@Test
	@DisplayName("setRate re-bases onto the current sample point rather than jumping")
	void setRateRebasesContinuously() {
		ServerAnimationState state = new ServerAnimationState();

		state.play("attack", "swing_1", 0, 0, 1, LoopMode.LOOP);

		double sampleBeforeChange = state.get("attack").elapsedTicks(30, 0);

		state.setRate("attack", 2, 30);

		AnimationPlayback afterChange = state.get("attack");

		assertEquals(sampleBeforeChange, afterChange.elapsedTicks(30, 0), 1.0e-6,
				"no jump at the instant the rate changes");
		assertEquals(sampleBeforeChange + 2, afterChange.elapsedTicks(31, 0), 1.0e-6,
				"and it runs at the new rate immediately after");
	}

	@Test
	@DisplayName("setRate on a controller that is not playing anything is a no-op")
	void setRateOnAnUnplayedControllerIsANoOp() {
		ServerAnimationState state = new ServerAnimationState();

		state.setRate("never_played", 2, 100);

		assertNull(state.get("never_played"));
		assertTrue(state.drainDirty().isEmpty());
	}

	@Test
	@DisplayName("setRate advances the sequence, same as play")
	void setRateAdvancesSequence() {
		ServerAnimationState state = new ServerAnimationState();

		state.play("attack", "swing_1", 0, 0, 1, LoopMode.LOOP);
		long beforeRateChange = state.get("attack").sequence();

		state.setRate("attack", 2, 30);

		assertTrue(state.get("attack").sequence() > beforeRateChange);
	}

	@Test
	@DisplayName("snapshot returns every controller's current playback")
	void snapshotReturnsEveryController() {
		ServerAnimationState state = new ServerAnimationState();

		state.play("attack", "swing_1", 100, 0, 1, LoopMode.PLAY_ONCE);
		state.play("idle", "breathe", 100, 0, 1, LoopMode.LOOP);

		assertEquals(2, state.snapshot().size());
	}

	@Test
	@DisplayName("a stopped controller does not appear in the snapshot")
	void snapshotOmitsStoppedControllers() {
		ServerAnimationState state = new ServerAnimationState();

		state.play("attack", "swing_1", 100, 0, 1, LoopMode.PLAY_ONCE);
		state.stop("attack");

		assertTrue(state.snapshot().isEmpty());
	}

	@Test
	@DisplayName("drainDirty reports what changed and clears its own marks")
	void drainDirtyReportsAndClears() {
		ServerAnimationState state = new ServerAnimationState();

		state.play("attack", "swing_1", 100, 0, 1, LoopMode.PLAY_ONCE);
		state.play("idle", "breathe", 100, 0, 1, LoopMode.LOOP);

		assertEquals(Set.of("attack", "idle"), state.drainDirty());
		assertTrue(state.drainDirty().isEmpty(), "drained marks must not report again");
	}

	@Test
	@DisplayName("touching the same controller twice before a drain reports it once")
	void repeatedTouchesReportOnce() {
		ServerAnimationState state = new ServerAnimationState();

		state.play("attack", "swing_1", 100, 0, 1, LoopMode.PLAY_ONCE);
		state.play("attack", "swing_2", 110, 0, 1, LoopMode.PLAY_ONCE);

		assertEquals(Set.of("attack"), state.drainDirty());
	}

	@Test
	@DisplayName("a play-then-stop before a drain is still reported dirty, even though nothing plays now")
	void playThenStopIsStillDirty() {
		ServerAnimationState state = new ServerAnimationState();

		state.play("attack", "swing_1", 100, 0, 1, LoopMode.PLAY_ONCE);
		state.stop("attack");

		assertEquals(Set.of("attack"), state.drainDirty(),
				"a receiver holding an earlier snapshot still needs to hear this stopped");
	}
}
