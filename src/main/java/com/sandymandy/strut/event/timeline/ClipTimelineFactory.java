package com.sandymandy.strut.event.timeline;

import com.sandymandy.strut.event.type.EventAuthority;
import com.sandymandy.strut.event.type.EventLine;
import com.sandymandy.strut.event.type.EventTypeRegistry;
import com.sandymandy.strut.event.type.ParsedEvent;
import com.sandymandy.strut.event.type.ReversePolicy;
import com.sandymandy.strut.event.type.StrutEventType;
import com.sandymandy.strut.event.builtin.StrutBuiltinEvents;
import com.sandymandy.strut.loading.binary.StrutBinaryFormats;
import com.sandymandy.strut.loading.binary.raw.RawBinaryAnimation;
import com.sandymandy.strut.loading.binary.raw.RawBinaryAnimation.RawClip;
import com.sandymandy.strut.loading.binary.raw.RawBinaryAnimation.RawEvent;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.jetbrains.annotations.Nullable;

/**
 * Builds {@link ClipTimeline}s from a parsed {@code .strutanim}, keeping the events and dropping
 * everything else - the <b>metadata-only bake</b>
 * <p>
 * Works on the raw parse rather than {@code BinaryAnimationBaker}'s output for two reasons: the baker
 * discards each event's authority byte, and baking bones a server never poses is work and memory for
 * nothing. It is also what the client will use on its own clip, so both sides hash identically
 *
 * <h2>Legacy kinds</h2>
 *
 * <p>The three event kinds every shipped file uses map onto typed events with no conversion:
 * {@code sound} becomes {@code strut:sound}, {@code particle} becomes {@code strut:particle}, and
 * {@code instruction} is read with the instruction-channel grammar ({@link EventLine}), one event per
 * line. Nothing is ever split out of a sound string: a sound entry <i>is</i> a sound, and anything
 * else belongs on the instruction channel
 *
 * <h2>Bad input never throws</h2>
 *
 * <p>An entry that cannot be read (an unknown kind, an unknown or unparsable type, an undefined
 * authority code) is reported through {@code warn} - naming the clip and time - and skipped. One bad
 * event must not lose the clip's other events, and must not stop a resource reload
 */
public final class ClipTimelineFactory {
	private static final double TICKS_PER_SECOND = 20d;

	private ClipTimelineFactory() {}

	/**
	 * @param source A name for the file, for messages
	 * @return Every clip in the file, by clip name
	 */
	public static Map<String, ClipTimeline> fromBinary(RawBinaryAnimation raw, String source, EventTypeRegistry registry,
													   Consumer<String> warn) {
		Map<String, ClipTimeline> timelines = new LinkedHashMap<>();

		for (RawClip clip : raw.clips()) {
			timelines.put(clip.name(), fromClip(clip, source, registry, warn));
		}

		return timelines;
	}

	public static ClipTimeline fromClip(RawClip clip, String source, EventTypeRegistry registry, Consumer<String> warn) {
		List<TimelineEvent> events = new ArrayList<>();

		for (RawEvent raw : clip.events()) {
			String where = source + " clip \"" + clip.name() + "\" event at " + raw.time() + "s";
			EventAuthority override = authorityOf(raw, where, warn);

			for (Entry entry : entriesOf(raw, registry, where, warn)) {
				StrutEventType<?> type = entry.event.type();

				events.add(new TimelineEvent(events.size(), raw.time() * TICKS_PER_SECOND, entry.event, entry.payloadText,
						resolveAuthority(entry.authority, override, type), resolveReverse(entry.reverse, type)));
			}
		}

		double length = clip.duration() * TICKS_PER_SECOND;

		return new ClipTimeline(length, clip.loop(), events, hash(length, clip.loop(), events));
	}

	/**
	 * @param authority The line's own authority modifier, or null
	 * @param reverse Whether the line asked to fire in reverse
	 */
	private record Entry(ParsedEvent<?> event, String payloadText, @Nullable EventAuthority authority, boolean reverse) {}

