package com.sandymandy.strut.physics.flesh;

import com.sandymandy.strut.animation.state.CageBindings;
import com.sandymandy.strut.animation.state.CageDeform;
import com.sandymandy.strut.animation.state.ModelInstance;
import com.sandymandy.strut.animation.state.ParticleDeform;
import com.sandymandy.strut.animation.state.Pose;
import com.sandymandy.strut.cache.object.BakedStrutModel;
import com.sandymandy.strut.cache.object.GroupBoneDefinition;
import com.sandymandy.strut.cache.object.StrutMesh;
import com.sandymandy.strut.loading.json.raw.ModelProperties;
import com.sandymandy.strut.physics.PhysicsInstance;
import com.sandymandy.strut.physics.collision.Bodies;
import com.sandymandy.strut.physics.collision.Collider;
import com.sandymandy.strut.physics.config.PhysicsConfig;
import com.sandymandy.strut.physics.tools.DebugSkirt;
import com.sandymandy.strut.physics.tools.PhysicsControls;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the vertex shader is given (the cage's numbers and the table of what never changes) works out the same offsets and shading changes as the writer does for
 * each vertex itself: the arithmetic of {@code CageGlsl} is repeated here step for step and held to the CPU's
 */
class CageDeformTest {
	private static float[] vec(float[] b, int at) {
		return new float[] {b[at], b[at + 1], b[at + 2]};
	}

	private static float dot(float[] a, float[] b) {
		return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
	}

	private static float bits(int at) {
		return Float.intBitsToFloat(CageBindings.word(at));
	}

	/** {@code cageDeform} of {@link com.sandymandy.strut.renderer.gpu.CageGlsl}: {offset, normalChange} */
	private static float[][] cageDeform(CageDeform cage, int particle) {
		float[] b = cage.blocks;
		int bind = cage.bindingBase() + particle * CageDeform.PARTICLE_WORDS;
		int region = CageBindings.word(bind);

		if (region < 0)
			return new float[][] {{0, 0, 0}, {0, 0, 0}};

		int count = CageBindings.word(bind + 1);
		int block = region * CageDeform.REGION_WORDS;
		int nodes = block + CageDeform.NODE_DISPLACEMENTS;
		int normals = block + CageDeform.NORMAL_CHANGES;
		float surface = bits(bind + CageDeform.PARTICLE_SURFACE);
		float soft = bits(bind + CageDeform.PARTICLE_SOFTNESS);
		float[] q = {bits(bind + CageDeform.PARTICLE_AT), bits(bind + CageDeform.PARTICLE_AT + 1), bits(bind + CageDeform.PARTICLE_AT + 2)};
		float[] blended = new float[3];
		float[] turned = new float[3];

		for (int j = 0; j < count; j++) {
			int node = CageBindings.word(bind + CageDeform.PARTICLE_NODES + j);
			float w = bits(bind + CageDeform.PARTICLE_WEIGHTS + j);

			for (int a = 0; a < 3; a++) {
				blended[a] += w * b[nodes + node * 3 + a];
				turned[a] += w * b[normals + node * 3 + a];
			}
		}

		float[] d = new float[3];

		for (int a = 0; a < 3; a++)
			d[a] = blended[a] * surface + b[nodes + 162 * 3 + a] * (1f - surface);

		int flags = (int)(b[block + CageDeform.FLAGS] + 0.5f);
		float[] swing = vec(b, block + CageDeform.SWING);
		float[] pivot = vec(b, block + CageDeform.PIVOT);
		float[] middle = vec(b, block + CageDeform.MIDDLE);

		if ((flags & 1) != 0) {
			float[] r = {q[0] - pivot[0], q[1] - pivot[1], q[2] - pivot[2]};
			float held = Math.min(1f, 2f * soft);

			for (int a = 0; a < 3; a++) {
				float turnedR = dot(vec(b, block + CageDeform.TURN + a * 3), r);

				d[a] += (turnedR - r[a]) * held - swing[a] * soft;
			}
		}

		if ((flags & 2) != 0) {
			float[] r = {q[0] - middle[0], q[1] - middle[1], q[2] - middle[2]};

			for (int a = 0; a < 3; a++)
				d[a] += dot(vec(b, block + CageDeform.STRETCH + a * 3), r) * soft;
		}

		if ((flags & 8) != 0) {
			float[] r = {q[0] - middle[0], q[1] - middle[1], q[2] - middle[2]};
			float length = (float)Math.sqrt(dot(r, r));

			if (length > 1e-9f) {
				float outward = dot(d, r) / length;
				float room = b[block + CageDeform.ROOM];

				if (outward > room) {
					for (int a = 0; a < 3; a++)
						d[a] -= r[a] * ((outward - room) / length);
				}
			}
		}

		if ((flags & 4) != 0) {
			float[] p = vec(b, block + CageDeform.PLANE_POINT);
			float[] n = vec(b, block + CageDeform.PLANE_NORMAL);
			float allow = b[block + CageDeform.PLANE_ALLOW];
			float edge = b[block + CageDeform.PLANE_EDGE];
			float[] at = {q[0] + d[0] - p[0], q[1] + d[1] - p[1], q[2] + d[2] - p[2]};
			float past = dot(at, n) - allow;

			if (past > 0f) {
				float kept = past < 2f * edge ? past - past * past / (4f * edge) : edge;
				float flattened = past - kept;

				for (int a = 0; a < 3; a++)
					d[a] -= n[a] * flattened;

				float[] o = {q[0] + d[0] - p[0], q[1] + d[1] - p[1], q[2] + d[2] - p[2]};
				float along = dot(o, n);

				for (int a = 0; a < 3; a++)
					o[a] -= along * n[a];

				float radial = (float)Math.sqrt(dot(o, o));

				if (radial > 1e-9f) {
					for (int a = 0; a < 3; a++)
						d[a] += o[a] * (b[block + CageDeform.RIM] * flattened / radial);
				}
			}
		}

		for (int a = 0; a < 3; a++)
			d[a] *= b[block + CageDeform.WEIGHT];

		float[] offset = new float[3];
		float[] change = new float[3];

		for (int a = 0; a < 3; a++) {
			float[] row = vec(b, block + CageDeform.TO_MESH + a * 3);

			offset[a] = dot(row, d);
			change[a] = dot(row, turned);
		}

		return new float[][] {offset, change};
	}

	private static BakedStrutModel model(StrutMesh mesh) {
		GroupBoneDefinition root = new GroupBoneDefinition(null, "root", false, null, null, null);
		GroupBoneDefinition skirt = new GroupBoneDefinition(root, "skirt", false, null, null, null);

		root.bakePivot(0, 0, 0);
		skirt.bakePivot(0, 16, 0);
		root.getChildBones().add(skirt);
		skirt.getMeshes().add(mesh);

		ModelProperties properties = new ModelProperties(null, null, null, null, null, null, null, null, null, null, "test", null, 16, 16, null, new double[] {0, 0, 0}, null);

		return new BakedStrutModel(List.of(root), properties);
	}

	private static Collider.Placed hand() {
		Collider.Placed shape = new Collider.Placed();

		shape.shape = Collider.Shape.SPHERE;
		shape.radius = 0.08;
		shape.p[1] = 64 + 0.74;
		shape.p[2] = 0.3;
		shape.layer = "body";

		return shape;
	}

	@Test
	void theShadersArithmeticGivesWhatTheWriterWorksOutForEveryVertex() {
		CageDeform.gpu = true;

		try {
			BakedStrutModel model = model(DebugSkirt.ball(0, 0.6, 0.3, 0.12));
			ModelInstance modelInstance = new ModelInstance();

			modelInstance.poseFor(model);
			modelInstance.resetPoseHistory();

			Pose animated = modelInstance.renderedPose();
			Pose pose = new Pose(animated.boneCount());

			pose.copyFrom(animated);

			PhysicsInstance instance = new PhysicsInstance(model, PhysicsConfig.parse("{\"flesh\": {\"ball\": {\"bone\": \"skirt\", \"preset\": \"jelly\"}}}", "test"));

			instance.entityId(5);

			for (int i = 0; i < 40; i++) {
				Bodies.publishModel(99, new ArrayList<>(List.of(hand())));
				Bodies.nextFrame();
				pose.copyFrom(animated);
				instance.update(model, animated, pose, new PhysicsInstance.Sample(i * 0.01, 64, 0, 180f, i / 60.0), new PhysicsControls());
			}

			List<CageDeform> cages = new ArrayList<>();

			instance.clothDeforms((mesh, deform) -> {
				if (deform instanceof CageDeform cage)
					cages.add(cage);
			});

			assertFalse(cages.isEmpty(), "with a renderer that reads it, the writer hands over the cage");

			float most = 0;

			for (CageDeform cage : cages) {
				ParticleDeform cpu = cage.fallback();

				assertNotNull(cage.cornerOffsets());

				for (int k = 0; k < cpu.particles(); k++) {
					float[][] gpu = cageDeform(cage, k);

					for (int a = 0; a < 3; a++) {
						assertEquals(cpu.offsets[k * 3 + a], gpu[0][a], 2e-5f, "offset " + k + " axis " + a);
						assertEquals(cpu.normalDeltas[k * 3 + a], gpu[1][a], 2e-5f, "normal " + k + " axis " + a);
						most = Math.max(most, Math.abs(gpu[0][a]));
					}
				}
			}

			assertTrue(most > 1e-4f, "the hand dented it, so there is something to compare");
		}
		finally {
			CageDeform.gpu = false;
		}
	}
}
