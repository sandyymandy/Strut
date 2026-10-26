package com.sandymandy.strut.animation;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Direction.Axis;
import org.apache.logging.log4j.Level;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import com.sandymandy.strut.StrutConstants;
import com.sandymandy.strut.animatable.StrutAnimatable;
import com.sandymandy.strut.animatable.StrutEntity;
import com.sandymandy.strut.animation.authority.AnimationPlayback;
import com.sandymandy.strut.animation.authority.PlaybackBlend;
import com.sandymandy.strut.animation.authority.AuthorityMode;
import com.sandymandy.strut.animation.authority.ClientAnimationState;
import com.sandymandy.strut.animation.authority.ClientAnimationStates;
import com.sandymandy.strut.animation.keyframe.*;
import com.sandymandy.strut.animation.keyframe.event.CustomInstructionKeyframeEvent;
import com.sandymandy.strut.animation.keyframe.event.ParticleKeyframeEvent;
import com.sandymandy.strut.animation.keyframe.event.SoundKeyframeEvent;
import com.sandymandy.strut.animation.keyframe.event.data.CustomInstructionKeyframeData;
import com.sandymandy.strut.animation.keyframe.event.data.KeyFrameData;
import com.sandymandy.strut.animation.keyframe.event.data.ParticleKeyframeData;
import com.sandymandy.strut.animation.keyframe.event.data.SoundKeyframeData;
import com.sandymandy.strut.animation.state.BoneSnapshot;
import com.sandymandy.strut.cache.object.PosableBone;
import com.sandymandy.strut.loading.math.MathParser;
import com.sandymandy.strut.loading.math.MathValue;
import com.sandymandy.strut.loading.math.MolangQueries;
import com.sandymandy.strut.loading.math.value.Constant;
import com.sandymandy.strut.model.StrutModel;

import java.util.*;
import java.util.function.Function;

/**
 * The actual controller that handles the playing and usage of animations, including their various keyframes and instruction markers
 * <p>
 * Each controller can only play a single animation at a time - for example you may have one controller to animate walking,
 * one to control attacks, one to control size, etc.
 */
public class AnimationController<T extends StrutAnimatable> {
	protected final T animatable;
	protected final String name;
	protected final AnimationStateHandler<T> stateHandler;
	protected final Map<BoneKey, BoneAnimationQueue> boneAnimationQueues = new Object2ObjectOpenHashMap<>();
	protected final Map<BoneKey, BoneSnapshot> boneSnapshots = new Object2ObjectOpenHashMap<>();
	protected Queue<AnimationProcessor.QueuedAnimation> animationQueue = new LinkedList<>();

	protected boolean isJustStarting = false;
	protected boolean needsAnimationReload = false;
	protected boolean shouldResetTick = false;
	private boolean justStopped = true;
	protected boolean justStartedTransition = false;

	protected SoundKeyframeHandler<T> soundKeyframeHandler = null;
	protected ParticleKeyframeHandler<T> particleKeyframeHandler = null;
	protected CustomKeyframeHandler<T> customKeyframeHandler = null;

	protected final Map<String, RawAnimation> triggerableAnimations = new Object2ObjectOpenHashMap<>(0);
	protected RawAnimation triggeredAnimation = null;
	protected boolean handlingTriggeredAnimations = false;

	protected double transitionLength;
	protected RawAnimation currentRawAnimation;
	protected AnimationProcessor.QueuedAnimation currentAnimation;
	protected State animationState = State.STOPPED;
	protected double tickOffset;
	protected double lastPollTime = -1;
	protected Function<T, Double> animationSpeedModifier = animatable -> 1d;
	protected Function<T, EasingType> overrideEasingTypeFunction = animatable -> null;
	private final Set<KeyFrameData> executedKeyFrames = new ObjectOpenHashSet<>();
	protected StrutModel<T> lastModel;
	protected AuthorityMode authorityMode;
	/** Whether this controller fades out of its last pose when it stops, instead of letting go of the bones at once */
	private boolean blendOut;
	/** When the fade began (animation time), or -1 when not fading */
	private double blendOutStart = -1;
	private final ControllerOutput output = new ControllerOutput();

	/**
	 * Instantiates a new {@code AnimationController}
	 * <p>
	 * This constructor assumes a 0-tick transition length between animations, and a generic name
	 *
	 * @param animatable The object that will be animated by this controller
	 * @param animationHandler The {@link AnimationStateHandler} animation state handler responsible for deciding which animations to play
	 */
	public AnimationController(T animatable, AnimationStateHandler<T> animationHandler) {
		this(animatable, "base_controller", 0, animationHandler);
	}

	/**
	 * Instantiates a new {@code AnimationController}
	 * <p>
	 * This constructor assumes a 0-tick transition length between animations
	 *
	 * @param animatable The object that will be animated by this controller
	 * @param name The name of the controller - should represent what animations it handles
	 * @param animationHandler The {@link AnimationStateHandler} animation state handler responsible for deciding which animations to play
	 */
	public AnimationController(T animatable, String name, AnimationStateHandler<T> animationHandler) {
		this(animatable, name, 0, animationHandler);
	}

	/**
	 * Instantiates a new {@code AnimationController}
	 * <p>
	 * This constructor assumes a generic name
	 *
	 * @param animatable The object that will be animated by this controller
	 * @param transitionTickTime The amount of time (in <b>ticks</b>) that the controller should take to transition between animations.
	 *                              Lerping is automatically applied where possible
	 * @param animationHandler The {@link AnimationStateHandler} animation state handler responsible for deciding which animations to play
	 */
	public AnimationController(T animatable, int transitionTickTime, AnimationStateHandler<T> animationHandler) {
		this(animatable, "base_controller", transitionTickTime, animationHandler);
	}

	/**
	 * Instantiates a new {@code AnimationController}
	 *
	 * @param animatable The object that will be animated by this controller
	 * @param name The name of the controller - should represent what animations it handles
	 * @param transitionTickTime The amount of time (in <b>ticks</b>) that the controller should take to transition between animations.
	 *                              Lerping is automatically applied where possible
	 * @param animationHandler The {@link AnimationStateHandler} animation state handler responsible for deciding which animations to play
	 */
	public AnimationController(T animatable, String name, int transitionTickTime, AnimationStateHandler<T> animationHandler) {
		this.animatable = animatable;
		this.name = name;
		this.transitionLength = transitionTickTime;
		this.stateHandler = animationHandler;
		// A StrutEntity controller is
		// authoritative by default - everything else (block entities, items, GUI previews,
		// singletons) keeps deciding locally exactly as every controller always has
		this.authorityMode = animatable instanceof StrutEntity ? AuthorityMode.SERVER : AuthorityMode.CLIENT_COSMETIC;
	}

