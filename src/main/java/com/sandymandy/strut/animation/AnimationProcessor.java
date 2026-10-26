package com.sandymandy.strut.animation;

import com.sandymandy.strut.animation.authority.ClientAnimationState;
import com.sandymandy.strut.animation.authority.ClientAnimationStates;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.MathHelper;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.sandymandy.strut.StrutConstants;
import com.sandymandy.strut.animatable.StrutAnimatable;
import com.sandymandy.strut.animation.keyframe.AnimationPoint;
import com.sandymandy.strut.animation.keyframe.BoneAnimationQueue;
import com.sandymandy.strut.animation.state.BoneSnapshot;
import com.sandymandy.strut.animation.state.ModelInstance;
import com.sandymandy.strut.cache.object.BakedStrutModel;
import com.sandymandy.strut.cache.object.GroupBoneDefinition;
import com.sandymandy.strut.cache.object.GroupBoneHandle;
import com.sandymandy.strut.cache.object.StrutArmature;
import com.sandymandy.strut.cache.object.ArmatureBoneDefinition;
import com.sandymandy.strut.cache.object.PosableBone;
import org.jetbrains.annotations.Nullable;
import com.sandymandy.strut.loading.binary.raw.RawBinaryAnimation;
import com.sandymandy.strut.loading.math.MolangQueries;
import com.sandymandy.strut.model.StrutModel;

import java.util.Collection;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

public class AnimationProcessor<T extends StrutAnimatable> {
	private final Map<String, GroupBoneDefinition> bones = new Object2ObjectOpenHashMap<>();
	private final Map<String, ArmatureBoneRegistration> armatureBones = new Object2ObjectOpenHashMap<>();
	private final StrutModel<T> model;

	/**
	 * {@link #bones}/{@link #armatureBones} again, keyed by their {@link BoneKey} instead of a bare
	 * name - built once per {@link #setActiveModel}, not once per frame
	 * <p>
	 * {@link BoneKey} is a record: constructing one allocates. {@link #handlesByKey} and
	 * {@link #updateBoneSnapshots} used to call {@link BoneKey#group}/{@link BoneKey#armature} once
	 * per registered bone on <em>every</em> frame, for every animatable sharing this processor's
	 * model, purely to look a key up or check whether one was already present - the exact same key
	 * object, rebuilt from scratch every time. These two maps exist so a bone's key is constructed
	 * exactly once, when the model binds, and reused - the {@code GroupBoneDefinition}/
	 * {@code ArmatureBoneRegistration} values let {@link #handlesByKey} resolve straight to an
	 * index or registration without a second name-keyed lookup.
	 */
	private final Map<BoneKey, GroupBoneDefinition> boneHandleKeys = new Object2ObjectOpenHashMap<>();
	private final Map<BoneKey, ArmatureBoneRegistration> armatureHandleKeys = new Object2ObjectOpenHashMap<>();

	/**
	 * The reused destination for {@link #handlesByKey} - cleared and refilled at the start of every
	 * {@link #tickAnimation} call rather than replaced with a fresh map
	 * <p>
	 * Safe to reuse across the many animatables one processor serves (one {@code StrutModel}, and so
	 * one processor, is shared by every animatable of its renderer type) because rendering is
	 * single-threaded on the render thread and {@link #tickAnimation} runs one animatable fully to
	 * completion - including everything downstream that reads this map - before the next one starts
	 */
	private final Map<BoneKey, PosableBone> handleScratch = new Object2ObjectOpenHashMap<>();

	/**
	 * The reused "was this bone written by the animated branch this tick" trackers {@link #tickAnimation}
	 * used to allocate three fresh {@link ObjectOpenHashSet}s for on every call - same reuse
	 * reasoning as {@link #handleScratch}
	 */
	private final Set<BoneKey> rotationAnimatedThisTick = new ObjectOpenHashSet<>();
	private final Set<BoneKey> positionAnimatedThisTick = new ObjectOpenHashSet<>();
	private final Set<BoneKey> scaleAnimatedThisTick = new ObjectOpenHashSet<>();

