package com.sandymandy.strut.cache.object;

import com.sandymandy.strut.animation.state.ArmaturePose;
import com.sandymandy.strut.animation.state.BoneSnapshot;
import com.sandymandy.strut.animation.state.ModelInstance;
import org.jetbrains.annotations.ApiStatus;

/**
 * A per-instance, mutable handle onto a {@link ArmatureBoneDefinition}
 * <p>
 * This is the object animations and consumer code actually read and write - every accessor here
 * delegates to the owning instance's {@link ArmaturePose}, addressed by this bone's flat
 * {@link #getIndex() index}, so two instances of the same model never share an armature transform.
 * One handle is allocated per armature bone per instance, eagerly, alongside the instance's
 * {@code ArmaturePose} - see {@link ModelInstance}
 * <p>
 * Immutable/authored data (name, hierarchy, bind matrices, length) is not duplicated here; it stays
 * on {@link #getDefinition()}
 * <p>
 * Implements {@link PosableBone} so the keyframe → snapshot → easing → transform pipeline in
 * {@link com.sandymandy.strut.animation.AnimationProcessor} serves armature bones the same way it
 * serves {@link GroupBoneHandle group bones}
 */
public class ArmatureBoneHandle implements PosableBone {
	private final ArmatureBoneDefinition definition;
	private final ArmaturePose pose;
	private final int index;

	/**
	 * Package-private in spirit, public only because {@link ModelInstance} - the sole legitimate
	 * caller - lives in a different package. Consumer code should never call this directly - get a
	 * handle through {@link ModelInstance#armatureHandlesFor} or {@link ModelInstance#armatureHandleFor}
	 */
	@ApiStatus.Internal
	public ArmatureBoneHandle(ArmatureBoneDefinition definition, ArmaturePose pose) {
		this.definition = definition;
		this.pose = pose;
		this.index = definition.getIndex();
	}

	/**
	 * The immutable, shared geometry and hierarchy this handle poses
	 */
	public ArmatureBoneDefinition getDefinition() {
		return this.definition;
	}

	public String getName() {
		return this.definition.getName();
	}

	/**
	 * This bone's position within its armature, which is the number a vertex influence carries
	 */
	public int getIndex() {
		return this.index;
	}

	public float getPosX() {
		return this.pose.getPosX(this.index);
	}

	public float getPosY() {
		return this.pose.getPosY(this.index);
	}

	public float getPosZ() {
		return this.pose.getPosZ(this.index);
	}

	public float getRotX() {
		return this.pose.getRotX(this.index);
	}

	public float getRotY() {
		return this.pose.getRotY(this.index);
	}

	public float getRotZ() {
		return this.pose.getRotZ(this.index);
	}

	public float getScaleX() {
		return this.pose.getScaleX(this.index);
	}

	public float getScaleY() {
		return this.pose.getScaleY(this.index);
	}

	public float getScaleZ() {
		return this.pose.getScaleZ(this.index);
	}

	public void setPosX(float value) {
		this.pose.setPosition(this.index, value, this.pose.getPosY(this.index), this.pose.getPosZ(this.index));
	}

	public void setPosY(float value) {
		this.pose.setPosition(this.index, this.pose.getPosX(this.index), value, this.pose.getPosZ(this.index));
	}

	public void setPosZ(float value) {
		this.pose.setPosition(this.index, this.pose.getPosX(this.index), this.pose.getPosY(this.index), value);
	}

	public void updatePosition(float posX, float posY, float posZ) {
		this.pose.setPosition(this.index, posX, posY, posZ);
	}

	public void setRotX(float value) {
		this.pose.setRotation(this.index, value, this.pose.getRotY(this.index), this.pose.getRotZ(this.index));
	}

	public void setRotY(float value) {
		this.pose.setRotation(this.index, this.pose.getRotX(this.index), value, this.pose.getRotZ(this.index));
	}

	public void setRotZ(float value) {
		this.pose.setRotation(this.index, this.pose.getRotX(this.index), this.pose.getRotY(this.index), value);
	}

	public void updateRotation(float rotX, float rotY, float rotZ) {
		this.pose.setRotation(this.index, rotX, rotY, rotZ);
	}

	public void setScaleX(float value) {
		this.pose.setScale(this.index, value, this.pose.getScaleY(this.index), this.pose.getScaleZ(this.index));
	}

	public void setScaleY(float value) {
		this.pose.setScale(this.index, this.pose.getScaleX(this.index), value, this.pose.getScaleZ(this.index));
	}

	public void setScaleZ(float value) {
		this.pose.setScale(this.index, this.pose.getScaleX(this.index), this.pose.getScaleY(this.index), value);
	}

	public void updateScale(float scaleX, float scaleY, float scaleZ) {
		this.pose.setScale(this.index, scaleX, scaleY, scaleZ);
	}

	public BoneSnapshot getInitialSnapshot() {
		return this.definition.getInitialSnapshot();
	}
}
