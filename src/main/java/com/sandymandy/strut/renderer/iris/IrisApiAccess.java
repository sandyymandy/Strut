package com.sandymandy.strut.renderer.iris;

import net.irisshaders.iris.api.v0.IrisApi;
import net.irisshaders.iris.uniforms.CapturedRenderingState;

/**
 * The only class that actually references {@code IrisApi} - see {@link IrisCompat}'s own doc
 * comment for why that separation is what keeps Iris a genuinely soft dependency. Package-private:
 * {@link IrisCompat} is the only caller, and the only one that has already checked
 * {@link IrisCompat#isLoaded()} before reaching here
 */
final class IrisApiAccess {
	private IrisApiAccess() {}

	static int getMinorApiRevision() {
		return IrisApi.getInstance().getMinorApiRevision();
	}

	static boolean isShaderPackInUse() {
		return IrisApi.getInstance().isShaderPackInUse();
	}

	static boolean isRenderingShadowPass() {
		return IrisApi.getInstance().isRenderingShadowPass();
	}

	/*
	 * Not public API - Iris's own CapturedRenderingState, which its entity render hooks set while each
	 * entity renders and its buffer builder writes into every vertex's iris_Entity attribute (real
	 * source, MixinBufferBuilder). Confirmed present with these exact signatures in the installed
	 * 1.8.8 jar via javap; IrisCompat's callers catch a LinkageError in case a future build changes it
	 */
	static int currentRenderedEntity() {
		return CapturedRenderingState.INSTANCE.getCurrentRenderedEntity();
	}

	static int currentRenderedBlockEntity() {
		return CapturedRenderingState.INSTANCE.getCurrentRenderedBlockEntity();
	}

	static int currentRenderedItem() {
		return CapturedRenderingState.INSTANCE.getCurrentRenderedItem();
	}
}
