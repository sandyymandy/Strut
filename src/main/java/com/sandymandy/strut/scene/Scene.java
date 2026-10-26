package com.sandymandy.strut.scene;

import com.sandymandy.strut.animatable.StrutEntity;
import com.sandymandy.strut.animation.authority.AnimationPlayback;
import com.sandymandy.strut.animation.authority.ServerAnimationState;
import com.sandymandy.strut.animation.authority.ServerAnimationStates;
import com.sandymandy.strut.event.dispatch.ClipResolution;
import com.sandymandy.strut.loading.binary.StrutBinaryFormats.LoopMode;
import com.sandymandy.strut.player.StrutPlayers;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Several entities (players with a body among them) animated together by the server on one clock: a paired animation, a cutscene, a scene of a mod's own
 * <p>
 * Strut owns the part that is the same for every such system and none of what the scene means. A scene has <b>roles</b> (names a mod picks: {@code host},
 * {@code partner}, {@code victim}), each filled by an entity placed at a spot of the scene's space. A <b>step</b> says which clip each role plays; every clip of a
 * step starts on the same game tick, so they stay in step for everyone however they are watching, and a step replaces the one before it on all roles at once
 * (a branch). When the clips of a step that do not loop have all run to their end, the mod is told ({@link StrutScenes.Builder#onStepEnd}) and may start the
 * next step from there; if it does not, the scene is over. The mod decides what any of it means: which step follows, what an event does, how a scene is won
 * <pre>{@code
 * Scene scene = StrutScenes.create(new Identifier("mymod", "embrace"), world, origin, yaw)
 *         .participant("host", creature, ScenePlacement.ORIGIN)
 *         .participant("partner", player, ScenePlacement.offset(0, 0, 0.8).facing(180))
 *         .onStepEnd(next -> next.step().clip("host", "embrace_end").clip("partner", "embrace_end").start())
 *         .onEnd((ended, why) -> ...)
 *         .start();
 * scene.step().clip("host", "embrace_in").clip("partner", "embrace_in").start();   // both start on the same tick
 * scene.setRate(1.5f);                                                              // faster for both, without a jump
 * }</pre>
 * What Strut keeps true for the length of a scene: a required participant that leaves (logs out, dies, is removed, changes dimension) ends it, with the reason; a
 * participant is in one scene at a time; the participants' clips stop and anything the scene changed on them is put back when it ends; and each participant's
 * {@link SceneMember} (the scene, the role, where it is) is on every client that sees them. A player's body is shown at its place with
 * {@code StrutPlayers.setAnchor}; the player themself is not moved or held, which a mod does in its own way (ask {@link StrutScenes#inScene}). Any other entity
 * is moved to its place and held there, its AI off, unless the scene is built with {@link StrutScenes.Builder#holdEntities(boolean) holdEntities(false)}
 * <p>
 * Server side only; a client reads {@link StrutScenes#of}
 */
public final class Scene {
	private final UUID id = UUID.randomUUID();
	private final Identifier type;
	private final ServerWorld world;
	private final boolean holdEntities;
	private final long timeoutTicks;
	@Nullable
	private final Consumer<Scene> stepEnd;
	@Nullable
	private final BiConsumer<Scene, SceneEnd> onEnd;
	private final Map<String, Participant> participants = new LinkedHashMap<>();
	private final long startedTick;

	private Vec3d origin;
	private float yaw;
	private Map<String, Track> tracks = new LinkedHashMap<>();
	private int step;
	private long stepStartTick;
	private boolean stepEndFired;
	private boolean ended;

	/** One role of the scene and who fills it */
	static final class Participant {
		final String role;
		final Entity entity;
		final ScenePlacement placement;
		final String controller;
		final boolean required;
		/** Whether this scene turned the entity's AI off, and so has to turn it back on */
		boolean aiDisabledByScene;
		boolean anchored;

		Participant(String role, Entity entity, ScenePlacement placement, String controller, boolean required) {
			this.role = role;
			this.entity = entity;
			this.placement = placement;
			this.controller = controller;
			this.required = required;
		}
	}

	/** What one role plays in the current step */
	private record Track(String clip, LoopMode loop, @Nullable String next, LoopMode nextLoop) {
		Track(String clip, LoopMode loop) {
			this(clip, loop, null, LoopMode.LOOP);
		}
	}

	Scene(Identifier type, ServerWorld world, Vec3d origin, float yaw, boolean holdEntities, long timeoutTicks, @Nullable Consumer<Scene> stepEnd,
		  @Nullable BiConsumer<Scene, SceneEnd> onEnd) {
		this.type = type;
		this.world = world;
		this.origin = origin;
		this.yaw = yaw;
		this.holdEntities = holdEntities;
		this.timeoutTicks = timeoutTicks;
		this.stepEnd = stepEnd;
		this.onEnd = onEnd;
		this.startedTick = world.getTime();
	}

	void add(String role, Entity entity, ScenePlacement placement, String controller, boolean required) {
		this.participants.put(role, new Participant(role, entity, placement, controller, required));
	}

	public UUID id() {
		return this.id;
	}

	/** The kind of scene a mod gave it */
	public Identifier type() {
		return this.type;
	}

	public ServerWorld world() {
		return this.world;
	}

	public Vec3d origin() {
		return this.origin;
	}

	/** The way the scene faces, in degrees */
	public float yaw() {
		return this.yaw;
	}

	/** Whether {@link #end} (or the scene's own end) has happened: nothing more can be started on it */
	public boolean isEnded() {
		return this.ended;
	}

	/** The number of the current step, counting from 1 (0 before the first {@code start}) */
	public int stepNumber() {
		return this.step;
	}

	/** The entity filling {@code role}, or null if there is none (or it has left an optional role) */
	@Nullable
	public Entity entity(String role) {
		Participant participant = this.participants.get(role);

		return participant == null ? null : participant.entity;
	}

	/** The role {@code entity} fills in this scene, or null */
	@Nullable
	public String roleOf(Entity entity) {
		for (Participant participant : this.participants.values()) {
			if (participant.entity == entity)
				return participant.role;
		}

		return null;
	}

	/** Every role and who fills it now, in the order they were added */
	public Map<String, Entity> participants() {
		Map<String, Entity> map = new LinkedHashMap<>();

		this.participants.forEach((role, participant) -> map.put(role, participant.entity));

		return Collections.unmodifiableMap(map);
	}

	/** Game ticks since the current step started */
	public long stepTicks() {
		return this.world.getTime() - this.stepStartTick;
	}

	/** Game ticks since the scene was started */
	public long ageTicks() {
		return this.world.getTime() - this.startedTick;
	}

	/**
	 * Adds a participant to a scene that is already running (a partner who gets on, a third party who walks in), placed and announced as at the start. It has no clip
	 * until it is given one: by the next step, or by {@link #playNow} for this role alone, which leaves the clips the others are playing as they are
	 *
	 * @throws IllegalStateException if the scene has ended, the role is taken, or the entity is already in a scene (the message names which)
	 * @throws IllegalArgumentException if the entity is not in the scene's world
	 */
	public void join(String role, Entity entity, ScenePlacement placement, String controller, boolean required) {
		if (this.ended)
			throw new IllegalStateException("scene " + this.type + " has ended");

		if (this.participants.containsKey(role))
			throw new IllegalStateException("scene " + this.type + " already has a role '" + role + "'");

		if (entity.getWorld() != this.world)
			throw new IllegalArgumentException("scene " + this.type + ": " + role + " (" + entity.getName().getString() + ") is not in the scene's world");

		StrutScenes.claim(this, entity, role);

		Participant participant = new Participant(role, entity, placement, controller, required);

		this.participants.put(role, participant);

		if (this.holdEntities && entity instanceof MobEntity mob && !mob.isAiDisabled()) {
			mob.setAiDisabled(true);
			participant.aiDisabledByScene = true;
		}

		place(participant);
		announce();
	}

	/**
	 * Starts {@code clip} for {@code role} alone, now, as part of the current step: the other roles keep playing what they were (a participant who has just joined, or
	 * a role that changes clip while the rest go on). It counts in the step's end like the step's own clips
	 */
	public void playNow(String role, String clip, LoopMode loop) {
		Participant participant = this.participants.get(role);

		if (participant == null)
			throw new IllegalArgumentException("scene " + this.type + " has no role '" + role + "' (roles: " + this.participants.keySet() + ")");

		this.tracks.put(role, new Track(clip, loop));
		this.stepEndFired = false;
		stateOf(participant.entity).play(participant.controller, clip, this.world.getTime(), 0f, 1f, loop);
	}

	/** {@link #join(String, Entity, ScenePlacement, String, boolean)} on the default controller, required */
	public void join(String role, Entity entity, ScenePlacement placement) {
		join(role, entity, placement, StrutPlayers.SCENE, true);
	}

	/**
	 * Takes a participant out of the scene, which goes on without it: its clip stops, what the scene changed on it is put back, and it is free to join another.
	 * Nothing happens for a role nobody fills. Leaving is not losing: the scene does not end, even if the role was required
	 *
	 * @return the entity that filled the role, or null
	 */
	@Nullable
	public Entity leave(String role) {
		Participant participant = this.participants.remove(role);

		if (participant == null)
			return null;

		this.tracks.remove(role);

		if (!participant.entity.isRemoved())
			stateOf(participant.entity).stop(participant.controller, this.world.getTime());

		release(participant);
		StrutScenes.release(this, participant.entity);

		return participant.entity;
	}

	/** Begins a step: choose the clip of each role, then {@link StepBuilder#start()} */
	public StepBuilder step() {
		return new StepBuilder();
	}

	/**
	 * Moves the scene: every participant is placed again against the new origin and yaw
	 */
	public void move(Vec3d newOrigin, float newYaw) {
		this.origin = newOrigin;
		this.yaw = newYaw;

		for (Participant participant : this.participants.values())
			place(participant);

		announce();
	}

	/**
	 * Changes how fast every clip of the step plays, without any of them jumping (a strike that speeds up, a scene played in slow motion)
	 */
	public void setRate(float rate) {
		long now = this.world.getTime();

		for (String role : this.tracks.keySet()) {
			Participant participant = this.participants.get(role);

			if (participant != null)
				stateOf(participant.entity).setRate(participant.controller, rate, now);
		}
	}

	/**
	 * Jumps every clip of the step to {@code clipTick} ticks into it, together
	 *
	 * @param fireCrossed whether the events passed over are fired
	 */
	public void seek(float clipTick, boolean fireCrossed) {
		long now = this.world.getTime();

		for (String role : this.tracks.keySet()) {
			Participant participant = this.participants.get(role);

			if (participant != null)
				stateOf(participant.entity).seek(participant.controller, clipTick, now, fireCrossed);
		}
	}

	/**
	 * Ends the scene now, with the reason {@link SceneEnd#ENDED}: the participants' clips stop, they are released, the end callback runs
	 */
	public void end() {
		end(SceneEnd.ENDED);
	}

	/** Ends the scene with {@code why} (the others are used by Strut itself, but a mod may report one: a custom timeout, say) */
	public void end(SceneEnd why) {
		if (this.ended)
			return;

		this.ended = true;

		long now = this.world.getTime();

		for (Participant participant : this.participants.values()) {
			if (!participant.entity.isRemoved())
				stateOf(participant.entity).stop(participant.controller, now);

			release(participant);
		}

		StrutScenes.forget(this);

		if (this.onEnd != null)
			this.onEnd.accept(this, why);
	}

	/** Chooses the clips of a step */
	public final class StepBuilder {
		private final Map<String, Track> chosen = new LinkedHashMap<>();
		private float rate = 1f;

		/** {@code role} plays {@code clip} once and stays on its last frame when it ends */
		public StepBuilder clip(String role, String clip) {
			return clip(role, clip, LoopMode.HOLD_ON_LAST_FRAME);
		}

		/** {@code role} plays {@code clip}: once, looped, or held on its last frame */
		public StepBuilder clip(String role, String clip, LoopMode loop) {
			if (!Scene.this.participants.containsKey(role))
				throw new IllegalArgumentException("scene " + Scene.this.type + " has no role '" + role + "' (roles: " + Scene.this.participants.keySet() + ")");

			this.chosen.put(role, new Track(clip, loop));

			return this;
		}

		/**
		 * {@code role} plays {@code intro} once and, the moment it ends, goes on to {@code then} (looped, once, or held), on the same clock with no gap: an intro and
		 * the loop that follows it, for one role. The step is not finished while the intro runs; it is finished when {@code then} is, or never if it loops
		 */
		public StepBuilder clipThen(String role, String intro, String then, LoopMode thenLoop) {
			clip(role, intro, LoopMode.HOLD_ON_LAST_FRAME);
			this.chosen.put(role, new Track(intro, LoopMode.HOLD_ON_LAST_FRAME, then, thenLoop));

			return this;
		}

		/** How fast the step starts, 1 by default */
		public StepBuilder rate(float rate) {
			this.rate = rate;

			return this;
		}

		/**
		 * Starts the step: every role named plays its clip from the same game tick, and a role not named stops what it was playing. Replaces the step before it
		 */
		public Scene start() {
			if (Scene.this.ended)
				throw new IllegalStateException("scene " + Scene.this.type + " has ended");

			long now = Scene.this.world.getTime();

			Scene.this.step++;
			Scene.this.stepStartTick = now;
			Scene.this.stepEndFired = false;
			Scene.this.tracks = new LinkedHashMap<>(this.chosen);

			for (Participant participant : Scene.this.participants.values()) {
				Track track = this.chosen.get(participant.role);
				ServerAnimationState state = stateOf(participant.entity);

				if (track == null)
					state.stop(participant.controller, now);
				else
					state.play(participant.controller, track.clip, now, 0f, this.rate, track.loop);
			}

			return Scene.this;
		}
	}

	// ---- driven by StrutScenes ----

	void begin() {
		for (Participant participant : this.participants.values()) {
			if (this.holdEntities && participant.entity instanceof MobEntity mob && !mob.isAiDisabled()) {
				mob.setAiDisabled(true);
				participant.aiDisabledByScene = true;
			}

			place(participant);
		}

		announce();
	}

	void tick() {
		if (this.ended)
			return;

		if (this.timeoutTicks > 0 && ageTicks() >= this.timeoutTicks) {
			end(SceneEnd.TIMED_OUT);

			return;
		}

		List<Participant> gone = new ArrayList<>();

		for (Participant participant : this.participants.values()) {
			if (lost(participant.entity))
				gone.add(participant);
		}

		for (Participant participant : gone) {
			if (participant.required) {
				end(SceneEnd.PARTICIPANT_LOST);

				return;
			}

			this.participants.remove(participant.role);
			this.tracks.remove(participant.role);
			release(participant);
		}

		if (this.holdEntities) {
			for (Participant participant : this.participants.values()) {
				if (!(participant.entity instanceof ServerPlayerEntity))
					hold(participant, false);
			}
		}

		advanceChains();

		if (!this.stepEndFired && this.step > 0 && stepFinished()) {
			this.stepEndFired = true;

			int before = this.step;

			if (this.stepEnd != null)
				this.stepEnd.accept(this);

			if (!this.ended && this.step == before)
				end(SceneEnd.COMPLETED);
		}
	}

	private boolean lost(Entity entity) {
		if (entity.isRemoved() || entity.getWorld() != this.world)
			return true;

		return entity instanceof LivingEntity living && !living.isAlive();
	}

	/** Starts the clip that follows an intro, at the exact tick the intro ended, for each role whose intro has run out */
	private void advanceChains() {
		long now = this.world.getTime();

		for (Map.Entry<String, Track> entry : new ArrayList<>(this.tracks.entrySet())) {
			Track track = entry.getValue();
			Participant participant = this.participants.get(entry.getKey());

			if (track.next == null || participant == null)
				continue;

			ServerAnimationState state = ServerAnimationStates.get(participant.entity);
			AnimationPlayback playback = state == null ? null : state.get(participant.controller);

			// something else replaced the intro: the clip that was to follow it is not this scene's to start any more
			if (playback == null || !playback.animation().equals(track.clip)) {
				this.tracks.put(entry.getKey(), new Track(track.clip, track.loop));

				continue;
			}

			double length = lengthOf(participant.entity, track.clip);

			if (Double.isNaN(length) || !playback.hasEnded(length, now))
				continue;

			state.playAt(participant.controller, track.next, playback.endGameTime(length), playback.playbackRate(), track.nextLoop);
			this.tracks.put(entry.getKey(), new Track(track.next, track.nextLoop));
		}
	}

	/** Whether every clip of the step that does not loop has run to its end (a step of loops never finishes by itself) */
	private boolean stepFinished() {
		long now = this.world.getTime();
		int waitedFor = 0;

		for (Map.Entry<String, Track> entry : this.tracks.entrySet()) {
			Participant participant = this.participants.get(entry.getKey());

			if (participant == null || entry.getValue().loop == LoopMode.LOOP)
				continue;

			waitedFor++;

			ServerAnimationState state = ServerAnimationStates.get(participant.entity);
			AnimationPlayback playback = state == null ? null : state.get(participant.controller);

			// something else replaced or stopped the clip: it is no longer this step's to wait for
			if (playback == null || !playback.animation().equals(entry.getValue().clip))
				continue;

			double length = lengthOf(participant.entity, entry.getValue().clip);

			if (Double.isNaN(length) || !playback.hasEnded(length, now))
				return false;
		}

		// a step of loops (or of nothing) has no clip that ends: it is not finished, and never is by itself
		return waitedFor > 0;
	}

	/** The length in ticks of {@code clip} for {@code entity} as the server finds it, or NaN if it cannot */
	private static double lengthOf(Entity entity, String clip) {
		if (entity instanceof ServerPlayerEntity player)
			return StrutPlayers.clipLengthTicks(player, clip).orElse(Double.NaN);

		if (entity instanceof StrutEntity strutEntity)
			return ClipResolution.resolve(strutEntity, clip).map(timeline -> timeline.lengthTicks()).orElse(Double.NaN);

		return Double.NaN;
	}

	private static ServerAnimationState stateOf(Entity entity) {
		return ServerAnimationStates.forEntity(entity, !(entity instanceof StrutEntity));
	}

	private void place(Participant participant) {
		if (participant.entity instanceof ServerPlayerEntity player) {
			if (StrutPlayers.body(player) != null) {
				StrutPlayers.setAnchor(player, participant.placement.position(this.origin, this.yaw), participant.placement.yaw(this.yaw));
				participant.anchored = true;
			}

			return;
		}

		if (this.holdEntities)
			hold(participant, true);
	}

	private void hold(Participant participant, boolean force) {
		Vec3d at = participant.placement.position(this.origin, this.yaw);
		float facing = participant.placement.yaw(this.yaw);
		Entity entity = participant.entity;

		// only a move of position puts it back: an entity's own yaw is not the scene's to compare against (a living entity's body and head turn on their own)
		if (force || entity.getPos().squaredDistanceTo(at) > 1.0E-6) {
			entity.refreshPositionAndAngles(at.x, at.y, at.z, facing, 0f);
			entity.setVelocity(Vec3d.ZERO);

			if (entity instanceof LivingEntity living) {
				living.setHeadYaw(facing);
				living.setBodyYaw(facing);
			}
		}
	}

	private void release(Participant participant) {
		if (participant.anchored && participant.entity instanceof ServerPlayerEntity player)
			StrutPlayers.clearAnchor(player);

		if (participant.aiDisabledByScene && participant.entity instanceof MobEntity mob)
			mob.setAiDisabled(false);

		StrutScenes.clearMember(participant.entity);
	}

	private void announce() {
		for (Participant participant : this.participants.values())
			StrutScenes.setMember(participant.entity, new SceneMember(this.id, participant.role, this.type, this.origin.x, this.origin.y, this.origin.z, this.yaw));
	}
}
