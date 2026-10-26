package com.sandymandy.strut.renderer;

import com.sandymandy.strut.model.DefaultedArmorStrutModel;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.item.ItemRenderer;
import net.minecraft.client.render.entity.feature.ArmorFeatureRenderer;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.component.type.DyedColorComponent;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import java.util.UUID;
import com.sandymandy.strut.StrutConstants;
import com.sandymandy.strut.StrutServices;
import com.sandymandy.strut.animatable.StrutItem;
import com.sandymandy.strut.animation.state.ModelInstance;
import com.sandymandy.strut.animation.state.PosePalette;
import com.sandymandy.strut.cache.object.BakedStrutModel;
import com.sandymandy.strut.cache.object.GroupBoneDefinition;
import com.sandymandy.strut.cache.object.GroupBoneHandle;
import com.sandymandy.strut.cache.texture.AnimatableTexture;
import com.sandymandy.strut.constant.DataTickets;
import com.sandymandy.strut.animatable.StrutAnimatable;
import com.sandymandy.strut.animation.AnimationState;
import com.sandymandy.strut.model.DefaultedStrutModel;
import com.sandymandy.strut.model.StrutModel;
import com.sandymandy.strut.util.Color;
import com.sandymandy.strut.util.RenderUtil;

/**
 * Base {@link StrutRenderer} for rendering in-world armor specifically
 * <p>
 * All custom armor added to be rendered in-world by Strut should use an instance of this class
 *
 * @see StrutItem
 */
public class StrutArmorRenderer<T extends Item & StrutItem> extends BipedEntityModel implements StrutRenderer<T> {
	protected final StrutModel<T> model;

	protected T animatable;
	protected BipedEntityModel<?> baseModel;
	protected float scaleWidth = 1;
	protected float scaleHeight = 1;

	protected Matrix4f entityRenderTranslations = new Matrix4f();
	protected Matrix4f modelRenderTranslations = new Matrix4f();

	protected BakedStrutModel lastModel = null;
	/*
	 * These are BoneDefinitions, not BoneHandles - safe to cache across every wearer of this armor
	 * (keyed on `lastModel`, exactly like before) because a definition is immutable and shared.
	 * The actual per-wearer transform is only ever read or written through handleFor(...), resolved
	 * against whichever animatable is currently set as this.animatable - see grabRelevantBones and
	 * the handleFor helper below. Caching a *handle* here instead would be the exact D4 bug this
	 * whole per-instance design exists to close: one armor renderer is shared by every entity wearing it,
	 * so a cached handle would silently keep pointing at whichever entity resolved it first
	 */
	protected GroupBoneDefinition head = null;
	protected GroupBoneDefinition body = null;
	protected GroupBoneDefinition rightArm = null;
	protected GroupBoneDefinition leftArm = null;
	protected GroupBoneDefinition rightLeg = null;
	protected GroupBoneDefinition leftLeg = null;
	protected GroupBoneDefinition rightBoot = null;
	protected GroupBoneDefinition leftBoot = null;

	protected Entity currentEntity = null;
	protected ItemStack currentStack = null;
	protected EquipmentSlot currentSlot = null;
	protected VertexConsumerProvider bufferSource = null;
	protected float partialTick;
	protected float limbSwing;
	protected float limbSwingAmount;
	protected float netHeadYaw;
	protected float headPitch;

    /**
     * Creates a new defaulted renderer instance, using the item's registered id as the file name for its assets
     */
    public <I extends T> StrutArmorRenderer(I armorItem) {
        this(new DefaultedArmorStrutModel<>(Registries.ITEM.getId(armorItem)));
    }

	public StrutArmorRenderer(StrutModel<T> model) {
		super(MinecraftClient.getInstance().getEntityModelLoader().getModelPart(EntityModelLayers.PLAYER_INNER_ARMOR));

		this.model = model;
		this.child = false;
	}

	/**
	 * Gets the model instance for this renderer
	 */
	@Override
	public StrutModel<T> getStrutModel() {
		return this.model;
	}

	/**
	 * Gets the {@link StrutItem} instance currently being rendered
	 */
	public T getAnimatable() {
		return this.animatable;
	}

