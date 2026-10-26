package com.sandymandy.strut.physics.solver;

import com.sandymandy.strut.animation.state.MeshDeform;
import com.sandymandy.strut.animation.state.Pose;
import com.sandymandy.strut.animation.state.ParticleDeform;
import com.sandymandy.strut.animation.state.PosePalette;
import com.sandymandy.strut.animation.state.RigidDeform;
import com.sandymandy.strut.cache.object.MeshWeld;
import com.sandymandy.strut.cache.object.BakedStrutModel;
import com.sandymandy.strut.cache.object.GroupBoneDefinition;
import com.sandymandy.strut.cache.object.StrutMesh;
import com.sandymandy.strut.cache.object.StrutPoly;
import com.sandymandy.strut.cache.object.StrutVertex;
import java.util.Arrays;
import java.util.Comparator;
import java.util.function.BiConsumer;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import com.sandymandy.strut.physics.collision.Bodies;
import com.sandymandy.strut.physics.collision.Collider;
import com.sandymandy.strut.physics.collision.ColliderRig;
import com.sandymandy.strut.physics.tools.PhysicsControls;

/**
 * One cloth on one model instance: the vertices of one mesh on a bone, welded into particles, read from the animation, run through {@link ClothSolver}, and
 * handed back as a per-corner offset for the renderer to add to the mesh
 * <p>
 * The cloth is simulated in world space, like a chain. What it gives back is each particle's displacement from where the animation put it at the last step, in
 * the model's space, turned into the mesh's own space by the bone's current matrix: a part sewn to the animation has no displacement at all, and the body moving
 * between two steps carries the whole cloth with it, which is what keeps a skirt from jolting when its wearer walks
 * <p>
 * A mesh bound to an armature, or turned on its own pivot, is not simulated yet: it is drawn as modelled and the config's warning says why
 */
public final class ClothInstance {
	public final String name;
	public final GroupBoneDefinition bone;
	final StrutMesh mesh;
	private final ClothParams configured;
	public ClothParams active;
	final int n;
	public final ClothSolver.Topology topology;
	public final ClothSolver.State state;
	/** Each particle's rest position in the mesh's space (the bone's space its vertices are given in), blocks */
	private final double[][] rest;
	/** The particle each poly corner is drawn at: {@code polys * 4} entries */
	private final int[] cornerParticle;
	/** What the renderer adds to each corner, for each particle and in the mesh's space: valid after {@link #computeOutput}. Read by the GPU as it is */
	private final ParticleDeform deform;
	private final double[][] animWorld;
	private final double[][] animModel;
	private final double[][] stepAnimModel;
	private final double[] delta = new double[3];
	private final Matrix4f matrix = new Matrix4f();
	private final Matrix3f inverse = new Matrix3f();
	private boolean inverseOk;
	private final Vector3f scratch = new Vector3f();
	private final List<Collider.Placed> placed = new ArrayList<>();
	private final List<Collider.Placed> shapes = new ArrayList<>();
	private final double[] lo = new double[3];
	private final double[] hi = new double[3];
	/** How far the cloth reaches from the model's origin, blocks, for choosing which blocks to read */
	private final double extent;
	/** Meshes on other bones that ride on this cloth, see {@link ClothParams#carry()} */
	private final List<Carried> carried = new ArrayList<>();
	private final float[] perParticle;
	/** Where each particle is now, in the model's space */
	private final double[][] curModel;
	/** The cloth's own bone matrix, as {@link #prepare} left it, for turning normals */
	private final Matrix3f rotation = new Matrix3f();
	private final float[] faceSum;
	private final int[] faceCount;
	private final double[] faceScratchA = new double[3];
	private final double[] faceScratchB = new double[3];
	/** The model's own generated shapes this cloth must not collide with, set once the rig exists */
	public boolean[] skip;
	private boolean hasOutput;

