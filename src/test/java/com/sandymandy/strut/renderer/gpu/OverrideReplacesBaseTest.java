package com.sandymandy.strut.renderer.gpu;

import com.sandymandy.strut.animation.state.BoneTextureOverrides;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A bone with a TEXTURE override (a player skin on a body bone) is left out of the main draw, so the override replaces the model's own texture there
 * instead of being drawn over it
 */
class OverrideReplacesBaseTest {
	private static final Identifier SKIN = Identifier.of("minecraft", "skins/0123456789abcdef0123456789abcdef01234567");

	@Test
	void anOverriddenBoneWithGeometryIsReplaced() {
		BoneTextureOverrides textures = new BoneTextureOverrides();

		textures.set(3, SKIN);

		assertTrue(GpuBatchQueue.replacedByOverride(textures, bone -> true, 3));
		assertFalse(GpuBatchQueue.replacedByOverride(textures, bone -> true, 4), "another bone keeps the model's texture");
	}

	@Test
	void nothingIsReplacedWithoutAnOverrideOrGeometry() {
		BoneTextureOverrides textures = new BoneTextureOverrides();

		assertFalse(GpuBatchQueue.replacedByOverride(null, bone -> true, 0));
		assertFalse(GpuBatchQueue.replacedByOverride(textures, bone -> true, 0));

		textures.set(3, SKIN);

		assertFalse(GpuBatchQueue.replacedByOverride(textures, bone -> false, 3), "a bone with no indices has no redraw, so it is not left out");
	}

	@Test
	void theDefaultRenderKeyReplaces() {
		assertTrue(GpuBatchQueue.RenderKey.DEFAULT.overrideReplacesBase());
	}
}
