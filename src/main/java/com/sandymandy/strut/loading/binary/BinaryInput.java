package com.sandymandy.strut.loading.binary;

import java.util.Arrays;
import org.joml.Vector3f;

import java.nio.charset.StandardCharsets;

/**
 * A bounds-checked, little-endian read cursor over a complete file held in memory
 * <p>
 * Shared by {@link BinaryModelReader} and {@link BinaryAnimationReader}, which differ in
 * their record layouts but not in their primitives or their string-table convention
 * <p>
 * Deliberately free of any Minecraft type so that the parse layer can be exercised by a
 * plain unit test without a game bootstrap
 */
public final class BinaryInput {
	private final byte[] bytes;
	private final String source;
	private int position;

	/**
	 * @param bytes The complete file contents
	 * @param source The file's name or path, quoted in every error raised from this cursor
	 */
	public BinaryInput(byte[] bytes, String source) {
		this.bytes = bytes;
		this.source = source;
	}

	/**
	 * The number of bytes consumed so far
	 * <p>
	 * Used to assert that a version-specific header layout consumed exactly the width that
	 * version declares, which catches a field read in the wrong order far more directly
	 * than the nonsense values it would otherwise produce further down the file
	 */
	public int position() {
		return this.position;
	}

	/**
	 * The number of bytes left unread
	 */
	public int remaining() {
		return this.bytes.length - this.position;
	}

	/**
	 * Construct an exception naming this file and the problem with it
	 *
	 * @param problem The complaint, phrased to follow the file's name (e.g. {@code "is truncated"})
	 */
	public BinaryFormatException fail(String problem) {
		return new BinaryFormatException("\"" + this.source + "\" " + problem);
	}

	private void require(int count, String what) {
		if (count < 0)
			throw fail("asked for " + count + " bytes reading " + what);

		if (this.remaining() < count)
			throw fail("is truncated: reading " + what + " at byte " + this.position + " needs "
					+ count + " byte(s), but only " + this.remaining() + " remain");
	}

	/**
	 * Skip a run of bytes, typically a header's reserved tail
	 */
	public void skip(int count, String what) {
		require(count, what);

		this.position += count;
	}

	/**
	 * A run of raw bytes, copied out
	 */
	public byte[] bytes(int count, String what) {
		require(count, what);

		byte[] copy = Arrays.copyOfRange(this.bytes, this.position, this.position + count);

		this.position += count;

		return copy;
	}

	public int u8(String what) {
		require(1, what);

		return this.bytes[this.position++] & 0xFF;
	}

	public int u16(String what) {
		require(2, what);

		int value = (this.bytes[this.position] & 0xFF) | ((this.bytes[this.position + 1] & 0xFF) << 8);
		this.position += 2;

		return value;
	}

	/**
	 * A signed 16-bit value, used wherever {@code -1} means "no parent" or "no armature"
	 */
	public int i16(String what) {
		return (short)u16(what);
	}

	/**
	 * An unsigned 32-bit value, rejected if it exceeds {@link Integer#MAX_VALUE}
	 * <p>
	 * Nothing in either format legitimately reaches two billion of anything, so a value that
	 * large is a corrupt count rather than a real one, and returning it as a negative int
	 * would let it slip past every subsequent bounds check
	 */
	public int u32(String what) {
		require(4, what);

		long value = (this.bytes[this.position] & 0xFFL)
				| ((this.bytes[this.position + 1] & 0xFFL) << 8)
				| ((this.bytes[this.position + 2] & 0xFFL) << 16)
				| ((this.bytes[this.position + 3] & 0xFFL) << 24);
		this.position += 4;

		if (value > Integer.MAX_VALUE)
			throw fail("declares " + what + " as " + value + ", which is not a plausible count or size");

		return (int)value;
	}

	/**
	 * Four raw bytes as an int, for values where every bit pattern is meaningful
	 */
	public int bits32(String what) {
		require(4, what);

		int value = (this.bytes[this.position] & 0xFF)
				| ((this.bytes[this.position + 1] & 0xFF) << 8)
				| ((this.bytes[this.position + 2] & 0xFF) << 16)
				| ((this.bytes[this.position + 3] & 0xFF) << 24);
		this.position += 4;

		return value;
	}

	public float f32(String what) {
		return Float.intBitsToFloat(bits32(what));
	}

	public Vector3f vec3(String what) {
		return new Vector3f(f32(what + " x"), f32(what + " y"), f32(what + " z"));
	}

	/**
	 * Read {@code count} consecutive vectors into a flat float array
	 * <p>
	 * Flat rather than a list of vectors because a mesh's vertex block is the largest thing
	 * in any model file, and one array of floats is both what the bake consumes and a fraction
	 * of the allocation an object per vertex would cost
	 */
	public float[] vec3Array(int count, String what) {
		if (count < 0 || count > this.remaining() / 12)
			throw fail("declares " + count + " vectors in " + what + ", more than the bytes that remain can hold");

		float[] values = new float[count * 3];

		for (int i = 0; i < values.length; i++) {
			values[i] = f32(what);
		}

		return values;
	}

	/**
	 * A fixed-width run of bytes as a string, used for the magic number
	 */
	public String fixedString(int length, String what) {
		require(length, what);

		String value = new String(this.bytes, this.position, length, StandardCharsets.UTF_8);
		this.position += length;

		return value;
	}

	/**
	 * A uint16-length-prefixed UTF-8 string, the shape every string-table entry takes
	 */
	public String string(String what) {
		int length = u16(what + " length");

		require(length, what);

		String value = new String(this.bytes, this.position, length, StandardCharsets.UTF_8);
		this.position += length;

		return value;
	}

	/**
	 * Reject a count that could not possibly be satisfied by the bytes that remain
	 * <p>
	 * Checked <i>before</i> the list it sizes is allocated. A corrupt uint32 vertex count is
	 * otherwise a multi-gigabyte allocation that fails as an {@link OutOfMemoryError} naming
	 * nothing, rather than as a format error naming the file and the field
	 *
	 * @param count The count just read
	 * @param minimumBytesEach The smallest a single record of this kind can be
	 * @param what What is being counted, for the error message
	 * @return {@code count}, so this can wrap the read that produced it
	 */
	public int plausibleCount(int count, int minimumBytesEach, String what) {
		if (count < 0)
			throw fail("declares a negative number of " + what + " (" + count + ")");

		if ((long)count * minimumBytesEach > this.remaining())
			throw fail("declares " + count + " " + what + " needing at least "
					+ ((long)count * minimumBytesEach) + " bytes, but only " + this.remaining()
					+ " remain after byte " + this.position + "; the file is truncated or the count is corrupt");

		return count;
	}
}
