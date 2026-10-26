package com.sandymandy.strut.renderer;

import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.registry.Registries;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.block.BlockState;
import net.minecraft.state.property.Properties;
import net.minecraft.state.property.EnumProperty;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import com.sandymandy.strut.StrutServices;
import com.sandymandy.strut.animatable.StrutAnimatable;
import com.sandymandy.strut.animation.AnimationState;
import com.sandymandy.strut.animation.state.PosePalette;
import com.sandymandy.strut.cache.object.BakedStrutModel;
import com.sandymandy.strut.cache.object.GroupBoneHandle;
import com.sandymandy.strut.cache.texture.AnimatableTexture;
import com.sandymandy.strut.constant.DataTickets;
import com.sandymandy.strut.model.DefaultedBlockStrutModel;
import com.sandymandy.strut.model.StrutModel;
import com.sandymandy.strut.util.RenderUtil;

/**
 * Base {@link StrutRenderer} class for rendering {@link BlockEntity Blocks} specifically
 * <p>
 * All blocks added to be rendered by Strut should use an instance of this class.
 */
public class StrutBlockRenderer<T extends BlockEntity & StrutAnimatable> implements StrutRenderer<T>, BlockEntityRenderer<T> {
	@SuppressWarnings("unchecked")
	protected static final EnumProperty<Direction>[] SUPPORTED_DIRECTION_PROPERTIES = new EnumProperty[] {Properties.FACING, Properties.HORIZONTAL_FACING, Properties.VERTICAL_DIRECTION, Properties.HOPPER_FACING };

	protected final StrutModel<T> model;

	protected T animatable;
	protected float scaleWidth = 1;
	protected float scaleHeight = 1;

	protected Matrix4f blockRenderTranslations = new Matrix4f();
	protected Matrix4f modelRenderTranslations = new Matrix4f();

    /**
     * Creates a new defaulted renderer instance, using the blockentity's registered id as the file name for its assets
     */
    public StrutBlockRenderer(BlockEntityType<? extends T> blockEntityType) {
        this(new DefaultedBlockStrutModel<>(Registries.BLOCK_ENTITY_TYPE.getId(blockEntityType)));
    }

