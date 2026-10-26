package com.sandymandy.strut.cache.object;

import net.minecraft.util.math.Vec3d;

/**
 * Baked cuboid for a {@link GroupBoneDefinition}
 */
public record StrutCube(StrutQuad[] quads, Vec3d pivot, Vec3d rotation, Vec3d size, double inflate, boolean mirror) {}
