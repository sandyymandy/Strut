package com.sandymandy.strut.loading.binary;

import com.sandymandy.strut.loading.binary.raw.RawBinaryModel;
import com.sandymandy.strut.loading.binary.raw.RawBinaryModel.RawArmature;
import com.sandymandy.strut.loading.binary.raw.RawBinaryModel.RawBone;
import com.sandymandy.strut.loading.binary.raw.RawBinaryModel.RawCube;
import com.sandymandy.strut.loading.binary.raw.RawBinaryModel.RawCubeFace;
import com.sandymandy.strut.loading.binary.raw.RawBinaryModel.RawElement;
import com.sandymandy.strut.loading.binary.raw.RawBinaryModel.RawFace;
import com.sandymandy.strut.loading.binary.raw.RawBinaryModel.RawGroupBone;
import com.sandymandy.strut.loading.binary.raw.RawBinaryModel.RawInfluence;
import com.sandymandy.strut.loading.binary.raw.RawBinaryModel.RawMesh;
import com.sandymandy.strut.loading.binary.raw.RawBinaryModel.RawTexture;
import org.joml.Vector3f;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.sandymandy.strut.loading.binary.StrutBinaryFormats.*;

/**
 * Reads a {@code .strutmodel} file into a {@link RawBinaryModel}
 * <p>
 * Little-endian binary behind a four-byte {@code STMD} magic, laid out as a fixed-width
 * header, a string table, and then one block per element kind:
 *
 * <pre>
 *   [HEADER 64 bytes]
 *   [STRING TABLE]      uint16-length-prefixed UTF-8, indexed everywhere else
 *   [TEXTURES]          name, pixel size, and the UV grid faces are measured against
 *   [GROUP BONES]       the rigid, Bedrock-style hierarchy; geometry hangs off bones
 *   [MESHES]            free-form; skinned ones carry per-vertex influences
 *   [CUBES]             boxes, with per-face UV rectangles or Bedrock box UVs
 *   [ARMATURES]         weighted skeletons
 * </pre>
 *
 * <h2>What this does not do</h2>
 *
 * <p>It resolves nothing. A parent is still an index, a unit is still whatever the model was
 * authored in, and a face is still an n-gon. Cross-references are range-checked but not
 * followed, because a file whose own indices are inconsistent should be refused here rather
 * than becoming an {@link ArrayIndexOutOfBoundsException} thrown from somewhere in the bake
 * that names neither the file nor the field
 */
public final class BinaryModelReader {
	private BinaryModelReader() {}

	// ===============================================================
	// Entry points
	// ===============================================================

	/**
	 * Read a model from a stream, closing it
	 *
	 * @param stream The file's bytes
	 * @param source The file's name or path, quoted in any error
	 * @throws BinaryFormatException If the bytes are not a readable model
	 */
	public static RawBinaryModel read(InputStream stream, String source) {
		byte[] bytes;

		try (InputStream in = stream) {
			bytes = in.readAllBytes();
		}
		catch (IOException ex) {
			throw new BinaryFormatException("\"" + source + "\" could not be read from disk", ex);
		}

		return read(bytes, source);
	}

	/**
	 * Read a model from bytes already in memory
	 *
	 * @param bytes The complete file contents
	 * @param source The file's name or path, quoted in any error
	 * @throws BinaryFormatException If the bytes are not a readable model
	 */
	public static RawBinaryModel read(byte[] bytes, String source) {
		if (bytes == null || bytes.length == 0)
			throw new BinaryFormatException("\"" + source + "\" is empty");

		return parse(new BinaryInput(bytes, source), source);
	}

	// ===============================================================
	// Header
	// ===============================================================

