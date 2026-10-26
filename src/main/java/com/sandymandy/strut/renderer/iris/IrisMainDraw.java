package com.sandymandy.strut.renderer.iris;

import com.mojang.blaze3d.systems.RenderSystem;
import com.sandymandy.strut.renderer.gpu.GpuModel;
import com.sandymandy.strut.renderer.gpu.GpuRenderBackend;
import java.util.Arrays;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.util.Identifier;
import org.joml.Matrix4f;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.system.MemoryStack;

/**
 * Draws {@link IrisPrePass}'s
 * packed output through the pack's own, unmodified {@code rendertype_entity_cutout_no_cull} program
 * rather than {@code GpuRenderBackend}'s raw-GL replica. Only ever meaningful while
 * {@link IrisCompat#isActive()} is true - {@code GameRenderer.getRenderTypeEntityCutoutNoNullProgram()}
 * is an ordinary vanilla method (Iris mixes into it, transparent to any caller), so calling it never
 * touches an Iris class directly and needs none of {@code IrisApiAccess}'s own isolation; without an
 * active pack it would just return vanilla's own plain entity shader, which expects vanilla's own
 * (shorter, `iris_Entity`/`mc_midTexCoord`/`at_tangent`-less) vertex format instead of this class's
 * packed buffer - the reason every call site gates on {@link IrisCompat#isActive()} first
 * <p>
 * <b>Attribute binding is positional, not name-queried</b> (not looked up with {@code glGetAttribLocation}). Real
 * `VertexFormat.setupBufferState()`/`VertexFormatElement.setupBufferState(int attributeIndex, ...)`
 * (confirmed via {@code javap} against the real 1.21.1 client jar) bind each element at a fixed index
 * equal to its position in the format's own element list - exactly the order
 * {@code IrisVertexFormats.ENTITY}'s builder declares them in, which is what this class's own
 * {@code glVertexAttribPointer} calls replicate by hand (real {@code setupBufferState()} isn't called
 * directly - it would assume that format's own unpadded 54-byte stride, wrong for
 * {@link IrisPrePass}'s 56-byte-padded packed buffer, see that class's own doc comment)
 */
public final class IrisMainDraw {
	private static int vao;
	private static boolean vaoReady;

	private IrisMainDraw() {}

	/**
	 * Draws one group's already-posed-and-packed output ({@link IrisPrePass#runPosePass} must have
	 * already run this frame for this group) through the pack's own program, one
	 * {@code glMultiDrawElementsBaseVertex} sub-draw per instance over {@code model}'s own existing
	 * index buffer, each based at that instance's own slice of the shared packed buffer
	 * <p>
	 * @return Whether anything was drawn: false when the pack supplied no program for this render type, which was previously
	 * indistinguishable from a successful draw
	 * <p>
	 * Silently does nothing if {@link IrisCompat#isActive()} is false, or if the pack hasn't (yet, or
	 * any longer) substituted a real program for this render type - {@code StrutRenderer}'s own
	 * check is what keeps a Strut entity off this path in either of those cases,
	 * so this method should never actually be called while either is true, but declines rather than
	 * drawing garbage if it somehow is anyway
	 * <p>
	 * {@code modelViewMatrix}/{@code projMatrix} are passed explicitly rather than read from a
	 * {@code WorldRenderContext} - 
	 * since the shadow pass has no such context at all (it runs from inside Iris's own
	 * {@code ShadowRenderer}, entirely outside Fabric's {@code WorldRenderEvents}). The main pass's own
	 * caller ({@code GpuBatchQueue.flush}) still sources these the same way it always did
	 * ({@code WorldRenderContext#positionMatrix()}/{@code #projectionMatrix()}); the shadow pass's own
	 * caller ({@code GpuBatchQueue.flushShadowQueue}, reached from a mixin on vanilla
	 * {@code RenderLayer.draw(BuiltBuffer)}) instead reads
	 * {@code RenderSystem.getModelViewMatrix()}/{@code #getProjectionMatrix()} - whatever Iris's own
	 * shadow render call left there, since that call is still on the stack when the mixin fires
	 */
	public static boolean draw(GpuModel model, Identifier texture, int instanceCount, int quadCount, Matrix4f modelViewMatrix,
					  Matrix4f projMatrix) {
		return draw(model, texture, instanceCount, quadCount, modelViewMatrix, projMatrix, false, false);
	}

