package com.sandymandy.strut.loading.binary;

/**
 * The wire vocabulary shared by Strut's binary model and animation formats
 * <p>
 * Both formats are little-endian, open with a four-byte magic and a {@code uint16} version,
 * carry a uint16-length-prefixed UTF-8 string table that every other record indexes into,
 * and end their header with a reserved tail that keeps the header a fixed width as fields
 * are added
 * <p>
 * The two version lines move independently. Geometry and animation change for unrelated
 * reasons, and re-exporting a model to fix a UV has no business invalidating the clips
 * authored against it - what couples the two is {@link com.sandymandy.strut.loading.binary.raw.RawBinaryAnimation#skeletonFingerprint()},
 * which couples them by shape rather than by version number
 */
public final class StrutBinaryFormats {
	private StrutBinaryFormats() {}

	// ===============================================================
	// Model
	// ===============================================================

	/** The magic every {@code .strutmodel} opens with */
	public static final String MODEL_MAGIC = "STMD";

	/** The file extension for a binary model */
	public static final String MODEL_EXTENSION = ".strutmodel";

	/**
	 * The model format version this build writes - 101 adds two new fixed fields to every armature
	 * bone ({@code width}/{@code colour}, round-trip-fidelity-only - see
	 * {@code RawBinaryModel.RawBone}'s own doc comment)
	 */
	public static final int MODEL_VERSION = 101;

	/**
	 * The oldest model version this build can read - kept at 100 rather than bumped alongside
	 * {@link #MODEL_VERSION}, since real shipped assets (`rig_demo.strutmodel`,
	 * `base_character_male.strutmodel`) already exist at v100 and nothing about the v101 addition
	 * needs them re-exported: {@code BinaryModelReader} branches on {@code version} exactly once, at
	 * each armature bone's own {@code width}/{@code colour} read, defaulting both to zero for a v100
	 * file rather than requiring every existing asset to catch up to the new version just to keep
	 * loading
	 */
	public static final int MODEL_MIN_VERSION = 100;

	/**
	 * Bit 0 of the model header flags: extension sections follow the armature block. Each is a uint16-length-prefixed name (a namespaced id such as
	 * {@code strut_physics:physics}), a uint32 byte length and that many bytes; a uint16 count precedes them. A reader that does not understand
	 * a section keeps its bytes and ignores them, and a reader older than this flag stops after the armatures and never sees the trailer, so a file
	 * with extensions still loads everywhere. No version bump: the flag is what says the trailer exists
	 */
	public static final int MODEL_FLAG_EXTENSIONS = 1;

	/** Every model header is exactly this wide, in every version */
	public static final int MODEL_HEADER_BYTES = 64;

	/** Bytes of model header reserved after the last live field of v100 */
	static final int MODEL_RESERVED_V100 = 12;

	/*
	 * Bit 0 of a group bone's, a mesh's and a cube's flags once meant "not rendered": the editor's visibility switch, saved with the model. It is no longer
	 * written (always 0) and no longer read: a model holds geometry, and what is shown is the game's business (bone overrides), as in GeckoLib. A file written
	 * with the bit set loads with everything visible. The bit stays reserved so no other flag moves
	 */

	/** Bit 1 of a mesh's flags: the mesh's faces are mirrored */
	public static final int MESH_FLAG_MIRROR = 2;

	/** Bit 2 of a mesh's flags: per-vertex influences follow the face block */
	public static final int MESH_FLAG_SKINNED = 4;

	/**
	 * Bit 3 of a mesh's flags: adjacent faces blend their normals at a shared vertex instead of
	 * each rendering flat. Added after the format's first release; safe on an old
	 * reader (an unset bit reads as ordinary flat shading) and on an old writer (the bit is simply
	 * never set, which is exactly what "flat" already meant)
	 */
	public static final int MESH_FLAG_SMOOTH = 8;

	/** Bit 1 of a cube's flags: the cube's faces are mirrored */
	public static final int CUBE_FLAG_MIRROR = 2;

	/**
	 * Bit 2 of a cube's flags: the cube's UVs are Bedrock-style box UVs
	 * <p>
	 * Without this a round trip through binary silently degrades a Bedrock-compatible model,
	 * because box UVs pre-expanded into six face rectangles can never be folded back up
	 */
	public static final int CUBE_FLAG_BOX_UV = 4;

	/** An element record's {@code kind} byte, naming which block its index points into */
	public static final int ELEMENT_KIND_MESH = 0;
	public static final int ELEMENT_KIND_CUBE = 1;

	/** The most influences a single vertex may carry */
	public static final int MAX_INFLUENCES = 4;

	/** The default authoring grid, in model units per block */
	public static final float DEFAULT_UNITS_PER_BLOCK = 16f;

	// ===============================================================
	// Animation
	// ===============================================================

	/** The magic every {@code .strutanim} opens with */
	public static final String ANIMATION_MAGIC = "STAM";

	/** The file extension for a binary animation */
	public static final String ANIMATION_EXTENSION = ".strutanim";

	/**
	 * The folders under {@code assets/<namespace>/} (and {@code data/<namespace>/}, for the server) that Strut reads {@code .strutmodel} and
	 * {@code .strutanim} files from: every asset of Strut is under the {@code strut} folder. A file's resource id is its full path, so
	 * {@code strut/models/bare/ava.strutmodel} is the id a model asks for, and nothing is renamed or looked up under another name. The older {@code geo}
	 * and {@code animations} folders are no longer read
	 */
	public static final String[] MODEL_FOLDERS = {"strut/models"};
	public static final String[] ANIMATION_FOLDERS = {"strut/animations"};

