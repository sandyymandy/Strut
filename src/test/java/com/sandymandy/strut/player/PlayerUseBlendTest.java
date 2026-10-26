package com.sandymandy.strut.player;

import com.sandymandy.strut.animation.AnimatableManager;
import com.sandymandy.strut.animation.Animation;
import com.sandymandy.strut.animation.AnimationState;
import com.sandymandy.strut.animation.keyframe.event.data.CustomInstructionKeyframeData;
import com.sandymandy.strut.animation.keyframe.event.data.ParticleKeyframeData;
import com.sandymandy.strut.animation.keyframe.event.data.SoundKeyframeData;
import com.sandymandy.strut.cache.object.BakedStrutModel;
import com.sandymandy.strut.cache.object.GroupBoneHandle;
import com.sandymandy.strut.loading.binary.BinaryAnimationBaker;
import com.sandymandy.strut.loading.binary.BinaryAnimationReader;
import com.sandymandy.strut.loading.binary.BinaryModelBaker;
import com.sandymandy.strut.loading.binary.BinaryModelReader;
import com.sandymandy.strut.loading.object.BakedAnimations;
import com.sandymandy.strut.model.StrutModel;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A body drawing a bow, raising a shield and loading a crossbow, twice each, with the dev body's real clips and all of its controllers (the legs' locomotion
 * under the arms' use): the arms never jump from one frame to the next. Not when the charge gives way to the hold (the hold used to blend from the legs'
 * pose, taking the arms down and back up), and not when the use ends (the arms used to snap to the legs' pose)
 */
class PlayerUseBlendTest {
	@Test
	void theArmsMoveSmoothlyThroughEveryUse() throws Exception {
		for (PlayerMotion.Use use : new PlayerMotion.Use[] {PlayerMotion.Use.BOW, PlayerMotion.Use.BLOCK, PlayerMotion.Use.CROSSBOW})
			trace(use);
	}

	private void trace(PlayerMotion.Use use) throws Exception {
		Map<String, Float> last = new HashMap<>();
		float highestOnSecondUse = -10;
		Path modelFile = Path.of("src/main/resources/assets/strut/strut/models/entity/test_dummy.strutmodel");
		Path animFile = Path.of("src/main/resources/assets/strut/strut/animations/entity/test_player.strutanim");
		BakedStrutModel baked = BinaryModelBaker.bake(BinaryModelReader.read(Files.readAllBytes(modelFile), "m"), "m");
		BakedAnimations anims = BinaryAnimationBaker.bake(BinaryAnimationReader.read(Files.readAllBytes(animFile), "a"), "a");
		PlayerBody body = PlayerBody.builder(Identifier.of("strut", "scratch_bow")).locomotion().clipPrefix("animation.base.").build();
		StrutModel<PlayerBody> model = new StrutModel<>() {
			@Override
			public Animation getAnimation(PlayerBody animatable, String name) {
				Animation a = anims.getAnimation(name);

				return a == null ? null : new Animation(a.name(), a.length(), a.loopType(), a.boneAnimations(), new Animation.Keyframes(
						new SoundKeyframeData[0], new ParticleKeyframeData[0],
						new CustomInstructionKeyframeData[0]));
			}

			@Override
			public Identifier getModelResource(PlayerBody animatable) {
				return Identifier.of("strut", "m");
			}

			@Override
			public Identifier getTextureResource(PlayerBody animatable) {
				return Identifier.of("strut", "m");
			}

			@Override
			public Identifier getAnimationResource(PlayerBody animatable) {
				return Identifier.of("strut", "a");
			}
		};

		model.getAnimationProcessor().setActiveModel(baked);

		AnimatableManager<PlayerBody> manager = new AnimatableManager<>(body);

		for (int frame = 0; frame < 780; frame++) {
			double time = frame / 3.0;
			boolean using = (time >= 10 && time < 100) || (time >= 130 && time < 220);
			PlayerMotion motion = new PlayerMotion(7, true, false, false, false, false, false, false, false, false, false, false, PlayerMotion.Vehicle.NONE, false,
					false, false, false, 0f, 0f, false, true, -1, using ? use : PlayerMotion.Use.NONE);
			AnimationState<PlayerBody> state = new AnimationState<>(body, 0, 0, 0, false);

			state.setData(PlayerTickets.MOTION, motion);
			state.setData(PlayerTickets.HAS_CLIP, clip -> anims.getAnimation(clip) != null);
			model.getAnimationProcessor().tickAnimation(body, model, manager, time, state, false);

			for (GroupBoneHandle handle : manager.getModelInstance().handlesFor(baked))
				if (handle.getName().equals("armL") || handle.getName().equals("armR") || handle.getName().equals("lowerArmL") || handle.getName().equals("lowerArmR")) {
					Float before = last.put(handle.getName(), handle.getRotX());

					// a sixtieth of a second: authored motion is well under this, a snap is several times it
					if (before != null)
						Assertions.assertTrue(Math.abs(handle.getRotX() - before) < 0.25f,
								use + ": " + handle.getName() + " jumped from " + before + " to " + handle.getRotX() + " at tick " + time);

					if (handle.getName().equals("armR") && time >= 130 && time < 220)
						highestOnSecondUse = Math.max(highestOnSecondUse, Math.abs(handle.getRotX()));
				}
		}

		// the second use plays its clips again: the arm comes up as it did the first time
		Assertions.assertTrue(highestOnSecondUse > 1.0f, use + ": the second use did not raise the arm (" + highestOnSecondUse + ")");
	}
}
