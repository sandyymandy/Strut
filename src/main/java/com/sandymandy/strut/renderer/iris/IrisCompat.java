package com.sandymandy.strut.renderer.iris;

import net.fabricmc.loader.api.FabricLoader;

/**
 * The only class in this package
 * permitted to be referenced when Iris is not installed. Every actual {@code IrisApi} call lives in
 * {@link IrisApiAccess} instead, a separate class this one never touches unless {@link #isLoaded()}
 * has already confirmed Iris is present - the JVM only resolves a class the first time code that
 * references it actually executes, so a soft dependency stays soft as long as the guard and the
 * reference are never in the same class
 * <p>
 * Iris is compiled against ({@code modCompileOnly}, never {@code modImplementation}) but must work
 * identically to "not installed" when it isn't: every call site in the renderer goes through this
 * class rather than touching {@code IrisApi} directly
 */
public final class IrisCompat {
	/**
	 * The Iris minor API revision this integration was written and tested against
	 * ({@code IrisApi#getMinorApiRevision()}). The API source this was written against
	 * is 1.8.14; an older or much newer installed build may still report this same
	 * revision (the API is additive), but {@link #isSupportedVersion()} is the one place that
	 * assumption is recorded, so a future revision bump has exactly one constant to update
	 */
	private static final int SUPPORTED_MINOR_API_REVISION = 2;

	private static final boolean LOADED = FabricLoader.getInstance().isModLoaded("iris");

	private IrisCompat() {}

	/** Whether Iris is installed at all, regardless of whether a pack is active */
	public static boolean isLoaded() {
		return LOADED;
	}

	/**
	 * Whether this build's own Iris integration should run at all - Iris is loaded, exposes an API
	 * revision this integration was written against, and (naturally) a pack is actually active. Every
	 * higher-level call site should gate on this rather than {@link #isLoaded()} alone: an
	 * unsupported revision degrades to the vanilla backend exactly like Iris being entirely absent,
	 * (a deliberately mismatched Iris version must not crash the game)
	 */
	public static boolean isActive() {
		return LOADED && isSupportedVersion() && IrisApiAccess.isShaderPackInUse();
	}

	/**
	 * Whether the installed Iris build's own minor API revision is one this integration has actually
	 * been tested against. Only ever meaningful once {@link #isLoaded()} is true - never called
	 * otherwise, since {@link #isActive()} short-circuits on {@code LOADED} first
	 */
	private static boolean isSupportedVersion() {
		return IrisApiAccess.getMinorApiRevision() >= SUPPORTED_MINOR_API_REVISION;
	}

	/**
	 * Whether Iris is currently rendering its shadow pass - only meaningful (and only ever called)
	 * while {@link #isActive()} is true, since {@code IrisApi} itself documents this as only ever
	 * true when a pack is in use
	 */
	public static boolean isRenderingShadowPass() {
		return LOADED && IrisApiAccess.isRenderingShadowPass();
	}

	/** Iris's own "no id" defaults for the three iris_Entity components (real CapturedRenderingState) */
	public static final int NO_ENTITY_ID = -1;
	public static final int NO_BLOCK_ENTITY_ID = 0;
	public static final int NO_ITEM_ID = -1;

	private static boolean capturedIdsUnavailable;

	/**
	 * The entity/block-entity/item ids Iris has captured for whatever is rendering right now - what its
	 * own buffer builder writes into a vanilla vertex's {@code iris_Entity} attribute, and what a pack
	 * keys per-entity materials on (its {@code entity.properties}). Read at {@code GpuBatchQueue.submit}
	 * time, while the entity's own render call is on the stack, into {@code dest[0..2]}; Iris's own
	 * defaults when Iris isn't active or this internal accessor stops existing in some future build
	 */
	public static void currentRenderedIds(int[] dest) {
		dest[0] = NO_ENTITY_ID;
		dest[1] = NO_BLOCK_ENTITY_ID;
		dest[2] = NO_ITEM_ID;

		if (!LOADED || capturedIdsUnavailable)
			return;

		try {
			dest[0] = IrisApiAccess.currentRenderedEntity();
			dest[1] = IrisApiAccess.currentRenderedBlockEntity();
			dest[2] = IrisApiAccess.currentRenderedItem();
		}
		catch (LinkageError ex) {
			capturedIdsUnavailable = true;
		}
	}
}
