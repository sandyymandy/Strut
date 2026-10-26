package com.sandymandy.strut.renderer.gpu;

import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL43;

/**
 * Saves and restores the {@code GL_SHADER_STORAGE_BUFFER} indexed binding points this engine's GPU
 * paths use (0-8), around every GPU flush
 * <p>
 * Live-caught, and the worst bug of the Iris path: Iris binds a shader pack's own SSBOs to
 * their binding points exactly once, when the pack's pipeline is created
 * ({@code ShaderStorageBufferHolder.setupBuffers()}, real source), and never re-binds them. Every
 * {@code glBindBufferBase} here silently replaced them for the rest of the session, so the pack's
 * shaders read and wrote this engine's buffers instead of their own - including a model's baked
 * vertex buffer and skin stream at bindings 5/6, which the pack then wrote into. The corrupted
 * geometry is cached, so it stayed corrupted even with shaders turned off, until a restart; and the
 * pack's own SSBO state (typically camera/position history) was lost, which is why the damage
 * tracked side-to-side movement
 * <p>
 * Iris binds by base ({@code glBindBufferBase}), so restoring each saved buffer id the same way
 * restores its bindings exactly. A pack's program must never run while this engine's buffers are
 * bound - {@code GpuBatchQueue} restores before every {@code IrisMainDraw} draw, and the compute
 * passes re-bind what they need themselves
 */
public final class SsboBindingGuard {
	/** One past the highest binding point any of this engine's shaders use (IrisPrePass's packed output, 8, and its deformation inputs, 9 to 11) */
	private static final int BINDING_COUNT = 12;

	private static final int[] SAVED = new int[BINDING_COUNT];

	private SsboBindingGuard() {}

	public static void save() {
		for (int i = 0; i < BINDING_COUNT; i++) {
			SAVED[i] = GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING, i);
		}
	}

	public static void restore() {
		for (int i = 0; i < BINDING_COUNT; i++) {
			GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, i, SAVED[i]);
		}
	}
}
