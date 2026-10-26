package com.sandymandy.strut.event.binding;

import net.minecraft.util.Identifier;
import org.jetbrains.annotations.ApiStatus;

/**
 * The entry point for data-driven event responses: per-character sounds and mod-defined actions, chosen by
 * which entity is playing a shared clip
 * <p>
 * A clip says <i>which cue</i> happened (a sound-channel keyframe {@code anal_startMSG1}, or a marker); the
 * <b>binding set</b> the entity names says <i>what that cue is</i> for this character. One animation, a
 * different voice per character, no code. An entity names its sets by overriding
 * {@code StrutEntity#getEventBindings()}, and the files live at
 * {@code assets/<namespace>/strut/event_bindings/<name>.json} (the set's id is {@code <namespace>:<name>}).
 *
 * <p>
 * The one thing a mod registers here is an <b>action</b>: something a binding can do beyond playing sounds,
 * such as saying a chat line
 * <pre>{@code
 * EventBindings.registerAction(MyMod.id("messages"), (ctx, json) -> {
 *     for (JsonElement line : json.getAsJsonArray()) say(ctx.entity(), line.getAsString());
 * });
 * }</pre>
 * and a binding file then uses it: {@code "mymod:messages": ["Hihi~"]}
 */
public final class EventBindings {
	private static final BindingSets SETS = new BindingSets();
	private static final EventBindingActions ACTIONS = new EventBindingActions();
	private static final BindingRuntime RUNTIME = new BindingRuntime(SETS, ACTIONS);

	private EventBindings() {}

	/**
	 * Registers something a binding can do, under a namespaced id of your own
	 *
	 * @throws IllegalArgumentException for the reserved {@code strut} namespace
	 * @throws IllegalStateException if the id is already taken
	 */
	public static void registerAction(Identifier id, BindingAction action) {
		ACTIONS.register(id, action);
	}

	/**
	 * Strut's own loader and playback read these; mods have no use for them
	 */
	@ApiStatus.Internal
	public static BindingSets sets() {
		return SETS;
	}

	@ApiStatus.Internal
	public static EventBindingActions actions() {
		return ACTIONS;
	}

	@ApiStatus.Internal
	public static BindingRuntime runtime() {
		return RUNTIME;
	}
}
