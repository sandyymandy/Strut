package com.sandymandy.strut.event.dispatch;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiPredicate;
import com.sandymandy.strut.event.client.ClientEventContext;

/**
 * Decides whether the local player is <i>taking part</i> in what an entity is doing, for
 * {@code ClientEventContext#isLocalPlayerParticipant}
 * <p>
 * Built in: the entity is the player, the player is riding it, or it is riding the player. A mod whose
 * notion of taking part is wider - a scene that morphs the player into a stand-in entity, say - adds
 * a rule with {@link com.sandymandy.strut.event.StrutEvents#addParticipantRule}. That is the whole
 * reason this is a list of rules rather than a fixed check: Strut cannot know what "involved" means to
 * a consumer's gameplay, and should not have to
 */
public final class EventParticipants {
	private static final Logger LOGGER = LogManager.getLogger("Strut");

	private final List<BiPredicate<Entity, PlayerEntity>> rules = new CopyOnWriteArrayList<>();

	public void addRule(BiPredicate<Entity, PlayerEntity> rule) {
		this.rules.add(rule);
	}

	/**
	 * @param player The local player, or null when there is none (still loading in)
	 */
	public boolean isParticipant(Entity entity, @Nullable PlayerEntity player) {
		if (player == null)
			return false;

		if (entity == player || entity.hasPassenger(player) || player.hasPassenger(entity))
			return true;

		for (BiPredicate<Entity, PlayerEntity> rule : this.rules) {
			try {
				if (rule.test(entity, player))
					return true;
			}
			catch (RuntimeException ex) {
				// A broken rule must not take the event delivery down with it; it simply does not match
				LOGGER.error("[Strut events] a participant rule threw", ex);
			}
		}

		return false;
	}
}
