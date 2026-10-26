package com.sandymandy.strut.animation;

import com.sandymandy.strut.cache.object.PosableBone;
import org.jetbrains.annotations.ApiStatus;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * What a controller last gave each bone, for a controller that blends out when it stops ({@link AnimationController#blendOut(boolean)}): the pose it fades
 * from. Written by the {@link AnimationProcessor} as it applies the controller's points, and reused frame to frame without allocating
 */
@ApiStatus.Internal
public final class ControllerOutput {
	/** One bone's last values from the controller, for the channels it set */
	public static final class Bone {
		final PosableBone bone;
		final float[] rotation = new float[3];
		final float[] offset = new float[3];
		final float[] scale = new float[3];
		boolean rotated;
		boolean moved;
		boolean scaled;

		Bone(PosableBone bone) {
			this.bone = bone;
		}
	}

	private final Map<BoneKey, Bone> bones = new HashMap<>();

	/** Forgets what was recorded: called before a frame's values are recorded, and when a blend out has finished */
	void clear() {
		for (Bone bone : this.bones.values())
			bone.rotated = bone.moved = bone.scaled = false;
	}

	/** Whether anything is recorded to blend out from */
	boolean isEmpty() {
		for (Bone bone : this.bones.values())
			if (bone.rotated || bone.moved || bone.scaled)
				return false;

		return true;
	}

	private Bone of(BoneKey key, PosableBone bone) {
		return this.bones.computeIfAbsent(key, k -> new Bone(bone));
	}

	void rotation(BoneKey key, PosableBone bone, float x, float y, float z) {
		Bone out = of(key, bone);

		out.rotation[0] = x;
		out.rotation[1] = y;
		out.rotation[2] = z;
		out.rotated = true;
	}

	void offset(BoneKey key, PosableBone bone, float x, float y, float z) {
		Bone out = of(key, bone);

		out.offset[0] = x;
		out.offset[1] = y;
		out.offset[2] = z;
		out.moved = true;
	}

	void scale(BoneKey key, PosableBone bone, float x, float y, float z) {
		Bone out = of(key, bone);

		out.scale[0] = x;
		out.scale[1] = y;
		out.scale[2] = z;
		out.scaled = true;
	}

	Collection<Map.Entry<BoneKey, Bone>> entries() {
		return this.bones.entrySet();
	}
}