	/**
	 * The baked model this processor's registered bones were flattened from
	 * <p>
	 * {@link #bones} holds {@link GroupBoneDefinition BoneDefinitions}, which are shared by every
	 * animatable instance using this model - that is safe because a definition is immutable. To
	 * actually pose a bone, {@link #tickAnimation} still needs to know which {@link BakedStrutModel}
	 * those definitions were flattened from, so it can ask the current instance's own
	 * {@link ModelInstance} for the {@link GroupBoneHandle} at each definition's flat index
	 */
	@Nullable
	private BakedStrutModel activeModel;

	/**
	 * One registered armature bone: its immutable definition, paired with the {@link StrutArmature}
	 * that owns it, since a {@link ModelInstance} needs both to resolve the bone's
	 * {@link com.sandymandy.strut.cache.object.ArmatureBoneHandle} (armature pose storage is keyed
	 * per armature, not by bone name alone)
	 */
	private record ArmatureBoneRegistration(StrutArmature armature, ArmatureBoneDefinition definition) {}

	/**
	 * Names of animations already warned about a {@link Animation#skeletonFingerprint()} mismatch
	 * against {@link #activeModel}, so {@link #buildAnimationQueue} - which can run every time an
	 * animatable's animation changes, not just once - warns about one clip at most once per model
	 * bind rather than spamming the log every time it plays. Cleared on {@link #setActiveModel},
	 * since a new model deserves a fresh chance to warn
	 */
	private final Set<String> fingerprintMismatchesWarned = new ObjectOpenHashSet<>();

	public boolean reloadAnimations = false;

	public AnimationProcessor(StrutModel<T> model) {
		this.model = model;
	}

	/**
	 * Build an animation queue for the given {@link RawAnimation}
	 *
	 * @param animatable The animatable object being rendered
	 * @param rawAnimation The raw animation to be compiled
	 * @return A queue of animations and loop types to play
	 */
	public Queue<QueuedAnimation> buildAnimationQueue(T animatable, RawAnimation rawAnimation) {
		LinkedList<QueuedAnimation> animations = new LinkedList<>();
		boolean error = false;

		for (RawAnimation.Stage stage : rawAnimation.getAnimationStages()) {
			Animation animation = null;

			if (stage.animationName() == RawAnimation.Stage.WAIT) { // This is intentional. Do not change this or Tslat will be unhappy
				animation = Animation.generateWaitAnimation(stage.additionalTicks());
			}
			else {
				try {
					animation = this.model.getAnimation(animatable, stage.animationName());
				}
				catch (RuntimeException ex) {
					StrutConstants.LOGGER.log(Level.ERROR, "Unable to find animation: " + stage.animationName() + " for " + animatable.getClass().getSimpleName());

					error = true;
					ex.printStackTrace();
				}
			}

			if (animation != null) {
				warnIfFingerprintMismatches(animation, stage.animationName());
				animations.add(new QueuedAnimation(animation, stage.loopType()));
			}
		}

		return error ? null : animations;
	}

	/**
	 * A logger of its own, under the same name {@link StrutConstants#LOGGER} logs under, rather
	 * than that field itself - referencing {@code StrutConstants} at all triggers its static
	 * initializer, which reaches into platform service registration and requires a live Minecraft
	 * bootstrap. This method needs to stay reachable from a plain unit test (see
	 * {@code SkeletonFingerprintTest}), so it logs under "Strut" directly instead
	 */
	private static final Logger LOGGER = LogManager.getLogger("Strut");

	/**
	 * Warns, once per animation name per model bind, if {@code animation} was authored against a
	 * different skeleton than {@link #activeModel}
	 * <p>
	 * A warning, never a refusal - {@code animation}'s {@link Animation#skeletonFingerprint()} is
	 * null for every source with no such notion (every {@code .animation.json} today), and even a
	 * real mismatch might be a clip that only ever intended to drive a subset of a rig, which is
	 * legitimate. This exists purely to turn "the animation silently does nothing" into a named
	 * mismatch a modder can actually act on
	 */
	private void warnIfFingerprintMismatches(Animation animation, String animationName) {
		if (animation.skeletonFingerprint() == null || this.activeModel == null)
			return;

		int modelFingerprint = this.activeModel.skeletonFingerprint();

		if (animation.skeletonFingerprint() == modelFingerprint)
			return;

		if (!this.fingerprintMismatchesWarned.add(animationName))
			return;

		LOGGER.warn("Animation \"{}\" was authored against a different skeleton than its model - fingerprint {} expected, model has {}."
						+ " It may not animate the bones you expect, or may not animate anything at all",
				animationName, RawBinaryAnimation.fingerprintText(animation.skeletonFingerprint()),
				RawBinaryAnimation.fingerprintText(modelFingerprint));
	}

