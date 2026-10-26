package com.sandymandy.strut.renderer.hook;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;

/**
 * Events along the GPU path, for what a shader hook cannot do alone: decide per model, or draw something of the mod's own in the pipeline's place
 * <ul>
 *     <li>{@link #SUBMIT}: a model is about to be queued; change its colour, how it is shaded, and the floats its shader hooks read</li>
 *     <li>{@link #BEFORE_DRAW}: the frame's models are about to be drawn, the shader not yet bound; any GL state set here is the mod's to put back</li>
 *     <li>{@link #AFTER_DRAW}: they have been drawn; the state the draw left is restored by the time this returns to the game</li>
 * </ul>
 * Client side only
 */
public final class StrutRenderHooks {
	@FunctionalInterface
	public interface Submit {
		void onSubmit(SubmitContext context);
	}

	@FunctionalInterface
	public interface Draw {
		void onDraw();
	}

	public static final Event<Submit> SUBMIT = EventFactory.createArrayBacked(Submit.class, listeners -> context -> {
		for (Submit listener : listeners)
			listener.onSubmit(context);
	});

	public static final Event<Draw> BEFORE_DRAW = EventFactory.createArrayBacked(Draw.class, listeners -> () -> {
		for (Draw listener : listeners)
			listener.onDraw();
	});

	public static final Event<Draw> AFTER_DRAW = EventFactory.createArrayBacked(Draw.class, listeners -> () -> {
		for (Draw listener : listeners)
			listener.onDraw();
	});

	private static int submitListeners;

	private StrutRenderHooks() {}

	/** Adds a {@link #SUBMIT} listener; use this and not {@code SUBMIT.register} so the renderer knows there is one to ask */
	public static void onSubmit(Submit listener) {
		SUBMIT.register(listener);
		submitListeners++;
	}

	/** Whether any {@link #SUBMIT} listener is registered, so a model is not wrapped for nothing */
	public static boolean hasSubmitListeners() {
		return submitListeners > 0;
	}
}
