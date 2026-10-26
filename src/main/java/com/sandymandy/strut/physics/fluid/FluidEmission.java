package com.sandymandy.strut.physics.fluid;

import com.sandymandy.strut.animation.state.PoseProcessor;
import com.sandymandy.strut.cache.object.BakedStrutModel;

/**
 * Starts the particles of the emissions that follow a bone of a model, as that model is drawn: the one place the bone's place this frame is known. It does
 * nothing to the pose, and nothing at all while no emission is under way
 */
public final class FluidEmission implements PoseProcessor {
	public static final FluidEmission INSTANCE = new FluidEmission();

	private FluidEmission() {}

	@Override
	public boolean appliesTo(BakedStrutModel model) {
		return true;
	}

	@Override
	public void process(Context context) {
		FluidSystem.processEntity(context.entityId(), context.model(), context.animated());
	}
}
