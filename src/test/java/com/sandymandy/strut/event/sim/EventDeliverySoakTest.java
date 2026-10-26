package com.sandymandy.strut.event.sim;

import com.sandymandy.strut.event.type.EventAuthority;
import com.sandymandy.strut.event.sim.EventDeliverySimulator.Client;
import com.sandymandy.strut.event.sim.EventDeliverySimulator.Fire;
import com.sandymandy.strut.event.sim.EventDeliverySimulator.Link;
import com.sandymandy.strut.event.timeline.ClipTimeline;
import com.sandymandy.strut.event.timeline.TimelineEvent;
import com.sandymandy.strut.loading.binary.StrutBinaryFormats.LoopMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static com.sandymandy.strut.event.sim.EventDeliverySimulator.clip;
import static com.sandymandy.strut.event.sim.EventDeliverySimulator.marker;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The guarantee, under randomised stress - hundreds of seeded scenarios, each a random clip,
 * random rate changes, random stalls, random links
 * <p>
 * Two kinds of scenario. A <b>steady</b> one is one clip played once with rate changes only, so the ideal
 * outcome is well defined and a healthy client must match an ideal one <i>exactly</i>. A <b>chaotic</b> one adds
 * replacements, timed stops, seeks, dropped and duplicated packets, and reconnects, where the promise is only
 * the floor: nothing fires twice, and nothing fires that no clip could have fired
 * <p>
 * Every failure names its seed, so it reproduces exactly: {@code new Scenario(seed, mode)}
 */
class EventDeliverySoakTest {
	private static final int SEEDS = 300;
	private static final String C = "base";

	private enum Mode { STEADY, CHAOTIC }

	/**
	 * One randomly generated scenario, built identically every time for a given seed. Stalls are drawn from a
	 * separate generator so a lagged and an unlagged build of the same seed share an identical script
	 */
	private static final class Scenario {
		final long seed;
		final Mode mode;
		final EventDeliverySimulator sim;
		final Client ideal;
		final List<Client> healthy = new ArrayList<>();
		final List<Client> chaotic = new ArrayList<>();
		final long end;

		Scenario(long seed, Mode mode, boolean lagged) {
			this.seed = seed;
			this.mode = mode;
			this.sim = new EventDeliverySimulator(seed * 31 + 5);
			this.end = 700;

			Random random = new Random(seed);
			Random stalls = new Random(seed ^ 0x5DEECE66DL);

			addClips(random);
			this.ideal = this.sim.addClient("ideal", Link.PERFECT, 0);

			scriptServer(random);
			addClients(random);

			if (lagged)
				addStalls(stalls);
		}

		private void addClips(Random random) {
			for (String name : List.of("lap", "other")) {
				double length = 12 + random.nextInt(49);
				int count = 3 + random.nextInt(4);
				List<TimelineEvent> events = new ArrayList<>();

				for (int i = 0; i < count; i++) {
					EventAuthority authority = EventAuthority.values()[random.nextInt(3)];

					events.add(marker(i, Math.round(random.nextDouble() * length * 4) / 4.0, name + i, authority));
				}

				this.sim.addClip(name, new ClipTimeline(length, LoopMode.LOOP, List.copyOf(events), 0L));
			}
		}

		private void scriptServer(Random random) {
			float[] rates = {0.5f, 0.75f, 1f, 1.5f, 2f};

			this.sim.at(100, () -> this.sim.server().play(C, "lap", 100, 0, rates[random.nextInt(rates.length)], LoopMode.LOOP));

			int changes = random.nextInt(6);
			long tick = 101;

			for (int i = 0; i < changes; i++) {
				// Sometimes back to back, so their packets can overtake each other
				tick += random.nextInt(4) == 0 ? 1 : 5 + random.nextInt(90);

				long at = tick;
				float rate = rates[random.nextInt(rates.length)];

				this.sim.at(at, () -> this.sim.server().setRate(C, rate, this.sim.time()));
			}

			if (this.mode == Mode.CHAOTIC) {
				int disruptions = 1 + random.nextInt(4);

				for (int i = 0; i < disruptions; i++) {
					long at = 120 + random.nextInt(450);

					switch (random.nextInt(4)) {
						case 0 -> this.sim.at(at, () -> this.sim.server().play(C, "other", this.sim.time(), 0, 1f, LoopMode.LOOP));
						case 1 -> this.sim.at(at, () -> this.sim.server().stop(C, this.sim.time()));
						case 2 -> this.sim.at(at, () -> this.sim.server().seek(C, random.nextInt(40), this.sim.time(), false));
						default -> this.sim.at(at, () -> this.sim.server().seek(C, random.nextInt(40), this.sim.time(), true));
					}
				}
			}
		}

