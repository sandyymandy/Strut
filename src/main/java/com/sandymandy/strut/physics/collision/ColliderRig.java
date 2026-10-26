package com.sandymandy.strut.physics.collision;

import com.sandymandy.strut.animation.state.Pose;
import com.sandymandy.strut.animation.state.PosePalette;
import com.sandymandy.strut.cache.object.BakedStrutModel;
import com.sandymandy.strut.cache.object.GroupBoneDefinition;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import com.sandymandy.strut.physics.PhysicsInstance;
import com.sandymandy.strut.physics.config.PhysicsConfig;
import com.sandymandy.strut.physics.solver.Kinematics;

/**
 * The collision shapes of one model instance: each rides a bone, so it is placed from the animated pose once per frame (in the model's space) and then into
 * the world for every step, following the entity
 */
public final class ColliderRig {
	private final Collider[] specs;
	/** The field of each shape that is a mesh, or null */
	private final MeshSdf[] fields;
	/** Whether each shape was made from the model's own geometry (those are left out for the bone they belong to, its parents and its children) */
	private final boolean[] auto;
	private final GroupBoneDefinition[] boneOfShape;
	private final GroupBoneDefinition[] bones;
	private final Collider.Placed[] inModel;
	private final Collider.Placed[] inWorld;
	private final Matrix3f[] orientation;
	private final Matrix4f matrix = new Matrix4f();
	private final Vector3f v = new Vector3f();
	private final Matrix3f turn = new Matrix3f();

	public ColliderRig(BakedStrutModel model, List<Collider> colliders, List<String> missing) {
		this(model, colliders, List.of(), missing);
	}

	public ColliderRig(BakedStrutModel model, List<Collider> authored, List<Collider> generated, List<String> missing) {
		List<Collider> colliders = new ArrayList<>(authored);
		List<Collider> kept = new ArrayList<>();
		List<GroupBoneDefinition> keptBones = new ArrayList<>();
		List<Boolean> keptAuto = new ArrayList<>();

		colliders.addAll(generated);

		for (Collider collider : colliders) {
			Optional<GroupBoneDefinition> bone = model.getBone(collider.bone());

			if (bone.isPresent()) {
				kept.add(collider);
				keptBones.add(bone.get());
				keptAuto.add(generated.contains(collider) && !authored.contains(collider));
			}
			else if (!missing.contains(collider.bone())) {
				missing.add(collider.bone());
			}
		}

		boolean anyMesh = kept.stream().anyMatch(c -> c.shape() == Collider.Shape.MESH);
		ModelSdfs.ModelSdf sdfs = anyMesh ? ModelSdfs.get(model) : null;

		// a mesh shape without its field (the model was reloaded under it) is dropped
		for (int i = kept.size() - 1; i >= 0; i--) {
			if (kept.get(i).shape() == Collider.Shape.MESH && (sdfs == null || sdfs.of(kept.get(i).bone()) == null)) {
				kept.remove(i);
				keptBones.remove(i);
				keptAuto.remove(i);
			}
		}

		this.specs = kept.toArray(new Collider[0]);
		this.fields = new MeshSdf[this.specs.length];

		for (int i = 0; i < this.specs.length; i++) {
			if (this.specs[i].shape() == Collider.Shape.MESH)
				this.fields[i] = sdfs.of(this.specs[i].bone());
		}

		this.bones = keptBones.toArray(new GroupBoneDefinition[0]);
		this.boneOfShape = this.bones;
		this.auto = new boolean[this.specs.length];

		for (int i = 0; i < this.auto.length; i++)
			this.auto[i] = keptAuto.get(i);

		this.inModel = new Collider.Placed[this.specs.length];
		this.inWorld = new Collider.Placed[this.specs.length];
		this.orientation = new Matrix3f[this.specs.length];

		for (int i = 0; i < this.specs.length; i++) {
			this.inModel[i] = new Collider.Placed();
			this.inWorld[i] = new Collider.Placed();
			this.orientation[i] = new Matrix3f();
		}
	}

	public int count() {
		return this.specs.length;
	}

	/** Places every shape in the model's space from the animated pose: blocks, turning with its bone */
	public void prepare(Pose animated) {
		for (int i = 0; i < this.specs.length; i++) {
			Collider c = this.specs[i];
			Collider.Placed m = this.inModel[i];

			PosePalette.computeBoneAtPivot(animated, this.bones[i], this.matrix);

			Vector3f origin = this.matrix.transformPosition(0f, 0f, 0f, new Vector3f());

			this.orientation[i].set(this.matrix);
			PhysicsInstance.stripScale(this.orientation[i], this.v);

			m.shape = c.shape();
			m.layer = c.layer();
			if (c.shape() != Collider.Shape.MESH)
				set(m.p, origin, this.orientation[i], c.a());

			switch (c.shape()) {
				case SPHERE -> m.radius = c.radius() / 16.0;
				case CAPSULE -> {
					set(m.q, origin, this.orientation[i], c.b());
					m.radius = c.radius() / 16.0;
				}
				case MESH -> {
					// the bounding sphere of the grid, in the bone's space then in the model's; the bone's pivot is kept in q
					MeshSdf f = this.fields[i];

					m.sdf = f;
					m.q[0] = origin.x;
					m.q[1] = origin.y;
					m.q[2] = origin.z;

					Vector3f centre = this.orientation[i].transform(new Vector3f(f.cx, f.cy, f.cz));

					m.p[0] = origin.x + centre.x;
					m.p[1] = origin.y + centre.y;
					m.p[2] = origin.z + centre.z;
					m.radius = f.radius;
				}
				case BOX -> {
					for (int a = 0; a < 3; a++)
						m.half[a] = Math.abs(c.b().get(a)) / 32.0;

					// a box turned on its bone: its axes are the bone's, turned by its own rotation (its centre was placed with the bone's alone)
					if (c.rotation().lengthSquared() > 0f)
						this.orientation[i].mul(c.turn());
				}
			}
		}
	}

