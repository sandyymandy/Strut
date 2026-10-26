package com.sandymandy.strut.animation.state;

import org.jetbrains.annotations.Nullable;

/**
 * How one mesh of a model instance is moved this frame (cloth, flesh, soft bodies: see {@link ModelInstance#setMeshDeform}), in whichever shape its writer has it
 * in cheaply. The GPU reads the shape as it is (a value for each welded particle, or one rigid motion for the whole mesh: a few hundred numbers, not a few
 * thousand), and a reader that wants a value for each poly corner (the CPU walk, a mod) asks for those, which are made from the shape when asked
 */
public interface MeshDeform {
	/** x, y and z for each poly corner, four corners to a poly in poly order ({@code polys * 12} floats), in the mesh's own space: how far each is moved from where the bone puts it */
	float[] cornerOffsets();

	/** The shading normal of each corner in the same layout and space, or null to light each face flat from its moved corners */
	@Nullable
	float[] cornerNormals();
}