	/**
	 * Overrides this controller's {@link AuthorityMode}
	 * <p>
	 * Use this to opt a {@link StrutEntity} controller back out to {@link AuthorityMode#CLIENT_COSMETIC}
	 * - the default only fits a controller whose animation is meant to be the same for every
	 * observer (an attack, a shared gameplay state); a purely cosmetic per-viewer effect on an
	 * entity (idle fidgeting, a look-at) should stay client-decided
	 */
	public AnimationController<T> authorityMode(AuthorityMode mode) {
		this.authorityMode = mode;

		return this;
	}

	/**
	 * This controller's current {@link AuthorityMode}
	 */
	public AuthorityMode getAuthorityMode() {
		return this.authorityMode;
	}

	/**
	 * Applies the given {@link SoundKeyframeHandler} to this controller, for handling {@link SoundKeyframeEvent sound keyframe instructions}
	 *
	 * @return this
	 */
	public AnimationController<T> setSoundKeyframeHandler(SoundKeyframeHandler<T> soundHandler) {
		this.soundKeyframeHandler = soundHandler;

		return this;
	}

	/**
	 * Applies the given {@link ParticleKeyframeHandler} to this controller, for handling {@link ParticleKeyframeEvent particle keyframe instructions}
	 *
	 * @return this
	 */
	public AnimationController<T> setParticleKeyframeHandler(ParticleKeyframeHandler<T> particleHandler) {
		this.particleKeyframeHandler = particleHandler;

		return this;
	}

	/**
	 * Applies the given {@link CustomKeyframeHandler} to this controller, for handling {@link CustomInstructionKeyframeEvent sound keyframe instructions}
	 *
	 * @return this
	 */
	public AnimationController<T> setCustomInstructionKeyframeHandler(CustomKeyframeHandler<T> customInstructionHandler) {
		this.customKeyframeHandler = customInstructionHandler;

		return this;
	}

	/**
	 * Applies the given modifier function to this controller, for handling the speed that the controller should play its animations at
	 * <p>
	 * An output value of 1 is considered neutral, with 2 playing an animation twice as fast, 0.5 playing half as fast, etc
	 *
	 * @param speedModFunction The function to apply to this controller to handle animation speed
	 * @return this
	 */
	public AnimationController<T> setAnimationSpeedHandler(Function<T, Double> speedModFunction) {
		this.animationSpeedModifier = speedModFunction;

		return this;
	}

	/**
	 * Applies the given modifier value to this controller, for handlign the speed that the controller hsould play its animations at
	 * <p>
	 * A value of 1 is considered neutral, with 2 playing an animation twice as fast, 0.5 playing half as fast, etc
	 *
	 * @param speed The speed modifier to apply to this controller to handle animation speed.
	 * @return this
	 */
	public AnimationController<T> setAnimationSpeed(double speed) {
		return setAnimationSpeedHandler(animatable -> speed);
	}

	/**
	 * Sets the controller's {@link EasingType} override for animations
	 * <p>
	 * By default, the controller will use whatever {@code EasingType} was defined in the animation json
	 *
	 * @param easingTypeFunction The new {@code EasingType} to use
	 * @return this
	 */
	public AnimationController<T> setOverrideEasingType(EasingType easingTypeFunction) {
		return setOverrideEasingTypeFunction(animatable -> easingTypeFunction);
	}

	/**
	 * Sets the controller's {@link EasingType} override function for animations
	 * <p>
	 * By default, the controller will use whatever {@code EasingType} was defined in the animation json
	 *
	 * @param easingType The new {@code EasingType} to use
	 * @return this
	 */
	public AnimationController<T> setOverrideEasingTypeFunction(Function<T, EasingType> easingType) {
		this.overrideEasingTypeFunction = easingType;

		return this;
	}

	/**
	 * Registers a triggerable {@link RawAnimation} with the controller
	 * <p>
	 * These can then be triggered by the various {@code triggerAnim} methods in {@code StrutAnimatable}'s subclasses
	 *
	 * @param name The name of the triggerable animation
	 * @param animation The RawAnimation for this triggerable animation
	 * @return this
	 */
	public AnimationController<T> triggerableAnim(String name, RawAnimation animation) {
		this.triggerableAnimations.put(name, animation);

		return this;
	}

	/**
	 * Tells the AnimationController that you want to receive the {@link AnimationController.AnimationStateHandler} while a triggered animation is playing
	 * <p>
	 * This has no effect if no triggered animation has been registered, or one isn't currently playing
	 * <p>
	 * If a triggered animation is playing, it can be checked in your AnimationStateHandler via {@link #isPlayingTriggeredAnimation()}
	 */
	public AnimationController<T> receiveTriggeredAnimations() {
		this.handlingTriggeredAnimations = true;

		return this;
	}

	/**
	 * Gets the controller's name
	 */
	public String getName() {
		return this.name;
	}

	/**
	 * Gets the currently loaded {@link Animation}, if present
	 * <p>
	 * An animation returned here does not guarantee it is currently playing, just that it is the currently loaded animation for this controller
	 */
	@Nullable
	public AnimationProcessor.QueuedAnimation getCurrentAnimation() {
		return this.currentAnimation;
	}

	/**
	 * Gets the currently playing {@link RawAnimation triggered animation}, if present
	 */
	@Nullable
	public RawAnimation getTriggeredAnimation() {
		return this.triggeredAnimation;
	}

	/**
	 * Returns the current state of this controller.
	 */
	public State getAnimationState() {
		return this.animationState;
	}

    /**
     * Get the state handler for this controller
     */
    public AnimationStateHandler<T> getStateHandler() {
        return this.stateHandler;
    }

	/**
	 * Gets the currently loaded animation's {@link BoneAnimationQueue BoneAnimationQueues}.
	 */
	public Map<BoneKey, BoneAnimationQueue> getBoneAnimationQueues() {
		return this.boneAnimationQueues;
	}

	/**
	 * Gets the current animation speed modifier
	 * <p>
	 * This modifier defines the relative speed in which animations will be played based on the current state of the game
	 *
	 * @return The computed current animation speed modifier
	 */
	public double getAnimationSpeed() {
		return this.animationSpeedModifier.apply(this.animatable);
	}

	/**
	 * Marks the controller as needing to reset its animation and state the next time {@link #setAnimation(RawAnimation)} is called
	 * <p>
	 * Use this if you have a {@link RawAnimation} with multiple stages and you want it to start again from the first stage, or if you want to reset the currently playing animation to the start
	 */
	public void forceAnimationReset() {
		this.needsAnimationReload = true;
	}

	/**
	 * Tells the controller to stop all animations until told otherwise
	 * <p>
	 * Calling this will prevent the controller from continuing to play the currently loaded animation until
	 * either {@link #forceAnimationReset()} is called, or
	 * {@link #setAnimation(RawAnimation)} is called with a different animation
	 */
	public void stop() {
		this.animationState = State.STOPPED;
	}

