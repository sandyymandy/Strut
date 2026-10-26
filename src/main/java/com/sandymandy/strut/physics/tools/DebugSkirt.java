package com.sandymandy.strut.physics.tools;

import com.sandymandy.strut.cache.StrutCache;
import com.sandymandy.strut.cache.object.BakedStrutModel;
import com.sandymandy.strut.cache.object.GroupBoneDefinition;
import com.sandymandy.strut.cache.object.StrutMesh;
import com.sandymandy.strut.cache.object.StrutPoly;
import com.sandymandy.strut.cache.object.StrutVertex;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import com.sandymandy.strut.physics.config.PhysicsConfigs;

/**
 * Test props for the cloth and flesh scenarios: Strut's test dummy is made of cubes, and cloth and flesh need a mesh, so the scenario brings one
 * <p>
 * A prop is added to the baked model's {@code hip} bone while the scenario is applied and taken off when another scenario or none is. The model on disk is
 * untouched, and a resource reload drops the prop with the model it was on. {@code dress}: a tube from the chest to the knees, a bodice that is sewn to the
 * body and a skirt that flares and hangs free. {@code jelly}: two closed balls at the hips
 */
public final class DebugSkirt {
	static final String BONE = "hip";
	static final String DRESS = "dress";
	static final String JELLY = "jelly";
	private static final int AROUND = 16;
	/** The dress's rings, top to bottom: height, half width (x) and half depth (z) in blocks */
	private static final double[][] RINGS = {
		{1.38, 0.26, 0.19}, {1.30, 0.25, 0.18}, {1.18, 0.21, 0.16}, {1.06, 0.19, 0.15}, {0.98, 0.22, 0.17},
		{0.88, 0.30, 0.25}, {0.76, 0.38, 0.33}, {0.64, 0.45, 0.40}, {0.52, 0.51, 0.46}, {0.40, 0.56, 0.52}
	};
	private static final Map<BakedStrutModel, List<StrutMesh>> ATTACHED = new IdentityHashMap<>();

	private DebugSkirt() {}

	/** The dress: open at the neck and the hem, outward faces, {@code AROUND} quads round and one fewer than the rings down */
	static StrutMesh dress() {
		int down = RINGS.length - 1;
		StrutPoly[] polys = new StrutPoly[AROUND * down];
		int at = 0;

		for (int j = 0; j < down; j++) {
			for (int i = 0; i < AROUND; i++)
				polys[at++] = quad(ring(i, j), ring(i, j + 1), ring(i + 1, j + 1), ring(i + 1, j));
		}

		return new StrutMesh(polys, Vec3d.ZERO, Vec3d.ZERO, false);
	}

	private static StrutVertex ring(int i, int j) {
		double angle = Math.PI * 2 * i / AROUND;

		return vertex(Math.sin(angle) * RINGS[j][1], RINGS[j][0], Math.cos(angle) * RINGS[j][2], Math.sin(angle), 0, Math.cos(angle));
	}

	/** A closed ball: {@code AROUND} round and {@code AROUND / 2} from pole to pole, the poles' quads collapsed to triangles */
	public static StrutMesh ball(double cx, double cy, double cz, double radius) {
		int rows = AROUND / 2;
		StrutPoly[] polys = new StrutPoly[AROUND * rows];
		int at = 0;

		for (int j = 0; j < rows; j++) {
			for (int i = 0; i < AROUND; i++)
				polys[at++] = quad(pole(cx, cy, cz, radius, i, j), pole(cx, cy, cz, radius, i, j + 1), pole(cx, cy, cz, radius, i + 1, j + 1), pole(cx, cy, cz, radius, i + 1, j));
		}

		return new StrutMesh(polys, Vec3d.ZERO, Vec3d.ZERO, false);
	}

	private static StrutVertex pole(double cx, double cy, double cz, double radius, int i, int j) {
		double angle = Math.PI * 2 * i / AROUND;
		double lat = Math.PI * j / (AROUND / 2.0);
		double ring = Math.sin(lat);
		double nx = Math.sin(angle) * ring;
		double ny = Math.cos(lat);
		double nz = Math.cos(angle) * ring;

		return vertex(cx + nx * radius, cy + ny * radius, cz + nz * radius, nx, ny, nz);
	}

	private static StrutPoly quad(StrutVertex a, StrutVertex b, StrutVertex c, StrutVertex d) {
		return new StrutPoly(new StrutVertex[] {a, b, c, d}, new Vector3f(0, 0, 1));
	}

	private static StrutVertex vertex(double x, double y, double z, double nx, double ny, double nz) {
		return new StrutVertex(new Vector3f((float)x, (float)y, (float)z), 0.5f, 0.5f, new Vector3f((float)nx, (float)ny, (float)nz));
	}

	/** Puts the prop called {@code prop} on {@code model} (and takes off any other), or only takes it off when {@code prop} is null; a model with no hip bone is left alone */
	static void sync(BakedStrutModel model, String prop) {
		GroupBoneDefinition hip = model.getBone(BONE).orElse(null);

		if (hip == null)
			return;

		List<StrutMesh> present = ATTACHED.remove(model);

		if (present != null)
			hip.getMeshes().removeAll(present);

		if (prop == null)
			return;

		List<StrutMesh> made = new ArrayList<>();

		if (prop.equals(DRESS)) {
			made.add(dress());
		}
		else if (prop.equals(JELLY)) {
			made.add(ball(0.30, 0.78, -0.05, 0.10));
			made.add(ball(-0.30, 0.78, -0.05, 0.10));
		}

		hip.getMeshes().addAll(made);
		ATTACHED.put(model, made);
	}

	/** {@link #sync(BakedStrutModel, String)} for the loaded model with resource id {@code stem}, if there is one */
	static void sync(Identifier stem, String prop) {
		for (Map.Entry<Identifier, BakedStrutModel> entry : StrutCache.getBakedModels().entrySet()) {
			if (PhysicsConfigs.stem(entry.getKey()).equals(stem))
				sync(entry.getValue(), prop);
		}
	}
}
