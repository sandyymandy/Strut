package com.sandymandy.strut.physics;

import com.sandymandy.strut.animation.state.MeshDeform;
import com.sandymandy.strut.animation.state.Pose;
import com.sandymandy.strut.animation.state.PosePalette;
import com.sandymandy.strut.cache.object.BakedStrutModel;
import com.sandymandy.strut.cache.object.GroupBoneDefinition;
import com.sandymandy.strut.cache.object.StrutMesh;
import com.sandymandy.strut.physics.world.Liquid;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BiConsumer;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import com.sandymandy.strut.physics.collision.AutoColliders;
import com.sandymandy.strut.physics.collision.Bodies;
import com.sandymandy.strut.physics.collision.Collider;
import com.sandymandy.strut.physics.collision.ColliderRig;
import com.sandymandy.strut.physics.config.PhysicsConfig;
import com.sandymandy.strut.physics.config.PhysicsOptions;
import com.sandymandy.strut.physics.solver.ChainInstance;
import com.sandymandy.strut.physics.solver.ChainParams;
import com.sandymandy.strut.physics.solver.ClothInstance;
import com.sandymandy.strut.physics.solver.ClothParams;
import com.sandymandy.strut.physics.solver.Kinematics;
import com.sandymandy.strut.physics.solver.RagdollInstance;
import com.sandymandy.strut.physics.solver.SpringParams;
import com.sandymandy.strut.physics.solver.SpringSolver;
import com.sandymandy.strut.physics.solver.StepFrame;
import com.sandymandy.strut.physics.tools.PhysicsControls;
import com.sandymandy.strut.physics.tools.PhysicsStats;
import com.sandymandy.strut.physics.world.WorldSource;

/**
 * The physics of one model instance: its spring bones' state, and the clock and entity motion that drive them. One per {@code ModelInstance}, kept in the
 * instance's processor state, so it lives and dies with the model it was made for
 * <p>
 * Takes an entity's position and yaw at a time (a {@link Sample}) and the pose the animation produced, advances the springs by fixed steps on that
 * clock, and adds the resulting rotation and translation to a copy of the pose. Free of game classes so a scripted walk can drive it in a test
 */
public final class PhysicsInstance {
	/** Where the entity is and which way it faces at a moment in time. {@code seconds} is game time: it stops when the game does */
	public record Sample(double x, double y, double z, float yawDegrees, double seconds) {}

	/** A time gap beyond this, or a jump beyond {@link #TELEPORT_DISTANCE}, restarts the springs instead of simulating across it */
	static final double MAX_GAP_SECONDS = 0.5;
	static final double TELEPORT_DISTANCE = 16.0;
	/** A frame whose time is earlier than the last by no more than this is clock jitter and is treated as no time passing */
	static final double BACKWARDS_JITTER_SECONDS = 0.25;

	/** How hard a held bone is pulled toward the cursor, in blocks per second squared per block of distance */
	private static final float PULL_GAIN = 400f;

	private static final class Bone {
		final String name;
		final int index;
		final GroupBoneDefinition definition;
		final SpringParams configured;
		final SpringSolver.State state = new SpringSolver.State();
		SpringParams active;
		/** Where the cursor holds this bone's mass, world space, or null */
		double[] pull;
		/** How flattened the bone is, 0 to 1, and the direction it is pressed along in the bone's own space (unit) */
			float squishAmount;
		/** The squish before the last step, for a frame between the last two steps */
		float prevSquish;
			/** The model's own generated shapes this bone must not collide with */
			boolean[] skip;
			final Vector3f squishNormal = new Vector3f(0, 1, 0);
		// Per frame, from the animated pose: where the bone's pivot is (model space, blocks) and its orientation
		final Vector3f origin = new Vector3f();
		final Matrix3f orientation = new Matrix3f();
		final Matrix3f orientationInverse = new Matrix3f();

		Bone(String name, GroupBoneDefinition definition, SpringParams configured) {
			this.name = name;
			this.index = definition.getIndex();
			this.definition = definition;
			this.configured = configured;
			this.active = configured;
		}
	}

	private final Bone[] bones;
	private final ChainInstance[] chains;
	private final ClothInstance[] cloths;
	private final RagdollInstance ragdoll;
	private final ColliderRig rig;
	private WorldSource world;
	/** The liquid at the entity's place this frame, or null */
	private Liquid liquid;
	/** The Bodies id of the entity this instance simulates, or -1 (tests, previews): without one it neither publishes nor feels other bodies */
	private int entityId = -1;
	private final List<Collider.Placed> others = new ArrayList<>();
	private final List<Collider.Placed> ownShapes = new ArrayList<>();
	private final List<Collider.Placed> published = new ArrayList<>();
	private final Vector3f contactA = new Vector3f();
	private final double[] contact = new double[3];
	private final List<Collider.Placed> worldBoxes = new ArrayList<>();
	private int worldBoxCount;
	private double cacheX = Double.NaN;
	private double cacheY;
	private double cacheZ;
	private int framesSinceWorld;
	private final Kinematics kinematics = new Kinematics();
	private Sample last;
	private Sample debugSample;
	/** The entity's samples from the recent frames, oldest first, that the fixed steps read its path from */
	private final List<Sample> history = new ArrayList<>();
	/** The time the physics has been simulated up to, on the game clock */
	private double simTime;
	/** The entity's place at the last step */
	private double stepX;
	private double stepY;
	private double stepZ;
	private float stepYaw;
	private boolean stepKnown;
	/** The entity's place at the step before the last, and where the frame being drawn falls between the two (see {@link StepFrame}) */
	private double prevStepX;
	private double prevStepY;
	private double prevStepZ;
	private float prevStepYaw;
	private float renderAlpha = 1f;
	private final StepFrame frame = new StepFrame();
	private final Matrix4f scratchMatrix = new Matrix4f();
	private final Vector3f lever = new Vector3f();
	private final Vector3f force = new Vector3f();
	private final Vector3f wind = new Vector3f();
	private final Vector3f scratch = new Vector3f();
	private final List<String> missing = new ArrayList<>();
	private final List<String> problems = new ArrayList<>();

	/** The most steps this frame may take, set by the frame budget (see {@link PhysicsStats#overBudget()}) */
	int stepLimit = Integer.MAX_VALUE;

	// The last frame's numbers, for the F9 line and the debug command
	public int lastSteps;
	int lastSimulatedBones;

	private final PhysicsConfig config;

