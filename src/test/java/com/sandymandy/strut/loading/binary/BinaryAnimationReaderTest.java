package com.sandymandy.strut.loading.binary;

import com.sandymandy.strut.loading.binary.StrutBinaryFormats.Interpolation;
import com.sandymandy.strut.loading.binary.StrutBinaryFormats.LoopMode;
import com.sandymandy.strut.loading.binary.StrutBinaryFormats.Property;
import com.sandymandy.strut.loading.binary.StrutBinaryFormats.TargetKind;
import com.sandymandy.strut.loading.binary.raw.RawBinaryAnimation;
import com.sandymandy.strut.loading.binary.raw.RawBinaryAnimation.RawChannel;
import com.sandymandy.strut.loading.binary.raw.RawBinaryAnimation.RawClip;
import com.sandymandy.strut.loading.binary.raw.RawBinaryAnimation.RawEvent;
import com.sandymandy.strut.loading.binary.raw.RawBinaryAnimation.RawKey;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static com.sandymandy.strut.loading.binary.StrutBinaryFormats.*;
import static org.junit.jupiter.api.Assertions.*;

class BinaryAnimationReaderTest {
	private static final String SOURCE = "test.strutanim";

	private static RawBinaryAnimation read(byte[] bytes) {
		return BinaryAnimationReader.read(bytes, SOURCE);
	}

	private static RawChannel onlyChannel() {
		return read(BinaryFixtures.animation()).clips().getFirst().channels().getFirst();
	}

	private static void assertRefused(byte[] bytes, String... expectedFragments) {
		BinaryFormatException thrown = assertThrows(BinaryFormatException.class, () -> read(bytes));

		assertTrue(thrown.getMessage().contains(SOURCE),
				() -> "error should name the file it came from, but was: " + thrown.getMessage());

		for (String fragment : expectedFragments) {
			assertTrue(thrown.getMessage().contains(fragment),
					() -> "error should mention \"" + fragment + "\", but was: " + thrown.getMessage());
		}
	}

	@Nested
	@DisplayName("a well-formed animation")
	class WellFormed {
		@Test
		@DisplayName("reads the clip and its skeleton fingerprint")
		void readsClip() {
			RawBinaryAnimation animation = read(BinaryFixtures.animation());

			assertEquals(ANIMATION_VERSION, animation.version());
			assertEquals(0xDEADBEEF, animation.skeletonFingerprint());
			assertEquals("0xdeadbeef", RawBinaryAnimation.fingerprintText(animation.skeletonFingerprint()));

			RawClip clip = animation.clips().getFirst();

			assertEquals("walk", clip.name());
			assertEquals(1f, clip.duration());
			assertEquals(LoopMode.LOOP, clip.loop());
			assertEquals(1, clip.channels().size());
		}

		@Test
		@DisplayName("reads a channel's target, kind and property without resolving them")
		void readsChannel() {
			RawChannel channel = onlyChannel();

			assertEquals("upper", channel.target());
			assertEquals(TargetKind.ARMATURE_BONE, channel.targetKind(),
					"the kind is what lets a group bone and an armature bone share a name");
			assertEquals(Property.ROTATION, channel.property());
			assertNull(channel.interpolation(), "a channel whose keyframes differ declares the mixed sentinel");
			assertEquals(4, channel.keys().size());
		}

		@Test
		@DisplayName("reads a keyframe carrying a separate outgoing value")
		void readsPostValue() {
			RawKey key = onlyChannel().keys().get(1);

			assertEquals(Interpolation.STEP, key.interpolation());
			assertEquals(10f, key.value().literals()[0]);
			assertNotNull(key.post());
			assertEquals(20f, key.post().literals()[0]);
		}

		@Test
		@DisplayName("reads a per-keyframe easing by name, with its arguments")
		void readsEasing() {
			RawKey key = onlyChannel().keys().get(2);

			assertEquals(Interpolation.CATMULLROM, key.interpolation());
			assertEquals("easeinoutelastic", key.easing(),
					"easings are stored by name so one registered at runtime round-trips without a format bump");
			assertArrayEquals(new float[] {1.5f}, key.easingArgs());
		}

		@Test
		@DisplayName("keyframes without an easing carry none rather than a default")
		void readsAbsentEasing() {
			RawKey key = onlyChannel().keys().getFirst();

			assertNull(key.easing());
			assertEquals(0, key.easingArgs().length);
			assertTrue(key.value().isLiteral());
		}

		@Test
		@DisplayName("reads a Molang expression in one component and literals in the others")
		void readsMixedValue() {
			RawKey key = onlyChannel().keys().get(3);

			assertFalse(key.value().isLiteral());
			assertNull(key.value().expressions()[0]);
			assertEquals("math.sin(query.anim_time * 30)", key.value().expressions()[1]);
			assertNull(key.value().expressions()[2]);
			assertEquals(40f, key.value().literals()[0], "the literal components still read normally");
		}

		@Test
		@DisplayName("reads bezier handles only for bezier keyframes")
		void readsBezier() {
			RawChannel channel = onlyChannel();

			assertNull(channel.keys().getFirst().bezier());
			assertNotNull(channel.keys().get(3).bezier());
			assertEquals(-1f, channel.keys().get(3).bezier().leftValue().x);
			assertEquals(0.1f, channel.keys().get(3).bezier().rightTime().x);
		}

