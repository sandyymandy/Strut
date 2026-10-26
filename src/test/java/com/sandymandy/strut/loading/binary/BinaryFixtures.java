package com.sandymandy.strut.loading.binary;

import java.util.LinkedHashMap;
import java.util.Map;

import static com.sandymandy.strut.loading.binary.StrutBinaryFormats.*;

/**
 * Builders for binary assets whose every field is known, so the reader can be checked against
 * bytes rather than against an exporter
 * <p>
 * The rigged model here is also the skinning fixture: a two-segment limb whose middle
 * ring of vertices is split evenly between two bones, which is the smallest thing that can
 * show a blend going wrong
 */
public final class BinaryFixtures {
	private BinaryFixtures() {}

	/**
	 * A string table that hands out indices as strings are interned
	 * <p>
	 * Ordered, and de-duplicating, so the index a builder gets back is the index the reader
	 * will resolve - which is the whole cross-reference the format is built on
	 */
	public static final class Strings {
		private final Map<String, Integer> indices = new LinkedHashMap<>();

		public int intern(String value) {
			return this.indices.computeIfAbsent(value, ignored -> this.indices.size());
		}

		public int size() {
			return this.indices.size();
		}

		public BinaryOutput write() {
			BinaryOutput out = new BinaryOutput();

			for (String value : this.indices.keySet()) {
				out.string(value);
			}

			return out;
		}
	}

	// ===============================================================
	// Models
	// ===============================================================

	/**
	 * A model with one texture, one group bone and one cube hanging off it - the simplest
	 * thing that exercises the Bedrock-compatible path
	 */
	public static byte[] cubeModel() {
		Strings strings = new Strings();
		int modelId = strings.intern("strut:test/cube");
		int textureName = strings.intern("texture");
		int boneName = strings.intern("body");
		int cubeName = strings.intern("box");

		BinaryOutput textures = new BinaryOutput()
				.u16(textureName).u16(64).u16(64).u16(64).u16(64);

		BinaryOutput bones = new BinaryOutput()
				.u16(boneName).i16(-1)
				.vec3(0, 0, 0).vec3(0, 0, 0)
				.u8(0)
				.u16(1).u8(ELEMENT_KIND_CUBE).u16(0);

		BinaryOutput cubes = new BinaryOutput()
				.u16(cubeName).u16(0)
				.vec3(0, 0, 0).vec3(0, 0, 0)
				.vec3(-4, 0, -4).vec3(4, 8, 4)
				.f32(0.25f)
				.u8(CUBE_FLAG_MIRROR | CUBE_FLAG_BOX_UV)
				.u8(1)
				.u8(2).f32(0).f32(0).f32(8).f32(8).u8(0);

		return modelFile(strings, modelId, 1, 1, 0, 1, 0, textures, bones, new BinaryOutput(), cubes,
				new BinaryOutput());
	}

	/**
	 * A model with one unskinned mesh carrying a triangle and a quad, which is what the bake's
	 * degenerate-quad emission has to cope with
	 */
	public static byte[] meshModel() {
		Strings strings = new Strings();
		int modelId = strings.intern("strut:test/mesh");
		int textureName = strings.intern("texture");
		int boneName = strings.intern("root");
		int meshName = strings.intern("plane");

		BinaryOutput textures = new BinaryOutput()
				.u16(textureName).u16(32).u16(32).u16(16).u16(16);

		BinaryOutput bones = new BinaryOutput()
				.u16(boneName).i16(-1)
				.vec3(0, 0, 0).vec3(0, 0, 0)
				.u8(0)
				.u16(1).u8(ELEMENT_KIND_MESH).u16(0);

		BinaryOutput meshes = new BinaryOutput()
				.u16(meshName).u16(0)
				.vec3(0, 1, 0).vec3(0, 45, 0)
				.u8(MESH_FLAG_MIRROR).i16(-1)
				.u32(5).u32(2)
				.vec3(0, 0, 0).vec3(4, 0, 0).vec3(4, 0, 4).vec3(0, 0, 4).vec3(2, 4, 2);

		// A quad
		meshes.u8(4)
				.u32(0).f32(0).f32(0)
				.u32(1).f32(1).f32(0)
				.u32(2).f32(1).f32(1)
				.u32(3).f32(0).f32(1);

		// A triangle sharing two of its vertices
		meshes.u8(3)
				.u32(0).f32(0).f32(0)
				.u32(1).f32(1).f32(0)
				.u32(4).f32(0.5f).f32(1);

		return modelFile(strings, modelId, 1, 1, 1, 0, 0, textures, bones, meshes, new BinaryOutput(),
				new BinaryOutput());
	}

