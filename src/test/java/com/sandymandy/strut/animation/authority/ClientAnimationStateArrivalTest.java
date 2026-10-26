package com.sandymandy.strut.animation.authority;

import com.sandymandy.strut.animation.authority.ClientAnimationState.Arrival;
import com.sandymandy.strut.animation.authority.ClientAnimationState.ArrivalKind;
import com.sandymandy.strut.loading.binary.StrutBinaryFormats.LoopMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * How a playback reached the client - what the event dispatcher reads to
 * decide whether a clip owes anyone its history
 */
class ClientAnimationStateArrivalTest {
	private static AnimationPlayback playback(String controller, long sequence) {
		return new AnimationPlayback(controller, "clip", 100, 0, 1, LoopMode.LOOP, sequence);
	}

	@Test
	@DisplayName("a playback that arrived in a delta is recorded as a delta")
	void deltaArrival() {
		ClientAnimationState state = new ClientAnimationState();

		state.applyDelta("base", 3, playback("base", 3));

		assertEquals(new Arrival(3, ArrivalKind.DELTA), state.arrivalOf("base"));
	}

	@Test
	@DisplayName("start-tracking is an introduction, an explicit resync is a refresh, and a periodic resend is a refresh only once the client has heard something")
	void snapshotArrivalKinds() {
		ClientAnimationState tracking = new ClientAnimationState();
		ClientAnimationState resync = new ClientAnimationState();
		ClientAnimationState periodicAfterDelta = new ClientAnimationState();

		tracking.applySnapshot(List.of(playback("base", 1)), SnapshotReason.START_TRACKING);
		resync.applySnapshot(List.of(playback("base", 1)), SnapshotReason.RESYNC);
		periodicAfterDelta.applyDelta("other", 1, playback("other", 1));
		periodicAfterDelta.applySnapshot(List.of(playback("base", 1), playback("other", 1)), SnapshotReason.PERIODIC);

		assertEquals(ArrivalKind.SNAPSHOT_START_TRACKING, tracking.arrivalOf("base").kind());
		assertEquals(ArrivalKind.SNAPSHOT_REFRESH, resync.arrivalOf("base").kind(), "the client asked for it, to fill in what it missed");
		assertEquals(ArrivalKind.SNAPSHOT_REFRESH, periodicAfterDelta.arrivalOf("base").kind(), "the client was already tracking, so this is news about something it missed");
	}

	@Test
	@DisplayName("the first snapshot a client ever receives is its introduction even if the server called it periodic - its own start-tracking snapshot was overtaken")
	void firstSnapshotIsAlwaysAnIntroduction() {
		ClientAnimationState state = new ClientAnimationState();

		state.applySnapshot(List.of(playback("base", 4)), SnapshotReason.PERIODIC);

		assertEquals(ArrivalKind.SNAPSHOT_START_TRACKING, state.arrivalOf("base").kind(), "a client meeting an entity for the first time is owed no history");

		// ...and the start-tracking snapshot that then arrives late changes nothing: it is the same playback
		state.applySnapshot(List.of(playback("base", 4)), SnapshotReason.START_TRACKING);

		assertEquals(new Arrival(4, ArrivalKind.SNAPSHOT_START_TRACKING), state.arrivalOf("base"));
	}

	@Test
	@DisplayName("a snapshot that repeats a playback already held does not turn it into a new arrival")
	void repeatedPlaybackKeepsItsOriginalArrival() {
		ClientAnimationState state = new ClientAnimationState();

		state.applyDelta("base", 5, playback("base", 5));
		state.applySnapshot(List.of(playback("base", 5)), SnapshotReason.PERIODIC);

		assertEquals(new Arrival(5, ArrivalKind.DELTA), state.arrivalOf("base"),
				"being told the same clip again must not make it look freshly started");
	}

	@Test
	@DisplayName("a snapshot that introduces a different sequence is a fresh arrival")
	void newSequenceIsAFreshArrival() {
		ClientAnimationState state = new ClientAnimationState();

		state.applyDelta("base", 5, playback("base", 5));
		state.applySnapshot(List.of(playback("base", 8)), SnapshotReason.RESYNC);

		assertEquals(new Arrival(8, ArrivalKind.SNAPSHOT_REFRESH), state.arrivalOf("base"));
	}

	@Test
	@DisplayName("a stop, or a snapshot that no longer lists the controller, clears its arrival")
	void stopsClearArrivals() {
		ClientAnimationState state = new ClientAnimationState();

		state.applyDelta("base", 1, playback("base", 1));
		state.applyDelta("other", 1, playback("other", 1));

		state.applyDelta("base", 2, null);
		assertNull(state.arrivalOf("base"));

		state.applySnapshot(List.of(), SnapshotReason.PERIODIC);
		assertNull(state.arrivalOf("other"));
	}

	@Test
	@DisplayName("a stale delta records no arrival and changes nothing")
	void staleDeltaIsNotAnArrival() {
		ClientAnimationState state = new ClientAnimationState();

		state.applyDelta("base", 5, playback("base", 5));

		assertFalse(state.applyDelta("base", 4, playback("base", 4)));
		assertEquals(new Arrival(5, ArrivalKind.DELTA), state.arrivalOf("base"));
	}

	@Test
	@DisplayName("playbacks() lists every controller currently mirrored")
	void playbacksLists() {
		ClientAnimationState state = new ClientAnimationState();

		state.applyDelta("a", 1, playback("a", 1));
		state.applyDelta("b", 1, playback("b", 1));

		assertEquals(2, state.playbacks().size());
	}

	@Test
	@DisplayName("every snapshot reason has a stable wire code that round-trips, and unknown codes are refused")
	void snapshotReasonWireCodes() {
		for (SnapshotReason reason : SnapshotReason.values())
			assertEquals(reason, SnapshotReason.fromWire(reason.ordinal()));

		assertEquals(SnapshotReason.START_TRACKING, SnapshotReason.fromWire(0), "the order is protocol: never reorder it");
		assertEquals(SnapshotReason.RESYNC, SnapshotReason.fromWire(1));
		assertEquals(SnapshotReason.PERIODIC, SnapshotReason.fromWire(2));
		assertThrows(IllegalArgumentException.class, () -> SnapshotReason.fromWire(3));
		assertThrows(IllegalArgumentException.class, () -> SnapshotReason.fromWire(-1));
	}
}
