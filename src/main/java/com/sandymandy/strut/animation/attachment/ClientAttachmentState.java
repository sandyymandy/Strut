package com.sandymandy.strut.animation.attachment;

import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * One tracked entity's mirrored attachments, held client-side - a plain mirror of the server's
 * {@link ServerAttachmentState}, applied the same delayed-packet-safe way
 * {@link com.sandymandy.strut.animation.authority.ClientAnimationState} mirrors
 * {@code AnimationPlayback} and {@link com.sandymandy.strut.animation.override.ClientBoneOverrideState}
 * mirrors {@code ServerBoneOverrideState}'s {@code synced} half
 * <p>
 * No client-local half - unlike bone overrides, attachments have no viewer-specific use case (nothing like "hide my own head in first person"), so there is no
 * {@code local}/{@code synced} split to resolve here, just the one authoritative map
 */
public final class ClientAttachmentState {
	private final Map<String, Attachment> attachments = new Object2ObjectOpenHashMap<>();
	private final Object2LongOpenHashMap<String> lastSequence = new Object2LongOpenHashMap<>();

	/**
	 * Replaces every attachment wholesale, from a full snapshot - see
	 * {@code ClientAnimationState#applySnapshot}'s own doc comment for why clearing first is what
	 * makes "the server stopped attaching this slot, and the snapshot simply does not mention it any
	 * more" fall out correctly rather than needing to be handled as a special case
	 */
	public void applySnapshot(Map<String, Attachment> snapshot) {
		this.attachments.clear();
		this.lastSequence.clear();

		for (Map.Entry<String, Attachment> entry : snapshot.entrySet()) {
			this.attachments.put(entry.getKey(), entry.getValue());
			this.lastSequence.put(entry.getKey(), entry.getValue().sequence());
		}
	}

	/**
	 * Applies one slot's change, unless a delayed packet has arrived after a newer one already did -
	 * see {@code ClientAnimationState#applyDelta}'s own doc comment; this is the identical guard.
	 * {@code attachment} null means the server cleared the slot
	 *
	 * @return Whether the delta was applied. {@code false} means it was stale and correctly ignored
	 */
	public boolean applyDelta(String slot, long sequence, @Nullable Attachment attachment) {
		if (sequence <= this.lastSequence.getLong(slot))
			return false;

		if (attachment == null) {
			this.attachments.remove(slot);
		}
		else {
			this.attachments.put(slot, attachment);
		}

		this.lastSequence.put(slot, sequence);

		return true;
	}

	/**
	 * Every attachment currently active for this entity - what the render-time hook iterates each
	 * frame
	 */
	public Map<String, Attachment> all() {
		return this.attachments;
	}
}
