package com.sandymandy.strut.event.timeline;

import com.sandymandy.strut.event.type.EventAuthority;
import com.sandymandy.strut.event.type.EventTypeRegistry;
import com.sandymandy.strut.event.type.ReversePolicy;
import com.sandymandy.strut.event.type.StrutEventType;
import com.sandymandy.strut.event.builtin.SoundEventPayload;
import com.sandymandy.strut.event.builtin.StrutBuiltinEvents;
import com.sandymandy.strut.loading.binary.StrutBinaryFormats;
import com.sandymandy.strut.loading.binary.StrutBinaryFormats.LoopMode;
import com.sandymandy.strut.loading.binary.raw.RawBinaryAnimation.RawClip;
import com.sandymandy.strut.loading.binary.raw.RawBinaryAnimation.RawEvent;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.util.Identifier;
import net.minecraft.util.Unit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Turning a parsed clip into the event timeline a server holds
 */
class ClipTimelineFactoryTest {
	private static final String SEP = StrutBinaryFormats.EVENT_FIELD_SEPARATOR;

	private static EventTypeRegistry registry() {
		EventTypeRegistry registry = new EventTypeRegistry();

		StrutBuiltinEvents.registerAll(registry);
		registry.register(StrutEventType.builder(Identifier.of("mymod", "strike"),
						PacketCodec.<PacketByteBuf, Unit>unit(Unit.INSTANCE))
				.authority(EventAuthority.SERVER).noPayload(Unit.INSTANCE).build());

		return registry;
	}

	private static RawClip clip(float seconds, LoopMode loop, RawEvent... events) {
		return new RawClip("test", seconds, loop, List.of(), List.of(events));
	}

	private static RawEvent event(float time, String kind, String payload, int authority) {
		return new RawEvent(time, kind, payload, authority);
	}

	private static ClipTimeline build(RawClip clip, List<String> warnings) {
		return ClipTimelineFactory.fromClip(clip, "file.strutanim", registry(), warnings::add);
	}

	private static ClipTimeline build(RawClip clip) {
		return build(clip, new ArrayList<>());
	}

	@Test
	@DisplayName("length and event times convert from seconds to ticks")
	void secondsBecomeTicks() {
		ClipTimeline timeline = build(clip(2f, LoopMode.LOOP, event(0.5f, "sound", "mod:step", 0)));

		assertEquals(40, timeline.lengthTicks(), 1e-9);
		assertEquals(LoopMode.LOOP, timeline.loopMode());
		assertEquals(10, timeline.events().get(0).tick(), 1e-9);
	}

	@Test
	@DisplayName("legacy sound and particle kinds become the engine's typed events with no conversion")
	void legacyKindsBecomeTypedEvents() {
		ClipTimeline timeline = build(clip(1f, LoopMode.PLAY_ONCE,
				event(0.1f, "sound", "mod:step 0.5", 0), event(0.2f, "particle", "mod:spark" + SEP + "hand", 0)));

		assertSame(StrutBuiltinEvents.SOUND, timeline.events().get(0).parsed().type());
		assertEquals(0.5f, ((SoundEventPayload)timeline.events().get(0).parsed().payload()).volume());
		assertSame(StrutBuiltinEvents.PARTICLE, timeline.events().get(1).parsed().type());
	}

	@Test
	@DisplayName("an authority byte of 0 gives the type's own default, so shipped files keep their meaning")
	void authorityZeroIsTheTypeDefault() {
		ClipTimeline timeline = build(clip(1f, LoopMode.PLAY_ONCE,
				event(0.1f, "sound", "mod:step", 0), event(0.2f, "instruction", "mymod:strike", 0)));

		assertEquals(EventAuthority.CLIENT, timeline.events().get(0).authority(), "sound defaults to client");
		assertEquals(EventAuthority.SERVER, timeline.events().get(1).authority(), "mymod:strike defaults to server");
	}

	@Test
	@DisplayName("a non-zero authority byte overrides the type's default for every line of that entry")
	void authorityOverride() {
		ClipTimeline timeline = build(clip(1f, LoopMode.PLAY_ONCE,
				event(0.1f, "instruction", "mymod:strike\nswap", 3)));

		assertEquals(EventAuthority.BOTH, timeline.events().get(0).authority());
		assertEquals(EventAuthority.BOTH, timeline.events().get(1).authority());
	}

