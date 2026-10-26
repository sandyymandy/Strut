package com.sandymandy.strut.event.cursor;

import com.sandymandy.strut.event.cursor.EventOccurrence.Kind;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The exactly-once machinery: how far along a playback's elapsed axis events have been dispatched,
 * and which occurrences the next stretch of time crosses
 * <p>
 * A pure function of numbers - no Minecraft types, no clock of its own. The caller supplies the
 * elapsed position ({@link com.sandymandy.strut.animation.authority.AnimationPlayback#elapsedTicks}),
 * so events ride the same synced clock the pose samples.
 *
 * <h2>The rule</h2>
 *
 * <p>An occurrence at position {@code p} is <b>crossed</b> going forward when
 * {@code cursor < p <= now}, and going backward when {@code now <= p < cursor}. A boundary belongs
 * to exactly one side of any split of time, so cutting a stretch into two calls (one long lagging
 * tick, or two normal ones) fires the same occurrences either way - that is the whole exactly-once
 * argument, and why lag cannot skip or double anything: nothing is ever <i>sampled</i>, only
 * <i>spanned</i>
 * <p>
 * The one exception is the very first call of a fresh playback, which uses a <b>closed</b> start so
 * an event authored at exactly the start fires instead of falling in the gap before it
 *
 * <h2>Built-ins</h2>
 *
 * <p>The cursor also reports {@link Kind#LOOP} each time a looping clip wraps (loop {@code n >= 1}
 * being entered) and {@link Kind#END} once when a non-looping clip's end is passed, so "the clip
 * finished" is an event rather than something a state handler polls for. Built-ins are forward-only
 *
 * <h2>Order</h2>
 *
 * <p>Within a call, occurrences come out in playback order: by position, and at one position an
 * authored event that closes loop {@code n} (time == length) precedes the {@link Kind#LOOP} for loop
 * {@code n + 1}, which precedes that loop's own time-0 events; {@link Kind#END} comes after the
 * events at the end. Ties among authored events break by index. Reversed, the order is reversed
 */
public final class EventCursor {
	/**
	 * The most occurrences one {@link #advance} will report - see {@link Result#truncated}
	 */
	public static final int DEFAULT_MAX_OCCURRENCES = 256;

	private double position;
	private boolean consumedStart;

	private EventCursor(double position, boolean consumedStart) {
		this.position = position;
		this.consumedStart = consumedStart;
	}

	/**
	 * A cursor for a playback that has just begun at {@code elapsedAtStart}. Its first
	 * {@link #advance} treats that position as inclusive
	 */
	public static EventCursor startingAt(double elapsedAtStart) {
		return new EventCursor(elapsedAtStart, false);
	}

	/**
	 * A cursor at {@code elapsed} that has already dispatched everything up to and including it
	 * <p>
	 * For a client that must not replay history (a late joiner's snapshot), and for carrying a cursor
	 * across a {@code setRate} re-base: the re-based playback is a new sequence, but it starts exactly
	 * where the old one was, so an event sitting on that instant must not fire a second time
	 */
	public static EventCursor resumingAt(double elapsed) {
		return new EventCursor(elapsed, true);
	}

	public double position() {
		return this.position;
	}

	/**
	 * A copy that continues from the same position, for a playback that carries straight on - a rate change,
	 * where the new playback is the same clip on the same elapsed axis
	 * <p>
	 * Keeps whether the start instant has been consumed. A cursor that has advanced has, so the copy will not
	 * fire the instant it sits on a second time; one that has <i>not</i> yet advanced (a clip whose rate changed
	 * in the very tick it began) has not, and its copy must still fire an event at the start - forcing "consumed"
	 * here would silently lose it
	 */
	public EventCursor carryOver() {
		return new EventCursor(this.position, this.consumedStart);
	}

	/**
	 * Everything crossed moving from this cursor's position to {@code now}, in playback order, and
	 * moves the cursor to {@code now}
	 *
	 * @param max Cap on occurrences reported. Past it the rest are dropped, the cursor still moves to
	 *            {@code now}, and {@link Result#truncated} is set - a clip that laps itself a
	 *            thousand times in one tick is a fault to log once, not a thousand handler calls
	 */
	public Result advance(EventSchedule schedule, double now, int max) {
		double from = this.position;
		boolean first = !this.consumedStart;

		this.position = now;
		this.consumedStart = true;

		if (schedule.isInert() || Double.isNaN(now))
			return Result.EMPTY;

		boolean forward = now >= from;
		Collector out = new Collector(max);

		if (forward) {
			collect(schedule, from, now, first, true, out);
		}
		else {
			collect(schedule, now, from, first, false, out);
		}

		return out.result();
	}

	public Result advance(EventSchedule schedule, double now) {
		return advance(schedule, now, DEFAULT_MAX_OCCURRENCES);
	}

	/**
	 * Walks the loops overlapping {@code [lo, hi]} in playback order, offering each candidate to
	 * {@code out} until it is full
	 *
	 * @param first Whether this is a fresh playback's first call - makes the start bound closed
	 */
	private static void collect(EventSchedule schedule, double lo, double hi, boolean first, boolean forward, Collector out) {
		double length = schedule.length();

		// Backwards, only events that opt in can fire, so a clip with none has nothing to walk - and
		// must not be walked, since a huge negative rate would otherwise spin over millions of empty loops
		if (!forward && schedule.events().stream().noneMatch(ScheduledEvent::fireInReverse))
			return;

		// The loop before the one containing `lo` is walked so an event at its very end (time == length), which
		// sits on the boundary, is not missed when a later call crosses it. A fresh playback's first call must
		// not walk it: that lap never happened, and an end-of-lap event sharing the start position would
		// otherwise fire as "loop -1" the instant the clip begins
		long firstLoop = schedule.loops() ? (long)Math.floor(lo / length) - (forward && first ? 0 : 1) : 0;
		long lastLoop = schedule.loops() ? (long)Math.floor(hi / length) : 0;

		if (forward) {
			for (long loop = firstLoop; loop <= lastLoop; loop++) {
				if (!collectLoop(schedule, loop, lo, hi, first, true, out))
					return;
			}
		}
		else {
			for (long loop = lastLoop; loop >= firstLoop; loop--) {
				if (!collectLoop(schedule, loop, lo, hi, first, false, out))
					return;
			}
		}
	}

	/**
	 * @return false once {@code out} is full and nothing more should be walked
	 */
	private static boolean collectLoop(EventSchedule schedule, long loop, double lo, double hi, boolean first,
									   boolean forward, Collector out) {
		double base = loop * schedule.length();
		List<EventOccurrence> candidates = new ArrayList<>(schedule.events().size() + 2);

		// A looping clip's wrap into this loop. Loop 0 is the start of the playback, not a wrap - and neither is
		// the loop a fresh playback begins in, whatever its number: it started there, it did not wrap into it
		boolean beganInThisLoop = first && schedule.loops() && loop == (long)Math.floor(lo / schedule.length());

		if (forward && schedule.loops() && loop >= 1 && !beganInThisLoop)
			candidates.add(new EventOccurrence(Kind.LOOP, loop, EventOccurrence.NO_EVENT, base, 0));

		for (ScheduledEvent event : schedule.events()) {
			if (!forward && !event.fireInReverse())
				continue;

			candidates.add(new EventOccurrence(Kind.EVENT, loop, event.index(), base + event.tick(), event.tick()));
		}

		if (forward && !schedule.loops())
			candidates.add(new EventOccurrence(Kind.END, 0, EventOccurrence.NO_EVENT, schedule.length(), schedule.length()));

		if (!forward)
			Collections.reverse(candidates);

		for (EventOccurrence candidate : candidates) {
			if (!crossed(candidate.tick(), lo, hi, first, forward))
				continue;

			if (!out.offer(candidate))
				return false;
		}

		return true;
	}

	/**
	 * The half-open rule from the class doc, with the first call's closed start
	 * <p>
	 * Forward covers {@code (lo, hi]} and backward {@code [lo, hi)}, where {@code lo}/{@code hi} are the
	 * lesser/greater of the two positions; the first call closes whichever end is the cursor's own
	 */
	private static boolean crossed(double position, double lo, double hi, boolean first, boolean forward) {
		if (forward)
			return (first ? position >= lo : position > lo) && position <= hi;

		return position >= lo && (first ? position <= hi : position < hi);
	}

	/**
	 * @param occurrences What was crossed, in playback order
	 * @param truncated Whether more was crossed than the cap allowed and the excess was dropped
	 */
	public record Result(List<EventOccurrence> occurrences, boolean truncated) {
		static final Result EMPTY = new Result(List.of(), false);

		public boolean isEmpty() {
			return this.occurrences.isEmpty();
		}
	}

	private static final class Collector {
		private final int max;
		private final List<EventOccurrence> occurrences = new ArrayList<>();
		private boolean truncated;

		Collector(int max) {
			this.max = max;
		}

		/**
		 * @return false once the cap is hit and this occurrence was dropped
		 */
		boolean offer(EventOccurrence occurrence) {
			if (this.occurrences.size() >= this.max) {
				this.truncated = true;

				return false;
			}

			this.occurrences.add(occurrence);

			return true;
		}

		Result result() {
			return this.occurrences.isEmpty() && !this.truncated ? Result.EMPTY
					: new Result(List.copyOf(this.occurrences), this.truncated);
		}
	}
}
