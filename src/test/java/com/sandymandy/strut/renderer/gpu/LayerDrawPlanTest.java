package com.sandymandy.strut.renderer.gpu;

import com.sandymandy.strut.animation.state.BoneRenderLayers;
import com.sandymandy.strut.animation.state.BoneTextureOverrides;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Which bone ranges are drawn in the blended passes, and in what order. The GL that executes the list cannot be
 * tested without a graphics context; the ordering rules can
 */
class LayerDrawPlanTest {
	private static final Identifier BASE = Identifier.of("strut", "textures/entity/base.png");
	private static final Identifier SKIN = Identifier.of("strut", "textures/entity/skin.png");
	private static final Identifier DECAL = Identifier.of("strut", "textures/entity/decal.png");
	private static final Identifier SPOTS = Identifier.of("strut", "textures/entity/spots.png");

	@Test
	void nothingIsDrawnWithoutLayers() {
		assertTrue(LayerDrawPlan.build(null, null, BASE, false, bone -> true).isEmpty());
		assertTrue(LayerDrawPlan.build(new BoneRenderLayers(), null, BASE, false, bone -> true).isEmpty());
	}

	@Test
	void translucentBonesComeFirstInBoneOrderAndOverlaysFollowLayerByLayer() {
		BoneRenderLayers layers = new BoneRenderLayers();

		layers.setTranslucent(5, true);
		layers.setTranslucent(2, true);
		layers.setOverlay(7, 1, SPOTS);
		layers.setOverlay(3, 0, DECAL);
		layers.setOverlay(7, 0, DECAL);

		List<LayerDrawPlan.Draw> draws = LayerDrawPlan.build(layers, null, BASE, false, bone -> true);

		assertEquals(List.of(
				new LayerDrawPlan.Draw(2, BASE, false, -1),
				new LayerDrawPlan.Draw(5, BASE, false, -1),
				new LayerDrawPlan.Draw(3, DECAL, true, 0),
				new LayerDrawPlan.Draw(7, DECAL, true, 0),
				new LayerDrawPlan.Draw(7, SPOTS, true, 1)), draws);
	}

	@Test
	void aTranslucentBoneWithATextureOverrideDrawsWithIt() {
		BoneRenderLayers layers = new BoneRenderLayers();
		BoneTextureOverrides textures = new BoneTextureOverrides();

		layers.setTranslucent(4, true);
		textures.set(4, SKIN);

		assertEquals(List.of(new LayerDrawPlan.Draw(4, SKIN, false, -1)), LayerDrawPlan.build(layers, textures, BASE, false, bone -> true));
	}

	@Test
	void aTranslucentModelDrawsOnlyItsOverlaysBecauseItsBonesAreAlreadyBlended() {
		BoneRenderLayers layers = new BoneRenderLayers();

		layers.setTranslucent(1, true);
		layers.setOverlay(1, 0, DECAL);

		assertEquals(List.of(new LayerDrawPlan.Draw(1, DECAL, true, 0)), LayerDrawPlan.build(layers, null, BASE, true, bone -> true));
	}

	@Test
	void aBoneWithNoGeometryIsSkipped() {
		BoneRenderLayers layers = new BoneRenderLayers();

		layers.setTranslucent(1, true);
		layers.setTranslucent(2, true);
		layers.setOverlay(1, 0, DECAL);

		assertEquals(List.of(new LayerDrawPlan.Draw(2, BASE, false, -1)), LayerDrawPlan.build(layers, null, BASE, false, bone -> bone == 2));
	}

	@Test
	void theOpaquePassLeavesOutExactlyTheBonesTheBlendedPassDraws() {
		BoneRenderLayers layers = new BoneRenderLayers();

		layers.setTranslucent(3, true);

		assertTrue(LayerDrawPlan.drawnBlended(layers, false, 3));
		assertFalse(LayerDrawPlan.drawnBlended(layers, false, 4), "an unflagged bone stays in the opaque pass");
		assertFalse(LayerDrawPlan.drawnBlended(layers, true, 3), "in a translucent model the whole model is one blended pass, not per bone");
		assertFalse(LayerDrawPlan.drawnBlended(null, false, 3));
	}
}