	private static RawBinaryModel parse(BinaryInput in, String source) {
		String magic = in.fixedString(4, "magic number");

		if (!MODEL_MAGIC.equals(magic))
			throw in.fail("does not begin with " + MODEL_MAGIC + " (found \"" + magic
					+ "\"); it is not a Strut binary model");

		int version = in.u16("format version");

		if (version < MODEL_MIN_VERSION)
			throw in.fail("is model format version " + version + ", which this build no longer reads;"
					+ " the oldest readable version is " + MODEL_MIN_VERSION + ". Re-export it");

		if (version > MODEL_VERSION)
			throw in.fail("is model format version " + version + ", which is newer than this build"
					+ " understands (" + MODEL_VERSION + "). Update Strut, or re-export the model"
					+ " from a matching version of the Blockbench plugin");

		int headerFlags = in.u16("header flags");

		int stringCount = in.u16("string count");
		int textureCount = in.u16("texture count");
		int groupBoneCount = in.u16("group bone count");
		int meshCount = in.u16("mesh count");
		int cubeCount = in.u16("cube count");
		int armatureCount = in.u16("armature count");
		int stringTableSize = in.u32("string table size");
		float unitsPerBlock = in.f32("units per block");
		Vector3f boundsMin = in.vec3("bounds minimum");
		Vector3f boundsMax = in.vec3("bounds maximum");

		in.skip(MODEL_RESERVED_V100, "reserved header bytes");

		/*
		 * Reading the reserved tail by count rather than seeking to a fixed offset is what keeps
		 * future versions a handful of conditionals instead of a separate parser each. This
		 * assertion is what makes that safe: a field read in the wrong order leaves the header a
		 * different width, and catching it here beats discovering it as plausible-looking
		 * nonsense several blocks later.
		 */
		if (in.position() != MODEL_HEADER_BYTES)
			throw in.fail("read a " + in.position() + "-byte header for version " + version
					+ "; every version's header is exactly " + MODEL_HEADER_BYTES + " bytes, so the"
					+ " version-specific layout above is wrong");

		if (stringTableSize > in.remaining())
			throw in.fail("declares a " + stringTableSize + "-byte string table with only "
					+ in.remaining() + " bytes left after its " + MODEL_HEADER_BYTES
					+ "-byte header; the file is truncated");

		if (unitsPerBlock <= 0 || !Float.isFinite(unitsPerBlock))
			throw in.fail("declares " + unitsPerBlock + " units per block; the authoring grid must"
					+ " be a positive, finite number");

		List<String> strings = readStringTable(in, stringCount);

		// Named locals, not one long argument list: the blocks must be read in file order, and the extension trailer comes after the last of them
		List<RawTexture> textures = readTextures(in, textureCount, strings);
		List<RawGroupBone> groupBones = readGroupBones(in, groupBoneCount, strings, meshCount, cubeCount);
		List<RawMesh> meshes = readMeshes(in, meshCount, strings, textureCount, armatureCount);
		List<RawCube> cubes = readCubes(in, cubeCount, strings, textureCount);
		List<RawArmature> armatures = readArmatures(in, armatureCount, strings, groupBoneCount, version);
		Map<String, byte[]> extensions = (headerFlags & MODEL_FLAG_EXTENSIONS) != 0 ? readExtensions(in) : Map.of();

		return new RawBinaryModel(
				version,
				strings.isEmpty() ? null : strings.get(0),
				unitsPerBlock,
				boundsMin,
				boundsMax,
				Collections.unmodifiableList(strings),
				textures, groupBones, meshes, cubes, armatures, extensions);
	}

	/**
	 * Read the extension sections that follow the armature block: their names and raw bytes, in file order. Nothing here interprets them
	 */
	private static Map<String, byte[]> readExtensions(BinaryInput in) {
		int count = in.plausibleCount(in.u16("extension count"), 6, "extension sections");
		Map<String, byte[]> sections = new LinkedHashMap<>();

		for (int i = 0; i < count; i++) {
			String name = in.string("extension " + i + " name");
			int length = in.u32("extension \"" + name + "\" length");

			if (length > in.remaining())
				throw in.fail("declares a " + length + "-byte extension \"" + name + "\" with only " + in.remaining() + " bytes left; the file is truncated");

			if (sections.put(name, in.bytes(length, "extension \"" + name + "\"")) != null)
				throw in.fail("has two extension sections named \"" + name + "\"");
		}

		return Collections.unmodifiableMap(sections);
	}

	/**
	 * Read the string table every other record indexes into
	 * <p>
	 * By the exporter's convention the model's own identifier is written first and so occupies
	 * index 0. That is a convention rather than a header field, which is why
	 * {@link RawBinaryModel#modelId()} is documented as informational
	 */
	private static List<String> readStringTable(BinaryInput in, int count) {
		in.plausibleCount(count, 2, "strings");

		List<String> strings = new ArrayList<>(count);

		for (int i = 0; i < count; i++) {
			strings.add(in.string("string " + i));
		}

		return strings;
	}