	/**
	 * Tick and apply transformations to the model based on the current state of the {@link AnimationController}
	 *
	 * @param animatable            The animatable object relevant to the animation being played
	 * @param model                 The model currently being processed
	 * @param animatableManager			The AnimatableManager instance being used for this animation processor
	 * @param animTime              The internal tick counter kept by the {@link AnimatableManager} for this animatable
	 * @param state                 An {@link AnimationState} instance applied to this render frame
	 * @param crashWhenCantFindBone Whether to crash if unable to find a required bone, or to continue with the remaining bones
	 */
	public void tickAnimation(T animatable, StrutModel<T> model, AnimatableManager<T> animatableManager, double animTime, AnimationState<T> state, boolean crashWhenCantFindBone) {
		ModelInstance instance = animatableManager.getModelInstance();

		// Snapshot current into previous before anything below overwrites current with this
		// update's values - see ModelInstance#beginPoseUpdate. Skipped when there's no active model
		// (e.g. a processor with no bones registered yet), matching handlesByKey's own guard below
		if (this.activeModel != null)
			instance.beginPoseUpdate(this.activeModel);

		Map<BoneKey, PosableBone> handles = handlesByKey(instance);
		Map<BoneKey, BoneSnapshot> boneSnapshots = updateBoneSnapshots(animatableManager.getBoneSnapshotCollection());

		// Replaces the old per-bone dirty flags: a bone written by the animated branch below is
		// recorded here for the reset pass at the bottom of this method, rather than marked on the
		// bone itself. Reused fields, cleared here rather than allocated fresh - safe because this
		// method runs one animatable to completion, including this tracking, before the next one
		// starts (see the fields' own doc comment)
		this.rotationAnimatedThisTick.clear();
		this.positionAnimatedThisTick.clear();
		this.scaleAnimatedThisTick.clear();

		Set<BoneKey> rotationAnimatedThisTick = this.rotationAnimatedThisTick;
		Set<BoneKey> positionAnimatedThisTick = this.positionAnimatedThisTick;
		Set<BoneKey> scaleAnimatedThisTick = this.scaleAnimatedThisTick;

		// A server-driven entity registers no controllers: make one for each name the server is playing on
		if (animatable instanceof Entity entity) {
			ClientAnimationState mirror = ClientAnimationStates.get(entity.getId());

			if (mirror != null)
				animatableManager.ensureControllersFor(animatable, mirror.playbacks());
		}

		for (AnimationController<T> controller : animatableManager.getAnimationControllers().values()) {
			if (this.reloadAnimations) {
				controller.forceAnimationReset();
				controller.getBoneAnimationQueues().clear();
			}

			controller.isJustStarting = animatableManager.isFirstTick();

			state.withController(controller);
			controller.process(model, state, handles, boneSnapshots, animTime, crashWhenCantFindBone);

			double blendOut = controller.blendOutProgress(animTime);
			ControllerOutput output = controller.blendsOut() && blendOut < 0 ? controller.output() : null;

			if (output != null)
				output.clear();

			for (Map.Entry<BoneKey, BoneAnimationQueue> entry : controller.getBoneAnimationQueues().entrySet()) {
				BoneKey key = entry.getKey();
				BoneAnimationQueue boneAnimation = entry.getValue();
				PosableBone bone = boneAnimation.bone();
				BoneSnapshot snapshot = boneSnapshots.get(key);
				BoneSnapshot initialSnapshot = bone.getInitialSnapshot();

				AnimationPoint rotXPoint = boneAnimation.rotationXQueue().poll();
				AnimationPoint rotYPoint = boneAnimation.rotationYQueue().poll();
				AnimationPoint rotZPoint = boneAnimation.rotationZQueue().poll();
				AnimationPoint posXPoint = boneAnimation.positionXQueue().poll();
				AnimationPoint posYPoint = boneAnimation.positionYQueue().poll();
				AnimationPoint posZPoint = boneAnimation.positionZQueue().poll();
				AnimationPoint scaleXPoint = boneAnimation.scaleXQueue().poll();
				AnimationPoint scaleYPoint = boneAnimation.scaleYQueue().poll();
				AnimationPoint scaleZPoint = boneAnimation.scaleZQueue().poll();
				EasingType easingType = controller.overrideEasingTypeFunction.apply(animatable);

				if (rotXPoint != null && rotYPoint != null && rotZPoint != null) {
					bone.setRotX((float)EasingType.lerpWithOverride(rotXPoint, easingType) + initialSnapshot.getRotX());
					bone.setRotY((float)EasingType.lerpWithOverride(rotYPoint, easingType) + initialSnapshot.getRotY());
					bone.setRotZ((float)EasingType.lerpWithOverride(rotZPoint, easingType) + initialSnapshot.getRotZ());
					snapshot.updateRotation(bone.getRotX(), bone.getRotY(), bone.getRotZ());
					snapshot.startRotAnim();
					rotationAnimatedThisTick.add(key);

					if (output != null)
						output.rotation(key, bone, bone.getRotX(), bone.getRotY(), bone.getRotZ());
				}

				if (posXPoint != null && posYPoint != null && posZPoint != null) {
					bone.setPosX((float)EasingType.lerpWithOverride(posXPoint, easingType));
					bone.setPosY((float)EasingType.lerpWithOverride(posYPoint, easingType));
					bone.setPosZ((float)EasingType.lerpWithOverride(posZPoint, easingType));
					snapshot.updateOffset(bone.getPosX(), bone.getPosY(), bone.getPosZ());
					snapshot.startPosAnim();
					positionAnimatedThisTick.add(key);

					if (output != null)
						output.offset(key, bone, bone.getPosX(), bone.getPosY(), bone.getPosZ());
				}

				if (scaleXPoint != null && scaleYPoint != null && scaleZPoint != null) {
					bone.setScaleX((float)EasingType.lerpWithOverride(scaleXPoint, easingType));
					bone.setScaleY((float)EasingType.lerpWithOverride(scaleYPoint, easingType));
					bone.setScaleZ((float)EasingType.lerpWithOverride(scaleZPoint, easingType));
					snapshot.updateScale(bone.getScaleX(), bone.getScaleY(), bone.getScaleZ());
					snapshot.startScaleAnim();
					scaleAnimatedThisTick.add(key);

					if (output != null)
						output.scale(key, bone, bone.getScaleX(), bone.getScaleY(), bone.getScaleZ());
				}
			}

			if (blendOut >= 0)
				applyBlendOut(controller.output(), (float) blendOut, boneSnapshots, rotationAnimatedThisTick, positionAnimatedThisTick, scaleAnimatedThisTick);
		}

		this.reloadAnimations = false;
		double resetTickLength = animatable.getBoneResetTime();

		for (Map.Entry<BoneKey, PosableBone> entry : handles.entrySet()) {
			BoneKey key = entry.getKey();
			PosableBone bone = entry.getValue();

			if (!rotationAnimatedThisTick.contains(key)) {
				BoneSnapshot initialSnapshot = bone.getInitialSnapshot();
				BoneSnapshot saveSnapshot = boneSnapshots.get(key);

				if (saveSnapshot.isRotAnimInProgress())
					saveSnapshot.stopRotAnim(animTime);

				double percentageReset = resetTickLength == 0 ? 1 : Math.min((animTime - saveSnapshot.getLastResetRotationTick()) / resetTickLength, 1);
				float initialRotX = initialSnapshot.getRotX();
				float initialRotY = initialSnapshot.getRotY();
				float initialRotZ = initialSnapshot.getRotZ();
				float lastXRot = saveSnapshot.getRotX();
				float lastYRot = saveSnapshot.getRotY();
				float lastZRot = saveSnapshot.getRotZ();

				// Let's capture suspected full-rotations and prevent them from back-lerping
				// Far from perfect, but the best I can think of until I redo the system itself
				if (percentageReset == 0) {
					if (lastXRot != initialRotX && isSuspectedCompletedRotation(lastXRot)) {
						lastXRot = initialRotX;
						percentageReset = 1;
					}

					if (lastYRot != initialRotY && isSuspectedCompletedRotation(lastYRot)) {
						lastYRot = initialRotY;
						percentageReset = 1;
					}

					if (lastZRot != initialRotZ && isSuspectedCompletedRotation(lastZRot)) {
						lastZRot = initialRotZ;
						percentageReset = 1;
					}
				}

				bone.setRotX((float)MathHelper.lerp(percentageReset, lastXRot, initialRotX));
				bone.setRotY((float)MathHelper.lerp(percentageReset, lastYRot, initialRotY));
				bone.setRotZ((float)MathHelper.lerp(percentageReset, lastZRot, initialRotZ));

				if (percentageReset >= 1)
					saveSnapshot.updateRotation(bone.getRotX(), bone.getRotY(), bone.getRotZ());
			}

			if (!positionAnimatedThisTick.contains(key)) {
				BoneSnapshot initialSnapshot = bone.getInitialSnapshot();
				BoneSnapshot saveSnapshot = boneSnapshots.get(key);

				if (saveSnapshot.isPosAnimInProgress())
					saveSnapshot.stopPosAnim(animTime);

				double percentageReset = resetTickLength == 0 ? 1 : Math.min((animTime - saveSnapshot.getLastResetPositionTick()) / resetTickLength, 1);

				bone.setPosX((float)MathHelper.lerp(percentageReset, saveSnapshot.getOffsetX(), initialSnapshot.getOffsetX()));
				bone.setPosY((float)MathHelper.lerp(percentageReset, saveSnapshot.getOffsetY(), initialSnapshot.getOffsetY()));
				bone.setPosZ((float)MathHelper.lerp(percentageReset, saveSnapshot.getOffsetZ(), initialSnapshot.getOffsetZ()));

				if (percentageReset >= 1)
					saveSnapshot.updateOffset(bone.getPosX(), bone.getPosY(), bone.getPosZ());
			}

			if (!scaleAnimatedThisTick.contains(key)) {
				BoneSnapshot initialSnapshot = bone.getInitialSnapshot();
				BoneSnapshot saveSnapshot = boneSnapshots.get(key);

				if (saveSnapshot.isScaleAnimInProgress())
					saveSnapshot.stopScaleAnim(animTime);

				double percentageReset = resetTickLength == 0 ? 1 : Math.min((animTime - saveSnapshot.getLastResetScaleTick()) / resetTickLength, 1);

				bone.setScaleX((float)MathHelper.lerp(percentageReset, saveSnapshot.getScaleX(), initialSnapshot.getScaleX()));
				bone.setScaleY((float)MathHelper.lerp(percentageReset, saveSnapshot.getScaleY(), initialSnapshot.getScaleY()));
				bone.setScaleZ((float)MathHelper.lerp(percentageReset, saveSnapshot.getScaleZ(), initialSnapshot.getScaleZ()));

				if (percentageReset >= 1)
					saveSnapshot.updateScale(bone.getScaleX(), bone.getScaleY(), bone.getScaleZ());
			}
		}

		// current is now fully written for this update - blend it with previous into rendered,
		// which is what everything that actually draws a bone reads. See ModelInstance#renderedPose
		if (this.activeModel != null)
			instance.interpolateRenderedPose(this.activeModel, state.getPartialTick());

		animatableManager.finishFirstTick();
	}

