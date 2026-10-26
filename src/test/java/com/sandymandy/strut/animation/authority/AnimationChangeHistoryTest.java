package com.sandymandy.strut.animation.authority;

import com.sandymandy.strut.animation.authority.ClientAnimationState.Applied;
import com.sandymandy.strut.animation.authority.ClientAnimationState.ArrivalKind;
import com.sandymandy.strut.animation.authority.ServerAnimationState.ChangeKind;
import com.sandymandy.strut.animation.authority.ServerAnimationState.ChangeRecord;
import com.sandymandy.strut.loading.binary.StrutBinaryFormats.LoopMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The change history each side keeps, and the kind of change that rides the wire
 * <p>
 * Both exist because of what the delivery simulator found: a dispatcher that only looks at the <i>current</i>
 * playback at each sweep cannot tell what happened between sweeps. Stall the server across a play and a rate
 * change and the play's events are lost; let a client apply two deltas in one tick and a chain of rate changes
 * looks like a jump. The history is what lets a dispatcher replay them in order
 */
class AnimationChangeHistoryTest {
	private static AnimationPlayback playback(long sequence) {
		return new AnimationPlayback("base", "clip", 100, 0, 1, LoopMode.LOOP, sequence);
	}

	// ---------------------------------------------------------------------------------------------
	// Server side
	// ---------------------------------------------------------------------------------------------

	@Test
	@DisplayName("every change is remembered in order, with the playback it produced")
	void serverRemembersEveryChange() {
		ServerAnimationState state = new ServerAnimationState();

		state.play("base", "clip", 100, 0, 1, LoopMode.LOOP);
		state.setRate("base", 2, 110);
		state.seek("base", 5, 120, false);
		state.stop("base", 130);

		List<ChangeRecord> all = state.changesAfter("base", 0);

		assertEquals(List.of(ChangeKind.PLAY, ChangeKind.REBASE, ChangeKind.SEEK_QUIET, ChangeKind.STOP), all.stream().map(ChangeRecord::kind).toList());
		assertEquals(List.of(100L, 110L, 120L, 130L), all.stream().map(ChangeRecord::gameTime).toList());
		assertNotNull(all.get(0).playback());
		assertNull(all.get(3).playback(), "a stop produces no playback");
		assertEquals(all.get(2).playback().sequence(), all.get(2).sequence());
	}

	@Test
	@DisplayName("changesAfter returns exactly what is newer than the sequence a caller has already dealt with")
	void changesAfterIsIncremental() {
		ServerAnimationState state = new ServerAnimationState();

		state.play("base", "clip", 100, 0, 1, LoopMode.LOOP);
		state.setRate("base", 2, 110);
		state.setRate("base", 3, 120);

		assertEquals(3, state.changesAfter("base", 0).size());
		assertEquals(2, state.changesAfter("base", 1).size());
		assertEquals(0, state.changesAfter("base", 3).size(), "nothing is newer than the latest");
		assertEquals(List.of(), state.changesAfter("never touched", 0));
	}

	@Test
	@DisplayName("a controller that has stopped still has a history, so a dispatcher is still told about the stop")
	void stoppedControllersStayInTheHistory() {
		ServerAnimationState state = new ServerAnimationState();

		state.play("base", "clip", 100, 0, 1, LoopMode.LOOP);
		state.stop("base", 110);

		assertTrue(state.controllersWithHistory().contains("base"));
		assertNull(state.get("base"));
	}

	@Test
	@DisplayName("the history is bounded: only the newest changes per controller are kept")
	void serverHistoryIsBounded() {
		ServerAnimationState state = new ServerAnimationState();

		state.play("base", "clip", 0, 0, 1, LoopMode.LOOP);

		for (int i = 1; i <= ServerAnimationState.HISTORY_CAPACITY + 10; i++)
			state.setRate("base", 1 + i, i);

		List<ChangeRecord> kept = state.changesAfter("base", 0);

		assertEquals(ServerAnimationState.HISTORY_CAPACITY, kept.size());
		assertEquals(state.lastSequenceFor("base"), kept.get(kept.size() - 1).sequence(), "the newest survives");
	}

