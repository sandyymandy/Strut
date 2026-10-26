package com.sandymandy.strut.loading.binary;

import com.sandymandy.strut.cache.object.BakedStrutModel;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Extension sections in a {@code .strutmodel}: opaque bytes keyed by name, after the
 * armature block, announced by header flag bit 0. Core keeps them and never reads them
 */
class ModelExtensionSectionTest {
	/** The smallest valid model: one string and nothing else, optionally with an extension trailer, optionally cut short */
	private static byte[] model(boolean flag, Map<String, byte[]> sections, int cutBytes) {
		byte[] id = "test:m".getBytes(StandardCharsets.UTF_8);
		ByteBuffer buf = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN);

		buf.put("STMD".getBytes(StandardCharsets.US_ASCII));
		buf.putShort((short)101);
		buf.putShort((short)(flag ? StrutBinaryFormats.MODEL_FLAG_EXTENSIONS : 0));
		buf.putShort((short)1);

		for (int i = 0; i < 5; i++)
			buf.putShort((short)0);

		buf.putInt(2 + id.length);
		buf.putFloat(16f);

		for (int i = 0; i < 6; i++)
			buf.putFloat(0f);

		buf.put(new byte[12]);
		buf.putShort((short)id.length);
		buf.put(id);

		if (flag) {
			buf.putShort((short)sections.size());

			for (Map.Entry<String, byte[]> entry : sections.entrySet()) {
				byte[] name = entry.getKey().getBytes(StandardCharsets.UTF_8);

				buf.putShort((short)name.length);
				buf.put(name);
				buf.putInt(entry.getValue().length);
				buf.put(entry.getValue());
			}
		}

		byte[] out = new byte[buf.position() - cutBytes];

		System.arraycopy(buf.array(), 0, out, 0, out.length);

		return out;
	}

	@Test
	void sectionsAreKeptAsOpaqueBytesInFileOrder() {
		Map<String, byte[]> sections = new LinkedHashMap<>();

		sections.put("strut_physics:physics", new byte[] {1, 2, 3, 4});
		sections.put("other:thing", new byte[0]);

		var raw = BinaryModelReader.read(model(true, sections, 0), "test");

		assertEquals(List.of("strut_physics:physics", "other:thing"), List.copyOf(raw.extensions().keySet()));
		assertArrayEquals(new byte[] {1, 2, 3, 4}, raw.extensions().get("strut_physics:physics"));

		BakedStrutModel baked = BinaryModelBaker.bake(raw, "test");

		assertArrayEquals(new byte[] {1, 2, 3, 4}, baked.extension("strut_physics:physics").orElseThrow());
		assertTrue(baked.extension("strut_physics:missing").isEmpty());

		baked.extension("strut_physics:physics").orElseThrow()[0] = 99;

		assertEquals(1, baked.extension("strut_physics:physics").orElseThrow()[0], "the caller gets a copy");
	}

	@Test
	void aModelWithoutTheFlagHasNoSectionsAndIsReadExactlyAsBefore() {
		var raw = BinaryModelReader.read(model(false, Map.of(), 0), "test");

		assertTrue(raw.extensions().isEmpty());
		assertTrue(BinaryModelBaker.bake(raw, "test").extensions().isEmpty());
	}

	@Test
	void aTruncatedTrailerIsAnErrorNamingTheFile() {
		Map<String, byte[]> one = new LinkedHashMap<>();

		one.put("a:b", new byte[] {1, 2, 3, 4, 5});

		BinaryFormatException error = assertThrows(BinaryFormatException.class, () -> BinaryModelReader.read(model(true, one, 3), "test"));

		assertTrue(error.getMessage().contains("test"), error.getMessage());
	}
}
