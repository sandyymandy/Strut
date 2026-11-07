package com.sandymandy.strut.physics.flesh;

import com.sandymandy.strut.physics.collision.Collider;

import java.util.Arrays;
import java.util.List;

/**
 * The dynamics of soft regions: no game classes, plain arrays in world space, so it runs and is tested without a game
 * <p>
 * Each region is a cage of {@link FleshCageGeometry#NODES} nodes that the animation carries rigidly (the targets), and two layers of motion on top: the whole
 * region swinging as one lump, slow, and each node's own lag, quick, both as exact damped springs ({@link Oscillator}). The inertia comes from how the targets
 * themselves accelerate, so an animation shakes the flesh as much as the entity walking does. Then the cage is pulled toward its own shape, its links are kept
 * to length, its nodes are pushed out of the body (only past where they rest against it) and out of whatever presses in, a pair is kept on its own side of the
 * plane between them, the volume pressed in at one place swells out at another, and every node is softly held within the region's limit
 */
public final class FleshSolver {
	/** The fixed step every rate here is tuned for: a stiffness given as a share of what one step can hold is turned into a frequency by it */
	public static final double STEP = 1.0 / 60.0;
	/** The highest frequency anything rings at, radians a second: above this a step of 1/60 s could not show it */
	static final double OMEGA_MAX = 18 * Math.PI;
	/** A node held at least this firmly is left where the animation puts it by the collisions */
	private static final double HELD = 0.95;
	/** The largest body acceleration that counts, blocks a second squared */
	private static final double ACCELERATION_CAP = 80;
	/** How far off a body part a node rests as the least, blocks */
	private static final double MARGIN = 1.0 / 80.0;
	/** The width of the soft edge where something presses in, blocks */
	private static final double SOFT_EDGE = 0.025;
	/** How long a region must be calm before it rests, steps */
	private static final int CALM_STEPS = 30;
	/** Below this the body's acceleration moves the flesh by a sliver of a pixel, blocks a second squared */
	private static final double CALM_ACCELERATION = 0.5;
	/** Below this the flesh is still, blocks a second */
	private static final double CALM_SPEED = 0.01;
	/** How far something near a resting region may move against it before it wakes, blocks (a sixteenth of a pixel) */
	private static final double WAKE = 1.0 / 256.0;
	/**
	 * The same for a part of its own body, which only ever pushes it past where it rests against it: the body bending slowly under a resting region changes
	 * nothing anyone could see until it has moved by this much, blocks (half a pixel)
	 */
	private static final double WAKE_BODY = 1.0 / 32.0;
	/**
	 * How deep toward the middle a pressed node may go, as a share of how far out it is from it: never through the middle, so the cage never turns inside out
	 * however deep something goes into it
	 */
	private static final double PRESS_DEEPEST = 0.8;
	/** How many steps a pressed node takes toward the middle to find where the shapes end, and the least step, blocks */
	private static final int PRESS_STEPS = 10;
	private static final double PRESS_LEAST = 0.002;
	/** How long what was pushed in takes to fade, seconds: a base, and more of it for each unit of meat */
	private static final double DENT_FADE = 0.05;
	private static final double DENT_FADE_MEAT = 0.3;
	/** Below this a dent is gone, blocks */
	private static final double DENT_GONE = 1e-5;
	/** The share of the way back to its dent a released node is put each step */
	private static final double DENT_HOLD = 0.7;
	/** The most a free node swells out to give back a pressed-in volume, as a share of the region's limit */
	private static final double BULGE_MOST = 0.3;
	/** The farthest the flesh in a block the middle is in is moved out of it, blocks */
	private static final double FLATTEN_MOST = 0.25;
	/** How much of the mean of what presses the region in moves the whole of it (its swing) along: pressed on the side, it is shoved and swings back */
	private static final double PRESS_CARRY = 0.6;
	/** How many numbers say where a body shape is against a region, see {@link Region#restKey} */
	private static final int KEY = 20;
	/** How near two of those must be to count as the same, blocks: a body part that has moved less than this against the region (a sixteenth of a pixel) keeps
	 * what it measured, which a walk would otherwise have measured again every frame */
	private static final double SAME = 0.004;
	/** The deepest two lumps of a pair may press into each other before they are pushed straight apart, blocks */
	private static final double SQUEEZE_MAX = 0.09375;

	private FleshSolver() {}

	/** One region's state: the nodes, the lump, and what the last steps measured */
	public static final class Region {
		public final FleshCage cage;
		/** Where each node is, world blocks */
		public final double[] x = new double[FleshCageGeometry.NODES * 3];
		/** Where the animation puts each node this step, and the step before: set {@link #target} before each step */
		public final double[] target = new double[FleshCageGeometry.NODES * 3];
		final double[] targetBefore = new double[FleshCageGeometry.NODES * 3];
		private final double[] ownBefore = new double[FleshCageGeometry.NODES * 3];
		/** How far each node's target moved this step, world: the entity's and the animation's. A node that only goes with it has no error to damp */
		private final double[] shift = new double[FleshCageGeometry.NODES * 3];
		/** Each node's body acceleration, smoothed */
		final double[] acceleration = new double[FleshCageGeometry.NODES * 3];
		/** The velocity of each node's own lag */
		private final double[] lagVelocity = new double[FleshCageGeometry.NODES * 3];
		/** Each node's displacement from its target after the last step and the one before, for drawing between them */
		public final double[] displacement = new double[FleshCageGeometry.NODES * 3];
		public final double[] displacementBefore = new double[FleshCageGeometry.NODES * 3];
		private final double[] contact = new double[FleshCageGeometry.NODES];
		/** The nodes something presses on this step: they go as far as the shape pushes them, the jiggle's few-pixel limit is for what is not pushed */
		private final boolean[] pressed = new boolean[FleshCageGeometry.NODES];
		/**
		 * How far a body part is from each node's target, one row of nodes for each body shape, not a number until a node first needs it: kept while the shape
		 * stays where it was against the region, see {@link #restStale}
		 */
		private float[] restDistance = new float[0];
		/**
		 * The way out of each body shape at each node's target, in the same rows as {@link #restDistance}: x, y, z each in the region's own frame
		 * ({@link #frameSide}, {@link #frameUp}, {@link #frameOut}), so it holds however the entity turns
		 */
		private float[] restGradient = new float[0];
		/**
		 * Where each body shape was, in the region's own frame, when its row was measured: a shape found where it was (the torso under a pouch, for most of any
		 * animation) keeps its row, and only one that has moved against the region is measured again
		 */
		private double[] restKey = new double[0];
		private final double[] key = new double[KEY];
		/** For each body shape, the free nodes whose targets are near enough it ever to be pushed by it: {@code near[s * NODES]} on, {@code nearCount[s]} of them */
		private int[] near = new int[0];
		private int[] nearCount = new int[0];
		/** Each node's displacement from its target as the collisions find it, x, y, z each, and its length */
		private final double[] lag = new double[FleshCageGeometry.NODES * 3];
		private final double[] lagLength = new double[FleshCageGeometry.NODES];
		private final double[] ball = new double[4];
		/** How many steps in a row the region has been calm, see {@link #sleepy} */
		private int calm;
		/** The most any node or the lump moved in the last step it took, squared, blocks */
		private double motion = Double.MAX_VALUE;
		/** The shapes near it when it fell asleep, and where each was against it, see {@link #nearShapes} */
		private final java.util.ArrayList<Collider.Placed> sleepShapes = new java.util.ArrayList<>();
		private double[] sleepKeys = new double[0];
		/** Something pressed on it, or a body part moved against it, in the last step it took */
		private boolean disturbed;
		private final java.util.ArrayList<Collider.Placed> measured = new java.util.ArrayList<>();
		private final double[] frameSide = new double[3];
		private final double[] frameUp = new double[3];
		private final double[] frameOut = new double[3];
		/** How far apart each link's two targets are: the length it is kept toward, measured once a frame */
		private final double[] linkLength = new double[FleshCageGeometry.get().linkA.length];
		/** The targets have moved within the model since the links were measured (a new frame) */
		public boolean linksStale = true;
		private double[] bodyBoxes = new double[0];
		private int[] bodyLive = new int[0];
		private int[] pressLive = new int[0];
		private double[] pressBoxes = new double[0];
		/** The whole region's swing, world blocks, and its velocity; and the swing as last drawn */
		public final double[] lump = new double[3];
		private final double[] lumpVelocity = new double[3];
		public final double[] lumpShown = new double[3];
		public final double[] lumpShownBefore = new double[3];
		private double lumpOmega;
		private boolean initialised;

		/** The region's up, its tip direction and the entity's side, world, unit: set before each step */
		public final double[] up = {0, 1, 0};
		public final double[] tipDirection = {0, 0, 1};
		/** Where the entity stands (its feet), world */
		public double entityX;
		public double entityY;
		public double entityZ;
		/** The region's softness this step (the entry's times the entity's), and its size compared with how it was modelled */
		public double softness = 1;
		public double sizeScale = 1;
		public boolean dressed;
		/** How fast and how far the legs have walked (the walking animation's own measures), for the bounce on each step */
		public double legSpeed;
		public double legPosition;
		/** The height of the region's middle in the model, which tells whether the animation bobs it already */
		public double animatedHeight;
		/** The region's own body shapes changed since the last step (a new frame): their distances from the targets are measured again */
		public boolean restStale = true;

		private double heading;
		private double headingRate;
		private double headingAcceleration;
		private boolean headingKnown;
		private final double[] rise = new double[3];
		private double riseAcceleration;
		private final double[] sway = new double[3];
		private double swayAcceleration;
		private double heightMean;
		private double heightSwing;
		private boolean heightKnown;

