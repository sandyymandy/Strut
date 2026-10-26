package com.sandymandy.strut.animation.override;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The override-shaped twin of {@code ServerAnimationStateTest} - same dirty-tracking and
 * sequence-advancement contract, applied to {@link ServerBoneOverrideState} instead of
 * {@code ServerAnimationState}
 */
class ServerBoneOverrideStateTest {
	@Test
	@DisplayName("set stores a value, retrievable by bone/property")
	void setStoresAValue() {
		ServerBoneOverrideState state = new ServerBoneOverrideState();

		state.set("horn_left", OverrideProperty.HIDDEN, new OverrideValue.BoolValue(true),
				OverrideScope.PERSISTENT, 0, 1f, 0f);

		BoneOverride override = state.get("horn_left", OverrideProperty.HIDDEN);

		assertNotNull(override);
		assertEquals(new OverrideValue.BoolValue(true), override.value());
	}

	@Test
	@DisplayName("an untouched bone/property has no override")
	void untouchedKeyHasNoOverride() {
		assertNull(new ServerBoneOverrideState().get("never_touched", OverrideProperty.HIDDEN));
	}

	@Test
	@DisplayName("clear removes a stored override")
	void clearRemovesTheOverride() {
		ServerBoneOverrideState state = new ServerBoneOverrideState();

		state.set("horn_left", OverrideProperty.HIDDEN, new OverrideValue.BoolValue(true),
				OverrideScope.PERSISTENT, 0, 1f, 0f);
		state.clear("horn_left", OverrideProperty.HIDDEN);

		assertNull(state.get("horn_left", OverrideProperty.HIDDEN));
	}

	@Test
	@DisplayName("clearing a key that was never set is a no-op, not an error")
	void clearingAnUnsetKeyIsANoOp() {
		ServerBoneOverrideState state = new ServerBoneOverrideState();

		assertDoesNotThrow(() -> state.clear("never_set", OverrideProperty.HIDDEN));
		assertTrue(state.drainDirty().isEmpty());
	}

	@Test
	@DisplayName("clear advances the key's sequence, even with nothing left to carry it")
	void clearAdvancesTheSequence() {
		ServerBoneOverrideState state = new ServerBoneOverrideState();

		state.set("horn_left", OverrideProperty.HIDDEN, new OverrideValue.BoolValue(true),
				OverrideScope.PERSISTENT, 0, 1f, 0f);

		long setSequence = state.get("horn_left", OverrideProperty.HIDDEN).sequence();

		state.clear("horn_left", OverrideProperty.HIDDEN);

		assertTrue(state.lastSequenceFor("horn_left", OverrideProperty.HIDDEN) > setSequence,
				"the clear must get a sequence strictly after the set it is cancelling");
	}

	@Test
	@DisplayName("lastSequenceFor is 0 for a key nothing has ever touched")
	void lastSequenceForIsZeroWhenUntouched() {
		assertEquals(0, new ServerBoneOverrideState().lastSequenceFor("never_touched", OverrideProperty.HIDDEN));
	}

	@Test
	@DisplayName("each set on the same key gets a strictly increasing sequence")
	void sequenceIncreasesMonotonically() {
		ServerBoneOverrideState state = new ServerBoneOverrideState();

		state.set("horn_left", OverrideProperty.HIDDEN, new OverrideValue.BoolValue(true), OverrideScope.PERSISTENT, 0, 1f, 0f);
		long first = state.get("horn_left", OverrideProperty.HIDDEN).sequence();

		state.set("horn_left", OverrideProperty.HIDDEN, new OverrideValue.BoolValue(false), OverrideScope.PERSISTENT, 0, 1f, 0f);
		long second = state.get("horn_left", OverrideProperty.HIDDEN).sequence();

		assertTrue(second > first);
	}

