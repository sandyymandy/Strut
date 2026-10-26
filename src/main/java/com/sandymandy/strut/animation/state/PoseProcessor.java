package com.sandymandy.strut.animation.state;

import com.sandymandy.strut.cache.object.BakedStrutModel;
import org.jetbrains.annotations.ApiStatus;

import java.util.function.Supplier;

/**
 * A step that adjusts an instance's pose each frame, after the animated pose and any client bone overrides are resolved and before anything reads it
 * (the bone palette, tracked bone matrices, attachments, the GPU and CPU draws). This is the extension point for anything that sits on top of an
 * animation without being one: secondary motion, physics, procedural look-at
 * <p>
 * Register one with {@link PoseProcessors#register}. It costs nothing unless one is registered and its {@link #appliesTo} accepts the model
 */
@ApiStatus.Experimental
public interface PoseProcessor {
	/**
	 * Whether this processor has work for {@code model}, for example because the model carries an extension section it understands. Called every frame
	 * for every instance while a processor is registered, so it must be cheap (cache the answer per model identity)
	 */
	boolean appliesTo(BakedStrutModel model);

	/**
	 * Adjusts {@link Context#pose()}. Anything written there is what renders this frame; {@link Context#animated()} is the pose it started from and must
	 * not be modified. Nothing written to {@code pose} is kept for the next frame: the next frame starts again from the animation
	 */
	void process(Context context);

	/**
	 * @param model The instance's baked model
	 * @param animated The resolved pose before any processor ran: the animation plus client overrides. Read only
	 * @param pose The pose that will be used to render, a copy of {@code animated} that processors edit in turn
	 * @param entityId The bound entity's id, or -1 when the instance is not tied to an entity
	 * @param instance The instance, for {@link ModelInstance#processorState}
	 */
	record Context(BakedStrutModel model, Pose animated, Pose pose, int entityId, ModelInstance instance) {
		/**
		 * State a processor keeps per instance, created on first use and dropped when the instance's model changes (a resource reload), so nothing sized
		 * for an old model is ever read against a new one
		 */
		public <T> T state(Object key, Supplier<T> factory) {
			return this.instance.processorState(key, factory);
		}
	}
}