	/**
	 * Whether this controller blends out when it stops: the bones it was animating go from where it left them to what the controllers under it give them (or
	 * their rest pose) over the controller's transition length, instead of all at once. Off by default. For a controller that plays over another, such as a
	 * bow's draw over the legs' walk, so lowering the bow does not snap the arms down
	 */
	public AnimationController<T> blendOut(boolean on) {
		this.blendOut = on;

		if (!on) {
			this.blendOutStart = -1;
			this.output.clear();
		}

		return this;
	}

	/** Whether this controller {@link #blendOut blends out} when it stops */
	public boolean blendsOut() {
		return this.blendOut;
	}

	/** Starts the fade out, if this controller blends out, has a pose to fade from, and is not fading already */
	private void beginBlendOut(double time) {
		if (this.blendOut && this.blendOutStart < 0 && this.transitionLength > 0 && !this.output.isEmpty())
			this.blendOutStart = time;
	}

	/**
	 * How far through its fade out this controller is at {@code time}, 0 to 1, or -1 when it is not fading. A fade that has run its length ends here, and what
	 * it faded from is forgotten
	 */
	@ApiStatus.Internal
	public double blendOutProgress(double time) {
		if (this.blendOutStart < 0)
			return -1;

		double progress = (time - this.blendOutStart) / this.transitionLength;

		if (progress >= 1 || progress < 0) {
			this.blendOutStart = -1;
			this.output.clear();

			return -1;
		}

		return progress;
	}

	/** What this controller last gave the bones, recorded by the processor for a fade out */
	@ApiStatus.Internal
	public ControllerOutput output() {
		return this.output;
	}

	/**
	 * Overrides the animation transition time for the controller
	 */
	public AnimationController<T> transitionLength(int ticks) {
		this.transitionLength = ticks;

		return this;
	}

	/**
	 * Checks whether the last animation that was playing on this controller has finished or not
	 * <p>
	 * This will return true if the controller has had an animation set previously, and it has finished playing
	 * and isn't going to loop or proceed to another animation
	 *
	 * @return Whether the previous animation finished or not
	 */
	public boolean hasAnimationFinished() {
		return this.currentRawAnimation != null && this.animationState == State.STOPPED;
	}

	/**
	 * Returns the currently cached {@link RawAnimation}
	 * <p>
	 * This animation may or may not still be playing, but it is the last one to be set in {@link #setAnimation}
	 */
	public RawAnimation getCurrentRawAnimation() {
		return this.currentRawAnimation;
	}

	/**
	 * Returns whether the controller is currently playing a triggered animation registered in
	 * {@link #triggerableAnim}<br>
	 * Used for custom handling if {@link #receiveTriggeredAnimations()} was marked
	 */
	public boolean isPlayingTriggeredAnimation() {
		return this.triggeredAnimation != null && !hasAnimationFinished();
	}

	/**
	 * Sets the currently loaded animation to the one provided
	 * <p>
	 * This method may be safely called every render frame, as passing the same builder that is already loaded will do nothing
	 * <p>
	 * Pass null to this method to tell the controller to stop
	 * <p>
	 * If {@link #forceAnimationReset()} has been called prior to this, the controller will reload the animation regardless of whether it matches the currently loaded one or not
	 */
	public void setAnimation(RawAnimation rawAnimation) {
		if (rawAnimation == null || rawAnimation.getAnimationStages().isEmpty()) {
			stop();

			return;
		}

		if (this.needsAnimationReload || !rawAnimation.equals(this.currentRawAnimation)) {
			if (this.lastModel != null) {
				Queue<AnimationProcessor.QueuedAnimation> animations = this.lastModel.getAnimationProcessor().buildAnimationQueue(this.animatable, rawAnimation);

				if (animations != null) {
					this.animationQueue = animations;
					this.currentRawAnimation = rawAnimation;
					this.shouldResetTick = true;
					this.animationState = State.TRANSITIONING;
					this.justStartedTransition = true;
					this.needsAnimationReload = false;

					return;
				}
			}

			stop();
		}
	}

	/**
	 * Attempt to trigger an animation from the list of {@link #triggerableAnimations triggerable animations} this controller contains
	 *
	 * @param animName The name of the animation to trigger
	 * @return Whether the controller triggered an animation or not
	 */
	public boolean tryTriggerAnimation(String animName) {
		RawAnimation anim = this.triggerableAnimations.get(animName);

		if (anim == null)
			return false;

		this.triggeredAnimation = anim;

		if (this.animationState == State.STOPPED) {
			this.animationState = State.TRANSITIONING;
			this.shouldResetTick = true;
			this.justStartedTransition = true;
		}

		return true;
	}

	/**
	 * Stops and removes a previously triggered animation, effectively ending it immediately.
	 *
	 * @return true if a triggered animation was stopped
	 */
	protected boolean stopTriggeredAnimation() {
		if (this.triggeredAnimation == null)
			return false;

		if (this.currentRawAnimation == this.triggeredAnimation) {
			this.currentAnimation = null;
			this.currentRawAnimation = null;
		}

		this.triggeredAnimation = null;
		this.needsAnimationReload = true;

		return true;
	}

	/**
	 * Handle a given AnimationState, alongside the current triggered animation if applicable
	 */
	protected PlayState handleAnimationState(AnimationState<T> state) {
		if (this.triggeredAnimation != null) {
			if (this.currentRawAnimation != this.triggeredAnimation)
				this.currentAnimation = null;

			setAnimation(this.triggeredAnimation);

			if (!hasAnimationFinished() && (!this.handlingTriggeredAnimations || getStateHandler().handle(state) == PlayState.CONTINUE))
				return PlayState.CONTINUE;

			this.triggeredAnimation = null;
			this.needsAnimationReload = true;
		}

		return getStateHandler().handle(state);
	}