	@Test
	@DisplayName("the same bone's different properties sequence independently")
	void propertiesSequenceIndependently() {
		ServerBoneOverrideState state = new ServerBoneOverrideState();

		state.set("horn_left", OverrideProperty.HIDDEN, new OverrideValue.BoolValue(true), OverrideScope.PERSISTENT, 0, 1f, 0f);
		state.set("horn_left", OverrideProperty.HIDDEN, new OverrideValue.BoolValue(false), OverrideScope.PERSISTENT, 0, 1f, 0f);
		state.set("horn_left", OverrideProperty.COLOR, new OverrideValue.ColorValue(0xFFFF0000), OverrideScope.PERSISTENT, 0, 1f, 0f);

		assertEquals(1, state.get("horn_left", OverrideProperty.COLOR).sequence(),
				"COLOR's own sequence starts fresh, unaffected by HIDDEN's history on the same bone");
	}

	@Test
	@DisplayName("different bones with the same property sequence independently")
	void differentBonesSequenceIndependently() {
		ServerBoneOverrideState state = new ServerBoneOverrideState();

		state.set("horn_left", OverrideProperty.HIDDEN, new OverrideValue.BoolValue(true), OverrideScope.PERSISTENT, 0, 1f, 0f);
		state.set("horn_right", OverrideProperty.HIDDEN, new OverrideValue.BoolValue(true), OverrideScope.PERSISTENT, 0, 1f, 0f);

		assertEquals(1, state.get("horn_right", OverrideProperty.HIDDEN).sequence());
	}

	@Test
	@DisplayName("snapshot returns every currently-set override")
	void snapshotReturnsEveryOverride() {
		ServerBoneOverrideState state = new ServerBoneOverrideState();

		state.set("horn_left", OverrideProperty.HIDDEN, new OverrideValue.BoolValue(true), OverrideScope.PERSISTENT, 0, 1f, 0f);
		state.set("chest", OverrideProperty.SCALE, new OverrideValue.Vec3Value(1.1f, 1.1f, 1.1f), OverrideScope.PERSISTENT, 0, 1f, 0f);

		assertEquals(2, state.snapshot().size());
	}

	@Test
	@DisplayName("a cleared key does not appear in the snapshot")
	void snapshotOmitsClearedKeys() {
		ServerBoneOverrideState state = new ServerBoneOverrideState();

		state.set("horn_left", OverrideProperty.HIDDEN, new OverrideValue.BoolValue(true), OverrideScope.PERSISTENT, 0, 1f, 0f);
		state.clear("horn_left", OverrideProperty.HIDDEN);

		assertTrue(state.snapshot().isEmpty());
	}

	@Test
	@DisplayName("drainDirty reports what changed and clears its own marks")
	void drainDirtyReportsAndClears() {
		ServerBoneOverrideState state = new ServerBoneOverrideState();

		state.set("horn_left", OverrideProperty.HIDDEN, new OverrideValue.BoolValue(true), OverrideScope.PERSISTENT, 0, 1f, 0f);
		state.set("chest", OverrideProperty.SCALE, new OverrideValue.Vec3Value(1.1f, 1.1f, 1.1f), OverrideScope.PERSISTENT, 0, 1f, 0f);

		assertEquals(Set.of(new OverrideKey("horn_left", OverrideProperty.HIDDEN), new OverrideKey("chest", OverrideProperty.SCALE)),
				state.drainDirty());
		assertTrue(state.drainDirty().isEmpty(), "drained marks must not report again");
	}

	@Test
	@DisplayName("a set-then-clear before a drain is still reported dirty")
	void setThenClearIsStillDirty() {
		ServerBoneOverrideState state = new ServerBoneOverrideState();

		state.set("horn_left", OverrideProperty.HIDDEN, new OverrideValue.BoolValue(true), OverrideScope.PERSISTENT, 0, 1f, 0f);
		state.clear("horn_left", OverrideProperty.HIDDEN);

		assertEquals(Set.of(new OverrideKey("horn_left", OverrideProperty.HIDDEN)), state.drainDirty(),
				"a receiver holding an earlier snapshot still needs to hear this cleared");
	}
}
