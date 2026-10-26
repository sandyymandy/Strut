package com.sandymandy.strut.animation;

import com.sandymandy.strut.animatable.StrutAnimatable;
import com.sandymandy.strut.animatable.instance.AnimatableInstanceCache;
import com.sandymandy.strut.animatable.instance.InstancedAnimatableInstanceCache;
import com.sandymandy.strut.animation.keyframe.BoneAnimation;
import com.sandymandy.strut.animation.keyframe.Keyframe;
import com.sandymandy.strut.animation.keyframe.KeyframeStack;
import com.sandymandy.strut.cache.object.BakedStrutModel;
import com.sandymandy.strut.cache.object.GroupBoneHandle;
import com.sandymandy.strut.animation.keyframe.event.data.CustomInstructionKeyframeData;
import com.sandymandy.strut.animation.keyframe.event.data.ParticleKeyframeData;
import com.sandymandy.strut.animation.keyframe.event.data.SoundKeyframeData;
import com.sandymandy.strut.loading.binary.BinaryFixtures;
import com.sandymandy.strut.loading.binary.BinaryModelBaker;
import com.sandymandy.strut.loading.binary.BinaryModelReader;
import com.sandymandy.strut.loading.math.MathValue;
import com.sandymandy.strut.loading.math.value.Constant;
import com.sandymandy.strut.model.StrutModel;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A chained animation (a clip played once, then another looped: a bow's charge then its hold) goes from the first into the second without the bone falling
 * back to its base pose between them
 */
class ChainedStageBlendTest {
	private static final double HOLD_DEGREES = 90;

	@Test
	void theBoneStaysUpFromTheEndOfTheChargeIntoTheHold() {
		BakedStrutModel baked = BinaryModelBaker.bake(BinaryModelReader.read(BinaryFixtures.riggedModel(), "rig.strutmodel"), "rig.strutmodel");
		Fake animatable = new Fake();
		FakeModel model = new FakeModel();

		model.getAnimationProcessor().setActiveModel(baked);

		AnimatableManager<Fake> manager = new AnimatableManager<>(animatable);
		List<Double> trace = new ArrayList<>();

		for (int frame = 0; frame <= 60; frame++) {
			double time = frame * 0.5;

			model.getAnimationProcessor().tickAnimation(animatable, model, manager, time, new AnimationState<>(animatable, 0, 0, 0, false), false);

			GroupBoneHandle root = null;

			for (GroupBoneHandle handle : manager.getModelInstance().handlesFor(baked))
				if (handle.getName().equals("root"))
					root = handle;

			trace.add(Math.toDegrees(root.getRotX()));
		}

		// the charge reaches 90 at frame 30 (after a five tick blend in and ten ticks of charge); from there on the bone may not dip below what the charge reached
		StringBuilder all = new StringBuilder();

		for (int i = 0; i < trace.size(); i++)
			all.append(i).append(':').append(String.format("%.1f", trace.get(i))).append(' ');

		for (int i = 30; i < trace.size(); i++)
			assertTrue(trace.get(i) > HOLD_DEGREES - 5, "dipped at frame " + i + ": " + all);
	}

	private static Animation clip(String name, double length, double from, double to) {
		KeyframeStack<Keyframe<MathValue>> rotation = new KeyframeStack<>();

		rotation.xKeyframes().add(new Keyframe<>(length, new Constant(Math.toRadians(from)), new Constant(Math.toRadians(to))));
		rotation.yKeyframes().add(new Keyframe<>(length, new Constant(0), new Constant(0)));
		rotation.zKeyframes().add(new Keyframe<>(length, new Constant(0), new Constant(0)));

		BoneAnimation bone = new BoneAnimation("root", null, rotation, new KeyframeStack<>(), new KeyframeStack<>());

		return new Animation(name, length, Animation.LoopType.PLAY_ONCE, new BoneAnimation[] {bone},
				new Animation.Keyframes(new SoundKeyframeData[0], new ParticleKeyframeData[0], new CustomInstructionKeyframeData[0]));
	}

	private static final class Fake implements StrutAnimatable {
		private final AnimatableInstanceCache cache = new InstancedAnimatableInstanceCache(this);

		@Override
		public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
			controllers.add(new AnimationController<>(this, "use", 5,
					state -> state.setAndContinue(RawAnimation.begin().then("charge", Animation.LoopType.PLAY_ONCE).thenLoop("hold"))));
		}

		@Override
		public AnimatableInstanceCache getAnimatableInstanceCache() {
			return this.cache;
		}

		@Override
		public double getTick(Object object) {
			return 0;
		}
	}

	private static final class FakeModel extends StrutModel<Fake> {
		private static final Identifier DUMMY = Identifier.of("strut", "dummy");
		private final Animation charge = clip("charge", 10, 0, HOLD_DEGREES);
		private final Animation hold = clip("hold", 20, HOLD_DEGREES, HOLD_DEGREES);

		@Override
		public Animation getAnimation(Fake animatable, String name) {
			return name.equals("charge") ? this.charge : name.equals("hold") ? this.hold : null;
		}

		@Override
		public Identifier getModelResource(Fake animatable) {
			return DUMMY;
		}

		@Override
		public Identifier getTextureResource(Fake animatable) {
			return DUMMY;
		}

		@Override
		public Identifier getAnimationResource(Fake animatable) {
			return DUMMY;
		}
	}
}
