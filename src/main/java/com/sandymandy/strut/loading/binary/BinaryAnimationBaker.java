package com.sandymandy.strut.loading.binary;

import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import com.sandymandy.strut.StrutConstants;
import com.sandymandy.strut.animation.Animation;
import com.sandymandy.strut.animation.BoneKey;
import com.sandymandy.strut.animation.EasingType;
import com.sandymandy.strut.animation.keyframe.BoneAnimation;
import com.sandymandy.strut.animation.keyframe.Keyframe;
import com.sandymandy.strut.animation.keyframe.KeyframeStack;
import com.sandymandy.strut.animation.keyframe.event.data.CustomInstructionKeyframeData;
import com.sandymandy.strut.animation.keyframe.event.data.KeyFrameData;
import com.sandymandy.strut.animation.keyframe.event.data.ParticleKeyframeData;
import com.sandymandy.strut.animation.keyframe.event.data.SoundKeyframeData;
import com.sandymandy.strut.loading.binary.StrutBinaryFormats.Interpolation;
import com.sandymandy.strut.loading.binary.StrutBinaryFormats.LoopMode;
import com.sandymandy.strut.loading.binary.StrutBinaryFormats.Property;
import com.sandymandy.strut.loading.binary.raw.RawBinaryAnimation;
import com.sandymandy.strut.loading.binary.raw.RawBinaryAnimation.RawBezier;
import com.sandymandy.strut.loading.binary.raw.RawBinaryAnimation.RawChannel;
import com.sandymandy.strut.loading.binary.raw.RawBinaryAnimation.RawClip;
import com.sandymandy.strut.loading.binary.raw.RawBinaryAnimation.RawEvent;
import com.sandymandy.strut.loading.binary.raw.RawBinaryAnimation.RawKey;
import com.sandymandy.strut.loading.binary.raw.RawBinaryAnimation.RawValue;
import com.sandymandy.strut.loading.json.typeadapter.BakedAnimationsAdapter;
import com.sandymandy.strut.loading.math.MathParser;
import com.sandymandy.strut.loading.math.MathValue;
import com.sandymandy.strut.loading.math.value.Constant;
import com.sandymandy.strut.loading.math.value.Negative;
import com.sandymandy.strut.loading.object.BakedAnimations;
import net.minecraft.util.math.MathHelper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns a parsed {@link RawBinaryAnimation} into a {@link BakedAnimations}, ready for
 * {@link com.sandymandy.strut.animation.AnimationController} to play
 * <p>
 * Mirrors {@link BakedAnimationsAdapter}'s segment semantics deliberately and exactly, down to
 * the same off-by-one on which keyframe's easing lands on which segment: the two asset formats
 * must animate identically from equivalent authored data, or a mod mixing {@code .animation.json}
 * and {@code .strutanim} in one pack gets a rig that moves differently depending on which format
 * happened to export a given clip
 *
 * <h2>Rotation is not mirrored here</h2>
 *
 * <p>Same correction as {@link BinaryModelBaker}: this baker used to convert a literal rotation
 * component as {@code (x, -y, -z)}, mirroring it to match that class's own (equally wrong) rest
 * rotation. Both are gone. A {@code .strutanim} stores Blockbench's live authoring values, and
 * render space <b>is</b> authoring space for the binary path - only the JSON path needs a mirror,
 * and only because Blockbench's Bedrock exporter applied one first. See
 * {@link BinaryModelBaker}'s own javadoc for the measurement that settled it.
 *
 * <p>So a literal rotation component converts from degrees to radians and nothing else. Position
 * and scale are untouched at bake either way.
 *
 * <h2>An inherited quirk, kept deliberately</h2>
 *
 * <p>Rotation units convert only for a literal component; a Molang expression component is
 * copied through unconverted, because the controller converts it (degrees to radians, and the sign
 * of X and Y) each frame when it is not a constant. An expression that is a constant (it reads no
 * query: {@code 12.5-9}) is folded to a {@link Constant} and would reach the controller looking like
 * a literal, so it is converted here instead, to the same value the controller would have made of it.
 */
public final class BinaryAnimationBaker {
	private BinaryAnimationBaker() {}

