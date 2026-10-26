package com.sandymandy.strut.mixin.iris;

import com.sandymandy.strut.renderer.gpu.GpuBatchQueue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The fix for an unreliable shadow-queue flush
 * - flushes {@code GpuBatchQueue}'s shadow-pass queue once per shadow pass from inside Iris's own real
 * {@code ShadowRenderer.renderShadows(LevelRendererAccessor, Camera)} (
 * signature and injection point confirmed against the actual installed 1.8.8 jar via {@code javap},
 * not assumed from the 1.8.14 source alone) - see {@link #strut$flushShadowQueue}'s own doc comment for
 * exactly where, and why not the method's tail
 * <p>
 * <b>Replaces the original fix, {@code RenderLayerShadowFlushMixin}, which turned out unreliable.</b>
 * That one hooked vanilla {@code RenderLayer.draw(BuiltBuffer)} (real code Iris's own batched shadow
 * source does call) on the theory that *something* would draw during the shadow pass - true for a
 * real scene with terrain and block entities nearby, false for a sparse one. Live-caught: a
 * `/strut bench` grid on flat grass with nothing else in shadow range never called it at all, so
 * every shadow submission queued that frame just sat there un-flushed -
 * {@code GpuBatchQueue.isShadowFlushUnreliable()} correctly tripped and fell every entity back to
 * CPU, which is what the safety net is for, but the entity's own shadow was still silently dropped
 * every frame before that. This mixin fires exactly once per shadow pass regardless of what else is
 * in the scene, so it doesn't depend on anything else drawing at all.
 * <p>
 * <b>String-targeted, never a class literal</b> - {@code @Mixin(targets = "...", remap = false)}
 * defers resolving {@code ShadowRenderer} to the moment Mixin actually tries to apply this mixin,
 * which {@link IrisMixinPlugin} only ever allows when Iris is loaded. The injected method's own
 * signature deliberately omits {@code renderShadows}'s two real parameters (an
 * {@code Iris}-internal {@code LevelRendererAccessor} and vanilla {@code Camera}, neither needed
 * here) rather than declaring them - Mixin only requires an {@code @Inject} handler to declare the
 * target's parameters it actually reads, and a bare {@code CallbackInfo} keeps this class' own
 * compiled bytecode free of any reference to an Iris-internal type, which a full parameter list
 * would otherwise embed regardless of whether the method body ever runs
 */
@Mixin(targets = "net.irisshaders.iris.shadows.ShadowRenderer", remap = false)
public class ShadowRendererMixin {
	/**
	 * Just before Iris's own flush of its batched shadow entities - the single
	 * {@code VertexConsumerProvider.Immediate#draw()} call in {@code renderShadows} (bytecode offset 901
	 * in the real 1.8.8 jar, after {@code renderEntities}/{@code renderPlayerEntity}, before
	 * {@code copyPreTranslucentDepth}/{@code restorePlayerProjection}). Not {@code TAIL}, as first
	 * built: by the tail Iris has already restored the player's projection, rebound the main render
	 * target and cleared {@code ShadowRenderer.ACTIVE}, so the shadow queue was drawn into the normal
	 * view with the normal camera and the normal entity program - live-seen as a tilted copy of the
	 * entity floating in the sky, and no GPU-path shadow at all. Here, the shadow framebuffer, Iris's
	 * shadow program (what {@code getRenderTypeEntityCutoutNoNullProgram()} returns while
	 * {@code ACTIVE} is true) and the shadow camera's {@code RenderSystem} matrices are all still
	 * current - exactly the state Iris's own batched shadow entities are drawn in a moment later
	 * <p>
	 * {@code remap = true} on the {@code @At} alone: the target is a vanilla member, named for Yarn
	 * here and remapped like any other vanilla reference, while {@code renderShadows} itself is Iris's
	 * own and never remapped
	 */
	@Inject(method = "renderShadows",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/VertexConsumerProvider$Immediate;draw()V", remap = true),
			require = 0)
	private void strut$flushShadowQueue(CallbackInfo ci) {
		GpuBatchQueue.flushShadowQueue();
	}
}
