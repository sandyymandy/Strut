package com.sandymandy.strut.renderer.gpu;

import com.sandymandy.strut.animation.state.ModelInstance;
import com.sandymandy.strut.animation.state.Pose;
import com.sandymandy.strut.animation.state.PosePalette;
import com.sandymandy.strut.animation.state.SkinningState;
import com.sandymandy.strut.cache.object.BakedStrutModel;
import com.sandymandy.strut.cache.object.StrutArmature;
import com.sandymandy.strut.cache.object.StrutMesh;
import com.sandymandy.strut.cache.object.StrutSkin;
import com.sandymandy.strut.cache.object.GroupBoneDefinition;
import com.sandymandy.strut.loading.binary.BinaryFixtures;
import com.sandymandy.strut.loading.binary.BinaryModelBaker;
import com.sandymandy.strut.loading.binary.BinaryModelReader;
import com.sandymandy.strut.util.RenderUtil;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the exact formula the
 * GPU skinning shader will have to implement, before any shader exists to hide a mistake in it
 * <p>
 * The formula, read off {@link com.sandymandy.strut.renderer.StrutRenderer#renderSkinnedMesh} rather
 * than assumed:
 *
 * <pre>posed = groupBonePalette · E_mesh · skin(restPosition)</pre>
 *
 * where {@code E_mesh} is the mesh's own static pivot rotation ({@link RenderUtil#applyPivotRotation}
 * on {@link StrutMesh#pivot()}/{@link StrutMesh#rotation()}) and {@code skin(...)} is
 * {@link StrutSkin#skin}'s linear blend of armature bone matrices - already proven correct in
 * isolation by {@code SkinningTest}, so this test's job is proving the composition
 * <em>around</em> that blend, not the blend itself
 * <p>
 * Two things get posed to nontrivial values at once - the mesh's owning group bone (position,
 * rotation and scale) and an armature bone - so a bug in either the group-bone term or the ordering
 * between the two terms shows up as a real numeric mismatch, the same reasoning
 * {@code PosePaletteTest} documents for its own per-bone seeding. The CPU reference is built the
 * exact way the real renderer builds it - a {@link MatrixStack} push plus
 * {@link RenderUtil#prepMatrixForBone}, then {@link RenderUtil#applyPivotRotation} for the mesh's
 * own pivot - not a second hand-written reimplementation of the same maths, for the same reason
 * {@code PosePaletteTest} uses {@code RenderUtil.prepMatrixForBone} as its own reference rather than
 * re-deriving it
 */
class GpuSkinningParityTest {
	private static final float EPSILON = 1.0e-4f;

	private BakedStrutModel model;
	private GroupBoneDefinition rootBone;
	private StrutMesh mesh;
	private StrutSkin skin;
	private StrutArmature armature;
	private ModelInstance instance;
	private SkinningState skinning;

	@BeforeEach
	void bakeFixture() {
		this.model = BinaryModelBaker.bake(
				BinaryModelReader.read(BinaryFixtures.riggedModel(), "rig.strutmodel"), "rig.strutmodel");
		this.rootBone = this.model.topLevelBones().getFirst();
		this.mesh = this.rootBone.getMeshes().getFirst();
		this.skin = this.mesh.skin();
		this.armature = this.model.armatures().getFirst();
		this.instance = new ModelInstance();
		this.skinning = new SkinningState();
	}

	@Test
	@DisplayName("groupBonePalette * E_mesh * skin(rest) matches the real MatrixStack-based render path")
	void gpuFormulaMatchesTheCpuRenderPath() {
		// Pose the owning group bone to something nontrivial, so the GPU formula's palette term is
		// actually exercised rather than composing against an identity that would hide an ordering bug
		Pose current = this.instance.poseFor(this.model);

		current.setPosition(this.rootBone.getIndex(), 3f, -2f, 1.5f);
		current.setRotation(this.rootBone.getIndex(), 0.2f, -0.35f, 0.15f);
		current.setScale(this.rootBone.getIndex(), 1.1f, 0.9f, 1.05f);
		this.instance.resetPoseHistory();

		// Pose an armature bone too, so both terms the design calls out - the owning bone's palette
		// entry and the skin blend - are live at once, not just one or the other
		this.instance.armatureHandleFor(this.model, this.armature, this.armature.getBone("upper"))
				.updateRotation(0, 0, (float)Math.toRadians(40));

		this.skinning.poseArmatures(this.model, this.instance);

		Vector3f[] cpuSkinned = this.skinning.skinnedVerticesFor(this.skin);

		this.skin.skin(this.mesh.restPositions(), this.skinning.skinMatricesFor(this.armature), cpuSkinned);

		// The real render path's own composition: MatrixStack push for the group bone, then the
		// mesh's own pivot rotation on top - exactly what StrutRenderer#renderMesh does before handing
		// off to renderSkinnedMesh
		MatrixStack poseStack = new MatrixStack();

		poseStack.push();
		RenderUtil.prepMatrixForBone(poseStack, this.instance.handleFor(this.model, this.rootBone));

		Matrix4f cpuPoseState = new Matrix4f(poseStack.peek().getPositionMatrix());
		Matrix3f cpuNormalState = new Matrix3f(poseStack.peek().getNormalMatrix());

		RenderUtil.applyPivotRotation(cpuPoseState, cpuNormalState, this.mesh.pivot(), this.mesh.rotation());

		// The GPU-side transcription: PosePalette.computeBone (already proven identical to the
		// MatrixStack walk by PosePaletteTest, for a pure bone hierarchy) composed with the mesh's
		// own pivot rotation the same way GpuModel.bake's elementMatrix already does for a rigid mesh
		Matrix4f groupBoneMatrix = new Matrix4f();

		PosePalette.computeBone(this.model, this.instance.renderedPose(), this.rootBone.getIndex(), groupBoneMatrix);

		Matrix4f elementMatrix = new Matrix4f();
		Matrix3f elementNormalMatrix = new Matrix3f();

		RenderUtil.applyPivotRotation(elementMatrix, elementNormalMatrix, this.mesh.pivot(), this.mesh.rotation());

		Matrix4f gpuPoseState = new Matrix4f(groupBoneMatrix).mul(elementMatrix);

		assertMatrixEquals(cpuPoseState, gpuPoseState, "the composed group-bone * mesh-pivot matrix");

		// With the composition proven identical, transform every deformed vertex through both and
		// confirm the actual render-time positions - not just the matrices - agree
		Vector4f cpuPosition = new Vector4f();
		Vector4f gpuPosition = new Vector4f();

		for (int poly = 0; poly < this.mesh.polys().length; poly++) {
			for (int corner = 0; corner < this.mesh.polys()[poly].vertices().length; corner++) {
				Vector3f deformed = this.skin.positionOf(cpuSkinned, poly, corner);

				cpuPoseState.transform(deformed.x(), deformed.y(), deformed.z(), 1.0f, cpuPosition);
				gpuPoseState.transform(deformed.x(), deformed.y(), deformed.z(), 1.0f, gpuPosition);

				assertEquals(cpuPosition.x(), gpuPosition.x(), EPSILON, "poly " + poly + " corner " + corner + " x");
				assertEquals(cpuPosition.y(), gpuPosition.y(), EPSILON, "poly " + poly + " corner " + corner + " y");
				assertEquals(cpuPosition.z(), gpuPosition.z(), EPSILON, "poly " + poly + " corner " + corner + " z");
			}
		}
	}

	/**
	 * The smooth-normal half of the same formula: a normal deforms by
	 * {@code groupBoneNormalMatrix * E_meshNormal * skinNormals(rest)}, verified the same way -
	 * against the real {@link MatrixStack}-derived normal matrix, not a second reimplementation.
	 * {@code riggedModel()}'s own mesh is flat-shaded (no {@link StrutMesh#restNormals()}), so this
	 * drives {@link StrutSkin#skinNormals} directly with synthetic rest normals, exactly like
	 * {@code SkinningTest.skinsNormalsLikePositions} already does for the un-composed case
	 */
	@Test
	@DisplayName("groupBoneNormalMatrix * E_meshNormal * skinNormals(rest) matches the real normal matrix")
	void gpuNormalFormulaMatchesTheCpuRenderPath() {
		Pose current = this.instance.poseFor(this.model);

		current.setRotation(this.rootBone.getIndex(), 0.2f, -0.35f, 0.15f);
		current.setScale(this.rootBone.getIndex(), 1.1f, 0.9f, 1.05f);
		this.instance.resetPoseHistory();

		this.instance.armatureHandleFor(this.model, this.armature, this.armature.getBone("upper"))
				.updateRotation(0, 0, (float)Math.toRadians(40));

		this.skinning.poseArmatures(this.model, this.instance);

		Vector3f[] restNormals = new Vector3f[this.skin.vertexCount()];
		Vector3f[] skinnedNormals = new Vector3f[this.skin.vertexCount()];

		for (int i = 0; i < restNormals.length; i++) {
			restNormals[i] = new Vector3f(0, 1, 0);
			skinnedNormals[i] = new Vector3f();
		}

		this.skin.skinNormals(restNormals, this.skinning.skinMatricesFor(this.armature), skinnedNormals);

		MatrixStack poseStack = new MatrixStack();

		poseStack.push();
		RenderUtil.prepMatrixForBone(poseStack, this.instance.handleFor(this.model, this.rootBone));

		Matrix4f cpuPoseState = new Matrix4f(poseStack.peek().getPositionMatrix());
		Matrix3f cpuNormalState = new Matrix3f(poseStack.peek().getNormalMatrix());

		RenderUtil.applyPivotRotation(cpuPoseState, cpuNormalState, this.mesh.pivot(), this.mesh.rotation());

		Matrix4f groupBoneMatrix = new Matrix4f();

		PosePalette.computeBone(this.model, this.instance.renderedPose(), this.rootBone.getIndex(), groupBoneMatrix);

		Matrix4f elementMatrix = new Matrix4f();
		Matrix3f elementNormalMatrix = new Matrix3f();

		RenderUtil.applyPivotRotation(elementMatrix, elementNormalMatrix, this.mesh.pivot(), this.mesh.rotation());

		// The bone's own normal matrix is its inverse-transpose, not a plain Matrix3f of its position
		// matrix - the root bone here carries non-uniform scale, and only the inverse-transpose
		// convention (what MatrixStack.Entry.getNormalMatrix() and the existing rigid-mesh GPU shader
		// both already use - "mat3 normalMatrix = transpose(inverse(mat3(boneMatrix)))") stays correct
		// under that. The mesh's own elementNormalMatrix needs no such correction: applyPivotRotation
		// only ever rotates it (pure rotation is its own inverse-transpose)
		Matrix3f gpuNormalState = groupBoneMatrix.normal(new Matrix3f()).mul(elementNormalMatrix);

		assertMatrixEquals(cpuNormalState, gpuNormalState, "the composed group-bone * mesh-pivot normal matrix");

		Vector3f cpuNormal = new Vector3f();
		Vector3f gpuNormal = new Vector3f();

		for (int poly = 0; poly < this.mesh.polys().length; poly++) {
			for (int corner = 0; corner < this.mesh.polys()[poly].vertices().length; corner++) {
				Vector3f deformed = this.skin.normalOf(skinnedNormals, poly, corner);

				cpuNormalState.transform(new Vector3f(deformed), cpuNormal);
				gpuNormalState.transform(new Vector3f(deformed), gpuNormal);

				assertEquals(cpuNormal.x(), gpuNormal.x(), EPSILON, "poly " + poly + " corner " + corner + " normal x");
				assertEquals(cpuNormal.y(), gpuNormal.y(), EPSILON, "poly " + poly + " corner " + corner + " normal y");
				assertEquals(cpuNormal.z(), gpuNormal.z(), EPSILON, "poly " + poly + " corner " + corner + " normal z");
			}
		}
	}

	private static void assertMatrixEquals(Matrix4f expected, Matrix4f actual, String what) {
		for (int row = 0; row < 4; row++) {
			for (int column = 0; column < 4; column++) {
				assertEquals(expected.get(column, row), actual.get(column, row), EPSILON, what + " element (" + row + "," + column + ")");
			}
		}
	}

	private static void assertMatrixEquals(Matrix3f expected, Matrix3f actual, String what) {
		for (int row = 0; row < 3; row++) {
			for (int column = 0; column < 3; column++) {
				assertEquals(expected.get(column, row), actual.get(column, row), EPSILON, what + " element (" + row + "," + column + ")");
			}
		}
	}
}