	public PhysicsInstance(BakedStrutModel model, PhysicsConfig config) {
		this.config = config;
		List<Bone> found = new ArrayList<>();

		for (var entry : config.bones().entrySet()) {
			Optional<GroupBoneDefinition> definition = model.getBone(entry.getKey());

			if (definition.isPresent())
				found.add(new Bone(entry.getKey(), definition.get(), entry.getValue()));
			else
				this.missing.add(entry.getKey());
		}

		this.bones = found.toArray(new Bone[0]);

		List<ChainInstance> chainList = new ArrayList<>();

		for (var entry : config.chains().entrySet()) {
			ChainInstance chain = ChainInstance.create(entry.getKey(), entry.getValue(), model, this.missing);

			if (chain != null)
				chainList.add(chain);
		}

		this.chains = chainList.toArray(new ChainInstance[0]);

		List<ClothInstance> clothList = new ArrayList<>();

		for (var entry : config.cloth().entrySet())
			clothList.addAll(ClothInstance.create(entry.getKey(), entry.getValue(), model, this.problems));

		this.cloths = clothList.toArray(new ClothInstance[0]);
		this.ragdoll = config.ragdoll() != null ? RagdollInstance.create(model, config.ragdoll(), this.missing) : null;

		boolean wantsBody = false;

		for (ChainParams chain : config.chains().values())
			wantsBody |= chain.collidesWith().contains("body");

		for (SpringParams spring : config.bones().values())
			wantsBody |= spring.collidesWith().contains("body");

		for (ClothParams cloth : config.cloth().values())
			wantsBody |= cloth.collidesWith().contains("body");

		AutoColliders.Options auto = config.auto();
		// shapes are made from the model whenever a part collides with its body, except on the bones that have shapes of their own authored
		boolean generate = auto != null && auto.enabled() != null ? auto.enabled() : wantsBody;
		Set<String> authoredBones = new HashSet<>();

		config.colliders().forEach(c -> authoredBones.add(c.bone()));

		List<Collider> generated = generate ? AutoColliders.generate(model, auto != null ? auto : AutoColliders.Options.DEFAULT, authoredBones) : List.of();

		this.rig = this.chains.length > 0 || this.cloths.length > 0 || wantsBody || !config.colliders().isEmpty() || !generated.isEmpty() ? new ColliderRig(model, config.colliders(), generated, this.missing) : null;

		if (this.rig != null) {
			for (ChainInstance chain : this.chains)
				chain.skip = this.rig.mask(config.filter(chain.name), chain.bones);

			for (ClothInstance cloth : this.cloths)
				cloth.skip = this.rig.mask(config.filter(cloth.name.contains("#") ? cloth.name.substring(0, cloth.name.indexOf('#')) : cloth.name), cloth.bone);

			for (Bone bone : this.bones)
				bone.skip = this.rig.mask(config.filter(bone.name), bone.definition);
		}
	}

	/** What cloth could not be made and why, for the log */
	public List<String> problems() {
		return this.problems;
	}

	/** Hands each cloth's offsets to {@code sink} (the mesh and its per-corner offsets) for those that have a result this frame */
	public void clothOutputs(BiConsumer<StrutMesh, float[]> sink) {
		for (ClothInstance cloth : this.cloths)
			cloth.outputs((mesh, offsets, normals) -> sink.accept(mesh, offsets));
	}

	/** Hands each cloth's mesh, and each mesh that rides on one, with how it is moved this frame, to {@code sink}: what the renderer reads (a value for each particle, not each corner) */
	public void clothDeforms(BiConsumer<StrutMesh, MeshDeform> sink) {
		for (ClothInstance cloth : this.cloths)
			cloth.deforms(sink);
	}

	/** {@link #clothOutputs(java.util.function.BiConsumer)} with the normals too */
	public void clothOutputs(ClothInstance.Sink sink) {
		for (ClothInstance cloth : this.cloths)
			cloth.outputs(sink);
	}

	/** The cloth called {@code name}, for tests */
	public ClothInstance cloth(String name) {
		for (ClothInstance cloth : this.cloths) {
			if (cloth.name.equals(name))
				return cloth;
		}

		throw new IllegalArgumentException("no cloth " + name);
	}

	/** How many cloths this instance simulates */
	public int clothCount() {
		return this.cloths.length;
	}

	/** Says which entity this is, so it publishes its colliders under that id and does not collide with itself */
	public void entityId(int id) {
		this.entityId = id;
	}

	/** The config this instance was built from; the pose processor rebuilds the instance when the model's config changes */
	PhysicsConfig config() {
		return this.config;
	}

	/** A part the cursor tool can pick up: a chain particle (index 1 and up) or a spring bone's mass (index 0), where it is in the world */
	public record Pickable(String part, int index, double x, double y, double z) {}

	/** Adds every pickable part as of the last frame */
	public void pickables(List<Pickable> out) {
		if (this.debugSample == null)
			return;

		for (ChainInstance chain : this.chains) {
			if (!chain.state.initialised)
				continue;

			for (int k = 1; k < chain.particles(); k++)
				out.add(new Pickable(chain.name, k, chain.state.x[k][0], chain.state.x[k][1], chain.state.x[k][2]));
		}

		if (this.ragdoll != null) {
			List<double[]> positions = new ArrayList<>();

			this.ragdoll.particlePositions(positions);

			for (int k = 0; k < positions.size(); k++)
				out.add(new Pickable("ragdoll", k + 1, positions.get(k)[0], positions.get(k)[1], positions.get(k)[2]));
		}

		for (ClothInstance cloth : this.cloths) {
			if (!cloth.state.initialised)
				continue;

			for (int k : cloth.pickable(24))
				out.add(new Pickable(cloth.name, k + 1, cloth.state.x[k][0], cloth.state.x[k][1], cloth.state.x[k][2]));
		}

		Vector3f tip = new Vector3f();

		for (Bone bone : this.bones) {
			tip.set(bone.active.tip()).mul(bone.active.length());
			bone.orientation.transform(tip);
			tip.add(bone.origin);
			Kinematics.modelToWorld(tip, this.debugSample.yawDegrees());
			out.add(new Pickable(bone.name, 0, this.debugSample.x() + tip.x, this.debugSample.y() + tip.y, this.debugSample.z() + tip.z));
		}
	}

	/** Where blocks come from; without one a chain collides only with the model's own shapes */
	public void world(WorldSource source) {
		this.world = source;
	}

	/** Names in the config that the model has no bone for, so a typo shows up in the log rather than as silence */
	public List<String> missingBones() {
		return this.missing;
	}

	public int boneCount() {
		return this.bones.length;
	}

	/** Forgets all motion: the springs rest and the next sample only says where the entity is */
	public void reset() {
		for (Bone bone : this.bones) {
			bone.state.reset();
			bone.squishAmount = 0f;
			bone.prevSquish = 0f;
		}

		for (ChainInstance chain : this.chains)
			chain.reset();

		for (ClothInstance cloth : this.cloths)
			cloth.reset();

		if (this.ragdoll != null)
			this.ragdoll.reset();

		this.kinematics.reset();
		this.last = null;
		this.history.clear();
		this.stepKnown = false;
		this.renderAlpha = 1f;
	}

