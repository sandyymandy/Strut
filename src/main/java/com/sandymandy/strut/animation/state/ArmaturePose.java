package com.sandymandy.strut.animation.state;

import com.sandymandy.strut.cache.object.StrutArmature;
import com.sandymandy.strut.cache.object.ArmatureBoneDefinition;

import java.util.Arrays;

/**
 * One animatable instance's armature bone transforms - position, rotation and scale - indexed
 * identically to {@link StrutArmature#bones()}
 *
 * <h2>Authoritative storage</h2>
 *
 * <p>This is the only place an armature bone's transform lives.
 * {@link com.sandymandy.strut.cache.object.ArmatureBoneHandle} is a thin per-instance accessor over
 * this class, exactly as {@link com.sandymandy.strut.cache.object.GroupBoneHandle} is over {@link Pose} -
 * every read and write on a handle addresses its owning instance's {@code ArmaturePose} by flat
 * index, so two instances of the same model never alias each other's armature pose. This closes the
 * same aliasing hole {@link Pose} closed for group bones, for
 * {@link ArmatureBoneDefinition}, which used to carry these nine floats itself as mutable state on a
 * shared, cached, baked object
 *
 * <h2>Layout</h2>
 *
 * <p>Plain parallel float arrays, matching {@link Pose}'s reasoning: a whole armature's animated
 * pose is a handful of contiguous arrays rather than one allocation per bone. No visibility bits and
 * no pivot delta - an armature bone has neither in the current API, unlike a group bone
 */
public final class ArmaturePose {
	private final float[] posX;
	private final float[] posY;
	private final float[] posZ;

	private final float[] rotX;
	private final float[] rotY;
	private final float[] rotZ;

	private final float[] scaleX;
	private final float[] scaleY;
	private final float[] scaleZ;

	public ArmaturePose(int boneCount) {
		this.posX = new float[boneCount];
		this.posY = new float[boneCount];
		this.posZ = new float[boneCount];

		this.rotX = new float[boneCount];
		this.rotY = new float[boneCount];
		this.rotZ = new float[boneCount];

		this.scaleX = new float[boneCount];
		this.scaleY = new float[boneCount];
		this.scaleZ = new float[boneCount];

		Arrays.fill(this.scaleX, 1f);
		Arrays.fill(this.scaleY, 1f);
		Arrays.fill(this.scaleZ, 1f);
	}

	public int boneCount() {
		return this.posX.length;
	}

	public void setPosition(int index, float x, float y, float z) {
		this.posX[index] = x;
		this.posY[index] = y;
		this.posZ[index] = z;
	}

	public void setRotation(int index, float x, float y, float z) {
		this.rotX[index] = x;
		this.rotY[index] = y;
		this.rotZ[index] = z;
	}

	public void setScale(int index, float x, float y, float z) {
		this.scaleX[index] = x;
		this.scaleY[index] = y;
		this.scaleZ[index] = z;
	}

	public float getPosX(int index) {
		return this.posX[index];
	}

	public float getPosY(int index) {
		return this.posY[index];
	}

	public float getPosZ(int index) {
		return this.posZ[index];
	}

	public float getRotX(int index) {
		return this.rotX[index];
	}

	public float getRotY(int index) {
		return this.rotY[index];
	}

	public float getRotZ(int index) {
		return this.rotZ[index];
	}

	public float getScaleX(int index) {
		return this.scaleX[index];
	}

	public float getScaleY(int index) {
		return this.scaleY[index];
	}

	public float getScaleZ(int index) {
		return this.scaleZ[index];
	}
}
