package com.sandymandy.strut.renderer;

import com.sandymandy.strut.animation.state.BoneVisuals;
import com.sandymandy.strut.model.RenderMode;
import com.sandymandy.strut.player.HandUse;
import com.sandymandy.strut.renderer.gpu.LightProbe;
import com.sandymandy.strut.renderer.hook.StrutRenderHooks;
import com.sandymandy.strut.renderer.hook.SubmitContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumers;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.OutlineVertexConsumerProvider;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.util.Arm;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import com.sandymandy.strut.animatable.StrutAnimatable;
import com.sandymandy.strut.animation.AnimationState;
import com.sandymandy.strut.animation.attachment.Attachment;
import com.sandymandy.strut.animation.attachment.ClientAttachmentState;
import com.sandymandy.strut.animation.attachment.ClientAttachmentStates;
import com.sandymandy.strut.animation.attachment.ClientModelAttachments;
import com.sandymandy.strut.animation.state.ModelInstance;
import com.sandymandy.strut.animation.state.Pose;
import com.sandymandy.strut.animation.state.PosePalette;
import com.sandymandy.strut.animation.state.SkinningState;
import com.sandymandy.strut.cache.object.*;
import com.sandymandy.strut.cache.texture.AnimatableTexture;
import com.sandymandy.strut.loading.math.MolangQueries;
import com.sandymandy.strut.model.StrutModel;
import com.sandymandy.strut.perf.RenderPerfStats;
import com.sandymandy.strut.renderer.gpu.GpuBatchQueue;
import com.sandymandy.strut.renderer.gpu.GpuModelCache;
import com.sandymandy.strut.renderer.iris.IrisCompat;
import com.sandymandy.strut.util.Color;
import com.sandymandy.strut.util.RenderUtil;

import java.util.Optional;

//TODO Split sources support
/**
 * Base interface for all Strut renderers.<br>
 */
public interface StrutRenderer<T extends StrutAnimatable> {
	/**
	 * Gets the model instance for this renderer
	 */
	StrutModel<T> getStrutModel();

	/**
	 * Gets the {@link StrutAnimatable} instance currently being rendered
	 */
	T getAnimatable();

	/**
	 * Gets the texture resource location to render for the given animatable
	 */
	default Identifier getTexture(T animatable) {
		return getStrutModel().getTextureResource(animatable, this);
	}

	/**
	 * Gets the {@link RenderLayer} to render the given animatable with
	 * <p>
	 * Uses the {@code RenderLayer#entityCutoutNoCull} {@code RenderLayer} by default
	 * <p>
	 * Override this to change the way a model will render (such as translucent models, etc)
	 *
	 * @return Return the RenderLayer to use, or null to prevent the model rendering. Returning null will not prevent animation functions taking place
	 */
	@Nullable
	default RenderLayer getRenderType(T animatable, Identifier texture,
									 @Nullable VertexConsumerProvider bufferSource,
									 float partialTick) {
		return getStrutModel().getRenderType(animatable, texture);
	}

	/**
	 * Gets a tint-applying color to render the given animatable with
	 * <p>
	 * Returns {@link Color#WHITE} by default
	 */
	default Color getRenderColor(T animatable, float partialTick, int packedLight) {
		return Color.WHITE;
	}

	/**
	 * Gets a packed overlay coordinate pair for rendering
	 * <p>
	 * Mostly just used for the red tint when an entity is hurt,
	 * but can be used for other things like the {@code Creeper}
	 * white tint when exploding.
	 */
	default int getPackedOverlay(T animatable, float u, float partialTick) {
		return OverlayTexture.DEFAULT_UV;
	}

	/**
	 * Gets the id that represents the current animatable's instance for animation purposes
	 * <p>
	 * This is mostly useful for things like items, which have a single registered instance for all objects
	 */
	default long getInstanceId(T animatable) {
		return animatable.hashCode();
	}

	/**
	 * The entity whose worn models ({@link ClientModelAttachments}) are drawn with this animatable: the animatable itself if it is an entity, or the entity a
	 * replaced-entity renderer is drawing; null for anything else
	 */
	@Nullable
	default Entity attachmentOwner(T animatable) {
		return animatable instanceof Entity entity ? entity : null;
	}

