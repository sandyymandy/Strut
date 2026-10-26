package com.sandymandy.strut.cache.object;

import com.sandymandy.strut.animation.state.BoneSnapshot;

/**
 * Whatever the keyframe → snapshot → easing → transform pipeline in
 * {@link com.sandymandy.strut.animation.AnimationProcessor} and
 * {@link com.sandymandy.strut.animation.AnimationController} needs from a bone, and nothing more
 * <p>
 * Implemented by both {@link GroupBoneHandle} (group bones) and {@link ArmatureBoneHandle} (armature
 * bones), so animation stays one code path for both kinds rather than two - this is
 * the original "highest-leverage decision"
 * ({@code ArmatureBoneDefinition extends StrutBone}) restated for the per-instance architecture,
 * where a group bone's handle and an armature bone's handle are unrelated classes addressing
 * unrelated per-instance pose storage.
 */
public interface PosableBone {
	String getName();

	/**
	 * This bone's rest pose, as the animation reset logic lerps back toward when nothing is
	 * actively animating it
	 */
	BoneSnapshot getInitialSnapshot();

	float getRotX();

	float getRotY();

	float getRotZ();

	void setRotX(float value);

	void setRotY(float value);

	void setRotZ(float value);

	float getPosX();

	float getPosY();

	float getPosZ();

	void setPosX(float value);

	void setPosY(float value);

	void setPosZ(float value);

	float getScaleX();

	float getScaleY();

	float getScaleZ();

	void setScaleX(float value);

	void setScaleY(float value);

	void setScaleZ(float value);
}
