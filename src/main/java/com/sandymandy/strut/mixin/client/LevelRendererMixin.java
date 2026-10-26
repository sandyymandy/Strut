package com.sandymandy.strut.mixin.client;

import com.sandymandy.strut.player.client.FirstPersonBody;
import com.sandymandy.strut.renderer.gpu.GpuBatchQueue;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.LightmapTextureManager;
import org.joml.Matrix4f;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.sandymandy.strut.animation.AnimationLod;
import com.sandymandy.strut.loading.math.MathParser;
import com.sandymandy.strut.loading.math.MolangQueries;
import com.sandymandy.strut.perf.RenderPerfStats;

/**
 * Capture pre-render data for Molang queries, and mark the render measurement
 * harness's frame boundary
 */
@Mixin(WorldRenderer.class)
public class LevelRendererMixin {
    @Shadow
    private int regularEntityCount;

    /**
     * The world does not draw the camera's own entity in first person. A player looking out of their own immersive body is drawn: the camera is at the body's
     * eye bone, and the body is what they see
     */
    @WrapOperation(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/Camera;isThirdPerson()Z"))
    private boolean strut$drawOwnBody(Camera camera, Operation<Boolean> original) {
        return original.call(camera) || FirstPersonBody.immersiveBodyOf(camera.getFocusedEntity()) != null;
    }

    /**
     * Fabric's AFTER_ENTITIES fires before the block entities are drawn, so a Strut block entity (queued while it is drawn) missed that frame's draw and was drawn
     * in the next one, from the camera of the frame before: it slid about the world as the camera turned. The queue is drawn again right after them, still before the
     * terrain's translucent layer
     */
    @Inject(method = "render", at = @At(value = "INVOKE_STRING", target = "Lnet/minecraft/util/profiler/Profiler;swap(Ljava/lang/String;)V", args = "ldc=destroyProgress"))
    private void strut$drawBlockEntities(RenderTickCounter deltaTracker, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer, LightmapTextureManager lightTexture, Matrix4f frustumMatrix, Matrix4f projectionMatrix, CallbackInfo ci) {
        GpuBatchQueue.flushBlockEntities();
    }

    @Inject(method = "render", at = @At(value = "HEAD"))
    public void strut$captureRenderedEntities(RenderTickCounter deltaTracker, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer, LightmapTextureManager lightTexture, Matrix4f frustumMatrix, Matrix4f projectionMatrix, CallbackInfo ci) {
        final int renderedEntityCount = this.regularEntityCount;

        MathParser.setVariable(MolangQueries.ACTOR_COUNT, () -> renderedEntityCount);

        // See RenderPerfStats#beginFrame - reusing this inject point rather than adding a second
        // mixin for it, since this is already the single choke point every entity render this
        // frame happens after
        RenderPerfStats.beginFrame();

        // Same reasoning - the camera position
        // this frame's animation-LOD distance checks need is already available right here
        AnimationLod.beginFrame(camera);
    }
}
