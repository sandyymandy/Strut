/*
 * Copyright (c) 2020.
 * Author: Bernie G. (Gecko)
 */

package com.sandymandy.strut.animation.keyframe;

import com.sandymandy.strut.loading.binary.StrutBinaryFormats.TargetKind;
import com.sandymandy.strut.loading.math.MathValue;
import org.jetbrains.annotations.Nullable;

/**
 * A record of a deserialized animation for a given bone
 * <p>
 * Responsible for holding the various {@link Keyframe Keyframes} for the bone's animation transformations
 *
 * @param boneName The name of the bone as listed in the {@code animation.json}
 * @param targetKind What kind of bone {@code boneName} names, or null where the source does not say
 *                   - which is every {@code .animation.json}, since the JSON format has no notion of
 *                   armature bones. A null kind resolves unqualified (group bone first); a non-null
 *                   one binds to exactly that kind, which is how a {@code .strutanim} channel's
 *                   {@code targetKind} reaches the animation pipeline
 * @param rotationKeyFrames The deserialized rotation {@code Keyframe} stack
 * @param positionKeyFrames The deserialized position {@code Keyframe} stack
 * @param scaleKeyFrames The deserialized scale {@code Keyframe} stack
 */
public record BoneAnimation(String boneName,
							@Nullable TargetKind targetKind,
							KeyframeStack<Keyframe<MathValue>> rotationKeyFrames,
							KeyframeStack<Keyframe<MathValue>> positionKeyFrames,
							KeyframeStack<Keyframe<MathValue>> scaleKeyFrames) {
	/**
	 * A bone animation from a source that names a bone without saying what kind it is
	 */
	public BoneAnimation(String boneName,
						 KeyframeStack<Keyframe<MathValue>> rotationKeyFrames,
						 KeyframeStack<Keyframe<MathValue>> positionKeyFrames,
						 KeyframeStack<Keyframe<MathValue>> scaleKeyFrames) {
		this(boneName, null, rotationKeyFrames, positionKeyFrames, scaleKeyFrames);
	}
}
