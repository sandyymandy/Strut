package com.sandymandy.strut.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link Color#multiplyArgb} is the CPU path's own combine of a base render colour with a per-bone
 * {@code COLOR} override - genuinely unit-testable
 * pure math, so it is
 */
class ColorTest {
	@Test
	void multiplyingByOpaqueWhiteIsIdentity() {
		assertEquals(0xFF123456, Color.multiplyArgb(0xFF123456, 0xFFFFFFFF));
	}

	@Test
	void multiplyingByOpaqueBlackZeroesEveryChannelButAlpha() {
		assertEquals(0xFF000000, Color.multiplyArgb(0xFF123456, 0xFF000000));
	}

	@Test
	void multipliesEachChannelIndependently() {
		// 0x80 * 0xFF / 255 == 0x80 exactly for every channel - multiplying by full-strength (0xFF)
		// leaves an already-scaled channel unchanged
		int result = Color.multiplyArgb(0x80808080, 0xFFFFFFFF);

		assertEquals(0x80, result >>> 24 & 0xFF);
		assertEquals(0x80, result >> 16 & 0xFF);
		assertEquals(0x80, result >> 8 & 0xFF);
		assertEquals(0x80, result & 0xFF);
	}

	@Test
	void halfAlphaTintedOverHalfAlphaBaseProducesQuarterAlpha() {
		// 0x80 * 0x80 / 255 == 64 (0x40) - two ~50% alphas compound to ~25%
		int result = Color.multiplyArgb(0x80FFFFFF, 0x80FFFFFF);

		assertEquals(0x40, result >>> 24 & 0xFF);
	}
}
