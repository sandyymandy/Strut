package com.sandymandy.strut.renderer;

import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import com.sandymandy.strut.StrutServices;
import com.sandymandy.strut.cache.object.BakedStrutModel;
import com.sandymandy.strut.cache.object.GroupBoneHandle;
import com.sandymandy.strut.cache.texture.AnimatableTexture;
import com.sandymandy.strut.animatable.StrutAnimatable;
import com.sandymandy.strut.animation.AnimationState;
import com.sandymandy.strut.animation.state.PosePalette;
import com.sandymandy.strut.model.StrutModel;
import com.sandymandy.strut.util.RenderUtil;

/**
 * Base {@link StrutRenderer} class for rendering anything that isn't already handled by the other builtin StrutRenderer subclasses
 * <p>
 * Before using this class you should ensure your use-case isn't already covered by one of the other existing renderers
 * <p>
 * It is <b>strongly</b> recommended you override {@link StrutRenderer#getInstanceId} if using this renderer
 */
public class StrutObjectRenderer<T extends StrutAnimatable> implements StrutRenderer<T> {
	protected final StrutModel<T> model;

	protected T animatable;
	protected float scaleWidth = 1;
	protected float scaleHeight = 1;

	protected Matrix4f objectRenderTranslations = new Matrix4f();
	protected Matrix4f modelRenderTranslations = new Matrix4f();

	public StrutObjectRenderer(StrutModel<T> model) {
		this.model = model;
	}

	/**
	 * Gets the model instance for this renderer
	 */
	@Override
	public StrutModel<T> getStrutModel() {
		return this.model;
	}

	/**
	 * Gets the {@link StrutAnimatable} instance currently being rendered
	 */
	@Override
	public T getAnimatable() {
		return this.animatable;
	}

	/**
	 * Shadowing override of {@link EntityRenderer#getTexture}
	 * <p>
	 * This redirects the call to {@link StrutRenderer#getTexture}
	 */
	@Override
	public Identifier getTexture(T animatable) {
		return StrutRenderer.super.getTexture(animatable);
	}

	/**
	 * Sets a scale override for this renderer, telling Strut to pre-scale the model
	 */
	public StrutObjectRenderer<T> withScale(float scale) {
		return withScale(scale, scale);
	}

	/**
	 * Sets a scale override for this renderer, telling Strut to pre-scale the model
	 */
	public StrutObjectRenderer<T> withScale(float scaleWidth, float scaleHeight) {
		this.scaleWidth = scaleWidth;
		this.scaleHeight = scaleHeight;

		return this;
	}

	/**
	 * The entry render point for this renderer
	 * <p>
	 * Call this whenever you want to render your object
	 *
	 * @param poseStack The MatrixStack to render under
	 * @param animatable The {@link T} instance to render
	 * @param bufferSource The BufferSource to render with, or null to use the default
	 * @param renderType The specific RenderLayer to use, or null to fall back to {@link StrutRenderer#getRenderType}
	 * @param buffer The VertexConsumer to use for rendering, or null to use the default for the RenderLayer
	 * @param packedLight The light level at the given render position for rendering
	 */
	@ApiStatus.Internal
	public void render(MatrixStack poseStack, T animatable, @Nullable VertexConsumerProvider bufferSource, @Nullable RenderLayer renderType,
					   @Nullable VertexConsumer buffer, int packedLight, float partialTick) {
		this.animatable = animatable;

		if (buffer == null)
			bufferSource = MinecraftClient.getInstance().worldRenderer.bufferBuilders.getEntityVertexConsumers();

		defaultRender(poseStack, animatable, bufferSource, renderType, buffer, 0, partialTick, packedLight);
	}

	/**
	 * Called before rendering the model to buffer. Allows for render modifications and preparatory work such as scaling and translating
	 * <p>
	 * {@link MatrixStack} translations made here are kept until the end of the render process
	 */
	@Override
	public void preRender(MatrixStack poseStack, T animatable, BakedStrutModel model, @Nullable VertexConsumerProvider bufferSource, @Nullable VertexConsumer buffer, boolean isReRender, float partialTick, int packedLight, int packedOverlay, int colour) {
		this.objectRenderTranslations = new Matrix4f(poseStack.peek().getPositionMatrix());

		scaleModelForRender(this.scaleWidth, this.scaleHeight, poseStack, animatable, model, isReRender, partialTick, packedLight, packedOverlay);

		poseStack.translate(0.5f, 0.51f, 0.5f);
	}