	/**
	 * Advances the springs to {@code now} and writes their offsets into {@code pose}
	 *
	 * @param animated The pose the animation produced, which is where the springs rest (read only)
	 * @param pose A copy of it that receives the offsets
	 * @param now The entity's state at this frame, or null to leave the springs where they are (the game is paused)
	 * @param controls The entity's runtime controls
	 */
	public void update(BakedStrutModel model, Pose animated, Pose pose, Sample now, PhysicsControls controls) {
		this.lastSteps = 0;
		this.lastSimulatedBones = 0;

		if (now != null) {
			advanceClock(now, animated, model, controls);
			this.debugSample = now;
		}
		else if (this.last != null) {
			// paused: the chains keep where they are, but the animation may still be changing under them
			prepareChains(animated, controls);
			computeChainOutput(frameFor(this.last));
			prepareCloth(animated, controls);
			computeClothOutput(frameFor(this.last));
		}

		apply(pose, controls);
	}

	private void advanceClock(Sample now, Pose animated, BakedStrutModel model, PhysicsControls controls) {
		// The game clock is the world time plus the frame's partial tick, and the two are not read at the same instant: now and then a frame reads a time a
		// hair before the last one. That is jitter, not a rewind, so the frame takes no step instead of restarting every spring
		if (this.last != null && now.seconds() < this.last.seconds() && this.last.seconds() - now.seconds() <= BACKWARDS_JITTER_SECONDS)
			now = new Sample(now.x(), now.y(), now.z(), now.yawDegrees(), this.last.seconds());

		if (this.last == null || now.seconds() < this.last.seconds() || now.seconds() - this.last.seconds() > MAX_GAP_SECONDS
				|| distance(now, this.last) > TELEPORT_DISTANCE) {
			reset();
			this.kinematics.advance(now.x(), now.y(), now.z(), now.yawDegrees(), SpringSolver.STEP);
			this.history.add(now);
			this.simTime = now.seconds();
			this.last = now;

			return;
		}

		this.history.add(now);

		// The physics runs on its own clock of fixed steps, and the entity's place at each step is read off its path between the frames' samples. Taking it
		// from the frames' own spacing instead (one step per frame that happens to be long enough) made the speed the springs saw depend on how the frames
		// fell against the steps, which is what made hair and jiggle jolt while the body moved smoothly
		double h = SpringSolver.STEP / PhysicsOptions.timeScale();
		int steps = Math.min((int)((now.seconds() - this.simTime) / h + 1e-9), Math.min(PhysicsOptions.maxSteps(), this.stepLimit));

		// The chains are read from the animation every frame, even one that takes no step, so their pose keeps following an animation that changes faster
		// than the physics steps
		prepareChains(animated, controls);
		prepareCloth(animated, controls);
		applyGrab(controls);

		if (this.ragdoll != null) {
			this.ragdoll.control(controls, now.seconds(), this.kinematics.velocity());
			this.ragdoll.prepare(animated);
		}

		refreshWorld(now);
		refreshLiquid(now);
		gatherOthers(now);

		if (steps > 0) {
			prepareBones(animated, model, controls);
			applyImpulses(controls);

			double previousX = this.stepKnown ? this.stepX : now.x();
			double previousY = this.stepKnown ? this.stepY : now.y();
			double previousZ = this.stepKnown ? this.stepZ : now.z();

			for (int s = 1; s <= steps; s++) {
				this.simTime += h;

				double[] at = pathAt(this.simTime);
				double x = at[0];
				double y = at[1];
				double z = at[2];
				float yaw = (float)at[3];

				this.kinematics.advance(x, y, z, yaw, (float)h);
				stepBones(x, y, z, yaw, controls);

				if (this.ragdoll != null)
					this.ragdoll.step(x, y, z, yaw, (float)h, this.worldBoxCount > 0 ? this.worldBoxes.subList(0, this.worldBoxCount) : null);

				for (ChainInstance chain : this.chains) {
					chain.step(x, y, z, yaw, x - previousX, y - previousY, z - previousZ, this.rig, this.worldBoxCount > 0 ? this.worldBoxes.subList(0, this.worldBoxCount) : null, this.others);
					this.lastSimulatedBones += chain.particles();
				}

				if (PhysicsOptions.cloth()) {
					for (ClothInstance cloth : this.cloths) {
						cloth.step(x, y, z, yaw, x - previousX, y - previousY, z - previousZ, this.rig, this.worldBoxCount > 0 ? this.worldBoxes.subList(0, this.worldBoxCount) : null, this.others);
						this.lastSimulatedBones += cloth.particles();
					}
				}

				previousX = x;
				previousY = y;
				previousZ = z;
				this.prevStepX = this.stepKnown ? this.stepX : x;
				this.prevStepY = this.stepKnown ? this.stepY : y;
				this.prevStepZ = this.stepKnown ? this.stepZ : z;
				this.prevStepYaw = this.stepKnown ? this.stepYaw : yaw;
				this.stepX = x;
				this.stepY = y;
				this.stepZ = z;
				this.stepYaw = yaw;
				this.stepKnown = true;
			}

			this.lastSteps = steps;
		}

		// behind by more than a burst allows: drop the time rather than simulate a hitch
		if (now.seconds() - this.simTime > Math.min(PhysicsOptions.maxSteps(), this.stepLimit) * h)
			this.simTime = now.seconds() - h;

		// keep the last sample at or before the simulation's time, and everything after it
		while (this.history.size() > 2 && this.history.get(1).seconds() <= this.simTime)
			this.history.remove(0);

		this.last = now;

		// the frame falls between the last step and the next: drawn at the fraction of the way the clock is from one to the other, the pose is the one
		// between the states before and after the last step, a step behind but smooth at any frame rate
		this.renderAlpha = (float)Math.max(0.0, Math.min(1.0, (now.seconds() - this.simTime) / h));

		computeChainOutput(frameFor(now));
		computeClothOutput(frameFor(now));

		if (this.ragdoll != null)
			this.ragdoll.computeOutput(frameFor(now));

		publishBody(now);
	}

	/** Whether this instance has run, so that drawing its last result again (see {@link #hold}) is a pose rather than nothing */
	public boolean canHold() {
		return this.last != null;
	}

	/**
	 * Draws the last result again without advancing anything: the frame's budget was spent (see {@link PhysicsStats#admit}), so this instance waits its turn.
	 * The clock is not touched, and its time is let go of when it next steps
	 */
	public void hold(Pose pose, PhysicsControls controls) {
		this.lastSteps = 0;
		this.lastSimulatedBones = 0;

		apply(pose, controls);
	}