		/** A node the cursor tool holds, and where (world), or -1: the debug grab */
		public int holdNode = -1;
		public final double[] holdTarget = new double[3];

		/** The pair plane this region is kept behind (point and normal, the normal toward the other), how far past it the region rests, and how hard it is pressed */
		public final double[] planePoint = new double[3];
		public final double[] planeNormal = new double[3];
		public double planeAllow;
		public double squeeze;
		public boolean paired;
		private final double[] inward = new double[3];
		// scratch for a step, so one allocates nothing
		private final double[] force = new double[3];
		private final double[] in = new double[3];
		private final double[] side = new double[3];
		private final double[] result = new double[3];
		private final double[] velocity = new double[3];
		private final double[] state = new double[2];
		private final double[] gradient = new double[3];
		private final double[] spread = new double[9];
		private final double[] rotation = new double[9];
		private final double[] cofactor = new double[9];
		/** Each node's springs, kept from step to step: worked out again only when the region's softness changes or the node's lag stiffens it a step more */
		private final Oscillator.Coefficients[] alongUp = new Oscillator.Coefficients[FleshCageGeometry.NODES];
		private final Oscillator.Coefficients[] across = new Oscillator.Coefficients[FleshCageGeometry.NODES];

		public Region(FleshCage cage) {
			this.cage = cage;

			for (int i = 0; i < FleshCageGeometry.NODES; i++) {
				this.alongUp[i] = new Oscillator.Coefficients();
				this.across[i] = new Oscillator.Coefficients();
			}
		}

		/** Snaps every node to its target with no motion: the next step starts from rest */
		public void reset() {
			this.initialised = false;
			this.calm = 0;
			this.motion = Double.MAX_VALUE;
		}

		public boolean initialised() {
			return this.initialised;
		}

		/**
		 * Where something pressing on the flesh pulls the region's swing to (world): the whole region sits displaced by this while it is held, and springs back, with
		 * its jiggle, once it is let go. Set each step by whoever presses; zero lets the swing go
		 */
		public final double[] shoveTarget = new double[3];
		/** How many nodes something pressed on in the last step */
		public int pressedNodes() {
			int count = 0;

			for (boolean on : this.pressed)
				count += on ? 1 : 0;

			return count;
		}

		/** The farthest any node was pushed in the last step, blocks */
		public double deepestPress() {
			double most = 0;

			for (int i = 0; i < FleshCageGeometry.NODES; i++) {
				if (this.pressed[i])
					most = Math.max(most, this.contact[i]);
			}

			return most;
		}

		/**
		 * How far what presses has pushed each node, world: it is part of where the node rests, and fades slowly once nothing presses, so a released dent creeps
		 * back (the slower the more {@code meat}) rather than snapping
		 */
		private final double[] dent = new double[FleshCageGeometry.NODES * 3];
		/** Whether any of {@link #dent} is not nothing */
		private boolean dented;
		/** Scratch: the share of the shell each surface node stands for */
		private final double[] area = new double[FleshCageGeometry.NODES];
		/** Scratch: the way a pressed node is pushed, and the way out of a block the middle is in */
		private final double[] pressWay = new double[3];
		private final double[] swallowWay = new double[3];

		/** Keeps the region from resting this step */
		public void wake() {
			this.calm = 0;
		}

		/** The limit this step: the cage's, or the dressed share of it */
		double limit(FleshParams params) {
			return this.cage.limit * (this.dressed ? params.dressedLimit() : 1.0);
		}
	}

	/**
	 * One step of every region of one soft body, {@link #STEP} long (or {@code h}): each region's {@link Region#target} and inputs must be set. A pair (two
	 * regions) also presses on itself
	 *
	 * @param body Each region's body shapes: the model's own parts, pushed out of only past where the node rests against them
	 * @param press Each region's pressing shapes: arms, a partner, other entities, pushed out of with a soft edge
	 * @param floorY The ground under the entity, world
	 */
	public static void step(Region[] regions, FleshParams params, List<Collider.Placed>[] body, List<Collider.Placed>[] press, double floorY, double h) {
		step(regions, params, body, press, floorY, h, false);
	}

	/**
	 * {@link #step(Region[], FleshParams, List[], List[], double, double)}, or with {@code swingOnly} only the swing of the whole: far off, where the flesh is a
	 * few pixels and nothing touches it, each node rides on the lump as the animation carries it, without its own lag, the pull to its shape or the links
	 */
	public static void step(Region[] regions, FleshParams params, List<Collider.Placed>[] body, List<Collider.Placed>[] press, double floorY, double h,
			boolean swingOnly) {
		boolean asleep = true;

		for (int i = 0; i < regions.length; i++) {
			Region r = regions[i];

			begin(r, h);
			gait(r, params, h);
			asleep &= sleepy(r, params, body[i], press[i]);
		}

		// every region calm: each keeps the shape it settled into, and nothing else is worked out until something stirs it
		if (asleep) {
			for (Region r : regions)
				hold(r);

			return;
		}

		if (regions.length == 2)
			pairPlane(regions[0], regions[1]);

		if (swingOnly) {
			for (Region r : regions) {
				lump(r, params, h);
				swing(r);
				Arrays.fill(r.contact, 0);
				Arrays.fill(r.pressed, false);
				settle(r, params);
			}

			return;
		}

		for (int i = 0; i < regions.length; i++) {
			Region r = regions[i];

			lump(r, params, h);
			nodes(r, params, h);
			shape(r, params);
			links(r, params);
			collide(r, params, body[i], press[i], floorY);

			if (r.holdNode >= 0) {
				// held where the cursor has it (settle then keeps it within the limit)
				System.arraycopy(r.holdTarget, 0, r.x, r.holdNode * 3, 3);
				Arrays.fill(r.lagVelocity, r.holdNode * 3, r.holdNode * 3 + 3, 0);
			}
		}

		if (regions.length == 2)
			pair(regions[0], regions[1], params, h);

		for (Region r : regions) {
			bulge(r, params);
			settle(r, params);
		}
	}

	/** Measures the body's acceleration at each node from how its target moved, the turn, and the walk */
	private static void begin(Region r, double h) {
		int n = FleshCageGeometry.NODES;

		System.arraycopy(r.displacement, 0, r.displacementBefore, 0, n * 3);
		System.arraycopy(r.lumpShown, 0, r.lumpShownBefore, 0, 3);

		if (!r.initialised) {
			System.arraycopy(r.target, 0, r.x, 0, n * 3);
			System.arraycopy(r.target, 0, r.targetBefore, 0, n * 3);
			Arrays.fill(r.ownBefore, 0);
			Arrays.fill(r.acceleration, 0);
			Arrays.fill(r.lagVelocity, 0);
			Arrays.fill(r.dent, 0);
			r.dented = false;
			Arrays.fill(r.displacement, 0);
			Arrays.fill(r.displacementBefore, 0);
			Arrays.fill(r.lump, 0);
			Arrays.fill(r.lumpVelocity, 0);
			Arrays.fill(r.lumpShown, 0);
			Arrays.fill(r.lumpShownBefore, 0);
			Arrays.fill(r.rise, 0);
			Arrays.fill(r.sway, 0);
			r.riseAcceleration = 0;
			r.swayAcceleration = 0;
			r.headingKnown = false;
			r.heightKnown = false;
			r.initialised = true;
		}

		double smooth = 1 - Math.pow(0.5, h / 0.04);

		for (int i = 0; i < n * 3; i++) {
			double own = r.target[i] - r.targetBefore[i];
			double a = (own - r.ownBefore[i]) / (h * h);

			r.shift[i] = own;

			r.ownBefore[i] = own;
			r.acceleration[i] += (a - r.acceleration[i]) * smooth;
			r.targetBefore[i] = r.target[i];
		}

		for (int i = 0; i < n; i++) {
			double ax = r.acceleration[i * 3];
			double ay = r.acceleration[i * 3 + 1];
			double az = r.acceleration[i * 3 + 2];
			double length = Math.sqrt(ax * ax + ay * ay + az * az);

			if (length > ACCELERATION_CAP) {
				double k = ACCELERATION_CAP / length;

				r.acceleration[i * 3] *= k;
				r.acceleration[i * 3 + 1] *= k;
				r.acceleration[i * 3 + 2] *= k;
			}
		}

		// how fast the region turns about the vertical, from the way its tip faces
		double heading = Math.atan2(r.tipDirection[0], r.tipDirection[2]);
		double turnSmooth = 1 - Math.pow(0.5, h / 0.06);

		if (r.headingKnown) {
			double change = heading - r.heading;

			change -= Math.floor((change + Math.PI) / (2 * Math.PI)) * 2 * Math.PI;

			double before = r.headingRate;

			r.headingRate += (change / h - r.headingRate) * turnSmooth;
			r.headingAcceleration += ((r.headingRate - before) / h - r.headingAcceleration) * turnSmooth;
		}
		else {
			r.headingRate = 0;
			r.headingAcceleration = 0;
		}

		r.heading = heading;
		r.headingKnown = true;

		// how much the animation already bobs the region: its height against a slow mean of it
		double settle = 1 - Math.pow(0.5, h / 0.5);

		if (!r.heightKnown) {
			r.heightMean = r.animatedHeight;
			r.heightSwing = 0;
			r.heightKnown = true;
		}

		r.heightMean += (r.animatedHeight - r.heightMean) * settle;
		r.heightSwing += (Math.abs(r.animatedHeight - r.heightMean) - r.heightSwing) * settle;
	}

