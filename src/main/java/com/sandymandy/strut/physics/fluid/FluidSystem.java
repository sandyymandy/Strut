package com.sandymandy.strut.physics.fluid;

import com.sandymandy.strut.animation.state.Pose;
import com.sandymandy.strut.animation.state.PosePalette;
import com.sandymandy.strut.cache.object.BakedStrutModel;
import com.sandymandy.strut.cache.object.GroupBoneDefinition;
import java.util.Collections;
import java.util.IdentityHashMap;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import com.sandymandy.strut.physics.PhysicsInstance;
import com.sandymandy.strut.physics.collision.Bodies;
import com.sandymandy.strut.physics.collision.Collider;
import com.sandymandy.strut.physics.collision.ShapeGrid;
import com.sandymandy.strut.physics.config.PhysicsOptions;
import com.sandymandy.strut.physics.solver.Kinematics;
import com.sandymandy.strut.physics.solver.MotionSampler;
import com.sandymandy.strut.physics.world.WorldBlocks;

/**
 * The game side of the thick liquid: one pool of particles per kind of liquid stepped on the game clock, the blocks they land on, the emitters that feed them
 * (a bone of a model, or a point in the world), and the drawing of each pool's surface after the entities
 * <p>
 * Everything runs on the render thread. The solver and the mesh are pure ({@link FluidSolver}, {@link FluidMesh}) and tested without a game
 */
public final class FluidSystem {
	private static final Identifier WHITE = Identifier.of("strut", "textures/fluid/white.png");
	/** The most particles one liquid ever holds; the quality level sets how many are allowed */
	static final int CAPACITY = 800;
	private static final int MAX_CELLS = 12;
	private static final double CELL = 8.0;

	private static final Map<Identifier, FluidSolver> POOLS = new LinkedHashMap<>();
	private static final Map<Identifier, FluidMesh> MESHES = new LinkedHashMap<>();
	private static final List<Active> ACTIVE = new ArrayList<>();
	private static final Random RANDOM = new Random();
	private static final float[] VELOCITY = new float[3];

	private static ClientWorld lastWorld;
	private static double now = Double.NaN;
	private static double simTime = Double.NaN;
	private static long frame;
	private static int lastSteps;
	private static int lastTriangles;

	// the blocks near the cloud
	private static final List<Collider.Placed> SHAPES = new ArrayList<>();
	private static int shapeCount;
	private static final Set<Long> CELLS = new HashSet<>();
	/** The shapes of the entities near the cloud this frame, and all the shapes the particles are tested against this frame (blocks first) */
	private static final List<Collider.Placed> BODIES = new ArrayList<>();
	private static final List<Collider.Placed> ALL = new ArrayList<>();
	private static final Set<Collider.Placed> SEEN = Collections.newSetFromMap(new IdentityHashMap<>());
	private static long cellsHash;
	private static int framesSinceShapes;

	private FluidSystem() {}

	/** An emission under way */
	private static final class Active {
		final StrutFluids.Emission emission;
		/** The entity whose bone it follows, or -1 for a fixed point */
		final int entityId;
		final String bone;
		final double x;
		final double y;
		final double z;
		final double dx;
		final double dy;
		final double dz;
		final double start;
		double lastSeen;
		int spawned;

		Active(StrutFluids.Emission emission, int entityId, String bone, double x, double y, double z, double dx, double dy, double dz, double start) {
			this.emission = emission;
			this.entityId = entityId;
			this.bone = bone;
			this.x = x;
			this.y = y;
			this.z = z;
			this.dx = dx;
			this.dy = dy;
			this.dz = dz;
			this.start = start;
			this.lastSeen = start;
		}

		/** How many of its particles are due by {@code time} */
		int due(double time) {
			int count = this.emission.count();

			if (this.emission.duration() <= 0f)
				return count;

			return (int)Math.min(count, Math.floor((time - this.start) / this.emission.duration() * count) + 1);
		}

		boolean done() {
			return this.spawned >= this.emission.count();
		}
	}

	/** How many particles the quality level allows */
	public static int cap() {
		return switch (PhysicsOptions.quality()) {
			case LOW -> 150;
			case MEDIUM -> 400;
			case HIGH -> CAPACITY;
		};
	}

	static boolean on() {
		return PhysicsOptions.enabled() && PhysicsOptions.fluid();
	}

	private static double clock(MinecraftClient client) {
		return (client.world.getTime() + client.getRenderTickCounter().getTickDelta(false)) / 20.0;
	}

	static void emitFromBone(int entityId, String bone, StrutFluids.Emission emission) {
		MinecraftClient client = MinecraftClient.getInstance();

		if (client.world == null || !on())
			return;

		ACTIVE.add(new Active(emission, entityId, bone, 0, 0, 0, 0, 0, 0, clock(client)));
	}

