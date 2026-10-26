package com.sandymandy.strut.model;

import java.util.Collection;
import java.util.List;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.util.Identifier;
import net.minecraft.entity.Entity;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import com.sandymandy.strut.StrutConstants;
import com.sandymandy.strut.animatable.StrutAnimatable;
import com.sandymandy.strut.animatable.StrutReplacedEntity;
import com.sandymandy.strut.animation.AnimatableManager;
import com.sandymandy.strut.animation.Animation;
import com.sandymandy.strut.animation.AnimationLod;
import com.sandymandy.strut.animation.AnimationProcessor;
import com.sandymandy.strut.animation.AnimationState;
import com.sandymandy.strut.cache.StrutCache;
import com.sandymandy.strut.cache.object.BakedStrutModel;
import com.sandymandy.strut.cache.object.GroupBoneDefinition;
import com.sandymandy.strut.cache.object.GroupBoneHandle;
import com.sandymandy.strut.animation.state.ModelInstance;
import com.sandymandy.strut.constant.DataTickets;
import com.sandymandy.strut.constant.dataticket.DataTicket;
import com.sandymandy.strut.loading.object.BakedAnimations;
import com.sandymandy.strut.perf.RenderPerfStats;
import com.sandymandy.strut.renderer.StrutRenderer;
import com.sandymandy.strut.util.RenderUtil;

import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.DoubleSupplier;

/**
 * Base class for all code-based model objects
 * <p>
 * All models registered to a {@link StrutRenderer} must be an instance of this or one of its subclasses
 *
 * @see "Strut documentation: doc/models.html"
 */
public abstract class StrutModel<T extends StrutAnimatable> {
	private final AnimationProcessor<T> processor = new AnimationProcessor<>(this);

	/**
	 * A model whose files follow from an id: {@code <ns>:strut/models/entity/<path>.strutmodel}, {@code <ns>:textures/entity/<path>.png} and
	 * {@code <ns>:strut/animations/entity/<path>.strutanim}. Pass an entity type's registry id and nothing else is needed
	 * <p>
	 * Change one part with {@code withAltModel}, {@code withAltTexture} or {@code withAltAnimations}
	 */
	public static <T extends StrutAnimatable> DefaultedEntityStrutModel<T> entity(Identifier id) {
		return new DefaultedEntityStrutModel<>(id);
	}

	/**
	 * Like {@link #entity} for an item: {@code <ns>:strut/models/item/<path>.strutmodel} and so on
	 */
	public static <T extends StrutAnimatable> DefaultedItemStrutModel<T> item(Identifier id) {
		return new DefaultedItemStrutModel<>(id);
	}

	/**
	 * Like {@link #entity} for a block entity: {@code <ns>:strut/models/block/<path>.strutmodel} and so on
	 */
	public static <T extends StrutAnimatable> DefaultedBlockStrutModel<T> block(Identifier id) {
		return new DefaultedBlockStrutModel<>(id);
	}

	/**
	 * Like {@link #entity} for worn armor: {@code <ns>:strut/models/armor/<path>.strutmodel} and so on
	 */
	public static <T extends StrutAnimatable> DefaultedArmorStrutModel<T> armor(Identifier id) {
		return new DefaultedArmorStrutModel<>(id);
	}

	/**
	 * A model built from three ids or functions of the animatable, without a subclass. See {@link StrutModelBuilder}
	 */
	public static <T extends StrutAnimatable> StrutModelBuilder<T> builder() {
		return new StrutModelBuilder<>();
	}

	private BakedStrutModel currentModel = null;

	/**
	 * Returns the resource path for the {@link BakedStrutModel} (model json file) to render based on the provided animatable
	 */
	public Identifier getModelResource(T animatable, @Nullable StrutRenderer<T> renderer) {
		return getModelResource(animatable);
	}

	/**
	 * Returns the resource path for the {@link BakedStrutModel} (model json file) to render based on the provided animatable
	 */
	@Deprecated
	public abstract Identifier getModelResource(T animatable);

	/**
	 * Returns the resource path for the texture file to render based on the provided animatable
	 */
	public Identifier getTextureResource(T animatable, @Nullable StrutRenderer<T> renderer) {
		return getTextureResource(animatable);
	}

