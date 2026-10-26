package com.sandymandy.strut.model;

/**
 * How a model's geometry is drawn by default
 * <p>
 * A per-bone {@code TRANSLUCENT} override (see {@code EntityBoneOverrides#setTranslucent}) moves a single bone the other way
 */
public enum RenderMode {
	/** Opaque, with alpha below a small threshold discarded: vanilla's entity cutout. The default */
	CUTOUT,

	/** Alpha-blended, drawn after the opaque geometry, with no depth writes: vanilla's entity translucent */
	TRANSLUCENT
}
