package com.sandymandy.strut.scene;

import com.sandymandy.strut.StrutConstants;
import com.sandymandy.strut.event.StrutEvents;
import com.sandymandy.strut.player.StrutPlayers;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.Entity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * The base for scenes: several entities animated together on one clock, which a mod builds its own scene, cutscene or paired-animation system on (see
 * {@link Scene})
 * <pre>{@code
 * Scene scene = StrutScenes.create(new Identifier("mymod", "duel"), world, origin, yaw)
 *         .participant("a", knight, ScenePlacement.offset(1, 0, 0).facing(90))
 *         .participant("b", player, ScenePlacement.offset(-1, 0, 0).facing(-90))
 *         .start();
 * scene.step().clip("a", "duel_start").clip("b", "duel_start").start();
 *
 * StrutScenes.of(entity);          // either side: the SceneMember (scene, role, kind, origin) or null
 * StrutScenes.inScene(player);     // either side
 * StrutScenes.sceneOf(entity);     // server: the Scene itself
 * }</pre>
 */
public final class StrutScenes {
	private static final AttachmentType<SceneMember> MEMBER = AttachmentRegistry.create(StrutConstants.id("scene_member"),
			builder -> builder.syncWith(SceneMember.PACKET_CODEC, AttachmentSyncPredicate.all()));

	private static final Map<UUID, Scene> SCENES = new LinkedHashMap<>();
	private static final Map<UUID, Scene> BY_ENTITY = new HashMap<>();

	private StrutScenes() {}