	/**
	 * Whether the region may rest this step: it has been calm for {@link #CALM_STEPS} steps (the body barely accelerating, not turning, not walking, the
	 * flesh barely moving, nothing pressing on it and no part of the body moving against it), so what it would do is less than anyone could see
	 */
	private static boolean sleepy(Region r, FleshParams params, List<Collider.Placed> body, List<Collider.Placed> press) {
		// how far anything was seen to move in the last step: the springs may still be pulling against the cage's shape, but where it is drawn does not change
		double still = CALM_SPEED * STEP;
		boolean calm = r.legSpeed < 0.01 && Math.abs(r.riseAcceleration) < CALM_ACCELERATION && Math.abs(r.swayAcceleration) < CALM_ACCELERATION
				&& Math.abs(r.headingRate) < 0.05 && r.motion < still * still && !r.disturbed && !dented(r);

		for (int i = 0; calm && i < FleshCageGeometry.NODES; i++) {
			int at = i * 3;

			calm = r.acceleration[at] * r.acceleration[at] + r.acceleration[at + 1] * r.acceleration[at + 1] + r.acceleration[at + 2] * r.acceleration[at + 2]
					< CALM_ACCELERATION * CALM_ACCELERATION;
		}

		if (calm && r.calm >= CALM_STEPS) {
			// asleep: woken by anything that could press on it, or a body part moving against it
			double[] lo = r.force;
			double[] hi = r.in;

			Arrays.fill(lo, Double.POSITIVE_INFINITY);
			Arrays.fill(hi, Double.NEGATIVE_INFINITY);

			for (int i = 0; i < FleshCageGeometry.NODES; i++) {
				for (int k = 0; k < 3; k++) {
					lo[k] = Math.min(lo[k], r.target[i * 3 + k]);
					hi[k] = Math.max(hi[k], r.target[i * 3 + k]);
				}
			}

			double reach = r.limit(params) + SOFT_EDGE;

			for (int k = 0; k < 3; k++) {
				lo[k] -= reach;
				hi[k] += reach;
			}

			regionFrame(r);

			// falling asleep: where everything near it is, against it; asleep: woken when any of that has moved more than a sliver (an arm swung in, someone
			// stepping up, the body bending), or something new has come near
			if (r.calm == CALM_STEPS)
				nearShapes(r, body, press, lo, hi, true);
			else if (nearShapes(r, body, press, lo, hi, false))
				calm = false;
		}

		if (!calm) {
			r.calm = 0;

			return false;
		}

		r.calm++;

		return r.calm > CALM_STEPS;
	}

	/** Whether something still has some of the region pushed in, fading */
	private static boolean dented(Region r) {
		return r.dented;
	}

	/** Each node where the animation puts it, moved by the lump's swing as far as it is free: the swing of the whole, and nothing of its own */
	private static void swing(Region r) {
		for (int i = 0; i < FleshCageGeometry.NODES; i++) {
			double soft = 1 - r.cage.anchor[i];
			int at = i * 3;

			r.x[at] = r.target[at] + r.lumpShown[0] * soft;
			r.x[at + 1] = r.target[at + 1] + r.lumpShown[1] * soft;
			r.x[at + 2] = r.target[at + 2] + r.lumpShown[2] * soft;
			r.lagVelocity[at] = 0;
			r.lagVelocity[at + 1] = 0;
			r.lagVelocity[at + 2] = 0;
		}
	}

	/** Keeps a resting region where it settled: each node at its target and the displacement it had */
	private static void hold(Region r) {
		for (int i = 0; i < FleshCageGeometry.NODES * 3; i++)
			r.x[i] = r.target[i] + r.displacement[i];
	}

	/** Whether the region is resting: its shape is not worked out, only carried */
	public static boolean asleep(Region r) {
		return r.calm > CALM_STEPS;
	}

	/** The rise and the sway of a walk at the legs' place, faded where the animation bobs the region itself */
	private static void gait(Region r, FleshParams params, double h) {
		double own = FleshCage.smoothstep(0.0125, 0.03, r.heightSwing);
		double speed = Math.max(0, Math.min(1, r.legSpeed));
		double rise = (1 - own) * params.rise() * speed * (1 - Math.cos(1.3324 * r.legPosition)) / 2;
		double sway = (1 - own) * params.sway() * speed * Math.sin(0.6662 * r.legPosition);
		double smooth = 1 - Math.pow(0.5, h / 0.04);

		r.rise[2] = r.rise[1];
		r.rise[1] = r.rise[0];
		r.rise[0] = rise;
		r.sway[2] = r.sway[1];
		r.sway[1] = r.sway[0];
		r.sway[0] = sway;
		r.riseAcceleration += ((r.rise[0] - 2 * r.rise[1] + r.rise[2]) / (h * h) - r.riseAcceleration) * smooth;
		r.swayAcceleration += ((r.sway[0] - 2 * r.sway[1] + r.sway[2]) / (h * h) - r.swayAcceleration) * smooth;
	}

	/** Where the two of a pair meet: the plane halfway between their middles, and which way is in for each */
	private static void pairPlane(Region a, Region b) {
		int c = FleshCageGeometry.CENTRE * 3;
		double nx = b.target[c] + b.lumpShown[0] - a.target[c] - a.lumpShown[0];
		double ny = b.target[c + 1] + b.lumpShown[1] - a.target[c + 1] - a.lumpShown[1];
		double nz = b.target[c + 2] + b.lumpShown[2] - a.target[c + 2] - a.lumpShown[2];
		double length = Math.sqrt(nx * nx + ny * ny + nz * nz);

		if (length < 1e-6) {
			a.paired = false;
			b.paired = false;

			return;
		}

		nx /= length;
		ny /= length;
		nz /= length;

		for (int k = 0; k < 3; k++) {
			double middle = (a.target[c + k] + a.lumpShown[k] + b.target[c + k] + b.lumpShown[k]) * 0.5;

			a.planePoint[k] = middle;
			b.planePoint[k] = middle;
		}

		a.planeNormal[0] = nx;
		a.planeNormal[1] = ny;
		a.planeNormal[2] = nz;
		b.planeNormal[0] = -nx;
		b.planeNormal[1] = -ny;
		b.planeNormal[2] = -nz;
		a.inward[0] = nx;
		a.inward[1] = ny;
		a.inward[2] = nz;
		b.inward[0] = -nx;
		b.inward[1] = -ny;
		b.inward[2] = -nz;
		// how far past the plane each rests: a model whose two touch at rest keeps that overlap
		a.planeAllow = Math.max(0, reachPast(a, a.target));
		b.planeAllow = Math.max(0, reachPast(b, b.target));
		a.paired = true;
		b.paired = true;
	}

	/** The farthest any of {@code points} reaches past the region's plane */
	private static double reachPast(Region r, double[] points) {
		double most = -Double.MAX_VALUE;

		for (int i = 0; i < FleshCageGeometry.SURFACE; i++) {
			double along = (points[i * 3] - r.planePoint[0]) * r.planeNormal[0] + (points[i * 3 + 1] - r.planePoint[1]) * r.planeNormal[1]
					+ (points[i * 3 + 2] - r.planePoint[2]) * r.planeNormal[2];

			most = Math.max(most, along);
		}

		return most;
	}