	private static final class Carried {
		final GroupBoneDefinition bone;
		final StrutMesh mesh;
		/** The particles around the mesh, whose movement it follows as one piece: it keeps its own shape */
		final int[] fit;
		/** How this mesh moves as one piece: twelve numbers and nine, whatever its size */
		final RigidDeform deform;
		final Matrix4f matrix = new Matrix4f();
		final Matrix3f inverse = new Matrix3f();
		boolean inverseOk;
		/** Scratch for working out the motion, so a frame allocates nothing */
		final RigidMotion motion = new RigidMotion();

		Carried(GroupBoneDefinition bone, StrutMesh mesh, int[] fit) {
			this.bone = bone;
			this.mesh = mesh;
			this.fit = fit;
			this.deform = new RigidDeform(mesh);
		}
	}

	private ClothInstance(String name, GroupBoneDefinition bone, StrutMesh mesh, ClothParams params, double[][] rest, int[] cornerParticle, int[] triangles) {
		this.name = name;
		this.bone = bone;
		this.mesh = mesh;
		this.configured = params;
		this.active = params;
		this.n = rest.length;
		this.rest = rest;
		this.cornerParticle = cornerParticle;
		this.topology = ClothSolver.Topology.build(rest, triangles);
		this.state = new ClothSolver.State(this.n);
		this.deform = new ParticleDeform(mesh, new MeshWeld(cornerParticle, rest));
		this.animWorld = new double[this.n][3];
		this.animModel = new double[this.n][3];
		this.stepAnimModel = new double[this.n][3];
		this.perParticle = new float[this.n * 3];
		this.curModel = new double[this.n][3];
		this.faceSum = new float[this.n * 3];
		this.faceCount = new int[this.n];

		double minY = Double.MAX_VALUE;
		double maxY = -Double.MAX_VALUE;
		double reach = 0;

		for (double[] r : rest) {
			minY = Math.min(minY, r[1]);
			maxY = Math.max(maxY, r[1]);
			reach = Math.max(reach, Math.sqrt(r[0] * r[0] + r[1] * r[1] + r[2] * r[2]));
		}

		this.extent = reach;

		// the part of the mesh outside the soft zone is sewn to the bone, blending to soft toward its middle
		if (params.softRadius() > 0f) {
			float[] c = params.softCenter() != null ? params.softCenter() : new float[] {0f, 0f, 0f};

			for (int k = 0; k < this.n; k++) {
				double d = Math.sqrt(Math.pow(rest[k][0] - c[0], 2) + Math.pow(rest[k][1] - c[1], 2) + Math.pow(rest[k][2] - c[2], 2));
				double t = Math.min(1.0, d / params.softRadius());

				this.state.pin[k] = (float)(t * t * (3 - 2 * t));
			}
		}

		// the top of the mesh, as far down as pinHeight says, is sewn to the bone
		if (params.pinHeight() > 0f) {
			double threshold = maxY - params.pinHeight() * (maxY - minY);

			for (int k = 0; k < this.n; k++)
				this.state.pin[k] = rest[k][1] >= threshold - 1e-6 ? 1f : 0f;
		}
	}

	/**
	 * Builds the cloths for {@code params}: one per mesh of the bone that can be simulated. Says in {@code problems} what it could not do and why (a bone the
	 * model lacks, a mesh bound to an armature, too many vertices) and returns what it could
	 */
	public static List<ClothInstance> create(String name, ClothParams params, BakedStrutModel model, List<String> problems) {
		List<ClothInstance> made = new ArrayList<>();
		Optional<GroupBoneDefinition> bone = model.getBone(params.bone());

		if (bone.isEmpty()) {
			problems.add("cloth " + name + ": no bone " + params.bone());

			return made;
		}

		List<StrutMesh> meshes = bone.get().getMeshes();

		if (meshes.isEmpty()) {
			problems.add("cloth " + name + ": bone " + params.bone() + " has no mesh; cloth is made from a mesh's vertices, not from cubes");

			return made;
		}

		for (int m = 0; m < meshes.size(); m++) {
			if (params.mesh() >= 0 && params.mesh() != m)
				continue;

			StrutMesh mesh = meshes.get(m);
			String where = "cloth " + name + (meshes.size() > 1 ? " (mesh " + m + ")" : "");

			if (mesh.hidden())
				continue;

			if (mesh.isSkinned()) {
				problems.add(where + ": its mesh is bound to an armature, which cloth does not follow yet; drawn as modelled");

				continue;
			}

			if (mesh.rotation().lengthSquared() > 1e-12) {
				problems.add(where + ": its mesh is turned on its own pivot, which cloth does not follow yet; drawn as modelled");

				continue;
			}

			ClothInstance cloth = build(name + (meshes.size() > 1 ? "#" + m : ""), params, bone.get(), mesh, where, problems);

			if (cloth != null) {
				cloth.carry(model, params, where, problems);
				made.add(cloth);
			}
		}

		return made;
	}