	/**
	 * The skinning buffers belonging to this animatable instance
	 * <p>
	 * Resolved through the animatable's own instance cache rather than held on the renderer, since
	 * a renderer is shared by every animatable of its type - storing pose data on it would be the
	 * same mistake as storing it on the baked model
	 */
	default SkinningState getSkinningState(T animatable) {
		return animatable.getAnimatableInstanceCache().getManagerForId(getInstanceId(animatable)).getSkinningState();
	}

	/**
	 * The model-shaped state - {@link com.sandymandy.strut.animation.state.Pose} and
	 * {@link GroupBoneHandle BoneHandles} - belonging to this animatable instance
	 * <p>
	 * Resolved through the animatable's own instance cache rather than held on the renderer, for
	 * the same reason as {@link #getSkinningState}: a renderer is shared by every animatable of
	 * its type
	 */
	default ModelInstance getModelInstanceState(T animatable) {
		return animatable.getAnimatableInstanceCache().getManagerForId(getInstanceId(animatable)).getModelInstance();
	}

	/**
	 * Determines the threshold value before the animatable should be considered moving for animation purposes
	 * <p>
	 * The default value and usage for this varies depending on the renderer
	 * <ul>
	 *     <li>For entities, it represents the averaged lateral velocity of the object.</li>
	 *     <li>For {@link com.sandymandy.strut.animatable.StrutBlockEntity Tile Entities} and {@link com.sandymandy.strut.animatable.StrutItem Items}, it's currently unused</li>
	 *</ul>
	 *
	 *
	 * The lower the value, the more sensitive the {@link AnimationState#isMoving()} check will be.<br>
	 * Particularly low values may have adverse effects however
	 */
	default float getMotionAnimThreshold(T animatable) {
		return 0.015f;
	}

	/**
	 * Whether this renderer draws {@link #renderAttachments} itself, inside the transform its model is drawn in (an entity's body rotation and
	 * scale are applied inside {@code actuallyRender} and popped again before {@link #defaultRender} regains control, so an attachment drawn there
	 * would stay put while the entity turns). False for renderers whose model is drawn in the stack {@link #defaultRender} owns
	 */
	default boolean attachmentsDrawnInsideModelSpace() {
		return false;
	}

	/**
	 * Initial access point for rendering. It all begins here
	 * <p>
	 * All Strut renderers should immediately defer their respective default {@code render} calls to this, for consistent handling
	 */
	default void defaultRender(MatrixStack poseStack, T animatable, VertexConsumerProvider bufferSource, @Nullable RenderLayer renderType, @Nullable VertexConsumer buffer,
							   float yaw, float partialTick, int packedLight) {
		poseStack.push();

		int renderColor = getRenderColor(animatable, partialTick, packedLight).argbInt();
		int packedOverlay = getPackedOverlay(animatable, 0, partialTick);
		BakedStrutModel model = getStrutModel().getBakedModel(getStrutModel().getModelResource(animatable, this));

		// the model can change between two draws of the same animatable (a body whose variant changes, a bare and a dressed file): its pose, handles and what is
		// resolved from them are made for the one it is drawn with now, before anything reads them. The animation step does this too, but it does not run for every draw
		// (a second draw in one tick, a distant instance), and a pose made for a model with fewer bones is read past its end by one with more
		getModelInstanceState(animatable).poseFor(model);

		if (renderType == null)
			renderType = getRenderType(animatable, getTexture(animatable), bufferSource, partialTick);

		if (buffer == null && renderType != null)
			buffer = bufferSource.getBuffer(renderType);

		preRender(poseStack, animatable, model, bufferSource, buffer, false, partialTick, packedLight, packedOverlay, renderColor);

		if (firePreRenderEvent(poseStack, model, bufferSource, partialTick, packedLight)) {
			actuallyRender(poseStack, animatable, model, renderType,
					bufferSource, buffer, false, partialTick, packedLight, packedOverlay, renderColor);
			postRender(poseStack, animatable, model, bufferSource, buffer, false, partialTick, packedLight, packedOverlay, renderColor);
			// An entity renderer draws its attachments itself, inside the rotated space its model is drawn in
			if (!attachmentsDrawnInsideModelSpace())
				renderAttachments(poseStack, animatable, model, bufferSource, packedLight, packedOverlay);

			firePostRenderEvent(poseStack, model, bufferSource, partialTick, packedLight);
		}

		poseStack.pop();

		renderFinal(poseStack, animatable, model, bufferSource, buffer, partialTick, packedLight, packedOverlay, renderColor);
		doPostRenderCleanup();
		MolangQueries.clearActor();
	}

