package com.sandymandy.strut.player;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import com.sandymandy.strut.animation.AnimatableManager;
import com.sandymandy.strut.animation.authority.AuthorityMode;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The part of player bodies that needs no running game: the registry, the body description, and what is stored on the player
 */
class PlayerBodiesTest {
	private static final AtomicInteger NEXT = new AtomicInteger();

	private static Identifier unique() {
		return Identifier.of("strut_test", "body_" + NEXT.getAndIncrement());
	}

	@Test
	void aRegisteredBodyIsFoundByItsId() {
		Identifier id = unique();
		PlayerBody body = PlayerBodies.register(PlayerBody.builder(id).build());

		assertSame(body, PlayerBodies.get(id));
		assertTrue(PlayerBodies.all().contains(body));
	}

	@Test
	void anUnknownOrMissingIdFindsNothing() {
		assertNull(PlayerBodies.get(unique()));
		assertNull(PlayerBodies.get(null));
	}

	@Test
	void theSameIdCannotBeRegisteredTwice() {
		Identifier id = unique();

		PlayerBodies.register(PlayerBody.builder(id).build());

		assertThrows(IllegalArgumentException.class, () -> PlayerBodies.register(PlayerBody.builder(id).build()));
	}

	@Test
	void aBodyKeepsItsIdAndKeepsStateApartPerPlayer() {
		PlayerBody body = PlayerBody.builder(unique()).build();

		assertNotNull(body.getAnimatableInstanceCache());
		assertNotSame(body.getAnimatableInstanceCache().getManagerForId(1), body.getAnimatableInstanceCache().getManagerForId(2),
				"two players with one body have their own animation state");
		assertSame(body.getAnimatableInstanceCache().getManagerForId(1), body.getAnimatableInstanceCache().getManagerForId(1));
	}

	@Test
	void theControllersGivenToTheBuilderAreRegisteredForEachPlayer() {
		AtomicInteger registered = new AtomicInteger();
		PlayerBody body = PlayerBody.builder(unique()).controllers(registrar -> registered.incrementAndGet()).build();

		body.getAnimatableInstanceCache().getManagerForId(1).getAnimationControllers();
		body.getAnimatableInstanceCache().getManagerForId(2).getAnimationControllers();

		assertEquals(2, registered.get());
	}

	@Test
	void anAssignmentSurvivesBeingSavedAndRead() {
		BodyAssignment original = new BodyAssignment(Identifier.of("mymod", "fox"), "red");

		JsonObject written = BodyAssignment.CODEC.encodeStart(JsonOps.INSTANCE, original).getOrThrow().getAsJsonObject();

		assertEquals(original, BodyAssignment.CODEC.parse(JsonOps.INSTANCE, written).getOrThrow());
	}

	@Test
	void anAssignmentWithoutAVariantReadsAsEmpty() {
		JsonObject saved = new JsonObject();

		saved.addProperty("body", "mymod:fox");

		assertEquals(new BodyAssignment(Identifier.of("mymod", "fox"), ""), BodyAssignment.CODEC.parse(JsonOps.INSTANCE, saved).getOrThrow());
	}

	@Test
	void aBodyDefaultsToTheVanillaFirstPersonAndAHeadThatTurns() {
		PlayerBody body = PlayerBody.builder(unique()).build();

		assertEquals(FirstPersonMode.VANILLA, body.firstPerson());
		assertEquals("head", body.headBone());
		assertEquals("eyes", body.eyeBone());
		assertEquals(List.of("head"), body.firstPersonHiddenBones());
		assertTrue(body.hiddenBones().isEmpty());
		assertEquals(ClipNames.DEFAULT, body.clipNames());
	}

	@Test
	void theBuilderOptionsAreKept() {
		PlayerBody body = PlayerBody.builder(unique()).firstPerson(FirstPersonMode.IMMERSIVE).eyeBone("eye_socket").hideInFirstPerson("head", "hair")
				.hideBones("hat", "wings").headBone(null).clipPrefix("animation.base.").clipAlias("death", "downed").build();

		assertEquals(FirstPersonMode.IMMERSIVE, body.firstPerson());
		assertEquals("eye_socket", body.eyeBone());
		assertEquals(List.of("head", "hair"), body.firstPersonHiddenBones());
		assertEquals(List.of("hat", "wings"), body.hiddenBones());
		assertNull(body.headBone());
		assertEquals(List.of("animation.base.death", "animation.base.downed"), body.clipNames().candidates("death"));
	}

	@Test
	void aBodyDoesNotChangeWhenTheBuildersListIsChangedAfterwards() {
		PlayerBody.Builder builder = PlayerBody.builder(unique()).hideBones("hat");
		PlayerBody body = builder.build();

		builder.hideBones("wings");

		assertEquals(List.of("hat"), body.hiddenBones());
	}

	@Test
	void handItemsAreNotDrawnUnlessTheBodySaysWhere() {
		assertNull(PlayerBody.builder(unique()).build().mainHandItem());
		assertNull(PlayerBody.builder(unique()).build().offHandItem());

		PlayerBody body = PlayerBody.builder(unique()).mainHandItem("hand_r").offHandItem(HandItem.at("hand_l").offset(0f, -0.1f, 0f).rotation(0f, 90f, 0f)).build();

		assertEquals(HandItem.at("hand_r"), body.mainHandItem());
		assertEquals(new HandItem("hand_l", 0f, -0.1f, 0f, 0f, 90f, 0f, 1, 1, 1), body.offHandItem());
	}

	@Test
	void aHandItemDefaultsToTheWayAVanillaArmHoldsOne() {
		HandItem item = HandItem.at("hand_r");

		assertEquals(-90f, item.rotationX());
		assertEquals(0f, item.rotationY());
		assertEquals(0f, item.offsetY());
	}

	@Test
	void aBodyHasNoDimensionsUnlessItSaysSo() {
		assertNull(PlayerBody.builder(unique()).build().dimensions());

		BodyDimensions size = PlayerBody.builder(unique()).dimensions(0.5f, 1.5f, 1.35f).build().dimensions();

		assertEquals(new BodyDimensions(0.5f, 1.5f, 1.35f), size);
		assertEquals(0.5f / 0.6f, size.widthRatio(), 1.0e-6f);
		assertEquals(1.5f / 1.8f, size.heightRatio(), 1.0e-6f);
	}

	@Test
	void dimensionsMustBeSensible() {
		assertThrows(IllegalArgumentException.class, () -> new BodyDimensions(0f, 1.8f, 1.6f));
		assertThrows(IllegalArgumentException.class, () -> new BodyDimensions(0.6f, -1f, 1.6f));
		assertThrows(IllegalArgumentException.class, () -> new BodyDimensions(0.6f, 1.8f, 0f));
		assertThrows(IllegalArgumentException.class, () -> new BodyDimensions(0.6f, 1.5f, 1.6f), "eyes above the top of the head");
		assertThrows(IllegalArgumentException.class, () -> new BodyDimensions(Float.NaN, 1.8f, 1.6f));
	}

	@Test
	void everyBodyHasASceneControllerThatShowsTheServersClipAboveTheRest() {
		AnimatableManager.ControllerRegistrar registrar = new AnimatableManager.ControllerRegistrar(new ArrayList<>());

		PlayerBody.builder(unique()).locomotion().build().registerControllers(registrar);

		var controllers = registrar.controllers();
		var scene = controllers.get(controllers.size() - 1);

		assertEquals(StrutPlayers.SCENE, scene.getName(), "last, so it plays over locomotion, swings and item use");
		assertEquals(AuthorityMode.SERVER, scene.getAuthorityMode());
	}
}