	/**
	 * Returns the resource path for the texture file to render based on the provided animatable
	 */
	@Deprecated
	public abstract Identifier getTextureResource(T animatable);

	/**
	 * Returns the resource path for the {@link BakedAnimations} (animation json file) to use for animations based on the provided animatable
	 */
	public abstract Identifier getAnimationResource(T animatable);

	/**
	 * Returns the resource path for the {@link BakedAnimations} (animation json file) fallback locations in the event
	 * your animation isn't present in the {@link #getAnimationResource(StrutAnimatable) primary resource}.
	 * <p>
	 * Should <b><u>NOT</u></b> be used as the primary animation resource path, and in general shouldn't be used
	 * at all unless you know what you are doing
	 */
	public Identifier[] getAnimationResourceFallbacks(T animatable) {
		return new Identifier[0];
	}

	/**
	 * Override this and return true if Strut should crash when attempting to animate the model, but fails to find a bone
	 * <p>
	 * By default, Strut will just gracefully ignore a missing bone, which might cause oddities with incorrect models or mismatching variables
	 */
	public boolean crashIfBoneMissing() {
		return false;
	}

	/**
	 * Gets the default render type for this animatable, to be selected by default by the renderer using it
	 *
	 * @return Return the RenderLayer to use, or null to prevent the model rendering. Returning null will not prevent animation functions taking place
	 */
	@Nullable
	public RenderLayer getRenderType(T animatable, Identifier texture) {
		return RenderLayer.getEntityCutoutNoCull(texture);
	}

	/**
	 * How the GPU path draws this animatable's geometry by default. Cutout unless overridden; {@code getRenderType} above is what the
	 * CPU path uses and is not consulted by the GPU path
	 */
	public RenderMode renderMode(T animatable) {
		return RenderMode.CUTOUT;
	}

	/**
	 * The polygon offset the GPU path applies to translucent <em>bones</em> and <em>overlays</em>, so they do not z-fight the geometry they are drawn
	 * over (the Imp's blush against her snout, a skin overlay against the skin). Tunable per model without an engine release. It is not applied to
	 * a translucent model's own draw ({@link #renderMode}): there it only pulled quads at different angles apart and showed as seams.
	 */
	public DepthBias translucentDepthBias(T animatable) {
		return DepthBias.DEFAULT;
	}

	/**
	 * How this animatable is shaded by the way its surfaces face: {@link ShadingMode#SOFT} by default, {@link ShadingMode#VANILLA} for the game's own entity
	 * shading, {@link ShadingMode#TOON} for four tones with a rim, {@link ShadingMode#FLAT} for no shading by direction at all. The world's own light (block and sky light) darkens it the same in every mode. Under a shader pack the
	 * pack lights the model and this has no effect
	 */
	public ShadingMode shadingMode(T animatable) {
		return ShadingMode.DEFAULT;
	}

	/**
	 * Whether an overlay (a bone drawn again in another texture, {@code overrides.setOverlay}) writes the depth of its own nearest surface before it is blended,
	 * as a translucent bone does. True by default. An overlay is meant to lie on its bone, which has its own depth; where the bone's own texels are transparent (a
	 * part that is drawn only by its overlay) there is none, so without this its faces blend in mesh order (the inside shows through the front) and the
	 * terrain's clouds and water, drawn later, cover it. False restores the old draw, for an overlay laid over a bone that is drawn opaque beneath it
	 */
	public boolean overlaysWriteDepth(T animatable) {
		return true;
	}

	/**
	 * Whether the GPU path drops back faces when it draws this animatable blended (a translucent model, translucent bones, overlays). False
	 * by default, matching vanilla's translucent entity, which draws every face: a closed cube then shows its inside faces through its
	 * front ones. True removes them, at the cost of a one-sided plane (a hair card, a bow) vanishing when seen from behind.
	 */
	public boolean translucentCullsBackFaces(T animatable) {
		return false;
	}

	/**
	 * Whether a bone with a {@code TEXTURE} override (a player skin on a body bone, say) is drawn with only that texture, on the GPU path. True by
	 * default: the model's own texture is left out of that bone, so a skin's transparent texels (unused corners, an empty second layer) show what is
	 * behind the bone rather than the model's texture beneath. False draws the override on top of the bone drawn with the model's own texture,
	 * which is what it did before. Overlays are separate and always stack on top either way.
	 */
	public boolean textureOverrideReplacesBase(T animatable) {
		return true;
	}

