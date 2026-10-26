package com.sandymandy.strut.cache.object;

import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A mesh's corners welded into particles: corners at one place (to 1/4096 of a unit) are one particle, numbered in the order they are first met. The same
 * numbering is made by whoever needs it (the cloth solver, whose particles these are, and the GPU bake, which tells each vertex which particle it is), so a
 * deformation given for each particle reaches each corner without a table being sent along
 *
 * @param cornerParticle the particle of each corner, {@code polygon * 4 + corner}
 * @param positions each particle's position in the mesh's space, as first met
 */
public record MeshWeld(int[] cornerParticle, double[][] positions) {
	public static MeshWeld of(StrutMesh mesh) {
		Map<List<Integer>, Integer> welded = new HashMap<>();
		List<double[]> positions = new ArrayList<>();
		StrutPoly[] polys = mesh.polys();
		int[] corner = new int[polys.length * 4];

		for (int p = 0; p < polys.length; p++) {
			for (int c = 0; c < 4; c++) {
				Vector3f v = polys[p].vertices()[c].position();
				List<Integer> key = List.of(Math.round(v.x * 4096f), Math.round(v.y * 4096f), Math.round(v.z * 4096f));
				Integer at = welded.get(key);

				if (at == null) {
					at = positions.size();
					welded.put(key, at);
					positions.add(new double[] {v.x, v.y, v.z});
				}

				corner[p * 4 + c] = at;
			}
		}

		return new MeshWeld(corner, positions.toArray(new double[0][]));
	}

	/** How many particles the mesh welds into */
	public int particles() {
		return this.positions.length;
	}
}
