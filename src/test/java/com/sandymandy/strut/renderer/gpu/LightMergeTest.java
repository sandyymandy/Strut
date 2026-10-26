package com.sandymandy.strut.renderer.gpu;

import com.sandymandy.strut.renderer.gpu.LightMerge.Light;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LightMergeTest {
	@Test
	void fewerLightsThanSlotsAreKeptAsTheyAreStrongestFirst() {
		Light weak = Light.of(0.2f, 0f, 0f, 0xFFFFFF);
		Light strong = Light.of(0f, 0.9f, 0f, 0xFF0000);
		Light[] out = LightMerge.merge(List.of(weak, strong), 3);

		assertEquals(strong, out[0]);
		assertEquals(weak, out[1]);
		assertEquals(Light.NONE, out[2], "a slot with nothing is no light");
	}

	@Test
	void noLightsGiveNoLights() {
		for (Light light : LightMerge.merge(List.of(), 3))
			assertEquals(Light.NONE, light);
	}

	@Test
	void theStrongestStayAndTheRestAreAddedIntoTheLastSoNothingIsLostAndNothingCancelsBySlot() {
		Light a = Light.of(1f, 0f, 0f, 0xFFFFFF);
		Light b = Light.of(0f, 0.8f, 0f, 0xFFFFFF);
		Light c = Light.of(0.3f, 0f, 0f, 0xFF0000);
		Light d = Light.of(0.2f, 0f, 0f, 0x0000FF);
		Light[] out = LightMerge.merge(List.of(c, a, d, b), 3);

		assertEquals(a, out[0]);
		assertEquals(b, out[1]);
		assertEquals(0.5f, out[2].x(), 1e-6f, "the two weak ones added");
		// mixed by strength: 0.3 of red and 0.2 of blue
		assertEquals(0.6f, out[2].r(), 1e-6f);
		assertEquals(0.4f, out[2].b(), 1e-6f);
	}

	@Test
	void twoLightsEitherSideOfAModelBothSurvive() {
		Light east = Light.of(0.7f, 0f, 0f, 0xFFFFFF);
		Light west = Light.of(-0.7f, 0f, 0f, 0xFFFFFF);
		Light[] out = LightMerge.merge(List.of(east, west), 3);

		assertEquals(0.7f, out[0].x(), 1e-6f);
		assertEquals(-0.7f, out[1].x(), 1e-6f);
	}

	@Test
	void aColourIsSplitIntoChannelsFromZeroToOne() {
		Light light = Light.of(0f, 1f, 0f, 0x8040FF);

		assertEquals(128f / 255f, light.r(), 1e-6f);
		assertEquals(64f / 255f, light.g(), 1e-6f);
		assertEquals(1f, light.b(), 1e-6f);
	}
}
