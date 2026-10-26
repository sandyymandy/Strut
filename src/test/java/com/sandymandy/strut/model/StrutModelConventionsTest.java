package com.sandymandy.strut.model;

import com.sandymandy.strut.animatable.StrutAnimatable;
import com.sandymandy.strut.animatable.instance.AnimatableInstanceCache;
import com.sandymandy.strut.animatable.instance.InstancedAnimatableInstanceCache;
import com.sandymandy.strut.animation.AnimatableManager;
import com.sandymandy.strut.animation.AnimationController;
import com.sandymandy.strut.animation.PlayState;
import com.sandymandy.strut.animation.authority.AnimationPlayback;
import com.sandymandy.strut.loading.binary.StrutBinaryFormats.LoopMode;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The parts of "write almost nothing to add an entity" that do not need a running game - model files
 * found by convention, the builder for files that do not follow it, and the controllers created on demand
 */
class StrutModelConventionsTest {
	/** An animatable that overrides nothing optional: no {@code registerControllers} at all */
	private static final class Bare implements StrutAnimatable {
		private final AnimatableInstanceCache cache = new InstancedAnimatableInstanceCache(this);

		@Override
		public AnimatableInstanceCache getAnimatableInstanceCache() {
			return this.cache;
		}

		@Override
		public double getTick(Object object) {
			return 0;
		}
	}

	private static Identifier id(String namespace, String path) {
		return Identifier.of(namespace, path);
	}

	// ---------------------------------------------------------------------------------------------
	// Conventions
	// ---------------------------------------------------------------------------------------------

	@Test
	@DisplayName("an entity's files follow from its id, in the binary formats")
	void entityConvention() {
		StrutModel<Bare> model = StrutModel.entity(id("mymod", "ava"));

		assertEquals(id("mymod", "strut/models/entity/ava.strutmodel"), model.getModelResource(null));
		assertEquals(id("mymod", "textures/entity/ava.png"), model.getTextureResource(null));
		assertEquals(id("mymod", "strut/animations/entity/ava.strutanim"), model.getAnimationResource(null));
	}

	@Test
	@DisplayName("items and blocks use their own folders, and nested ids nest the files")
	void itemBlockAndNested() {
		StrutModel<Bare> item = StrutModel.item(id("mymod", "staff"));
		StrutModel<Bare> block = StrutModel.block(id("mymod", "bed"));
		StrutModel<Bare> nested = StrutModel.entity(id("mymod", "creatures/ava"));

		assertEquals(id("mymod", "strut/models/item/staff.strutmodel"), item.getModelResource(null));
		assertEquals(id("mymod", "strut/models/block/bed.strutmodel"), block.getModelResource(null));
		assertEquals(id("mymod", "textures/block/bed.png"), block.getTextureResource(null));
		assertEquals(id("mymod", "strut/models/entity/creatures/ava.strutmodel"), nested.getModelResource(null));
	}

	@Test
	@DisplayName("worn armor uses its own folder")
	void armorConvention() {
		StrutModel<Bare> armor = StrutModel.armor(id("mymod", "gilded_helmet"));

		assertEquals(id("mymod", "strut/models/armor/gilded_helmet.strutmodel"), armor.getModelResource(null));
		assertEquals(id("mymod", "textures/armor/gilded_helmet.png"), armor.getTextureResource(null));
		assertEquals(id("mymod", "strut/animations/armor/gilded_helmet.strutanim"), armor.getAnimationResource(null));
	}

	@Test
	@DisplayName("no convention path uses a legacy JSON extension")
	void noLegacyExtensions() {
		for (StrutModel<Bare> model : List.<StrutModel<Bare>>of(StrutModel.entity(id("a", "b")), StrutModel.item(id("a", "b")), StrutModel.block(id("a", "b")))) {
			assertFalse(model.getModelResource(null).getPath().contains(".json"));
			assertFalse(model.getAnimationResource(null).getPath().contains(".json"));
		}
	}

	@Test
	@DisplayName("changing one part leaves the other two on the convention, and the namespace can differ per part")
	void changeOnePart() {
		StrutModel<Bare> model = StrutModel.<Bare>entity(id("mymod", "rig_demo")).withAltTexture(id("shared", "skin"));

		assertEquals(id("mymod", "strut/models/entity/rig_demo.strutmodel"), model.getModelResource(null));
		assertEquals(id("shared", "textures/entity/skin.png"), model.getTextureResource(null));
		assertEquals(id("mymod", "strut/animations/entity/rig_demo.strutanim"), model.getAnimationResource(null));

		DefaultedStrutModel<Bare> other = StrutModel.<Bare>entity(id("mymod", "a")).withAltModel(id("mymod", "b")).withAltAnimations(id("mymod", "c"));

		assertEquals(id("mymod", "strut/models/entity/b.strutmodel"), other.getModelResource(null));
		assertEquals(id("mymod", "strut/animations/entity/c.strutanim"), other.getAnimationResource(null));
		assertEquals(id("mymod", "textures/entity/a.png"), other.getTextureResource(null));
	}

	// ---------------------------------------------------------------------------------------------
	// Builder
	// ---------------------------------------------------------------------------------------------

