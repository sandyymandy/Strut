package com.sandymandy.strut.physics.solver;

import org.joml.Vector3f;

/**
 * What an entity's own motion does to a bone riding on it: the inertial (fictitious) force in a frame that moves and turns with the entity, plus the
 * conversions between world space, the model's space and a bone's own space
 * <p>
 * Pure maths, no game classes, so the physics can be tested by feeding it a scripted walk and turn. World axes are Minecraft's (x east, y up, z south),
 * and a yaw is Minecraft's: 0 faces south and it grows clockwise seen from above, which is a turn of minus that angle about +Y
 */
public final class Kinematics {
	private double px;
	private double py;
	private double pz;
	private float yaw;
	private boolean started;
	/** The entity's velocity over the last step, as the path gave it */
	private final Vector3f velocity = new Vector3f();
	private float angularVelocity;
	/** The same, low-pass filtered: what the accelerations are taken from (see {@link #SMOOTHING_SECONDS}) */
	private final Vector3f smoothVelocity = new Vector3f();
	private float smoothAngularVelocity;
	/** The first stage of that filter: it is two one-pole filters in a row, which cut a tick's kink far harder than one with the same lag */
	private final Vector3f firstStage = new Vector3f();
	private float firstStageAngular;

	/**
	 * The time constant of each of the two stages of the filter the entity's velocity and turn rate pass through before they are differentiated. A game moves an entity once a tick (twenty
	 * times a second, with whatever speed that tick happened to have, and a network-driven mob in lumps of three), so its path is straight lines with kinks
	 * every 50 ms; differentiated twice that is a spike of acceleration at every kink, and the springs answered each one: the jitter of a moving entity. Filtering
	 * the velocity at about a tick and a bit takes the kinks out and costs a lag far below the half-second a jiggle takes to swing
	 */
	static final float SMOOTHING_SECONDS = 0.05f;

	/** What the filter uses: {@link #SMOOTHING_SECONDS}, changed only by a test that wants the unfiltered behaviour to compare against */
	static volatile float smoothingSeconds = SMOOTHING_SECONDS;

	/** The last step's acceleration of the entity's origin, in world blocks per second squared */
	final Vector3f acceleration = new Vector3f();
	/** The last step's turn rate about +Y (radians per second) and its rate of change */
	float omega;
	float alpha;

	public void reset() {
		this.started = false;
		this.velocity.zero();
		this.angularVelocity = 0f;
		this.smoothVelocity.zero();
		this.smoothAngularVelocity = 0f;
		this.firstStage.zero();
		this.firstStageAngular = 0f;
		this.acceleration.zero();
		this.omega = 0f;
		this.alpha = 0f;
	}

	/**
	 * Takes the entity's position and yaw at the end of a step of {@code h} seconds and derives the step's acceleration and turn. The first call after a
	 * reset only records where the entity is
	 */
	public void advance(double x, double y, double z, float yawDegrees, float h) {
		if (!this.started) {
			this.px = x;
			this.py = y;
			this.pz = z;
			this.yaw = yawDegrees;
			this.started = true;

			return;
		}

		float vx = (float)((x - this.px) / h);
		float vy = (float)((y - this.py) / h);
		float vz = (float)((z - this.pz) / h);
		float w = -wrapDegrees(yawDegrees - this.yaw) * (float)(Math.PI / 180.0) / h;

		// The velocity the accelerations come from is the path's velocity run through a one-pole low-pass filter, so a tick's kink in the path is spread out
		float k = 1f - (float)Math.exp(-h / Math.max(1e-5f, smoothingSeconds));
		this.firstStage.add((vx - this.firstStage.x) * k, (vy - this.firstStage.y) * k, (vz - this.firstStage.z) * k);
		this.firstStageAngular += (w - this.firstStageAngular) * k;

		float sx = this.smoothVelocity.x + (this.firstStage.x - this.smoothVelocity.x) * k;
		float sy = this.smoothVelocity.y + (this.firstStage.y - this.smoothVelocity.y) * k;
		float sz = this.smoothVelocity.z + (this.firstStage.z - this.smoothVelocity.z) * k;
		float sw = this.smoothAngularVelocity + (this.firstStageAngular - this.smoothAngularVelocity) * k;

		this.acceleration.set((sx - this.smoothVelocity.x) / h, (sy - this.smoothVelocity.y) / h, (sz - this.smoothVelocity.z) / h);
		this.alpha = (sw - this.smoothAngularVelocity) / h;
		this.omega = sw;
		this.smoothVelocity.set(sx, sy, sz);
		this.smoothAngularVelocity = sw;
		this.velocity.set(vx, vy, vz);
		this.angularVelocity = w;
		this.px = x;
		this.py = y;
		this.pz = z;
		this.yaw = yawDegrees;
	}

	/**
	 * The push on a mass at {@code lever} (world offset from the entity's origin) in the entity's accelerating, turning frame: minus the linear acceleration,
	 * minus the Euler force of the turn's acceleration and minus the centrifugal force, all times {@code inertia}
	 */
	public void inertialForce(Vector3f lever, float inertia, Vector3f dest) {
		// alpha x r with alpha along +Y is (alpha * r.z, 0, -alpha * r.x); omega x (omega x r) with omega along +Y is (-omega^2 r.x, 0, -omega^2 r.z)
		float euler = this.alpha;
		float centrifugal = this.omega * this.omega;

		dest.set(this.acceleration.x + euler * lever.z - centrifugal * lever.x,
				this.acceleration.y,
				this.acceleration.z - euler * lever.x - centrifugal * lever.z);
		dest.mul(-inertia);
	}

	/** The entity's velocity over the last step, world blocks per second */
	public Vector3f velocity() {
		return this.velocity;
	}

	/** A yaw difference as the shortest turn, in degrees */
	public static float wrapDegrees(float degrees) {
		float wrapped = degrees % 360f;

		if (wrapped >= 180f)
			wrapped -= 360f;

		if (wrapped < -180f)
			wrapped += 360f;

		return wrapped;
	}

	/**
	 * The renderer turns a model by {@code 180 - yaw} degrees about +Y to put it in the world, so a world vector is taken into model space by the
	 * opposite turn
	 */
	public static void worldToModel(Vector3f v, float yawDegrees) {
		rotateY(v, -(180f - yawDegrees));
	}

	public static void modelToWorld(Vector3f v, float yawDegrees) {
		rotateY(v, 180f - yawDegrees);
	}

	/** A right-handed rotation about +Y, the same as {@code RotationAxis.POSITIVE_Y.rotationDegrees} */
	private static void rotateY(Vector3f v, float degrees) {
		double radians = Math.toRadians(degrees);
		float cos = (float)Math.cos(radians);
		float sin = (float)Math.sin(radians);
		float x = v.x * cos + v.z * sin;
		float z = -v.x * sin + v.z * cos;

		v.x = x;
		v.z = z;
	}
}
