package com.sandymandy.strut.renderer;

import com.sandymandy.strut.armour.ArmourClient;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.EntityPose;
import net.minecraft.world.LightType;
import net.minecraft.util.math.Vec3d;
import net.minecraft.scoreboard.AbstractTeam;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import com.sandymandy.strut.StrutServices;
import com.sandymandy.strut.animatable.StrutAnimatable;
import com.sandymandy.strut.animation.AnimationState;
import com.sandymandy.strut.animation.state.PosePalette;
import com.sandymandy.strut.cache.object.BakedStrutModel;
import com.sandymandy.strut.cache.object.GroupBoneHandle;
import com.sandymandy.strut.cache.texture.AnimatableTexture;
import com.sandymandy.strut.constant.DataTickets;
import com.sandymandy.strut.model.StrutModel;
import com.sandymandy.strut.model.data.EntityModelData;
import com.sandymandy.strut.util.ClientUtil;
import com.sandymandy.strut.util.RenderUtil;

/**
 * An alternate to {@link StrutEntityRenderer}, used specifically for replacing existing non-strut
 * entities with strut rendering dynamically, without the need for an additional entity class
 */
public class StrutReplacedEntityRenderer<E extends Entity, T extends StrutAnimatable> extends EntityRenderer<E> implements StrutRenderer<T> {
	protected final StrutModel<T> model;
	protected final T animatable;

	protected E currentEntity;
	protected float scaleWidth = 1;
	protected float scaleHeight = 1;

	protected Matrix4f entityRenderTranslations = new Matrix4f();
	protected Matrix4f modelRenderTranslations = new Matrix4f();

