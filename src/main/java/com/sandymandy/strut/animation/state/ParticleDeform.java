package com.sandymandy.strut.animation.state;

import com.sandymandy.strut.cache.object.MeshWeld;
import com.sandymandy.strut.cache.object.StrutMesh;
import com.sandymandy.strut.cache.object.StrutPoly;
import org.joml.Vector3f;

/**
 * A deformation given for each welded particle of the mesh (see {@link MeshWeld}): how far the particle is moved, in the mesh's own space, and how far the
 * shading normal of the corners at it has turned. A corner is moved by its particle's offset and shaded by its own modelled normal plus its particle's change
 * <p>
 * This is what cloth and flesh have after a step, so it costs the writer a few hundred numbers where a value for every corner costs thousands; the GPU expands it
 * to the corners itself. The arrays are filled in place every frame by the writer, which calls {@link #changed} when it has
 */
public final class ParticleDeform implements MeshDeform {
	private final StrutMesh mesh;
	private final int[] cornerParticle;
	/** x, y, z for each particle */
	public final float[] offsets;
	/** x, y, z for each particle: added to the modelled normal of each corner there */
	public final float[] normalDeltas;
	private float[] cornerOffsets;
	private float[] cornerNormals;
	private boolean expanded;

	public ParticleDeform(StrutMesh mesh, MeshWeld weld) {
		this.mesh = mesh;
		this.cornerParticle = weld.cornerParticle();
		this.offsets = new float[weld.particles() * 3];
		this.normalDeltas = new float[weld.particles() * 3];
	}

	public StrutMesh mesh() {
		return this.mesh;
	}

	public int particles() {
		return this.offsets.length / 3;
	}

	/** Tells this the arrays have been refilled, so the corner form made from the last fill is stale */
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

		for (int p = 0; p < polys.length; p++) {
			for (int corner = 0; corner < 4; corner++) {
				int at = (p * 4 + corner) * 3;
				int k = this.cornerParticle[p * 4 + corner] * 3;
				Vector3f own = polys[p].vertices()[corner].normal();

				this.cornerOffsets[at] = this.offsets[k];
				this.cornerOffsets[at + 1] = this.offsets[k + 1];
				this.cornerOffsets[at + 2] = this.offsets[k + 2];

				float nx = own.x + this.normalDeltas[k];
				float ny = own.y + this.normalDeltas[k + 1];
				float nz = own.z + this.normalDeltas[k + 2];
				float lengthSquared = nx * nx + ny * ny + nz * nz;

				if (lengthSquared < 1e-12f) {
					nx = 0f;
					ny = 1f;
					nz = 0f;
					lengthSquared = 1f;
				}

				float scale = (float)(1.0 / Math.sqrt(lengthSquared));

				this.cornerNormals[at] = nx * scale;
				this.cornerNormals[at + 1] = ny * scale;
				this.cornerNormals[at + 2] = nz * scale;
			}
		}

		this.expanded = true;
	}
}