	/**
	 * A two-segment limb skinned to a two-bone armature
	 * <p>
	 * Three rings of four vertices: the bottom ring belongs wholly to the lower bone, the top
	 * ring wholly to the upper, and the middle ring is split evenly between them. That middle
	 * ring is the point - a skinning bug that drops, renormalises or mis-orders influences
	 * shows up there and nowhere else
	 */
	public static byte[] riggedModel() {
		Strings strings = new Strings();
		int modelId = strings.intern("strut:test/rig");
		int textureName = strings.intern("texture");
		int boneName = strings.intern("root");
		int meshName = strings.intern("limb");
		int armatureName = strings.intern("rig");
		int lowerName = strings.intern("lower");
		int upperName = strings.intern("upper");

		BinaryOutput textures = new BinaryOutput()
				.u16(textureName).u16(16).u16(16).u16(16).u16(16);

		BinaryOutput bones = new BinaryOutput()
				.u16(boneName).i16(-1)
				.vec3(0, 0, 0).vec3(0, 0, 0)
				.u8(0)
				.u16(1).u8(ELEMENT_KIND_MESH).u16(0);

		BinaryOutput meshes = new BinaryOutput()
				.u16(meshName).u16(0)
				.vec3(0, 0, 0).vec3(0, 0, 0)
				.u8(MESH_FLAG_SKINNED).i16(0)
				.u32(12).u32(8);

		// Three rings of four, at y = 0, 8 and 16
		for (int ring = 0; ring < 3; ring++) {
			float y = ring * 8f;

			meshes.vec3(-2, y, -2).vec3(2, y, -2).vec3(2, y, 2).vec3(-2, y, 2);
		}

		// Two bands of four side quads
		for (int band = 0; band < 2; band++) {
			int lower = band * 4;

			for (int side = 0; side < 4; side++) {
				int a = lower + side;
				int b = lower + (side + 1) % 4;

				meshes.u8(4)
						.u32(a).f32(0).f32(0)
						.u32(b).f32(1).f32(0)
						.u32(b + 4).f32(1).f32(1)
						.u32(a + 4).f32(0).f32(1);
			}
		}

		// Influences, in the same vertex order
		for (int ring = 0; ring < 3; ring++) {
			for (int corner = 0; corner < 4; corner++) {
				switch (ring) {
					case 0 -> meshes.u8(1).u16(0).f32(1f);
					case 1 -> meshes.u8(2).u16(0).f32(0.5f).u16(1).f32(0.5f);
					default -> meshes.u8(1).u16(1).f32(1f);
				}
			}
		}

		BinaryOutput armatures = new BinaryOutput()
				.u16(armatureName)
				.vec3(0, 0, 0)
				.i16(0)
				.u8(0)
				.u16(2)
				.u16(lowerName).i16(-1).vec3(0, 0, 0).vec3(0, 0, 0).f32(8f).f32(0f).u8(0).u8(0)
				.u16(upperName).i16(0).vec3(0, 8, 0).vec3(0, 0, 0).f32(8f).f32(0f).u8(0).u8(0);

		return modelFile(strings, modelId, 1, 1, 1, 0, 1, textures, bones, meshes, new BinaryOutput(),
				armatures);
	}

	/**
	 * Assemble a complete model file around already-built blocks
	 */
	public static byte[] modelFile(Strings strings, int modelId, int textureCount, int boneCount,
								   int meshCount, int cubeCount, int armatureCount,
								   BinaryOutput textures, BinaryOutput bones, BinaryOutput meshes,
								   BinaryOutput cubes, BinaryOutput armatures) {
		if (modelId != 0)
			throw new IllegalArgumentException("the model identifier must be interned first, at index 0");

		BinaryOutput stringTable = strings.write();
		BinaryOutput out = new BinaryOutput()
				.fixedString(MODEL_MAGIC)
				.u16(MODEL_VERSION)
				.u16(0)
				.u16(strings.size())
				.u16(textureCount)
				.u16(boneCount)
				.u16(meshCount)
				.u16(cubeCount)
				.u16(armatureCount)
				.u32(stringTable.length())
				.f32(DEFAULT_UNITS_PER_BLOCK)
				.vec3(-16, 0, -16)
				.vec3(16, 32, 16)
				.zeros(MODEL_RESERVED_V100);

		if (out.length() != MODEL_HEADER_BYTES)
			throw new IllegalStateException("fixture wrote a " + out.length() + "-byte model header");

		return out.append(stringTable).append(textures).append(bones).append(meshes).append(cubes)
				.append(armatures).toByteArray();
	}

	// ===============================================================
	// Animations
	// ===============================================================