	private static ClothInstance build(String name, ClothParams params, GroupBoneDefinition bone, StrutMesh mesh, String where, List<String> problems) {
		MeshWeld weld = MeshWeld.of(mesh);
		double[][] positions = weld.positions();
		StrutPoly[] polys = mesh.polys();
		int[] corner = weld.cornerParticle();
		List<Integer> triangles = new ArrayList<>();

		for (int p = 0; p < polys.length; p++) {
			// a triangle is a quad whose last corner repeats the one before it
			triangles.add(corner[p * 4]);
			triangles.add(corner[p * 4 + 1]);
			triangles.add(corner[p * 4 + 2]);

			if (corner[p * 4 + 3] != corner[p * 4 + 2] && corner[p * 4 + 3] != corner[p * 4]) {
				triangles.add(corner[p * 4]);
				triangles.add(corner[p * 4 + 2]);
				triangles.add(corner[p * 4 + 3]);
			}
		}

		if (positions.length > params.maxParticles()) {
			problems.add(where + ": its mesh has " + positions.length + " vertices, more than maxParticles " + params.maxParticles()
					+ "; drawn as modelled (use a lower-poly mesh for the cloth, or raise maxParticles)");

			return null;
		}

		if (positions.length < 3) {
			problems.add(where + ": its mesh has too few vertices to be cloth");

			return null;
		}

		return new ClothInstance(name, bone, mesh, params, positions, corner, triangles.stream().mapToInt(Integer::intValue).toArray());
	}

	/** Finds, for every corner of the meshes on the bones {@code params} names, the particle of this cloth nearest to it at rest */
	private void carry(BakedStrutModel model, ClothParams params, String where, List<String> problems) {
		for (String boneName : params.carry()) {
			Optional<GroupBoneDefinition> other = model.getBone(boneName);

			if (other.isEmpty()) {
				problems.add(where + ": carries bone " + boneName + ", which the model lacks");

				continue;
			}

			for (StrutMesh mesh : other.get().getMeshes()) {
				if (mesh.isSkinned() || mesh.rotation().lengthSquared() > 1e-12)
					continue;

				StrutPoly[] polys = mesh.polys();
				double cx = 0;
				double cy = 0;
				double cz = 0;

				for (StrutPoly poly : polys) {
					Vector3f v = poly.vertices()[0].position();

					cx += v.x;
					cy += v.y;
					cz += v.z;
				}

				cx /= polys.length;
				cy /= polys.length;
				cz /= polys.length;

				double reach = 0;

				for (StrutPoly poly : polys) {
					for (int c = 0; c < 4; c++) {
						Vector3f v = poly.vertices()[c].position();

						reach = Math.max(reach, Math.sqrt(Math.pow(v.x - cx, 2) + Math.pow(v.y - cy, 2) + Math.pow(v.z - cz, 2)));
					}
				}

				// the particles within the mesh's own size (and a little more) of its middle, at least the six nearest: the piece moves as they do
				List<double[]> byDistance = new ArrayList<>();

				for (int k = 0; k < this.n; k++)
					byDistance.add(new double[] {k, Math.sqrt(Math.pow(this.rest[k][0] - cx, 2) + Math.pow(this.rest[k][1] - cy, 2) + Math.pow(this.rest[k][2] - cz, 2))});

				byDistance.sort(Comparator.comparingDouble(a -> a[1]));

				int count = 0;

				while (count < byDistance.size() && (count < 6 || byDistance.get(count)[1] <= reach + 0.04))
					count++;

				int[] fit = new int[count];

				for (int i = 0; i < count; i++)
					fit[i] = (int)byDistance.get(i)[0];

				this.carried.add(new Carried(other.get(), mesh, fit));
			}
		}
	}

