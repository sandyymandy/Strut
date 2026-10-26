package com.sandymandy.strut.player;

import com.sandymandy.strut.player.PlayerMotion.Use;
import com.sandymandy.strut.player.PlayerMotion.Vehicle;
import java.util.Locale;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Which clip a player's motion plays: the first preference the body's animation file has, so a body with few clips still moves sensibly
 */
class PlayerLocomotionTest {
	private static final Set<String> EVERYTHING = Set.of("death", "sleep", "ride_boat", "ride_minecart", "ride_mount", "ride", "sit", "glide", "fly", "fall",
			"swim", "swim_idle", "tread", "crawl", "crawl_idle", "climb", "sneak_walk", "sneak_idle", "sneak", "run", "sprint", "walk", "idle",
			"swing_main", "swing_off", "swing", "use_eat", "use_bow", "use");

	private static Predicate<String> has(String... clips) {
		Set<String> set = Set.of(clips);

		return set::contains;
	}

	/** A player standing on the ground doing nothing: tests change what they are testing */
	private static class Motion {
		boolean onGround = true, inWater, underwater, swimming, crawling, sprinting, sneaking, climbing, gliding, flying, riding, sleeping, dead, hurt, moving;
		boolean swinging, swingMainHand = true, useOffHand;
		Vehicle vehicle = Vehicle.NONE;
		Use using = Use.NONE;
		float vertical;
		int swingStart = -1;

		PlayerMotion build() {
			return new PlayerMotion(1, this.onGround, this.inWater, this.underwater, this.swimming, this.crawling, this.sprinting, this.sneaking,
					this.climbing, this.gliding, this.flying, this.riding, this.vehicle, this.sleeping, this.dead, this.hurt, this.moving,
					this.moving ? 1f : 0f, this.vertical, this.swinging, this.swingMainHand, this.swingStart, this.using, this.useOffHand);
		}
	}

	private static String pick(Motion motion, Predicate<String> has) {
		return PlayerLocomotion.pickLocomotion(motion.build(), has, ClipNames.DEFAULT);
	}

	private static String pick(Motion motion) {
		return pick(motion, EVERYTHING::contains);
	}

	@Test
	void standingIdlesAndWalkingWalks() {
		Motion m = new Motion();

		assertEquals("idle", pick(m));

		m.moving = true;
		assertEquals("walk", pick(m));
	}

	@Test
	void sprintingRunsAndFallsBackToWalk() {
		Motion m = new Motion();

		m.moving = true;
		m.sprinting = true;
		assertEquals("run", pick(m));
		assertEquals("sprint", pick(m, has("sprint", "walk", "idle")));
		assertEquals("walk", pick(m, has("walk", "idle")));
	}

	@Test
	void sprintingWhileStandingStillIsIdle() {
		Motion m = new Motion();

		m.sprinting = true;
		assertEquals("idle", pick(m));
	}

	@Test
	void sneakingUsesTheSneakClipsAndFallsBack() {
		Motion m = new Motion();

		m.sneaking = true;
		assertEquals("sneak_idle", pick(m));
		assertEquals("sneak", pick(m, has("sneak", "idle")));
		assertEquals("idle", pick(m, has("idle", "walk")));

		m.moving = true;
		assertEquals("sneak_walk", pick(m));
		assertEquals("walk", pick(m, has("walk", "idle")));
	}

	@Test
	void fallingNeedsToBeAirborneAndGoingDown() {
		Motion m = new Motion();

		m.onGround = false;
		m.vertical = -0.4f;
		assertEquals("fall", pick(m));

		m.vertical = 0.3f;
		assertEquals("idle", pick(m), "a jump going up is not a fall");

		m.onGround = true;
		m.vertical = -0.4f;
		assertEquals("idle", pick(m), "on the ground is not a fall");
	}

