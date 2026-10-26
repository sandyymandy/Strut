package com.sandymandy.strut.loading.binary;

import org.joml.Vector3f;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * A little-endian write cursor, the mirror of {@link BinaryInput}
 * <p>
 * Lives in the test source set on purpose. The production writer is the Blockbench plugin's,
 * written in TypeScript against Blockbench's own model; this exists so the reader can be
 * tested against bytes whose every field is known, rather than against whatever an exporter
 * happened to produce
 */
public final class BinaryOutput {
	private final ByteArrayOutputStream bytes = new ByteArrayOutputStream(1024);

	public int length() {
		return this.bytes.size();
	}

	public byte[] toByteArray() {
		return this.bytes.toByteArray();
	}

	public BinaryOutput u8(int value) {
		this.bytes.write(value & 0xFF);

		return this;
	}

	public BinaryOutput u16(int value) {
		this.bytes.write(value & 0xFF);
		this.bytes.write((value >> 8) & 0xFF);

		return this;
	}

	public BinaryOutput i16(int value) {
		return u16(value & 0xFFFF);
	}

	public BinaryOutput u32(int value) {
		this.bytes.write(value & 0xFF);
		this.bytes.write((value >> 8) & 0xFF);
		this.bytes.write((value >> 16) & 0xFF);
		this.bytes.write((value >> 24) & 0xFF);

		return this;
	}

	public BinaryOutput f32(float value) {
		return u32(Float.floatToIntBits(value));
	}

	public BinaryOutput vec3(float x, float y, float z) {
		return f32(x).f32(y).f32(z);
	}

	public BinaryOutput vec3(Vector3f value) {
		return vec3(value.x, value.y, value.z);
	}

	/**
	 * A fixed-width run of bytes, used for a magic number
	 */
	public BinaryOutput fixedString(String value) {
		this.bytes.writeBytes(value.getBytes(StandardCharsets.UTF_8));

		return this;
	}

	/**
	 * A uint16-length-prefixed UTF-8 string, the shape every string-table entry takes
	 */
	public BinaryOutput string(String value) {
		byte[] encoded = value.getBytes(StandardCharsets.UTF_8);

		u16(encoded.length);
		this.bytes.writeBytes(encoded);

		return this;
	}

	public BinaryOutput zeros(int count) {
		for (int i = 0; i < count; i++) {
			this.bytes.write(0);
		}

		return this;
	}

	public BinaryOutput append(BinaryOutput other) {
		this.bytes.writeBytes(other.toByteArray());

		return this;
	}
}