	/** Where the entity was at the last step (x, y, z, yaw in degrees): what the particles are measured from, for tests and tools; null before the first step */
	public double[] stepPlace() {
		return this.stepKnown ? new double[] {this.stepX, this.stepY, this.stepZ, this.stepYaw} : null;
	}

	/** Where the entity was at {@code seconds}, interpolated along the frames' samples: x, y, z and yaw in degrees */
	private double[] pathAt(double seconds) {
		Sample from = this.history.get(0);
		Sample to = from;

		for (int i = 1; i < this.history.size(); i++) {
			from = this.history.get(i - 1);
			to = this.history.get(i);

			if (to.seconds() >= seconds)
				break;
		}

		double span = to.seconds() - from.seconds();
		double f = span <= 1e-9 ? 1.0 : Math.max(0.0, Math.min(1.0, (seconds - from.seconds()) / span));

		return new double[] {from.x() + (to.x() - from.x()) * f, from.y() + (to.y() - from.y()) * f, from.z() + (to.z() - from.z()) * f,
				from.yawDegrees() + Kinematics.wrapDegrees(to.yawDegrees() - from.yawDegrees()) * f};
	}

	/**
	 * Where the chains' particles are measured from: the entity's place at the last step, because that is where the particles were solved. Measuring them from
	 * where the entity is drawn now would leave them standing still while the body moved on between steps, and catching up when a step came: a jolt
	 */
	private Sample anchor(Sample fallback) {
		return this.stepKnown ? new Sample(this.stepX, this.stepY, this.stepZ, this.stepYaw, fallback.seconds()) : fallback;
	}

	/** {@link #anchor} for the last two steps, and where the frame falls between them */
	private StepFrame frameFor(Sample fallback) {
		Sample at = anchor(fallback);

		if (this.stepKnown)
			this.frame.set(at.x(), at.y(), at.z(), at.yawDegrees(), this.prevStepX, this.prevStepY, this.prevStepZ, this.prevStepYaw, this.renderAlpha);
		else
			this.frame.set(at.x(), at.y(), at.z(), at.yawDegrees(), at.x(), at.y(), at.z(), at.yawDegrees(), 1f);

		return this.frame;
	}

	/** Reads the shapes of the entities around into {@link #others}: once a frame, they are only stepped through */
	private void gatherOthers(Sample at) {
		this.others.clear();

		if (this.entityId < 0 || (!PhysicsOptions.otherCollision() && !PhysicsOptions.playerCollision()))
			return;

		boolean wants = false;
		double reach = 1.5;

		for (ChainInstance chain : this.chains) {
			if (chain.active.weight() > 0f && feelsOthers(chain.active.collidesWith())) {
				wants = true;
				reach = Math.max(reach, chain.reach());
			}
		}

		for (ClothInstance cloth : this.cloths) {
			if (cloth.active.weight() > 0f && feelsOthers(cloth.active.collidesWith())) {
				wants = true;
				reach = Math.max(reach, cloth.reach());
			}
		}

		for (Bone bone : this.bones) {
			if (bone.active.weight() > 0f && bone.active.rotate() && feelsOthers(bone.active.collidesWith()))
				wants = true;
		}

		if (wants)
			Bodies.nearby(this.entityId, at.x(), at.y() + 1.0, at.z(), reach + 3.5, PhysicsOptions.otherCollision(), PhysicsOptions.playerCollision(), this.others);
	}

	static boolean feelsOthers(Set<String> layers) {
		return layers.contains(Bodies.OTHER) || layers.contains(Bodies.PLAYERS) || layers.contains(Bodies.MOBS);
	}

	/** Puts this model's body colliders where they are now, for the other entities to press on */
	private void publishBody(Sample at) {
		if (this.entityId < 0 || this.rig == null || this.rig.count() == 0)
			return;

		this.published.clear();
		this.rig.place(at.x(), at.y(), at.z(), at.yawDegrees(), null, this.published);
		Bodies.publishModel(this.entityId, this.published);
	}

	/** Holds the part the cursor has grabbed, and lets go of everything else */
	private void applyGrab(PhysicsControls controls) {
		PhysicsControls.Grab grab = controls.grab();

		for (ChainInstance chain : this.chains) {
			chain.unpinAll();

			if (grab != null && grab.part().equals(chain.name))
				chain.pin(grab.index(), new double[] {grab.x(), grab.y(), grab.z()});
		}

		for (ClothInstance cloth : this.cloths) {
			cloth.releaseAll();

			// a cloth's particles are picked by index + 1, so that index 0 stays a spring bone's mass
			if (grab != null && grab.part().equals(cloth.name))
				cloth.hold(grab.index() - 1, new double[] {grab.x(), grab.y(), grab.z()});
		}

		if (this.ragdoll != null) {
			this.ragdoll.unpinAll();

			if (grab != null && grab.part().equals("ragdoll"))
				this.ragdoll.pin(grab.index() - 1, new double[] {grab.x(), grab.y(), grab.z()});
		}

		for (Bone bone : this.bones)
			bone.pull = grab != null && grab.index() == 0 && grab.part().equals(bone.name) ? new double[] {grab.x(), grab.y(), grab.z()} : null;
	}