	@Test
	void aFallWithoutAFallClipGoesOnPlayingWhatTheLegsDo() {
		Motion m = new Motion();

		m.onGround = false;
		m.vertical = -0.5f;
		assertEquals("idle", pick(m, has("idle", "walk")), "standing still in the air: idle");

		m.moving = true;
		assertEquals("walk", pick(m, has("idle", "walk")), "moving: not idle");

		m.sprinting = true;
		assertEquals("run", pick(m, has("idle", "walk", "run")), "a sprint jump keeps running");
	}

	@Test
	void aSprintJumpWithNoAirClipsNeverTouchesIdle() {
		Motion m = new Motion();

		m.moving = true;
		m.sprinting = true;

		for (boolean onGround : new boolean[] {true, false}) {
			for (float vertical : new float[] {0.4f, 0.1f, 0f, -0.1f, -0.5f}) {
				m.onGround = onGround;
				m.vertical = vertical;

				assertEquals("run", pick(m, has("idle", "walk", "run")), "on ground " + onGround + ", vertical " + vertical);
			}
		}
	}

	@Test
	void aFallClipIsPlayedWhenTheBodyHasOne() {
		Motion m = new Motion();

		m.onGround = false;
		m.moving = true;
		m.sprinting = true;
		m.vertical = -0.5f;
		assertEquals("fall", pick(m, has("idle", "walk", "run", "fall")));
	}

	@Test
	void flyingGlidingAndClimbingWithoutTheirClipsKeepWalking() {
		Motion m = new Motion();

		m.gliding = true;
		m.moving = true;
		assertEquals("walk", pick(m, has("idle", "walk")));

		m = new Motion();
		m.flying = true;
		m.onGround = false;
		assertEquals("idle", pick(m, has("idle", "walk")));

		m = new Motion();
		m.climbing = true;
		m.onGround = false;
		m.moving = true;
		assertEquals("walk", pick(m, has("idle", "walk")));
	}

	@Test
	void swimmingMovingOrTreadingWithFallbacks() {
		Motion m = new Motion();

		m.swimming = true;
		m.moving = true;
		assertEquals("swim", pick(m));
		assertEquals("walk", pick(m, has("walk", "idle")));

		m.moving = false;
		assertEquals("swim_idle", pick(m));
		assertEquals("tread", pick(m, has("tread", "idle")));
		assertEquals("idle", pick(m, has("idle")));
	}

	@Test
	void movingInWaterOutsideTheSwimmingPoseWalks() {
		Motion m = new Motion();

		m.inWater = true;
		m.underwater = true;
		m.onGround = false;
		m.moving = true;
		assertEquals("walk", pick(m));
	}

	@Test
	void ridingPicksTheVehicleClipFirst() {
		Motion m = new Motion();

		m.riding = true;
		m.vehicle = Vehicle.BOAT;
		assertEquals("ride_boat", pick(m));
		assertEquals("ride", pick(m, has("ride", "idle")));
		assertEquals("sit", pick(m, has("sit", "idle")));

		m.vehicle = Vehicle.MOUNT;
		assertEquals("ride_mount", pick(m));
	}

	@Test
	void glidingFlyingClimbingAndCrawling() {
		Motion m = new Motion();

		m.gliding = true;
		assertEquals("glide", pick(m));
		assertEquals("fly", pick(m, has("fly", "idle")));

		m = new Motion();
		m.flying = true;
		m.onGround = false;
		assertEquals("fly", pick(m));

		m = new Motion();
		m.climbing = true;
		m.onGround = false;
		assertEquals("climb", pick(m));

		m = new Motion();
		m.crawling = true;
		m.moving = true;
		assertEquals("crawl", pick(m));
		assertEquals("sneak_walk", pick(m, has("sneak_walk", "idle")));
	}

	@Test
	void sleepingAndDeathTakePriorityOverEverything() {
		Motion m = new Motion();

		m.sleeping = true;
		m.swimming = true;
		m.sneaking = true;
		assertEquals("sleep", pick(m));

		m.dead = true;
		assertEquals("death", pick(m));
	}

