package com.sandymandy.strut.animation.authority;

import com.sandymandy.strut.loading.binary.StrutBinaryFormats.LoopMode;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One tracked animatable's authoritative playback state, held server-side
 * <p>
 * This is deliberately small and deliberately the <b>whole</b> of what the server knows about an
 * animatable's animation: a name-keyed map of controller to its current {@link AnimationPlayback},
 * nothing bone-shaped in it anywhere. No pose, no bone hierarchy, no baked model.
 * The server owns *decisions*; working out what a bone
 * looks like from one is entirely a client concern, so this class has no way to answer that
 * question and is not meant to gain one
 * <p>
 * Plain state: nothing here knows about entities, and nothing sends what changes over the
 * wire; that is the registry's and the network's job. This class is the state and its mutators, proven in isolation
 *
 * <h2>Dirty tracking</h2>
 *
 * <p>Every mutator marks the controller it touched. {@link #drainDirty()} hands back which
 * controllers changed since the last drain and clears the marks, which is what a future delta
 * sender polls: send a delta only for what actually changed, never the whole map on every tick
 */
public final class ServerAnimationState {
	private final Map<String, AnimationPlayback> playbacks = new Object2ObjectLinkedOpenHashMap<>();
	private final Object2LongOpenHashMap<String> nextSequence = new Object2LongOpenHashMap<>();
	private final Set<String> dirty = new ObjectLinkedOpenHashSet<>();
	private final Map<String, Change> lastChange = new Object2ObjectLinkedOpenHashMap<>();
	private final Map<String, ArrayDeque<ChangeRecord>> history = new Object2ObjectOpenHashMap<>();
	private final Map<String, ChangeKind> pendingKind = new Object2ObjectOpenHashMap<>();

	/**
	 * How a controller's playback last changed - what the event dispatcher needs to know to decide
	 * whether the new playback is a fresh start, a continuation, or a jump
	 * <p>
	 * Not on the wire: clients never see it, and never need to.
	 */
	public enum ChangeKind {
		/** A new clip (or a restart): its own start instant fires events, and it owes nothing to what came before */
		PLAY,
		/** {@link #setRate}: the same clip, continuous with the old playback, so the event cursor carries straight over */
		REBASE,
		/** {@link #seek} with {@code fireCrossed}: a jump whose crossed events do fire */
		SEEK_FIRE,
		/** {@link #seek} without it: a jump that fires nothing, not even an event at the landing instant */
		SEEK_QUIET,
		/** {@link #stop}: the controller no longer plays anything */
		STOP;

		private static final ChangeKind[] WIRE = values();

		/**
		 * Decodes the byte a delta packet carries. <b>The order of these constants is protocol</b> - reordering or
		 * inserting one changes what every client reads
		 *
		 * @throws IllegalArgumentException for a code no version defines
		 */
		public static ChangeKind fromWire(int wire) {
			if (wire < 0 || wire >= WIRE.length)
				throw new IllegalArgumentException("no animation change kind has wire code " + wire);

			return WIRE[wire];
		}
	}

	/**
	 * @param sequence The sequence the change produced
	 * @param kind What kind of change
	 * @param gameTime The game time the change took effect at, or {@link Long#MIN_VALUE} if the caller
	 *                 did not say (a {@link #stop(String)} without a time)
	 */
	public record Change(long sequence, ChangeKind kind, long gameTime) {
		public static final long UNKNOWN_TIME = Long.MIN_VALUE;
	}

	/**
	 * One entry of a controller's change history: {@link Change}, plus the playback it produced
	 * <p>
	 * The event dispatcher sweeps once per tick and must fire the events of <b>every</b> playback that existed
	 * since its last sweep, not just the one that exists now: a server that stalls across two changes (a play,
	 * then a rate change) would otherwise never see the first playback, drop its events, and compute the exact
	 * time of the rest against the wrong speed. This is what lets it replay them in order, each under its own
	 * identity and its own clock
	 *
	 * @param playback The playback this change started, or null for a {@link ChangeKind#STOP}
	 */
	public record ChangeRecord(long sequence, ChangeKind kind, long gameTime, @Nullable AnimationPlayback playback) {}

	/**
	 * How many changes per controller are remembered. A dispatcher that falls further behind than this has been
	 * stalled for something like a minute of scripted changes, and the oldest are simply not there to replay
	 */
	public static final int HISTORY_CAPACITY = 64;

	/**
	 * Starts (or restarts) a controller playing a clip from the given point
	 * <p>
	 * "Transition" from the {@code play}/{@code transition}/{@code setRate}/{@code stop}
	 * quartet is not a separate method here - a transition's blend duration is how long the client
	 * spends visually interpolating from wherever it currently is into this new playback, which is a
	 * rendering concern, not a fact about what is authoritative. On the wire, starting a
	 * new clip and starting a smoothly-blended one are the same operation: a new
	 * {@link AnimationPlayback}
	 *
	 * @param startGameTick Normally the current {@code World#getTime()}, taken as a parameter rather
	 *                      than read here so this class stays free of a {@code World} dependency and
	 *                      stays trivially testable
	 */
	public void play(String controller, String animation, long startGameTick, float startOffsetTicks,
					 float playbackRate, LoopMode loopMode) {
		set(ChangeKind.PLAY, startGameTick, new AnimationPlayback(controller, animation, startGameTick, startOffsetTicks, playbackRate,
				loopMode, nextSequenceFor(controller)));
	}

	/**
	 * Changes a playing controller's rate without the clip jumping
	 * <p>
	 * Re-bases the playback onto its own current sample point (see {@link AnimationPlayback}'s own
	 * javadoc on why re-basing is what keeps this continuous) rather than just swapping the rate on
	 * the existing record, which would retroactively change how the clip got to where it already is
	 * <p>
	 * A no-op if {@code controller} is not currently playing anything - there is nothing to re-base
	 *
	 * @param gameTime The current {@code World#getTime()}, used both to compute the current sample
	 *                 point and as the re-based playback's new start tick
	 */
	public void setRate(String controller, float playbackRate, long gameTime) {
		AnimationPlayback current = this.playbacks.get(controller);

		if (current == null)
			return;

		float sampledAt = (float)current.elapsedTicks(gameTime, 0);

		set(ChangeKind.REBASE, gameTime, new AnimationPlayback(controller, current.animation(), gameTime, sampledAt, playbackRate,
				current.loopMode(), nextSequenceFor(controller)));
	}

	/**
	 * Stops a controller, clearing its playback entirely
	 * <p>
	 * A no-op if the controller was not playing anything - not marked dirty in that case, since
	 * nothing observable changed
	 * <p>
	 * Still advances the controller's sequence, even though the resulting state has no
	 * {@link AnimationPlayback} of its own to carry it - {@link #lastSequenceFor} is where a caller
	 * building a delta for this stop reads it from instead. Without this, a stop would be the one
	 * mutation with no sequence attached to it at all, and a delayed stop packet could cancel a
	 * newer {@link #play} that a client had already correctly applied - exactly the class of bug the
	 * sequence exists to rule out
	 */
	public void stop(String controller) {
		stop(controller, Change.UNKNOWN_TIME);
	}

	/**
	 * {@link #stop(String)}, saying when it happened
	 * <p>
	 * The event dispatcher uses the time to fire, before the stop takes effect, whatever events the
	 * clip crossed between the last sweep and this instant - without it, a stop landing partway through
	 * a tick would silently drop up to one tick of the clip's events
	 *
	 * @param gameTime The current {@code World#getTime()}
	 */
	public void stop(String controller, long gameTime) {
		if (this.playbacks.remove(controller) != null) {
			long sequence = nextSequenceFor(controller);

			this.lastChange.put(controller, new Change(sequence, ChangeKind.STOP, gameTime));
			remember(controller, new ChangeRecord(sequence, ChangeKind.STOP, gameTime, null));
			this.dirty.add(controller);
		}
	}

	/**
	 * Starts a clip as though it had begun at exactly {@code exactGameTime}, which may fall between
	 * two ticks
	 * <p>
	 * This is how a handler chains one clip onto the instant a marker fired (see
	 * {@code EventContext#exactGameTime}) without the next clip lagging the marker by up to a tick: the
	 * playback starts on the next whole tick at or after the instant, with a start offset that already
	 * accounts for the fraction of a tick between them, so its clip position is exactly right on every
	 * tick from then on. Meant for forward playback; the fraction is rounded <i>up</i> so the clip never
	 * begins before its own start
	 *
	 * @param exactGameTime Normally {@code EventContext#exactGameTime()}, never later than the current
	 *                      game time
	 */
	public void playAt(String controller, String animation, double exactGameTime, float playbackRate, LoopMode loopMode) {
		long startGameTick = (long)Math.ceil(exactGameTime);
		float startOffsetTicks = (float)((startGameTick - exactGameTime) * playbackRate);

		play(controller, animation, startGameTick, startOffsetTicks, playbackRate, loopMode);
	}

	/**
	 * Jumps a playing controller to {@code clipTick}, continuing at its current rate - a new sequence
	 * <p>
	 * By default a seek is silent: the events it jumps over do not fire, and neither does one sitting
	 * exactly on the landing point. Seeking and resync do not replay side
	 * effects unless requested. Pass {@code fireCrossed} to have the events between the old and new
	 * positions fire in order, as if the clip had run through them
	 * <p>
	 * A no-op if {@code controller} is not currently playing anything
	 *
	 * @param clipTick Where in the clip to land, in clip ticks
	 * @param gameTime The current {@code World#getTime()}
	 */
	public void seek(String controller, float clipTick, long gameTime, boolean fireCrossed) {
		AnimationPlayback current = this.playbacks.get(controller);

		if (current == null)
			return;

		set(fireCrossed ? ChangeKind.SEEK_FIRE : ChangeKind.SEEK_QUIET, gameTime,
				new AnimationPlayback(controller, current.animation(), gameTime, clipTick, current.playbackRate(),
						current.loopMode(), nextSequenceFor(controller)));
	}

	/**
	 * How this controller's playback last changed, or null if nothing has ever touched it
	 */
	@Nullable
	public Change lastChange(String controller) {
		return this.lastChange.get(controller);
	}

	/**
	 * This controller's current playback, or null if it is not playing anything
	 */
	@Nullable
	public AnimationPlayback get(String controller) {
		return this.playbacks.get(controller);
	}

	/**
	 * The most recent sequence issued for this controller, including one issued by a {@link #stop}
	 * that left no {@link AnimationPlayback} to read it off of - the only reliable place to read a
	 * stop's own sequence from when building a delta for it
	 * <p>
	 * 0 for a controller nothing has ever touched. A real sequence is never 0 - {@link #nextSequenceFor}
	 * always returns at least 1 - so 0 unambiguously means "never played"
	 */
	public long lastSequenceFor(String controller) {
		return this.nextSequence.getLong(controller);
	}

	/**
	 * Every controller's current playback - what a full snapshot packet sends a client that has just
	 * started tracking this animatable
	 */
	public Collection<AnimationPlayback> snapshot() {
		return this.playbacks.values();
	}

	/**
	 * The controllers that changed since the last call to this method, with their marks cleared
	 * <p>
	 * A controller that was set and then stopped between two drains still comes back - the record it
	 * ends up in ({@link #snapshot()} plus {@link #get}) reflects the net result, but the fact that
	 * it moved is real and a delta receiver still needs to hear about it (to remove a playback it may
	 * be holding from an earlier drain)
	 */
	public Set<String> drainDirty() {
		Set<String> drained = new ObjectLinkedOpenHashSet<>(this.dirty);

		this.dirty.clear();

		return drained;
	}

	/**
	 * What kind of change the next delta for this controller is telling clients about, and forgets it
	 * <p>
	 * Several changes can land in one tick and reach clients as <b>one</b> delta, describing only the last
	 * playback. The kind sent must be the <i>strongest</i> of them - a play followed by a rate change in the
	 * same tick is a {@link ChangeKind#PLAY}, not a {@link ChangeKind#REBASE}, or a client would carry the old
	 * clip's cursor onto a different clip. Strength runs from a fresh play down through a stop and the two seeks
	 * to a rate change, the one change that is continuous with what came before
	 * <p>
	 * Call it once per drained controller, as the delta is built. A controller with nothing recorded (one
	 * reaching a client for the first time) is reported as a play
	 */
	public ChangeKind takePendingKind(String controller) {
		ChangeKind kind = this.pendingKind.remove(controller);

		return kind != null ? kind : ChangeKind.PLAY;
	}

	private static ChangeKind stronger(ChangeKind a, ChangeKind b) {
		return strength(a) >= strength(b) ? a : b;
	}

	private static int strength(ChangeKind kind) {
		return switch (kind) {
			case PLAY -> 5;
			case STOP -> 4;
			case SEEK_QUIET -> 3;
			case SEEK_FIRE -> 2;
			case REBASE -> 1;
		};
	}

	private void set(ChangeKind kind, long gameTime, AnimationPlayback playback) {
		this.lastChange.put(playback.controller(), new Change(playback.sequence(), kind, gameTime));
		remember(playback.controller(), new ChangeRecord(playback.sequence(), kind, gameTime, playback));
		this.playbacks.put(playback.controller(), playback);
		this.dirty.add(playback.controller());
	}

	private void remember(String controller, ChangeRecord record) {
		ArrayDeque<ChangeRecord> log = this.history.computeIfAbsent(controller, name -> new ArrayDeque<>());

		if (log.size() >= HISTORY_CAPACITY)
			log.removeFirst();

		log.addLast(record);
		this.pendingKind.merge(controller, record.kind(), ServerAnimationState::stronger);
	}

	/**
	 * Every change to this controller with a sequence after {@code afterSequence}, oldest first
	 * <p>
	 * Pass the highest sequence already dealt with (0 for none) to get exactly what is new. Sequences only ever
	 * rise, so each change is returned once to a caller that tracks what it has seen
	 */
	public List<ChangeRecord> changesAfter(String controller, long afterSequence) {
		ArrayDeque<ChangeRecord> log = this.history.get(controller);

		if (log == null)
			return List.of();

		List<ChangeRecord> newer = new ArrayList<>();

		for (ChangeRecord record : log) {
			if (record.sequence() > afterSequence)
				newer.add(record);
		}

		return newer;
	}

	/**
	 * Every controller that has a history, including one whose playback has since stopped - a dispatcher must
	 * still be told about the stop
	 */
	public Set<String> controllersWithHistory() {
		return new ObjectLinkedOpenHashSet<>(this.history.keySet());
	}

	private long nextSequenceFor(String controller) {
		return this.nextSequence.addTo(controller, 1L) + 1L;
	}
}
