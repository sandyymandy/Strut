package com.sandymandy.strut.player;

import com.sandymandy.strut.animation.AnimatableManager;
import com.sandymandy.strut.animation.Animation;
import com.sandymandy.strut.animation.AnimationController;
import com.sandymandy.strut.animation.AnimationState;
import com.sandymandy.strut.animation.PlayState;
import com.sandymandy.strut.animation.RawAnimation;
import com.sandymandy.strut.animation.authority.ClientAnimationState;
import com.sandymandy.strut.animation.authority.ClientAnimationStates;
import java.util.HashSet;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.function.Predicate;

/**
 * The ready-made controllers that make a body walk, run, sneak, swim, ride, sleep, swing and use items, from {@link PlayerMotion}
 * <p>
 * Add them with {@link PlayerBody.Builder#locomotion()}. They look for these clips in the body's animation file (named as {@link ClipNames} says, with
 * {@code crouch} understood for {@code sneak}), taking the first that exists, so a
 * body only has to author the ones it wants and the rest fall back (a body with only {@code idle} and {@code walk} still works in every situation):
 * <table>
 * <caption>Clips, in order of preference</caption>
 * <tr><th>Doing</th><th>Clips</th></tr>
 * <tr><td>dead</td><td>{@code death} (played once, held)</td></tr>
 * <tr><td>sleeping</td><td>{@code sleep}, {@code idle}</td></tr>
 * <tr><td>riding</td><td>{@code ride_boat}, {@code ride_minecart}, {@code ride_mount} (for that vehicle), {@code ride}, {@code sit}, {@code idle}</td></tr>
 * <tr><td>gliding (elytra)</td><td>{@code gliding}, {@code glide}, {@code elytra}, {@code fly}, {@code fall}, {@code idle}</td></tr>
 * <tr><td>swimming</td><td>in the swimming pose only (wading and treading water walk or stand): {@code swim} when moving else {@code swim_idle}, {@code tread}; then {@code walk} / {@code idle}</td></tr>
 * <tr><td>crawling</td><td>{@code crawl} / {@code crawl_idle}, then the sneak clips</td></tr>
 * <tr><td>climbing</td><td>{@code climb}, then {@code walk}, {@code idle}</td></tr>
 * <tr><td>flying (creative)</td><td>{@code fly}, {@code fall}, {@code idle}</td></tr>
 * <tr><td>falling</td><td>{@code fall}, {@code idle}</td></tr>
 * <tr><td>sneaking</td><td>{@code sneak_walk} / {@code sneak_idle}, {@code sneak}, then {@code walk} / {@code idle}</td></tr>
 * <tr><td>sprinting</td><td>{@code run}, {@code sprint}, {@code walk}</td></tr>
 * <tr><td>walking</td><td>{@code walk}</td></tr>
 * <tr><td>standing</td><td>{@code idle}</td></tr>
 * </table>
 * A second controller, {@code actions}, plays over those: {@code swing_main} or {@code swing_off} (then {@code swing}, then the combo {@code attack0}, {@code attack1}, ... one for each swing in turn) once per hand swing, played out in full even when the swing is shorter, and
 * {@code <item>_charge} once then {@code <item>_idle} held (<item> is {@code bow}, {@code crossbow}, {@code shield}, {@code eat}, {@code drink}, {@code spear}, {@code spyglass}, {@code horn} or {@code brush}), or
 * else {@code use_<item>} then {@code use} while the item is used
 * <p>
 * While the server is playing a clip on the body ({@code StrutPlayers.play}), locomotion stands aside, so a scene is not walked over
 */
public final class PlayerLocomotion {
	/** The name of the controller that plays the body's movement */
	public static final String LOCOMOTION = "locomotion";

	/** The name of the controller that plays swings */
	public static final String ACTIONS = "actions";

	/** The name of the controller that plays item use: charging a bow, raising a shield */
	public static final String USE = "use";