	/** Registers the scene tick and the attachment. Called from the common entrypoint */
	public static void init() {
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			for (Scene scene : new ArrayList<>(SCENES.values()))
				scene.tick();
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			for (Scene scene : new ArrayList<>(SCENES.values()))
				scene.end(SceneEnd.SERVER_STOPPING);
		});

		// taking part in a scene counts as taking part in what the scene's other participants do (the client's isLocalPlayerParticipant)
		StrutEvents.addParticipantRule((entity, player) -> {
			SceneMember mine = of(player);
			SceneMember theirs = of(entity);

			return mine != null && theirs != null && mine.scene().equals(theirs.scene());
		});
	}

	/**
	 * Begins describing a scene of kind {@code type} (a name of the mod's own) at {@code origin}, facing {@code yaw} degrees
	 */
	public static Builder create(Identifier type, ServerWorld world, Vec3d origin, float yaw) {
		return new Builder(type, world, origin, yaw);
	}

	/** What {@code entity} is doing in a scene, or null if it is in none. Works on either side */
	@Nullable
	public static SceneMember of(Entity entity) {
		return entity.getAttached(MEMBER);
	}

	/** Whether {@code entity} is in a scene. Works on either side; a mod's own mixin that holds a player in place asks it */
	public static boolean inScene(Entity entity) {
		return entity.hasAttached(MEMBER);
	}

	/** The scene {@code entity} is in, or null. Server side */
	@Nullable
	public static Scene sceneOf(Entity entity) {
		return BY_ENTITY.get(entity.getUuid());
	}

	/** Every scene running now */
	public static Collection<Scene> all() {
		return List.copyOf(SCENES.values());
	}

	static void setMember(Entity entity, SceneMember member) {
		entity.setAttached(MEMBER, member);
	}

	static void clearMember(Entity entity) {
		entity.removeAttached(MEMBER);
	}

	/** Records that {@code entity} is in {@code scene}; throws, naming both scenes, if it is in another */
	static void claim(Scene scene, Entity entity, String role) {
		Scene other = BY_ENTITY.get(entity.getUuid());

		if (other != null && other != scene && !other.isEnded())
			throw new IllegalStateException("scene " + scene.type() + ": " + role + " (" + entity.getName().getString() + ") is already in the scene " + other.type() + " as " + other.roleOf(entity)
					+ "; end that scene first");

		BY_ENTITY.put(entity.getUuid(), scene);
	}

	static void release(Scene scene, Entity entity) {
		BY_ENTITY.remove(entity.getUuid(), scene);
	}

	static void forget(Scene scene) {
		SCENES.remove(scene.id());
		BY_ENTITY.values().removeIf(found -> found == scene);
	}

	/** Describes a scene: who is in it, where, and what Strut does for it. {@link #start()} creates it */
	public static final class Builder {
		private record Role(Entity entity, ScenePlacement placement, String controller, boolean required) {}

		private final Identifier type;
		private final ServerWorld world;
		private final Vec3d origin;
		private final float yaw;
		private final Map<String, Role> roles = new LinkedHashMap<>();
		private boolean holdEntities = true;
		private long timeout;
		private Consumer<Scene> stepEnd;
		private BiConsumer<Scene, SceneEnd> onEnd;

		Builder(Identifier type, ServerWorld world, Vec3d origin, float yaw) {
			this.type = type;
			this.world = world;
			this.origin = origin;
			this.yaw = yaw;
		}

		/**
		 * A role filled by {@code entity} that the scene cannot do without: if it leaves, the scene ends. A player with a body plays on the controller
		 * {@code scene} that every body has; any other entity on the controller of the same name (a {@code StrutEntity} makes one on demand)
		 */
		public Builder participant(String role, Entity entity, ScenePlacement placement) {
			return participant(role, entity, placement, StrutPlayers.SCENE, true);
		}

		/** A role that can go without: if its entity leaves, the scene goes on without it */
		public Builder optional(String role, Entity entity, ScenePlacement placement) {
			return participant(role, entity, placement, StrutPlayers.SCENE, false);
		}

		/** A role played on the controller {@code controller} of its entity */
		public Builder participant(String role, Entity entity, ScenePlacement placement, String controller, boolean required) {
			if (this.roles.containsKey(role))
				throw new IllegalArgumentException("scene " + this.type + " already has a role '" + role + "'");

			if (entity.getWorld() != this.world)
				throw new IllegalArgumentException("scene " + this.type + ": " + role + " (" + entity.getName().getString() + ") is not in the scene's world");

			this.roles.put(role, new Role(entity, placement, controller, required));

			return this;
		}

		/**
		 * Whether entities that are not players are moved to their place and held there, their AI off, for the scene's length (true by default). A player's
		 * body is shown at its place either way and the player is never held by Strut
		 */
		public Builder holdEntities(boolean hold) {
			this.holdEntities = hold;

			return this;
		}

		/** Ends the scene with {@link SceneEnd#TIMED_OUT} after this many game ticks, whatever it is doing. Off by default */
		public Builder timeout(long ticks) {
			this.timeout = ticks;

			return this;
		}

		/**
		 * Runs when every clip of a step that does not loop has run to its end (a step of loops never does): start the next step from here, or the scene is
		 * over. Runs on the server tick, once for each step
		 */
		public Builder onStepEnd(Consumer<Scene> handler) {
			this.stepEnd = handler;

			return this;
		}

		/** Runs once when the scene ends, with the scene and why */
		public Builder onEnd(BiConsumer<Scene, SceneEnd> handler) {
			this.onEnd = handler;

			return this;
		}

		/**
		 * Creates the scene and places its participants; it plays nothing until a step is started with {@link Scene#step()}
		 *
		 * @throws IllegalStateException if a participant is already in a scene (the message names which, and which scene)
		 */
		public Scene start() {
			if (this.roles.isEmpty())
				throw new IllegalStateException("scene " + this.type + " has no participants");

			for (Map.Entry<String, Role> entry : this.roles.entrySet()) {
				Entity entity = entry.getValue().entity();
				Scene other = BY_ENTITY.get(entity.getUuid());

				if (other != null && !other.isEnded())
					throw new IllegalStateException("scene " + this.type + ": " + entry.getKey() + " (" + entity.getName().getString() + ") is already in the scene " + other.type()
							+ " as " + other.roleOf(entity) + "; end that scene first");
			}

			Scene scene = new Scene(this.type, this.world, this.origin, this.yaw, this.holdEntities, this.timeout, this.stepEnd, this.onEnd);

			for (Map.Entry<String, Role> entry : this.roles.entrySet()) {
				Role role = entry.getValue();

				scene.add(entry.getKey(), role.entity(), role.placement(), role.controller(), role.required());
				BY_ENTITY.put(role.entity().getUuid(), scene);
			}

			SCENES.put(scene.id(), scene);
			scene.begin();

			return scene;
		}
	}
}
