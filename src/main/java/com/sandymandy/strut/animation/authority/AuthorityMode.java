package com.sandymandy.strut.animation.authority;

import com.sandymandy.strut.animatable.StrutEntity;
import com.sandymandy.strut.animation.AnimationController;

/**
 * Who decides what an {@link AnimationController} plays
 * <p>
 * A controller's default is computed from
 * what kind of animatable it belongs to, not declared per model - see
 * {@code AnimationController}'s own constructor
 */
public enum AuthorityMode {
	/**
	 * The server decides what plays, when it started, and at what rate. The controller's own
	 * {@link AnimationController.AnimationStateHandler} is not run at all while in this mode - the
	 * handler <i>is</i> the decision the server has taken over, and running both would mean two
	 * authorities disagreeing about the same controller
	 * <p>
	 * The default for a {@link StrutEntity} controller (one whose animatable both implements
	 * {@code StrutEntity} and is itself an {@code Entity}) - it is deliberately narrower than
	 * "entity and block-entity"
	 */
	SERVER,

	/**
	 * Reserved for later. Like {@link #SERVER}, but the client applies its own guess immediately
	 * and reconciles against the server's answer once it arrives, rather than waiting - for actions
	 * that need to feel instant locally (an attack swing on the attacker's own screen) without
	 * losing server authority over the gameplay-visible result
	 */
	SERVER_WITH_LOCAL_PREDICTION,

	/**
	 * The controller's own {@link AnimationController.AnimationStateHandler} decides what plays,
	 * exactly as every controller worked before animation became server-authoritative. The default for everything that is
	 * not a {@link StrutEntity} controller - block entities, items, GUI previews, singletons, and any
	 * {@link StrutEntity} controller that opts back in explicitly
	 */
	CLIENT_COSMETIC
}