	/**
	 * The actual render method that sub-type renderers should override to handle their specific rendering tasks
	 * <p>
	 * {@link StrutRenderer#preRender} has already been called by this stage, and {@link StrutRenderer#postRender} will be called directly after
	 */
	default void actuallyRender(MatrixStack poseStack, T animatable, BakedStrutModel model, @Nullable RenderLayer renderType,
								VertexConsumerProvider bufferSource, @Nullable VertexConsumer buffer, boolean isReRender, float partialTick,
								int packedLight, int packedOverlay, int colour) {
		updateAnimatedTextureFrame(animatable);

		/*
		 * Armatures are posed once here, ahead of the GpuBatchQueue.submit call, which needs this instance's skin
		 * matrices already current, the same way it needs effectivePose() current. Posing them into this
		 * animatable's own buffers keeps two entities sharing a model from overwriting each other mid-frame
		 */
		if (!model.armatures().isEmpty())
			getSkinningState(animatable).poseArmatures(model, getModelInstanceState(animatable));

		/*
		 * Queue the model on GpuBatchQueue, which draws it once a frame after the world's entities (on WorldRenderEvents.AFTER_ENTITIES), and
		 * through the shader pack's own program under one. That is done in the world pass only: poseStack.peek().getPositionMatrix() is
		 * camera-relative but unrotated only for things rendered there (see GpuRenderBackend's doc comment), and a held item or a
		 * GUI-rendered block has a different camera and view setup, so those are drawn at once instead
		 */
		// Every renderer is drawn by the GPU path: in the world pass it is queued and flushed with the frame's entities (the shader pack's own program under one), and in a
		// screen, a hand or an inventory slot it is drawn at once in the view it was asked for. There is no other path: a draw the GPU cannot make draws nothing
		StrutModel<T> strutModel = getStrutModel();
		GpuBatchQueue.RenderKey renderKey = new GpuBatchQueue.RenderKey(strutModel.renderMode(animatable) == RenderMode.TRANSLUCENT,
				strutModel.translucentDepthBias(animatable), strutModel.translucentCullsBackFaces(animatable), strutModel.textureOverrideReplacesBase(animatable), false,
				strutModel.overlaysWriteDepth(animatable), strutModel.shadingMode(animatable));
		ModelInstance instance = getModelInstanceState(animatable);
		// the pose is renderedPose() plus whatever bone overrides were resolved this frame (in preRender, ahead of this call), and the bones the model never draws
		Pose pose = instance.effectivePoseWithHidden(model, strutModel.hiddenBones(animatable));
		BoneVisuals visuals = customBoneVisuals(animatable, model, instance.boneVisuals());

		if (RenderPass.worldPass()) {
			GpuBatchQueue.setLights(lightsOf(animatable, attachmentOwner(animatable)));

			// what a mod does with a model on its way to the queue: its colour, how it is shaded, the floats its shader hooks read
			float[] custom = null;

			if (StrutRenderHooks.hasSubmitListeners()) {
				SubmitContext submit = new SubmitContext(animatable, colour, renderKey.shading());

				StrutRenderHooks.SUBMIT.invoker().onSubmit(submit);
				colour = submit.colour();
				custom = submit.customValues();

				if (submit.shading() != renderKey.shading())
					renderKey = new GpuBatchQueue.RenderKey(renderKey.translucent(), renderKey.bias(), renderKey.cullBackFaces(), renderKey.overrideReplacesBase(), renderKey.additive(),
							renderKey.overlayDepth(), submit.shading());
			}

			GpuBatchQueue.setCustom(custom);

			if (attachmentOwner(animatable) != null)
				packedLight = LightProbe.smooth(attachmentOwner(animatable), packedLight);

			// a bone's tracked matrices (a passenger seat, a camera bone) are refreshed here, since there is no walk to refresh them; a no-op for a bone nobody has asked of
			for (GroupBoneHandle handle : instance.handlesFor(model))
				trackBoneMatrices(animatable, handle);

			GpuBatchQueue.submit(GpuModelCache.get(model), model, getTexture(animatable), renderKey, new Matrix4f(poseStack.peek().getPositionMatrix()), pose,
					visuals, instance.boneTextures(), instance.boneLayers(), model.armatures().isEmpty() ? null : getSkinningState(animatable), packedLight, packedOverlay, colour,
					instance);

			// the Strut models the wearer has on (armour, a hat), posed on its own pose and drawn with it, in the same batch
			Entity wearer = attachmentOwner(animatable);

			if (wearer != null)
				ClientModelAttachments.submitAll(wearer, model, pose, poseStack.peek().getPositionMatrix(), renderKey, packedLight, packedOverlay, colour);

			GpuBatchQueue.setLights(null);
			GpuBatchQueue.setCustom(null);

			return;
		}

		GpuBatchQueue.setLights(null);
		GpuBatchQueue.beginImmediate();

		try {
			GpuBatchQueue.submit(GpuModelCache.get(model), model, getTexture(animatable), renderKey, new Matrix4f(poseStack.peek().getPositionMatrix()), pose, visuals,
					instance.boneTextures(), instance.boneLayers(), model.armatures().isEmpty() ? null : getSkinningState(animatable), packedLight, packedOverlay, colour, instance);

			Entity wearer = attachmentOwner(animatable);

			if (wearer != null)
				ClientModelAttachments.submitAll(wearer, model, pose, poseStack.peek().getPositionMatrix(), renderKey, packedLight, packedOverlay, colour);
		}
		finally {
			GpuBatchQueue.endImmediate();
		}
	}