	@Test
	@DisplayName("the builder takes constants and functions of the animatable")
	void builderConstantsAndFunctions() {
		Bare creature = new Bare();
		StrutModel<Bare> model = StrutModel.<Bare>builder()
				.model(animatable -> animatable == creature ? id("mymod", "strut/models/bare/eve.strutmodel") : id("mymod", "strut/models/bare/other.strutmodel"))
				.texture(id("mymod", "textures/entities/eve.png"))
				.animation(id("mymod", "strut/animations/base/creature.strutanim"))
				.build();

		assertEquals(id("mymod", "strut/models/bare/eve.strutmodel"), model.getModelResource(creature));
		assertEquals(id("mymod", "strut/models/bare/other.strutmodel"), model.getModelResource(new Bare()));
		assertEquals(id("mymod", "textures/entities/eve.png"), model.getTextureResource(creature), "a plural folder, unlike the convention");
		assertEquals(id("mymod", "strut/animations/base/creature.strutanim"), model.getAnimationResource(creature));
	}

	@Test
	@DisplayName("a function is evaluated every time, so a model can change with the animatable's state")
	void builderFunctionsAreLive() {
		boolean[] stripped = {false};
		StrutModel<Bare> model = StrutModel.<Bare>builder()
				.model(animatable -> id("mymod", stripped[0] ? "strut/models/bare/x.strutmodel" : "strut/models/dressed/x.strutmodel"))
				.texture(id("mymod", "t.png"))
				.animation(id("mymod", "a.strutanim"))
				.build();

		assertEquals("strut/models/dressed/x.strutmodel", model.getModelResource(new Bare()).getPath());

		stripped[0] = true;

		assertEquals("strut/models/bare/x.strutmodel", model.getModelResource(new Bare()).getPath());
	}

	@Test
	@DisplayName("building without all three parts fails at build time and names what is missing")
	void builderNeedsAllThree() {
		IllegalStateException none = assertThrows(IllegalStateException.class, () -> StrutModel.<Bare>builder().build());

		assertTrue(none.getMessage().contains("model") && none.getMessage().contains("texture") && none.getMessage().contains("animation"));

		IllegalStateException noTexture = assertThrows(IllegalStateException.class,
				() -> StrutModel.<Bare>builder().model(id("a", "m")).animation(id("a", "n")).build());

		assertTrue(noTexture.getMessage().contains("texture"));
		assertFalse(noTexture.getMessage().contains("missing: model"), "it reports only what is actually missing");
	}

	@Test
	@DisplayName("a null part is refused immediately, not on the first frame")
	void builderRefusesNull() {
		assertThrows(NullPointerException.class, () -> StrutModel.<Bare>builder().model((Identifier)null));
		assertThrows(NullPointerException.class, () -> StrutModel.<Bare>builder().model((Function<Bare, Identifier>)null));
		assertThrows(NullPointerException.class, () -> StrutModel.<Bare>builder().animation((Function<Bare, Identifier>)null));
		assertThrows(NullPointerException.class, () -> StrutModel.<Bare>builder().texture((Function<Bare, Identifier>)null));
		assertThrows(NullPointerException.class, () -> StrutModel.<Bare>builder().animation((Identifier)null));
	}

	// ---------------------------------------------------------------------------------------------
	// Controllers on demand
	// ---------------------------------------------------------------------------------------------

	private static AnimationPlayback playback(String controller) {
		return new AnimationPlayback(controller, "clip", 0, 0, 1, LoopMode.LOOP, 1);
	}

	@Test
	@DisplayName("an animatable that registers nothing has no controllers until the server plays on a name")
	void noControllersUntilPlayed() {
		AnimatableManager<Bare> manager = new AnimatableManager<>(new Bare());

		assertTrue(manager.getAnimationControllers().isEmpty());
	}

	@Test
	@DisplayName("a controller is created for each unregistered name the server plays on, once")
	void controllersAreCreatedOnDemand() {
		Bare animatable = new Bare();
		AnimatableManager<Bare> manager = new AnimatableManager<>(animatable);

		manager.ensureControllersFor(animatable, List.of(playback("base"), playback("face")));

		assertEquals(Set.of("base", "face"), manager.getAnimationControllers().keySet());

		AnimationController<Bare> base = manager.getAnimationControllers().get("base");

		manager.ensureControllersFor(animatable, List.of(playback("base"), playback("face")));

		assertSame(base, manager.getAnimationControllers().get("base"), "asking again must not replace a controller that already exists");
		assertEquals(2, manager.getAnimationControllers().size());
	}

	@Test
	@DisplayName("a controller the animatable registered itself is never replaced or touched")
	void registeredControllersSurvive() {
		Bare animatable = new Bare();
		AnimatableManager<Bare> manager = new AnimatableManager<>(animatable);
		AnimationController<Bare> own = new AnimationController<>(animatable, "base", 7, state -> PlayState.CONTINUE);

		manager.addController(own);
		manager.ensureControllersFor(animatable, List.of(playback("base"), playback("extra")));

		assertSame(own, manager.getAnimationControllers().get("base"));
		assertSame(own, manager.getAnimationControllers().get("base"), "and it is the same object with its own handler");
		assertTrue(manager.getAnimationControllers().containsKey("extra"));
	}

	@Test
	@DisplayName("nothing playing creates nothing")
	void nothingPlaying() {
		Bare animatable = new Bare();
		AnimatableManager<Bare> manager = new AnimatableManager<>(animatable);

		manager.ensureControllersFor(animatable, List.of());

		assertTrue(manager.getAnimationControllers().isEmpty());
	}
}
