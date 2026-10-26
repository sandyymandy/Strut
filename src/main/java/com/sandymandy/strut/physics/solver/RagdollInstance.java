package com.sandymandy.strut.physics.solver;

import com.sandymandy.strut.animation.state.Pose;
import com.sandymandy.strut.animation.state.PosePalette;
import com.sandymandy.strut.cache.object.BakedStrutModel;
import com.sandymandy.strut.cache.object.GroupBoneDefinition;
import com.sandymandy.strut.cache.object.StrutCube;
import com.sandymandy.strut.cache.object.StrutMesh;
import com.sandymandy.strut.cache.object.StrutPoly;
import com.sandymandy.strut.cache.object.StrutQuad;
import com.sandymandy.strut.cache.object.StrutVertex;
import java.util.Arrays;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import com.sandymandy.strut.physics.PhysicsInstance;
import com.sandymandy.strut.physics.collision.Collider;
import com.sandymandy.strut.physics.tools.PhysicsControls;

/**
 * The ragdoll of one model instance: a skeleton of particles over the bones named in the config, free in the world, that the animation pulls on with a muscle
 * strength (see {@link RagdollSolver}), and the rotations (and, for the root, the position) that put every bone where its particles are
 * <p>
 * Turned on and off by {@link PhysicsControls}: {@code ragdoll(strength)} lets go (0 is limp), {@code ragdollRecover(seconds)} brings the muscle back up to full
 * and hands the body back to the animation, {@code ragdollOff()} fades out at once. The fades in and out are what keep the change from being a snap
 */
public final class RagdollInstance {
	private static final float FADE_IN_SECONDS = 0.12f;
	private static final float FADE_OUT_SECONDS = 0.25f;

	private final RagdollParams params;
	private final GroupBoneDefinition[] bones;
	private final int n;
	private final int[] parentBone;
	private final int[][] kids;
	private final int[] tipParticle;
	private final Vector3f[] tipLocal;
	private final int particles;
	private final int[] particleParent;
	private final int parentSticks;
	private RagdollSolver.Skeleton skeleton;
	private final RagdollSolver.State state;

	// per frame, from the animated pose (model space, blocks)
	private final Vector3f[] modelAnim;
	private final Matrix3f[] orient;
	private final Matrix3f[] animLocal;
	private final Matrix3f[] rootParent;
	private final Matrix4f matrix = new Matrix4f();
	private final Vector3f scratch = new Vector3f();
	private boolean prepared;

	// what the last output computed
	private final float[][] rot;
	private final Vector3f[] rootDelta;
	private boolean hasOutput;

	// control
	private boolean on;
	private float blend;
	private float strength;
	private boolean active;
	private double lastSeconds = Double.NaN;
	private final Vector3f startVelocity = new Vector3f();
	private final List<PhysicsControls.Impulse> pendingHits = new ArrayList<>();

	private RagdollInstance(RagdollParams params, GroupBoneDefinition[] ordered, int[] parentBone, int[] skeletonParent) {
		this.params = params;
		this.bones = ordered;
		this.n = ordered.length;
		this.parentBone = parentBone;
		this.kids = new int[this.n][];

		for (int i = 0; i < this.n; i++) {
			List<Integer> list = new ArrayList<>();

			for (int j = 0; j < this.n; j++) {
				if (skeletonParent[j] == i)
					list.add(j);
			}

			this.kids[i] = list.stream().mapToInt(Integer::intValue).toArray();
		}

		this.tipParticle = new int[this.n];

		int next = this.n;

		for (int i = 0; i < this.n; i++)
			this.tipParticle[i] = this.kids[i].length == 0 ? next++ : -1;

		this.particles = next;
		this.particleParent = new int[this.particles];

		int sticks = 0;

		for (int i = 0; i < this.n; i++) {
			this.particleParent[i] = skeletonParent[i];
			sticks += skeletonParent[i] >= 0 ? 1 : 0;

			if (this.tipParticle[i] >= 0) {
				this.particleParent[this.tipParticle[i]] = i;
				sticks++;
			}
		}

		this.parentSticks = sticks;
		this.tipLocal = new Vector3f[this.n];

		for (int i = 0; i < this.n; i++)
			this.tipLocal[i] = this.tipParticle[i] >= 0 ? tipOf(ordered[i], params) : null;

		this.state = new RagdollSolver.State(this.particles);
		this.modelAnim = new Vector3f[this.particles];

		for (int k = 0; k < this.particles; k++)
			this.modelAnim[k] = new Vector3f();

		this.orient = new Matrix3f[this.n];
		this.animLocal = new Matrix3f[this.n];
		this.rootParent = new Matrix3f[this.n];
		this.rot = new float[this.n][3];
		this.rootDelta = new Vector3f[this.n];

		for (int i = 0; i < this.n; i++) {
			this.orient[i] = new Matrix3f();
			this.animLocal[i] = new Matrix3f();
			this.rootParent[i] = new Matrix3f();
			this.rootDelta[i] = new Vector3f();
		}
	}