	/**
	 * The per-bone colours and texture shifts this draw uses, in place of {@code resolved} (what the instance's bone overrides gave this frame, or null for none): a
	 * renderer that tints some bones itself (a dyed armour) returns its own. Return {@code resolved} to change nothing. The result is read when the frame's batch is
	 * flushed, so it must not be reused for another draw
	 */
	@Nullable
	default BoneVisuals customBoneVisuals(T animatable, BakedStrutModel model, @Nullable BoneVisuals resolved) {
		return resolved;
	}

	/**
	 * Where {@code bone} is in the model this frame, at its pivot, as a matrix that takes a point in the bone's own space into the model's: what a renderer that draws
	 * something on a bone (an item on a block's slot) multiplies onto its stack, as {@link #renderAttachments} does. Null if the model has no such bone
	 */
	@Nullable
	default Matrix4f boneMatrixAtPivot(T animatable, BakedStrutModel model, String bone) {
		Optional<GroupBoneDefinition> definition = model.getBone(bone);

		if (definition.isEmpty())
			return null;

		Matrix4f matrix = new Matrix4f();

		PosePalette.computeBoneAtPivot(getModelInstanceState(animatable).effectivePose(), definition.get(), matrix);

		return matrix;
	}

	/**
	 * Called before rendering the model to buffer. Allows for render modifications and preparatory work such as scaling and translating
	 * <p>
	 * {@link MatrixStack} translations made here are kept until the end of the render process
	 */
	default void preRender(MatrixStack poseStack, T animatable, BakedStrutModel model, @Nullable VertexConsumerProvider bufferSource, @Nullable VertexConsumer buffer, boolean isReRender, float partialTick, int packedLight,
						   int packedOverlay, int colour) {}

	/**
	 * Called after rendering the model to buffer. Post-render modifications should be performed here
	 * <p>
	 * {@link MatrixStack} transformations will be unused and lost once this method ends
	 */
	default void postRender(MatrixStack poseStack, T animatable, BakedStrutModel model, VertexConsumerProvider bufferSource, @Nullable VertexConsumer buffer, boolean isReRender, float partialTick, int packedLight, int packedOverlay, int colour) {}

