package com.sandymandy.strut.loading.binary;

import com.sandymandy.strut.loading.binary.StrutBinaryFormats.Interpolation;
import com.sandymandy.strut.loading.binary.StrutBinaryFormats.LoopMode;
import com.sandymandy.strut.loading.binary.StrutBinaryFormats.Property;
import com.sandymandy.strut.loading.binary.StrutBinaryFormats.TargetKind;
import com.sandymandy.strut.loading.binary.raw.RawBinaryAnimation;
import com.sandymandy.strut.loading.binary.raw.RawBinaryAnimation.RawBezier;
import com.sandymandy.strut.loading.binary.raw.RawBinaryAnimation.RawChannel;
import com.sandymandy.strut.loading.binary.raw.RawBinaryAnimation.RawClip;
import com.sandymandy.strut.loading.binary.raw.RawBinaryAnimation.RawEvent;
import com.sandymandy.strut.loading.binary.raw.RawBinaryAnimation.RawKey;
import com.sandymandy.strut.loading.binary.raw.RawBinaryAnimation.RawValue;
import java.util.function.Supplier;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static com.sandymandy.strut.loading.binary.StrutBinaryFormats.*;

/**
 * Reads a {@code .strutanim} file into a {@link RawBinaryAnimation}
 * <p>
 * Little-endian binary behind a four-byte {@code STAM} magic, sharing the string-table
 * convention of {@code .strutmodel} so that {@link BinaryInput} reads both:
 *
 * <pre>
 *   [HEADER 32 bytes]
 *   [STRING TABLE]      uint16-length-prefixed UTF-8, indexed everywhere else
 *   [CLIPS]             each a name, a duration, a loop mode, channels and events
 * </pre>
 *
 * <h2>Easing is a name, not an enum</h2>
 *
 * <p>Each keyframe may carry the name of the easing applied across the segment ending at it,
 * as a string-table index plus its arguments. Holding it as a name rather than a wire code
 * is what lets any easing registered through {@code EasingType.register} - including one a
 * third-party mod adds at runtime - round-trip through the format without a version bump.
 * It is the single reason this format exists rather than an off-the-shelf one.
 */
public final class BinaryAnimationReader {
	private BinaryAnimationReader() {}

	// ===============================================================
	// Entry points
	// ===============================================================

	/**
	 * Read clips from a stream, closing it
	 *
	 * @param stream The file's bytes
	 * @param source The file's name or path, quoted in any error
	 * @throws BinaryFormatException If the bytes are not readable clips
	 */
	public static RawBinaryAnimation read(InputStream stream, String source) {
		byte[] bytes;

		try (InputStream in = stream) {
			bytes = in.readAllBytes();
		}
		catch (IOException ex) {
			throw new BinaryFormatException("\"" + source + "\" could not be read from disk", ex);
		}

		return read(bytes, source);
	}

	/**
	 * Read clips from bytes already in memory
	 *
	 * @param bytes The complete file contents
	 * @param source The file's name or path, quoted in any error
	 * @throws BinaryFormatException If the bytes are not readable clips
	 */
	public static RawBinaryAnimation read(byte[] bytes, String source) {
		if (bytes == null || bytes.length == 0)
			throw new BinaryFormatException("\"" + source + "\" is empty");

		return parse(new BinaryInput(bytes, source));
	}

	// ===============================================================
	// Header
	// ===============================================================