	/** Builds the ragdoll, or returns null (with the reasons in {@code problems}) if fewer than two of its bones exist */
	public static RagdollInstance create(BakedStrutModel model, RagdollParams params, List<String> problems) {
		List<GroupBoneDefinition> found = new ArrayList<>();

		for (String name : params.bones()) {
			Optional<GroupBoneDefinition> bone = model.getBone(name);

			if (bone.isPresent() && !found.contains(bone.get()))
				found.add(bone.get());
			else if (bone.isEmpty())
				problems.add("ragdoll: no bone " + name);
		}

		if (found.size() < 2) {
			problems.add("ragdoll: needs at least two bones that the model has");

			return null;
		}

		// parents first: a bone's parent is the nearest bone above it that is in the set
		List<GroupBoneDefinition> ordered = new ArrayList<>();
		List<GroupBoneDefinition> left = new ArrayList<>(found);

		while (!left.isEmpty()) {
			boolean progressed = false;

			for (int i = 0; i < left.size(); i++) {
				GroupBoneDefinition parent = nearestIn(left.get(i), found);

				if (parent == null || ordered.contains(parent)) {
					ordered.add(left.remove(i));
					progressed = true;

					break;
				}
			}

			if (!progressed)
				break;
		}

		int[] parent = new int[ordered.size()];

		for (int i = 0; i < parent.length; i++) {
			GroupBoneDefinition p = nearestIn(ordered.get(i), found);

			parent[i] = p == null ? -1 : ordered.indexOf(p);
		}

		int[] skeleton = parent.clone();

		params.parents().forEach((child, hangsFrom) -> {
			int c = indexOfName(ordered, child);
			int p = indexOfName(ordered, hangsFrom);

			if (c >= 0 && p >= 0 && c != p)
				skeleton[c] = p;
			else
				problems.add("ragdoll: parents entry \"" + child + "\": \"" + hangsFrom + "\" needs two bones of the ragdoll");
		});

		// the skeleton must be a tree: a bone whose chain of parents comes back to itself is cut loose
		for (int i = 0; i < skeleton.length; i++) {
			int steps = 0;

			for (int p = skeleton[i]; p >= 0 && steps <= skeleton.length; p = skeleton[p])
				steps++;

			if (steps > skeleton.length) {
				problems.add("ragdoll: \"" + ordered.get(i).getName() + "\" hangs from itself through its parents; cut loose");
				skeleton[i] = -1;
			}
		}

		return new RagdollInstance(params, ordered.toArray(new GroupBoneDefinition[0]), parent, skeleton);
	}

	private static int indexOfName(List<GroupBoneDefinition> list, String name) {
		for (int i = 0; i < list.size(); i++) {
			if (list.get(i).getName().equals(name))
				return i;
		}

		return -1;
	}

	private static GroupBoneDefinition nearestIn(GroupBoneDefinition bone, List<GroupBoneDefinition> set) {
		for (GroupBoneDefinition p = bone.getParent(); p != null; p = p.getParent()) {
			if (set.contains(p))
				return p;
		}

		return null;
	}

