package com.sandymandy.strut.perf;

import com.sandymandy.strut.assetsync.client.StrutAssetSyncClient;
import com.sandymandy.strut.renderer.gpu.GpuBatchQueue;
import com.sandymandy.strut.event.binding.EventBindings;
import com.sandymandy.strut.event.dispatch.EventStats;
import com.sandymandy.strut.renderer.iris.IrisCompat;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.List;

/**
 * The render measurement harness - render-thread
 * counters and stage timers for the current render path, so later stages have real numbers to
 * improve against instead of the estimate derived from reading
 * the code
 * <p>
 * Render-thread only, not synchronized - Minecraft's entity render pass is single-threaded on the
 * render thread, and every call site this class exposes is reached only from that thread. Two
 * generations of counters: the ones being accumulated for the frame currently being drawn, and the
 * previous frame's finished totals, which is what {@link #summaryLines()} actually reads - so the
 * overlay always shows a complete frame's numbers, never a partial one caught mid-accumulation
 *
 * <h2>What "animation" and "submission" mean here</h2>
 *
 * <p>"Animation" is time spent in {@link com.sandymandy.strut.model.StrutModel#handleAnimations} -
 * evaluating controllers and writing the pose. "Submission" is time spent in the per-bone render
 * walk inside {@link com.sandymandy.strut.renderer.StrutRenderer#actuallyRender} - transforming and
 * pushing vertices. These two windows never overlap for one entity (animation finishes before
 * submission starts, per {@link com.sandymandy.strut.renderer.StrutEntityRenderer#actuallyRender}),
 * and layered sub-renders (armor, held items) run after the outer entity's submission window has
 * already closed - so plain start/end timestamps are safe without a call stack. If a future caller
 * introduces nesting, these timers would silently misattribute time rather than crash, which is a
 * real constraint on future call sites, not just a note
 *
 * <h2>Allocation rate</h2>
 *
 * <p>Sampled once per frame via {@code com.sun.management.ThreadMXBean#getThreadAllocatedBytes},
 * a standard (if vendor-namespaced) part of every HotSpot-derived JDK, requiring no java agent.
 * This is the render thread's <em>total</em> allocation for the frame, not Strut-attributed
 * allocation specifically - deliberately, since the point is confirming or correcting
 * the original estimate of the render path's own behaviour, and narrowing the
 * sample to "calls that happened between two markers we control" would hide exactly the kind of
 * allocation a later optimisation is trying to remove
 */
public final class RenderPerfStats {
	private RenderPerfStats() {}

	@SuppressWarnings("restriction")
	private static final ThreadMXBean THREAD_MX_BEAN = resolveAllocationCapableBean();

	/**
	 * Captured from the first call to {@link #beginFrame()} rather than at class-init time - class
	 * initialization can legally be triggered by whichever thread first references this class, and
	 * only {@link #beginFrame()}'s caller ({@code LevelRendererMixin}, itself only ever invoked from
	 * {@code WorldRenderer#render}) is guaranteed to actually be the render thread
	 */
	private static long renderThreadId = -1;

	private static long previousSampleAllocatedBytes = -1;

	// Accumulating for the frame currently being built
	private static long animationNanos;
	private static long submissionNanos;
	private static int entitiesDrawn;
	private static int bonesEvaluated;
	private static int verticesSubmitted;
	private static int gpuInstancesSubmitted;
	private static int gpuDrawCalls;
	private static int irisGpuDrawCalls;
	private static int irisBoneTextureDraws;
	private static int blendedModelInstances;
	private static int blendedBoneDraws;
	private static int overlayDraws;
	private static int animationsEvaluated;
	private static int animationsSkipped;

	// The last fully-completed frame's totals - what the overlay reads
	private static long lastAnimationNanos;
	private static long lastSubmissionNanos;
	private static int lastEntitiesDrawn;
	private static int lastBonesEvaluated;
	private static int lastVerticesSubmitted;
	private static int lastGpuInstancesSubmitted;
	private static int lastGpuDrawCalls;
	private static int lastIrisGpuDrawCalls;
	private static int lastIrisBoneTextureDraws;
	private static int lastBlendedModelInstances;
	private static int lastBlendedBoneDraws;
	private static int lastOverlayDraws;
	private static int lastShadowSubmitted;
	private static int lastShadowFlushes;
	private static int lastShadowDrawn;
	private static int lastShadowSkipped;
	private static int lastShadowPoseFailed;
	private static long lastAllocatedBytes;
	private static int lastAnimationsEvaluated;
	private static int lastAnimationsSkipped;