	/**
	 * Resolve a string-table index, failing rather than substituting
	 * <p>
	 * An index past the table means the file's own cross-references are inconsistent, and a
	 * placeholder name would carry that corruption forward into a model that loads and is
	 * quietly wrong
	 */
	private static String resolve(BinaryInput in, List<String> strings, int index, String what) {
		if (index < 0 || index >= strings.size())
			throw in.fail("names " + what + " with string index " + index
					+ ", but the string table holds " + strings.size() + " entries");

		return strings.get(index);
	}

	/**
	 * Range-check an index into one of the file's own blocks
	 */
	private static int checkIndex(BinaryInput in, int index, int count, String what, String block) {
		if (index < 0 || index >= count)
			throw in.fail("has " + what + " referencing " + block + " " + index + " of " + count);

		return index;
	}

	/**
	 * Range-check a parent index, which may be -1 to mean "no parent"
	 */
	private static int checkParent(BinaryInput in, int index, int count, String what) {
		if (index < -1 || index >= count)
			throw in.fail("has " + what + " naming parent " + index + ", which is neither -1 nor"
					+ " one of the " + count + " entries in its own block");

		return index;
	}

	// ===============================================================
	// Blocks
	// ===============================================================

	private static List<RawTexture> readTextures(BinaryInput in, int count, List<String> strings) {
		in.plausibleCount(count, 10, "textures");

		List<RawTexture> textures = new ArrayList<>(count);

		for (int i = 0; i < count; i++) {
			String name = resolve(in, strings, in.u16("texture " + i + " name"), "texture " + i);

			textures.add(new RawTexture(name,
					in.u16("texture " + i + " width"), in.u16("texture " + i + " height"),
					in.u16("texture " + i + " uv width"), in.u16("texture " + i + " uv height")));
		}

		return Collections.unmodifiableList(textures);
	}

	private static List<RawGroupBone> readGroupBones(BinaryInput in, int count, List<String> strings,
													 int meshCount, int cubeCount) {
		in.plausibleCount(count, 31, "group bones");

		List<RawGroupBone> bones = new ArrayList<>(count);

		for (int i = 0; i < count; i++) {
			String name = resolve(in, strings, in.u16("bone " + i + " name"), "bone " + i);
			int parentIndex = checkParent(in, in.i16("bone " + i + " parent"), count, "bone \"" + name + "\"");
			Vector3f pivot = in.vec3("bone \"" + name + "\" pivot");
			Vector3f rotation = in.vec3("bone \"" + name + "\" rotation");
			// the flags byte is read and dropped: its bit 0 once hid the bone, and a model no longer carries what its author had switched off in the editor
			in.u8("bone \"" + name + "\" flags");

			int elementCount = in.plausibleCount(in.u16("bone \"" + name + "\" element count"), 3, "elements");
			List<RawElement> elements = new ArrayList<>(elementCount);

			for (int e = 0; e < elementCount; e++) {
				int kind = in.u8("element kind of bone \"" + name + "\"");
				int index = in.u16("element index of bone \"" + name + "\"");

				switch (kind) {
					case ELEMENT_KIND_MESH -> checkIndex(in, index, meshCount, "bone \"" + name + "\"", "mesh");
					case ELEMENT_KIND_CUBE -> checkIndex(in, index, cubeCount, "bone \"" + name + "\"", "cube");
					default -> throw in.fail("has bone \"" + name + "\" holding an element of kind "
							+ kind + "; the only kinds are " + ELEMENT_KIND_MESH + " (mesh) and "
							+ ELEMENT_KIND_CUBE + " (cube)");
				}

				elements.add(new RawElement(kind, index));
			}

			bones.add(new RawGroupBone(name, parentIndex, pivot, rotation,
					Collections.unmodifiableList(elements)));
		}

		return Collections.unmodifiableList(bones);
	}

