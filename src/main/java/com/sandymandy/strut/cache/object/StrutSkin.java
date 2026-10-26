package com.sandymandy.strut.cache.object;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * The per-vertex binding between a {@link StrutMesh} and a {@link StrutArmature}
 * <p>
 * Skinning runs on the CPU. Minecraft's entity pipeline has no vertex-skinning stage, and
 * adding one would mean a custom shader, which would put Strut at odds with Iris, Sodium and
 * every existing render layer. Transforming vertices in Java before they reach the buffer costs
 * more per vertex and nothing in compatibility
 *
 * <h2>Layout</h2>
 *
 * <p>Influences are fixed at four per vertex, padded with zero weights. The exporter caps,
 * sorts and normalises them, so the inner loop is a flat unrolled blend with no sorting, no
 * branching on count, and no bounds arithmetic per influence
 */
public final class StrutSkin {
	/** The most influences a single vertex may carry */
	public static final int MAX_INFLUENCES = 4;

	private final StrutArmature armature;
	private final int[] boneIndices;
	private final float[] weights;
	private final int[] polyCorners;

	/**
	 * @param boneIndices Four bone indices per vertex
	 * @param weights Four weights per vertex, padded with zeroes
	 * @param polyCorners Four source vertex indices per poly, mapping a baked corner back to the
	 *                    vertex it came from - triangulation duplicates corners across polys, so
	 *                    this is what lets each unique vertex be skinned exactly once
	 */
	public StrutSkin(StrutArmature armature, int[] boneIndices, float[] weights, int[] polyCorners) {
		this.armature = armature;
		this.boneIndices = boneIndices;
		this.weights = weights;
		this.polyCorners = polyCorners;
	}

	/**
	 * How many unique source vertices this skin covers, and so how long a caller's deformed
	 * vertex buffer must be
	 */
	public int vertexCount() {
		return this.weights.length / MAX_INFLUENCES;
	}

	public StrutArmature getArmature() {
		return this.armature;
	}

	/**
	 * The source vertex a poly's corner binds to - the same indexing {@link #positionOf}/
	 * {@link #normalOf} use, exposed directly for a caller (the GPU bake) that
	 * needs a corner's own raw influences rather than an already-blended position or normal
	 */
	public int sourceVertexOf(int poly, int corner) {
		return this.polyCorners[poly * 4 + corner];
	}

	/**
	 * Source vertex {@code vertex}'s {@code influence}-th bone index (0-3, per {@link #MAX_INFLUENCES}) -
	 * meaningless where {@link #weight} for the same slot is zero, exactly like every other reader of
	 * this fixed-width, zero-padded layout
	 */
	public int boneIndex(int vertex, int influence) {
		return this.boneIndices[vertex * MAX_INFLUENCES + influence];
	}

	/**
	 * Source vertex {@code vertex}'s {@code influence}-th weight (0-3, per {@link #MAX_INFLUENCES}),
	 * zero for a pad slot
	 */
	public float weight(int vertex, int influence) {
		return this.weights[vertex * MAX_INFLUENCES + influence];
	}

	/**
	 * Transform every vertex of the mesh by its bones' current skinning matrices
	 * <p>
	 * Linear blend skinning: {@code p' = Σ wᵢ · (Mᵢ · p)}. Each unique vertex is transformed
	 * once, however many faces share it
	 * <p>
	 * Both the matrices read and the buffer written belong to the animatable instance being posed.
	 * A skin is baked once and shared by every animatable using the model, so it holds the binding
	 * - which bones, at what weight - and never the result of applying it
	 *
	 * @param rest The mesh's rest positions, indexed by source vertex
	 * @param skinMatrices The posing instance's skinning matrices for {@link #getArmature()}
	 * @param out Receives the deformed positions, {@link #vertexCount()} long
	 */
	public void skin(Vector3f[] rest, Matrix4f[] skinMatrices, Vector3f[] out) {
		Vector3f blended = new Vector3f();
		Vector3f contribution = new Vector3f();

		for (int vertex = 0; vertex < out.length; vertex++) {
			blended.set(0, 0, 0);

			int base = vertex * MAX_INFLUENCES;
			float totalWeight = 0;

			for (int i = 0; i < MAX_INFLUENCES; i++) {
				float weight = this.weights[base + i];

				if (weight == 0)
					continue;

				Matrix4f matrix = skinMatrices[this.boneIndices[base + i]];

				matrix.transformPosition(contribution.set(rest[vertex]));
				blended.add(contribution.mul(weight));
				totalWeight += weight;
			}

			// Matches Blockbench's own calculateVertexDeformation: an unweighted vertex stays at rest
			// rather than collapsing to the origin, and weights are normalised to sum to one
			if (totalWeight == 0)
				out[vertex].set(rest[vertex]);
			else
				out[vertex].set(blended).div(totalWeight);
		}
	}

	/**
	 * The deformed position of a poly's corner, read out of the buffer {@link #skin} wrote
	 *
	 * @param skinned The posing instance's deformed vertex buffer for this skin
	 */
	public Vector3f positionOf(Vector3f[] skinned, int poly, int corner) {
		return skinned[this.polyCorners[poly * 4 + corner]];
	}

	/**
	 * The deformed normal of a poly's corner, read out of the buffer {@link #skinNormals} wrote
	 * <p>
	 * Indexed identically to {@link #positionOf}, since a smooth mesh's rest normals share the
	 * same per-source-vertex indexing as its rest positions
	 *
	 * @param skinnedNormals The posing instance's deformed normal buffer for this skin
	 */
	public Vector3f normalOf(Vector3f[] skinnedNormals, int poly, int corner) {
		return skinnedNormals[this.polyCorners[poly * 4 + corner]];
	}

	/**
	 * Transform every rest normal of a smooth-shaded skinned mesh by the same blend of bone
	 * matrices used to transform its positions
	 * <p>
	 * A direction, unlike a position, is blended with {@link Matrix4f#transformDirection} rather
	 * than {@link Matrix4f#transformPosition} - it must not pick up any of a bone's translation -
	 * and the blend of several unit normals is not itself a unit vector, so the result is
	 * renormalised once the weighted sum is complete
	 *
	 * @param restNormals The mesh's rest normals, indexed by source vertex
	 * @param skinMatrices The posing instance's skinning matrices for {@link #getArmature()}
	 * @param out Receives the deformed normals, {@link #vertexCount()} long
	 */
	public void skinNormals(Vector3f[] restNormals, Matrix4f[] skinMatrices, Vector3f[] out) {
		Vector3f blended = new Vector3f();
		Vector3f contribution = new Vector3f();

		for (int vertex = 0; vertex < out.length; vertex++) {
			blended.set(0, 0, 0);

			int base = vertex * MAX_INFLUENCES;
			boolean weighted = false;

			for (int i = 0; i < MAX_INFLUENCES; i++) {
				float weight = this.weights[base + i];

				if (weight == 0)
					continue;

				Matrix4f matrix = skinMatrices[this.boneIndices[base + i]];

				matrix.transformDirection(contribution.set(restNormals[vertex]));
				blended.add(contribution.mul(weight));
				weighted = true;
			}

			// An unweighted vertex keeps its rest normal, the same fallback skin() uses for position
			if (weighted)
				out[vertex].set(blended).normalize();
			else
				out[vertex].set(restNormals[vertex]);
		}
	}
}