	/**
	 * Returns the entity currently being rendered with armour equipped
	 */
	public Entity getCurrentEntity() {
		return this.currentEntity;
	}

	/**
	 * Returns the ItemStack pertaining to the current piece of armor being rendered
	 */
	public ItemStack getCurrentStack() {
		return this.currentStack;
	}

	/**
	 * Returns the equipped slot of the armor piece being rendered
	 */
	public EquipmentSlot getCurrentSlot() {
		return this.currentSlot;
	}

	/**
	 * Gets the id that represents the current animatable's instance for animation purposes
	 * <p>
	 * This is mostly useful for things like items, which have a single registered instance for all objects
	 * <p>
	 * For Strut armor specifically, this creates a paired negative-id to allow for
	 * armor to have different animations to its associated item
	 */
	@Override
	public long getInstanceId(T animatable) {
		long stackId = StrutItem.getId(this.currentStack);

		if (stackId == Long.MAX_VALUE)
			return wearerInstanceId(this.currentEntity, this.currentSlot);

		return -stackId;
	}

	/**
	 * Derives a fallback instance id for a worn stack that hasn't been assigned a real
	 * {@link StrutItem#getId StrutItem id} yet - typically before the server round-trip that assigns one
	 * <p>
	 * Keyed on the wearer's {@link Entity#getUuid()}, not {@link Entity#getId()}: this id addresses
	 * a {@link com.sandymandy.strut.animatable.instance.SingletonAnimatableInstanceCache} shared by
	 * every wearer of this armor item, and Minecraft frees and reuses numeric entity ids once an
	 * entity is removed. Keying on the numeric id meant a despawned wearer's slot could hand its
	 * {@link com.sandymandy.strut.animation.AnimatableManager} - its animation state - to whichever
	 * new entity the server later reused that id for. A UUID is never reused, so this id can't
	 * collide across entities even when their numeric ids do
	 */
	private static long wearerInstanceId(Entity wearer, EquipmentSlot slot) {
		return deriveWearerInstanceId(wearer.getUuid(), slot);
	}

	/**
	 * The pure id-derivation {@link #wearerInstanceId} delegates to, split out so it can be tested
	 * without needing a real, world-bound {@link Entity}
	 */
	static long deriveWearerInstanceId(UUID wearerUuid, EquipmentSlot slot) {
		long mixed = wearerUuid.getMostSignificantBits() ^ Long.rotateLeft(wearerUuid.getLeastSignificantBits(), 32);

		return -(mixed ^ (slot.ordinal() + 1));
	}

	/**
	 * Gets the {@link RenderLayer} to render the given animatable with
	 * <p>
	 * Uses the {@code RenderLayer#armorCutoutNoCull} {@code RenderLayer} by default
	 * <p>
	 * Override this to change the way a model will render (such as translucent models, etc)
	 */
	@Override
	public RenderLayer getRenderType(T animatable, Identifier texture, @Nullable VertexConsumerProvider bufferSource, float partialTick) {
		return RenderLayer.getArmorCutoutNoCull(texture);
	}

	/**
	 * Sets a scale override for this renderer, telling Strut to pre-scale the model
	 */
	public StrutArmorRenderer<T> withScale(float scale) {
		return withScale(scale, scale);
	}

	/**
	 * Sets a scale override for this renderer, telling Strut to pre-scale the model
	 */
	public StrutArmorRenderer<T> withScale(float scaleWidth, float scaleHeight) {
		this.scaleWidth = scaleWidth;
		this.scaleHeight = scaleHeight;

		return this;
	}

	/**
	 * Returns the 'head' bone definition from this model
	 * <p>
	 * Override if your geo model has different bone names for these bones
	 *
	 * @return The bone for the head model piece, or null if not using it
	 */
	@Nullable
	public GroupBoneDefinition getHeadBone(StrutModel<T> model) {
		return model.getBone("armorHead").orElse(null);
	}

	/**
	 * Returns the 'body' bone definition from this model
	 * <p>
	 * Override if your geo model has different bone names for these bones
	 *
	 * @return The bone for the body model piece, or null if not using it
	 */
	@Nullable
	public GroupBoneDefinition getBodyBone(StrutModel<T> model) {
		return model.getBone("armorBody").orElse(null);
	}