	/**
	 * Reads the solid boxes around the chains into {@link #worldBoxes}, when any chain collides with the world. Kept while the entity has moved less than a
	 * quarter block, and refreshed every eight frames regardless, so a block placed or broken next to a still entity is noticed
	 */
	private void refreshWorld(Sample at) {
		this.worldBoxes.clear();
		this.worldBoxCount = 0;

		boolean ragdolling = this.ragdoll != null && this.ragdoll.active();

		if (this.world == null || !PhysicsOptions.worldCollision() || (this.chains.length == 0 && this.cloths.length == 0 && this.bones.length == 0 && !ragdolling))
			return;

		// what hangs from the entity: read around the entity, kept while it has moved less than a quarter block, and refreshed every eight frames regardless so a
		// block placed next to a still entity is noticed
		double reach = 0;
		boolean entity = false;

		for (ChainInstance chain : this.chains) {
			if (chain.usesWorld()) {
				entity = true;
				reach = Math.max(reach, chain.reach());
			}
		}

		if (PhysicsOptions.cloth()) {
			for (ClothInstance cloth : this.cloths) {
				if (cloth.usesWorld()) {
					entity = true;
					reach = Math.max(reach, cloth.reach());
				}
			}
		}

		for (Bone bone : this.bones) {
			if (feelsWorld(bone.active)) {
				entity = true;
				reach = Math.max(reach, bone.active.length() + bone.active.radius());
			}
		}

		if (entity) {
			// the chains hang from somewhere within about two and a half blocks of the entity's feet
			double margin = reach + 2.5 + 0.5;
			boolean moved = Double.isNaN(this.cacheX) || Math.abs(at.x() - this.cacheX) > 0.25 || Math.abs(at.y() - this.cacheY) > 0.25 || Math.abs(at.z() - this.cacheZ) > 0.25;

			if (moved || ++this.framesSinceWorld >= 15) {
				this.cacheX = at.x();
				this.cacheY = at.y();
				this.cacheZ = at.z();
				this.framesSinceWorld = 0;
				this.entityCount = readBoxes(at.x() - margin, at.y() - margin, at.z() - margin, at.x() + margin, at.y() + margin, at.z() + margin, this.entityPool,
						at.x(), at.y() + 1.0, at.z());
			}

			for (int i = 0; i < this.entityCount; i++)
				this.worldBoxes.add(this.entityPool.get(i));
		}
		else {
			this.cacheX = Double.NaN;
			this.entityCount = 0;
		}

		// a ragdoll that has been let go is wherever it was thrown, not near the entity: its blocks are read around its own joints, in a volume grown by how far it
		// can travel in a third of a second, and kept until its joints leave that volume. Reading them every frame (and joined to the entity's volume, which
		// spans the whole distance between the two) was what made a thrown ragdoll lag the game
		double[] body = ragdolling ? this.ragdoll.bounds(0.0) : null;

		if (body != null) {
			boolean inside = this.bodyVolume != null;

			for (int a = 0; a < 3 && inside; a++)
				inside = body[a] >= this.bodyVolume[a] && body[a + 3] <= this.bodyVolume[a + 3];

			if (!inside || ++this.framesSinceBody >= 8) {
				double[] volume = this.ragdoll.bounds(0.3);

				// and a margin more, so a body that has come to rest and shifts a little is not read again for it
				for (int a = 0; a < 3; a++) {
					volume[a] -= 1.5;
					volume[a + 3] += 1.5;
				}

				this.bodyVolume = volume;
				this.framesSinceBody = 0;
				this.bodyCount = readBoxes(volume[0], volume[1], volume[2], volume[3], volume[4], volume[5], this.bodyPool,
						(body[0] + body[3]) * 0.5, (body[1] + body[4]) * 0.5, (body[2] + body[5]) * 0.5);
			}

			for (int i = 0; i < this.bodyCount; i++)
				this.worldBoxes.add(this.bodyPool.get(i));
		}
		else {
			this.bodyVolume = null;
			this.bodyCount = 0;
		}

		this.worldBoxCount = this.worldBoxes.size();
	}

	private final List<Collider.Placed> entityPool = new ArrayList<>();
	private final List<Collider.Placed> bodyPool = new ArrayList<>();
	private int entityCount;
	private int bodyCount;
	private double[] bodyVolume;
	private int framesSinceBody;

	/**
	 * Reads every solid box in the volume, keeps the {@link PhysicsOptions#maxWorldBoxes()} nearest to {@code (cx, cy, cz)} in {@code pool} (a dense place has more
	 * boxes than the cap, and dropping them in the order the world lists them left holes next to the body, which a fast chain fell through) and returns how many
	 */
	private int readBoxes(double x0, double y0, double z0, double x1, double y1, double z1, List<Collider.Placed> pool, double cx, double cy, double cz) {
		List<double[]> found = new ArrayList<>();

		this.world.boxes(x0, y0, z0, x1, y1, z1, (bx0, by0, bz0, bx1, by1, bz1) -> {
			double dx = Math.max(0, Math.max(bx0 - cx, cx - bx1));
			double dy = Math.max(0, Math.max(by0 - cy, cy - by1));
			double dz = Math.max(0, Math.max(bz0 - cz, cz - bz1));

			found.add(new double[] {bx0, by0, bz0, bx1, by1, bz1, dx * dx + dy * dy + dz * dz});
		});

		int cap = PhysicsOptions.maxWorldBoxes();

		if (found.size() > cap)
			found.sort(Comparator.comparingDouble(b -> b[6]));

		int count = Math.min(found.size(), cap);

		for (int f = 0; f < count; f++) {
			double[] b = found.get(f);

			while (pool.size() <= f)
				pool.add(new Collider.Placed());

			Collider.Placed box = pool.get(f);

			box.shape = Collider.Shape.BOX;
			box.layer = "world";
			box.p[0] = (b[0] + b[3]) * 0.5;
			box.p[1] = (b[1] + b[4]) * 0.5;
			box.p[2] = (b[2] + b[5]) * 0.5;
			box.half[0] = (b[3] - b[0]) * 0.5;
			box.half[1] = (b[4] - b[1]) * 0.5;
			box.half[2] = (b[5] - b[2]) * 0.5;
			box.axes.identity();
		}

		return count;
	}

	private void prepareChains(Pose animated, PhysicsControls controls) {
		if (this.rig != null)
			this.rig.prepare(animated);

		if (this.chains.length == 0)
			return;

		for (ChainInstance chain : this.chains)
			chain.prepare(animated, controls);
	}

	/** Reads the animated mesh of each cloth every frame, even one that takes no step, so a cloth sewn to the animation keeps following it */
	private void prepareCloth(Pose animated, PhysicsControls controls) {
		if (!PhysicsOptions.cloth()) {
			for (ClothInstance cloth : this.cloths)
				cloth.reset();

			return;
		}

		for (ClothInstance cloth : this.cloths)
			cloth.prepare(animated, controls);
	}

	private void computeClothOutput(StepFrame at) {
		for (ClothInstance cloth : this.cloths)
			cloth.computeOutput(at);
	}

	private void computeChainOutput(StepFrame at) {
		for (ChainInstance chain : this.chains)
			chain.computeOutput(at);
	}

	/** Reads each bone's place and orientation from the animated pose, and its parameters with the runtime controls applied */
	private void prepareBones(Pose animated, BakedStrutModel model, PhysicsControls controls) {
		for (Bone bone : this.bones) {
			PhysicsControls.BoneControl control = controls.boneOrNull(bone.name);

			bone.active = bone.configured.scaled(controls.firmness() * (control != null ? control.frequencyScale : 1f),
					controls.weight() * (control != null ? control.weight : 1f), controls.inertia());

			PosePalette.computeBoneAtPivot(animated, bone.definition, this.scratchMatrix);
			this.scratchMatrix.transformPosition(0f, 0f, 0f, bone.origin);
			bone.orientation.set(this.scratchMatrix);
			stripScale(bone.orientation, this.scratch);
			bone.orientationInverse.set(bone.orientation).transpose();
		}
	}

