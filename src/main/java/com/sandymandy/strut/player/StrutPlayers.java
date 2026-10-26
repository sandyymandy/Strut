package com.sandymandy.strut.player;

import com.sandymandy.strut.StrutConstants;
import com.sandymandy.strut.animatable.EntityAttachments;
import com.sandymandy.strut.animatable.EntityBoneOverrides;
import com.sandymandy.strut.animation.authority.AnimationPlayback;
import com.sandymandy.strut.animation.authority.ServerAnimationState;
import com.sandymandy.strut.animation.authority.ServerAnimationStates;
import com.sandymandy.strut.event.dispatch.ClipResolution;
import com.sandymandy.strut.event.timeline.ClipTimelines;
import com.sandymandy.strut.loading.binary.StrutBinaryFormats.LoopMode;
import java.util.List;
import java.util.OptionalDouble;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Players that are drawn as a Strut model: the one entry point for giving a player a {@link PlayerBody} and driving it
 * <p>
 * A body is the player's own, not another entity beside it. The player keeps moving, colliding, taking damage and being tracked as the player they are,
 * and what changes is how they are drawn and animated, on every client that can see them. The assignment is stored on the player and synced by Fabric's
 * data attachments, so a client that comes into range later, a player who respawns or changes dimension, and the owner's own client all have it without
 * anything else being sent
 * <pre>{@code
 * PlayerBodies.register(PlayerBody.builder(new Identifier("mymod", "fox")).build());   // both sides, at startup
 *
 * StrutPlayers.setBody(player, new Identifier("mymod", "fox"), "red", StrutPlayers.Persistence.SAVED);   // server
 * StrutPlayers.overrides(player).setHidden("tail", true);                                                  // either side
 * StrutPlayers.play(player, "scene", "hug", LoopMode.LOOP);                                                // server: everyone sees it
 * }</pre>
 * A player without a body is untouched
 */
public final class StrutPlayers {
	/** How long a body assignment lasts */
	public enum Persistence {
		/** Until the player leaves: a death keeps it (they respawn with the body), a relog gives the ordinary player back */
		SESSION,
		/** Saved with the player, and kept through death, so it is there next time they join and after they respawn */
		SAVED
	}

	private static final AttachmentType<BodyAssignment> SESSION_BODY = AttachmentRegistry.create(StrutConstants.id("body"),
			builder -> builder.copyOnDeath().syncWith(BodyAssignment.PACKET_CODEC, AttachmentSyncPredicate.all()));

	private static final AttachmentType<BodyAssignment> SAVED_BODY = AttachmentRegistry.create(StrutConstants.id("body_saved"),
			builder -> builder.persistent(BodyAssignment.CODEC).copyOnDeath().syncWith(BodyAssignment.PACKET_CODEC, AttachmentSyncPredicate.all()));

	private static final AttachmentType<Boolean> HELD_HIDDEN = AttachmentRegistry.create(StrutConstants.id("body_held_hidden"),
			builder -> builder.syncWith(PacketCodecs.BOOL.cast(), AttachmentSyncPredicate.all()));

	private static final AttachmentType<BodyAnchor> ANCHOR = AttachmentRegistry.create(StrutConstants.id("body_anchor"),
			builder -> builder.syncWith(BodyAnchor.PACKET_CODEC, AttachmentSyncPredicate.all()));

	/** The controller of every body that shows the server's clip ({@link #play}): it plays over the movement, swing and use controllers */
	public static final String SCENE = "scene";

	private StrutPlayers() {}

	/**
	 * Loads the class, so the two attachment types are registered at startup, before any player is created. Called from the common entrypoint
	 */
	public static void init() {}

	/**
	 * Gives {@code player} the body registered as {@code body}, in place of the player model, replacing any body they had
	 *
	 * @param variant Whatever the body's code reads to tell variants apart, such as a character id; empty if there is none
	 * @throws IllegalArgumentException If no body is registered under that id
	 */
	public static void setBody(ServerPlayerEntity player, Identifier body, String variant, Persistence persistence) {
		if (PlayerBodies.get(body) == null)
			throw new IllegalArgumentException("no player body is registered as " + body);

		BodyAssignment assignment = new BodyAssignment(body, variant);

		if (persistence == Persistence.SAVED) {
			player.removeAttached(SESSION_BODY);
			player.setAttached(SAVED_BODY, assignment);
		}
		else {
			player.removeAttached(SAVED_BODY);
			player.setAttached(SESSION_BODY, assignment);
		}
	}