	/** The end of a bone with no child bone: from the config, else the far corner of its geometry, else a little way down */
	private static Vector3f tipOf(GroupBoneDefinition bone, RagdollParams params) {
		Vector3f configured = params.tips().get(bone.getName());

		if (configured != null)
			return new Vector3f(configured);

		Vector3f far = new Vector3f();
		float best = 0f;
		float px = bone.getPivotX() / 16f;
		float py = bone.getPivotY() / 16f;
		float pz = bone.getPivotZ() / 16f;

		for (StrutCube cube : bone.getCubes()) {
			for (StrutQuad quad : cube.quads()) {
				for (StrutVertex v : quad.vertices()) {
					float d = new Vector3f(v.position()).sub(px, py, pz).lengthSquared();

					if (d > best) {
						best = d;
						far.set(v.position()).sub(px, py, pz);
					}
				}
			}
		}

		for (StrutMesh mesh : bone.getMeshes()) {
			for (StrutPoly poly : mesh.polys()) {
				for (StrutVertex v : poly.vertices()) {
					float d = new Vector3f(v.position()).sub(px, py, pz).lengthSquared();

					if (d > best) {
						best = d;
						far.set(v.position()).sub(px, py, pz);
					}
				}
			}
		}

		return best < 0.0025f ? new Vector3f(0, -0.2f, 0) : far;
	}

	public boolean active() {
		return this.active;
	}

	int particleCount() {
		return this.particles;
	}

	/** How far from the model's origin the skeleton can reach, blocks (for choosing which blocks to read) */
	float reach() {
		return 3.5f;
	}

	/**
	 * The box around every joint, grown by how far the fastest one can travel in {@code seconds}, as {minX, minY, minZ, maxX, maxY, maxZ}; null until the body
	 * has been let go. A thrown body goes far from the entity, and the blocks it can hit are read around it, not around the entity
	 */
	public double[] bounds(double seconds) {
		if (!this.active || !this.state.initialised)
			return null;

		double[] b = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
		double fastest = 0;

		for (int k = 0; k < this.particles; k++) {
			double[] p = this.state.x[k];
			double[] q = this.state.prev[k];

			for (int a = 0; a < 3; a++) {
				b[a] = Math.min(b[a], p[a]);
				b[a + 3] = Math.max(b[a + 3], p[a]);
			}

			fastest = Math.max(fastest, Math.sqrt(Math.pow(p[0] - q[0], 2) + Math.pow(p[1] - q[1], 2) + Math.pow(p[2] - q[2], 2)));
		}

		// per step to per second, times the time to cover, plus the joints' size and a block
		double grow = Math.min(10.0, fastest * 60.0 * seconds) + this.params.radius() + 1.0;

		for (int a = 0; a < 3; a++) {
			b[a] -= grow;
			b[a + 3] += grow;
		}

		return b;
	}

	public void reset() {
		this.on = false;
		this.blend = 0f;
		this.active = false;
		this.state.initialised = false;
		this.hasOutput = false;
		this.lastSeconds = Double.NaN;
		this.pendingHits.clear();
		Arrays.fill(this.state.pin, null);
	}

	/**
	 * Follows the controls for this frame: turns on (letting go with the entity's own velocity), ramps the muscle back during a recovery, fades in and out
	 */
	public void control(PhysicsControls controls, double seconds, Vector3f worldVelocity) {
		double dt = Double.isNaN(this.lastSeconds) ? 0.0 : Math.max(0.0, Math.min(0.1, seconds - this.lastSeconds));

		this.lastSeconds = seconds;

		if (controls.ragdollOn()) {
			if (!this.on) {
				this.on = true;
				this.state.initialised = false;
				this.startVelocity.set(worldVelocity);
				this.strength = controls.ragdollStrength();
			}

			if (controls.ragdollRecoverSeconds() > 0f) {
				this.strength = Math.min(1f, this.strength + (float)dt / controls.ragdollRecoverSeconds());

				if (this.strength >= 1f)
					controls.ragdollFinished();
			}
			else {
				this.strength = controls.ragdollStrength();
			}

			this.blend = Math.min(1f, this.blend + (float)dt / FADE_IN_SECONDS);
		}
		else {
			this.on = false;
			this.blend = Math.max(0f, this.blend - (float)dt / FADE_OUT_SECONDS);
		}

		this.active = this.on || this.blend > 0f;

		if (!this.active) {
			this.state.initialised = false;
			this.hasOutput = false;
		}

		this.pendingHits.addAll(controls.drainRagdollHits());
	}