	/**
	 * As above. {@code translucent} draws through the pack's translucent entity program (Iris falls back to its plain entity program when the
	 * pack has none) instead of the cutout one; {@code depthPrepass} first writes the nearest surface's depth with colour masked off, then draws,
	 * so a blended mesh shows only its nearest layer. The caller sets the blend state.
	 */
	public static boolean draw(GpuModel model, Identifier texture, int instanceCount, int quadCount, Matrix4f modelViewMatrix,
					  Matrix4f projMatrix, boolean translucent, boolean depthPrepass) {
		return draw(model, texture, instanceCount, quadCount, modelViewMatrix, projMatrix, translucent, depthPrepass, null);
	}

	/**
	 * As above, leaving out index ranges of some instances: {@code skipRanges[instance]}, if not null, is a list of {@code (start, count)} pairs in
	 * the model's index buffer, ascending and not overlapping, that this instance's draw does not cover. A bone whose {@code TEXTURE} override
	 * replaces the model's own texture is left out this way, and drawn by {@link #drawBoneRange} with the override instead, so the model's texture
	 * does not show through the override's transparent texels
	 */
	public static boolean draw(GpuModel model, Identifier texture, int instanceCount, int quadCount, Matrix4f modelViewMatrix,
					  Matrix4f projMatrix, boolean translucent, boolean depthPrepass, @Nullable int[][] skipRanges) {
		ShaderProgram program = begin(model, texture, modelViewMatrix, projMatrix, translucent);

		if (program == null)
			return false;

		Segments segs = segments(model.indexCount(), quadCount * 4, instanceCount, skipRanges);
		int[] counts = segs.counts();
		long[] starts = segs.starts();
		int[] baseVertices = segs.baseVertices();
		int used = counts.length;

		// the index buffer is read from the start unless a range is skipped: the segment's own start
		try (MemoryStack stack = MemoryStack.stackPush()) {
			PointerBuffer indices = stack.mallocPointer(used);

			for (int segment = 0; segment < used; segment++) {
				indices.put(segment, starts[segment]);
			}

			if (depthPrepass) {
				prepassOn();
				GL32.glMultiDrawElementsBaseVertex(GL11.GL_TRIANGLES, counts, GL11.GL_UNSIGNED_INT, indices, baseVertices);
				prepassOff();
			}

			GL32.glMultiDrawElementsBaseVertex(GL11.GL_TRIANGLES, counts, GL11.GL_UNSIGNED_INT, indices, baseVertices);
		}

		end(program);

		return true;
	}

	/** The sub-draws of one multi-draw: for each, its index count, its start in the index buffer (bytes) and its base vertex */
	record Segments(int[] counts, long[] starts, int[] baseVertices) {}

	/**
	 * Splits each instance's draw of the whole index buffer into the stretches that are not in its {@code skipRanges} ({@code (start, count)} pairs,
	 * ascending, not overlapping; null for none)
	 */
	static Segments segments(int indexCount, int vertexCount, int instanceCount, @Nullable int[][] skipRanges) {
		int max = 0;

		for (int instance = 0; instance < instanceCount; instance++)
			max += 1 + (skipRanges == null || skipRanges[instance] == null ? 0 : skipRanges[instance].length / 2);

		int[] counts = new int[max];
		long[] starts = new long[max];
		int[] baseVertices = new int[max];
		int used = 0;

		for (int instance = 0; instance < instanceCount; instance++) {
			int[] skip = skipRanges == null ? null : skipRanges[instance];
			int from = 0;

			if (skip != null) {
				for (int i = 0; i < skip.length; i += 2) {
					if (skip[i] > from) {
						counts[used] = skip[i] - from;
						starts[used] = (long) from * Integer.BYTES;
						baseVertices[used++] = instance * vertexCount;
					}

					from = skip[i] + skip[i + 1];
				}
			}

			if (indexCount > from) {
				counts[used] = indexCount - from;
				starts[used] = (long) from * Integer.BYTES;
				baseVertices[used++] = instance * vertexCount;
			}
		}

		return new Segments(Arrays.copyOf(counts, used), Arrays.copyOf(starts, used), Arrays.copyOf(baseVertices, used));
	}