	/**
	 * Get the baked geo model object used for rendering from the given resource path
	 */
	public BakedStrutModel getBakedModel(Identifier location) {
		BakedStrutModel model = StrutCache.getBakedModels().get(location);

		if (model == null) {
			if (!location.getPath().contains("strut/models/"))
				throw StrutConstants.exception(location, "Invalid model resource path provided - Strut models must be placed in assets/<modid>/strut/models/");

			throw StrutConstants.exception(location, "Unable to find model");
		}

		if (model != this.currentModel) {
			this.processor.setActiveModel(model);
			this.currentModel = model;
		}

		return this.currentModel;
	}

	/**
	 * Gets a bone's immutable, shared definition from this model by name
	 *
	 * @param name The name of the bone
	 * @return An {@link Optional} containing the {@link GroupBoneDefinition} if one matches, otherwise an empty Optional
	 */
	public Optional<GroupBoneDefinition> getBone(String name) {
		return Optional.ofNullable(getAnimationProcessor().getBone(name));
	}

	/**
	 * Gets a bone's per-instance handle for the given animatable, resolved by name
	 * <p>
	 * Unlike {@link #getBone(String)}, this actually addresses the given animatable's own pose -
	 * use this (not {@link #getBone(String)}) whenever you intend to read or write a bone's
	 * transform for one specific animatable, such as in {@link #setCustomAnimations}
	 *
	 * @param animatable The animatable whose pose the returned handle addresses
	 * @param instanceId The animatable's instance id
	 * @param name The name of the bone
	 */
	public Optional<GroupBoneHandle> getBone(T animatable, long instanceId, String name) {
		GroupBoneDefinition definition = getAnimationProcessor().getBone(name);

		if (definition == null)
			return Optional.empty();

		ModelInstance instance = animatable.getAnimatableInstanceCache().getManagerForId(instanceId).getModelInstance();

		return Optional.of(instance.handleFor(this.currentModel, definition));
	}

	/**
	 * Gets the loaded {@link Animation} for the given animation {@code name}, if it exists
	 *
	 * @param animatable The {@code StrutAnimatable} instance being referred to
	 * @param name The name of the animation to retrieve
	 * @return The {@code Animation} instance for the provided {@code name}, or null if none match
	 */
	@Nullable
	public Animation getAnimation(T animatable, String name) {
		Identifier location = getAnimationResource(animatable);
		BakedAnimations bakedAnimations = StrutCache.getBakedAnimations().get(location);
		Animation animation = bakedAnimations != null ? bakedAnimations.getAnimation(name) : null;

		if (animation != null)
			return animation;

		for (Identifier fallbackLocation : getAnimationResourceFallbacks(animatable)) {
			bakedAnimations = StrutCache.getBakedAnimations().get(location = fallbackLocation);
			animation = bakedAnimations != null ? bakedAnimations.getAnimation(name) : null;

			if (animation != null)
				return animation;
		}

		if (bakedAnimations == null) {
			if (!location.getPath().contains("strut/animations/"))
				throw StrutConstants.exception(location, "Invalid animation resource path provided - Strut animations must be placed in assets/<modid>/strut/animations/");

			throw StrutConstants.exception(location, "Unable to find animation file.");
		}

		return null;
	}

	/**
	 * Gets the {@link AnimationProcessor} for this model.
	 */
	public AnimationProcessor<T> getAnimationProcessor() {
		return this.processor;
	}

	/**
	 * Add additional {@link DataTicket DataTickets} to the {@link AnimationState} to be handled by your animation handler at render time
	 *
	 * @param animatable The animatable instance currently being animated
	 * @param instanceId The unique instance id of the animatable being animated
	 * @param dataConsumer The DataTicket + data consumer to be added to the AnimationEvent
	 */
	public void addAdditionalStateData(T animatable, long instanceId, BiConsumer<DataTicket<T>, T> dataConsumer) {}