	// ---------------------------------------------------------------------------------------------
	// The kind of change, coalesced for the wire
	// ---------------------------------------------------------------------------------------------

	@Test
	@DisplayName("a lone rate change is reported as a rate change")
	void loneRebase() {
		ServerAnimationState state = new ServerAnimationState();

		state.play("base", "clip", 100, 0, 1, LoopMode.LOOP);
		state.takePendingKind("base");
		state.setRate("base", 2, 110);

		assertEquals(ChangeKind.REBASE, state.takePendingKind("base"));
	}

	@Test
	@DisplayName("a play then a rate change in one tick is reported as a PLAY - or a client would carry the old clip's progress onto a different clip")
	void playThenRebaseIsAPlay() {
		ServerAnimationState state = new ServerAnimationState();

		state.play("base", "clip", 100, 0, 1, LoopMode.LOOP);
		state.setRate("base", 2, 100);

		assertEquals(ChangeKind.PLAY, state.takePendingKind("base"));
	}

	@Test
	@DisplayName("several rate changes in one tick are still a rate change")
	void rebaseChain() {
		ServerAnimationState state = new ServerAnimationState();

		state.play("base", "clip", 100, 0, 1, LoopMode.LOOP);
		state.takePendingKind("base");
		state.setRate("base", 2, 110);
		state.setRate("base", 3, 110);

		assertEquals(ChangeKind.REBASE, state.takePendingKind("base"));
	}

	@Test
	@DisplayName("a silent seek outranks a rate change in the same tick, and a fire-crossed seek outranks a rate change too")
	void seekOutranksRebase() {
		ServerAnimationState quiet = new ServerAnimationState();
		ServerAnimationState fire = new ServerAnimationState();

		for (ServerAnimationState state : List.of(quiet, fire)) {
			state.play("base", "clip", 100, 0, 1, LoopMode.LOOP);
			state.takePendingKind("base");
		}

		quiet.seek("base", 5, 110, false);
		quiet.setRate("base", 2, 110);
		fire.seek("base", 5, 110, true);
		fire.setRate("base", 2, 110);

		assertEquals(ChangeKind.SEEK_QUIET, quiet.takePendingKind("base"));
		assertEquals(ChangeKind.SEEK_FIRE, fire.takePendingKind("base"));
	}

	@Test
	@DisplayName("taking the kind clears it, and a controller with nothing recorded reads as a play")
	void takingClears() {
		ServerAnimationState state = new ServerAnimationState();

		state.play("base", "clip", 100, 0, 1, LoopMode.LOOP);

		assertEquals(ChangeKind.PLAY, state.takePendingKind("base"));

		state.setRate("base", 2, 110);

		assertEquals(ChangeKind.REBASE, state.takePendingKind("base"), "only the change since the last take");
		assertEquals(ChangeKind.PLAY, state.takePendingKind("never touched"));
	}

	@Test
	@DisplayName("the kind's wire codes are stable, and unknown codes are refused")
	void wireCodes() {
		for (ChangeKind kind : ChangeKind.values())
			assertEquals(kind, ChangeKind.fromWire(kind.ordinal()));

		assertEquals(ChangeKind.PLAY, ChangeKind.fromWire(0), "the order is protocol: never reorder it");
		assertEquals(ChangeKind.REBASE, ChangeKind.fromWire(1));
		assertEquals(ChangeKind.SEEK_FIRE, ChangeKind.fromWire(2));
		assertEquals(ChangeKind.SEEK_QUIET, ChangeKind.fromWire(3));
		assertEquals(ChangeKind.STOP, ChangeKind.fromWire(4));
		assertThrows(IllegalArgumentException.class, () -> ChangeKind.fromWire(5));
		assertThrows(IllegalArgumentException.class, () -> ChangeKind.fromWire(-1));
	}

	// ---------------------------------------------------------------------------------------------
	// Client side
	// ---------------------------------------------------------------------------------------------

