package com.sandymandy.strut.player;

import net.minecraft.util.Identifier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** A variant of a body changes only what it says: the rest is the body's own */
class PlayerBodyVariantTest {
	private static final Identifier BODY = Identifier.of("test", "creature");
	private static final Identifier KOBOLD_ARMOUR = Identifier.of("test", "imp");

	private static PlayerBody body() {
		return PlayerBody.builder(BODY).dimensions(0.6f, 1.8f, 1.62f).armour(Identifier.of("test", "creature")).eyeBone("creatureCam").hideBones("head")
				.hideInFirstPerson("head").firstPerson(FirstPersonMode.IMMERSIVE)
				.variants(variant -> switch (variant) {
					case "imp" -> BodyVariant.builder().dimensions(0.5f, 1.2f, 1.05f).armour(KOBOLD_ARMOUR).hideInFirstPerson("head", "horns").build();
					case "bee" -> BodyVariant.builder().eyeBone("beeCam").firstPerson(FirstPersonMode.VANILLA).build();
					default -> null;
				}).build();
	}

	@Test
	void aVariantWithNothingToSayIsTheBody() {
		PlayerBody body = body();

		assertEquals(1.8f, body.dimensions("ava").height());
		assertEquals(Identifier.of("test", "creature"), body.armourProfile("ava"));
		assertEquals("creatureCam", body.eyeBone("ava"));
		assertEquals(List.of("head"), body.hiddenBones("ava"));
		assertEquals(FirstPersonMode.IMMERSIVE, body.firstPerson("ava"));
		assertSame(BodyVariant.NONE, body.variant("ava"));
	}

	@Test
	void aVariantChangesWhatItNamesAndKeepsTheRest() {
		PlayerBody body = body();

		assertEquals(1.2f, body.dimensions("imp").height());
		assertEquals(KOBOLD_ARMOUR, body.armourProfile("imp"));
		assertEquals(List.of("head", "horns"), body.firstPersonHiddenBones("imp"));
		assertEquals("creatureCam", body.eyeBone("imp"), "the eye bone was not changed");
		assertEquals(List.of("head"), body.hiddenBones("imp"));

		assertEquals("beeCam", body.eyeBone("bee"));
		assertEquals(FirstPersonMode.VANILLA, body.firstPerson("bee"));
		assertEquals(1.8f, body.dimensions("bee").height(), "the hitbox was not changed");
	}

	@Test
	void aBodyWithoutVariantsAnswersItsOwn() {
		PlayerBody plain = PlayerBody.builder(BODY).dimensions(0.7f, 1.9f, 1.7f).build();

		assertEquals(1.9f, plain.dimensions("anything").height());
		assertNull(plain.armourProfile("anything"));
	}

	@Test
	void animationFilesEventBindingsAndHandlersAreAskedByVariant() {
		int[] handled = {0};
		PlayerBody body = PlayerBody.builder(BODY)
				.animationFiles(variant -> List.of(Identifier.of("test", "animations/" + variant + ".strutanim"), Identifier.of("test", "animations/base.strutanim")))
				.eventBindings(variant -> List.of(Identifier.of("test", "creatures/" + variant)))
				.onServerEvent(context -> handled[0]++).build();

		assertEquals(Identifier.of("test", "animations/ava.strutanim"), body.animationFiles("ava").get(0));
		assertEquals(2, body.animationFiles("ava").size());
		assertEquals(List.of(Identifier.of("test", "creatures/nia")), body.eventBindings("nia"));
		body.onServerEvent(null);
		body.onClientEvent(null);
		assertEquals(1, handled[0], "the server handler ran, and a body with no client handler ignores a client event");

		PlayerBody plain = PlayerBody.builder(BODY).build();

		assertTrue(plain.animationFiles("x").isEmpty());
		assertTrue(plain.eventBindings("x").isEmpty());
	}
}