	static void emitAt(double x, double y, double z, double dx, double dy, double dz, StrutFluids.Emission emission) {
		MinecraftClient client = MinecraftClient.getInstance();

		if (client.world == null || !on())
			return;

		ACTIVE.add(new Active(emission, -1, "", x, y, z, dx, dy, dz, clock(client)));
	}

	public static void clear() {
		POOLS.clear();
		MESHES.clear();
		ACTIVE.clear();
		SHAPES.clear();
		shapeCount = 0;
		CELLS.clear();
		now = Double.NaN;
		simTime = Double.NaN;
	}

	static int particleCount() {
		int total = 0;

		for (FluidSolver pool : POOLS.values())
			total += pool.count;

		return total;
	}

	public static String line() {
		int particles = particleCount();

		return particles == 0 && ACTIVE.isEmpty() ? null : String.format("fluid: %d particles in %d pools, %d emitters, %d steps, %d triangles", particles, POOLS.size(), ACTIVE.size(),
				lastSteps, lastTriangles);
	}

	private static FluidSolver pool(Identifier id) {
		FluidParams params = FluidPresets.get(id);
		FluidSolver pool = POOLS.get(id);

		// the liquid was retuned by a reload: the old particles are of the old liquid
		if (pool == null || pool.params != params) {
			pool = new FluidSolver(params, CAPACITY);
			POOLS.put(id, pool);
		}

		pool.limit(cap());

		return pool;
	}

	// ---------------------------------------------------------------------------------------------
	// emitters
	// ---------------------------------------------------------------------------------------------

	/** Called for each model drawn this frame, with the pose it was animated to: starts the particles of the emissions that follow one of its bones */
	static void processEntity(int entityId, BakedStrutModel model, Pose animated) {
		if (ACTIVE.isEmpty() || entityId < 0 || !on())
			return;

		PhysicsInstance.Sample sample = null;
		Matrix4f matrix = null;

		for (Active active : ACTIVE) {
			if (active.entityId != entityId || active.done())
				continue;

			if (sample == null) {
				sample = MotionSampler.sample(entityId);

				if (sample == null)
					return;
			}

			Optional<GroupBoneDefinition> bone = model.getBone(active.bone);

			if (bone.isEmpty())
				continue;

			if (matrix == null)
				matrix = new Matrix4f();

			PosePalette.computeBoneAtPivot(animated, bone.get(), matrix);
			active.lastSeen = now;

			StrutFluids.Emission e = active.emission;
			float[] offset = e.offset();
			float[] direction = e.direction();
			Vector3f at = new Vector3f(offset == null ? 0 : offset[0], offset == null ? 0 : offset[1], offset == null ? 0 : offset[2]);
			Vector3f way = new Vector3f(direction == null ? 0 : direction[0], direction == null ? 0 : direction[1], direction == null ? -1 : direction[2]);

			matrix.transformPosition(at);
			matrix.transformDirection(way);
			Kinematics.modelToWorld(at, sample.yawDegrees());
			Kinematics.modelToWorld(way, sample.yawDegrees());

			Entity entity = MinecraftClient.getInstance().world.getEntityById(entityId);
			Vec3d carried = entity == null ? Vec3d.ZERO : entity.getVelocity().multiply(20.0 * e.inherit());

			spawn(active, sample.x() + at.x, sample.y() + at.y, sample.z() + at.z, way.x, way.y, way.z, carried);
		}
	}

	private static void spawn(Active active, double x, double y, double z, double dx, double dy, double dz, Vec3d carried) {
		StrutFluids.Emission e = active.emission;
		int due = active.due(now);
		FluidSolver pool = pool(e.preset());

		while (active.spawned < due) {
			StrutFluids.coneVelocity(RANDOM, dx, dy, dz, e.speed(), e.spread(), e.jitter(), VELOCITY);

			// a hair of scatter, so a burst is not many particles on one point
			pool.spawn(x + (RANDOM.nextDouble() - 0.5) * 0.02, y + (RANDOM.nextDouble() - 0.5) * 0.02, z + (RANDOM.nextDouble() - 0.5) * 0.02,
					VELOCITY[0] + (float)carried.x, VELOCITY[1] + (float)carried.y, VELOCITY[2] + (float)carried.z, 0f, active.entityId);
			active.spawned++;
		}
	}

	// ---------------------------------------------------------------------------------------------
	// the step
	// ---------------------------------------------------------------------------------------------