	/**
	 * {@link #setBody(ServerPlayerEntity, Identifier, String, Persistence)} with no variant, saved: the body is still there after a relog
	 */
	public static void setBody(ServerPlayerEntity player, Identifier body) {
		setBody(player, body, "", Persistence.SAVED);
	}

	/**
	 * Gives a player entity that is not in the world a body, on this client only: what the menu preview does (see {@code StrutPlayersClient#drawBody})
	 */
	@ApiStatus.Internal
	public static void setLocalBody(PlayerEntity player, Identifier body, String variant) {
		player.setAttached(SESSION_BODY, new BodyAssignment(body, variant));
	}

	/**
	 * Gives {@code player} the ordinary player model back, forgetting a saved assignment too
	 */
	public static void clearBody(ServerPlayerEntity player) {
		player.removeAttached(SESSION_BODY);
		player.removeAttached(SAVED_BODY);
	}

	/**
	 * Which body {@code player} was given, or null: the saved one if there is one, else the session's. A body whose id is not registered (a mod that is no
	 * longer there) is still returned here, and {@link #body} is what says whether it can be drawn
	 */
	@Nullable
	public static BodyAssignment assignment(PlayerEntity player) {
		BodyAssignment saved = player.getAttached(SAVED_BODY);

		return saved != null ? saved : player.getAttached(SESSION_BODY);
	}

	/**
	 * The body {@code player} is drawn as, or null if they have none, or have one that is not registered. Works on either side
	 */
	@Nullable
	public static PlayerBody body(PlayerEntity player) {
		BodyAssignment assignment = assignment(player);

		return assignment == null ? null : PlayerBodies.get(assignment.body());
	}

	/**
	 * {@code player}'s body variant (see {@link #setBody(ServerPlayerEntity, Identifier, String, Persistence)}), or an empty string if they have no body
	 */
	public static String variant(PlayerEntity player) {
		BodyAssignment assignment = assignment(player);

		return assignment == null ? "" : assignment.variant();
	}

	/**
	 * Hides or shows what {@code player}'s hands hold on their body, for everyone who sees them: nothing is drawn on the hand bones, whatever the player has in their
	 * hands. Server side; it lasts until it is shown again. A body also hides them by itself while its player is in a scene ({@code StrutScenes}), unless it was built
	 * with {@code showHeldItemsInScenes()}
	 */
	public static void setHeldItemsHidden(ServerPlayerEntity player, boolean hidden) {
		if (hidden)
			player.setAttached(HELD_HIDDEN, true);
		else
			player.removeAttached(HELD_HIDDEN);
	}

	/** Whether {@link #setHeldItemsHidden} hid {@code player}'s held items. Works on either side */
	public static boolean heldItemsHidden(PlayerEntity player) {
		return player.getAttachedOrElse(HELD_HIDDEN, false);
	}

	/**
	 * Draws {@code player}'s body at {@code position}, facing {@code yaw} degrees, instead of where the player is, for everyone who can see them: on a bed, at the
	 * mark of a scene. The player themself is not moved (where they stand, what they hit and are hit by is unchanged), and in first person the immersive camera
	 * goes with the body. Lasts until {@link #clearAnchor}, the player respawns or leaves. Only does anything for a player who has a body
	 */
	public static void setAnchor(ServerPlayerEntity player, Vec3d position, float yaw) {
		player.setAttached(ANCHOR, new BodyAnchor(position.x, position.y, position.z, yaw));
	}

	/**
	 * Draws {@code player}'s body where the player is again
	 */
	public static void clearAnchor(ServerPlayerEntity player) {
		player.removeAttached(ANCHOR);
	}

	/**
	 * Where {@code player}'s body is drawn if it is not at the player, or null. Works on either side
	 */
	@Nullable
	public static BodyAnchor anchor(PlayerEntity player) {
		return player.getAttached(ANCHOR);
	}

