package com.sandymandy.strut.event.sim;

import com.sandymandy.strut.animation.authority.AnimationPlayback;
import com.sandymandy.strut.animation.authority.ClientAnimationState;
import com.sandymandy.strut.animation.authority.ServerAnimationState;
import com.sandymandy.strut.animation.authority.SnapshotReason;
import com.sandymandy.strut.event.type.EventAuthority;
import com.sandymandy.strut.event.server.EventContext;
import com.sandymandy.strut.event.client.ClientEventContext;
import com.sandymandy.strut.event.type.ParsedEvent;
import com.sandymandy.strut.event.type.ReversePolicy;
import com.sandymandy.strut.event.builtin.StrutBuiltinEvents;
import com.sandymandy.strut.event.dispatch.ClientEventDispatcher;
import com.sandymandy.strut.event.dispatch.ClientEventListeners;
import com.sandymandy.strut.event.dispatch.EventLog;
import com.sandymandy.strut.event.dispatch.ServerEventDispatcher;
import com.sandymandy.strut.event.dispatch.ServerEventListeners;
import com.sandymandy.strut.event.timeline.ClipTimeline;
import com.sandymandy.strut.event.timeline.TimelineEvent;
import com.sandymandy.strut.loading.binary.StrutBinaryFormats.LoopMode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * A deterministic model of the whole event pipeline, built from the <b>real</b> classes, for proving the
 * delivery guarantee: lag, seeking, reconnects and packet reordering do not duplicate or skip events
 * <p>
 * Nothing in the event system is mocked: the server is a real {@link ServerAnimationState} swept by a real
 * {@link ServerEventDispatcher}; each client is a real {@link ClientAnimationState} swept by a real
 * {@link ClientEventDispatcher}. What the simulator supplies is only the world around them, mirroring
 * {@code ServerAnimationStates}: once per tick the server sweeps, then sends a delta per dirty controller to
 * every connected client, and every {@value #RESNAPSHOT_INTERVAL} ticks resends everything; a client that starts
 * tracking gets a start-tracking snapshot. Between server and client sits a network with per-client latency
 * jitter (so packets overtake each other), loss and duplication, all drawn from one seeded {@link Random} so
 * a failing seed reproduces exactly
 * <p>
 * Also injected: server tick stalls, client tick stalls, clients that disconnect and reconnect, and scripted
 * server actions (play, stop, rate change, seek) at chosen ticks
 *
 * <h2>Model assumptions</h2>
 *
 * <p>Server and client read the same {@code World#getTime()} - in the game a client's clock is corrected by
 * time-update packets, and a drift of a tick or two is real but is a clock question, not an event one. A
 * client's world does not advance while its tick stalls; it simply sweeps late with the then-current time
 */
public final class EventDeliverySimulator {
	public static final int RESNAPSHOT_INTERVAL = 200;

	private static final UUID ENTITY = new UUID(42, 42);
	private static final int ENTITY_ID = 1;

	private final Random random;
	private final Map<String, ClipTimeline> clips = new HashMap<>();
	private final TreeMap<Long, List<Runnable>> script = new TreeMap<>();
	private final Set<Long> serverStalls = new HashSet<>();

	private final ServerAnimationState state = new ServerAnimationState();
	private final ServerEventListeners serverListeners = new ServerEventListeners();
	private final ServerEventDispatcher serverDispatcher = new ServerEventDispatcher(this.serverListeners, new EventLog());
	private final List<Fire> serverFires = new ArrayList<>();
	private final List<Client> clients = new ArrayList<>();

	private long time;
	private long messageCounter;

	public EventDeliverySimulator(long seed) {
		this.random = new Random(seed);

		this.serverListeners.add(StrutBuiltinEvents.MARKER.id(), context -> this.serverFires.add(Fire.of(context, this.time)));
	}

	// ---------------------------------------------------------------------------------------------
	// The world
	// ---------------------------------------------------------------------------------------------

	/**
	 * What happened: one delivered event, as a listener saw it
	 *
	 * @param logical Identity that survives a rate change - controller, loop and event index, not the playback
	 *                sequence, which a rate change advances. Two fires with the same logical key are the same
	 *                authored event fired twice
	 */
	public record Fire(String controller, long sequence, long loop, int index, String marker, double exact, long delivered, String logical) {
		static Fire of(EventContext context, long time) {
			return new Fire(context.controller(), context.sequence(), context.loop(), context.eventIndex(), (String)context.payload(), context.exactGameTime(),
					time, context.controller() + "|" + context.loop() + "|" + context.eventIndex());
		}

		static Fire of(ClientEventContext context, long time) {
			return new Fire(context.controller(), context.sequence(), context.loop(), context.eventIndex(), (String)context.payload(), context.exactGameTime(),
					time, context.controller() + "|" + context.loop() + "|" + context.eventIndex());
		}

		/** The identity a dispatcher uses: includes the playback sequence */
		public String strict() {
			return this.controller + "|" + this.sequence + "|" + this.loop + "|" + this.index;
		}
	}

	/**
	 * The link to one client
	 *
	 * @param dropChance Probability a packet never arrives
	 * @param duplicateChance Probability a packet arrives twice
	 */
	public record Link(int minLatency, int maxLatency, double dropChance, double duplicateChance) {
		public static final Link PERFECT = new Link(0, 0, 0, 0);

		public static Link jitter(int min, int max) {
			return new Link(min, max, 0, 0);
		}

		public static Link lossy(int min, int max, double drop, double duplicate) {
			return new Link(min, max, drop, duplicate);
		}
	}

	private sealed interface Message permits Delta, Snapshot {}

	private record Delta(String controller, long sequence, AnimationPlayback playback, ServerAnimationState.ChangeKind kind) implements Message {}

	private record Snapshot(SnapshotReason reason, List<AnimationPlayback> playbacks) implements Message {}

	private record Queued(long deliverAt, long order, Message message) implements Comparable<Queued> {
		@Override
		public int compareTo(Queued other) {
			int byTime = Long.compare(this.deliverAt, other.deliverAt);

			return byTime != 0 ? byTime : Long.compare(this.order, other.order);
		}
	}

	/**
	 * One client and everything it has done, across every connection it makes
	 */
	public final class Client {
		public final String name;
		public final Link link;
		public final List<Fire> history = new ArrayList<>();
		/** Game times at which each start-tracking snapshot was applied - a reconnect's "now" */
		public final List<Long> joinedAt = new ArrayList<>();

		private final ClientEventListeners listeners = new ClientEventListeners();
		private final ClientEventDispatcher dispatcher = new ClientEventDispatcher(this.listeners, new EventLog());
		private final PriorityQueue<Queued> inbox = new PriorityQueue<>();
		private final Set<Long> stalls = new HashSet<>();
		private ClientAnimationState clientState;
		private boolean connected;
		/** True until the first message of the current connection has been applied */
		private boolean sessionFresh;

		Client(String name, Link link) {
			this.name = name;
			this.link = link;

			this.listeners.add(StrutBuiltinEvents.MARKER.id(), context -> this.history.add(Fire.of(context, EventDeliverySimulator.this.time)));
		}

		public boolean isConnected() {
			return this.connected;
		}

		/** Logical keys of everything this client fired, in order */
		public List<String> logicalKeys() {
			return this.history.stream().map(Fire::logical).toList();
		}

		public Set<String> logicalSet() {
			return new HashSet<>(logicalKeys());
		}

		public List<String> strictKeys() {
			return this.history.stream().map(Fire::strict).toList();
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Building a scenario
	// ---------------------------------------------------------------------------------------------

	public static TimelineEvent marker(int index, double tick, String name, EventAuthority authority) {
		return new TimelineEvent(index, tick, new ParsedEvent<>(StrutBuiltinEvents.MARKER, name), name, authority, ReversePolicy.SKIP);
	}

	public static ClipTimeline clip(double length, LoopMode mode, TimelineEvent... events) {
		return new ClipTimeline(length, mode, List.of(events), 0L);
	}

	public void addClip(String name, ClipTimeline timeline) {
		this.clips.put(name, timeline);
	}

	public Map<String, ClipTimeline> clips() {
		return this.clips;
	}

	public ServerAnimationState server() {
		return this.state;
	}

	public long time() {
		return this.time;
	}

	public List<Fire> serverFires() {
		return this.serverFires;
	}

	/**
	 * A client that connects at {@code connectAt}
	 */
	public Client addClient(String name, Link link, long connectAt) {
		Client client = new Client(name, link);

		this.clients.add(client);
		at(connectAt, () -> connect(client));

		return client;
	}

	/**
	 * Runs {@code action} at the start of tick {@code tick}, before the server sweeps
	 */
	public void at(long tick, Runnable action) {
		this.script.computeIfAbsent(tick, t -> new ArrayList<>()).add(action);
	}

	public void disconnectAt(Client client, long tick) {
		at(tick, () -> client.connected = false);
	}

	public void reconnectAt(Client client, long tick) {
		at(tick, () -> connect(client));
	}

	/** The server skips its sweep for ticks in {@code [from, to)} and catches up at {@code to} */
	public void stallServer(long from, long to) {
		for (long t = from; t < to; t++)
			this.serverStalls.add(t);
	}

	/** The client skips its sweep for ticks in {@code [from, to)}; packets still arrive and apply */
	public void stallClient(Client client, long from, long to) {
		for (long t = from; t < to; t++)
			client.stalls.add(t);
	}

	// ---------------------------------------------------------------------------------------------
	// Running it
	// ---------------------------------------------------------------------------------------------

	/**
	 * Runs ticks {@code from} through {@code to}, inclusive
	 */
	public void run(long from, long to) {
		for (long tick = from; tick <= to; tick++) {
			this.time = tick;

			for (Runnable action : this.script.getOrDefault(tick, List.of())) {
				action.run();
			}

			if (!this.serverStalls.contains(tick))
				serverSweep();

			flush();

			for (Client client : this.clients) {
				deliver(client);

				if (client.connected && !client.stalls.contains(tick))
					clientSweep(client);
			}
		}
	}

	private void serverSweep() {
		this.serverDispatcher.sweep(new ServerEventDispatcher.Subject(ENTITY_ID, ENTITY, null, null), this.state,
				clip -> Optional.ofNullable(this.clips.get(clip)), this.time);
	}

	/**
	 * Mirrors {@code ServerAnimationStates.flushDirty} and the periodic resnapshot
	 */
	private void flush() {
		for (String controller : this.state.drainDirty()) {
			Delta delta = new Delta(controller, this.state.lastSequenceFor(controller), this.state.get(controller),
					this.state.takePendingKind(controller));

			for (Client client : this.clients) {
				if (client.connected)
					send(client, delta);
			}
		}

		if (this.time % RESNAPSHOT_INTERVAL == 0) {
			for (Client client : this.clients) {
				if (client.connected)
					send(client, new Snapshot(SnapshotReason.PERIODIC, List.copyOf(this.state.snapshot())));
			}
		}
	}

	private void connect(Client client) {
		client.connected = true;
		client.sessionFresh = true;
		client.clientState = new ClientAnimationState(() -> this.time);
		client.dispatcher.forget(ENTITY_ID);
		client.inbox.clear();

		// EntityTrackingEvents.START_TRACKING sends a snapshot only if there is something to say
		if (!this.state.snapshot().isEmpty())
			send(client, new Snapshot(SnapshotReason.START_TRACKING, List.copyOf(this.state.snapshot())));
	}

	private void send(Client client, Message message) {
		if (this.random.nextDouble() < client.link.dropChance())
			return;

		int copies = this.random.nextDouble() < client.link.duplicateChance() ? 2 : 1;

		for (int i = 0; i < copies; i++) {
			int latency = client.link.minLatency() + (client.link.maxLatency() > client.link.minLatency()
					? this.random.nextInt(client.link.maxLatency() - client.link.minLatency() + 1) : 0);

			client.inbox.add(new Queued(this.time + latency, this.messageCounter++, message));
		}
	}

	private void deliver(Client client) {
		while (!client.inbox.isEmpty() && client.inbox.peek().deliverAt() <= this.time) {
			Message message = client.inbox.poll().message();

			// A packet that arrives at a disconnected client is simply gone
			if (!client.connected)
				continue;

			boolean wasFresh = client.sessionFresh;

			client.sessionFresh = false;

			if (message instanceof Delta delta) {
				client.clientState.applyDelta(delta.controller(), delta.sequence(), delta.playback(), delta.kind());
			}
			else if (message instanceof Snapshot snapshot) {
				client.clientState.applySnapshot(snapshot.playbacks(), snapshot.reason());

				// The client treats its first snapshot as its introduction whatever the server called it, so that is
				// the moment its "no history" starts - an explicit resync excepted, which is a request for catch-up
				if (!snapshot.playbacks().isEmpty() && (snapshot.reason() == SnapshotReason.START_TRACKING || (snapshot.reason() == SnapshotReason.PERIODIC && wasFresh)))
					client.joinedAt.add(this.time);
			}
		}
	}

	private void clientSweep(Client client) {
		client.dispatcher.sweep(new ClientEventDispatcher.Subject(ENTITY_ID, ENTITY, null, false, null), client.clientState,
				clip -> Optional.ofNullable(this.clips.get(clip)), this.time);
	}
}