	/**
	 * This method is called every frame in order to populate the animation point
	 * queues, and process animation state logic
	 *
	 * @param model					The model currently being processed
	 * @param state                 The animation test state
	 * @param bones                 The registered {@link PosableBone bones} for this model, group and armature alike
	 * @param snapshots             The {@link BoneSnapshot} map
	 * @param seekTime              The current tick + partial tick
	 * @param crashWhenCantFindBone Whether to hard-fail when a bone can't be found, or to continue with the remaining bones
	 */
	public void process(StrutModel<T> model, AnimationState<T> state, Map<BoneKey, PosableBone> bones, Map<BoneKey, BoneSnapshot> snapshots, final double seekTime, boolean crashWhenCantFindBone) {
		this.lastModel = model;

		if (this.authorityMode == AuthorityMode.SERVER) {
			processServerAuthoritative(model, state, bones, snapshots, crashWhenCantFindBone);

			return;
		}

		// AuthorityMode.SERVER_WITH_LOCAL_PREDICTION falls through to the client-decided path below
		// deliberately, not by omission: it is reserved for a later piece of work (see that enum
		// constant's own doc comment) that predicts locally and then reconciles against the
		// server's answer once it arrives. Until that reconciliation exists, "predict locally,
		// never reconcile" - exactly what the state-handler path below already does - is the
		// correct interim behaviour, not a gap silently papered over
		double adjustedTick = adjustTick(seekTime);

		if (animationState == State.TRANSITIONING && adjustedTick >= this.transitionLength) {
			this.shouldResetTick = true;
			this.animationState = State.RUNNING;
			adjustedTick = adjustTick(seekTime);
		}

		PlayState playState = handleAnimationState(state);

		if (playState == PlayState.STOP || (this.currentAnimation == null && this.animationQueue.isEmpty())) {
			beginBlendOut(seekTime);
			this.animationState = State.STOPPED;
			this.justStopped = true;

			return;
		}

		createInitialQueues(bones);

		if (this.justStartedTransition && (this.shouldResetTick || this.justStopped)) {
			this.justStopped = false;
			adjustedTick = adjustTick(seekTime);

			if (this.currentAnimation == null)
				this.animationState = State.TRANSITIONING;
		}
		else if (this.currentAnimation == null) {
			this.shouldResetTick = true;
			this.animationState = State.TRANSITIONING;
			this.justStartedTransition = true;
			this.needsAnimationReload = false;
			adjustedTick = adjustTick(seekTime);
		}
		else if (this.animationState != State.TRANSITIONING) {
			this.animationState = State.RUNNING;
		}

		if (getAnimationState() == State.RUNNING) {
			processCurrentAnimation(adjustedTick, seekTime, crashWhenCantFindBone);
		}
		else if (this.animationState == State.TRANSITIONING) {
			if (this.lastPollTime != seekTime && (adjustedTick == 0 || this.isJustStarting)) {
				this.justStartedTransition = false;
				this.lastPollTime = seekTime;
				this.currentAnimation = this.animationQueue.poll();

				resetEventKeyFrames();

				if (this.currentAnimation == null)
					return;

				saveSnapshotsForAnimation(this.currentAnimation, snapshots);

				// the next stage of a chain blends from where this controller left the bones, not from what the bones were last given by any controller
				if (!this.stageEnd.isEmpty()) {
					for (Map.Entry<BoneKey, StageEnd> entry : this.stageEnd.entrySet()) {
						BoneSnapshot snapshot = this.boneSnapshots.get(entry.getKey());

						if (snapshot != null)
							entry.getValue().applyTo(snapshot);
					}

					this.stageEnd.clear();
				}
			}

			if (this.currentAnimation != null) {
				this.blendOutStart = -1;
				MathParser.setVariable(MolangQueries.ANIM_TIME, () -> 0);

				for (BoneAnimation boneAnimation : this.currentAnimation.animation().boneAnimations()) {
					// A .strutanim channel says which kind of bone it drives and binds to that kind
					// alone; a .animation.json bone animation says nothing and resolves group-first.
					// Resolved against `bones`, but the three maps below are always populated in
					// lockstep (createInitialQueues/saveSnapshotsForAnimation against the same
					// `bones`/`snapshots` this method was called with), so any one of them would do
					BoneKey key = BoneKey.resolve(bones, boneAnimation.boneName(), boneAnimation.targetKind());
					BoneAnimationQueue boneAnimationQueue = key == null ? null : this.boneAnimationQueues.get(key);
					BoneSnapshot boneSnapshot = key == null ? null : this.boneSnapshots.get(key);
					PosableBone bone = key == null ? null : bones.get(key);

					if (boneSnapshot == null)
						continue;

					if (bone == null) {
						if (crashWhenCantFindBone)
							throw new RuntimeException("Could not find bone: " + boneAnimation.boneName());

						continue;
					}

					KeyframeStack<Keyframe<MathValue>> rotationKeyFrames = boneAnimation.rotationKeyFrames();
					KeyframeStack<Keyframe<MathValue>> positionKeyFrames = boneAnimation.positionKeyFrames();
					KeyframeStack<Keyframe<MathValue>> scaleKeyFrames = boneAnimation.scaleKeyFrames();

					if (!rotationKeyFrames.xKeyframes().isEmpty()) {
						boneAnimationQueue.addNextRotation(null, adjustedTick, this.transitionLength, boneSnapshot, bone.getInitialSnapshot(),
								getAnimationPointAtTick(rotationKeyFrames.xKeyframes(), 0, true, Axis.X),
								getAnimationPointAtTick(rotationKeyFrames.yKeyframes(), 0, true, Axis.Y),
								getAnimationPointAtTick(rotationKeyFrames.zKeyframes(), 0, true, Axis.Z));
					}

					if (!positionKeyFrames.xKeyframes().isEmpty()) {
						boneAnimationQueue.addNextPosition(null, adjustedTick, this.transitionLength, boneSnapshot,
								getAnimationPointAtTick(positionKeyFrames.xKeyframes(), 0, false, Axis.X),
								getAnimationPointAtTick(positionKeyFrames.yKeyframes(), 0, false, Axis.Y),
								getAnimationPointAtTick(positionKeyFrames.zKeyframes(), 0, false, Axis.Z));
					}

					if (!scaleKeyFrames.xKeyframes().isEmpty()) {
						boneAnimationQueue.addNextScale(null, adjustedTick, this.transitionLength, boneSnapshot,
								getAnimationPointAtTick(scaleKeyFrames.xKeyframes(), 0, false, Axis.X),
								getAnimationPointAtTick(scaleKeyFrames.yKeyframes(), 0, false, Axis.Y),
								getAnimationPointAtTick(scaleKeyFrames.zKeyframes(), 0, false, Axis.Z));
					}
				}
			}
		}
	}

	/**
	 * {@link #process}'s {@link AuthorityMode#SERVER} path
	 * <p>
	 * Deliberately a completely separate, much smaller path rather than a few branches threaded
	 * through the client-decided one above: the two have almost nothing in common. There is no
	 * {@link #stateHandler} to run (the mirrored {@link AnimationPlayback} <i>is</i> the decision),
	 * no {@link #animationQueue} or multi-stage {@link RawAnimation} chaining (one controller mirrors
	 * one clip at a time), no {@link #adjustTick}
	 * offset-tracking (the tick comes from {@link AnimationPlayback#sampleTick}, which is already an
	 * absolute position - nothing to offset from), and no transition blending between animations
	 * (not yet; a transition is just "play" on the
	 * wire). Looping is entirely {@link AnimationPlayback#sampleTick}'s job, not
	 * {@link AnimationProcessor.QueuedAnimation#loopType}'s - the server decided the loop mode when
	 * it started the playback, and the sample already reflects it
	 * <p>
	 * Sound/particle/custom-instruction keyframe events are not fired here. They are explicitly out
	 * of scope here (typed events are handled separately) - reusing the
	 * legacy per-frame "has this tick range been crossed" firing here would double-fire or miss-fire
	 * across a sequence-driven jump (a fresh snapshot, a rate change) in a way the existing mechanism
	 * was never designed to survive, and getting that right belongs with the typed system, not
	 * bolted onto this one
	 */
	private final Set<String> warnedMissingClips = new HashSet<>();