	/**
	 * Renders every active {@link Attachment} for this animatable - the
	 * {@code BlockAndItemStrutLayer} replacement. Called directly from
	 * {@link #defaultRender}, not from {@link #postRender} - {@code StrutReplacedEntityRenderer}'s own
	 * {@code postRender} override does not chain to this interface's default at all (it calls
	 * vanilla's own {@code super.render} instead), so a renderer-level hook would silently skip
	 * attachments for every replaced entity, which is exactly the animatable kind
	 * {@code entity.attachments(relatedEntity)} exists for
	 * <p>
	 * No-op for any animatable that is not an {@link Entity} at all (items, blocks, singletons -
	 * attachments are entity-only) or has never had one set. {@code poseStack} is
	 * expected to carry exactly the entity-level transform and nothing bone-level, the same
	 * convention {@code GpuBatchQueue.submit}'s own {@code entityTransform} capture relies on -
	 * {@link #defaultRender}'s own call site is the only correct place this holds
	 * <p>
	 * The item sits at the bone's pivot, in the bone's posed orientation (an attachment's offset, rotation and scale are relative to that)
	 * <p>
	 * Applies the bone's world matrix by multiplying it directly onto the pushed
	 * {@code MatrixStack.Entry}'s own position <em>and</em> normal matrices, rather than decomposing
	 * it into translate/rotate({@code Quaternionf})/scale and replaying that through
	 * {@code MatrixStack}'s own convenience calls - live-found necessary: a decomposed
	 * {@link Matrix4f#getScale} always returns positive magnitudes, silently discarding a bone's own
	 * negative-scale axis (a real, common rigging trick for mirroring a symmetric limb, not a
	 * hypothetical), which flips the reconstructed transform's handedness relative to the original.
	 * That flip is invisible in the position data alone but corrupts winding order, so the attached
	 * item back-face-culled itself from whichever side of the mirror the camera happened to be on -
	 * exactly "works half the time, and not from certain angles." Multiplying the matrix in directly
	 * carries the reflection through intact. The normal matrix needs its own explicit update to match
	 * - {@code MatrixStack} only keeps its normal matrix in sync automatically for the
	 * translate/rotate/scale calls it exposes, not for an arbitrary {@code Matrix4f} multiplied in
	 * from outside - computed as the inverse-transpose of the composed matrix's own 3x3 part, the
	 * identical convention {@code GpuRenderBackend}'s own vertex shader already uses
	 * ({@code transpose(inverse(mat3(boneMatrix)))})
	 */
	/** The light on {@code animatable} where it stands (see {@code LightProbe}), or null for one that is nowhere in a world */
	private static LightProbe.Rig lightsOf(Object animatable, @Nullable Entity owner) {
		// the entity it is drawn for: the animatable itself, or the player a body is drawn on
		if (owner != null)
			return LightProbe.of(owner);

		if (animatable instanceof BlockEntity blockEntity)
			return LightProbe.at(blockEntity.getWorld(), blockEntity.getPos(), blockEntity);

		return null;
	}

	default void renderAttachments(MatrixStack poseStack, T animatable, BakedStrutModel model, VertexConsumerProvider bufferSource,
									int packedLight, int packedOverlay) {
		if (!(animatable instanceof Entity entity))
			return;

		ClientAttachmentState state = ClientAttachmentStates.get(entity.getId());

		if (state == null || state.all().isEmpty())
			return;

		Pose pose = getModelInstanceState(animatable).effectivePose();
		Matrix4f boneMatrix = new Matrix4f();

		for (Attachment attachment : state.all().values()) {
			Optional<GroupBoneDefinition> definition = model.getBone(attachment.bone());

			if (definition.isEmpty())
				continue;

			// a hand-held item whose holder is using the item in its other hand (a bow drawn, a shield raised, food eaten) is not drawn until that is over
			if (attachment.hand() != Attachment.HAND_NONE && entity instanceof LivingEntity user) {
				boolean mainArmRight = user.getMainArm() == Arm.RIGHT;
				boolean isMainHand = (attachment.hand() == Attachment.HAND_RIGHT) == mainArmRight;

				if (HandUse.hiddenBecauseOtherIsBusy(user, isMainHand ? Hand.MAIN_HAND : Hand.OFF_HAND))
					continue;
			}

			// Origin at the bone's pivot: an item belongs there (GeckoLib's item layer did the same)
			PosePalette.computeBoneAtPivot(pose, definition.get(), boneMatrix);

			// The attachment's own offset/rotation/scale, composed in the same bone-local space
			// PosePalette.computeBone already left boneMatrix in - translate, then rotate, then
			// scale, the same order every other bone-local transform in this codebase applies them
			boneMatrix.translate(attachment.offsetX(), attachment.offsetY(), attachment.offsetZ());

			if (attachment.rotX() != 0 || attachment.rotY() != 0 || attachment.rotZ() != 0)
				boneMatrix.rotateZYX(attachment.rotZ(), attachment.rotY(), attachment.rotX());

			boneMatrix.scale(attachment.scaleX(), attachment.scaleY(), attachment.scaleZ());

			poseStack.push();
			poseStack.peek().getPositionMatrix().mul(boneMatrix);
			poseStack.peek().getNormalMatrix().mul(new Matrix3f(boneMatrix).invert().transpose());

			if (attachment.item() != null)
				renderAttachedItem(poseStack, attachment, entity, bufferSource, packedLight, packedOverlay);
			else if (attachment.block() != null)
				renderAttachedBlock(poseStack, attachment, bufferSource, packedLight);

			poseStack.pop();
		}
	}