	// The Iris shadow pass, stage by stage, so a missing shadow can be traced to the stage that lost it: entities submitted for
	// the shadow queue, flushes that ran, instances actually drawn, draws declined (Iris supplied no program), and pose
	// passes that failed
	private static int shadowSubmitted;
	private static int shadowFlushes;
	private static int shadowDrawn;
	private static int shadowSkipped;
	private static int shadowPoseFailed;

	private static long animationStartNanos;
	private static long submissionStartNanos;

	// The GPU path's own two costs, previously
	// invisible - CPU time building/uploading this frame's palette+instance buffers and issuing the
	// draw calls (gpuFlushNanos), and the GPU's own execution time for those draws, measured with a
	// timer query rather than guessed from wall-clock (gpuTimerNanos)
	private static long gpuFlushNanos;
	private static long lastGpuFlushNanos;
	private static long gpuFlushStartNanos;

	/**
	 * Double-buffered {@code GL_TIMESTAMP} query pairs (start, end) - one pair issued per
	 * {@link #beginGpuTimer()}/{@link #endGpuTimer()} (once per frame that actually flushes the GPU
	 * batch queue), read back on a later frame once available. Two slots, alternated, so reading
	 * back never blocks on a pair that is almost certainly still in flight
	 * <p>
	 * Timestamps rather than a {@code GL_TIME_ELAPSED} begin/end scope, deliberately: GL allows only
	 * one active elapsed query at a time, and vanilla's own {@code GlTimer} (the F3 screen's GPU
	 * usage readout) keeps one open across the whole frame. Nesting ours inside it made our begin fail
	 * and our end close vanilla's query, which then ended a query that no longer existed - a native
	 * driver crash (live-found: F3 open while looking at a Strut entity). {@code glQueryCounter} is
	 * not a scope, so it cannot conflict with anyone else's elapsed query
	 */
	private static int[] gpuQueryIds;
	private static boolean[] gpuQueryIssued;
	private static int gpuQuerySlot = -1;
	private static boolean gpuTimerUnsupported;
	private static long lastGpuTimerNanos = -1;

	/**
	 * {@code com.sun.management.ThreadMXBean} is not in the {@code java.lang.management} public
	 * API surface, so resolving it can fail on a non-HotSpot JVM - caught here, once, rather than
	 * at every call site, so the rest of this class can treat "unsupported" as a single null check
	 */
	private static ThreadMXBean resolveAllocationCapableBean() {
		try {
			ThreadMXBean bean = ManagementFactory.getThreadMXBean();

			if (bean instanceof com.sun.management.ThreadMXBean sunBean && sunBean.isThreadAllocatedMemorySupported()) {
				sunBean.setThreadAllocatedMemoryEnabled(true);

				return bean;
			}
		}
		catch (Throwable ignored) {
			// Fall through to "unsupported" - this is a measurement aid, never worth crashing over
		}

		return null;
	}

	/**
	 * Publishes the frame just finished as {@code last*}, then resets the accumulators for the
	 * frame about to start
	 * <p>
	 * Called once per rendered frame from {@link com.sandymandy.strut.mixin.client.LevelRendererMixin},
	 * which already injects at the head of {@code WorldRenderer#render} for Molang's actor-count
	 * query - the same single choke point every entity render this frame happens after, and the
	 * natural frame boundary for this class too rather than adding a second mixin for it
	 */
	public static void beginFrame() {
		lastAnimationNanos = animationNanos;
		lastSubmissionNanos = submissionNanos;
		lastEntitiesDrawn = entitiesDrawn;
		lastBonesEvaluated = bonesEvaluated;
		lastVerticesSubmitted = verticesSubmitted;
		lastGpuInstancesSubmitted = gpuInstancesSubmitted;
		lastGpuDrawCalls = gpuDrawCalls;
		lastIrisGpuDrawCalls = irisGpuDrawCalls;
		lastIrisBoneTextureDraws = irisBoneTextureDraws;
		lastBlendedModelInstances = blendedModelInstances;
		lastBlendedBoneDraws = blendedBoneDraws;
		lastOverlayDraws = overlayDraws;
		lastShadowSubmitted = shadowSubmitted;
		lastShadowFlushes = shadowFlushes;
		lastShadowDrawn = shadowDrawn;
		lastShadowSkipped = shadowSkipped;
		lastShadowPoseFailed = shadowPoseFailed;
		lastGpuFlushNanos = gpuFlushNanos;
		lastAnimationsEvaluated = animationsEvaluated;
		lastAnimationsSkipped = animationsSkipped;

		animationNanos = 0;
		submissionNanos = 0;
		entitiesDrawn = 0;
		bonesEvaluated = 0;
		verticesSubmitted = 0;
		gpuInstancesSubmitted = 0;
		gpuDrawCalls = 0;
		irisGpuDrawCalls = 0;
		irisBoneTextureDraws = 0;
		blendedModelInstances = 0;
		blendedBoneDraws = 0;
		overlayDraws = 0;
		shadowSubmitted = 0;
		shadowFlushes = 0;
		shadowDrawn = 0;
		shadowSkipped = 0;
		shadowPoseFailed = 0;
		gpuFlushNanos = 0;
		animationsEvaluated = 0;
		animationsSkipped = 0;

		sampleAllocatedBytes();
	}