	private void applyImpulses(PhysicsControls controls) {
		for (PhysicsControls.Impulse impulse : controls.drainImpulses()) {
			for (ChainInstance chain : this.chains) {
				if (chain.name.equals(impulse.bone()))
					chain.impulse(impulse.x(), impulse.y(), impulse.z());
			}

			for (ClothInstance cloth : this.cloths) {
				if (cloth.name.equals(impulse.bone()))
					cloth.impulse(impulse.x(), impulse.y(), impulse.z());
			}

			for (Bone bone : this.bones) {
				if (!bone.name.equals(impulse.bone()))
					continue;

				// A velocity v given to the mass on the lever is an angular velocity of (tip x v) / length; the same v moves the bone directly if it translates
				this.scratch.set(bone.active.tip()).cross(impulse.x(), impulse.y(), impulse.z()).div(Math.max(1e-4f, bone.active.length()));

				for (int i = 0; i < 3; i++) {
					bone.state.rotVel[i] += this.scratch.get(i);
					bone.state.posVel[i] += (i == 0 ? impulse.x() : i == 1 ? impulse.y() : impulse.z()) * 16f;
				}
			}
		}
	}

	/**
	 * Reads the liquid at the entity's place for the chains, the cloth, the ragdoll and the spring bones: once a frame, the column of it at the entity
	 */
	private void refreshLiquid(Sample at) {
		this.liquid = this.world == null || !PhysicsOptions.water() ? null : this.world.liquid(at.x(), at.y(), at.z());

		for (ChainInstance chain : this.chains)
			chain.state.liquid = this.liquid;

		for (ClothInstance cloth : this.cloths)
			cloth.state.liquid = this.liquid;

		if (this.ragdoll != null)
			this.ragdoll.solverState().liquid = this.liquid;
	}

	private void stepBones(double ex, double ey, double ez, float yawDegrees, PhysicsControls controls) {
		this.wind.set(controls.wind());

		for (Bone bone : this.bones) {
			SpringParams params = bone.active;

			if (params.weight() <= 0f || (!params.rotate() && !params.translate())) {
				bone.state.reset();
				bone.squishAmount = 0f;
				bone.prevSquish = 0f;

				continue;
			}

			// The frame's inertial push at this bone, in world space, then into the model's space and the bone's own
			this.lever.set(bone.origin);
			Kinematics.modelToWorld(this.lever, yawDegrees);
			this.kinematics.inertialForce(this.lever, params.inertia(), this.force);
			this.force.add(this.wind);

			double wet = this.liquid == null ? 0 : this.liquid.submersion(ey + this.lever.y);

			if (wet > 0) {
				// in water or lava the mass is lighter, and the current pushes it
				this.force.add((float) this.liquid.pushX(wet), 0f, (float) this.liquid.pushZ(wet));
				this.force.y -= (float) this.liquid.gravity(params.gravity(), wet);
			}
			else {
				this.force.y -= params.gravity();
			}

			if (bone.pull != null) {
				// held by the cursor: pulled toward the point, in world space, like a spring to the hand
				this.scratch.set(params.tip()).mul(params.length());
				bone.orientation.transform(this.scratch);
				this.scratch.add(bone.origin);
				Kinematics.modelToWorld(this.scratch, yawDegrees);
				this.force.add((float)(bone.pull[0] - (ex + this.scratch.x)) * PULL_GAIN, (float)(bone.pull[1] - (ey + this.scratch.y)) * PULL_GAIN,
						(float)(bone.pull[2] - (ez + this.scratch.z)) * PULL_GAIN);
			}

			Kinematics.worldToModel(this.force, yawDegrees);
			bone.orientationInverse.transform(this.force);

			bone.state.mark();
			bone.prevSquish = bone.squishAmount;
			SpringSolver.step(bone.state, params, SpringSolver.STEP, this.force, this.scratch);

			if (wet > 0) {
				// the liquid slows it
				float drag = (float) this.liquid.keep(wet);

				for (int i = 0; i < 3; i++) {
					bone.state.rotVel[i] *= drag;
					bone.state.posVel[i] *= drag;
				}
			}

			this.lastSimulatedBones++;
			pressBone(bone, params, ex, ey, ez, yawDegrees);
		}
	}

	/**
	 * Pushes a spring bone's mass out of the bodies around it, and flattens the bone under the push. The mass is the point {@code tip * length} from the pivot,
	 * moved by the rotation the spring has added; a push turns the bone (the inverse of the lever) and takes the velocity that was heading into it
	 */
	private void pressBone(Bone bone, SpringParams params, double ex, double ey, double ez, float yaw) {
		float target = 0f;

		boolean world = this.worldBoxCount > 0 && feelsWorld(params);
		boolean bodies = !this.others.isEmpty() && feelsOthers(params.collidesWith());
		boolean own = this.rig != null && params.collidesWith().contains("body");

		if (own) {
			this.ownShapes.clear();
			this.rig.place(ex, ey, ez, yaw, params.collidesWith(), bone.skip, this.ownShapes);

			// where the mass rests: a generated shape it already overlaps there is part of the model's pose, not something to collide with
			Vector3f restPoint = bone.orientation.transform(new Vector3f(params.tip()).mul(params.length())).add(bone.origin);

			Kinematics.modelToWorld(restPoint, yaw);

			double[][] probe = {{ex + restPoint.x, ey + restPoint.y, ez + restPoint.z}};

			Collider.dropRestingContacts(this.ownShapes, probe, 0, 1, params.radius());
			own = !this.ownShapes.isEmpty();
		}

		if (params.rotate() && (world || bodies || own)) {
			Vector3f rest = this.contactA.set(params.tip()).mul(params.length());
			Vector3f moved = new Vector3f(bone.state.rot[0], bone.state.rot[1], bone.state.rot[2]).cross(rest).add(rest);
			// the mass in world space
			Vector3f at = bone.orientation.transform(new Vector3f(moved)).add(bone.origin);

			Kinematics.modelToWorld(at, yaw);

			this.contact[0] = ex + at.x;
			this.contact[1] = ey + at.y;
			this.contact[2] = ez + at.z;

			double x0 = this.contact[0];
			double y0 = this.contact[1];
			double z0 = this.contact[2];
			boolean pressed = pushOutOfAll(params, world, bodies, own);

			if (pressed) {
				Vector3f push = new Vector3f((float)(this.contact[0] - x0), (float)(this.contact[1] - y0), (float)(this.contact[2] - z0));
				Kinematics.worldToModel(push, yaw);
				bone.orientationInverse.transform(push);

				// the mass would sit at moved + push; the rotation that puts it there keeps the twist about the lever and swings the rest
				Vector3f goal = new Vector3f(moved).add(push);
				Vector3f swing = new Vector3f(rest).cross(new Vector3f(goal).sub(rest)).div(Math.max(1e-6f, rest.lengthSquared()));
				Vector3f axis = new Vector3f(rest).normalize();
				float twist = bone.state.rot[0] * axis.x + bone.state.rot[1] * axis.y + bone.state.rot[2] * axis.z;
				Vector3f newRot = new Vector3f(axis).mul(twist).add(swing);
				float[] before = bone.state.rot.clone();

				for (int i = 0; i < 3; i++) {
					float v = newRot.get(i);
					float[] limit = params.rotationLimit() == null ? null : params.rotationLimit()[i];

					if (limit != null)
						v = Math.max(limit[0], Math.min(limit[1], v));

					bone.state.rot[i] = v;
				}

				// the velocity that was heading into the push is gone: what is left slides along it
				float dx = bone.state.rot[0] - before[0];
				float dy = bone.state.rot[1] - before[1];
				float dz = bone.state.rot[2] - before[2];
				float dl = (float)Math.sqrt(dx * dx + dy * dy + dz * dz);

				if (dl > 1e-9f) {
					float into = (bone.state.rotVel[0] * dx + bone.state.rotVel[1] * dy + bone.state.rotVel[2] * dz) / dl;

					if (into < 0f) {
						bone.state.rotVel[0] -= into * dx / dl;
						bone.state.rotVel[1] -= into * dy / dl;
						bone.state.rotVel[2] -= into * dz / dl;
					}
				}

			}

			// How hard the body is pressed in: how far it reaches past where the mass rests, not how far the bone has already given way (the bone is pushed
			// aside and the spring pulls it back, so that residue is small however hard the push)
			if (params.squish() > 0f) {
				Vector3f atRest = bone.orientation.transform(new Vector3f(rest)).add(bone.origin);

				Kinematics.modelToWorld(atRest, yaw);

				this.contact[0] = ex + atRest.x;
				this.contact[1] = ey + atRest.y;
				this.contact[2] = ez + atRest.z;

				double rx = this.contact[0];
				double ry = this.contact[1];
				double rz = this.contact[2];
				boolean inside = pushOutOfAll(params, world, bodies, own);

				if (inside) {
					Vector3f out = new Vector3f((float)(this.contact[0] - rx), (float)(this.contact[1] - ry), (float)(this.contact[2] - rz));
					float depth = out.length();

					if (depth > 1e-6f) {
						Kinematics.worldToModel(out, yaw);
						bone.orientationInverse.transform(out);
						bone.squishNormal.set(out.normalize());
						target = Math.min(1f, depth / Math.max(1e-3f, params.radius())) * params.squish();
					}
				}
			}
		}

		// flatten fast under a press, recover slower: the target is 0 once nothing presses
		float rate = target > bone.squishAmount ? 40f : 10f;

		bone.squishAmount += (target - bone.squishAmount) * (1f - (float)Math.exp(-rate * SpringSolver.STEP));

		if (bone.squishAmount < 1e-4f)
			bone.squishAmount = 0f;
	}

