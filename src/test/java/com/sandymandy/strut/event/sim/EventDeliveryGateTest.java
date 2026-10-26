package com.sandymandy.strut.event.sim;

import com.sandymandy.strut.event.timeline.TimelineEvent;
import com.sandymandy.strut.event.type.EventAuthority;
import com.sandymandy.strut.event.sim.EventDeliverySimulator.Client;
import com.sandymandy.strut.event.sim.EventDeliverySimulator.Fire;
import com.sandymandy.strut.event.sim.EventDeliverySimulator.Link;
import com.sandymandy.strut.loading.binary.StrutBinaryFormats.LoopMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.sandymandy.strut.event.sim.EventDeliverySimulator.clip;
import static com.sandymandy.strut.event.sim.EventDeliverySimulator.marker;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The delivery guarantee - <b>lag, seeking, reconnects and packet reordering do not duplicate or
 * skip gameplay events</b> - one named test each, run through {@link EventDeliverySimulator}
 * <p>
 * "Gameplay" is the server's events, which are decided by the server clock alone and never depend on a network
 * packet, so what these prove about them is that <i>nothing a network or a stalled tick can do changes which of
 * them fire</i>. The client's cosmetic events are held to a lower bar, stated in each test: at most once per
 * playback, no replay of history, and - on a healthy link - exactly the events an ideal client would have fired
 */
class EventDeliveryGateTest {
	private static final String C = "base";

	/** A 20-tick loop: server event at 5, client event at 10, both-sides event at 15 */
	private static EventDeliverySimulator lapping(long seed) {
		EventDeliverySimulator sim = new EventDeliverySimulator(seed);

		sim.addClip("lap", clip(20, LoopMode.LOOP, marker(0, 5, "s", EventAuthority.SERVER), marker(1, 10, "c", EventAuthority.CLIENT),
				marker(2, 15, "b", EventAuthority.BOTH)));

		return sim;
	}

	private static void play(EventDeliverySimulator sim, long tick, String clip, float rate) {
		sim.at(tick, () -> sim.server().play(C, clip, tick, 0, rate, LoopMode.LOOP));
	}

	private static void setRate(EventDeliverySimulator sim, long tick, float rate) {
		sim.at(tick, () -> sim.server().setRate(C, rate, sim.time()));
	}

	private static List<String> strict(List<Fire> fires) {
		return fires.stream().map(Fire::strict).toList();
	}

	private static void assertNoDuplicates(String what, List<String> keys) {
		assertEquals(new HashSet<>(keys).size(), keys.size(), what + " fired something twice: " + duplicatesIn(keys));
	}

	private static List<String> duplicatesIn(List<String> keys) {
		Set<String> seen = new HashSet<>();
		List<String> duplicates = new ArrayList<>();

		for (String key : keys) {
			if (!seen.add(key))
				duplicates.add(key);
		}

		return duplicates;
	}

	// ---------------------------------------------------------------------------------------------
	// Lag
	// ---------------------------------------------------------------------------------------------

	@Test
	@DisplayName("LAG: a stalled server tick or client tick changes nothing about which events fire, or how many times")
	void lag() {
		// Reference: nobody ever stalls
		EventDeliverySimulator reference = lapping(7);
		Client referenceIdeal = reference.addClient("ideal", Link.PERFECT, 0);

		// Lagged: the server skips ticks (including across an event), and so does the healthy client
		EventDeliverySimulator lagged = lapping(7);
		Client laggedIdeal = lagged.addClient("ideal", Link.PERFECT, 0);
		Client healthy = lagged.addClient("healthy", Link.jitter(0, 4), 0);

		for (EventDeliverySimulator sim : List.of(reference, lagged)) {
			play(sim, 100, "lap", 1f);
			setRate(sim, 150, 1.5f);
			setRate(sim, 200, 0.75f);
		}

		lagged.stallServer(120, 135);
		lagged.stallServer(210, 214);
		lagged.stallClient(healthy, 130, 150);
		lagged.stallClient(healthy, 300, 330);

		reference.run(0, 400);
		lagged.run(0, 400);

		assertFalse(reference.serverFires().isEmpty(), "the scenario must actually fire server events");
		assertEquals(strict(reference.serverFires()), strict(lagged.serverFires()),
				"server: the same events, in the same order, exactly once each, however the ticks stalled");
		assertNoDuplicates("server", strict(lagged.serverFires()));

		assertNoDuplicates("the healthy client", healthy.logicalKeys());
		assertEquals(laggedIdeal.logicalSet(), healthy.logicalSet(), "client: a stalled client still fires exactly what an ideal one does");
		assertEquals(referenceIdeal.logicalSet(), laggedIdeal.logicalSet());
	}