	private static void sampleAllocatedBytes() {
		if (THREAD_MX_BEAN == null)
			return;

		if (renderThreadId < 0)
			renderThreadId = Thread.currentThread().threadId();

		long current = ((com.sun.management.ThreadMXBean)THREAD_MX_BEAN).getThreadAllocatedBytes(renderThreadId);

		if (previousSampleAllocatedBytes >= 0)
			lastAllocatedBytes = current - previousSampleAllocatedBytes;

		previousSampleAllocatedBytes = current;
	}

	/**
	 * Records one instance's animation controllers actually being evaluated this frame, as opposed
	 * to {@link #recordAnimationSkipped} - see {@link com.sandymandy.strut.animation.AnimationLod}'s
	 * own doc comment
	 */
	public static void recordAnimationEvaluated() {
		animationsEvaluated++;
	}

	/**
	 * Records one instance's animation controllers being skipped this frame (too far from the
	 * camera), reusing its last evaluated pose instead
	 */
	public static void recordAnimationSkipped() {
		animationsSkipped++;
	}

	public static void beginAnimation() {
		animationStartNanos = System.nanoTime();
	}

	public static void endAnimation() {
		animationNanos += System.nanoTime() - animationStartNanos;
	}

	public static void beginSubmission() {
		submissionStartNanos = System.nanoTime();
	}

	/**
	 * Ends the current submission window and counts the entity it belonged to as drawn
	 * <p>
	 * Called once per {@link com.sandymandy.strut.renderer.StrutRenderer#actuallyRender} invocation -
	 * once per model actually submitted, which includes layered sub-renders (armor, held items) as
	 * their own count, matching "entities drawn" being a proxy for "models submitted this frame"
	 * rather than literally "distinct game entities"
	 */
	public static void endSubmission() {
		submissionNanos += System.nanoTime() - submissionStartNanos;
		entitiesDrawn++;
	}

	/**
	 * Records one bone having been visited by the render walk
	 * <p>
	 * Called from {@code StrutRenderer#renderCubesOfBone}, which every
	 * {@code renderRecursively} override still calls unmodified (see that interface's own doc
	 * comment on why re-implementers must), so this stays accurate without instrumenting every
	 * renderer subtype that reimplements the recursive walk itself
	 */
	public static void recordBoneEvaluated() {
		bonesEvaluated++;
	}

	/**
	 * Records one vertex having been pushed to a {@link net.minecraft.client.render.VertexConsumer}
	 */
	public static void recordVertexSubmitted() {
		verticesSubmitted++;
	}

	/**
	 * Records one {@link com.sandymandy.strut.renderer.gpu.GpuBatchQueue#submit} call - one real
	 * entity's worth of GPU-path render this frame, counted separately from
	 * {@link #endSubmission}'s {@code entitiesDrawn} since a GPU-eligible entity never reaches that
	 * call at all (see {@code StrutRenderer#actuallyRender}'s own interception)
	 */
	public static void recordGpuInstanceSubmitted() {
		gpuInstancesSubmitted++;
	}

