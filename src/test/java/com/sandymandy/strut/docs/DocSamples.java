package com.sandymandy.strut.docs;

import com.sandymandy.strut.StrutClient;
import com.sandymandy.strut.animatable.StrutBlockEntity;
import com.sandymandy.strut.animatable.StrutEntity;
import com.sandymandy.strut.animatable.StrutItem;
import com.sandymandy.strut.animation.AnimatableManager;
import com.sandymandy.strut.animation.AnimationController;
import com.sandymandy.strut.animation.RawAnimation;
import com.sandymandy.strut.animation.authority.ServerAnimationStates;
import com.sandymandy.strut.assetsync.StrutAssetSync;
import com.sandymandy.strut.assetsync.client.StrutAssetSyncClient;
import com.sandymandy.strut.event.type.EventAuthority;
import com.sandymandy.strut.event.server.EventContext;
import com.sandymandy.strut.event.type.StrutEventType;
import com.sandymandy.strut.event.type.StrutEventTypes;
import com.sandymandy.strut.event.StrutEvents;
import com.sandymandy.strut.loading.binary.StrutBinaryFormats.LoopMode;
import com.sandymandy.strut.model.DepthBias;
import com.sandymandy.strut.model.RenderMode;
import com.sandymandy.strut.model.ShadingMode;
import com.sandymandy.strut.model.StrutModel;
import com.sandymandy.strut.renderer.StrutEntityRenderer;
import com.sandymandy.strut.util.Color;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.item.Item;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.List;

/**
 * Every code sample in the documentation site (the {@code doc/} folder), as real, compiling code
 * <p>
 * The pages embed these regions verbatim, and {@code DocSamplesTest} fails when a page's copy differs from the region it names or
 * a region is unused. So if the API changes and a sample stops compiling, the build breaks; if a sample changes and its page does
 * not, the tests break. The documentation cannot silently drift from the code
 * <p>
 * Region markers are {@code docs:begin <name>} and {@code docs:end <name>} in line comments. Nothing here ever runs
 */
@SuppressWarnings("unused")
final class DocSamples {
	private DocSamples() {}

	// ---------------------------------------------------------------------------------------------
	// Entities
	// ---------------------------------------------------------------------------------------------

	// docs:begin entity-minimal
	public class HeroEntity extends PathAwareEntity implements StrutEntity {
		public HeroEntity(EntityType<? extends HeroEntity> type, World world) {
			super(type, world);
		}
	}
	// docs:end entity-minimal

	static void clientEntity(EntityType<HeroEntity> AVA) {
		// docs:begin client-entity
		StrutClient.entity(AVA);
		// docs:end client-entity
	}

	static void clientEntityAltTexture(EntityType<HeroEntity> NIA, Identifier shared) {
		// docs:begin client-entity-alt-texture
		StrutClient.entity(NIA, StrutModel.<HeroEntity>entity(Identifier.of("mymod", "nia")).withAltTexture(shared));
		// docs:end client-entity-alt-texture
	}

	static void clientEntityFactory(EntityType<HeroEntity> AVA) {
		// docs:begin client-entity-factory
		StrutClient.entity(AVA, context -> new StrutEntityRenderer<>(context, AVA).withScale(1.2f));
		// docs:end client-entity-factory
	}

	static StrutModel<HeroEntity> modelBuilder() {
		// docs:begin model-builder
		return StrutModel.<HeroEntity>builder()
				.model(creature -> Identifier.of("mymod", "strut/models/dressed/" + creature.getName().getString() + ".strutmodel"))
				.texture(creature -> Identifier.of("mymod", "textures/entities/" + creature.getName().getString() + ".png"))
				.animation(Identifier.of("mymod", "strut/animations/base/creature.strutanim"))
				.build();
		// docs:end model-builder
	}

	// ---------------------------------------------------------------------------------------------
	// Items, armor and block entities
	// ---------------------------------------------------------------------------------------------

	// docs:begin item-class
	public class CreaturestaffItem extends Item implements StrutItem {
		public CreaturestaffItem(Settings settings) {
			super(settings);
		}
	}
	// docs:end item-class

	static void clientItems(CreaturestaffItem STAFF, CreaturestaffItem OUTFIT, BlockEntityType<CreatureBedBlockEntity> BED) {
		// docs:begin client-items
		StrutClient.item(STAFF);          // strut/models/item/staff.strutmodel by convention
		StrutClient.armor(OUTFIT);             // strut/models/armor/outfit.strutmodel
		StrutClient.blockEntity(BED);     // strut/models/block/bed.strutmodel
		// docs:end client-items
	}

	// docs:begin block-entity-class
	public class CreatureBedBlockEntity extends BlockEntity implements StrutBlockEntity {
		public CreatureBedBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
			super(type, pos, state);
		}