	/** The swing of the whole region: three springs, along up, toward the other of a pair (or forward), and to the side */
	private static void lump(Region r, FleshParams params, double h) {
		double limit = r.limit(params);
		double tip = Math.min(1, 0.05 / Math.max(0.05, r.softness) * r.sizeScale);
		double firm = params.firmness() * (r.dressed ? Math.sqrt(params.dressedStiffness()) : 1);
		double swing = Math.sqrt(r.lump[0] * r.lump[0] + r.lump[1] * r.lump[1] + r.lump[2] * r.lump[2]);
		double stiffening = Math.sqrt(1 + square(swing / Math.max(1e-6, params.stiffen() * limit)));
		double omega = Math.min(OMEGA_MAX, Math.sqrt(tip) / STEP * 0.85 * stiffening * firm);
		double zeta = r.dressed ? Math.max(params.lumpDamping(), 0.4) : params.lumpDamping();
		int c = FleshCageGeometry.CENTRE * 3;
		double[] force = r.force;
		double share = params.inertia() * params.lumpShare();

		for (int k = 0; k < 3; k++)
			force[k] = -share * r.acceleration[c + k];

		// turning swings it to the outside of the turn and lags it behind, more than the turn's own acceleration alone would
		double tx = r.tipDirection[0];
		double tz = r.tipDirection[2];
		double flat = Math.sqrt(tx * tx + tz * tz);

		if (flat > 1e-6) {
			tx /= flat;
			tz /= flat;

			// up crossed with the tip: the way the region's front moves when it turns the positive way
			double sideX = tz;
			double sideZ = -tx;
			double turn = 30 * Math.tanh(r.headingRate / 5) + 0.3 * r.headingAcceleration;
			double lateral = -Math.max(-40, Math.min(40, params.turn() * turn));

			force[0] += lateral * sideX + r.swayAcceleration * -sideX;
			force[2] += lateral * sideZ + r.swayAcceleration * -sideZ;

			double spin = 5 * Math.tanh(r.headingRate / 5);
			double rx = r.target[c] - r.entityX;
			double rz = r.target[c + 2] - r.entityZ;

			force[0] += params.turn() * spin * spin * rx;
			force[2] += params.turn() * spin * spin * rz;
		}

		// three axes: up, in (toward the other of a pair, or forward), and the side between them
		double[] u = r.up;
		double[] in = r.in;

		if (r.paired)
			System.arraycopy(r.inward, 0, in, 0, 3);
		else
			System.arraycopy(r.tipDirection, 0, in, 0, 3);

		double along = in[0] * u[0] + in[1] * u[1] + in[2] * u[2];

		for (int k = 0; k < 3; k++)
			in[k] -= along * u[k];

		if (!normalise(in))
			perpendicular(u, in);

		double[] side = r.side;

		side[0] = u[1] * in[2] - u[2] * in[1];
		side[1] = u[2] * in[0] - u[0] * in[2];
		side[2] = u[0] * in[1] - u[1] * in[0];

		double[] state = r.state;
		double[] result = r.result;
		double[] velocity = r.velocity;

		Arrays.fill(result, 0);
		Arrays.fill(velocity, 0);

		for (int a = 0; a < 3; a++) {
			double[] axis = a == 0 ? u : a == 1 ? in : side;

			state[0] = dot(r.lump, axis);
			state[1] = dot(r.lumpVelocity, axis);

			double w = omega;

			if (a == 1)
				w = r.paired ? omega * Math.sqrt(state[0] > 0 ? params.squeezeIn() : params.squeezeOut()) : omega * Math.sqrt(params.sideStiffness());
			else if (a == 2)
				w = omega * Math.sqrt(params.sideStiffness());

			double reach = Math.min(OMEGA_MAX, w);

			// the swing is held toward what presses on it as a spring is toward its target: a push of the same size that a force of w squared times it would be
			Oscillator.step(state, dot(force, axis) + reach * reach * dot(r.shoveTarget, axis), reach, zeta, h);

			for (int k = 0; k < 3; k++) {
				result[k] += state[0] * axis[k];
				velocity[k] += state[1] * axis[k];
			}
		}

		// never far past the limit, whatever pushed it
		double length = Math.sqrt(dot(result, result));

		if (length > 2 * limit) {
			double k = 2 * limit / length;

			for (int i = 0; i < 3; i++) {
				result[i] *= k;
				velocity[i] *= k;
			}
		}

		System.arraycopy(result, 0, r.lump, 0, 3);
		System.arraycopy(velocity, 0, r.lumpVelocity, 0, 3);
		r.lumpOmega = omega;
		softClamp(r.lump, 0.8 * limit, r.lumpShown);
	}

	/** Each node's own lag behind its target, riding on the lump: a spring along up and one across, firm where the node is held and soft at the free end */
	private static void nodes(Region r, FleshParams params, double h) {
		double limit = r.limit(params);
		double tip = Math.min(1, 0.05 / Math.max(0.05, r.softness) * r.sizeScale);
		double firm = params.firmness() * (r.dressed ? Math.sqrt(params.dressedStiffness()) : 1);
		double zetaTip = r.dressed ? Math.max(params.nodeDamping(), 0.45) : params.nodeDamping();
		double nodeShare = params.inertia() * (1 - params.lumpShare());
		double sag = params.gravity() / (STEP * STEP);
		double[] u = r.up;
		double[] state = r.state;
		double[] before = r.lumpShownBefore;
		double[] now = r.lumpShown;

		for (int i = 0; i < FleshCageGeometry.NODES; i++) {
			double w = r.cage.anchor[i];
			double soft = 1 - w;
			int at = i * 3;
			// the error is against where the target was at the start of the step, not where it has gone: a node that has only gone along with its target
			// (the entity walking, the animation moving the part) has none, and what the node adds to that is its own lag
			// a node still pressed (last step) is pulled to where it would be; one let go rests at the dent it had, which fades
			double rest = r.pressed[i] ? 0 : 1;
			double ex = r.x[at] - (r.target[at] - r.shift[at]) - before[0] * soft - r.dent[at] * rest;
			double ey = r.x[at + 1] - (r.target[at + 1] - r.shift[at + 1]) - before[1] * soft - r.dent[at + 1] * rest;
			double ez = r.x[at + 2] - (r.target[at + 2] - r.shift[at + 2]) - before[2] * soft - r.dent[at + 2] * rest;
			double k = Math.min(1, tip + (1 - tip) * w);
			double lag = Math.sqrt(ex * ex + ey * ey + ez * ez);
			double base = Math.sqrt(k) / STEP * (1 + 0.6 * soft) * firm;
			double stiffening = Math.sqrt(1 + square(lag / Math.max(1e-6, params.stiffen() * limit)));
			double zeta = zetaTip + (1 - zetaTip) * w * w;
			// the stiffening in steps of two percent, which no eye tells apart: a node's springs are worked out again only when its lag crosses a step, and are
			// otherwise the ones kept for it
			stiffening = 1 + Math.rint((stiffening - 1) * 50) / 50;

			Oscillator.Coefficients up = r.alongUp[i].set(Math.min(OMEGA_MAX, base * stiffening), zeta, h);
			Oscillator.Coefficients side = r.across[i].set(Math.min(OMEGA_MAX, base * stiffening * Math.sqrt(params.sideStiffness())), zeta, h);

			double ax = -nodeShare * soft * r.acceleration[at];
			double ay = -nodeShare * soft * r.acceleration[at + 1] - sag * soft - r.riseAcceleration * soft;
			double az = -nodeShare * soft * r.acceleration[at + 2];
			double vx = r.lagVelocity[at];
			double vy = r.lagVelocity[at + 1];
			double vz = r.lagVelocity[at + 2];

			// along up
			double eu = ex * u[0] + ey * u[1] + ez * u[2];
			double vu = vx * u[0] + vy * u[1] + vz * u[2];
			double au = ax * u[0] + ay * u[1] + az * u[2];

			state[0] = eu;
			state[1] = vu;
			up.step(state, au);

			double eu2 = state[0];
			double vu2 = state[1];

			// across: each world axis of what is left, the same spring
			for (int c = 0; c < 3; c++) {
				double e = (c == 0 ? ex : c == 1 ? ey : ez) - eu * u[c];
				double v = (c == 0 ? vx : c == 1 ? vy : vz) - vu * u[c];
				double a = (c == 0 ? ax : c == 1 ? ay : az) - au * u[c];

				state[0] = e;
				state[1] = v;
				side.step(state, a);
				r.x[at + c] = r.target[at + c] + now[c] * soft + state[0] + eu2 * u[c];
				r.lagVelocity[at + c] = state[1] + vu2 * u[c];
			}
		}
	}

	/**
	 * The cage pulled toward its own shape, turned and moved as the nodes are now (the rotation from the polar part of their spread against the targets'): it
	 * may swing and shift as a whole but keeps its form, so the nodes cannot wander off on their own
	 */
	private static void shape(Region r, FleshParams params) {
		double pull = params.shape() / Math.max(0.1, params.shapeSoftness() * r.softness / Math.max(1e-6, params.softness()));

		if (pull <= 0)
			return;

		double total = 0;
		double[] cx = r.result;
		double[] ct = r.velocity;

		Arrays.fill(cx, 0);
		Arrays.fill(ct, 0);

		for (int i = 0; i < FleshCageGeometry.NODES; i++) {
			double m = 1 - r.cage.anchor[i];

			if (m < 0.05)
				continue;

			total += m;

			for (int k = 0; k < 3; k++) {
				cx[k] += m * r.x[i * 3 + k];
				ct[k] += m * r.target[i * 3 + k];
			}
		}

		if (total < 1e-9)
			return;

		for (int k = 0; k < 3; k++) {
			cx[k] /= total;
			ct[k] /= total;
		}

		double[] spread = r.spread;

		Arrays.fill(spread, 0);

		for (int i = 0; i < FleshCageGeometry.NODES; i++) {
			double m = 1 - r.cage.anchor[i];

			if (m < 0.05)
				continue;

			for (int a = 0; a < 3; a++) {
				double p = r.x[i * 3 + a] - cx[a];

				for (int b = 0; b < 3; b++)
					spread[a * 3 + b] += m * p * (r.target[i * 3 + b] - ct[b]);
			}
		}

		double[] rotation = r.rotation;

		if (!polar(spread, rotation, r.cofactor))
			return;

		for (int i = 0; i < FleshCageGeometry.NODES; i++) {
			double m = 1 - r.cage.anchor[i];

			if (m < 0.05)
				continue;

			double qx = r.target[i * 3] - ct[0];
			double qy = r.target[i * 3 + 1] - ct[1];
			double qz = r.target[i * 3 + 2] - ct[2];
			double amount = Math.min(1, pull * m);

			for (int a = 0; a < 3; a++) {
				double goal = cx[a] + rotation[a * 3] * qx + rotation[a * 3 + 1] * qy + rotation[a * 3 + 2] * qz;

				r.x[i * 3 + a] += (goal - r.x[i * 3 + a]) * amount;
			}
		}
	}

