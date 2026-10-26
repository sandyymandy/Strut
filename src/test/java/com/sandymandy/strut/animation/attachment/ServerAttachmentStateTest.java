package com.sandymandy.strut.animation.attachment;

import net.minecraft.util.Identifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The attachment-shaped twin of {@code ServerBoneOverrideStateTest} - same dirty-tracking and
 * sequence-advancement contract, applied to {@link ServerAttachmentState} instead of
 * {@code ServerBoneOverrideState}, keyed by a plain slot name rather than a bone/property pair
 */
class ServerAttachmentStateTest {
	private static Attachment item(String bone) {
		return new Attachment(bone, Identifier.of("minecraft", "diamond_sword"), 1, null,
				0f, 0f, 0f, 0f, 0f, 0f, 1f, 1f, 1f, 0);
	}

	@Test
	@DisplayName("set stores an attachment, retrievable by slot")
	void setStoresAnAttachment() {
		ServerAttachmentState state = new ServerAttachmentState();

		state.set("mainhand", item("hand_r"));

		Attachment attachment = state.get("mainhand");

		assertNotNull(attachment);
		assertEquals("hand_r", attachment.bone());
		assertEquals(Identifier.of("minecraft", "diamond_sword"), attachment.item());
	}

	@Test
	@DisplayName("an untouched slot has no attachment")
	void untouchedSlotHasNoAttachment() {
		assertNull(new ServerAttachmentState().get("never_touched"));
	}

	@Test
	@DisplayName("clear removes a stored attachment")
	void clearRemovesTheAttachment() {
		ServerAttachmentState state = new ServerAttachmentState();

		state.set("mainhand", item("hand_r"));
		state.clear("mainhand");

		assertNull(state.get("mainhand"));
	}

	@Test
	@DisplayName("clearing a slot that was never set is a no-op, not an error")
	void clearingAnUnsetSlotIsANoOp() {
		ServerAttachmentState state = new ServerAttachmentState();

		assertDoesNotThrow(() -> state.clear("never_set"));
		assertTrue(state.drainDirty().isEmpty());
	}

	@Test
	@DisplayName("set replaces attachment.sequence() with this call's own freshly-issued sequence")
	void setIgnoresTheCallersOwnSequence() {
		ServerAttachmentState state = new ServerAttachmentState();
		Attachment withBogusSequence = new Attachment("hand_r", Identifier.of("minecraft", "diamond_sword"), 1, null,
				0f, 0f, 0f, 0f, 0f, 0f, 1f, 1f, 1f, 999);

		state.set("mainhand", withBogusSequence);

		assertEquals(1, state.get("mainhand").sequence(), "the stored sequence must come from the state itself, not the caller");
	}

	@Test
	@DisplayName("each set on the same slot gets a strictly increasing sequence")
	void sequenceIncreasesMonotonically() {
		ServerAttachmentState state = new ServerAttachmentState();

		state.set("mainhand", item("hand_r"));
		long first = state.get("mainhand").sequence();

		state.set("mainhand", item("hand_r"));
		long second = state.get("mainhand").sequence();

		assertTrue(second > first);
	}

	@Test
	@DisplayName("different slots sequence independently")
	void differentSlotsSequenceIndependently() {
		ServerAttachmentState state = new ServerAttachmentState();

		state.set("mainhand", item("hand_r"));
		state.set("offhand", item("hand_l"));

		assertEquals(1, state.get("offhand").sequence());
	}

	@Test
	@DisplayName("snapshot returns every currently-set attachment")
	void snapshotReturnsEveryAttachment() {
		ServerAttachmentState state = new ServerAttachmentState();

		state.set("mainhand", item("hand_r"));
		state.set("offhand", item("hand_l"));

		assertEquals(2, state.snapshot().size());
	}

	@Test
	@DisplayName("a cleared slot does not appear in the snapshot")
	void snapshotOmitsClearedSlots() {
		ServerAttachmentState state = new ServerAttachmentState();

		state.set("mainhand", item("hand_r"));
		state.clear("mainhand");

		assertTrue(state.snapshot().isEmpty());
	}

	@Test
	@DisplayName("drainDirty reports what changed and clears its own marks")
	void drainDirtyReportsAndClears() {
		ServerAttachmentState state = new ServerAttachmentState();

		state.set("mainhand", item("hand_r"));
		state.set("offhand", item("hand_l"));

		assertEquals(Set.of("mainhand", "offhand"), state.drainDirty());
		assertTrue(state.drainDirty().isEmpty(), "drained marks must not report again");
	}

	@Test
	@DisplayName("a set-then-clear before a drain is still reported dirty")
	void setThenClearIsStillDirty() {
		ServerAttachmentState state = new ServerAttachmentState();

		state.set("mainhand", item("hand_r"));
		state.clear("mainhand");

		assertEquals(Set.of("mainhand"), state.drainDirty(), "a receiver holding an earlier snapshot still needs to hear this cleared");
	}
}
