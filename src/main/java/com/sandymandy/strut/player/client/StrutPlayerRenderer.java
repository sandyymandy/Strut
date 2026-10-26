package com.sandymandy.strut.player.client;

import com.sandymandy.strut.StrutConstants;
import com.sandymandy.strut.animation.AnimationState;
import com.sandymandy.strut.animation.state.PosePalette;
import com.sandymandy.strut.cache.object.BakedStrutModel;
import com.sandymandy.strut.cache.object.GroupBoneDefinition;
import com.sandymandy.strut.cache.object.GroupBoneHandle;
import com.sandymandy.strut.constant.DataTickets;
import com.sandymandy.strut.model.StrutModel;
import com.sandymandy.strut.model.data.EntityModelData;
import com.sandymandy.strut.player.BodyAnchor;
import com.sandymandy.strut.player.HandItem;
import com.sandymandy.strut.player.HandUse;
import com.sandymandy.strut.player.PlayerBody;
import com.sandymandy.strut.player.PlayerMotion;
import com.sandymandy.strut.player.PlayerTickets;
import com.sandymandy.strut.player.StrutPlayers;
import com.sandymandy.strut.animation.override.OverrideScope;
import com.sandymandy.strut.renderer.RenderPass;
import com.sandymandy.strut.renderer.StrutReplacedEntityRenderer;
import com.sandymandy.strut.renderer.iris.IrisCompat;
import com.sandymandy.strut.scene.StrutScenes;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.scoreboard.AbstractTeam;
import net.minecraft.util.Arm;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

/**
 * Draws a player as the {@link PlayerBody} they were given. One renderer exists per body, and draws every player that has it
 * <p>
 * It is the replaced-entity renderer, so a body gets what any Strut-drawn entity does (bone overrides, attachments, server-driven animation, events,
 * physics, the GPU and shader paths), plus what a player's own renderer does that the model is not responsible for: the name tag with the scoreboard
 * team's rules, spectators not being drawn, and invisibility
 */
public class StrutPlayerRenderer extends StrutReplacedEntityRenderer<AbstractClientPlayerEntity, PlayerBody> {
	private final Set<Identifier> warnedMissingAnimations = new HashSet<>();

	/** Below the default priority 0 of an override a mod sets */
	private static final int HIDDEN_BONE_PRIORITY = -1000;

	public StrutPlayerRenderer(EntityRendererFactory.Context context, StrutModel<PlayerBody> model, PlayerBody body) {
		super(context, model, body);

	}

	/**
	 * The overrides this body puts on the player for this frame are set here, before the pose is resolved: the pose is resolved by {@code preRender} (what an
	 * override is read by), so overrides set any later (while the animation state is made) would apply a frame late, or, being frame-scoped, never
	 */
	@Override
	public void preRender(MatrixStack poseStack, PlayerBody body, BakedStrutModel model, VertexConsumerProvider bufferSource,
			VertexConsumer buffer, boolean isReRender, float partialTick, int packedLight, int packedOverlay, int colour) {
		if (!isReRender && this.currentEntity != null)
			applyBodyOverrides(body, this.currentEntity);

		super.preRender(poseStack, body, model, bufferSource, buffer, isReRender, partialTick, packedLight, packedOverlay, colour);
	}

	private void applyBodyOverrides(PlayerBody body, AbstractClientPlayerEntity player) {
		// the body's own hidden bones: a frame-scoped override of low priority, so a server override that shows the bone wins and nothing is left behind
		for (String bone : body.hiddenBones(StrutPlayers.variant(player)))
			StrutPlayers.overrides(player).setHidden(bone, true, OverrideScope.FRAME, HIDDEN_BONE_PRIORITY, 1f, 0f);

		// looking out of the body: the bones the camera would be inside are hidden for this player only (local, this frame), and the eye bone is tracked so the
		// camera can be put at it
		// (a body drawn into a screen, the inventory's doll, keeps its head: it is not what the camera is inside)
		if (FirstPersonBody.immersiveBodyOf(player) != null && RenderPass.worldPass()) {
			// not in the shadow pass: the player looking out of the head still casts its shadow
			if (!IrisCompat.isRenderingShadowPass())
				for (String bone : body.firstPersonHiddenBones(StrutPlayers.variant(player)))
					StrutPlayers.overrides(player).setHidden(bone, true, OverrideScope.FRAME, 0, 1f, 0f);

			getStrutModel().getBone(body, player.getId(), StrutPlayersClient.eyeBoneOf(player, body)).ifPresent(handle -> handle.setTrackingMatrices(true));
		}

	}