	private static final int LOCOMOTION_BLEND_TICKS = 5;
	private static final int ACTIONS_BLEND_TICKS = 2;
	/** Longer than a swing's: a drawn bow settles into its hold and comes down to the legs' clip over this */
	private static final int USE_BLEND_TICKS = 5;

	private PlayerLocomotion() {}

	/**
	 * Adds the locomotion and action controllers to {@code registrar}
	 */
	public static void addTo(PlayerBody body, AnimatableManager.ControllerRegistrar registrar) {
		registrar.add(new AnimationController<PlayerBody>(body, LOCOMOTION, LOCOMOTION_BLEND_TICKS, PlayerLocomotion::locomotion));
		registrar.add(new AnimationController<PlayerBody>(body, ACTIONS, ACTIONS_BLEND_TICKS, new Actions()).blendOut(true));
		registrar.add(new AnimationController<PlayerBody>(body, USE, USE_BLEND_TICKS, new Uses()).blendOut(true));
	}

	private static PlayState locomotion(AnimationState<PlayerBody> state) {
		PlayerMotion motion = state.getData(PlayerTickets.MOTION);
		Predicate<String> has = state.getData(PlayerTickets.HAS_CLIP);

		if (motion == null || has == null || serverIsPlaying(motion.playerId()))
			return PlayState.STOP;

		String clip = pickLocomotion(motion, has, state.getAnimatable().clipNames());

		if (clip == null)
			return PlayState.STOP;

		return state.setAndContinue(motion.dead() ? RawAnimation.begin().thenPlayAndHold(clip) : RawAnimation.begin().thenLoop(clip));
	}

	/**
	 * The clip locomotion plays for {@code motion}: the first of its preferences that {@code has}, or null if the body has none of them
	 */
	@Nullable
	public static String pickLocomotion(PlayerMotion motion, Predicate<String> has, ClipNames names) {
		return firstOf(has, names, locomotionPreferences(motion));
	}

	static List<String> locomotionPreferences(PlayerMotion m) {
		List<String> list = new ArrayList<>(8);

		if (m.dead()) {
			list.add("death");
			list.add("idle");
		}
		else if (m.sleeping()) {
			list.add("sleep");
			list.add("idle");
		}
		else if (m.riding()) {
			switch (m.vehicle()) {
				case BOAT -> list.add("ride_boat");
				case MINECART -> list.add("ride_minecart");
				case MOUNT -> list.add("ride_mount");
				default -> {}
			}

			list.add("ride");
			list.add("sit");
			list.add("idle");
		}
		else {
			// What the player is doing besides walking about, if the body has a clip for it. A body that has none does not stand still for it: the preferences go on to
			// what the player's legs are doing (a sprint jump keeps running through the air, and a fall without a fall clip keeps walking or standing)
			if (m.gliding()) {
				list.add("gliding");
				list.add("glide");
				list.add("elytra");
				list.add("fly");
				list.add("fall");
			}
			else if (m.swimming()) {
				list.add(m.moving() ? "swim" : "swim_idle");

				if (!m.moving())
					list.add("tread");
			}
			else if (m.crawling()) {
				list.add(m.moving() ? "crawl" : "crawl_idle");
			}
			else if (m.climbing() && !m.onGround()) {
				list.add("climb");
			}
			else if (m.flying() && !m.onGround()) {
				list.add("fly");
				list.add("fall");
			}
			else if (!m.onGround() && m.verticalSpeed() < -0.08f && !m.inWater()) {
				list.add("fall");
			}

			list.addAll(onFoot(m));
		}

		return list;
	}

	/** What a player on their feet plays, and what everything else falls back to: sneaking, sprinting, walking, standing */
	private static List<String> onFoot(PlayerMotion m) {
		List<String> list = new ArrayList<>(5);

		if (m.sneaking() || m.crawling()) {
			list.add(m.moving() ? "sneak_walk" : "sneak_idle");
			list.add("sneak");
		}

		if (m.moving() && m.sprinting() && !m.sneaking() && !m.crawling()) {
			list.add("run");
			list.add("sprint");
		}

		list.add(m.moving() ? "walk" : "idle");
		list.add("idle");

		return list;
	}

