package com.sandymandy.strut.animation.attachment;

import net.minecraft.util.Identifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ClientAttachmentState}'s sequence-guarded mirror contract - the same
 * {@code applyDelta}/{@code applySnapshot} guard {@code ClientBoneOverrideStateTest} covers for bone
 * overrides, applied here with no {@code local}/{@code synced} split at all (see this class's own
 * doc comment on why there is only the one store)
 */
class ClientAttachmentStateTest {
	private static Attachment item(long sequence) {
		return new Attachment("hand_r", Identifier.of("minecraft", "diamond_sword"), 1, null,
				0f, 0f, 0f, 0f, 0f, 0f, 1f, 1f, 1f, sequence);
	}

	@Test
	@DisplayName("an in-order delta is applied")
	void inOrderDeltaIsApplied() {
		ClientAttachmentState state = new ClientAttachmentState();

		boolean applied = state.applyDelta("mainhand", 1, item(1));

		assertTrue(applied);
		assertEquals(item(1), state.all().get("mainhand"));
	}

	@Test
	@DisplayName("a delayed delta (a lower sequence arriving after a higher one) is ignored")
	void delayedDeltaIsIgnored() {
		ClientAttachmentState state = new ClientAttachmentState();

		state.applyDelta("mainhand", 5, item(5));

		boolean applied = state.applyDelta("mainhand", 2, item(2));

		assertFalse(applied, "a sequence of 2 must not beat an already-held sequence of 5");
		assertEquals(item(5), state.all().get("mainhand"));
	}

	@Test
	@DisplayName("a null attachment (a clear) removes the entry when its sequence is in order")
	void clearRemovesTheEntry() {
		ClientAttachmentState state = new ClientAttachmentState();

		state.applyDelta("mainhand", 1, item(1));

		boolean applied = state.applyDelta("mainhand", 2, null);

		assertTrue(applied);
		assertFalse(state.all().containsKey("mainhand"));
	}

	@Test
	@DisplayName("a snapshot replaces every entry wholesale, seeded from each attachment's own sequence")
	void snapshotReplacesEverything() {
		ClientAttachmentState state = new ClientAttachmentState();

		state.applyDelta("mainhand", 100, item(100));
		state.applySnapshot(Map.of("mainhand", item(1)));

		assertEquals(item(1), state.all().get("mainhand"),
				"a snapshot is more current by construction, even at a lower sequence than an already-applied delta");
	}

	@Test
	@DisplayName("a delta after a snapshot is guarded by the snapshotted attachment's own sequence")
	void deltaAfterSnapshotIsGuardedBySnapshotSequence() {
		ClientAttachmentState state = new ClientAttachmentState();

		state.applySnapshot(Map.of("mainhand", item(5)));

		boolean applied = state.applyDelta("mainhand", 2, item(2));

		assertFalse(applied, "the snapshot's own sequence (5) must guard against a stale delta (2), not a sentinel");
	}

	@Test
	@DisplayName("a slot the snapshot does not mention is gone after applying it")
	void snapshotOmitsClearsEverythingNotMentioned() {
		ClientAttachmentState state = new ClientAttachmentState();

		state.applyDelta("mainhand", 1, item(1));
		state.applySnapshot(Map.of());

		assertTrue(state.all().isEmpty(), "the server no longer mentioning a slot means it is no longer attached");
	}
}
