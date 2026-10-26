package com.sandymandy.strut.renderer;

import net.minecraft.client.render.DiffuseLighting;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.entity.model.EntityModelLoader;
import net.minecraft.client.render.item.BuiltinModelItemRenderer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.block.entity.BlockEntityRenderDispatcher;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.item.ItemRenderer;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.item.Item;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.item.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import com.sandymandy.strut.StrutServices;
import com.sandymandy.strut.animatable.StrutAnimatable;
import com.sandymandy.strut.animatable.StrutItem;
import com.sandymandy.strut.animation.AnimationState;
import com.sandymandy.strut.animation.state.PosePalette;
import com.sandymandy.strut.cache.object.BakedStrutModel;
import com.sandymandy.strut.cache.object.GroupBoneHandle;
import com.sandymandy.strut.cache.texture.AnimatableTexture;
import com.sandymandy.strut.constant.DataTickets;
import com.sandymandy.strut.model.DefaultedItemStrutModel;
import com.sandymandy.strut.model.StrutModel;
import com.sandymandy.strut.util.RenderUtil;

/**
 * Base {@link StrutRenderer} class for rendering {@link Item Items} specifically
 * <p>
 * All items added to be rendered by Strut should use an instance of this class.
 */
public class StrutItemRenderer<T extends Item & StrutAnimatable> extends BuiltinModelItemRenderer implements StrutRenderer<T> {
	protected final StrutModel<T> model;

	protected ItemStack currentItemStack;
	protected ModelTransformationMode renderPerspective;
	protected T animatable;
	protected float scaleWidth = 1;
	protected float scaleHeight = 1;
	protected boolean useEntityGuiLighting = false;

	protected Matrix4f itemRenderTranslations = new Matrix4f();
	protected Matrix4f modelRenderTranslations = new Matrix4f();

    /**
     * Creates a new defaulted renderer instance, using the item's registered id as the file name for its assets
     */
    public <I extends T> StrutItemRenderer(I item) {
        this(new DefaultedItemStrutModel<>(Registries.ITEM.getId(item)));
    }

	public StrutItemRenderer(StrutModel<T> model) {
		this(MinecraftClient.getInstance().getBlockEntityRenderDispatcher(), MinecraftClient.getInstance().getEntityModelLoader(),
				model);
	}