	@Nullable
	private static String firstOf(Predicate<String> has, ClipNames names, List<String> roles) {
		for (String role : roles)
			for (String clip : names.candidates(role))
				if (has.test(clip))
					return clip;

		return null;
	}

	/**
	 * The clip the actions controller plays for the hand swing of {@code motion}, or null
	 */
	@Nullable
	public static String pickSwing(PlayerMotion motion, Predicate<String> has, ClipNames names) {
		return pickSwing(motion, has, names, 0);
	}

	/**
	 * As {@link #pickSwing(PlayerMotion, Predicate, ClipNames)}, falling back to the body's combo of {@code attack0}, {@code attack1}, ... (as many as it has),
	 * of which swing number {@code combo} plays the one at {@code combo} modulo how many there are
	 */
	@Nullable
	public static String pickSwing(PlayerMotion motion, Predicate<String> has, ClipNames names, int combo) {
		if (!motion.swinging())
			return null;

		String own = firstOf(has, names, List.of(motion.swingMainHand() ? "swing_main" : "swing_off", "swing"));

		if (own != null)
			return own;

		List<String> attacks = new ArrayList<>(3);

		for (int i = 0; ; i++) {
			String clip = firstOf(has, names, List.of("attack" + i));

			if (clip == null)
				break;

			attacks.add(clip);
		}

		return attacks.isEmpty() ? null : attacks.get(Math.floorMod(combo, attacks.size()));
	}

	/** The part of a clip name for what the item does: {@code bow}, {@code crossbow}, {@code shield} (for blocking), {@code eat} */
	private static String useName(PlayerMotion motion) {
		return motion.using() == PlayerMotion.Use.BLOCK ? "shield" : motion.using().clipSuffix();
	}

	/**
	 * The roles to look for, for an item used in the off hand each preceded by its own off hand clip ({@code shield_idle_off} before {@code shield_idle}, as
	 * {@code swing_off} is for {@code swing}), so a body that animates only one arm for a use has the one clip for both hands, and a body that has the off hand's
	 * own arm in a clip of its own plays that
	 */
	private static List<String> withOffHand(PlayerMotion motion, String... roles) {
		List<String> list = new ArrayList<>(roles.length * 2);

		for (String role : roles) {
			if (motion.useOffHand())
				list.add(role + "_off");

			list.add(role);
		}

		return list;
	}

	/**
	 * The clip held (looped) while {@code motion}'s player uses an item: {@code bow_idle}, {@code crossbow_idle}, {@code shield_idle}, {@code eat_idle}, ...,
	 * then {@code use_bow} and so on, then {@code use}; or null
	 */
	@Nullable
	public static String pickUse(PlayerMotion motion, Predicate<String> has, ClipNames names) {
		if (motion.using() == PlayerMotion.Use.NONE)
			return null;

		return firstOf(has, names, withOffHand(motion, useName(motion) + "_idle", "use_" + motion.using().clipSuffix(), "use"));
	}

	/**
	 * The clip played once as an item starts to be used, before {@link #pickUse}'s clip is held: {@code bow_charge}, {@code crossbow_charge},
	 * {@code shield_charge}, ...; or null if the body has none
	 */
	@Nullable
	public static String pickRaise(PlayerMotion motion, Predicate<String> has, ClipNames names) {
		return motion.using() == PlayerMotion.Use.NONE ? null : firstOf(has, names, withOffHand(motion, useName(motion) + "_charge"));
	}

	private static boolean serverIsPlaying(int playerId) {
		ClientAnimationState state = ClientAnimationStates.get(playerId);

		return state != null && !state.playbacks().isEmpty();
	}

