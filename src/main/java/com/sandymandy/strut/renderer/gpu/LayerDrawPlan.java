package com.sandymandy.strut.renderer.gpu;

import com.sandymandy.strut.animation.override.OverrideProperty;
import com.sandymandy.strut.animation.state.BoneRenderLayers;
import com.sandymandy.strut.animation.state.BoneTextureOverrides;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;

/**
 * Which bone ranges one instance draws in the blended passes, and in what order
 * <p>
 * Pure data, no GL: {@link GpuBatchQueue} executes the list. Kept apart so the ordering rules can be tested without a graphics
 * context, which is the only way this logic can be tested at all
 * <p>
 * Order: the translucent bones first, in bone order; then each overlay layer in turn (layer 0, then 1, ...), in bone order within a
 * layer, so a higher layer always lands on top of a lower one
 */
final class LayerDrawPlan {
	/**
	 * @param bone The bone whose index range is drawn
	 * @param texture The texture to draw it with
	 * @param overlay Whether this is an overlay (it carries the bone's own colour and UV offset) rather than a translucent bone
	 * @param layer The overlay layer, or {@code -1} for a translucent bone
	 */
	record Draw(int bone, Identifier texture, boolean overlay, int layer) {}

	private LayerDrawPlan() {}

	/**
	 * @param layers This instance's resolved translucent flags and overlays, or null for none
	 * @param textures This instance's resolved {@code TEXTURE} overrides, or null: a translucent bone with one draws with it
	 * @param baseTexture The group's own texture
	 * @param translucentModel Whether the whole model is drawn blended already, in which case its bones are not drawn again as
	 *                         translucent bones, only their overlays are
	 * @param hasGeometry Whether a bone has any indices to draw; a bone with none is skipped
	 */
	static List<Draw> build(@Nullable BoneRenderLayers layers, @Nullable BoneTextureOverrides textures, Identifier baseTexture,
							boolean translucentModel, IntPredicate hasGeometry) {
		List<Draw> draws = new ArrayList<>();

		if (layers == null)
			return draws;

		if (!translucentModel) {
			for (int bone : layers.translucentBones()) {
				if (!hasGeometry.test(bone))
					continue;

				Identifier own = textures != null ? textures.get(bone) : null;

				draws.add(new Draw(bone, own != null ? own : baseTexture, false, -1));
			}
		}

		int[] overlayBones = layers.overlayBones();

		for (int layer = 0; layer < OverrideProperty.MAX_OVERLAYS; layer++) {
			for (int bone : overlayBones) {
				Identifier texture = layers.overlay(bone, layer);

				if (texture != null && hasGeometry.test(bone))
					draws.add(new Draw(bone, texture, true, layer));
			}
		}

		return draws;
	}

	/**
	 * @return Whether the opaque pass must leave {@code bone} out because a blended pass draws it instead. Also the bones a
	 *         {@code TEXTURE} redraw must skip, or it would draw them opaque
	 */
	static boolean drawnBlended(@Nullable BoneRenderLayers layers, boolean translucentModel, int bone) {
		return !translucentModel && layers != null && layers.isTranslucent(bone);
	}
}