	/** Called once at the start of each world render */
	public static void update() {
		MinecraftClient client = MinecraftClient.getInstance();

		if (client == null || client.world == null) {
			if (lastWorld != null || !POOLS.isEmpty())
				clear();

			lastWorld = null;

			return;
		}

		if (client.world != lastWorld) {
			clear();
			lastWorld = client.world;
		}

		frame++;

		if (!on()) {
			if (!POOLS.isEmpty() || !ACTIVE.isEmpty())
				clear();

			return;
		}

		now = clock(client);

		if (client.isPaused())
			return;

		if (Double.isNaN(simTime) || now < simTime || now - simTime > 0.5)
			simTime = now;

		// emitters at a point start here; those on a bone start as their entity is drawn. Those that have finished, or whose entity is not being drawn, go
		for (int i = ACTIVE.size() - 1; i >= 0; i--) {
			Active a = ACTIVE.get(i);

			if (a.entityId < 0 && !a.done())
				spawn(a, a.x, a.y, a.z, a.dx, a.dy, a.dz, Vec3d.ZERO);

			if (a.done() || (a.entityId >= 0 && now - a.lastSeen > 1.5))
				ACTIVE.remove(i);
		}

		int particles = particleCount();

		lastSteps = 0;

		if (particles == 0)
			return;

		int steps = Math.min((int)((now - simTime) / FluidSolver.STEP + 1e-9), PhysicsOptions.maxSteps());

		// the bodies of the entities around are wanted from now on: they publish their shapes while this runs
		Bodies.demand();

		if (steps > 0) {
			gatherShapes(client);
			gatherBodies();

			int iterations = PhysicsOptions.iterations(3);

			ALL.clear();
			ALL.addAll(SHAPES.subList(0, shapeCount));
			ALL.addAll(BODIES);

			ShapeGrid grid = ShapeGrid.of(ALL, FluidSolver.GRID_MARGIN);

			for (int s = 0; s < steps; s++) {
				for (FluidSolver pool : POOLS.values())
					pool.step(FluidSolver.STEP, grid, iterations);
			}

			lastSteps = steps;
			simTime += steps * (double)FluidSolver.STEP;
		}

		// behind by more than a burst allows: drop the time rather than simulate a hitch
		if (now - simTime > PhysicsOptions.maxSteps() * (double)FluidSolver.STEP)
			simTime = now - FluidSolver.STEP;
	}

	/** The shapes of the entities (models' real shapes, mobs' and players' hitboxes) around each cell that holds a particle */
	private static void gatherBodies() {
		BODIES.clear();
		SEEN.clear();

		if (!PhysicsOptions.otherCollision() && !PhysicsOptions.playerCollision())
			return;

		List<Collider.Placed> found = new ArrayList<>();

		for (long key : CELLS) {
			long cx = (key & 0x1FFFFF);
			long cy = (key >> 21) & 0x1FFFFF;
			long cz = (key >> 42) & 0x1FFFFF;

			cx = (cx << 43) >> 43;
			cy = (cy << 43) >> 43;
			cz = (cz << 43) >> 43;

			found.clear();
			// from the middle of the cell, far enough to reach the centre of any shape whose bounds touch it: half the cell's diagonal and the biggest shape's radius
			Bodies.nearby(-1, (cx + 0.5) * CELL, (cy + 0.5) * CELL, (cz + 0.5) * CELL, CELL * 0.87 + 3.0, PhysicsOptions.otherCollision(), PhysicsOptions.playerCollision(), found);

			for (Collider.Placed shape : found) {
				if (SEEN.add(shape))
					BODIES.add(shape);
			}
		}
	}