	/** Swings and item use. Remembers when each player's current swing began, so that a new swing restarts the clip */
	private static final class Actions implements AnimationController.AnimationStateHandler<PlayerBody> {
		private final Map<Integer, Integer> lastSwing = new HashMap<>();
		/** How many swings each player has made, for choosing the clip of a combo */
		private final Map<Integer, Integer> swings = new HashMap<>();
		/** The players whose swing clip is playing, which plays out after the swing itself ends */
		private final Set<Integer> finishing = new HashSet<>();

		@Override
		public PlayState handle(AnimationState<PlayerBody> state) {
			PlayerMotion motion = state.getData(PlayerTickets.MOTION);
			Predicate<String> has = state.getData(PlayerTickets.HAS_CLIP);

			if (motion == null || has == null || serverIsPlaying(motion.playerId()))
				return PlayState.STOP;

			ClipNames names = state.getAnimatable().clipNames();
			Integer previous = this.lastSwing.get(motion.playerId());
			boolean newSwing = motion.swinging() && (previous == null || previous != motion.swingStart());
			int count = this.swings.getOrDefault(motion.playerId(), -1) + (newSwing ? 1 : 0);
			String swing = pickSwing(motion, has, names, Math.max(0, count));

			if (motion.swinging()) {
				this.lastSwing.put(motion.playerId(), motion.swingStart());
				this.swings.put(motion.playerId(), count);
			}

			if (swing != null) {
				if (newSwing)
					state.getController().forceAnimationReset();

				this.finishing.add(motion.playerId());

				return state.setAndContinue(RawAnimation.begin().then(swing, Animation.LoopType.PLAY_ONCE));
			}

			this.lastSwing.remove(motion.playerId());

			// the swing is over but its clip is longer than the swing: it plays out
			if (this.finishing.contains(motion.playerId())) {
				AnimationController.State running = state.getController().getAnimationState();

				if (running == AnimationController.State.RUNNING || running == AnimationController.State.TRANSITIONING)
					return PlayState.CONTINUE;

				this.finishing.remove(motion.playerId());
			}

			return PlayState.STOP;
		}
	}

	/**
	 * Item use: the charge clip once, then the hold clip looped, started afresh each time an item is used (not only when the item changes). The controller
	 * blends out when the use ends, so a bow lowered or a shield dropped settles into the clip the legs play instead of snapping to it
	 */
	private static final class Uses implements AnimationController.AnimationStateHandler<PlayerBody> {
		private final Set<Integer> using = new HashSet<>();

		@Override
		public PlayState handle(AnimationState<PlayerBody> state) {
			PlayerMotion motion = state.getData(PlayerTickets.MOTION);
			Predicate<String> has = state.getData(PlayerTickets.HAS_CLIP);

			if (motion == null || has == null || serverIsPlaying(motion.playerId())) {
				if (motion != null)
					this.using.remove(motion.playerId());

				return PlayState.STOP;
			}

			int id = motion.playerId();
			ClipNames names = state.getAnimatable().clipNames();
			String hold = pickUse(motion, has, names);
			String charge = pickRaise(motion, has, names);

			// not using anything: the controller blends out of the last pose (see AnimationController#blendOut)
			if (hold == null && charge == null) {
				this.using.remove(id);

				return PlayState.STOP;
			}

			// a new use starts the clips again: the same clips as the use before would otherwise be taken for the ones already playing
			if (this.using.add(id))
				state.getController().forceAnimationReset();

			if (charge != null && hold != null && !charge.equals(hold))
				return state.setAndContinue(RawAnimation.begin().then(charge, Animation.LoopType.PLAY_ONCE).thenLoop(hold));

			if (charge != null && hold == null)
				return state.setAndContinue(RawAnimation.begin().thenPlayAndHold(charge));

			return state.setAndContinue(RawAnimation.begin().thenLoop(hold));
		}
	}
}