	/** Reads where the animation puts every joint this frame, in the model's space */
	public void prepare(Pose animated) {
		this.prepared = false;

		if (!this.active)
			return;

		for (int i = 0; i < this.n; i++) {
			PosePalette.computeBoneAtPivot(animated, this.bones[i], this.matrix);
			this.matrix.transformPosition(0f, 0f, 0f, this.modelAnim[i]);
			this.orient[i].set(this.matrix);
			PhysicsInstance.stripScale(this.orient[i], this.scratch);

			int index = this.bones[i].getIndex();

			this.animLocal[i].identity().rotateZYX(animated.getRotZ(index), animated.getRotY(index), animated.getRotX(index));

			if (this.parentBone[i] < 0) {
				GroupBoneDefinition outside = this.bones[i].getParent();

				if (outside == null) {
					this.rootParent[i].identity();
				}
				else {
					PosePalette.computeBoneAtPivot(animated, outside, this.matrix);
					this.rootParent[i].set(this.matrix);
					PhysicsInstance.stripScale(this.rootParent[i], this.scratch);
				}
			}
		}

		for (int i = 0; i < this.n; i++) {
			if (this.tipParticle[i] >= 0)
				this.orient[i].transform(new Vector3f(this.tipLocal[i]), this.modelAnim[this.tipParticle[i]]).add(this.modelAnim[i]);
		}

		if (this.skeleton == null) {
			double[][] rest = new double[this.particles][];

			for (int k = 0; k < this.particles; k++)
				rest[k] = new double[] {this.modelAnim[k].x, this.modelAnim[k].y, this.modelAnim[k].z};

			this.skeleton = RagdollSolver.build(this.particleParent, rest, this.params.minJointAngle());
		}

		this.prepared = true;
	}

	/**
	 * One step with the entity at {@code (ex, ey, ez)} facing {@code yaw}
	 *
	 * @param world Solid boxes around the entity in world space, or null
	 */
	public void step(double ex, double ey, double ez, float yaw, float h, List<Collider.Placed> world) {
		if (!this.active || !this.prepared || this.skeleton == null)
			return;

		double[][] anim = new double[this.particles][3];

		for (int k = 0; k < this.particles; k++) {
			this.scratch.set(this.modelAnim[k]);
			Kinematics.modelToWorld(this.scratch, yaw);
			anim[k][0] = ex + this.scratch.x;
			anim[k][1] = ey + this.scratch.y;
			anim[k][2] = ez + this.scratch.z;
		}

		if (!this.state.initialised)
			this.state.snapTo(anim, this.startVelocity.x, this.startVelocity.y, this.startVelocity.z, h);

		for (PhysicsControls.Impulse hit : this.pendingHits) {
			for (int i = 0; i < this.n; i++) {
				if (!this.bones[i].getName().equals(hit.bone()))
					continue;

				kick(i, hit.x(), hit.y(), hit.z(), 1.0, h);

				if (this.parentBone[i] >= 0)
					kick(this.parentBone[i], hit.x(), hit.y(), hit.z(), 0.5, h);

				for (int kid : this.kids[i])
					kick(kid, hit.x(), hit.y(), hit.z(), 0.5, h);
			}
		}

		this.pendingHits.clear();

		// only the shapes near the body can touch it: a dense place has hundreds, and every one is tested against every joint, several times a step
		this.near.clear();

		if (world != null) {
			double fastest = 0;

			for (int k = 0; k < this.particles; k++) {
				double[] p = this.state.x[k];
				double[] q = this.state.prev[k];

				fastest = Math.max(fastest, Math.abs(p[0] - q[0]) + Math.abs(p[1] - q[1]) + Math.abs(p[2] - q[2]));
			}

			Collider.cull(world, this.state.x, this.particles, 3.0 * fastest + this.params.radius() + 0.5, this.near);
		}

		RagdollSolver.step(this.state, this.skeleton, h, this.params.gravity(), this.params.damping(), this.params.friction(), this.params.radius(), anim, this.strength, this.near);
	}

