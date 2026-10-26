package com.sandymandy.strut.loading.binary.raw;

import java.util.Map;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.List;

/**
 * A parsed {@code .strutmodel}, one record per thing the file says, and nothing resolved
 * <p>
 * Indices are still indices, angles are still degrees, and positions are still in the
 * model's own units. Resolving a parent index to a bone, converting a unit or triangulating
 * a face is the bake's work - a parse that did any of it could not be checked against the
 * file format alone
 *
 * @param version The format version the file declared
 * @param modelId The identifier the file gives itself, informational only; a model is loaded
 *                by the path that was asked for, not by what it calls itself
 * @param unitsPerBlock The authoring grid, used by the bake to normalise to Minecraft's 16
 * @param boundsMin The model's minimum extent, in model units
 * @param boundsMax The model's maximum extent, in model units
 * @param strings The string table, exposed so a consumer can resolve an index this layer left alone
 * @param textures The textures the model's faces are measured against
 * @param groupBones The rigid, Bedrock-style hierarchy; geometry hangs off these
 * @param meshes Free-form geometry; a skinned mesh carries per-vertex influences
 * @param cubes Boxes, with either per-face UV rectangles or Bedrock box UVs
 * @param armatures Weighted skeletons that deform meshes
 */
public record RawBinaryModel(int version, @Nullable String modelId, float unitsPerBlock,
							 Vector3f boundsMin, Vector3f boundsMax, List<String> strings,
							 List<RawTexture> textures, List<RawGroupBone> groupBones,
							 List<RawMesh> meshes, List<RawCube> cubes, List<RawArmature> armatures,
							 Map<String, byte[]> extensions) {
	/**
	 * A model with no extension sections, which is every model exported before they existed
	 */
	public RawBinaryModel(int version, @Nullable String modelId, float unitsPerBlock, Vector3f boundsMin, Vector3f boundsMax, List<String> strings,
						  List<RawTexture> textures, List<RawGroupBone> groupBones, List<RawMesh> meshes, List<RawCube> cubes, List<RawArmature> armatures) {
		this(version, modelId, unitsPerBlock, boundsMin, boundsMax, strings, textures, groupBones, meshes, cubes, armatures, Map.of());
	}

	/**
	 * A texture, and the UV grid its faces are measured against
	 * <p>
	 * The grid is recorded separately from the pixel size because face coordinates have
	 * always been measured against it, and the two only coincide when a texture is unwrapped
	 * at its own resolution
	 */
	public record RawTexture(String name, int width, int height, int uvWidth, int uvHeight) {}

	/**
	 * A reference from a group bone to one piece of geometry
	 *
	 * @param kind Which block {@code index} points into; see {@code ELEMENT_KIND_*}
	 * @param index The index within that block
	 */
	public record RawElement(int kind, int index) {}

	/**
	 * A rigid bone in the Bedrock-style hierarchy
	 *
	 * @param parentIndex The index of this bone's parent within the same block, or -1 for a root
	 */
	public record RawGroupBone(String name, int parentIndex, Vector3f pivot, Vector3f rotation,
							   List<RawElement> elements) {}

	/**
	 * A free-form mesh
	 *
	 * @param armatureIndex The armature this mesh's influences index into, or -1 if unskinned
	 * @param positions Vertex positions relative to {@code origin}, three floats per vertex
	 * @param skin One entry per vertex, or empty if the mesh is not skinned
	 * @param smooth Whether adjacent faces blend their normals at a shared vertex, rather than
	 *               each rendering with its own flat face normal
	 */
	public record RawMesh(String name, int textureIndex, Vector3f origin, Vector3f rotation,
						  boolean mirror, int armatureIndex, float[] positions,
						  List<RawFace> faces, List<List<RawInfluence>> skin, boolean smooth) {
		public int vertexCount() {
			return this.positions.length / 3;
		}

		public boolean isSkinned() {
			return !this.skin.isEmpty();
		}
	}

	/**
	 * One face of a mesh
	 *
	 * @param vertices Indices into the owning mesh's vertex array, one per corner
	 * @param uvs Texture coordinates, two floats per corner, measured against the texture's UV grid
	 */
	public record RawFace(int[] vertices, float[] uvs) {
		public int cornerCount() {
			return this.vertices.length;
		}
	}

	/**
	 * One bone's pull on one vertex
	 *
	 * @param boneIndex The index of the bone within its armature
	 * @param weight The bone's share of the vertex, as authored
	 */
	public record RawInfluence(int boneIndex, float weight) {}

	/**
	 * A box
	 *
	 * @param boxUV Whether {@code faces} holds Bedrock box UVs rather than per-face rectangles
	 */
	public record RawCube(String name, int textureIndex, Vector3f origin, Vector3f rotation,
						  Vector3f from, Vector3f to, float inflate,
						  boolean mirror, boolean boxUV, List<RawCubeFace> faces) {}

	/**
	 * One face of a box
	 *
	 * @param faceId Which of the six faces this is, 0 to 5
	 * @param uvRotation The face's UV rotation, in 90-degree steps
	 */
	public record RawCubeFace(int faceId, float u1, float v1, float u2, float v2, int uvRotation) {}

	/**
	 * A weighted skeleton
	 *
	 * @param parentGroupBone The group bone this armature hangs off, or -1 if it is a root
	 */
	public record RawArmature(String name, Vector3f origin, int parentGroupBone, List<RawBone> bones) {}

	/**
	 * One bone of an armature
	 *
	 * @param parentIndex The index of this bone's parent within the same armature, or -1 for a root
	 * @param head The bone's origin, in model units
	 * @param length The bone's length along its own axis, used to place its tail
	 * @param width The bone's display thickness in Blockbench's own editor - carried for round-trip
	 *              fidelity only, like {@code length}; nothing in this engine's own rendering reads it
	 * @param colour The bone's display colour, one of Blockbench's own fixed marker colour indices -
	 *               carried for round-trip fidelity only, same as {@code width}
	 */
	public record RawBone(String name, int parentIndex, Vector3f head, Vector3f rotation,
						  float length, float width, int colour) {}
}
