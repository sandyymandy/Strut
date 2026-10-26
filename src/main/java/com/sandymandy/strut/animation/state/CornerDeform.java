package com.sandymandy.strut.animation.state;

import org.jetbrains.annotations.Nullable;

/** A deformation given as a value for each poly corner, the form {@link ModelInstance#setMeshOffsets} takes: the arrays are kept, not copied */
public record CornerDeform(float[] offsets, @Nullable float[] normals) implements MeshDeform {
	@Override
	public float[] cornerOffsets() {
		return this.offsets;
	}

	@Override
	@Nullable
	public float[] cornerNormals() {
		return this.normals;
	}
}