	private final List<Collider.Placed> near = new ArrayList<>();

	private void kick(int particle, float x, float y, float z, double share, float h) {
		this.state.prev[particle][0] -= x * share * h;
		this.state.prev[particle][1] -= y * share * h;
		this.state.prev[particle][2] -= z * share * h;
	}

	/**
	 * Turns the particles into a rotation for each bone (from the root down: each bone swings and twists from its animated direction to its simulated one,
	 * relative to the bone above in its final place) and a position for the root, blended with the animation by the fade
	 *
	 * @param ex The place the particles are measured from: the entity at the last step
	 */
	public void computeOutput(double ex, double ey, double ez, float yaw) {
		computeOutput(StepFrame.at(ex, ey, ez, yaw));
	}

	/** As above, with the particles read at the frame's fraction between the last two steps */
	public void computeOutput(StepFrame frame) {
		if (!this.active || !this.prepared || !this.state.initialised) {
			this.hasOutput = false;

			return;
		}

		Vector3f[] sim = new Vector3f[this.particles];

		for (int k = 0; k < this.particles; k++) {
			sim[k] = new Vector3f();
			frame.toModel(this.state.x[k], this.state.before[k], sim[k]);
		}

		Matrix3f[] newOrient = new Matrix3f[this.n];
		Vector3f euler = new Vector3f();

		for (int i = 0; i < this.n; i++) {
			List<Vector3f> from = new ArrayList<>();
			List<Vector3f> to = new ArrayList<>();

			for (int kid : this.kids[i]) {
				from.add(new Vector3f(this.modelAnim[kid]).sub(this.modelAnim[i]));
				to.add(new Vector3f(sim[kid]).sub(sim[i]));
			}

			if (this.tipParticle[i] >= 0) {
				from.add(new Vector3f(this.modelAnim[this.tipParticle[i]]).sub(this.modelAnim[i]));
				to.add(new Vector3f(sim[this.tipParticle[i]]).sub(sim[i]));
			}

			Quaternionf swing = fit(from, to);
			Matrix3f newWorld = new Matrix3f().set(swing).mul(this.orient[i]);
			Matrix3f parentNew = this.parentBone[i] >= 0 ? newOrient[this.parentBone[i]] : this.rootParent[i];
			Matrix3f local = new Matrix3f(parentNew).transpose().mul(newWorld);
			Quaternionf animated = new Quaternionf().setFromNormalized(this.animLocal[i]);
			Quaternionf simulated = new Quaternionf().setFromNormalized(local).normalize();
			Quaternionf blended = new Quaternionf(animated).slerp(simulated, this.blend).normalize();
			Matrix3f blendedM = new Matrix3f().set(blended);

			blendedM.getEulerAnglesZYX(euler);
			this.rot[i][0] = euler.x;
			this.rot[i][1] = euler.y;
			this.rot[i][2] = euler.z;
			newOrient[i] = new Matrix3f(parentNew).mul(blendedM);

			if (this.parentBone[i] < 0)
				this.rootDelta[i].set(sim[i]).sub(this.modelAnim[i]).mul(this.blend);
		}

		this.hasOutput = true;
	}

