package com.sandymandy.strut.animation.state;

import com.sandymandy.strut.cache.object.StrutMesh;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2ObjectMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.jetbrains.annotations.ApiStatus;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;
import com.sandymandy.strut.animation.override.BoneOverride;
import com.sandymandy.strut.animation.override.ClientBoneOverrideState;
import com.sandymandy.strut.animation.override.ClientBoneOverrideStates;
import com.sandymandy.strut.animation.override.OverrideKey;
import com.sandymandy.strut.animation.override.OverrideProperty;
import com.sandymandy.strut.animation.override.OverrideValue;
import com.sandymandy.strut.cache.object.ArmatureBoneHandle;
import com.sandymandy.strut.cache.object.BakedStrutModel;
import com.sandymandy.strut.cache.object.GroupBoneDefinition;
import com.sandymandy.strut.cache.object.GroupBoneHandle;
import com.sandymandy.strut.cache.object.StrutArmature;
import com.sandymandy.strut.cache.object.ArmatureBoneDefinition;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * One animatable instance's model-shaped state
 * <p>
 * Owns this instance's three {@link Pose Poses} - {@link #currentPose() current},
 * {@link #previousPose() previous} and {@link #renderedPose() rendered} - and its
 * {@link GroupBoneHandle} tree, the per-instance half of the {@link GroupBoneDefinition}/{@link GroupBoneHandle}
 * split, for group bones. It owns the armature equivalent too: one
 * {@link ArmaturePose} and {@link ArmatureBoneHandle} tree per {@link StrutArmature} the model
 * carries, the same split applied to {@link ArmatureBoneDefinition}. Grows into the full per-instance
 * ownership: controllers, overrides, physics and attachment history all belong here
 * eventually, alongside {@link SkinningState}, which made the same move earlier
 * <p>
 * Owned by the instance's {@link com.sandymandy.strut.animation.AnimatableManager}, so it is
 * created with the instance and released with it
 *
 * <h2>Three poses, plus one resolved view</h2>
 *
 * <p>Only {@code current} is ever animated directly - every {@link GroupBoneHandle} accessor reads and
 * writes it. {@code previous} and {@code rendered} exist for interpolation: once per real
 * animation update, {@link #beginPoseUpdate} snapshots {@code current} into {@code previous}
 * before it's overwritten, and {@link #interpolateRenderedPose} then blends the two into
 * {@code rendered} by partial tick. Render-time code reads {@code rendered}, not {@code current},
 * so what's drawn is always the smoothed value
 * <p>
 * {@link #effectivePose()} is {@code rendered}
 * with any active bone override layered on top, resolved once per frame by
 * {@link #prepareForRender}. It is what actually renders: both the CPU walk
 * ({@code GroupBoneHandle#isHiddenForRender}) and the GPU path ({@code GpuBatchQueue}'s own
 * submission) read this instead of {@code rendered} directly, which is also what fixes a real
 * pre-existing bug the two paths disagreed on - see {@code GroupBoneHandle#isHiddenForRender}'s
 * own doc comment for the details
 *
 * <h2>Rebinding</h2>
 *
 * <p>Rebound whenever the animatable's baked model changes identity, exactly like
 * {@link SkinningState} and for the same reason: a resource reload produces new baked objects at
 * the same resource path, and a stale {@link Pose}/handle tree sized for the old bone count must
 * not be read back against the new one. Handles are allocated eagerly on rebind, one per bone,
 * rather than lazily on first touch - the whole tree is cheap (a handful of references and an int
 * per bone) next to a single mesh's vertex data, and eager allocation means a handle is always
 * ready the moment something asks for it
 */
public final class ModelInstance {
	@Nullable
	private BakedStrutModel boundModel;
	@Nullable
	private Pose current;
	@Nullable
	private Pose previous;
	@Nullable
	private Pose rendered;
	@Nullable
	private Pose effectiveScratch;
	@Nullable
	private boolean[] subtreeHiddenScratch;
	@Nullable
	private Pose effective;
	@Nullable
	private BoneVisuals boneVisualsScratch;
	@Nullable
	private BoneVisuals boneVisuals;
	@Nullable
	private BoneTextureOverrides boneTexturesScratch;
	@Nullable
	private BoneTextureOverrides boneTextures;
	@Nullable
	private BoneRenderLayers boneLayersScratch;
	@Nullable
	private BoneRenderLayers boneLayers;
	@Nullable
	private BoneOverride[] resolvedOverrideScratch;
	@Nullable
	private Pose processedScratch;
	@Nullable
	private Map<Object, Object> processorState;
	@Nullable
	private Reference2ObjectMap<StrutMesh, MeshDeform> meshDeforms;

	/**
	 * Every property that inherits down the bone tree via {@link #resolveInherited} and lands in a
	 * {@link Pose}. {@code CHILDREN_HIDDEN} is deliberately not one of these - see
	 * {@link #resolveEffectivePose()}'s own doc comment for why it stays literal-only
	 */
	private static final OverrideProperty[] POSE_PROPERTIES = {OverrideProperty.HIDDEN, OverrideProperty.POSITION,
			OverrideProperty.ROTATION, OverrideProperty.SCALE};

	/** Every property that inherits down the bone tree via {@link #resolveInherited} and lands in {@link BoneVisuals} */
	private static final OverrideProperty[] VISUAL_PROPERTIES = {OverrideProperty.COLOR, OverrideProperty.UV_OFFSET, OverrideProperty.UV_SIZE};

	/** {@code TEXTURE} alone - lands in {@link BoneTextureOverrides}, not {@link BoneVisuals}, since it drives draw-call grouping rather than per-bone shader data */
	private static final OverrideProperty[] TEXTURE_PROPERTIES = {OverrideProperty.TEXTURE};
	private int entityId;
	private boolean hasEntityId;
	/** The bone overrides read this frame: an entity's, a block entity's, or none. Looked up once by {@link #prepareForRender} */
	@Nullable
	private ClientBoneOverrideState overrideState;
	@Nullable
	private GroupBoneHandle[] handles;
	@Nullable
	private List<GroupBoneHandle> topLevelHandles;
	private final Reference2ObjectMap<StrutArmature, ArmaturePose> armaturePoses = new Reference2ObjectOpenHashMap<>();
	private final Reference2ObjectMap<StrutArmature, ArmatureBoneHandle[]> armatureHandles = new Reference2ObjectOpenHashMap<>();

	/**
	 * This instance's pose for the given model, rebuilding it if the model has changed identity
	 * since the last call
	 */
	public Pose poseFor(BakedStrutModel model) {
		rebindIfNeeded(model);

		return this.current;
	}

	/**
	 * This instance's bone handles for the given model, flat-indexed identically to
	 * {@link BakedStrutModel#flatBones()}, rebuilding them if the model has changed identity since
	 * the last call
	 */
	public GroupBoneHandle[] handlesFor(BakedStrutModel model) {
		rebindIfNeeded(model);

		return this.handles;
	}

	/**
	 * This instance's top-level bone handles for the given model, in the same order as
	 * {@link BakedStrutModel#topLevelBones()}
	 */
	public List<GroupBoneHandle> topLevelHandlesFor(BakedStrutModel model) {
		rebindIfNeeded(model);

		return this.topLevelHandles;
	}

	/**
	 * This instance's handle for the given definition, resolved through the given model
	 */
	public GroupBoneHandle handleFor(BakedStrutModel model, GroupBoneDefinition definition) {
		return handlesFor(model)[definition.getIndex()];
	}

	/**
	 * The {@link BakedStrutModel} this instance is currently bound to, or {@code null} if it has never
	 * rendered - the visibility bounds read this to
	 * find an entity's own model without needing a renderer reference at all, from
	 * {@link com.sandymandy.strut.model.ModelBounds}'s own visibility-box mixin
	 */
	@Nullable
	public BakedStrutModel boundModel() {
		return this.boundModel;
	}

	/**
	 * This instance's animated pose for the given armature, rebuilding this instance's bone and
	 * armature state if the model has changed identity since the last call
	 * <p>
	 * Sized and indexed identically to {@link StrutArmature#bones()} - never called across a
	 * different model's armature, since {@link #armatureHandlesFor} is the only legitimate way to
	 * reach one of an armature's bones and always resolves through the same model
	 */
	public ArmaturePose armaturePoseFor(BakedStrutModel model, StrutArmature armature) {
		rebindIfNeeded(model);

		return this.armaturePoses.get(armature);
	}

	/**
	 * This instance's bone handles for the given armature, flat-indexed identically to
	 * {@link StrutArmature#bones()}, rebuilding them if the model has changed identity since the
	 * last call
	 */
	public ArmatureBoneHandle[] armatureHandlesFor(BakedStrutModel model, StrutArmature armature) {
		rebindIfNeeded(model);

		return this.armatureHandles.get(armature);
	}

	/**
	 * This instance's handle for the given armature bone definition, resolved through the given
	 * armature
	 */
	public ArmatureBoneHandle armatureHandleFor(BakedStrutModel model, StrutArmature armature, ArmatureBoneDefinition definition) {
		return armatureHandlesFor(model, armature)[definition.getIndex()];
	}

	/**
	 * The {@link Pose} currently bound to this instance
	 * <p>
	 * Package-private in spirit, public only because {@link GroupBoneHandle} lives in a different
	 * package. Only ever called by a handle, and only ever while already bound to a model, since a
	 * handle is only ever constructed here, mid-rebind, after the {@code Pose} it addresses already
	 * exists
	 */
	@ApiStatus.Internal
	public Pose currentPose() {
		return this.current;
	}

	/**
	 * The pose actually used to draw geometry and populate tracked bone matrices - a blend of
	 * {@link #previousPose()} and {@link #currentPose()} by partial tick, refreshed once per real
	 * animation update by {@link #interpolateRenderedPose}
	 * <p>
	 * Every bone-driven render-matrix helper in {@link com.sandymandy.strut.util.RenderUtil} reads
	 * this pose rather than {@link #currentPose()} directly, so what's drawn is always the smoothed
	 * value rather than whatever {@code current} last jumped to
	 */
	public Pose renderedPose() {
		return this.rendered;
	}

	/**
	 * Binds this instance to the entity whose bone overrides it should resolve against, and resolves
	 * this frame's {@link #effectivePose()} once
	 * Call once per render pass, before anything asks for
	 * {@link #effectivePose()} - {@code StrutEntityRenderer}/{@code StrutReplacedEntityRenderer}'s own
	 * {@code preRender} is where this happens, always ahead of the per-bone walk or the GPU submit
	 * it precedes
	 * <p>
	 * Resolving once here rather than inside {@link #effectivePose()} itself is what keeps a dense
	 * model's 250-bone walk from redoing the same two-map lookup and union 250 times over - every
	 * bone this frame reads the one already-resolved result
	 */
	@ApiStatus.Internal
	public void prepareForRender(int entityId) {
		prepareForRender(entityId, true);
	}

	/**
	 * {@link #prepareForRender(int)}, saying whether the pose processors (physics, liquid emission, collider publishing) run. A draw that is not the world's (the
	 * inventory's doll) must not run them: they step on the time and the movement of each frame, and a doll turned toward the mouse and drawn at another time
	 * in the frame would move the springs of the body the world draws
	 */
	@ApiStatus.Internal
	public void prepareForRender(int entityId, boolean processors) {
		this.entityId = entityId;
		this.hasEntityId = true;
		resolve(ClientBoneOverrideStates.get(entityId), processors);
	}

	/**
	 * {@link #prepareForRender(int)} for a block entity: its overrides are the ones kept for {@code pos}, see
	 * {@link ClientBoneOverrideStates#getBlock}
	 */
	@ApiStatus.Internal
	public void prepareForRender(BlockPos pos) {
		this.entityId = -1;
		this.hasEntityId = false;
		resolve(ClientBoneOverrideStates.getBlock(pos), false);
	}

	/** @param processors Whether the pose processors run: they are written for entities, so a block entity is not given them */
	private void resolve(@Nullable ClientBoneOverrideState overrides, boolean processors) {
		this.overrideState = overrides;
		Pose resolved = resolveEffectivePose();
		this.effective = processors ? runPoseProcessors(resolved) : resolved;
		this.boneVisuals = resolveBoneVisuals();
		this.boneTextures = resolveBoneTextures();
		this.boneLayers = resolveBoneLayers();
	}

	/**
	 * The pose actually posing this frame's geometry and bone matrices - {@link #renderedPose()}
	 * with any active bone override layered on top, resolved by {@link #prepareForRender}
	 * <p>
	 * Every render-time consumer of hidden/position/rotation/scale state should read this instead of
	 * {@link #renderedPose()} directly - {@code GroupBoneHandle#isHiddenForRender}/
	 * {@code isHidingChildrenForRender} and {@code GpuBatchQueue}'s own submission both do. Falls
	 * back to {@link #renderedPose()} itself (no copy) if {@link #prepareForRender} was never called
	 * for this instance - true for every non-entity animatable (items, blocks, singletons), which
	 * have no bone overrides to resolve in the first place
	 */
	public Pose effectivePose() {
		if (this.effective != null)
			return this.effective;

		Pose base = renderedPose();

		// an animatable that is not prepared (an item, armour, an object) has no overrides, but a bone it hid with its children is hidden down to every descendant here,
		// since what draws reads only each bone's own bit
		if (base == null || this.boundModel == null || !base.hasAnyChildrenHidden())
			return base;

		if (this.unpreparedVersion != this.poseVersion || this.unprepared == null) {
			this.unprepared = resolveEffectivePose();
			this.unpreparedVersion = this.poseVersion;
		}

		return this.unprepared;
	}

	/** Counts the times {@link #rendered} was written, so what is worked out from it is worked out again only when it has changed */
	private long poseVersion;
	private long unpreparedVersion = -1;
	@Nullable
	private Pose unprepared;

	/**
	 * This frame's resolved per-bone {@code COLOR}/{@code UV_OFFSET} overrides, resolved by
	 * {@link #prepareForRender} alongside {@link #effectivePose()} - {@code null} when no such
	 * override is active for this instance at all, which is the common case and deliberately not
	 * represented by an all-neutral instance of this class (see {@link BoneVisuals}'s own doc
	 * comment). {@code GroupBoneHandle#getColorTint}/{@code getUOffset}/{@code getVOffset} read this
	 * for the CPU path; {@code GpuBatchQueue}'s own submission reads it to decide whether a group
	 * needs its per-bone visual SSBO built at all this frame
	 */
	@Nullable
	public BoneVisuals boneVisuals() {
		return this.boneVisuals;
	}

	/**
	 * Rebuilds {@link #boneVisuals} from whatever this entity's {@link ClientBoneOverrideState}
	 * currently resolves for {@code COLOR}/{@code UV_OFFSET}, inherited down the bone tree exactly
	 * like the pose properties {@link #resolveEffectivePose} resolves (see {@link #resolveInherited}'s
	 * own doc comment) - {@code null} when neither is active anywhere (no bound entity id, no
	 * mirrored override state, or nothing in it affecting either property), which is the common case
	 * for an entity that has never called {@code entity.overrides().setColor}/{@code setUvOffset}
	 */
	@Nullable
	private BoneVisuals resolveBoneVisuals() {
		ClientBoneOverrideState overrides = this.overrideState;

		if (overrides == null || this.boundModel == null)
			return null;

		if (overrides == null)
			return null;

		Set<OverrideKey> activeKeys = overrides.activeKeys();
		boolean anyVisualOverride = false;

		for (OverrideKey key : activeKeys) {
			if (affectsVisuals(key.property())) {
				anyVisualOverride = true;

				break;
			}
		}

		if (!anyVisualOverride)
			return null;

		GroupBoneDefinition[] flatBones = this.boundModel.flatBones();
		BoneVisuals visuals = boneVisualsScratch(flatBones.length);

		visuals.reset();

		for (OverrideProperty property : VISUAL_PROPERTIES) {
			resolveInherited(overrides, activeKeys, property, flatBones,
					(index, value) -> applyToVisuals(visuals, index, property, value, this.boundModel));
		}

		return visuals;
	}

	private static boolean affectsVisuals(OverrideProperty property) {
		return switch (property) {
			case COLOR, UV_OFFSET, UV_SIZE -> true;
			case HIDDEN, CHILDREN_HIDDEN, POSITION, ROTATION, SCALE, TEXTURE, TRANSLUCENT, OVERLAY_0, OVERLAY_1, OVERLAY_2, OVERLAY_3 -> false;
		};
	}

	private static void applyToVisuals(BoneVisuals visuals, int index, OverrideProperty property, OverrideValue value, BakedStrutModel model) {
		switch (property) {
			case COLOR -> visuals.setColor(index, ((OverrideValue.ColorValue)value).argb());
			case UV_OFFSET -> {
				OverrideValue.Vec2Value vec = (OverrideValue.Vec2Value)value;

				visuals.setUvOffset(index, vec.x(), vec.y());
			}
			case UV_SIZE -> {
				OverrideValue.Vec2Value vec = (OverrideValue.Vec2Value)value;

				// the model's own texture size over the override's: a 0 or negative size is ignored rather than dividing by it
				if (vec.x() > 0 && vec.y() > 0)
					visuals.setUvScale(index, (float)model.properties().textureWidth() / vec.x(), (float)model.properties().textureHeight() / vec.y());
			}
			default -> {}
		}
	}

	private BoneVisuals boneVisualsScratch(int boneCount) {
		if (this.boneVisualsScratch == null || this.boneVisualsScratch.boneCount() < boneCount)
			this.boneVisualsScratch = new BoneVisuals(boneCount);

		return this.boneVisualsScratch;
	}

	/**
	 * This frame's resolved per-bone {@code TEXTURE} overrides, resolved by {@link #prepareForRender}
	 * alongside {@link #effectivePose()}/{@link #boneVisuals()} - {@code null} when no such override
	 * is active for this instance at all, the common case for an entity that has never called
	 * {@code entity.overrides().setTexture}. Only the texture override uses it
	 * - deliberately shipped last, since resolving it is cheap but <em>drawing</em> it costs an extra
	 * {@code glDrawElementsInstanced} call per distinct per-bone texture ({@code GpuBatchQueue}'s own
	 * doc comment has the details), unlike every other visual property this class resolves
	 */
	@Nullable
	public BoneTextureOverrides boneTextures() {
		return this.boneTextures;
	}

	/**
	 * Rebuilds {@link #boneTextures} from whatever this entity's {@link ClientBoneOverrideState}
	 * currently resolves for {@code TEXTURE}, inherited down the bone tree exactly like every other
	 * property {@link #resolveInherited} handles
	 */
	@Nullable
	private BoneTextureOverrides resolveBoneTextures() {
		ClientBoneOverrideState overrides = this.overrideState;

		if (overrides == null || this.boundModel == null)
			return null;

		if (overrides == null)
			return null;

		Set<OverrideKey> activeKeys = overrides.activeKeys();
		boolean anyTextureOverride = false;

		for (OverrideKey key : activeKeys) {
			if (key.property() == OverrideProperty.TEXTURE) {
				anyTextureOverride = true;

				break;
			}
		}

		if (!anyTextureOverride)
			return null;

		GroupBoneDefinition[] flatBones = this.boundModel.flatBones();
		BoneTextureOverrides textures = boneTexturesScratch();

		textures.reset();

		for (OverrideProperty property : TEXTURE_PROPERTIES) {
			resolveInherited(overrides, activeKeys, property, flatBones,
					(index, value) -> textures.set(index, ((OverrideValue.TextureValue)value).texture()));
		}

		return textures;
	}

	/**
	 * This frame's resolved per-bone {@code TRANSLUCENT} flags and {@code OVERLAY_n} textures, or null when none is active - the
	 * common case. The renderer reads it to decide which bones go in the blended
	 * pass and which get a second draw
	 */
	@Nullable
	public BoneRenderLayers boneLayers() {
		return this.boneLayers;
	}

	/**
	 * Rebuilds {@link #boneLayers} from this entity's resolved {@code TRANSLUCENT} and {@code OVERLAY_n} overrides, inherited down the
	 * bone tree like {@code TEXTURE} (an override on a bone means it and everything under it, unless a closer one says otherwise)
	 */
	@Nullable
	private BoneRenderLayers resolveBoneLayers() {
		ClientBoneOverrideState overrides = this.overrideState;

		if (overrides == null || this.boundModel == null)
			return null;

		if (overrides == null)
			return null;

		Set<OverrideKey> activeKeys = overrides.activeKeys();
		boolean any = false;

		for (OverrideKey key : activeKeys) {
			if (key.property() == OverrideProperty.TRANSLUCENT || key.property().overlayLayer() >= 0) {
				any = true;

				break;
			}
		}

		if (!any)
			return null;

		GroupBoneDefinition[] flatBones = this.boundModel.flatBones();

		if (this.boneLayersScratch == null)
			this.boneLayersScratch = new BoneRenderLayers();

		BoneRenderLayers layers = this.boneLayersScratch;

		layers.reset();

		resolveInherited(overrides, activeKeys, OverrideProperty.TRANSLUCENT, flatBones,
				(index, value) -> layers.setTranslucent(index, ((OverrideValue.BoolValue)value).value()));

		for (int layer = 0; layer < OverrideProperty.MAX_OVERLAYS; layer++) {
			int layerIndex = layer;

			resolveInherited(overrides, activeKeys, OverrideProperty.overlay(layer), flatBones,
					(index, value) -> layers.setOverlay(index, layerIndex, ((OverrideValue.TextureValue)value).texture()));
		}

		return layers;
	}

	private BoneTextureOverrides boneTexturesScratch() {
		if (this.boneTexturesScratch == null)
			this.boneTexturesScratch = new BoneTextureOverrides();

		return this.boneTexturesScratch;
	}

	/**
	 * Rebuilds {@link #effective} from {@link #renderedPose()} plus whatever this entity's
	 * {@link ClientBoneOverrideState} currently resolves for each actively-overridden bone, then
	 * propagates {@code CHILDREN_HIDDEN} down every affected ancestor chain - returns
	 * {@link #renderedPose()} itself, uncopied, only when neither is needed at all: no pose-affecting
	 * override is active (or no entity id is bound to look one up against, true for every non-entity
	 * animatable) <em>and</em> the base pose itself has no {@code CHILDREN_HIDDEN} bit set anywhere
	 * <p>
	 * Only {@link OverrideProperty#HIDDEN}/{@code CHILDREN_HIDDEN}/{@code POSITION}/{@code ROTATION}/
	 * {@code SCALE} affect a {@link Pose} at all - {@code COLOR}/{@code UV_OFFSET} resolve into the
	 * GPU visual buffer instead and {@code TEXTURE} into a group key, so
	 * both are skipped here rather than silently ignored somewhere less obvious
	 * <p>
	 * {@link #POSE_PROPERTIES} - everything except {@code CHILDREN_HIDDEN} - inherits down the bone
	 * tree via {@link #resolveInherited}: a bone with no override of its own inherits its nearest
	 * ancestor's, and a bone with its own always wins outright. {@code CHILDREN_HIDDEN} deliberately
	 * does not: it stays literal-only (applied only to the exact bone it was set on, via
	 * {@link #applyLiteral}), because its whole purpose is the one case inheritance itself cannot
	 * express - "hide every descendant unconditionally, with no way for any of them to opt back out."
	 * Letting it inherit like everything else would mean a descendant's own {@code HIDDEN=false}
	 * override could cancel it, which defeats the property's entire reason to exist as distinct from
	 * plain (now-inheriting) {@code HIDDEN}.
	 * <p>
	 * <b>The propagation pass below exists because the GPU path has no tree to walk.</b> The CPU walk
	 * (`StrutRenderer#renderChildBones`) stops recursing the instant it meets a
	 * {@code CHILDREN_HIDDEN} bone, so nothing under it is ever visited, regardless of what each
	 * descendant's own {@code HIDDEN} bit says. {@code GpuBatchQueue} has no recursion at all - every
	 * bone's matrix is computed independently, so it can only ever ask "is <em>this</em> bone
	 * hidden?" Folding {@code CHILDREN_HIDDEN} into every affected descendant's own effective
	 * {@code HIDDEN} bit here, once, is what lets that flat per-bone check produce the same answer
	 * the CPU recursion already does
	 */
	private Pose resolveEffectivePose() {
		Pose base = renderedPose();

		if (base == null || this.boundModel == null)
			return base;

		ClientBoneOverrideState overrides = this.overrideState;
		Set<OverrideKey> activeKeys = overrides != null ? overrides.activeKeys() : Set.of();
		boolean anyPoseAffectingOverride = false;

		for (OverrideKey key : activeKeys) {
			if (affectsPose(key.property())) {
				anyPoseAffectingOverride = true;

				break;
			}
		}

		if (!anyPoseAffectingOverride && !base.hasAnyChildrenHidden())
			return base;

		Pose scratch = effectiveScratch();

		scratch.copyFrom(base);

		if (overrides != null) {
			GroupBoneDefinition[] flatBones = this.boundModel.flatBones();

			for (OverrideProperty property : POSE_PROPERTIES) {
				resolveInherited(overrides, activeKeys, property, flatBones,
						(index, value) -> applyToPose(scratch, index, property, value));
			}

			applyLiteral(overrides, activeKeys, OverrideProperty.CHILDREN_HIDDEN, scratch);
		}

		propagateChildrenHidden(scratch);

		return scratch;
	}

	/**
	 * Applies {@code property}'s override only to the exact bone it was set on - no inheritance, the
	 * behaviour every pose property had before {@link #resolveInherited} generalized the rest. Used
	 * solely for {@code CHILDREN_HIDDEN}; see {@link #resolveEffectivePose()}'s own doc comment for
	 * why that one property stays this way deliberately rather than joining {@link #POSE_PROPERTIES}
	 */
	private void applyLiteral(ClientBoneOverrideState overrides, Set<OverrideKey> activeKeys, OverrideProperty property, Pose scratch) {
		for (OverrideKey key : activeKeys) {
			if (key.property() != property)
				continue;

			BoneOverride override = overrides.resolve(key.bone(), property);

			if (override == null)
				continue;

			Optional<GroupBoneDefinition> definition = this.boundModel.getBone(key.bone());

			if (definition.isEmpty())
				continue;

			applyToPose(scratch, definition.get().getIndex(), property, override.value());
		}
	}

	/**
	 * Resolves {@code property}'s effective value for every bone in {@code flatBones}, in one flat
	 * parent-before-child pass, applying it via {@code applier} wherever a value is found - a bone
	 * with its own override for {@code property} always wins outright ("child first"); a bone with
	 * none inherits its nearest ancestor's resolved value, all the way down through however many
	 * un-overridden bones sit in between. The same shape {@code propagateChildrenHidden} already
	 * proved for {@code CHILDREN_HIDDEN} specifically, generalized here to every property - a
	 * correction found in live testing, the same day as the {@code CHILDREN_HIDDEN}/{@code shouldNeverRender}
	 * GPU-parity fixes: the exact same "the named bone owns no geometry of its own, every visible
	 * part sits on a descendant" gap that motivated coupling {@code CHILDREN_HIDDEN} onto
	 * {@code HIDDEN} turned out to apply to every override property, not just visibility - setting
	 * {@code COLOR} on a pivot bone tinted nothing, for the identical reason hiding one used to hide
	 * nothing, since a caller expects "override this bone" to mean "override it and everything under
	 * it" by default, with a closer override still free to opt back out
	 */
	private void resolveInherited(ClientBoneOverrideState overrides, Set<OverrideKey> activeKeys, OverrideProperty property,
								   GroupBoneDefinition[] flatBones, InheritedApplier applier) {
		BoneOverride[] resolved = resolvedOverrideScratch(flatBones.length);

		Arrays.fill(resolved, 0, flatBones.length, null);

		for (OverrideKey key : activeKeys) {
			if (key.property() != property)
				continue;

			Optional<GroupBoneDefinition> definition = this.boundModel.getBone(key.bone());

			if (definition.isEmpty())
				continue;

			BoneOverride own = overrides.resolve(key.bone(), property);

			if (own != null)
				resolved[definition.get().getIndex()] = own;
		}

		for (int i = 0; i < flatBones.length; i++) {
			GroupBoneDefinition parent = flatBones[i].getParent();

			if (resolved[i] == null && parent != null)
				resolved[i] = resolved[parent.getIndex()];

			if (resolved[i] != null)
				applier.apply(i, resolved[i].value());
		}
	}

	@FunctionalInterface
	private interface InheritedApplier {
		void apply(int boneIndex, OverrideValue value);
	}

	/**
	 * Reused across every {@link #resolveInherited} call this frame (one per active property) -
	 * cleared to {@code null} at the top of each call rather than between calls, since a stale slot
	 * left over from a previous property's pass must never leak into the next one's own resolution
	 */
	private BoneOverride[] resolvedOverrideScratch(int boneCount) {
		if (this.resolvedOverrideScratch == null || this.resolvedOverrideScratch.length < boneCount)
			this.resolvedOverrideScratch = new BoneOverride[boneCount];

		return this.resolvedOverrideScratch;
	}

	/**
	 * Folds {@code CHILDREN_HIDDEN} down into every affected descendant's own effective
	 * {@code HIDDEN} bit, in one flat parent-before-child pass - see {@link #resolveEffectivePose()}'s
	 * own doc comment for why this needs to exist as a separate pass at all. A bone's own
	 * {@code HIDDEN} bit is left alone unless suppression from above applies; suppression itself
	 * never clears once entered for a subtree (checked via {@code subtreeHidden[parent]} as well as
	 * the parent's own {@code CHILDREN_HIDDEN} bit), so it correctly reaches grandchildren and
	 * beyond, not just direct children
	 */
	private void propagateChildrenHidden(Pose pose) {
		GroupBoneDefinition[] flatBones = this.boundModel.flatBones();
		boolean[] subtreeHidden = subtreeHiddenScratch(flatBones.length);

		for (int i = 0; i < flatBones.length; i++) {
			GroupBoneDefinition parent = flatBones[i].getParent();
			boolean suppressed = parent != null && (subtreeHidden[parent.getIndex()] || pose.isChildrenHidden(parent.getIndex()));

			subtreeHidden[i] = suppressed;

			if (suppressed)
				pose.setHidden(i, true);
		}
	}

	private static boolean affectsPose(OverrideProperty property) {
		return switch (property) {
			case HIDDEN, CHILDREN_HIDDEN, POSITION, ROTATION, SCALE -> true;
			case COLOR, UV_OFFSET, UV_SIZE, TEXTURE, TRANSLUCENT, OVERLAY_0, OVERLAY_1, OVERLAY_2, OVERLAY_3 -> false;
		};
	}

	private static void applyToPose(Pose pose, int index, OverrideProperty property, OverrideValue value) {
		switch (property) {
			case HIDDEN -> pose.setHidden(index, ((OverrideValue.BoolValue)value).value());
			case CHILDREN_HIDDEN -> pose.setChildrenHidden(index, ((OverrideValue.BoolValue)value).value());
			case POSITION -> {
				OverrideValue.Vec3Value vec = (OverrideValue.Vec3Value)value;

				pose.setPosition(index, vec.x(), vec.y(), vec.z());
			}
			case ROTATION -> {
				OverrideValue.Vec3Value vec = (OverrideValue.Vec3Value)value;

				pose.setRotation(index, vec.x(), vec.y(), vec.z());
			}
			case SCALE -> {
				OverrideValue.Vec3Value vec = (OverrideValue.Vec3Value)value;

				pose.setScale(index, vec.x(), vec.y(), vec.z());
			}
			default -> {}
		}
	}

	/**
	 * Runs the registered {@link PoseProcessor}s that accept this instance's model on a copy of {@code resolved}, and returns that copy, or returns
	 * {@code resolved} itself untouched when there is nothing to run (the common case: no processor registered, or none accepting this model)
	 */
	private Pose runPoseProcessors(@Nullable Pose resolved) {
		// offsets are a processor's output for one frame: a frame no processor writes them is drawn without
		if (this.meshDeforms != null)
			this.meshDeforms.clear();

		PoseProcessor[] processors = PoseProcessors.all();

		if (processors.length == 0 || resolved == null || this.boundModel == null)
			return resolved;

		Pose target = null;

		for (PoseProcessor processor : processors) {
			if (!processor.appliesTo(this.boundModel))
				continue;

			if (target == null) {
				if (this.processedScratch == null || this.processedScratch.boneCount() != resolved.boneCount())
					this.processedScratch = new Pose(resolved.boneCount());

				target = this.processedScratch;
				target.copyFrom(resolved);
			}

			processor.process(new PoseProcessor.Context(this.boundModel, resolved, target, this.hasEntityId ? this.entityId : -1, this));
		}

		return target != null ? target : resolved;
	}

	/**
	 * Sets how far each corner of {@code mesh} is moved from where the bone puts it, for this frame: {@code offsets} holds x, y and z per poly corner, four
	 * corners per poly in poly order ({@code polys.length * 12} floats), in the mesh's own space (the bone's space its vertices are given in). Cloth and
	 * soft bodies write these from a {@link PoseProcessor}. Cleared at the start of every frame's processing, so a processor that stops writing stops
	 * deforming. The array is kept, not copied: the writer may refill it next frame
	 */
	public void setMeshOffsets(StrutMesh mesh, float[] offsets) {
		setMeshOffsets(mesh, offsets, null);
	}

	/**
	 * {@link #setMeshOffsets(com.sandymandy.strut.cache.object.StrutMesh, float[])} with a normal per corner as well, in the same layout and space: what keeps a
	 * smooth-shaded mesh smooth while it is deformed. Without normals the renderer lights each face flat from its moved corners
	 */
	public void setMeshOffsets(StrutMesh mesh, float[] offsets, @Nullable float[] normals) {
		setMeshDeform(mesh, new CornerDeform(offsets, normals));
	}

	/**
	 * Sets how {@code mesh} is moved this frame in whichever shape the writer has it in (see {@link MeshDeform}: a value for each welded particle, one rigid
	 * motion, or a value for each corner as {@link #setMeshOffsets} gives). Cleared at the start of every frame's processing like the offsets are
	 */
	public void setMeshDeform(StrutMesh mesh, MeshDeform deform) {
		if (this.meshDeforms == null)
			this.meshDeforms = new Reference2ObjectOpenHashMap<>();

		this.meshDeforms.put(mesh, deform);
	}

	/** How {@code mesh} is moved this frame, in the shape its writer gave, or null when it is drawn as the bone and skin place it */
	@Nullable
	public MeshDeform meshDeform(StrutMesh mesh) {
		return this.meshDeforms == null || this.meshDeforms.isEmpty() ? null : this.meshDeforms.get(mesh);
	}

	/** The corner normals {@link #setMeshOffsets} gave {@code mesh} this frame, or null to light it flat */
	@Nullable
	public float[] meshNormals(StrutMesh mesh) {
		MeshDeform deform = meshDeform(mesh);

		return deform == null ? null : deform.cornerNormals();
	}

	/** The offsets {@link #setMeshOffsets} gave {@code mesh} this frame (a value for each corner, made from the writer's own shape if it gave another), or null when it is drawn as the bone and skin place it */
	@Nullable
	public float[] meshOffsets(StrutMesh mesh) {
		MeshDeform deform = meshDeform(mesh);

		return deform == null ? null : deform.cornerOffsets();
	}

	/** Whether any mesh is deformed this frame: the GPU path reads the deformations into one buffer for the group ({@code MeshDeformPack}); the CPU walk applies them itself */
	public boolean hasMeshOffsets() {
		return this.meshDeforms != null && !this.meshDeforms.isEmpty();
	}

	/**
	 * A processor's per-instance state, see {@link PoseProcessor.Context#state}
	 */
	@SuppressWarnings("unchecked")
	<T> T processorState(Object key, Supplier<T> factory) {
		if (this.processorState == null)
			this.processorState = new HashMap<>();

		return (T)this.processorState.computeIfAbsent(key, k -> factory.get());
	}

	@Nullable
	private Pose hiddenScratch;

	/**
	 * {@link #effectivePose()} with the bones named hidden (and what is under them), for the draw of this instance this frame: a copy kept for this instance, so it
	 * is the same until the next call. A name the model does not have is ignored. With no names, {@link #effectivePose()} itself
	 */
	public Pose effectivePoseWithHidden(BakedStrutModel model, Collection<String> bones) {
		Pose base = effectivePose();

		if (bones.isEmpty() || base == null)
			return base;

		if (this.hiddenScratch == null || this.hiddenScratch.boneCount() != base.boneCount())
			this.hiddenScratch = new Pose(base.boneCount());

		this.hiddenScratch.copyFrom(base);

		for (String name : bones) {
			model.getBone(name).ifPresent(definition -> {
				this.hiddenScratch.setHidden(definition.getIndex(), true);
				this.hiddenScratch.setChildrenHidden(definition.getIndex(), true);
			});
		}

		if (this.boundModel != null)
			propagateChildrenHidden(this.hiddenScratch);

		return this.hiddenScratch;
	}

	private Pose effectiveScratch() {
		if (this.effectiveScratch == null)
			this.effectiveScratch = new Pose(this.rendered.boneCount());

		return this.effectiveScratch;
	}

	/**
	 * Reused across frames and across calls within {@link #propagateChildrenHidden} - every index
	 * in {@code [0, boneCount)} is unconditionally overwritten before it is read (parent-before-child
	 * order guarantees a parent's slot is already written by the time a child reads it), so nothing
	 * needs clearing between calls, only growing if a rebind ever needs more of it than the last one did
	 */
	private boolean[] subtreeHiddenScratch(int boneCount) {
		if (this.subtreeHiddenScratch == null || this.subtreeHiddenScratch.length < boneCount)
			this.subtreeHiddenScratch = new boolean[boneCount];

		return this.subtreeHiddenScratch;
	}

	/**
	 * This instance's pose as of one animation update ago - the interpolation source alongside
	 * {@link #currentPose()}
	 */
	@ApiStatus.Internal
	public Pose previousPose() {
		return this.previous;
	}

	/**
	 * Snapshots {@link #currentPose()} into {@link #previousPose()}, ahead of a new animation
	 * update overwriting {@code current} with fresh values
	 * <p>
	 * Called once per real animation update, by {@link com.sandymandy.strut.animation.AnimationProcessor#tickAnimation}
	 * before anything writes a new pose - never on a skipped re-render, since {@code current}
	 * hasn't changed and there is nothing new to snapshot
	 */
	@ApiStatus.Internal
	public void beginPoseUpdate(BakedStrutModel model) {
		rebindIfNeeded(model);

		this.previous.copyFrom(this.current);
	}

	/**
	 * Refreshes {@link #renderedPose()} by interpolating {@link #previousPose()} toward
	 * {@link #currentPose()} by {@code partialTick}
	 * <p>
	 * Called once per real animation update, immediately after {@code current} has been fully
	 * written for that update - see {@link #beginPoseUpdate}
	 */
	@ApiStatus.Internal
	public void interpolateRenderedPose(BakedStrutModel model, float partialTick) {
		rebindIfNeeded(model);

		Pose.interpolate(this.previous, this.current, partialTick, this.rendered);
		this.poseVersion++;
	}

	/**
	 * Collapses this instance's pose history so the next {@link #interpolateRenderedPose} call
	 * produces exactly {@link #currentPose()}, with no blend from whatever {@link #previousPose()}
	 * held before
	 * <p>
	 * Call this after a hard discontinuity a smooth blend would misrepresent as motion - a
	 * teleport, an authoritative snap, or a dimension change (the
	 * interpolation rules, "reset history on teleports, authoritative snaps, model swaps, and
	 * dimension changes"). A model swap resets automatically, since {@link #rebindIfNeeded} always
	 * starts a freshly bound instance's three poses in sync; this method is the explicit
	 * correction operation §5 also calls for, for callers that detect the other kinds of
	 * discontinuity themselves
	 */
	public void resetPoseHistory() {
		if (this.current == null)
			return;

		this.previous.copyFrom(this.current);
		this.rendered.copyFrom(this.current);
		this.poseVersion++;
	}

	private void rebindIfNeeded(BakedStrutModel model) {
		if (this.boundModel == model)
			return;

		GroupBoneDefinition[] definitions = model.flatBones();
		Pose pose = new Pose(definitions.length);

		// State kept by pose processors was sized for the old model
		this.processorState = null;
		this.processedScratch = null;
		this.meshDeforms = null;

		GroupBoneHandle[] handles = new GroupBoneHandle[definitions.length];
		List<GroupBoneHandle> topLevel = new ObjectArrayList<>();

		this.current = pose;

		// Bones are flat-indexed parent-before-child, so a parent handle always already exists in
		// this array by the time a child needs to reference it - no second pass required.
		for (int i = 0; i < definitions.length; i++) {
			GroupBoneDefinition definition = definitions[i];

			pose.setRotation(i, definition.getInitialRotX(), definition.getInitialRotY(), definition.getInitialRotZ());
			pose.setHidden(i, definition.shouldNeverRender() == Boolean.TRUE);

			GroupBoneDefinition parentDefinition = definition.getParent();
			GroupBoneHandle parentHandle = parentDefinition == null ? null : handles[parentDefinition.getIndex()];
			GroupBoneHandle handle = new GroupBoneHandle(definition, this, parentHandle);

			handles[i] = handle;

			if (parentHandle != null) {
				parentHandle.addChild(handle);
			}
			else {
				topLevel.add(handle);
			}
		}

		this.handles = handles;
		this.topLevelHandles = List.copyOf(topLevel);
		this.boundModel = model;

		// A freshly bound instance starts all three poses in sync, so the first interpolation
		// after a model swap blends current toward itself rather than from a stale or zeroed buffer
		this.previous = new Pose(definitions.length);
		this.rendered = new Pose(definitions.length);
		this.previous.copyFrom(this.current);
		this.rendered.copyFrom(this.current);
		this.poseVersion++;

		// A stale scratch sized for the old model's bone count must not survive a rebind - dropped
		// rather than resized, since #effectiveScratch()/#subtreeHiddenScratch()/#boneVisualsScratch()
		// already recreate them lazily on next use
		this.effectiveScratch = null;
		this.subtreeHiddenScratch = null;
		this.effective = null;
		this.boneVisualsScratch = null;
		this.boneVisuals = null;
		this.boneTexturesScratch = null;
		this.boneTextures = null;
		this.boneLayersScratch = null;
		this.boneLayers = null;
		this.resolvedOverrideScratch = null;
		this.hiddenScratch = null;
		this.unprepared = null;

		// Dropped rather than reused across the rebind: a resource reload produces new StrutArmature
		// objects, so old entries are simply unreachable dead weight from here on. Cleared explicitly
		// anyway, rather than left to accumulate across repeated reloads within one session
		this.armaturePoses.clear();
		this.armatureHandles.clear();

		for (StrutArmature armature : model.armatures()) {
			List<ArmatureBoneDefinition> armatureBones = armature.bones();
			ArmaturePose armaturePose = new ArmaturePose(armatureBones.size());
			ArmatureBoneHandle[] armatureBoneHandles = new ArmatureBoneHandle[armatureBones.size()];

			for (int i = 0; i < armatureBones.size(); i++) {
				armatureBoneHandles[i] = new ArmatureBoneHandle(armatureBones.get(i), armaturePose);
			}

			this.armaturePoses.put(armature, armaturePose);
			this.armatureHandles.put(armature, armatureBoneHandles);
		}
	}
}
