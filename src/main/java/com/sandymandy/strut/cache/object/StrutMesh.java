package com.sandymandy.strut.cache.object;

import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

/**
 * Baked free-form geometry for a {@link GroupBoneDefinition}
 * <p>
 * The mesh counterpart to {@link StrutCube}, and handled the same way by the renderer: its
 * vertices are already in the owning bone's space, and its own pivot and rotation exist only
 * so the mesh can be turned about a point of its own
 *
 * @param polys The mesh's faces, already triangulated and wound
 * @param pivot The point the mesh rotates about, in model units
 * @param rotation The mesh's own rotation, in radians
 * @param hidden Whether the mesh should be skipped when rendering
 * @param skin The mesh's binding to an armature, or null if it moves rigidly with its bone
 * @param restPositions The mesh's unskinned vertex positions, indexed by source vertex, or null
 *                      if the mesh is unskinned. Kept separate from {@code polys} because
 *                      triangulation duplicates a vertex across every face that shares it, and
 *                      skinning has to transform each one exactly once
 * @param restNormals The mesh's unskinned, already-smoothed vertex normals, indexed the same way
 *                    as {@code restPositions}, or null unless the mesh is both skinned and
 *                    smooth-shaded. A flat skinned mesh has no use for this - it recomputes its
 *                    normals from the deformed corners every frame instead - but a smooth one has
 *                    no per-face normal to fall back on, so its rest normals must be deformed the
 *                    same way its rest positions are
 */
public record StrutMesh(StrutPoly[] polys, Vec3d pivot, Vec3d rotation, boolean hidden,
					  @Nullable StrutSkin skin, @Nullable Vector3f[] restPositions,
					  @Nullable Vector3f[] restNormals) {
	public StrutMesh(StrutPoly[] polys, Vec3d pivot, Vec3d rotation, boolean hidden) {
		this(polys, pivot, rotation, hidden, null, null, null);
	}

	public boolean isSkinned() {
		return this.skin != null;
	}
}
