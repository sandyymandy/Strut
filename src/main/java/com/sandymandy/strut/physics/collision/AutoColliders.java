package com.sandymandy.strut.physics.collision;

import com.sandymandy.strut.cache.object.BakedStrutModel;
import com.sandymandy.strut.cache.object.GroupBoneDefinition;
import com.sandymandy.strut.cache.object.StrutCube;
import com.sandymandy.strut.cache.object.StrutMesh;
import com.sandymandy.strut.cache.object.StrutPoly;
import com.sandymandy.strut.cache.object.StrutQuad;
import com.sandymandy.strut.cache.object.StrutVertex;
import java.util.Map;
import java.util.Set;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import com.sandymandy.strut.physics.config.PhysicsOptions;

/**
 * Collision shapes made from a model's own geometry, so a jiggle bone or a chain does not sink into the body it belongs to without anyone authoring
 * colliders. A bone with a few cubes gets a box per cube (exactly its shape); a bone with more cubes, or with a mesh, is too detailed to test against, so it
 * gets <em>one low-poly proxy</em>: a capsule if it is long and thin (a limb), otherwise a box around all of it. The proxies are ordinary
 * {@link Collider}s in the bone's own space, so they follow the animation like authored ones
 * <p>
 * Vertices are baked in blocks in the space the bone's geometry is drawn in, whose origin the bone's pose moves about its pivot; a collider is described
 * relative to that pivot in model units, so a corner is simply {@code vertex * 16 - pivot}
 */
public final class AutoColliders {
	/**
	 * @param enabled true to force them on, false to force them off, null to decide by need (on when something collides with {@code body} and no collider is
	 *                authored)
	 * @param maxCubes A bone with more cubes than this (or any mesh) is simplified to one proxy
	 * @param exclude Bone name patterns to leave out ({@code *} at either end); the defaults leave out armor and custom layers
	 * @param layer The layer the shapes are on
	 * @param maxShapes The most shapes made for a model: the biggest bones win
	 * @param modes Per bone: {@code off} (no shape), {@code exact} (a box per cube, however many), {@code simple} (one proxy), or absent for the rules above.
	 *              A mode also overrides the built-in and configured excludes, so an armor bone can be tagged back in
	 * @param precision {@code mesh} (the default): a bone with no mode of its own gets a signed distance field of its real geometry once the model's fields are
	 *                  made ({@link ModelSdfs}), which is as exact as the mesh and as fast as the simplest shape; until then, and with {@code proxy}, the boxes and
	 *                  capsules of {@code maxCubes}
	 */
	public record Options(Boolean enabled, int maxCubes, List<String> exclude, String layer, int maxShapes, Map<String, String> modes, String precision) {
		public static final Options DEFAULT = new Options(null, 3, List.of(), "body", 32, Map.of());

		public Options(Boolean enabled, int maxCubes, List<String> exclude, String layer, int maxShapes) {
			this(enabled, maxCubes, exclude, layer, maxShapes, Map.of(), "mesh");
		}

		public Options(Boolean enabled, int maxCubes, List<String> exclude, String layer, int maxShapes, Map<String, String> modes) {
			this(enabled, maxCubes, exclude, layer, maxShapes, modes, "mesh");
		}

		public Options {
			modes = Map.copyOf(modes);
			precision = "proxy".equals(precision) ? "proxy" : "mesh";
		}

		Options withPrecision(String precision) {
			return new Options(this.enabled, this.maxCubes, this.exclude, this.layer, this.maxShapes, this.modes, precision);
		}
	}

	/** Bones this small (in model units, on their longest side) are not worth a collider */
	private static final float MIN_EXTENT = 1.0f;

	private static final List<String> BUILT_IN_EXCLUDE = List.of("armor*", "custom*");

	private AutoColliders() {}

	public static List<Collider> generate(BakedStrutModel model, Options options) {
		return generate(model, options, Set.of());
	}