	private static RawBinaryAnimation parse(BinaryInput in) {
		String magic = in.fixedString(4, "magic number");

		if (!ANIMATION_MAGIC.equals(magic))
			throw in.fail("opens with \"" + magic + "\" rather than \"" + ANIMATION_MAGIC
					+ "\"; it is not a Strut binary animation");

		int version = in.u16("format version");

		if (version < ANIMATION_MIN_VERSION)
			throw in.fail("is animation format version " + version + ", which this build no longer"
					+ " reads; the oldest readable version is " + ANIMATION_MIN_VERSION + ". Re-export it");

		if (version > ANIMATION_VERSION)
			throw in.fail("is animation format version " + version + ", which is newer than this"
					+ " build understands (" + ANIMATION_VERSION + "). Update Strut, or re-export"
					+ " the animation from a matching version of the Blockbench plugin");

		int flags = in.u16("header flags");
		int stringCount = in.u16("string count");
		int stringTableSize = in.u32("string table size");
		int clipCount = in.u16("clip count");
		int fingerprint = in.bits32("skeleton fingerprint");

		in.skip(ANIMATION_RESERVED_V100, "reserved header bytes");

		if (in.position() != ANIMATION_HEADER_BYTES)
			throw in.fail("read a " + in.position() + "-byte header for version " + version
					+ "; every version's header is exactly " + ANIMATION_HEADER_BYTES + " bytes, so"
					+ " the version-specific layout above is wrong");

		/*
		 * Checked against what is left before a single string is read. The count is checked
		 * again inside readStringTable; this one catches a header claiming a table larger than
		 * the whole file, which is the cheapest possible rejection of a truncated download.
		 */
		if (stringTableSize > in.remaining())
			throw in.fail("declares a " + stringTableSize + "-byte string table with only "
					+ in.remaining() + " bytes left after its " + ANIMATION_HEADER_BYTES
					+ "-byte header; the file is truncated");

		List<String> strings = readStringTable(in, stringCount);

		in.plausibleCount(clipCount, MIN_CLIP_BYTES, "clips");

		List<RawClip> clips = new ArrayList<>(clipCount);

		for (int i = 0; i < clipCount; i++) {
			clips.add(readClip(in, strings, i));
		}

		return new RawBinaryAnimation(version, flags, fingerprint, List.copyOf(strings), List.copyOf(clips));
	}

	private static List<String> readStringTable(BinaryInput in, int count) {
		in.plausibleCount(count, 2, "strings");

		List<String> strings = new ArrayList<>(count);

		for (int i = 0; i < count; i++) {
			strings.add(in.string("string " + i));
		}

		return strings;
	}

	/**
	 * Resolve a string-table index, failing rather than substituting
	 * <p>
	 * A placeholder would carry the inconsistency forward into a clip that binds to a bone
	 * named {@code "?"}, finds nothing, and animates nothing, with no line anywhere saying why
	 */
	private static String resolve(BinaryInput in, List<String> strings, int index, String what) {
		if (index < 0 || index >= strings.size())
			throw in.fail("names " + what + " with string index " + index
					+ ", but the string table holds " + strings.size() + " entries");

		return strings.get(index);
	}

	// ===============================================================
	// Clips
	// ===============================================================

	private static RawClip readClip(BinaryInput in, List<String> strings, int index) {
		String name = resolve(in, strings, in.u16("clip " + index + " name"), "clip " + index);
		String where = "clip \"" + name + "\"";
		float duration = in.f32(where + " duration");
		LoopMode loop = readEnum(in, () -> LoopMode.fromWire(in.u8(where + " loop mode")), where + " loop mode");

		int channelCount = in.plausibleCount(in.u16(where + " channel count"), MIN_CHANNEL_BYTES,
				"channels of " + where);
		int eventCount = in.u16(where + " event count");

		List<RawChannel> channels = new ArrayList<>(channelCount);

		for (int c = 0; c < channelCount; c++) {
			channels.add(readChannel(in, strings, name, c));
		}

		in.plausibleCount(eventCount, EVENT_BYTES, "events of " + where);

		List<RawEvent> events = new ArrayList<>(eventCount);

		for (int e = 0; e < eventCount; e++) {
			events.add(readEvent(in, strings, name));
		}

		return new RawClip(name, duration, loop, List.copyOf(channels), List.copyOf(events));
	}

	private static RawChannel readChannel(BinaryInput in, List<String> strings, String clip, int index) {
		String where = "channel " + index + " of clip \"" + clip + "\"";
		String target = resolve(in, strings, in.u16(where + " target"), where);
		TargetKind targetKind = readEnum(in, () -> TargetKind.fromWire(in.u8(where + " target kind")),
				where + " target kind");
		Property property = readEnum(in, () -> Property.fromWire(in.u8(where + " property")),
				where + " property");
		Interpolation interpolation = readEnum(in,
				() -> Interpolation.fromWire(in.u8(where + " interpolation")), where + " interpolation");

		int keyCount = in.plausibleCount(in.u16(where + " keyframe count"), MIN_KEY_BYTES,
				"keyframes of " + where);
		List<RawKey> keys = new ArrayList<>(keyCount);

		for (int k = 0; k < keyCount; k++) {
			keys.add(readKey(in, strings, where, k, interpolation));
		}

		return new RawChannel(target, targetKind, property, interpolation, List.copyOf(keys));
	}

