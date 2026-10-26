package com.sandymandy.strut.animation;

import net.minecraft.util.Identifier;
import com.sandymandy.strut.animatable.StrutAnimatable;
import com.sandymandy.strut.animatable.instance.AnimatableInstanceCache;
import com.sandymandy.strut.animatable.instance.InstancedAnimatableInstanceCache;
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
 * Proves the actual wiring in {@link AnimationProcessor#tickAnimation}, not just the
 * {@link Pose}/{@link com.sandymandy.strut.animation.state.ModelInstance} storage that
 * {@code ModelInstanceIsolationTest} exercises directly
 * <p>
 * {@link GroupBoneHandle} is a thin accessor directly over {@link Pose} -
 * there is no longer a separate "bone" to dual-write from, so what this proves is narrower than it
 * used to be: that reading a handle and reading its instance's {@code Pose} by flat index are
 * literally the same value, for every registered bone, after a real tick with no controllers
 * registered (so every bone takes the reset branch, not the animated one - the path most likely to
 * be missed if a future change reintroduced separate storage)
 */
class AnimationProcessorPoseSyncTest {
	@Test
	@DisplayName("every bone handle's transform after a tick is the same read as its instance's Pose at that index")
	void handleReadsMirrorPoseAfterTick() {
		BakedStrutModel bakedModel = BinaryModelBaker.bake(
				BinaryModelReader.read(BinaryFixtures.riggedModel(), "rig.strutmodel"), "rig.strutmodel");

		FakeAnimatable animatable = new FakeAnimatable();
		FakeModel model = new FakeModel();

		model.getAnimationProcessor().setActiveModel(bakedModel);

		AnimatableManager<FakeAnimatable> manager = new AnimatableManager<>(animatable);
		AnimationState<FakeAnimatable> state = new AnimationState<>(animatable, 0, 0, 0, false);

		model.getAnimationProcessor().tickAnimation(animatable, model, manager, 0, state, false);

		Pose pose = manager.getModelInstance().poseFor(bakedModel);
		GroupBoneHandle[] handles = manager.getModelInstance().handlesFor(bakedModel);

		assertEquals(bakedModel.flatBones().length, pose.boneCount());

		for (GroupBoneHandle bone : handles) {
			int index = bone.getIndex();

			assertEquals(bone.getPosX(), pose.getPosX(index), "position.x must be the same read as the Pose");
			assertEquals(bone.getPosY(), pose.getPosY(index), "position.y must be the same read as the Pose");
			assertEquals(bone.getPosZ(), pose.getPosZ(index), "position.z must be the same read as the Pose");
			assertEquals(bone.getRotX(), pose.getRotX(index), "rotation.x must be the same read as the Pose");
			assertEquals(bone.getRotY(), pose.getRotY(index), "rotation.y must be the same read as the Pose");
			assertEquals(bone.getRotZ(), pose.getRotZ(index), "rotation.z must be the same read as the Pose");
			assertEquals(bone.getScaleX(), pose.getScaleX(index), "scale.x must be the same read as the Pose");
			assertEquals(bone.getScaleY(), pose.getScaleY(index), "scale.y must be the same read as the Pose");
			assertEquals(bone.getScaleZ(), pose.getScaleZ(index), "scale.z must be the same read as the Pose");
			assertEquals(bone.isHidden(), pose.isHidden(index), "visibility must be the same read as the Pose");
		}
	}

	@Test
	@DisplayName("two animatables of the same type get independent Poses from the same processor's ticks")
	void twoAnimatablesDoNotShareAPoseThroughOneProcessor() {
		BakedStrutModel bakedModel = BinaryModelBaker.bake(
				BinaryModelReader.read(BinaryFixtures.riggedModel(), "rig.strutmodel"), "rig.strutmodel");

		FakeModel model = new FakeModel();
		model.getAnimationProcessor().setActiveModel(bakedModel);

		FakeAnimatable first = new FakeAnimatable();
		FakeAnimatable second = new FakeAnimatable();
		AnimatableManager<FakeAnimatable> firstManager = new AnimatableManager<>(first);
		AnimatableManager<FakeAnimatable> secondManager = new AnimatableManager<>(second);

		model.getAnimationProcessor().tickAnimation(first, model, firstManager,
				0, new AnimationState<>(first, 0, 0, 0, false), false);
		model.getAnimationProcessor().tickAnimation(second, model, secondManager,
				0, new AnimationState<>(second, 0, 0, 0, false), false);

		assertNotSame(firstManager.getModelInstance().poseFor(bakedModel),
				secondManager.getModelInstance().poseFor(bakedModel),
				"each AnimatableManager must own its own ModelInstance and Pose");
	}

	private static final class FakeAnimatable implements StrutAnimatable {
		private final AnimatableInstanceCache cache = new InstancedAnimatableInstanceCache(this);

		@Override
		public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
			// Deliberately empty - every bone should take the reset branch, not the animated one
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
