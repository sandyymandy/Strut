package com.sandymandy.strut.animation.state;

import com.sandymandy.strut.cache.object.BakedStrutModel;
import com.sandymandy.strut.cache.object.GroupBoneHandle;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Arrays;

/**
 * One animatable instance's bone transforms - position, rotation, scale, visibility and the
 * per-instance pivot adjustment - indexed identically to {@link BakedStrutModel#flatBones()}
 *
 * <h2>Authoritative storage</h2>
 *
 * <p>This is the only place a bone's transform lives.
 * {@link GroupBoneHandle} is a thin per-instance accessor over this class - every read and write on a
 * handle addresses its owning instance's {@code Pose} by flat index, so two instances of the same
 * model never alias each other's pose
 *
 * <h2>Three buffers, one class</h2>
 *
 * <p>One {@code Pose} instance is just storage - a {@link ModelInstance} holds
 * three of them ({@code previous}, {@code current}, {@code rendered}), and {@link #interpolate}
 * blends the first two into the third by partial tick. {@link GroupBoneHandle}'s ordinary accessors
 * still read and write {@code current} only; {@code rendered} is what render-time code (see
 * {@link com.sandymandy.strut.util.RenderUtil}) actually draws
 *
 * <h2>Layout</h2>
 *
 * <p>Plain parallel float arrays rather than an array of small structs, so a whole pose is a
 * handful of contiguous arrays rather than one allocation per bone. Visibility is a bit set
 * rather than a {@code boolean[]} for the same reason at smaller scale - free up to 64 bones,
 * and every reference model measured so far is well under that
 */
public final class Pose {
	private final float[] posX;
	private final float[] posY;
	private final float[] posZ;

	private final float[] rotX;
	private final float[] rotY;
	private final float[] rotZ;

	private final float[] scaleX;
	private final float[] scaleY;
	private final float[] scaleZ;

	private final float[] pivotDeltaX;
	private final float[] pivotDeltaY;
	private final float[] pivotDeltaZ;

	private final long[] hidden;
	private final long[] childrenHidden;

