package com.sandymandy.strut.event.dispatch;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A bounded memory of the occurrence keys recently delivered for one entity - the client dispatcher's
 * second guard against delivering the same firing twice
 * <p>
 * The cursor already makes a duplicate impossible by construction; this exists for a path nobody has
 * thought of yet (a state rebuilt mid-clip, an id reused), and is cheap enough to keep. Bounded because
 * an entity can play for hours: only the newest {@code capacity} keys are kept, which is far more than
 * one sweep can deliver, so a duplicate of a <i>recent</i> firing is always caught
 */
public final class RecentOccurrences {
	private final int capacity;
	private final Set<String> keys = new LinkedHashSet<>();

	public RecentOccurrences(int capacity) {
		if (capacity < 1)
			throw new IllegalArgumentException("capacity must be at least 1");

		this.capacity = capacity;
	}

	/**
	 * Records {@code key} and reports whether this is the first time it has been seen
	 *
	 * @return false if it was already remembered - the caller should drop the occurrence
	 */
	public boolean firstTime(String key) {
		if (!this.keys.add(key))
			return false;

		if (this.keys.size() > this.capacity) {
			Iterator<String> oldest = this.keys.iterator();

			oldest.next();
			oldest.remove();
		}

		return true;
	}

	public int size() {
		return this.keys.size();
	}
}
