package com.sandymandy.strut.physics.collision;

import com.sandymandy.strut.animation.state.PoseProcessor;
import com.sandymandy.strut.cache.object.BakedStrutModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import com.sandymandy.strut.physics.PhysicsInstance;
import com.sandymandy.strut.physics.config.PhysicsOptions;
import com.sandymandy.strut.physics.solver.MotionSampler;

/**
 * Makes every Strut model solid to the things that collide with bodies (the thick liquid, other models' chains and cloth), with nothing authored: from its
 * own geometry, as a signed distance field per bone ({@link ModelSdfs}), placed on the bones of the pose it was drawn in
 * <p>
 * It costs nothing while nothing is listening: a model publishes its shapes only in a frame that follows one in which something asked for them
 * ({@link Bodies#demanded()}), and only until the model's fields are made, which is once, in the background, the first time anything wanted them. A model
 * whose physics instance publishes its own shapes is left to that
 */
public final class BodyPublisher implements PoseProcessor {
	public static final BodyPublisher INSTANCE = new BodyPublisher();

	private static final Object STATE_KEY = new Object();

	private BodyPublisher() {}

	private static final class Holder {
		ColliderRig rig;
		final List<Collider.Placed> out = new ArrayList<>();
	}

	@Override
	public boolean appliesTo(BakedStrutModel model) {
		return ModelSdfs.enabled() && PhysicsOptions.meshColliders();
	}

	@Override
	public void process(Context context) {
		int id = context.entityId();

		if (id < 0 || !Bodies.demanded() || !PhysicsOptions.enabled() || Bodies.publishedThisFrame(id))
			return;

		Holder holder = context.state(STATE_KEY, Holder::new);

		if (holder.rig == null) {
			// the fields are made in the background the first time they are asked for; until they are, the entity keeps its hitbox
			if (ModelSdfs.get(context.model()) == null)
				return;

			List<Collider> shapes = AutoColliders.generate(context.model(), AutoColliders.Options.DEFAULT, Set.of());

			holder.rig = new ColliderRig(context.model(), List.of(), shapes, new ArrayList<>());
		}

		if (holder.rig.count() == 0)
			return;

		PhysicsInstance.Sample sample = MotionSampler.sample(id);

		if (sample == null)
			return;

		holder.rig.prepare(context.animated());
		holder.out.clear();
		holder.rig.place(sample.x(), sample.y(), sample.z(), sample.yawDegrees(), null, holder.out);
		Bodies.publishModel(id, holder.out);
	}
}
