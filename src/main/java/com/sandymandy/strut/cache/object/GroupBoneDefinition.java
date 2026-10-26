package com.sandymandy.strut.cache.object;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.jetbrains.annotations.Nullable;
import com.sandymandy.strut.animation.state.BoneSnapshot;

import java.util.List;
import java.util.Objects;

/**
 * The immutable, authored half of a group bone: geometry, hierarchy and bake-time metadata
 * <p>
 * One {@code GroupBoneDefinition} tree is baked per {@link BakedStrutModel} and shared by every
 * animatable instance rendering that model - it never changes after bake, which is exactly what
 * lets it be shared safely. Anything that changes per instance (position, rotation, scale,
 * visibility, the tracked render matrices) lives instead on {@link GroupBoneHandle}, this bone's
 * per-instance counterpart, one of which exists per {@link com.sandymandy.strut.animation.state.ModelInstance}
 *
 * @see GroupBoneHandle
 */
public class GroupBoneDefinition {
	private final GroupBoneDefinition parent;
	private final String name;

	private final List<GroupBoneDefinition> children = new ObjectArrayList<>();
	private final List<StrutCube> cubes = new ObjectArrayList<>();
	private final List<StrutMesh> meshes = new ObjectArrayList<>();

	private final Boolean mirror;
	private final Double inflate;
	private final Boolean dontRender;
	private final Boolean reset;

	private BoneSnapshot initialSnapshot;

	private float pivotX;
	private float pivotY;
	private float pivotZ;

	/**
	 * This bone's authored rest rotation
	 * <p>
	 * Set once at bake time via {@link #bakeInitialRotation}, immutable afterwards. This is the
	 * pose a {@link GroupBoneHandle} starts at before any animation runs, and the pose the animation
	 * reset logic lerps back toward
	 */
	private float initialRotX;
	private float initialRotY;
	private float initialRotZ;

	/**
	 * This bone's position in {@link BakedStrutModel#flatBones()}, a parent-before-child flat
	 * ordering of the whole group-bone tree
	 * <p>
	 * Assigned once, when the owning {@link BakedStrutModel} is constructed - never by the bone
	 * itself, since a bone has no notion of its position in the tree until the tree is complete.
	 * {@code -1} until then. This is what lets a bone be addressed by index rather than by name,
	 * which per-instance pose storage needs
	 */
	private int index = -1;

	public GroupBoneDefinition(@Nullable GroupBoneDefinition parent, String name, Boolean mirror, @Nullable Double inflate, @Nullable Boolean dontRender, @Nullable Boolean reset) {
		this.parent = parent;
		this.name = name;
		this.mirror = mirror;
		this.inflate = inflate;
		this.dontRender = dontRender;
		this.reset = reset;
	}

	public String getName() {
		return this.name;
	}

	public GroupBoneDefinition getParent() {
		return this.parent;
	}

	/**
	 * This bone's position in its model's flat, parent-before-child bone ordering
	 *
	 * @see BakedStrutModel#flatBones()
	 */
	public int getIndex() {
		return this.index;
	}

	/**
	 * Package-private: only {@link BakedStrutModel} assigns this, once, while flattening the tree
	 * it was just handed
	 */
	void setIndex(int index) {
		this.index = index;
	}

	/**
	 * Set this bone's authored pivot
	 * <p>
	 * Bake-time only - called once, immediately after construction, by whichever
	 * {@link com.sandymandy.strut.loading.object.BakedModelFactory} or binary baker built this
	 * bone. Nothing after bake should call this; a per-instance pivot adjustment (as
	 * {@link com.sandymandy.strut.renderer.StrutArmorRenderer} needs, to align a bone with a
	 * vanilla {@link net.minecraft.client.model.ModelPart}) is a delta stored on
	 * {@link com.sandymandy.strut.animation.state.Pose}, not a redefinition of this
	 */
	public void bakePivot(float pivotX, float pivotY, float pivotZ) {
		this.pivotX = pivotX;
		this.pivotY = pivotY;
		this.pivotZ = pivotZ;
	}

	/**
	 * Set this bone's authored rest rotation
	 * <p>
	 * Bake-time only - see {@link #bakePivot}
	 */
	public void bakeInitialRotation(float rotX, float rotY, float rotZ) {
		this.initialRotX = rotX;
		this.initialRotY = rotY;
		this.initialRotZ = rotZ;
	}

	public float getPivotX() {
		return this.pivotX;
	}

	public float getPivotY() {
		return this.pivotY;
	}

	public float getPivotZ() {
		return this.pivotZ;
	}

	public float getInitialRotX() {
		return this.initialRotX;
	}

	public float getInitialRotY() {
		return this.initialRotY;
	}

	public float getInitialRotZ() {
		return this.initialRotZ;
	}

	public BoneSnapshot getInitialSnapshot() {
		return this.initialSnapshot;
	}

	public List<GroupBoneDefinition> getChildBones() {
		return this.children;
	}

	public void saveInitialSnapshot() {
		if (this.initialSnapshot == null)
			this.initialSnapshot = new BoneSnapshot(this.name, this.initialRotX, this.initialRotY, this.initialRotZ,
					0, 0, 0, 1, 1, 1);
	}

	public Boolean getMirror() {
		return this.mirror;
	}

	public Double getInflate() {
		return this.inflate;
	}

	public Boolean shouldNeverRender() {
		return this.dontRender;
	}

	public Boolean getReset() {
		return this.reset;
	}

	/**
	 * Get the {@link StrutMesh meshes} belonging to this bone
	 * <p>
	 * Free-form geometry, rendered alongside this bone's {@link #getCubes() cubes} and under the
	 * same bone transform. A model may hold either, or both
	 */
	public List<StrutMesh> getMeshes() {
		return this.meshes;
	}

	public List<StrutCube> getCubes() {
		return this.cubes;
	}

	public boolean equals(Object obj) {
		if (this == obj)
			return true;

		if (obj == null || getClass() != obj.getClass())
			return false;

		return hashCode() == obj.hashCode();
	}

	public int hashCode() {
		return Objects.hash(getName(), (getParent() != null ? getParent().getName() : 0), getCubes().size(), getChildBones().size());
	}
}