	/** One warning per clip name and controller, not one per frame: a clip the client cannot find stays missing until its file does */
	private void warnMissingServerClip(String clip, String why) {
		if (this.warnedMissingClips.add(clip))
			StrutConstants.LOGGER.log(Level.WARN, "The server plays clip \"" + clip + "\" on controller \"" + getName() + "\" of " + this.animatable.getClass().getSimpleName()
					+ ", but " + why + "; nothing is shown for it until it is found (logged once)");
	}

	/** When this server-driven controller blends into a new clip, and from what: the client part of a transition, set with {@link #transitionLength} */
	private final PlaybackBlend<Map<BoneKey, BoneSnapshot>> serverBlend = new PlaybackBlend<>();

	private void processServerAuthoritative(StrutModel<T> model, AnimationState<T> state, Map<BoneKey, PosableBone> bones,
											Map<BoneKey, BoneSnapshot> snapshots, boolean crashWhenCantFindBone) {
		Entity owner = ownerOf(state);
		AnimationPlayback playback = owner == null ? null : mirroredPlayback(owner);

		if (playback == null) {
			this.serverBlend.reset();
			this.animationState = State.STOPPED;

			return;
		}

		Animation animation;

		try {
			animation = model.getAnimation(this.animatable, playback.animation());
		}
		catch (RuntimeException ex) {
			warnMissingServerClip(playback.animation(), "its animation file could not be found");

			this.animationState = State.STOPPED;

			return;
		}

		if (animation == null) {
			// A playback can reach a client a frame before the data that picks its animation file (tracked data on the same tick): nothing is shown for the clip,
			// it is not an error and it does not throw. The controller shows no pose from it, and the next frame looks again
			warnMissingServerClip(playback.animation(), "no clip of that name is in the model's animation files");
			this.animationState = State.STOPPED;

			return;
		}

		this.animationState = State.RUNNING;

		createInitialQueues(bones);

		long gameTime = owner.getWorld().getTime();
		double adjustedTick = playback.sampleTick(animation.length(), gameTime, state.getPartialTick());

		MathParser.setVariable(MolangQueries.ANIM_TIME, () -> adjustedTick / 20d);

		// the server says which clip plays; whether it cuts or blends into it is decided here, from the pose this controller was showing when the clip changed
		double blendElapsed = this.serverBlend.observe(playback.animation(), gameTime + state.getPartialTick(), this.transitionLength, () -> {
			Map<BoneKey, BoneSnapshot> copy = new HashMap<>();

			snapshots.forEach((key, snapshot) -> copy.put(key, BoneSnapshot.copy(snapshot)));

			return copy;
		});

		applyBoneAnimations(animation, adjustedTick, crashWhenCantFindBone, blendElapsed >= 0 ? this.serverBlend.from() : null, blendElapsed);
	}

	/**
	 * How long, in game ticks, between two resync requests {@link #requestResyncIfDue} sends for the
	 * same controller
	 * <p>
	 * A player joining tracking range and the server's own start-tracking snapshot arriving are not
	 * instant - without a throttle, every tick between "this entity has no mirror yet" becoming true
	 * and the legitimate snapshot actually arriving would fire its own resync request. Five seconds
	 * is long enough for one round trip to comfortably land, short enough that a request lost for
	 * some other reason does not leave the entity stuck silent for long
	 */
	private static final long RESYNC_REQUEST_THROTTLE_TICKS = 100;

	private long lastResyncRequestGameTick = Long.MIN_VALUE;

	/**
	 * This controller's mirrored {@link AnimationPlayback}, or null if there is none - either
	 * because nothing has ever told the server to play anything on this controller (an entirely
	 * normal state {@link ClientAnimationState#get} answers with null just as readily, and not
	 * itself worth a resync), or because this controller's entity has {@link ClientAnimationState no
	 * mirror at all} yet, which is not normal once tracking has started and is what
	 * {@link #requestResyncIfDue} exists for - or (should not happen given how
	 * {@link #authorityMode}'s default is computed, but checked rather than assumed) this
	 * controller's animatable is not an {@link Entity} at all, and so has no entity id for
	 * {@link ClientAnimationStates} to be keyed by
	 */
	@Nullable
	private AnimationPlayback mirroredPlayback(Entity entity) {
		ClientAnimationState mirror = ClientAnimationStates.get(entity.getId());

		if (mirror == null) {
			requestResyncIfDue(entity);

			return null;
		}

		return mirror.get(this.name);
	}

	/**
	 * The entity whose server-driven playback this controller shows: the animatable itself if it is an entity, else the entity a replaced-entity renderer put in
	 * the state (a player's body is not an entity, the player it is drawn for is), or null if there is none
	 */
	@Nullable
	private Entity ownerOf(AnimationState<T> state) {
		if (this.animatable instanceof Entity entity)
			return entity;

		return state.owner();
	}

	/**
	 * Asks the server for {@code entity}'s full state, throttled per
	 * {@link #RESYNC_REQUEST_THROTTLE_TICKS} so a legitimately in-flight snapshot gets a fair chance
	 * to arrive before a second request goes out
	 */
	private void requestResyncIfDue(Entity entity) {
		long now = entity.getWorld().getTime();

		if (now - this.lastResyncRequestGameTick < RESYNC_REQUEST_THROTTLE_TICKS)
			return;

		this.lastResyncRequestGameTick = now;

		ClientAnimationStates.requestResync(entity.getId());
	}

	/**
	 * Populates every registered bone's {@link BoneAnimationQueue} for one tick of {@code animation},
	 * shared by both {@link #processCurrentAnimation} (client-decided, transition-aware) and
	 * {@link #processServerAuthoritative} (server-decided, no transition) - the two arrive at an
	 * {@code adjustedTick} completely differently, but once they have one, reading keyframes off it
	 * is identical either way
	 */
	private void applyBoneAnimations(Animation animation, double adjustedTick, boolean crashWhenCantFindBone) {
		applyBoneAnimations(animation, adjustedTick, crashWhenCantFindBone, null, 0);
	}