	/**
	 * How far before a "post" keyframe's own time its "pre" (incoming) value is placed
	 * <p>
	 * Matches {@code BakedAnimationsAdapter}'s Bedrock pre/post split exactly - see
	 * {@link #flattenKeys}
	 */
	private static final float PRE_POST_EPSILON = 0.001f;

	/**
	 * Bake a parsed animation file
	 *
	 * @param animation The parsed animation
	 * @param source The file's name or path, quoted in any error
	 */
	public static BakedAnimations bake(RawBinaryAnimation animation, String source) {
		Map<String, Animation> baked = new Object2ObjectLinkedOpenHashMap<>(animation.clips().size());

		for (RawClip clip : animation.clips()) {
			try {
				// A fingerprint of 0 means the file names no skeleton (the converter writes 0 for a file shared by several models), so nothing is checked
				baked.put(clip.name(), bakeClip(clip, animation.skeletonFingerprint() == 0 ? null : animation.skeletonFingerprint(), source));
			}
			catch (Exception ex) {
				StrutConstants.LOGGER.error("\"" + source + "\": unable to bake clip \"" + clip.name() + "\"", ex);
			}
		}

		return new BakedAnimations(baked);
	}

	private static Animation bakeClip(RawClip clip, @Nullable Integer skeletonFingerprint, String source) {
		BoneAnimation[] boneAnimations = bakeBoneAnimations(clip, source);
		Animation.Keyframes keyframes = bakeEvents(clip, source);

		return new Animation(clip.name(), clip.duration() * 20d, loopTypeOf(clip.loop()), boneAnimations, keyframes, skeletonFingerprint);
	}

	// ===============================================================
	// Bone channels
	// ===============================================================

	/**
	 * Group this clip's channels by the bone (and kind) they target, then bake one
	 * {@link BoneAnimation} per group - a channel is one property of one bone, but
	 * {@code BoneAnimation} carries all three
	 */
	private static BoneAnimation[] bakeBoneAnimations(RawClip clip, String source) {
		Map<BoneKey, List<RawChannel>> byBone = new Object2ObjectLinkedOpenHashMap<>();

		for (RawChannel channel : clip.channels()) {
			byBone.computeIfAbsent(new BoneKey(channel.targetKind(), channel.target()), key -> new ObjectArrayList<>())
					.add(channel);
		}

		BoneAnimation[] boneAnimations = new BoneAnimation[byBone.size()];
		int index = 0;

		for (Map.Entry<BoneKey, List<RawChannel>> entry : byBone.entrySet()) {
			BoneKey bone = entry.getKey();
			List<RawChannel> channels = entry.getValue();

			String where = "\"" + source + "\": clip \"" + clip.name() + "\" bone \"" + bone.name() + "\"";

			KeyframeStack<Keyframe<MathValue>> rotation = buildKeyframeStack(channelFor(channels, Property.ROTATION, bone, source), Property.ROTATION, where);
			KeyframeStack<Keyframe<MathValue>> position = buildKeyframeStack(channelFor(channels, Property.POSITION, bone, source), Property.POSITION, where);
			KeyframeStack<Keyframe<MathValue>> scale = buildKeyframeStack(channelFor(channels, Property.SCALE, bone, source), Property.SCALE, where);

			boneAnimations[index++] = new BoneAnimation(bone.name(), bone.kind(), rotation, position, scale);
		}

		return boneAnimations;
	}

	/**
	 * The first channel in {@code channels} driving {@code property}, warning once if more than
	 * one does - two channels for the same bone and property is an exporter bug, not a
	 * legitimate layering (the format has no notion of additive channels), so the extra ones are
	 * dropped rather than silently blended
	 */
	@Nullable
	private static RawChannel channelFor(List<RawChannel> channels, Property property, BoneKey bone, String source) {
		RawChannel found = null;

		for (RawChannel channel : channels) {
			if (channel.property() != property)
				continue;

			if (found == null) {
				found = channel;
			}
			else {
				StrutConstants.LOGGER.warn("\"{}\": bone \"{}\" ({}) has more than one {} channel; only the first is used",
						source, bone.name(), bone.kind(), property);
			}
		}

		return found;
	}

