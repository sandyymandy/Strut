package com.sandymandy.strut.event.dispatch;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Locale;

/**
 * A short memory of what fired recently on each entity, so tick code can ask "did {@code swap} just
 * fire?" instead of every mod writing its own queue and edge detector
 * <p>
 * This is the exact replacement for the pattern a consumer mod built by hand: an event queue
 * cleared "next tick", polled from state handlers, HUD code and managers, with a {@code sawSwap} flag
 * to turn "visible for two ticks" back into "happened once". Here an occurrence is recorded once, at
 * the sweep that fired it, so {@link #firedLastTick} is true for exactly one tick of polling and the
 * edge comes for free
 * <p>
 * Server thread only. Each entity keeps its last {@value #CAPACITY} occurrences; older ones fall off
 */
public final class EventLog {
	/** Occurrences remembered per entity */
	public static final int CAPACITY = 32;

	private record Entry(Identifier type, @Nullable String marker, long sweepTime) {}

	private final Int2ObjectMap<ArrayDeque<Entry>> entries = new Int2ObjectOpenHashMap<>();

	/**
	 * Records that an event fired during the sweep at {@code sweepTime}
	 *
	 * @param marker The marker's name if this was a {@code strut:marker}, else null
	 */
	public void record(int entityId, Identifier type, @Nullable String marker, long sweepTime) {
		ArrayDeque<Entry> log = this.entries.computeIfAbsent(entityId, id -> new ArrayDeque<>(CAPACITY));

		if (log.size() >= CAPACITY)
			log.removeFirst();

		log.addLast(new Entry(type, marker, sweepTime));
	}

	/**
	 * Whether an event of {@code type} fired on this entity at a sweep at or after {@code sinceGameTime}
	 */
	public boolean firedSince(int entityId, Identifier type, long sinceGameTime) {
		ArrayDeque<Entry> log = this.entries.get(entityId);

		if (log == null)
			return false;

		for (Entry entry : log) {
			if (entry.sweepTime >= sinceGameTime && entry.type.equals(type))
				return true;
		}

		return false;
	}

	/**
	 * Whether a {@code strut:marker} named {@code marker} fired at a sweep at or after {@code sinceGameTime}
	 */
	public boolean firedMarkerSince(int entityId, String marker, long sinceGameTime) {
		ArrayDeque<Entry> log = this.entries.get(entityId);
		String wanted = marker.toLowerCase(Locale.ROOT);

		if (log == null)
			return false;

		for (Entry entry : log) {
			if (entry.sweepTime >= sinceGameTime && wanted.equals(entry.marker))
				return true;
		}

		return false;
	}

	/**
	 * Whether {@code type} fired in the previous tick's sweep or this tick's - so, polled once per tick
	 * from entity tick code (which runs before the sweep), true for exactly one tick per occurrence
	 *
	 * @param currentGameTime The current {@code World#getTime()}
	 */
	public boolean firedLastTick(int entityId, Identifier type, long currentGameTime) {
		return firedSince(entityId, type, currentGameTime - 1);
	}

	public boolean firedMarkerLastTick(int entityId, String marker, long currentGameTime) {
		return firedMarkerSince(entityId, marker, currentGameTime - 1);
	}

	/**
	 * Drops an entity's memory - when it unloads
	 */
	public void forget(int entityId) {
		this.entries.remove(entityId);
	}
}
