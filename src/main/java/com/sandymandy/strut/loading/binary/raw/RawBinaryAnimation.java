package com.sandymandy.strut.loading.binary.raw;

import com.sandymandy.strut.loading.binary.StrutBinaryFormats.Interpolation;
import com.sandymandy.strut.loading.binary.StrutBinaryFormats.LoopMode;
import com.sandymandy.strut.loading.binary.StrutBinaryFormats.Property;
import com.sandymandy.strut.loading.binary.StrutBinaryFormats.TargetKind;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.List;

/**
 * A parsed {@code .strutanim}, one record per thing the file says, and nothing resolved
 * <p>
 * A channel's target is still a name, angles are still degrees, positions are still model
 * units and scale is still an absolute factor. Binding a channel to a bone and ordering
 * keyframes is the bake's work
 *
 * @param skeletonFingerprint A hash of the bone names the clips were authored against.
 *                            Cheap, and it turns "the animation silently does nothing" into
 *                            a load-time warning naming the mismatch
 */
public record RawBinaryAnimation(int version, int flags, int skeletonFingerprint,
								 List<String> strings, List<RawClip> clips) {
	/**
	 * Render a fingerprint the way an exporter logs it, so the two can be compared by eye
	 * when a clip is refused
	 */
	public static String fingerprintText(int fingerprint) {
		return "0x" + String.format("%08x", fingerprint);
	}

	/**
	 * One animation clip
	 *
	 * @param duration The clip's length, in seconds
	 */
	public record RawClip(String name, float duration, LoopMode loop, List<RawChannel> channels,
						  List<RawEvent> events) {}

	/**
	 * One property of one target, over time
	 *
	 * @param target The name of the bone this drives, unresolved
	 * @param targetKind Whether {@code target} names a group bone or an armature bone
	 * @param interpolation The mode shared by every keyframe, or null if they differ, in
	 *                      which case each keyframe's own mode is authoritative
	 */
	public record RawChannel(String target, TargetKind targetKind, Property property,
							 @Nullable Interpolation interpolation, List<RawKey> keys) {}

	/**
	 * One keyframe
	 *
	 * @param post The outgoing value, where a keyframe holds a different value on each side
	 *             of its own time, or null where it does not
	 * @param easing The name of the easing applied across the segment ending at this keyframe,
	 *               or null for the channel's default. Held as a name rather than an enum so
	 *               that any easing registered at runtime - including one a third-party mod
	 *               adds - round-trips without a format version bump
	 * @param easingArgs Arguments to that easing, empty where it takes none
	 * @param bezier The four handles of a bezier segment, or null for any other mode
	 */
	public record RawKey(float time, @Nullable Interpolation interpolation, RawValue value,
						 @Nullable RawValue post, @Nullable String easing, float[] easingArgs,
						 @Nullable RawBezier bezier) {}

	/**
	 * A keyframe's three components, each either a literal or a Molang expression
	 * <p>
	 * Mixed per component rather than per keyframe because that is how animations are
	 * actually authored - a bone whose Y rotation follows a query while X and Z stay put is
	 * ordinary, and forcing the whole keyframe into one representation would mean writing
	 * two constants as expressions
	 *
	 * @param literals The literal value of each component, meaningless where an expression is present
	 * @param expressions The Molang source of each component, null where that component is a literal
	 */
	public record RawValue(float[] literals, String[] expressions) {
		/**
		 * @return Whether every component is a literal, the common case
		 */
		public boolean isLiteral() {
			return this.expressions[0] == null && this.expressions[1] == null && this.expressions[2] == null;
		}

		public static RawValue ofLiterals(float x, float y, float z) {
			return new RawValue(new float[] {x, y, z}, new String[3]);
		}

		public static RawValue ofLiterals(Vector3f value) {
			return ofLiterals(value.x, value.y, value.z);
		}
	}

	/**
	 * The handles of a bezier segment, one component per axis
	 */
	public record RawBezier(Vector3f leftTime, Vector3f leftValue, Vector3f rightTime, Vector3f rightValue) {}

	/**
	 * A timed instruction - a sound, a particle, or a custom instruction
	 *
	 * @param kind What sort of event this is
	 * @param payload The event's argument, uninterpreted
	 * @param authority Where the event should fire; see the keyframe event system
	 */
	public record RawEvent(float time, String kind, String payload, int authority) {}
}
