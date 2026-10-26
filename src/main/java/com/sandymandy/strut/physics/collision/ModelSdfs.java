package com.sandymandy.strut.physics.collision;

import com.sandymandy.strut.cache.object.BakedStrutModel;
import com.sandymandy.strut.cache.object.GroupBoneDefinition;
import com.sandymandy.strut.cache.object.StrutCube;
import com.sandymandy.strut.cache.object.StrutMesh;
import com.sandymandy.strut.cache.object.StrutPoly;
import com.sandymandy.strut.cache.object.StrutQuad;
import com.sandymandy.strut.cache.object.StrutVertex;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Vector3f;

import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.sandymandy.strut.physics.PhysicsPoseProcessor;

/**
 * The signed distance field of every bone of a model, made once from the model's own geometry on a background thread the first time something asks for it
 * (nobody authors anything). A bone's field is in the space its geometry is drawn in, relative to its pivot, so it rides the bone like any other collider
 * <p>
 * Until a model's fields are ready {@link #get} answers null and the callers use the simple shapes they always had; when they are, the callers switch over (see
 * {@link PhysicsPoseProcessor}). A resource reload makes new models, so it drops the old fields ({@link #clear}); building is off by default and switched on by the
 * client, so a test sees only the behaviour it set up
 */
public final class ModelSdfs {
	private static final Logger LOGGER = LogManager.getLogger("Strut Physics");

	/** How far from the surface a field is meaningful: a part pushed out of a body is never asked about more than this far */
	static final float BAND = 0.25f;
	/** The most cells along the longest side of a bone's grid */
	static final int MAX_DIM = 64;
	/** A bone this small on its longest side (model units) is not worth a shape */
	static final float MIN_EXTENT = 1.0f;

	/** What was made for one model: a field for each bone that has geometry, by name */
	public record ModelSdf(Map<String, MeshSdf> bones, long nodes, long millis) {
		MeshSdf of(String bone) {
			return this.bones.get(bone);
		}
	}

	private static final Object BUILDING = new Object();
	private static final Map<BakedStrutModel, Object> CACHE = Collections.synchronizedMap(new IdentityHashMap<>());
	private static volatile boolean enabled;
	private static ExecutorService executor;

	private ModelSdfs() {}

	/** Whether fields are built at all: the client switches it on; a test that wants them builds them itself */
	public static void enabled(boolean value) {
		enabled = value;
	}

	static boolean enabled() {
		return enabled;
	}

	public static void clear() {
		CACHE.clear();
	}

	/**
	 * The model's fields, or null while they are being made (or if building is off, or the model has no geometry). The first call starts the build
	 */
	static ModelSdf get(BakedStrutModel model) {
		if (!enabled)
			return null;

		Object found = CACHE.get(model);

		if (found instanceof ModelSdf sdf)
			return sdf;

		if (found == null) {
			synchronized (CACHE) {
				if (CACHE.get(model) == null) {
					CACHE.put(model, BUILDING);
					start(model);
				}
			}
		}

		return null;
	}

	/** One line for the colliders command: how many models have fields, and how much they hold */
	public static String summary() {
		int models = 0;
		int pending = 0;
		long bones = 0;
		long nodes = 0;

		synchronized (CACHE) {
			for (Object value : CACHE.values()) {
				if (value instanceof ModelSdf sdf) {
					models++;
					bones += sdf.bones().size();
					nodes += sdf.nodes();
				}
				else {
					pending++;
				}
			}
		}

		return String.format("%d model(s) made (%d bones, %.1f MB), %d being made.", models, bones, nodes * 4 / 1048576.0, pending);
	}

	/** Gives a model its fields without a build, for a test */
	static void put(BakedStrutModel model, ModelSdf fields) {
		CACHE.put(model, fields);
	}

	/** Whether {@link #get} would answer now */
	public static boolean ready(BakedStrutModel model) {
		return CACHE.get(model) instanceof ModelSdf;
	}

