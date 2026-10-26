package com.sandymandy.strut.animation;

import com.sandymandy.strut.animatable.StrutAnimatable;
import com.sandymandy.strut.constant.DataTickets;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.UseAction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Ready-made controllers for what a living entity does with its hands, for any Strut entity (a mob, a replaced entity): the swing of an attack, and using an
 * item
 * <pre>{@code
 * public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
 *     LivingActions.addTo(this, controllers);   // plays attack0, attack1, ... in turn, one for each swing, and use_eat, use_bow, ... while using an item
 * }
 * }</pre>
 * Clips are looked for in the model's animation file by name ({@code attack0}, {@code attack1}, ... as many as there are; {@code swing} when there are none;
 * {@code use_eat}, {@code use_drink}, {@code use_block}, {@code use_bow}, {@code use_crossbow}, {@code use_spear}, {@code use_spyglass}, {@code use_horn},
 * {@code use_brush}, then {@code use}), and one the file does not have is skipped. {@code prefix} goes in front of each name for a file whose clips are
 * {@code animation.base.attack0}. A swing's clip is played out in full when it is longer than the swing
 */
public final class LivingActions {
	/** The default name of the controller that plays attack swings */
	public static final String SWING = "swing";

	/** The default name of the controller that plays item use */
	public static final String USE = "use";

	private LivingActions() {}

	/**
	 * Adds a swing controller and a use controller with the default names and no prefix to {@code registrar}
	 */
	public static <T extends StrutAnimatable> void addTo(T animatable, AnimatableManager.ControllerRegistrar registrar) {
		addTo(animatable, registrar, "");
	}

	/**
	 * As {@link #addTo(StrutAnimatable, AnimatableManager.ControllerRegistrar)}, with {@code prefix} in front of every clip name
	 */
	public static <T extends StrutAnimatable> void addTo(T animatable, AnimatableManager.ControllerRegistrar registrar, String prefix) {
		registrar.add(swing(animatable, SWING, 2, prefix));
		registrar.add(use(animatable, USE, 2, prefix));
	}

	/**
	 * A controller that plays {@code attack0}, {@code attack1}, ... (or {@code swing}) once for each hand swing of the entity, turn by turn
	 */
	public static <T extends StrutAnimatable> AnimationController<T> swing(T animatable, String name, int transitionTicks, String prefix) {
		return new AnimationController<T>(animatable, name, transitionTicks, new Swing<T>(prefix)).blendOut(true);
	}

	/**
	 * A controller that plays {@code use_<action>} (or {@code use}) while the entity uses an item
	 */
	public static <T extends StrutAnimatable> AnimationController<T> use(T animatable, String name, int transitionTicks, String prefix) {
		return new AnimationController<T>(animatable, name, transitionTicks, state -> {
			Entity entity = state.getData(DataTickets.ENTITY);
			Predicate<String> has = state.getData(DataTickets.HAS_CLIP);

			if (!(entity instanceof LivingEntity living) || has == null || !living.isUsingItem())
				return PlayState.STOP;

			String clip = firstOf(has, prefix, List.of("use_" + actionName(living.getActiveItem()), "use"));

			return clip == null ? PlayState.STOP : state.setAndContinue(RawAnimation.begin().thenLoop(clip));
		}).blendOut(true);
	}

	/** The part of a clip name for what the item does: {@code eat}, {@code bow}; {@code other} for an action with no clip of its own */
	static String actionName(ItemStack stack) {
		UseAction action = stack.getUseAction();

		return switch (action) {
			case EAT, DRINK, BLOCK, BOW, SPEAR, CROSSBOW, SPYGLASS, BRUSH -> action.name().toLowerCase(Locale.ROOT);
			case TOOT_HORN -> "horn";
			default -> "other";
		};
	}

	private static String firstOf(Predicate<String> has, String prefix, List<String> names) {
		for (String name : names)
			if (has.test(prefix + name))
				return prefix + name;

		return null;
	}

	/** The clips of a combo: {@code attack0}, {@code attack1}, ... that the file has, or {@code swing} if it has none of them */
	static List<String> combo(Predicate<String> has, String prefix) {
		List<String> clips = new ArrayList<>(3);

		for (int i = 0; has.test(prefix + "attack" + i); i++)
			clips.add(prefix + "attack" + i);

		if (clips.isEmpty() && has.test(prefix + "swing"))
			clips.add(prefix + "swing");

		return clips;
	}

	private static final class Swing<T extends StrutAnimatable> implements AnimationController.AnimationStateHandler<T> {
		private final String prefix;
		private final Map<Integer, Integer> lastStart = new HashMap<>();
		private final Map<Integer, Integer> swings = new HashMap<>();
		private final Set<Integer> finishing = new HashSet<>();

		private Swing(String prefix) {
			this.prefix = prefix;
		}

		@Override
		public PlayState handle(AnimationState<T> state) {
			Entity entity = state.getData(DataTickets.ENTITY);
			Predicate<String> has = state.getData(DataTickets.HAS_CLIP);

			if (!(entity instanceof LivingEntity living) || has == null)
				return PlayState.STOP;

			int id = living.getId();

			if (living.handSwinging) {
				int start = living.age - living.handSwingTicks;
				Integer previous = this.lastStart.put(id, start);
				boolean fresh = previous == null || previous != start;
				List<String> clips = combo(has, this.prefix);

				if (clips.isEmpty())
					return PlayState.STOP;

				int count = this.swings.getOrDefault(id, -1) + (fresh ? 1 : 0);

				this.swings.put(id, count);

				if (fresh)
					state.getController().forceAnimationReset();

				this.finishing.add(id);

				return state.setAndContinue(RawAnimation.begin().then(clips.get(Math.floorMod(count, clips.size())), Animation.LoopType.PLAY_ONCE));
			}

			this.lastStart.remove(id);

			if (this.finishing.contains(id)) {
				AnimationController.State running = state.getController().getAnimationState();

				if (running == AnimationController.State.RUNNING || running == AnimationController.State.TRANSITIONING)
					return PlayState.CONTINUE;

				this.finishing.remove(id);
			}

			return PlayState.STOP;
		}
	}
}
