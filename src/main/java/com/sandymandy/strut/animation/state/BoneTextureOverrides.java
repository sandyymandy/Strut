package com.sandymandy.strut.animation.state;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

/**
 * One instance's per-bone {@code TEXTURE} overrides - resolved once per frame by
 * {@link ModelInstance#prepareForRender}, the same "resolved once, read every render" shape as
 * {@link BoneVisuals} - the one
 * deliberately shipped last (an extra {@code glDrawElementsInstanced} call per distinct per-bone
 * texture, rather than a dense per-bone GPU buffer like {@code BoneVisuals}' own)
 * <p>
 * Sparse rather than dense on purpose, unlike {@link BoneVisuals}: this drives which bones need an
 * <em>extra draw call</em> at all (see {@code GpuBatchQueue}'s own doc comment on how), so a bone
 * with nothing overridden simply has no entry here, rather than an entry holding some "no override"
 * sentinel every bone would otherwise need to carry
 */
public final class BoneTextureOverrides {
	private final Int2ObjectMap<Identifier> textures = new Int2ObjectOpenHashMap<>();

	public void reset() {
		this.textures.clear();
	}

	public void set(int boneIndex, Identifier texture) {
		this.textures.put(boneIndex, texture);
	}

	@Nullable
	public Identifier get(int boneIndex) {
		return this.textures.get(boneIndex);
	}

	public boolean isEmpty() {
		return this.textures.isEmpty();
	}

	/**
	 * Every bone index with an active override, paired with its texture - what
	 * {@code GpuBatchQueue}'s own extra-draw pass iterates
	 */
	public Int2ObjectMap<Identifier> entries() {
		return this.textures;
	}
}