	/**
	 * @param blendFrom The poses to blend from, when this clip has just replaced another on a server-driven controller (see {@link PlaybackBlend}); null for no blend.
	 *                  Each bone then goes from its pose in {@code blendFrom} to where the clip puts it now, over {@link #transitionLength} ticks, {@code blendElapsed}
	 *                  of them gone, so the target keeps moving with the clip rather than waiting at its first frame
	 */
	private void applyBoneAnimations(Animation animation, double adjustedTick, boolean crashWhenCantFindBone,
									 @Nullable Map<BoneKey, BoneSnapshot> blendFrom, double blendElapsed) {
		for (BoneAnimation boneAnimation : animation.boneAnimations()) {
			BoneKey key = BoneKey.resolve(this.boneAnimationQueues, boneAnimation.boneName(), boneAnimation.targetKind());
			BoneAnimationQueue boneAnimationQueue = key == null ? null : this.boneAnimationQueues.get(key);

			if (boneAnimationQueue == null) {
				if (crashWhenCantFindBone)
					throw new RuntimeException("Could not find bone: " + boneAnimation.boneName());

				continue;
			}

			KeyframeStack<Keyframe<MathValue>> rotationKeyFrames = boneAnimation.rotationKeyFrames();
			KeyframeStack<Keyframe<MathValue>> positionKeyFrames = boneAnimation.positionKeyFrames();
			KeyframeStack<Keyframe<MathValue>> scaleKeyFrames = boneAnimation.scaleKeyFrames();

			BoneKey snapshotKey = blendFrom == null ? null : BoneKey.resolve(blendFrom, boneAnimation.boneName(), boneAnimation.targetKind());
			BoneSnapshot from = snapshotKey == null ? null : blendFrom.get(snapshotKey);

			if (!rotationKeyFrames.xKeyframes().isEmpty()) {
				AnimationPoint x = getAnimationPointAtTick(rotationKeyFrames.xKeyframes(), adjustedTick, true, Axis.X);
				AnimationPoint y = getAnimationPointAtTick(rotationKeyFrames.yKeyframes(), adjustedTick, true, Axis.Y);
				AnimationPoint z = getAnimationPointAtTick(rotationKeyFrames.zKeyframes(), adjustedTick, true, Axis.Z);

				if (from != null) {
					BoneSnapshot initial = boneAnimationQueue.bone().getInitialSnapshot();

					boneAnimationQueue.addRotationXPoint(null, blendElapsed, this.transitionLength, from.getRotX() - initial.getRotX(), EasingType.lerpWithOverride(x, null));
					boneAnimationQueue.addRotationYPoint(null, blendElapsed, this.transitionLength, from.getRotY() - initial.getRotY(), EasingType.lerpWithOverride(y, null));
					boneAnimationQueue.addRotationZPoint(null, blendElapsed, this.transitionLength, from.getRotZ() - initial.getRotZ(), EasingType.lerpWithOverride(z, null));
				}
				else {
					boneAnimationQueue.addRotations(x, y, z);
				}
			}

			if (!positionKeyFrames.xKeyframes().isEmpty()) {
				AnimationPoint x = getAnimationPointAtTick(positionKeyFrames.xKeyframes(), adjustedTick, false, Axis.X);
				AnimationPoint y = getAnimationPointAtTick(positionKeyFrames.yKeyframes(), adjustedTick, false, Axis.Y);
				AnimationPoint z = getAnimationPointAtTick(positionKeyFrames.zKeyframes(), adjustedTick, false, Axis.Z);

				if (from != null) {
					boneAnimationQueue.addPosXPoint(null, blendElapsed, this.transitionLength, from.getOffsetX(), EasingType.lerpWithOverride(x, null));
					boneAnimationQueue.addPosYPoint(null, blendElapsed, this.transitionLength, from.getOffsetY(), EasingType.lerpWithOverride(y, null));
					boneAnimationQueue.addPosZPoint(null, blendElapsed, this.transitionLength, from.getOffsetZ(), EasingType.lerpWithOverride(z, null));
				}
				else {
					boneAnimationQueue.addPositions(x, y, z);
				}
			}

			if (!scaleKeyFrames.xKeyframes().isEmpty()) {
				AnimationPoint x = getAnimationPointAtTick(scaleKeyFrames.xKeyframes(), adjustedTick, false, Axis.X);
				AnimationPoint y = getAnimationPointAtTick(scaleKeyFrames.yKeyframes(), adjustedTick, false, Axis.Y);
				AnimationPoint z = getAnimationPointAtTick(scaleKeyFrames.zKeyframes(), adjustedTick, false, Axis.Z);

				if (from != null) {
					boneAnimationQueue.addScaleXPoint(null, blendElapsed, this.transitionLength, from.getScaleX(), EasingType.lerpWithOverride(x, null));
					boneAnimationQueue.addScaleYPoint(null, blendElapsed, this.transitionLength, from.getScaleY(), EasingType.lerpWithOverride(y, null));
					boneAnimationQueue.addScaleZPoint(null, blendElapsed, this.transitionLength, from.getScaleZ(), EasingType.lerpWithOverride(z, null));
				}
				else {
					boneAnimationQueue.addScales(x, y, z);
				}
			}
		}
	}

