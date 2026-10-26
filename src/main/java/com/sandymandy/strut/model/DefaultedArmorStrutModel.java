package com.sandymandy.strut.model;

import com.sandymandy.strut.animatable.StrutAnimatable;
import net.minecraft.util.Identifier;

/**
 * Using this class pre-sorts provided asset paths into the "armor" subdirectory: {@code <ns>:strut/models/armor/<path>.strutmodel},
 * {@code <ns>:textures/armor/<path>.png} and {@code <ns>:strut/animations/armor/<path>.strutanim}
 */
public class DefaultedArmorStrutModel<T extends StrutAnimatable> extends DefaultedStrutModel<T> {
	public DefaultedArmorStrutModel(Identifier assetSubpath) {
		super(assetSubpath);
	}

	@Override
	protected String subtype() {
		return "armor";
	}

	@Override
	public DefaultedArmorStrutModel<T> withAltModel(Identifier altPath) {
		return (DefaultedArmorStrutModel<T>)super.withAltModel(altPath);
	}

	@Override
	public DefaultedArmorStrutModel<T> withAltAnimations(Identifier altPath) {
		return (DefaultedArmorStrutModel<T>)super.withAltAnimations(altPath);
	}

	@Override
	public DefaultedArmorStrutModel<T> withAltTexture(Identifier altPath) {
		return (DefaultedArmorStrutModel<T>)super.withAltTexture(altPath);
	}
}