		@Test
		@DisplayName("reads events")
		void readsEvents() {
			RawEvent event = read(BinaryFixtures.animation()).clips().getFirst().events().getFirst();

			assertEquals(0.5f, event.time());
			assertEquals("sound", event.kind());
			assertEquals("strut:test.step", event.payload());
		}
	}

	@Nested
	@DisplayName("a malformed animation is refused")
	class Malformed {
		@Test
		@DisplayName("when it does not open with the magic")
		void wrongMagic() {
			byte[] bytes = BinaryFixtures.animation();

			bytes[1] = 'X';

			assertRefused(bytes, ANIMATION_MAGIC, "not a Strut binary animation");
		}

		@Test
		@DisplayName("when a Strut model is loaded as an animation")
		void wrongKindOfFile() {
			assertRefused(BinaryFixtures.cubeModel(), ANIMATION_MAGIC, MODEL_MAGIC);
		}

		@Test
		@DisplayName("when it is truncated mid-keyframe")
		void truncated() {
			byte[] whole = BinaryFixtures.animation();

			assertRefused(BinaryFixtures.truncated(whole, whole.length - 6), "truncated");
		}

		@Test
		@DisplayName("when a keyframe claims the mixed-interpolation sentinel")
		void keyframeClaimsMixed() {
			assertRefused(clip(channel(Interpolation.MIXED, key -> key
							.f32(0f).u8(Interpolation.MIXED).u16(0).f32(0).f32(0).f32(0))),
					"mixed-interpolation sentinel", "a keyframe has exactly one mode");
		}

		@Test
		@DisplayName("when a channel declares one mode but a keyframe uses another")
		void channelContradictsKeyframe() {
			assertRefused(clip(channel(Interpolation.LINEAR.ordinal(), key -> key
							.f32(0f).u8(Interpolation.STEP.ordinal()).u16(0).f32(0).f32(0).f32(0))),
					"STEP", "channel declares LINEAR", "mixed sentinel");
		}

		@Test
		@DisplayName("when a property has a wire code this build does not know")
		void unknownProperty() {
			BinaryFixtures.Strings strings = new BinaryFixtures.Strings();
			int clipName = strings.intern("broken");
			int target = strings.intern("bone");

			BinaryOutput clips = new BinaryOutput()
					.u16(clipName).f32(1f).u8(LoopMode.LOOP.ordinal()).u16(1).u16(0)
					.u16(target)
					.u8(TargetKind.GROUP_BONE.ordinal())
					.u8(7)
					.u8(Interpolation.LINEAR.ordinal())
					.u16(0);

			assertRefused(BinaryFixtures.animationFile(strings, 1, 0, clips),
					"property", "wire code 7");
		}

		/**
		 * One clip, one channel, one keyframe, around a caller-built keyframe body
		 */
		private static byte[] clip(BinaryOutput channel) {
			BinaryFixtures.Strings strings = new BinaryFixtures.Strings();
			int clipName = strings.intern("broken");
			int target = strings.intern("bone");

			BinaryOutput clips = new BinaryOutput()
					.u16(clipName).f32(1f).u8(LoopMode.LOOP.ordinal()).u16(1).u16(0)
					.u16(target)
					.append(channel);

			return BinaryFixtures.animationFile(strings, 1, 0, clips);
		}

		private static BinaryOutput channel(int interpolation, Consumer<BinaryOutput> key) {
			BinaryOutput out = new BinaryOutput()
					.u8(TargetKind.GROUP_BONE.ordinal())
					.u8(Property.ROTATION.ordinal())
					.u8(interpolation)
					.u16(1);

			key.accept(out);

			return out;
		}
	}

	@Test
	@DisplayName("a file whose last key is all Molang expressions is not mistaken for a truncated one")
	void aShortAllExpressionKeyAtTheEndOfTheFileReads() {
		// 13 bytes: a time, an interpolation, flags, and three 2-byte expression indices. The reader used to assume 19 (three floats)
		BinaryFixtures.Strings strings = new BinaryFixtures.Strings();
		int clipName = strings.intern("a");
		int target = strings.intern("bone");
		int expression = strings.intern("math.sin(query.anim_time)");
		BinaryOutput clips = new BinaryOutput()
				.u16(clipName).f32(1f).u8(LoopMode.LOOP.ordinal()).u16(1).u16(0)
				.u16(target).u8(TargetKind.GROUP_BONE.ordinal()).u8(Property.POSITION.ordinal()).u8(Interpolation.LINEAR.ordinal()).u16(1)
				.f32(0f).u8(Interpolation.LINEAR.ordinal()).u16(KEY_FLAG_VALUE_EXPRESSION | (KEY_FLAG_VALUE_EXPRESSION << 1) | (KEY_FLAG_VALUE_EXPRESSION << 2))
				.u16(expression).u16(expression).u16(expression);
		RawBinaryAnimation read = BinaryAnimationReader.read(BinaryFixtures.animationFile(strings, 1, 0, clips), "short.strutanim");

		assertEquals("math.sin(query.anim_time)", read.clips().get(0).channels().get(0).keys().get(0).value().expressions()[2]);
	}
}
