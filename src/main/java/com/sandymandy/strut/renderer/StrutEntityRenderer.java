package com.sandymandy.strut.renderer;

import com.sandymandy.strut.armour.ArmourClient;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
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
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
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
import com.sandymandy.strut.model.DefaultedEntityStrutModel;
import com.sandymandy.strut.model.StrutModel;
import com.sandymandy.strut.model.data.EntityModelData;
import com.sandymandy.strut.util.ClientUtil;
import com.sandymandy.strut.util.Color;
import com.sandymandy.strut.util.RenderUtil;

/**
 * Base {@link StrutRenderer} class for rendering {@link Entity Entities} specifically
 * <p>
 * All entities added to be rendered by Strut should use an instance of this class
 * <p>
 * This also includes {@code Projectiles}
 */
public class StrutEntityRenderer<T extends Entity & StrutAnimatable> extends EntityRenderer<T> implements StrutRenderer<T> {
	protected final StrutModel<T> model;

	protected T animatable;
	protected float scaleWidth = 1;
	protected float scaleHeight = 1;

	protected Matrix4f entityRenderTranslations = new Matrix4f();
	protected Matrix4f modelRenderTranslations = new Matrix4f();

    /**
     * Creates a new defaulted renderer instance, using the entity's registered id as the file name for its assets
     */
    public StrutEntityRenderer(EntityRendererFactory.Context context, EntityType<? extends T> entityType) {
        this(context, new DefaultedEntityStrutModel<>(Registries.ENTITY_TYPE.getId(entityType)));
    }

