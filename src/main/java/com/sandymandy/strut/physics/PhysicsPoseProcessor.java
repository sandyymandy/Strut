package com.sandymandy.strut.physics;

import com.sandymandy.strut.animation.state.PoseProcessor;
import com.sandymandy.strut.cache.object.BakedStrutModel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import com.sandymandy.strut.physics.collision.ModelSdfs;
import com.sandymandy.strut.physics.config.PhysicsConfig;
import com.sandymandy.strut.physics.config.PhysicsConfigs;
import com.sandymandy.strut.physics.config.PhysicsOptions;
import com.sandymandy.strut.physics.flesh.FleshInstance;
import com.sandymandy.strut.physics.solver.MotionSampler;
import com.sandymandy.strut.physics.tools.PhysicsControls;
import com.sandymandy.strut.physics.tools.PhysicsStats;
import com.sandymandy.strut.physics.world.WorldBlocks;

/**
 * The module's pose processor: for a model with a physics config, steps that instance's springs to this frame and adds their offsets to the pose. A model
 * without a config costs one identity map lookup per frame
 */
public final class PhysicsPoseProcessor implements PoseProcessor {
	static final PhysicsPoseProcessor INSTANCE = new PhysicsPoseProcessor();

	private static final Logger LOGGER = LogManager.getLogger("Strut Physics");
	private static final Object STATE_KEY = new Object();
	/** Controls of an entity nobody tuned: never modified */
	private static final PhysicsControls DEFAULTS = new PhysicsControls();
	/** The most steps one that waited for its turn takes when it gets it: enough that its motion runs on, too few to be a spike */
	private static final int CATCH_UP = 1;
	/** Within this of the camera flesh feels everything (other entities, blocks, its own body); within the second its own body only; past it, nothing, blocks */
	private static final double CONTACT_ALL_WITHIN = 8;
	/** Within this many blocks a region is pressed by the most shapes of the others; past it, half as many */
	private static final double CONTACT_MANY_WITHIN = 5;
	private static final double CONTACT_BODY_WITHIN = 12;

	/** The live physics instance of each entity that had any this frame, for the debug command and the debug lines */
	public static final Map<Integer, PhysicsInstance> LIVE = new ConcurrentHashMap<>();

	private PhysicsPoseProcessor() {}

	/** The per-instance state: replaced when the config changes */
	private static final class Holder {
		PhysicsInstance instance;
		/** Whether the model's mesh colliders were made when the instance was: when they become ready later, the instance is made again with them */
		boolean fieldsReady;
		/** How many frames in a row this instance has waited for its turn at the frame's budget */
		int starved;
	}

	@Override
	public boolean appliesTo(BakedStrutModel model) {
		return PhysicsConfigs.forModel(model) != null;
	}

	@Override
	public void process(Context context) {
		if (!PhysicsOptions.enabled())
			return;

		PhysicsControls controls = context.entityId() >= 0 ? StrutPhysics.existing(context.entityId()) : null;

		if (controls != null && !controls.enabled())
			return;

		PhysicsConfig config = PhysicsConfigs.forModel(context.model());
		Holder holder = context.state(STATE_KEY, Holder::new);

		// a scenario or a reload can change the config under a live instance: build a fresh one
		if (holder.instance != null && !holder.fieldsReady && ModelSdfs.ready(context.model()))
			holder.instance = null;

		if (holder.instance == null || holder.instance.config() != config) {
			holder.instance = new PhysicsInstance(context.model(), config);
			holder.fieldsReady = ModelSdfs.ready(context.model());
			holder.instance.world(WorldBlocks.INSTANCE);
			holder.instance.entityId(context.entityId());
			// a crowd's turns are spread over the frames by where in the cadence each instance starts
			holder.starved = Math.floorMod(context.entityId(), PhysicsStats.MAX_CADENCE);

			if (!holder.instance.missingBones().isEmpty())
				LOGGER.warn("Physics config names bones the model does not have: {}", holder.instance.missingBones());

			if (!holder.instance.problems().isEmpty())
				LOGGER.warn("Physics cloth and soft body problems: {}", holder.instance.problems());
		}

		PhysicsInstance instance = holder.instance;

		long started = System.nanoTime();
		PhysicsInstance.Sample sample = context.entityId() >= 0 ? MotionSampler.sample(context.entityId()) : null;

		double distance = sample != null ? MotionSampler.distanceFromCamera(sample.x(), sample.y(), sample.z()) : 0.0;

		if (sample != null && distance > PhysicsOptions.effectiveRange()) {
			// Out of range: drawn as animated, and the springs restart when it comes back rather than jumping
			instance.reset();

			return;
		}

		// soft bodies have a shorter range of their own: past it the flesh is a few pixels, and is drawn as modelled
		instance.fleshInRange(distance <= PhysicsOptions.effectiveFleshRange());

		boolean viewer = sample != null && MotionSampler.isViewer(context.entityId());

		// what the flesh feels: everything near the camera, where a dent shows; its own body farther off; only its swing far away
		instance.fleshContact(viewer || distance <= CONTACT_ALL_WITHIN ? FleshInstance.CONTACT_ALL
				: distance <= CONTACT_BODY_WITHIN ? FleshInstance.CONTACT_BODY : FleshInstance.CONTACT_NONE,
				viewer || distance <= CONTACT_MANY_WITHIN ? FleshInstance.MOST_OTHERS : FleshInstance.MOST_OTHERS / 2);

		PhysicsStats.Turn turn = sample != null && instance.canHold() ? PhysicsStats.admit(holder.starved, distance, viewer)
				: PhysicsStats.Turn.FULL;

		if (turn == PhysicsStats.Turn.HOLD) {
			// the budget is spent: the last result is drawn again and this instance waits for its turn
			holder.starved++;
			instance.hold(context.pose(), controls != null ? controls : DEFAULTS);
		}
		else {
			// one that was held does not catch up the steps it missed (that would be a spike where the budget was saved, and each turn of the crowd would cost
			// as many steps as it waited): the rest of its time is let go of
			instance.stepLimit = turn == PhysicsStats.Turn.ONE || holder.starved > 0 ? Math.min(holder.starved + 1, CATCH_UP) : Integer.MAX_VALUE;
			holder.starved = 0;
			instance.update(context.model(), context.animated(), context.pose(), sample, controls != null ? controls : DEFAULTS);
		}

		instance.clothDeforms((mesh, deform) -> context.instance().setMeshDeform(mesh, deform));

		if (context.entityId() >= 0)
			LIVE.put(context.entityId(), instance);

		PhysicsStats.record(instance.lastSimulatedBones, instance.lastSteps, System.nanoTime() - started);
	}
}