	/**
	 * Bake one property's keyframes into a {@link KeyframeStack}, mirroring
	 * {@code BakedAnimationsAdapter.buildKeyframeStack} exactly
	 *
	 * @param channel The channel driving this property, or null if the bone has none - an empty
	 *                stack either way
	 * @param property Which property this channel drives - each converts its components
	 *                 differently (see {@link #componentValue})
	 * @param where Which clip and bone this is, for a warning about one of its expressions
	 */
	private static KeyframeStack<Keyframe<MathValue>> buildKeyframeStack(@Nullable RawChannel channel, Property property, String where) {
		if (channel == null || channel.keys().isEmpty())
			return new KeyframeStack<>();

		List<FlatKey> flat = flattenKeys(channel.keys());
		List<Keyframe<MathValue>> xFrames = new ObjectArrayList<>();
		List<Keyframe<MathValue>> yFrames = new ObjectArrayList<>();
		List<Keyframe<MathValue>> zFrames = new ObjectArrayList<>();

		MathValue xPrev = null;
		MathValue yPrev = null;
		MathValue zPrev = null;
		float prevTime = 0;
		@Nullable RawKey prevEasingSource = null;
		boolean first = true;

		for (FlatKey entry : flat) {
			// Measured from 0 for the first key too, like the JSON path: a channel whose first key is
			// at 0.25s holds that value for 0.25s rather than starting its motion 0.25s early
			float timeDelta = entry.time() - prevTime;
			float segmentTicks = timeDelta * 20;

			MathValue xValue = componentValue(entry.value(), 0, property, where, entry.time());
			MathValue yValue = componentValue(entry.value(), 1, property, where, entry.time());
			MathValue zValue = componentValue(entry.value(), 2, property, where, entry.time());

			xFrames.add(new Keyframe<>(segmentTicks, first ? xValue : xPrev, xValue, entry.easing(), easingArgsOf(entry, prevEasingSource, 0, property, segmentTicks)));
			yFrames.add(new Keyframe<>(segmentTicks, first ? yValue : yPrev, yValue, entry.easing(), easingArgsOf(entry, prevEasingSource, 1, property, segmentTicks)));
			zFrames.add(new Keyframe<>(segmentTicks, first ? zValue : zPrev, zValue, entry.easing(), easingArgsOf(entry, prevEasingSource, 2, property, segmentTicks)));

			xPrev = xValue;
			yPrev = yValue;
			zPrev = zValue;
			prevTime = entry.time();
			prevEasingSource = entry.easingSource();
			first = false;
		}

		return new KeyframeStack<>(addSplineArgs(xFrames), addSplineArgs(yFrames), addSplineArgs(zFrames));
	}

	/**
	 * One flattened, time-ordered entry a {@link KeyframeStack}'s segments are built from - the
	 * unit {@link RawKey} expands into once a "post" value is involved
	 *
	 * @param easingSource The key whose {@link RawKey#easingArgs()} or {@link RawKey#bezier()}
	 *                      apply here, or null for a flattened pre-entry, which carries no easing
	 *                      of its own (see {@link #flattenKeys})
	 */
	private record FlatKey(float time, RawValue value, EasingType easing, @Nullable RawKey easingSource) {}

	/**
	 * Splits every {@link RawKey} carrying a "post" value into two flat entries, exactly as
	 * {@code BakedAnimationsAdapter}'s Bedrock {@code pre}/{@code post} handling does: the
	 * incoming ({@link RawKey#value()}) value lands just before the keyframe's own time (or at
	 * it, if the keyframe is at time zero), carrying no easing of its own (the segment arriving
	 * there was already shaped by whatever easing the previous keyframe declared); the outgoing
	 * ({@link RawKey#post()}) value lands exactly at the keyframe's time and carries this
	 * keyframe's own easing - which is therefore applied over the near-instant segment between
	 * the two, not the segment leading up to them. This looks like an odd place to put an easing
	 * curve, and it is, but it is exactly what the JSON path already does, and the two formats
	 * must agree
	 */
	private static List<FlatKey> flattenKeys(List<RawKey> keys) {
		List<FlatKey> flat = new ObjectArrayList<>(keys.size());

		for (RawKey key : keys) {
			EasingType easing = easingOf(key);

			if (key.post() != null) {
				float preTime = key.time() == 0f ? 0f : key.time() - PRE_POST_EPSILON;

				flat.add(new FlatKey(preTime, key.value(), EasingType.LINEAR, null));
				flat.add(new FlatKey(key.time(), key.post(), easing, key));
			}
			else {
				flat.add(new FlatKey(key.time(), key.value(), easing, key));
			}
		}

		return flat;
	}