	private static RawKey readKey(BinaryInput in, List<String> strings, String channel, int index,
								  @Nullable Interpolation channelInterpolation) {
		String where = "keyframe " + index + " of " + channel;
		float time = in.f32(where + " time");
		Interpolation interpolation = readEnum(in,
				() -> Interpolation.fromWire(in.u8(where + " interpolation")), where + " interpolation");

		if (interpolation == null)
			throw in.fail("has " + where + " declaring the mixed-interpolation sentinel, which is"
					+ " only meaningful on a channel; a keyframe has exactly one mode");

		/*
		 * A channel that declares one mode for all of its keyframes and then contradicts itself
		 * is a broken exporter, not an authoring choice. Caught here because the alternative is
		 * a clip that plays with an interpolation neither the file nor the author intended, with
		 * nothing anywhere saying which of the two statements won.
		 */
		if (channelInterpolation != null && channelInterpolation != interpolation)
			throw in.fail("has " + where + " using " + interpolation + " interpolation, but its"
					+ " channel declares " + channelInterpolation + " for every keyframe; a channel"
					+ " whose keyframes differ must declare the mixed sentinel ("
					+ Interpolation.MIXED + ")");

		int flags = in.u16(where + " flags");
		RawValue value = readValue(in, strings, where + " value", flags, KEY_FLAG_VALUE_EXPRESSION);
		RawValue post = (flags & KEY_FLAG_HAS_POST) != 0
				? readValue(in, strings, where + " outgoing value", flags, KEY_FLAG_POST_EXPRESSION)
				: null;

		String easing = null;
		float[] easingArgs = NO_ARGS;

		if ((flags & KEY_FLAG_HAS_EASING) != 0) {
			easing = resolve(in, strings, in.u16(where + " easing name"), where + " easing");
			int argCount = in.plausibleCount(in.u8(where + " easing argument count"), 4,
					"easing arguments of " + where);
			easingArgs = new float[argCount];

			for (int i = 0; i < argCount; i++) {
				easingArgs[i] = in.f32(where + " easing argument " + i);
			}
		}

		RawBezier bezier = interpolation == Interpolation.BEZIER
				? new RawBezier(
						in.vec3(where + " left handle time"),
						in.vec3(where + " left handle value"),
						in.vec3(where + " right handle time"),
						in.vec3(where + " right handle value"))
				: null;

		return new RawKey(time, interpolation, value, post, easing, easingArgs, bezier);
	}

	/**
	 * Read a keyframe's three components, each a literal float or a Molang expression
	 *
	 * @param expressionBit The flag bit for the first component; the next two follow it
	 */
	private static RawValue readValue(BinaryInput in, List<String> strings, String where, int flags,
									  int expressionBit) {
		float[] literals = new float[3];
		String[] expressions = new String[3];

		for (int i = 0; i < 3; i++) {
			if ((flags & (expressionBit << i)) != 0) {
				expressions[i] = resolve(in, strings, in.u16(where + " component " + i + " expression"),
						where + " component " + i);
			}
			else {
				literals[i] = in.f32(where + " component " + i);
			}
		}

		return new RawValue(literals, expressions);
	}

	private static RawEvent readEvent(BinaryInput in, List<String> strings, String clip) {
		String where = "an event of clip \"" + clip + "\"";
		float time = in.f32(where + " time");
		String kind = resolve(in, strings, in.u16(where + " kind"), where);
		String payload = resolve(in, strings, in.u16(where + " payload"), where);

		return new RawEvent(time, kind, payload, in.u8(where + " authority"));
	}

	private static final float[] NO_ARGS = new float[0];

	/**
	 * Translate an unreadable wire code into an error naming the file and the field
	 * <p>
	 * An {@link IllegalArgumentException} reading "no property has wire code 7" names neither,
	 * and this is exactly the shape of failure a mismatched exporter produces
	 */
	private static <T> T readEnum(BinaryInput in, Supplier<T> read, String what) {
		try {
			return read.get();
		}
		catch (IllegalArgumentException ex) {
			throw in.fail("has " + what + " with an unreadable value: " + ex.getMessage());
		}
	}
}