	// ---------------------------------------------------------------------------------------------
	// Seeking
	// ---------------------------------------------------------------------------------------------

	/** A long play-once clip with a both-sides marker every 10 ticks, so a jump is easy to see in the output */
	private static EventDeliverySimulator ruler() {
		EventDeliverySimulator sim = new EventDeliverySimulator(3);
		List<TimelineEvent> events = new ArrayList<>();

		for (int i = 1; i <= 19; i++)
			events.add(marker(i - 1, i * 10, "m" + (i * 10), EventAuthority.BOTH));

		sim.addClip("ruler", clip(200, LoopMode.PLAY_ONCE, events.toArray(new TimelineEvent[0])));

		return sim;
	}

	@Test
	@DisplayName("SEEK (silent): the server fires nothing it jumps over and nothing on the landing instant")
	void seekQuiet() {
		EventDeliverySimulator sim = ruler();

		sim.at(100, () -> sim.server().play(C, "ruler", 100, 0, 1f, LoopMode.PLAY_ONCE));
		// At 145 the clip is at 45 (m10..m40 have fired). Jump to exactly m100, skipping m50..m90
		sim.at(145, () -> sim.server().seek(C, 100, sim.time(), false));
		sim.run(0, 320);

		List<String> fired = sim.serverFires().stream().map(Fire::marker).toList();

		assertEquals(List.of("m10", "m20", "m30", "m40", "m110", "m120", "m130", "m140", "m150", "m160", "m170", "m180", "m190"), fired,
				"m50-m90 were jumped over, and m100 - the landing instant - is silent too");
		assertNoDuplicates("server", strict(sim.serverFires()));
	}

	@Test
	@DisplayName("SEEK (asking to fire crossed events): the server fires what the jump crossed, in order, once")
	void seekFiringCrossed() {
		EventDeliverySimulator sim = ruler();

		sim.at(100, () -> sim.server().play(C, "ruler", 100, 0, 1f, LoopMode.PLAY_ONCE));
		sim.at(145, () -> sim.server().seek(C, 100, sim.time(), true));
		sim.run(0, 320);

		List<String> fired = sim.serverFires().stream().map(Fire::marker).toList();

		assertEquals(List.of("m10", "m20", "m30", "m40", "m50", "m60", "m70", "m80", "m90", "m100", "m110", "m120", "m130", "m140", "m150",
				"m160", "m170", "m180", "m190"), fired, "every marker fires exactly once, the jumped-over ones in order at the jump");
		assertNoDuplicates("server", strict(sim.serverFires()));
	}

	@Test
	@DisplayName("SEEK (silent), on a client: it honours the seek exactly as the server does - the same events, none on the landing instant, none twice")
	void seekQuietOnClients() {
		EventDeliverySimulator sim = ruler();
		Client healthy = sim.addClient("healthy", Link.jitter(0, 4), 0);

		sim.at(100, () -> sim.server().play(C, "ruler", 100, 0, 1f, LoopMode.PLAY_ONCE));
		sim.at(145, () -> sim.server().seek(C, 100, sim.time(), false));
		sim.run(0, 320);

		assertNoDuplicates("the client", healthy.strictKeys());

		// The delta carries the kind of change, so the client knows this seek was silent. (It used to have to
		// guess, treat the landing as a fresh start, and fire m100 - a cosmetic difference that is now closed)
		assertEquals(sim.serverFires().stream().map(Fire::marker).toList(), healthy.history.stream().map(Fire::marker).toList(),
				"the client fires exactly what the server fired, in the same order");
	}

	@Test
	@DisplayName("SEEK (fire crossed), on a client: it fires what the jump crossed, once, like the server")
	void seekFiringCrossedOnClients() {
		EventDeliverySimulator sim = ruler();
		Client healthy = sim.addClient("healthy", Link.jitter(0, 4), 0);

		sim.at(100, () -> sim.server().play(C, "ruler", 100, 0, 1f, LoopMode.PLAY_ONCE));
		sim.at(145, () -> sim.server().seek(C, 100, sim.time(), true));
		sim.run(0, 320);

		assertNoDuplicates("the client", healthy.strictKeys());
		assertEquals(sim.serverFires().stream().map(Fire::marker).toList(), healthy.history.stream().map(Fire::marker).toList());
	}