	@Test
	@DisplayName("the client records each applied delta with its kind, in order, and a stale one leaves no trace")
	void clientRecordsAppliedDeltas() {
		ClientAnimationState state = new ClientAnimationState();

		state.applyDelta("base", 1, playback(1), ChangeKind.PLAY);
		state.applyDelta("base", 3, playback(3), ChangeKind.REBASE);
		assertFalse(state.applyDelta("base", 2, playback(2), ChangeKind.REBASE), "overtaken by 3, so stale");

		List<Applied> history = state.changesAfter("base", 0);

		assertEquals(2, history.size());
		assertEquals(List.of(ChangeKind.PLAY, ChangeKind.REBASE), history.stream().map(Applied::origin).toList());
		assertEquals(List.of(1L, 3L), history.stream().map(a -> a.playback().sequence()).toList());
		assertTrue(history.get(0).version() < history.get(1).version());
	}

	@Test
	@DisplayName("a delta with no stated kind has an unknown origin, and a snapshot never states one")
	void unknownOrigins() {
		ClientAnimationState state = new ClientAnimationState();

		state.applyDelta("a", 1, playback(1));
		state.applySnapshot(List.of(new AnimationPlayback("b", "clip", 100, 0, 1, LoopMode.LOOP, 1)), SnapshotReason.RESYNC);

		assertNull(state.changesAfter("a", 0).get(0).origin());
		assertNull(state.changesAfter("b", 0).get(0).origin());
	}

	@Test
	@DisplayName("a snapshot records a change only for what is new to the client, plus a stop for what it no longer lists")
	void snapshotRecordsOnlyNews() {
		ClientAnimationState state = new ClientAnimationState();

		state.applyDelta("base", 5, playback(5), ChangeKind.PLAY);
		state.applyDelta("gone", 1, new AnimationPlayback("gone", "clip", 100, 0, 1, LoopMode.LOOP, 1), ChangeKind.PLAY);

		long seen = state.changesAfter("base", 0).get(0).version();

		// Repeats base at the sequence already held, omits "gone"
		state.applySnapshot(List.of(playback(5)), SnapshotReason.PERIODIC);

		assertEquals(List.of(), state.changesAfter("base", seen - 1).stream().filter(a -> a.version() > seen).toList(),
				"being told the same playback again is not news");
		assertNull(state.changesAfter("gone", 0).get(state.changesAfter("gone", 0).size() - 1).playback(), "gone has stopped");
	}

	@Test
	@DisplayName("a clock stamps each change with the game time it arrived at; without one it is unknown")
	void changesAreStampedWhenTheyArrive() {
		AtomicLong now = new AtomicLong(50);
		ClientAnimationState clocked = new ClientAnimationState(now::get);
		ClientAnimationState unclocked = new ClientAnimationState();

		clocked.applyDelta("base", 1, playback(1), ChangeKind.PLAY);
		now.set(77);
		clocked.applyDelta("base", 2, playback(2), ChangeKind.REBASE);
		unclocked.applyDelta("base", 1, playback(1));

		assertEquals(List.of(50L, 77L), clocked.changesAfter("base", 0).stream().map(Applied::appliedAt).toList());
		assertEquals(Applied.UNKNOWN_TIME, unclocked.changesAfter("base", 0).get(0).appliedAt());
	}

	@Test
	@DisplayName("the client's history is bounded too")
	void clientHistoryIsBounded() {
		ClientAnimationState state = new ClientAnimationState();

		for (int i = 1; i <= ClientAnimationState.HISTORY_CAPACITY + 10; i++)
			state.applyDelta("base", i, playback(i), ChangeKind.REBASE);

		assertEquals(ClientAnimationState.HISTORY_CAPACITY, state.changesAfter("base", 0).size());
	}

	@Test
	@DisplayName("a delta records as a delta arrival")
	void deltaArrivalKind() {
		ClientAnimationState state = new ClientAnimationState();

		state.applyDelta("base", 1, playback(1), ChangeKind.PLAY);

		assertEquals(ArrivalKind.DELTA, state.changesAfter("base", 0).get(0).kind());
	}
}