	/**
	 * Renders one attachment's {@link net.minecraft.item.ItemStack}, constructed fresh from
	 * {@link Attachment#item()}/{@link Attachment#itemCount()} rather than round-tripped over the
	 * network - see {@link Attachment}'s own doc comment on why
	 */
	default void renderAttachedItem(MatrixStack poseStack, Attachment attachment, Entity contextEntity, VertexConsumerProvider bufferSource,
									 int packedLight, int packedOverlay) {
		Item item = Registries.ITEM.get(attachment.item());
		ItemStack stack = new ItemStack(item, attachment.itemCount());

		if (attachment.hand() != Attachment.HAND_NONE) {
			boolean right = attachment.hand() == Attachment.HAND_RIGHT;
			ModelTransformationMode mode = right ? ModelTransformationMode.THIRD_PERSON_RIGHT_HAND : ModelTransformationMode.THIRD_PERSON_LEFT_HAND;

			// the same call a player's hand is drawn with (the held item renderer), so the item looks as it does in a player's hand
			if (contextEntity instanceof LivingEntity holder) {
				MinecraftClient.getInstance().getEntityRenderDispatcher().getHeldItemRenderer().renderItem(holder, stack, mode, !right, poseStack, bufferSource, packedLight);

				return;
			}
		}

		if (contextEntity instanceof LivingEntity livingEntity) {
			MinecraftClient.getInstance().getItemRenderer().renderItem(livingEntity, stack, ModelTransformationMode.NONE, false,
					poseStack, bufferSource, livingEntity.getWorld(), packedLight, packedOverlay, livingEntity.getId());
		}
		else {
			MinecraftClient.getInstance().getItemRenderer().renderItem(stack, ModelTransformationMode.NONE, packedLight, packedOverlay,
					poseStack, bufferSource, contextEntity.getWorld(), contextEntity.getId());
		}
	}

	/**
	 * Renders one attachment's {@link BlockState} (always the block's default state - see
	 * {@link Attachment}'s own doc comment), scaled down to item-sized the same way
	 * {@code BlockAndItemStrutLayer#renderBlockForBone} already does
	 */
	default void renderAttachedBlock(MatrixStack poseStack, Attachment attachment, VertexConsumerProvider bufferSource, int packedLight) {
		Block block = Registries.BLOCK.get(attachment.block());
		BlockState state = block.getDefaultState();

		poseStack.push();
		poseStack.translate(-0.25f, -0.25f, -0.25f);
		poseStack.scale(0.5f, 0.5f, 0.5f);
		MinecraftClient.getInstance().getBlockRenderManager().renderBlockAsEntity(state, poseStack, bufferSource, packedLight, OverlayTexture.DEFAULT_UV);
		poseStack.pop();
	}

	/**
	 * Call after all other rendering work has taken place, including reverting the {@link MatrixStack}'s state
	 * <p>
	 * This method is <u>not</u> called in {@code re-render}
	 */
	default void renderFinal(MatrixStack poseStack, T animatable, BakedStrutModel model, VertexConsumerProvider bufferSource, @Nullable VertexConsumer buffer, float partialTick, int packedLight,
							 int packedOverlay, int colour) {}