	public void reset() {
		this.state.initialised = false;
		this.hasOutput = false;
	}

	public int particles() {
		return this.n;
	}

	/** Reads where the animation puts the mesh's vertices this frame (model space) and the bone's matrix to bring offsets back to the mesh's space */
	public void prepare(Pose animated, PhysicsControls controls) {
		PhysicsControls.BoneControl control = controls.boneOrNull(this.name);

		this.active = this.configured.scaled(controls.weight() * (control != null ? control.weight : 1f));

		PosePalette.computeBone(animated, this.bone, this.matrix);

		for (int k = 0; k < this.n; k++) {
			this.scratch.set((float)this.rest[k][0], (float)this.rest[k][1], (float)this.rest[k][2]);
			this.matrix.transformPosition(this.scratch);
			this.animModel[k][0] = this.scratch.x;
			this.animModel[k][1] = this.scratch.y;
			this.animModel[k][2] = this.scratch.z;
		}

		this.rotation.set(this.matrix);
		this.inverse.set(this.matrix);
		this.inverseOk = Math.abs(this.inverse.determinant()) > 1e-9f;

		if (this.inverseOk)
			this.inverse.invert();

		for (Carried c : this.carried) {
			PosePalette.computeBone(animated, c.bone, c.matrix);
			c.inverse.set(c.matrix);
			c.inverseOk = Math.abs(c.inverse.determinant()) > 1e-9f;

			if (c.inverseOk)
				c.inverse.invert();
		}
	}

	/**
	 * One step of the cloth with the entity at {@code (ex, ey, ez)} facing {@code yaw}, having moved by {@code (dx, dy, dz)} since the last step
	 */
	public void step(double ex, double ey, double ez, float yaw, double dx, double dy, double dz, ColliderRig rig, List<Collider.Placed> world, List<Collider.Placed> others) {
		for (int k = 0; k < this.n; k++) {
			this.scratch.set((float)this.animModel[k][0], (float)this.animModel[k][1], (float)this.animModel[k][2]);
			Kinematics.modelToWorld(this.scratch, yaw);
			this.animWorld[k][0] = ex + this.scratch.x;
			this.animWorld[k][1] = ey + this.scratch.y;
			this.animWorld[k][2] = ez + this.scratch.z;
		}

		if (!this.state.initialised)
			this.state.snapTo(this.animWorld);

		for (int k = 0; k < this.n; k++)
			System.arraycopy(this.animModel[k], 0, this.stepAnimModel[k], 0, 3);

		if (this.active.weight() <= 0f)
			return;

		this.delta[0] = dx;
		this.delta[1] = dy;
		this.delta[2] = dz;
		this.placed.clear();

		if (rig != null)
			rig.place(ex, ey, ez, yaw, this.active.collidesWith(), this.skip, this.placed);

		if (world != null && this.active.collidesWith().contains("world"))
			this.placed.addAll(world);

		if (others != null) {
			for (Collider.Placed shape : others) {
				if (Bodies.feels(this.active.collidesWith(), shape))
					this.placed.add(shape);
			}
		}

		// only the shapes near the cloth can touch it: it is every particle against every shape, several times a step, and a blocky floor has hundreds of boxes
		bounds(2.0 * Math.sqrt(dx * dx + dy * dy + dz * dz) + 0.6 + this.active.radius());
		this.shapes.clear();

		for (Collider.Placed shape : this.placed) {
			if (Collider.near(shape, this.lo, this.hi))
				this.shapes.add(shape);
		}

		// a generated shape the animated cloth already overlaps is part of the model's pose, not something to collide with
		Collider.dropRestingContacts(this.shapes, this.animWorld, 0, this.n, this.active.radius());

		ClothSolver.step(this.topology, this.state, this.active, SpringSolver.STEP, this.animWorld, this.delta, this.shapes);
	}