	/**
	 * Blends the pose again after {@code StrutModel#setCustomAnimations} has written to it
	 * <p>
	 * {@link #tickAnimation} blends {@code previous} and {@code current} into the pose that draws, and only then does the model's
	 * custom code (head look, jiggle) write to {@code current}. Without this second blend a custom write reached the screen one
	 * frame late and mixed with last frame's value by the partial tick, so a head turn flickered between its full and half value.
	 * Called once per real update, after the custom writes
	 */
	public void refreshRenderedPose(ModelInstance instance, float partialTick) {
		if (this.activeModel != null)
			instance.interpolateRenderedPose(this.activeModel, partialTick);
	}

	/**
	 * Bandaid helper to try to detect suspected completed rotations in an animation frame
	 * <p>
	 * By no means perfectly accurate, but is the best we can do until the system is changed
	 */
	private boolean isSuspectedCompletedRotation(float lastRotation) {
		float rotations = MathHelper.abs(lastRotation / (360f * MathHelper.RADIANS_PER_DEGREE));
		float partialRotation = 1 - (rotations - (int)rotations);

		return partialRotation == 1 || partialRotation < 0.026 * rotations;
	}

	/**
	 * A controller fading out: each bone it was animating goes from where it left it toward what the controllers before it gave the bone this frame, or the
	 * bone's rest pose if none did, {@code progress} of the way
	 */
	private static void applyBlendOut(ControllerOutput output, float progress, Map<BoneKey, BoneSnapshot> snapshots, Set<BoneKey> rotated, Set<BoneKey> moved, Set<BoneKey> scaled) {
		for (Map.Entry<BoneKey, ControllerOutput.Bone> entry : output.entries()) {
			BoneKey key = entry.getKey();
			ControllerOutput.Bone from = entry.getValue();
			PosableBone bone = from.bone;
			BoneSnapshot initial = bone.getInitialSnapshot();
			BoneSnapshot snapshot = snapshots.get(key);

			if (snapshot == null)
				continue;

			if (from.rotated) {
				boolean under = rotated.contains(key);

				bone.setRotX(MathHelper.lerp(progress, from.rotation[0], under ? bone.getRotX() : initial.getRotX()));
				bone.setRotY(MathHelper.lerp(progress, from.rotation[1], under ? bone.getRotY() : initial.getRotY()));
				bone.setRotZ(MathHelper.lerp(progress, from.rotation[2], under ? bone.getRotZ() : initial.getRotZ()));
				snapshot.updateRotation(bone.getRotX(), bone.getRotY(), bone.getRotZ());
				snapshot.startRotAnim();
				rotated.add(key);
			}

			if (from.moved) {
				boolean under = moved.contains(key);

				bone.setPosX(MathHelper.lerp(progress, from.offset[0], under ? bone.getPosX() : initial.getOffsetX()));
				bone.setPosY(MathHelper.lerp(progress, from.offset[1], under ? bone.getPosY() : initial.getOffsetY()));
				bone.setPosZ(MathHelper.lerp(progress, from.offset[2], under ? bone.getPosZ() : initial.getOffsetZ()));
				snapshot.updateOffset(bone.getPosX(), bone.getPosY(), bone.getPosZ());
				snapshot.startPosAnim();
				moved.add(key);
			}

			if (from.scaled) {
				boolean under = scaled.contains(key);

				bone.setScaleX(MathHelper.lerp(progress, from.scale[0], under ? bone.getScaleX() : initial.getScaleX()));
				bone.setScaleY(MathHelper.lerp(progress, from.scale[1], under ? bone.getScaleY() : initial.getScaleY()));
				bone.setScaleZ(MathHelper.lerp(progress, from.scale[2], under ? bone.getScaleZ() : initial.getScaleZ()));
				snapshot.updateScale(bone.getScaleX(), bone.getScaleY(), bone.getScaleZ());
				snapshot.startScaleAnim();
				scaled.add(key);
			}
		}
	}