	/**
	 * This method is called once per render-frame for each {@link StrutAnimatable} being rendered
	 * <p>
	 * It is an internal method for automated animation parsing. Use {@link StrutModel#setCustomAnimations(StrutAnimatable, long, AnimationState)} for custom animation work
	 */
	@ApiStatus.Internal
	public void handleAnimations(T animatable, long instanceId, AnimationState<T> animationState, float partialTick) {
		RenderPerfStats.beginAnimation();

		try {
			MinecraftClient mc = MinecraftClient.getInstance();
			AnimatableManager<T> animatableManager = animatable.getAnimatableInstanceCache().getManagerForId(instanceId);
			Double currentTick = animationState.getData(DataTickets.TICK);

			if (currentTick == null)
				currentTick = animatable instanceof Entity entity ? (double)entity.age : RenderUtil.getCurrentTick();

			if (animatableManager.getFirstTickTime() == -1)
				animatableManager.startedAt(currentTick + partialTick);

			double currentFrameTime = animatable instanceof Entity || animatable instanceof StrutReplacedEntity ? currentTick + partialTick : currentTick - animatableManager.getFirstTickTime();
			boolean isReRender = !animatableManager.isFirstTick() && currentFrameTime == animatableManager.getLastUpdateTime();

			if (isReRender && animatableManager.wasLastRendered(instanceId))
				return;

			if (!mc.isPaused() || animatable.shouldPlayAnimsWhileGamePaused()) {
				animatableManager.updatedAt(currentFrameTime);
				animatableManager.advanceAnimTime(animatableManager.getLastUpdateTime());
			}

			double animTime = animatableManager.getAnimTime();

			animationState.animationTick = animTime;
			animatableManager.markRendered(instanceId);
			AnimationProcessor<T> processor = getAnimationProcessor();

			processor.preAnimationSetup(animationState, animTime);

			// A distant instance may skip the
			// expensive controller/bone walk this frame, reusing its last evaluated pose - see
			// AnimationLod's own doc comment for why this is safe. animTime itself keeps advancing
			// every frame above regardless, so the next real evaluation lands at the right time
			if (AnimationLod.shouldEvaluate(animatable, animatableManager)) {
				RenderPerfStats.recordAnimationEvaluated();

				if (processor.hasRegisteredBones())
					processor.tickAnimation(animatable, this, animatableManager, animTime, animationState, crashIfBoneMissing());

				setCustomAnimations(animatable, instanceId, animationState);
				processor.refreshRenderedPose(animatableManager.getModelInstance(), animationState.getPartialTick());
			}
			else {
				RenderPerfStats.recordAnimationSkipped();
			}
		}
		finally {
			RenderPerfStats.endAnimation();
		}
	}

	/**
	 * This method is called once per render-frame for each {@link StrutAnimatable} being rendered
	 * <p>
	 * Override to set custom animations (such as head rotation, etc)
	 *
	 * @param animatable The {@code StrutAnimatable} instance currently being rendered
	 * @param instanceId The instance id of the {@code StrutAnimatable}
	 * @param animationState An {@link AnimationState} instance created to hold animation data for the {@code animatable} for this method call
	 */
	public void setCustomAnimations(T animatable, long instanceId, AnimationState<T> animationState) {}

	/**
	 * Bones (and what is under them) that are never drawn for {@code animatable}: parts of the model that are alternatives, such as the halves of a bed an item always
	 * shows one of. Asked every draw, so return a list that is kept. A bone a server override or the animation shows is still hidden: this is applied last, to what is
	 * drawn. Empty by default
	 */
	public Collection<String> hiddenBones(T animatable) {
		return List.of();
	}

	/**
	 * This method is called once per render-frame for each {@link StrutAnimatable} being rendered
	 * <p>
	 * Use this method to set custom {@link com.sandymandy.strut.loading.math.value.Variable Variable} values via
	 * {@link com.sandymandy.strut.loading.math.MathParser#setVariable(String, DoubleSupplier) MathParser.setVariable}
	 *
	 * @param animationState The AnimationState data for the current render frame
	 * @param animTime The internal tick counter kept by the {@link AnimatableManager manager} for this animatable
	 */
	public void applyMolangQueries(AnimationState<T> animationState, double animTime) {}
}