	/**
	 * A keyframe's declared easing takes priority; otherwise the channel's coarse
	 * {@link Interpolation} supplies a default - {@link Interpolation#BEZIER} maps onto
	 * {@link EasingType#BEZIER}, reading its four per-axis handle components via
	 * {@link #easingArgsOf}
	 */
	private static EasingType easingOf(RawKey key) {
		if (key.easing() != null)
			return EasingType.fromString(key.easing().toLowerCase(Locale.ROOT));

		return switch (key.interpolation()) {
			case STEP -> EasingType.STEP;
			case LINEAR -> EasingType.LINEAR;
			case CATMULLROM -> EasingType.CATMULLROM;
			case BEZIER -> EasingType.BEZIER;
		};
	}

	/**
	 * The easing arguments for one axis of {@code entry}'s segment
	 * <p>
	 * A bezier keyframe (one with no explicit named easing, interpolated as
	 * {@link Interpolation#BEZIER}) needs a different value per axis - its four handle components
	 * are themselves per-axis ({@link RawKey#bezier()} is a {@code Vector3f} quartet). Every other
	 * easing's arguments (a named easing's {@link RawKey#easingArgs()}) are axis-independent, the
	 * same three floats shared by X, Y and Z
	 * <p>
	 * The bezier case pulls from <b>two</b> keys, matching Blockbench's own pairing (see
	 * {@link EasingType.BezierEasing}'s javadoc): {@code entry}'s own left handle, and
	 * {@code previous}'s own right handle - {@code previous} being the flattened key immediately
	 * before {@code entry} in this property's own key list, or null at the first key, where no
	 * previous handle exists and the missing right handle defaults to zero exactly as an absent
	 * {@code bezier_right_time}/{@code bezier_right_value} does in Blockbench's own {@code || 0}
	 * fallback. Both handles' time components convert seconds to ticks; a rotation channel's value
	 * components also convert degrees to radians the same way {@link #componentValue} does, since
	 * a handle is a delta in the same unit space as the value it offsets. Both time
	 * components are clamped to {@code segmentTicks} here, at bake time, since Blockbench's own
	 * clamp bound (the segment's length) never changes after authoring
	 *
	 * @param axis 0 for X, 1 for Y, 2 for Z
	 * @param segmentTicks This entry's own segment length, in ticks - the bound both handles'
	 *                      time components clamp against
	 */
	private static List<MathValue> easingArgsOf(FlatKey entry, @Nullable RawKey previous, int axis, Property property, float segmentTicks) {
		RawKey key = entry.easingSource();

		if (key == null)
			return List.of();

		if (key.easing() == null && key.interpolation() == Interpolation.BEZIER && key.bezier() != null) {
			RawBezier endHandle = key.bezier();
			RawBezier startHandle = previous != null ? previous.bezier() : null;

			float startHandleTime = startHandle != null
					? MathHelper.clamp(axisOf(startHandle.rightTime(), axis) * 20f, 0f, segmentTicks)
					: 0f;
			float startHandleValue = startHandle != null
					? handleValueComponent(axisOf(startHandle.rightValue(), axis), axis, property)
					: 0f;
			float endHandleTime = MathHelper.clamp(axisOf(endHandle.leftTime(), axis) * 20f, -segmentTicks, 0f);
			float endHandleValue = handleValueComponent(axisOf(endHandle.leftValue(), axis), axis, property);

			return List.of(
					new Constant(startHandleTime),
					new Constant(startHandleValue),
					new Constant(endHandleTime),
					new Constant(endHandleValue));
		}

		if (key.easingArgs().length == 0)
			return List.of();

		List<MathValue> args = new ObjectArrayList<>(key.easingArgs().length);

		for (float arg : key.easingArgs()) {
			args.add(new Constant(arg));
		}

		return args;
	}

	private static float axisOf(Vector3f vector, int axis) {
		return switch (axis) {
			case 0 -> vector.x();
			case 1 -> vector.y();
			default -> vector.z();
		};
	}

