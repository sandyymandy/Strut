package com.sandymandy.strut.physics.solver;

import com.sandymandy.strut.animation.state.Pose;
import com.sandymandy.strut.animation.state.PosePalette;
import com.sandymandy.strut.cache.object.BakedStrutModel;
import com.sandymandy.strut.cache.object.GroupBoneDefinition;
import java.util.Arrays;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import com.sandymandy.strut.physics.PhysicsInstance;
import com.sandymandy.strut.physics.collision.Bodies;
import com.sandymandy.strut.physics.collision.Collider;
import com.sandymandy.strut.physics.collision.ColliderRig;
import com.sandymandy.strut.physics.tools.PhysicsControls;

/**
 * One chain of bones on one model instance (hair, a tail, an ear): reads where the animation puts the chain, runs {@link ChainSolver} on it, and turns the
 * simulated particle positions back into a rotation for every bone
 * <p>
 * The bones must be consecutive: each the child of the one before. Rotating a bone about its pivot carries the next bone's pivot along the arc of a fixed-length
 * segment, which is exactly what the solver's distance constraints keep, so the particles and the bone hierarchy always agree
 */
public final class ChainInstance {
	public final String name;
	public final GroupBoneDefinition[] bones;
	private final ChainParams configured;
	public ChainParams active;
	private final int n;
	public final ChainSolver.State state;
	// The animated chain, in the model's space (blocks)
	private final Vector3f[] points;
	private final Matrix3f[] orientation;
	private final Vector3f[] tipLocal;
	private final Matrix3f[] animatedLocal;
	private final Matrix3f parentOrientation = new Matrix3f();
	private final double[] rest;
	private final double[][] anim;
	private final double[] delta = new double[3];
	private final List<Collider.Placed> shapes = new ArrayList<>();
	/** The model's own generated shapes this chain must not collide with (its own bones, parents and children), set once the rig exists */
	public boolean[] skip;
	private final Matrix4f matrix = new Matrix4f();
	private final Vector3f scratch = new Vector3f();
	/** The rotations to write, per bone (x, y, z radians), valid after {@link #computeOutput} */
	final float[][] output;
	private boolean hasOutput;

	private ChainInstance(String name, GroupBoneDefinition[] bones, ChainParams params) {
		this.name = name;
		this.bones = bones;
		this.configured = params;
		this.active = params;
		this.n = bones.length;
		this.state = new ChainSolver.State(this.n + 1);
		this.points = new Vector3f[this.n + 1];
		this.orientation = new Matrix3f[this.n];
		this.tipLocal = new Vector3f[this.n];
		this.animatedLocal = new Matrix3f[this.n];
		this.rest = new double[this.n];
		this.anim = new double[this.n + 1][3];
		this.output = new float[this.n][3];

		for (int i = 0; i <= this.n; i++)
			this.points[i] = new Vector3f();

		for (int i = 0; i < this.n; i++) {
			this.orientation[i] = new Matrix3f();
			this.tipLocal[i] = new Vector3f(0, -1, 0);
			this.animatedLocal[i] = new Matrix3f();
		}
	}

	/**
	 * Builds the chain, or returns null and says why in {@code problems}: a bone the model lacks, or bones that are not each the child of the one before
	 */
	public static ChainInstance create(String name, ChainParams params, BakedStrutModel model, List<String> problems) {
		GroupBoneDefinition[] defs = new GroupBoneDefinition[params.bones().size()];

		for (int i = 0; i < defs.length; i++) {
			Optional<GroupBoneDefinition> def = model.getBone(params.bones().get(i));

			if (def.isEmpty()) {
				problems.add("chain " + name + ": no bone " + params.bones().get(i));

				return null;
			}

			defs[i] = def.get();

			if (i > 0 && defs[i].getParent() != defs[i - 1]) {
				problems.add("chain " + name + ": bone " + params.bones().get(i) + " is not a child of " + params.bones().get(i - 1) + "; a chain is bones each hanging from the one before");

				return null;
			}
		}

		return new ChainInstance(name, defs, params);
	}

	public void reset() {
		this.state.initialised = false;
		this.hasOutput = false;
	}

	public int particles() {
		return this.n + 1;
	}