	private static List<RawMesh> readMeshes(BinaryInput in, int count, List<String> strings,
											int textureCount, int armatureCount) {
		in.plausibleCount(count, 39, "meshes");

		List<RawMesh> meshes = new ArrayList<>(count);

		for (int i = 0; i < count; i++) {
			String name = resolve(in, strings, in.u16("mesh " + i + " name"), "mesh " + i);
			String where = "mesh \"" + name + "\"";
			int textureIndex = checkIndex(in, in.u16(where + " texture"), textureCount, where, "texture");
			Vector3f origin = in.vec3(where + " origin");
			Vector3f rotation = in.vec3(where + " rotation");
			int flags = in.u8(where + " flags");
			int armatureIndex = in.i16(where + " armature");
			boolean skinned = (flags & MESH_FLAG_SKINNED) != 0;

			if (skinned) {
				checkIndex(in, armatureIndex, armatureCount, where, "armature");
			}
			else if (armatureIndex != -1) {
				throw in.fail("has " + where + " naming armature " + armatureIndex
						+ " without being flagged as skinned; an unskinned mesh must name armature -1");
			}

			int vertexCount = in.plausibleCount(in.u32(where + " vertex count"), 12, "vertices of " + where);
			int faceCount = in.plausibleCount(in.u32(where + " face count"), 1, "faces of " + where);
			float[] positions = in.vec3Array(vertexCount, where + " vertex");
			List<RawFace> faces = readFaces(in, faceCount, vertexCount, where);
			List<List<RawInfluence>> skin = skinned ? readSkin(in, vertexCount, where) : List.of();

			meshes.add(new RawMesh(name, textureIndex, origin, rotation,
					(flags & MESH_FLAG_MIRROR) != 0,
					skinned ? armatureIndex : -1, positions, faces, skin, (flags & MESH_FLAG_SMOOTH) != 0));
		}

		return Collections.unmodifiableList(meshes);
	}

	private static List<RawFace> readFaces(BinaryInput in, int faceCount, int vertexCount, String where) {
		List<RawFace> faces = new ArrayList<>(faceCount);

		for (int f = 0; f < faceCount; f++) {
			int corners = in.plausibleCount(in.u8(where + " face " + f + " corner count"), 12, "face corners");

			if (corners < 3)
				throw in.fail("has " + where + " face " + f + " with " + corners
						+ " corner(s); a face needs at least three");

			int[] indices = new int[corners];
			float[] uvs = new float[corners * 2];

			for (int c = 0; c < corners; c++) {
				indices[c] = checkIndex(in, in.u32(where + " face " + f + " corner vertex index"),
						vertexCount, where + " face " + f, "vertex");
				uvs[c * 2] = in.f32(where + " face " + f + " corner u");
				uvs[c * 2 + 1] = in.f32(where + " face " + f + " corner v");
			}

			faces.add(new RawFace(indices, uvs));
		}

		return Collections.unmodifiableList(faces);
	}

	/**
	 * Read per-vertex influences, which follow the face block on a skinned mesh
	 * <p>
	 * Read exactly as authored. The exporter caps influences at {@link StrutBinaryFormats#MAX_INFLUENCES},
	 * sorts them by weight and normalises them to sum to one; re-doing any of that here would
	 * mask an exporter that had stopped
	 */
	private static List<List<RawInfluence>> readSkin(BinaryInput in, int vertexCount, String where) {
		List<List<RawInfluence>> skin = new ArrayList<>(vertexCount);

		for (int v = 0; v < vertexCount; v++) {
			int influenceCount = in.plausibleCount(in.u8(where + " vertex " + v + " influence count"),
					6, "influences of " + where + " vertex " + v);

			if (influenceCount > MAX_INFLUENCES)
				throw in.fail("has " + where + " vertex " + v + " carrying " + influenceCount
						+ " influences; the format allows at most " + MAX_INFLUENCES
						+ ", so the exporter did not cap them");

			List<RawInfluence> influences = new ArrayList<>(influenceCount);

			for (int k = 0; k < influenceCount; k++) {
				influences.add(new RawInfluence(in.u16(where + " influence bone"),
						in.f32(where + " influence weight")));
			}

			skin.add(Collections.unmodifiableList(influences));
		}

		return Collections.unmodifiableList(skin);
	}