	/**
	 * Puts the shapes whose layer is in {@code layers} (every shape if it is null) into world space for a step at {@code (ex, ey, ez)} facing {@code yaw},
	 * appending them to {@code out}
	 */
	public void place(double ex, double ey, double ez, float yaw, Set<String> layers, List<Collider.Placed> out) {
		place(ex, ey, ez, yaw, layers, null, out);
	}

	/**
	 * For a part made of {@code partBones}: which shapes to leave out. Only generated shapes are ever left out (an authored collider is exactly what its
	 * author wants), and those on the part's own bones, their parents and their children: a jiggling pouch overlaps the torso it hangs from by design
	 */
	boolean[] relatedMask(GroupBoneDefinition... partBones) {
		boolean[] skip = new boolean[this.specs.length];

		for (int i = 0; i < skip.length; i++) {
			if (!this.auto[i])
				continue;

			for (GroupBoneDefinition part : partBones)
				skip[i] |= related(this.boneOfShape[i], part);
		}

		return skip;
	}

	/** {@link #relatedMask}, and also leaving out every shape on a bone {@code filter} does not allow (made or authored alike) */
	public boolean[] mask(PhysicsConfig.BoneFilter filter, GroupBoneDefinition... partBones) {
		boolean[] skip = relatedMask(partBones);

		for (int i = 0; i < skip.length; i++)
			skip[i] |= !filter.allows(this.boneOfShape[i].getName());

		return skip;
	}

	private static boolean related(GroupBoneDefinition a, GroupBoneDefinition b) {
		for (GroupBoneDefinition x = a; x != null; x = x.getParent()) {
			if (x == b)
				return true;
		}

		for (GroupBoneDefinition x = b; x != null; x = x.getParent()) {
			if (x == a)
				return true;
		}

		return false;
	}

	/** {@link #place(double, double, double, float, Set, List)}, leaving out the shapes {@code skip} marks (may be null) */
	public void place(double ex, double ey, double ez, float yaw, Set<String> layers, boolean[] skip, List<Collider.Placed> out) {
		for (int i = 0; i < this.specs.length; i++) {
			if ((layers != null && !layers.contains(this.specs[i].layer())) || (skip != null && skip[i]))
				continue;

			Collider.Placed m = this.inModel[i];
			Collider.Placed w = this.inWorld[i];

			w.shape = m.shape;
			w.layer = m.layer;
			w.generated = this.auto[i];
			w.radius = m.radius;
			toWorld(m.p, w.p, ex, ey, ez, yaw);

			if (m.shape == Collider.Shape.CAPSULE || m.shape == Collider.Shape.MESH)
				toWorld(m.q, w.q, ex, ey, ez, yaw);

			if (m.shape == Collider.Shape.MESH) {
				w.sdf = m.sdf;
				this.turn.identity().rotateY((float)Math.toRadians(180f - yaw)).mul(this.orientation[i], w.axes);
			}

			if (m.shape == Collider.Shape.BOX) {
				System.arraycopy(m.half, 0, w.half, 0, 3);
				this.turn.identity().rotateY((float)Math.toRadians(180f - yaw)).mul(this.orientation[i], w.axes);
			}

			out.add(w);
		}
	}

	private void toWorld(double[] model, double[] world, double ex, double ey, double ez, float yaw) {
		this.v.set((float)model[0], (float)model[1], (float)model[2]);
		Kinematics.modelToWorld(this.v, yaw);
		world[0] = ex + this.v.x;
		world[1] = ey + this.v.y;
		world[2] = ez + this.v.z;
	}

	/** {@code origin + orientation * (local / 16)} into {@code out} */
	private void set(double[] out, Vector3f origin, Matrix3f orientation, Vector3f local) {
		Vector3f p = orientation.transform(new Vector3f(local).div(16f));

		out[0] = origin.x + p.x;
		out[1] = origin.y + p.y;
		out[2] = origin.z + p.z;
	}

	/** Every shape in world space for the last {@link #place}, for the debug lines: (x, y, z) centres and radii */
	Collider.Placed[] worldShapes() {
		return this.inWorld;
	}
}
