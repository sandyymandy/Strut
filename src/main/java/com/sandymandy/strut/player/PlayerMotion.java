package com.sandymandy.strut.player;

import java.util.Locale;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.vehicle.AbstractMinecartEntity;
import net.minecraft.entity.vehicle.BoatEntity;
import net.minecraft.item.CrossbowItem;
import net.minecraft.util.Hand;
import net.minecraft.util.UseAction;
import org.jetbrains.annotations.Nullable;

/**
 * What a player is doing right now, in the terms an animation cares about, read from state the client has for every player (their own and everyone
 * else's): the flags vanilla syncs, the limb animator, the hand swing and what they are using
 * <p>
 * A body's controllers get it as the {@link PlayerTickets#MOTION} data of their animation state. {@link PlayerLocomotion} turns it into clips
 *
 * @param playerId the player's entity id
 * @param moving whether the legs are moving: the limb animator's speed is above a small threshold, as vanilla's walking animation reads it
 * @param speed the limb animator's speed: 0 standing, about 1 at a walk, and not more than 1
 * @param verticalSpeed blocks per tick up (positive) or down (negative), from the position change, since a remote player's velocity is not known
 * @param flying creative flight. Only known for the player themself: another player's flight is not synced, so they read as falling or standing
 * @param gliding flying with an elytra
 * @param swingStart the age, in ticks, at which the current hand swing began, or -1 if not swinging: two different swings have different values
 * @param useOffHand whether the item being used is in the off hand (or, with nothing in use, the loaded crossbow held is the off hand's alone)
 */
public record PlayerMotion(int playerId, boolean onGround, boolean inWater, boolean underwater, boolean swimming, boolean crawling, boolean sprinting,
						   boolean sneaking, boolean climbing, boolean gliding, boolean flying, boolean riding, Vehicle vehicle, boolean sleeping,
						   boolean dead, boolean hurt, boolean moving, float speed, float verticalSpeed, boolean swinging, boolean swingMainHand,
						   int swingStart, Use using, boolean useOffHand) {
	/** A motion in the main hand: what a player using nothing, or an item in the main hand, has */
	public PlayerMotion(int playerId, boolean onGround, boolean inWater, boolean underwater, boolean swimming, boolean crawling, boolean sprinting, boolean sneaking,
						boolean climbing, boolean gliding, boolean flying, boolean riding, Vehicle vehicle, boolean sleeping, boolean dead, boolean hurt, boolean moving,
						float speed, float verticalSpeed, boolean swinging, boolean swingMainHand, int swingStart, Use using) {
		this(playerId, onGround, inWater, underwater, swimming, crawling, sprinting, sneaking, climbing, gliding, flying, riding, vehicle, sleeping, dead, hurt, moving, speed,
				verticalSpeed, swinging, swingMainHand, swingStart, using, false);
	}

	/** What a riding player is on */
	public enum Vehicle {
		NONE, BOAT, MINECART, MOUNT, OTHER
	}

	/** What a player is using an item for */
	public enum Use {
		NONE, EAT, DRINK, BLOCK, BOW, SPEAR, CROSSBOW, SPYGLASS, HORN, BRUSH, OTHER;

		/** The part a clip name carries: {@code use_eat}, {@code use_bow} */
		public String clipSuffix() {
			return name().toLowerCase(Locale.ROOT);
		}
	}

	/**
	 * Reads {@code player}'s motion for the frame {@code partialTick} of the way into the current tick
	 */
	public static PlayerMotion of(PlayerEntity player, float partialTick) {
		float speed = Math.min(1f, player.limbAnimator.getSpeed(partialTick));
		boolean swimming = player.isSwimming();

		return new PlayerMotion(player.getId(), player.isOnGround(), player.isTouchingWater(), player.isSubmergedInWater(), swimming,
				player.getPose() == EntityPose.SWIMMING && !swimming, player.isSprinting(), player.getPose() == EntityPose.CROUCHING,
				player.isClimbing(), player.isFallFlying(), player.getAbilities().flying, player.hasVehicle(), vehicleOf(player.getVehicle()),
				player.isSleeping(), !player.isAlive() || player.getHealth() <= 0, player.hurtTime > 0, speed > 0.02f, speed,
				(float) (player.getY() - player.prevY), player.handSwinging, player.preferredHand == Hand.MAIN_HAND,
				player.handSwinging ? player.age - player.handSwingTicks : -1, useOf(player), useOffHandOf(player));
	}

	private static Vehicle vehicleOf(@Nullable Entity vehicle) {
		if (vehicle == null)
			return Vehicle.NONE;

		if (vehicle instanceof BoatEntity)
			return Vehicle.BOAT;

		if (vehicle instanceof AbstractMinecartEntity)
			return Vehicle.MINECART;

		return vehicle instanceof LivingEntity ? Vehicle.MOUNT : Vehicle.OTHER;
	}

	/** Whether the item in use is in the off hand; with none in use, whether the loaded crossbow that is held is in the off hand alone */
	private static boolean useOffHandOf(PlayerEntity player) {
		if (player.isUsingItem())
			return player.getActiveHand() == Hand.OFF_HAND;

		return !CrossbowItem.isCharged(player.getMainHandStack()) && CrossbowItem.isCharged(player.getOffHandStack());
	}

	private static Use useOf(PlayerEntity player) {
		if (!player.isUsingItem()) {
			// a loaded crossbow in hand is held as it is while it is drawn
			return CrossbowItem.isCharged(player.getMainHandStack()) || CrossbowItem.isCharged(player.getOffHandStack()) ? Use.CROSSBOW : Use.NONE;
		}

		UseAction action = player.getActiveItem().getUseAction();

		return switch (action) {
			case EAT -> Use.EAT;
			case DRINK -> Use.DRINK;
			case BLOCK -> Use.BLOCK;
			case BOW -> Use.BOW;
			case SPEAR -> Use.SPEAR;
			case CROSSBOW -> Use.CROSSBOW;
			case SPYGLASS -> Use.SPYGLASS;
			case TOOT_HORN -> Use.HORN;
			case BRUSH -> Use.BRUSH;
			default -> Use.OTHER;
		};
	}
}
