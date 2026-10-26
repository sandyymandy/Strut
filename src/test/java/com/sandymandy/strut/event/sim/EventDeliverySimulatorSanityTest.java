package com.sandymandy.strut.event.sim;

import com.sandymandy.strut.event.type.EventAuthority;
import com.sandymandy.strut.event.sim.EventDeliverySimulator.Client;
import com.sandymandy.strut.event.sim.EventDeliverySimulator.Fire;
import com.sandymandy.strut.event.sim.EventDeliverySimulator.Link;
import com.sandymandy.strut.loading.binary.StrutBinaryFormats.LoopMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.sandymandy.strut.event.sim.EventDeliverySimulator.clip;
import static com.sandymandy.strut.event.sim.EventDeliverySimulator.marker;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Proof that the simulator itself works, before any delivery test is trusted
 * <p>
 * A test that passes against a simulator which never ran anything proves nothing - the standing lesson of
 * the Iris work. So these use scenarios small enough to count by hand, and assert the exact numbers
 */
class EventDeliverySimulatorSanityTest {
	/** A 20-tick loop: server event at 5, client event at 10, both-sides event at 15 */
	private static EventDeliverySimulator lapping() {
		EventDeliverySimulator sim = new EventDeliverySimulator(1);

		sim.addClip("lap", clip(20, LoopMode.LOOP, marker(0, 5, "s", EventAuthority.SERVER), marker(1, 10, "c", EventAuthority.CLIENT),
				marker(2, 15, "b", EventAuthority.BOTH)));

		return sim;
	}

	private static List<String> markers(List<Fire> fires) {
		return fires.stream().map(Fire::marker).toList();
	}

	@Test
	@DisplayName("three laps of a 20-tick loop: the server fires s and b each lap, a perfect client fires c and b each lap")
	void countedByHand() {
		EventDeliverySimulator sim = lapping();
		Client perfect = sim.addClient("perfect", Link.PERFECT, 0);

		sim.at(100, () -> sim.server().play("base", "lap", 100, 0, 1f, LoopMode.LOOP));
		sim.run(0, 159);

		// Elapsed 0..59: positions 5,15 | 25,35 | 45,55 on the server axis, so s at 5,25,45 and b at 15,35,55
		assertEquals(List.of("s", "b", "s", "b", "s", "b"), markers(sim.serverFires()));
		// and the client's: c at 10,30,50 and b at 15,35,55
		assertEquals(List.of("c", "b", "c", "b", "c", "b"), markers(perfect.history));
	}

	@Test
	@DisplayName("nothing fires before the clip starts, or on a client that is not connected")
	void nothingWithoutAPlaybackOrAConnection() {
		EventDeliverySimulator sim = lapping();
		Client absent = sim.addClient("absent", Link.PERFECT, 500);

		sim.at(100, () -> sim.server().play("base", "lap", 100, 0, 1f, LoopMode.LOOP));
		sim.run(0, 99);

		assertTrue(sim.serverFires().isEmpty());
		assertTrue(absent.history.isEmpty());
		assertFalse(absent.isConnected());
	}

	@Test
	@DisplayName("latency delays an event only if it falls before the client learns of the clip - after that the shared clock keeps it on time")
	void latencyIsReal() {
		EventDeliverySimulator sim = new EventDeliverySimulator(1);

		sim.addClip("early", clip(20, LoopMode.LOOP, marker(0, 1, "e", EventAuthority.CLIENT), marker(1, 10, "l", EventAuthority.CLIENT)));

		Client perfect = sim.addClient("perfect", Link.PERFECT, 0);
		Client slow = sim.addClient("slow", Link.jitter(4, 4), 0);

		sim.at(100, () -> sim.server().play("base", "early", 100, 0, 1f, LoopMode.LOOP));
		sim.run(0, 115);

		Fire perfectEarly = perfect.history.get(0);
		Fire slowEarly = slow.history.get(0);

		assertEquals(perfectEarly.strict(), slowEarly.strict(), "the very same occurrence");
		assertEquals(101, perfectEarly.delivered());
		assertEquals(104, slowEarly.delivered(), "the slow client only learned of the clip at 104, so its event at 101 is delivered late");
		assertEquals(perfectEarly.exact(), slowEarly.exact(), 1e-9, "its authored time is untouched by the delay");

		Fire perfectLater = perfect.history.get(1);
		Fire slowLater = slow.history.get(1);

		assertEquals(110, perfectLater.delivered());
		assertEquals(110, slowLater.delivered(), "an event after the client knows the clip is on time: the clock is shared and the start is absolute");
	}

	@Test
	@DisplayName("a dropped packet really is dropped: a link that loses everything delivers nothing")
	void lossIsReal() {
		EventDeliverySimulator sim = lapping();
		Client deaf = sim.addClient("deaf", Link.lossy(0, 0, 1.0, 0), 0);

		sim.at(100, () -> sim.server().play("base", "lap", 100, 0, 1f, LoopMode.LOOP));
		sim.run(0, 300);

		assertTrue(deaf.history.isEmpty());
		assertFalse(sim.serverFires().isEmpty(), "while the server carried on regardless");
	}

	@Test
	@DisplayName("a duplicating link really does deliver twice, and the client still fires once")
	void duplicationIsReal() {
		EventDeliverySimulator sim = lapping();
		Client echo = sim.addClient("echo", Link.lossy(0, 0, 0, 1.0), 0);

		sim.at(100, () -> sim.server().play("base", "lap", 100, 0, 1f, LoopMode.LOOP));
		sim.run(0, 159);

		assertEquals(List.of("c", "b", "c", "b", "c", "b"), markers(echo.history));
	}

	@Test
	@DisplayName("a server stall really skips sweeps, and the catch-up sweep still fires everything")
	void serverStallIsReal() {
		EventDeliverySimulator sim = lapping();

		sim.at(100, () -> sim.server().play("base", "lap", 100, 0, 1f, LoopMode.LOOP));
		sim.stallServer(101, 150);
		sim.run(0, 105);

		assertEquals(0, sim.serverFires().size(), "elapsed 5 is the first event - and the server is stalled through it");

		sim.run(106, 159);

		assertEquals(List.of("s", "b", "s", "b", "s", "b"), markers(sim.serverFires()), "the catch-up at 150 fired everything the stall held back");
	}

	@Test
	@DisplayName("a disconnect and reconnect really produce a fresh session")
	void reconnectIsReal() {
		EventDeliverySimulator sim = lapping();
		Client c = sim.addClient("c", Link.PERFECT, 0);

		sim.at(100, () -> sim.server().play("base", "lap", 100, 0, 1f, LoopMode.LOOP));
		sim.disconnectAt(c, 120);
		sim.reconnectAt(c, 140);
		sim.run(0, 159);

		assertEquals(1, c.joinedAt.size(), "one start-tracking snapshot, at the reconnect");
		assertEquals(140L, c.joinedAt.get(0));
	}
}
