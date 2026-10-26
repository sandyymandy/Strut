package com.sandymandy.strut.animation.override;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ClientBoneOverrideState}'s two-store contract: the sequence-guarded mirror
 * ({@code applyDelta}/{@code applySnapshot}, the identical guard {@code ClientAnimationStateTest}
 * already covers for animation playback) plus the one thing that has no animation-playback
 * equivalent at all - {@link ClientBoneOverrideState#resolve}'s "local beats synced" precedence,
 * the answer to a per-viewer decision the server cannot
 * make on a client's behalf
 */
class ClientBoneOverrideStateTest {
	private static final OverrideKey HORN_HIDDEN = new OverrideKey("horn_left", OverrideProperty.HIDDEN);

	private static BoneOverride hidden(boolean value, int priority, long sequence) {
		return new BoneOverride(new OverrideValue.BoolValue(value), OverrideScope.PERSISTENT, priority, 1f, 0f, sequence);
	}

	@Test
	@DisplayName("an in-order delta is applied to the synced store")
	void inOrderDeltaIsApplied() {
		ClientBoneOverrideState state = new ClientBoneOverrideState();

		boolean applied = state.applyDelta(HORN_HIDDEN, 1, hidden(true, 0, 1));

		assertTrue(applied);
		assertEquals(hidden(true, 0, 1), state.resolve("horn_left", OverrideProperty.HIDDEN));
	}

	@Test
	@DisplayName("a delayed delta (a lower sequence arriving after a higher one) is ignored")
	void delayedDeltaIsIgnored() {
		ClientBoneOverrideState state = new ClientBoneOverrideState();

		state.applyDelta(HORN_HIDDEN, 5, hidden(true, 0, 5));

		boolean applied = state.applyDelta(HORN_HIDDEN, 2, hidden(false, 0, 2));

		assertFalse(applied, "a sequence of 2 must not beat an already-held sequence of 5");
		assertEquals(hidden(true, 0, 5), state.resolve("horn_left", OverrideProperty.HIDDEN));
	}

	@Test
	@DisplayName("a null override (a clear) removes the synced entry when its sequence is in order")
	void clearRemovesTheSyncedEntry() {
		ClientBoneOverrideState state = new ClientBoneOverrideState();

		state.applyDelta(HORN_HIDDEN, 1, hidden(true, 0, 1));

		boolean applied = state.applyDelta(HORN_HIDDEN, 2, null);

		assertTrue(applied);
		assertNull(state.resolve("horn_left", OverrideProperty.HIDDEN));
	}

	@Test
	@DisplayName("a snapshot replaces every synced entry wholesale, never rejected for sequence")
	void snapshotReplacesEverything() {
		ClientBoneOverrideState state = new ClientBoneOverrideState();

		state.applyDelta(HORN_HIDDEN, 100, hidden(true, 0, 100));
		state.applySnapshot(Map.of(HORN_HIDDEN, hidden(false, 0, 1)));

		assertEquals(hidden(false, 0, 1), state.resolve("horn_left", OverrideProperty.HIDDEN),
				"a snapshot is more current by construction, even at a lower sequence than an already-applied delta");
	}

	@Test
	@DisplayName("resolve returns the synced value when nothing local is set")
	void resolveFallsBackToSyncedWhenNoLocal() {
		ClientBoneOverrideState state = new ClientBoneOverrideState();

		state.applyDelta(HORN_HIDDEN, 1, hidden(true, 0, 1));

		assertEquals(hidden(true, 0, 1), state.resolve("horn_left", OverrideProperty.HIDDEN));
	}

	@Test
	@DisplayName("resolve returns the local value when nothing synced is set")
	void resolveFallsBackToLocalWhenNoSynced() {
		ClientBoneOverrideState state = new ClientBoneOverrideState();

		state.setLocal("horn_left", OverrideProperty.HIDDEN, new OverrideValue.BoolValue(true), OverrideScope.FRAME, 0, 1f, 0f);

		BoneOverride resolved = state.resolve("horn_left", OverrideProperty.HIDDEN);

		assertNotNull(resolved);
		assertEquals(new OverrideValue.BoolValue(true), resolved.value());
	}

	/**
	 * The load-bearing rule: local wins a tie against synced, so a client
	 * override does not need to guess a priority number to beat the server's. Both here sit at
	 * priority 0 - the default every {@code EntityBoneOverrides} setter uses when a caller does not
	 * ask for anything else - and local still wins
	 */
	@Test
	@DisplayName("resolve prefers local over synced at equal priority")
	void resolvePrefersLocalOnATie() {
		ClientBoneOverrideState state = new ClientBoneOverrideState();

		state.applyDelta(HORN_HIDDEN, 1, hidden(true, 0, 1));
		state.setLocal("horn_left", OverrideProperty.HIDDEN, new OverrideValue.BoolValue(false), OverrideScope.FRAME, 0, 1f, 0f);

		BoneOverride resolved = state.resolve("horn_left", OverrideProperty.HIDDEN);

		assertNotNull(resolved);
		assertEquals(new OverrideValue.BoolValue(false), resolved.value(), "local must win the tie");
	}

	@Test
	@DisplayName("resolve prefers synced when it has strictly higher priority than local")
	void resolvePrefersSyncedWhenItOutranksLocal() {
		ClientBoneOverrideState state = new ClientBoneOverrideState();

		state.applyDelta(HORN_HIDDEN, 1, hidden(true, 10, 1));
		state.setLocal("horn_left", OverrideProperty.HIDDEN, new OverrideValue.BoolValue(false), OverrideScope.FRAME, 0, 1f, 0f);

		BoneOverride resolved = state.resolve("horn_left", OverrideProperty.HIDDEN);

		assertNotNull(resolved);
		assertEquals(new OverrideValue.BoolValue(true), resolved.value(),
				"a synced override at strictly higher priority must still win over local");
	}

	@Test
	@DisplayName("resolve prefers local when it has strictly higher priority than synced")
	void resolvePrefersLocalWhenItOutranksSynced() {
		ClientBoneOverrideState state = new ClientBoneOverrideState();

		state.applyDelta(HORN_HIDDEN, 1, hidden(true, 0, 1));
		state.setLocal("horn_left", OverrideProperty.HIDDEN, new OverrideValue.BoolValue(false), OverrideScope.FRAME, 10, 1f, 0f);

		BoneOverride resolved = state.resolve("horn_left", OverrideProperty.HIDDEN);

		assertNotNull(resolved);
		assertEquals(new OverrideValue.BoolValue(false), resolved.value());
	}

	@Test
	@DisplayName("resolve returns null when neither store has this key")
	void resolveIsNullWhenNothingIsSet() {
		assertNull(new ClientBoneOverrideState().resolve("never_touched", OverrideProperty.HIDDEN));
	}

	@Test
	@DisplayName("clearLocal removes only the local entry, leaving synced untouched")
	void clearLocalLeavesSyncedUntouched() {
		ClientBoneOverrideState state = new ClientBoneOverrideState();

		state.applyDelta(HORN_HIDDEN, 1, hidden(true, 0, 1));
		state.setLocal("horn_left", OverrideProperty.HIDDEN, new OverrideValue.BoolValue(false), OverrideScope.FRAME, 0, 1f, 0f);
		state.clearLocal("horn_left", OverrideProperty.HIDDEN);

		assertEquals(hidden(true, 0, 1), state.resolve("horn_left", OverrideProperty.HIDDEN));
	}

	@Test
	@DisplayName("clearFrameScopedLocal drops only FRAME-scoped local entries")
	void clearFrameScopedLocalDropsOnlyFrameScoped() {
		ClientBoneOverrideState state = new ClientBoneOverrideState();

		state.setLocal("horn_left", OverrideProperty.HIDDEN, new OverrideValue.BoolValue(true), OverrideScope.FRAME, 0, 1f, 0f);
		state.setLocal("chest", OverrideProperty.SCALE, new OverrideValue.Vec3Value(1.1f, 1.1f, 1.1f), OverrideScope.PERSISTENT, 0, 1f, 0f);

		state.clearFrameScopedLocal();

		assertNull(state.resolve("horn_left", OverrideProperty.HIDDEN), "FRAME-scoped local override must be gone");
		assertNotNull(state.resolve("chest", OverrideProperty.SCALE), "PERSISTENT-scoped local override must survive");
	}
}