	public StrutReplacedEntityRenderer(EntityRendererFactory.Context renderManager, StrutModel<T> model, T animatable) {
		super(renderManager);

		this.model = model;
		this.animatable = animatable;
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
	 *
	 * @see StrutReplacedEntityRenderer#getCurrentEntity()
	 */
	@Override
	public T getAnimatable() {
		return this.animatable;
	}

	/**
	 * Returns the current entity having its rendering replaced by this renderer
	 * @see StrutReplacedEntityRenderer#getAnimatable()
	 */
	public E getCurrentEntity() {
		return this.currentEntity;
	}

	/**
	 * Gets the id that represents the current animatable's instance for animation purposes
	 * <p>
	 * This is mostly useful for things like items, which have a single registered instance for all objects
	 */
	@Override
	public Entity attachmentOwner(T animatable) {
		return this.currentEntity;
	}

	@Override
	public long getInstanceId(T animatable) {
		// a singleton keeps a state for each id, so a screen's draw of the entity has a state of its own (see RenderPass#instanceId)
		return RenderPass.instanceId(this.currentEntity.getId());
	}

	/**
	 * Shadowing override of {@link EntityRenderer#getTexture}
	 * <p>
	 * This redirects the call to {@link StrutRenderer#getTexture}
	 */
	@Override
	public Identifier getTexture(E entity) {
		return StrutRenderer.super.getTexture(this.animatable);
	}

	/**
	 * Sets a scale override for this renderer, telling Strut to pre-scale the model
	 */
	public StrutReplacedEntityRenderer<E, T> withScale(float scale) {
		return withScale(scale, scale);
	}

	/**
	 * Sets a scale override for this renderer, telling Strut to pre-scale the model
	 */
	public StrutReplacedEntityRenderer<E, T> withScale(float scaleWidth, float scaleHeight) {
		this.scaleWidth = scaleWidth;
		this.scaleHeight = scaleHeight;

		return this;
	}

	/**
	 * Gets the {@link RenderLayer} to render the given animatable with
	 * <p>
	 * Uses the {@code RenderLayer#entityCutoutNoCull} {@code RenderLayer} by default
	 * <p>
	 * Override this to change the way a model will render (such as translucent models, etc).
	 *
	 * @return Return the RenderLayer to use, or null to prevent the model rendering. Returning null will not prevent animation functions taking place
	 */
	@Nullable
	@Override
	public RenderLayer getRenderType(T animatable, Identifier texture, @Nullable VertexConsumerProvider bufferSource, float partialTick) {
		final boolean invisible = this.currentEntity != null && this.currentEntity.isInvisible();

		if (invisible && !this.currentEntity.isInvisibleTo(ClientUtil.getClientPlayer()))
			return RenderLayer.getItemEntityTranslucentCull(texture);

		if (!invisible)
			return StrutRenderer.super.getRenderType(animatable, texture, bufferSource, partialTick);

		return this.currentEntity != null && MinecraftClient.getInstance().hasOutline(this.currentEntity) ? RenderLayer.getOutline(texture) : null;
	}

	/**
	 * Called before rendering the model to buffer. Allows for render modifications and preparatory work such as scaling and translating
	 * <p>
	 * {@link MatrixStack} translations made here are kept until the end of the render process
	 */
	@Override
	public void preRender(MatrixStack poseStack, T animatable, BakedStrutModel model, @Nullable VertexConsumerProvider bufferSource, @Nullable VertexConsumer buffer, boolean isReRender, float partialTick, int packedLight, int packedOverlay, int colour) {
		this.entityRenderTranslations = new Matrix4f(poseStack.peek().getPositionMatrix());

		scaleModelForRender(this.scaleWidth, this.scaleHeight, poseStack, animatable, model, isReRender, partialTick, packedLight, packedOverlay);

		// Resolves this frame's effectivePose()
		// once, ahead of the per-bone walk or GPU submission that reads it - see
		// ModelInstance#prepareForRender's own doc comment. currentEntity, not animatable, matching
		// getInstanceId's own reasoning just below
		// what the wearer has on (worn models, the bones they cover), before the pose that the covers hide bones of is resolved
		if (!isReRender && RenderPass.worldPass())
			ArmourClient.update(this.currentEntity);

		getModelInstanceState(animatable).prepareForRender(this.currentEntity.getId(), RenderPass.worldPass());
	}

	@Override
	@ApiStatus.Internal
	public void render(E entity, float entityYaw, float partialTick, MatrixStack poseStack, VertexConsumerProvider bufferSource, int packedLight) {
		this.currentEntity = entity;

		defaultRender(poseStack, this.animatable, bufferSource, null, null, entityYaw, partialTick, packedLight);
	}

	/**
	 * The actual render method that subtype renderers should override to handle their specific rendering tasks
	 * <p>
	 * {@link StrutRenderer#preRender} has already been called by this stage, and {@link StrutRenderer#postRender} will be called directly after
	 */
	@Override
	public void actuallyRender(MatrixStack poseStack, T animatable, BakedStrutModel model, @Nullable RenderLayer renderType, VertexConsumerProvider bufferSource,
							   @Nullable VertexConsumer buffer, boolean isReRender, float partialTick, int packedLight, int packedOverlay, int colour) {
		poseStack.push();

		LivingEntity livingEntity = this.currentEntity instanceof LivingEntity entity ? entity : null;

		if (this.currentEntity instanceof MobEntity mob && !isReRender) {
			Entity leashHolder = mob.getLeashHolder();

			if (leashHolder != null)
				renderLeash(mob, partialTick, poseStack, bufferSource, leashHolder);
		}

		boolean shouldSit = this.currentEntity.hasVehicle() && (this.currentEntity.getVehicle() != null);
		float lerpBodyRot = livingEntity == null ? 0 : MathHelper.lerpAngleDegrees(partialTick, livingEntity.prevBodyYaw, livingEntity.bodyYaw);
		float lerpHeadRot = livingEntity == null ? 0 : MathHelper.lerpAngleDegrees(partialTick, livingEntity.prevHeadYaw, livingEntity.headYaw);
		float netHeadYaw = lerpHeadRot - lerpBodyRot;

		if (shouldSit && this.currentEntity.getVehicle() instanceof LivingEntity livingentity) {
			lerpBodyRot = MathHelper.lerpAngleDegrees(partialTick, livingentity.prevBodyYaw, livingentity.bodyYaw);
			netHeadYaw = lerpHeadRot - lerpBodyRot;
			float clampedHeadYaw = MathHelper.clamp(MathHelper.wrapDegrees(netHeadYaw), -85, 85);
			lerpBodyRot = lerpHeadRot - clampedHeadYaw;

			if (clampedHeadYaw * clampedHeadYaw > 2500f)
				lerpBodyRot += clampedHeadYaw * 0.2f;

			netHeadYaw = lerpHeadRot - lerpBodyRot;
		}

		if (this.currentEntity.getPose() == EntityPose.SLEEPING && livingEntity != null) {
			Direction bedDirection = livingEntity.getSleepingDirection();

			if (bedDirection != null) {
				float eyePosOffset = livingEntity.getEyeHeight(EntityPose.STANDING) - 0.1F;

				poseStack.translate(-bedDirection.getOffsetX() * eyePosOffset, 0, -bedDirection.getOffsetZ() * eyePosOffset);
			}
		}

		float nativeScale = livingEntity != null ? livingEntity.getScale() : 1;
		float ageInTicks = this.currentEntity.age + partialTick;
		float limbSwingAmount = 0;
		float limbSwing = 0;

		poseStack.scale(nativeScale, nativeScale, nativeScale);
		applyRotations(animatable, poseStack, ageInTicks, lerpBodyRot, partialTick, nativeScale);

		if (!shouldSit && this.currentEntity.isAlive() && livingEntity != null) {
			limbSwingAmount = livingEntity.limbAnimator.getSpeed(partialTick);
			limbSwing = livingEntity.limbAnimator.getPos(partialTick);

			if (livingEntity.isBaby())
				limbSwing *= 3f;

			if (limbSwingAmount > 1f)
				limbSwingAmount = 1f;
		}

		float headPitch = MathHelper.lerp(partialTick, this.currentEntity.prevPitch, this.currentEntity.getPitch());
		float motionThreshold = getMotionAnimThreshold(animatable);
		boolean isMoving;

		if (livingEntity != null) {
			Vec3d velocity = livingEntity.getVelocity();
			float avgVelocity = (float)(Math.abs(velocity.x) + Math.abs(velocity.z)) / 2f;

			isMoving = avgVelocity >= motionThreshold && limbSwingAmount != 0;
		}
		else {
			isMoving = (limbSwingAmount <= -motionThreshold || limbSwingAmount >= motionThreshold);
		}

		if (!isReRender) {
			AnimationState<T> animationState = new AnimationState<T>(animatable, limbSwing, limbSwingAmount, partialTick, isMoving);
			long instanceId = getInstanceId(animatable);
			StrutModel<T> currentModel = getStrutModel();

			animationState.setData(DataTickets.TICK, animatable.getTick(this.currentEntity));
			animationState.setData(DataTickets.ENTITY, this.currentEntity);
			animationState.setOwner(this.currentEntity);
			animationState.setData(DataTickets.HAS_CLIP, clip -> {
				try {
					return currentModel.getAnimation(animatable, clip) != null;
				}
				catch (RuntimeException missingFile) {
					return false;
				}
			});
			animationState.setData(DataTickets.ENTITY_MODEL_DATA, new EntityModelData(shouldSit, livingEntity != null && livingEntity.isBaby(), -netHeadYaw, -headPitch));
			addStateData(animatable, animationState, partialTick);
			currentModel.addAdditionalStateData(animatable, instanceId, animationState::setData);
			currentModel.handleAnimations(animatable, instanceId, animationState, partialTick);
		}

		poseStack.translate(0, 0.01f, 0);

		this.modelRenderTranslations = new Matrix4f(poseStack.peek().getPositionMatrix());

		if (buffer != null)
			StrutRenderer.super.actuallyRender(poseStack, animatable, model, renderType, bufferSource, buffer, isReRender, partialTick, packedLight, packedOverlay, colour);
		else if (!isReRender)
			trackAllBones(animatable, model);

		// Attachments follow the model, so they are drawn in the same rotated, scaled space as its geometry, not after this method has popped it
		if (!isReRender)
			renderAttachments(poseStack, animatable, model, bufferSource, packedLight, packedOverlay);

		poseStack.pop();
	}

	/**
	 * Puts data on the animation state of the entity being drawn, before the model adds its own and the controllers run. A subclass sets what its controllers read
	 */
	protected void addStateData(T animatable, AnimationState<T> state, float partialTick) {}

	/**
	 * Call after all other rendering work has taken place, including reverting the {@link MatrixStack}'s state
	 * <p>
	 * This method is <u>not</u> called in {@code re-render}
	 */
	@Override
	public boolean attachmentsDrawnInsideModelSpace() {
		return true;
	}

	@Override
	public void renderFinal(MatrixStack poseStack, T animatable, BakedStrutModel model, VertexConsumerProvider bufferSource, @Nullable VertexConsumer buffer, float partialTick, int packedLight, int packedOverlay, int colour) {
		super.render(this.currentEntity, 0, partialTick, poseStack, bufferSource, packedLight);

		if (this.currentEntity instanceof MobEntity mob) {
			Entity leashHolder = mob.getLeashHolder();

			if (leashHolder != null)
				renderLeash(mob, partialTick, poseStack, bufferSource, leashHolder);
		}
	}

	/**
	 * Called after rendering the model to buffer. Post-render modifications should be performed here
	 * <p>
	 * {@link MatrixStack} transformations will be unused and lost once this method ends
	 */
	@Override
	public void postRender(MatrixStack poseStack, T animatable, BakedStrutModel model, VertexConsumerProvider bufferSource, VertexConsumer buffer, boolean isReRender, float partialTick, int packedLight, int packedOverlay, int colour) {
		if (!isReRender)
			super.render(this.currentEntity, 0, partialTick, poseStack, bufferSource, packedLight);
	}

	/**
	 * Called after all render operations are completed and the render pass is considered functionally complete.
	 * <p>
	 * Use this method to clean up any leftover persistent objects stored during rendering or any other post-render maintenance tasks as required
	 */
	@Override
	public void doPostRenderCleanup() {
		this.currentEntity = null;
	}

	/**
	 * Refreshes {@code bone}'s tracked matrices from the current pose - see
	 * {@link StrutRenderer#trackBoneMatrices}'s own doc comment for why this no longer needs
	 * {@link MatrixStack} at all. Reads {@link #currentEntity} rather than the {@code animatable}
	 * parameter for position/offset, matching the replaced (non-Strut) entity this renderer stands
	 * in for, not the {@code StrutAnimatable} that supplies the model
	 */
	@Override
	public void trackBoneMatrices(T animatable, GroupBoneHandle bone) {
		// not for an entity drawn into a screen: turned toward the mouse and scaled to fit, its bones are not where they are in the world
		if (!bone.isTrackingMatrices() || !RenderPass.worldPass())
			return;

		Matrix4f modelSpace = new Matrix4f();

		PosePalette.computeBoneAtPivot(getModelInstanceState(animatable).renderedPose(), bone.getDefinition(), modelSpace);
		bone.setModelSpaceMatrix(modelSpace);

		Matrix4f delta = RenderUtil.invertAndMultiplyMatrices(this.modelRenderTranslations, this.entityRenderTranslations);
		Matrix4f localBase = new Matrix4f(delta).mul(modelSpace);

		Matrix4f localSpace = new Matrix4f(localBase);

		localSpace.translate(getPositionOffset(this.currentEntity, 1).toVector3f());
		bone.setLocalSpaceMatrix(localSpace);

		Matrix4f worldSpace = new Matrix4f(localBase);

		worldSpace.translate(this.currentEntity.getPos().toVector3f());
		bone.setWorldSpaceMatrix(worldSpace);
	}

	/**
	 * Applies rotation transformations to the renderer prior to render time to account for various entity states
	 * @deprecated Use {@link #applyRotations(StrutAnimatable, MatrixStack, float, float, float, float)}
	 */
	@Deprecated(forRemoval = true)
	protected void applyRotations(T animatable, MatrixStack poseStack, float ageInTicks, float rotationYaw,
								  float partialTick) {
		applyRotations(animatable, poseStack, ageInTicks, rotationYaw, partialTick, 1);
	}

	/**
	 * Applies rotation transformations to the renderer prior to render time to account for various entity states
	 */
	protected void applyRotations(T animatable, MatrixStack poseStack, float ageInTicks, float rotationYaw,
								  float partialTick, float nativeScale) {
		if (isShaking(animatable))
			rotationYaw += (float)(Math.cos(this.currentEntity.age * 3.25d) * Math.PI * 0.4d);

		if (!this.currentEntity.isInPose(EntityPose.SLEEPING))
			poseStack.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180f - rotationYaw));

