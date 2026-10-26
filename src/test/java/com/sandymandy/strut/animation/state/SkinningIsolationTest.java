package com.sandymandy.strut.animation.state;

import com.sandymandy.strut.cache.object.ArmatureBoneHandle;
import com.sandymandy.strut.cache.object.BakedStrutModel;
import com.sandymandy.strut.cache.object.StrutArmature;
import com.sandymandy.strut.cache.object.ArmatureBoneDefinition;
import com.sandymandy.strut.cache.object.StrutMesh;
import com.sandymandy.strut.cache.object.StrutSkin;
import com.sandymandy.strut.loading.binary.BinaryFixtures;
import com.sandymandy.strut.loading.binary.BinaryModelBaker;
import com.sandymandy.strut.loading.binary.BinaryModelReader;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Two animatables sharing one baked model must never see each other's pose
 * <p>
 * A {@link BakedStrutModel} is loaded once and handed to every animatable that names it, so the
 * moment anything written during posing lives on the model, the last animatable rendered in a
 * frame wins and every earlier one is drawn with its geometry. That is silent: nothing throws,
 * nothing logs, the model simply flickers between two entities' poses, and it is the reason the
 * skinning buffers moved off {@link StrutArmature} and {@link StrutSkin} and onto
 * {@link SkinningState} (D1), and why {@link ArmatureBoneDefinition} itself became immutable in fork
 * Its position/rotation/scale moving to a per-instance {@link ArmaturePose}
 * addressed through {@link ArmatureBoneHandle} (D7)
 * <p>
 * These tests would all have passed trivially before either move, because there was only ever one
 * buffer - or one bone - to read. Each {@code Instance} below owns both halves a real animatable
 * would: a {@link SkinningState} for the derived matrices and vertices, and a {@link ModelInstance}
 * for the {@link ArmaturePose} that actually drives them
 */
class SkinningIsolationTest {
	private static final float EPSILON = 1e-5f;

	private BakedStrutModel model;
	private StrutArmature armature;
	private ArmatureBoneDefinition upperDefinition;
	private StrutMesh mesh;
	private StrutSkin skin;

	@BeforeEach
	void bakeSharedFixture() {
		this.model = BinaryModelBaker.bake(
				BinaryModelReader.read(BinaryFixtures.riggedModel(), "rig.strutmodel"), "rig.strutmodel");
		this.armature = this.model.armatures().getFirst();
		this.upperDefinition = this.armature.getBone("upper");
		this.mesh = this.model.topLevelBones().getFirst().getMeshes().getFirst();
		this.skin = this.mesh.skin();
	}

	/**
	 * One animatable's own pose-producing state - a {@link ModelInstance} for the armature pose
	 * that drives everything, plus the {@link SkinningState} derived from it - bundled together
	 * because a real animatable always owns both
	 */
	private record Instance(ModelInstance modelInstance, SkinningState skinning) {
		static Instance create() {
			return new Instance(new ModelInstance(), new SkinningState());
		}
	}

	private Vector3f[] poseInto(Instance instance, float bendDegrees) {
		ArmatureBoneHandle upper = instance.modelInstance().armatureHandleFor(this.model, this.armature, this.upperDefinition);

		upper.updateRotation(0, 0, (float)Math.toRadians(bendDegrees));

		Vector3f[] skinned = instance.skinning().skinnedVerticesFor(this.skin);

		instance.skinning().poseArmatures(this.model, instance.modelInstance());
		this.skin.skin(this.mesh.restPositions(), instance.skinning().skinMatricesFor(this.armature), skinned);

		return skinned;
	}

	@Test
	@DisplayName("two instances resolve different handles, and different armature poses, for the same bone")
	void armatureHandlesDoNotAlias() {
		Instance first = Instance.create();
		Instance second = Instance.create();

		ArmatureBoneHandle firstUpper = first.modelInstance().armatureHandleFor(this.model, this.armature, this.upperDefinition);
		ArmatureBoneHandle secondUpper = second.modelInstance().armatureHandleFor(this.model, this.armature, this.upperDefinition);

		assertNotSame(firstUpper, secondUpper, "each instance must have its own handle for the same bone definition");

		firstUpper.updateRotation(0, 0, (float)Math.toRadians(90));

		assertEquals(0f, secondUpper.getRotZ(), "posing the first instance's handle must not move the second's");
	}

	@Test
	@DisplayName("two instances posing the same model do not share vertex buffers")
	void vertexBuffersDoNotAlias() {
		Instance first = Instance.create();
		Instance second = Instance.create();

		assertNotSame(first.skinning().skinnedVerticesFor(this.skin), second.skinning().skinnedVerticesFor(this.skin),
				"each instance must own its deformed vertices");
		assertNotSame(first.skinning().skinMatricesFor(this.armature), second.skinning().skinMatricesFor(this.armature),
				"each instance must own its skinning matrices");
	}

	@Test
	@DisplayName("posing a second instance leaves the first instance's pose untouched")
	void posingOneInstanceDoesNotDisturbAnother() {
		Instance first = Instance.create();
		Instance second = Instance.create();

		Vector3f[] firstPose = poseInto(first, 90);
		Vector3f[] snapshot = new Vector3f[firstPose.length];

		for (int i = 0; i < firstPose.length; i++) {
			snapshot[i] = new Vector3f(firstPose[i]);
		}

		Vector3f[] secondPose = poseInto(second, -45);

		for (int i = 0; i < firstPose.length; i++) {
			assertTrue(firstPose[i].equals(snapshot[i], EPSILON),
					"vertex " + i + " of the first instance changed when the second was posed");
		}

		boolean anyDifference = false;

		for (int i = 0; i < firstPose.length; i++) {
			if (!firstPose[i].equals(secondPose[i], EPSILON)) {
				anyDifference = true;

				break;
			}
		}

		assertTrue(anyDifference, "the two instances were posed differently, so their vertices must differ - "
				+ "if they match, the test is not actually exercising two poses");
	}

	@Test
	@DisplayName("buffers survive across frames but are dropped when the model is replaced")
	void rebindingOnModelSwapDropsStaleBuffers() {
		Instance instance = Instance.create();

		instance.skinning().poseArmatures(this.model, instance.modelInstance());

		Vector3f[] before = instance.skinning().skinnedVerticesFor(this.skin);

		instance.skinning().poseArmatures(this.model, instance.modelInstance());

		assertSame(before, instance.skinning().skinnedVerticesFor(this.skin),
				"re-posing the same model must reuse the buffers rather than reallocate them every frame");

		BakedStrutModel reloaded = BinaryModelBaker.bake(
				BinaryModelReader.read(BinaryFixtures.riggedModel(), "rig.strutmodel"), "rig.strutmodel");

		instance.skinning().poseArmatures(reloaded, instance.modelInstance());

		assertNotSame(before, instance.skinning().skinnedVerticesFor(reloaded.topLevelBones().getFirst().getMeshes().getFirst().skin()),
				"a resource reload produces new baked objects, so the buffers keyed to the old ones must be dropped");
	}
}
