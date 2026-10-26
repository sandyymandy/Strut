package com.sandymandy.strut.cache.object;

import com.sandymandy.strut.animation.state.ArmaturePose;
import com.sandymandy.strut.util.RenderUtil;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import java.util.List;

/**
 * A weighted skeleton that deforms {@link StrutMesh meshes}
 * <p>
 * Where a {@link GroupBoneDefinition group bone} moves its geometry rigidly, an armature bone pulls on
 * individual vertices in proportion to their weight, so geometry spanning a joint bends instead
 * of tearing. A model may carry both: group bones for the parts that swing, an armature where
 * something has to deform
 *
 * <h2>The skinning matrices</h2>
 *
 * <p>For each bone, {@code skin = poseModel · inverseBind}, where {@code poseModel} accumulates
 * down the hierarchy as {@code parentPoseModel · bindLocal · animationDelta}. A bone with no
 * animation on it contributes its {@code bindLocal} alone, so its {@code poseModel} equals its
 * bind transform and {@code skin} comes out as the identity - an unanimated armature leaves its
 * mesh exactly as authored, which is what makes the rest pose the natural default rather than a
 * special case.
 */
public record StrutArmature(String name, List<ArmatureBoneDefinition> bones, String parentBoneName) {
    public StrutArmature(String name, List<ArmatureBoneDefinition> bones, @Nullable String parentBoneName) {
        this.name = name;
        this.bones = bones;
        this.parentBoneName = parentBoneName;
    }


    /**
     * The group bone this armature hangs off, or null if it is a root
     */
    @Override
    @Nullable
    public String parentBoneName() {
        return this.parentBoneName;
    }

    /**
     * How many bones this armature has, and so how long a caller's matrix buffers must be
     */
    public int boneCount() {
        return this.bones.size();
    }

    /**
     * Write this armature's skinning matrices, for its bones' current transforms in {@code pose},
     * into the caller's buffers
     * <p>
     * One walk of the hierarchy per frame, not per mesh and certainly not per vertex. Bones are
     * stored parent-before-child, so a single forward pass is enough to accumulate down the
     * hierarchy without recursion or revisiting
     * <p>
     * Both the pose and the output buffers belong to the animatable instance being posed, never to
     * the armature. An armature is baked once and shared by every animatable using the model, so
     * reading transforms off it - or holding the results here - would mean two entities of the
     * same type overwriting each other's pose within a single frame
     *
     * @param pose       The posing instance's own {@link ArmaturePose}, indexed identically to
     *                   {@link #bones ()}
     * @param poseBuffer Scratch for the accumulated model-space pose, {@link #boneCount()} long
     * @param skinBuffer Receives {@code poseModel · inverseBind} per bone, {@link #boneCount()} long
     */
    public void computeSkinMatrices(ArmaturePose pose, Matrix4f[] poseBuffer, Matrix4f[] skinBuffer) {
        for (int i = 0; i < this.bones.size(); i++) {
            ArmatureBoneDefinition bone = this.bones.get(i);
            Matrix4f poseMatrix = poseBuffer[i];

            if (bone.getParent() instanceof ArmatureBoneDefinition parent) {
                poseMatrix.set(poseBuffer[parent.getIndex()]);
                poseMatrix.mul(bone.getBindLocal());
            } else {
                poseMatrix.set(bone.getBindLocal());
            }

            applyAnimationDelta(pose, bone.getIndex(), poseMatrix);

            skinBuffer[i].set(poseMatrix).mul(bone.getInverseBind());
        }
    }

    /**
     * Apply a bone's animated offset within its own rest frame
     * <p>
     * Ordered and signed to match {@link RenderUtil#prepMatrixForBone},
     * X negated and rotations applied Z then Y then X, so that a keyframe means the same thing on
     * an armature bone as it does on a group bone. An animation that reads correctly on one and
     * mirrored on the other would be far harder to diagnose than it is to prevent
     */
    private static void applyAnimationDelta(ArmaturePose pose, int index, Matrix4f poseMatrix) {
        float posX = pose.getPosX(index);
        float posY = pose.getPosY(index);
        float posZ = pose.getPosZ(index);

        if (posX != 0 || posY != 0 || posZ != 0)
            poseMatrix.translate(-posX / 16f, posY / 16f, posZ / 16f);

        float rotZ = pose.getRotZ(index);

        if (rotZ != 0)
            poseMatrix.rotateZ(rotZ);

        float rotY = pose.getRotY(index);

        if (rotY != 0)
            poseMatrix.rotateY(rotY);

        float rotX = pose.getRotX(index);

        if (rotX != 0)
            poseMatrix.rotateX(rotX);

        float scaleX = pose.getScaleX(index);
        float scaleY = pose.getScaleY(index);
        float scaleZ = pose.getScaleZ(index);

        if (scaleX != 1 || scaleY != 1 || scaleZ != 1)
            poseMatrix.scale(scaleX, scaleY, scaleZ);
    }

    @Nullable
    public ArmatureBoneDefinition getBone(String name) {
        for (ArmatureBoneDefinition bone : this.bones) {
            if (bone.getName().equals(name))
                return bone;
        }

        return null;
    }
}