	// ---------------------------------------------------------------------------------------------
	// Reconnects
	// ---------------------------------------------------------------------------------------------

	@Test
	@DisplayName("RECONNECT: a client that drops out and rejoins replays no history, fires nothing twice, and carries on")
	void reconnect() {
		EventDeliverySimulator sim = lapping(11);
		Client ideal = sim.addClient("ideal", Link.PERFECT, 0);
		Client rejoiner = sim.addClient("rejoiner", Link.jitter(0, 4), 0);

		play(sim, 100, "lap", 1f);
		setRate(sim, 180, 1.5f);
		sim.disconnectAt(rejoiner, 150);
		sim.reconnectAt(rejoiner, 200);
		sim.run(0, 400);

		assertNoDuplicates("the rejoining client, across both sessions", rejoiner.strictKeys());

		long rejoined = rejoiner.joinedAt.get(0);

		for (Fire fire : rejoiner.history) {
			if (fire.delivered() >= 200) {
				assertTrue(fire.exact() >= rejoined, "after rejoining it fired " + fire + ", which was authored before the snapshot arrived at " + rejoined
						+ " - that is replayed history");
			}

			assertFalse(fire.delivered() > 150 + 4 && fire.delivered() < 200, "it fired " + fire + " while it was disconnected");
		}

		Set<String> expectedAfter = new HashSet<>();

		for (Fire fire : ideal.history) {
			if (fire.exact() > rejoined)
				expectedAfter.add(fire.logical());
		}

		Set<String> gotAfter = new HashSet<>();

		for (Fire fire : rejoiner.history) {
			if (fire.delivered() >= 200)
				gotAfter.add(fire.logical());
		}

		assertEquals(expectedAfter, gotAfter, "after rejoining it fires exactly what an ideal client fires from that moment on");
		assertFalse(gotAfter.isEmpty(), "and it does carry on");
	}

	// ---------------------------------------------------------------------------------------------
	// Reordering
	// ---------------------------------------------------------------------------------------------

	@Test
	@DisplayName("REORDER: packets that overtake each other neither repeat nor drop an event, even through back-to-back rate changes")
	void reorder() {
		EventDeliverySimulator sim = lapping(13);
		Client ideal = sim.addClient("ideal", Link.PERFECT, 0);
		// Latency 0..9 against changes one tick apart, so later deltas routinely arrive first
		Client shuffled = sim.addClient("shuffled", Link.jitter(0, 9), 0);

		play(sim, 100, "lap", 1f);
		setRate(sim, 150, 1.5f);
		setRate(sim, 151, 0.5f);
		setRate(sim, 152, 2f);
		setRate(sim, 153, 1f);
		setRate(sim, 250, 0.75f);
		sim.run(0, 500);

		assertNoDuplicates("the shuffled client", shuffled.strictKeys());
		assertNoDuplicates("the shuffled client, by authored event", shuffled.logicalKeys());
		assertEquals(ideal.logicalSet(), shuffled.logicalSet(), "it fires exactly what an ideal client fires - nothing repeated, nothing missing");
		assertNoDuplicates("server", strict(sim.serverFires()));
	}

	// ---------------------------------------------------------------------------------------------
	// Loss and duplication
	// ---------------------------------------------------------------------------------------------

	@Test
	@DisplayName("LOSS: a client on a link that drops and duplicates packets never fires an event twice, and recovers")
	void lossyLink() {
		EventDeliverySimulator sim = lapping(17);
		Client lossy = sim.addClient("lossy", Link.lossy(0, 6, 0.35, 0.3), 0);

		play(sim, 100, "lap", 1f);
		sim.run(0, 900);

		assertNoDuplicates("the lossy client", lossy.strictKeys());
		assertFalse(lossy.history.isEmpty(), "it heard something");

		long last = lossy.history.get(lossy.history.size() - 1).delivered();

		assertTrue(last > 700, "and it kept recovering through the periodic resnapshots rather than going quiet - last event at " + last);
	}
}
