package com.sandymandy.strut.animation.state;

import java.util.Arrays;

/**
 * One instance's per-bone visual overrides - {@link com.sandymandy.strut.animation.override.OverrideProperty#COLOR}/
 * {@code UV_OFFSET} resolved once per frame by {@link ModelInstance#prepareForRender}, exactly like
 * {@link Pose} resolves the pose-affecting properties into {@link ModelInstance#effectivePose()} -
 * and, as there, they are only worked out for a bone that has one
 * <p>
 * Unlike {@link Pose}, there is no "base" to fall back to - a bone nobody has tinted or UV-shifted
 * has nothing here worth representing, which is why {@link ModelInstance#boneVisuals()} returns
 * {@code null} rather than an instance of this class full of neutral defaults. This class only ever
 * exists at all once at least one {@code COLOR}/{@code UV_OFFSET} override is active for the
 * instance it belongs to; every bone the override doesn't name still reads its neutral default from
 * here (white tint, zero UV offset), since the GPU path's per-bone visual buffer is dense - every
 * bone needs an entry, overridden or not
 */
public final class BoneVisuals {
	private final int[] colors;
	private final float[] uOffsets;
	private final float[] vOffsets;
	private final float[] uScales;
	private final float[] vScales;

	public BoneVisuals(int boneCount) {
		this.colors = new int[boneCount];
		this.uOffsets = new float[boneCount];
		this.vOffsets = new float[boneCount];
		this.uScales = new float[boneCount];
		this.vScales = new float[boneCount];

		reset();
	}

	/** Restores every bone to its neutral default - reused across frames rather than reallocated */
	public void reset() {
		Arrays.fill(this.colors, 0xFFFFFFFF);
		Arrays.fill(this.uOffsets, 0f);
		Arrays.fill(this.vOffsets, 0f);
		Arrays.fill(this.uScales, 1f);
		Arrays.fill(this.vScales, 1f);
	}

	public int boneCount() {
		return this.colors.length;
	}

	/** Packed ARGB, matching {@link com.sandymandy.strut.util.Color} - {@code 0xFFFFFFFF} (opaque white) when unset */
	public int getColor(int index) {
		return this.colors[index];
	}

	public void setColor(int index, int argb) {
		this.colors[index] = argb;
	}

	public float getUOffset(int index) {
		return this.uOffsets[index];
	}

	public float getVOffset(int index) {
		return this.vOffsets[index];
	}

	public void setUvOffset(int index, float u, float v) {
		this.uOffsets[index] = u;
		this.vOffsets[index] = v;
	}

	/** What the bone's UVs are multiplied by before the offset is added, {@code 1} when unset: the model's texture size over the override texture's {@code UV_SIZE} */
	public float getUScale(int index) {
		return this.uScales[index];
	}

	public float getVScale(int index) {
		return this.vScales[index];
	}

	public void setUvScale(int index, float u, float v) {
		this.uScales[index] = u;
		this.vScales[index] = v;
	}
}
