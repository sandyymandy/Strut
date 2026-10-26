package com.sandymandy.strut.cache.object;

import org.joml.Vector3f;

/**
 * Vertex data holder
 *
 * @param position The position of the vertex
 * @param texU The texture U coordinate
 * @param texV The texture V coordinate
 * @param normal The vertex's own normal, distinct from its poly's face normal only when the
 *               owning mesh is smooth-shaded; otherwise a copy of the face normal
 */
public record StrutVertex(Vector3f position, float texU, float texV, Vector3f normal) {
	public StrutVertex(double x, double y, double z) {
		this(new Vector3f((float)x, (float)y, (float)z), 0, 0, new Vector3f());
	}

	public StrutVertex withUVs(float texU, float texV) {
		return new StrutVertex(this.position, texU, texV, this.normal);
	}

	public StrutVertex withNormal(Vector3f normal) {
		return new StrutVertex(this.position, this.texU, this.texV, normal);
	}
}