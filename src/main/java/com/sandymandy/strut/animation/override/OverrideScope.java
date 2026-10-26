package com.sandymandy.strut.animation.override;

/**
 * How long a {@link BoneOverride} lives before it is expected to clear itself, per
 * the original scope list (minus "render layer" - {@code StrutRenderLayer} is
 * exactly what this system removes, so a scope named after it no longer means anything)
 */
public enum OverrideScope {
	/**
	 * Stays set until explicitly cleared. The default for every setter that does not name a scope
	 */
	PERSISTENT,

	/**
	 * Reserved: intended to clear automatically when the owning {@code AnimationController}'s active
	 * state changes, the same way a controller-scoped animation effect would end with the state that
	 * triggered it. Nothing clears an override at this scope yet - no controller hook calls into this
	 * package - so it behaves exactly like {@link #PERSISTENT} for now. Carried anyway, the same
	 * reasoning {@link com.sandymandy.strut.animation.authority.AnimationPlayback#sequence()} was
	 * introduced before the protocol spent it: retrofitting a scope into an already-shipped wire
	 * format is worse than carrying the value early
	 */
	CONTROLLER,

	/**
	 * Reserved: intended to clear when the entity's synchronization-group context changes (
	 * not built yet). Same status as {@link #CONTROLLER}:
	 * defined now, not yet wired to anything that clears it
	 */
	SYNC_GROUP,

	/**
	 * Clears automatically at the end of the client frame that set it - the scope a per-viewer,
	 * per-frame decision needs (the canonical case: hiding the local player's own head bone only
	 * while the camera is in first person, re-evaluated every frame). Client-only in practice:
	 * nothing server-side has a "frame" to clear at, so a {@code SYNCED} override at this scope is
	 * indistinguishable from {@link #PERSISTENT} - use {@link #PERSISTENT} or {@link #CONTROLLER} for
	 * server-authoritative overrides instead. The per-frame clearing sweep clears it, and only the client runs one.
	 * Not this one
	 */
	FRAME
}