	/**
	 * The rotation part of a 3 by 3 matrix (row major) into {@code r}, by averaging it with its inverse transpose until it settles; false when it has none (a
	 * flat or mirrored spread)
	 */
	static boolean polar(double[] m, double[] r, double[] next) {
		double scale = 0;

		for (double v : m)
			scale = Math.max(scale, Math.abs(v));

		if (scale < 1e-12)
			return false;

		for (int i = 0; i < 9; i++)
			r[i] = m[i] / scale;

		for (int iteration = 0; iteration < 12; iteration++) {
			double det = determinant(r);

			if (!(det > 1e-12))
				return false;

			// the inverse transpose is the cofactor matrix over the determinant
			next[0] = (r[4] * r[8] - r[5] * r[7]) / det;
			next[1] = (r[5] * r[6] - r[3] * r[8]) / det;
			next[2] = (r[3] * r[7] - r[4] * r[6]) / det;
			next[3] = (r[2] * r[7] - r[1] * r[8]) / det;
			next[4] = (r[0] * r[8] - r[2] * r[6]) / det;
			next[5] = (r[1] * r[6] - r[0] * r[7]) / det;
			next[6] = (r[1] * r[5] - r[2] * r[4]) / det;
			next[7] = (r[2] * r[3] - r[0] * r[5]) / det;
			next[8] = (r[0] * r[4] - r[1] * r[3]) / det;

			for (int i = 0; i < 9; i++)
				r[i] = (r[i] + next[i]) * 0.5;
		}

		return true;
	}

	private static double determinant(double[] r) {
		return r[0] * (r[4] * r[8] - r[5] * r[7]) - r[1] * (r[3] * r[8] - r[5] * r[6]) + r[2] * (r[3] * r[7] - r[4] * r[6]);
	}

	/** One pass over the cage's links: each kept toward the length its two targets are apart, the freer end moving more */
	private static void links(Region r, FleshParams params) {
		FleshCageGeometry geometry = FleshCageGeometry.get();
		double stiffness = params.link();

		if (stiffness <= 0)
			return;

		// the targets move as one within a frame (the entity carries them), so the lengths between them are measured once a frame
		if (r.linksStale) {
			for (int l = 0; l < geometry.linkA.length; l++) {
				int a = geometry.linkA[l] * 3;
				int b = geometry.linkB[l] * 3;
				double rx = r.target[b] - r.target[a];
				double ry = r.target[b + 1] - r.target[a + 1];
				double rz = r.target[b + 2] - r.target[a + 2];

				r.linkLength[l] = Math.sqrt(rx * rx + ry * ry + rz * rz);
			}

			r.linksStale = false;
		}

		for (int l = 0; l < geometry.linkA.length; l++) {
			int a = geometry.linkA[l];
			int b = geometry.linkB[l];
			double wa = 1 - r.cage.anchor[a];
			double wb = 1 - r.cage.anchor[b];

			if (wa + wb < 1e-6)
				continue;

			double dx = r.x[b * 3] - r.x[a * 3];
			double dy = r.x[b * 3 + 1] - r.x[a * 3 + 1];
			double dz = r.x[b * 3 + 2] - r.x[a * 3 + 2];
			double length = Math.sqrt(dx * dx + dy * dy + dz * dz);

			if (length < 1e-9)
				continue;

			double error = (length - r.linkLength[l]) * stiffness / length;
			double sa = wa / (wa + wb);
			double sb = wb / (wa + wb);

			r.x[a * 3] += dx * error * sa;
			r.x[a * 3 + 1] += dy * error * sa;
			r.x[a * 3 + 2] += dz * error * sa;
			r.x[b * 3] -= dx * error * sb;
			r.x[b * 3 + 1] -= dy * error * sb;
			r.x[b * 3 + 2] -= dz * error * sb;
		}
	}

	/**
	 * Out of the body, but only past where each node rests against it (a part that overlaps the region as modelled does not push it), out of what presses
	 * in with a soft edge, and off the floor
	 */
	private static void collide(Region r, FleshParams params, List<Collider.Placed> body, List<Collider.Placed> press, double floorY) {
		int n = FleshCageGeometry.NODES;
		double[] gradient = r.gradient;

		Arrays.fill(r.contact, 0);
		Arrays.fill(r.pressed, false);

		// the box round each shape, once a step: a node outside a shape's box by more than it could need is passed over without the shape being measured
		double[] bodyBoxes = boxes(body, r.bodyBoxes, r.force, r.in);
		double[] pressBoxes = boxes(press, r.pressBoxes, r.force, r.in);

		r.bodyBoxes = bodyBoxes;
		r.pressBoxes = pressBoxes;

		double[] side = r.frameSide;
		double[] up = r.frameUp;
		double[] out = r.frameOut;

		regionFrame(r);

		if (r.restStale || r.restDistance.length != body.size() * n)
			refreshRows(r, params, body);

		// a shape no node can reach this step is passed over whole: a body shape whose box is farther from every free node's target than the farthest any has
		// moved (and the margin), a pressing shape whose box is clear of the box round the nodes
		double farthest = 0;
		double[] lo = r.force;
		double[] hi = r.in;
		double[] restLo = r.result;
		double[] restHi = r.velocity;

		Arrays.fill(lo, Double.POSITIVE_INFINITY);
		Arrays.fill(hi, Double.NEGATIVE_INFINITY);
		Arrays.fill(restLo, Double.POSITIVE_INFINITY);
		Arrays.fill(restHi, Double.NEGATIVE_INFINITY);

		for (int i = 0; i < n; i++) {
			if (r.cage.anchor[i] > HELD)
				continue;

			int at = i * 3;
			double dx = r.x[at] - r.target[at];
			double dy = r.x[at + 1] - r.target[at + 1];
			double dz = r.x[at + 2] - r.target[at + 2];
			double moved2 = dx * dx + dy * dy + dz * dz;

			r.lag[at] = dx;
			r.lag[at + 1] = dy;
			r.lag[at + 2] = dz;
			r.lagLength[i] = Math.sqrt(moved2);
			farthest = Math.max(farthest, moved2);

			for (int k = 0; k < 3; k++) {
				lo[k] = Math.min(lo[k], r.x[at + k]);
				hi[k] = Math.max(hi[k], r.x[at + k]);
				restLo[k] = Math.min(restLo[k], r.target[at + k]);
				restHi[k] = Math.max(restHi[k], r.target[at + k]);
			}
		}

		farthest = Math.sqrt(farthest);

		double room = farthest + MARGIN;

		int bodyCount = 0;
		int pressCount = 0;

		if (r.bodyLive.length < body.size())
			r.bodyLive = new int[body.size() + 8];

		if (r.pressLive.length < press.size())
			r.pressLive = new int[press.size() + 8];

		int[] bodyLive = r.bodyLive;
		int[] pressLive = r.pressLive;

		for (int s = 0; s < body.size(); s++) {
			int b = s * 6;

			if (bodyBoxes[b] <= restHi[0] + room && bodyBoxes[b + 3] >= restLo[0] - room && bodyBoxes[b + 1] <= restHi[1] + room
					&& bodyBoxes[b + 4] >= restLo[1] - room && bodyBoxes[b + 2] <= restHi[2] + room && bodyBoxes[b + 5] >= restLo[2] - room)
				bodyLive[bodyCount++] = s;
		}

		for (int s = 0; s < press.size(); s++) {
			int b = s * 6;

			if (pressBoxes[b] <= hi[0] + SOFT_EDGE && pressBoxes[b + 3] >= lo[0] - SOFT_EDGE && pressBoxes[b + 1] <= hi[1] + SOFT_EDGE
					&& pressBoxes[b + 4] >= lo[1] - SOFT_EDGE && pressBoxes[b + 2] <= hi[2] + SOFT_EDGE && pressBoxes[b + 5] >= lo[2] - SOFT_EDGE)
				pressLive[pressCount++] = s;
		}

		// a node this far from a body shape where it rests can never reach it, however it swings: it is let go of until the shape moves against the region
		double never = 2 * r.limit(params) + MARGIN;

		// the body, shape by shape, over the nodes near each
		for (int live = 0; live < bodyCount; live++) {
			int s = bodyLive[live];

			for (int listed = 0; listed < r.nearCount[s]; listed++) {
				int i = r.near[s * n + listed];
				int at = i * 3;
				double dx = r.lag[at];
				double dy = r.lag[at + 1];
				double dz = r.lag[at + 2];
				double moved = r.lagLength[i];
				double need = moved + MARGIN;

				// the node is no nearer the shape than its target is to the shape's box, less how far it has moved: if that is already far enough, there is
				// nothing to measure
				double clear = outside(bodyBoxes, s, r.target[at], r.target[at + 1], r.target[at + 2]);

				// the box holds the shape, so a node this far outside it is this far from the shape at least, for as long as the shape stays where it is
				// against the region: let go of
				if (clear > never * never) {
					r.near[s * n + listed] = r.near[s * n + --r.nearCount[s]];
					listed--;

					continue;
				}

				if (clear >= need * need)
					continue;

				float row = r.restDistance[s * n + i];

				if (row != row) {
					// first needed this frame (or since the shape moved): how far the shape is from the node's target, and the way out there
					row = (float)Collider.signedDistance(body.get(s), r.target[at], r.target[at + 1], r.target[at + 2], gradient);

					int g = (s * n + i) * 3;

					r.restDistance[s * n + i] = row;
					r.restGradient[g] = (float)(gradient[0] * side[0] + gradient[1] * side[1] + gradient[2] * side[2]);
					r.restGradient[g + 1] = (float)(gradient[0] * up[0] + gradient[1] * up[1] + gradient[2] * up[2]);
					r.restGradient[g + 2] = (float)(gradient[0] * out[0] + gradient[1] * out[1] + gradient[2] * out[2]);
				}

				if (row > never) {
					r.near[s * n + listed] = r.near[s * n + --r.nearCount[s]];
					listed--;

					continue;
				}

				double atRest = row;
				double allowed = Math.min(atRest, MARGIN);

				if (atRest - moved >= allowed)
					continue;

				// how far it has gone into the shape past where it rests, along the way out at its target: pushed back by what is more than the room it had
				int g = (s * n + i) * 3;
				double gs = r.restGradient[g];
				double gu = r.restGradient[g + 1];
				double go = r.restGradient[g + 2];
				double ox = side[0] * gs + up[0] * gu + out[0] * go;
				double oy = side[1] * gs + up[1] * gu + out[1] * go;
				double oz = side[2] * gs + up[2] * gu + out[2] * go;
				double push = allowed - atRest - (dx * ox + dy * oy + dz * oz);

				if (push > 0) {
					gradient[0] = ox;
					gradient[1] = oy;
					gradient[2] = oz;
					push(r, i, gradient, push);
				}
			}
		}

		double contactBefore = 0;

		for (int i = 0; i < n; i++)
			contactBefore += r.contact[i];

		press(r, params, press, pressBoxes, pressLive, pressCount);
		creep(r);

		for (int i = 0; i < n; i++) {
			if (r.cage.anchor[i] > HELD)
				continue;

			int at = i * 3;
			double floor = floorY + 1.0 / 64.0;

			if (r.target[at + 1] > floor && r.x[at + 1] < floor) {
				r.contact[i] += floor - r.x[at + 1];
				r.x[at + 1] = floor;
			}
		}

		// something pressed it this step (more contact than the body alone gave): a region pressed on does not rest; one settled against its own body does
		double contactAfter = 0;

		for (int i = 0; i < n; i++)
			contactAfter += r.contact[i];

		r.disturbed = contactAfter > contactBefore + 1e-9;
	}

