package com.sandymandy.strut.cache.object;

import com.sandymandy.strut.animation.state.PosePalette;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector4f;
import com.sandymandy.strut.animation.state.BoneSnapshot;
import com.sandymandy.strut.animation.state.BoneVisuals;
import com.sandymandy.strut.animation.state.ModelInstance;
import com.sandymandy.strut.animation.state.Pose;

import java.util.List;

/**
 * A per-instance, mutable handle onto a {@link GroupBoneDefinition}
 * <p>
 * This is the object animations, renderers and consumer code actually read and write - every
 * accessor here delegates to the owning {@link ModelInstance}'s current {@link Pose}, addressed
 * by this bone's flat {@link #getIndex() index}, so two instances of the same model never share a
 * transform. One handle is allocated per bone per instance, eagerly, alongside the instance's
 * {@link Pose} - see {@link ModelInstance}
 * <p>
 * Immutable/authored data (name, hierarchy, cubes, meshes, the rest pivot) is not duplicated here;
 * it stays on {@link #getDefinition()}
 */
public class GroupBoneHandle implements PosableBone {
	private final GroupBoneDefinition definition;
	private final ModelInstance instance;
	@Nullable
	private final GroupBoneHandle parent;
	private final List<GroupBoneHandle> children = new ObjectArrayList<>();
	private final int index;

	private final Matrix4f modelSpaceMatrix = new Matrix4f();
	private final Matrix4f localSpaceMatrix = new Matrix4f();
	private final Matrix4f worldSpaceMatrix = new Matrix4f();
	private Matrix3f worldSpaceNormal = new Matrix3f();

	private boolean trackingMatrices;

	/**
	 * Package-private in spirit, public only because {@link ModelInstance} - the sole legitimate
	 * caller - lives in a different package
	 * <p>
	 * Only ever called by {@link ModelInstance}, in flat (parent-before-child) index order, so
	 * {@code parent} is always already built by the time a child needs it. Consumer code should
	 * never call this directly - get a handle through {@link ModelInstance#handlesFor},
	 * {@link ModelInstance#topLevelHandlesFor} or {@link ModelInstance#handleFor}
	 */
	@ApiStatus.Internal
	public GroupBoneHandle(GroupBoneDefinition definition, ModelInstance instance, @Nullable GroupBoneHandle parent) {
		this.definition = definition;
		this.instance = instance;
		this.parent = parent;
		this.index = definition.getIndex();

		this.worldSpaceNormal.identity();
		this.worldSpaceMatrix.identity();
		this.localSpaceMatrix.identity();
		this.modelSpaceMatrix.identity();
	}

	/**
	 * Package-private in spirit, public only for the same cross-package reason as the constructor
	 * above. Called once by {@link ModelInstance} while building the handle tree, right after this
	 * handle's parent (if any) is known to already exist
	 */
	@ApiStatus.Internal
	public void addChild(GroupBoneHandle child) {
		this.children.add(child);
	}

	/**
	 * The immutable, shared geometry and hierarchy this handle poses
	 */
	public GroupBoneDefinition getDefinition() {
		return this.definition;
	}

	public String getName() {
		return this.definition.getName();
	}

