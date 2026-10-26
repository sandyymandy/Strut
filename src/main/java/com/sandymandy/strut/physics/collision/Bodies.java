package com.sandymandy.strut.physics.collision;

import java.util.ArrayList;
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
		long frame;
		/** Whether these are real colliders of a Strut model (which then replace the entity's hitbox capsule) */
		boolean fromModel;
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
		entry.shapes.clear();

		for (Collider.Placed s : shapes) {
			if (isBody(s.layer)) {
				Collider.Placed copy = copy(s, s.layer);

				copy.owner = id;
				entry.shapes.add(copy);
			}
		}
	}

	/** Whether a part listing {@code layers} in its {@code collidesWith} feels {@code shape}: a model's body colliders are {@code other}, a hitbox is its own layer */
	public static boolean feels(Set<String> layers, Collider.Placed shape) {
		return layers.contains(isBody(shape.layer) ? OTHER : shape.layer);
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
		entry.shapes.clear();

		Collider.Placed s = new Collider.Placed();
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
	}

	private static Entry entry(int id) {
		Entry entry = ENTRIES.computeIfAbsent(id, k -> new Entry());

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
		if (!others && !players)
			return;

		demandedFrame = frame;

		double limit = reach * reach;

		for (Map.Entry<Integer, Entry> e : ENTRIES.entrySet()) {
			Entry entry = e.getValue();

			if (e.getKey() == self || entry.frame < frame - 1)
				continue;

			for (Collider.Placed shape : entry.shapes) {
				if (PLAYERS.equals(shape.layer) ? !players : !others)
					continue;

				double dx = shape.p[0] - x;
				double dy = shape.p[1] - y;
				double dz = shape.p[2] - z;

				if (dx * dx + dy * dy + dz * dz <= limit)
					out.add(shape);
			}
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
		Collider.Placed to = new Collider.Placed();

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