	/**
	 * The rotation that takes the animated directions to the simulated ones: swing the first pair onto each other, then twist about it to bring the second pair
	 * as close as it can. A direction too short to mean anything (two pivots on the same spot) is left out
	 */
	private static Quaternionf fit(List<Vector3f> from, List<Vector3f> to) {
		Quaternionf q = new Quaternionf();
		int first = -1;
		int second = -1;

		for (int j = 0; j < from.size(); j++) {
			if (from.get(j).lengthSquared() < 1e-6f || to.get(j).lengthSquared() < 1e-6f)
				continue;

			if (first < 0)
				first = j;
			else if (second < 0 && from.get(j).cross(new Vector3f(from.get(first)), new Vector3f()).lengthSquared() > 1e-6f * from.get(j).lengthSquared() * from.get(first).lengthSquared())
				second = j;
		}

		if (first < 0)
			return q;

		Vector3f a0 = new Vector3f(from.get(first)).normalize();
		Vector3f s0 = new Vector3f(to.get(first)).normalize();

		q.rotationTo(a0, s0);

		if (second >= 0) {
			Vector3f a1 = q.transform(new Vector3f(from.get(second)));
			Vector3f s1 = new Vector3f(to.get(second));
			Vector3f u = a1.sub(new Vector3f(s0).mul(a1.dot(s0)));
			Vector3f v = s1.sub(new Vector3f(s0).mul(s1.dot(s0)));

			if (u.lengthSquared() > 1e-8f && v.lengthSquared() > 1e-8f) {
				float angle = (float)Math.atan2(s0.dot(new Vector3f(u).cross(v)), u.dot(v));

				q = new Quaternionf().rotationAxis(angle, s0.x, s0.y, s0.z).mul(q);
			}
		}

		return q;
	}

	/** Writes each bone's rotation into {@code pose}, and the root's move; nothing while inactive or before there is a result */
	public void apply(Pose pose) {
		if (!this.hasOutput || this.blend <= 0f)
			return;

		for (int i = 0; i < this.n; i++) {
			int index = this.bones[i].getIndex();

			pose.setRotation(index, this.rot[i][0], this.rot[i][1], this.rot[i][2]);

			if (this.parentBone[i] < 0) {
				// The renderer draws a bone's X position negated, so a model-space offset of dx is a pose value of -dx (blocks to model units)
				pose.setPosition(index, pose.getPosX(index) - this.rootDelta[i].x * 16f, pose.getPosY(index) + this.rootDelta[i].y * 16f, pose.getPosZ(index) + this.rootDelta[i].z * 16f);
			}
		}
	}

	/** Holds particle {@code index} (0-based) at {@code target} in world space, or lets go if null */
	public void pin(int index, double[] target) {
		if (index >= 0 && index < this.particles)
			this.state.pin[index] = target == null ? null : target.clone();
	}

	public void unpinAll() {
		Arrays.fill(this.state.pin, null);
	}

	/** The world position of each particle (for the cursor tool and drawing), empty until the body has been let go */
	public void particlePositions(List<double[]> out) {
		if (!this.active || !this.state.initialised)
			return;

		for (double[] p : this.state.x)
			out.add(p);
	}

	/** The sticks that follow the bones, as pairs of particle indices, for drawing */
	public int[][] boneSticks() {
		if (this.skeleton == null)
			return new int[0][];

		int[][] out = new int[Math.min(this.parentSticks, this.skeleton.stickA().length)][];

		for (int s = 0; s < out.length; s++)
			out[s] = new int[] {this.skeleton.stickA()[s], this.skeleton.stickB()[s]};

		return out;
	}

	/** The bone names, for tests and messages */
	public List<String> boneNames() {
		List<String> names = new ArrayList<>();

		for (GroupBoneDefinition b : this.bones)
			names.add(b.getName());

		return names;
	}

	public RagdollSolver.State solverState() {
		return this.state;
	}

	RagdollSolver.Skeleton skeleton() {
		return this.skeleton;
	}

	float blend() {
		return this.blend;
	}

	float strength() {
		return this.strength;
	}
}
