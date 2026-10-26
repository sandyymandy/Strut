package com.sandymandy.strut.physics.solver;

import org.joml.Vector3f;

import java.util.List;
import java.util.Map;

/**
 * A model's ragdoll: which bones make the skeleton, and how it behaves. Each bone becomes a joint at its pivot; the
 * parent of a bone is the nearest bone above it in this list
 *
 * @param bones The skeleton, any order; a bone the model lacks is reported and skipped
 * @param radius The size of each joint for collision with the ground and with itself, blocks
 * @param gravity Blocks per second squared
 * @param damping The share of its velocity a joint loses each second, 0 to 1
 * @param friction The share of its motion a joint in contact loses each step, 0 to 1
 * @param minJointAngle The smallest angle a joint may close to, radians (a knee cannot fold flat)
 * @param parents Joints that hang from a different bone than the model's own: the legs of a model whose hierarchy puts them beside the torso hang from the
 *                pelvis here (bone name to the bone it hangs from). The rotations are still relative to the model's own parents
 * @param tips Where the end of a bone with no child bone is, in the bone's own space (blocks); by default the far corner of the bone's geometry
 */
public record RagdollParams(List<String> bones, float radius, float gravity, float damping, float friction, float minJointAngle, Map<String, String> parents, Map<String, Vector3f> tips) {
	public RagdollParams {
		bones = List.copyOf(bones);
		parents = Map.copyOf(parents);
		tips = Map.copyOf(tips);
	}
}
