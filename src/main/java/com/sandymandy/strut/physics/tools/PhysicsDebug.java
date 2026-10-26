package com.sandymandy.strut.physics.tools;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.BuiltBuffer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import com.sandymandy.strut.physics.PhysicsInstance;
import com.sandymandy.strut.physics.PhysicsPoseProcessor;

/**
 * What the physics draws in the world after the entities: the debug lines of {@code /strutphysics debug on} (every spring bone and chain segment) and, while
 * the cursor tool is open, a cross on every part you can pick up
 */
public final class PhysicsDebug {
	/** Written by the command on the client thread, read while drawing */
	static volatile boolean drawing;

	private PhysicsDebug() {}

	public static void draw(WorldRenderContext context) {
		boolean tool = PhysicsToolState.open;

		if ((!drawing && !tool) || context.matrixStack() == null || PhysicsPoseProcessor.LIVE.isEmpty())
			return;

		// Drawn last and with the depth test off, so the lines show through the entity they belong to instead of hiding inside it
		BufferBuilder lines = Tessellator.getInstance().begin(VertexFormat.DrawMode.LINES, VertexFormats.LINES);
		MatrixStack.Entry entry = context.matrixStack().peek();
		Matrix4f matrix = entry.getPositionMatrix();
		Vec3d camera = context.camera().getPos();

		for (PhysicsToolState.Cross cross : PhysicsToolState.crosses) {
			for (int axis = 0; axis < 3; axis++) {
				double dx = axis == 0 ? cross.size() : 0;
				double dy = axis == 1 ? cross.size() : 0;
				double dz = axis == 2 ? cross.size() : 0;

				line(lines, entry, matrix, camera, cross.x() - dx, cross.y() - dy, cross.z() - dz, cross.x() + dx, cross.y() + dy, cross.z() + dz, cross.rgb());
			}
		}

		if (drawing) {
			for (PhysicsInstance instance : PhysicsPoseProcessor.LIVE.values())
				instance.debugLines((x1, y1, z1, x2, y2, z2, rgb) -> line(lines, entry, matrix, camera, x1, y1, z1, x2, y2, z2, rgb));
		}

		// nothing to draw (no crosses, debug lines off or no simulated parts yet): ending an empty builder throws
		BuiltBuffer built = lines.endNullable();

		if (built == null)
			return;

		RenderSystem.disableDepthTest();
		RenderSystem.setShader(GameRenderer::getRenderTypeLinesProgram);
		RenderSystem.lineWidth(3f);
		BufferRenderer.drawWithGlobalProgram(built);
		RenderSystem.lineWidth(1f);
		RenderSystem.enableDepthTest();
	}

	private static void line(VertexConsumer lines, MatrixStack.Entry entry, Matrix4f matrix, Vec3d camera, double x1, double y1, double z1, double x2, double y2, double z2, int rgb) {
		float r = ((rgb >> 16) & 0xFF) / 255f;
		float g = ((rgb >> 8) & 0xFF) / 255f;
		float b = (rgb & 0xFF) / 255f;
		float dx = (float)(x2 - x1);
		float dy = (float)(y2 - y1);
		float dz = (float)(z2 - z1);
		float length = (float)Math.sqrt(dx * dx + dy * dy + dz * dz);
		float nx = length > 0 ? dx / length : 0;
		float ny = length > 0 ? dy / length : 1;
		float nz = length > 0 ? dz / length : 0;

		lines.vertex(matrix, (float)(x1 - camera.x), (float)(y1 - camera.y), (float)(z1 - camera.z)).color(r, g, b, 1f).normal(entry, nx, ny, nz);
		lines.vertex(matrix, (float)(x2 - camera.x), (float)(y2 - camera.y), (float)(z2 - camera.z)).color(r, g, b, 1f).normal(entry, nx, ny, nz);
	}
}