	/**
	 * The {@code TEXTURE} redraws of many bones and instances that share one texture, in one multi-draw: segment {@code i} is {@code segCount[i]} indices from
	 * {@code segStart[i]} of the instance {@code segInstance[i]} of the chunk just drawn. What one call for each bone of each instance cost (a skin on a bone
	 * with a hundred bones under it, on a hundred models) is one call for each texture
	 */
	public static void drawBoneRanges(GpuModel model, Identifier texture, int quadCount, int[] segInstance, int[] segStart, int[] segCount, int segments,
									  Matrix4f modelViewMatrix, Matrix4f projMatrix, boolean translucent) {
		ShaderProgram program = begin(model, texture, modelViewMatrix, projMatrix, translucent);

		if (program == null)
			return;

		RenderSystem.depthFunc(GL11.GL_LEQUAL);

		int[] counts = Arrays.copyOf(segCount, segments);
		int[] baseVertices = new int[segments];

		try (MemoryStack stack = MemoryStack.stackPush()) {
			PointerBuffer indices = stack.mallocPointer(segments);

			for (int i = 0; i < segments; i++) {
				indices.put(i, (long) segStart[i] * Integer.BYTES);
				baseVertices[i] = segInstance[i] * quadCount * 4;
			}

			GL32.glMultiDrawElementsBaseVertex(GL11.GL_TRIANGLES, counts, GL11.GL_UNSIGNED_INT, indices, baseVertices);
		}

		end(program);
	}

	/**
	 * A per-bone {@code TEXTURE} override: redraws just {@code indexCount} indices starting at
	 * {@code indexStart} (one bone's own contiguous range, {@link GpuModel#boneIndexStart}) of one
	 * instance of the chunk just drawn, with {@code texture} bound instead. Composited over that
	 * instance's main draw rather than excluding the bone from it - both draws land at identical depth
	 * from identical vertices, so the same {@code GL_LEQUAL} test the raw path's own override relies on
	 * lets the second draw's cutout fragments overwrite the first's
	 * <p>
	 * The instance's packed vertices are already in the buffer from the main draw's own pose pass, so
	 * this is one {@code glDrawElementsBaseVertex} with that instance's own base vertex
	 */
	public static void drawBoneRange(GpuModel model, Identifier texture, int instanceInChunk, int quadCount, int indexStart,
									 int indexCount, Matrix4f modelViewMatrix, Matrix4f projMatrix) {
		drawBoneRange(model, texture, instanceInChunk, quadCount, indexStart, indexCount, modelViewMatrix, projMatrix, false, false);
	}

	/**
	 * As above, optionally through the translucent program and with a depth pre-pass (see {@link #draw(GpuModel, Identifier, int, int, Matrix4f,
	 * Matrix4f, boolean, boolean)}).
	 */
	public static void drawBoneRange(GpuModel model, Identifier texture, int instanceInChunk, int quadCount, int indexStart,
									 int indexCount, Matrix4f modelViewMatrix, Matrix4f projMatrix, boolean translucent, boolean depthPrepass) {
		ShaderProgram program = begin(model, texture, modelViewMatrix, projMatrix, translucent);

		if (program == null)
			return;

		RenderSystem.depthFunc(GL11.GL_LEQUAL);

		if (depthPrepass) {
			prepassOn();
			GL32.glDrawElementsBaseVertex(GL11.GL_TRIANGLES, indexCount, GL11.GL_UNSIGNED_INT, (long)indexStart * Integer.BYTES,
					instanceInChunk * quadCount * 4);
			prepassOff();
		}

		GL32.glDrawElementsBaseVertex(GL11.GL_TRIANGLES, indexCount, GL11.GL_UNSIGNED_INT, (long)indexStart * Integer.BYTES,
				instanceInChunk * quadCount * 4);

		end(program);
	}

	// Colour masked off, depth written: the nearest surface's depth for a blended draw that follows
	private static void prepassOn() {
		RenderSystem.colorMask(false, false, false, false);
		RenderSystem.depthMask(true);
	}

	private static void prepassOff() {
		RenderSystem.depthMask(false);
		RenderSystem.colorMask(true, true, true, true);
	}

	private static int previousTexture;
	private static int previousOverlay;
	private static int previousLightmap;