	/** The box around the cloth's particles and where the animation puts them, grown by {@code margin} */
	private void bounds(double margin) {
		for (int a = 0; a < 3; a++) {
			this.lo[a] = Double.MAX_VALUE;
			this.hi[a] = -Double.MAX_VALUE;
		}

		for (int k = 0; k < this.n; k++) {
			for (int a = 0; a < 3; a++) {
				this.lo[a] = Math.min(this.lo[a], Math.min(this.state.x[k][a], this.animWorld[k][a]));
				this.hi[a] = Math.max(this.hi[a], Math.max(this.state.x[k][a], this.animWorld[k][a]));
			}
		}

		for (int a = 0; a < 3; a++) {
			this.lo[a] -= margin;
			this.hi[a] += margin;
		}
	}

	/** Whether this cloth wants blocks to collide with */
	public boolean usesWorld() {
		return this.active.collidesWith().contains("world") && this.active.weight() > 0f;
	}

	/** How far the cloth reaches from the model's origin, blocks */
	public double reach() {
		return this.extent + this.active.radius();
	}

	/** Holds particle {@code index} at {@code target} (world space), or lets go if target is null */
	public void hold(int index, double[] target) {
		if (index < 0 || index >= this.n)
			return;

		this.state.hold[index] = target == null ? null : target.clone();
	}

	public void releaseAll() {
		Arrays.fill(this.state.hold, null);
	}

	/** Which particles the cursor tool offers: about {@code limit} of the free ones, spread over the cloth, as indices into the particles */
	public int[] pickable(int limit) {
		int free = 0;

		for (int k = 0; k < this.n; k++) {
			if (this.state.pin[k] < 0.999f)
				free++;
		}

		int every = Math.max(1, free / Math.max(1, limit));
		int[] out = new int[Math.min(free, limit + 1)];
		int count = 0;
		int seen = 0;

		for (int k = 0; k < this.n && count < out.length; k++) {
			if (this.state.pin[k] < 0.999f && seen++ % every == 0)
				out[count++] = k;
		}

		return Arrays.copyOf(out, count);
	}

	/** A kick to every free particle, a velocity in world blocks per second */
	public void impulse(float x, float y, float z) {
		if (!this.state.initialised)
			return;

		for (int k = 0; k < this.n; k++) {
			if (this.state.pin[k] >= 0.999f)
				continue;

			this.state.prev[k][0] -= x * SpringSolver.STEP;
			this.state.prev[k][1] -= y * SpringSolver.STEP;
			this.state.prev[k][2] -= z * SpringSolver.STEP;
		}
	}

	/**
	 * Turns the particles into the offsets the renderer adds: each one's displacement from where the animation put it at the last step, in the mesh's space,
	 * for every corner of every poly
	 *
	 * @param ex The entity's position the particles are measured from, and its yaw
	 */
	public void computeOutput(double ex, double ey, double ez, float yaw) {
		computeOutput(StepFrame.at(ex, ey, ez, yaw));
	}

	/** As above, with the particles read at the frame's fraction between the last two steps */
	public void computeOutput(StepFrame frame) {
		this.hasOutput = false;

		if (!this.state.initialised || !this.inverseOk || this.active.weight() <= 0f)
			return;

		float weight = this.active.weight();
		float[] perParticle = this.perParticle;

		for (int k = 0; k < this.n; k++) {
			frame.toModel(this.state.x[k], this.state.before[k], this.scratch);
			this.curModel[k][0] = this.scratch.x;
			this.curModel[k][1] = this.scratch.y;
			this.curModel[k][2] = this.scratch.z;
			this.scratch.sub((float)this.stepAnimModel[k][0], (float)this.stepAnimModel[k][1], (float)this.stepAnimModel[k][2]);

			// a displacement over two blocks is a glitch (a teleport, a bad step), not cloth: never show it
			if (!Float.isFinite(this.scratch.lengthSquared()) || this.scratch.lengthSquared() > 4f)
				return;

			// the displacement in the model's space; each mesh takes it into its own bone's space
			this.scratch.mul(weight);
			perParticle[k * 3] = this.scratch.x;
			perParticle[k * 3 + 1] = this.scratch.y;
			perParticle[k * 3 + 2] = this.scratch.z;
		}

		fill(this.inverse);
		smoothNormals();
		this.deform.changed();

		for (Carried c : this.carried) {
			if (c.inverseOk) {
				carryPiece(c, weight);
				c.deform.changed();
			}
		}

		this.hasOutput = true;
	}