	private static List<RawCube> readCubes(BinaryInput in, int count, List<String> strings, int textureCount) {
		in.plausibleCount(count, 58, "cubes");

		List<RawCube> cubes = new ArrayList<>(count);

		for (int i = 0; i < count; i++) {
			String name = resolve(in, strings, in.u16("cube " + i + " name"), "cube " + i);
			String where = "cube \"" + name + "\"";
			int textureIndex = checkIndex(in, in.u16(where + " texture"), textureCount, where, "texture");
			Vector3f origin = in.vec3(where + " origin");
			Vector3f rotation = in.vec3(where + " rotation");
			Vector3f from = in.vec3(where + " from");
			Vector3f to = in.vec3(where + " to");
			float inflate = in.f32(where + " inflate");
			int flags = in.u8(where + " flags");

			int faceCount = in.plausibleCount(in.u8(where + " face count"), 18, "faces of " + where);

			if (faceCount > 6)
				throw in.fail("has " + where + " declaring " + faceCount + " faces; a box has six");

			List<RawCubeFace> faces = new ArrayList<>(faceCount);

			for (int f = 0; f < faceCount; f++) {
				int faceId = in.u8(where + " face id");

				if (faceId > 5)
					throw in.fail("has " + where + " naming face " + faceId
							+ "; a box has six faces, numbered 0 to 5");

				faces.add(new RawCubeFace(faceId,
						in.f32(where + " face u1"), in.f32(where + " face v1"),
						in.f32(where + " face u2"), in.f32(where + " face v2"),
						in.u8(where + " face uv rotation")));
			}

			cubes.add(new RawCube(name, textureIndex, origin, rotation, from, to, inflate,
					(flags & CUBE_FLAG_MIRROR) != 0,
					(flags & CUBE_FLAG_BOX_UV) != 0, Collections.unmodifiableList(faces)));
		}

		return Collections.unmodifiableList(cubes);
	}

	/**
	 * {@code version} gates only one thing here: a v100 file's armature bone record has no
	 * {@code width}/{@code colour} fields at all (added in v101 - see {@link StrutBinaryFormats#MODEL_VERSION}'s
	 * own doc comment), so those bytes simply aren't there to read on one. Defaulted to zero rather
	 * than made {@code Optional} or similar - both are round-trip-fidelity-only fields nothing in
	 * this engine's own rendering reads, so a v100 asset loading with "no custom width/colour" is
	 * exactly as correct as it already was before v101 existed
	 */
	private static List<RawArmature> readArmatures(BinaryInput in, int count, List<String> strings,
												   int groupBoneCount, int version) {
		if (count == 0)
			return List.of();

		in.plausibleCount(count, 19, "armatures");

		List<RawArmature> armatures = new ArrayList<>(count);

		for (int i = 0; i < count; i++) {
			String name = resolve(in, strings, in.u16("armature " + i + " name"), "armature " + i);
			String where = "armature \"" + name + "\"";
			Vector3f origin = in.vec3(where + " origin");
			int parentGroupBone = checkParent(in, in.i16(where + " parent bone"), groupBoneCount, where);

			in.u8(where + " flags");

			int boneCount = in.plausibleCount(in.u16(where + " bone count"), 33, "bones of " + where);
			List<RawBone> bones = new ArrayList<>(boneCount);

			for (int b = 0; b < boneCount; b++) {
				String boneName = resolve(in, strings, in.u16(where + " bone name"), where + " bone " + b);
				int parentIndex = checkParent(in, in.i16(where + " bone \"" + boneName + "\" parent"), boneCount,
						where + " bone \"" + boneName + "\"");
				Vector3f head = in.vec3(where + " bone \"" + boneName + "\" head");
				Vector3f rotation = in.vec3(where + " bone \"" + boneName + "\" rotation");
				float length = in.f32(where + " bone \"" + boneName + "\" length");
				float width = version >= 101 ? in.f32(where + " bone \"" + boneName + "\" width") : 0f;
				int colour = version >= 101 ? in.u8(where + " bone \"" + boneName + "\" colour") : 0;
				in.u8(where + " bone \"" + boneName + "\" flags");

				bones.add(new RawBone(boneName, parentIndex, head, rotation, length, width, colour));
			}

			armatures.add(new RawArmature(name, origin, parentGroupBone, Collections.unmodifiableList(bones)));
		}

		return Collections.unmodifiableList(armatures);
	}
}
