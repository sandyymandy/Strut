package com.sandymandy.strut.animation.attachment;

/**
 * How a worn model's bone is moved from where its wearer's bone puts it, to make a model fit a wearer it was not made for: a helmet made for a smaller head, a
 * chest made on a rig with another pivot
 * <p>
 * Applied after the wearer's pose is copied into the bone, so it moves with the wearer's animation. Offsets are in the same units animations move bones in
 * (sixteenths of a block); rotations are degrees, added to the bone's; scales multiply the bone's
 *
 * @param offset x, y, z
 * @param rotation x, y, z in degrees
 * @param scale x, y, z
 */
public record BoneFit(float[] offset, float[] rotation, float[] scale) {
	public static final BoneFit NONE = new BoneFit(new float[3], new float[3], new float[] {1f, 1f, 1f});

	public BoneFit {
		offset = offset.clone();
		rotation = rotation.clone();
		scale = scale.clone();
	}

	/** Whether this changes nothing */
	public boolean isNone() {
		for (int i = 0; i < 3; i++)
			if (this.offset[i] != 0f || this.rotation[i] != 0f || this.scale[i] != 1f)
				return false;

		return true;
	}
}
