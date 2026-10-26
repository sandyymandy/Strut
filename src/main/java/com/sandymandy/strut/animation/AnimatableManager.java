package com.sandymandy.strut.animation;

import com.sandymandy.strut.animation.authority.AnimationPlayback;
import it.unimi.dsi.fastutil.objects.Object2ObjectArrayMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.Collection;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import com.sandymandy.strut.animatable.StrutAnimatable;
import com.sandymandy.strut.animation.state.BoneSnapshot;
import com.sandymandy.strut.animation.state.ModelInstance;
import com.sandymandy.strut.animation.state.SkinningState;
import com.sandymandy.strut.constant.dataticket.DataTicket;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * The animation data collection for a given animatable instance
 * <p>
 * Generally speaking, a single working-instance of an {@link StrutAnimatable Animatable}
 * will have a single instance of {@code AnimatableManager} associated with it
 */
public class AnimatableManager<T extends StrutAnimatable> {
	private final Map<BoneKey, BoneSnapshot> boneSnapshotCollection = new Object2ObjectOpenHashMap<>();
	private final SkinningState skinningState = new SkinningState();
	private final ModelInstance modelInstance = new ModelInstance();
	private final Map<String, AnimationController<T>> animationControllers;
	private Map<DataTicket<?>, Object> extraData;

	private double lastUpdateTime;
	private boolean isFirstTick = true;
	private double firstTickTime = -1;

	private double animTime;
	private double lastGameTickTime;
	private long lastRenderedInstance = -1;

	/**
	 * Instantiates a new AnimatableManager for the given animatable, calling {@link StrutAnimatable#registerControllers} to define its controllers
	 */
	public AnimatableManager(StrutAnimatable animatable) {
		ControllerRegistrar registrar = new ControllerRegistrar(new ObjectArrayList<>(2));

		animatable.registerControllers(registrar);

		this.animationControllers = registrar.build();
	}

	/**
	 * Add an {@link AnimationController} to this animatable's manager
	 * <p>
	 * Generally speaking, you probably should have added it during {@link StrutAnimatable#registerControllers}
	 */
	public void addController(AnimationController controller) {
		getAnimationControllers().put(controller.getName(), controller);
	}

	/**
	 * Makes sure there is a controller for every controller name the server is playing something on
	 * <p>
	 * A server-driven entity is not required to register controllers: the first time the server plays an animation on a name
	 * that has none, a default controller is created here. It has no handler of its own (the server decides what plays), and is
	 * server-authoritative for a {@code StrutEntity}. Controllers the animatable did register are never touched
	 *
	 * @param playbacks What the server currently has playing for this animatable
	 */
	public void ensureControllersFor(T animatable, Collection<AnimationPlayback> playbacks) {
		for (AnimationPlayback playback : playbacks) {
			if (!getAnimationControllers().containsKey(playback.controller()))
				addController(new AnimationController<>(animatable, playback.controller(), state -> PlayState.STOP));
		}
	}

	/**
	 * Removes an {@link AnimationController} from this manager by the given name, if present.
	 */
	public void removeController(String name) {
		getAnimationControllers().remove(name);
	}

	/**
	 * This instance's skinning buffers
	 * <p>
	 * Lives here because it is per-animatable-instance state, and this is where per-instance
	 * animation state lives. Holding it on the shared {@link com.sandymandy.strut.cache.object.BakedStrutModel}
	 * instead would let two animatables using one model overwrite each other's pose
	 */
	public SkinningState getSkinningState() {
		return this.skinningState;
	}

	/**
	 * This instance's model-shaped state - currently just its {@link com.sandymandy.strut.animation.state.Pose Pose}
	 * <p>
	 * Same reasoning as {@link #getSkinningState()}: it is per-instance, so it lives where
	 * per-instance state lives, not on the shared {@link com.sandymandy.strut.cache.object.BakedStrutModel}
	 */
	public ModelInstance getModelInstance() {
		return this.modelInstance;
	}

	public Map<String, AnimationController<T>> getAnimationControllers() {
		return this.animationControllers;
	}

	public Map<BoneKey, BoneSnapshot> getBoneSnapshotCollection() {
		return this.boneSnapshotCollection;
	}

	public void clearSnapshotCache() {
		getBoneSnapshotCollection().clear();
	}

	public double getLastUpdateTime() {
		return this.lastUpdateTime;
	}

	public void updatedAt(double updateTime) {
		this.lastUpdateTime = updateTime;
	}

	public double getFirstTickTime() {
		return this.firstTickTime;
	}

	public void startedAt(double time) {
		this.firstTickTime = time;
	}

	public boolean isFirstTick() {
		return this.isFirstTick;
	}

	protected void finishFirstTick() {
		this.isFirstTick = false;
	}

	/**
	 * This instance's animation clock
	 * <p>
	 * Used to be a single clock shared by every instance of a {@link com.sandymandy.strut.model.StrutModel type}
	 * - two instances animated at different speeds or phases would accumulate onto the same
	 * {@code animTime}. Lives here now, per instance, for the same reason {@link #getModelInstance()}
	 * does
	 */
	public double getAnimTime() {
		return this.animTime;
	}

	/**
	 * Advances this instance's animation clock to account for the time elapsed since the last call,
	 * and returns the new value
	 *
	 * @param lastUpdateTime This instance's current frame time, as tracked by {@link #updatedAt}
	 */
	public double advanceAnimTime(double lastUpdateTime) {
		this.animTime += lastUpdateTime - this.lastGameTickTime;
		this.lastGameTickTime = lastUpdateTime;

		return this.animTime;
	}

