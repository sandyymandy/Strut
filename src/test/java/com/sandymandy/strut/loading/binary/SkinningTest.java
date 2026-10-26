package com.sandymandy.strut.loading.binary;

import com.sandymandy.strut.animation.state.ModelInstance;
import com.sandymandy.strut.animation.state.SkinningState;
import com.sandymandy.strut.cache.object.ArmatureBoneHandle;
import com.sandymandy.strut.cache.object.BakedStrutModel;
import com.sandymandy.strut.cache.object.StrutArmature;
import com.sandymandy.strut.cache.object.ArmatureBoneDefinition;
import com.sandymandy.strut.cache.object.StrutMesh;
import com.sandymandy.strut.cache.object.StrutSkin;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Drives the skinning pipeline against the two-bone limb fixture
 * <p>
 * Three rings of four vertices: the bottom ring belongs wholly to the lower bone, the top ring
 * wholly to the upper, and the middle ring is split evenly between them. Posing the upper bone
 * therefore has to leave the bottom ring exactly where it was, carry the top ring rigidly, and
 * put the middle ring halfway between the two answers - which is the only part of linear blend
 * skinning that can go subtly rather than obviously wrong
 *
 * <h2>Where the numbers come from</h2>
 *
 * <p>The fixture is authored on a 16-unit grid, so its rings at y = 0, 8 and 16 bake to y = 0,
 * 0.5 and 1.0 blocks, and its corners at x = ±2 bake to ±0.125 - unmirrored, since the bake no
 * longer negates X (see {@link BinaryModelBaker}'s own javadoc). The upper bone's head sits at
 * y = 8, so it pivots about (0, 0.5, 0) in baked space.
 *
 * <p>Corner indices follow from the same correction: with the winding no longer reversed, a
 * baked poly's corners are in the order the file wrote them. The fixture writes each side quad
 * as {@code (a, b, b + 4, a + 4)}, so for poly 0 corner 0 is the bottom ring and corner 3 the
 * middle - the opposite way round from when the bake reversed them.
 */
class SkinningTest {
	private static final float EPSILON = 1.0e-5f;

	private BakedStrutModel model;
	private StrutArmature armature;
	private StrutMesh mesh;
	private StrutSkin skin;
	private SkinningState skinning;
	private ModelInstance instance;
	private Vector3f[] skinned;

	@BeforeEach
	void bakeFixture() {
		this.model = BinaryModelBaker.bake(
				BinaryModelReader.read(BinaryFixtures.riggedModel(), "rig.strutmodel"), "rig.strutmodel");
		this.armature = this.model.armatures().getFirst();
		this.mesh = this.model.topLevelBones().getFirst().getMeshes().getFirst();
		this.skin = this.mesh.skin();
		this.skinning = new SkinningState();
		this.instance = new ModelInstance();
		this.skinned = this.skinning.skinnedVerticesFor(this.skin);
	}

	private void pose() {
		this.skinning.poseArmatures(this.model, this.instance);
		this.skin.skin(this.mesh.restPositions(), this.skinning.skinMatricesFor(this.armature), this.skinned);
	}

	/**
	 * Resolves this instance's own handle for a named armature bone - the per-instance accessor
	 * that replaced posing {@link ArmatureBoneDefinition} directly, since it is
	 * immutable
	 */
	private ArmatureBoneHandle armatureBone(String name) {
		return this.instance.armatureHandleFor(this.model, this.armature, this.armature.getBone(name));
	}

	private static void assertPosition(Vector3f actual, float x, float y, float z, String what) {
		assertEquals(x, actual.x, EPSILON, what + " x");
		assertEquals(y, actual.y, EPSILON, what + " y");
		assertEquals(z, actual.z, EPSILON, what + " z");
	}

	@Test
	@DisplayName("bakes the armature, its hierarchy and the mesh's binding to it")
	void bakesArmature() {
		assertEquals(1, this.model.armatures().size());
		assertEquals("rig", this.armature.name());
		assertEquals("root", this.armature.parentBoneName(), "the armature hangs off a group bone");
		assertEquals(2, this.armature.bones().size());

		ArmatureBoneDefinition lower = this.armature.getBone("lower");
		ArmatureBoneDefinition upper = this.armature.getBone("upper");

		assertNotNull(lower);
		assertNotNull(upper);
		assertNull(lower.getParent(), "the lower bone is the armature's root");
		assertSame(lower, upper.getParent());
		assertEquals(0, lower.getIndex());
		assertEquals(1, upper.getIndex());

		assertTrue(this.mesh.isSkinned());
		assertNotNull(this.mesh.restPositions());
		assertEquals(12, this.mesh.restPositions().length, "one rest position per source vertex");
	}

	/**
	 * The upper bone's rest transform, relative to its parent, is the 8 units between their heads
	 */
	@Test
	@DisplayName("derives a bone's rest transform relative to its parent")
	void derivesBindLocal() {
		ArmatureBoneDefinition upper = this.armature.getBone("upper");
		Vector3f head = upper.getBindLocal().transformPosition(new Vector3f());

		assertPosition(head, 0, 0.5f, 0, "the upper bone's head relative to the lower's");

		/*
		 * The inverse bind exists to take a vertex out of model space and into the bone's rest
		 * frame, so it has to send the bone's own head to the origin.
		 */
		Vector3f headInBoneSpace = upper.getInverseBind().transformPosition(new Vector3f(0, 0.5f, 0));

		assertPosition(headInBoneSpace, 0, 0, 0, "the upper bone's head, taken into its own rest frame");
	}

	/**
	 * An unanimated armature has to leave its mesh exactly as authored
	 * <p>
	 * This is what makes the rest pose fall out of the general case rather than being special
	 * cased: {@code poseModel} equals the bind transform, so {@code skin} is the identity
	 */
	@Test
	@DisplayName("leaves the mesh untouched at rest")
	void restPoseIsIdentity() {
		pose();

		Matrix4f[] skinMatrices = this.skinning.skinMatricesFor(this.armature);

		for (int bone = 0; bone < this.armature.bones().size(); bone++) {
			assertTrue(skinMatrices[bone].equals(new Matrix4f(), EPSILON),
					"bone " + bone + "'s skinning matrix should be the identity at rest");
		}

		assertPosition(this.skin.positionOf(this.skinned, 0, 0), -0.125f, 0, -0.125f, "a bottom-ring vertex at rest");
		assertPosition(this.skin.positionOf(this.skinned, 0, 3), -0.125f, 0.5f, -0.125f, "a middle-ring vertex at rest");
		assertPosition(this.skin.positionOf(this.skinned, 4, 3), -0.125f, 1f, -0.125f, "a top-ring vertex at rest");
	}

	/**
	 * The skinning gate: a hand-posed bone deforms the mesh, and only the parts bound to it
	 */
	@Test
	@DisplayName("bends the mesh when a bone is posed, leaving the other bone's vertices alone")
	void posedBoneDeformsItsOwnVertices() {
		armatureBone("upper").updateRotation(0, 0, (float)Math.toRadians(90));

		pose();

		/*
		 * Rotating 90 degrees about Z, about the upper bone's head at (0, 0.5, 0), maps a point's
		 * offset from that head as (x, y) -> (-y, x). The top ring sits at (-0.125, 1), an offset
		 * of (-0.125, 0.5), which swings to (-0.5, -0.125) and lands at (-0.5, 0.375).
		 */
		assertPosition(this.skin.positionOf(this.skinned, 4, 3), -0.5f, 0.375f, -0.125f,
				"a top-ring vertex, weighted wholly to the posed bone, swings rigidly with it");

		assertPosition(this.skin.positionOf(this.skinned, 0, 0), -0.125f, 0, -0.125f,
				"a bottom-ring vertex, weighted wholly to the other bone, must not move at all");
	}

	/**
	 * The middle ring is the whole point of the fixture
	 * <p>
	 * Weighted evenly between a posed bone and an unposed one, it has to land midway between the
	 * two answers. A blend that drops an influence snaps it to one side; one that renormalises
	 * silently moves it; one that transforms a shared vertex once per face tears the seam apart
	 */
	@Test
	@DisplayName("blends a vertex between two bones rather than picking one")
	void blendsAcrossAJoint() {
		armatureBone("upper").updateRotation(0, 0, (float)Math.toRadians(90));

		pose();

		/*
		 * The middle ring sits exactly at the posed bone's head, so the posed answer is
		 * (-0.125, 0.5) swung to (0, 0.375) while the unposed one leaves it where it was; halfway
		 * between the two is (-0.0625, 0.4375).
		 */
		assertPosition(this.skin.positionOf(this.skinned, 0, 3), -0.0625f, 0.4375f, -0.125f,
				"a middle-ring vertex, weighted half to each bone");
	}

	/**
	 * A vertex shared by several faces must be transformed once, not once per face
	 */
	@Test
	@DisplayName("skins a shared vertex to one position, however many faces use it")
	void sharedVerticesStayWelded() {
		armatureBone("upper").updateRotation(0, 0, (float)Math.toRadians(35));

		pose();

		// Poly 0's corner 3 and poly 4's corner 0 are both middle-ring vertex 4
		assertSame(this.skin.positionOf(this.skinned, 0, 3), this.skin.positionOf(this.skinned, 4, 0),
				"both faces meeting at this vertex must read the same skinned position, or the seam splits");
	}

	/**
	 * The shipped demo asset is written by a Python tool and read by this Java parser, which is
	 * two independent implementations of one binary layout
	 * <p>
	 * Nothing but a test keeps them in step. A field added on one side and forgotten on the other
	 * produces a file that either fails to load or, worse, loads with every subsequent field one
	 * step out of place - which is a plausible-looking model full of nonsense
	 */
	@Test
	@DisplayName("reads the demo asset written by the Python generator")
	void readsGeneratedDemoAsset() throws IOException {
		BakedStrutModel demo;

		try (InputStream in = SkinningTest.class.getResourceAsStream("/assets/strut/strut/models/entity/rig_demo.strutmodel")) {
			assertNotNull(in, "the skinning demo asset is missing; run tools/make_rig_demo.py");

			demo = BinaryModelBaker.bake(BinaryModelReader.read(in, "rig_demo"), "rig_demo");
		}

		assertEquals(1, demo.armatures().size());

		StrutArmature rig = demo.armatures().getFirst();

		assertEquals(2, rig.bones().size());
		assertNotNull(rig.getBone("upper"));

		StrutMesh limb = demo.topLevelBones().getFirst().getMeshes().getFirst();

		assertTrue(limb.isSkinned(), "the demo exists to show skinning, so its mesh had better be skinned");
		assertEquals(12, limb.restPositions().length);
		assertEquals(8, limb.polys().length);

		SkinningState demoSkinning = new SkinningState();
		ModelInstance demoInstance = new ModelInstance();
		Vector3f[] demoSkinned = demoSkinning.skinnedVerticesFor(limb.skin());

		demoInstance.armatureHandleFor(demo, rig, rig.getBone("upper")).updateRotation(0, 0, (float)Math.toRadians(90));
		demoSkinning.poseArmatures(demo, demoInstance);
		limb.skin().skin(limb.restPositions(), demoSkinning.skinMatricesFor(rig), demoSkinned);

		assertNotEquals(limb.restPositions()[8].y, limb.skin().positionOf(demoSkinned, 4, 3).y,
				"the top ring should have moved");
		assertEquals(limb.restPositions()[0].y, limb.skin().positionOf(demoSkinned, 0, 0).y, EPSILON,
				"and the bottom ring should not have");
	}

	@Test
	@DisplayName("pads influences to a fixed width without disturbing the authored weights")
	void padsInfluences() {
		armatureBone("upper").updateRotation(0, 0, (float)Math.toRadians(90));

		pose();

		Vector3f bottom = this.skin.positionOf(this.skinned, 0, 0);
		Vector3f rest = this.mesh.restPositions()[0];

		assertPosition(bottom, rest.x, rest.y, rest.z,
				"a vertex with one real influence and three zero-weight pads must be unaffected by the pads");
	}

	/**
	 * A smooth-shaded skinned mesh deforms its rest normals the same blend of bone matrices its
	 * positions go through - {@link StrutSkin#skinNormals} - rather than recomputing them from the
	 * deformed corners the way a flat one does. This drives that method directly, independent of
	 * whether the fixture's own mesh happens to be smooth, since the method only needs a skin and
	 * some rest normals to blend
	 */
	@Test
	@DisplayName("deforms a normal by the same bone blend as a position, then renormalises")
	void skinsNormalsLikePositions() {
		armatureBone("upper").updateRotation(0, 0, (float)Math.toRadians(90));

		pose();

		Vector3f[] restNormals = new Vector3f[this.skin.vertexCount()];

		for (int i = 0; i < restNormals.length; i++) {
			restNormals[i] = new Vector3f(0, 1, 0);
		}

		Vector3f[] skinnedNormals = new Vector3f[this.skin.vertexCount()];

		for (int i = 0; i < skinnedNormals.length; i++) {
			skinnedNormals[i] = new Vector3f();
		}

		this.skin.skinNormals(restNormals, this.skinning.skinMatricesFor(this.armature), skinnedNormals);

		/*
		 * Rotating 90 degrees about Z maps (x, y) -> (-y, x), same as the position case above, so
		 * an up-pointing normal swings to point along -X.
		 */
		Vector3f topNormal = this.skin.normalOf(skinnedNormals, 4, 3);

		assertPosition(topNormal, -1f, 0f, 0f, "a top-ring normal, weighted wholly to the posed bone, swings with it");
		assertEquals(1f, topNormal.length(), EPSILON, "a blended normal must come back out unit length");

		Vector3f bottomNormal = this.skin.normalOf(skinnedNormals, 0, 0);

		assertPosition(bottomNormal, 0f, 1f, 0f,
				"a bottom-ring normal, weighted wholly to the other bone, must not rotate at all");
	}
}