	/** @param skip Bones that get no shape: those with shapes of their own authored */
	public static List<Collider> generate(BakedStrutModel model, Options options, Set<String> skip) {
		record Candidate(Collider collider, float volume) {}

		List<Candidate> found = new ArrayList<>();
		// the model's fields, if they are made: null until then, and the simple shapes stand in
		ModelSdfs.ModelSdf fields = "mesh".equals(options.precision()) && PhysicsOptions.meshColliders() ? ModelSdfs.get(model) : null;

		for (GroupBoneDefinition bone : model.flatBones()) {
			if (bone == null || skip.contains(bone.getName()))
				continue;

			String mode = options.modes().getOrDefault(bone.getName(), "auto");

			if (mode.equals("off") || (mode.equals("auto") && (bone.shouldNeverRender() == Boolean.TRUE || excluded(bone.getName(), options))))
				continue;

			List<StrutCube> cubes = bone.getCubes();
			List<StrutMesh> meshes = bone.getMeshes();

			if (cubes.isEmpty() && meshes.isEmpty())
				continue;

			float px = bone.getPivotX();
			float py = bone.getPivotY();
			float pz = bone.getPivotZ();

			MeshSdf field = fields != null && mode.equals("auto") ? fields.of(bone.getName()) : null;

			if (field != null) {
				float volume = (field.nx - 1) * (field.ny - 1) * (field.nz - 1) * field.cell * field.cell * field.cell;

				found.add(new Candidate(new Collider(bone.getName(), Collider.Shape.MESH, new Vector3f(), new Vector3f(), 0f, options.layer()), volume));

				continue;
			}

			boolean exact = mode.equals("exact") || (mode.equals("auto") && cubes.size() <= options.maxCubes());

			if (meshes.isEmpty() && exact && !mode.equals("simple")) {
				for (StrutCube cube : cubes) {
					float[] box = new float[] {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};

					for (StrutQuad quad : cube.quads())
						grow(box, quad.vertices(), px, py, pz);

					box(bone, box, options, (c, v) -> found.add(new Candidate(c, v)));
				}

				continue;
			}

			float[] box = new float[] {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};

			for (StrutCube cube : cubes) {
				for (StrutQuad quad : cube.quads())
					grow(box, quad.vertices(), px, py, pz);
			}

			for (StrutMesh mesh : meshes) {
				for (StrutPoly poly : mesh.polys())
					grow(box, poly.vertices(), px, py, pz);
			}

			proxy(bone, box, options, (c, v) -> found.add(new Candidate(c, v)));
		}

		found.sort(Comparator.comparingDouble(Candidate::volume).reversed());

		List<Collider> out = new ArrayList<>();

		// a field is one shape a bone, however detailed the bone: there is room for every bone of a model
		int limit = fields != null ? Math.max(options.maxShapes(), 256) : options.maxShapes();

		for (int i = 0; i < found.size() && i < limit; i++)
			out.add(found.get(i).collider());

		return out;
	}

	private static void grow(float[] box, StrutVertex[] vertices, float px, float py, float pz) {
		for (StrutVertex vertex : vertices) {
			Vector3f p = vertex.position();
			float x = p.x * 16f - px;
			float y = p.y * 16f - py;
			float z = p.z * 16f - pz;

			box[0] = Math.min(box[0], x);
			box[1] = Math.min(box[1], y);
			box[2] = Math.min(box[2], z);
			box[3] = Math.max(box[3], x);
			box[4] = Math.max(box[4], y);
			box[5] = Math.max(box[5], z);
		}
	}

	private interface Sink {
		void add(Collider collider, float volume);
	}

	/** One cube: a box exactly around its corners */
	private static void box(GroupBoneDefinition bone, float[] b, Options options, Sink sink) {
		float sx = b[3] - b[0];
		float sy = b[4] - b[1];
		float sz = b[5] - b[2];

		if (Math.max(sx, Math.max(sy, sz)) < MIN_EXTENT)
			return;

		sink.add(new Collider(bone.getName(), Collider.Shape.BOX, new Vector3f((b[0] + b[3]) / 2, (b[1] + b[4]) / 2, (b[2] + b[5]) / 2), new Vector3f(sx, sy, sz), 0f, options.layer()),
				sx * sy * sz);
	}

	/** A whole detailed bone reduced to one shape: a capsule if it is long and thin, else a box */
	private static void proxy(GroupBoneDefinition bone, float[] b, Options options, Sink sink) {
		float[] size = {b[3] - b[0], b[4] - b[1], b[5] - b[2]};
		float[] centre = {(b[0] + b[3]) / 2, (b[1] + b[4]) / 2, (b[2] + b[5]) / 2};
		int longest = 0;

		for (int i = 1; i < 3; i++) {
			if (size[i] > size[longest])
				longest = i;
		}

		float a = size[(longest + 1) % 3];
		float c = size[(longest + 2) % 3];
		float thick = Math.max(a, c);

		if (size[longest] < MIN_EXTENT)
			return;

		if (size[longest] > 2.2f * thick && thick >= 0.5f) {
			float radius = (a + c) / 4f;
			float half = Math.max(0f, size[longest] / 2f - radius);
			Vector3f from = new Vector3f(centre[0], centre[1], centre[2]);
			Vector3f to = new Vector3f(centre[0], centre[1], centre[2]);

			from.setComponent(longest, centre[longest] - half);
			to.setComponent(longest, centre[longest] + half);
			sink.add(new Collider(bone.getName(), Collider.Shape.CAPSULE, from, to, radius, options.layer()), size[0] * size[1] * size[2]);

			return;
		}

		sink.add(new Collider(bone.getName(), Collider.Shape.BOX, new Vector3f(centre[0], centre[1], centre[2]), new Vector3f(size[0], size[1], size[2]), 0f, options.layer()),
				size[0] * size[1] * size[2]);
	}

	private static boolean excluded(String name, Options options) {
		for (String pattern : BUILT_IN_EXCLUDE) {
			if (matches(pattern, name))
				return true;
		}

		for (String pattern : options.exclude()) {
			if (matches(pattern, name))
				return true;
		}

		return false;
	}

	public static boolean matches(String pattern, String name) {
		String p = pattern.toLowerCase();
		String n = name.toLowerCase();

		if (p.startsWith("*") && p.endsWith("*") && p.length() > 2)
			return n.contains(p.substring(1, p.length() - 1));

		if (p.endsWith("*"))
			return n.startsWith(p.substring(0, p.length() - 1));

		if (p.startsWith("*"))
			return n.endsWith(p.substring(1));

		return n.equals(p);
	}
}