	public GroupBoneHandle getParent() {
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

	private Pose pose() {
		return this.instance.currentPose();
	}

	public float getRotX() {
		return pose().getRotX(this.index);
	}

	public float getRotY() {
		return pose().getRotY(this.index);
	}

	public float getRotZ() {
		return pose().getRotZ(this.index);
	}

	public float getPosX() {
		return pose().getPosX(this.index);
	}

	public float getPosY() {
		return pose().getPosY(this.index);
	}

	public float getPosZ() {
		return pose().getPosZ(this.index);
	}

	public float getScaleX() {
		return pose().getScaleX(this.index);
	}

	public float getScaleY() {
		return pose().getScaleY(this.index);
	}

	public float getScaleZ() {
		return pose().getScaleZ(this.index);
	}

	public void setRotX(float value) {
		Pose pose = pose();

		pose.setRotation(this.index, value, pose.getRotY(this.index), pose.getRotZ(this.index));
	}

	public void setRotY(float value) {
		Pose pose = pose();

		pose.setRotation(this.index, pose.getRotX(this.index), value, pose.getRotZ(this.index));
	}

	public void setRotZ(float value) {
		Pose pose = pose();

		pose.setRotation(this.index, pose.getRotX(this.index), pose.getRotY(this.index), value);
	}

	public void updateRotation(float xRot, float yRot, float zRot) {
		pose().setRotation(this.index, xRot, yRot, zRot);
	}

	public void setPosX(float value) {
		Pose pose = pose();

		pose.setPosition(this.index, value, pose.getPosY(this.index), pose.getPosZ(this.index));
	}

	public void setPosY(float value) {
		Pose pose = pose();

		pose.setPosition(this.index, pose.getPosX(this.index), value, pose.getPosZ(this.index));
	}

	public void setPosZ(float value) {
		Pose pose = pose();

		pose.setPosition(this.index, pose.getPosX(this.index), pose.getPosY(this.index), value);
	}

	public void updatePosition(float posX, float posY, float posZ) {
		pose().setPosition(this.index, posX, posY, posZ);
	}

	public void setScaleX(float value) {
		Pose pose = pose();

		pose.setScale(this.index, value, pose.getScaleY(this.index), pose.getScaleZ(this.index));
	}

	public void setScaleY(float value) {
		Pose pose = pose();

		pose.setScale(this.index, pose.getScaleX(this.index), value, pose.getScaleZ(this.index));
	}

	public void setScaleZ(float value) {
		Pose pose = pose();

		pose.setScale(this.index, pose.getScaleX(this.index), pose.getScaleY(this.index), value);
	}

	public void updateScale(float scaleX, float scaleY, float scaleZ) {
		pose().setScale(this.index, scaleX, scaleY, scaleZ);
	}

	public boolean isHidden() {
		return pose().isHidden(this.index);
	}

	public void setHidden(boolean hidden) {
		pose().setHidden(this.index, hidden);
		setChildrenHidden(hidden);
	}

	public void setChildrenHidden(boolean hideChildren) {
		pose().setChildrenHidden(this.index, hideChildren);
	}

	public boolean isHidingChildren() {
		return pose().isChildrenHidden(this.index);
	}

	/**
	 * Whether this bone's own geometry should be skipped <em>this render</em> - what
	 * {@code StrutRenderer#renderCubesOfBone}/{@code renderMeshesOfBone} actually call, not
	 * {@link #isHidden()}
	 * <p>
	 * Reads {@link ModelInstance#effectivePose()} rather than {@link #pose() the actively-being-written
	 * current pose} {@link #isHidden()} reads - the same {@code current} vs. {@code rendered}
	 * distinction {@link #getRenderRotX()} and its siblings already draw for rotation/position/scale,
	 * extended to visibility. Fixes a real bug: the old CPU walk checked {@link #isHidden()}
	 * (current pose) while {@code GpuBatchQueue} checked {@code renderedPose().isHidden()} directly,
	 * so a visibility change set outside an animation update landed on the GPU path a full tick later
	 * than on the CPU path. Both now read the exact same {@link ModelInstance#effectivePose()} result,
	 * which is also where an active {@code HIDDEN} bone override
	 * gets layered in
	 */
	public boolean isHiddenForRender() {
		return this.instance.effectivePose().isHidden(this.index);
	}

	/**
	 * The render-time twin of {@link #isHidingChildren()} - see {@link #isHiddenForRender()}'s own
	 * doc comment for why this reads {@link ModelInstance#effectivePose()} instead of the current pose
	 */
	public boolean isHidingChildrenForRender() {
		return this.instance.effectivePose().isChildrenHidden(this.index);
	}

	/**
	 * This bone's resolved {@code COLOR} override, packed ARGB matching
	 * {@link com.sandymandy.strut.util.Color} - opaque white ({@code 0xFFFFFFFF}, a no-op multiply)
	 * when {@link ModelInstance#boneVisuals()} is {@code null} or has nothing set for this bone.
	 * {@code StrutRenderer#renderCubesOfBone}/{@code renderMeshesOfBone} fold this into the colour
	 * every cube/mesh under this bone is submitted with
	 */
	public int getColorTint() {
		BoneVisuals visuals = this.instance.boneVisuals();

		return visuals != null ? visuals.getColor(this.index) : 0xFFFFFFFF;
	}

	/**
	 * This bone's resolved {@code UV_OFFSET} U component, {@code 0} when unset - see
	 * {@link #getColorTint()}'s own doc comment for the same null-instance convention
	 */
	public float getUOffset() {
		BoneVisuals visuals = this.instance.boneVisuals();

		return visuals != null ? visuals.getUOffset(this.index) : 0f;
	}

	/** This bone's resolved {@code UV_OFFSET} V component, {@code 0} when unset - see {@link #getColorTint()} */
	public float getVOffset() {
		BoneVisuals visuals = this.instance.boneVisuals();

		return visuals != null ? visuals.getVOffset(this.index) : 0f;
	}

	/** This bone's resolved {@code UV_SIZE} scale, {@code 1} when unset: what its UVs are multiplied by before the offset */
	public float getUScale() {
		BoneVisuals visuals = this.instance.boneVisuals();

		return visuals != null ? visuals.getUScale(this.index) : 1f;
	}

	public float getVScale() {
		BoneVisuals visuals = this.instance.boneVisuals();

		return visuals != null ? visuals.getVScale(this.index) : 1f;
	}

	/**
	 * This bone's effective pivot: the authored pivot from {@link #getDefinition()}, plus this
	 * instance's own adjustment
	 * <p>
	 * The adjustment exists for {@link com.sandymandy.strut.renderer.StrutArmorRenderer}, which
	 * nudges bones to align with vanilla {@link net.minecraft.client.model.ModelPart} pivots per
	 * wearer - a delta, not a redefinition, so the authored geometry in
	 * {@link GroupBoneDefinition} never changes
	 */
	public float getPivotX() {
		return this.definition.getPivotX() + pose().getPivotDeltaX(this.index);
	}

	public float getPivotY() {
		return this.definition.getPivotY() + pose().getPivotDeltaY(this.index);
	}

	public float getPivotZ() {
		return this.definition.getPivotZ() + pose().getPivotDeltaZ(this.index);
	}

	public void setPivotX(float value) {
		pose().setPivotDeltaX(this.index, value - this.definition.getPivotX());
	}

	public void setPivotY(float value) {
		pose().setPivotDeltaY(this.index, value - this.definition.getPivotY());
	}

	public void setPivotZ(float value) {
		pose().setPivotDeltaZ(this.index, value - this.definition.getPivotZ());
	}

	public void updatePivot(float pivotX, float pivotY, float pivotZ) {
		setPivotX(pivotX);
		setPivotY(pivotY);
		setPivotZ(pivotZ);
	}

	private Pose renderPose() {
		return this.instance.renderedPose();
	}

	/**
	 * This bone's rotation, position, scale and pivot as smoothed for rendering, rather than the
	 * raw values {@link #getRotX()} and its siblings read from the instance's {@code current} pose
	 * <p>
	 * Backed by {@link ModelInstance#renderedPose()} - see its javadoc, and
	 * for why a render-time consumer should read these instead
	 * of the plain accessors above. {@link com.sandymandy.strut.util.RenderUtil}'s bone-driven
	 * matrix helpers are exactly that consumer; nothing else should need these
	 */
	public float getRenderRotX() {
		return renderPose().getRotX(this.index);
	}

	public float getRenderRotY() {
		return renderPose().getRotY(this.index);
	}

	public float getRenderRotZ() {
		return renderPose().getRotZ(this.index);
	}

	public float getRenderPosX() {
		return renderPose().getPosX(this.index);
	}

	public float getRenderPosY() {
		return renderPose().getPosY(this.index);
	}

	public float getRenderPosZ() {
		return renderPose().getPosZ(this.index);
	}

	public float getRenderScaleX() {
		return renderPose().getScaleX(this.index);
	}

	public float getRenderScaleY() {
		return renderPose().getScaleY(this.index);
	}

	public float getRenderScaleZ() {
		return renderPose().getScaleZ(this.index);
	}

	public float getRenderPivotX() {
		return this.definition.getPivotX() + renderPose().getPivotDeltaX(this.index);
	}

	public float getRenderPivotY() {
		return this.definition.getPivotY() + renderPose().getPivotDeltaY(this.index);
	}

	public float getRenderPivotZ() {
		return this.definition.getPivotZ() + renderPose().getPivotDeltaZ(this.index);
	}

	public BoneSnapshot getInitialSnapshot() {
		return this.definition.getInitialSnapshot();
	}

	public void saveInitialSnapshot() {
		this.definition.saveInitialSnapshot();
	}

	public List<GroupBoneHandle> getChildBones() {
		return this.children;
	}

	public Boolean getMirror() {
		return this.definition.getMirror();
	}

	public Double getInflate() {
		return this.definition.getInflate();
	}

	public Boolean shouldNeverRender() {
		return this.definition.shouldNeverRender();
	}

	public Boolean getReset() {
		return this.definition.getReset();
	}

	public List<StrutMesh> getMeshes() {
		return this.definition.getMeshes();
	}

	public List<StrutCube> getCubes() {
		return this.definition.getCubes();
	}

	public boolean isTrackingMatrices() {
		return this.trackingMatrices;
	}

	public void setTrackingMatrices(boolean trackingMatrices) {
		this.trackingMatrices = trackingMatrices;
	}

	public Matrix4f getModelSpaceMatrix() {
		// The first time anyone asks, the matrix is worked out from the pose now rather than left as it started (all zeros) until a renderer next draws the
		// model: a bone that is hidden, or whose entity is not drawn at all, has a position like any other
		if (!this.trackingMatrices) {
			setTrackingMatrices(true);
			computeModelSpaceNow();
		}

		return this.modelSpaceMatrix;
	}

	private void computeModelSpaceNow() {
		Pose rendered = this.instance.renderedPose();

		if (rendered == null)
			return;

		Matrix4f computed = new Matrix4f();

		PosePalette.computeBoneAtPivot(rendered, this.definition, computed);
		this.modelSpaceMatrix.set(computed);
	}

	public void setModelSpaceMatrix(Matrix4f matrix) {
		this.modelSpaceMatrix.set(matrix);
	}

	public Matrix4f getLocalSpaceMatrix() {
		setTrackingMatrices(true);

		return this.localSpaceMatrix;
	}

	public void setLocalSpaceMatrix(Matrix4f matrix) {
		this.localSpaceMatrix.set(matrix);
	}

	public Matrix4f getWorldSpaceMatrix() {
		setTrackingMatrices(true);

		return this.worldSpaceMatrix;
	}

	public void setWorldSpaceMatrix(Matrix4f matrix) {
		this.worldSpaceMatrix.set(matrix);
	}

	public void setWorldSpaceNormal(Matrix3f matrix) {
		this.worldSpaceNormal = matrix;
	}

	public Matrix3f getWorldSpaceNormal() {
		return this.worldSpaceNormal;
	}

	/**
	 * Get the position of the bone relative to its owner
	 */
	public Vector3d getLocalPosition() {
		Vector4f vec = getLocalSpaceMatrix().transform(new Vector4f(0, 0, 0, 1));

		return new Vector3d(vec.x(), vec.y(), vec.z());
	}

	/**
	 * Get the position of the bone relative to the model it belongs to
	 */
	public Vector3d getModelPosition() {
		Vector4f vec = getModelSpaceMatrix().transform(new Vector4f(0, 0, 0, 1));

		return new Vector3d(-vec.x() * 16f, vec.y() * 16f, vec.z() * 16f);
	}

	/**
	 * Get the position of the bone relative to the world
	 */
	public Vector3d getWorldPosition() {
		Vector4f vec = getWorldSpaceMatrix().transform(new Vector4f(0, 0, 0, 1));

		return new Vector3d(vec.x(), vec.y(), vec.z());
	}

	public void setModelPosition(Vector3d pos) {
		// Doesn't work on bones with parent transforms
		GroupBoneHandle parent = getParent();
		Matrix4f matrix = (parent == null ? new Matrix4f().identity() : new Matrix4f(parent.getModelSpaceMatrix())).invert();
		Vector4f vec = matrix.transform(new Vector4f(-(float)pos.x / 16f, (float)pos.y / 16f, (float)pos.z / 16f, 1));

		updatePosition(-vec.x() * 16f, vec.y() * 16f, vec.z() * 16f);
	}

	public Matrix4f getModelRotationMatrix() {
		Matrix4f matrix = new Matrix4f(getModelSpaceMatrix());
		matrix.m03(0);
		matrix.m13(0);
		matrix.m23(0);

		return matrix;
	}

	public Vector3d getPositionVector() {
		return new Vector3d(getPosX(), getPosY(), getPosZ());
	}

	public Vector3d getRotationVector() {
		return new Vector3d(getRotX(), getRotY(), getRotZ());
	}

	public Vector3d getScaleVector() {
		return new Vector3d(getScaleX(), getScaleY(), getScaleZ());
	}

	public void addRotationOffsetFromBone(GroupBoneHandle source) {
		setRotX(getRotX() + source.getRotX() - source.getInitialSnapshot().getRotX());
		setRotY(getRotY() + source.getRotY() - source.getInitialSnapshot().getRotY());
		setRotZ(getRotZ() + source.getRotZ() - source.getInitialSnapshot().getRotZ());
	}

	public boolean equals(Object obj) {
		if (this == obj)
			return true;

		if (obj == null || getClass() != obj.getClass())
			return false;

		GroupBoneHandle other = (GroupBoneHandle)obj;

		return this.instance == other.instance && this.definition.equals(other.definition);
	}

	public int hashCode() {
		return this.definition.hashCode() * 31 + System.identityHashCode(this.instance);
	}
}