	/** A quad's unit normal from four particles' positions (Newell's method), or zero for a degenerate one */
	private static void faceNormal(double[][] x, int a, int b, int c, int d, double[] out) {
		double nx = 0;
		double ny = 0;
		double nz = 0;
		double[] p = x[a];
		double[] q = x[b];

		nx += (p[1] - q[1]) * (p[2] + q[2]);
		ny += (p[2] - q[2]) * (p[0] + q[0]);
		nz += (p[0] - q[0]) * (p[1] + q[1]);
		p = q;
		q = x[c];
		nx += (p[1] - q[1]) * (p[2] + q[2]);
		ny += (p[2] - q[2]) * (p[0] + q[0]);
		nz += (p[0] - q[0]) * (p[1] + q[1]);
		p = q;
		q = x[d];
		nx += (p[1] - q[1]) * (p[2] + q[2]);
		ny += (p[2] - q[2]) * (p[0] + q[0]);
		nz += (p[0] - q[0]) * (p[1] + q[1]);
		p = q;
		q = x[a];
		nx += (p[1] - q[1]) * (p[2] + q[2]);
		ny += (p[2] - q[2]) * (p[0] + q[0]);
		nz += (p[0] - q[0]) * (p[1] + q[1]);

		double len = Math.sqrt(nx * nx + ny * ny + nz * nz);

		if (len < 1e-12) {
			out[0] = 0;
			out[1] = 0;
			out[2] = 0;
		}
		else {
			out[0] = nx / len;
			out[1] = ny / len;
			out[2] = nz / len;
		}
	}

	/**
	 * The shading of the deformed mesh: each corner keeps the normal the mesh was modelled with (a smooth mesh stays smooth, a flat one flat, a hard edge hard)
	 * turned by how much the faces around its particle have turned from where the animation puts them
	 */
	private void smoothNormals() {
		Arrays.fill(this.faceSum, 0f);
		Arrays.fill(this.faceCount, 0);

		StrutPoly[] polys = this.mesh.polys();
		double[] rest = this.faceScratchA;
		double[] now = this.faceScratchB;

		for (int p = 0; p < polys.length; p++) {
			int a = this.cornerParticle[p * 4];
			int b = this.cornerParticle[p * 4 + 1];
			int c = this.cornerParticle[p * 4 + 2];
			int d = this.cornerParticle[p * 4 + 3];

			faceNormal(this.stepAnimModel, a, b, c, d, rest);
			faceNormal(this.curModel, a, b, c, d, now);

			float dx = (float)(now[0] - rest[0]);
			float dy = (float)(now[1] - rest[1]);
			float dz = (float)(now[2] - rest[2]);

			for (int corner = 0; corner < 4; corner++) {
				int k = this.cornerParticle[p * 4 + corner] * 3;

				this.faceSum[k] += dx;
				this.faceSum[k + 1] += dy;
				this.faceSum[k + 2] += dz;
				this.faceCount[k / 3]++;
			}
		}

		// a corner's normal is the mesh's own turned by the bone and brought back (the matrix and its inverse cancel) plus its particle's share of how the faces
		// around it have turned, in the mesh's space: the share is all that is written, for each particle, and the GPU adds it to the corners' own normals
		float[] deltas = this.deform.normalDeltas;

		for (int k = 0; k < this.n; k++) {
			float share = this.faceCount[k] == 0 ? 0f : 1f / this.faceCount[k];

			this.scratch.set(this.faceSum[k * 3] * share, this.faceSum[k * 3 + 1] * share, this.faceSum[k * 3 + 2] * share);
			this.inverse.transform(this.scratch);
			deltas[k * 3] = this.scratch.x;
			deltas[k * 3 + 1] = this.scratch.y;
			deltas[k * 3 + 2] = this.scratch.z;
		}
	}