	@Test
	void aBodyWithNoMatchingClipPlaysNothing() {
		assertNull(pick(new Motion(), has()));
	}

	@Test
	void swingsPickTheHandsClipAndFallBack() {
		Motion m = new Motion();

		assertNull(PlayerLocomotion.pickSwing(m.build(), EVERYTHING::contains, ClipNames.DEFAULT), "nothing to play when not swinging");

		m.swinging = true;
		assertEquals("swing_main", PlayerLocomotion.pickSwing(m.build(), EVERYTHING::contains, ClipNames.DEFAULT));

		m.swingMainHand = false;
		assertEquals("swing_off", PlayerLocomotion.pickSwing(m.build(), EVERYTHING::contains, ClipNames.DEFAULT));
		assertEquals("swing", PlayerLocomotion.pickSwing(m.build(), has("swing"), ClipNames.DEFAULT));
		assertNull(PlayerLocomotion.pickSwing(m.build(), has("walk"), ClipNames.DEFAULT));
	}

	@Test
	void usingAnItemPicksItsClipAndFallsBackToTheGenericOne() {
		Motion m = new Motion();

		assertNull(PlayerLocomotion.pickUse(m.build(), EVERYTHING::contains, ClipNames.DEFAULT));

		m.using = Use.BOW;
		assertEquals("use_bow", PlayerLocomotion.pickUse(m.build(), EVERYTHING::contains, ClipNames.DEFAULT));

		m.using = Use.DRINK;
		assertEquals("use", PlayerLocomotion.pickUse(m.build(), EVERYTHING::contains, ClipNames.DEFAULT), "no use_drink clip: the generic one");
		assertNull(PlayerLocomotion.pickUse(m.build(), has("walk"), ClipNames.DEFAULT));
	}

	@Test
	void everyUseHasAClipSuffix() {
		for (Use use : Use.values())
			assertEquals(use.name().toLowerCase(Locale.ROOT), use.clipSuffix());
	}

	@Test
	void aPrefixIsPutInFrontOfEveryName() {
		ClipNames base = ClipNames.DEFAULT.withPrefix("animation.base.");
		Motion m = new Motion();

		m.moving = true;
		assertEquals("animation.base.walk", PlayerLocomotion.pickLocomotion(m.build(), has("animation.base.walk", "animation.base.idle"), base));
		assertNull(PlayerLocomotion.pickLocomotion(m.build(), has("walk", "idle"), base), "the unprefixed names are not looked for");
	}

	@Test
	void crouchIsUnderstoodAsSneak() {
		Motion m = new Motion();

		m.sneaking = true;
		m.moving = true;
		assertEquals("crouch_walk", PlayerLocomotion.pickLocomotion(m.build(), has("crouch_walk", "walk", "idle"), ClipNames.DEFAULT));

		m.moving = false;
		assertEquals("crouch_idle", PlayerLocomotion.pickLocomotion(m.build(), has("crouch_idle", "idle"), ClipNames.DEFAULT));
	}

	@Test
	void anAddedAliasIsTriedAfterTheRolesOwnName() {
		ClipNames names = ClipNames.DEFAULT.withAlias("death", "downed");
		Motion m = new Motion();

		m.dead = true;
		assertEquals("downed", PlayerLocomotion.pickLocomotion(m.build(), has("downed", "idle"), names));
		assertEquals("death", PlayerLocomotion.pickLocomotion(m.build(), has("death", "downed"), names));
	}

