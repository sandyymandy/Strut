package com.sandymandy.strut.animation.authority;

import com.sandymandy.strut.animation.authority.ServerAnimationState.Change;
import com.sandymandy.strut.animation.authority.ServerAnimationState.ChangeKind;
import com.sandymandy.strut.loading.binary.StrutBinaryFormats.LoopMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * How a playback last changed, and the mutators the event dispatcher needs -
 * timed stop, seek, and starting a clip at an instant between ticks
 */
class ServerAnimationStateChangeTest {
	private static ServerAnimationState playing() {
		ServerAnimationState state = new ServerAnimationState();

		state.play("base", "clip", 100, 0, 1, LoopMode.LOOP);

		return state;
	}

	@Test
	@DisplayName("nothing has changed on a controller nobody touched")
	void untouchedHasNoChange() {
		assertNull(new ServerAnimationState().lastChange("base"));
	}

	@Test
	@DisplayName("play records a PLAY change at its start tick, carrying the playback's sequence")
	void playIsRecorded() {
		ServerAnimationState state = playing();
		Change change = state.lastChange("base");

		assertEquals(ChangeKind.PLAY, change.kind());
		assertEquals(100, change.gameTime());
		assertEquals(state.get("base").sequence(), change.sequence());
	}

	@Test
	@DisplayName("setRate records a REBASE at the game time it took effect")
	void setRateIsARebase() {
		ServerAnimationState state = playing();

		state.setRate("base", 2, 130);

		assertEquals(new Change(state.get("base").sequence(), ChangeKind.REBASE, 130), state.lastChange("base"));
	}

	@Test
	@DisplayName("stop with a time records it, and stop without one says the time is unknown")
	void stopRecordsItsTime() {
		ServerAnimationState timed = playing();
		ServerAnimationState untimed = playing();

		timed.stop("base", 140);
		untimed.stop("base");

		assertEquals(ChangeKind.STOP, timed.lastChange("base").kind());
		assertEquals(140, timed.lastChange("base").gameTime());
		assertEquals(timed.lastSequenceFor("base"), timed.lastChange("base").sequence(), "a stop's sequence is readable, though it has no playback");
		assertEquals(Change.UNKNOWN_TIME, untimed.lastChange("base").gameTime());
		assertNull(timed.get("base"));
	}

	@Test
	@DisplayName("stopping something that is not playing changes nothing and is not marked dirty")
	void noOpStopRecordsNothing() {
		ServerAnimationState state = new ServerAnimationState();

		state.stop("base", 5);

		assertNull(state.lastChange("base"));
		assertTrue(state.drainDirty().isEmpty());
	}

	@Test
	@DisplayName("seek keeps the clip and rate, lands on the clip tick, and is a new sequence")
	void seekLandsOnTheTick() {
		ServerAnimationState state = playing();
		long before = state.get("base").sequence();

		state.seek("base", 25, 110, false);

		AnimationPlayback playback = state.get("base");

		assertEquals("clip", playback.animation());
		assertEquals(1f, playback.playbackRate());
		assertEquals(25, playback.elapsedTicks(110, 0), 1e-9, "at the seek instant it is at the requested tick");
		assertEquals(35, playback.elapsedTicks(120, 0), 1e-9, "and runs on from there at the same rate");
		assertTrue(playback.sequence() > before);
		assertTrue(state.drainDirty().contains("base"), "a seek reaches clients like any other change");
	}

	@Test
	@DisplayName("a seek records whether crossed events should fire")
	void seekKinds() {
		ServerAnimationState quiet = playing();
		ServerAnimationState firing = playing();

		quiet.seek("base", 25, 110, false);
		firing.seek("base", 25, 110, true);

		assertEquals(ChangeKind.SEEK_QUIET, quiet.lastChange("base").kind());
		assertEquals(ChangeKind.SEEK_FIRE, firing.lastChange("base").kind());
	}

	@Test
	@DisplayName("seeking a controller that is not playing does nothing")
	void seekOnNothingIsANoOp() {
		ServerAnimationState state = new ServerAnimationState();

		state.seek("base", 25, 110, true);

		assertNull(state.get("base"));
		assertNull(state.lastChange("base"));
	}

	@Test
	@DisplayName("playAt starts on the next whole tick with an offset that makes the clip exact at the instant")
	void playAtIsExact() {
		for (double instant : new double[] {110.0, 110.25, 110.5, 110.999, 100.001}) {
			ServerAnimationState state = new ServerAnimationState();

			state.playAt("base", "clip", instant, 2f, LoopMode.PLAY_ONCE);

			AnimationPlayback playback = state.get("base");
			long whole = (long)Math.floor(instant);
			float partial = (float)(instant - whole);

			assertEquals(0, playback.elapsedTicks(whole, partial), 1e-4, "clip is at its start at " + instant);
			assertTrue(playback.startOffsetTicks() >= 0, "never begins before the start of the clip");
			assertTrue(playback.startGameTick() >= instant, "the playback starts at or after the instant, so the client never samples before it");
			assertEquals(ChangeKind.PLAY, state.lastChange("base").kind());
		}
	}

	@Test
	@DisplayName("an instant exactly on a tick starts exactly there, with no offset")
	void playAtOnATick() {
		ServerAnimationState state = new ServerAnimationState();

		state.playAt("base", "clip", 120.0, 1f, LoopMode.LOOP);

		assertEquals(120, state.get("base").startGameTick());
		assertEquals(0f, state.get("base").startOffsetTicks());
	}
}
