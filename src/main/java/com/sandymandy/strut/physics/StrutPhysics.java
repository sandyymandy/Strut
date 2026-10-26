package com.sandymandy.strut.physics;

import com.sandymandy.strut.cache.object.BakedStrutModel;
import net.minecraft.entity.Entity;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import com.sandymandy.strut.physics.tools.PhysicsControls;

/**
 * The physics module's public entry: what a mod that wants to react to or tune physics calls
 * <pre>{@code
 * StrutPhysics.of(creature).firmness(0.7f).weight("pouchL", 0f);   // a slider, and a bone held still
 * StrutPhysics.of(creature).impulse("belly", 0f, -2f, 0f);        // a bounce
 * }</pre>
 * Client side only, per viewer, never synced. Safe to call whether or not the module's other classes have loaded
 */
public final class StrutPhysics {
	/** The name of the extension section a {@code .strutmodel} carries its physics data in */
	public static final String SECTION = "strut_physics:physics";

	/** The module's mod id, for {@code FabricLoader#isModLoaded} */
	public static final String MOD_ID = "strut_physics";

	private static final Map<Integer, PhysicsControls> CONTROLS = new ConcurrentHashMap<>();

	private StrutPhysics() {}

	/**
	 * The runtime controls for {@code entity}'s physics, created on first use
	 */
	public static PhysicsControls of(Entity entity) {
		return ofId(entity.getId());
	}

	public static PhysicsControls ofId(int entityId) {
		return CONTROLS.computeIfAbsent(entityId, id -> new PhysicsControls());
	}

	/** The controls for an entity id only if some were ever created, so reading costs nothing for an entity nobody tuned */
	static PhysicsControls existing(int entityId) {
		return CONTROLS.get(entityId);
	}

	/** Forgets every entity's controls, called when the client leaves a world */
	static void clearAll() {
		CONTROLS.clear();
	}

	/**
	 * Whether {@code model}'s file carried physics data
	 */
	public static boolean hasPhysicsData(BakedStrutModel model) {
		return model.extensions().containsKey(SECTION);
	}
}
