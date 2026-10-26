package com.sandymandy.strut.physics.world;

import net.minecraft.client.MinecraftClient;
import net.minecraft.fluid.FluidState;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;

/**
 * The client world's solid boxes, for chains to collide with: the collision shape of every block in the volume, stairs and slabs by their real boxes
 */
public final class WorldBlocks implements WorldSource {
	public static final WorldBlocks INSTANCE = new WorldBlocks();

	private WorldBlocks() {}

	@Override
	public Liquid liquid(double x, double y, double z) {
		MinecraftClient client = MinecraftClient.getInstance();

		if (client == null || client.world == null)
			return null;

		BlockPos.Mutable pos = new BlockPos.Mutable(MathHelper.floor(x), 0, MathHelper.floor(z));
		int base = MathHelper.floor(y);
		int bottom = Integer.MIN_VALUE;
		double surface = 0;
		boolean lava = false;
		float flowX = 0;
		float flowZ = 0;

		// the first run of liquid from a block under the feet to a few above the head, and where its top is
		for (int by = base - 1; by <= base + 4; by++) {
			pos.setY(by);

			FluidState fluid = client.world.getFluidState(pos);

			if (fluid.isEmpty()) {
				if (bottom != Integer.MIN_VALUE)
					break;

				continue;
			}

			if (bottom == Integer.MIN_VALUE) {
				bottom = by;
				lava = fluid.isIn(FluidTags.LAVA);

				Vec3d flow = fluid.getVelocity(client.world, pos);

				flowX = (float) flow.x;
				flowZ = (float) flow.z;
			}

			surface = by + fluid.getHeight(client.world, pos);
		}

		return bottom == Integer.MIN_VALUE ? null : new Liquid(bottom, surface, flowX, flowZ, lava);
	}

	@Override
	public void boxes(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, BoxSink sink) {
		MinecraftClient client = MinecraftClient.getInstance();

		if (client == null || client.world == null)
			return;

		for (VoxelShape shape : client.world.getBlockCollisions(null, new Box(minX, minY, minZ, maxX, maxY, maxZ)))
			shape.forEachBox(sink::box);
	}
}