	/**
	 * Handle the current animation's state modifications and translations
	 *
	 * @param adjustedTick The controller-adjusted tick for animation purposes
	 * @param seekTime The lerped tick (current tick + partial tick)
	 * @param crashWhenCantFindBone Whether the controller should throw an exception when unable to find the required bone, or continue with the remaining bones
	 */
	private void processCurrentAnimation(double adjustedTick, double seekTime, boolean crashWhenCantFindBone) {
		// a looping clip of length 0 (one that is only molang, such as a bone driven by math.sin(query.anim_time)) has no end to loop at: its time runs on
		boolean endless = this.currentAnimation.animation().length() <= 0 && this.currentAnimation.loopType().shouldPlayAgain(this.animatable, this, this.currentAnimation.animation());

		if (!endless && adjustedTick >= this.currentAnimation.animation().length()) {
			if (this.currentAnimation.loopType().shouldPlayAgain(this.animatable, this, this.currentAnimation.animation())) {
				if (this.animationState != State.PAUSED) {
					this.shouldResetTick = true;

					adjustedTick = adjustTick(seekTime);
					resetEventKeyFrames();
				}
			}
			else {
				AnimationProcessor.QueuedAnimation nextAnimation = this.animationQueue.peek();

				resetEventKeyFrames();

				if (nextAnimation == null) {
					beginBlendOut(seekTime);
					this.animationState = State.STOPPED;

					return;
				}
				else if (this.transitionLength > 0) {
					// Into the next stage the way a new animation is entered: this frame shows the last frame of the stage that ended, and the next frame
					// takes the next stage from the queue and blends to it from there. Taking it from the queue here as well left the transition with nothing to
					// take, so the bones fell to the base pose for the length of the blend and only then went to the next stage (a jerk between a charge and its hold)
					this.animationState = State.TRANSITIONING;
					this.shouldResetTick = true;
					this.justStartedTransition = true;
					this.captureStageEnd = true;
					adjustedTick = this.currentAnimation.animation().length();
				}
				else {
					this.animationState = State.TRANSITIONING;
					this.shouldResetTick = true;
					adjustedTick = adjustTick(seekTime);
					this.currentAnimation = this.animationQueue.poll();
				}
			}
		}

		final double finalAdjustedTick = adjustedTick;

		MathParser.setVariable(MolangQueries.ANIM_TIME, () -> finalAdjustedTick / 20d);

		this.blendOutStart = -1;
		applyBoneAnimations(this.currentAnimation.animation(), adjustedTick, crashWhenCantFindBone);

		if (this.captureStageEnd) {
			this.captureStageEnd = false;
			captureStageEnd();
		}

		adjustedTick += this.transitionLength;

		for (SoundKeyframeData keyframeData : this.currentAnimation.animation().keyFrames().sounds()) {
			if (adjustedTick >= keyframeData.getStartTick() && this.executedKeyFrames.add(keyframeData)) {
				if (this.soundKeyframeHandler == null) {
					StrutConstants.LOGGER.log(Level.WARN, "Sound Keyframe found for " + this.animatable.getClass().getSimpleName() + " -> " + getName() + ", but no keyframe handler registered");

					break;
				}

				this.soundKeyframeHandler.handle(new SoundKeyframeEvent<>(this.animatable, adjustedTick, this, keyframeData));
			}
		}

		for (ParticleKeyframeData keyframeData : this.currentAnimation.animation().keyFrames().particles()) {
			if (adjustedTick >= keyframeData.getStartTick() && this.executedKeyFrames.add(keyframeData)) {
				if (this.particleKeyframeHandler == null) {
					StrutConstants.LOGGER.log(Level.WARN, "Particle Keyframe found for " + this.animatable.getClass().getSimpleName() + " -> " + getName() + ", but no keyframe handler registered");

					break;
				}

				this.particleKeyframeHandler.handle(new ParticleKeyframeEvent<>(this.animatable, adjustedTick, this, keyframeData));
			}
		}

		for (CustomInstructionKeyframeData keyframeData : this.currentAnimation.animation().keyFrames().customInstructions()) {
			if (adjustedTick >= keyframeData.getStartTick() && this.executedKeyFrames.add(keyframeData)) {
				if (this.customKeyframeHandler == null) {
					StrutConstants.LOGGER.log(Level.WARN, "Custom Instruction Keyframe found for " + this.animatable.getClass().getSimpleName() + " -> " + getName() + ", but no keyframe handler registered");

					break;
				}

				this.customKeyframeHandler.handle(new CustomInstructionKeyframeEvent<>(this.animatable, adjustedTick, this, keyframeData));
			}
		}

		if (this.transitionLength == 0 && this.shouldResetTick && this.animationState == State.TRANSITIONING)
			this.currentAnimation = this.animationQueue.poll();
	}

	/** Set when a stage of a chain has ended this frame: its last frame is recorded once it has been queued (see {@link #captureStageEnd()}) */
	private boolean captureStageEnd;

	/** Where the stage that just ended left each bone it animates, for the next stage to blend from */
	private final Map<BoneKey, StageEnd> stageEnd = new HashMap<>();

	/** One bone's pose at the end of a stage, for the channels the stage animates (null for one it does not) */
	private record StageEnd(float @Nullable [] rotation, float @Nullable [] offset, float @Nullable [] scale) {
		void applyTo(BoneSnapshot snapshot) {
			if (this.rotation != null)
				snapshot.updateRotation(this.rotation[0], this.rotation[1], this.rotation[2]);

			if (this.offset != null)
				snapshot.updateOffset(this.offset[0], this.offset[1], this.offset[2]);

			if (this.scale != null)
				snapshot.updateScale(this.scale[0], this.scale[1], this.scale[2]);
		}
	}

	/**
	 * Records where the stage that has just ended puts each of its bones, from the points just queued for its last frame
	 * <p>
	 * The next stage blends from there. The snapshots the processor keeps hold what each bone was last given by any controller: when the next stage is taken
	 * from the queue, a controller processed earlier in the same frame (the legs' locomotion, under a bow's charge) has already given the arms its own pose,
	 * and blending from that took the arms down to it and back up again
	 */
	private void captureStageEnd() {
		this.stageEnd.clear();

		EasingType easing = this.overrideEasingTypeFunction.apply(this.animatable);

		for (Map.Entry<BoneKey, BoneAnimationQueue> entry : this.boneAnimationQueues.entrySet()) {
			BoneAnimationQueue queue = entry.getValue();
			AnimationPoint rotX = queue.rotationXQueue().peekLast();
			AnimationPoint rotY = queue.rotationYQueue().peekLast();
			AnimationPoint rotZ = queue.rotationZQueue().peekLast();
			AnimationPoint posX = queue.positionXQueue().peekLast();
			AnimationPoint posY = queue.positionYQueue().peekLast();
			AnimationPoint posZ = queue.positionZQueue().peekLast();
			AnimationPoint scaleX = queue.scaleXQueue().peekLast();
			AnimationPoint scaleY = queue.scaleYQueue().peekLast();
			AnimationPoint scaleZ = queue.scaleZQueue().peekLast();
			boolean rotated = rotX != null && rotY != null && rotZ != null;
			boolean moved = posX != null && posY != null && posZ != null;
			boolean scaled = scaleX != null && scaleY != null && scaleZ != null;

			if (!rotated && !moved && !scaled)
				continue;

			BoneSnapshot initial = queue.bone().getInitialSnapshot();
			StageEnd end = new StageEnd(
					rotated ? new float[] {(float) EasingType.lerpWithOverride(rotX, easing) + initial.getRotX(), (float) EasingType.lerpWithOverride(rotY, easing) + initial.getRotY(),
							(float) EasingType.lerpWithOverride(rotZ, easing) + initial.getRotZ()} : null,
					moved ? new float[] {(float) EasingType.lerpWithOverride(posX, easing), (float) EasingType.lerpWithOverride(posY, easing), (float) EasingType.lerpWithOverride(posZ, easing)} : null,
					scaled ? new float[] {(float) EasingType.lerpWithOverride(scaleX, easing), (float) EasingType.lerpWithOverride(scaleY, easing), (float) EasingType.lerpWithOverride(scaleZ, easing)} : null);

			this.stageEnd.put(entry.getKey(), end);
		}
	}