	/** Whether a spring bone with these parameters collides with blocks: it lists {@code world}, and both the world switch and the spring one are on */
	private static boolean feelsWorld(SpringParams params) {
		return params.weight() > 0f && params.rotate() && params.collidesWith().contains("world") && PhysicsOptions.worldCollision() && PhysicsOptions.springWorldCollision();
	}

	/** Pushes {@link #contact} out of the blocks and bodies this bone collides with; true if it moved */
	private boolean pushOutOfAll(SpringParams params, boolean world, boolean bodies, boolean own) {
		boolean moved = false;

		if (own) {
			for (Collider.Placed shape : this.ownShapes)
				moved |= Collider.pushOut(shape, this.contact, params.radius());
		}

		if (world) {
			for (int i = 0; i < this.worldBoxCount; i++)
				moved |= Collider.pushOut(this.worldBoxes.get(i), this.contact, params.radius());
		}

		if (bodies) {
			for (Collider.Placed shape : this.others) {
				if (Bodies.feels(params.collidesWith(), shape))
					moved |= Collider.pushOut(shape, this.contact, params.radius());
			}
		}

		return moved;
	}

	private void apply(Pose pose, PhysicsControls controls) {
		for (ChainInstance chain : this.chains)
			chain.apply(pose);

		// applied after the springs and chains: a bone in the ragdoll belongs to it while it is let go
		if (this.ragdoll != null)
			this.ragdoll.apply(pose);

		for (Bone bone : this.bones) {
			SpringParams params = bone.active;
			float w = params.weight();

			if (w <= 0f)
				continue;

			int i = bone.index;
			// drawn between the states before and after the last step, see StepFrame
			float t = this.renderAlpha;
			SpringSolver.State st = bone.state;

			if (params.rotate()) {
				float rx = st.prevRot[0] + (st.rot[0] - st.prevRot[0]) * t;
				float ry = st.prevRot[1] + (st.rot[1] - st.prevRot[1]) * t;
				float rz = st.prevRot[2] + (st.rot[2] - st.prevRot[2]) * t;

				if (params.localRotation()) {
					// turned about the bone's own axes as it is posed (the frame the springs work in), not added to the angles it is posed with: for a bone that has a
					// rotation of its own the two are different axes. The posed rotation, then the spring's
					float length = (float) Math.sqrt(rx * rx + ry * ry + rz * rz);

					if (length * w > 1.0E-7f) {
						Quaternionf turned = new Quaternionf().rotationZYX(pose.getRotZ(i), pose.getRotY(i), pose.getRotX(i))
								.mul(new Quaternionf().fromAxisAngleRad(rx / length, ry / length, rz / length, length * w));
						Vector3f angles = turned.getEulerAnglesZYX(new Vector3f());

						pose.setRotation(i, angles.x, angles.y, angles.z);
					}
				}
				else {
					pose.setRotation(i, pose.getRotX(i) + rx * w, pose.getRotY(i) + ry * w, pose.getRotZ(i) + rz * w);
				}
			}

			float squish = bone.prevSquish + (bone.squishAmount - bone.prevSquish) * t;

			if (squish > 0f) {
				// shortened along the push, and widened across it to keep the volume (a scale about the bone's pivot)
				float a = squish * w;
				float sx = 1f - a * bone.squishNormal.x * bone.squishNormal.x;
				float sy = 1f - a * bone.squishNormal.y * bone.squishNormal.y;
				float sz = 1f - a * bone.squishNormal.z * bone.squishNormal.z;
				float k = (float)(1.0 / Math.cbrt(sx * sy * sz));

				pose.setScale(i, pose.getScaleX(i) * sx * k, pose.getScaleY(i) * sy * k, pose.getScaleZ(i) * sz * k);
			}

			if (params.translate()) {
				float s = params.translationScale() * w;
				float px = st.prevPos[0] + (st.pos[0] - st.prevPos[0]) * t;
				float py = st.prevPos[1] + (st.pos[1] - st.prevPos[1]) * t;
				float pz = st.prevPos[2] + (st.pos[2] - st.prevPos[2]) * t;

				// A bone's position is drawn in its parent's space, which is scaled by every bone above it (a pouch size on the bone the springs hang from): the offset
				// is meant in the model's own space, so it is divided by that scale or it would be drawn that many times as far
				float above = ancestorScale(pose, bone.definition);

				// The renderer draws a bone's X position negated, so a model-space offset of dx is a pose value of -dx
				pose.setPosition(i, pose.getPosX(i) - px * s / above, pose.getPosY(i) + py * s / above, pose.getPosZ(i) + pz * s / above);
			}
		}
	}

