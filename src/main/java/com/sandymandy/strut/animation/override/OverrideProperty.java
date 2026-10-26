package com.sandymandy.strut.animation.override;

/**
 * The per-bone properties {@link BoneOverride} can carry a value for - the discrete,
 * server-authoritative overrides that replace
 * {@code BoneFilterStrutLayer}/{@code FastBoneFilterStrutLayer} with
 * <p>
 * Each constant corresponds to exactly one {@link OverrideValue} shape - {@link #valueShape()} says
 * which - so a caller building a {@link BoneOverride} for a given property always knows which
 * {@code OverrideValue} record to construct
 * <p>
 * {@code SCALE}/{@code ROTATION}/{@code POSITION}/{@code HIDDEN}/{@code CHILDREN_HIDDEN} resolve
 * into a bone's pose ({@code ModelInstance#effectivePose()}); {@code COLOR}/
 * {@code UV_OFFSET} resolve into the per-instance GPU visual buffer; {@code TEXTURE}
 * is the per-bone texture override, the one deliberately added last (see that
 * property's own note on why it costs an extra draw call). No generic "material" property exists -
 * One was sketched early on, but this engine has no material system for it to mean
 * anything against, and none is shipped
 */
public enum OverrideProperty {
	/** This bone's own geometry does not render - its children still do, unless they say otherwise */
	HIDDEN(OverrideValue.Shape.BOOL),

	/**
	 * This bone's children do not render, independently of whether this bone itself is hidden -
	 * the "hide a parent without hiding its children" case, which
	 * previously needed a mixin (see the CPU walk's {@code GroupBoneHandle#isHidden}/
	 * {@code isChildrenHidden} split, currently only half-honoured)
	 */
	CHILDREN_HIDDEN(OverrideValue.Shape.BOOL),

	/** Overrides this bone's authored rest position, in the same model-space units {@link com.sandymandy.strut.animation.state.Pose#setPosition} takes */
	POSITION(OverrideValue.Shape.VEC3),

	/** Overrides this bone's authored rest rotation, in radians, same convention as {@link com.sandymandy.strut.animation.state.Pose#setRotation} */
	ROTATION(OverrideValue.Shape.VEC3),

	/** Overrides this bone's authored rest scale */
	SCALE(OverrideValue.Shape.VEC3),

	/** Tints this bone's geometry - the {@code BoneFilterStrutLayer} tint use case */
	COLOR(OverrideValue.Shape.COLOR),

	/** Shifts this bone's UV sampling within its texture, for a scrolling/paged-texture effect */
	UV_OFFSET(OverrideValue.Shape.VEC2),

	/** Replaces this bone's texture entirely - the one that costs a draw call */
	TEXTURE(OverrideValue.Shape.IDENTIFIER),

	/**
	 * Draws this bone in the blended (translucent) pass instead of the cutout one
	 * Appended after {@code TEXTURE}: the wire format writes a property's ordinal, so the
	 * existing constants must keep their numbers
	 */
	TRANSLUCENT(OverrideValue.Shape.BOOL),

	/** A second texture drawn over this bone, translucent, after the base draw (layer 0 first) */
	OVERLAY_0(OverrideValue.Shape.IDENTIFIER),
	OVERLAY_1(OverrideValue.Shape.IDENTIFIER),
	OVERLAY_2(OverrideValue.Shape.IDENTIFIER),
	OVERLAY_3(OverrideValue.Shape.IDENTIFIER),

	/**
	 * The pixel size of the texture this bone's UVs are read against, when it is not the size the model was made for (width, height). A
	 * model's UVs are fractions of its own texture, so a {@code TEXTURE} override whose image is a different size (a player's skin on a model
	 * made for a smaller texture) lands on the wrong pixels; with this set the UVs are rescaled by the model's texture size over this size,
	 * so the same pixel positions are read in the override. Appended last: the wire format writes a property's ordinal
	 */
	UV_SIZE(OverrideValue.Shape.VEC2);

	/** How many overlay layers a bone can carry: {@code OVERLAY_0} to {@code OVERLAY_3} */
	public static final int MAX_OVERLAYS = 4;

	private final OverrideValue.Shape valueShape;

	OverrideProperty(OverrideValue.Shape valueShape) {
		this.valueShape = valueShape;
	}

	public OverrideValue.Shape valueShape() {
		return this.valueShape;
	}

	/**
	 * @return The property for overlay layer {@code layer}
	 * @throws IllegalArgumentException If {@code layer} is not in {@code 0} to {@link #MAX_OVERLAYS} exclusive
	 */
	public static OverrideProperty overlay(int layer) {
		return switch (layer) {
			case 0 -> OVERLAY_0;
			case 1 -> OVERLAY_1;
			case 2 -> OVERLAY_2;
			case 3 -> OVERLAY_3;
			default -> throw new IllegalArgumentException("overlay layer must be 0 to " + (MAX_OVERLAYS - 1) + ", not " + layer);
		};
	}

	/**
	 * @return Which overlay layer this is, or {@code -1} if it is not an overlay property
	 */
	public int overlayLayer() {
		return switch (this) {
			case OVERLAY_0 -> 0;
			case OVERLAY_1 -> 1;
			case OVERLAY_2 -> 2;
			case OVERLAY_3 -> 3;
			default -> -1;
		};
	}
}
