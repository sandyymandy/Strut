package com.sandymandy.strut.event.type;

import com.sandymandy.strut.event.builtin.StrutBuiltinEvents;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;

/**
 * The game's one {@link EventTypeRegistry}, and the entry point a consuming mod registers its own
 * event types through
 * <p>
 * Register from your mod initialiser; Strut freezes the registry when the server or client finishes
 * starting, so everything a clip can name is known before any clip is parsed. The engine's own types
 * ({@code strut:marker}, {@code strut:sound}, {@code strut:particle}, {@code strut:loop},
 * {@code strut:end}) are registered here before anyone else can run
 * <p>
 * <pre>{@code
 * StrutEventTypes.register(StrutEventType.builder(MyMod.id("strike"), PacketCodec.unit(Unit.INSTANCE))
 *         .category(EventCategory.GAMEPLAY).authority(EventAuthority.SERVER)
 *         .noPayload(Unit.INSTANCE).build());
 * }</pre>
 *
 */
public final class StrutEventTypes {
	private static final EventTypeRegistry REGISTRY = new EventTypeRegistry();

	static {
		StrutBuiltinEvents.registerAll(REGISTRY);
	}

	private StrutEventTypes() {}

	/**
	 * @throws IllegalStateException if the id is taken or the registry is already frozen
	 */
	public static <P> StrutEventType<P> register(StrutEventType<P> type) {
		return REGISTRY.register(type);
	}

	@Nullable
	public static StrutEventType<?> get(Identifier id) {
		return REGISTRY.get(id);
	}

	public static Collection<StrutEventType<?>> all() {
		return REGISTRY.all();
	}

	/**
	 * Called by Strut once the server or client has finished starting. Idempotent
	 */
	public static void freeze() {
		REGISTRY.freeze();
	}

	public static boolean isFrozen() {
		return REGISTRY.isFrozen();
	}

	/**
	 * The instance clip loading resolves against
	 */
	public static EventTypeRegistry registry() {
		return REGISTRY;
	}
}
