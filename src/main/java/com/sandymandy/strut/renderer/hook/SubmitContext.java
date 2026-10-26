package com.sandymandy.strut.renderer.hook;

import com.sandymandy.strut.model.ShadingMode;
import org.jetbrains.annotations.Nullable;

/** What a {@link StrutRenderHooks#SUBMIT} listener sees of one model about to be queued for drawing, and may change */
public final class SubmitContext {
	private final Object animatable;
	private int colour;
	private ShadingMode shading;
	private final float[] custom = new float[4];

	public SubmitContext(Object animatable, int colour, ShadingMode shading) {
		this.animatable = animatable;
		this.colour = colour;
		this.shading = shading;
	}

	/** The entity, block entity or item whose model this is */
	@Nullable
	public Object animatable() {
		return this.animatable;
	}

	/** The colour the model is multiplied by, {@code 0xAARRGGBB} */
	public int colour() {
		return this.colour;
	}

	public void setColour(int argb) {
		this.colour = argb;
	}

	/** How the model is shaded */
	public ShadingMode shading() {
		return this.shading;
	}

	public void setShading(ShadingMode shading) {
		this.shading = shading;
	}

	/**
	 * Sets one of the four floats that go with this model to the shader, which reads them as {@code instData.custom[i]} in the vertex hooks and {@code strutCustom[i]} in the
	 * fragment ones. Zero unless set
	 */
	public void setCustom(int index, float value) {
		this.custom[index] = value;
	}

	public float custom(int index) {
		return this.custom[index];
	}

	/** The four floats, for the renderer */
	public float[] customValues() {
		return this.custom;
	}
}