	/**
	 * A bezier handle's value component, converted the same way {@link #componentValue} converts a
	 * literal - a handle is a delta in the same unit space as the value it offsets from, and the
	 * degree-to-radian scale is linear, so the same conversion applies to a delta unchanged
	 */
	private static float handleValueComponent(float raw, int axis, Property property) {
		return switch (property) {
			case ROTATION -> (float)Math.toRadians(raw);
			case POSITION -> axis == 0 ? -raw : raw;
			case SCALE -> raw;
		};
	}

	/**
	 * One component of a keyframe's value, converted and compressed
	 * <p>
	 * Rotation converts degrees to radians and nothing else - no axis is mirrored, per this
	 * class's own javadoc. Scale passes through untouched.
	 * <p>
	 * Position negates X, and that one <b>is</b> a mirror - but not this format's. The renderer's
	 * {@code RenderUtil.translateMatrixToBone} negates a bone's animated X offset for every model,
	 * whatever format it came from, because a Bedrock {@code .animation.json} stores that offset
	 * already negated and the two cancel. A {@code .strutanim} stores the authoring value, so
	 * without this the renderer's negation would have nothing to cancel and every animated sideways
	 * movement would run the wrong way - the one place the binary path has to meet the JSON path's
	 * convention rather than its own.
	 * <p>
	 * A Molang expression is unit-converted nowhere (matching {@code BakedAnimationsAdapter} - see
	 * this class's own javadoc on that inherited quirk), but a position X expression still has to
	 * be negated, since that is a coordinate-space correction rather than a unit one.
	 *
	 * <p>
	 * An expression that does not compile does not fail the clip: that component becomes 0 and a warning names the clip, bone,
	 * property, axis and time, so one bad keyframe cannot cost the whole animation
	 *
	 * @param axis 0 for X, 1 for Y, 2 for Z
	 * @param where The clip and bone, for the warning
	 * @param time The keyframe's time in seconds, for the warning
	 */
	private static MathValue componentValue(RawValue value, int axis, Property property, String where, float time) {
		boolean negateX = property == Property.POSITION && axis == 0;

		if (value.expressions()[axis] != null) {
			MathValue expression;

			try {
				expression = MathParser.compileMolang(value.expressions()[axis]);
			}
			catch (RuntimeException ex) {
				// One keyframe with a component that is not valid Molang must not throw away the whole clip. It becomes 0, and the
				// warning says exactly where it is so the animation can be fixed in Blockbench
				EXPRESSION_LOGGER.warn("{} {} {} at {}s: cannot compile the Molang expression '{}' ({}); using 0 instead", where,
						property.name().toLowerCase(), "xyz".charAt(axis), time, value.expressions()[axis], describe(ex));

				return compress(new Constant(0));
			}

			// An expression that reads no query (12.5 - 9) is a number, and gets folded into a Constant by compress below, which the controller then reads as a value
			// that is already in radians. It was written in degrees like every rotation expression, so it is converted here, the way the controller converts one that
			// does change (and with the sign it gives X and Y), or a jowl turned 3.5 degrees turns 3.5 radians
			if (property == Property.ROTATION && !expression.isMutable())
				return compress(new Constant(Math.toRadians(expression.get()) * (axis < 2 ? -1 : 1)));

			return compress(negateX ? new Negative(expression) : expression);
		}

		float literal = value.literals()[axis];

		return switch (property) {
			case ROTATION -> compress(new Constant(Math.toRadians(literal)));
			case POSITION -> compress(new Constant(negateX ? -literal : literal));
			case SCALE -> compress(new Constant(literal));
		};
	}

	// Not StrutConstants.LOGGER: this class must stay usable without the mod loader, for tests
	private static final Logger EXPRESSION_LOGGER = LogManager.getLogger("Strut");

	private static String describe(RuntimeException ex) {
		return ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
	}