	private static synchronized void start(BakedStrutModel model) {
		if (executor == null) {
			executor = Executors.newSingleThreadExecutor(r -> {
				Thread thread = new Thread(r, "Strut mesh colliders");

				thread.setDaemon(true);
				thread.setPriority(Thread.MIN_PRIORITY);

				return thread;
			});
		}

		executor.execute(() -> {
			try {
				CACHE.put(model, buildAll(model));
			}
			catch (RuntimeException ex) {
				LOGGER.warn("Could not make mesh colliders for a model: {}", ex.toString());
				CACHE.put(model, new ModelSdf(Map.of(), 0, 0));
			}
		});
	}

	/** Makes the fields of every bone of {@code model} now, on this thread */
	static ModelSdf buildAll(BakedStrutModel model) {
		long start = System.nanoTime();
		Map<String, MeshSdf> bones = new HashMap<>();
		long nodes = 0;

		for (GroupBoneDefinition bone : model.flatBones()) {
			if (bone == null)
				continue;

			MeshSdf sdf = buildBone(bone);

			if (sdf != null) {
				bones.put(bone.getName(), sdf);
				nodes += sdf.nodes();
			}
		}

		long millis = (System.nanoTime() - start) / 1_000_000;

		LOGGER.info("Made mesh colliders for {} bones ({} numbers) in {} ms", bones.size(), nodes, millis);

		return new ModelSdf(Map.copyOf(bones), nodes, millis);
	}

	/** The field of one bone's own cubes and meshes (not its children's), or null if it has none worth a shape */
	static MeshSdf buildBone(GroupBoneDefinition bone) {
		int quads = 0;

		for (StrutCube cube : bone.getCubes())
			quads += cube.quads().length;

		for (StrutMesh mesh : bone.getMeshes())
			quads += mesh.polys().length;

		if (quads == 0)
			return null;

		// each quad is two triangles; a mesh's triangle is a quad with a repeated corner, whose second triangle is nothing
		float[] triangles = new float[quads * 2 * 9];
		int[] at = {0};
		float px = bone.getPivotX() / 16f;
		float py = bone.getPivotY() / 16f;
		float pz = bone.getPivotZ() / 16f;
		float[] lo = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
		float[] hi = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};

		for (StrutCube cube : bone.getCubes()) {
			for (StrutQuad quad : cube.quads())
				addQuad(quad.vertices(), px, py, pz, triangles, at, lo, hi);
		}

		for (StrutMesh mesh : bone.getMeshes()) {
			for (StrutPoly poly : mesh.polys())
				addQuad(poly.vertices(), px, py, pz, triangles, at, lo, hi);
		}

		float extent = Math.max(hi[0] - lo[0], Math.max(hi[1] - lo[1], hi[2] - lo[2])) * 16f;

		if (extent < MIN_EXTENT)
			return null;

		return MeshSdf.build(triangles, at[0] / 9, BAND, MAX_DIM);
	}

	private static void addQuad(StrutVertex[] v, float px, float py, float pz, float[] out, int[] at, float[] lo, float[] hi) {
		if (v.length < 3)
			return;

		for (int[] tri : v.length >= 4 ? new int[][] {{0, 1, 2}, {0, 2, 3}} : new int[][] {{0, 1, 2}}) {
			for (int corner : tri) {
				Vector3f p = v[corner].position();
				float x = p.x - px;
				float y = p.y - py;
				float z = p.z - pz;

				out[at[0]++] = x;
				out[at[0]++] = y;
				out[at[0]++] = z;
				lo[0] = Math.min(lo[0], x);
				lo[1] = Math.min(lo[1], y);
				lo[2] = Math.min(lo[2], z);
				hi[0] = Math.max(hi[0], x);
				hi[1] = Math.max(hi[1], y);
				hi[2] = Math.max(hi[2], z);
			}
		}
	}
}