	/**
	 * The shapes near the box {@code lo}..{@code hi} (body and pressing alike) and where each is against the region, in its own frame (see {@link #key}): kept,
	 * if {@code keep}, as they are when the region falls asleep; otherwise whether they are other shapes than those kept, or any has moved more than
	 * {@link #WAKE} (a pressing shape) or {@link #WAKE_BODY} (a part of the body) from where it was kept
	 */
	private static boolean nearShapes(Region r, List<Collider.Placed> body, List<Collider.Placed> press, double[] lo, double[] hi, boolean keep) {
		int count = 0;

		for (int list = 0; list < 2; list++) {
			for (Collider.Placed shape : list == 0 ? body : press) {
				if (!Collider.near(shape, lo, hi))
					continue;

				key(r, shape);

				if (keep) {
					if (r.sleepKeys.length < (count + 1) * KEY)
						r.sleepKeys = Arrays.copyOf(r.sleepKeys, (count + 4) * KEY);

					if (count < r.sleepShapes.size())
						r.sleepShapes.set(count, shape);
					else
						r.sleepShapes.add(shape);

					System.arraycopy(r.key, 0, r.sleepKeys, count * KEY, KEY);
				}
				else if (count >= r.sleepShapes.size() || r.sleepShapes.get(count) != shape) {
					return true;
				}
				else if (!within(r.sleepKeys, count * KEY, r.key, list == 0 ? WAKE_BODY : WAKE)) {
					// a part of the body has moved under it, or something that presses has moved and is now within reach of the flesh: it may press now. One
					// still clear of it (an arm swinging at the side) is only kept where it is now
					if (list == 0 || clearance(r, shape) < SOFT_EDGE + WAKE)
						return true;

					System.arraycopy(r.key, 0, r.sleepKeys, count * KEY, KEY);
				}

				count++;
			}
		}

		if (keep) {
			while (r.sleepShapes.size() > count)
				r.sleepShapes.remove(r.sleepShapes.size() - 1);

			return false;
		}

		return count != r.sleepShapes.size();
	}

	/** How far {@code shape} is from the nearest free node, blocks */
	private static double clearance(Region r, Collider.Placed shape) {
		double nearest = Double.MAX_VALUE;

		for (int i = 0; i < FleshCageGeometry.NODES; i++) {
			if (r.cage.anchor[i] <= HELD)
				nearest = Math.min(nearest, Collider.signedDistance(shape, r.x[i * 3], r.x[i * 3 + 1], r.x[i * 3 + 2], r.gradient));
		}

		return nearest;
	}

	/**
	 * Brings each body shape's row up to date with where it is against the region (see {@link Region#restKey}): a shape that has moved forgets its row and
	 * finds its near nodes again. True if any shape that can reach a node had moved
	 */
	private static boolean refreshRows(Region r, FleshParams params, List<Collider.Placed> body) {
		int n = FleshCageGeometry.NODES;
		boolean moved = false;

		// the same shapes as last time, in the same order: each keeps its row unless it has moved against the region
		boolean same = r.restDistance.length == body.size() * n && r.measured.size() == body.size();

		for (int s = 0; same && s < body.size(); s++)
			same = r.measured.get(s) == body.get(s);

		if (r.restDistance.length != body.size() * n) {
			r.restDistance = new float[body.size() * n];
			r.restGradient = new float[body.size() * n * 3];
			r.restKey = new double[body.size() * KEY];
			r.near = new int[body.size() * n];
			r.nearCount = new int[body.size()];
		}

		// a node never ends a step farther from its target than the limit, nor goes past twice that within one
		double reach = 2 * r.limit(params) + MARGIN;

		// a shape that has moved against the region forgets its row: each node measures it again when it first comes near enough to need it
		for (int s = 0; s < body.size(); s++) {
			key(r, body.get(s));

			if (same && unchanged(r.restKey, s * KEY, r.key))
				continue;

			moved |= r.nearCount.length > s && r.nearCount[s] > 0;
			System.arraycopy(r.key, 0, r.restKey, s * KEY, KEY);
			Arrays.fill(r.restDistance, s * n, (s + 1) * n, Float.NaN);

			// the nodes it could ever reach, by the ball round it (which, unlike its box in the world, does not change as the entity turns)
			Collider.Placed shape = body.get(s);
			double[] ball = r.ball;
			int count = 0;

			Collider.bound(shape, ball);

			for (int i = 0; i < n; i++) {
				if (r.cage.anchor[i] > HELD)
					continue;

				double dx = r.target[i * 3] - ball[0];
				double dy = r.target[i * 3 + 1] - ball[1];
				double dz = r.target[i * 3 + 2] - ball[2];
				double within = ball[3] + reach;

				if (dx * dx + dy * dy + dz * dz < within * within)
					r.near[s * n + count++] = i;
			}

			r.nearCount[s] = count;
			moved |= count > 0;
		}

		r.measured.clear();
		r.measured.addAll(body);
		r.restStale = false;

		return moved;
	}

	/** The region's own frame in the world: up, out (its tip, square to up) and the side across them, unit */
	private static void regionFrame(Region r) {
		double[] up = r.frameUp;
		double[] out = r.frameOut;
		double[] side = r.frameSide;

		System.arraycopy(r.up, 0, up, 0, 3);

		double along = dot(r.tipDirection, up);

		for (int k = 0; k < 3; k++)
			out[k] = r.tipDirection[k] - along * up[k];

		if (!normalise(out))
			perpendicular(up, out);

		side[0] = up[1] * out[2] - up[2] * out[1];
		side[1] = up[2] * out[0] - up[0] * out[2];
		side[2] = up[0] * out[1] - up[1] * out[0];
	}