	/** The blocks around every 8-block cell that holds a particle, kept for a few frames while the set of cells does not change */
	private static void gatherShapes(MinecraftClient client) {
		CELLS.clear();

		long hash = 17;

		for (FluidSolver pool : POOLS.values()) {
			for (int i = 0; i < pool.count && CELLS.size() < MAX_CELLS; i++) {
				long cx = (long)Math.floor(pool.x[i] / CELL);
				long cy = (long)Math.floor(pool.y[i] / CELL);
				long cz = (long)Math.floor(pool.z[i] / CELL);
				long key = (cx & 0x1FFFFF) | ((cy & 0x1FFFFF) << 21) | ((cz & 0x1FFFFF) << 42);

				if (CELLS.add(key))
					hash = hash * 31 + key;
			}
		}

		if (hash == cellsHash && ++framesSinceShapes < 4 && shapeCount > 0)
			return;

		cellsHash = hash;
		framesSinceShapes = 0;
		shapeCount = 0;

		int limit = PhysicsOptions.maxWorldBoxes();

		for (long key : CELLS) {
			long cx = (key & 0x1FFFFF);
			long cy = (key >> 21) & 0x1FFFFF;
			long cz = (key >> 42) & 0x1FFFFF;

			// back from the 21 bit fields to signed cells
			cx = (cx << 43) >> 43;
			cy = (cy << 43) >> 43;
			cz = (cz << 43) >> 43;

			double x0 = cx * CELL - 1;
			double y0 = cy * CELL - 1;
			double z0 = cz * CELL - 1;

			WorldBlocks.INSTANCE.boxes(x0, y0, z0, x0 + CELL + 2, y0 + CELL + 2, z0 + CELL + 2, (bx0, by0, bz0, bx1, by1, bz1) -> {
				if (shapeCount >= limit)
					return;

				while (SHAPES.size() <= shapeCount)
					SHAPES.add(new Collider.Placed());

				Collider.Placed box = SHAPES.get(shapeCount++);

				box.shape = Collider.Shape.BOX;
				box.layer = "world";
				box.p[0] = (bx0 + bx1) * 0.5;
				box.p[1] = (by0 + by1) * 0.5;
				box.p[2] = (bz0 + bz1) * 0.5;
				box.half[0] = (bx1 - bx0) * 0.5;
				box.half[1] = (by1 - by0) * 0.5;
				box.half[2] = (bz1 - bz0) * 0.5;
				box.axes.identity();
			});
		}
	}

	// ---------------------------------------------------------------------------------------------
	// drawing
	// ---------------------------------------------------------------------------------------------

	/** Called after the entities are drawn: builds each pool's surface and draws it */
	public static void draw(WorldRenderContext context) {
		if (!on() || context.matrixStack() == null || context.consumers() == null || POOLS.isEmpty())
			return;

		MinecraftClient client = MinecraftClient.getInstance();

		if (client.world == null)
			return;

		VertexConsumerProvider consumers = context.consumers();
		VertexConsumer buffer = null;
		MatrixStack.Entry entry = context.matrixStack().peek();
		Matrix4f matrix = entry.getPositionMatrix();
		Vec3d camera = context.camera().getPos();
		int triangles = 0;
		int budget = 24_000;

		for (Map.Entry<Identifier, FluidSolver> pair : POOLS.entrySet()) {
			FluidSolver pool = pair.getValue();
			FluidMesh mesh = MESHES.computeIfAbsent(pair.getKey(), k -> new FluidMesh());

			if (pool.count == 0)
				continue;

			// a big cloud is cut every other frame: the surface is a frame late, a thing nobody sees in a splash
			if (pool.count <= 300 || frame % 2 == 0 || mesh.isEmpty())
				mesh.build(pool, budget);

			if (mesh.isEmpty())
				continue;

			if (buffer == null)
				buffer = consumers.getBuffer(RenderLayer.getEntityTranslucent(WHITE));

			int color = pool.params.color();
			float a = ((color >>> 24) & 0xFF) / 255f;
			float r = ((color >> 16) & 0xFF) / 255f;
			float g = ((color >> 8) & 0xFF) / 255f;
			float b = (color & 0xFF) / 255f;
			double ox = mesh.originX - camera.x;
			double oy = mesh.originY - camera.y;
			double oz = mesh.originZ - camera.z;
			BlockPos.Mutable pos = new BlockPos.Mutable();
			int light = 0xF000F0;
			long lightAt = Long.MIN_VALUE;

			// the layer draws quads: a triangle is a quad whose last corner is repeated
			for (int t = 0; t < mesh.triangleCount; t++) {
				for (int corner = 0; corner < 4; corner++) {
					int v = mesh.indices[t * 3 + Math.min(corner, 2)];
					float px = mesh.positions[v * 3];
					float py = mesh.positions[v * 3 + 1];
					float pz = mesh.positions[v * 3 + 2];
					long block = BlockPos.asLong((int)Math.floor(mesh.originX + px), (int)Math.floor(mesh.originY + py), (int)Math.floor(mesh.originZ + pz));

					if (block != lightAt) {
						lightAt = block;
						pos.set(block);
						light = WorldRenderer.getLightmapCoordinates(client.world, pos);
					}

					buffer.vertex(matrix, (float)(ox + px), (float)(oy + py), (float)(oz + pz)).color(r, g, b, a).texture(0.5f, 0.5f).overlay(OverlayTexture.DEFAULT_UV)
							.light(light).normal(entry, mesh.normals[v * 3], mesh.normals[v * 3 + 1], mesh.normals[v * 3 + 2]);
				}
			}

			triangles += mesh.triangleCount;
			budget = Math.max(1000, budget - mesh.triangleCount);
		}

		lastTriangles = triangles;
	}
}