	public Pose(int boneCount) {
		this.posX = new float[boneCount];
		this.posY = new float[boneCount];
		this.posZ = new float[boneCount];

		this.rotX = new float[boneCount];
		this.rotY = new float[boneCount];
		this.rotZ = new float[boneCount];

		this.scaleX = new float[boneCount];
		this.scaleY = new float[boneCount];
		this.scaleZ = new float[boneCount];

		this.pivotDeltaX = new float[boneCount];
		this.pivotDeltaY = new float[boneCount];
		this.pivotDeltaZ = new float[boneCount];

		this.hidden = new long[bitSetLength(boneCount)];
		this.childrenHidden = new long[bitSetLength(boneCount)];

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

	public float getPivotDeltaX(int index) {
		return this.pivotDeltaX[index];
	}

	public float getPivotDeltaY(int index) {
		return this.pivotDeltaY[index];
	}

	public float getPivotDeltaZ(int index) {
		return this.pivotDeltaZ[index];
	}

	public void setPivotDeltaX(int index, float value) {
		this.pivotDeltaX[index] = value;
	}

	public void setPivotDeltaY(int index, float value) {
		this.pivotDeltaY[index] = value;
	}

	public void setPivotDeltaZ(int index, float value) {
		this.pivotDeltaZ[index] = value;
	}

	public void setHidden(int index, boolean value) {
		setBit(this.hidden, index, value);
	}

	public boolean isHidden(int index) {
		return getBit(this.hidden, index);
	}

	public void setChildrenHidden(int index, boolean value) {
		setBit(this.childrenHidden, index, value);
	}

	public boolean isChildrenHidden(int index) {
		return getBit(this.childrenHidden, index);
	}

	/**
	 * Whether any bone in this pose has {@link #isChildrenHidden(int)} set - a cheap (one word per
	 * 64 bones) way for {@code ModelInstance#resolveEffectivePose} to skip its ancestor-propagation
	 * pass entirely on the common frame where nothing needs it, rather than paying for a bone-by-bone
	 * walk to discover the same answer
	 */
	public boolean hasAnyChildrenHidden() {
		for (long word : this.childrenHidden) {
			if (word != 0)
				return true;
		}

		return false;
	}

	/**
	 * Overwrites every value in this pose with {@code other}'s
	 * <p>
	 * Used by {@link ModelInstance} to snapshot {@code current} into {@code previous} ahead of a
	 * new animation update, and to seed {@code previous}/{@code rendered} on first bind - never
	 * called with poses of different {@link #boneCount()}, since all three of one instance's poses
	 * are always sized together
	 */
	public void copyFrom(Pose other) {
		System.arraycopy(other.posX, 0, this.posX, 0, this.posX.length);
		System.arraycopy(other.posY, 0, this.posY, 0, this.posY.length);
		System.arraycopy(other.posZ, 0, this.posZ, 0, this.posZ.length);

		System.arraycopy(other.rotX, 0, this.rotX, 0, this.rotX.length);
		System.arraycopy(other.rotY, 0, this.rotY, 0, this.rotY.length);
		System.arraycopy(other.rotZ, 0, this.rotZ, 0, this.rotZ.length);

		System.arraycopy(other.scaleX, 0, this.scaleX, 0, this.scaleX.length);
		System.arraycopy(other.scaleY, 0, this.scaleY, 0, this.scaleY.length);
		System.arraycopy(other.scaleZ, 0, this.scaleZ, 0, this.scaleZ.length);

		System.arraycopy(other.pivotDeltaX, 0, this.pivotDeltaX, 0, this.pivotDeltaX.length);
		System.arraycopy(other.pivotDeltaY, 0, this.pivotDeltaY, 0, this.pivotDeltaY.length);
		System.arraycopy(other.pivotDeltaZ, 0, this.pivotDeltaZ, 0, this.pivotDeltaZ.length);

		System.arraycopy(other.hidden, 0, this.hidden, 0, this.hidden.length);
		System.arraycopy(other.childrenHidden, 0, this.childrenHidden, 0, this.childrenHidden.length);
	}

	/**
	 * Interpolates {@code previous} toward {@code current} by {@code partialTick} and writes the
	 * result into {@code dest} - the pose pipeline's core operation: "quaternion slerp for rotation, linear interpolation for
	 * translation and scale"
	 * <p>
	 * Rotation is interpolated as a quaternion rather than lerping the three Euler floats directly,
	 * so a bone that spins past a wrap boundary (e.g. rotZ going from just under 2π to just over 0)
	 * blends through the short way round instead of snapping backwards. The quaternion is built and
	 * decomposed with the same Z-then-Y-then-X convention {@link com.sandymandy.strut.util.RenderUtil#optionalRotateZYX}
	 * applies at render time, so the result means the same thing when it's read back into
	 * {@code rotX}/{@code rotY}/{@code rotZ}. Visibility bits are not interpolated - a bone is
	 * either shown or hidden, and this takes {@code current}'s value so a visibility change takes
	 * effect on the same update that caused it, not fading in over the blend
	 * <p>
	 * Never called across poses of different {@link #boneCount()}
	 */
	public static void interpolate(Pose previous, Pose current, float partialTick, Pose dest) {
		int boneCount = current.boneCount();
		Quaternionf blended = new Quaternionf();
		Quaternionf target = new Quaternionf();
		Vector3f euler = new Vector3f();

		for (int i = 0; i < boneCount; i++) {
			dest.posX[i] = lerp(partialTick, previous.posX[i], current.posX[i]);
			dest.posY[i] = lerp(partialTick, previous.posY[i], current.posY[i]);
			dest.posZ[i] = lerp(partialTick, previous.posZ[i], current.posZ[i]);

			dest.scaleX[i] = lerp(partialTick, previous.scaleX[i], current.scaleX[i]);
			dest.scaleY[i] = lerp(partialTick, previous.scaleY[i], current.scaleY[i]);
			dest.scaleZ[i] = lerp(partialTick, previous.scaleZ[i], current.scaleZ[i]);

			dest.pivotDeltaX[i] = lerp(partialTick, previous.pivotDeltaX[i], current.pivotDeltaX[i]);
			dest.pivotDeltaY[i] = lerp(partialTick, previous.pivotDeltaY[i], current.pivotDeltaY[i]);
			dest.pivotDeltaZ[i] = lerp(partialTick, previous.pivotDeltaZ[i], current.pivotDeltaZ[i]);

			float prevRotX = previous.rotX[i];
			float prevRotY = previous.rotY[i];
			float prevRotZ = previous.rotZ[i];
			float currRotX = current.rotX[i];
			float currRotY = current.rotY[i];
			float currRotZ = current.rotZ[i];

			/*
			 * A bone whose rotation did not change since the last update needs no interpolation at
			 * all - the Euler -> quaternion -> slerp -> Euler round trip below always returns the
			 * same value when previous equals current, so skipping it is a pure win, not an
			 * approximation. On a model with hundreds of bones where a clip only ever animates a
			 * handful, this is the overwhelming majority of bones on the overwhelming majority of
			 * frames
			 */
			if (prevRotX == currRotX && prevRotY == currRotY && prevRotZ == currRotZ) {
				dest.rotX[i] = currRotX;
				dest.rotY[i] = currRotY;
				dest.rotZ[i] = currRotZ;

				continue;
			}

			blended.rotationZYX(prevRotZ, prevRotY, prevRotX);
			target.rotationZYX(currRotZ, currRotY, currRotX);
			blended.slerp(target, partialTick);
			eulerAnglesZYX(blended, euler);

			dest.rotX[i] = euler.x;
			dest.rotY[i] = euler.y;
			dest.rotZ[i] = euler.z;
		}

		System.arraycopy(current.hidden, 0, dest.hidden, 0, current.hidden.length);
		System.arraycopy(current.childrenHidden, 0, dest.childrenHidden, 0, current.childrenHidden.length);
	}

	/**
	 * How close the Y angle's sine may get to ±1 before the X/Z pair is treated as degenerate
	 * <p>
	 * Sized for the float precision this runs at rather than tuned: at a sine this close to ±1 the
	 * ordinary branch's two {@code atan2} arguments have both collapsed to within rounding noise of
	 * zero, so the angle it returns is decided by that noise
	 */
	private static final float GIMBAL_LOCK_SINE = 0.999995f;

	/**
	 * Decomposes {@code q} into the Z-then-Y-then-X Euler angles that would reproduce it via
	 * {@link Quaternionf#rotationZYX}, writing the result into {@code dest}
	 * <p>
	 * Hand-rolled rather than {@link Quaternionf#getEulerAnglesZYX} because that method (at least as
	 * of JOML 1.10.5, the version resolved transitively here) does not actually invert
	 * {@code rotationZYX}: its X-angle formula has a sign error on the {@code y*y} term
	 * ({@code 0.5 - x*x + y*y} where {@code rotationZYX}'s own construction requires
	 * {@code 0.5 - x*x - y*y}), verified by round-tripping arbitrary angles through both and getting
	 * a materially different quaternion back out - not a gimbal-lock edge case. Y and Z decompose
	 * correctly; only this method's X differs from JOML's
	 *
	 * <h2>Gimbal lock</h2>
	 *
	 * <p>At {@code Y = ±90°} the X and Z axes coincide and the ordinary formulas fail outright, not
	 * merely ambiguously: both arguments of the X and Z {@code atan2} calls go to zero, so each
	 * returns whatever the rounding noise decides. Live-verified against a real rig - an arm
	 * animated to exactly {@code Y = 90°} decomposed to {@code [0°, 89.97°, 0°]}, silently dropping
	 * a 15.5° Z rotation and reproducing a materially different orientation, and the frames either
	 * side of it decomposed to wildly different triples. On screen that is a bone snapping to a
	 * wrong pose for the moment it passes through 90°.
	 *
	 * <p>In that configuration only the <i>combination</i> of X and Z is determined:
	 * {@code Ry(90°)·Rx(a) = Rz(-a)·Ry(90°)}, so {@code q = Rz(z)·Ry(90°)·Rx(x)} collapses to
	 * {@code Rz(z - x)·Ry(90°)} (and to {@code Rz(z + x)·Ry(-90°)} at the other pole). Folding the
	 * whole rotation into Z and pinning X to zero is therefore the one choice that reproduces
	 * {@code q} exactly, and it stays continuous with the frames either side of the pole. Both
	 * poles reduce to the same closed form for that angle, {@code 2·atan2(z, w)}
	 */
	private static void eulerAnglesZYX(Quaternionf q, Vector3f dest) {
		float x = q.x, y = q.y, z = q.z, w = q.w;
		float sineY = Math.max(-1f, Math.min(1f, -2.0f * (x * z - w * y)));

		if (Math.abs(sineY) >= GIMBAL_LOCK_SINE) {
			dest.x = 0;
			dest.y = (float)Math.copySign(Math.PI / 2, sineY);
			dest.z = (float)(2 * Math.atan2(z, w));

			return;
		}

		dest.x = (float)Math.atan2(y * z + w * x, 0.5f - x * x - y * y);
		dest.y = (float)Math.asin(sineY);
		dest.z = (float)Math.atan2(x * y + w * z, 0.5f - y * y - z * z);
	}

	private static float lerp(float t, float a, float b) {
		return a + (b - a) * t;
	}

	private static int bitSetLength(int boneCount) {
		return (boneCount + 63) / 64;
	}

	private static void setBit(long[] bits, int index, boolean value) {
		int word = index >> 6;
		long mask = 1L << (index & 63);

		if (value) {
			bits[word] |= mask;
		}
		else {
			bits[word] &= ~mask;
		}
	}

	private static boolean getBit(long[] bits, int index) {
		return (bits[index >> 6] & (1L << (index & 63))) != 0;
	}
}
