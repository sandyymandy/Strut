package com.sandymandy.strut.cache.object;

import com.sandymandy.strut.util.RenderUtil;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns the cubes of a bone into meshes, so what moves meshes (cloth, flesh) can move them too
 * <p>
 * A cube has eight corners, which is too few to bend: each face is cut into a grid of quads, about {@link #CELL} model units across, and the grid's corners are
 * what the soft body moves. The geometry, the texture coordinates and the shading are those of the cube, so until something moves it nothing is different
 * to look at
 */
public final class CubeMeshes {
	/** About how wide one quad of a cut face is, in model units (a sixteenth of a block) */
	private static final float CELL = 1.25f;

	/** The most quads along one edge of a face: a large cube is not worth more */
	private static final int MOST = 6;

	/** Told, with the model, when cubes of it were replaced: what was made from its cubes (the GPU's copy) is out of date */
	public static volatile java.util.function.Consumer<BakedStrutModel> onConverted;

	private CubeMeshes() {}

	/** {@link #convertUnder(GroupBoneDefinition)} for a model's bone, telling {@link #onConverted} if anything was replaced */
	public static boolean convertUnder(BakedStrutModel model, GroupBoneDefinition bone) {
		boolean any = convertUnder(bone);
		java.util.function.Consumer<BakedStrutModel> listener = onConverted;

		if (any && listener != null)
			listener.accept(model);

		return any;
	}

	/**
	 * Replaces the cubes of {@code bone} and everything under it with meshes
	 *
	 * @return Whether there was anything to replace
	 */
	public static boolean convertUnder(GroupBoneDefinition bone) {
		boolean any = convert(bone);

		for (GroupBoneDefinition child : bone.getChildBones())
			any |= convertUnder(child);

		return any;
	}

	/** Replaces the cubes of {@code bone} alone with meshes: the meshes are added first, so what reads the bone meanwhile always finds the geometry */
	public static boolean convert(GroupBoneDefinition bone) {
		List<StrutCube> cubes = bone.getCubes();

		if (cubes.isEmpty())
			return false;

		List<StrutMesh> made = new ArrayList<>();
		List<StrutCube> kept = new ArrayList<>();

		for (StrutCube cube : new ArrayList<>(cubes)) {
			// a flat cube (a plane) is two faces on one plane and is shaded as a pair: it stays as it is
			if (cube.size().x == 0 || cube.size().y == 0 || cube.size().z == 0) {
				kept.add(cube);

				continue;
			}

			made.add(mesh(cube));
		}

		if (made.isEmpty())
			return false;

		bone.getMeshes().addAll(made);
		bone.getCubes().clear();
		bone.getCubes().addAll(kept);

		return true;
	}

	/** One cube as a mesh: each face cut into a grid, wound as the face is, and carrying the face's normal */
	public static StrutMesh mesh(StrutCube cube) {
		List<StrutPoly> polys = new ArrayList<>();

		for (StrutQuad quad : cube.quads()) {
			if (quad == null)
				continue;

			Vector3f normal = new Vector3f(quad.normal());

			RenderUtil.fixInvertedFlatCube(cube, normal);

			StrutVertex[] c = quad.vertices();
			int across = cuts(c[0].position(), c[1].position());
			int down = cuts(c[0].position(), c[3].position());

			for (int j = 0; j < down; j++) {
				for (int i = 0; i < across; i++) {
					StrutVertex[] corners = {
							at(c, (float)i / across, (float)j / down, normal),
							at(c, (float)(i + 1) / across, (float)j / down, normal),
							at(c, (float)(i + 1) / across, (float)(j + 1) / down, normal),
							at(c, (float)i / across, (float)(j + 1) / down, normal)};

					polys.add(new StrutPoly(corners, new Vector3f(normal)));
				}
			}
		}

		return new StrutMesh(polys.toArray(new StrutPoly[0]), cube.pivot(), cube.rotation(), false);
	}

	private static int cuts(Vector3f a, Vector3f b) {
		// the positions are in blocks: a sixteenth of one is a model unit
		float length = a.distance(b) * 16f;

		return Math.max(1, Math.min(MOST, Math.round(length / CELL)));
	}

	/** The point of the quad {@code s} of the way from corner 0 to corner 1 and {@code t} of the way from corner 0 to corner 3, with the texture coordinates there */
	private static StrutVertex at(StrutVertex[] c, float s, float t, Vector3f normal) {
		float w0 = (1 - s) * (1 - t);
		float w1 = s * (1 - t);
		float w2 = s * t;
		float w3 = (1 - s) * t;
		Vector3f position = new Vector3f()
				.fma(w0, c[0].position()).fma(w1, c[1].position()).fma(w2, c[2].position()).fma(w3, c[3].position());
		float u = w0 * c[0].texU() + w1 * c[1].texU() + w2 * c[2].texU() + w3 * c[3].texU();
		float v = w0 * c[0].texV() + w1 * c[1].texV() + w2 * c[2].texV() + w3 * c[3].texV();

		return new StrutVertex(position, u, v, new Vector3f(normal));
	}
}
