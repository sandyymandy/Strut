package com.sandymandy.strut.scene;

/**
 * Why a scene ended, as its end callback is told
 */
public enum SceneEnd {
	/** Every clip of the last step ran to its end and no new step was started from the step-end callback */
	COMPLETED,
	/** The mod ended it with {@link Scene#end} */
	ENDED,
	/** A required participant left: logged out, died, was removed, or changed dimension */
	PARTICIPANT_LOST,
	/** It ran longer than the time it was given ({@link StrutScenes.Builder#timeout}) */
	TIMED_OUT,
	/** The server is stopping */
	SERVER_STOPPING
}
