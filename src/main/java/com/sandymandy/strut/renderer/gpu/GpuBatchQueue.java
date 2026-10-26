package com.sandymandy.strut.renderer.gpu;

import com.mojang.blaze3d.systems.RenderSystem;
import com.sandymandy.strut.animation.state.BoneRenderLayers;
import com.sandymandy.strut.animation.state.BoneTextureOverrides;
import com.sandymandy.strut.animation.state.BoneVisuals;
import com.sandymandy.strut.animation.state.ModelInstance;
import com.sandymandy.strut.animation.state.Pose;
import com.sandymandy.strut.animation.state.PosePalette;
import com.sandymandy.strut.animation.state.SkinningState;
import com.sandymandy.strut.cache.object.BakedStrutModel;
import com.sandymandy.strut.cache.object.StrutArmature;
import com.sandymandy.strut.model.DepthBias;
import com.sandymandy.strut.model.ShadingMode;
import com.sandymandy.strut.renderer.hook.StrutRenderHooks;
import com.sandymandy.strut.perf.RenderPerfStats;
import com.sandymandy.strut.renderer.iris.IrisCompat;
import com.sandymandy.strut.renderer.iris.IrisMainDraw;
import com.sandymandy.strut.renderer.iris.IrisPrePass;
import com.sandymandy.strut.util.Color;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.function.IntPredicate;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL43;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Collects one frame's worth of GPU-eligible {@link com.sandymandy.strut.renderer.StrutRenderer#actuallyRender}
 * calls, grouped by
 * {@code (GpuModel, texture)}, and draws each group as one instanced draw call on
 * {@link WorldRenderEvents#AFTER_ENTITIES}
 * - the step that turns entities' render calls into GPU draws
 * <p>
 * {@link com.sandymandy.strut.renderer.StrutRenderer#actuallyRender} is the interception point: {@code handleAnimations} and
 * {@code preRender}'s own {@code ModelInstance#prepareForRender} call have already run by then, so
 * {@link com.sandymandy.strut.animation.state.ModelInstance#effectivePose()} ({@code renderedPose()} plus any active bone override)
 * is already resolved and current. {@code poseStack.peek().getPositionMatrix()} is the entity's camera-relative-but-unrotated
 * transform (translation plus whatever yaw, scale, sit or sleep the calling renderer applied). See {@link GpuRenderBackend}'s own
 * doc comment for why that matters: it is folded directly into each submission's own bone matrices in {@link #drawGroup}, with
 * nothing left over for a separate camera-offset uniform
 * <p>
 * Every eligible entity always renders through this path
 */
public final class GpuBatchQueue {
	/**
	 * One submission's worth of state, captured at {@link #submit} time rather than read again at
	 * {@link #flush} time - {@code entityTransform} specifically must be a copy
	 * ({@code new Matrix4f(poseStack.peek().getPositionMatrix())}), since the {@code MatrixStack}
	 * entry it came from is popped and reused for the next entity in the same frame. {@code pose}
	 * (this entity's {@code effectivePose()}) does not need copying the same way: nothing mutates it
	 * again before the next frame's own {@code preRender} - {@code StrutRenderLayer}s do not run for a
	 * GPU-eligible model, so there is no re-render call that could touch it a second time this frame.
	 * {@code skinningState} is non-null exactly when {@code model} has at least one skinned mesh
	 * - read lazily in {@link #drawGroup}, the same way
	 * {@code pose} is, rather than flattened into a buffer here, since not every group needs it
	 */
	private record Submission(BakedStrutModel model, Matrix4f entityTransform, Pose pose, @Nullable BoneVisuals visuals,
							   @Nullable BoneTextureOverrides textures, @Nullable BoneRenderLayers layers, @Nullable SkinningState skinningState,
							   int packedLight, int packedOverlay, int colour, int irisEntityId, int irisBlockEntityItemIds,
							   @Nullable ModelInstance deform, @Nullable LightProbe.Rig lights, float[] custom) {}

	/**
	 * The group key two submissions must share to be drawn together - same baked geometry (identity,
	 * via {@link GpuModelCache}'s own keying) and same texture. {@code GpuModel} has no custom
	 * {@code equals}/{@code hashCode}, so this record's generated ones fall back to identity for it,
	 * which is exactly right: {@link GpuModelCache} already guarantees one {@code GpuModel} instance
	 * per {@code BakedStrutModel} identity
	 */
	private record GroupKey(GpuModel gpuModel, Identifier texture, RenderKey render) {}

	/**
	 * What a model asks of the draw beyond its geometry and texture, part of the group key
	 * because each is one uniform or GL state for a whole instanced call: two instances that disagree cannot share a draw
	 *
	 * @param translucent Drawn blended, after the opaque geometry
	 * @param bias The polygon offset for this model's translucent bones and overlays
	 * @param cullBackFaces Whether the blended draws drop back faces
	 * @param overrideReplacesBase Whether a bone with a {@code TEXTURE} override is left out of the main draw, so the override texture replaces the model's own
	 *                             there instead of being drawn over it (a skin's transparent texels then show what is behind, not the model's texture)
	 * @param additive A translucent model is added to what is under it (its alpha says how much) instead of covering it: an enchantment's glint. Only the raw
	 *                 path does this; under a shader pack the pack's own translucent blending is used
	 * @param shading How the model is shaded by the way its surfaces face ({@link com.sandymandy.strut.model.ShadingMode}); the raw path only
	 * @param overlayDepth Whether an overlay writes its own nearest surface's depth first (a pre-pass) as a translucent bone does, so its faces are not blended in
	 *                     mesh order and what is drawn after it (terrain's clouds, translucent water) goes behind it. An overlay on a bone whose own texels are
	 *                     transparent has no depth of the bone's to rely on without it. Raw path only
	 */
	public record RenderKey(boolean translucent, DepthBias bias, boolean cullBackFaces, boolean overrideReplacesBase, boolean additive,
							boolean overlayDepth, ShadingMode shading) {
		public static final RenderKey DEFAULT = new RenderKey(false, DepthBias.DEFAULT, false, true);

		public RenderKey(boolean translucent, DepthBias bias, boolean cullBackFaces, boolean overrideReplacesBase) {
			this(translucent, bias, cullBackFaces, overrideReplacesBase, false, false, ShadingMode.DEFAULT);
		}

		public RenderKey(boolean translucent, DepthBias bias, boolean cullBackFaces, boolean overrideReplacesBase, boolean additive) {
			this(translucent, bias, cullBackFaces, overrideReplacesBase, additive, false, ShadingMode.DEFAULT);
		}
	}

	/** The fixed normal a shader pack lights a model of {@link com.sandymandy.strut.model.ShadingMode#FLAT} by: a pack has no shading modes of its own to switch to */
	private static final Vector3f PACK_FLAT_NORMAL = new Vector3f(0f, 0.6f, 0f);

	private static Vector3f packFlatNormal(RenderKey key) {
		return key.shading() == ShadingMode.FLAT ? PACK_FLAT_NORMAL : null;
	}

	/** Whether a glint is kept to the armour's opaque texels by an exact depth test; -Dstrut.glintMask=false draws it over the whole model as before, if a driver does not give the two draws the same depth */
	private static final boolean GLINT_MASK = !"false".equalsIgnoreCase(System.getProperty("strut.glintMask"));

	private static final Map<GroupKey, List<Submission>> GROUPS = new LinkedHashMap<>();

	/**
	 * Submissions made between {@link #beginImmediate()} and {@link #endImmediate()}: an entity drawn into a screen (the inventory's paper doll, a menu's
	 * character), drawn at once, in the screen's own view, instead of when the world has drawn its entities
	 */
	private static final Map<GroupKey, List<Submission>> IMMEDIATE_GROUPS = new LinkedHashMap<>();

	/** Whether submissions are being gathered for an immediate draw: they go to {@link #IMMEDIATE_GROUPS}, and the draw never goes through a shader pack */
	private static boolean immediate;

	private static final int[] capturedIds = new int[3];

	/**
	 * A submission
	 * made while {@code IrisCompat.isRenderingShadowPass()} is true goes here instead of {@link #GROUPS}.
	 * Iris re-renders every entity a second time from the shadow camera's own point of view
	 * (`ShadowRenderer.renderEntities`), so without this split a shadow-pass submission's own
	 * shadow-space-relative pose would land in the same instanced draw as the main pass's
	 * camera-space-relative one - live-seen as a second, wildly mispositioned copy of the entity
	 * floating in whatever direction the shadow camera happens to be offset, exactly what the
	 * first real Iris screenshots showed.
	 * Flushed separately by {@link #flushShadowQueue()}, not on {@code WorldRenderEvents.AFTER_ENTITIES}
	 * - the shadow pass runs entirely inside Iris's own {@code ShadowRenderer}, with no Fabric world-
	 * render event of its own
	 */
	private static final Map<GroupKey, List<Submission>> SHADOW_GROUPS = new LinkedHashMap<>();

	/**
	 * Reused every group, every frame, rather than one pair of buffer objects per group - each
	 * {@code glBufferData} call on the render thread fully replaces the previous contents (buffer
	 * orphaning), which is well-defined even if the GPU is still consuming the previous group's draw
	 * call, so one shared pair is both simpler and avoids a per-group-key GL object cache that would
	 * otherwise need its own resource-reload cleanup (unlike {@link GpuModelCache}, these buffers
	 * hold no data derived from any particular {@code BakedStrutModel}, so a reload can't make them
	 * stale in the first place)
	 */
	private static int paletteSsbo;
	private static int instanceSsbo;
	private static int visualsSsbo;
	private static int skinSsbo;
	private static int deformSsbo;
	/** Packs what a group's instances move their mesh vertices by (cloth, flesh): one, reused, since a group is drawn at a time */
	private static final MeshDeformPack DEFORM_PACK = new MeshDeformPack();
	private static boolean buffersReady;

	/**
	 * CPU-side scratch, grown on demand and reused across groups and frames rather than reallocated
	 * every draw - the same "no allocation on the render path" rule the CPU path itself follows
	 *
	 */
	private static FloatBuffer paletteScratch = BufferUtils.createFloatBuffer(0);
	private static ByteBuffer instanceScratch = BufferUtils.createByteBuffer(0);
	private static ByteBuffer visualsScratch = BufferUtils.createByteBuffer(0);
	private static FloatBuffer skinScratch = BufferUtils.createFloatBuffer(0);
	private static Matrix4f[] boneScratch = new Matrix4f[0];
	private static final Matrix4f worldBoneScratch = new Matrix4f();

	private GpuBatchQueue() {}

	public static void init() {
		WorldRenderEvents.AFTER_ENTITIES.register(context -> {
			frameContext = context;
			flush(context);
		});
		WorldRenderEvents.END.register(context -> frameContext = null);
	}

	/** The world render context of the frame being drawn, from the entities' flush until the frame ends: what the second flush (block entities) draws with */
	private static WorldRenderContext frameContext;

	/**
	 * Draws what was queued since the entities' flush: the block entities, which vanilla draws after the AFTER_ENTITIES event. Called from a mixin right after
	 * them, so they are drawn in the frame they were queued in, with its camera
	 */
	public static void flushBlockEntities() {
		if (frameContext != null && !GROUPS.isEmpty())
			flush(frameContext);
	}

	/**
	 * Queues one entity's (or other {@code StrutAnimatable} instance's) render for this frame's flush,
	 * grouped with every other submission sharing the same {@code gpuModel}/{@code texture}
	 * <p>
	 * {@code model} is kept alongside {@code gpuModel} (not derivable from it - {@link GpuModel}
	 * carries only geometry) purely to hand {@link PosePalette#compute} the {@code BakedStrutModel} it
	 * needs; every submission in one group necessarily shares the same one, since {@code gpuModel}'s
	 * identity already determines it
	 */
	public static void submit(GpuModel gpuModel, BakedStrutModel model, Identifier texture, RenderKey render, Matrix4f entityTransform,
							   Pose pose, @Nullable BoneVisuals visuals, @Nullable BoneTextureOverrides textures,
							   @Nullable BoneRenderLayers layers, @Nullable SkinningState skinningState, int packedLight, int packedOverlay,
							   int colour) {
		submit(gpuModel, model, texture, render, entityTransform, pose, visuals, textures, layers, skinningState, packedLight, packedOverlay, colour, null);
	}

	/**
	 * As above for an instance whose mesh vertices are moved (cloth, flesh, soft bodies: {@link ModelInstance#meshOffsets}), read from {@code deform} when the
	 * group is drawn; null for one that moves none
	 */
	public static void submit(GpuModel gpuModel, BakedStrutModel model, Identifier texture, RenderKey render, Matrix4f entityTransform,
							   Pose pose, @Nullable BoneVisuals visuals, @Nullable BoneTextureOverrides textures,
							   @Nullable BoneRenderLayers layers, @Nullable SkinningState skinningState, int packedLight, int packedOverlay,
							   int colour, @Nullable ModelInstance deform) {
		GroupKey key = new GroupKey(gpuModel, texture, render);
		Map<GroupKey, List<Submission>> target = immediate ? IMMEDIATE_GROUPS : IrisCompat.isRenderingShadowPass() ? SHADOW_GROUPS : GROUPS;

		// Captured now, not at flush - Iris sets these for the entity currently rendering, and only
		// this entity's own render call is on the stack here (Iris's iris_Entity)
		IrisCompat.currentRenderedIds(capturedIds);

		target.computeIfAbsent(key, k -> new ObjectArrayList<>())
				.add(new Submission(model, entityTransform, pose, visuals, textures, layers, skinningState, packedLight, packedOverlay, colour,
						capturedIds[0], (capturedIds[1] & 0xFFFF) | (capturedIds[2] << 16), deform != null && deform.hasMeshOffsets() ? deform : null,
						immediate ? null : lights, immediate || custom == null ? NO_CUSTOM : custom.clone()));

		RenderPerfStats.recordGpuInstanceSubmitted();

		if (target == SHADOW_GROUPS)
			RenderPerfStats.recordShadowSubmitted();
	}

	/**
	 * Set once {@link #flush} finds a non-empty {@link #SHADOW_GROUPS} that {@link #flushShadowQueue}
	 * never drained - a submission queued expecting a shadow draw that never happened, meaning a
	 * shadow got silently dropped this frame. Sticky for the rest of the session rather than retried:
	 * the F9 overlay shows it, so that a missing shadow can be traced
	 */
	private static boolean shadowFlushUnreliable = false;

	public static boolean isShadowFlushUnreliable() {
		return shadowFlushUnreliable;
	}

	private static void flush(WorldRenderContext context) {
		// Iris's shadow pass runs before the main pass's entities each frame, so anything still here
		// by now was never flushed - see shadowFlushUnreliable's own doc comment
		if (!SHADOW_GROUPS.isEmpty()) {
			shadowFlushUnreliable = true;

			SHADOW_GROUPS.clear();
		}

		if (GROUPS.isEmpty())
			return;

		StrutRenderHooks.BEFORE_DRAW.invoker().onDraw();
		RenderPerfStats.beginGpuFlush();
		GpuRenderBackend.saveOuterVao();

		if (!GpuRenderBackend.beginFrame(context)) {
			GROUPS.clear();
			RenderPerfStats.endGpuFlush();

			return;
		}

		ensureBuffers();
		SsboBindingGuard.save();
		RenderPerfStats.beginGpuTimer();

		// Both backends: under a shader pack the blended passes go through the pack's own translucent program
		boolean blendedPasses = true;

		// Pass A: opaque and cutout. A translucent model waits for pass B; a translucent bone is left out of its own model's draw
		for (Map.Entry<GroupKey, List<Submission>> entry : GROUPS.entrySet()) {
			if (blendedPasses && entry.getKey().render().translucent())
				continue;

			drawGroup(entry.getKey(), entry.getValue(), context.positionMatrix(), context.projectionMatrix(), false, blendedPasses);
		}

		if (blendedPasses) {
			// Pass B: translucent models, whole, blended. Pass C: translucent bones, then overlays layer by layer, after everything
			// else so they land on top. Submission order within a pass, not sorted by distance (vanilla translucent entities are not)
			for (Map.Entry<GroupKey, List<Submission>> entry : GROUPS.entrySet()) {
				if (entry.getKey().render().translucent())
					drawGroup(entry.getKey(), entry.getValue(), context.positionMatrix(), context.projectionMatrix(), false, true);
			}

			for (Map.Entry<GroupKey, List<Submission>> entry : GROUPS.entrySet()) {
				{
					if (IrisCompat.isActive())
						drawLayerPassesIris(entry.getKey(), entry.getValue(), context.positionMatrix(), context.projectionMatrix());
					else
						drawLayerPasses(entry.getKey(), entry.getValue());
				}
			}
		}

		RenderPerfStats.endGpuTimer();
		GpuRenderBackend.endFrame();
		SsboBindingGuard.restore();
		GROUPS.clear();
		RenderPerfStats.endGpuFlush();
		StrutRenderHooks.AFTER_DRAW.invoker().onDraw();
	}

	/**
	 * Starts gathering what is submitted for a draw made at once, outside the world's render: an entity (and what it wears) drawn into a screen. Everything
	 * {@link #submit} is given until {@link #endImmediate()} is drawn there, with the render system's own matrices (the screen's) and never through a shader
	 * pack, which does not touch a screen
	 */
	public static void beginImmediate() {
		immediate = true;
	}

	/**
	 * Draws what was submitted since {@link #beginImmediate()}, now. Returns whether it was drawn: false if the backend could not start (nothing was drawn, and
	 * the caller has the CPU walk to fall back on)
	 */
	public static boolean endImmediate() {
		try {
			return flushImmediate();
		}
		finally {
			// whatever happened, the world's own submissions go where they did
			immediate = false;
			IMMEDIATE_GROUPS.clear();
		}
	}

	private static boolean flushImmediate() {
		if (IMMEDIATE_GROUPS.isEmpty())
			return false;

		RenderPerfStats.beginGpuFlush();
		GpuRenderBackend.saveOuterVao();

		Matrix4f modelViewMatrix = new Matrix4f(RenderSystem.getModelViewMatrix());
		Matrix4f projMatrix = new Matrix4f(RenderSystem.getProjectionMatrix());

		if (!GpuRenderBackend.beginFrame(modelViewMatrix, projMatrix)) {
			IMMEDIATE_GROUPS.clear();
			RenderPerfStats.endGpuFlush();

			return false;
		}

		ensureBuffers();
		SsboBindingGuard.save();
		RenderPerfStats.beginGpuTimer();

		for (Map.Entry<GroupKey, List<Submission>> entry : IMMEDIATE_GROUPS.entrySet()) {
			if (!entry.getKey().render().translucent())
				drawGroup(entry.getKey(), entry.getValue(), modelViewMatrix, projMatrix, false, true);
		}

		for (Map.Entry<GroupKey, List<Submission>> entry : IMMEDIATE_GROUPS.entrySet()) {
			if (entry.getKey().render().translucent())
				drawGroup(entry.getKey(), entry.getValue(), modelViewMatrix, projMatrix, false, true);
		}

		for (Map.Entry<GroupKey, List<Submission>> entry : IMMEDIATE_GROUPS.entrySet())
			drawLayerPasses(entry.getKey(), entry.getValue());

		RenderPerfStats.endGpuTimer();
		GpuRenderBackend.endFrame();
		SsboBindingGuard.restore();
		IMMEDIATE_GROUPS.clear();
		RenderPerfStats.endGpuFlush();

		return true;
	}

	/**
	 * Flushes {@link #SHADOW_GROUPS} - called from a mixin on
	 * {@code RenderLayer.draw(BuiltBuffer)} ({@code RenderLayerShadowFlushMixin}) while {@code IrisCompat.isRenderingShadowPass()}
	 * is true (see that field's own doc comment), not from {@code WorldRenderEvents.AFTER_ENTITIES} -
	 * the shadow pass has no world-render event of its own. No {@code WorldRenderContext} exists here
	 * either; {@code RenderSystem}'s own current matrices are whatever Iris's own shadow render call
	 * left there, since that call is still on the stack when this runs. Never rasterizes through
	 * {@link GpuRenderBackend} at all (a shadow-queue entry only exists because
	 * {@code IrisCompat.isActive()} was already true at submit time), only the SSBO upload
	 * {@code drawGroup} always does
	 */
	public static void flushShadowQueue() {
		if (SHADOW_GROUPS.isEmpty())
			return;

		// This runs mid-way through Iris's own batched shadow draw, where Iris's program is current -
		// drawInstances' own glUniform calls would otherwise land on Iris's program at our locations.
		// Before GpuRenderBackend has ever compiled (no main-pass flush yet this session) there is no
		// program to bind, so the frame's shadow submissions are dropped instead
		GpuRenderBackend.saveOuterVao();

		if (!GpuRenderBackend.rebind()) {
			SHADOW_GROUPS.clear();

			return;
		}

		ensureBuffers();
		SsboBindingGuard.save();
		GpuRenderBackend.disableCullSaving();
		RenderPerfStats.recordShadowFlush();

		// A vanilla entity draw gets its GL state from RenderLayer.startDrawing (depth test on, LEQUAL, depth writes on, no
		// blending). This draw bypasses the render layer, so without this it used whatever Iris left current - and a shadow
		// that never writes depth is no shadow at all
		ShadowGlState previousState = ShadowGlState.prepare(SHADOW_GROUPS.values().stream().mapToInt(List::size).sum());

		Matrix4f modelViewMatrix = RenderSystem.getModelViewMatrix();
		Matrix4f projMatrix = RenderSystem.getProjectionMatrix();

		for (Map.Entry<GroupKey, List<Submission>> entry : SHADOW_GROUPS.entrySet()) {
			drawGroup(entry.getKey(), entry.getValue(), modelViewMatrix, projMatrix, true, false);
		}

		SHADOW_GROUPS.clear();
		previousState.restore();
		GpuRenderBackend.restoreCull();
		SsboBindingGuard.restore();
		GpuShaderProgram.unbind();
	}

	private static void ensureBuffers() {
		if (buffersReady)
			return;

		paletteSsbo = GL15.glGenBuffers();
		instanceSsbo = GL15.glGenBuffers();
		visualsSsbo = GL15.glGenBuffers();
		skinSsbo = GL15.glGenBuffers();
		deformSsbo = GL15.glGenBuffers();

		// Given real content the first time any group actually needs it (see drawGroup) - seeded
		// with one struct's worth of storage now purely so the binding point GpuRenderBackend always
		// rebinds it to is never left pointing at a buffer object with no storage at all, for a
		// frame where not a single group happens to use COLOR/UV_OFFSET
		GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, visualsSsbo);
		GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, GpuRenderBackend.VISUAL_STRIDE_BYTES, GL15.GL_STREAM_DRAW);

		buffersReady = true;
	}

	/**
	 * Builds one group's concatenated bone-palette SSBO and per-instance light/overlay/colour SSBO,
	 * then issues its one {@code glDrawElementsInstanced} call via {@link GpuRenderBackend}
	 * <p>
	 * Each instance's own {@link PosePalette#compute} result is left-multiplied by that instance's
	 * own {@code entityTransform} before upload - the same "fold the per-instance offset directly
	 * into the palette" shape the old proof-of-concept probe's own {@code drawModelGroup} proved,
	 * with a real per-entity transform where the probe had a grid-cell offset. A hidden bone (see {@link Pose#isHidden})
	 * gets a zeroed matrix instead of being skipped - static GPU geometry can't skip vertices the way
	 * the CPU walk skips a hidden bone's own draw calls, so a zeroed matrix collapses that bone's
	 * vertices to a degenerate point instead, rasterising nothing without touching the geometry a
	 * sibling or child bone still needs
	 */
	/**
	 * Whether the opaque pass leaves {@code bone} out because its {@code TEXTURE} override is drawn in its place. A bone with no geometry needs no
	 * redraw, so it is not left out
	 */
	static boolean replacedByOverride(@Nullable BoneTextureOverrides textures, IntPredicate hasGeometry, int bone) {
		return textures != null && !textures.isEmpty() && textures.get(bone) != null && hasGeometry.test(bone);
	}

	/**
	 * The {@code TEXTURE} redraws of one chunk of a group under a shader pack: the bones of each instance that have an override, joined where their index ranges
	 * touch (a skin on a bone puts it on every bone under it, and those are next to each other) and grouped by texture, so a texture is one multi-draw for all of
	 * the chunk's instances instead of a call for each bone of each
	 */
	private static void drawIrisTextureOverrides(GpuModel gpuModel, List<Submission> submissions, int offset, int count, int quadCount, boolean blendedPasses,
												 boolean blendedModel, GroupKey key, Matrix4f modelViewMatrix, Matrix4f projMatrix) {
		Map<Identifier, IntArrayList[]> byTexture = null;

		for (int k = 0; k < count; k++) {
			Submission submission = submissions.get(offset + k);
			BoneTextureOverrides textures = submission.textures();

			if (textures == null || textures.isEmpty())
				continue;

			int[] bones = textures.entries().keySet().toIntArray();

			Arrays.sort(bones);

			for (int bone : bones) {
				int indexCount = gpuModel.boneIndexCount(bone);

				// A translucent bone was collapsed out of this pose pass; pass C draws it, with this texture
				if (indexCount == 0 || (blendedPasses && LayerDrawPlan.drawnBlended(submission.layers(), key.render().translucent(), bone)))
					continue;

				if (byTexture == null)
					byTexture = new LinkedHashMap<>();

				IntArrayList[] lists = byTexture.computeIfAbsent(textures.get(bone), texture -> new IntArrayList[] {
						new IntArrayList(), new IntArrayList(), new IntArrayList()});
				int start = gpuModel.boneIndexStart(bone);
				int last = lists[0].size() - 1;

				if (last >= 0 && lists[0].getInt(last) == k && lists[1].getInt(last) + lists[2].getInt(last) == start)
					lists[2].set(last, lists[2].getInt(last) + indexCount);
				else {
					lists[0].add(k);
					lists[1].add(start);
					lists[2].add(indexCount);
				}
			}
		}

		if (byTexture == null)
			return;

		for (Map.Entry<Identifier, IntArrayList[]> entry : byTexture.entrySet()) {
			IntArrayList[] lists = entry.getValue();

			IrisMainDraw.drawBoneRanges(gpuModel, entry.getKey(), quadCount, lists[0].toIntArray(), lists[1].toIntArray(), lists[2].toIntArray(), lists[0].size(),
					modelViewMatrix, projMatrix, blendedModel);
			RenderPerfStats.recordIrisBoneTextureDraw();
		}
	}

	/**
	 * For each of {@code count} submissions from {@code offset}, the index ranges of the bones that a {@code TEXTURE} override replaces (see
	 * {@link #replacedByOverride}), as ascending {@code (start, count)} pairs; null for a submission with none, and null overall if no submission has any
	 */
	@Nullable
	private static int[][] replacedRanges(GpuModel gpuModel, List<Submission> submissions, int offset, int count, boolean blendedPasses, GroupKey key) {
		int[][] ranges = null;

		for (int k = 0; k < count; k++) {
			Submission submission = submissions.get(offset + k);
			BoneTextureOverrides textures = submission.textures();

			if (textures == null || textures.isEmpty())
				continue;

			IntArrayList bones = new IntArrayList();

			for (Int2ObjectMap.Entry<Identifier> entry : textures.entries().int2ObjectEntrySet()) {
				int bone = entry.getIntKey();

				// a bone drawn blended by pass C is already collapsed out of the pose pass, with its own texture
				if (replacedByOverride(textures, b -> gpuModel.boneIndexCount(b) > 0, bone)
						&& !(blendedPasses && LayerDrawPlan.drawnBlended(submission.layers(), key.render().translucent(), bone)))
					bones.add(bone);
			}

			if (bones.isEmpty())
				continue;

			bones.sort(null);

			int[] pairs = new int[bones.size() * 2];

			for (int i = 0; i < bones.size(); i++) {
				pairs[i * 2] = gpuModel.boneIndexStart(bones.getInt(i));
				pairs[i * 2 + 1] = gpuModel.boneIndexCount(bones.getInt(i));
			}

			if (ranges == null)
				ranges = new int[count][];

			ranges[k] = pairs;
		}

		return ranges;
	}

	private static void drawGroup(GroupKey key, List<Submission> submissions, Matrix4f modelViewMatrix, Matrix4f projMatrix,
								  boolean shadowPass, boolean blendedPasses) {
		// See GpuRenderBackend.drawInstances's own "rasterize" doc comment - reached at all under an
		// active pack only because StrutRenderer decided the Iris path owns this entity
		boolean irisActive = IrisCompat.isActive() && !immediate;
		GpuModel gpuModel = key.gpuModel();
		int boneCount = gpuModel.boneCount();
		int instanceCount = submissions.size();
		Matrix4f[] palette = boneScratch(boneCount);
		FloatBuffer paletteBuffer = paletteBuffer(instanceCount * boneCount * 16);
		ByteBuffer instanceBuffer = instanceBuffer(instanceCount * GpuRenderBackend.INSTANCE_STRIDE_BYTES);
		boolean anyVisuals = false;

		for (Submission submission : submissions) {
			if (submission.visuals() != null) {
				anyVisuals = true;

				break;
			}
		}

		for (int instance = 0; instance < instanceCount; instance++) {
			Submission submission = submissions.get(instance);

			PosePalette.compute(submission.model(), submission.pose(), palette);

			int instanceBase = instance * boneCount * 16;

			for (int bone = 0; bone < boneCount; bone++) {
				// A translucent bone collapses here exactly like a hidden one: pass C draws it, blended, from its own palette. So does a bone
				// with a TEXTURE override on the raw path: the redraw below replaces it, rather than drawing over the model's own texture (under a
				// shader pack the redraw reads this same pose output, so the bone must stay in it there)
				if (submission.pose().isHidden(bone) || (blendedPasses && LayerDrawPlan.drawnBlended(submission.layers(), key.render().translucent(), bone))
						|| (!irisActive && key.render().overrideReplacesBase() && replacedByOverride(submission.textures(), b -> gpuModel.boneIndexCount(b) > 0, bone)))
					worldBoneScratch.zero();
				else
					worldBoneScratch.set(submission.entityTransform()).mul(palette[bone]);

				worldBoneScratch.get(instanceBase + bone * 16, paletteBuffer);
			}

			Color colour = new Color(submission.colour());

			instanceBuffer.putInt(submission.packedLight());
			instanceBuffer.putInt(submission.packedOverlay());
			instanceBuffer.putInt(submission.irisEntityId());
			instanceBuffer.putInt(submission.irisBlockEntityItemIds());
			instanceBuffer.putFloat(colour.getRedFloat());
			instanceBuffer.putFloat(colour.getGreenFloat());
			instanceBuffer.putFloat(colour.getBlueFloat());
			instanceBuffer.putFloat(colour.getAlphaFloat());
			putDeformInstance(instanceBuffer, instance, submission);
		}

		instanceBuffer.flip();

		ByteBuffer visualsBuffer = null;

		if (anyVisuals) {
			visualsBuffer = visualsBuffer(instanceCount * boneCount * GpuRenderBackend.VISUAL_STRIDE_BYTES);

			for (int instance = 0; instance < instanceCount; instance++) {
				BoneVisuals visuals = submissions.get(instance).visuals();

				for (int bone = 0; bone < boneCount; bone++) {
					int argb = visuals != null ? visuals.getColor(bone) : 0xFFFFFFFF;
					float u = visuals != null ? visuals.getUOffset(bone) : 0f;
					float v = visuals != null ? visuals.getVOffset(bone) : 0f;
					float uScale = visuals != null ? visuals.getUScale(bone) : 1f;
					float vScale = visuals != null ? visuals.getVScale(bone) : 1f;
					Color colour = new Color(argb);

					visualsBuffer.putFloat(colour.getRedFloat());
					visualsBuffer.putFloat(colour.getGreenFloat());
					visualsBuffer.putFloat(colour.getBlueFloat());
					visualsBuffer.putFloat(colour.getAlphaFloat());
					visualsBuffer.putFloat(u);
					visualsBuffer.putFloat(v);
					// uvOffsetScale.zw is the UV_SIZE override's scale; flags (reserved for the emissive
					// toggle) is an inert default, see VISUAL_STRIDE_BYTES
					visualsBuffer.putFloat(uScale);
					visualsBuffer.putFloat(vScale);
					visualsBuffer.putInt(0);
					visualsBuffer.putInt(0);
					visualsBuffer.putInt(0);
					visualsBuffer.putInt(0);
				}
			}

			visualsBuffer.flip();
		}

		FloatBuffer skinBuffer = null;
		int skinBoneCount = gpuModel.totalSkinBoneCount();

		if (gpuModel.hasSkinning()) {
			skinBuffer = skinBuffer(instanceCount * skinBoneCount * 16);

			for (int instance = 0; instance < instanceCount; instance++) {
				writeInstanceSkinMatrices(submissions.get(instance), instance * skinBoneCount * 16, skinBuffer);
			}
		}

		boolean blendedModel = blendedPasses && key.render().translucent();

		if (blendedModel)
			RenderPerfStats.recordBlendedModel(instanceCount);

		if (!irisActive)
			GpuRenderBackend.setShadingMode(key.render().shading());

		// One blend state for the whole group's draws, raw or through the pack, including its TEXTURE redraws
		if (blendedModel) {
			// No polygon offset on a translucent model's own draw: it is its own depth (the pre-pass and this draw share one offset, so they tie
			// exactly), and a slope-scaled offset only pulls neighbouring quads at different angles apart, which showed as seams along quad borders.
			// The model's bias is for what is drawn over other geometry: translucent bones and overlays
			GpuRenderBackend.beginBlendPass(DepthBias.NONE, false, key.render().cullBackFaces(), key.render().additive());
			// An additive model (an enchantment's glint) is the armour drawn again, so it is added only where the armour itself wrote its depth: the opaque texels
			// of the armour's texture. Its transparent texels wrote none, and the glint there would sit in the air in front of the body. So no depth pre-pass of its
			// own (that would write depth over the transparent texels too), and an exact depth test against what the armour left. Under a shader pack the glint is
			// drawn by another program whose depth is not bit for bit the same, so there it is drawn as before
			boolean glint = GLINT_MASK && key.render().additive() && !irisActive;

			if (glint)
				RenderSystem.depthFunc(GL11.GL_EQUAL);

			GpuRenderBackend.setDepthPrepass(!irisActive && !glint);
		}

		// What this group's instances move their mesh vertices by (cloth, flesh), packed once for every draw of the group: the main draw, the texture redraws
		// and, under a shader pack, the pose pass; each instance's own place in it is in its instance data
		GpuRenderBackend.setDeform(gpuModel, gpuModel.hasMeshes() && DEFORM_PACK.build(gpuModel.meshes(), instanceCount, idx -> submissions.get(idx).deform()) ? DEFORM_PACK : null, deformSsbo);

		GpuRenderBackend.drawInstances(gpuModel, key.texture(), paletteSsbo, paletteBuffer, instanceSsbo, instanceBuffer,
				visualsSsbo, visualsBuffer, skinSsbo, skinBuffer, gpuModel.skinnedMeshSsbo(), skinBoneCount, instanceCount, !irisActive);

		if (!irisActive)
			RenderPerfStats.recordGpuDrawCall();

		// The real Iris draw, replacing (not compositing over) the raw pass drawInstances skipped above
		// via its own rasterize=false - reached only when StrutRenderer's own eligibility check already
		// confirmed a pack is active and the shadow flush is reliable. Per-bone COLOR / UV_OFFSET overrides ride the pose and pack
		// passes (the visuals SSBO drawInstances uploaded); TEXTURE overrides are extra bone-range
		// draws after each chunk's main draw
		//
		// Chunked - see IrisPrePass.maxInstancesPerChunk's own doc comment. Each chunk is posed,
		// packed and drawn before the next one reuses (re-orphans) the same output buffers
		if (irisActive) {
						IrisPrePass.setFlatNormal(packFlatNormal(key.render()));

			int chunk = IrisPrePass.maxInstancesPerChunk(gpuModel);
			boolean hasVisuals = visualsBuffer != null;
			int quadCount = gpuModel.indexCount() / 6;

			for (int offset = 0; offset < instanceCount; offset += chunk) {
				int count = Math.min(chunk, instanceCount - offset);

				if (!IrisPrePass.runPosePass(gpuModel, gpuModel.hasSkinning(), skinBoneCount, offset, count,
						paletteSsbo, instanceSsbo, skinSsbo, gpuModel.skinnedMeshSsbo(), visualsSsbo, hasVisuals)) {
					if (shadowPass)
						RenderPerfStats.recordShadowPoseFailed();

					break;
				}

				SsboBindingGuard.restore();

				// A bone whose TEXTURE override replaces the model's own texture is left out of the main draw and drawn below with the override alone: drawn on
				// top of the model's texture, the base showed through every transparent texel of the override (a player skin's unused corners and second layer).
				// The shadow pass keeps the whole silhouette
				int[][] skipRanges = shadowPass || !key.render().overrideReplacesBase() ? null : replacedRanges(gpuModel, submissions, offset, count, blendedPasses, key);

				boolean drawn = IrisMainDraw.draw(gpuModel, key.texture(), count, quadCount, modelViewMatrix, projMatrix, blendedModel, blendedModel, skipRanges);

				if (drawn)
					RenderPerfStats.recordIrisGpuDrawCall();

				if (shadowPass) {
					if (drawn)
						RenderPerfStats.recordShadowDrawn(count);
					else
						RenderPerfStats.recordShadowSkipped(count);
				}

				// The shadow pass only needs silhouettes: a per-bone texture swap changes colour, not
				// (except at a cutout edge) coverage, so it isn't redrawn there
				if (shadowPass)
					continue;

				drawIrisTextureOverrides(gpuModel, submissions, offset, count, quadCount, blendedPasses, blendedModel, key, modelViewMatrix, projMatrix);
			}
		}

				if (irisActive) {
			IrisPrePass.setFlatNormal(null);

			if (blendedModel)
				GpuRenderBackend.endBlendPass();
		}

		GpuRenderBackend.rebind();

		// Raw-GL only, same as the main draw above - the CPU walk (still running whenever irisActive,
		// see StrutRenderer's own doc comment) already renders a bone TEXTURE override correctly on its
		// own under a pack, so there is nothing this raster-only pass needs to contribute there
		if (!irisActive) {
			// Still inside a translucent model's blend state, so a TEXTURE redraw of one of its bones blends too
			drawTextureOverrides(gpuModel, submissions, palette, boneCount, blendedModel, blendedPasses);
		}

		if (!irisActive && blendedModel)
			GpuRenderBackend.endBlendPass();

		GpuRenderBackend.clearDeform();
	}

	/**
	 * Writes one instance's skin matrices into {@code dest} starting at {@code floatBase}, in
	 * {@link BakedStrutModel#armatures()} order - the same concatenation
	 * {@link GpuModel#totalSkinBoneCount()} sizes for and every skinned vertex's baked joint index
	 * (see {@code GpuModel.appendSkinnedFace}'s own doc comment) already assumes
	 * <p>
	 * {@code skinningState} is only null for a submission whose own model has no skinning at all -
	 * unreachable today (a group only reaches here via {@link GpuModel#hasSkinning()}, and every
	 * submission in a group shares one model identity), guarded anyway rather than assumed, since a
	 * null here would otherwise surface as a confusing {@code NullPointerException} deep in a
	 * per-frame draw call instead of a clear one at the actual mistake
	 */
	private static void writeInstanceSkinMatrices(Submission submission, int floatBase, FloatBuffer dest) {
		SkinningState skinningState = submission.skinningState();

		if (skinningState == null)
			throw new IllegalStateException("a GpuModel reporting hasSkinning() was submitted without a SkinningState");

		int boneBase = floatBase;

		for (StrutArmature armature : submission.model().armatures()) {
			Matrix4f[] skinMatrices = skinningState.skinMatricesFor(armature);

			for (Matrix4f skinMatrix : skinMatrices) {
				skinMatrix.get(boneBase, dest);
				boneBase += 16;
			}
		}
	}

	/**
	 * The extra pass, after the group's main instanced draw: every submission with an
	 * active {@link BoneTextureOverrides} gets one extra {@code glDrawElementsInstanced} call per
	 * overridden bone, restricted to that bone's own index range via {@link GpuModel#boneIndexStart}/
	 * {@link GpuModel#boneIndexCount} - see {@link GpuRenderBackend#drawBoneRange}'s own doc comment
	 * for why this composites correctly over the main draw rather than z-fighting against it
	 * <p>
	 * Deliberately not batched across instances that share the same overridden bone/texture, unlike
	 * the main draw - each override redraw is its own single-instance call. A model with many
	 * instances all overriding the same bone the same way pays one draw call per instance rather than
	 * one shared one; correct, and far simpler than re-grouping by (bone, texture) on top of the
	 * group's own (model, texture) grouping, for a property whose design already
	 * accepts a draw-call cost from
	 */
	private static void drawTextureOverrides(GpuModel gpuModel, List<Submission> submissions, Matrix4f[] palette, int boneCount,
											  boolean translucentModel, boolean blendedPasses) {
		int skinBoneCount = gpuModel.totalSkinBoneCount();

		// the instances that have an override with each texture
		Map<Identifier, IntArrayList> instancesOf = null;

		for (int i = 0; i < submissions.size(); i++) {
			BoneTextureOverrides textures = submissions.get(i).textures();

			if (textures == null || textures.isEmpty())
				continue;

			for (Int2ObjectMap.Entry<Identifier> entry : textures.entries().int2ObjectEntrySet()) {
				int bone = entry.getIntKey();

				if (gpuModel.boneIndexCount(bone) == 0 || (blendedPasses && LayerDrawPlan.drawnBlended(submissions.get(i).layers(), translucentModel, bone)))
					continue;

				if (instancesOf == null)
					instancesOf = new LinkedHashMap<>();

				IntArrayList list = instancesOf.computeIfAbsent(entry.getValue(), texture -> new IntArrayList());

				if (list.isEmpty() || list.getInt(list.size() - 1) != i)
					list.add(i);
			}
		}

		if (instancesOf == null)
			return;

		// One draw for each texture, over every instance that uses it: the palette of each instance holds only the bones that have that texture (the others are
		// collapsed to nothing, as hidden bones are), so the whole model is drawn and only those bones show. What a call for each bone of each instance cost (a skin
		// on a bone with a hundred bones under it, on a hundred models: nearly ten thousand calls, each uploading its own buffers) is a call for each texture
		for (Map.Entry<Identifier, IntArrayList> group : instancesOf.entrySet()) {
			Identifier texture = group.getKey();
			IntArrayList which = group.getValue();
			int n = which.size();
			FloatBuffer paletteBuffer = paletteBuffer(n * boneCount * 16);
			ByteBuffer instanceBuffer = instanceBuffer(n * GpuRenderBackend.INSTANCE_STRIDE_BYTES);
			FloatBuffer skinBuffer = gpuModel.hasSkinning() ? skinBuffer(n * skinBoneCount * 16) : null;
			boolean anyVisuals = false;

			for (int j = 0; j < n; j++)
				if (submissions.get(which.getInt(j)).visuals() != null)
					anyVisuals = true;

			ByteBuffer visuals = anyVisuals ? visualsBuffer(n * boneCount * GpuRenderBackend.VISUAL_STRIDE_BYTES) : null;

			for (int j = 0; j < n; j++) {
				Submission submission = submissions.get(which.getInt(j));
				BoneTextureOverrides textures = submission.textures();

				PosePalette.compute(submission.model(), submission.pose(), palette);

				for (int bone = 0; bone < boneCount; bone++) {
					boolean mine = !submission.pose().isHidden(bone) && gpuModel.boneIndexCount(bone) > 0 && texture.equals(textures.get(bone))
							&& !(blendedPasses && LayerDrawPlan.drawnBlended(submission.layers(), translucentModel, bone));

					if (mine)
						worldBoneScratch.set(submission.entityTransform()).mul(palette[bone]);
					else
						worldBoneScratch.zero();

					worldBoneScratch.get((j * boneCount + bone) * 16, paletteBuffer);
				}

				Color colour = new Color(submission.colour());

				instanceBuffer.putInt(submission.packedLight());
				instanceBuffer.putInt(submission.packedOverlay());
				instanceBuffer.putInt(0);
				instanceBuffer.putInt(0);
				instanceBuffer.putFloat(colour.getRedFloat());
				instanceBuffer.putFloat(colour.getGreenFloat());
				instanceBuffer.putFloat(colour.getBlueFloat());
				instanceBuffer.putFloat(colour.getAlphaFloat());
				putDeformInstance(instanceBuffer, which.getInt(j), submission);

				// This instance's own skin matrices, at its own place in the buffer, which is what its
				// gl_InstanceID reads
				if (skinBuffer != null)
					writeInstanceSkinMatrices(submission, j * skinBoneCount * 16, skinBuffer);

				// this instance's own tint, UV offset and UV size: without them a bone with a texture override ignored its UV_SIZE (a player's skin on a
				// model made for a smaller texture) and its colour
				if (visuals != null) {
					BoneVisuals own = submission.visuals();

					for (int bone = 0; bone < boneCount; bone++) {
						Color boneColour = new Color(own != null ? own.getColor(bone) : 0xFFFFFFFF);

						visuals.putFloat(boneColour.getRedFloat());
						visuals.putFloat(boneColour.getGreenFloat());
						visuals.putFloat(boneColour.getBlueFloat());
						visuals.putFloat(boneColour.getAlphaFloat());
						visuals.putFloat(own != null ? own.getUOffset(bone) : 0f);
						visuals.putFloat(own != null ? own.getVOffset(bone) : 0f);
						visuals.putFloat(own != null ? own.getUScale(bone) : 1f);
						visuals.putFloat(own != null ? own.getVScale(bone) : 1f);
						visuals.putInt(0);
						visuals.putInt(0);
						visuals.putInt(0);
						visuals.putInt(0);
					}
				}
			}

			instanceBuffer.flip();

			if (visuals != null)
				visuals.flip();

			GpuRenderBackend.drawBoneRange(gpuModel, texture, paletteSsbo, paletteBuffer, instanceSsbo, instanceBuffer, skinSsbo, skinBuffer, gpuModel.skinnedMeshSsbo(),
					skinBoneCount, 0, gpuModel.indexCount(), visualsSsbo, visuals, n);
			RenderPerfStats.recordGpuDrawCall();
		}
	}

	/**
	 * Pass C for one group: each instance's translucent bones and overlays (see {@link LayerDrawPlan}), blended, one bone range at a time
	 * <p>
	 * A bone range redraws the same geometry the opaque pass already drew (or, for a translucent bone, left out), so these draws depth-test
	 * with LEQUAL, write no depth, and take the model's polygon offset to win the tie. Both carry the bone's own colour and UV
	 * offset (a colour override's alpha is what makes a bone see-through). Raw GL only
	 */
	private static void drawLayerPasses(GroupKey key, List<Submission> submissions) {
		GpuModel gpuModel = key.gpuModel();
		int boneCount = gpuModel.boneCount();
		int skinBoneCount = gpuModel.totalSkinBoneCount();
		Matrix4f[] palette = boneScratch(boneCount);
		boolean began = false;

		for (int groupIndex = 0; groupIndex < submissions.size(); groupIndex++) {
			Submission submission = submissions.get(groupIndex);
			List<LayerDrawPlan.Draw> draws = LayerDrawPlan.build(submission.layers(), submission.textures(), key.texture(),
					key.render().translucent(), bone -> gpuModel.boneIndexCount(bone) > 0);

			if (draws.isEmpty())
				continue;

			if (!began) {
				GpuRenderBackend.setShadingMode(key.render().shading());
				GpuRenderBackend.beginBlendPass(key.render().bias(), false, key.render().cullBackFaces());
				GpuRenderBackend.setDeform(gpuModel, gpuModel.hasMeshes() && DEFORM_PACK.build(gpuModel.meshes(), submissions.size(), idx -> submissions.get(idx).deform()) ? DEFORM_PACK : null, deformSsbo);

				began = true;
			}

			PosePalette.compute(submission.model(), submission.pose(), palette);

			FloatBuffer singlePalette = paletteBuffer(boneCount * 16);

			for (int bone = 0; bone < boneCount; bone++) {
				if (submission.pose().isHidden(bone))
					worldBoneScratch.zero();
				else
					worldBoneScratch.set(submission.entityTransform()).mul(palette[bone]);

				worldBoneScratch.get(bone * 16, singlePalette);
			}

			Color colour = new Color(submission.colour());
			ByteBuffer singleInstance = instanceBuffer(GpuRenderBackend.INSTANCE_STRIDE_BYTES);

			singleInstance.putInt(submission.packedLight());
			singleInstance.putInt(submission.packedOverlay());
			singleInstance.putInt(0);
			singleInstance.putInt(0);
			singleInstance.putFloat(colour.getRedFloat());
			singleInstance.putFloat(colour.getGreenFloat());
			singleInstance.putFloat(colour.getBlueFloat());
			singleInstance.putFloat(colour.getAlphaFloat());
			putDeformInstance(singleInstance, groupIndex, submission);
			singleInstance.flip();

			FloatBuffer singleSkinBuffer = null;

			if (gpuModel.hasSkinning()) {
				singleSkinBuffer = skinBuffer(skinBoneCount * 16);

				writeInstanceSkinMatrices(submission, 0, singleSkinBuffer);
			}

			ByteBuffer singleVisuals = singleInstanceVisuals(submission.visuals(), boneCount);

			for (LayerDrawPlan.Draw draw : draws) {
				GpuRenderBackend.setDepthPrepass(!draw.overlay() || key.render().overlayDepth());
				GpuRenderBackend.drawBoneRange(gpuModel, draw.texture(), paletteSsbo, singlePalette, instanceSsbo, singleInstance, skinSsbo,
						singleSkinBuffer, gpuModel.skinnedMeshSsbo(), skinBoneCount, gpuModel.boneIndexStart(draw.bone()),
						gpuModel.boneIndexCount(draw.bone()), visualsSsbo, singleVisuals);
				RenderPerfStats.recordGpuDrawCall();
				RenderPerfStats.recordBlendedBoneDraw(draw.overlay());
			}
		}

		if (began) {
			GpuRenderBackend.endBlendPass();
			GpuRenderBackend.clearDeform();
		}
	}

	/**
	 * Pass C under a shader pack: the same translucent bones and overlays as {@link #drawLayerPasses}, drawn through the pack's translucent
	 * entity program. Each instance needs its own pose pass first, with nothing collapsed (the main pass collapsed its translucent bones), so
	 * it uploads its own one-instance buffers and poses them before drawing bone ranges out of the packed result.
	 */
	private static void drawLayerPassesIris(GroupKey key, List<Submission> submissions, Matrix4f modelViewMatrix, Matrix4f projMatrix) {
		GpuModel gpuModel = key.gpuModel();
		int boneCount = gpuModel.boneCount();
		int skinBoneCount = gpuModel.totalSkinBoneCount();
		int quadCount = gpuModel.indexCount() / 6;
		Matrix4f[] palette = boneScratch(boneCount);
		boolean began = false;

		for (int groupIndex = 0; groupIndex < submissions.size(); groupIndex++) {
			Submission submission = submissions.get(groupIndex);
			List<LayerDrawPlan.Draw> draws = LayerDrawPlan.build(submission.layers(), submission.textures(), key.texture(),
					key.render().translucent(), bone -> gpuModel.boneIndexCount(bone) > 0);

			if (draws.isEmpty())
				continue;

			if (!began) {
				// The pose pass and the uploads use this backend's own program and the shared buffers, then the pack's program draws
				GpuRenderBackend.rebind();
				IrisPrePass.setFlatNormal(packFlatNormal(key.render()));
				GpuRenderBackend.setDeform(gpuModel, gpuModel.hasMeshes() && DEFORM_PACK.build(gpuModel.meshes(), submissions.size(), idx -> submissions.get(idx).deform()) ? DEFORM_PACK : null, deformSsbo);

				began = true;
			}

			PosePalette.compute(submission.model(), submission.pose(), palette);

			FloatBuffer singlePalette = paletteBuffer(boneCount * 16);

			for (int bone = 0; bone < boneCount; bone++) {
				if (submission.pose().isHidden(bone))
					worldBoneScratch.zero();
				else
					worldBoneScratch.set(submission.entityTransform()).mul(palette[bone]);

				worldBoneScratch.get(bone * 16, singlePalette);
			}

			Color colour = new Color(submission.colour());
			ByteBuffer singleInstance = instanceBuffer(GpuRenderBackend.INSTANCE_STRIDE_BYTES);

			singleInstance.putInt(submission.packedLight());
			singleInstance.putInt(submission.packedOverlay());
			singleInstance.putInt(submission.irisEntityId());
			singleInstance.putInt(submission.irisBlockEntityItemIds());
			singleInstance.putFloat(colour.getRedFloat());
			singleInstance.putFloat(colour.getGreenFloat());
			singleInstance.putFloat(colour.getBlueFloat());
			singleInstance.putFloat(colour.getAlphaFloat());
			putDeformInstance(singleInstance, groupIndex, submission);
			singleInstance.flip();

			FloatBuffer singleSkinBuffer = null;

			if (gpuModel.hasSkinning()) {
				singleSkinBuffer = skinBuffer(skinBoneCount * 16);

				writeInstanceSkinMatrices(submission, 0, singleSkinBuffer);
			}

			ByteBuffer singleVisuals = singleInstanceVisuals(submission.visuals(), boneCount);

			// Uploads only (rasterize = false), the same way the main Iris group draw feeds its pose pass
			GpuRenderBackend.drawInstances(gpuModel, key.texture(), paletteSsbo, singlePalette, instanceSsbo, singleInstance, visualsSsbo,
					singleVisuals, skinSsbo, singleSkinBuffer, gpuModel.skinnedMeshSsbo(), skinBoneCount, 1, false);

			if (!IrisPrePass.runPosePass(gpuModel, gpuModel.hasSkinning(), skinBoneCount, 0, 1, paletteSsbo, instanceSsbo, skinSsbo,
					gpuModel.skinnedMeshSsbo(), visualsSsbo, singleVisuals != null))
				continue;

			SsboBindingGuard.restore();
			GpuRenderBackend.beginBlendPass(key.render().bias(), false, key.render().cullBackFaces());

			for (LayerDrawPlan.Draw draw : draws) {
				IrisMainDraw.drawBoneRange(gpuModel, draw.texture(), 0, quadCount, gpuModel.boneIndexStart(draw.bone()),
						gpuModel.boneIndexCount(draw.bone()), modelViewMatrix, projMatrix, true, !draw.overlay());
				RenderPerfStats.recordIrisBoneTextureDraw();
				RenderPerfStats.recordBlendedBoneDraw(draw.overlay());
			}

			GpuRenderBackend.endBlendPass();
			GpuRenderBackend.rebind();
		}

		if (began) {
			IrisPrePass.setFlatNormal(null);
			GpuRenderBackend.clearDeform();
		}
	}

	/**
	 * The end of an instance's record: its place in the group, which is the row of the deformation table the shaders read for it. A draw that holds only some
	 * of a group's instances (a texture redraw, one instance's overlay) writes the group's number for each, not its own place in the draw, so the one buffer
	 * the group's deformation was packed into serves them all
	 */
	private static void putDeformInstance(ByteBuffer instanceBuffer, int groupIndex, Submission submission) {
		LightProbe.Rig rig = submission.lights();

		instanceBuffer.putInt(groupIndex);

		// the directions and strengths of the lights on this instance (see LightProbe), zero for none: the shader then uses the game's own fixed lights
		for (int i = 0; i < LightProbe.LIGHTS; i++) {
			Vector3f direction = rig == null ? null : rig.direction(i);

			instanceBuffer.putFloat(direction == null ? 0f : direction.x);
			instanceBuffer.putFloat(direction == null ? 0f : direction.y);
			instanceBuffer.putFloat(direction == null ? 0f : direction.z);
		}

		for (int i = 0; i < LightProbe.LIGHTS; i++)
			instanceBuffer.putInt(rig == null ? 0xFFFFFF : rig.packedColour(i));

		for (int i = 0; i < 4; i++)
			instanceBuffer.putFloat(submission.custom()[i]);

		// the struct is a multiple of 16 bytes long
		instanceBuffer.putFloat(0f);
		instanceBuffer.putFloat(0f);
		instanceBuffer.putFloat(0f);
	}

	private static final float[] NO_CUSTOM = new float[4];

	/**
	 * The lights on what is submitted from here until it is set again (see {@link LightProbe}), or null for none: a model drawn into a screen has no world to be lit
	 * by. Set around a renderer's submissions, so the worn models queued with it are lit the same
	 */
	public static void setLights(@Nullable LightProbe.Rig rig) {
		lights = rig;

		if (rig != null)
			lastLit = rig;
	}

	/** The four floats the shader hooks read for what is submitted from here until it is set again (see {@code StrutRenderHooks.SUBMIT}), or null for zeros */
	public static void setCustom(@Nullable float[] values) {
		custom = values;
	}

	/** The lights on the model most recently queued in the world, for the F9 overlay */
	@Nullable
	public static LightProbe.Rig lastLit() {
		return lastLit;
	}

	@Nullable
	private static LightProbe.Rig lastLit;

	@Nullable
	private static float[] custom;

	@Nullable
	private static LightProbe.Rig lights;

	/** One instance's per-bone visuals (tint, UV offset, UV scale) as the single-instance buffer a bone-range draw reads, or null when it has none */
	@Nullable
	private static ByteBuffer singleInstanceVisuals(@Nullable BoneVisuals visuals, int boneCount) {
		if (visuals == null)
			return null;

		ByteBuffer buffer = visualsBuffer(boneCount * GpuRenderBackend.VISUAL_STRIDE_BYTES);

		for (int bone = 0; bone < boneCount; bone++) {
			Color boneColour = new Color(visuals.getColor(bone));

			buffer.putFloat(boneColour.getRedFloat());
			buffer.putFloat(boneColour.getGreenFloat());
			buffer.putFloat(boneColour.getBlueFloat());
			buffer.putFloat(boneColour.getAlphaFloat());
			buffer.putFloat(visuals.getUOffset(bone));
			buffer.putFloat(visuals.getVOffset(bone));
			buffer.putFloat(visuals.getUScale(bone));
			buffer.putFloat(visuals.getVScale(bone));
			buffer.putInt(0);
			buffer.putInt(0);
			buffer.putInt(0);
			buffer.putInt(0);
		}

		buffer.flip();

		return buffer;
	}

	private static Matrix4f[] boneScratch(int boneCount) {
		if (boneScratch.length < boneCount) {
			int previousLength = boneScratch.length;

			boneScratch = Arrays.copyOf(boneScratch, boneCount);

			for (int i = previousLength; i < boneCount; i++) {
				boneScratch[i] = new Matrix4f();
			}
		}

		return boneScratch;
	}

	/**
	 * {@code paletteScratch}, grown to at least {@code floatCount} and limited to exactly it -
	 * {@link Matrix4f#get(int, FloatBuffer)} writes absolutely (it never advances position), so a
	 * buffer reused across groups of different sizes needs its limit set explicitly rather than
	 * relying on a fresh buffer's limit already matching its capacity the way the old probe's own
	 * fixed-size palette buffers could
	 */
	private static FloatBuffer paletteBuffer(int floatCount) {
		if (paletteScratch.capacity() < floatCount)
			paletteScratch = BufferUtils.createFloatBuffer(floatCount);

		paletteScratch.clear();
		paletteScratch.limit(floatCount);

		return paletteScratch;
	}

	/**
	 * {@code instanceScratch}, grown to at least {@code byteCount} and cleared - filled through
	 * relative {@code putInt}/{@code putFloat} calls in {@link #drawGroup}, so (unlike
	 * {@link #paletteBuffer}) a plain {@code clear()} before filling and {@code flip()} after is
	 * the correct pattern here
	 */
	private static ByteBuffer instanceBuffer(int byteCount) {
		if (instanceScratch.capacity() < byteCount)
			instanceScratch = BufferUtils.createByteBuffer(byteCount);

		instanceScratch.clear();
		instanceScratch.limit(byteCount);

		return instanceScratch;
	}

	/**
	 * {@code visualsScratch}, grown to at least {@code byteCount} and cleared - only ever allocated
	 * (and grown) for a group that actually has an active {@code COLOR}/{@code UV_OFFSET} override
	 * on at least one instance, which keeps a session that never touches either at whatever size
	 * (typically 0) it started at
	 */
	private static ByteBuffer visualsBuffer(int byteCount) {
		if (visualsScratch.capacity() < byteCount)
			visualsScratch = BufferUtils.createByteBuffer(byteCount);

		visualsScratch.clear();
		visualsScratch.limit(byteCount);

		return visualsScratch;
	}

	/**
	 * {@code skinScratch}, grown to at least {@code floatCount} and limited to exactly it - written
	 * through {@link Matrix4f#get(int, FloatBuffer)}'s absolute form, like {@link #paletteBuffer},
	 * only ever allocated (and grown) for a group whose model actually has skinning
	 */
	private static FloatBuffer skinBuffer(int floatCount) {
		if (skinScratch.capacity() < floatCount)
			skinScratch = BufferUtils.createFloatBuffer(floatCount);

		skinScratch.clear();
		skinScratch.limit(floatCount);

		return skinScratch;
	}
}
