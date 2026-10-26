package com.sandymandy.strut.renderer.gpu;

import com.sandymandy.strut.animation.state.MeshDeform;
import com.sandymandy.strut.animation.state.ModelInstance;
import com.sandymandy.strut.animation.state.ParticleDeform;
import com.sandymandy.strut.animation.state.RigidDeform;
import com.sandymandy.strut.cache.object.StrutMesh;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.BufferUtils;

import java.nio.ByteBuffer;
import java.util.function.IntFunction;

/**
 * What a group's instances move their mesh vertices by this frame (cloth, flesh and soft bodies: {@link ModelInstance#setMeshDeform}), packed as the one buffer
 * the vertex shader and the shader-pack pose pass read
 * <p>
 * The buffer is a run of 32-bit words, three parts:
 * <ol>
 * <li>a table, one word for each instance and mesh ({@code instance * meshCount + mesh}): {@code -1} if that instance does not move that mesh (most meshes of most
 * instances: the table is small and the rest is only for what moves), else where its record starts and how it is read: {@code w >= 0} is a value for each
 * corner, starting at slot {@code w}; {@code w <= -2} is {@code -2 - w = slot * 2 + kind}, kind 0 a value for each welded particle (see
 * {@link com.sandymandy.strut.cache.object.MeshWeld}) starting at {@code slot}, kind 1 one rigid motion in three slots from {@code slot}</li>
 * <li>the offsets, a slot being four words (x, y, z, and a pad), meshes of all instances one after another. A particle's slot is its offset; a rigid mesh's three
 * slots are the rows of {@code A | b} of {@code offset = A * position + b}</li>
 * <li>the normals, in the same slots, only when some moved mesh has its own (x, y, z, and {@code 1}); a corner slot of a mesh without them has {@code 0} for the
 * last word, so the shader takes a flat face normal there instead. A particle's slot is how far the normals of the corners at it have turned (added to each
 * corner's own); a rigid mesh's three slots are the rows of the matrix that turns its normals</li>
 * </ol>
 * A corner of mesh {@code m}, as {@code polygon * 4 + corner}, is found at {@code table[instance * meshCount + m] + corner}; one given for each particle is found
 * by the corner's particle in place of the corner. Built once for a group, whatever is drawn from it (the main draw, a texture redraw, an overlay), since every
 * draw reads an instance's place in the group from the instance data. Reused between frames: nothing is allocated for a frame that has no deformation, and the
 * buffer only grows
 */
public final class MeshDeformPack {
	/** The slots of a rigid mesh's record: the three rows of its motion */
	static final int RIGID_SLOTS = 3;

	private ByteBuffer data = BufferUtils.createByteBuffer(256);
	private int words;
	private int offsetBase;
	private int normalBase;
	private int meshCount;
	private boolean hasNormals;

	/** How many slots a mesh's deformation takes in the pack, or 0 if it takes none (nothing, or an array of the wrong size) */
	private static int slotsOf(StrutMesh mesh, @Nullable MeshDeform deform) {
		if (deform == null)
			return 0;

		if (deform instanceof ParticleDeform particles)
			return particles.particles();

		if (deform instanceof RigidDeform)
			return RIGID_SLOTS;

		return valid(mesh, deform.cornerOffsets()) != null ? mesh.polys().length * 4 : 0;
	}

	/**
	 * Packs what {@code instanceCount} instances of a model with {@code meshes} move, from the instance {@code instances} gives for each (null for one that
	 * moves nothing). Returns whether anything moves at all: if not, there is nothing to draw with and the buffer is not to be used
	 */
	public boolean build(StrutMesh[] meshes, int instanceCount, IntFunction<ModelInstance> instances) {
		this.meshCount = meshes.length;

		int table = instanceCount * this.meshCount;
		int entries = 0;
		boolean normals = false;

		for (int i = 0; i < instanceCount; i++) {
			ModelInstance instance = instances.apply(i);

			if (instance == null || !instance.hasMeshOffsets())
				continue;

			for (StrutMesh mesh : meshes) {
				MeshDeform deform = instance.meshDeform(mesh);
				int slots = slotsOf(mesh, deform);

				if (slots == 0)
					continue;

				entries += slots;
				normals |= deform instanceof ParticleDeform || deform instanceof RigidDeform || valid(mesh, deform.cornerNormals()) != null;
			}
		}

		if (entries == 0)
			return false;

		// the limit of the last build was its own size: absolute writes past it throw, so the whole buffer is open again before this one fills it
		this.data.clear();

		this.hasNormals = normals;
		this.offsetBase = table;
		this.normalBase = table + entries * 4;
		this.words = table + entries * 4 * (normals ? 2 : 1);

		if (this.data.capacity() < this.words * 4)
			this.data = BufferUtils.createByteBuffer(Math.max(this.words * 4, this.data.capacity() * 2));

		int entry = 0;

		for (int i = 0; i < instanceCount; i++) {
			ModelInstance instance = instances.apply(i);
			boolean moves = instance != null && instance.hasMeshOffsets();

			for (int m = 0; m < this.meshCount; m++) {
				MeshDeform deform = moves ? instance.meshDeform(meshes[m]) : null;
				int slots = slotsOf(meshes[m], deform);
				int slot = i * this.meshCount + m;

				if (slots == 0) {
					this.data.putInt(slot * 4, -1);

					continue;
				}

				if (deform instanceof ParticleDeform particles) {
					this.data.putInt(slot * 4, -2 - entry * 2);
					writeParticles(particles, entry);
				}
				else if (deform instanceof RigidDeform rigid) {
					this.data.putInt(slot * 4, -2 - (entry * 2 + 1));
					writeRigid(rigid, entry);
				}
				else {
					this.data.putInt(slot * 4, entry);
					writeCorners(meshes[m], deform, entry, normals);
				}

				entry += slots;
			}
		}

		this.data.limit(this.words * 4).position(0);

		return true;
	}

