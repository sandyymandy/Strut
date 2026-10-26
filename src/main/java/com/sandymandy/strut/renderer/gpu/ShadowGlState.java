package com.sandymandy.strut.renderer.gpu;

import com.mojang.blaze3d.systems.RenderSystem;
import com.sandymandy.strut.StrutConstants;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/**
 * The GL state around Strut's Iris shadow draw
 * <p>
 * A vanilla entity is drawn through a {@code RenderLayer}, and {@code RenderLayer.startDrawing} sets the state the draw
 * needs: depth test on, {@code LEQUAL}, depth writes on, blending off. Strut's shadow draw does not go through a render layer,
 * so it used whatever Iris happened to leave current at that moment. A shadow caster that does not write depth is invisible
 * to the shader pack, which fits "cows cast shadows, Strut entities do not"
 * <p>
 * {@link #prepare} sets that state and remembers what it replaced; {@link #restore} puts it back, so Iris's own batched
 * shadow draw a moment later sees exactly what it expects. For the first few flushes it also logs the state it found, so a
 * shadow that is still missing can be diagnosed from {@code latest.log} instead of guessed at
 */
final class ShadowGlState {
	private static final int LOGGED_FLUSHES = 3;
	private static int logged;

	private final boolean depthTest;
	private final boolean blend;
	private final boolean depthMask;
	private final int depthFunc;

	private ShadowGlState(boolean depthTest, boolean blend, boolean depthMask, int depthFunc) {
		this.depthTest = depthTest;
		this.blend = blend;
		this.depthMask = depthMask;
		this.depthFunc = depthFunc;
	}

	/**
	 * Reads the current state, logs it for the first few calls, then sets the state a vanilla entity draw would have
	 *
	 * @param instances How many entities are about to be drawn, for the log line
	 */
	static ShadowGlState prepare(int instances) {
		ShadowGlState previous = new ShadowGlState(GL11.glIsEnabled(GL11.GL_DEPTH_TEST), GL11.glIsEnabled(GL11.GL_BLEND),
				GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK), GL11.glGetInteger(GL11.GL_DEPTH_FUNC));

		if (logged < LOGGED_FLUSHES) {
			logged++;
			log(previous, instances);
		}

		RenderSystem.enableDepthTest();
		RenderSystem.depthFunc(GL11.GL_LEQUAL);
		RenderSystem.depthMask(true);
		RenderSystem.disableBlend();

		return previous;
	}

	void restore() {
		RenderSystem.depthMask(this.depthMask);
		RenderSystem.depthFunc(this.depthFunc);

		if (this.depthTest)
			RenderSystem.enableDepthTest();
		else
			RenderSystem.disableDepthTest();

		if (this.blend)
			RenderSystem.enableBlend();
		else
			RenderSystem.disableBlend();
	}

	private static void log(ShadowGlState state, int instances) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			ByteBuffer colourMask = stack.malloc(4);
			IntBuffer viewport = stack.mallocInt(4);

			GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, colourMask);
			GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);

			StrutConstants.LOGGER.info("[Strut shadow] flush #{}: {} instance(s); on entry depthTest={}, depthMask={}, depthFunc={}, blend={}, "
							+ "cull={}, colorMask={}{}{}{}, program={}, drawFramebuffer={}, viewport={}x{}",
					logged, instances, state.depthTest, state.depthMask, state.depthFunc, state.blend, GL11.glIsEnabled(GL11.GL_CULL_FACE),
					colourMask.get(0) != 0, colourMask.get(1) != 0, colourMask.get(2) != 0, colourMask.get(3) != 0,
					GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM), GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING),
					viewport.get(2), viewport.get(3));
		}
	}
}