	/**
	 * Records one {@code glDrawElementsInstanced} call issued by
	 * {@link com.sandymandy.strut.renderer.gpu.GpuRenderBackend#drawInstances} - one per
	 * {@code (GpuModel, texture)} group per frame, however many instances that group holds. This is
	 * what counts the draw calls: this is the first point draw
	 * calls exist as a countable thing on this engine's own render path at all
	 */
	public static void recordGpuDrawCall() {
		gpuDrawCalls++;
	}

	/**
	 * Records one {@link com.sandymandy.strut.renderer.iris.IrisMainDraw#draw} call actually reaching
	 * its own {@code glMultiDrawElementsBaseVertex} -
	 * the one direct way to confirm the Iris GPU path is genuinely
	 * running rather than every entity silently falling back to the CPU walk (a per-bone override, the
	 * toggle being off, or {@code GpuBatchQueue.isShadowFlushUnreliable()} having tripped would all
	 * look identical from the CPU-side counters alone, since the CPU walk renders every one of those
	 * cases correctly too)
	 */
	public static void recordIrisGpuDrawCall() {
		irisGpuDrawCalls++;
	}

	/**
	 * One per-bone {@code TEXTURE} override redraw on the Iris path (a bone's index range drawn again
	 * with the override texture) - the counter that proves that path actually ran, since a working
	 * texture override and one that silently fell back look alike from the other counters
	 */
	public static void recordShadowSubmitted() {
		shadowSubmitted++;
	}

	public static void recordShadowFlush() {
		shadowFlushes++;
	}

	/** @param instances How many entities that draw covered */
	public static void recordShadowDrawn(int instances) {
		shadowDrawn += instances;
	}

	/** A shadow draw that did not happen because Iris supplied no program for it */
	public static void recordShadowSkipped(int instances) {
		shadowSkipped += instances;
	}

	public static void recordShadowPoseFailed() {
		shadowPoseFailed++;
	}

	public static void recordIrisBoneTextureDraw() {
		irisBoneTextureDraws++;
	}

	/** {@code instances} entities drawn as a translucent model this frame (one blended group draw) */
	public static void recordBlendedModel(int instances) {
		blendedModelInstances += instances;
	}

	/** One translucent bone range (an overlay when {@code overlay}) drawn in the blended pass */
	public static void recordBlendedBoneDraw(boolean overlay) {
		if (overlay)
			overlayDraws++;
		else
			blendedBoneDraws++;
	}

	/**
	 * Wraps {@link com.sandymandy.strut.renderer.gpu.GpuBatchQueue}'s per-frame flush - CPU time
	 * spent building this frame's palette/instance byte buffers and issuing every group's GL calls,
	 * as distinct from {@link #beginGpuTimer}'s own measurement of the GPU's own execution time for
	 * those same calls
	 */
	public static void beginGpuFlush() {
		gpuFlushStartNanos = System.nanoTime();
	}

	public static void endGpuFlush() {
		gpuFlushNanos += System.nanoTime() - gpuFlushStartNanos;
	}

	/**
	 * Records a {@code GL_TIMESTAMP} before this frame's GPU draw calls, and opportunistically reads
	 * back whichever slot's pair last completed - never the one about to be issued. A pair not being
	 * ready when its slot comes back around just leaves {@link #lastGpuTimerNanos} a frame staler;
	 * never blocks waiting for it
	 * <p>
	 * Render-thread only, like the rest of this class - the actual GL calls require it anyway
	 */
	public static void beginGpuTimer() {
		if (gpuTimerUnsupported)
			return;

		try {
			if (gpuQueryIds == null) {
				gpuQueryIds = new int[4];
				gpuQueryIssued = new boolean[2];

				GL15.glGenQueries(gpuQueryIds);
			}

			gpuQuerySlot = (gpuQuerySlot + 1) % gpuQueryIssued.length;

			int start = gpuQueryIds[gpuQuerySlot * 2];
			int end = gpuQueryIds[gpuQuerySlot * 2 + 1];

			if (gpuQueryIssued[gpuQuerySlot] && GL15.glGetQueryObjecti(end, GL15.GL_QUERY_RESULT_AVAILABLE) != 0)
				lastGpuTimerNanos = GL33.glGetQueryObjectui64(end, GL15.GL_QUERY_RESULT)
						- GL33.glGetQueryObjectui64(start, GL15.GL_QUERY_RESULT);

			GL33.glQueryCounter(start, GL33.GL_TIMESTAMP);
		}
		catch (Throwable ignored) {
			// A driver without ARB_timer_query support - report "n/a" forever after rather than
			// throwing on every frame's flush
			gpuTimerUnsupported = true;
		}
	}

