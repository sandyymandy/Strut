package com.sandymandy.strut.animation.authority;

import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * One tracked animatable's mirror of the server's {@link ServerAnimationState}, held client-side
 * <p>
 * A plain reflection of whatever the server last said, with one piece of judgement of its own: it
 * will not let a delayed packet move it backwards. That is the whole reason
 * {@link AnimationPlayback#sequence()} exists (see that record's own javadoc) - this is the class
 * that finally spends it. This owns the ordering check:
 * a delta only takes effect if its sequence beats the one already held
 * for that controller
 * <p>
 * Also remembers <b>how</b> each playback arrived ({@link Arrival}), and keeps a short <b>history</b> of every
 * change it applied ({@link Applied}). The event dispatcher needs both and nothing else does: a clip that
 * arrived in the snapshot sent when this client began tracking the entity must not replay its history, while
 * one that arrived as a delta - or as a resync filling in a lost delta - should still deliver the events it
 * just missed; and a client that applies several deltas between two ticks (a stall, or packets arriving
 * together) must replay each playback in turn rather than see only the newest, or a chain of rate changes
 * looks like one discontinuous jump.
 */
public final class ClientAnimationState {
	/**
	 * How many applied changes per controller are remembered - far more than any realistic gap between two ticks
	 */
	public static final int HISTORY_CAPACITY = 64;

	private final Map<String, AnimationPlayback> playbacks = new Object2ObjectOpenHashMap<>();
	private final Object2LongOpenHashMap<String> lastSequence = new Object2LongOpenHashMap<>();
	private final Map<String, Arrival> arrivals = new Object2ObjectOpenHashMap<>();
	private final Map<String, ArrayDeque<Applied>> history = new Object2ObjectOpenHashMap<>();
	@Nullable
	private final LongSupplier clock;
	private long nextVersion = 1;
	private boolean everApplied;

	/**
	 * A state that does not know what time it is. Changes are recorded with {@link Applied#UNKNOWN_TIME}, and the
	 * event dispatcher falls back to the time of its own sweep - which is what tests without a clock want
	 */
	public ClientAnimationState() {
		this(null);
	}

	/**
	 * @param clock The current game time, read as each change is applied. The real client passes the world's
	 *              clock, so a change is stamped with when it <i>arrived</i>, not when the next tick got round
	 *              to looking at it - which matters to a client whose tick stalled
	 */
	public ClientAnimationState(@Nullable LongSupplier clock) {
		this.clock = clock;
	}

	/**
	 * How a playback reached this client
	 */
	public enum ArrivalKind {
		/** In an ordinary delta - a change the server just made */
		DELTA,
		/** In the snapshot sent when this client began tracking the entity - owed no history */
		SNAPSHOT_START_TRACKING,
		/** In a resync or periodic snapshot - possibly filling in a delta that was lost */
		SNAPSHOT_REFRESH
	}

	/**
	 * @param sequence The sequence of the playback that arrived
	 * @param kind How it arrived
	 */
	public record Arrival(long sequence, ArrivalKind kind) {}

	/**
	 * One change this state applied, in the order it applied them
	 *
	 * @param version Rises by one with every change to any controller of this state; a dispatcher remembers the
	 *                highest it has dealt with and asks for what is newer
	 * @param playback The playback that took effect, or null if the controller stopped
	 * @param kind How it arrived
	 * @param appliedAt The game time the change was applied at, or {@link #UNKNOWN_TIME} if this state has no clock
	 * @param origin What kind of change the server said this was (a play, a rate change, a seek...), or null when the
	 *               server did not say - a snapshot describes a state, not a change
	 */
	public record Applied(long version, @Nullable AnimationPlayback playback, ArrivalKind kind, long appliedAt,
						  @Nullable ServerAnimationState.ChangeKind origin) {
		public static final long UNKNOWN_TIME = Long.MIN_VALUE;
	}

	/**
	 * Replaces every controller's mirrored state wholesale, from a full snapshot
	 * <p>
	 * A snapshot is comprehensive by construction - {@link ServerAnimationState#snapshot()} lists
	 * every controller currently playing anything, full stop - so a controller this instance was
	 * mirroring that the snapshot does not mention has, in the time since, stopped. Clearing first
	 * rather than merging is what makes that fall out correctly instead of needing to be handled as
	 * a special case
	 * <p>
	 * Unlike {@link #applyDelta}, a snapshot is never rejected for its sequence - it exists precisely
	 * to catch a client up from nothing, so there is no "already held" state for an out-of-order
	 * check to be relative to
	 * <p>
	 * A playback whose sequence the client already held keeps its earlier {@link Arrival}, and is <b>not</b>
	 * recorded as a change: being told the same thing again is not news, and must not make an old clip look
	 * freshly started
	 *
	 * @param reason Why the server sent it, which decides how a <i>newly introduced</i> playback is
	 *               treated by event dispatch
	 */
	public void applySnapshot(Collection<AnimationPlayback> snapshot, SnapshotReason reason) {
		Map<String, Arrival> previous = new Object2ObjectOpenHashMap<>(this.arrivals);
		Map<String, AnimationPlayback> previousPlaybacks = new Object2ObjectOpenHashMap<>(this.playbacks);

		this.playbacks.clear();
		this.lastSequence.clear();
		this.arrivals.clear();

		// The first snapshot a state ever receives is its introduction, whatever the server called it. A periodic
		// resend can only be meant for a client that was already tracking, so one arriving first has overtaken this
		// client's own start-tracking snapshot - and a client meeting an entity for the first time is owed no
		// history. An explicit resync stays a resync: the client asked, precisely to fill in what it missed
		boolean introduction = reason == SnapshotReason.START_TRACKING || (reason == SnapshotReason.PERIODIC && !this.everApplied);
		ArrivalKind kind = introduction ? ArrivalKind.SNAPSHOT_START_TRACKING : ArrivalKind.SNAPSHOT_REFRESH;

		this.everApplied = true;

		for (AnimationPlayback playback : snapshot) {
			this.playbacks.put(playback.controller(), playback);
			this.lastSequence.put(playback.controller(), playback.sequence());

			Arrival held = previous.get(playback.controller());
			boolean unchanged = held != null && held.sequence() == playback.sequence();

			this.arrivals.put(playback.controller(), unchanged ? held : new Arrival(playback.sequence(), kind));

			if (!unchanged)
				remember(playback.controller(), playback, kind, null);
		}

		// A controller held before that the snapshot no longer lists has stopped
		for (String controller : previousPlaybacks.keySet()) {
			if (!this.playbacks.containsKey(controller))
				remember(controller, null, kind, null);
		}
	}

	/**
	 * {@link #applySnapshot(Collection, SnapshotReason)} as a refresh - what a snapshot of unknown
	 * origin should be assumed to be
	 */
	public void applySnapshot(Collection<AnimationPlayback> snapshot) {
		applySnapshot(snapshot, SnapshotReason.RESYNC);
	}

	/**
	 * Applies one controller's change, unless a delayed packet has arrived after a newer one already
	 * did
	 * <p>
	 * {@code playback} null means the controller stopped - see
	 * {@link com.sandymandy.strut.network.packet.AnimationPlaybackDeltaPacket}'s own doc comment on
	 * why a stop's sequence has to be carried separately from a playback it does not have
	 *
	 * @return Whether the delta was applied. {@code false} means it was stale and correctly ignored -
	 *         not an error, and callers with nothing more to do can discard the result
	 */
	public boolean applyDelta(String controller, long sequence, @Nullable AnimationPlayback playback) {
		return applyDelta(controller, sequence, playback, null);
	}

	/**
	 * {@link #applyDelta(String, long, AnimationPlayback)}, also recording what kind of change the server said this
	 * was, which is how the event dispatcher knows whether to carry a clip's progress across (a rate change), start
	 * afresh (a new clip) or honour a seek - instead of guessing from the numbers
	 *
	 * @param origin The server's kind of change, or null if unknown
	 */
	public boolean applyDelta(String controller, long sequence, @Nullable AnimationPlayback playback,
							  @Nullable ServerAnimationState.ChangeKind origin) {
		if (sequence <= this.lastSequence.getLong(controller))
			return false;

		if (playback == null) {
			this.playbacks.remove(controller);
			this.arrivals.remove(controller);
		}
		else {
			this.playbacks.put(controller, playback);
			this.arrivals.put(controller, new Arrival(sequence, ArrivalKind.DELTA));
		}

		this.lastSequence.put(controller, sequence);
		this.everApplied = true;
		remember(controller, playback, ArrivalKind.DELTA, origin);

		return true;
	}

	private void remember(String controller, @Nullable AnimationPlayback playback, ArrivalKind kind,
						  @Nullable ServerAnimationState.ChangeKind origin) {
		ArrayDeque<Applied> log = this.history.computeIfAbsent(controller, name -> new ArrayDeque<>());

		if (log.size() >= HISTORY_CAPACITY)
			log.removeFirst();

		log.addLast(new Applied(this.nextVersion++, playback, kind,
				this.clock != null ? this.clock.getAsLong() : Applied.UNKNOWN_TIME, origin));
	}

	/**
	 * Every change applied to this controller with a version after {@code afterVersion}, oldest first
	 * <p>
	 * Pass the highest version already dealt with (0 for none) to get exactly what is new
	 */
	public List<Applied> changesAfter(String controller, long afterVersion) {
		ArrayDeque<Applied> log = this.history.get(controller);

		if (log == null)
			return List.of();

		List<Applied> newer = new ArrayList<>();

		for (Applied applied : log) {
			if (applied.version() > afterVersion)
				newer.add(applied);
		}

		return newer;
	}

	/**
	 * Every controller with a history, including one that has since stopped - a dispatcher must still be told
	 */
	public Set<String> controllersWithHistory() {
		return new ObjectLinkedOpenHashSet<>(this.history.keySet());
	}

	/**
	 * This controller's last-known authoritative playback, or null if it is not mirrored as playing
	 * anything
	 */
	@Nullable
	public AnimationPlayback get(String controller) {
		return this.playbacks.get(controller);
	}

	/**
	 * How this controller's current playback arrived, or null if it is not mirrored as playing anything
	 */
	@Nullable
	public Arrival arrivalOf(String controller) {
		return this.arrivals.get(controller);
	}

	/**
	 * Every controller currently mirrored as playing something
	 */
	public Collection<AnimationPlayback> playbacks() {
		return this.playbacks.values();
	}
}