	/**
	 * Back-fills a catmull-rom keyframe's {@code easingArgs} with its neighbours' end values, and
	 * demotes a lone non-linear keyframe to linear - both verbatim from
	 * {@code BakedAnimationsAdapter.addSplineArgs}. Skipping either makes catmull-rom silently
	 * degrade to a plain lerp
	 */
	private static List<Keyframe<MathValue>> addSplineArgs(List<Keyframe<MathValue>> frames) {
		if (frames.size() == 1) {
			Keyframe<MathValue> frame = frames.getFirst();

			if (frame.easingType() != EasingType.LINEAR) {
				frames.set(0, new Keyframe<>(frame.length(), frame.startValue(), frame.endValue()));

				return frames;
			}
		}

		for (int i = 0; i < frames.size(); i++) {
			Keyframe<MathValue> frame = frames.get(i);

			if (frame.easingType() == EasingType.CATMULLROM) {
				frames.set(i, new Keyframe<>(frame.length(), frame.startValue(), frame.endValue(), frame.easingType(), ObjectArrayList.of(
						i < 2 ? frame.startValue() : frames.get(i - 2).endValue(),
						i + 1 >= frames.size() ? frame.endValue() : frames.get(i + 1).endValue()
				)));
			}
		}

		return frames;
	}

	/**
	 * Shares {@code BakedAnimationsAdapter}'s compression cache rather than keeping a separate
	 * one, so a literal repeated across both a {@code .animation.json} and a {@code .strutanim}
	 * loaded in the same reload compresses to the one {@link Constant}. Absent (null) outside a
	 * reload pass, in which case values are left uncompressed
	 */
	private static MathValue compress(MathValue input) {
		if (BakedAnimationsAdapter.COMPRESSION_CACHE == null || input.isMutable())
			return input;

		return BakedAnimationsAdapter.COMPRESSION_CACHE.computeIfAbsent(input.get(), Constant::new);
	}

	private static Animation.LoopType loopTypeOf(LoopMode mode) {
		return switch (mode) {
			case PLAY_ONCE -> Animation.LoopType.PLAY_ONCE;
			case LOOP -> Animation.LoopType.LOOP;
			case HOLD_ON_LAST_FRAME -> Animation.LoopType.HOLD_ON_LAST_FRAME;
		};
	}

	// ===============================================================
	// Events
	// ===============================================================

	/**
	 * Bake this clip's events into sound, particle and custom-instruction keyframe data
	 * <p>
	 * {@link RawEvent#authority()} is parsed and dropped - there is no authority concept yet in
	 * {@link KeyFrameData}, and no consumer besides the typed events
	 */
	private static Animation.Keyframes bakeEvents(RawClip clip, String source) {
		List<SoundKeyframeData> sounds = new ObjectArrayList<>();
		List<ParticleKeyframeData> particles = new ObjectArrayList<>();
		List<CustomInstructionKeyframeData> instructions = new ObjectArrayList<>();

		for (RawEvent event : clip.events()) {
			double startTick = event.time() * 20d;

			switch (event.kind()) {
				case StrutBinaryFormats.EVENT_KIND_SOUND -> sounds.add(new SoundKeyframeData(startTick, event.payload()));
				case StrutBinaryFormats.EVENT_KIND_PARTICLE -> particles.add(particleOf(startTick, event.payload()));
				case StrutBinaryFormats.EVENT_KIND_INSTRUCTION -> instructions.add(new CustomInstructionKeyframeData(startTick, event.payload()));
				default -> StrutConstants.LOGGER.warn("\"{}\": clip \"{}\" has an event of unrecognised kind \"{}\"; skipped",
						source, clip.name(), event.kind());
			}
		}

		return new Animation.Keyframes(sounds.toArray(new SoundKeyframeData[0]), particles.toArray(new ParticleKeyframeData[0]),
				instructions.toArray(new CustomInstructionKeyframeData[0]));
	}

	/**
	 * Splits a particle event's {@code effect|locator|script} payload on
	 * {@link StrutBinaryFormats#EVENT_FIELD_SEPARATOR} - see that field's javadoc for why that
	 * separator and not a printable one
	 */
	private static ParticleKeyframeData particleOf(double startTick, String payload) {
		String[] fields = payload.split(StrutBinaryFormats.EVENT_FIELD_SEPARATOR, -1);
		String effect = fields.length > 0 ? fields[0] : "";
		String locator = fields.length > 1 ? fields[1] : "";
		String script = fields.length > 2 ? fields[2] : "";

		return new ParticleKeyframeData(startTick, effect, locator, script);
	}
}
