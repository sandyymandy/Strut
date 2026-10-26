package com.sandymandy.strut.renderer.gpu;

/**
 * Which group's mesh deformation is bound now, for the passes that read it (the vertex shader here, the pose pass of a shader pack): set by
 * {@link GpuRenderBackend#setDeform} while a group is drawn and cleared after, so it never leaks to the next group
 */
public final class MeshDeformState {
	/** Whether the buffers below hold deformation to apply */
	public static boolean active;
	/** The group's packed buffer ({@link MeshDeformPack}) */
	public static int dataSsbo;
	/** The model's static element matrices, one for each mesh */
	public static int meshSsbo;
	/** The model's deform stream: {@code (mesh, corner)} for each vertex */
	public static int streamVbo;
	public static int meshCount;
	public static int offsetBase;
	public static int normalBase;
	public static boolean hasNormals;

	private MeshDeformState() {}
}
