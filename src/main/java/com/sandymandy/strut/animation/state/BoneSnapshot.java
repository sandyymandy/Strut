package com.sandymandy.strut.animation.state;

import com.sandymandy.strut.animation.AnimationProcessor;

/**
 * A state monitoring class for a bone, addressed by name
 * <p>
 * Transformations applied to the bone are monitored by the {@link AnimationProcessor}
 * in the course of animations, and stored here for monitoring.
 * <p>
 * Holds only the bone's name, not a reference to the bone itself
 * split the bone into an immutable {@link com.sandymandy.strut.cache.object.GroupBoneDefinition} and a
 * per-instance {@link com.sandymandy.strut.cache.object.GroupBoneHandle}, and a snapshot outlives
 * neither of those in any way that requires holding one
 */
public class BoneSnapshot {
	private final String boneName;

	private float scaleX;
	private float scaleY;
	private float scaleZ;

	private float offsetPosX;
	private float offsetPosY;
	private float offsetPosZ;

	private float rotX;
	private float rotY;
	private float rotZ;

	private double lastResetRotationTick = 0;
	private double lastResetPositionTick = 0;
	private double lastResetScaleTick = 0;

	private boolean rotAnimInProgress = true;
	private boolean posAnimInProgress = true;
	private boolean scaleAnimInProgress = true;

	public BoneSnapshot(String boneName, float rotX, float rotY, float rotZ, float offsetPosX, float offsetPosY,
						 float offsetPosZ, float scaleX, float scaleY, float scaleZ) {
		this.boneName = boneName;
		this.rotX = rotX;
		this.rotY = rotY;
		this.rotZ = rotZ;
		this.offsetPosX = offsetPosX;
		this.offsetPosY = offsetPosY;
		this.offsetPosZ = offsetPosZ;
		this.scaleX = scaleX;
		this.scaleY = scaleY;
		this.scaleZ = scaleZ;
	}

	public static BoneSnapshot copy(BoneSnapshot snapshot) {
		BoneSnapshot newSnapshot = new BoneSnapshot(snapshot.boneName, snapshot.rotX, snapshot.rotY, snapshot.rotZ,
				snapshot.offsetPosX, snapshot.offsetPosY, snapshot.offsetPosZ, snapshot.scaleX, snapshot.scaleY, snapshot.scaleZ);

		return newSnapshot;
	}

	public String getName() {
		return this.boneName;
	}

	public float getScaleX() {
		return this.scaleX;
	}

	public float getScaleY() {
		return this.scaleY;
	}

	public float getScaleZ() {
		return this.scaleZ;
	}

	public float getOffsetX() {
		return this.offsetPosX;
	}

	public float getOffsetY() {
		return this.offsetPosY;
	}

	public float getOffsetZ() {
		return this.offsetPosZ;
	}

	public float getRotX() {
		return this.rotX;
	}

	public float getRotY() {
		return this.rotY;
	}

	public float getRotZ() {
		return this.rotZ;
	}

	public double getLastResetRotationTick() {
		return this.lastResetRotationTick;
	}

	public double getLastResetPositionTick() {
		return this.lastResetPositionTick;
	}

	public double getLastResetScaleTick() {
		return this.lastResetScaleTick;
	}

	public boolean isRotAnimInProgress() {
		return this.rotAnimInProgress;
	}

	public boolean isPosAnimInProgress() {
		return this.posAnimInProgress;
	}

	public boolean isScaleAnimInProgress() {
		return this.scaleAnimInProgress;
	}

	/**
	 * Update the scale state of this snapshot
	 */
	public void updateScale(float scaleX, float scaleY, float scaleZ) {
		this.scaleX = scaleX;
		this.scaleY = scaleY;
		this.scaleZ = scaleZ;
	}

	/**
	 * Update the offset state of this snapshot
	 */
	public void updateOffset(float offsetX, float offsetY, float offsetZ) {
		this.offsetPosX = offsetX;
		this.offsetPosY = offsetY;
		this.offsetPosZ = offsetZ;
	}

	/**
	 * Update the rotation state of this snapshot
	 */
	public void updateRotation(float rotX, float rotY, float rotZ) {
		this.rotX = rotX;
		this.rotY = rotY;
		this.rotZ = rotZ;
	}

	public void startPosAnim() {
		this.posAnimInProgress = true;
	}

	public void stopPosAnim(double tick) {
		this.posAnimInProgress = false;
		this.lastResetPositionTick = tick;
	}

	public void startRotAnim() {
		this.rotAnimInProgress = true;
	}

	public void stopRotAnim(double tick) {
		this.rotAnimInProgress = false;
		this.lastResetRotationTick = tick;
	}

	public void startScaleAnim() {
		this.scaleAnimInProgress = true;
	}

	public void stopScaleAnim(double tick) {
		this.scaleAnimInProgress = false;
		this.lastResetScaleTick = tick;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;

		if (obj == null || getClass() != obj.getClass())
			return false;

		return hashCode() == obj.hashCode();
	}

	@Override
	public int hashCode() {
		return this.boneName.hashCode();
	}
}