	/**
	 * Create new bone {@link BoneSnapshot} based on each registered bone's initial snapshot - a
	 * group {@link GroupBoneDefinition} or an armature {@link ArmatureBoneDefinition} alike - filtered by the
	 * bones already present in the master snapshots map
	 *
	 * @param snapshots The master bone snapshots map from the related {@link AnimatableManager}
	 * @return The input snapshots map, for easy assignment
	 */
	private Map<BoneKey, BoneSnapshot> updateBoneSnapshots(Map<BoneKey, BoneSnapshot> snapshots) {
		for (Map.Entry<BoneKey, GroupBoneDefinition> entry : this.boneHandleKeys.entrySet()) {
			if (!snapshots.containsKey(entry.getKey()))
				snapshots.put(entry.getKey(), BoneSnapshot.copy(entry.getValue().getInitialSnapshot()));
		}

		for (Map.Entry<BoneKey, ArmatureBoneRegistration> entry : this.armatureHandleKeys.entrySet()) {
			if (!snapshots.containsKey(entry.getKey()))
				snapshots.put(entry.getKey(), BoneSnapshot.copy(entry.getValue().definition().getInitialSnapshot()));
		}

		return snapshots;
	}

	/**
	 * This instance's {@link PosableBone} handle for each registered bone - group and armature
	 * alike - keyed by {@link BoneKey}
	 * <p>
	 * Refilled into {@link #handleScratch} every tick, for the instance currently being ticked -
	 * {@link #bones} and {@link #armatureBones} are shared across every animatable using this
	 * model, but each animatable's handles are its own, so the map's <em>values</em> still have to
	 * be rebuilt every call even though its keys ({@link #boneHandleKeys}/{@link #armatureHandleKeys})
	 * no longer do
	 */
	private Map<BoneKey, PosableBone> handlesByKey(ModelInstance instance) {
		this.handleScratch.clear();

		if (this.activeModel == null)
			return this.handleScratch;

		GroupBoneHandle[] flatHandles = instance.handlesFor(this.activeModel);

		for (Map.Entry<BoneKey, GroupBoneDefinition> entry : this.boneHandleKeys.entrySet()) {
			this.handleScratch.put(entry.getKey(), flatHandles[entry.getValue().getIndex()]);
		}

		for (Map.Entry<BoneKey, ArmatureBoneRegistration> entry : this.armatureHandleKeys.entrySet()) {
			ArmatureBoneRegistration registration = entry.getValue();

			this.handleScratch.put(entry.getKey(),
					instance.armatureHandleFor(this.activeModel, registration.armature(), registration.definition()));
		}

		return this.handleScratch;
	}

