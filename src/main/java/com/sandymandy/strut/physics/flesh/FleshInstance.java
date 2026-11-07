package com.sandymandy.strut.physics.flesh;

import com.sandymandy.strut.animation.state.MeshDeform;
import com.sandymandy.strut.animation.state.CageBindings;
import com.sandymandy.strut.animation.state.CageDeform;
import com.sandymandy.strut.animation.state.ParticleDeform;
import com.sandymandy.strut.animation.state.Pose;
import com.sandymandy.strut.animation.state.PosePalette;
import com.sandymandy.strut.animation.state.RigidDeform;
import com.sandymandy.strut.cache.object.BakedStrutModel;
import com.sandymandy.strut.cache.object.GroupBoneDefinition;
import com.sandymandy.strut.cache.object.MeshWeld;
import com.sandymandy.strut.cache.object.StrutMesh;
import com.sandymandy.strut.physics.collision.Bodies;
import com.sandymandy.strut.physics.collision.Collider;
import com.sandymandy.strut.physics.config.PhysicsOptions;
import com.sandymandy.strut.physics.collision.ColliderRig;
import com.sandymandy.strut.physics.solver.Kinematics;
import com.sandymandy.strut.physics.solver.StepFrame;
import com.sandymandy.strut.physics.tools.PhysicsControls;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;

/**
 * One soft body on one model instance: a region (or a left and right pair of them) whose meshes are moved by a cage that {@link FleshSolver} runs. The cage is
 * fitted to the meshes once, when the instance is made; each frame the animation carries it, each step the solver moves it, and each frame its movement is
 * blended onto every welded vertex of the meshes as one offset each, which the renderer adds on the GPU like cloth's
 * <p>
 * The cost of a step does not depend on the mesh (it is the cage's {@link FleshCageGeometry#NODES} nodes); only the blend onto the vertices does, once a frame
 * <p>
 * A mesh bound to an armature is not followed yet: it is drawn as modelled and the problems say why
 */
public final class FleshInstance {
	/** How long a node's displacement may be and still count as at rest, blocks: a region at rest is drawn as modelled, with nothing for the GPU to add */
	private static final double REST = 1e-5;

	public final String name;
	private final FleshParams configured;
	public FleshParams active;
	private final Part[] parts;
	private final FleshSolver.Region[] regions;
	private final List<Collider.Placed>[] body;
	private final List<Collider.Placed>[] press;
	private final List<Collider.Placed> scratch = new ArrayList<>();
	private final double[] swallowed = new double[3];
	private final double[] lo = new double[3];
	private final double[] hi = new double[3];
	/** The model's own shapes this soft body is not pushed by as body (its own bones, and those that press instead), and those that press: set once the rig exists */
	public boolean[] bodySkip;
	public boolean[] pressSkip;
	private boolean hasOutput;
	// the blend's state for the region being drawn: its swing in its frame, the turn and the stretch made of it, and the pair's plane; and scratch
	private double mx;
	private double my;
	private double mz;
	private double[] turn;
	private double[] stretch;
	private boolean plane;
	private double px;
	private double py;
	private double pz;
	private double nx;
	private double ny;
	private double nz;
	private double allow;
	private double edge;
	private final double[] moved = new double[6];
	// the shapes that press into the region being drawn, and where it is in the world: each vertex is pushed out of them after the cage has moved it
	private final double[] pressGradient = new double[3];
	private final double[] ball = new double[4];
	private final Matrix3f carryTurn = new Matrix3f();
	private final Matrix3f carryLess = new Matrix3f();
	private final Matrix3f carryG = new Matrix3f();
	private final Matrix3f carryA = new Matrix3f();
	private final Matrix3f carryT = new Matrix3f();
	private final Vector3f carryVector = new Vector3f();

	/** One region: its root bone, its cage and state, and the meshes it moves */
	private static final class Part {
		final GroupBoneDefinition root;
		final FleshCage cage;
		final FleshSolver.Region region;
		final MeshPart[] meshes;
		final Matrix4f matrix = new Matrix4f();
		final Matrix4f inverse = new Matrix4f();
		final Matrix3f rotation = new Matrix3f();
		final Matrix3f rotationInverse = new Matrix3f();
		boolean usable;
		/** Where the animation puts each node this frame, and where it put them at the last step, in the model's space */
		final double[] nodes = new double[FleshCageGeometry.NODES * 3];
		final double[] stepNodes = new double[FleshCageGeometry.NODES * 3];
		boolean stepped;
		/** Each node's displacement for drawing, in the region's frame */
		final double[] local = new double[FleshCageGeometry.NODES * 3];
		final double[] smoothed = new double[FleshCageGeometry.NODES * 3];
		/** The surface nodes where the drawn mesh has them, and how much the surface's normal there has turned from rest, in the region's frame */
		final double[] cageMoved = new double[FleshCageGeometry.NODES * 3];
		final double[] cageNormals = new double[FleshCageGeometry.SURFACE * 3];
		boolean moving;
		/** What the last blend was made from, so a resting region that has not moved in the model or turned with the entity keeps it */
		final Matrix4f shownMatrix = new Matrix4f();
		float shownYaw = Float.NaN;
		boolean shownAsleep;
		/** What the last blend was made at, so a reader that wants the vertices later can have them worked out the same */
		double shownX;
		double shownY;
		double shownZ;
		float shownAlpha = 1f;
		double shownCos = 1;
		double shownSin;

		Part(GroupBoneDefinition root, FleshCage cage, MeshPart[] meshes) {
			this.root = root;
			this.cage = cage;
			this.region = new FleshSolver.Region(cage);
			this.meshes = meshes;
		}
	}

	/**
	 * One mesh a region moves: a soft one by a value for each welded particle, or one that rides on the region (a mesh on a bone under the region's root, when
	 * the root has meshes of its own: a tip on a pouch) as one rigid piece, by the cage's motion where it sits
	 */
	private static final class MeshPart {
		final StrutMesh mesh;
		final GroupBoneDefinition bone;
		/** Each particle in the region's frame at rest, x, y, z each (soft meshes) */
		final double[] inRegion;
		final FleshCage.Binding binding;
		final ParticleDeform deform;
		/** The same mesh as the GPU takes it: the cage's numbers, which the shader works every vertex out from (null for a carried mesh), and which region of the mesh's this is */
		CageDeform cage;
		int cageRegion;
		/** Carried: the mesh's space into the region's frame at rest, the piece's middle there, how that middle follows the cage, and the motion it is given */
		final boolean carried;
		final Matrix4f restToRegion;
		final Matrix3f restToRegionInverse;
		final double[] middle;
		final RigidDeform rigid;
		final Matrix4f matrix = new Matrix4f();
		final Matrix3f inverse = new Matrix3f();
		/** The region's frame into this mesh's space, for a displacement */
		final Matrix3f toMesh = new Matrix3f();
		boolean usable;
		/**
		 * The particles of the mesh this region moves: all of them, or the ones of one connected piece when a mesh holds several parts (two pouches in one mesh).
		 * The pieces of a mesh share its {@link #deform}, each writing its own particles
		 */
		final int[] ids;

		MeshPart(StrutMesh mesh, GroupBoneDefinition bone, double[] inRegion, FleshCage.Binding binding, ParticleDeform deform, int[] ids) {
			this.mesh = mesh;
			this.bone = bone;
			this.inRegion = inRegion;
			this.binding = binding;
			this.deform = deform;
			this.ids = ids;
			this.carried = false;
			this.restToRegion = null;
			this.restToRegionInverse = null;
			this.middle = null;
			this.rigid = null;
		}

		MeshPart(StrutMesh mesh, GroupBoneDefinition bone, Matrix4f restToRegion, double[] middle, FleshCage.Binding binding) {
			this.mesh = mesh;
			this.bone = bone;
			this.inRegion = null;
			this.binding = binding;
			this.deform = null;
			this.ids = new int[0];
			this.carried = true;
			this.restToRegion = restToRegion;
			this.restToRegionInverse = new Matrix3f().set(restToRegion).invert();
			this.middle = middle;
			this.rigid = new RigidDeform(mesh);
		}

		int particles() {
			return this.ids.length;
		}
	}

	@SuppressWarnings("unchecked")
	private FleshInstance(String name, FleshParams params, Part[] parts) {
		this.name = name;
		this.configured = params;
		this.active = params;
		this.parts = parts;
		this.regions = new FleshSolver.Region[parts.length];
		this.body = new List[parts.length];
		this.press = new List[parts.length];

		for (int i = 0; i < parts.length; i++) {
			this.regions[i] = parts[i].region;
			this.body[i] = new ArrayList<>();
			this.press[i] = new ArrayList<>();
		}

		// a reader that wants the vertices' offsets (rather than the GPU working them out) has them made from what the last blend left
		java.util.Set<CageDeform> seen = new java.util.HashSet<>();

		for (Part part : parts) {
			for (MeshPart mesh : part.meshes) {
				if (mesh.cage != null && seen.add(mesh.cage))
					mesh.cage.fallbackWith(() -> this.fallback(mesh.cage));
			}
		}
	}

