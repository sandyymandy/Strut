package com.sandymandy.strut.animation;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.Identifier;
import org.joml.Matrix4f;
import com.sandymandy.strut.animatable.StrutAnimatable;
import com.sandymandy.strut.animatable.instance.AnimatableInstanceCache;
import com.sandymandy.strut.animatable.instance.InstancedAnimatableInstanceCache;
import com.sandymandy.strut.cache.object.ArmatureBoneHandle;
import com.sandymandy.strut.cache.object.BakedStrutModel;
import com.sandymandy.strut.cache.object.GroupBoneDefinition;
import com.sandymandy.strut.cache.object.GroupBoneHandle;
import com.sandymandy.strut.cache.object.StrutArmature;
import com.sandymandy.strut.cache.object.ArmatureBoneDefinition;
import com.sandymandy.strut.loading.json.raw.ModelProperties;
import com.sandymandy.strut.loading.math.MathValue;
import com.sandymandy.strut.loading.math.value.Constant;
import com.sandymandy.strut.animation.keyframe.BoneAnimation;
import com.sandymandy.strut.animation.keyframe.Keyframe;
import com.sandymandy.strut.animation.keyframe.KeyframeStack;
import com.sandymandy.strut.animation.keyframe.event.data.CustomInstructionKeyframeData;
import com.sandymandy.strut.animation.keyframe.event.data.ParticleKeyframeData;
import com.sandymandy.strut.animation.keyframe.event.data.SoundKeyframeData;
import com.sandymandy.strut.model.StrutModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A model carrying a group bone and an armature bone with the same
 * name must bind each to its own transform, and an unqualified lookup - which is all any existing
 * animation source (every {@code .animation.json}, and every {@code .strutanim} until bone kinds are stored)
 * provides - must find the group bone
 * <p>
 * {@link BoneKey#resolveUnqualified} is the rule in isolation; the second test drives the real
 * pipeline ({@link AnimationController} + {@link AnimationProcessor#tickAnimation}) with an
 * animation targeting a bone name that both a group bone and an armature bone share, proving the
 * rule actually governs which bone an animation binds to, not just what the helper method returns
 * in isolation
 */
class BoneKeyResolutionTest {
	@Test
	@DisplayName("resolveUnqualified prefers a registered group key over an armature key of the same name")
	void resolveUnqualifiedPrefersGroup() {
		Map<BoneKey, String> registered = Map.of(
				BoneKey.group("shared"), "group",
				BoneKey.armature("shared"), "armature");

		assertEquals(BoneKey.group("shared"), BoneKey.resolveUnqualified(registered, "shared"));
	}

	@Test
	@DisplayName("resolveUnqualified falls back to the armature key when no group bone of that name is registered")
	void resolveUnqualifiedFallsBackToArmature() {
		Map<BoneKey, String> registered = Map.of(BoneKey.armature("shared"), "armature");

		assertEquals(BoneKey.armature("shared"), BoneKey.resolveUnqualified(registered, "shared"));
	}

	@Test
	@DisplayName("resolveUnqualified returns null when neither kind is registered")
	void resolveUnqualifiedReturnsNullWhenNeitherRegistered() {
		assertNull(BoneKey.resolveUnqualified(Map.of(), "shared"));
	}

	@Test
	@DisplayName("an animation targeting a shared name binds the group bone, leaving the same-named armature bone untouched")
	void sharedNameBindsTheGroupBoneOnly() {
		GroupBoneDefinition groupBone = new GroupBoneDefinition(null, "shared", false, null, null, null);

		groupBone.bakeInitialRotation(0, 0, 0);
		groupBone.bakePivot(0, 0, 0);

		ArmatureBoneDefinition armatureBone = new ArmatureBoneDefinition(null, "shared", new Matrix4f(), new Matrix4f(), 1f, 0);
		StrutArmature armature = new StrutArmature("rig", List.of(armatureBone), null);

		ModelProperties properties = new ModelProperties(null, null, null, null, null, null, null, null, null, null,
				"test", null, 16, 16, null, new double[] {0, 0, 0}, null);
		BakedStrutModel bakedModel = new BakedStrutModel(List.of(groupBone), List.of(armature), properties);

		// A single keyframe segment rotating Z from 0 to a right angle over 20 ticks - long enough
		// that a handful of ticks lands clearly inside the transition, not right at either endpoint
		KeyframeStack<Keyframe<MathValue>> rotation = new KeyframeStack<>(
				new ObjectArrayList<>(List.of(new Keyframe<>(20, new Constant(0), new Constant(0)))),
				new ObjectArrayList<>(List.of(new Keyframe<>(20, new Constant(0), new Constant(0)))),
				new ObjectArrayList<>(List.of(new Keyframe<>(20, new Constant(0), new Constant(Math.PI / 2)))));
		BoneAnimation boneAnimation = new BoneAnimation("shared", rotation, new KeyframeStack<>(), new KeyframeStack<>());
		Animation animation = new Animation("test", 20, Animation.LoopType.LOOP, new BoneAnimation[] {boneAnimation},
				new Animation.Keyframes(new SoundKeyframeData[0], new ParticleKeyframeData[0], new CustomInstructionKeyframeData[0]));

		FakeAnimatable animatable = new FakeAnimatable();
		FakeModel model = new FakeModel(animation);

		model.getAnimationProcessor().setActiveModel(bakedModel);

		AnimatableManager<FakeAnimatable> manager = new AnimatableManager<>(animatable);

		for (double tick = 0; tick <= 19; tick++) {
			model.getAnimationProcessor().tickAnimation(animatable, model, manager, tick,
					new AnimationState<>(animatable, 0, 0, 0, false), false);
		}

		GroupBoneHandle groupHandle = manager.getModelInstance().handlesFor(bakedModel)[groupBone.getIndex()];
		ArmatureBoneHandle armatureHandle = manager.getModelInstance().armatureHandleFor(bakedModel, armature, armatureBone);

		// Not asserted against the exact endpoint value - a handful of ticks in, close but not
		// necessarily exactly at the target is expected. What this proves is that the animation
		// bound to the group bone at all, and specifically not to the same-named armature bone
		assertTrue(groupHandle.getRotZ() > 1.0f,
				"the group bone, resolved unqualified, must have been driven substantially toward the target by the animation, was " + groupHandle.getRotZ());
		assertEquals(0f, armatureHandle.getRotZ(), 1e-6,
				"the armature bone sharing the group bone's name must be untouched - the animation must not have bound to it");
	}

	private static final class FakeAnimatable implements StrutAnimatable {
		private final AnimatableInstanceCache cache = new InstancedAnimatableInstanceCache(this);

		@Override
		public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
			controllers.add(new AnimationController<>(this,
					state -> state.setAndContinue(RawAnimation.begin().thenLoop("test"))));
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
