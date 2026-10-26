package com.sandymandy.strut.animation;

import net.minecraft.util.Identifier;
import com.sandymandy.strut.animatable.StrutAnimatable;
import com.sandymandy.strut.animatable.instance.AnimatableInstanceCache;
import com.sandymandy.strut.animatable.instance.InstancedAnimatableInstanceCache;
import com.sandymandy.strut.animation.state.ModelInstance;
import com.sandymandy.strut.animation.state.Pose;
import com.sandymandy.strut.cache.object.BakedStrutModel;
import com.sandymandy.strut.cache.object.GroupBoneHandle;
import com.sandymandy.strut.loading.binary.BinaryFixtures;
import com.sandymandy.strut.loading.binary.BinaryModelBaker;
import com.sandymandy.strut.loading.binary.BinaryModelReader;
import com.sandymandy.strut.model.StrutModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves the wiring in {@link AnimationProcessor#tickAnimation} that
 * calls {@link ModelInstance#beginPoseUpdate} and {@link ModelInstance#interpolateRenderedPose},
 * not just the interpolation math itself, which {@code PoseInterpolationTest} covers directly
 * <p>
 * A real controller-driven animation makes this awkward to observe (the very thing under test -
 * {@code current} changing between ticks - would be buried under keyframe evaluation), so this
 * uses a {@code FakeAnimatable} with no controllers registered, exactly like
 * {@code AnimationProcessorPoseSyncTest}, and drives the change in {@code current} by writing to a
 * {@link GroupBoneHandle} directly between two {@code tickAnimation} calls - standing in for whatever
 * real source (a controller resuming, an external override) would otherwise produce it
 */
class AnimationProcessorPoseInterpolationTest {
	@Test
	@DisplayName("tickAnimation snapshots current into previous before overwriting it, then blends both into rendered by partial tick")
	void tickAnimationWiresThePoseInterpolationPipeline() {
		BakedStrutModel bakedModel = BinaryModelBaker.bake(
				BinaryModelReader.read(BinaryFixtures.riggedModel(), "rig.strutmodel"), "rig.strutmodel");

		FakeAnimatable animatable = new FakeAnimatable();
		FakeModel model = new FakeModel();

		model.getAnimationProcessor().setActiveModel(bakedModel);

		AnimatableManager<FakeAnimatable> manager = new AnimatableManager<>(animatable);

		// Settles current (and, since previous/rendered start bound equal to current, all three
		// poses) at rest - there are no controllers to animate anything
		model.getAnimationProcessor().tickAnimation(animatable, model, manager, 0,
				new AnimationState<>(animatable, 0, 0, 0, false), false);

		ModelInstance instance = manager.getModelInstance();
		GroupBoneHandle handle = instance.handlesFor(bakedModel)[0];
		int index = handle.getIndex();
		float restRotX = handle.getRotX();
		float displacedRotX = restRotX + 1.0f;

		handle.setRotX(displacedRotX);

		AnimationState<FakeAnimatable> secondTickState = new AnimationState<>(animatable, 0, 0, 0.5f, false);

		model.getAnimationProcessor().tickAnimation(animatable, model, manager, 1, secondTickState, false);

		Pose previous = instance.previousPose();
		Pose current = instance.currentPose();
		Pose rendered = instance.renderedPose();

		assertEquals(displacedRotX, previous.getRotX(index), 1e-4,
				"beginPoseUpdate must have snapshotted current from before this tick's reset-toward-rest pass overwrote it");
		assertEquals(restRotX, current.getRotX(index), 1e-4,
				"with no controllers registered, the bone must reset fully back to rest by the end of the tick");

		float expectedBlend = previous.getRotX(index) + (current.getRotX(index) - previous.getRotX(index)) * secondTickState.getPartialTick();

		assertEquals(expectedBlend, rendered.getRotX(index), 1e-2,
				"rendered must be the interpolation of previous and current by this tick's partial tick");
		assertNotEquals(previous.getRotX(index), rendered.getRotX(index), 1e-4,
				"rendered must actually be blended, not just equal to previous");
		assertNotEquals(current.getRotX(index), rendered.getRotX(index), 1e-4,
				"rendered must actually be blended, not just equal to current");
	}

	@Test
	@DisplayName("refreshRenderedPose puts a write made after tickAnimation (head look, jiggle) into the pose that draws")
	void customWriteAfterTheTickReachesTheRenderedPose() {
		BakedStrutModel bakedModel = BinaryModelBaker.bake(
				BinaryModelReader.read(BinaryFixtures.riggedModel(), "rig.strutmodel"), "rig.strutmodel");

		FakeAnimatable animatable = new FakeAnimatable();
		FakeModel model = new FakeModel();

		model.getAnimationProcessor().setActiveModel(bakedModel);

		AnimatableManager<FakeAnimatable> manager = new AnimatableManager<>(animatable);
		ModelInstance instance = manager.getModelInstance();

		model.getAnimationProcessor().tickAnimation(animatable, model, manager, 0, new AnimationState<>(animatable, 0, 0, 0, false), false);

		GroupBoneHandle handle = instance.handlesFor(bakedModel)[0];
		int index = handle.getIndex();
		AnimationState<FakeAnimatable> state = new AnimationState<>(animatable, 0, 0, 0.5f, false);

		model.getAnimationProcessor().tickAnimation(animatable, model, manager, 1, state, false);

		// What setCustomAnimations does, after the tick
		handle.setRotX(0.8f);

		model.getAnimationProcessor().refreshRenderedPose(instance, state.getPartialTick());

		// previous was snapshotted before the write, current holds it: the blend is between the two
		assertEquals(0.4f, instance.renderedPose().getRotX(index), 1e-3, "rendered must include the custom write, blended by the partial tick");

		float before = instance.renderedPose().getRotX(index);

		model.getAnimationProcessor().tickAnimation(animatable, model, manager, 2, new AnimationState<>(animatable, 0, 0, 1f, false), false);
		handle.setRotX(0.8f);
		model.getAnimationProcessor().refreshRenderedPose(instance, 1f);

		assertEquals(0.8f, instance.renderedPose().getRotX(index), 1e-3, "a steady custom value must render in full, not half");
		assertNotEquals(before, instance.renderedPose().getRotX(index), 1e-4);
	}

	@Test
	@DisplayName("resetPoseHistory collapses previous and rendered to current, discarding any pending blend")
	void resetPoseHistoryCollapsesToCurrent() {
		BakedStrutModel bakedModel = BinaryModelBaker.bake(
				BinaryModelReader.read(BinaryFixtures.riggedModel(), "rig.strutmodel"), "rig.strutmodel");

		FakeAnimatable animatable = new FakeAnimatable();
		FakeModel model = new FakeModel();

		model.getAnimationProcessor().setActiveModel(bakedModel);

		AnimatableManager<FakeAnimatable> manager = new AnimatableManager<>(animatable);

		model.getAnimationProcessor().tickAnimation(animatable, model, manager, 0,
				new AnimationState<>(animatable, 0, 0, 0, false), false);

		ModelInstance instance = manager.getModelInstance();
		GroupBoneHandle handle = instance.handlesFor(bakedModel)[0];
		int index = handle.getIndex();

		handle.setRotX(handle.getRotX() + 1.0f);

		model.getAnimationProcessor().tickAnimation(animatable, model, manager, 1,
				new AnimationState<>(animatable, 0, 0, 0.5f, false), false);

		// Before the reset: previous and current disagree, so rendered sits somewhere in between
		assertNotEquals(instance.previousPose().getRotX(index), instance.renderedPose().getRotX(index), 1e-6);

		instance.resetPoseHistory();

		assertEquals(instance.currentPose().getRotX(index), instance.previousPose().getRotX(index), 1e-6,
				"resetPoseHistory must collapse previous to current");
		assertEquals(instance.currentPose().getRotX(index), instance.renderedPose().getRotX(index), 1e-6,
				"resetPoseHistory must collapse rendered to current");
	}

	private static final class FakeAnimatable implements StrutAnimatable {
		private final AnimatableInstanceCache cache = new InstancedAnimatableInstanceCache(this);

		@Override
		public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
			// Deliberately empty - every bone takes the reset-toward-rest branch, which is what
			// this test relies on to observe current actually changing between ticks
		}

		@Override
		public AnimatableInstanceCache getAnimatableInstanceCache() {
			return this.cache;
		}

		@Override
		public double getBoneResetTime() {
			// 0 makes the reset-toward-rest branch snap back immediately (percentageReset == 1)
			// on the very next tick, rather than easing back over several ticks - this test needs
			// current's value after tick two to be deterministic, not dependent on timing
			return 0;
		}

		@Override
		public double getTick(Object object) {
			return 0;
		}
	}

	private static final class FakeModel extends StrutModel<FakeAnimatable> {
		private static final Identifier DUMMY = Identifier.of("strut", "dummy");

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
	}
}
