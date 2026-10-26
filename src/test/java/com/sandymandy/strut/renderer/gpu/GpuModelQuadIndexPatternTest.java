package com.sandymandy.strut.renderer.gpu;

import com.sandymandy.strut.cache.object.BakedStrutModel;
import com.sandymandy.strut.loading.binary.BinaryFixtures;
import com.sandymandy.strut.loading.binary.BinaryModelBaker;
import com.sandymandy.strut.loading.binary.BinaryModelReader;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The compute pre-pass
 * ({@code IrisPrePass}) dispatches one invocation per quad and assumes every four consecutive baked
 * vertices form exactly one quad, drawn as two triangles via the fixed {@code (n, n+1, n+2, n, n+2,
 * n+3)} index pattern {@link GpuModel#appendFace}/{@code appendSkinnedFace} both emit - this pins
 * that assumption directly, so a future change to either method that broke it would fail here instead
 * of silently corrupting the compute pass's own quad math
 */
class GpuModelQuadIndexPatternTest {
	@Test
	@DisplayName("every baked model's indices are a repeating (n, n+1, n+2, n, n+2, n+3) pattern, four vertices per quad")
	void indicesFollowTheFixedQuadPattern() {
		assertQuadPattern(BinaryModelBaker.bake(
				BinaryModelReader.read(BinaryFixtures.cubeModel(), "cube.strutmodel"), "cube.strutmodel"));
		assertQuadPattern(BinaryModelBaker.bake(
				BinaryModelReader.read(BinaryFixtures.riggedModel(), "rig.strutmodel"), "rig.strutmodel"));
	}

	private static void assertQuadPattern(BakedStrutModel model) {
		GpuModel.Accumulated baked = GpuModel.accumulate(model);
		IntArrayList indices = baked.indices();

		assertEquals(0, indices.size() % 6, "every quad contributes exactly six indices (two triangles)");

		int vertexCount = baked.vertexBoneIndices().size();

		assertEquals(0, vertexCount % 4, "every quad contributes exactly four vertices");

		for (int quad = 0; quad < indices.size() / 6; quad++) {
			int base = quad * 6;
			int n = quad * 4;

			assertEquals(n, indices.getInt(base), "quad " + quad + " index 0");
			assertEquals(n + 1, indices.getInt(base + 1), "quad " + quad + " index 1");
			assertEquals(n + 2, indices.getInt(base + 2), "quad " + quad + " index 2");
			assertEquals(n, indices.getInt(base + 3), "quad " + quad + " index 3");
			assertEquals(n + 2, indices.getInt(base + 4), "quad " + quad + " index 4");
			assertEquals(n + 3, indices.getInt(base + 5), "quad " + quad + " index 5");
		}
	}
}