	public StrutBlockRenderer(StrutModel<T> model) {
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
	 * Gets the id that represents the current animatable's instance for animation purposes
	 * <p>
	 * This is mostly useful for things like items, which have a single registered instance for all objects
	 */
	@Override
	public long getInstanceId(T animatable) {
		return animatable.getPos().hashCode();
	}

	/**
	 * Sets a scale override for this renderer, telling Strut to pre-scale the model
	 */
	public StrutBlockRenderer<T> withScale(float scale) {
		return withScale(scale, scale);
	}

	/**
	 * Sets a scale override for this renderer, telling Strut to pre-scale the model
	 */
	public StrutBlockRenderer<T> withScale(float scaleWidth, float scaleHeight) {
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
		this.blockRenderTranslations = new Matrix4f(poseStack.peek().getPositionMatrix());

		if (!isReRender)
			poseStack.translate(0.5, 0, 0.5);

		scaleModelForRender(this.scaleWidth, this.scaleHeight, poseStack, animatable, model, isReRender, partialTick, packedLight, packedOverlay);

		// the bone overrides of this block entity, resolved once before the walk or the GPU submit that reads them
		getModelInstanceState(animatable).prepareForRender(animatable.getPos());
	}

	@Override
	@ApiStatus.Internal
	public void render(T animatable, float partialTick, MatrixStack poseStack, VertexConsumerProvider bufferSource,
			int packedLight, int packedOverlay) {
		this.animatable = animatable;

		defaultRender(poseStack, this.animatable, bufferSource, null, null, 0, partialTick, packedLight);
	}

	/**
	 * The actual render method that subtype renderers should override to handle their specific rendering tasks
	 * <p>
	 * {@link StrutRenderer#preRender} has already been called by this stage, and {@link StrutRenderer#postRender} will be called directly after
	 */
	@Override
	public void actuallyRender(MatrixStack poseStack, T animatable, BakedStrutModel model, @Nullable RenderLayer renderType,
							   VertexConsumerProvider bufferSource, @Nullable VertexConsumer buffer, boolean isReRender, float partialTick, int packedLight,
							   int packedOverlay, int colour) {
		if (!isReRender) {
			AnimationState<T> animationState = new AnimationState<T>(animatable, 0, 0, partialTick, false);
			long instanceId = getInstanceId(animatable);
			StrutModel<T> currentModel = getStrutModel();

			animationState.setData(DataTickets.TICK, animatable.getTick(animatable));
			animationState.setData(DataTickets.BLOCK_ENTITY, animatable);
			currentModel.addAdditionalStateData(animatable, instanceId, animationState::setData);
			rotateBlock(getFacing(animatable), poseStack);
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
	}

	/**
	 * Refreshes {@code bone}'s tracked matrices from the current pose - see
	 * {@link StrutRenderer#trackBoneMatrices}'s own doc comment for why this no longer needs
	 * {@link MatrixStack} at all. {@code worldSpaceMatrix} translates by this block entity's own
	 * {@link BlockPos}, not an entity's interpolated world position - blocks don't move
	 */
	@Override
	public void trackBoneMatrices(T animatable, GroupBoneHandle bone) {
		if (!bone.isTrackingMatrices())
			return;

		Matrix4f modelSpace = new Matrix4f();

		PosePalette.computeBoneAtPivot(getModelInstanceState(animatable).renderedPose(), bone.getDefinition(), modelSpace);
		bone.setModelSpaceMatrix(modelSpace);

		Matrix4f delta = RenderUtil.invertAndMultiplyMatrices(this.modelRenderTranslations, this.blockRenderTranslations);
		Matrix4f localSpace = new Matrix4f(delta).mul(modelSpace);

		bone.setLocalSpaceMatrix(localSpace);

		BlockPos pos = animatable.getPos();
		Matrix4f worldSpace = new Matrix4f(localSpace);

		worldSpace.translate(new Vector3f(pos.getX(), pos.getY(), pos.getZ()));
		bone.setWorldSpaceMatrix(worldSpace);
	}

	/**
	 * Rotate the {@link MatrixStack} based on the determined {@link Direction} the block is facing
	 */
	protected void rotateBlock(Direction facing, MatrixStack poseStack) {
		switch (facing) {
			case SOUTH -> poseStack.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180));
			case WEST -> poseStack.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(90));
			case NORTH -> poseStack.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(0));
			case EAST -> poseStack.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(270));
			case UP -> poseStack.multiply(RotationAxis.POSITIVE_X.rotationDegrees(90));
			case DOWN -> poseStack.multiply(RotationAxis.NEGATIVE_X.rotationDegrees(90));
		}
	}

	/**
	 * Attempt to extract a direction from the block so that the model can be oriented correctly
	 */
	protected Direction getFacing(T block) {
		BlockState blockState = block.getCachedState();

		for (EnumProperty<Direction> property : SUPPORTED_DIRECTION_PROPERTIES) {
			if (blockState.contains(property))
				return blockState.get(property);
		}

		return Direction.NORTH;
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
		return StrutServices.Client.EVENTS.fireBlockPreRender(this, poseStack, model, bufferSource, partialTick, packedLight);
	}

	/**
	 * Create and fire the relevant {@code Post-Render} event hook for this renderer
	 */
	@Override
	public void firePostRenderEvent(MatrixStack poseStack, BakedStrutModel model, VertexConsumerProvider bufferSource, float partialTick, int packedLight) {
		StrutServices.Client.EVENTS.fireBlockPostRender(this, poseStack, model, bufferSource, partialTick, packedLight);
	}
}