	/** Reads the animated chain from the pose: where each pivot is, how each bone is turned, and how long each segment is */
	public void prepare(Pose animated, PhysicsControls controls) {
		PhysicsControls.BoneControl control = controls.boneOrNull(this.name);

		this.active = this.configured.scaled(controls.firmness() * (control != null ? control.frequencyScale : 1f),
				controls.weight() * (control != null ? control.weight : 1f), controls.inertia());

		for (int i = 0; i < this.n; i++) {
			PosePalette.computeBoneAtPivot(animated, this.bones[i], this.matrix);
			this.matrix.transformPosition(0f, 0f, 0f, this.points[i]);
			this.orientation[i].set(this.matrix);
			PhysicsInstance.stripScale(this.orientation[i], this.scratch);

			int index = this.bones[i].getIndex();

			this.animatedLocal[i].identity().rotateZYX(animated.getRotZ(index), animated.getRotY(index), animated.getRotX(index));
		}

		GroupBoneDefinition parent = this.bones[0].getParent();

		if (parent == null) {
			this.parentOrientation.identity();
		}
		else {
			PosePalette.computeBoneAtPivot(animated, parent, this.matrix);
			this.parentOrientation.set(this.matrix);
			PhysicsInstance.stripScale(this.parentOrientation, this.scratch);
		}

		// the last particle is where the last bone reaches to: straight on from the segment before it, or down if there is none
		if (this.n == 1) {
			this.tipLocal[0].set(0, -1, 0);
		}
		else {
			this.scratch.set(this.points[this.n - 1]).sub(this.points[this.n - 2]);

			if (this.scratch.lengthSquared() < 1e-10f)
				this.scratch.set(0, -1, 0);

			this.orientation[this.n - 1].transpose(new Matrix3f()).transform(this.scratch.normalize(), this.tipLocal[this.n - 1]);
		}

		this.orientation[this.n - 1].transform(new Vector3f(this.tipLocal[this.n - 1]).mul(this.active.tipLength()), this.points[this.n]);
		this.points[this.n].add(this.points[this.n - 1]);

		for (int i = 0; i < this.n; i++) {
			this.scratch.set(this.points[i + 1]).sub(this.points[i]);

			double length = Math.max(1e-6, this.scratch.length());

			this.rest[i] = length;

			if (i < this.n - 1)
				this.orientation[i].transpose(new Matrix3f()).transform(this.scratch.div((float)length), this.tipLocal[i]);
		}
	}

	/**
	 * One step of the chain with the entity at {@code (ex, ey, ez)} facing {@code yaw}, having moved by {@code (dx, dy, dz)} since the last step
	 */
	public void step(double ex, double ey, double ez, float yaw, double dx, double dy, double dz, ColliderRig rig, List<Collider.Placed> world, List<Collider.Placed> others) {
		for (int k = 0; k <= this.n; k++) {
			this.scratch.set(this.points[k]);
			Kinematics.modelToWorld(this.scratch, yaw);
			this.anim[k][0] = ex + this.scratch.x;
			this.anim[k][1] = ey + this.scratch.y;
			this.anim[k][2] = ez + this.scratch.z;
		}

		if (!this.state.initialised)
			this.state.snapTo(this.anim);

		this.shapes.clear();

		if (rig != null)
			rig.place(ex, ey, ez, yaw, this.active.collidesWith(), this.skip, this.shapes);

		// a generated shape the animated chain already overlaps is part of the model's pose, not something to collide with
		Collider.dropRestingContacts(this.shapes, this.anim, 1, this.n + 1, this.active.radius());

		if (world != null && this.active.collidesWith().contains("world"))
			Collider.cull(world, this.state.x, this.n + 1, 0.8 + 2.0 * Math.sqrt(dx * dx + dy * dy + dz * dz) + this.active.radius(), this.shapes);

		if (others != null) {
			for (Collider.Placed shape : others) {
				if (Bodies.feels(this.active.collidesWith(), shape))
					this.shapes.add(shape);
			}
		}

		this.delta[0] = dx;
		this.delta[1] = dy;
		this.delta[2] = dz;

		if (this.active.weight() > 0f)
			ChainSolver.step(this.state, this.active, SpringSolver.STEP, this.anim, this.rest, this.delta, this.shapes);
	}

	/** Whether this chain wants blocks to collide with */
	public boolean usesWorld() {
		return this.active.collidesWith().contains("world") && this.active.weight() > 0f;
	}