	/** Works every vertex of the regions that share {@code cage} out from the cage's last blend, for a reader that wants them here */
	private void fallback(CageDeform cage) {
		StepFrame frame = new StepFrame();
		Vector3f v = new Vector3f();

		for (Part part : this.parts) {
			boolean mine = false;

			for (MeshPart mesh : part.meshes)
				mine |= mesh.cage == cage;

			if (!mine)
				continue;

			frame.set(part.shownX, part.shownY, part.shownZ, 0f, part.shownX, part.shownY, part.shownZ, 0f, 1f);
			blend(part, frame, part.shownAlpha, part.shownCos, part.shownSin, v, true);
		}
	}

	/** Makes the soft body {@code params} describes, or null with the reasons in {@code problems} when none of its regions can be made */
	public static FleshInstance create(String name, FleshParams params, BakedStrutModel model, List<String> problems) {
		Pose rest = restPose(model);
		List<Part> parts = new ArrayList<>();
		int rootsMade = 0;

		for (String boneName : params.bones()) {
			Optional<GroupBoneDefinition> root = model.getBone(boneName);
			String where = "flesh " + name + " (" + boneName + ")";

			if (root.isEmpty()) {
				problems.add(where + ": the model has no such bone");

				continue;
			}

			List<Part> made = region(model, root.get(), rest, params, where, problems);

			parts.addAll(made);

			if (!made.isEmpty())
				rootsMade++;
		}

		if (parts.isEmpty())
			return null;

		if (rootsMade != params.bones().size() && params.bones().size() == 2)
			problems.add("flesh " + name + ": only one of the pair could be made, so it is not pressed by the other");

		return new FleshInstance(name, params, parts.toArray(new Part[0]));
	}

	private static List<Part> region(BakedStrutModel model, GroupBoneDefinition root, Pose rest, FleshParams params, String where, List<String> problems) {
		Matrix4f restMatrix = new Matrix4f();

		PosePalette.computeBone(rest, root, restMatrix);

		Matrix4f toRegion = new Matrix4f(restMatrix).invert();
		List<GroupBoneDefinition> bones = new ArrayList<>();
		List<StrutMesh> meshes = new ArrayList<>();
		List<MeshWeld> welds = new ArrayList<>();
		List<double[]> points = new ArrayList<>();

		// the cubes of the region are cut into meshes, which is what the cage moves
		com.sandymandy.strut.cache.object.CubeMeshes.convertUnder(model, root);

		collect(root, bones, meshes, where, problems);

		// the bones named in carry that are not under the root: their meshes ride on the region as rigid pieces, and are not part of what the cage is fitted to
		int ownMeshes = meshes.size();

		for (String carried : params.carry()) {
			Optional<GroupBoneDefinition> other = model.getBone(carried);

			if (other.isEmpty()) {
				problems.add(where + ": carries bone " + carried + ", which the model lacks");

				continue;
			}

			if (ColliderRig.under(other.get(), root))
				continue;

			com.sandymandy.strut.cache.object.CubeMeshes.convertUnder(model, other.get());
			collect(other.get(), bones, meshes, where, problems);
		}

		if (ownMeshes == 0) {
			problems.add(where + ": no mesh or cube on the bone or under it");

			return List.of();
		}

		List<double[]> inRegion = new ArrayList<>();
		List<Matrix4f> toRegions = new ArrayList<>();
		Vector3f v = new Vector3f();

		for (int m = 0; m < meshes.size(); m++) {
			MeshWeld weld = MeshWeld.of(meshes.get(m));
			double[][] positions = weld.positions();
			double[] local = new double[positions.length * 3];
			Matrix4f meshMatrix = new Matrix4f();

			PosePalette.computeBone(rest, bones.get(m), meshMatrix);
			element(meshes.get(m), meshMatrix);
			toRegion.mul(meshMatrix, meshMatrix);

			for (int k = 0; k < positions.length; k++) {
				meshMatrix.transformPosition((float)positions[k][0], (float)positions[k][1], (float)positions[k][2], v);
				local[k * 3] = v.x;
				local[k * 3 + 1] = v.y;
				local[k * 3 + 2] = v.z;

				if (m < ownMeshes)
					points.add(new double[] {v.x, v.y, v.z});
			}

			welds.add(weld);
			inRegion.add(local);
			toRegions.add(meshMatrix);
		}

		boolean rootHasMesh = bones.contains(root);
		boolean[] carried = new boolean[meshes.size()];
		int softMesh = -1;
		int softMeshes = 0;

		for (int m = 0; m < meshes.size(); m++) {
			carried[m] = m >= ownMeshes || (rootHasMesh && bones.get(m) != root);

			if (!carried[m]) {
				softMesh = m;
				softMeshes++;
			}
		}

		// a mesh that holds several separate parts (two pouches in one mesh) is one region for each part, when it is the region's only soft mesh: each with a cage
		// of its own, so each swings and is pressed on its own, and the two press each other
		List<int[]> pieces = softMeshes == 1 ? pieces(welds.get(softMesh), inRegion.get(softMesh)) : null;
		boolean split = pieces != null && pieces.size() > 1;

		if (!split) {
			pieces = new ArrayList<>();
			pieces.add(new int[0]);
		}

		ParticleDeform[] shared = new ParticleDeform[meshes.size()];
		List<Part> made = new ArrayList<>();
		List<List<MeshPart>> owned = new ArrayList<>();
		List<FleshCage> cages = new ArrayList<>();

		for (int[] piece : pieces) {
			List<double[]> fit = new ArrayList<>();

			if (split) {
				double[] local = inRegion.get(softMesh);

				for (int id : piece)
					fit.add(new double[] {local[id * 3], local[id * 3 + 1], local[id * 3 + 2]});
			}
			else {
				fit = points;
			}

			FleshCage cage = FleshCage.fit(fit.toArray(new double[0][]), params.tip(), params.up(), restMatrix, params);

			// the swing turns about the bone's own pivot (or one given), not only about the base of the cage: a bone is where the animator put the joint
			double[] about = null;

			if (params.pivot() != null)
				about = new double[] {params.pivot()[0], params.pivot()[1], params.pivot()[2]};
			else if (params.bonePivot())
				about = new double[] {root.getPivotX() / 16.0, root.getPivotY() / 16.0, root.getPivotZ() / 16.0};

			if (about != null) {
				double away = Math.sqrt(Math.pow(about[0] - cage.centre[0], 2) + Math.pow(about[1] - cage.centre[1], 2) + Math.pow(about[2] - cage.centre[2], 2));

				if (away > cage.length * 4) {
					problems.add(where + ": the pivot is " + String.format("%.2f", away) + " blocks from the flesh, too far to swing about; swinging about its base instead (set \"pivot\")");
				}
				else {
					System.arraycopy(about, 0, cage.pivot, 0, 3);
				}
			}
			List<MeshPart> mine = new ArrayList<>();

			for (int m = 0; m < meshes.size(); m++) {
				if (carried[m])
					continue;

				double[] local = inRegion.get(m);
				double[][] asPoints = new double[local.length / 3][];

				for (int k = 0; k < asPoints.length; k++)
					asPoints[k] = new double[] {local[k * 3], local[k * 3 + 1], local[k * 3 + 2]};

				if (shared[m] == null)
					shared[m] = new ParticleDeform(meshes.get(m), welds.get(m));

				// a split mesh's piece, or every particle of this mesh (each of the region's meshes owns its own)
				int[] owns = piece;

				if (!split) {
					owns = new int[welds.get(m).particles()];

					for (int i = 0; i < owns.length; i++)
						owns[i] = i;
				}

				mine.add(new MeshPart(meshes.get(m), bones.get(m), local, cage.bind(asPoints), shared[m], owns));
			}

			cages.add(cage);
			owned.add(mine);
		}

		// what never changes for each particle, for the GPU: once for every instance that has this flesh, and a record for each region of the mesh
		for (int m = 0; m < meshes.size(); m++) {
			if (carried[m] || shared[m] == null)
				continue;

			int particles = welds.get(m).particles();
			int[] words = new int[particles * CageDeform.PARTICLE_WORDS];

			for (int k = 0; k < particles; k++)
				words[k * CageDeform.PARTICLE_WORDS + CageDeform.PARTICLE_REGION] = -1;

			for (int c = 0; c < owned.size(); c++) {
				for (MeshPart part : owned.get(c)) {
					if (part.mesh != meshes.get(m))
						continue;

					part.cageRegion = c;
					fillBindings(words, part);
				}
			}

			CageDeform cage = new CageDeform(shared[m], owned.size(), CageBindings.register(java.util.List.of(model, params, root.getName(), m), words));

			for (List<MeshPart> list : owned) {
				for (MeshPart part : list) {
					if (part.mesh == meshes.get(m))
						part.cage = cage;
				}
			}
		}

		// each carried mesh rides on the part whose middle is nearest it
		for (int m = 0; m < meshes.size(); m++) {
			if (!carried[m])
				continue;

			double[] local = inRegion.get(m);
			double[] middle = new double[3];
			int count = local.length / 3;

			for (int k = 0; k < count; k++) {
				for (int a = 0; a < 3; a++)
					middle[a] += local[k * 3 + a] / count;
			}

			int best = 0;
			double nearest = Double.MAX_VALUE;

			for (int c = 0; c < cages.size(); c++) {
				double[] centre = cages.get(c).centre;
				double d = Math.pow(centre[0] - middle[0], 2) + Math.pow(centre[1] - middle[1], 2) + Math.pow(centre[2] - middle[2], 2);

				if (d < nearest) {
					nearest = d;
					best = c;
				}
			}

			owned.get(best).add(new MeshPart(meshes.get(m), bones.get(m), toRegions.get(m), middle, cages.get(best).bind(new double[][] {middle})));
		}

		for (int c = 0; c < cages.size(); c++) {
			Part part = new Part(root, cages.get(c), owned.get(c).toArray(new MeshPart[0]));

			made.add(part);
		}

		return made;
	}