	public StrutItemRenderer(BlockEntityRenderDispatcher dispatcher, EntityModelLoader modelSet, StrutModel<T> model) {
		super(dispatcher, modelSet);

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
	 * Returns the current ItemStack being rendered
	 */
	public ItemStack getCurrentItemStack() {
		return this.currentItemStack;
	}

	/**
	 * Mark this renderer so that it uses an alternate lighting scheme when rendering the item in GUI
	 * <p>
	 * This can help with improperly lit 3d models
	 */
	public StrutItemRenderer<T> useAlternateGuiLighting() {
		this.useEntityGuiLighting = true;

		return this;
	}

	/**
	 * Gets the id that represents the current animatable's instance for animation purposes
	 * <p>
	 * This is mostly useful for things like items, which have a single registered instance for all objects
	 */
	@Override
	public long getInstanceId(T animatable) {
		return StrutItem.getId(this.currentItemStack);
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
	public StrutItemRenderer<T> withScale(float scale) {
		return withScale(scale, scale);
	}

	/**
	 * Sets a scale override for this renderer, telling Strut to pre-scale the model
	 */
	public StrutItemRenderer<T> withScale(float scaleWidth, float scaleHeight) {
		this.scaleWidth = scaleWidth;
		this.scaleHeight = scaleHeight;

		return this;
	}

	/**
	 * Called before rendering the model to buffer. Allows for render modifications and preparatory work such as scaling and translating
	 * <p>
	 * {@link MatrixStack} translations made here are kept until the end of the render process
	 */
	@Override
	public void preRender(MatrixStack poseStack, T animatable, BakedStrutModel model, @Nullable VertexConsumerProvider bufferSource, @Nullable VertexConsumer buffer, boolean isReRender, float partialTick, int packedLight, int packedOverlay, int colour) {
		this.itemRenderTranslations = new Matrix4f(poseStack.peek().getPositionMatrix());

		scaleModelForRender(this.scaleWidth, this.scaleHeight, poseStack, animatable, model, isReRender, partialTick, packedLight, packedOverlay);

		if (!isReRender)
			poseStack.translate(0.5f, 0.51f, 0.5f);
	}

	@Override
	@ApiStatus.Internal
	public void render(ItemStack stack, ModelTransformationMode transformType, MatrixStack poseStack,
			VertexConsumerProvider bufferSource, int packedLight, int packedOverlay) {
		this.animatable = (T)stack.getItem();
		this.currentItemStack = stack;
		this.renderPerspective = transformType;
		float partialTick = MinecraftClient.getInstance().getRenderTickCounter().getTickDelta(true);

		if (transformType == ModelTransformationMode.GUI) {
			renderInGui(transformType, poseStack, bufferSource, packedLight, packedOverlay, partialTick);
		}
		else {
			RenderLayer renderType = getRenderType(this.animatable, getTexture(this.animatable), bufferSource, partialTick);
			VertexConsumer buffer = ItemRenderer.getDirectItemGlintConsumer(bufferSource, renderType, false, this.currentItemStack != null && this.currentItemStack.hasGlint());

			defaultRender(poseStack, this.animatable, bufferSource, renderType, buffer,
					0, partialTick, packedLight);
		}

		this.animatable = null;
	}

	/**
	 * Wrapper method to handle rendering the item in a GUI context (defined by {@link ModelTransformationMode#GUI} normally)
	 * <p>
	 * Just includes some additional required transformations and settings
	 */
	protected void renderInGui(ModelTransformationMode transformType, MatrixStack poseStack,
							   VertexConsumerProvider bufferSource, int packedLight, int packedOverlay, float partialTick) {
		setupLightingForGuiRender();

		VertexConsumerProvider.Immediate defaultBufferSource = bufferSource instanceof VertexConsumerProvider.Immediate bufferSource2 ? bufferSource2 : MinecraftClient.getInstance().worldRenderer.bufferBuilders.getEntityVertexConsumers();
		RenderLayer renderType = getRenderType(this.animatable, getTexture(this.animatable), defaultBufferSource, partialTick);
		VertexConsumer buffer = ItemRenderer.getDirectItemGlintConsumer(bufferSource, renderType, true, this.currentItemStack != null && this.currentItemStack.hasGlint());

		poseStack.push();
		defaultRender(poseStack, this.animatable, defaultBufferSource, renderType, buffer, 0, partialTick, packedLight);
		defaultBufferSource.draw();
		RenderSystem.enableDepthTest();
		DiffuseLighting.enableGuiDepthLighting();
		poseStack.pop();
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

		if (!isReRender) {
			AnimationState<T> animationState = new AnimationState<>(animatable, 0, 0, partialTick, false);
			long instanceId = getInstanceId(animatable);
			StrutModel<T> currentModel = getStrutModel();

			animationState.setData(DataTickets.TICK, animatable.getTick(this.currentItemStack));
			animationState.setData(DataTickets.ITEM_RENDER_PERSPECTIVE, this.renderPerspective);
			animationState.setData(DataTickets.ITEMSTACK, this.currentItemStack);
			animatable.getAnimatableInstanceCache().getManagerForId(instanceId).setData(DataTickets.ITEM_RENDER_PERSPECTIVE, this.renderPerspective);
			currentModel.addAdditionalStateData(animatable, instanceId, animationState::setData);
			currentModel.handleAnimations(animatable, instanceId, animationState, partialTick);
		}

		this.modelRenderTranslations = new Matrix4f(poseStack.peek().getPositionMatrix());

		if (buffer != null)
			StrutRenderer.super.actuallyRender(poseStack, animatable, model, renderType, bufferSource, buffer, isReRender, partialTick,
					packedLight, packedOverlay, colour);
		else if (!isReRender)
			trackAllBones(animatable, model);
	}

	/**
	 * Called after all render operations are completed and the render pass is considered functionally complete.
	 * <p>
	 * Use this method to clean up any leftover persistent objects stored during rendering or any other post-render maintenance tasks as required
	 */
	@Override
	public void doPostRenderCleanup() {
		this.animatable = null;
		this.currentItemStack = null;
		this.renderPerspective = null;
	}

	/**
	 * Refreshes {@code bone}'s tracked matrices from the current pose - see
	 * {@link StrutRenderer#trackBoneMatrices}'s own doc comment for why this no longer needs
	 * {@link MatrixStack} at all, and for the previous "before this bone's own transform" bug it
	 * fixes (items are one of the three renderer types that had it)
	 */
	@Override
	public void trackBoneMatrices(T animatable, GroupBoneHandle bone) {
		if (!bone.isTrackingMatrices())
			return;

		Matrix4f modelSpace = new Matrix4f();

		PosePalette.computeBoneAtPivot(getModelInstanceState(animatable).renderedPose(), bone.getDefinition(), modelSpace);
		bone.setModelSpaceMatrix(modelSpace);

		Matrix4f delta = RenderUtil.invertAndMultiplyMatrices(this.modelRenderTranslations, this.itemRenderTranslations);

		bone.setLocalSpaceMatrix(new Matrix4f(delta).mul(modelSpace));
	}

	/**
	 * Set the current lighting normals for the current render pass
	 * <p>
	 * Only used for {@link ModelTransformationMode#GUI} rendering
	 */
	public void setupLightingForGuiRender() {
		if (this.useEntityGuiLighting) {
			DiffuseLighting.enableGuiDepthLighting();
		}
		else {
			DiffuseLighting.disableGuiDepthLighting();
		}
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
		return StrutServices.Client.EVENTS.fireItemPreRender(this, poseStack, model, bufferSource, partialTick, packedLight);
	}

	/**
	 * Create and fire the relevant {@code Post-Render} event hook for this renderer
	 */
	@Override
	public void firePostRenderEvent(MatrixStack poseStack, BakedStrutModel model, VertexConsumerProvider bufferSource, float partialTick, int packedLight) {
		StrutServices.Client.EVENTS.fireItemPostRender(this, poseStack, model, bufferSource, partialTick, packedLight);
	}
}