	public static void endGpuTimer() {
		if (gpuTimerUnsupported || gpuQuerySlot < 0)
			return;

		GL33.glQueryCounter(gpuQueryIds[gpuQuerySlot * 2 + 1], GL33.GL_TIMESTAMP);
		gpuQueryIssued[gpuQuerySlot] = true;
	}

	/**
	 * The overlay's content, one line per entry, refreshed once per frame
	 */
	public static List<String> summaryLines() {
		List<String> lines = new ArrayList<>(10);

		lines.add("Strut render stats (F9 to hide)");

		// Live-caught gap: a low/zero "Iris GPU draw calls" line alone doesn't say *why* - the
		// question ("is the GPU running or not") needed reading three separate live-only states
		// (IrisCompat.isActive(), GpuBatchQueue.isShadowFlushUnreliable()) that nothing
		// on-screen showed at a glance, only a chat message that scrolls away the moment F7 is pressed
		lines.add("Iris: active=" + IrisCompat.isActive()
				+ ", shadow flush unreliable=" + GpuBatchQueue.isShadowFlushUnreliable());

		// The "did it actually run?" line for typed events. Cumulative since launch, so
		// compare before and after an action. Unbound keys are sound cues no binding set defined; capped sweeps
		// are ticks that crossed more events than the cap allows
		lines.add("events fired: server " + EventStats.serverDelivered() + " / client " + EventStats.clientDelivered()
				+ ", unbound keys " + EventStats.unboundKeys() + ", capped sweeps " + EventStats.truncatedSweeps()
				+ ", binding sets " + EventBindings.sets().size());

		// Asset sync: whether the server's assets are live, and what the last join had to fetch
		lines.addAll(StrutAssetSyncClient.statusLines());

		lines.add("shadow pass: submitted " + lastShadowSubmitted + ", flushes " + lastShadowFlushes + ", instances drawn " + lastShadowDrawn
				+ ", skipped (no program) " + lastShadowSkipped + ", pose failed " + lastShadowPoseFailed);

		lines.add("GPU instances drawn: " + lastGpuInstancesSubmitted);
		lines.add("GPU draw calls: " + lastGpuDrawCalls);
		lines.add("Iris GPU draw calls: " + lastIrisGpuDrawCalls);
		lines.add("Iris bone-texture draws: " + lastIrisBoneTextureDraws);
		lines.add("blended: model instances " + lastBlendedModelInstances + ", translucent bones " + lastBlendedBoneDraws + ", overlays " + lastOverlayDraws);
		lines.add(String.format("animation: %.2f ms", lastAnimationNanos / 1_000_000.0));
		lines.add("animations evaluated / skipped (LOD): " + lastAnimationsEvaluated + " / " + lastAnimationsSkipped);
		lines.add(String.format("submission: %.2f ms", lastSubmissionNanos / 1_000_000.0));
		lines.add(String.format("gpu flush (CPU): %.2f ms", lastGpuFlushNanos / 1_000_000.0));

		if (gpuTimerUnsupported)
			lines.add("gpu draw (GPU): n/a - ARB_timer_query unsupported here");
		else if (lastGpuTimerNanos < 0)
			lines.add("gpu draw (GPU): warming up");
		else
			lines.add(String.format("gpu draw (GPU): %.2f ms", lastGpuTimerNanos / 1_000_000.0));

		if (THREAD_MX_BEAN != null)
			lines.add(String.format("render-thread alloc: %.2f MB/frame", lastAllocatedBytes / (1024.0 * 1024.0)));
		else
			lines.add("render-thread alloc: n/a - ThreadMXBean allocation tracking unsupported here");

		for (Supplier<List<String>> provider : LINE_PROVIDERS) {
			try {
				lines.addAll(provider.get());
			}
			catch (RuntimeException ex) {
				lines.add("(a stats line provider failed: " + ex + ")");
			}
		}

		return lines;
	}

	private static final CopyOnWriteArrayList<Supplier<List<String>>> LINE_PROVIDERS = new CopyOnWriteArrayList<>();

	/**
	 * Lets a module add its own lines to the F9 overlay, after Strut's. The supplier is called once per rendered frame while the overlay is visible
	 */
	public static void addSummaryLineProvider(Supplier<List<String>> provider) {
		LINE_PROVIDERS.add(provider);
	}
}
