package com.sandymandy.strut.animation.state;

import org.jetbrains.annotations.Nullable;

/**
 * A flesh mesh moved by its cage, left to the GPU: the writer gives the cage's own numbers for each region of the mesh (the nodes' displacements, the swing's
 * turn and stretch, the pair's plane: about a thousand words, whatever the mesh), and the vertex shader works out each vertex's offset and shading from them and
 * from what never changes ({@link CageBindings}). A mesh of thousands of vertices costs the CPU what a region of 163 nodes does
 * <p>
 * A reader that wants a value for each poly corner (the CPU walk, a mod) asks for it, and the writer works the vertices out for it then ({@link #fallbackWith})
 * <p>
 * Each region's record is {@link #REGION_WORDS} floats, laid out as:
 * <pre>
 *   0   toMesh, three rows: the region's frame into the mesh's own space
 *   9   flags: 1 turn, 2 stretch, 4 plane, 8 dressed
 *   10  the swing, in the region's frame        13  the pivot        16  the middle of the region
 *   19  turn, three rows                        28  stretch, three rows
 *   37  the plane's point   40  its normal   43  how far past it the region rests   44  its soft edge   45  the rim   46  the room a dressed region swells
 *   47  weight
 *   48  the 163 nodes' displacements, x y z each (the evened-out ones)           537  the 162 surface nodes' change of normal
 * </pre>
 */
public final class CageDeform implements MeshDeform {
	/** Whether a renderer that reads this is there: when it is not, the writer works out every vertex itself, as it did before there was one */
	public static volatile boolean gpu;

	public static final int NODES = 163;
	public static final int SURFACE = 162;
	public static final int TO_MESH = 0;
	public static final int FLAGS = 9;
	public static final int SWING = 10;
	public static final int PIVOT = 13;
	public static final int MIDDLE = 16;
	public static final int TURN = 19;
	public static final int STRETCH = 28;
	public static final int PLANE_POINT = 37;
	public static final int PLANE_NORMAL = 40;
	public static final int PLANE_ALLOW = 43;
	public static final int PLANE_EDGE = 44;
	public static final int RIM = 45;
	public static final int ROOM = 46;
	public static final int WEIGHT = 47;
	public static final int NODE_DISPLACEMENTS = 48;
	public static final int NORMAL_CHANGES = NODE_DISPLACEMENTS + NODES * 3;
	/** A region's record, padded to whole slots of four words */
	public static final int REGION_WORDS = 1024;
	public static final int REGION_SLOTS = REGION_WORDS / 4;

	public static final int FLAG_TURN = 1;
	public static final int FLAG_STRETCH = 2;
	public static final int FLAG_PLANE = 4;
	public static final int FLAG_DRESSED = 8;

	/**
	 * What never changes for each welded particle, in words: the region of the mesh that owns it (-1 for none), how many nodes it follows, the nodes (6), their
	 * weights (6, floats), how far out it is (surface), how free it is (softness) and where it sits in the region's frame (x, y, z), and a pad
	 */
	public static final int PARTICLE_WORDS = 20;
	public static final int PARTICLE_REGION = 0;
	public static final int PARTICLE_COUNT = 1;
	public static final int PARTICLE_NODES = 2;
	public static final int PARTICLE_WEIGHTS = 8;
	public static final int PARTICLE_SURFACE = 14;
	public static final int PARTICLE_SOFTNESS = 15;
	public static final int PARTICLE_AT = 16;
	public static final int MOST_NODES = 6;

	/** The regions' records, one after another */
	public final float[] blocks;
	private final int regions;
	private final int bindingBase;
	private final ParticleDeform fallback;
	private Runnable compute;
	private boolean stale = true;

	/**
	 * @param fallback What the corner form is made from when something asks for it
	 * @param regions How many regions of the mesh are moved (a mesh that holds two parts has two)
	 * @param bindingBase Where this mesh's particles start in {@link CageBindings}
	 */
	public CageDeform(ParticleDeform fallback, int regions, int bindingBase) {
		this.fallback = fallback;
		this.regions = regions;
		this.bindingBase = bindingBase;
		this.blocks = new float[regions * REGION_WORDS];
	}

	public int regions() {
		return this.regions;
	}

	public int bindingBase() {
		return this.bindingBase;
	}

	public ParticleDeform fallback() {
		return this.fallback;
	}

	/** Sets what works the vertices out when a reader wants them (it fills {@link #fallback()}) */
	public void fallbackWith(Runnable compute) {
		this.compute = compute;
	}

	/** Tells this the records have been refilled, so the corner form made from the last fill is stale */
	public void changed() {
		this.stale = true;
	}

	/** Gives region {@code region} no deformation: its record is nothing, and every vertex it owns is where the bone puts it */
	public void clear(int region) {
		java.util.Arrays.fill(this.blocks, region * REGION_WORDS, (region + 1) * REGION_WORDS, 0f);
		changed();
	}

	/** Brings {@link #fallback()} up to date with the records, when someone asks for it */
	private void bring() {
		if (this.stale && this.compute != null)
			this.compute.run();

		this.stale = false;
	}

	@Override
	public float[] cornerOffsets() {
		bring();

		return this.fallback.cornerOffsets();
	}

	@Override
	@Nullable
	public float[] cornerNormals() {
		bring();

		return this.fallback.cornerNormals();
	}
}
