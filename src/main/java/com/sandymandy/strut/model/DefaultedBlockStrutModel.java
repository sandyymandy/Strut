package com.sandymandy.strut.model;

import net.minecraft.util.Identifier;
import com.sandymandy.strut.animatable.StrutAnimatable;

/**
 * {@link DefaultedStrutModel} specific to {@code Blocks}
 * <p>
 * Using this class pre-sorts provided asset paths into the "block" subdirectory
 */
public class DefaultedBlockStrutModel<T extends StrutAnimatable> extends DefaultedStrutModel<T> {
	/**
	 * Create a new instance of this model class
	 * <p>
	 * The asset path should be the truncated relative path from the base folder
	 * <p>
	 * E.G.
	 * <pre>{@code
	 * 	new Identifier("myMod", "workbench/sawmill")
	 * }</pre>
	 */
	public DefaultedBlockStrutModel(Identifier assetSubpath) {
		super(assetSubpath);
	}

	/**
	 * Returns the subtype string for this type of model
	 * <p>
	 * This allows for sorting of asset files into neat subdirectories for clean management.
	 */
	@Override
	protected String subtype() {
		return "block";
	}

	/**
	 * Changes the constructor-defined model path for this model to an alternate
	 * <p>
	 * This is useful if your animatable shares a model path with another animatable that differs in path to the texture and animations for this model
	 */
	@Override
	public DefaultedBlockStrutModel<T> withAltModel(Identifier altPath) {
		return (DefaultedBlockStrutModel<T>)super.withAltModel(altPath);
	}

	/**
	 * Changes the constructor-defined animations path for this model to an alternate
	 * <p>
	 * This is useful if your animatable shares an animations path with another animatable that differs in path to the model and texture for this model
	 */
	@Override
	public DefaultedBlockStrutModel<T> withAltAnimations(Identifier altPath) {
		return (DefaultedBlockStrutModel<T>)super.withAltAnimations(altPath);
	}

	/**
	 * Changes the constructor-defined texture path for this model to an alternate
	 * <p>
	 * This is useful if your animatable shares a texture path with another animatable that differs in path to the model and animations for this model
	 */
	@Override
	public DefaultedBlockStrutModel<T> withAltTexture(Identifier altPath) {
		return (DefaultedBlockStrutModel<T>)super.withAltTexture(altPath);
	}
}