	@Override
	protected void addStateData(PlayerBody body, AnimationState<PlayerBody> state, float partialTick) {
		AbstractClientPlayerEntity player = this.currentEntity;
		StrutModel<PlayerBody> model = getStrutModel();

		state.setData(PlayerTickets.MOTION, PlayerMotion.of(player, partialTick));

		// swimming and gliding point the whole body along the way it goes: the head does not turn to the camera on top of that
		if (player.isInSwimmingPose() || player.isFallFlying()) {
			EntityModelData look = state.getData(DataTickets.ENTITY_MODEL_DATA);

			if (look != null)
				state.setData(DataTickets.ENTITY_MODEL_DATA, new EntityModelData(look.isSitting(), look.isChild(), 0f, 0f));
		}
		state.setData(PlayerTickets.HAS_CLIP, clip -> {
			try {
				return model.getAnimation(body, clip) != null;
			}
			catch (RuntimeException missingFile) {
				// said once, not every frame: a body whose animation file is not found plays nothing
				if (this.warnedMissingAnimations.add(body.id()))
					StrutConstants.LOGGER.warn("[Strut player body] {} has no animation file it can read, so it plays no animations: {}", body.id(), missingFile.getMessage());

				return false;
			}
		});
	}

	@Override
	public void render(AbstractClientPlayerEntity player, float entityYaw, float partialTick, MatrixStack poseStack, VertexConsumerProvider bufferSource, int packedLight) {
		// a spectator is a camera, not a body
		if (player.isSpectator())
			return;

		AbstractClientPlayerEntity previous = StrutPlayersClient.renderedPlayer;

		StrutPlayersClient.renderedPlayer = player;

		BodyAnchor anchor = StrutPlayers.anchor(player);

		poseStack.push();

		// anchored: the body is drawn at the anchor, as far from the player as the anchor is (the stack is at the player's interpolated position)
		if (anchor != null) {
			poseStack.translate(anchor.x() - MathHelper.lerp(partialTick, player.prevX, player.getX()), anchor.y() - MathHelper.lerp(partialTick, player.prevY, player.getY()),
					anchor.z() - MathHelper.lerp(partialTick, player.prevZ, player.getZ()));
		}

		try {
			super.render(player, entityYaw, partialTick, poseStack, bufferSource, packedLight);
		}
		finally {
			poseStack.pop();
			StrutPlayersClient.renderedPlayer = previous;
		}
	}

	/**
	 * The attachments the server set, and the items in the player's hands on the bones the body draws them on
	 */
	@Override
	public void renderAttachments(MatrixStack poseStack, PlayerBody body, BakedStrutModel model, VertexConsumerProvider bufferSource,
			int packedLight, int packedOverlay) {
		super.renderAttachments(poseStack, body, model, bufferSource, packedLight, packedOverlay);

		AbstractClientPlayerEntity player = this.currentEntity;

		if (player == null)
			return;

		// nothing in the hands while the server says so, or while the player is in a scene (the clip puts the hands where an item means nothing)
		if (StrutPlayers.heldItemsHidden(player) || (!body.showsHeldItemsInScenes() && StrutScenes.inScene(player)))
			return;

		boolean rightHanded = player.getMainArm() == Arm.RIGHT;

		drawHeld(poseStack, body, model, bufferSource, packedLight, player, body.mainHandItem(StrutPlayers.variant(player)), player.getMainHandStack(), rightHanded, Hand.MAIN_HAND);
		drawHeld(poseStack, body, model, bufferSource, packedLight, player, body.offHandItem(StrutPlayers.variant(player)), player.getOffHandStack(), !rightHanded, Hand.OFF_HAND);
	}

