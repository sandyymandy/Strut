package com.sandymandy.strut.player;

import com.sandymandy.strut.constant.dataticket.DataTicket;

import java.util.function.Predicate;

/**
 * The data a player body's controllers can read from their animation state
 */
public final class PlayerTickets {
	/** What the player is doing: set for every frame a body is drawn */
	public static final DataTicket<PlayerMotion> MOTION = new DataTicket<>("player_motion", PlayerMotion.class);

	/** Whether the body's animation file has a clip of that name: lets a controller fall back from a clip the file does not have */
	@SuppressWarnings({"unchecked", "rawtypes"})
	public static final DataTicket<Predicate<String>> HAS_CLIP = new DataTicket<>("player_has_clip", (Class) Predicate.class);

	private PlayerTickets() {}
}
