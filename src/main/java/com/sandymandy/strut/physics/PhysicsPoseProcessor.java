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
				LOGGER.warn("Physics cloth problems: {}", holder.instance.problems());
		}

		PhysicsInstance instance = holder.instance;

		long started = System.nanoTime();
		PhysicsInstance.Sample sample = context.entityId() >= 0 ? MotionSampler.sample(context.entityId()) : null;

		if (sample != null && MotionSampler.distanceFromCamera(sample.x(), sample.y(), sample.z()) > PhysicsOptions.effectiveRange()) {
			// Out of range: drawn as animated, and the springs restart when it comes back rather than jumping
			instance.reset();

			return;
		}

		PhysicsStats.Turn turn = sample != null && instance.canHold() ? PhysicsStats.admit(holder.starved) : PhysicsStats.Turn.FULL;

		if (turn == PhysicsStats.Turn.HOLD) {
			// the budget is spent: the last result is drawn again and this instance waits for its turn
			holder.starved++;
			instance.hold(context.pose(), controls != null ? controls : DEFAULTS);
		}
		else {
			// one that was held does not catch up the steps it missed (that would be a spike where the budget was saved): its time is let go of
			instance.stepLimit = turn == PhysicsStats.Turn.ONE || holder.starved > 0 ? 1 : Integer.MAX_VALUE;
			holder.starved = 0;
			instance.update(context.model(), context.animated(), context.pose(), sample, controls != null ? controls : DEFAULTS);
		}

		instance.clothDeforms((mesh, deform) -> context.instance().setMeshDeform(mesh, deform));

		if (context.entityId() >= 0)
			LIVE.put(context.entityId(), instance);

		PhysicsStats.record(instance.lastSimulatedBones, instance.lastSteps, System.nanoTime() - started);
	}
}