	/**
	 * The actual render method that subtype renderers should override to handle their specific rendering tasks
	 * <p>
	 * {@link StrutRenderer#preRender} has already been called by this stage, and {@link StrutRenderer#postRender} will be called directly after
	 */
	@Override
	public void actuallyRender(MatrixStack poseStack, T animatable, BakedStrutModel model, @Nullable RenderLayer renderType,
							   VertexConsumerProvider bufferSource, @Nullable VertexConsumer buffer, boolean isReRender, float partialTick,
							   int packedLight, int packedOverlay, int colour) {
		poseStack.push();

		if (!isReRender) {
			AnimationState<T> animationState = new AnimationState<>(animatable, 0, 0, partialTick, false);
			long instanceId = getInstanceId(animatable);
			StrutModel<T> currentModel = getStrutModel();

			currentModel.addAdditionalStateData(animatable, instanceId, animationState::setData);
			currentModel.handleAnimations(animatable, instanceId, animationState, partialTick);
		}

		this.modelRenderTranslations = new Matrix4f(poseStack.peek().getPositionMatrix());

		if (buffer != null)
			StrutRenderer.super.actuallyRender(poseStack, animatable, model, renderType, bufferSource, buffer, isReRender, partialTick,
					packedLight, packedOverlay, colour);

		poseStack.pop();
	}

	/**
	 * Called after all render operations are completed and the render pass is considered functionally complete.
	 * <p>
	 * Use this method to clean up any leftover persistent objects stored during rendering or any other post-render maintenance tasks as required
	 */
	@Override
	public void doPostRenderCleanup() {
		this.animatable = null;
	}

	/**
	 * Refreshes {@code bone}'s tracked matrices from the current pose - see
	 * {@link StrutRenderer#trackBoneMatrices}'s own doc comment for why this no longer needs
	 * {@link MatrixStack} at all, and for the previous "before this bone's own transform" bug it
	 * fixes (generic objects are one of the three renderer types that had it)
	 */
	@Override
	public void trackBoneMatrices(T animatable, GroupBoneHandle bone) {
		if (!bone.isTrackingMatrices())
			return;

		Matrix4f modelSpace = new Matrix4f();

		PosePalette.computeBoneAtPivot(getModelInstanceState(animatable).renderedPose(), bone.getDefinition(), modelSpace);
		bone.setModelSpaceMatrix(modelSpace);

		Matrix4f delta = RenderUtil.invertAndMultiplyMatrices(this.modelRenderTranslations, this.objectRenderTranslations);

		bone.setLocalSpaceMatrix(new Matrix4f(delta).mul(modelSpace));
	}

	/**
	 * Update the current frame of a {@link AnimatableTexture potentially animated} texture used by this StrutRenderer
	 * <p>
	 * This should only be called immediately prior to rendering
	 *
	 * @see AnimatableTexture#setAndUpdate
	 */
	@Override
	public void updateAnimatedTextureFrame(T animatable) {
		AnimatableTexture.setAndUpdate(getTexture(animatable));
	}

	/**
	 * Create and fire the relevant {@code Pre-Render} event hook for this renderer
	 *
	 * @return Whether the renderer should proceed based on the cancellation state of the event
	 */
	@Override
	public boolean firePreRenderEvent(MatrixStack poseStack, BakedStrutModel model, VertexConsumerProvider bufferSource, float partialTick, int packedLight) {
		return StrutServices.Client.EVENTS.fireObjectPreRender(this, poseStack, model, bufferSource, partialTick, packedLight);
	}

	/**
	 * Create and fire the relevant {@code Post-Render} event hook for this renderer
	 */
	@Override
	public void firePostRenderEvent(MatrixStack poseStack, BakedStrutModel model, VertexConsumerProvider bufferSource, float partialTick, int packedLight) {
		StrutServices.Client.EVENTS.fireObjectPostRender(this, poseStack, model, bufferSource, partialTick, packedLight);
	}
}