	/**
	 * Gets a bone definition by name
	 *
	 * @param boneName The bone name
	 * @return the bone's immutable, shared definition
	 */
	public GroupBoneDefinition getBone(String boneName) {
		return this.bones.get(boneName);
	}

	/**
	 * Adds the given bone to the bones list for this processor
	 * <p>
	 * This is normally handled automatically by Strut
	 * <p>
	 * Failure to properly register a bone will break things.
	 */
	public void registerStrutBone(GroupBoneDefinition bone) {
		bone.saveInitialSnapshot();
		this.bones.put(bone.getName(), bone);
		this.boneHandleKeys.put(BoneKey.group(bone.getName()), bone);

		for (GroupBoneDefinition child : bone.getChildBones()) {
			registerStrutBone(child);
		}
	}

	/**
	 * Clear the {@link GroupBoneDefinition BoneDefinitions} and {@link ArmatureBoneDefinition StrutArmatureBones}
	 * currently registered to the processor, then prepares the processor for a new model
	 * <p>
	 * Should be called whenever switching models to render/animate
	 */
	public void setActiveModel(BakedStrutModel model) {
		this.bones.clear();
		this.armatureBones.clear();
		this.boneHandleKeys.clear();
		this.armatureHandleKeys.clear();
		this.fingerprintMismatchesWarned.clear();
		this.activeModel = model;

		for (GroupBoneDefinition bone : model.topLevelBones()) {
			registerStrutBone(bone);
		}

		for (StrutArmature armature : model.armatures()) {
			for (ArmatureBoneDefinition bone : armature.bones()) {
				bone.saveInitialSnapshot();

				ArmatureBoneRegistration registration = new ArmatureBoneRegistration(armature, bone);

				this.armatureBones.put(bone.getName(), registration);
				this.armatureHandleKeys.put(BoneKey.armature(bone.getName()), registration);
			}
		}
	}

