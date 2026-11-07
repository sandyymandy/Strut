package com.sandymandy.strut.physics.collision;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The solid things entities put into the world for each other's soft parts to press on: the colliders of every Strut
 * model with physics, and a capsule for every other living entity's hitbox. Each frame every entity publishes its shapes in world space, and an instance reads
 * the ones near it (never its own) before it steps
 * <p>
 * The layers a chain or a soft bone lists in {@code collidesWith} choose which of these it feels: {@code other} is the {@code body} colliders of other models,
 * {@code players} the hitboxes of players, {@code mobs} the hitboxes of everything else alive. Visual only: nothing here moves an entity
 * <p>
 * Read and written on the render thread; the map is concurrent only so a stray read from another thread cannot break it
 */
public final class Bodies {
	/** The layer names a soft part lists to feel other entities */
	public static final String OTHER = "other";
	public static final String PLAYERS = "players";
	public static final String MOBS = "mobs";

	/** One entity's published shapes, valid for the frame it was published in */
	private static final class Entry {
		final List<Collider.Placed> shapes = new ArrayList<>();
		/** The shapes made for this entity before, reused rather than made again every frame */
		final List<Collider.Placed> spare = new ArrayList<>();
		/** The entity these shapes belong to */
		final int id;
		long frame;
		/** Whether these are real colliders of a Strut model (which then replace the entity's hitbox capsule) */
		boolean fromModel;
		/** The box round the centres of {@link #shapes}: a point farther from it than a search reaches can be near none of them */
		double minX, minY, minZ, maxX, maxY, maxZ;
		/** The box round all of every shape, as {@link Collider#near} measures them: low corner then high */
		final double[] lo = new double[3];
		final double[] hi = new double[3];

		Entry(int id) {
			this.id = id;
		}

		void clearShapes() {
			// one at a time: addAll would copy the list into a new array first
			for (int i = 0, n = this.shapes.size(); i < n; i++)
				this.spare.add(this.shapes.get(i));

			this.shapes.clear();
		}

		/** A shape to fill, as a new one would be: every field at its default */
		Collider.Placed take() {
			if (this.spare.isEmpty())
				return new Collider.Placed();

			Collider.Placed shape = this.spare.removeLast();

			shape.shape = null;
			shape.layer = null;
			shape.sdf = null;
			shape.owner = -1;
			shape.radius = 0;
			shape.generated = false;
			Arrays.fill(shape.p, 0);
			Arrays.fill(shape.q, 0);
			Arrays.fill(shape.half, 0);
			shape.axes.identity();

			return shape;
		}

		void bound() {
			this.minX = this.minY = this.minZ = Double.POSITIVE_INFINITY;
			this.maxX = this.maxY = this.maxZ = Double.NEGATIVE_INFINITY;

			Arrays.fill(this.lo, Double.POSITIVE_INFINITY);
			Arrays.fill(this.hi, Double.NEGATIVE_INFINITY);

			for (Collider.Placed shape : this.shapes) {
				Collider.grow(shape, this.lo, this.hi);
				this.minX = Math.min(this.minX, shape.p[0]);
				this.minY = Math.min(this.minY, shape.p[1]);
				this.minZ = Math.min(this.minZ, shape.p[2]);
				this.maxX = Math.max(this.maxX, shape.p[0]);
				this.maxY = Math.max(this.maxY, shape.p[1]);
				this.maxZ = Math.max(this.maxZ, shape.p[2]);
			}
		}
	}

	private static final Map<Integer, Entry> ENTRIES = new ConcurrentHashMap<>();
	private static volatile long frame;
	/** The last frame something asked for the shapes of the entities around it: models only make theirs while someone is listening ({@link BodyPublisher}) */
	private static volatile long demandedFrame = Long.MIN_VALUE;

	private Bodies() {}

	/** Starts a frame: entries older than the one before are ignored and dropped */
	public static void nextFrame() {
		frame++;
		ENTRIES.values().removeIf(e -> e.frame < frame - 2);
	}

	public static void clear() {
		ENTRIES.clear();
	}

