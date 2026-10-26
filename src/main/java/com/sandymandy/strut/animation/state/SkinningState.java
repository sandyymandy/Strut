package com.sandymandy.strut.animation.state;

import it.unimi.dsi.fastutil.objects.Reference2ObjectMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import com.sandymandy.strut.cache.object.BakedStrutModel;
import com.sandymandy.strut.cache.object.StrutArmature;
import com.sandymandy.strut.cache.object.StrutSkin;

/**
 * One animatable instance's skinning buffers
 * <p>
 * A {@link BakedStrutModel} is loaded once and shared by every animatable using it, so anything
 * written while posing must not live on it. Bone matrices and deformed vertex positions are
 * written per instance per frame, which makes them the instance's property, not the model's.
 * Held here, two entities sharing a model can be posed differently without either seeing the
 * other's vertices - if these arrays lived on the shared {@link StrutArmature} and {@link StrutSkin},
 * the second entity rendered in a frame would overwrite the first, and the first would be drawn
 * with whatever the second was doing
 *
 * <h2>Lifetime</h2>
 *
 * <p>Owned by the instance's {@link com.sandymandy.strut.animation.AnimatableManager}, so it is
 * created with the instance and released with it. Buffers are allocated on first use and reused
 * every frame after - the point of keeping them is to avoid allocating per frame, not to avoid
 * allocating at all
 *
 * <h2>Rebinding</h2>
 *
 * <p>Buffers are keyed by object identity on the armature or skin they belong to, so a resource
 * reload - which replaces every baked object - is detected by the model itself changing identity,
 * and every buffer is dropped. Nothing from the previous model can be read back through a stale
 * key, because the keys themselves are gone
 *
 * <p>This is one piece of the per-instance ownership held by {@code ModelInstance}.
 * {@link Pose} and {@link com.sandymandy.strut.cache.object.GroupBoneHandle} are per-instance too, the same way this
 * class makes skinning per-instance
 */
public final class SkinningState {
	private final Reference2ObjectMap<StrutArmature, Matrix4f[]> poseMatrices = new Reference2ObjectOpenHashMap<>();
	private final Reference2ObjectMap<StrutArmature, Matrix4f[]> skinMatrices = new Reference2ObjectOpenHashMap<>();
	private final Reference2ObjectMap<StrutSkin, Vector3f[]> skinnedVertices = new Reference2ObjectOpenHashMap<>();
	private final Reference2ObjectMap<StrutSkin, Vector3f[]> skinnedNormals = new Reference2ObjectOpenHashMap<>();

	@Nullable
	private BakedStrutModel boundModel = null;

	/**
	 * Pose every armature in the model into this instance's own matrices, reading each armature's
	 * animated bone transforms from {@code modelInstance}'s own {@link ArmaturePose}
	 * <p>
	 * Done once per frame for the whole model rather than once per mesh. Each bone's matrix is
	 * needed by every vertex weighted to it, and several meshes may share one armature, so
	 * accumulating the hierarchy per mesh - let alone per vertex - would repeat the same walk
	 * hundreds of times a frame for no change in result
	 * <p>
	 * {@code modelInstance} is passed in rather than held here because it is the same instance's
	 * {@link ModelInstance}, reached through the same {@link com.sandymandy.strut.animation.AnimatableManager} -
	 * one instance, one source of pose truth, whether the consumer is a group bone or an armature
	 * one
	 */
	public void poseArmatures(BakedStrutModel model, ModelInstance modelInstance) {
		rebindIfNeeded(model);

		for (StrutArmature armature : model.armatures()) {
			armature.computeSkinMatrices(modelInstance.armaturePoseFor(model, armature),
					matricesFor(this.poseMatrices, armature, armature.boneCount()),
					matricesFor(this.skinMatrices, armature, armature.boneCount()));
		}
	}

	/**
	 * This instance's skinning matrices for the given armature, as {@link #poseArmatures} last
	 * left them
	 */
	public Matrix4f[] skinMatricesFor(StrutArmature armature) {
		return matricesFor(this.skinMatrices, armature, armature.boneCount());
	}

	/**
	 * This instance's deformed vertex buffer for the given skin
	 * <p>
	 * Sized by unique source vertex, not by poly corner - triangulation duplicates a vertex across
	 * every face sharing it, and each one is skinned exactly once
	 */
	public Vector3f[] skinnedVerticesFor(StrutSkin skin) {
		return bufferFor(this.skinnedVertices, skin);
	}

	/**
	 * This instance's deformed normal buffer for the given skin, used only by a smooth-shaded
	 * skinned mesh - a flat one recomputes its normals from the deformed corners every frame and
	 * never touches this buffer
	 * <p>
	 * Sized and indexed the same way as {@link #skinnedVerticesFor}, for the same reason
	 */
	public Vector3f[] skinnedNormalsFor(StrutSkin skin) {
		return bufferFor(this.skinnedNormals, skin);
	}

	private static Vector3f[] bufferFor(Reference2ObjectMap<StrutSkin, Vector3f[]> store, StrutSkin skin) {
		Vector3f[] buffer = store.get(skin);

		if (buffer == null) {
			buffer = new Vector3f[skin.vertexCount()];

			for (int i = 0; i < buffer.length; i++) {
				buffer[i] = new Vector3f();
			}

			store.put(skin, buffer);
		}

		return buffer;
	}

	/**
	 * Drop every buffer if the model this state was built against is no longer the one being
	 * rendered
	 * <p>
	 * Happens on a resource reload, and on any renderer that swaps model by animatable state. The
	 * check is on identity rather than resource path because a reload produces a new baked object
	 * at the same path, and it is the object the buffers are keyed against
	 */
	private void rebindIfNeeded(BakedStrutModel model) {
		if (this.boundModel == model)
			return;

		this.poseMatrices.clear();
		this.skinMatrices.clear();
		this.skinnedVertices.clear();
		this.skinnedNormals.clear();
		this.boundModel = model;
	}

	private static Matrix4f[] matricesFor(Reference2ObjectMap<StrutArmature, Matrix4f[]> store, StrutArmature armature, int count) {
		Matrix4f[] matrices = store.get(armature);

		if (matrices == null) {
			matrices = new Matrix4f[count];

			for (int i = 0; i < count; i++) {
				matrices[i] = new Matrix4f();
			}

			store.put(armature, matrices);
		}

		return matrices;
	}

}