	/**
	 * Prepare the {@link BoneAnimationQueue} map for the current render frame
	 *
	 * @param bones The registered bones from the {@link AnimationProcessor}, keyed the same way
	 */
	private void createInitialQueues(Map<BoneKey, PosableBone> bones) {
		this.boneAnimationQueues.clear();

		for (Map.Entry<BoneKey, PosableBone> entry : bones.entrySet()) {
			this.boneAnimationQueues.put(entry.getKey(), new BoneAnimationQueue(entry.getValue()));
		}
	}

	/**
	 * Cache the relevant {@link BoneSnapshot BoneSnapshots} for the current {@link AnimationProcessor.QueuedAnimation}
	 * for animation lerping
	 *
	 * @param animation The {@code QueuedAnimation} to filter {@code BoneSnapshots} for
	 * @param snapshots The master snapshot collection to pull filter from
	 */
	private void saveSnapshotsForAnimation(AnimationProcessor.QueuedAnimation animation, Map<BoneKey, BoneSnapshot> snapshots) {
		if (animation.animation().boneAnimations() == null)
			return;

		for (BoneAnimation boneAnimation : animation.animation().boneAnimations()) {
			BoneKey key = BoneKey.resolve(snapshots, boneAnimation.boneName(), boneAnimation.targetKind());

			if (key != null)
				this.boneSnapshots.put(key, BoneSnapshot.copy(snapshots.get(key)));
		}
	}

	/**
	 * Adjust a tick value depending on the controller's current state and speed modifier
	 * <p>
	 * Is used when starting a new animation, transitioning, and a few other key areas
	 *
	 * @param tick The currently used tick value
	 * @return 0 if {@link #shouldResetTick} is set to false, or a {@link #animationSpeedModifier} modified value otherwise
	 */
	protected double adjustTick(double tick) {
		if (!this.shouldResetTick)
			return this.animationSpeedModifier.apply(this.animatable) * Math.max(tick - this.tickOffset, 0);

		if (getAnimationState() != State.STOPPED)
			this.tickOffset = tick;

		this.shouldResetTick = false;

		return 0;
	}

	/**
	 * Convert a {@link KeyframeLocation} to an {@link AnimationPoint}
	 */
	private AnimationPoint getAnimationPointAtTick(List<Keyframe<MathValue>> frames, double tick, boolean isRotation,
												   Axis axis) {
		KeyframeLocation<Keyframe<MathValue>> location = getCurrentKeyFrameLocation(frames, tick);
		Keyframe<MathValue> currentFrame = location.keyframe();
		double startValue = currentFrame.startValue().get();
		double endValue = currentFrame.endValue().get();

		if (isRotation) {
			if (!(currentFrame.startValue() instanceof Constant)) {
				startValue = Math.toRadians(startValue);

				if (axis == Axis.X || axis == Axis.Y)
					startValue *= -1;
			}

			if (!(currentFrame.endValue() instanceof Constant)) {
				endValue = Math.toRadians(endValue);

				if (axis == Axis.X || axis == Axis.Y)
					endValue *= -1;
			}
		}

		return new AnimationPoint(currentFrame, location.startTick(), currentFrame.length(), startValue, endValue);
	}

	/**
	 * Returns the {@link Keyframe} relevant to the current tick time
	 *
	 * @param frames The list of {@code KeyFrames} to filter through
	 * @param ageInTicks The current tick time
	 * @return A new {@code KeyFrameLocation} containing the current {@code KeyFrame} and the tick time used to find it
	 */
	private KeyframeLocation<Keyframe<MathValue>> getCurrentKeyFrameLocation(List<Keyframe<MathValue>> frames,
																			 double ageInTicks) {
		double totalFrameTime = 0;

		for (Keyframe<MathValue> frame : frames) {
			totalFrameTime += frame.length();

			if (totalFrameTime > ageInTicks)
				return new KeyframeLocation<>(frame, (ageInTicks - (totalFrameTime - frame.length())));
		}

		return new KeyframeLocation<>(frames.getLast(), ageInTicks);
	}

	/**
	 * Clear the {@link KeyFrameData} cache in preparation for the next animation
	 */
	private void resetEventKeyFrames() {
		this.executedKeyFrames.clear();
	}

	/**
	 * Every render frame, the {@code AnimationController} will call this handler for <u>each</u> animatable that is being rendered
	 * <p>
	 * This handler defines which animation should be currently playing, and returning a {@link PlayState} to tell the controller what to do next
	 * <p>
	 * Example Usage:
	 * <pre>{@code
	 * AnimationFrameHandler myIdleWalkHandler = state -> {
	 *	if (state.isMoving()) {
	 *		state.getController().setAnimation(myWalkAnimation);
	 *	}
	 *	else {
	 *		state.getController().setAnimation(myIdleAnimation);
	 *	}
	 *
	 *	return PlayState.CONTINUE;
	 *};}</pre>
	 */
	@FunctionalInterface
	public interface AnimationStateHandler<A extends StrutAnimatable> {
		/**
		 * The handling method, called each frame
		 * <p>
		 * Return {@link PlayState#CONTINUE} to tell the controller to continue animating,
		 * or return {@link PlayState#STOP} to tell it to stop playing all animations and wait for the next {@link PlayState#CONTINUE} return.
		 */
		PlayState handle(AnimationState<A> state);
	}

	/**
	 * A handler for when a predefined sound keyframe is hit
	 * <p>
	 * When the keyframe is encountered, the {@link SoundKeyframeHandler#handle(SoundKeyframeEvent)} method will be called.
	 * Play the sound(s) of your choice at this time.
	 */
	@FunctionalInterface
	public interface SoundKeyframeHandler<A extends StrutAnimatable> {
		void handle(SoundKeyframeEvent<A> event);
	}

	/**
	 * A handler for when a predefined particle keyframe is hit
	 * <p>
	 * When the keyframe is encountered, the {@link ParticleKeyframeHandler#handle(ParticleKeyframeEvent)} method will be called.
	 * Spawn the particles/effects of your choice at this time.
	 */
	@FunctionalInterface
	public interface ParticleKeyframeHandler<A extends StrutAnimatable> {
		void handle(ParticleKeyframeEvent<A> event);
	}

	/**
	 * A handler for pre-defined custom instruction keyframes
	 * <p>
	 * When the keyframe is encountered, the {@link CustomKeyframeHandler#handle(CustomInstructionKeyframeEvent)} method will be called.
	 * You can then take whatever action you want at this point.
	 */
	@FunctionalInterface
	public interface CustomKeyframeHandler<A extends StrutAnimatable> {
		void handle(CustomInstructionKeyframeEvent<A> event);
	}

	public enum State {
		RUNNING,
		TRANSITIONING,
		PAUSED,
		STOPPED
	}
}