	/** The scale the bones above {@code bone} put on its space, as one number (the mean of the three axes), never below 0.05 */
	private static float ancestorScale(Pose pose, GroupBoneDefinition bone) {
		float scale = 1f;

		for (GroupBoneDefinition up = bone.getParent(); up != null; up = up.getParent()) {
			int j = up.getIndex();

			scale *= (Math.abs(pose.getScaleX(j)) + Math.abs(pose.getScaleY(j)) + Math.abs(pose.getScaleZ(j))) / 3f;
		}

		return Math.max(0.05f, scale);
	}

	/** The ragdoll, or null, for tests and the tool */
	public RagdollInstance ragdoll() {
		return this.ragdoll;
	}

	/** The chain called {@code name}, for tests */
	public ChainInstance chain(String name) {
		for (ChainInstance chain : this.chains) {
			if (chain.name.equals(name))
				return chain;
		}

		throw new IllegalArgumentException("no chain " + name);
	}

	/** One line per bone for the debug command: its offsets in degrees and model units */
	public List<String> describe() {
		List<String> lines = new ArrayList<>();

		for (ChainInstance chain : this.chains)
			lines.add(String.format("chain %s: %d bones, worst segment length error %.2f%%, weight %.2f", chain.name, chain.bones.length, chain.worstLengthError() * 100, chain.active.weight()));

		for (ClothInstance cloth : this.cloths)
			lines.add(String.format("cloth %s: %d particles, %d edges, worst edge length error %.2f%%, weight %.2f", cloth.name, cloth.particles(), cloth.topology.edgeA.length, cloth.worstEdgeError() * 100, cloth.active.weight()));

		for (Bone bone : this.bones) {
			lines.add(String.format("%s: rot (%.1f, %.1f, %.1f) deg, pos (%.2f, %.2f, %.2f) units, weight %.2f", bone.name,
					Math.toDegrees(bone.state.rot[0]), Math.toDegrees(bone.state.rot[1]), Math.toDegrees(bone.state.rot[2]),
					bone.state.pos[0], bone.state.pos[1], bone.state.pos[2], bone.active.weight()));
		}

		return lines;
	}

	/** Receives one debug segment in world coordinates */
	public interface LineSink {
		void line(double x1, double y1, double z1, double x2, double y2, double z2, int rgb);
	}

	/**
	 * Draws each spring bone as two lines from its pivot: green to where its mass rests, red to where the springs have moved it
	 */
	public void debugLines(LineSink sink) {
		if (this.debugSample == null)
			return;

		if (this.ragdoll != null && this.ragdoll.active()) {
			List<double[]> positions = new ArrayList<>();

			this.ragdoll.particlePositions(positions);

			for (int[] stick : this.ragdoll.boneSticks()) {
				if (stick[0] < positions.size() && stick[1] < positions.size()) {
					double[] a = positions.get(stick[0]);
					double[] b = positions.get(stick[1]);

					sink.line(a[0], a[1], a[2], b[0], b[1], b[2], 0xFFA030);
				}
			}
		}

		for (ChainInstance chain : this.chains) {
			for (int k = 0; k + 1 < chain.particles(); k++) {
				sink.line(chain.state.x[k][0], chain.state.x[k][1], chain.state.x[k][2], chain.state.x[k + 1][0], chain.state.x[k + 1][1], chain.state.x[k + 1][2], 0xF04040);
			}
		}

		for (ClothInstance cloth : this.cloths) {
			if (!cloth.state.initialised)
				continue;

			for (int e = 0; e < cloth.topology.edgeA.length; e++) {
				double[] a = cloth.state.x[cloth.topology.edgeA[e]];
				double[] b = cloth.state.x[cloth.topology.edgeB[e]];

				sink.line(a[0], a[1], a[2], b[0], b[1], b[2], 0x40A0F0);
			}
		}

		Vector3f pivot = new Vector3f();
		Vector3f rest = new Vector3f();
		Vector3f moved = new Vector3f();

		for (Bone bone : this.bones) {
			SpringParams params = bone.active;

			rest.set(params.tip()).mul(params.length());
			moved.set(rest);
			// A small rotation vector r moves a point p to p + r x p
			new Vector3f(bone.state.rot[0], bone.state.rot[1], bone.state.rot[2]).cross(rest, this.scratch);
			moved.add(this.scratch);
			bone.orientation.transform(rest);
			bone.orientation.transform(moved);
			rest.add(bone.origin);
			moved.add(bone.origin);
			pivot.set(bone.origin);

			float yaw = this.debugSample.yawDegrees();

			Kinematics.modelToWorld(pivot, yaw);
			Kinematics.modelToWorld(rest, yaw);
			Kinematics.modelToWorld(moved, yaw);

			sink.line(this.debugSample.x() + pivot.x, this.debugSample.y() + pivot.y, this.debugSample.z() + pivot.z,
					this.debugSample.x() + rest.x, this.debugSample.y() + rest.y, this.debugSample.z() + rest.z, 0x40E040);
			sink.line(this.debugSample.x() + pivot.x, this.debugSample.y() + pivot.y, this.debugSample.z() + pivot.z,
					this.debugSample.x() + moved.x, this.debugSample.y() + moved.y, this.debugSample.z() + moved.z, 0xF04040);
		}
	}

	/** How flattened a spring bone is, 0 to 1 (for tests and the debug command) */
	public float squishOf(String boneName) {
		for (Bone bone : this.bones) {
			if (bone.name.equals(boneName))
				return bone.squishAmount;
		}

		throw new IllegalArgumentException("no spring bone " + boneName);
	}

	/** Every spring bone's current rotation offset in radians, indexed like the config's bones (for tests) */
	public float[] rotationOf(String boneName) {
		for (Bone bone : this.bones) {
			if (bone.name.equals(boneName))
				return bone.state.rot.clone();
		}

		throw new IllegalArgumentException("no spring bone " + boneName);
	}

	/** Divides each column by its length, so the matrix turns without scaling (an animated bone scale would otherwise leak into the force) */
	public static void stripScale(Matrix3f m, Vector3f scratch) {
		for (int c = 0; c < 3; c++) {
			m.getColumn(c, scratch);

			float length = scratch.length();

			if (length > 1e-6f)
				m.setColumn(c, scratch.div(length));
		}
	}

	private static double distance(Sample a, Sample b) {
		double dx = a.x() - b.x();
		double dy = a.y() - b.y();
		double dz = a.z() - b.z();

		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}
}