	/** Where {@code shape} is against the region, into {@link Region#key}: its points and axes from the region's middle in the region's own frame, its size, and the region's own size */
	private static void key(Region r, Collider.Placed shape) {
		double[] k = r.key;
		int c = FleshCageGeometry.CENTRE * 3;
		double cx = r.target[c];
		double cy = r.target[c + 1];
		double cz = r.target[c + 2];

		Arrays.fill(k, 0);
		local(r, shape.p[0] - cx, shape.p[1] - cy, shape.p[2] - cz, k, 0);

		// only what each kind of shape uses: a box has no second point, a capsule no axes
		if (shape.shape == Collider.Shape.CAPSULE || shape.shape == Collider.Shape.MESH)
			local(r, shape.q[0] - cx, shape.q[1] - cy, shape.q[2] - cz, k, 3);

		if (shape.shape == Collider.Shape.BOX || shape.shape == Collider.Shape.MESH) {
			local(r, shape.axes.m00(), shape.axes.m01(), shape.axes.m02(), k, 6);
			local(r, shape.axes.m10(), shape.axes.m11(), shape.axes.m12(), k, 9);
			local(r, shape.axes.m20(), shape.axes.m21(), shape.axes.m22(), k, 12);
		}

		k[15] = shape.half[0];
		k[16] = shape.half[1];
		k[17] = shape.half[2];
		k[18] = shape.radius;

		// the region's own size: a size slider moves every node against every shape
		double dx = r.target[0] - cx;
		double dy = r.target[1] - cy;
		double dz = r.target[2] - cz;

		k[19] = Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	private static void local(Region r, double x, double y, double z, double[] into, int at) {
		into[at] = x * r.frameSide[0] + y * r.frameSide[1] + z * r.frameSide[2];
		into[at + 1] = x * r.frameUp[0] + y * r.frameUp[1] + z * r.frameUp[2];
		into[at + 2] = x * r.frameOut[0] + y * r.frameOut[1] + z * r.frameOut[2];
	}

	private static boolean unchanged(double[] keys, int at, double[] key) {
		return within(keys, at, key, SAME);
	}

	private static boolean within(double[] keys, int at, double[] key, double tolerance) {
		for (int i = 0; i < KEY; i++) {
			if (!(Math.abs(keys[at + i] - key[i]) <= tolerance))
				return false;
		}

		return true;
	}

	/** The box round each of {@code shapes}, six numbers each (low corner, high corner), into {@code into} if it is big enough */
	private static double[] boxes(List<Collider.Placed> shapes, double[] into, double[] lo, double[] hi) {
		double[] boxes = into.length >= shapes.size() * 6 ? into : new double[shapes.size() * 6 + 24];

		for (int s = 0; s < shapes.size(); s++) {
			Arrays.fill(lo, Double.POSITIVE_INFINITY);
			Arrays.fill(hi, Double.NEGATIVE_INFINITY);
			Collider.grow(shapes.get(s), lo, hi);
			System.arraycopy(lo, 0, boxes, s * 6, 3);
			System.arraycopy(hi, 0, boxes, s * 6 + 3, 3);
		}

		return boxes;
	}

	/** How far outside box {@code s} of {@code boxes} the point is, squared; 0 inside */
	private static double outside(double[] boxes, int s, double x, double y, double z) {
		int b = s * 6;
		double dx = Math.max(0, Math.max(boxes[b] - x, x - boxes[b + 3]));
		double dy = Math.max(0, Math.max(boxes[b + 1] - y, y - boxes[b + 4]));
		double dz = Math.max(0, Math.max(boxes[b + 2] - z, z - boxes[b + 5]));

		return dx * dx + dy * dy + dz * dz;
	}

	private static void push(Region r, int i, double[] direction, double amount) {
		r.x[i * 3] += direction[0] * amount;
		r.x[i * 3 + 1] += direction[1] * amount;
		r.x[i * 3 + 2] += direction[2] * amount;
		r.contact[i] += amount;
	}

	/**
	 * What presses on the region pushes its surface nodes in. The shapes count as one solid (a hand of several cubes, a column of blocks): a node is pushed
	 * straight toward the middle of the region until it is clear of all of them, with a soft edge just outside, never more than {@link #PRESS_DEEPEST} of the
	 * way to the middle. Every node goes the same way, toward the middle, so neighbours never go apart and the surface is never torn or turned inside out;
	 * what the cage does, every vertex bound to it does, so a tip goes in with the pouch it sits on. The node is left resting against the shape (its
	 * velocity into it taken away), what was pressed in swells out elsewhere ({@link #bulge}), and the whole region is shoved by the mean of it
	 */
	private static void press(Region r, FleshParams params, List<Collider.Placed> press, double[] boxes, int[] live, int count) {
		Arrays.fill(r.shoveTarget, 0);

		if (count == 0) {
			fadeDent(r, params);

			return;
		}

		int c = FleshCageGeometry.CENTRE * 3;
		double cx = r.x[c];
		double cy = r.x[c + 1];
		double cz = r.x[c + 2];
		double[] gradient = r.gradient;
		double[] way = r.pressWay;
		int pressed = 0;
		// a block the middle is in: what of the flesh is in it goes out of it the way the middle would, all the same way, so it lies flat against the face
		int swallowing = -1;

		for (int l = 0; l < count; l++) {
			if (Collider.signedDistance(press.get(live[l]), cx, cy, cz, r.swallowWay) < 0) {
				swallowing = live[l];

				break;
			}
		}

		double sx = 0;
		double sy = 0;
		double sz = 0;

		for (int i = 0; i < FleshCageGeometry.SURFACE; i++) {
			if (r.cage.anchor[i] > HELD)
				continue;

			int at = i * 3;
			double x = r.x[at];
			double y = r.x[at + 1];
			double z = r.x[at + 2];
			double nearest = depthIn(press, boxes, live, count, x, y, z, gradient, SOFT_EDGE);

			if (nearest >= SOFT_EDGE)
				continue;

			way[0] = cx - x;
			way[1] = cy - y;
			way[2] = cz - z;

			double toMiddle = Math.sqrt(way[0] * way[0] + way[1] * way[1] + way[2] * way[2]);
			double most = PRESS_DEEPEST * toMiddle;

			if (toMiddle < 1e-9)
				continue;

			way[0] /= toMiddle;
			way[1] /= toMiddle;
			way[2] /= toMiddle;

			if (swallowing >= 0 && Collider.signedDistance(press.get(swallowing), x, y, z, gradient) < 0) {
				System.arraycopy(r.swallowWay, 0, way, 0, 3);
				most = FLATTEN_MOST;
			}

			// a soft knee from the edge in to the surface, then as far toward the middle as the shapes reach, each step as far as the deepest of them says it is
			// still inside
			double amount;

			if (nearest >= 0) {
				amount = square(SOFT_EDGE - nearest) / (4 * SOFT_EDGE);
			}
			else {
				double along = 0;

				// as far as the shapes reach that way, but no farther than the node may go anyway
				for (int step = 0; step < PRESS_STEPS && along < most; step++) {
					double inside = -depthIn(press, boxes, live, count, x + way[0] * along, y + way[1] * along, z + way[2] * along, gradient, 0);

					if (inside < 0)
						break;

					along += Math.max(inside, PRESS_LEAST);
				}

				amount = along + SOFT_EDGE * 0.25;
			}

			amount = Math.min(amount, most);

			push(r, i, way, amount);
			r.pressed[i] = true;

			// while it is pressed the dent is what the shape pushes it now; once it is let go the node rests at it, and it fades
			r.dented = true;
			r.dent[at] = way[0] * amount;
			r.dent[at + 1] = way[1] * amount;
			r.dent[at + 2] = way[2] * amount;

			// resting against the shape: none of its velocity goes out into it
			double v = r.lagVelocity[at] * way[0] + r.lagVelocity[at + 1] * way[1] + r.lagVelocity[at + 2] * way[2];

			if (v < 0) {
				r.lagVelocity[at] -= way[0] * v;
				r.lagVelocity[at + 1] -= way[1] * v;
				r.lagVelocity[at + 2] -= way[2] * v;
			}

			pressed++;
			sx += way[0] * amount;
			sy += way[1] * amount;
			sz += way[2] * amount;
		}

		if (pressed == 0) {
			fadeDent(r, params);

			return;
		}

		// the whole region sits shoved by a share of the mean dent while it is held, and swings back when it is let go
		double share = PRESS_CARRY * Math.min(1.0, pressed / (0.12 * FleshCageGeometry.SURFACE)) / pressed;

		r.shoveTarget[0] = sx * share;
		r.shoveTarget[1] = sy * share;
		r.shoveTarget[2] = sz * share;
		fadeDent(r, params);
	}

	/**
	 * A node that something let go of holds on to most of the dent it had, along the way it was pushed, while the dent fades: the cage's own pull back to its
	 * shape would otherwise take it all back at once
	 */
	private static void creep(Region r) {
		if (!r.dented)
			return;

		for (int i = 0; i < FleshCageGeometry.SURFACE; i++) {
			int at = i * 3;
			double length = Math.sqrt(r.dent[at] * r.dent[at] + r.dent[at + 1] * r.dent[at + 1] + r.dent[at + 2] * r.dent[at + 2]);

			if (r.pressed[i] || length < DENT_GONE)
				continue;

			double ux = r.dent[at] / length;
			double uy = r.dent[at + 1] / length;
			double uz = r.dent[at + 2] / length;
			double along = (r.x[at] - r.target[at]) * ux + (r.x[at + 1] - r.target[at + 1]) * uy + (r.x[at + 2] - r.target[at + 2]) * uz;

			if (along < length) {
				double k = (length - along) * DENT_HOLD;

				r.x[at] += ux * k;
				r.x[at + 1] += uy * k;
				r.x[at + 2] += uz * k;
			}
		}
	}

	/** What has been pushed in fades with a time of its own: a firm flesh gives it back in a moment, a meaty one takes a while */
	private static void fadeDent(Region r, FleshParams params) {
		if (!r.dented)
			return;

		double fade = Math.exp(-STEP / (DENT_FADE + DENT_FADE_MEAT * params.meat()));
		double left = 0;

		for (int i = 0; i < r.dent.length; i++) {
			r.dent[i] *= fade;
			left = Math.max(left, Math.abs(r.dent[i]));
		}

		if (left < DENT_GONE) {
			Arrays.fill(r.dent, 0);
			r.dented = false;
		}
	}

	/**
	 * How far {@code (x, y, z)} is outside the solid the live pressing shapes make together (negative inside, as deep as it is in the deepest of them), or
	 * {@code reach} if none is within {@code reach}
	 */
	private static double depthIn(List<Collider.Placed> press, double[] boxes, int[] live, int count, double x, double y, double z, double[] gradient, double reach) {
		double nearest = Math.max(reach, 1e-9);

		for (int l = 0; l < count; l++) {
			int s = live[l];

			if (outside(boxes, s, x, y, z) >= nearest * nearest && nearest > 0)
				continue;

			nearest = Math.min(nearest, Collider.distance(press.get(s), x, y, z));
		}

		return nearest;
	}

	/** The two of a pair: kept on their own sides, squeezed apart when pressed together, and moved together when dressed */
	private static void pair(Region a, Region b, FleshParams params, double h) {
		if (!a.paired || !b.paired)
			return;

		if (a.dressed && b.dressed && params.dressedCouple() > 0) {
			for (int k = 0; k < 3; k++) {
				double mean = (a.lump[k] + b.lump[k]) * 0.5;
				double meanVelocity = (a.lumpVelocity[k] + b.lumpVelocity[k]) * 0.5;

				a.lump[k] += (mean - a.lump[k]) * params.dressedCouple();
				b.lump[k] += (mean - b.lump[k]) * params.dressedCouple();
				a.lumpVelocity[k] += (meanVelocity - a.lumpVelocity[k]) * params.dressedCouple();
				b.lumpVelocity[k] += (meanVelocity - b.lumpVelocity[k]) * params.dressedCouple();
			}
		}

		// how far the lumps have moved into each other past the gap there was between them
		double[] n = a.planeNormal;
		double front = reachPast(a, a.target);
		double back = reachPast(b, b.target);
		double gap = Math.max(0, -front - back);
		double closing = dot(a.lump, n) - dot(b.lump, n);
		double into = closing - gap;

		a.squeeze = 0;
		b.squeeze = 0;

		if (into > 0) {
			double omega = 0.5 * (a.lumpOmega + b.lumpOmega) * Math.sqrt(params.squeezeStiffness());
			double approach = dot(a.lumpVelocity, n) - dot(b.lumpVelocity, n);
			double push = (omega * omega * into + 2 * 0.6 * omega * approach) * h * 0.5;

			for (int k = 0; k < 3; k++) {
				a.lumpVelocity[k] -= n[k] * push;
				b.lumpVelocity[k] += n[k] * push;
			}

			if (into > SQUEEZE_MAX) {
				double back2 = (into - SQUEEZE_MAX) * 0.5;

				for (int k = 0; k < 3; k++) {
					a.lump[k] -= n[k] * back2;
					b.lump[k] += n[k] * back2;
				}
			}

			double squeeze = Math.min(1, into / SQUEEZE_MAX);

			a.squeeze = squeeze;
			b.squeeze = squeeze;
		}

		keepBehind(a);
		keepBehind(b);
	}

	/** Moves the free nodes that have crossed the plane halfway back toward it each step */
	private static void keepBehind(Region r) {
		for (int i = 0; i < FleshCageGeometry.NODES; i++) {
			if (r.cage.anchor[i] > HELD)
				continue;

			int at = i * 3;
			double past = (r.x[at] - r.planePoint[0]) * r.planeNormal[0] + (r.x[at + 1] - r.planePoint[1]) * r.planeNormal[1]
					+ (r.x[at + 2] - r.planePoint[2]) * r.planeNormal[2] - r.planeAllow;

			if (past > 0) {
				double back = past * 0.5;

				r.x[at] -= r.planeNormal[0] * back;
				r.x[at + 1] -= r.planeNormal[1] * back;
				r.x[at + 2] -= r.planeNormal[2] * back;
				r.contact[i] += back;
			}
		}
	}

	/**
	 * What was pressed in swells out where nothing presses: the volume the cage has lost (its shell against where the animation puts it) is given back, by
	 * {@code bulge} of it, along the free nodes' own outward directions, each by its share of the free surface. The cage is a closed shell, so this holds even for
	 * a mesh that is open; the nodes it moves are the ones every vertex follows
	 */
	private static void bulge(Region r, FleshParams params) {
		if (params.bulge() <= 0)
			return;

		boolean pressed = false;

		for (int i = 0; i < FleshCageGeometry.SURFACE && !pressed; i++)
			pressed = r.contact[i] > 0;

		if (!pressed)
			return;

		int c = FleshCageGeometry.CENTRE * 3;
		int[] faces = FleshCageGeometry.get().faces;
		double lost = volume(r.target, faces, c) - volume(r.x, faces, c);

		if (lost <= 0)
			return;

		// each node's share of the surface it is on, from where it is now
		double[] area = r.area;

		Arrays.fill(area, 0);

		for (int f = 0; f < faces.length; f += 3) {
			int a = faces[f] * 3;
			int b = faces[f + 1] * 3;
			int d = faces[f + 2] * 3;
			double ux = r.x[b] - r.x[a];
			double uy = r.x[b + 1] - r.x[a + 1];
			double uz = r.x[b + 2] - r.x[a + 2];
			double vx = r.x[d] - r.x[a];
			double vy = r.x[d + 1] - r.x[a + 1];
			double vz = r.x[d + 2] - r.x[a + 2];
			double cx = uy * vz - uz * vy;
			double cy = uz * vx - ux * vz;
			double cz = ux * vy - uy * vx;
			double third = Math.sqrt(cx * cx + cy * cy + cz * cz) * 0.5 / 3;

			area[faces[f]] += third;
			area[faces[f + 1]] += third;
			area[faces[f + 2]] += third;
		}

		double free = 0;

		for (int i = 0; i < FleshCageGeometry.SURFACE; i++) {
			if (r.cage.anchor[i] <= HELD && r.contact[i] <= 0)
				free += area[i] * (1 - r.cage.anchor[i]);
		}

		if (free <= 1e-12)
			return;

		double amount = Math.min(lost * params.bulge() / free, BULGE_MOST * r.limit(params));

		for (int i = 0; i < FleshCageGeometry.SURFACE; i++) {
			if (r.cage.anchor[i] > HELD || r.contact[i] > 0)
				continue;

			double dx = r.x[i * 3] - r.x[c];
			double dy = r.x[i * 3 + 1] - r.x[c + 1];
			double dz = r.x[i * 3 + 2] - r.x[c + 2];
			double length = Math.sqrt(dx * dx + dy * dy + dz * dz);

			if (length < 1e-9)
				continue;

			double k = amount * (1 - r.cage.anchor[i]) / length;

			r.x[i * 3] += dx * k;
			r.x[i * 3 + 1] += dy * k;
			r.x[i * 3 + 2] += dz * k;
		}
	}

	/** The volume the cage's shell (its triangles, wound outward) holds round node {@code c}, cubic blocks */
	private static double volume(double[] x, int[] faces, int c) {
		double total = 0;

		for (int f = 0; f < faces.length; f += 3) {
			int a = faces[f] * 3;
			int b = faces[f + 1] * 3;
			int d = faces[f + 2] * 3;
			double ax = x[a] - x[c];
			double ay = x[a + 1] - x[c + 1];
			double az = x[a + 2] - x[c + 2];
			double bx = x[b] - x[c];
			double by = x[b + 1] - x[c + 1];
			double bz = x[b + 2] - x[c + 2];
			double dx = x[d] - x[c];
			double dy = x[d + 1] - x[c + 1];
			double dz = x[d + 2] - x[c + 2];

			total += (ax * (by * dz - bz * dy) + ay * (bz * dx - bx * dz) + az * (bx * dy - by * dx)) / 6.0;
		}

		return total;
	}

	/** Each node softly held within the limit (free to half of it, then ever stiffer, never past it), anything not a number put back, and the displacement kept */
	private static void settle(Region r, FleshParams params) {
		double limit = r.limit(params);
		double free = 0.5 * limit;
		double motion = 0;

		for (int i = 0; i < FleshCageGeometry.NODES; i++) {
			int at = i * 3;
			double dx = r.x[at] - r.target[at];
			double dy = r.x[at + 1] - r.target[at + 1];
			double dz = r.x[at + 2] - r.target[at + 2];
			double length = Math.sqrt(dx * dx + dy * dy + dz * dz);

			if (!Double.isFinite(length)) {
				System.arraycopy(r.target, at, r.x, at, 3);
				r.lagVelocity[at] = 0;
				r.lagVelocity[at + 1] = 0;
				r.lagVelocity[at + 2] = 0;
				dx = 0;
				dy = 0;
				dz = 0;
			}
			else if (length > free && !r.pressed[i]) {
				double k = (free + (limit - free) * Math.tanh((length - free) / (limit - free))) / length;

				dx *= k;
				dy *= k;
				dz *= k;
				r.x[at] = r.target[at] + dx;
				r.x[at + 1] = r.target[at + 1] + dy;
				r.x[at + 2] = r.target[at + 2] + dz;
			}

			r.displacement[at] = dx;
			r.displacement[at + 1] = dy;
			r.displacement[at + 2] = dz;

			double mx = dx - r.displacementBefore[at];
			double my = dy - r.displacementBefore[at + 1];
			double mz = dz - r.displacementBefore[at + 2];

			motion = Math.max(motion, mx * mx + my * my + mz * mz);
		}

		for (int k = 0; k < 3; k++)
			motion = Math.max(motion, (r.lumpShown[k] - r.lumpShownBefore[k]) * (r.lumpShown[k] - r.lumpShownBefore[k]));

		r.motion = motion;

		for (int k = 0; k < 3; k++) {
			if (!Double.isFinite(r.lump[k]) || !Double.isFinite(r.lumpVelocity[k])) {
				Arrays.fill(r.lump, 0);
				Arrays.fill(r.lumpVelocity, 0);
				Arrays.fill(r.lumpShown, 0);

				break;
			}
		}
	}

	/** {@code v} scaled so its length is {@code reach * tanh(length / reach)}: the same when small, never past {@code reach} */
	static void softClamp(double[] v, double reach, double[] out) {
		double length = Math.sqrt(dot(v, v));

		if (length < 1e-12 || reach <= 0) {
			System.arraycopy(v, 0, out, 0, 3);

			return;
		}

		double k = reach * Math.tanh(length / reach) / length;

		for (int i = 0; i < 3; i++)
			out[i] = v[i] * k;
	}

	private static boolean normalise(double[] v) {
		double length = Math.sqrt(dot(v, v));

		if (length < 1e-9)
			return false;

		for (int i = 0; i < 3; i++)
			v[i] /= length;

		return true;
	}

	private static void perpendicular(double[] u, double[] out) {
		boolean x = Math.abs(u[0]) < 0.9;

		out[0] = x ? 1 : 0;
		out[1] = 0;
		out[2] = x ? 0 : 1;

		double along = dot(out, u);

		for (int k = 0; k < 3; k++)
			out[k] -= along * u[k];

		normalise(out);
	}

	static double dot(double[] a, double[] b) {
		return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
	}

	private static double square(double v) {
		return v * v;
	}
}
