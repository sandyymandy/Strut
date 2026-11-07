package com.sandymandy.strut.renderer.gpu;

import com.sandymandy.strut.animation.state.CageBindings;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL43;

/**
 * The GPU's copy of {@link CageBindings}: what never changes for each flesh particle, uploaded when something has been added to it and not otherwise (a flesh
 * is added once when it is made, however many instances have it). Render thread only, like the rest of the backend
 */
final class CageBuffer {
	private static int ssbo;
	private static int uploaded = -1;

	private CageBuffer() {}

	/** The buffer, brought up to date; made (a few words, nothing to read) the first time */
	static int sync() {
		if (ssbo == 0)
			ssbo = GL15.glGenBuffers();

		int generation = CageBindings.generation();

		if (generation != uploaded) {
			GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, ssbo);
			GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, CageBindings.snapshot(), GL15.GL_STATIC_DRAW);
			uploaded = generation;
		}

		return ssbo;
	}
}