	/**
	 * Called after all render operations are completed and the render pass is considered functionally complete.
	 * <p>
	 * Use this method to clean up any leftover persistent objects stored during rendering or any other post-render maintenance tasks as required
	 */
	default void doPostRenderCleanup() {}

	/**
	 * Refreshes {@code bone}'s tracked model/local/world-space matrices (see
	 * {@link GroupBoneHandle#isTrackingMatrices()}) for this frame's pose, if anything is actually
	 * tracking it - a no-op otherwise, and a no-op by default: only the renderer subtypes that
	 * expose bone matrix tracking at all override this (the entity/block/item/armor/object/
	 * replaced-entity renderers; the {@code Dynamic*} subclasses inherit their parent's override
	 * rather than duplicating it)
	 * <p>
	 * Called once per bone from {@code #renderRecursively}'s default implementation, in place of
	 * what every override of that method used to compute by hand: pushing this bone's own transform
	 * onto the {@link MatrixStack} and inverting the entity's own base matrix back out of the
	 * result to recover the bone's model-space transform alone. That inversion reduces,
	 * algebraically, to exactly the bone's own model-space transform - the entity-level component
	 * cancels regardless of what it actually contains - which {@link PosePalette#computeBone}
	 * already produces directly from the current pose, with no {@code MatrixStack} involved at all.
	 * <p>
	 * <b>Fixes a real bug found while collapsing this</b>: three of the six previous overrides
	 * (armor/item/object) computed this snapshot <em>before</em> pushing the bone's own transform -
	 * capturing the parent's model-space matrix (or identity, for a top-level bone) rather than the
	 * bone's own. Nothing in this repository ever read the result, so nothing could have depended on
	 * the bug; every renderer now agrees on "this bone's own model-space transform," which is what
	 * {@link GroupBoneHandle#getModelPosition()}'s own contract already implies
	 */
	default void trackBoneMatrices(T animatable, GroupBoneHandle bone) {}

	/**
	 * Refreshes the tracked matrices of everything under {@code bone}, without drawing any of it
	 */
	default void trackDescendants(T animatable, GroupBoneHandle bone) {
		for (GroupBoneHandle child : bone.getChildBones()) {
			trackBoneMatrices(animatable, child);
			trackDescendants(animatable, child);
		}
	}

	/**
	 * Refreshes the tracked matrices of every bone of the model for this frame's pose, without drawing: what is done when nothing is drawn (an invisible
	 * entity), so the position of a bone is still current, as it is for a bone that is hidden
	 */
	default void trackAllBones(T animatable, BakedStrutModel model) {
		for (GroupBoneHandle handle : getModelInstanceState(animatable).handlesFor(model))
			trackBoneMatrices(animatable, handle);
	}

	/**
	 * Create and fire the relevant {@code Pre-Render} event hook for this renderer.<br>
	 * @return Whether the renderer should proceed based on the cancellation state of the event
	 */
	boolean firePreRenderEvent(MatrixStack poseStack, BakedStrutModel model, VertexConsumerProvider bufferSource, float partialTick, int packedLight);

	/**
	 * Create and fire the relevant {@code Post-Render} event hook for this renderer
	 */
	void firePostRenderEvent(MatrixStack poseStack, BakedStrutModel model, VertexConsumerProvider bufferSource, float partialTick, int packedLight);
	
    /**
     * Scales the {@link MatrixStack} in preparation for rendering the model, excluding when re-rendering the model as part of {@code #reRender} or an external render call
	 * <p>
     * Override and call super with modified scale values as needed to further modify the scale of the model (E.G. child entities)
     */
	default void scaleModelForRender(float widthScale, float heightScale, MatrixStack poseStack, T animatable, BakedStrutModel model, boolean isReRender, float partialTick, int packedLight, int packedOverlay) {
		if (!isReRender && (widthScale != 1 || heightScale != 1))
			poseStack.scale(widthScale, heightScale, widthScale);
	}

	/**
	 * Update the current frame of a {@link AnimatableTexture potentially animated} texture used by this StrutRenderer
	 * <p>
	 * This should only be called immediately prior to rendering
	 *
	 * @see AnimatableTexture#setAndUpdate
	 */
	void updateAnimatedTextureFrame(T animatable);
}