	@Test
	@DisplayName("an instruction keyframe with several lines becomes several events, numbered in line order")
	void instructionLinesFlatten() {
		ClipTimeline timeline = build(clip(1f, LoopMode.PLAY_ONCE,
				event(0.5f, "instruction", "mymod:strike\n\nswap_end", 0), event(0.1f, "sound", "mod:step", 0)));

		assertEquals(3, timeline.events().size());
		assertEquals(List.of(0, 1, 2), timeline.events().stream().map(TimelineEvent::index).toList());
		assertSame(StrutBuiltinEvents.MARKER, timeline.events().get(1).parsed().type());
		assertEquals("swap_end", timeline.events().get(1).parsed().payload());
		assertEquals(10, timeline.events().get(0).tick(), 1e-9, "both instruction lines share the keyframe's tick");
		assertEquals(10, timeline.events().get(1).tick(), 1e-9);
	}

	@Test
	@DisplayName("bad entries warn with the file, clip and time, and never lose their neighbours")
	void badEntriesWarnAndAreSkipped() {
		List<String> warnings = new ArrayList<>();

		ClipTimeline timeline = build(clip(1f, LoopMode.PLAY_ONCE,
				event(0.1f, "sound", "", 0),
				event(0.2f, "wat", "x", 0),
				event(0.3f, "instruction", "nope:missing\nmymod:strike", 0),
				event(0.4f, "sound", "mod:ok", 9)), warnings);

		assertEquals(4, warnings.size(), warnings.toString());
		assertTrue(warnings.stream().allMatch(w -> w.contains("file.strutanim") && w.contains("\"test\"")), warnings.toString());
		assertTrue(warnings.get(0).contains("sound id"));
		assertTrue(warnings.get(1).contains("unrecognised event kind"));
		assertTrue(warnings.get(2).contains("nope:missing"));
		assertTrue(warnings.get(3).contains("authority"), "an undefined authority code warns and falls back to the default");
		assertEquals(2, timeline.events().size(), "mymod:strike and the sound with a bad authority code still made it");
	}

	@Test
	@DisplayName("the schedule handed to the cursor carries ticks, indices and the reverse flag")
	void scheduleMatchesTheTimeline() {
		ClipTimeline timeline = build(clip(1f, LoopMode.LOOP, event(0.5f, "instruction", "mymod:strike", 0)));

		assertEquals(20, timeline.schedule().length(), 1e-9);
		assertEquals(LoopMode.LOOP, timeline.schedule().loopMode());
		assertEquals(10, timeline.schedule().events().get(0).tick(), 1e-9);
		assertEquals(ReversePolicy.SKIP.firesInReverse(), timeline.schedule().events().get(0).fireInReverse());
	}

	@Test
	@DisplayName("hasServerEvents tells the dispatcher which clips it can skip entirely")
	void hasServerEvents() {
		assertFalse(build(clip(1f, LoopMode.LOOP, event(0.1f, "sound", "mod:step", 0))).hasServerEvents());
		assertTrue(build(clip(1f, LoopMode.LOOP, event(0.1f, "instruction", "mymod:strike", 0))).hasServerEvents());
		assertFalse(build(clip(1f, LoopMode.LOOP)).hasServerEvents());
	}

	@Test
	@DisplayName("the content hash is stable, and moves with anything that changes what fires")
	void contentHash() {
		RawClip base = clip(1f, LoopMode.LOOP, event(0.5f, "instruction", "mymod:strike", 0));

		assertEquals(build(base).contentHash(), build(base).contentHash(), "same clip, same hash");

		long hash = build(base).contentHash();

		assertNotEquals(hash, build(clip(1f, LoopMode.LOOP, event(0.6f, "instruction", "mymod:strike", 0))).contentHash(), "time");
		assertNotEquals(hash, build(clip(1f, LoopMode.LOOP, event(0.5f, "instruction", "mymod:strike", 2))).contentHash(), "authority");
		assertNotEquals(hash, build(clip(1f, LoopMode.LOOP, event(0.5f, "instruction", "swap", 0))).contentHash(), "type/payload");
		assertNotEquals(hash, build(clip(1f, LoopMode.PLAY_ONCE, event(0.5f, "instruction", "mymod:strike", 0))).contentHash(), "loop mode");
		assertNotEquals(hash, build(clip(2f, LoopMode.LOOP, event(0.5f, "instruction", "mymod:strike", 0))).contentHash(), "length");
	}

	@Test
	@DisplayName("a clip's bones are irrelevant to its hash - only event-relevant data is")
	void hashIgnoresEverythingButEvents() {
		RawClip plain = new RawClip("a", 1f, LoopMode.LOOP, List.of(), List.of(event(0.5f, "sound", "mod:s", 0)));
		RawClip renamed = new RawClip("different name", 1f, LoopMode.LOOP, List.of(), List.of(event(0.5f, "sound", "mod:s", 0)));

		assertEquals(build(plain).contentHash(), build(renamed).contentHash());
	}
}