		if (this.currentEntity instanceof LivingEntity livingEntity) {
			if (livingEntity.deathTime > 0) {
				float deathRotation = (livingEntity.deathTime + partialTick - 1f) / 20f * 1.6f;

				poseStack.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(Math.min(MathHelper.sqrt(deathRotation), 1) * getDeathMaxRotation(animatable)));
			}
			else if (livingEntity.isUsingRiptide()) {
				poseStack.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-90f - livingEntity.getPitch()));
				poseStack.multiply(RotationAxis.POSITIVE_Y.rotationDegrees((livingEntity.age + partialTick) * -75f));
			}
			else if (livingEntity.isInPose(EntityPose.SLEEPING)) {
				Direction bedOrientation = livingEntity.getSleepingDirection();

				poseStack.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(bedOrientation != null ? RenderUtil.getDirectionAngle(bedOrientation) : rotationYaw));
				poseStack.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(getDeathMaxRotation(animatable)));
				poseStack.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(270f));
			}
			else if (LivingEntityRenderer.shouldFlipUpsideDown(livingEntity)) {
				poseStack.translate(0, (livingEntity.getHeight() + 0.1f) / nativeScale, 0);
				poseStack.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(180f));
			}
		}
	}

	/**
	 * Gets the max rotation value for dying entities
	 * <p>
	 * You might want to modify this for different aesthetics, such as a {@code Spider} flipping upside down on death
	 * <p>
	 * Functionally equivalent to {@code LivingEntityRenderer#getFlipDegrees}
	 */
	protected float getDeathMaxRotation(T animatable) {
		return 90f;
	}

	/**
	 * Get the maximum distance (in blocks) that an entity's nameplate should be visible
	 * <p>
	 * This is only a short-circuit predicate, and other conditions after this check must be also passed in order for the name to render
	 */
	public double getNameRenderCutoffDistance(E entity, T animatable) {
		return entity.isSneaky() ? 32d : 64d;
	}

	/**
	 * Whether the entity's nametag should be rendered or not
	 * <p>
	 * Pretty much exclusively used in {@code EntityRenderer#renderNameTag}
	 */
	@Override
	public boolean hasLabel(E entity) {
		if (!(entity instanceof LivingEntity))
			return super.hasLabel(entity);

		double nameRenderCutoff = getNameRenderCutoffDistance(entity, this.animatable);

		if (this.dispatcher.getSquaredDistanceToCamera(entity) >= nameRenderCutoff * nameRenderCutoff)
			return false;

		if (entity instanceof MobEntity && (!entity.shouldRenderName() && (!entity.hasCustomName() || entity != this.dispatcher.targetedEntity)))
			return false;

		final MinecraftClient minecraft = MinecraftClient.getInstance();
		boolean visibleToClient = !entity.isInvisibleTo(minecraft.player);
		AbstractTeam entityTeam = entity.getScoreboardTeam();

		if (entityTeam == null)
			return MinecraftClient.isHudEnabled() && entity != minecraft.getCameraEntity() && visibleToClient && !entity.hasPassengers();

		AbstractTeam playerTeam = minecraft.player.getScoreboardTeam();

		return switch (entityTeam.getNameTagVisibilityRule()) {
			case ALWAYS -> visibleToClient;
			case NEVER -> false;
			case HIDE_FOR_OTHER_TEAMS -> playerTeam == null ? visibleToClient : entityTeam.isEqual(playerTeam) && (entityTeam.shouldShowFriendlyInvisibles() || visibleToClient);
			case HIDE_FOR_OWN_TEAM -> playerTeam == null ? visibleToClient : !entityTeam.isEqual(playerTeam) && visibleToClient;
		};
	}

	/**
	 * Gets a packed overlay coordinate pair for rendering
	 * <p>
	 * Mostly just used for the red tint when an entity is hurt,
	 * but can be used for other things like the {@code Creeper}
	 * white tint when exploding.
	 */
	@Override
	public int getPackedOverlay(T animatable, float u, float partialTick) {
		if (!(this.currentEntity instanceof LivingEntity entity))
			return OverlayTexture.DEFAULT_UV;

		return OverlayTexture.packUv(OverlayTexture.getU(u),
				OverlayTexture.getV(entity.hurtTime > 0 || entity.deathTime > 0));
	}

	/**
	 * Whether the entity is currently shaking. This is usually used for freezing, but also for things like piglin conversion or striders suffocating
	 * <p>
	 * This is used for a shaking effect while rendering
	 *
	 * @see net.minecraft.client.render.entity.LivingEntityRenderer#isShaking(LivingEntity)
	 */
	public boolean isShaking(T animatable) {
		return this.currentEntity.isFrozen();
	}

	/**
	 * Static rendering code for rendering a leash segment
	 * <p>
	 * It's a like-for-like from {@link EntityRenderer#renderLeash} that had to be duplicated here for flexible usage
	 */
	public <H extends Entity, M extends MobEntity> void renderLeash(M mob, float partialTick, MatrixStack poseStack,
															  VertexConsumerProvider bufferSource, H leashHolder) {
		double lerpBodyAngle = (MathHelper.lerp(partialTick, mob.prevBodyYaw, mob.bodyYaw) * MathHelper.RADIANS_PER_DEGREE) + MathHelper.HALF_PI;
		Vec3d leashOffset = mob.getLeashOffset(partialTick);
		double xAngleOffset = Math.cos(lerpBodyAngle) * leashOffset.z + Math.sin(lerpBodyAngle) * leashOffset.x;
		double zAngleOffset = Math.sin(lerpBodyAngle) * leashOffset.z - Math.cos(lerpBodyAngle) * leashOffset.x;
		double lerpOriginX = MathHelper.lerp(partialTick, mob.prevX, mob.getX()) + xAngleOffset;
		double lerpOriginY = MathHelper.lerp(partialTick, mob.prevY, mob.getY()) + leashOffset.y;
		double lerpOriginZ = MathHelper.lerp(partialTick, mob.prevZ, mob.getZ()) + zAngleOffset;
		Vec3d ropeGripPosition = leashHolder.getLeashPos(partialTick);
		float xDif = (float)(ropeGripPosition.x - lerpOriginX);
		float yDif = (float)(ropeGripPosition.y - lerpOriginY);
		float zDif = (float)(ropeGripPosition.z - lerpOriginZ);
		float offsetMod = MathHelper.inverseSqrt(xDif * xDif + zDif * zDif) * 0.025f / 2f;
		float xOffset = zDif * offsetMod;
		float zOffset = xDif * offsetMod;
		VertexConsumer vertexConsumer = bufferSource.getBuffer(RenderLayer.getLeash());
		BlockPos entityEyePos = BlockPos.ofFloored(mob.getCameraPosVec(partialTick));
		BlockPos holderEyePos = BlockPos.ofFloored(leashHolder.getCameraPosVec(partialTick));
		int entityBlockLight = getBlockLight((E)mob, entityEyePos);
		int holderBlockLight = leashHolder.isOnFire() ? 15 : leashHolder.getWorld().getLightLevel(LightType.BLOCK, holderEyePos);
		int entitySkyLight = mob.getWorld().getLightLevel(LightType.SKY, entityEyePos);
		int holderSkyLight = mob.getWorld().getLightLevel(LightType.SKY, holderEyePos);

		poseStack.push();
		poseStack.translate(xAngleOffset, leashOffset.y, zAngleOffset);

		Matrix4f posMatrix = new Matrix4f(poseStack.peek().getPositionMatrix());

		for (int segment = 0; segment <= 24; ++segment) {
			renderLeashPiece(vertexConsumer, posMatrix, xDif, yDif, zDif, entityBlockLight, holderBlockLight,
					entitySkyLight, holderSkyLight, 0.025f, 0.025f, xOffset, zOffset, segment, false);
		}

		for (int segment = 24; segment >= 0; --segment) {
			renderLeashPiece(vertexConsumer, posMatrix, xDif, yDif, zDif, entityBlockLight, holderBlockLight,
					entitySkyLight, holderSkyLight, 0.025f, 0.0f, xOffset, zOffset, segment, true);
		}

		poseStack.pop();
	}

	/**
	 * Static rendering code for rendering a leash segment
	 * <p>
	 * It's a like-for-like from {@link EntityRenderer#renderLeash} that had to be duplicated here for flexible usage
	 */
	private static void renderLeashPiece(VertexConsumer buffer, Matrix4f positionMatrix, float xDif, float yDif,
										 float zDif, int entityBlockLight, int holderBlockLight, int entitySkyLight,
										 int holderSkyLight, float width, float yOffset, float xOffset, float zOffset, int segment, boolean isLeashKnot) {
		float piecePosPercent = segment / 24f;
		int lerpBlockLight = (int)MathHelper.lerp(piecePosPercent, entityBlockLight, holderBlockLight);
		int lerpSkyLight = (int)MathHelper.lerp(piecePosPercent, entitySkyLight, holderSkyLight);
		int packedLight = LightmapTextureManager.pack(lerpBlockLight, lerpSkyLight);
		float knotColourMod = segment % 2 == (isLeashKnot ? 1 : 0) ? 0.7f : 1f;
		float red = 0.5f * knotColourMod;
		float green = 0.4f * knotColourMod;
		float blue = 0.3f * knotColourMod;
		float x = xDif * piecePosPercent;
		float y = yDif > 0.0f ? yDif * piecePosPercent * piecePosPercent : yDif - yDif * (1.0f - piecePosPercent) * (1.0f - piecePosPercent);
		float z = zDif * piecePosPercent;

		buffer.vertex(positionMatrix, x - xOffset, y + yOffset, z + zOffset).color(red, green, blue, 1).light(packedLight);
		buffer.vertex(positionMatrix, x + xOffset, y + width - yOffset, z - zOffset).color(red, green, blue, 1).light(packedLight);
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
	 * <p>
	 * @return Whether the renderer should proceed based on the cancellation state of the event
	 */
	@Override
	public boolean firePreRenderEvent(MatrixStack poseStack, BakedStrutModel model, VertexConsumerProvider bufferSource, float partialTick, int packedLight) {
		return StrutServices.Client.EVENTS.fireReplacedEntityPreRender(this, poseStack, model, bufferSource, partialTick, packedLight);
	}

	/**
	 * Create and fire the relevant {@code Post-Render} event hook for this renderer
	 */
	@Override
	public void firePostRenderEvent(MatrixStack poseStack, BakedStrutModel model, VertexConsumerProvider bufferSource, float partialTick, int packedLight) {
		StrutServices.Client.EVENTS.fireReplacedEntityPostRender(this, poseStack, model, bufferSource, partialTick, packedLight);
	}
}