	/**
	 * {@code player}'s bone overrides: hide, move, scale, tint or re-texture a bone of their body. Called on the server it is authoritative and every
	 * client that can see the player gets it; called on a client it is only that client's view. See {@link EntityBoneOverrides}
	 */
	public static EntityBoneOverrides overrides(PlayerEntity player) {
		return EntityBoneOverrides.forReplaced(player);
	}

	/**
	 * Items drawn on the bones of {@code player}'s body, as {@code entity.attachments()} does for an entity. Server side only
	 */
	public static EntityAttachments attachments(PlayerEntity player) {
		return EntityAttachments.forReplaced(player);
	}

	/**
	 * Starts {@code animation} on {@code controller} of {@code player}'s body, for everyone who can see them. Use {@link #SCENE}, the SERVER-authority
	 * controller every body has, or a controller of your own that is set to {@code AuthorityMode.SERVER}
	 */
	public static void play(ServerPlayerEntity player, String controller, String animation, LoopMode loopMode) {
		ServerAnimationStates.forEntity(player, true).play(controller, animation, player.getWorld().getTime(), 0f, 1f, loopMode);
	}

	/**
	 * The length of {@code clip} in ticks as the server finds it for {@code player}'s body (through the body's declared animation files for the player's variant,
	 * else by name), or empty if it cannot be found
	 */
	public static OptionalDouble clipLengthTicks(ServerPlayerEntity player, String clip) {
		PlayerBody body = body(player);
		List<Identifier> files = body == null ? List.of() : body.animationFiles(variant(player));

		return ClipResolution.resolve(ClipTimelines.instance(), files, clip)
				.map(timeline -> OptionalDouble.of(timeline.lengthTicks())).orElse(OptionalDouble.empty());
	}

	/**
	 * Whether {@code controller} of {@code player}'s body has nothing playing from {@link #play}: it was never started, was stopped, or its clip is not a looping
	 * one and has run to its end (a clip held on its last frame has ended too). A clip whose length the server cannot find counts as still playing
	 */
	public static boolean hasEnded(ServerPlayerEntity player, String controller) {
		ServerAnimationState state = ServerAnimationStates.get(player);
		AnimationPlayback playback = state == null ? null : state.get(controller);

		if (playback == null)
			return true;

		OptionalDouble length = clipLengthTicks(player, playback.animation());

		return length.isPresent() && playback.hasEnded(length.getAsDouble(), player.getWorld().getTime());
	}

	/**
	 * Plays {@code animation} on the {@link #SCENE} controller of {@code player}'s body: {@link #play} for the controller every body has. Playing the clip that is
	 * already playing starts it again from its first frame
	 */
	public static void play(ServerPlayerEntity player, String animation, LoopMode loopMode) {
		play(player, SCENE, animation, loopMode);
	}

	/**
	 * Changes how fast {@code controller} of {@code player}'s body plays what it is playing, without the clip jumping; nothing happens if it plays nothing
	 */
	public static void setRate(ServerPlayerEntity player, String controller, float rate) {
		ServerAnimationStates.forEntity(player, true).setRate(controller, rate, player.getWorld().getTime());
	}

	/**
	 * Jumps {@code controller} of {@code player}'s body to {@code clipTick} ticks into its clip
	 *
	 * @param fireCrossed whether the events passed over are fired
	 */
	public static void seek(ServerPlayerEntity player, String controller, float clipTick, boolean fireCrossed) {
		ServerAnimationStates.forEntity(player, true).seek(controller, clipTick, player.getWorld().getTime(), fireCrossed);
	}

	/**
	 * The clip {@code controller} of {@code player}'s body is playing from {@link #play}, or null if it plays none
	 */
	@Nullable
	public static String playing(ServerPlayerEntity player, String controller) {
		ServerAnimationState state = ServerAnimationStates.get(player);
		AnimationPlayback playback = state == null ? null : state.get(controller);

		return playback == null ? null : playback.animation();
	}

	/**
	 * Stops whatever {@code controller} of {@code player}'s body was playing from {@link #play}
	 */
	public static void stop(ServerPlayerEntity player, String controller) {
		ServerAnimationStates.forEntity(player, true).stop(controller, player.getWorld().getTime());
	}
}