		@Override
		public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
			controllers.add(new AnimationController<>(this, "main",
					state -> state.setAndContinue(RawAnimation.begin().thenLoop("idle"))));
		}
	}
	// docs:end block-entity-class

	// ---------------------------------------------------------------------------------------------
	// Animations
	// ---------------------------------------------------------------------------------------------

	static void playAnimation(Entity entity) {
		// docs:begin play-animation
		ServerAnimationStates.forEntity(entity, false)
				.play("base", "walk", entity.getWorld().getTime(), 0, 1f, LoopMode.LOOP);
		// docs:end play-animation
	}

	static void controlAnimation(Entity entity) {
		// docs:begin control-animation
		long now = entity.getWorld().getTime();

		ServerAnimationStates.forEntity(entity, false).setRate("base", 2f, now);       // play twice as fast from now on
		ServerAnimationStates.forEntity(entity, false).seek("base", 40f, now, false);  // jump to tick 40 without firing events
		ServerAnimationStates.forEntity(entity, false).stop("base", now);              // stop
		// docs:end control-animation
	}

	// ---------------------------------------------------------------------------------------------
	// Events
	// ---------------------------------------------------------------------------------------------

	static StrutEventType<Float> registerEventType() {
		// docs:begin event-type
		return StrutEventTypes.register(StrutEventType
				.builder(Identifier.of("mymod", "strike"), PacketCodecs.FLOAT.cast())
				.authority(EventAuthority.SERVER)
				.parser(Float::parseFloat)
				.serverHandler((context, strength) -> {
					// context.entity(), context.exactGameTime(), context.random() ... and the parsed payload
				})
				.build());
		// docs:end event-type
	}

	static void markerListener() {
		// docs:begin marker-listener
		StrutEvents.onServerMarker("swap_end", context -> {
			Entity entity = context.entity();
			// runs once per crossing of the marker, on the server, whatever the lag
		});
		// docs:end marker-listener
	}

	// docs:begin entity-event-hooks
	public class HeroEvents extends PathAwareEntity implements StrutEntity {
		public HeroEvents(EntityType<? extends HeroEvents> type, World world) {
			super(type, world);
		}

		@Override
		public void onAnimationEvent(EventContext context) {
			if (context.isMarker("footstep"))
				playSound(net.minecraft.sound.SoundEvents.BLOCK_GRASS_STEP, 0.5f, 1f);
		}

		@Override
		public List<Identifier> getEventBindings() {
			return List.of(Identifier.of("mymod", "creatures/ava"), Identifier.of("mymod", "creatures/default"));
		}
	}
	// docs:end entity-event-hooks

	// ---------------------------------------------------------------------------------------------
	// Bone overrides and attachments
	// ---------------------------------------------------------------------------------------------

	static void boneOverrides(StrutEntity entity) {
		// docs:begin bone-overrides
		entity.overrides().setHidden("hat", true);
		entity.overrides().setColor("hair", new Color(0xFFFF66AA));
		entity.overrides().setRotation("head", 0f, (float) Math.toRadians(25), 0f);
		entity.overrides().clear("hat", com.sandymandy.strut.animation.override.OverrideProperty.HIDDEN);
		// docs:end bone-overrides
	}

	static void attachments(StrutEntity entity) {
		// docs:begin attachments
		entity.attachments().setItem("main_hand", "right_hand", Identifier.of("minecraft", "diamond_sword"));
		entity.attachments().setBlock("crate", "back", Identifier.of("minecraft", "barrel"));
		entity.attachments().clear("main_hand");
		// docs:end attachments
	}

	// ---------------------------------------------------------------------------------------------
	// Asset sync
	// ---------------------------------------------------------------------------------------------

	static void assetSyncServer(byte[] soundsJson) {
		// docs:begin asset-sync-server
		StrutAssetSync.setRequired(true);
		StrutAssetSync.addSource(sink -> sink.add("mymod", "sounds.json", soundsJson));
		// docs:end asset-sync-server
	}

	static void assetSyncClient() {
		// docs:begin asset-sync-client
		StrutAssetSyncClient.onAssetsReady(() -> {
			// the server's models, sounds and textures are loaded; the player has not joined yet
		});
		// docs:end asset-sync-client
	}

	// ---------------------------------------------------------------------------------------------
	// Translucency, overlays, flat lighting
	// ---------------------------------------------------------------------------------------------

	static class HeroModel extends com.sandymandy.strut.model.DefaultedEntityStrutModel<HeroEntity> {
		HeroModel() {
			super(Identifier.of("mymod", "creature"));
		}

		// docs:begin render-mode
		@Override
		public RenderMode renderMode(HeroEntity creature) {
			return RenderMode.TRANSLUCENT;                 // the whole model blends, after everything opaque
		}

		@Override
		public ShadingMode shadingMode(HeroEntity creature) {
			return ShadingMode.FLAT;                        // no shading by direction; the world's own light still applies
		}

		@Override
		public DepthBias translucentDepthBias(HeroEntity creature) {
			return new DepthBias(-1f, -2f);                 // pull translucent bones and overlays towards the camera; not the model itself
		}

		@Override
		public boolean translucentCullsBackFaces(HeroEntity creature) {
			return true;                                    // hide a closed cube's inside faces; a one-sided plane vanishes from behind
		}
		// docs:end render-mode
	}

	static void boneLayers(HeroEntity creature, Identifier skin, Identifier freckles) {
		// docs:begin bone-layers
		// A model that is opaque by default, with one bone drawn blended
		creature.overrides().setTranslucent("blush", true);

		// A second texture over a bone, translucent, on top of its own. Layers 0 to 3, a higher layer draws later
		creature.overrides().setOverlay("head", 0, freckles);

		// On the client the same calls are this viewer's own, never sent anywhere: a skin, a player's setting
		creature.overrides().setTexture("head", skin);
		creature.overrides().clearOverlay("head", 0);
		// docs:end bone-layers
	}

	// ---------------------------------------------------------------------------------------------
	// Migrating from GeckoLib
	// ---------------------------------------------------------------------------------------------

	// docs:begin migrate-after
	public class HeroStrutEntity extends PathAwareEntity implements StrutEntity {
		public HeroStrutEntity(EntityType<? extends HeroStrutEntity> type, World world) {
			super(type, world);
		}

		// No cache field, no getAnimatableInstanceCache(), no getTick(), and no registerControllers()
		// unless you animate on the client. The server plays animations; see "Playing animations".
	}
	// docs:end migrate-after
}