	private void writeParticles(ParticleDeform deform, int entry) {
		float[] offsets = deform.offsets;
		float[] deltas = deform.normalDeltas;

		for (int k = 0; k < offsets.length / 3; k++) {
			int at = (this.offsetBase + (entry + k) * 4) * 4;
			int normalAt = (this.normalBase + (entry + k) * 4) * 4;

			this.data.putFloat(at, offsets[k * 3]);
			this.data.putFloat(at + 4, offsets[k * 3 + 1]);
			this.data.putFloat(at + 8, offsets[k * 3 + 2]);
			this.data.putFloat(at + 12, 0f);
			this.data.putFloat(normalAt, deltas[k * 3]);
			this.data.putFloat(normalAt + 4, deltas[k * 3 + 1]);
			this.data.putFloat(normalAt + 8, deltas[k * 3 + 2]);
			this.data.putFloat(normalAt + 12, 1f);
		}
	}

	private void writeRigid(RigidDeform deform, int entry) {
		for (int row = 0; row < RIGID_SLOTS; row++) {
			int at = (this.offsetBase + (entry + row) * 4) * 4;
			int normalAt = (this.normalBase + (entry + row) * 4) * 4;

			this.data.putFloat(at, deform.offset[row * 4]);
			this.data.putFloat(at + 4, deform.offset[row * 4 + 1]);
			this.data.putFloat(at + 8, deform.offset[row * 4 + 2]);
			this.data.putFloat(at + 12, deform.offset[row * 4 + 3]);
			this.data.putFloat(normalAt, deform.turn[row * 3]);
			this.data.putFloat(normalAt + 4, deform.turn[row * 3 + 1]);
			this.data.putFloat(normalAt + 8, deform.turn[row * 3 + 2]);
			this.data.putFloat(normalAt + 12, 1f);
		}
	}

	private void writeCorners(StrutMesh mesh, MeshDeform deform, int entry, boolean normals) {
		float[] offsets = deform.cornerOffsets();
		int corners = offsets.length / 3;
		float[] meshNormals = normals ? valid(mesh, deform.cornerNormals()) : null;

		for (int k = 0; k < corners; k++) {
			int at = (this.offsetBase + (entry + k) * 4) * 4;

			this.data.putFloat(at, offsets[k * 3]);
			this.data.putFloat(at + 4, offsets[k * 3 + 1]);
			this.data.putFloat(at + 8, offsets[k * 3 + 2]);
			this.data.putFloat(at + 12, 0f);

			if (normals) {
				int normalAt = (this.normalBase + (entry + k) * 4) * 4;

				this.data.putFloat(normalAt, meshNormals != null ? meshNormals[k * 3] : 0f);
				this.data.putFloat(normalAt + 4, meshNormals != null ? meshNormals[k * 3 + 1] : 0f);
				this.data.putFloat(normalAt + 8, meshNormals != null ? meshNormals[k * 3 + 2] : 0f);
				this.data.putFloat(normalAt + 12, meshNormals != null ? 1f : 0f);
			}
		}
	}

	/** {@code values} if it has one entry (three floats) for each corner of {@code mesh}, else null: an array of another size would read into the next mesh's */
	@Nullable
	private static float[] valid(StrutMesh mesh, @Nullable float[] values) {
		return values != null && values.length == mesh.polys().length * 4 * 3 ? values : null;
	}

	/** The packed words, from position 0 to the limit, for the upload; after a {@link #build} that returned true */
	public ByteBuffer data() {
		return this.data;
	}

	/** Where the offsets start, in words */
	public int offsetBase() {
		return this.offsetBase;
	}

	/** Where the normals start, in words (meaningful only when {@link #hasNormals()}) */
	public int normalBase() {
		return this.normalBase;
	}

	/** How many meshes the model has: the table's row length */
	public int meshCount() {
		return this.meshCount;
	}

	/** Whether any moved mesh gave normals, so the normal part exists */
	public boolean hasNormals() {
		return this.hasNormals;
	}

	/** The packed size in words */
	public int words() {
		return this.words;
	}
}
