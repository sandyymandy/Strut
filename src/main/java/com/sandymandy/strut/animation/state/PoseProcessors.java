package com.sandymandy.strut.animation.state;

import org.jetbrains.annotations.ApiStatus;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The registered {@link PoseProcessor}s, run in registration order for every instance whose model one of them accepts
 */
@ApiStatus.Experimental
public final class PoseProcessors {
	private static final CopyOnWriteArrayList<PoseProcessor> PROCESSORS = new CopyOnWriteArrayList<>();
	private static volatile PoseProcessor[] snapshot = new PoseProcessor[0];

	private PoseProcessors() {}

	/**
	 * Adds a processor. Call it during mod initialisation
	 */
	public static synchronized void register(PoseProcessor processor) {
		PROCESSORS.add(processor);
		snapshot = PROCESSORS.toArray(new PoseProcessor[0]);
	}

	/**
	 * Removes a processor, mostly for tests
	 */
	public static synchronized void unregister(PoseProcessor processor) {
		PROCESSORS.remove(processor);
		snapshot = PROCESSORS.toArray(new PoseProcessor[0]);
	}

	/** The processors in run order; an empty array (the common case) means the hook costs one volatile read */
	static PoseProcessor[] all() {
		return snapshot;
	}
}