	@Test
	void aPrefixAndAliasesWorkTogetherForAFileNamedLikeABlockbenchExport() {
		// a Blockbench export names every clip animation.<model>.<name>; this file has run, crouch and downed but no sneak clips of its own
		ClipNames names = ClipNames.DEFAULT.withPrefix("animation.base.").withAlias("death", "downed");
		Predicate<String> file = has("animation.base.idle", "animation.base.walk", "animation.base.run", "animation.base.crouch_idle",
				"animation.base.crouch_walk", "animation.base.downed", "animation.base.sleep", "animation.base.sit", "animation.base.ride", "animation.base.fly");
		Motion m = new Motion();

		m.moving = true;
		m.sprinting = true;
		assertEquals("animation.base.run", PlayerLocomotion.pickLocomotion(m.build(), file, names));

		m.sprinting = false;
		m.sneaking = true;
		assertEquals("animation.base.crouch_walk", PlayerLocomotion.pickLocomotion(m.build(), file, names));

		m = new Motion();
		m.dead = true;
		assertEquals("animation.base.downed", PlayerLocomotion.pickLocomotion(m.build(), file, names));

		m = new Motion();
		m.gliding = true;
		assertEquals("animation.base.fly", PlayerLocomotion.pickLocomotion(m.build(), file, names));
	}

	@Test
	void swingsWalkThroughTheAttackCombo() {
		Motion m = new Motion();
		ClipNames names = ClipNames.DEFAULT.withPrefix("animation.base.");
		Predicate<String> file = has("animation.base.attack0", "animation.base.attack1", "animation.base.attack2");

		m.swinging = true;
		m.swingStart = 5;
		assertEquals("animation.base.attack0", PlayerLocomotion.pickSwing(m.build(), file, names, 0));
		assertEquals("animation.base.attack2", PlayerLocomotion.pickSwing(m.build(), file, names, 2));
		assertEquals("animation.base.attack1", PlayerLocomotion.pickSwing(m.build(), file, names, 4));
	}

	@Test
	void aChargedItemPlaysChargeThenHolds() {
		Motion m = new Motion();
		ClipNames names = ClipNames.DEFAULT.withPrefix("animation.base.");
		Predicate<String> file = has("animation.base.bow_charge", "animation.base.bow_idle", "animation.base.shield_charge", "animation.base.shield_idle");

		m.using = Use.BOW;
		assertEquals("animation.base.bow_charge", PlayerLocomotion.pickRaise(m.build(), file, names));
		assertEquals("animation.base.bow_idle", PlayerLocomotion.pickUse(m.build(), file, names));

		m.using = Use.BLOCK;
		assertEquals("animation.base.shield_charge", PlayerLocomotion.pickRaise(m.build(), file, names));
		assertEquals("animation.base.shield_idle", PlayerLocomotion.pickUse(m.build(), file, names));

		m.using = Use.CROSSBOW;
		assertNull(PlayerLocomotion.pickRaise(m.build(), file, names), "no crossbow clips in this file");
	}

	@Test
	void anItemUsedInTheOffHandPlaysItsOwnClipsWhenTheBodyHasThemAndTheMainHandOnesWhenNot() {
		Motion m = new Motion();

		m.using = Use.BLOCK;
		m.useOffHand = true;

		assertEquals("shield_idle_off", PlayerLocomotion.pickUse(m.build(), has("shield_idle", "shield_idle_off", "idle"), ClipNames.DEFAULT));
		assertEquals("shield_idle", PlayerLocomotion.pickUse(m.build(), has("shield_idle", "idle"), ClipNames.DEFAULT), "no clip of its own: the main hand's");
		assertEquals("shield_charge_off", PlayerLocomotion.pickRaise(m.build(), has("shield_charge", "shield_charge_off"), ClipNames.DEFAULT));
		assertEquals("use_off", PlayerLocomotion.pickUse(m.build(), has("use", "use_off"), ClipNames.DEFAULT));

		// in the main hand the off hand clips are never looked at
		m.useOffHand = false;

		assertEquals("shield_idle", PlayerLocomotion.pickUse(m.build(), has("shield_idle", "shield_idle_off"), ClipNames.DEFAULT));
	}
}
