package com.sandymandy.strut.renderer.iris;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Under a shader pack a bone whose texture override replaces the model's texture is left out of the main draw, so the main draw becomes several index
 * ranges per instance. Which ranges decides what is drawn twice, and what is not drawn at all
 */
class IrisMainDrawSegmentsTest {
	private static final int INDICES = 600;
	private static final int VERTICES = 400;

	@Test
	void withNothingSkippedEachInstanceIsOneDrawOfTheWholeModel() {
		IrisMainDraw.Segments s = IrisMainDraw.segments(INDICES, VERTICES, 3, null);

		assertArrayEquals(new int[] {600, 600, 600}, s.counts());
		assertArrayEquals(new long[] {0, 0, 0}, s.starts());
		assertArrayEquals(new int[] {0, 400, 800}, s.baseVertices());
	}

	@Test
	void aSkippedRangeInTheMiddleSplitsTheDrawInTwoAndLeavesItOut() {
		IrisMainDraw.Segments s = IrisMainDraw.segments(INDICES, VERTICES, 1, new int[][] {{120, 60}});

		assertArrayEquals(new int[] {120, 420}, s.counts());
		assertArrayEquals(new long[] {0, 180L * Integer.BYTES}, s.starts());
		assertArrayEquals(new int[] {0, 0}, s.baseVertices());
	}

	@Test
	void aRangeAtEitherEndLeavesOneDrawAndTwoRangesGiveThree() {
		IrisMainDraw.Segments start = IrisMainDraw.segments(INDICES, VERTICES, 1, new int[][] {{0, 100}});

		assertArrayEquals(new int[] {500}, start.counts());
		assertArrayEquals(new long[] {100L * Integer.BYTES}, start.starts());

		IrisMainDraw.Segments end = IrisMainDraw.segments(INDICES, VERTICES, 1, new int[][] {{500, 100}});

		assertArrayEquals(new int[] {500}, end.counts());
		assertArrayEquals(new long[] {0}, end.starts());

		IrisMainDraw.Segments two = IrisMainDraw.segments(INDICES, VERTICES, 1, new int[][] {{60, 30, 200, 100}});

		assertArrayEquals(new int[] {60, 110, 300}, two.counts());
		assertArrayEquals(new long[] {0, 90L * Integer.BYTES, 300L * Integer.BYTES}, two.starts());
	}

	@Test
	void adjacentRangesLeaveNoEmptyDraw() {
		IrisMainDraw.Segments s = IrisMainDraw.segments(INDICES, VERTICES, 1, new int[][] {{100, 50, 150, 50}});

		assertArrayEquals(new int[] {100, 400}, s.counts());
		assertArrayEquals(new long[] {0, 200L * Integer.BYTES}, s.starts());
	}

	@Test
	void instancesAreSplitIndependentlyAndKeepTheirOwnBaseVertex() {
		IrisMainDraw.Segments s = IrisMainDraw.segments(INDICES, VERTICES, 3, new int[][] {null, {100, 100}, null});

		assertArrayEquals(new int[] {600, 100, 400, 600}, s.counts());
		assertArrayEquals(new int[] {0, 400, 400, 800}, s.baseVertices());
	}

	@Test
	void everythingSkippedLeavesNothingToDraw() {
		assertEquals(0, IrisMainDraw.segments(INDICES, VERTICES, 1, new int[][] {{0, 600}}).counts().length);
	}
}