	/**
	 * Get an iterable collection of the {@link GroupBoneDefinition BoneDefinitions} currently registered to the processor
	 * <p>
	 * Group bones only - see {@link #hasRegisteredBones()} for a check that also counts armature
	 * bones
	 */
	public Collection<GroupBoneDefinition> getRegisteredBones() {
		return this.bones.values();
	}

	/**
	 * Whether this processor has any bone registered to animate - group or armature
	 * <p>
	 * {@link com.sandymandy.strut.model.StrutModel#handleAnimations} uses this to skip
	 * {@link #tickAnimation} entirely for a model with nothing to animate, rather than running the
	 * whole pipeline over an empty bone set every frame
	 */
	public boolean hasRegisteredBones() {
		return !this.bones.isEmpty() || !this.armatureBones.isEmpty();
	}

	/**
	 * Apply transformations and settings prior to acting on any animation-related functionality
	 */
	public void preAnimationSetup(AnimationState<T> animationState, double animTime) {
		MolangQueries.updateActor(animationState, animTime);
		this.model.applyMolangQueries(animationState, animTime);
	}

	/**
	 * {@link Animation} and {@link Animation.LoopType} override pair,
	 * used to define a playable animation stage for a {@link StrutAnimatable}
	 */
	public record QueuedAnimation(Animation animation, Animation.LoopType loopType) {}
}