	/**
	 * Publishes a model's {@code body} colliders (world space) as the shapes of entity {@code id}, replacing its hitbox capsule. The shapes are copied, so the
	 * caller may reuse its own. A model with no body collider publishes nothing, and its hitbox stays
	 */
	public static void publishModel(int id, List<Collider.Placed> shapes) {
		boolean any = false;

		for (Collider.Placed s : shapes)
			any |= isBody(s.layer);

		if (!any)
			return;

		Entry entry = entry(id);

		entry.fromModel = true;
		entry.clearShapes();

		for (Collider.Placed s : shapes) {
			if (isBody(s.layer)) {
				Collider.Placed copy = copy(s, s.layer, entry.take());

				copy.owner = id;
				entry.shapes.add(copy);
			}
		}

		entry.bound();
	}

	/** Whether a part listing {@code layers} in its {@code collidesWith} feels {@code shape}: a model's body colliders are {@code other}, a hitbox is its own layer */
	public static boolean feels(Set<String> layers, Collider.Placed shape) {
		return layers.contains(isBody(shape.layer) ? OTHER : shape.layer);
	}

	/**
	 * {@link #feels(Set, Collider.Placed)} with whether {@code layers} holds {@link #OTHER} already looked up, for a part that asks of many shapes in a row: a model's
	 * body colliders, most of what there is near a crowd, are then answered without a look in the set
	 */
	public static boolean feels(Set<String> layers, boolean feelsOther, Collider.Placed shape) {
		return isBody(shape.layer) ? feelsOther : layers.contains(shape.layer);
	}

	/**
	 * The layers a model shows to others: {@code body} (torso, head) and {@code limb} (arms, legs). Its own chains list {@code body} only, so a limb collider
	 * presses on other models without pushing on its own model's chains
	 */
	static boolean isBody(String layer) {
		return "body".equals(layer) || "limb".equals(layer);
	}

	/**
	 * Publishes a hitbox as a vertical capsule for entity {@code id}, unless a model already published real colliders for it this frame
	 *
	 * @param layer {@link #PLAYERS} or {@link #MOBS}
	 * @param x The centre of the feet, and {@code height} and {@code radius} of the hitbox, in blocks
	 */
	static void publishHitbox(int id, String layer, double x, double y, double z, double height, double radius) {
		Entry entry = entry(id);

		if (entry.fromModel)
			return;

		entry.fromModel = false;
		entry.clearShapes();

		Collider.Placed s = entry.take();
		double r = Math.min(radius, height * 0.5);

		s.shape = Collider.Shape.CAPSULE;
		s.owner = id;
		s.layer = layer;
		s.radius = r;
		s.p[0] = x;
		s.p[1] = y + r;
		s.p[2] = z;
		s.q[0] = x;
		s.q[1] = y + Math.max(r, height - r);
		s.q[2] = z;
		entry.shapes.add(s);
		entry.bound();
	}

	private static Entry entry(int id) {
		Entry entry = ENTRIES.computeIfAbsent(id, Entry::new);

		if (entry.frame != frame) {
			entry.frame = frame;
			entry.fromModel = false;
		}

		return entry;
	}

	/**
	 * Adds to {@code out} the shapes of other entities near {@code (x, y, z)}: models' body colliders and mobs' hitboxes if {@code others}, players' hitboxes if
	 * {@code players}. Entity {@code self} is left out. Each part then keeps the ones it {@linkplain #feels feels}
	 *
	 * @param reach How far from the point a shape may be to count, in blocks (a shape is kept if its centre is within this)
	 */
	public static void nearby(int self, double x, double y, double z, double reach, boolean others, boolean players, List<Collider.Placed> out) {
		nearby(self, x, y, z, reach, others, players, out, null);
	}

