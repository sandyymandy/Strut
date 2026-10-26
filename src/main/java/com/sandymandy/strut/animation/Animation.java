/*
 * Copyright (c) 2020.
 * Author: Bernie G. (Gecko)
 */

package com.sandymandy.strut.animation;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.sandymandy.strut.animatable.StrutAnimatable;
import com.sandymandy.strut.animation.keyframe.BoneAnimation;
import com.sandymandy.strut.animation.keyframe.event.data.CustomInstructionKeyframeData;
import com.sandymandy.strut.animation.keyframe.event.data.ParticleKeyframeData;
import com.sandymandy.strut.animation.keyframe.event.data.SoundKeyframeData;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A compiled animation instance for use by the {@link AnimationController}
 * <p>
 * Modifications or extensions of a compiled Animation are not supported, and therefore an instance of <code>Animation</code> is considered final and immutable
 *
 * @param skeletonFingerprint The hash of bone names this animation's {@code .strutanim} source
 *                            was authored against, or null for one with no such notion - every
 *                            {@code .animation.json} today, since the JSON format carries no
 *                            fingerprint. Compared against {@link com.sandymandy.strut.cache.object.BakedStrutModel#skeletonFingerprint()}
 *                            where an animation and a model first meet, in
 *                            {@link AnimationProcessor#buildAnimationQueue} - a mismatch is
 *                            warned, never refused, since a clip driving a subset of a rig is
 *                            legitimate
 */
public record Animation(String name, double length, LoopType loopType, BoneAnimation[] boneAnimations, Keyframes keyFrames,
						@Nullable Integer skeletonFingerprint) {
	/**
	 * An animation from a source with no notion of a skeleton fingerprint - every
	 * {@code .animation.json} today
	 */
	public Animation(String name, double length, LoopType loopType, BoneAnimation[] boneAnimations, Keyframes keyFrames) {
		this(name, length, loopType, boneAnimations, keyFrames, null);
	}

	public record Keyframes(SoundKeyframeData[] sounds, ParticleKeyframeData[] particles, CustomInstructionKeyframeData[] customInstructions) {}

	static Animation generateWaitAnimation(double length) {
		return new Animation(RawAnimation.Stage.WAIT, length, LoopType.PLAY_ONCE, new BoneAnimation[0],
				new Keyframes(new SoundKeyframeData[0], new ParticleKeyframeData[0], new CustomInstructionKeyframeData[0]));
	}

	/**
	 * Loop type functional interface to define post-play handling for a given animation
	 * <p>
	 * Custom loop types are supported by extending this class and providing the extended class instance as the loop type for the animation
	 */
	@FunctionalInterface
	public interface LoopType {
		Map<String, LoopType> LOOP_TYPES = new ConcurrentHashMap<>(4);

		LoopType DEFAULT = register("default", (animatable, controller, currentAnimation) -> currentAnimation.loopType().shouldPlayAgain(animatable, controller, currentAnimation));
		LoopType PLAY_ONCE = register("play_once", register("false", (animatable, controller, currentAnimation) -> false));
		LoopType HOLD_ON_LAST_FRAME = register("hold_on_last_frame", (animatable, controller, currentAnimation) -> {
			controller.animationState = AnimationController.State.PAUSED;

			return true;
		});
		LoopType LOOP = register("loop", register("true", (animatable, controller, currentAnimation) -> true));

		/**
		 * Override in a custom instance to dynamically decide whether an animation should repeat or stop
		 *
		 * @param animatable The animating object relevant to this method call
		 * @param controller The {@link AnimationController} playing the current animation
		 * @param currentAnimation The current animation that just played
		 * @return Whether the animation should play again, or stop
		 */
		boolean shouldPlayAgain(StrutAnimatable animatable, AnimationController<? extends StrutAnimatable> controller, Animation currentAnimation);

		/**
		 * Retrieve a LoopType instance based on a {@link JsonElement}
		 * <p>
		 * Returns either {@link LoopType#PLAY_ONCE} or {@link LoopType#LOOP} based on a boolean or string element type,
		 * or any other registered loop type with a matching type string
		 *
		 * @param json The <code>loop</code> {@link JsonElement} to attempt to parse
		 * @return A usable LoopType instance
		 */
		static LoopType fromJson(JsonElement json) {
			if (json == null || !json.isJsonPrimitive())
				return PLAY_ONCE;

			JsonPrimitive primitive = json.getAsJsonPrimitive();

			if (primitive.isBoolean())
				return primitive.getAsBoolean() ? LOOP : PLAY_ONCE;

			if (primitive.isString())
				return fromString(primitive.getAsString());

			return PLAY_ONCE;
		}

        /**
         * Get the registered name for this LoopType
         *
         * @throws IllegalStateException if this LoopType has not been registered
         */
        default String getId() throws IllegalStateException {
            for (String loopType : LOOP_TYPES.keySet()) {
                if (LOOP_TYPES.get(loopType) == this)
                    return loopType;
            }

            throw new IllegalStateException("LoopType has not been registered before being used!");
        }

        /**
         * Get a registered LoopType by name, or {@link LoopType#PLAY_ONCE} if none match
         */
		static LoopType fromString(String name) {
			return LOOP_TYPES.getOrDefault(name, PLAY_ONCE);
		}

		/**
		 * Register a LoopType with Strut for handling loop functionality of animations
		 * <p>
		 * <b><u>MUST be called during mod construct</u></b>
		 * <p>
		 * It is recommended you don't call this directly, and instead call it via {@code StrutUtil#addCustomLoopType}
		 *
		 * @param name The name of the loop type
		 * @param loopType The loop type to register
		 * @return The registered {@code LoopType}
		 */
		static LoopType register(String name, LoopType loopType) {
			LOOP_TYPES.put(name, loopType);

			return loopType;
		}
	}
}