	/**
	 * The connected pieces of a mesh (particles that share a poly are one piece) that are each a good share of it, as the particles of each; null when it has fewer
	 * than two. A small piece (a stray fragment) goes with the big piece nearest it
	 */
	private static List<int[]> pieces(MeshWeld weld, double[] inRegion) {
		int n = weld.particles();
		int[] parent = new int[n];

		for (int i = 0; i < n; i++)
			parent[i] = i;

		int[] corner = weld.cornerParticle();

		for (int c = 0; c + 3 < corner.length; c += 4) {
			for (int k = 1; k < 4; k++)
				union(parent, corner[c], corner[c + k]);
		}

		int[] size = new int[n];

		for (int i = 0; i < n; i++)
			size[find(parent, i)]++;

		// an island that sits inside another's bounds (a tip on a pouch, merged into its mesh) is part of it: only islands side by side are separate parts
		int threshold = Math.max(6, n / 4);
		double[][] low = new double[n][];
		double[][] high = new double[n][];

		for (int i = 0; i < n; i++) {
			int root = find(parent, i);

			if (size[root] < threshold)
				continue;

			if (low[root] == null) {
				low[root] = new double[] {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE};
				high[root] = new double[] {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
			}

			for (int a = 0; a < 3; a++) {
				low[root][a] = Math.min(low[root][a], inRegion[i * 3 + a]);
				high[root][a] = Math.max(high[root][a], inRegion[i * 3 + a]);
			}
		}

		for (int a = 0; a < n; a++) {
			for (int b = 0; b < n; b++) {
				if (a == b || low[a] == null || low[b] == null || find(parent, a) != a || find(parent, b) != b)
					continue;

				// the one with the smaller bounds goes into the other (a tip has more vertices than the pouch it is on, so not by count)
				double volumeA = 1;
				double volumeB = 1;

				for (int k = 0; k < 3; k++) {
					volumeA *= Math.max(high[a][k] - low[a][k], 0.01);
					volumeB *= Math.max(high[b][k] - low[b][k], 0.01);
				}

				if (volumeA > volumeB || (volumeA == volumeB && a > b))
					continue;

				// the share of the smaller island's bounds that lies inside the larger's: side by side there is none, a tip on a pouch has most of it
				double share = 1;

				for (int k = 0; k < 3; k++) {
					double extent = Math.max(high[a][k] - low[a][k], 0.01);
					double overlap = Math.min(high[a][k], high[b][k]) - Math.max(low[a][k], low[b][k]);

					share *= Math.max(0, Math.min(1, overlap / extent));
				}

				boolean inside = share >= 0.25;

				if (inside) {
					parent[a] = b;
					size[b] += size[a];
					high[b][0] = Math.max(high[b][0], high[a][0]);
					high[b][1] = Math.max(high[b][1], high[a][1]);
					high[b][2] = Math.max(high[b][2], high[a][2]);
					low[b][0] = Math.min(low[b][0], low[a][0]);
					low[b][1] = Math.min(low[b][1], low[a][1]);
					low[b][2] = Math.min(low[b][2], low[a][2]);
				}
			}
		}

		List<Integer> big = new ArrayList<>();

		for (int i = 0; i < n; i++) {
			if (find(parent, i) == i && size[i] >= threshold)
				big.add(i);
		}

		if (big.size() < 2)
			return null;

		// every particle goes with its piece, or with the nearest big piece's middle if its piece is small
		double[][] middle = new double[big.size()][3];
		int[] count = new int[big.size()];

		for (int i = 0; i < n; i++) {
			int at = big.indexOf(find(parent, i));

			if (at >= 0) {
				for (int a = 0; a < 3; a++)
					middle[at][a] += inRegion[i * 3 + a];

				count[at]++;
			}
		}

		for (int b = 0; b < big.size(); b++) {
			for (int a = 0; a < 3; a++)
				middle[b][a] /= count[b];
		}

		List<List<Integer>> members = new ArrayList<>();

		for (int b = 0; b < big.size(); b++)
			members.add(new ArrayList<>());

		java.util.Map<Integer, Integer> smallTo = new java.util.HashMap<>();

		for (int i = 0; i < n; i++) {
			int root = find(parent, i);
			int at = big.indexOf(root);

			if (at < 0) {
				Integer chosen = smallTo.get(root);

				if (chosen == null) {
					double nearest = Double.MAX_VALUE;

					at = 0;

					for (int b = 0; b < big.size(); b++) {
						double d = Math.pow(middle[b][0] - inRegion[i * 3], 2) + Math.pow(middle[b][1] - inRegion[i * 3 + 1], 2) + Math.pow(middle[b][2] - inRegion[i * 3 + 2], 2);

						if (d < nearest) {
							nearest = d;
							at = b;
						}
					}

					smallTo.put(root, at);
				}
				else {
					at = chosen;
				}
			}

			members.get(at).add(i);
		}

		List<int[]> out = new ArrayList<>();

		for (List<Integer> m : members)
			out.add(m.stream().mapToInt(Integer::intValue).toArray());

		return out;
	}

	private static int find(int[] parent, int i) {
		while (parent[i] != i) {
			parent[i] = parent[parent[i]];
			i = parent[i];
		}

		return i;
	}

	private static void union(int[] parent, int a, int b) {
		int ra = find(parent, a);
		int rb = find(parent, b);

		if (ra != rb)
			parent[ra] = rb;
	}

	private static double triangle(double[] a, double[] b, double[] c) {
		double ux = b[0] - a[0];
		double uy = b[1] - a[1];
		double uz = b[2] - a[2];
		double vx = c[0] - a[0];
		double vy = c[1] - a[1];
		double vz = c[2] - a[2];
		double x = uy * vz - uz * vy;
		double y = uz * vx - ux * vz;
		double z = ux * vy - uy * vx;

		return 0.5 * Math.sqrt(x * x + y * y + z * z);
	}

	private static double[] region(MeshPart mesh, int k) {
		return new double[] {mesh.inRegion[k * 3], mesh.inRegion[k * 3 + 1], mesh.inRegion[k * 3 + 2]};
	}


	/** The meshes of {@code bone} and every bone under it that a soft body can move, with the bone each is on */
	private static void collect(GroupBoneDefinition bone, List<GroupBoneDefinition> bones, List<StrutMesh> meshes, String where, List<String> problems) {
		for (StrutMesh mesh : bone.getMeshes()) {
			if (mesh.hidden())
				continue;

			if (mesh.isSkinned()) {
				problems.add(where + ": a mesh on " + bone.getName() + " is bound to an armature, which a soft body does not follow yet; drawn as modelled");

				continue;
			}

			bones.add(bone);
			meshes.add(mesh);
		}

		for (GroupBoneDefinition child : bone.getChildBones())
			collect(child, bones, meshes, where, problems);
	}

	/** {@code matrix}, a bone's, with the turn the mesh has on its own pivot after it: the mesh's space into the model's */
	private static void element(StrutMesh mesh, Matrix4f matrix) {
		com.sandymandy.strut.util.RenderUtil.applyPivotRotation(matrix, new Matrix3f(), mesh.pivot(), mesh.rotation());
	}

	/** The model as it is modelled: every bone at its own rotation, nothing moved */
	private static Pose restPose(BakedStrutModel model) {
		GroupBoneDefinition[] definitions = model.flatBones();
		Pose pose = new Pose(definitions.length);

		for (int i = 0; i < definitions.length; i++)
			pose.setRotation(i, definitions[i].getInitialRotX(), definitions[i].getInitialRotY(), definitions[i].getInitialRotZ());

		return pose;
	}

	/** The root bone of each region */
	public GroupBoneDefinition[] roots() {
		GroupBoneDefinition[] roots = new GroupBoneDefinition[this.parts.length];

		for (int i = 0; i < roots.length; i++)
			roots[i] = this.parts[i].root;

		return roots;
	}

	/** How many regions this soft body has: one for each bone it names, and one for each separate part of a mesh that holds several */
	public int regionCount() {
		return this.parts.length;
	}

	/** How many cage nodes this soft body steps, for the budget */
	public int nodes() {
		return this.parts.length * FleshCageGeometry.NODES;
	}

	/** How far the soft body reaches from the model's origin, blocks, for choosing which other bodies to gather */
	public double reach() {
		double reach = 0;

		for (Part part : this.parts) {
			double[] c = part.cage.centre;
			double[] h = part.cage.half;

			reach = Math.max(reach, Math.sqrt(c[0] * c[0] + c[2] * c[2]) + Math.max(h[0], Math.max(h[1], h[2])) * 1.8 + part.cage.limit);
		}

		return reach;
	}

	public void reset() {
		for (Part part : this.parts) {
			part.region.reset();
			part.stepped = false;

		}

		this.hasOutput = false;
	}

	/** Reads where the animation puts each region and its meshes this frame */
	public void prepare(Pose animated, PhysicsControls controls) {
		PhysicsControls.BoneControl control = controls.boneOrNull(this.name);

		this.active = this.configured.scaled(controls.weight() * (control != null ? control.weight : 1f));

		for (Part part : this.parts) {
			PosePalette.computeBone(animated, part.root, part.matrix);
			part.rotation.set(part.matrix);

			float det = part.rotation.determinant();

			part.usable = Math.abs(det) > 1e-9f;

			if (!part.usable)
				continue;

			part.inverse.set(part.matrix).invert();
			part.rotationInverse.set(part.rotation).invert();

			Vector3f v = new Vector3f();

			for (int i = 0; i < FleshCageGeometry.NODES; i++) {
				part.matrix.transformPosition((float)part.cage.rest[i * 3], (float)part.cage.rest[i * 3 + 1], (float)part.cage.rest[i * 3 + 2], v);
				part.nodes[i * 3] = v.x;
				part.nodes[i * 3 + 1] = v.y;
				part.nodes[i * 3 + 2] = v.z;
			}

			FleshSolver.Region r = part.region;
			// drawn bigger than modelled (a size slider) it moves slower and heavier, smaller quicker
			double size = Math.cbrt(Math.abs(det));

			r.sizeScale = Math.max(0.65, Math.min(2.5, 1.0 / (size * size)));
			r.softness = this.active.softness() * controls.softness();
			r.dressed = controls.dressed();
			r.animatedHeight = part.nodes[FleshCageGeometry.CENTRE * 3 + 1];
			r.restStale = true;
			r.linksStale = true;


			for (MeshPart mesh : part.meshes) {
				PosePalette.computeBone(animated, mesh.bone, mesh.matrix);
				element(mesh.mesh, mesh.matrix);
				mesh.inverse.set(mesh.matrix);
				mesh.usable = Math.abs(mesh.inverse.determinant()) > 1e-9f;

				if (mesh.usable) {
					mesh.inverse.invert();
					mesh.inverse.mul(part.rotation, mesh.toMesh);
				}
			}
		}
	}

	/**
	 * One step with the entity at {@code (ex, ey, ez)} facing {@code yaw}
	 *
	 * @param fraction The share of the way left this step takes the targets, from where the last step had them to where the animation puts them this frame:
	 *                 1 over the steps this frame has left, this one included (1 for the last), so the animation's motion is spread evenly over the steps
	 * @param others The shapes of the entities around, and {@code groups} which says where each one's are
	 */
	public void step(double ex, double ey, double ez, float yaw, double fraction, ColliderRig rig, List<Collider.Placed> others, Bodies.Groups groups,
			double legSpeed, double legPosition) {
		step(ex, ey, ez, yaw, fraction, rig, others, groups, null, legSpeed, legPosition);
	}

	/** Whether this flesh is pressed by blocks: it lists {@code world} and the world switch is on */
	public boolean feelsWorld() {
		return this.active.weight() > 0f && this.active.collidesWith().contains("world") && PhysicsOptions.worldCollision();
	}

	/** @param blocks The solid boxes round the entity (layer {@code world}), or null: the ones near the flesh press it like any other shape */
	public void step(double ex, double ey, double ez, float yaw, double fraction, ColliderRig rig, List<Collider.Placed> others, Bodies.Groups groups,
			List<Collider.Placed> blocks, double legSpeed, double legPosition) {
		Vector3f v = this.stepVector;
		boolean feelsBody = this.contact >= CONTACT_BODY && this.active.collidesWith().contains("body");
		boolean feelsOther = this.active.collidesWith().contains(Bodies.OTHER);

		for (int p = 0; p < this.parts.length; p++) {
			Part part = this.parts[p];
			FleshSolver.Region r = part.region;

			for (int k = 0; k < 3; k++) {
				this.lo[k] = Double.MAX_VALUE;
				this.hi[k] = -Double.MAX_VALUE;
			}

			for (int i = 0; i < FleshCageGeometry.NODES; i++) {
				int at = i * 3;

				for (int k = 0; k < 3; k++)
					part.stepNodes[at + k] = part.stepped ? part.stepNodes[at + k] + (part.nodes[at + k] - part.stepNodes[at + k]) * fraction : part.nodes[at + k];

				v.set((float)part.stepNodes[at], (float)part.stepNodes[at + 1], (float)part.stepNodes[at + 2]);
				Kinematics.modelToWorld(v, yaw);
				r.target[at] = ex + v.x;
				r.target[at + 1] = ey + v.y;
				r.target[at + 2] = ez + v.z;

				for (int k = 0; k < 3; k++) {
					this.lo[k] = Math.min(this.lo[k], r.target[at + k]);
					this.hi[k] = Math.max(this.hi[k], r.target[at + k]);
				}
			}

			part.stepped = true;
			direction(part, part.cage.up, yaw, v, r.up);
			direction(part, part.cage.tip, yaw, v, r.tipDirection);
			r.entityX = ex;
			r.entityY = ey;
			r.entityZ = ez;
			r.legSpeed = legSpeed;
			r.legPosition = legPosition;

			double margin = part.cage.limit + 0.05;

			for (int k = 0; k < 3; k++) {
				this.lo[k] -= margin;
				this.hi[k] += margin;
			}

			// the body shapes are chosen at the first step of a frame and kept (their distances from the targets are measured once a frame); placing them
			// again for this step moves the same shapes to it
			List<Collider.Placed> body = this.body[p];
			List<Collider.Placed> press = this.press[p];

			if (rig != null && feelsBody) {
				this.scratch.clear();
				rig.place(ex, ey, ez, yaw, null, this.bodySkip, this.scratch);

				if (r.restStale) {
					body.clear();

					for (Collider.Placed shape : this.scratch) {
						// only the fields of the model's own geometry: until they are made (a moment after the model is first drawn) the flesh has no body
						// of its own to rest against
						if (shape.shape == Collider.Shape.MESH && Collider.near(shape, this.lo, this.hi))
							body.add(shape);
					}
				}
			}
			else {
				body.clear();
			}

			press.clear();

			if (rig != null && this.pressSkip != null && this.contact >= CONTACT_BODY) {
				rig.place(ex, ey, ez, yaw, null, this.pressSkip, this.lo, this.hi, press);
				press.removeIf(shape -> shape.shape != Collider.Shape.MESH);
			}

			if (blocks != null && this.contact >= CONTACT_ALL && feelsWorld()) {
				for (int i = 0, end = blocks.size(); i < end; i++) {
					Collider.Placed shape = blocks.get(i);

					if (Collider.near(shape, this.lo, this.hi))
						press.add(shape);
				}
			}

			int c = FleshCageGeometry.CENTRE * 3;
			double cx = r.target[c];
			double cy = r.target[c + 1];
			double cz = r.target[c + 2];
			int othersFrom = press.size();

			if (others != null && groups != null && this.contact >= CONTACT_ALL) {
				for (int g = 0; g < groups.count(); g++) {
					if (!groups.touches(g, this.lo, this.hi))
						continue;

					for (int i = groups.start(g), end = groups.end(g); i < end; i++) {
						Collider.Placed shape = others.get(i);

						if (Bodies.feels(this.active.collidesWith(), feelsOther, shape) && Collider.near(shape, this.lo, this.hi))
							press.add(shape);
					}
				}
			}

			// in a crowd every neighbour's body is near: only the nearest few of the other entities' shapes press (a partner's hand on it is always among them),
			// so a packed crowd costs what a few neighbours do
			if (press.size() - othersFrom > this.mostOthers)
				keepNearest(press, othersFrom, this.mostOthers, cx, cy, cz);

			// a body that has swallowed the middle of the region (one standing in it, a camera flown into it) is not pressed against: there is no way round it,
			// so the flesh clips into it as the rest of the model does. A block is solid and flattens it instead (see the solver)
			for (int i = press.size() - 1; i >= 0; i--) {
				Collider.Placed shape = press.get(i);

				if (!"world".equals(shape.layer) && Collider.distance(shape, cx, cy, cz) < 0)
					press.remove(i);
			}
		}

		if (this.active.weight() <= 0f)
			return;

		FleshSolver.step(this.regions, this.active, this.body, this.press, ey, FleshSolver.STEP, this.contact == CONTACT_NONE);
	}

	/** Keeps of {@code shapes} from {@code from} on only the {@code most} nearest the point (by the ball round each), in that order */
	private void keepNearest(List<Collider.Placed> shapes, int from, int most, double x, double y, double z) {
		int n = shapes.size() - from;

		if (this.nearKey.length < n)
			this.nearKey = new double[n * 2];

		for (int i = 0; i < n; i++) {
			Collider.bound(shapes.get(from + i), this.ball);

			double dx = this.ball[0] - x;
			double dy = this.ball[1] - y;
			double dz = this.ball[2] - z;

			this.nearKey[i] = Math.sqrt(dx * dx + dy * dy + dz * dz) - this.ball[3];
		}

		for (int k = 0; k < most; k++) {
			int best = k;

			for (int i = k + 1; i < n; i++) {
				if (this.nearKey[i] < this.nearKey[best])
					best = i;
			}

			if (best != k) {
				double key = this.nearKey[k];

				this.nearKey[k] = this.nearKey[best];
				this.nearKey[best] = key;
				shapes.set(from + k, shapes.set(from + best, shapes.get(from + k)));
			}
		}

		while (shapes.size() > from + most)
			shapes.remove(shapes.size() - 1);
	}

	private double[] nearKey = new double[64];
	private final Vector3f stepVector = new Vector3f();

	/** What the flesh feels: nothing (it only swings), its own body and arms, or everything (other entities and blocks too); see {@link #contact} */
	public static final int CONTACT_NONE = 0;
	public static final int CONTACT_BODY = 1;
	public static final int CONTACT_ALL = 2;
	/**
	 * How much this flesh feels this frame: everything near the camera, where a dent shows; its own body farther off; nothing at all far away, where only the
	 * swing shows. Set by whoever steps it; everything by default
	 */
	public int contact = CONTACT_ALL;
	/** The most of the other entities' shapes that press on one region */
	public static final int MOST_OTHERS = 8;
	/** The most of the other entities' shapes that press on this one now: fewer when it is not among those nearest the camera */
	public int mostOthers = MOST_OTHERS;

	/** A direction in the region's frame, in the world: through the root bone's turn and the entity's, unit */
	private static void direction(Part part, double[] local, float yaw, Vector3f v, double[] out) {
		v.set((float)local[0], (float)local[1], (float)local[2]);
		part.rotation.transform(v);
		Kinematics.modelToWorld(v, yaw);

		float length = v.length();

		if (length < 1e-6f)
			v.set(0, 1, 0);
		else
			v.div(length);

		out[0] = v.x;
		out[1] = v.y;
		out[2] = v.z;
	}

	/** Blends the cage's movement at the frame's fraction between the last two steps onto every vertex of the meshes */
	public void computeOutput(StepFrame frame) {
		this.hasOutput = false;

		if (this.active.weight() <= 0f)
			return;

		float alpha = frame.alpha();
		double turn = Math.toRadians(-(180f - frame.yaw()));
		double cos = Math.cos(turn);
		double sin = Math.sin(turn);
		FleshCageGeometry geometry = FleshCageGeometry.get();
		Vector3f v = new Vector3f();
		boolean any = false;

		for (int p = 0; p < this.parts.length; p++) {
			Part part = this.parts[p];
			FleshSolver.Region r = part.region;

			if (part.usable && r.initialised() && FleshSolver.asleep(r) && part.shownAsleep && part.shownYaw == frame.yaw()
					&& part.matrix.equals(part.shownMatrix, 1e-6f)) {
				// resting, where it rested last frame: last frame's blend is this one's
				any |= part.moving;

				continue;
			}

			boolean wasMoving = part.moving;

			part.moving = false;
			part.shownAsleep = false;

			if (!part.usable || !r.initialised()) {
				if (wasMoving)
					clearOwned(part);

				continue;
			}

			double most = 0;

			// each node's displacement at the frame's fraction, from the world into the model (the entity's turn) and then the region's frame
			for (int i = 0; i < FleshCageGeometry.NODES; i++) {
				int at = i * 3;
				double dx = r.displacementBefore[at] + (r.displacement[at] - r.displacementBefore[at]) * alpha;
				double dy = r.displacementBefore[at + 1] + (r.displacement[at + 1] - r.displacementBefore[at + 1]) * alpha;
				double dz = r.displacementBefore[at + 2] + (r.displacement[at + 2] - r.displacementBefore[at + 2]) * alpha;

				v.set((float)(dx * cos + dz * sin), (float)dy, (float)(-dx * sin + dz * cos));
				part.rotationInverse.transform(v);
				part.local[at] = v.x;
				part.local[at + 1] = v.y;
				part.local[at + 2] = v.z;
				most = Math.max(most, dx * dx + dy * dy + dz * dz);
			}

			// at rest, with nothing pressed in or springing back: drawn as modelled
			if (most < REST * REST) {
				if (wasMoving)
					clearOwned(part);

				continue;
			}

			if (!(most < 4.0)) {
				// over two blocks, or not a number: a glitch (a teleport, a bad step), never shown
				if (wasMoving)
					clearOwned(part);

				continue;
			}

			// each node evened out with its neighbours, so the surface is smooth between them
			int c = FleshCageGeometry.CENTRE * 3;
			double[] surface = new double[3];

			for (int i = 0; i < FleshCageGeometry.SURFACE; i++) {
				int[] around = geometry.neighbours[i];
				double sx = 0;
				double sy = 0;
				double sz = 0;

				for (int j : around) {
					sx += part.local[j * 3];
					sy += part.local[j * 3 + 1];
					sz += part.local[j * 3 + 2];
				}

				part.smoothed[i * 3] = part.local[i * 3] * 0.5 + sx / around.length * 0.5;
				part.smoothed[i * 3 + 1] = part.local[i * 3 + 1] * 0.5 + sy / around.length * 0.5;
				part.smoothed[i * 3 + 2] = part.local[i * 3 + 2] * 0.5 + sz / around.length * 0.5;
				surface[0] += part.local[i * 3];
				surface[1] += part.local[i * 3 + 1];
				surface[2] += part.local[i * 3 + 2];
			}

			for (int k = 0; k < 3; k++)
				part.smoothed[c + k] = part.local[c + k] * 0.5 + surface[k] / FleshCageGeometry.SURFACE * 0.5;

			blend(part, frame, alpha, cos, sin, v);
			part.moving = true;
			part.shownAsleep = FleshSolver.asleep(r);
			part.shownMatrix.set(part.matrix);
			part.shownYaw = frame.yaw();
			any = true;
		}

		this.hasOutput = any;
	}

	/**
	 * A part that has gone still gives its particles back to the model as modelled: the mesh's deform may be shared with another part of it that is still moving,
	 * and is handed to the renderer for that one, so what this part last wrote must not stay in it
	 */
	private static void clearOwned(Part part) {
		for (MeshPart mesh : part.meshes) {
			if (mesh.carried)
				continue;

			if (mesh.cage != null)
				mesh.cage.clear(mesh.cageRegion);

			float[] offsets = mesh.deform.offsets;
			float[] normals = mesh.deform.normalDeltas;

			for (int id : mesh.ids) {
				offsets[id * 3] = 0;
				offsets[id * 3 + 1] = 0;
				offsets[id * 3 + 2] = 0;
				normals[id * 3] = 0;
				normals[id * 3 + 1] = 0;
				normals[id * 3 + 2] = 0;
			}

			mesh.deform.changed();
		}
	}

	/** Writes what never changes for the particles {@code part} owns into {@code words}, the mesh's table for the GPU */
	private static void fillBindings(int[] words, MeshPart part) {
		FleshCage.Binding binding = part.binding;

		for (int k : part.ids) {
			int at = k * CageDeform.PARTICLE_WORDS;
			int from = binding.start()[k];
			int count = Math.min(CageDeform.MOST_NODES, binding.start()[k + 1] - from);

			words[at + CageDeform.PARTICLE_REGION] = part.cageRegion;
			words[at + CageDeform.PARTICLE_COUNT] = count;

			for (int j = 0; j < count; j++) {
				words[at + CageDeform.PARTICLE_NODES + j] = binding.node()[from + j];
				words[at + CageDeform.PARTICLE_WEIGHTS + j] = Float.floatToRawIntBits(binding.weight()[from + j]);
			}

			words[at + CageDeform.PARTICLE_SURFACE] = Float.floatToRawIntBits(binding.surface()[k]);
			words[at + CageDeform.PARTICLE_SOFTNESS] = Float.floatToRawIntBits(binding.softness()[k]);
			words[at + CageDeform.PARTICLE_AT] = Float.floatToRawIntBits((float)part.inRegion[k * 3]);
			words[at + CageDeform.PARTICLE_AT + 1] = Float.floatToRawIntBits((float)part.inRegion[k * 3 + 1]);
			words[at + CageDeform.PARTICLE_AT + 2] = Float.floatToRawIntBits((float)part.inRegion[k * 3 + 2]);
		}
	}

	/**
	 * Gives the GPU what it works a region's vertices out from: the nodes' displacements and the change of their normals, the swing's turn and stretch, the
	 * pair's plane, and the matrix into the mesh's own space (see {@link CageDeform}). A few hundred numbers where the vertices are thousands
	 */
	private void writeCage(Part part, MeshPart mesh, Vector3f v) {
		float[] b = mesh.cage.blocks;
		int o = mesh.cageRegion * CageDeform.REGION_WORDS;
		FleshCage cage = part.cage;
		Matrix3f m = mesh.toMesh;

		// the rows of the matrix: its columns are what it makes of each axis
		for (int c = 0; c < 3; c++) {
			v.set(c == 0 ? 1f : 0f, c == 1 ? 1f : 0f, c == 2 ? 1f : 0f);
			m.transform(v);
			b[o + CageDeform.TO_MESH + c] = v.x;
			b[o + CageDeform.TO_MESH + 3 + c] = v.y;
			b[o + CageDeform.TO_MESH + 6 + c] = v.z;
		}

		b[o + CageDeform.FLAGS] = (this.turn != null ? CageDeform.FLAG_TURN : 0) + (this.stretch != null ? CageDeform.FLAG_STRETCH : 0)
				+ (this.plane ? CageDeform.FLAG_PLANE : 0) + (part.region.dressed ? CageDeform.FLAG_DRESSED : 0);
		b[o + CageDeform.SWING] = (float)this.mx;
		b[o + CageDeform.SWING + 1] = (float)this.my;
		b[o + CageDeform.SWING + 2] = (float)this.mz;

		for (int a = 0; a < 3; a++) {
			b[o + CageDeform.PIVOT + a] = (float)cage.pivot[a];
			b[o + CageDeform.MIDDLE + a] = (float)cage.centre[a];
		}

		for (int i = 0; i < 9; i++) {
			b[o + CageDeform.TURN + i] = this.turn != null ? (float)this.turn[i] : 0f;
			b[o + CageDeform.STRETCH + i] = this.stretch != null ? (float)this.stretch[i] : 0f;
		}

		b[o + CageDeform.PLANE_POINT] = (float)this.px;
		b[o + CageDeform.PLANE_POINT + 1] = (float)this.py;
		b[o + CageDeform.PLANE_POINT + 2] = (float)this.pz;
		b[o + CageDeform.PLANE_NORMAL] = (float)this.nx;
		b[o + CageDeform.PLANE_NORMAL + 1] = (float)this.ny;
		b[o + CageDeform.PLANE_NORMAL + 2] = (float)this.nz;
		b[o + CageDeform.PLANE_ALLOW] = (float)this.allow;
		b[o + CageDeform.PLANE_EDGE] = (float)this.edge;
		b[o + CageDeform.RIM] = this.active.rim();
		b[o + CageDeform.ROOM] = (float)(0.15 * cage.limit);
		b[o + CageDeform.WEIGHT] = this.active.weight();

		for (int i = 0; i < CageDeform.NODES * 3; i++)
			b[o + CageDeform.NODE_DISPLACEMENTS + i] = (float)part.smoothed[i];

		for (int i = 0; i < CageDeform.SURFACE * 3; i++)
			b[o + CageDeform.NORMAL_CHANGES + i] = (float)part.cageNormals[i];

		mesh.cage.changed();
	}

	/** The vertices of one region's meshes: the cage's blend, the swing turned about the base, the stretch that keeps the volume, and the pair's plane */
	private void blend(Part part, StepFrame frame, float alpha, double cos, double sin, Vector3f v) {
		blend(part, frame, alpha, cos, sin, v, false);
	}

	/** {@link #blend(Part, StepFrame, float, double, double, Vector3f)}, or with {@code everyVertex} each vertex worked out here even where the GPU would take the cage */
	private void blend(Part part, StepFrame frame, float alpha, double cos, double sin, Vector3f v, boolean everyVertex) {
		part.shownX = frame.x();
		part.shownY = frame.y();
		part.shownZ = frame.z();
		part.shownAlpha = alpha;
		part.shownCos = cos;
		part.shownSin = sin;
		FleshSolver.Region r = part.region;
		FleshCage cage = part.cage;
		FleshParams params = this.active;
		float weight = params.weight();

		// the lump's swing in the region's frame
		double lx = r.lumpShownBefore[0] + (r.lumpShown[0] - r.lumpShownBefore[0]) * alpha;
		double ly = r.lumpShownBefore[1] + (r.lumpShown[1] - r.lumpShownBefore[1]) * alpha;
		double lz = r.lumpShownBefore[2] + (r.lumpShown[2] - r.lumpShownBefore[2]) * alpha;

		v.set((float)(lx * cos + lz * sin), (float)ly, (float)(-lx * sin + lz * cos));
		part.rotationInverse.transform(v);

		double mx = v.x;
		double my = v.y;
		double mz = v.z;
		// the swing as a turn about the base: the axis across the arm and the swing, by the angle the swing makes at the arm's length
		double[] t = cage.tip;
		double ax = t[1] * mz - t[2] * my;
		double ay = t[2] * mx - t[0] * mz;
		double az = t[0] * my - t[1] * mx;
		double across = Math.sqrt(ax * ax + ay * ay + az * az);
		double[] turn = null;

		if (params.rigid() > 0 && across > 1e-9) {
			double angle = params.rigid() * across / cage.length;

			turn = rotation(ax / across, ay / across, az / across, angle, this.turnScratch);
		}

		// the stretch: pulled down and out it lengthens that way and narrows across, the volume kept
		double[] stretch = null;

		if (params.volume() > 0 && !r.dressed) {
			double[] up = cage.up;
			double[] out = {t[0], t[1], t[2]};
			double along = FleshSolver.dot(out, up);

			for (int k = 0; k < 3; k++)
				out[k] -= along * up[k];

			double outLength = Math.sqrt(FleshSolver.dot(out, out));

			if (outLength > 1e-6) {
				for (int k = 0; k < 3; k++)
					out[k] /= outLength;

				double[] down = {-up[0], -up[1], -up[2]};
				double[] side = {down[1] * out[2] - down[2] * out[1], down[2] * out[0] - down[0] * out[2], down[0] * out[1] - down[1] * out[0]};
				double scale = Math.max(1e-6, cage.length);
				double ld = stretch((mx * down[0] + my * down[1] + mz * down[2]) / scale, params.volume());
				double lo = stretch((mx * out[0] + my * out[1] + mz * out[2]) / scale, params.volume());
				double ls = 1 / (ld * lo);

				stretch = new double[9];

				for (int a = 0; a < 3; a++) {
					for (int b = 0; b < 3; b++)
						stretch[a * 3 + b] = (ld - 1) * down[a] * down[b] + (lo - 1) * out[a] * out[b] + (ls - 1) * side[a] * side[b];
				}
			}
		}

		// the pair's plane in the region's frame
		boolean plane = r.paired;
		double px = 0;
		double py = 0;
		double pz = 0;
		double nx = 0;
		double ny = 0;
		double nz = 0;
		double allow = 0;
		double edge = 0.025;

		if (plane) {
			double wx = r.planePoint[0] - frame.x();
			double wy = r.planePoint[1] - frame.y();
			double wz = r.planePoint[2] - frame.z();

			v.set((float)(wx * cos + wz * sin), (float)wy, (float)(-wx * sin + wz * cos));
			part.inverse.transformPosition(v);
			px = v.x;
			py = v.y;
			pz = v.z;
			v.set((float)(r.planeNormal[0] * cos + r.planeNormal[2] * sin), (float)r.planeNormal[1], (float)(-r.planeNormal[0] * sin + r.planeNormal[2] * cos));
			part.rotationInverse.transform(v);

			double scale = v.length();

			if (scale < 1e-9) {
				plane = false;
			}
			else {
				nx = v.x / scale;
				ny = v.y / scale;
				nz = v.z / scale;
				allow = r.planeAllow * scale;
				edge *= scale;
			}
		}

		this.mx = mx;
		this.my = my;
		this.mz = mz;
		this.turn = turn;
		this.stretch = stretch;
		this.plane = plane;
		this.px = px;
		this.py = py;
		this.pz = pz;
		this.nx = nx;
		this.ny = ny;
		this.nz = nz;
		this.allow = allow;
		this.edge = edge;

		double[] centre = cage.centre;
		double[] pivot = cage.pivot;

		// the shading: the cage's own surface moved as the mesh is (the blend, the turn and the stretch at each node), its normals against those at rest. The
		// mesh is moved smoothly by the cage, so its faces turn as the cage's do; this is a few hundred numbers a frame where every face of the mesh is thousands
		for (int i = 0; i < FleshCageGeometry.SURFACE; i++) {
			int at = i * 3;
			double qx = cage.rest[at];
			double qy = cage.rest[at + 1];
			double qz = cage.rest[at + 2];
			double soft = 1 - cage.anchor[i];
			double dx = part.smoothed[at];
			double dy = part.smoothed[at + 1];
			double dz = part.smoothed[at + 2];

			if (turn != null) {
				double rx = qx - pivot[0];
				double ry = qy - pivot[1];
				double rz = qz - pivot[2];
				double held = Math.min(1, 2 * soft);

				dx += (turn[0] * rx + turn[1] * ry + turn[2] * rz - rx) * held - mx * soft;
				dy += (turn[3] * rx + turn[4] * ry + turn[5] * rz - ry) * held - my * soft;
				dz += (turn[6] * rx + turn[7] * ry + turn[8] * rz - rz) * held - mz * soft;
			}

			if (stretch != null) {
				double rx = qx - centre[0];
				double ry = qy - centre[1];
				double rz = qz - centre[2];

				dx += (stretch[0] * rx + stretch[1] * ry + stretch[2] * rz) * soft;
				dy += (stretch[3] * rx + stretch[4] * ry + stretch[5] * rz) * soft;
				dz += (stretch[6] * rx + stretch[7] * ry + stretch[8] * rz) * soft;
			}

			part.cageMoved[at] = qx + dx * weight;
			part.cageMoved[at + 1] = qy + dy * weight;
			part.cageMoved[at + 2] = qz + dz * weight;
		}

		FleshCageGeometry.get().normals(part.cageMoved, part.cageNormals);

		for (int i = 0; i < FleshCageGeometry.SURFACE * 3; i++)
			part.cageNormals[i] -= cage.restNormals[i];

		double[] moved = this.moved;

		for (MeshPart mesh : part.meshes) {
			if (!mesh.usable)
				continue;

			if (mesh.carried) {
				carry(part, mesh, v);

				continue;
			}

			// the GPU works the vertices out from the cage itself
			if (!everyVertex && CageDeform.gpu && mesh.cage != null) {
				writeCage(part, mesh, v);

				continue;
			}

			FleshCage.Binding binding = mesh.binding;
			float[] out = mesh.deform.offsets;
			float[] normals = mesh.deform.normalDeltas;
			int[] ids = mesh.ids;

			for (int j = 0; j < ids.length; j++) {
				int k = ids[j];

				displace(part, mesh.inRegion[k * 3], mesh.inRegion[k * 3 + 1], mesh.inRegion[k * 3 + 2], binding, k, moved);

				// into the mesh's own space for the GPU
				v.set((float)moved[0], (float)moved[1], (float)moved[2]);
				mesh.toMesh.transform(v);
				out[k * 3] = v.x;
				out[k * 3 + 1] = v.y;
				out[k * 3 + 2] = v.z;
				v.set((float)moved[3], (float)moved[4], (float)moved[5]);
				mesh.toMesh.transform(v);
				normals[k * 3] = v.x;
				normals[k * 3 + 1] = v.y;
				normals[k * 3 + 2] = v.z;
			}

			mesh.deform.changed();
		}

	}

	/**
	 * Where the point {@code (qx, qy, qz)} of the region (its frame, at rest), bound to the cage by entry {@code k} of {@code binding}, is moved this frame and
	 * how the normal there turns, both in the region's frame, into {@code out}: the cage's blend, the swing turned about the base, the stretch that keeps the
	 * volume, a top's hold when dressed, and the pair's plane
	 */
	private void displace(Part part, double qx, double qy, double qz, FleshCage.Binding binding, int k, double[] out) {
		FleshCage cage = part.cage;
		FleshParams params = this.active;
		double sx = 0;
		double sy = 0;
		double sz = 0;
		double tx = 0;
		double ty = 0;
		double tz = 0;

		for (int j = binding.start()[k], end = binding.start()[k + 1]; j < end; j++) {
			int node = binding.node()[j] * 3;
			float w = binding.weight()[j];

			sx += part.smoothed[node] * w;
			sy += part.smoothed[node + 1] * w;
			sz += part.smoothed[node + 2] * w;
			tx += part.cageNormals[node] * w;
			ty += part.cageNormals[node + 1] * w;
			tz += part.cageNormals[node + 2] * w;
		}

		int cc = FleshCageGeometry.CENTRE * 3;
		double s = binding.surface()[k];
		double soft = binding.softness()[k];
		double dx = sx * s + part.smoothed[cc] * (1 - s);
		double dy = sy * s + part.smoothed[cc + 1] * (1 - s);
		double dz = sz * s + part.smoothed[cc + 2] * (1 - s);
		double[] turn = this.turn;
		double[] stretch = this.stretch;
		double[] centre = cage.centre;

		if (turn != null) {
			// turned about the base rather than slid: the swing the nodes already carry is taken back out
			double[] pivot = cage.pivot;
			double rx = qx - pivot[0];
			double ry = qy - pivot[1];
			double rz = qz - pivot[2];
			double held = Math.min(1, 2 * soft);

			dx += (turn[0] * rx + turn[1] * ry + turn[2] * rz - rx) * held - this.mx * soft;
			dy += (turn[3] * rx + turn[4] * ry + turn[5] * rz - ry) * held - this.my * soft;
			dz += (turn[6] * rx + turn[7] * ry + turn[8] * rz - rz) * held - this.mz * soft;
		}

		if (stretch != null) {
			double rx = qx - centre[0];
			double ry = qy - centre[1];
			double rz = qz - centre[2];

			dx += (stretch[0] * rx + stretch[1] * ry + stretch[2] * rz) * soft;
			dy += (stretch[3] * rx + stretch[4] * ry + stretch[5] * rz) * soft;
			dz += (stretch[6] * rx + stretch[7] * ry + stretch[8] * rz) * soft;
		}

		if (part.region.dressed) {
			// a top holds it in: it may not swell out past where it is modelled by more than a little
			double rx = qx - centre[0];
			double ry = qy - centre[1];
			double rz = qz - centre[2];
			double length = Math.sqrt(rx * rx + ry * ry + rz * rz);

			if (length > 1e-9) {
				double outward = (dx * rx + dy * ry + dz * rz) / length;
				double room = 0.15 * cage.limit;

				if (outward > room) {
					double back = (outward - room) / length;

					dx -= rx * back;
					dy -= ry * back;
					dz -= rz * back;
				}
			}
		}

		if (this.plane) {
			// flattened against the other of the pair with a soft knee, and what is flattened pushed out along the plane at the rim
			double nx = this.nx;
			double ny = this.ny;
			double nz = this.nz;
			double past = (qx + dx - this.px) * nx + (qy + dy - this.py) * ny + (qz + dz - this.pz) * nz - this.allow;

			if (past > 0) {
				double edge = this.edge;
				double kept = past < 2 * edge ? past - past * past / (4 * edge) : edge;
				double flattened = past - kept;

				dx -= nx * flattened;
				dy -= ny * flattened;
				dz -= nz * flattened;

				double ox = qx + dx - this.px;
				double oy = qy + dy - this.py;
				double oz = qz + dz - this.pz;
				double normal = ox * nx + oy * ny + oz * nz;

				ox -= normal * nx;
				oy -= normal * ny;
				oz -= normal * nz;

				double radial = Math.sqrt(ox * ox + oy * oy + oz * oz);

				if (radial > 1e-9) {
					double rim = params.rim() * flattened / radial;

					dx += ox * rim;
					dy += oy * rim;
					dz += oz * rim;
				}
			}
		}

		float weight = params.weight();

		dx *= weight;
		dy *= weight;
		dz *= weight;

		out[0] = dx;
		out[1] = dy;
		out[2] = dz;
		out[3] = tx;
		out[4] = ty;
		out[5] = tz;
	}

	/**
	 * A carried piece: moved as one by where the cage takes its middle, and turned by how the cage's surface turns there (the least turn that takes the normal
	 * at rest to the one now). As {@code offset = A * p + b} of its own modelled positions, in its own space: {@code A = M (R - I) G} and
	 * {@code b = M (d + (R - I)(g - c))}, with {@code G | g} its space into the region's frame at rest, {@code c} its middle there, {@code d} the middle's
	 * displacement, {@code R} the turn and {@code M} the region's frame into its space now; its normals are turned by {@code G^-1 R G}
	 */
	private void carry(Part part, MeshPart mesh, Vector3f v) {
		double[] moved = this.moved;
		double[] c = mesh.middle;
		FleshCage.Binding binding = mesh.binding;

		displace(part, c[0], c[1], c[2], binding, 0, moved);

		// the normal at rest about the middle, and the same turned
		double rx = 0;
		double ry = 0;
		double rz = 0;

		for (int j = binding.start()[0], end = binding.start()[1]; j < end; j++) {
			int node = binding.node()[j] * 3;
			float w = binding.weight()[j];

			rx += part.cage.restNormals[node] * w;
			ry += part.cage.restNormals[node + 1] * w;
			rz += part.cage.restNormals[node + 2] * w;
		}

		Matrix3f turn = this.carryTurn.identity();
		double restLength = Math.sqrt(rx * rx + ry * ry + rz * rz);

		if (restLength > 1e-9) {
			rx /= restLength;
			ry /= restLength;
			rz /= restLength;

			double ux = rx + moved[3];
			double uy = ry + moved[4];
			double uz = rz + moved[5];
			double nowLength = Math.sqrt(ux * ux + uy * uy + uz * uz);

			if (nowLength > 1e-9) {
				ux /= nowLength;
				uy /= nowLength;
				uz /= nowLength;

				// the least turn from one to the other: about their cross product, by the angle between them
				double ax = ry * uz - rz * uy;
				double ay = rz * ux - rx * uz;
				double az = rx * uy - ry * ux;
				double sin = Math.sqrt(ax * ax + ay * ay + az * az);
				double cos = rx * ux + ry * uy + rz * uz;

				if (sin > 1e-9) {
					double[] r = rotation(ax / sin, ay / sin, az / sin, Math.atan2(sin, cos), this.carryScratch);

					turn.set((float)r[0], (float)r[3], (float)r[6], (float)r[1], (float)r[4], (float)r[7], (float)r[2], (float)r[5], (float)r[8]);
				}
			}
		}

		// R - I
		Matrix3f less = this.carryLess.set(turn);

		less.m00 -= 1f;
		less.m11 -= 1f;
		less.m22 -= 1f;

		Matrix3f g = this.carryG.set(mesh.restToRegion);
		Matrix3f a = this.carryA.set(mesh.toMesh).mul(less).mul(g);

		mesh.restToRegion.getTranslation(this.carryVector);
		v.set((float)(this.carryVector.x - c[0]), (float)(this.carryVector.y - c[1]), (float)(this.carryVector.z - c[2]));
		less.transform(v);
		v.add((float)moved[0], (float)moved[1], (float)moved[2]);
		mesh.toMesh.transform(v);

		float[] o = mesh.rigid.offset;

		o[0] = a.m00;
		o[1] = a.m10;
		o[2] = a.m20;
		o[3] = v.x;
		o[4] = a.m01;
		o[5] = a.m11;
		o[6] = a.m21;
		o[7] = v.y;
		o[8] = a.m02;
		o[9] = a.m12;
		o[10] = a.m22;
		o[11] = v.z;

		Matrix3f t = this.carryT.set(mesh.restToRegionInverse).mul(turn).mul(g);
		float[] n = mesh.rigid.turn;

		n[0] = t.m00;
		n[1] = t.m10;
		n[2] = t.m20;
		n[3] = t.m01;
		n[4] = t.m11;
		n[5] = t.m21;
		n[6] = t.m02;
		n[7] = t.m12;
		n[8] = t.m22;
		mesh.rigid.changed();
	}

	/** {@code exp(L tanh(ln(1 + volume x) / L))} with {@code L = -ln 0.75}: the same as {@code 1 + volume x} near 1, and never below 0.75 or above 4/3 */
	private static double stretch(double x, double volume) {
		double l = -Math.log(0.75);
		double inner = Math.max(0.05, 1 + volume * x);

		return Math.exp(l * Math.tanh(Math.log(inner) / l));
	}

	/** The rotation about the unit axis by {@code angle}, row major */
	private static double[] rotation(double x, double y, double z, double angle, double[] out) {
		double c = Math.cos(angle);
		double s = Math.sin(angle);
		double k = 1 - c;

		out[0] = c + x * x * k;
		out[1] = x * y * k - z * s;
		out[2] = x * z * k + y * s;
		out[3] = y * x * k + z * s;
		out[4] = c + y * y * k;
		out[5] = y * z * k - x * s;
		out[6] = z * x * k - y * s;
		out[7] = z * y * k + x * s;
		out[8] = c + z * z * k;

		return out;
	}

	/** Scratch for the swing's turn and a carried piece's, so a frame's blend allocates nothing */
	private final double[] turnScratch = new double[9];
	private final double[] carryScratch = new double[9];

	/** Hands each moved mesh, with how it is moved, to {@code sink}: a region at rest hands nothing, and is drawn as modelled */
	public void deforms(BiConsumer<StrutMesh, MeshDeform> sink) {
		if (!this.hasOutput)
			return;

		for (Part part : this.parts) {
			if (!part.moving)
				continue;

			for (MeshPart mesh : part.meshes) {
				if (mesh.usable)
					sink.accept(mesh.mesh, mesh.carried ? mesh.rigid : CageDeform.gpu && mesh.cage != null ? mesh.cage : mesh.deform);
			}
		}
	}

	/** The largest node displacement of any region after the last step, blocks, for tests and the debug line */
	public double largestDisplacement() {
		double most = 0;

		for (Part part : this.parts) {
			double[] d = part.region.displacement;

			for (int i = 0; i < FleshCageGeometry.NODES; i++)
				most = Math.max(most, Math.sqrt(d[i * 3] * d[i * 3] + d[i * 3 + 1] * d[i * 3 + 1] + d[i * 3 + 2] * d[i * 3 + 2]));
		}

		return most;
	}

	/** The free surface nodes the cursor tool offers, about {@code limit} of them over the regions, as indices for {@link #nodePosition} and {@link #hold} */
	public int[] pickable(int limit) {
		List<Integer> found = new ArrayList<>();
		int each = Math.max(1, limit / Math.max(1, this.parts.length));

		for (int p = 0; p < this.parts.length; p++) {
			Part part = this.parts[p];
			List<Integer> free = new ArrayList<>();

			for (int i = 0; i < FleshCageGeometry.SURFACE; i++) {
				if (part.cage.anchor[i] < 0.9)
					free.add(p * FleshCageGeometry.SURFACE + i);
			}

			int every = Math.max(1, free.size() / each);

			for (int i = 0; i < free.size(); i += every)
				found.add(free.get(i));
		}

		return found.stream().mapToInt(Integer::intValue).toArray();
	}

	/** Where surface node {@code index} (see {@link #pickable}) is now, world */
	public void nodePosition(int index, double[] out) {
		FleshSolver.Region r = this.parts[index / FleshCageGeometry.SURFACE].region;
		int at = (index % FleshCageGeometry.SURFACE) * 3;

		out[0] = r.x[at];
		out[1] = r.x[at + 1];
		out[2] = r.x[at + 2];
	}

	/** Holds surface node {@code index} at {@code target} (world), or lets go of every node if {@code target} is null */
	public void hold(int index, double[] target) {
		for (Part part : this.parts)
			part.region.holdNode = -1;

		if (target == null || index < 0 || index >= this.parts.length * FleshCageGeometry.SURFACE)
			return;

		FleshSolver.Region r = this.parts[index / FleshCageGeometry.SURFACE].region;

		r.holdNode = index % FleshCageGeometry.SURFACE;
		System.arraycopy(target, 0, r.holdTarget, 0, 3);
	}

	/** How many of the cage's nodes something presses on, and the deepest any is pressed in, blocks, for the debug line */
	public int pressing() {
		int count = 0;

		for (Part part : this.parts)
			count += part.region.pressedNodes();

		return count;
	}

	public double deepestPress() {
		double most = 0;

		for (Part part : this.parts)
			most = Math.max(most, part.region.deepestPress());

		return most;
	}

	/** How many of the regions are resting, for the debug line */
	public int resting() {
		int count = 0;

		for (Part part : this.parts) {
			if (FleshSolver.asleep(part.region))
				count++;
		}

		return count;
	}

	/** Each region's limit, blocks, for the debug line */
	public double limit() {
		double limit = 0;

		for (Part part : this.parts)
			limit = Math.max(limit, part.cage.limit);

		return limit;
	}

	/** The vertices this soft body moves, for the debug line */
	public int vertices() {
		int count = 0;

		for (Part part : this.parts) {
			for (MeshPart mesh : part.meshes)
				count += mesh.particles();
		}

		return count;
	}

	/** The meshes' per-particle offsets as last written, for tests */
	public List<float[]> offsets() {
		List<float[]> out = new ArrayList<>();

		for (Part part : this.parts) {
			for (MeshPart mesh : part.meshes) {
				if (!mesh.carried)
					out.add(mesh.deform.offsets);
			}
		}

		return out;
	}
}