	/**
	 * {@link #nearby(int, double, double, double, double, boolean, boolean, List)}, also noting in {@code groups} (if not null, and cleared first) where each
	 * entity's shapes start and end in {@code out} and the box round that entity
	 */
	public static void nearby(int self, double x, double y, double z, double reach, boolean others, boolean players, List<Collider.Placed> out, @Nullable Groups groups) {
		if (groups != null)
			groups.count = 0;

		if (!others && !players)
			return;

		demandedFrame = frame;

		double limit = reach * reach;

		// by the values: walking the entries makes a new map entry object for each one
		for (Entry entry : ENTRIES.values()) {
			if (entry.id == self || entry.frame < frame - 1)
				continue;

			// every shape is kept by where its centre is: one entity whose centres are all farther than the reach has none to give
			double ox = Math.max(0, Math.max(entry.minX - x, x - entry.maxX));
			double oy = Math.max(0, Math.max(entry.minY - y, y - entry.maxY));
			double oz = Math.max(0, Math.max(entry.minZ - z, z - entry.maxZ));

			if (ox * ox + oy * oy + oz * oz > limit)
				continue;

			int start = out.size();

			// by index: a for-each makes an iterator for every entity every time a part asks
			for (int i = 0, n = entry.shapes.size(); i < n; i++) {
				Collider.Placed shape = entry.shapes.get(i);

				if (PLAYERS.equals(shape.layer) ? !players : !others)
					continue;

				double dx = shape.p[0] - x;
				double dy = shape.p[1] - y;
				double dz = shape.p[2] - z;

				if (dx * dx + dy * dy + dz * dz <= limit)
					out.add(shape);
			}

			if (groups != null && out.size() > start)
				groups.add(start, out.size(), entry.lo, entry.hi);
		}
	}

	/**
	 * The shapes {@link #nearby} found, in runs of one entity each: where each run starts and ends, and the box round that entity. A part tests a run's box
	 * before any of its shapes, so a crowd costs it a test per neighbour rather than one per neighbour's shape
	 */
	public static final class Groups {
		private int count;
		private int[] range = new int[32];
		private double[] box = new double[16 * 6];

		public int count() {
			return this.count;
		}

		public int start(int group) {
			return this.range[group * 2];
		}

		public int end(int group) {
			return this.range[group * 2 + 1];
		}

		/** Whether anything of group {@code group} could be inside the box {@code lo}..{@code hi} */
		public boolean touches(int group, double[] lo, double[] hi) {
			int b = group * 6;

			return this.box[b + 3] >= lo[0] && this.box[b] <= hi[0] && this.box[b + 4] >= lo[1] && this.box[b + 1] <= hi[1] && this.box[b + 5] >= lo[2]
					&& this.box[b + 2] <= hi[2];
		}

		public void clear() {
			this.count = 0;
		}

		void add(int start, int end, double[] lo, double[] hi) {
			if (this.count * 2 == this.range.length) {
				this.range = Arrays.copyOf(this.range, this.range.length * 2);
				this.box = Arrays.copyOf(this.box, this.box.length * 2);
			}

			this.range[this.count * 2] = start;
			this.range[this.count * 2 + 1] = end;
			System.arraycopy(lo, 0, this.box, this.count * 6, 3);
			System.arraycopy(hi, 0, this.box, this.count * 6 + 3, 3);
			this.count++;
		}
	}

	/** Whether anything has looked for the shapes of other entities this frame or the one before */
	static boolean demanded() {
		return demandedFrame >= frame - 1;
	}

	/** Says that the shapes of the entities around are wanted, though nothing has asked for them yet (the fluid, which gathers them once a frame) */
	public static void demand() {
		demandedFrame = frame;
	}

	/** Whether a model has published real colliders for entity {@code id} this frame already */
	static boolean publishedThisFrame(int id) {
		Entry entry = ENTRIES.get(id);

		return entry != null && entry.fromModel && entry.frame == frame;
	}

	/** How many entities have published shapes, for the debug line and tests */
	static int published() {
		return ENTRIES.size();
	}

	private static Collider.Placed copy(Collider.Placed from, String layer) {
		return copy(from, layer, new Collider.Placed());
	}

	/** {@code from} copied into {@code to}, every field of it */
	private static Collider.Placed copy(Collider.Placed from, String layer, Collider.Placed to) {
		to.shape = from.shape;
		to.layer = layer;
		to.sdf = from.sdf;
		to.owner = from.owner;
		to.radius = from.radius;
		System.arraycopy(from.p, 0, to.p, 0, 3);
		System.arraycopy(from.q, 0, to.q, 0, 3);
		System.arraycopy(from.half, 0, to.half, 0, 3);
		to.axes.set(from.axes);

		return to;
	}
}
