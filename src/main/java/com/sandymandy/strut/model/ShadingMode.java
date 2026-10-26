package com.sandymandy.strut.model;

/**
 * How the GPU path shades a model by the direction its surfaces face (see {@link StrutModel#shadingMode}). The world's own light, from the block and sky light
 * of where it stands, darkens it the same way in every mode
 */
public enum ShadingMode {
	/** The game's own entity shading: two directional lights over a base of 0.4, which is dark on a face turned from both and has hard steps between faces */
	VANILLA,
	/** Wrapped light and a sky-to-ground tint: a face turned from the light is dimmer but never harsh, and the small cubes and meshes a character is made of blend */
	SOFT,
	/**
	 * An anime look on {@link #SOFT}'s light: the shadow is a deeper, more saturated and slightly cool version of the texture's own colour, not the same colour
	 * darker, with a middle tone and soft edges between the tones, a thin soft rim of light on the lit side of the outline, and the world's own light (block and sky
	 * light) in steps, so the model steps down as it goes into shade or the night comes
	 */
	TOON,
	/** No shading by direction: every surface is lit the same, as if it faced the light. The world's own light still applies */
	FLAT;

	/** What a model that does not choose has */
	public static final ShadingMode DEFAULT = SOFT;
}