		private void addClients(Random random) {
			this.healthy.add(this.sim.addClient("healthy", Link.jitter(0, random.nextInt(9)), 0));
			this.healthy.add(this.sim.addClient("late", Link.jitter(0, random.nextInt(9)), 100 + random.nextInt(250)));

			if (this.mode == Mode.CHAOTIC) {
				Client lossy = this.sim.addClient("lossy", Link.lossy(0, random.nextInt(13), random.nextDouble() * 0.4, random.nextDouble() * 0.4), 0);
				Client rejoiner = this.sim.addClient("rejoiner", Link.jitter(0, 6), 0);

				this.chaotic.add(lossy);
				this.chaotic.add(rejoiner);
				this.sim.disconnectAt(rejoiner, 150 + random.nextInt(200));
				this.sim.reconnectAt(rejoiner, 400 + random.nextInt(100));
			}
		}

		private void addStalls(Random stalls) {
			for (int i = 0; i < 3; i++) {
				long from = 100 + stalls.nextInt(500);

				this.sim.stallServer(from, from + 1 + stalls.nextInt(25));
			}

			for (Client client : this.healthy) {
				long from = 100 + stalls.nextInt(500);

				this.sim.stallClient(client, from, from + 1 + stalls.nextInt(25));
			}
		}

		void run() {
			this.sim.run(0, this.end);
		}

		String describe() {
			return "seed " + this.seed + " (" + this.mode + ")";
		}
	}

	private static List<String> strict(List<Fire> fires) {
		return fires.stream().map(Fire::strict).toList();
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

	/** Every fired marker must be one some clip actually has at that index - nothing invented */
	private static void assertOnlyPossibleEvents(Scenario scenario, Client client) {
		for (Fire fire : client.history) {
			boolean possible = scenario.sim.clips().values().stream().anyMatch(timeline ->
					fire.index() < timeline.events().size() && timeline.events().get(fire.index()).parsed().payload().equals(fire.marker()));

			assertTrue(possible, scenario.describe() + ": " + client.name + " fired " + fire + ", which no clip contains");
			assertTrue(fire.loop() >= 0 || scenario.mode == Mode.CHAOTIC, scenario.describe() + ": negative loop " + fire);
		}
	}

	@Test
	@DisplayName("SOAK (steady): across " + SEEDS + " random scenarios, a healthy client fires exactly what an ideal one does, and the server never depends on stalls")
	void steady() {
		for (long seed = 0; seed < SEEDS; seed++) {
			Scenario reference = new Scenario(seed, Mode.STEADY, false);
			Scenario lagged = new Scenario(seed, Mode.STEADY, true);

			reference.run();
			lagged.run();

			// Server: identical with and without stalls, and never twice
			assertEquals(strict(reference.sim.serverFires()), strict(lagged.sim.serverFires()), lagged.describe() + ": server events changed under lag");
			assertEquals(List.of(), duplicatesIn(strict(lagged.sim.serverFires())), lagged.describe() + ": server fired twice");

			for (Client client : lagged.healthy) {
				assertEquals(List.of(), duplicatesIn(client.logicalKeys()), lagged.describe() + ": " + client.name + " fired an authored event twice");
				assertOnlyPossibleEvents(lagged, client);

				Set<String> expected = new HashSet<>();
				long joined = client.joinedAt.isEmpty() ? Long.MIN_VALUE : client.joinedAt.get(0);

				for (Fire fire : lagged.ideal.history) {
					if (fire.exact() > joined)
						expected.add(fire.logical());
				}

				Set<String> missing = new HashSet<>(expected);
				Set<String> unexpected = client.logicalSet();

				missing.removeAll(client.logicalSet());
				unexpected.removeAll(expected);

				assertTrue(missing.isEmpty() && unexpected.isEmpty(), lagged.describe() + ": " + client.name + " differs from an ideal client - missed "
						+ missing + ", fired extra " + unexpected + (joined == Long.MIN_VALUE ? "" : " (joined by snapshot at " + joined + ")"));
			}
		}
	}

	@Test
	@DisplayName("SOAK (chaotic): across " + SEEDS + " random scenarios with replacements, stops, seeks, loss and reconnects, nothing fires twice and nothing is invented")
	void chaotic() {
		for (long seed = 0; seed < SEEDS; seed++) {
			Scenario reference = new Scenario(seed, Mode.CHAOTIC, false);
			Scenario lagged = new Scenario(seed, Mode.CHAOTIC, true);

			reference.run();
			lagged.run();

			assertEquals(strict(reference.sim.serverFires()), strict(lagged.sim.serverFires()), lagged.describe() + ": server events changed under lag");
			assertEquals(List.of(), duplicatesIn(strict(lagged.sim.serverFires())), lagged.describe() + ": server fired twice");

			List<Client> everyone = new ArrayList<>(lagged.healthy);

			everyone.addAll(lagged.chaotic);
			everyone.add(lagged.ideal);

			for (Client client : everyone) {
				assertEquals(List.of(), duplicatesIn(client.strictKeys()), lagged.describe() + ": " + client.name + " fired a playback's event twice");
				assertOnlyPossibleEvents(lagged, client);
			}
		}
	}
}