	/** Whether {@code path} (a resource path, {@code /} separated) is inside one of {@link #ANIMATION_FOLDERS} */
	public static boolean isInAnimationFolder(String path) {
		for (String folder : ANIMATION_FOLDERS) {
			if (path.startsWith(folder + "/"))
				return true;
		}

		return false;
	}

	/** The animation format version this build writes */
	public static final int ANIMATION_VERSION = 100;

	/** The oldest animation version this build can read */
	public static final int ANIMATION_MIN_VERSION = 100;

	/** Every animation header is exactly this wide, in every version */
	public static final int ANIMATION_HEADER_BYTES = 32;

	/** Bytes of animation header reserved after the last live field of v100 */
	static final int ANIMATION_RESERVED_V100 = 12;

	/** Set on a keyframe carrying a second, differing outgoing value */
	public static final int KEY_FLAG_HAS_POST = 0x0001;

	/** Set on a keyframe carrying an easing name and its arguments */
	public static final int KEY_FLAG_HAS_EASING = 0x0002;

	/**
	 * Bits 2-4: the corresponding component of the keyframe's value is a Molang expression
	 * held in the string table, rather than a literal float
	 */
	public static final int KEY_FLAG_VALUE_EXPRESSION = 0x0004;

	/** Bits 5-7: the same, for the outgoing value of a keyframe that has one */
	public static final int KEY_FLAG_POST_EXPRESSION = 0x0020;

	/** The smallest a clip record can be: a name, a duration, a loop mode and two counts */
	static final int MIN_CLIP_BYTES = 11;

	/** A channel record's fixed head: target, kind, property, interpolation, keyframe count */
	static final int MIN_CHANNEL_BYTES = 7;

	/**
	 * The smallest a keyframe can be: a time (4), an interpolation (1), two flag bytes (2), and three components that are each a 2-byte Molang
	 * expression index instead of a 4-byte float (6). This was 19 (three floats), which rejected a valid file whose last key is all
	 * expressions as "truncated"
	 */
	static final int MIN_KEY_BYTES = 13;

	/** An event record, which is fixed width */
	static final int EVENT_BYTES = 9;

	/** The event kind naming a sound instruction, whose payload is the sound's identifier */
	public static final String EVENT_KIND_SOUND = "sound";

	/**
	 * The event kind naming a particle instruction, whose payload is an
	 * {@link #EVENT_FIELD_SEPARATOR}-delimited {@code effect|locator|script} triple
	 */
	public static final String EVENT_KIND_PARTICLE = "particle";

	/** The event kind naming a custom instruction, whose payload is the instruction text */
	public static final String EVENT_KIND_INSTRUCTION = "instruction";

	/**
	 * Separates the fields of an event payload that carries more than one
	 * <p>
	 * Only {@link #EVENT_KIND_PARTICLE} needs this today: a particle instruction carries an effect,
	 * a locator and a pre-effect script, where the wire format gives an event one payload string.
	 * ASCII <b>unit separator</b> (U+001F) rather than a printable character because every printable
	 * candidate appears legitimately in the fields themselves - a pre-effect script is Molang, so
	 * {@code ;} is out, and effect names carry {@code :} and {@code .}
	 * <p>
	 * Empty fields are allowed and mean "absent", so a particle with no locator writes two
	 * consecutive separators rather than omitting the field
	 */
	public static final String EVENT_FIELD_SEPARATOR = "";

	/**
	 * How a channel's keyframes are interpolated
	 */
	public enum Interpolation {
		STEP,
		LINEAR,
		CATMULLROM,
		BEZIER;

		private static final Interpolation[] WIRE = values();

		/**
		 * The sentinel a channel uses to say its keyframes do not share one mode, in which
		 * case each keyframe's own byte is authoritative
		 */
		public static final int MIXED = 255;

		/**
		 * @param wire The wire code
		 * @return The matching mode, or null for {@link #MIXED}
		 */
		public static Interpolation fromWire(int wire) {
			if (wire == MIXED)
				return null;

			if (wire < 0 || wire >= WIRE.length)
				throw new IllegalArgumentException("no interpolation has wire code " + wire);

			return WIRE[wire];
		}
	}

	/**
	 * Which transform a channel drives
	 */
	public enum Property {
		ROTATION,
		POSITION,
		SCALE;

		private static final Property[] WIRE = values();

		public static Property fromWire(int wire) {
			if (wire < 0 || wire >= WIRE.length)
				throw new IllegalArgumentException("no property has wire code " + wire);

			return WIRE[wire];
		}
	}

	/**
	 * What a channel's target name refers to
	 * <p>
	 * A group bone and an armature bone may legitimately share a name, so the kind is what
	 * disambiguates them rather than a naming convention the author has to remember
	 */
	public enum TargetKind {
		GROUP_BONE,
		ARMATURE_BONE;

		private static final TargetKind[] WIRE = values();

		public static TargetKind fromWire(int wire) {
			if (wire < 0 || wire >= WIRE.length)
				throw new IllegalArgumentException("no target kind has wire code " + wire);

			return WIRE[wire];
		}
	}

	/**
	 * What a clip does when it reaches its end
	 */
	public enum LoopMode {
		PLAY_ONCE,
		LOOP,
		HOLD_ON_LAST_FRAME;

		private static final LoopMode[] WIRE = values();

		public static LoopMode fromWire(int wire) {
			if (wire < 0 || wire >= WIRE.length)
				throw new IllegalArgumentException("no loop mode has wire code " + wire);

			return WIRE[wire];
		}
	}
}
