package com.sandymandy.strut.event.timeline;

import com.sandymandy.strut.event.type.EventAuthority;
import com.sandymandy.strut.event.type.ParsedEvent;
import com.sandymandy.strut.event.type.ReversePolicy;
import com.sandymandy.strut.event.type.StrutEventType;
import com.sandymandy.strut.event.builtin.SoundEventPayload;
import com.sandymandy.strut.event.builtin.StrutBuiltinEvents;
import com.sandymandy.strut.loading.binary.StrutBinaryFormats.LoopMode;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Declaring the markers a clip must contain - the load-time replacement for a runtime
 * "no animation event detected" timeout
 */
class MarkerRequirementsTest {
	private static final Identifier FILE = Identifier.of("mymod", "strut/animations/scenes/bow.strutanim");

	private static TimelineEvent marker(int index, String name) {
		return new TimelineEvent(index, index, new ParsedEvent<>(StrutBuiltinEvents.MARKER, name), name, EventAuthority.BOTH, ReversePolicy.SKIP);
	}

	private static ClipTimeline timeline(TimelineEvent... events) {
		return new ClipTimeline(100, LoopMode.LOOP, List.of(events), 0L);
	}

	@Test
	@DisplayName("a clip with every required marker has nothing missing")
	void satisfied() {
		MarkerRequirements requirements = new MarkerRequirements();

		requirements.require("clip", "swap", "swap_end");

		assertEquals(List.of(), requirements.missing("clip", timeline(marker(0, "swap"), marker(1, "swap_end"))));
	}

	@Test
	@DisplayName("missing markers are named, sorted")
	void missingAreNamed() {
		MarkerRequirements requirements = new MarkerRequirements();

		requirements.require("clip", "swap_end", "swap", "reset");

		assertEquals(List.of("reset", "swap_end"), requirements.missing("clip", timeline(marker(0, "swap"))));
	}

	@Test
	@DisplayName("names are case-insensitive, and repeated require calls add to the list")
	void caseInsensitiveAndCumulative() {
		MarkerRequirements requirements = new MarkerRequirements();

		requirements.require("clip", "SWAP");
		requirements.require("clip", "Swap_End");

		assertEquals(List.of("swap_end"), requirements.missing("clip", timeline(marker(0, "swap"))));
	}

	@Test
	@DisplayName("swap_end is not swap: a required swap is not satisfied by swap_end alone")
	void exactNamesOnly() {
		MarkerRequirements requirements = new MarkerRequirements();

		requirements.require("clip", "swap");

		assertEquals(List.of("swap"), requirements.missing("clip", timeline(marker(0, "swap_end"))));
	}

	@Test
	@DisplayName("only a strut:marker satisfies a marker requirement - a different event type whose string payload reads the same does not")
	void onlyMarkersCount() {
		MarkerRequirements requirements = new MarkerRequirements();

		// A type of a mod's own whose payload is a string equal to the marker's name: only the type tells them apart
		StrutEventType<String> lookalike = StrutEventType.builder(Identifier.of("mymod", "lookalike"), StrutBuiltinEvents.MARKER.codec())
				.parser(text -> text).build();
		TimelineEvent impostor = new TimelineEvent(0, 0, new ParsedEvent<>(lookalike, "swap"), "swap", EventAuthority.BOTH, ReversePolicy.SKIP);

		requirements.require("clip", "swap");

		assertEquals(List.of("swap"), requirements.missing("clip", timeline(impostor)));
	}

	@Test
	@DisplayName("a sound cue with the same text is not a marker either")
	void soundCuesDoNotCount() {
		MarkerRequirements requirements = new MarkerRequirements();
		TimelineEvent sound = new TimelineEvent(0, 0, new ParsedEvent<>(StrutBuiltinEvents.SOUND, SoundEventPayload.ofKey("swap", 1, 1, "")),
				"swap", EventAuthority.CLIENT, ReversePolicy.SKIP);

		requirements.require("clip", "swap");

		assertEquals(List.of("swap"), requirements.missing("clip", timeline(sound)));
	}

	@Test
	@DisplayName("a clip nobody required anything of has nothing missing")
	void unrequiredClip() {
		assertEquals(List.of(), new MarkerRequirements().missing("clip", timeline()));
	}

	@Test
	@DisplayName("validation warns about a clip that is missing a required marker, once")
	void validationWarnsOnce() {
		MarkerRequirements requirements = new MarkerRequirements();
		ClipTimelines timelines = new ClipTimelines(List.of());

		requirements.require("bow.slow", "swap", "swap_end");
		timelines.register(FILE, "bow.slow", timeline(marker(0, "swap")));

		timelines.validateRequirements("server", requirements);
		timelines.validateRequirements("server", requirements);

		assertEquals(1, timelines.warningCount(), "the missing swap_end warned, and asking again did not repeat it");
	}

	@Test
	@DisplayName("validation is silent for a clip that has everything")
	void validationSilentWhenSatisfied() {
		MarkerRequirements requirements = new MarkerRequirements();
		ClipTimelines timelines = new ClipTimelines(List.of());

		requirements.require("bow.slow", "swap");
		timelines.register(FILE, "bow.slow", timeline(marker(0, "swap")));

		timelines.validateRequirements("client", requirements);

		assertEquals(0, timelines.warningCount());
	}

	@Test
	@DisplayName("a required clip with no timeline at all warns - a typo in the name must not make the check silently do nothing")
	void requiredClipWithNoTimelineWarns() {
		MarkerRequirements requirements = new MarkerRequirements();
		ClipTimelines timelines = new ClipTimelines(List.of());

		requirements.require("bow.slwo", "swap");
		timelines.register(FILE, "bow.slow", timeline(marker(0, "swap")));

		timelines.validateRequirements("server", requirements);

		assertEquals(1, timelines.warningCount());
	}

	@Test
	@DisplayName("a clip that exists under two files is checked in both")
	void checkedInEveryFile() {
		MarkerRequirements requirements = new MarkerRequirements();
		ClipTimelines timelines = new ClipTimelines(List.of());
		Identifier other = Identifier.of("mymod", "strut/animations/scenes/other.strutanim");

		requirements.require("shared", "swap");
		timelines.register(FILE, "shared", timeline(marker(0, "swap")));
		timelines.register(other, "shared", timeline());

		timelines.validateRequirements("server", requirements);

		assertEquals(1, timelines.warningCount(), "only the file lacking swap warns");
	}

	@Test
	@DisplayName("an empty requirement set does nothing")
	void noRequirements() {
		ClipTimelines timelines = new ClipTimelines(List.of());

		timelines.register(FILE, "c", timeline());
		timelines.validateRequirements("server", new MarkerRequirements());

		assertEquals(0, timelines.warningCount());
	}
}
