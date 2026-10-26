package com.sandymandy.strut.cache.object;

import org.joml.Vector3f;

/**
 * A single baked face of a {@link StrutMesh}
 * <p>
 * Always four vertices, even for a triangle, whose last vertex is simply repeated. Minecraft's
 * entity render types are {@code QUADS}-mode, so a triangle has to reach the buffer as a
 * degenerate quad regardless; doing that once at bake time rather than per-frame keeps the
 * render loop a flat walk over uniform data
 * <p>
 * This is not a rare case. Blockbench triangulates on export, and a real authored character
 * model is triangles from end to end - so the degenerate quad is the ordinary shape here, not
 * the exception
 *
 * @param vertices The four corners, wound so the face points along {@code normal}
 * @param normal The face's outward normal, computed at bake from the wound corners
 */
public record StrutPoly(StrutVertex[] vertices, Vector3f normal) {}