	/**
	 * Everything both draws share: gate, program lookup, texture slots, uniforms then bind, VAO and
	 * index buffer. {@code null} when there is nothing to draw through (see {@link #draw}'s own doc)
	 */
	private static ShaderProgram begin(GpuModel model, Identifier texture, Matrix4f modelViewMatrix, Matrix4f projMatrix, boolean translucent) {
		if (!IrisCompat.isActive())
			return null;

		ShaderProgram program = translucent ? GameRenderer.getRenderTypeEntityTranslucentProgram() : GameRenderer.getRenderTypeEntityCutoutNoNullProgram();

		if (program == null)
			return null;

		ensureVao();

		int textureGlId = MinecraftClient.getInstance().getTextureManager().getTexture(texture).getGlId();

		previousTexture = RenderSystem.getShaderTexture(0);
		previousOverlay = RenderSystem.getShaderTexture(1);
		previousLightmap = RenderSystem.getShaderTexture(2);

		var gameRenderer = MinecraftClient.getInstance().gameRenderer;

		// Slots 1/2 as well as 0 - Iris's own ExtendedShader.setupTextures (real source) binds the
		// overlay and lightmap from RenderSystem's shader-texture slots 1 and 2 on apply(), exactly
		// where vanilla's entity render layers put them. Only slot 0 used to be set here, so the pack
		// lit these entities with whatever overlay/lightmap the last unrelated draw left behind
		RenderSystem.setShaderTexture(0, textureGlId);
		RenderSystem.setShaderTexture(1, gameRenderer.getOverlayTexture().texture.getGlId());
		RenderSystem.setShaderTexture(2, gameRenderer.getLightmapTextureManager().texture.getGlId());

		// initializeUniforms before bind(), never after - vanilla's own VertexBuffer._drawWithShader
		// (javap-confirmed) calls setDefaultUniforms then apply(), because apply() is what uploads the
		// uniform values to GL. The reverse order uploaded whatever this program last held - the
		// previous frame's camera - live-seen as an F7 copy lagging behind the real entity while the
		// camera turned, worse at low fps
		program.initializeUniforms(VertexFormat.DrawMode.TRIANGLES, modelViewMatrix, projMatrix,
				MinecraftClient.getInstance().getWindow());
		program.bind();

		GL30.glBindVertexArray(vao);
		GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, model.ebo());

		return program;
	}

	private static void end(ShaderProgram program) {
		GpuRenderBackend.restoreOuterVao();
		program.unbind();

		// The shadow flush runs in the middle of Iris's own batched shadow draw, whose next segment
		// relies on the textures its own render state already set - restored, not left pointing at
		// this group's
		RenderSystem.setShaderTexture(0, previousTexture);
		RenderSystem.setShaderTexture(1, previousOverlay);
		RenderSystem.setShaderTexture(2, previousLightmap);
	}

	/**
	 * Builds the VAO's own attribute bindings once, against {@link IrisPrePass}'s packed buffer - safe
	 * to set up before that buffer holds any real data, since {@code glVertexAttribPointer} records
	 * only the buffer id and byte offsets/stride, not its current contents or size, and
	 * {@code IrisPrePass} never replaces that buffer's id once created (only grows it in place via
	 * {@code glBufferData} on the same name) - see {@code GpuBatchQueue}'s own shared-buffer doc
	 * comment for the same "one id, reused every frame via orphaning" reasoning
	 */
	private static void ensureVao() {
		if (vaoReady)
			return;

		vaoReady = true;
		vao = GL30.glGenVertexArrays();

		GL30.glBindVertexArray(vao);
		GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, IrisPrePass.packedSsbo());

		int stride = IrisPrePass.PACKED_VERTEX_STRIDE_WORDS * Integer.BYTES;

		// Byte offsets match IrisVertexFormats.ENTITY's own element order and sizes exactly (see
		// IrisPrePass.PACK_COMPUTE_SOURCE's own doc comment) - only the stride between vertices (56,
		// not that format's own 54) differs from what VertexFormat.setupBufferState() would compute
		GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, stride, 0L);
		GL20.glEnableVertexAttribArray(0);
		GL20.glVertexAttribPointer(1, 4, GL11.GL_UNSIGNED_BYTE, true, stride, 12L);
		GL20.glEnableVertexAttribArray(1);
		GL20.glVertexAttribPointer(2, 2, GL11.GL_FLOAT, false, stride, 16L);
		GL20.glEnableVertexAttribArray(2);
		// UV1 (overlay)/UV2 (light) are read as raw integers by vanilla's own entity shader (ivec2),
		// not normalized floats - GL30.glVertexAttribIPointer, the same distinction GpuModel's own
		// BoneIndex attribute already relies on
		GL30.glVertexAttribIPointer(3, 2, GL11.GL_SHORT, stride, 24L);
		GL20.glEnableVertexAttribArray(3);
		GL30.glVertexAttribIPointer(4, 2, GL11.GL_SHORT, stride, 28L);
		GL20.glEnableVertexAttribArray(4);
		GL20.glVertexAttribPointer(5, 3, GL11.GL_BYTE, true, stride, 32L);
		GL20.glEnableVertexAttribArray(5);
		GL30.glVertexAttribIPointer(6, 3, GL11.GL_UNSIGNED_SHORT, stride, 36L);
		GL20.glEnableVertexAttribArray(6);
		GL20.glVertexAttribPointer(7, 2, GL11.GL_FLOAT, false, stride, 44L);
		GL20.glEnableVertexAttribArray(7);
		GL20.glVertexAttribPointer(8, 4, GL11.GL_BYTE, true, stride, 52L);
		GL20.glEnableVertexAttribArray(8);

		GpuRenderBackend.restoreOuterVao();
		GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
	}
}