	/**
	 * Where an event runs: the line's own modifier, else the authority byte of the entry it came from,
	 * else its type's default. The closer to the author's hand, the higher it ranks
	 */
	private static EventAuthority resolveAuthority(@Nullable EventAuthority line, @Nullable EventAuthority entry, StrutEventType<?> type) {
		if (line != null)
			return line;

		return entry != null ? entry : type.defaultAuthority();
	}

	/**
	 * A line that asks for {@code reverse} fires in reverse; otherwise its type's default decides
	 */
	private static ReversePolicy resolveReverse(boolean lineAsked, StrutEventType<?> type) {
		return lineAsked ? ReversePolicy.FIRE : type.defaultReverse();
	}

	private static EventAuthority authorityOf(RawEvent raw, String where, Consumer<String> warn) {
		try {
			return EventAuthority.fromWire(raw.authority());
		}
		catch (IllegalArgumentException ex) {
			warn.accept(where + ": " + ex.getMessage() + "; using the event type's default");

			return null;
		}
	}

	private static List<Entry> entriesOf(RawEvent raw, EventTypeRegistry registry, String where, Consumer<String> warn) {
		List<Entry> entries = new ArrayList<>();

		switch (raw.kind()) {
			case StrutBinaryFormats.EVENT_KIND_SOUND -> resolve(StrutBuiltinEvents.SOUND, raw.payload(), where, warn, entries);
			case StrutBinaryFormats.EVENT_KIND_PARTICLE -> resolve(StrutBuiltinEvents.PARTICLE, raw.payload(), where, warn, entries);
			case StrutBinaryFormats.EVENT_KIND_INSTRUCTION -> {
				for (EventLine.Line line : EventLine.parse(raw.payload())) {
					EventTypeRegistry.Resolution resolution = registry.resolve(line);

					if (resolution.isOk()) {
						entries.add(new Entry(resolution.event(), line.payload(), line.authority(), line.reverse()));
					}
					else {
						warn.accept(where + ": " + resolution.error());
					}
				}
			}
			default -> warn.accept(where + ": unrecognised event kind \"" + raw.kind() + "\"; skipped");
		}

		return entries;
	}

	private static <P> void resolve(StrutEventType<P> type, String payload, String where, Consumer<String> warn, List<Entry> out) {
		try {
			out.add(new Entry(new ParsedEvent<>(type, type.parse(payload)), payload, null, false));
		}
		catch (IllegalArgumentException ex) {
			warn.accept(where + ": " + ex.getMessage());
		}
	}

	/**
	 * A stable 64-bit hash of everything that decides what fires and when
	 */
	private static long hash(double length, StrutBinaryFormats.LoopMode loop, List<TimelineEvent> events) {
		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			DataOutputStream out = new DataOutputStream(bytes);

			out.writeDouble(length);
			out.writeByte(loop.ordinal());
			out.writeInt(events.size());

			for (TimelineEvent event : events) {
				out.writeInt(event.index());
				out.writeDouble(event.tick());
				writeString(out, event.parsed().type().id().toString());
				writeString(out, event.payloadText());
				out.writeByte(event.authority().ordinal());
				out.writeByte(event.reverse() == ReversePolicy.FIRE ? 1 : 0);
			}

			byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray());
			long hash = 0;

			for (int i = 0; i < Long.BYTES; i++) {
				hash = (hash << 8) | (digest[i] & 0xFF);
			}

			return hash;
		}
		catch (IOException | NoSuchAlgorithmException ex) {
			throw new IllegalStateException("cannot hash a clip timeline", ex);
		}
	}

	private static void writeString(DataOutputStream out, String value) throws IOException {
		byte[] bytes = value.getBytes(StandardCharsets.UTF_8);

		out.writeInt(bytes.length);
		out.write(bytes);
	}
}
