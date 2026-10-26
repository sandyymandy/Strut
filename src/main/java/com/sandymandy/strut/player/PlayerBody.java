package com.sandymandy.strut.player;

import com.sandymandy.strut.animatable.StrutReplacedEntity;
import com.sandymandy.strut.animatable.instance.AnimatableInstanceCache;
import com.sandymandy.strut.animatable.instance.SingletonAnimatableInstanceCache;
import com.sandymandy.strut.animation.AnimatableManager;
import com.sandymandy.strut.animation.AnimationController;
import com.sandymandy.strut.animation.PlayState;
import com.sandymandy.strut.animation.authority.AuthorityMode;
import com.sandymandy.strut.event.client.ClientEventContext;
import com.sandymandy.strut.event.server.EventContext;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * What a player can look like instead of the player model: a Strut model, with the animation controllers that drive it
 * <p>
 * A body is a description, not an entity. It is registered by id ({@link PlayerBodies#register}, on the server and on every client, usually from a mod
 * initialiser) and given to a player with {@link StrutPlayers#setBody}. Many players can have the same body at once: like an item or a replaced entity it is
 * a {@link StrutReplacedEntity}, keeping one set of controllers and animation state for each player, by the player's entity id
 * <p>
 * The model, texture and animations come from the same folders as an entity's, by the body's id: {@code strut/models/entity/<path>.strutmodel},
 * {@code textures/entity/<path>.png} and {@code strut/animations/entity/<path>.strutanim} in the id's namespace. A different model is given on the client with
 * {@code StrutPlayersClient#model}
 * <p>
 * Server-driven animation (a scene, a cutscene) works as it does for any entity: {@link StrutPlayers#play} starts a clip on a {@code SERVER}-authority
 * controller (every body has one, {@link StrutPlayers#SCENE}, over its other controllers), and scenes of several entities are made with {@code StrutScenes}, and bone overrides, attachments and events are used on the player through {@link StrutPlayers}
 */
public class PlayerBody implements StrutReplacedEntity {
	private final Identifier id;
	private final AnimatableInstanceCache cache = new SingletonAnimatableInstanceCache(this);
	private final boolean locomotion;
	private final ClipNames clipNames;
	@Nullable
	private final BodyDimensions dimensions;
	@Nullable
	private final HandItem mainHandItem;
	@Nullable
	private final HandItem offHandItem;
	private final FirstPersonMode firstPerson;
	private final boolean sleepRotation;
	@Nullable
	private final Identifier armourProfile;
	private int sceneBlendTicks = 6;
	private final boolean swimTilt;
	private final boolean glideTilt;
	private final String eyeBone;
	private final List<String> firstPersonHiddenBones;
	private final List<String> hiddenBones;
	@Nullable
	private final String headBone;
	private final Consumer<AnimatableManager.ControllerRegistrar> controllers;
	@Nullable
	private Function<String, BodyVariant> variants;
	private List<String> stableHeldModels = List.of();
	private boolean showOtherHand;
	private boolean showHeldInScenes;
	@Nullable
	private Function<String, List<Identifier>> animationFiles;
	@Nullable
	private Function<String, List<Identifier>> eventBindings;
	@Nullable
	private Consumer<EventContext> serverEvents;
	@Nullable
	private Consumer<ClientEventContext> clientEvents;

	protected PlayerBody(Identifier id, boolean locomotion, @Nullable String headBone, ClipNames clipNames, List<String> hiddenBones,
						 FirstPersonMode firstPerson, String eyeBone, List<String> firstPersonHiddenBones, @Nullable HandItem mainHandItem, @Nullable HandItem offHandItem,
						 @Nullable BodyDimensions dimensions, boolean sleepRotation, boolean swimTilt, boolean glideTilt, @Nullable Identifier armourProfile, Consumer<AnimatableManager.ControllerRegistrar> controllers) {
		this.armourProfile = armourProfile;
		this.sleepRotation = sleepRotation;
		this.swimTilt = swimTilt;
		this.glideTilt = glideTilt;
		this.dimensions = dimensions;
		this.mainHandItem = mainHandItem;
		this.offHandItem = offHandItem;
		this.firstPerson = firstPerson;
		this.eyeBone = eyeBone;
		this.firstPersonHiddenBones = List.copyOf(firstPersonHiddenBones);
		this.id = id;
		this.locomotion = locomotion;
		this.clipNames = clipNames;
		this.hiddenBones = List.copyOf(hiddenBones);
		this.headBone = headBone;
		this.controllers = controllers;
	}

	/**
	 * Starts describing a body. Nothing is registered until {@link PlayerBodies#register} is given the result
	 */
	public static Builder builder(Identifier id) {
		return new Builder(id);
	}

	/**
	 * The id this body is registered, found and chosen by
	 */
	public Identifier id() {
		return this.id;
	}

	/**
	 * The bone that turns to where the player looks, or null if none does. {@code head} unless the builder says otherwise. Used by the conventional model;
	 * a model given with {@code StrutPlayersClient#model} does its own
	 */
	@Nullable
	public String headBone() {
		return this.headBone;
	}

	/**
	 * How this body's animation file names the clips locomotion looks for
	 */
	public ClipNames clipNames() {
		return this.clipNames;
	}

	/**
	 * Bones that are hidden whenever this body is drawn, unless something with a higher priority (the server's overrides) shows them: parts of the model
	 * that are alternatives, or that only some variants use
	 */
	public List<String> hiddenBones() {
		return this.hiddenBones;
	}

	/** The wearer profile this body wears armour by (see {@code StrutArmour}), or null for a body that wears none */
	@Nullable
	public Identifier armourProfile() {
		return this.armourProfile;
	}

	/** Whether the renderer lays the body down on a bed (vanilla's sleeping rotation). When false only the clip lies it down, and it is turned to the bed's direction */
	public boolean sleepRotation() {
		return this.sleepRotation;
	}

	/** Whether the renderer tilts the body to lie along the water while swimming or crawling, as vanilla does */
	public boolean swimTilt() {
		return this.swimTilt;
	}

	/** Whether the renderer tilts the body to fly forward and bank while gliding with an elytra, as vanilla does */
	public boolean glideTilt() {
		return this.glideTilt;
	}

	/**
	 * The size of a player with this body, or null to leave the player the size they are
	 */
	@Nullable
	public BodyDimensions dimensions() {
		return this.dimensions;
	}

	/**
	 * Where the item in the player's main hand is drawn, or null if it is not drawn on this body
	 */
	@Nullable
	public HandItem mainHandItem() {
		return this.mainHandItem;
	}

	/**
	 * Where the item in the player's off hand is drawn, or null if it is not drawn on this body
	 */
	@Nullable
	public HandItem offHandItem() {
		return this.offHandItem;
	}

	/**
	 * What the player sees of their own body in first person: {@link FirstPersonMode#VANILLA} unless the builder says otherwise
	 */
	public FirstPersonMode firstPerson() {
		return this.firstPerson;
	}

	/**
	 * The bone the camera sits at in {@link FirstPersonMode#IMMERSIVE}
	 */
	public String eyeBone() {
		return this.eyeBone;
	}

	/**
	 * The bones hidden for the player themself in first person when {@link FirstPersonMode#IMMERSIVE}, so the camera is not inside them
	 */
	public List<String> firstPersonHiddenBones() {
		return this.firstPersonHiddenBones;
	}

	/**
	 * What differs from this body's own for {@code variant} (a player's {@code StrutPlayers.variant}), see {@link Builder#variants}: {@link BodyVariant#NONE} if
	 * nothing does
	 */
	public BodyVariant variant(String variant) {
		BodyVariant found = this.variants == null ? null : this.variants.apply(variant);

		return found == null ? BodyVariant.NONE : found;
	}

	/**
	 * The animation files the clips of {@code variant} come from, most preferred first: what the server, which has no model to ask, finds a clip's events and length
	 * by (see {@link Builder#animationFiles}). Empty if none are declared
	 */
	public List<Identifier> animationFiles(String variant) {
		List<Identifier> files = this.animationFiles == null ? null : this.animationFiles.apply(variant);

		return files == null ? List.of() : files;
	}

	/** The event binding sets {@code variant} uses, most specific first (see {@link Builder#eventBindings}); empty if none */
	public List<Identifier> eventBindings(String variant) {
		List<Identifier> sets = this.eventBindings == null ? null : this.eventBindings.apply(variant);

		return sets == null ? List.of() : sets;
	}

	/** Runs the body's server event handler, if it has one: called by the dispatcher for every event a clip of a player with this body fires */
	public void onServerEvent(EventContext context) {
		if (this.serverEvents != null)
			this.serverEvents.accept(context);
	}

	/** Runs the body's client event handler, if it has one */
	public void onClientEvent(ClientEventContext context) {
		if (this.clientEvents != null)
			this.clientEvents.accept(context);
	}

	/**
	 * Whether {@code stack}, held in a hand, keeps the model it has at rest while it is used (see {@link Builder#stableHeldModels}): a shield stays the shield it is
	 * held as, instead of turning into the blocking one. Anything not in the body's list has the game's own models, which change with what the player does (a bow
	 * is drawn)
	 */
	public boolean stableHeldModel(ItemStack stack) {
		if (this.stableHeldModels.isEmpty() || stack.isEmpty())
			return false;

		Identifier id = Registries.ITEM.getId(stack.getItem());

		for (String entry : this.stableHeldModels) {
			if (entry.startsWith("#") ? stack.isIn(TagKey.of(RegistryKeys.ITEM, Identifier.of(entry.substring(1)))) : id.equals(Identifier.of(entry)))
				return true;
		}

		return false;
	}

	/** Whether what the hands hold is drawn while the player is in a scene: not by default */
	public boolean showsHeldItemsInScenes() {
		return this.showHeldInScenes;
	}

	/** Whether the item in the hand that is not using something is drawn while the other hand uses one: not by default */
	public boolean showsOtherHandWhileUsing() {
		return this.showOtherHand;
	}

	/** The hitbox of a player with this body and {@code variant}, or null to leave them the size they are */
	@Nullable
	public BodyDimensions dimensions(String variant) {
		BodyDimensions own = variant(variant).dimensions();

		return own != null ? own : this.dimensions;
	}

	/** The wearer profile armour is worn by for {@code variant}, or null for none */
	@Nullable
	public Identifier armourProfile(String variant) {
		Identifier own = variant(variant).armourProfile();

		return own != null ? own : this.armourProfile;
	}

	/** {@link #eyeBone()} for {@code variant} */
	public String eyeBone(String variant) {
		String own = variant(variant).eyeBone();

		return own != null ? own : this.eyeBone;
	}

	/** {@link #hiddenBones()} for {@code variant} */
	public List<String> hiddenBones(String variant) {
		List<String> own = variant(variant).hiddenBones();

		return own != null ? own : this.hiddenBones;
	}

	/** {@link #firstPersonHiddenBones()} for {@code variant} */
	public List<String> firstPersonHiddenBones(String variant) {
		List<String> own = variant(variant).firstPersonHiddenBones();

		return own != null ? own : this.firstPersonHiddenBones;
	}

	/** {@link #mainHandItem()} for {@code variant} */
	@Nullable
	public HandItem mainHandItem(String variant) {
		HandItem own = variant(variant).mainHandItem();

		return own != null ? own : this.mainHandItem;
	}

	/** {@link #offHandItem()} for {@code variant} */
	@Nullable
	public HandItem offHandItem(String variant) {
		HandItem own = variant(variant).offHandItem();

		return own != null ? own : this.offHandItem;
	}

	/** {@link #firstPerson()} for {@code variant} */
	public FirstPersonMode firstPerson(String variant) {
		FirstPersonMode own = variant(variant).firstPerson();

		return own != null ? own : this.firstPerson;
	}

	@Override
	public EntityType<?> getReplacingEntityType() {
		return EntityType.PLAYER;
	}

	@Override
	public void registerControllers(AnimatableManager.ControllerRegistrar registrar) {
		// locomotion first: a controller added after it, and a server clip, play over it
		if (this.locomotion)
			PlayerLocomotion.addTo(this, registrar);

		this.controllers.accept(registrar);

		// the server's clip (a scene, a cutscene) is shown by a controller of every body, over all the rest, so StrutPlayers.play works without one being declared;
		// a body that declares its own of the name keeps it
		if (registrar.controllers().stream().noneMatch(controller -> controller.getName().equals(StrutPlayers.SCENE)))
			registrar.add(new AnimationController<PlayerBody>(this, StrutPlayers.SCENE, this.sceneBlendTicks, state -> PlayState.STOP).authorityMode(AuthorityMode.SERVER));
	}

	@Override
	public AnimatableInstanceCache getAnimatableInstanceCache() {
		return this.cache;
	}

	/**
	 * The player's age in ticks, which is what a player's own animation timing counts in
	 */
	@Override
	public double getTick(Object object) {
		return object instanceof Entity entity ? entity.age : 0;
	}

	@Override
	public String toString() {
		return "PlayerBody[" + this.id + "]";
	}

	public static final class Builder {
		private final Identifier id;
		private Consumer<AnimatableManager.ControllerRegistrar> controllers = registrar -> {};
		private boolean locomotion;
		private String headBone = "head";
		private ClipNames clipNames = ClipNames.DEFAULT;
		private final List<String> hiddenBones = new ArrayList<>();
		private FirstPersonMode firstPerson = FirstPersonMode.VANILLA;
		private HandItem mainHand;
		private BodyDimensions dimensions;
		private boolean sleepRotation = true;
		@Nullable
		private Identifier armourProfile;
		private boolean swimTilt = true;
		private boolean glideTilt = true;
		private HandItem offHand;
		private String eyeBone = "eyes";
		private List<String> firstPersonHidden = List.of("head");
		@Nullable
		private Function<String, BodyVariant> variants;
		private List<String> stableHeldModels = List.of();
		private boolean showOtherHand;
		private boolean showHeldInScenes;
		@Nullable
		private Function<String, List<Identifier>> animationFiles;
		@Nullable
		private Function<String, List<Identifier>> eventBindings;
		@Nullable
		private Consumer<EventContext> serverEvents;
		private int sceneBlendTicks = 6;
		@Nullable
		private Consumer<ClientEventContext> clientEvents;

		private Builder(Identifier id) {
			this.id = id;
		}

		/**
		 * The controllers that animate this body, as for any {@code StrutAnimatable#registerControllers}. Called once for each player that has the body
		 */
		public Builder controllers(Consumer<AnimatableManager.ControllerRegistrar> controllers) {
			this.controllers = controllers;

			return this;
		}

		/**
		 * Adds the ready-made controllers that walk, run, sneak, swim, ride, sleep, swing and use items from what the player is doing, choosing among
		 * the clips the body's animation file has: see {@link PlayerLocomotion}
		 */
		public Builder locomotion() {
			this.locomotion = true;

			return this;
		}

		/**
		 * The bone that follows where the player looks (pitch and yaw, on top of the animation), {@code head} by default; null for none
		 */
		public Builder headBone(@Nullable String bone) {
			this.headBone = bone;

			return this;
		}

		/**
		 * Puts {@code prefix} in front of every clip name locomotion looks for: {@code "animation.base."} for a file whose clips are {@code animation.base.walk}
		 */
		public Builder clipPrefix(String prefix) {
			this.clipNames = this.clipNames.withPrefix(prefix);

			return this;
		}

		/**
		 * Another name locomotion tries for {@code role} (one of the clips in {@link PlayerLocomotion}), after the role's own: {@code clipAlias("death", "downed")}
		 */
		public Builder clipAlias(String role, String clip) {
			this.clipNames = this.clipNames.withAlias(role, clip);

			return this;
		}

		/**
		 * Bones of the model that are hidden whenever this body is drawn. The server can show one again with an override of higher priority
		 */
		public Builder hideBones(String... bones) {
			this.hiddenBones.addAll(List.of(bones));

			return this;
		}

		/**
		 * What the player sees of their own body in first person. {@link FirstPersonMode#VANILLA} unless set
		 */
		public Builder firstPerson(FirstPersonMode mode) {
			this.firstPerson = mode;

			return this;
		}

		/**
		 * The bone the camera sits at in {@link FirstPersonMode#IMMERSIVE}: {@code eyes} unless set. Its position in the animated pose is the camera position, so
		 * it is best a bone with no geometry, placed at the eyes and not turned by the head's own animation
		 */
		public Builder eyeBone(String bone) {
			this.eyeBone = bone;

			return this;
		}

		/**
		 * The bones hidden for the player themself in first person when {@link FirstPersonMode#IMMERSIVE} (a hidden bone hides what is under it): {@code head}
		 * unless set. Other players still see them
		 */
		public Builder hideInFirstPerson(String... bones) {
			this.firstPersonHidden = List.of(bones);

			return this;
		}

		/**
		 * Draws the item the player holds in their main hand on {@code bone}, put at its pivot, turned as a vanilla arm holds it. Nothing is drawn unless set
		 */
		public Builder mainHandItem(String bone) {
			return mainHandItem(HandItem.at(bone));
		}

		/**
		 * As {@link #mainHandItem(String)}, with the item moved and turned from there: {@code HandItem.at("hand_r").offset(0, -0.1f, 0)}
		 */
		public Builder mainHandItem(HandItem item) {
			this.mainHand = item;

			return this;
		}

		/**
		 * Draws the item the player holds in their off hand on {@code bone}. Nothing is drawn unless set
		 */
		public Builder offHandItem(String bone) {
			return offHandItem(HandItem.at(bone));
		}

		/**
		 * As {@link #offHandItem(String)}, with the item moved and turned from there
		 */
		public Builder offHandItem(HandItem item) {
			this.offHand = item;

			return this;
		}

		/**
		 * Gives a player with this body this hitbox and eye height when standing, on both sides: how big they are to the game, what fits where they walk, and the
		 * height they aim from. Left alone unless set. This changes what a player can do, so a server has to be happy with it
		 */
		public Builder dimensions(float width, float height, float eyeHeight) {
			this.dimensions = new BodyDimensions(width, height, eyeHeight);

			return this;
		}

		/**
		 * Whether the renderer lays the body down in a bed, as vanilla does (on by default). Turn it off when the sleep clip lies the body down itself: the body
		 * is then only turned to the bed's direction
		 */
		public Builder sleepRotation(boolean on) {
			this.sleepRotation = on;

			return this;
		}

		/**
		 * Whether the renderer tilts the body along the water while swimming and crawling, as vanilla does (on by default). Turn it off when the clip is
		 * already horizontal
		 */
		public Builder swimTilt(boolean on) {
			this.swimTilt = on;

			return this;
		}

		/**
		 * Whether the renderer tilts the body forward and banks it while gliding with an elytra, as vanilla does (on by default)
		 */
		public Builder glideTilt(boolean on) {
			this.glideTilt = on;

			return this;
		}

		/**
		 * The wearer profile this body wears armour by: a file {@code assets/<ns>/strut/armour/wearers/<path>.json}. A body with none wears no armour
		 */
		public Builder armour(Identifier profile) {
			this.armourProfile = profile;

			return this;
		}

		/**
		 * What differs for each variant of this body (the string given to {@code StrutPlayers.setBody}): a character's hitbox, her armour profile, her camera bone.
		 * Called often (a hitbox is asked for every tick), so it should look something up, not build it. Return null or {@link BodyVariant#NONE} for a variant
		 * that is the body as it is. Variants that have no say in a setting get the body's own
		 */
		public Builder variants(Function<String, BodyVariant> variants) {
			this.variants = variants;

			return this;
		}

		/**
		 * The animation files the clips of a variant come from, most preferred first (ids as {@code StrutEntity#getAnimationFiles}: the animation resource's, as
		 * {@code mymod:strut/animations/entity/creature.strutanim}). The server uses them to find the events and the length of a clip played with {@code StrutPlayers.play};
		 * without them it looks the clip up by name across every file, which works only if no two files have it. Called whenever a clip is looked up, so return a list
		 * that is kept
		 */
		public Builder animationFiles(Function<String, List<Identifier>> files) {
			this.animationFiles = files;

			return this;
		}

		/**
		 * The event binding sets a variant's sounds and chat lines come from, most specific first (as {@code StrutEntity#getEventBindings}). Asked on the client
		 * whenever a marker of a clip of a player with this body fires
		 */
		public Builder eventBindings(Function<String, List<Identifier>> sets) {
			this.eventBindings = sets;

			return this;
		}

		/** How many ticks the scene controller (the server's clip, {@link StrutPlayers#SCENE}) blends into a clip from the pose it was showing, 6 by default; 0 cuts */
		public Builder sceneBlend(int ticks) {
			this.sceneBlendTicks = Math.max(0, ticks);

			return this;
		}

		/**
		 * Reacts on the server to each event a clip of a player with this body fires (the context's entity is the player, and {@code context.event()} the event), as
		 * {@code StrutEntity#onAnimationEvent} does for an entity: after the event type's own handler and before any {@code StrutEvents.onServer} listener
		 */
		public Builder onServerEvent(Consumer<EventContext> handler) {
			this.serverEvents = handler;

			return this;
		}

		/** As {@link #onServerEvent} on each client that sees the event */
		public Builder onClientEvent(Consumer<ClientEventContext> handler) {
			this.clientEvents = handler;

			return this;
		}

		/**
		 * The items that keep the model they have at rest while they are held and used, because the body's own clip shows the use: {@code "minecraft:shield"}, or a tag
		 * with a hash, {@code "#minecraft:swords"}. Any other item is drawn as the game draws it, so a bow shows its draw and a crossbow its load
		 */
		public Builder showHeldItemsInScenes() {
			this.showHeldInScenes = true;

			return this;
		}

		/**
		 * Keeps drawing what the hands hold while the player is in a scene ({@code StrutScenes.inScene}). By default it is hidden for the scene's length, since a
		 * scene's clip puts the hands where an item makes no sense
		 */
		public Builder showOtherHandWhileUsing() {
			this.showOtherHand = true;

			return this;
		}

		/**
		 * Keeps drawing the item in the hand that is not using something while the other hand uses one (a bow drawn, a shield raised, food eaten). By default that item
		 * is not drawn until the use is over, so it is not in the way of the aim, or the screen in first person
		 */
		public Builder stableHeldModels(String... itemsOrTags) {
			this.stableHeldModels = List.of(itemsOrTags);

			return this;
		}

		public PlayerBody build() {
			PlayerBody body = new PlayerBody(this.id, this.locomotion, this.headBone, this.clipNames, this.hiddenBones, this.firstPerson, this.eyeBone,
					this.firstPersonHidden, this.mainHand, this.offHand, this.dimensions, this.sleepRotation, this.swimTilt, this.glideTilt, this.armourProfile, this.controllers);
			body.variants = this.variants;
			body.stableHeldModels = this.stableHeldModels;
			body.showOtherHand = this.showOtherHand;
			body.showHeldInScenes = this.showHeldInScenes;
			body.animationFiles = this.animationFiles;
			body.eventBindings = this.eventBindings;
			body.serverEvents = this.serverEvents;
			body.sceneBlendTicks = this.sceneBlendTicks;
			body.clientEvents = this.clientEvents;

			return body;
		}
	}
}
