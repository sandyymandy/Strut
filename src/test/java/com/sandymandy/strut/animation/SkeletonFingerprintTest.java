package com.sandymandy.strut.animation;

import com.sandymandy.strut.animation.keyframe.BoneAnimation;
import com.sandymandy.strut.animation.keyframe.event.data.CustomInstructionKeyframeData;
import com.sandymandy.strut.animation.keyframe.event.data.ParticleKeyframeData;
import com.sandymandy.strut.animation.keyframe.event.data.SoundKeyframeData;
import net.minecraft.util.Identifier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import com.sandymandy.strut.animatable.StrutAnimatable;
import com.sandymandy.strut.animatable.instance.AnimatableInstanceCache;
import com.sandymandy.strut.animatable.instance.InstancedAnimatableInstanceCache;
import com.sandymandy.strut.cache.object.BakedStrutModel;
import com.sandymandy.strut.loading.binary.BinaryAnimationBaker;
import com.sandymandy.strut.loading.binary.BinaryAnimationReader;
import com.sandymandy.strut.loading.binary.BinaryFixtures;
import com.sandymandy.strut.loading.binary.BinaryModelBaker;
import com.sandymandy.strut.loading.binary.BinaryModelReader;
import com.sandymandy.strut.loading.binary.raw.RawBinaryAnimation;
import com.sandymandy.strut.loading.object.BakedAnimations;
import com.sandymandy.strut.model.StrutModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The skeleton fingerprint gate: an animation authored against one skeleton,
 * bound to a model with a different one, warns exactly once naming both fingerprints - never a
 * failure, since a clip legitimately driving a subset of a rig would otherwise trip it too
 */
class SkeletonFingerprintTest {
	private final List<String> capturedMessages = new CopyOnWriteArrayList<>();
	private Appender appender;
	private Logger strutLogger;

	@BeforeEach
	void captureStrutLog() {
		this.strutLogger = (Logger)LogManager.getLogger("Strut");
		this.appender = new AbstractAppender("test-capture", null, null, false, null) {
			@Override
			public void append(LogEvent event) {
				capturedMessages.add(event.getMessage().getFormattedMessage());
			}
		};

		this.appender.start();
		this.strutLogger.addAppender(this.appender);
	}

	@AfterEach
	void stopCapturing() {
		this.strutLogger.removeAppender(this.appender);
		this.appender.stop();
	}

	@Test
	@DisplayName("a mismatched fingerprint warns exactly once, naming both fingerprints, however many times the clip plays")
	void mismatchedFingerprintWarnsOnce() {
		BakedStrutModel model = BinaryModelBaker.bake(
				BinaryModelReader.read(BinaryFixtures.riggedModel(), "rig.strutmodel"), "rig.strutmodel");
		RawBinaryAnimation rawAnimation = BinaryAnimationReader.read(BinaryFixtures.animation(), "test.strutanim");
		BakedAnimations animations = BinaryAnimationBaker.bake(rawAnimation, "test.strutanim");
		Animation animation = animations.getAnimation("walk");

		assertNotNull(animation);
		assertNotEquals(model.skeletonFingerprint(), animation.skeletonFingerprint(),
				"the fixture's animation carries a fixed placeholder fingerprint (0xDEADBEEF) that a real model's computed fingerprint essentially never matches");

		FakeAnimatable animatable = new FakeAnimatable();
		FakeModel strutModel = new FakeModel(animation);

		strutModel.getAnimationProcessor().setActiveModel(model);

		RawAnimation rawAnim = RawAnimation.begin().thenLoop("walk");

		Queue<AnimationProcessor.QueuedAnimation> first = strutModel.getAnimationProcessor().buildAnimationQueue(animatable, rawAnim);
		Queue<AnimationProcessor.QueuedAnimation> second = strutModel.getAnimationProcessor().buildAnimationQueue(animatable, rawAnim);

		assertEquals(1, first.size(), "the mismatch is a warning, not a refusal - the animation must still queue");
		assertEquals(1, second.size());

		List<String> mismatchWarnings = this.capturedMessages.stream()
				.filter(message -> message.contains("different skeleton"))
				.toList();

		assertEquals(1, mismatchWarnings.size(),
				"exactly one warning across two calls - the second must be suppressed as a duplicate, not because nothing happened");

		String warning = mismatchWarnings.getFirst();

		assertTrue(warning.contains(RawBinaryAnimation.fingerprintText(animation.skeletonFingerprint())),
				"the warning must name the animation's own fingerprint: " + warning);
		assertTrue(warning.contains(RawBinaryAnimation.fingerprintText(model.skeletonFingerprint())),
				"the warning must name the model's fingerprint: " + warning);
	}

	@Test
	@DisplayName("a matching fingerprint never warns")
	void matchingFingerprintDoesNotWarn() {
		BakedStrutModel model = BinaryModelBaker.bake(
				BinaryModelReader.read(BinaryFixtures.riggedModel(), "rig.strutmodel"), "rig.strutmodel");
		Animation animation = new Animation("walk", 20, Animation.LoopType.LOOP, new BoneAnimation[0],
				new Animation.Keyframes(new SoundKeyframeData[0],
						new ParticleKeyframeData[0],
						new CustomInstructionKeyframeData[0]),
				model.skeletonFingerprint());

		FakeAnimatable animatable = new FakeAnimatable();
		FakeModel strutModel = new FakeModel(animation);

		strutModel.getAnimationProcessor().setActiveModel(model);
		strutModel.getAnimationProcessor().buildAnimationQueue(animatable, RawAnimation.begin().thenLoop("walk"));

		assertTrue(this.capturedMessages.stream().noneMatch(message -> message.contains("different skeleton")),
				"a matching fingerprint must never produce a mismatch warning");
	}

	private static final class FakeAnimatable implements StrutAnimatable {
		private final AnimatableInstanceCache cache = new InstancedAnimatableInstanceCache(this);

		@Override
		public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {}

		@Override
		public AnimatableInstanceCache getAnimatableInstanceCache() {
			return this.cache;
		}

		@Override
		public double getTick(Object object) {
			return 0;
		}
	}

	private static final class FakeModel extends StrutModel<FakeAnimatable> {
		private static final Identifier DUMMY = Identifier.of("strut", "dummy");

		private final Animation animation;

		private FakeModel(Animation animation) {
			this.animation = animation;
		}

		@Override
		public Identifier getModelResource(FakeAnimatable animatable) {
			return DUMMY;
		}

		@Override
		public Identifier getTextureResource(FakeAnimatable animatable) {
			return DUMMY;
		}

		@Override
		public Identifier getAnimationResource(FakeAnimatable animatable) {
			return DUMMY;
		}

		@Override
		public Animation getAnimation(FakeAnimatable animatable, String name) {
			return name.equals(this.animation.name()) ? this.animation : null;
		}
	}
}
