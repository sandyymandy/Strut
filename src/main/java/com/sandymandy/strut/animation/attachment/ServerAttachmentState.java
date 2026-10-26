package com.sandymandy.strut.animation.attachment;

import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Set;

/**
 * One tracked entity's authoritative attachments, held server-side - the attachment-shaped twin of
 * {@link com.sandymandy.strut.animation.override.ServerBoneOverrideState}, keyed by a caller-chosen
 * slot name (arbitrary - {@code "mainhand"}, {@code "head"}, whatever the caller finds meaningful)
 * rather than a bone/property pair, since an attachment is not overriding anything a bone already
 * has - it is declaring that something new exists at that bone. Folded into
 * {@code ServerAnimationStates}'s own per-entity {@code Entry}, exactly like bone overrides, so
 * replication rides the same start-tracking/tick/resync lifecycle
 */
public final class ServerAttachmentState {
	private final Map<String, Attachment> attachments = new Object2ObjectLinkedOpenHashMap<>();
	private final Object2LongOpenHashMap<String> nextSequence = new Object2LongOpenHashMap<>();
	private final Set<String> dirty = new ObjectLinkedOpenHashSet<>();

	/**
	 * Sets (or replaces) one slot's attachment - {@code attachment}'s own {@link Attachment#sequence()}
	 * is ignored and replaced with this call's own freshly-issued one; a caller only ever builds an
	 * {@link Attachment} to describe <em>what</em> to attach, never to pick its own place in
	 * replication ordering
	 */
	public void set(String slot, Attachment attachment) {
		Attachment sequenced = new Attachment(attachment.bone(), attachment.item(), attachment.itemCount(), attachment.block(),
				attachment.offsetX(), attachment.offsetY(), attachment.offsetZ(),
				attachment.rotX(), attachment.rotY(), attachment.rotZ(),
				attachment.scaleX(), attachment.scaleY(), attachment.scaleZ(),
				nextSequenceFor(slot), attachment.hand());

		this.attachments.put(slot, sequenced);
		this.dirty.add(slot);
	}

	/**
	 * Clears one slot's attachment, if it had one - still advances the slot's sequence even though
	 * nothing is left to carry it, the same reason {@link com.sandymandy.strut.animation.override.ServerBoneOverrideState#clear}
	 * does: a delayed clear must not resurrect an attachment a newer {@link #set} already replaced
	 */
	public void clear(String slot) {
		if (this.attachments.remove(slot) != null) {
			nextSequenceFor(slot);
			this.dirty.add(slot);
		}
	}

	@Nullable
	public Attachment get(String slot) {
		return this.attachments.get(slot);
	}

	public long lastSequenceFor(String slot) {
		return this.nextSequence.getLong(slot);
	}

	/**
	 * Every currently-set attachment, keyed by slot - what a full snapshot packet sends a client that
	 * has just started tracking this entity
	 */
	public Map<String, Attachment> snapshot() {
		return this.attachments;
	}

	/**
	 * The slots that changed since the last call to this method, with their marks cleared
	 */
	public Set<String> drainDirty() {
		Set<String> drained = new ObjectLinkedOpenHashSet<>(this.dirty);

		this.dirty.clear();

		return drained;
	}

	private long nextSequenceFor(String slot) {
		return this.nextSequence.addTo(slot, 1L) + 1L;
	}
}
