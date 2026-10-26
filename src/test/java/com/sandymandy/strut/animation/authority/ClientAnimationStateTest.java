package com.sandymandy.strut.animation.authority;

import com.sandymandy.strut.loading.binary.StrutBinaryFormats.LoopMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A delayed delta must never
 * move a client's mirrored state backwards. Every test below is a pure sequence-ordering question -
 * no {@code Entity}, no bootstrap, just longs
 */
class ClientAnimationStateTest {
	private static AnimationPlayback playback(long sequence) {
		return new AnimationPlayback("attack", "swing_1", 100, 0, 1, LoopMode.PLAY_ONCE, sequence);
	}

	@Test
	@DisplayName("an in-order delta is applied")
	void inOrderDeltaIsApplied() {
		ClientAnimationState state = new ClientAnimationState();

		boolean applied = state.applyDelta("attack", 1, playback(1));

		assertTrue(applied);
		assertEquals(playback(1), state.get("attack"));
	}

	/**
	 * The ordering rule, stated directly: a packet whose sequence does not beat what is already held
	 * must be rejected, and the held state must not change at all - not partially, not to some
	 * intermediate value, simply untouched
	 */
	@Test
	@DisplayName("a delayed delta (a lower sequence arriving after a higher one) is ignored, and the held state is untouched")
	void delayedDeltaIsIgnored() {
		ClientAnimationState state = new ClientAnimationState();

		state.applyDelta("attack", 5, playback(5));

		AnimationPlayback current = playback(5);
		boolean applied = state.applyDelta("attack", 2, new AnimationPlayback("attack", "some_other_clip", 999, 0, 1, LoopMode.LOOP, 2));

		assertFalse(applied, "a sequence of 2 must not beat an already-held sequence of 5");
		assertEquals(current, state.get("attack"), "the held playback must be completely unchanged");
	}

	@Test
	@DisplayName("a duplicate delta at the same sequence is ignored, not re-applied")
	void duplicateSequenceIsIgnored() {
		ClientAnimationState state = new ClientAnimationState();

		state.applyDelta("attack", 3, playback(3));

		boolean appliedAgain = state.applyDelta("attack", 3, playback(3));

		assertFalse(appliedAgain, "the same sequence arriving twice must not count as new information");
	}

	@Test
	@DisplayName("a null playback (a stop) removes the mirrored controller when its sequence is in order")
	void stopRemovesTheController() {
		ClientAnimationState state = new ClientAnimationState();

		state.applyDelta("attack", 1, playback(1));

		boolean applied = state.applyDelta("attack", 2, null);

		assertTrue(applied);
		assertNull(state.get("attack"));
	}

	/**
	 * The exact bug ServerAnimationState#stop's sequence advancement exists to prevent: a stop that
	 * arrives late must not cancel a play that arrived after it, even though the stop packet itself
	 * is "about" an earlier moment in time
	 */
	@Test
	@DisplayName("a delayed stop does not cancel a newer play")
	void delayedStopDoesNotCancelANewerPlay() {
		ClientAnimationState state = new ClientAnimationState();

		// The server issued stop (sequence 2) before play (sequence 3), but the stop's packet
		// arrives second - exactly the reordering the sequence guard exists for
		state.applyDelta("attack", 3, playback(3));

		boolean staleStopApplied = state.applyDelta("attack", 2, null);

		assertFalse(staleStopApplied);
		assertEquals(playback(3), state.get("attack"), "the newer play must survive the late-arriving stale stop");
	}

	@Test
	@DisplayName("different controllers on the same instance are guarded independently")
	void controllersAreGuardedIndependently() {
		ClientAnimationState state = new ClientAnimationState();

		state.applyDelta("attack", 10, playback(10));

		// A fresh controller's own sequence starts from nothing - it must not be compared against
		// "attack"'s unrelated sequence of 10
		boolean applied = state.applyDelta("idle", 1, new AnimationPlayback("idle", "breathe", 0, 0, 1, LoopMode.LOOP, 1));

		assertTrue(applied, "idle's own sequence of 1 has nothing to do with attack's sequence of 10");
	}

	@Test
	@DisplayName("a controller nothing has been applied for has no mirrored playback")
	void unmirroredControllerHasNoPlayback() {
		assertNull(new ClientAnimationState().get("never_seen"));
	}

	@Test
	@DisplayName("a snapshot replaces every controller wholesale")
	void snapshotReplacesEverything() {
		ClientAnimationState state = new ClientAnimationState();

		state.applyDelta("attack", 1, playback(1));

		state.applySnapshot(List.of(new AnimationPlayback("idle", "breathe", 0, 0, 1, LoopMode.LOOP, 7)));

		assertNull(state.get("attack"), "a controller the snapshot does not mention has, by definition, stopped since");
		assertNotNull(state.get("idle"));
	}

	/**
	 * A snapshot exists precisely to catch a client up from nothing - there is no "already held"
	 * sequence for an out-of-order check to be relative to, so it must never be rejected the way a
	 * delta can be, even carrying a sequence lower than one a delta already applied for the same
	 * controller (the snapshot is simply more current, by construction - it is read fresh off the
	 * server's live state at the moment tracking started)
	 */
	@Test
	@DisplayName("a snapshot is never rejected for its sequence, unlike a delta")
	void snapshotIsNeverRejectedForSequence() {
		ClientAnimationState state = new ClientAnimationState();

		state.applyDelta("attack", 100, playback(100));
		state.applySnapshot(List.of(new AnimationPlayback("attack", "swing_2", 200, 0, 1, LoopMode.PLAY_ONCE, 1)));

		assertEquals("swing_2", state.get("attack").animation());
	}

	@Test
	@DisplayName("after a snapshot, a delta must still beat the snapshot's own sequence to apply")
	void deltaAfterSnapshotStillNeedsToBeatItsSequence() {
		ClientAnimationState state = new ClientAnimationState();

		state.applySnapshot(List.of(playback(50)));

		boolean applied = state.applyDelta("attack", 10, playback(10));

		assertFalse(applied, "a snapshot still sets the bar a later delta has to clear");
	}
}