	/**
	 * A clip covering every keyframe shape the format allows: each interpolation mode, a named
	 * easing with an argument, a keyframe with a separate outgoing value, a Molang expression
	 * in one component of one keyframe, and an event
	 */
	public static byte[] animation() {
		Strings strings = new Strings();
		int clipName = strings.intern("walk");
		int target = strings.intern("upper");
		int easing = strings.intern("easeinoutelastic");
		int expression = strings.intern("math.sin(query.anim_time * 30)");
		int eventKind = strings.intern("sound");
		int eventPayload = strings.intern("strut:test.step");

		BinaryOutput keys = new BinaryOutput();
		int keyCount = 0;

		// Linear, all literals
		keys.f32(0f).u8(Interpolation.LINEAR.ordinal()).u16(0).f32(0).f32(0).f32(0);
		keyCount++;

		// Step, carrying a separate outgoing value
		keys.f32(0.25f).u8(Interpolation.STEP.ordinal()).u16(KEY_FLAG_HAS_POST)
				.f32(10).f32(0).f32(0)
				.f32(20).f32(0).f32(0);
		keyCount++;

		// Catmull-rom, with a named easing and one argument
		keys.f32(0.5f).u8(Interpolation.CATMULLROM.ordinal()).u16(KEY_FLAG_HAS_EASING)
				.f32(30).f32(0).f32(0)
				.u16(easing).u8(1).f32(1.5f);
		keyCount++;

		// Bezier, with a Molang expression in its Y component
		keys.f32(0.75f).u8(Interpolation.BEZIER.ordinal()).u16(KEY_FLAG_VALUE_EXPRESSION << 1)
				.f32(40).u16(expression).f32(0)
				.vec3(-0.1f, -0.1f, -0.1f).vec3(-1, -1, -1)
				.vec3(0.1f, 0.1f, 0.1f).vec3(1, 1, 1);
		keyCount++;

		BinaryOutput channels = new BinaryOutput()
				.u16(target)
				.u8(TargetKind.ARMATURE_BONE.ordinal())
				.u8(Property.ROTATION.ordinal())
				.u8(Interpolation.MIXED)
				.u16(keyCount)
				.append(keys);

		BinaryOutput clips = new BinaryOutput()
				.u16(clipName)
				.f32(1f)
				.u8(LoopMode.LOOP.ordinal())
				.u16(1)
				.u16(1)
				.append(channels)
				.f32(0.5f).u16(eventKind).u16(eventPayload).u8(0);

		return animationFile(strings, 1, 0xDEADBEEF, clips);
	}

	/**
	 * Assemble a complete animation file around an already-built clip block
	 */
	public static byte[] animationFile(Strings strings, int clipCount, int fingerprint, BinaryOutput clips) {
		BinaryOutput stringTable = strings.write();
		BinaryOutput out = new BinaryOutput()
				.fixedString(ANIMATION_MAGIC)
				.u16(ANIMATION_VERSION)
				.u16(0)
				.u16(strings.size())
				.u32(stringTable.length())
				.u16(clipCount)
				.u32(fingerprint)
				.zeros(ANIMATION_RESERVED_V100);

		if (out.length() != ANIMATION_HEADER_BYTES)
			throw new IllegalStateException("fixture wrote a " + out.length() + "-byte animation header");

		return out.append(stringTable).append(clips).toByteArray();
	}

	// ===============================================================
	// Corruption
	// ===============================================================

	/**
	 * The same bytes, cut short
	 */
	public static byte[] truncated(byte[] bytes, int keep) {
		byte[] cut = new byte[keep];

		System.arraycopy(bytes, 0, cut, 0, keep);

		return cut;
	}

	/**
	 * The same bytes with one 16-bit field overwritten, for driving a specific field out of range
	 */
	public static byte[] withU16At(byte[] bytes, int offset, int value) {
		byte[] copy = bytes.clone();

		copy[offset] = (byte)(value & 0xFF);
		copy[offset + 1] = (byte)((value >> 8) & 0xFF);

		return copy;
	}

	/**
	 * Every byte offset a model header field begins at, so a test can name the field it corrupts
	 */
	public static final class ModelHeader {
		public static final int VERSION = 4;
		public static final int STRING_COUNT = 8;
		public static final int TEXTURE_COUNT = 10;
		public static final int BONE_COUNT = 12;
		public static final int MESH_COUNT = 14;
		public static final int CUBE_COUNT = 16;
		public static final int ARMATURE_COUNT = 18;
		public static final int STRING_TABLE_SIZE = 20;
		public static final int UNITS_PER_BLOCK = 24;
	}
}
