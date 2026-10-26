package com.sandymandy.strut.physics.world;

/**
 * Where the physics learns what is solid around an entity: the collision boxes of the blocks in a volume, in world coordinates. The game's implementation
 * reads the client world; a test supplies a floor
 */
public interface WorldSource {
	/** Calls {@code sink} once for every solid box that overlaps the volume (blocks, world space) */
	void boxes(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, BoxSink sink);

	/** The liquid in the column of blocks at this place (from a block under it to several above), or null if there is none; a source with no liquids need not say */
	default Liquid liquid(double x, double y, double z) {
		return null;
	}

	@FunctionalInterface
	public interface BoxSink {
		void box(double x0, double y0, double z0, double x1, double y1, double z1);
	}
}