	private void drawHeld(MatrixStack poseStack, PlayerBody body, BakedStrutModel model, VertexConsumerProvider bufferSource, int packedLight,
			AbstractClientPlayerEntity player, @Nullable HandItem where, ItemStack stack, boolean rightHand, Hand hand) {
		if (where == null || stack.isEmpty())
			return;

		// the other hand is using something (a bow drawn, a shield raised, food eaten): what this one holds is not drawn until it is over, so it is not in the way
		if (!body.showsOtherHandWhileUsing() && HandUse.hiddenBecauseOtherIsBusy(player, hand))
			return;

		GroupBoneDefinition bone = model.getBone(where.bone()).orElse(null);

		if (bone == null)
			return;

		Matrix4f matrix = new Matrix4f();

		PosePalette.computeBoneAtPivot(getModelInstanceState(body).effectivePose(), bone, matrix);
		matrix.translate(where.offsetX(), where.offsetY(), where.offsetZ());
		matrix.rotateZYX((float) Math.toRadians(where.rotationZ()), (float) Math.toRadians(where.rotationY()), (float) Math.toRadians(where.rotationX()));
        matrix.scale(where.scaleX(), where.scaleY(), where.scaleZ());

		poseStack.push();
		poseStack.peek().getPositionMatrix().mul(matrix);
		poseStack.peek().getNormalMatrix().mul(new Matrix3f(matrix).invert().transpose());

		ModelTransformationMode mode = rightHand ? ModelTransformationMode.THIRD_PERSON_RIGHT_HAND
				: ModelTransformationMode.THIRD_PERSON_LEFT_HAND;

		if (body.stableHeldModel(stack)) {
			// without the player the item's model predicates (blocking, pulling a bow) have nothing to read, so it stays the model it has at rest
			MinecraftClient.getInstance().getItemRenderer().renderItem((LivingEntity) null, stack, mode, !rightHand, poseStack, bufferSource, player.getWorld(),
					packedLight, OverlayTexture.DEFAULT_UV, player.getId() + mode.ordinal());
		}
		else {
			MinecraftClient.getInstance().getEntityRenderDispatcher().getHeldItemRenderer().renderItem(player, stack, mode, !rightHand, poseStack, bufferSource, packedLight);
		}

		poseStack.pop();
	}

	/**
	 * Where the eye bone is, noted while the player is still the one being drawn (the renderer lets go of them when the draw is over), for the next frame's camera
	 */
	@Override
	public void postRender(MatrixStack poseStack, PlayerBody body, BakedStrutModel model, VertexConsumerProvider bufferSource,
			VertexConsumer buffer, boolean isReRender, float partialTick, int packedLight, int packedOverlay, int colour) {
		super.postRender(poseStack, body, model, bufferSource, buffer, isReRender, partialTick, packedLight, packedOverlay, colour);

		// the eye is where it is in the world: not when the body is drawn into a screen (the inventory's paper doll), turned toward the mouse and scaled to fit
		if (!isReRender && this.currentEntity != null && RenderPass.worldPass()) {
			recordEye(this.currentEntity);

			AbstractClientPlayerEntity drawn = this.currentEntity;

			StrutPlayersClient.recordBones(drawn, bone -> offsetOfBone(body, drawn, bone));
		}
	}

	/**
	 * An anchored body faces the way the anchor says
	 */
	@Override
	protected void applyRotations(PlayerBody body, MatrixStack poseStack, float ageInTicks, float rotationYaw, float partialTick, float nativeScale) {
		AbstractClientPlayerEntity player = this.currentEntity;
		BodyAnchor anchor = player == null ? null : StrutPlayers.anchor(player);
		float yaw = anchor != null ? anchor.yaw() : rotationYaw;

		if (player != null && player.isInPose(EntityPose.SLEEPING) && !body.sleepRotation()) {
			// the clip lies the body down: it is only turned to the bed
			Direction bed = player.getSleepingDirection();

			poseStack.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180f - (bed != null ? bed.asRotation() : yaw)));

