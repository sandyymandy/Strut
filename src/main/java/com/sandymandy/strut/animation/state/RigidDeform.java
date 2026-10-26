package com.sandymandy.strut.animation.state;

import com.sandymandy.strut.cache.object.StrutMesh;
import com.sandymandy.strut.cache.object.StrutPoly;
import org.joml.Vector3f;

/**
 * A mesh that moves as one piece (a tip riding on a pouch): every corner is moved by {@code offset = A * position + b} of its own modelled position, in the
 * mesh's own space, and its normal is turned by {@code T}. The GPU applies it to each vertex itself, so the writer gives twelve numbers and nine, not a value for
 * every corner
 */
public final class RigidDeform implements MeshDeform {
	private final StrutMesh mesh;
	/** The three rows of {@code A | b}: {@code a00 a01 a02 b0, a10 a11 a12 b1, a20 a21 a22 b2} */
	public final float[] offset = new float[12];
	/** The three rows of {@code T} */
	public final float[] turn = new float[9];
	private float[] cornerOffsets;
	private float[] cornerNormals;
	private boolean expanded;

	public RigidDeform(StrutMesh mesh) {
		this.mesh = mesh;
	}

	public StrutMesh mesh() {
		return this.mesh;
	}

	/** Tells this the numbers have been refilled, so the corner form made from the last fill is stale */
	public void changed() {
		this.expanded = false;
	}

	@Override
	public float[] cornerOffsets() {
		expand();

		return this.cornerOffsets;
	}

	@Override
	public float[] cornerNormals() {
		expand();

		return this.cornerNormals;
	}

	private void expand() {
		if (this.expanded)
			return;

		StrutPoly[] polys = this.mesh.polys();

		if (this.cornerOffsets == null) {
			this.cornerOffsets = new float[polys.length * 12];
			this.cornerNormals = new float[polys.length * 12];
		}

		float[] a = this.offset;
		float[] t = this.turn;

		for (int p = 0; p < polys.length; p++) {
			for (int corner = 0; corner < 4; corner++) {
				int at = (p * 4 + corner) * 3;
				Vector3f v = polys[p].vertices()[corner].position();
				Vector3f own = polys[p].vertices()[corner].normal();

				this.cornerOffsets[at] = a[0] * v.x + a[1] * v.y + a[2] * v.z + a[3];
				this.cornerOffsets[at + 1] = a[4] * v.x + a[5] * v.y + a[6] * v.z + a[7];
				this.cornerOffsets[at + 2] = a[8] * v.x + a[9] * v.y + a[10] * v.z + a[11];

				float nx = t[0] * own.x + t[1] * own.y + t[2] * own.z;
				float ny = t[3] * own.x + t[4] * own.y + t[5] * own.z;
				float nz = t[6] * own.x + t[7] * own.y + t[8] * own.z;
				float lengthSquared = nx * nx + ny * ny + nz * nz;

				if (lengthSquared < 1e-12f) {
					nx = own.x;
					ny = own.y;
					nz = own.z;
					lengthSquared = nx * nx + ny * ny + nz * nz;
				}

				float scale = lengthSquared < 1e-12f ? 1f : (float)(1.0 / Math.sqrt(lengthSquared));

				this.cornerNormals[at] = nx * scale;
				this.cornerNormals[at + 1] = ny * scale;
				this.cornerNormals[at + 2] = nz * scale;
			}
		}

		this.expanded = true;
	}
}