	/**
	 * Returns the 'right arm' bone definition from this model
	 * <p>
	 * Override if your geo model has different bone names for these bones
	 *
	 * @return The bone for the right arm model piece, or null if not using it
	 */
	@Nullable
	public GroupBoneDefinition getRightArmBone(StrutModel<T> model) {
		return model.getBone("armorRightArm").orElse(null);
	}

	/**
	 * Returns the 'left arm' bone definition from this model
	 * <p>
	 * Override if your geo model has different bone names for these bones
	 *
	 * @return The bone for the left arm model piece, or null if not using it
	 */
	@Nullable
	public GroupBoneDefinition getLeftArmBone(StrutModel<T> model) {
		return model.getBone("armorLeftArm").orElse(null);
	}

	/**
	 * Returns the 'right leg' bone definition from this model
	 * <p>
	 * Override if your geo model has different bone names for these bones
	 *
	 * @return The bone for the right leg model piece, or null if not using it
	 */
	@Nullable
	public GroupBoneDefinition getRightLegBone(StrutModel<T> model) {
		return model.getBone("armorRightLeg").orElse(null);
	}

	/**
	 * Returns the 'left leg' bone definition from this model
	 * <p>
	 * Override if your geo model has different bone names for these bones
	 *
	 * @return The bone for the left leg model piece, or null if not using it
	 */
	@Nullable
	public GroupBoneDefinition getLeftLegBone(StrutModel<T> model) {
		return model.getBone("armorLeftLeg").orElse(null);
	}

	/**
	 * Returns the 'right boot' bone definition from this model
	 * <p>
	 * Override if your geo model has different bone names for these bones
	 *
	 * @return The bone for the right boot model piece, or null if not using it
	 */
	@Nullable
	public GroupBoneDefinition getRightBootBone(StrutModel<T> model) {
		return model.getBone("armorRightBoot").orElse(null);
	}

	/**
	 * Returns the 'left boot' bone definition from this model
	 * <p>
	 * Override if your geo model has different bone names for these bones
	 *
	 * @return The bone for the left boot model piece, or null if not using it
	 */
	@Nullable
	public GroupBoneDefinition getLeftBootBone(StrutModel<T> model) {
		return model.getBone("armorLeftBoot").orElse(null);
	}

	/**
	 * Resolve a cached {@link GroupBoneDefinition} to the current wearer's own {@link GroupBoneHandle}
	 * <p>
	 * {@code null} in, {@code null} out - every caller below already tolerates a null bone
	 */
	@Nullable
	protected GroupBoneHandle handleFor(@Nullable GroupBoneDefinition definition) {
		if (definition == null || this.lastModel == null)
			return null;

		ModelInstance instance = getModelInstanceState(this.animatable);

		return instance.handleFor(this.lastModel, definition);
	}

	/**
	 * Gets a tint-applying color to render the given animatable with
	 * <p>
	 * Returns {@link Color#WHITE} by default
	 */
	@Override
	public Color getRenderColor(T animatable, float partialTick, int packedLight) {
		return this.currentStack.isIn(ItemTags.DYEABLE) ? Color.ofOpaque(DyedColorComponent.getColor(this.currentStack, -6265536)) : Color.WHITE;
	}

	/**
	 * Called before rendering the model to buffer. Allows for render modifications and preparatory work such as scaling and translating
	 * <p>
	 * {@link MatrixStack} translations made here are kept until the end of the render process
	 */
	@Override
	public void preRender(MatrixStack poseStack, T animatable, BakedStrutModel model, @Nullable VertexConsumerProvider bufferSource,
						  @Nullable VertexConsumer buffer, boolean isReRender, float partialTick, int packedLight,
						  int packedOverlay, int colour) {
		this.entityRenderTranslations = new Matrix4f(poseStack.peek().getPositionMatrix());

		applyBaseModel(this.baseModel);
		grabRelevantBones(model);
		applyBaseTransformations(this.baseModel);
		scaleModelForBaby(poseStack, animatable, partialTick, isReRender);
		scaleModelForRender(this.scaleWidth, this.scaleHeight, poseStack, animatable, model, isReRender, partialTick, packedLight, packedOverlay);

		if (!(this.currentEntity instanceof StrutAnimatable))
			applyBoneVisibilityBySlot(this.currentSlot);
	}