	/**
	 * Whether this instance was already the last one processed under the given instance id
	 * <p>
	 * Backs the re-render short-circuit in {@link com.sandymandy.strut.model.StrutModel#handleAnimations}:
	 * a renderer may call {@code handleAnimations} more than once per frame for the same instance
	 * (e.g. multiple render passes), and reprocessing animation for a frame that hasn't advanced is
	 * wasted work. Scoped to this manager - which belongs to exactly one instance - rather than to
	 * the shared {@code StrutModel}, so one instance's re-render can no longer be missed or
	 * false-skipped because another instance of the same type rendered in between
	 */
	public boolean wasLastRendered(long instanceId) {
		return instanceId == this.lastRenderedInstance;
	}

	/**
	 * Records that this instance was just processed under the given instance id, for
	 * {@link #wasLastRendered}
	 */
	public void markRendered(long instanceId) {
		this.lastRenderedInstance = instanceId;
	}

	/**
	 * Set a custom data point to be used later
	 *
	 * @param dataTicket The DataTicket for the data point
	 * @param data The piece of data to store
	 */
	public <D> void setData(DataTicket<D> dataTicket, D data) {
		if (this.extraData == null)
			this.extraData = new Object2ObjectOpenHashMap<>();

		this.extraData.put(dataTicket, data);
	}

	/**
	 * Retrieve a custom data point that was stored earlier, or null if it hasn't been stored
	 */
	public <D> D getData(DataTicket<D> dataTicket) {
		return this.extraData != null ? dataTicket.getData(this.extraData) : null;
	}

	/**
	 * Attempt to trigger an animation from a given controller name and registered triggerable animation name
	 * <p>
	 * This pseudo-overloaded method checks each controller in turn until one of them accepts the trigger
	 * <p>
	 * This can be sped up by specifying which controller you intend to receive the trigger in {@link AnimatableManager#tryTriggerAnimation(String, String)}
	 *
	 * @param animName The name of animation to trigger. This needs to have been registered with the controller via {@link AnimationController#triggerableAnim AnimationController.triggerableAnim}
	 */
	public void tryTriggerAnimation(String animName) {
		for (AnimationController<?> controller : getAnimationControllers().values()) {
			if (controller.tryTriggerAnimation(animName))
				return;
		}
	}

	/**
	 * Attempt to trigger an animation from a given controller name and registered triggerable animation name
	 *
	 * @param controllerName The name of the controller the animation belongs to
	 * @param animName The name of animation to trigger. This needs to have been registered with the controller via {@link AnimationController#triggerableAnim AnimationController.triggerableAnim}
	 */
	public void tryTriggerAnimation(String controllerName, String animName) {
		AnimationController<?> controller = getAnimationControllers().get(controllerName);

		if (controller != null)
			controller.tryTriggerAnimation(animName);
	}

	/**
	 * Stop a triggered animation, or all triggered animations, depending on the current state of animations and the passed argument
	 *
	 * @param animName The trigger name of the animation to stop, or null to stop any triggered animation
	 */
	public void stopTriggeredAnimation(@Nullable String animName) {
		for (AnimationController<?> controller : getAnimationControllers().values()) {
			if ((animName == null || controller.triggerableAnimations.get(animName) == controller.getTriggeredAnimation()) && controller.stopTriggeredAnimation())
				return;
		}
	}

	/**
	 * Stop a triggered animation or all triggered animations on a given controller, depending on the current state of animations and the passed arguments
	 *
	 * @param controllerName The name of the controller the triggered animation belongs to
	 * @param animName The trigger name of the animation to stop, or null to stop any triggered animation
	 */
	public void stopTriggeredAnimation(String controllerName, @Nullable String animName) {
		AnimationController<?> controller = getAnimationControllers().get(controllerName);

		if (controller != null && (animName == null || controller.triggerableAnimations.get(animName) == controller.getTriggeredAnimation()))
			controller.stopTriggeredAnimation();
	}

	/**
	 * Helper class for the AnimatableManager to cleanly register controllers in one shot at instantiation for efficiency
	 */
	public record ControllerRegistrar(List<AnimationController<? extends StrutAnimatable>> controllers) {
		/**
		 * Add multiple {@link AnimationController}s to this registrar
		 */
		public ControllerRegistrar add(AnimationController<?>... controllers) {
			controllers().addAll(Arrays.asList(controllers));

			return this;
		}

		/**
		 * Add an {@link AnimationController} to this registrar
		 */
		public ControllerRegistrar add(AnimationController<?> controller) {
			controllers().add(controller);

			return this;
		}

		/**
		 * Remove an {@link AnimationController} from this registrar by name
		 * <p>
		 * This is mostly only useful if you're sub-classing an existing animatable object and want to modify the super list
		 */
		public ControllerRegistrar remove(String name) {
			controllers().removeIf(controller -> controller.getName().equals(name));

			return this;
		}

		@ApiStatus.Internal
		private <T extends StrutAnimatable> Object2ObjectArrayMap<String, AnimationController<T>> build() {
			Object2ObjectArrayMap<String, AnimationController<?>> map = new Object2ObjectArrayMap<>(controllers().size());

			controllers().forEach(controller -> map.put(controller.getName(), controller));

			return (Object2ObjectArrayMap)map;
		}
	}
}