	/**
	 * A carried mesh moves as one piece, the rigid motion that best takes the particles around it from where the animation puts them to where they are now
	 * (a Kabsch fit: their centres, and the rotation from the polar part of their spread). Each of its vertices is moved by that, so it keeps its shape
	 */
	private void carryPiece(Carried c, float weight) {
		int m = c.fit.length;
		double[] ra = new double[3];
		double[] rc = new double[3];

		for (int k : c.fit) {
			for (int a = 0; a < 3; a++) {
				ra[a] += this.stepAnimModel[k][a] / m;
				rc[a] += this.curModel[k][a] / m;
			}
		}

		Matrix3f spread = new Matrix3f();

		for (int k : c.fit) {
			for (int i = 0; i < 3; i++) {
				for (int j = 0; j < 3; j++)
					spread.set(j, i, spread.get(j, i) + (float)((this.curModel[k][i] - rc[i]) * (this.stepAnimModel[k][j] - ra[j])));
			}
		}

		// the rotation part: average the matrix with its inverse transpose until it settles
		Matrix3f r = new Matrix3f(spread);
		boolean usable = Math.abs(r.determinant()) > 1e-14f;

		if (usable) {
			Matrix3f next = new Matrix3f();

			for (int i = 0; i < 12; i++) {
				next.set(r).invert().transpose();
				r.add(next).scale(0.5f);
			}
		}
		else {
			r.identity();
		}

		// every vertex goes through the same motion: one map of its own place, worked out once (see RigidMotion)
		c.motion.collapse(c.matrix, c.inverse, r, ra, rc, weight, c.deform);
	}

	/** Writes each particle's model-space displacement taken through {@code inverse} into the mesh's own space, for the GPU to give to the corners at it */
	private void fill(Matrix3f inverse) {
		float[] out = this.deform.offsets;

		for (int k = 0; k < this.n; k++) {
			this.scratch.set(this.perParticle[k * 3], this.perParticle[k * 3 + 1], this.perParticle[k * 3 + 2]);
			inverse.transform(this.scratch);
			out[k * 3] = this.scratch.x;
			out[k * 3 + 1] = this.scratch.y;
			out[k * 3 + 2] = this.scratch.z;
		}
	}

	/** Hands this cloth's mesh and each carried mesh, with how each is moved, to {@code sink}: what the renderer reads, without a value for each corner being made */
	public void deforms(BiConsumer<StrutMesh, MeshDeform> sink) {
		if (!this.hasOutput)
			return;

		sink.accept(this.mesh, this.deform);

		for (Carried c : this.carried) {
			if (c.inverseOk)
				sink.accept(c.mesh, c.deform);
		}
	}

	/** Hands this cloth's mesh and each carried mesh, with a value for each corner (made from the particles' when asked), to {@code sink}: for tests and tools */
	public void outputs(Sink sink) {
		deforms((mesh, deform) -> sink.accept(mesh, deform.cornerOffsets(), deform.cornerNormals()));
	}

	/** Receives a mesh with its per-corner offsets and normals */
	public interface Sink {
		void accept(StrutMesh mesh, float[] offsets, float[] normals);
	}

	/** This cloth's offset for each poly corner of its mesh, in the mesh's space: x, y, z each (made from the particles' when asked, for tests and tools) */
	public float[] cornerOffsets() {
		return this.deform.cornerOffsets();
	}

	/** This cloth's shading normal for each poly corner of its mesh, in the mesh's space (made from the particles' when asked, for tests and tools) */
	public float[] cornerNormals() {
		return this.deform.cornerNormals();
	}

	/** Whether this cloth has this frame's result */
	boolean hasOutput() {
		return this.hasOutput;
	}

	/** The largest relative difference between an edge's length and its rest length, for tests */
	public double worstEdgeError() {
		return this.state.initialised ? ClothSolver.worstEdgeError(this.topology, this.state) : 0;
	}
}
