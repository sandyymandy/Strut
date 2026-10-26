package com.sandymandy.strut.animation.state;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import java.util.Arrays;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;
import com.sandymandy.strut.animation.override.OverrideProperty;

/**
 * One instance's per-bone translucent flags and overlay textures, resolved once per frame by {@link ModelInstance#prepareForRender}
 *
 * <p>
 * Sparse, like {@link BoneTextureOverrides}: a bone with neither has no entry. What draws them - a blended pass after the opaque
 * one, then each overlay layer in order - lives in the renderer; this class only says which bones and which textures
 */
public final class BoneRenderLayers {
	private final IntOpenHashSet translucent = new IntOpenHashSet();
	private final Int2ObjectMap<Identifier[]> overlays = new Int2ObjectOpenHashMap<>();

	public void reset() {
		this.translucent.clear();
		this.overlays.clear();
	}

	public void setTranslucent(int boneIndex, boolean value) {
		if (value)
			this.translucent.add(boneIndex);
		else
			this.translucent.remove(boneIndex);
	}

	public boolean isTranslucent(int boneIndex) {
		return this.translucent.contains(boneIndex);
	}

	/**
	 * @param layer {@code 0} to {@link OverrideProperty#MAX_OVERLAYS} exclusive
	 */
	public void setOverlay(int boneIndex, int layer, Identifier texture) {
		if (layer < 0 || layer >= OverrideProperty.MAX_OVERLAYS)
			throw new IllegalArgumentException("overlay layer must be 0 to " + (OverrideProperty.MAX_OVERLAYS - 1) + ", not " + layer);

		this.overlays.computeIfAbsent(boneIndex, index -> new Identifier[OverrideProperty.MAX_OVERLAYS])[layer] = texture;
	}

	/**
	 * @return This bone's texture for overlay {@code layer}, or null if none
	 */
	@Nullable
	public Identifier overlay(int boneIndex, int layer) {
		Identifier[] layers = this.overlays.get(boneIndex);

		return layers == null ? null : layers[layer];
	}

	/**
	 * @return Every bone index with at least one overlay, ascending
	 */
	public int[] overlayBones() {
		int[] bones = this.overlays.keySet().toIntArray();

		Arrays.sort(bones);

		return bones;
	}

	/**
	 * @return Every bone index flagged translucent, ascending
	 */
	public int[] translucentBones() {
		int[] bones = this.translucent.toIntArray();

		Arrays.sort(bones);

		return bones;
	}

	public boolean hasTranslucent() {
		return !this.translucent.isEmpty();
	}

	public boolean hasOverlays() {
		return !this.overlays.isEmpty();
	}

	public boolean isEmpty() {
		return this.translucent.isEmpty() && this.overlays.isEmpty();
	}
}