	@Override
	@ApiStatus.Internal
	public void render(MatrixStack poseStack, @Nullable VertexConsumer buffer, int packedLight,
							   int packedOverlay, int colour) {
		if (this.currentEntity == null) {
            StrutConstants.LOGGER.error("A mod is attempting to render Strut armor ({}) without using StrutRenderProvider! " +
										   "IClientItemExtensions is not supported for Strut!", getClass());

			return;
		}

		MinecraftClient mc = MinecraftClient.getInstance();
		VertexConsumerProvider getEntityVertexConsumers =  mc.worldRenderer.bufferBuilders.getEntityVertexConsumers();

		if (mc.worldRenderer.canDrawEntityOutlines() && mc.hasOutline(this.currentEntity))
			bufferSource =  mc.worldRenderer.bufferBuilders.getOutlineVertexConsumers();

		float partialTick = mc.getRenderTickCounter().getTickDelta(true);
		RenderLayer renderType = getRenderType(this.animatable, getTexture(this.animatable), bufferSource, partialTick);
		buffer = ItemRenderer.getArmorGlintConsumer(bufferSource, renderType, this.currentStack.hasGlint());

		defaultRender(poseStack, this.animatable, bufferSource, null, buffer,
				0, partialTick, packedLight);

		this.animatable = null;
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
		poseStack.translate(0, 24 / 16f, 0);
		poseStack.scale(-1, -1, 1);

		if (!isReRender) {
			AnimationState<T> animationState = new AnimationState<>(animatable, 0, 0, partialTick, false);
			long instanceId = getInstanceId(animatable);
			StrutModel<T> currentModel = getStrutModel();

			animationState.setData(DataTickets.TICK, animatable.getTick(this.currentEntity));
			animationState.setData(DataTickets.ITEMSTACK, this.currentStack);
			animationState.setData(DataTickets.ENTITY, this.currentEntity);
			animationState.setData(DataTickets.EQUIPMENT_SLOT, this.currentSlot);
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
		this.baseModel = null;
		this.currentEntity = null;
		this.currentStack = null;
		this.animatable = null;
		this.currentSlot = null;
		this.bufferSource = null;
		this.partialTick = 0;
		this.limbSwing = 0;
		this.limbSwingAmount = 0;
		this.netHeadYaw = 0;
		this.headPitch = 0;
	}

	/**
	 * Being removed now that the injection point has been moved to a safer spot
	 */
	@Deprecated(forRemoval = true)
	public void doArmourPostRenderCleanup() {}

	/**
	 * Refreshes {@code bone}'s tracked matrices from the current pose - see
	 * {@link StrutRenderer#trackBoneMatrices}'s own doc comment for why this no longer needs
	 * {@link MatrixStack} at all, and for the previous "before this bone's own transform" bug it
	 * fixes (armor is one of the three renderer types that had it)
	 */
	@Override
	public void trackBoneMatrices(T animatable, GroupBoneHandle bone) {
		if (!bone.isTrackingMatrices())
			return;

		Matrix4f modelSpace = new Matrix4f();

		PosePalette.computeBoneAtPivot(getModelInstanceState(animatable).renderedPose(), bone.getDefinition(), modelSpace);
		bone.setModelSpaceMatrix(modelSpace);

		Matrix4f delta = RenderUtil.invertAndMultiplyMatrices(this.modelRenderTranslations, this.entityRenderTranslations);

		bone.setLocalSpaceMatrix(new Matrix4f(delta).mul(modelSpace));
	}

	/**
	 * Gets and caches the relevant armor model bones for this baked model if it hasn't been done already
	 */
	protected void grabRelevantBones(BakedStrutModel bakedModel) {
		if (this.lastModel == bakedModel)
			return;

		StrutModel<T> model = getStrutModel();
		this.lastModel = bakedModel;
		this.head = getHeadBone(model);
		this.body = getBodyBone(model);
		this.rightArm = getRightArmBone(model);
		this.leftArm = getLeftArmBone(model);
		this.rightLeg = getRightLegBone(model);
		this.leftLeg = getLeftLegBone(model);
		this.rightBoot = getRightBootBone(model);
		this.leftBoot = getLeftBootBone(model);
	}

	/**
	 * Prepare the renderer for the current render cycle
	 * <p>
	 * Must be called prior to render as the default BipedEntityModel doesn't give render context
	 * <p>
	 * Params have been left nullable so that the renderer can be called for model/texture purposes safely.
	 * If you do grab the renderer using null parameters, you should not use it for actual rendering
	 *
	 * @param entity The entity being rendered with the armor on
	 * @param stack The ItemStack being rendered
	 * @param slot The slot being rendered
	 * @param baseModel The default (vanilla) model that would have been rendered if this model hadn't replaced it
	 * @deprecated Use {@link #prepForRender(Entity, ItemStack, EquipmentSlot, BipedEntityModel, VertexConsumerProvider, float, float, float, float, float)}
	 */
	@Deprecated(forRemoval = true)
	public void prepForRender(@Nullable Entity entity, ItemStack stack, @Nullable EquipmentSlot slot, @Nullable BipedEntityModel<?> baseModel) {
		if (entity == null || slot == null || baseModel == null)
			return;

		final MinecraftClient mc = MinecraftClient.getInstance();

		prepForRender(entity, stack, slot, baseModel, mc.worldRenderer.bufferBuilders.getEntityVertexConsumers(), mc.getRenderTickCounter().getTickDelta(true), 0, 0, 0, 0);
	}

	/**
	 * Prepare the renderer for the current render pass
	 * <p>
	 * Must be called prior to render as the default BipedEntityModel doesn't give render context
	 *
	 * @param entity The entity being rendered with the armor on
	 * @param stack The ItemStack being rendered
	 * @param slot The slot being rendered
	 * @param baseModel The default (vanilla) model that would have been rendered if this model hadn't replaced it
	 * @param bufferSource The buffer supplier for the current render context
	 * @param partialTick The fraction of a tick passed since the last game tick
	 * @param limbSwing The position in the limb swing cycle that the entity is in
	 * @param limbSwingAmount The frame-relative velocity of the entity's limb swing
	 * @param netHeadYaw The entity's Y rotation, discounting any head rotation
	 * @param headPitch The entity's X rotation
	 */
	public void prepForRender(Entity entity, ItemStack stack, EquipmentSlot slot, BipedEntityModel<?> baseModel, VertexConsumerProvider bufferSource,
							  float partialTick, float limbSwing, float limbSwingAmount, float netHeadYaw, float headPitch) {
		this.baseModel = baseModel;
		this.currentEntity = entity;
		this.currentStack = stack;
		this.animatable = (T)stack.getItem();
		this.currentSlot = slot;
		this.bufferSource = bufferSource;
		this.partialTick = partialTick;
		this.limbSwing = limbSwing;
		this.limbSwingAmount = limbSwingAmount;
		this.netHeadYaw = netHeadYaw;
		this.headPitch = headPitch;
	}

	/**
	 * Applies settings and transformations pre-render based on the default model
	 */
	protected void applyBaseModel(BipedEntityModel<?> baseModel) {
		BipedEntityModel<?> self = (BipedEntityModel<?>)this;

		self.child = baseModel.child;
		self.sneaking = baseModel.sneaking;
		self.riding = baseModel.riding;
		self.rightArmPose = baseModel.rightArmPose;
		self.leftArmPose = baseModel.leftArmPose;
		self.head.visible = baseModel.head.visible;
		self.hat.visible = baseModel.hat.visible;
		self.body.visible = baseModel.body.visible;
		self.rightArm.visible = baseModel.rightArm.visible;
		self.leftArm.visible = baseModel.leftArm.visible;
		self.rightLeg.visible = baseModel.rightLeg.visible;
		self.leftLeg.visible = baseModel.leftLeg.visible;
	}

	/**
	 * Resets the bone visibility for the model based on the currently rendering slot,
	 * and then sets bones relevant to the current slot as visible for rendering
	 * <p>
	 * This is only called by default for non-geo entities (I.E. players or vanilla humanoid mobs)
	 */
	protected void applyBoneVisibilityBySlot(EquipmentSlot currentSlot) {
		setAllBonesVisible(false);
		BipedEntityModel<?> model = this;

		switch (currentSlot) {
			case HEAD -> setBoneVisible(this.head, model.head.visible);
			case CHEST -> {
				setBoneVisible(this.body, model.body.visible);
				setBoneVisible(this.rightArm, model.rightArm.visible);
				setBoneVisible(this.leftArm, model.leftArm.visible);
			}
			case LEGS -> {
				setBoneVisible(this.rightLeg, model.rightLeg.visible);
				setBoneVisible(this.leftLeg, model.leftLeg.visible);
			}
			case FEET -> {
				setBoneVisible(this.rightBoot, model.rightLeg.visible);
				setBoneVisible(this.leftBoot, model.leftLeg.visible);
			}
			default -> {}
		}
	}

	/**
	 * Resets the bone visibility for the model based on the current {@link ModelPart} and {@link EquipmentSlot},
	 * and then sets the bones relevant to the current part as visible for rendering
	 * <p>
	 * If you are rendering a geo entity with armor, you should probably be calling this prior to rendering
	 */
	public void applyBoneVisibilityByPart(EquipmentSlot currentSlot, ModelPart currentPart, BipedEntityModel<?> model) {
		setVisible(false);

		currentPart.visible = true;
		GroupBoneDefinition bone = null;

		if (currentPart == model.hat || currentPart == model.head) {
			bone = this.head;
		}
		else if (currentPart == model.body) {
			bone = this.body;
		}
		else if (currentPart == model.leftArm) {
			bone = this.leftArm;
		}
		else if (currentPart == model.rightArm) {
			bone = this.rightArm;
		}
		else if (currentPart == model.leftLeg) {
			bone = currentSlot == EquipmentSlot.FEET ? this.leftBoot : this.leftLeg;
		}
		else if (currentPart == model.rightLeg) {
			bone = currentSlot == EquipmentSlot.FEET ? this.rightBoot : this.rightLeg;
		}

		GroupBoneHandle handle = handleFor(bone);

		if (handle != null)
			handle.setHidden(false);
	}

	/**
	 * Transform the currently rendering {@link StrutModel} to match the positions and rotations of the base model
	 */
	protected void applyBaseTransformations(BipedEntityModel<?> baseModel) {
		GroupBoneHandle head = handleFor(this.head);

		if (head != null) {
			ModelPart headPart = baseModel.head;

			RenderUtil.matchModelPartRot(headPart, head);
			head.updatePosition(headPart.pivotX, -headPart.pivotY, headPart.pivotZ);
		}

		GroupBoneHandle body = handleFor(this.body);

		if (body != null) {
			ModelPart bodyPart = baseModel.body;

			RenderUtil.matchModelPartRot(bodyPart, body);
			body.updatePosition(bodyPart.pivotX, -bodyPart.pivotY, bodyPart.pivotZ);
		}

		GroupBoneHandle rightArm = handleFor(this.rightArm);

		if (rightArm != null) {
			ModelPart rightArmPart = baseModel.rightArm;

			RenderUtil.matchModelPartRot(rightArmPart, rightArm);
			rightArm.updatePosition(rightArmPart.pivotX + 5, 2 - rightArmPart.pivotY, rightArmPart.pivotZ);
		}

		GroupBoneHandle leftArm = handleFor(this.leftArm);

		if (leftArm != null) {
			ModelPart leftArmPart = baseModel.leftArm;

			RenderUtil.matchModelPartRot(leftArmPart, leftArm);
			leftArm.updatePosition(leftArmPart.pivotX - 5f, 2f - leftArmPart.pivotY, leftArmPart.pivotZ);
		}

		GroupBoneHandle rightLeg = handleFor(this.rightLeg);

		if (rightLeg != null) {
			ModelPart rightLegPart = baseModel.rightLeg;

			RenderUtil.matchModelPartRot(rightLegPart, rightLeg);
			rightLeg.updatePosition(rightLegPart.pivotX + 2, 12 - rightLegPart.pivotY, rightLegPart.pivotZ);

			GroupBoneHandle rightBoot = handleFor(this.rightBoot);

			if (rightBoot != null) {
				RenderUtil.matchModelPartRot(rightLegPart, rightBoot);
				rightBoot.updatePosition(rightLegPart.pivotX + 2, 12 - rightLegPart.pivotY, rightLegPart.pivotZ);
			}
		}

		GroupBoneHandle leftLeg = handleFor(this.leftLeg);

		if (leftLeg != null) {
			ModelPart leftLegPart = baseModel.leftLeg;

			RenderUtil.matchModelPartRot(leftLegPart, leftLeg);
			leftLeg.updatePosition(leftLegPart.pivotX - 2, 12 - leftLegPart.pivotY, leftLegPart.pivotZ);

			GroupBoneHandle leftBoot = handleFor(this.leftBoot);

			if (leftBoot != null) {
				RenderUtil.matchModelPartRot(leftLegPart, leftBoot);
				leftBoot.updatePosition(leftLegPart.pivotX - 2, 12 - leftLegPart.pivotY, leftLegPart.pivotZ);
			}
		}
	}

	@Override
	public void setVisible(boolean visible) {
		super.setVisible(visible);
		setAllBonesVisible(visible);
	}

	/**
	 * Equivalent to {@code BipedEntityModel#setAllVisible(boolean)}, but explicitly only for this model's bones.
	 * <p>
	 * This allows for resetting of model visibility whilst still allowing inheritance of visibility from the {@link ArmorFeatureRenderer}
	 */
	protected void setAllBonesVisible(boolean visible) {
		setBoneVisible(this.head, visible);
		setBoneVisible(this.body, visible);
		setBoneVisible(this.rightArm, visible);
		setBoneVisible(this.leftArm, visible);
		setBoneVisible(this.rightLeg, visible);
		setBoneVisible(this.leftLeg, visible);
		setBoneVisible(this.rightBoot, visible);
		setBoneVisible(this.leftBoot, visible);
	}

	/**
	 * Apply custom scaling to account for {@code AgeableListModel} baby models
	 */
	public void scaleModelForBaby(MatrixStack poseStack, T animatable, float partialTick, boolean isReRender) {
		if (!this.child || isReRender)
			return;

		if (this.currentSlot == EquipmentSlot.HEAD) {
			if (this.baseModel.headScaled) {
				float headScale = 1.5f / this.baseModel.invertedChildHeadScale;

				poseStack.scale(headScale, headScale, headScale);
			}

			poseStack.translate(0, this.baseModel.childHeadYOffset / 16f, this.baseModel.childHeadZOffset / 16f);
		}
		else {
			float bodyScale = 1 / this.baseModel.invertedChildBodyScale;

			poseStack.scale(bodyScale, bodyScale, bodyScale);
			poseStack.translate(0, this.baseModel.childBodyYOffset / 16f, 0);
		}
	}

	/**
	 * Sets a bone as visible or hidden, with nullability
	 */
	protected void setBoneVisible(@Nullable GroupBoneDefinition bone, boolean visible) {
		GroupBoneHandle handle = handleFor(bone);

		if (handle == null)
			return;

		handle.setHidden(!visible);
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
		if (this.currentEntity != null)
			AnimatableTexture.setAndUpdate(getTexture(animatable));
	}

	/**
	 * Create and fire the relevant {@code Pre-Render} event hook for this renderer
	 *
	 * @return Whether the renderer should proceed based on the cancellation state of the event
	 */
	@Override
	public boolean firePreRenderEvent(MatrixStack poseStack, BakedStrutModel model, VertexConsumerProvider bufferSource, float partialTick, int packedLight) {
		return StrutServices.Client.EVENTS.fireArmorPreRender(this, poseStack, model, bufferSource, partialTick, packedLight);
	}

	/**
	 * Create and fire the relevant {@code Post-Render} event hook for this renderer
	 */
	@Override
	public void firePostRenderEvent(MatrixStack poseStack, BakedStrutModel model, VertexConsumerProvider bufferSource, float partialTick, int packedLight) {
		StrutServices.Client.EVENTS.fireArmorPostRender(this, poseStack, model, bufferSource, partialTick, packedLight);
	}
}