			return;
		}

		super.applyRotations(body, poseStack, ageInTicks, yaw, partialTick, nativeScale);

		if (player != null)
			applyTilts(body, poseStack, player, partialTick);
	}

	/**
	 * What the vanilla player renderer does after turning the body: lean it along the water when swimming, and fly it forward and bank it when gliding
	 */
	private void applyTilts(PlayerBody body, MatrixStack poseStack, AbstractClientPlayerEntity player, float partialTick) {
		float pitch = player.getPitch(partialTick);

		if (player.isFallFlying()) {
			if (!body.glideTilt())
				return;

			float ticks = player.getFallFlyingTicks() + partialTick;
			float amount = MathHelper.clamp(ticks * ticks / 100f, 0f, 1f);

			if (!player.isUsingRiptide())
				poseStack.multiply(RotationAxis.POSITIVE_X.rotationDegrees(amount * (-90f - pitch)));

			Vec3d look = player.getRotationVec(partialTick);
			Vec3d velocity = player.lerpVelocity(partialTick);
			double velocitySq = velocity.horizontalLengthSquared();
			double lookSq = look.horizontalLengthSquared();

			if (velocitySq > 0 && lookSq > 0) {
				double dot = (velocity.x * look.x + velocity.z * look.z) / Math.sqrt(velocitySq * lookSq);
				double cross = velocity.x * look.z - velocity.z * look.x;

				poseStack.multiply(RotationAxis.POSITIVE_Y.rotation((float) (Math.signum(cross) * Math.acos(Math.min(1, dot)))));
			}

			return;
		}

		float lean = player.getLeaningPitch(partialTick);

		if (lean > 0 && body.swimTilt()) {
			float target = player.isTouchingWater() ? -90f - pitch : -90f;

			poseStack.multiply(RotationAxis.POSITIVE_X.rotationDegrees(MathHelper.lerp(lean, 0f, target)));

			if (player.isInSwimmingPose())
				poseStack.translate(0f, -1f, 0.3f);
		}
	}

	/**
	 * The camera sits at the eye bone for a player looking out of their own body, so the body is always drawn: its box is where the camera is
	 */
	@Override
	public boolean shouldRender(AbstractClientPlayerEntity player, Frustum frustum, double x, double y, double z) {
		// an anchored body is somewhere the player's own box does not say
		return FirstPersonBody.immersiveBodyOf(player) != null || StrutPlayers.anchor(player) != null || super.shouldRender(player, frustum, x, y, z);
	}

	/**
	 * Where the eye bone is relative to the player, as just drawn, for the camera of the next frame
	 */
	private void recordEye(AbstractClientPlayerEntity player) {
		PlayerBody body = getAnimatable();

		if (FirstPersonBody.immersiveBodyOf(player) == null) {
			FirstPersonBody.clear();

			return;
		}

		FirstPersonBody.recordEyeOffset(eyeOffset(body, player));
	}

	/**
	 * Where the eye bone is relative to the player's own position, as it was just drawn, or null if the model has no such bone
	 * <p>
	 * Worked out from the pose directly, not from the bone's tracked matrices, and so the same whether the bone is hidden (the head is, for the player themself,
	 * in first person) or not: the bone's pivot in the animated model, taken through the transform the body was drawn with (turned to face, scaled, placed), and
	 * measured from where the player is drawn. Both are relative to the camera, so what is left is the offset in the world's own axes
	 */
	@Nullable
	private Vec3d eyeOffset(PlayerBody body, AbstractClientPlayerEntity player) {
		return offsetOfBone(body, player, StrutPlayersClient.eyeBoneOf(player, body));
	}

	/** {@link #eyeOffset} for any bone of the body */
	@Nullable
	private Vec3d offsetOfBone(PlayerBody body, AbstractClientPlayerEntity player, String boneName) {
		GroupBoneDefinition eye = getStrutModel().getBone(body, player.getId(), boneName)
				.map(GroupBoneHandle::getDefinition).orElse(null);

		if (eye == null)
			return null;

		Matrix4f inModel = new Matrix4f();

		PosePalette.computeBoneAtPivot(getModelInstanceState(body).effectivePose(), eye, inModel);

		return FirstPersonBody.offsetOfBone(inModel, this.modelRenderTranslations, this.entityRenderTranslations);
	}

	/**
	 * A player's name is shown to other players within range, unless the scoreboard team says otherwise: the rules the vanilla player renderer applies
	 */
	@Override
	public boolean hasLabel(AbstractClientPlayerEntity player) {
		MinecraftClient client = MinecraftClient.getInstance();

		if (!MinecraftClient.isHudEnabled() || client.player == null || player == client.getCameraEntity() || BodyPreview.isPreview(player))
			return false;

		if (player.isInvisibleTo(client.player) || player.hasPassengers())
			return false;

		double range = player.isSneaking() ? 32 : 64;

		if (this.dispatcher.getSquaredDistanceToCamera(player) >= range * range)
			return false;

		AbstractTeam team = player.getScoreboardTeam();

		if (team == null)
			return true;

		AbstractTeam own = client.player.getScoreboardTeam();

		return switch (team.getNameTagVisibilityRule()) {
			case ALWAYS -> true;
			case NEVER -> false;
			case HIDE_FOR_OTHER_TEAMS -> own != null && team.isEqual(own);
			case HIDE_FOR_OWN_TEAM -> own == null || !team.isEqual(own);
		};
	}
}