	public StrutEntityRenderer(EntityRendererFactory.Context renderManager, StrutModel<T> model) {
		super(renderManager);

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
	/** When the world last drew each entity, by id: how a screen's draw of an entity knows the world is drawing it too */
	private final Int2LongOpenHashMap lastWorldDraw = new Int2LongOpenHashMap();

	/** An entity the world drew within this long is one the world is drawing */
	private static final long WORLD_DRAW_RECENT_NANOS = 1_000_000_000L;

	@Override
	public long getInstanceId(T animatable) {
		return animatable.getId();
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
	public StrutEntityRenderer<T> withScale(float scale) {
		return withScale(scale, scale);
	}

	/**
	 * Sets a scale override for this renderer, telling Strut to pre-scale the model
	 */
	public StrutEntityRenderer<T> withScale(float scaleWidth, float scaleHeight) {
		this.scaleWidth = scaleWidth;
		this.scaleHeight = scaleHeight;

		return this;
	}

	/**
	 * Gets a tint-applying color to render the given animatable with
	 * <p>
	 * Returns {@link Color#WHITE} by default, modified for invisibility in spectator
	 */
	@Override
	public Color getRenderColor(T animatable, float partialTick, int packedLight) {
		Color color = StrutRenderer.super.getRenderColor(animatable, partialTick, packedLight);

		if (animatable.isInvisible() && !animatable.isInvisibleTo(ClientUtil.getClientPlayer()))
			color = Color.ofARGB(MathHelper.ceil(color.getAlpha() * 38 / 255f), color.getRed(), color.getGreen(), color.getBlue());

		return color;
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
		final boolean invisible = animatable.isInvisible();

		if (invisible && !animatable.isInvisibleTo(ClientUtil.getClientPlayer()))
			return RenderLayer.getItemEntityTranslucentCull(texture);

		if (!invisible)
			return StrutRenderer.super.getRenderType(animatable, texture, bufferSource, partialTick);

		return MinecraftClient.getInstance().hasOutline(animatable) ? RenderLayer.getOutline(texture) : null;
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
		// ModelInstance#prepareForRender's own doc comment
		// what the wearer has on (worn models, the bones they cover), before the pose that the covers hide bones of is resolved
		if (!isReRender && RenderPass.worldPass())
			ArmourClient.update(animatable);

		getModelInstanceState(animatable).prepareForRender(animatable.getId(), RenderPass.worldPass());
	}

	@Override
	@ApiStatus.Internal
	public void render(T entity, float entityYaw, float partialTick, MatrixStack poseStack, VertexConsumerProvider bufferSource, int packedLight) {
		this.animatable = entity;

		defaultRender(poseStack, entity, bufferSource, null, null, entityYaw, partialTick, packedLight);

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

		LivingEntity livingEntity = animatable instanceof LivingEntity entity ? entity : null;
		boolean shouldSit = animatable.hasVehicle() && (animatable.getVehicle() != null);
		float lerpBodyRot = livingEntity == null ? 0 : MathHelper.lerpAngleDegrees(partialTick, livingEntity.prevBodyYaw, livingEntity.bodyYaw);
		float lerpHeadRot = livingEntity == null ? 0 : MathHelper.lerpAngleDegrees(partialTick, livingEntity.prevHeadYaw, livingEntity.headYaw);
		float netHeadYaw = lerpHeadRot - lerpBodyRot;

		if (shouldSit && animatable.getVehicle() instanceof LivingEntity livingentity) {
			lerpBodyRot = MathHelper.lerpAngleDegrees(partialTick, livingentity.prevBodyYaw, livingentity.bodyYaw);
			netHeadYaw = lerpHeadRot - lerpBodyRot;
			float clampedHeadYaw = MathHelper.clamp(MathHelper.wrapDegrees(netHeadYaw), -85, 85);
			lerpBodyRot = lerpHeadRot - clampedHeadYaw;

			if (clampedHeadYaw * clampedHeadYaw > 2500f)
				lerpBodyRot += clampedHeadYaw * 0.2f;

			netHeadYaw = lerpHeadRot - lerpBodyRot;
		}

		if (animatable.getPose() == EntityPose.SLEEPING && livingEntity != null) {
			Direction bedDirection = livingEntity.getSleepingDirection();

			if (bedDirection != null) {
				float eyePosOffset = livingEntity.getEyeHeight(EntityPose.STANDING) - 0.1F;

				poseStack.translate(-bedDirection.getOffsetX() * eyePosOffset, 0, -bedDirection.getOffsetZ() * eyePosOffset);
			}
		}

		float nativeScale = livingEntity != null ? livingEntity.getScale() : 1;
		float ageInTicks = animatable.age + partialTick;
		float limbSwingAmount = 0;
		float limbSwing = 0;

		poseStack.scale(nativeScale, nativeScale, nativeScale);
		applyRotations(animatable, poseStack, ageInTicks, lerpBodyRot, partialTick, nativeScale);

		if (!shouldSit && animatable.isAlive() && livingEntity != null) {
			limbSwingAmount = livingEntity.limbAnimator.getSpeed(partialTick);
			limbSwing = livingEntity.limbAnimator.getPos(partialTick);

			if (livingEntity.isBaby())
				limbSwing *= 3f;

			if (limbSwingAmount > 1f)
				limbSwingAmount = 1f;
		}

		// An entity has one animation state, and a screen's draw of it (the inventory's doll) is turned toward the mouse and made at another time in the frame:
		// advancing the state for that would move the pose the world draws. So an entity that the world is drawing is only drawn in a screen, with the pose the
		// world gave it. One the world does not draw (a preview made for the screen) is animated as usual
		boolean worldPass = RenderPass.worldPass();
		long now = System.nanoTime();

		if (worldPass)
			this.lastWorldDraw.put(animatable.getId(), now);

		boolean leaveAnimationAlone = !worldPass && now - this.lastWorldDraw.getOrDefault(animatable.getId(), Long.MIN_VALUE / 2) < WORLD_DRAW_RECENT_NANOS;

		if (!isReRender && !leaveAnimationAlone) {
			float headPitch = MathHelper.lerp(partialTick, animatable.prevPitch, animatable.getPitch());
			float motionThreshold = getMotionAnimThreshold(animatable);
			Vec3d velocity = animatable.getVelocity();
			float avgVelocity = (float)((Math.abs(velocity.x) + Math.abs(velocity.z)) / 2f);
			AnimationState<T> animationState = new AnimationState<T>(animatable, limbSwing, limbSwingAmount, partialTick, avgVelocity >= motionThreshold && limbSwingAmount != 0);
			long instanceId = getInstanceId(animatable);
			StrutModel<T> currentModel = getStrutModel();

			animationState.setData(DataTickets.TICK, animatable.getTick(animatable));
			animationState.setData(DataTickets.ENTITY, animatable);
			animationState.setData(DataTickets.HAS_CLIP, clip -> {
				try {
					return currentModel.getAnimation(animatable, clip) != null;
				}
				catch (RuntimeException missingFile) {
					return false;
				}
			});
			animationState.setData(DataTickets.ENTITY_MODEL_DATA, new EntityModelData(shouldSit, livingEntity != null && livingEntity.isBaby(), -netHeadYaw, -headPitch));
			currentModel.addAdditionalStateData(animatable, instanceId, animationState::setData);
			currentModel.handleAnimations(animatable, instanceId, animationState, partialTick);
		}

		poseStack.translate(0, 0.01f, 0);

		this.modelRenderTranslations = new Matrix4f(poseStack.peek().getPositionMatrix());

		if (buffer != null)
			StrutRenderer.super.actuallyRender(poseStack, animatable, model, renderType, bufferSource, buffer, isReRender, partialTick,
					packedLight, packedOverlay, colour);
		else if (!isReRender)
			trackAllBones(animatable, model);

		// Attachments follow the model, so they are drawn in the same rotated, scaled space as its geometry, not after this method has popped it
		if (!isReRender)
			renderAttachments(poseStack, animatable, model, bufferSource, packedLight, packedOverlay);

		poseStack.pop();
	}

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
		super.render(animatable, 0, partialTick, poseStack, bufferSource, packedLight);

		if (animatable instanceof MobEntity mob) {
			Entity leashHolder = mob.getLeashHolder();

			if (leashHolder != null)
				renderLeash(mob, partialTick, poseStack, bufferSource, leashHolder);
		}
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
	 * {@link MatrixStack} at all. {@code localSpaceMatrix}/{@code worldSpaceMatrix} both start from
	 * the same {@code entityRenderTranslations}-relative delta, translated differently (interpolated
	 * render-position offset vs. this entity's real world position) - exactly what the old
	 * poseStack-derived computation produced, just without walking one
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

		localSpace.translate(getPositionOffset(animatable, 1).toVector3f());
		bone.setLocalSpaceMatrix(localSpace);

		Matrix4f worldSpace = new Matrix4f(localBase);

		worldSpace.translate(animatable.getPos().toVector3f());
		bone.setWorldSpaceMatrix(worldSpace);
	}

	/**
	 * Applies rotation transformations to the renderer prior to render time to account for various entity states
	 * @deprecated Use {@link #applyRotations(Entity, MatrixStack, float, float, float, float)}
	 */
	@Deprecated(forRemoval = true)
	protected void applyRotations(T animatable, MatrixStack poseStack, float ageInTicks, float rotationYaw,
			float partialTick) {
		applyRotations(animatable, poseStack, ageInTicks, rotationYaw, partialTick, 1);
	}

	/**
	 * Applies rotation transformations to the renderer prior to render time to account for various entity states
	 */
	protected void applyRotations(T animatable, MatrixStack poseStack, float ageInTicks, float rotationYaw, float partialTick, float nativeScale) {
		if (isShaking(animatable))
			rotationYaw += (float)(Math.cos(animatable.age * 3.25d) * Math.PI * 0.4d);

		if (!animatable.isInPose(EntityPose.SLEEPING))
			poseStack.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180f - rotationYaw));

		if (animatable instanceof LivingEntity livingEntity) {
			if (livingEntity.deathTime > 0) {
				float deathRotation = (livingEntity.deathTime + partialTick - 1f) / 20f * 1.6f;

				poseStack.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(Math.min(MathHelper.sqrt(deathRotation), 1) * getDeathMaxRotation(animatable)));
			}
			else if (livingEntity.isUsingRiptide()) {
				poseStack.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-90f - livingEntity.getPitch()));
				poseStack.multiply(RotationAxis.POSITIVE_Y.rotationDegrees((livingEntity.age + partialTick) * -75f));
			}
			else if (animatable.isInPose(EntityPose.SLEEPING)) {
				Direction bedOrientation = livingEntity.getSleepingDirection();

				poseStack.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(bedOrientation != null ? RenderUtil.getDirectionAngle(bedOrientation) : rotationYaw));
				poseStack.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(getDeathMaxRotation(animatable)));
				poseStack.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(270f));
			}
			else if (LivingEntityRenderer.shouldFlipUpsideDown(livingEntity)) {
				poseStack.translate(0, (animatable.getHeight() + 0.1f) / nativeScale, 0);
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
	 * Get the maximum distance (in blocks) that an entity's nameplate should be visible.
	 * <p>This is only a short-circuit predicate, and other conditions after this check must be also passed in order for the name to render</p>
	 */
	public double getNameRenderCutoffDistance(T animatable) {
		return animatable.isSneaky() ? 32d : 64d;
	}

	/**
	 * Whether the entity's nametag should be rendered or not
	 * <p>
	 * Pretty much exclusively used in {@code EntityRenderer#renderNameTag}
	 */
	@Override
	public boolean hasLabel(T animatable) {
		if (!(animatable instanceof LivingEntity))
			return super.hasLabel(animatable);

		double nameRenderCutoff = getNameRenderCutoffDistance(animatable);

		if (this.dispatcher.getSquaredDistanceToCamera(animatable) >= nameRenderCutoff * nameRenderCutoff)
			return false;

		if (animatable instanceof MobEntity && (!animatable.shouldRenderName() && (!animatable.hasCustomName() || animatable != this.dispatcher.targetedEntity)))
			return false;

		final MinecraftClient minecraft = MinecraftClient.getInstance();
		boolean visibleToClient = !animatable.isInvisibleTo(minecraft.player);
		AbstractTeam entityTeam = animatable.getScoreboardTeam();

		if (entityTeam == null)
			return MinecraftClient.isHudEnabled() && animatable != minecraft.getCameraEntity() && visibleToClient && !animatable.hasPassengers();

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
		if (!(animatable instanceof LivingEntity entity))
			return OverlayTexture.DEFAULT_UV;

		return OverlayTexture.packUv(OverlayTexture.getU(u),
				OverlayTexture.getV(entity.hurtTime > 0 || entity.deathTime > 0));
	}

	/**
	 * Whether the entity is currently shaking. This is usually used for freezing, but also for things like piglin conversion or striders suffocating
	 * <p>
	 * This is used for a shaking effect while rendering
	 * @see net.minecraft.client.render.entity.LivingEntityRenderer#isShaking(LivingEntity)
	 */
	public boolean isShaking(T animatable) {
		return animatable.isFrozen();
	}

	/**
	 * Static rendering code for rendering a leash segment
	 * <p>
	 * It's a like-for-like from {@code MobRenderer#renderLeash} that had to be duplicated here for flexible usage
	 */
	public <E extends Entity, M extends MobEntity> void renderLeash(M mob, float partialTick, MatrixStack poseStack,
			VertexConsumerProvider bufferSource, E leashHolder) {
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
		int entityBlockLight = getBlockLight((T)mob, entityEyePos);
		int holderBlockLight = leashHolder.isOnFire() ? 15 : leashHolder.getWorld().getLightLevel(LightType.BLOCK, holderEyePos);
		int entitySkyLight = mob.getWorld().getLightLevel(LightType.SKY, entityEyePos);
		int holderSkyLight = mob.getWorld().getLightLevel(LightType.SKY, holderEyePos);

		poseStack.push();
		poseStack.translate(xAngleOffset, leashOffset.y, zAngleOffset);

		Matrix4f posMatrix = new Matrix4f(poseStack.peek().getPositionMatrix());

		for (int segment = 0; segment <= 24; ++segment) {
			StrutEntityRenderer.renderLeashPiece(vertexConsumer, posMatrix, xDif, yDif, zDif, entityBlockLight, holderBlockLight,
					entitySkyLight, holderSkyLight, 0.025f, 0.025f, xOffset, zOffset, segment, false);
		}

		for (int segment = 24; segment >= 0; --segment) {
			StrutEntityRenderer.renderLeashPiece(vertexConsumer, posMatrix, xDif, yDif, zDif, entityBlockLight, holderBlockLight,
					entitySkyLight, holderSkyLight, 0.025f, 0.0f, xOffset, zOffset, segment, true);
		}

		poseStack.pop();
	}

	/**
	 * Static rendering code for rendering a leash segment
	 * <p>
	 * It's a like-for-like from {@link EntityRenderer#addVertexPair} that had to be duplicated here for flexible usage
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
	 *
	 * @return Whether the renderer should proceed based on the cancellation state of the event
	 */
	@Override
	public boolean firePreRenderEvent(MatrixStack poseStack, BakedStrutModel model, VertexConsumerProvider bufferSource, float partialTick, int packedLight) {
		return StrutServices.Client.EVENTS.fireEntityPreRender(this, poseStack, model, bufferSource, partialTick, packedLight);
	}

	/**
	 * Create and fire the relevant {@code Post-Render} event hook for this renderer
	 */
	@Override
	public void firePostRenderEvent(MatrixStack poseStack, BakedStrutModel model, VertexConsumerProvider bufferSource, float partialTick, int packedLight) {
		StrutServices.Client.EVENTS.fireEntityPostRender(this, poseStack, model, bufferSource, partialTick, packedLight);
	}
}