	/** How far the chain reaches from its root, in blocks, and where its root is in the world at the last step (for choosing which blocks to read) */
	public double reach() {
		double sum = 0;

		for (double r : this.rest)
			sum += r;

		return sum + this.active.radius();
	}

	/** Holds particle {@code index} at {@code target} (world space), or lets go if target is null. Particle 0 is the animated root and cannot be held */
	public void pin(int index, double[] target) {
		if (index < 1 || index > this.n)
			return;

		this.state.pin[index] = target == null ? null : target.clone();
	}

	public void unpinAll() {
		Arrays.fill(this.state.pin, null);
	}

	/** A kick to every particle but the root, a velocity in world blocks per second */
	public void impulse(float x, float y, float z) {
		if (!this.state.initialised)
			return;

		for (int k = 1; k <= this.n; k++) {
			this.state.prev[k][0] -= x * SpringSolver.STEP;
			this.state.prev[k][1] -= y * SpringSolver.STEP;
			this.state.prev[k][2] -= z * SpringSolver.STEP;
		}
	}

	/**
	 * Turns the particles into a rotation for each bone, from the root down: each bone swings the way its segment points now, relative to the bone before it
	 * (which is already in its final place)
	 *
	 * @param ex The entity's position the particles are measured from, and its yaw
	 */
	public void computeOutput(double ex, double ey, double ez, float yaw) {
		computeOutput(StepFrame.at(ex, ey, ez, yaw));
	}

	/** As above, with the particles read at the frame's fraction between the last two steps */
	public void computeOutput(StepFrame frame) {
		if (!this.state.initialised) {
			this.hasOutput = false;

			return;
		}

		Vector3f[] model = new Vector3f[this.n + 1];

		for (int k = 0; k <= this.n; k++) {
			model[k] = new Vector3f();
			frame.toModel(this.state.x[k], this.state.before[k], model[k]);
		}

		Matrix3f parentNew = new Matrix3f(this.parentOrientation);
		float weight = this.active.weight();
		Vector3f euler = new Vector3f();

		for (int i = 0; i < this.n; i++) {
			Vector3f animatedTip = this.orientation[i].transform(new Vector3f(this.tipLocal[i]));
			Vector3f simulated = new Vector3f(model[i + 1]).sub(model[i]);

			if (simulated.lengthSquared() < 1e-12f)
				simulated.set(animatedTip);

			simulated.normalize();

			Quaternionf swing = new Quaternionf().rotationTo(animatedTip.normalize(), simulated);
			Matrix3f newWorld = new Matrix3f().set(swing).mul(this.orientation[i]);
			Matrix3f local = new Matrix3f(parentNew).transpose().mul(newWorld);
			Quaternionf animatedQ = new Quaternionf().setFromNormalized(this.animatedLocal[i]);
			Quaternionf simulatedQ = new Quaternionf().setFromNormalized(local).normalize();
			Quaternionf blended = new Quaternionf(animatedQ).slerp(simulatedQ, weight).normalize();
			Matrix3f blendedM = new Matrix3f().set(blended);

			blendedM.getEulerAnglesZYX(euler);
			this.output[i][0] = euler.x;
			this.output[i][1] = euler.y;
			this.output[i][2] = euler.z;
			parentNew = new Matrix3f(parentNew).mul(blendedM);
		}

		this.hasOutput = true;
	}

	/** Writes each bone's rotation into {@code pose} if the chain has a result */
	public void apply(Pose pose) {
		if (!this.hasOutput || this.active.weight() <= 0f)
			return;

		for (int i = 0; i < this.n; i++)
			pose.setRotation(this.bones[i].getIndex(), this.output[i][0], this.output[i][1], this.output[i][2]);
	}

	/** The largest difference between a segment's length and its rest length, relative, for tests */
	public double worstLengthError() {
		double worst = 0;

		if (!this.state.initialised)
			return 0;

		for (int i = 0; i < this.n; i++) {
			double dx = this.state.x[i + 1][0] - this.state.x[i][0];
			double dy = this.state.x[i + 1][1] - this.state.x[i][1];
			double dz = this.state.x[i + 1][2] - this.state.x[i][2];

			worst = Math.max(worst, Math.abs(Math.sqrt(dx * dx + dy * dy + dz * dz) - this.rest[i]) / this.rest[i]);
		}

		return worst;
	}
}
