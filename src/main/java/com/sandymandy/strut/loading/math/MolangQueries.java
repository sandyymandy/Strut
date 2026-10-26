package com.sandymandy.strut.loading.math;

import com.google.common.collect.Streams;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.Hand;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.Saddleable;
import net.minecraft.entity.Leashable;
import net.minecraft.entity.SkinOverlayOwner;
import net.minecraft.entity.Tameable;
import net.minecraft.entity.mob.Angerable;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.ai.pathing.AmphibiousSwimNavigation;
import net.minecraft.entity.ai.pathing.BirdNavigation;
import net.minecraft.entity.ai.pathing.MobNavigation;
import net.minecraft.entity.ai.pathing.SwimNavigation;
import net.minecraft.entity.ai.pathing.SpiderNavigation;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import net.minecraft.block.entity.BlockEntity;
import com.sandymandy.strut.animatable.StrutAnimatable;
import com.sandymandy.strut.animation.AnimatableManager;
import com.sandymandy.strut.animation.AnimationState;
import com.sandymandy.strut.constant.DataTickets;
import com.sandymandy.strut.loading.math.value.Variable;
import com.sandymandy.strut.util.ClientUtil;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToDoubleFunction;

/**
 * Helper class for the builtin <a href="https://learn.microsoft.com/en-us/minecraft/creator/reference/content/molangreference/examples/molangconcepts/molangintroduction?view=minecraft-bedrock-stable">Molang</a> query string constants for the {@link MathParser}.
 * <p>
 * These do not constitute a definitive list of queries; merely the default ones
 * <p>
 * Note that the implementations of the various queries in Strut may not necessarily match its implementation in Bedrock
 */
public final class MolangQueries {
	public static final String ACTOR_COUNT = "query.actor_count";
	public static final String ANIM_TIME = "query.anim_time";
	public static final String BLOCKING = "query.blocking";
	public static final String BLOCK_STATE = "query.block_state";
	public static final String BODY_X_ROTATION = "query.body_x_rotation";
	public static final String BODY_Y_ROTATION = "query.body_y_rotation";
	public static final String CAN_CLIMB = "query.can_climb";
	public static final String CAN_FLY = "query.can_fly";
	public static final String CAN_SWIM = "query.can_swim";
	public static final String CAN_WALK = "query.can_walk";
	public static final String CARDINAL_FACING = "query.cardinal_facing";
	public static final String CARDINAL_FACING_2D = "query.cardinal_facing_2d";
	public static final String CARDINAL_PLAYER_FACING = "query.cardinal_player_facing";
	public static final String CONTROLLER_SPEED = "query.controller_speed";
	public static final String DAY = "query.day";
	public static final String DEATH_TICKS = "query.death_ticks";
	public static final String DISTANCE_FROM_CAMERA = "query.distance_from_camera";
	public static final String EQUIPMENT_COUNT = "query.equipment_count";
	public static final String FRAME_ALPHA = "query.frame_alpha";
	public static final String GET_ACTOR_INFO_ID = "query.get_actor_info_id";
	public static final String GROUND_SPEED = "query.ground_speed";
	public static final String HAS_CAPE = "query.has_cape";
	public static final String HAS_COLLISION = "query.has_collision";
	public static final String HAS_GRAVITY = "query.has_gravity";
	public static final String HAS_HEAD_GEAR = "query.has_head_gear";
	public static final String HAS_OWNER = "query.has_owner";
	public static final String HAS_PLAYER_RIDER = "query.has_player_rider";
	public static final String HAS_RIDER = "query.has_rider";
	public static final String HEAD_X_ROTATION = "query.head_x_rotation";
	public static final String HEAD_Y_ROTATION = "query.head_y_rotation";
	public static final String HEALTH = "query.health";
	public static final String HURT_TIME = "query.hurt_time";
	public static final String INVULNERABLE_TICKS = "query.invulnerable_ticks";
	public static final String IS_ALIVE = "query.is_alive";
	public static final String IS_ANGRY = "query.is_angry";
	public static final String IS_BABY = "query.is_baby";
	public static final String IS_BREATHING = "query.is_breathing";
	public static final String IS_ENCHANTED = "query.is_enchanted";
	public static final String IS_FIRE_IMMUNE = "query.is_fire_immune";
	public static final String IS_FIRST_PERSON = "query.is_first_person";
	public static final String IS_INVISIBLE = "query.is_invisible";
	public static final String IS_IN_CONTACT_WITH_WATER = "query.is_in_contact_with_water";
	public static final String IS_IN_LAVA = "query.is_in_lava";
	public static final String IS_IN_WATER = "query.is_in_water";
	public static final String IS_IN_WATER_OR_RAIN = "query.is_in_water_or_rain";
	public static final String IS_LEASHED = "query.is_leashed";
	public static final String IS_MOVING = "query.is_moving";
	public static final String IS_ON_FIRE = "query.is_on_fire";
	public static final String IS_ON_GROUND = "query.is_on_ground";
	public static final String IS_POWERED = "query.is_powered";
	public static final String IS_RIDING = "query.is_riding";
	public static final String IS_SADDLED = "query.is_saddled";
	public static final String IS_SILENT = "query.is_silent";
	public static final String IS_SLEEPING = "query.is_sleeping";
	public static final String IS_SNEAKING = "query.is_sneaking";
	public static final String IS_SPRINTING = "query.is_sprinting";
	public static final String IS_STACKABLE = "query.is_stackable";
	public static final String IS_SWIMMING = "query.is_swimming";
	public static final String IS_USING_ITEM = "query.is_using_item";
	public static final String IS_WALL_CLIMBING = "query.is_wall_climbing";
	public static final String ITEM_MAX_USE_DURATION = "query.item_max_use_duration";
	public static final String LIFE_TIME = "query.life_time";
	public static final String MAIN_HAND_ITEM_MAX_DURATION = "query.main_hand_item_max_duration";
	public static final String MAIN_HAND_ITEM_USE_DURATION = "query.main_hand_item_use_duration";
	public static final String MAX_DURABILITY = "query.max_durability";
	public static final String MAX_HEALTH = "query.max_health";
	public static final String MOON_BRIGHTNESS = "query.moon_brightness";
	public static final String MOON_PHASE = "query.moon_phase";
	public static final String MOVEMENT_DIRECTION = "query.movement_direction";
	public static final String PLAYER_LEVEL = "query.player_level";
	public static final String REMAINING_DURABILITY = "query.remaining_durability";
	public static final String RIDER_BODY_X_ROTATION = "query.rider_body_x_rotation";
	public static final String RIDER_BODY_Y_ROTATION = "query.rider_body_y_rotation";
	public static final String RIDER_HEAD_X_ROTATION = "query.rider_head_x_rotation";
	public static final String RIDER_HEAD_Y_ROTATION = "query.rider_head_y_rotation";
	public static final String SCALE = "query.scale";
	public static final String SLEEP_ROTATION = "query.sleep_rotation";
	public static final String TIME_OF_DAY = "query.time_of_day";
	public static final String TIME_STAMP = "query.time_stamp";
	public static final String VERTICAL_SPEED = "query.vertical_speed";
	public static final String YAW_SPEED = "query.yaw_speed";

	private static final Map<String, Variable> VARIABLES = new ConcurrentHashMap<>();
	private static Actor<?> ACTOR = null;

	static {
		setDefaultQueryValues();
	}

	/**
	 * Returns whether a variable under the given identifier has already been registered, without creating a new instance
	 */
	public static boolean isExistingVariable(String name) {
		return VARIABLES.containsKey(name.toLowerCase(Locale.ROOT));
	}

	/**
	 * Register a new {@link Variable} with the math parsing system
	 * <p>
	 * Technically supports overriding by matching keys, though you should try to update the existing variable instances instead if possible
	 *
	 * @see MathParser#registerVariable(Variable)
	 */
	static void registerVariable(Variable variable) {
		VARIABLES.put(variable.name().toLowerCase(Locale.ROOT), variable);
	}

	/**
	 * @return The registered {@link Variable} instance for the given name
	 *
	 * @see MathParser#getVariableFor(String)
	 */
	static Variable getVariableFor(String name) {
		return VARIABLES.computeIfAbsent(applyPrefixAliases(name.toLowerCase(Locale.ROOT), "query.", "q."), key -> new Variable(key, 0));
	}

	/**
	 * Parse a given string formatted with a prefix, swapping out any potential aliases for the defined proper name
	 *
	 * @param text The base text to parse
	 * @param properName The "correct" prefix to apply
	 * @param aliases The available prefixes to check and replace
	 * @return The unaliased string, or the original string if no aliases match
	 */
	private static String applyPrefixAliases(String text, String properName, String... aliases) {
		for (String alias : aliases) {
			if (text.startsWith(alias))
				return properName + text.substring(alias.length());
		}

		return text;
	}

	/**
	 * Update the currently rendering animatable. Should be called via {@link com.sandymandy.strut.model.StrutModel#applyMolangQueries(AnimationState, double) StrutModel.applyMolangQueries} when rendering
	 * @param animationState The AnimationState for the current render pass
	 * @param animTime The internal tick counter kept by the {@link AnimatableManager manager} for this animatable
	 */
	public static void updateActor(AnimationState<? extends StrutAnimatable> animationState, double animTime) {
		ACTOR = new Actor<>(animationState, animationState.getAnimatable(), animTime, MinecraftClient.getInstance(), MinecraftClient.getInstance().world);
	}

	/**
	 * Cleanup method called automatically by {@link com.sandymandy.strut.renderer.StrutRenderer#defaultRender} to eliminate a memory leak
	 */
	public static void clearActor() {
		ACTOR = null;
	}

	/**
	 * Container record holding animation frame information for the currently rendering animatable.
	 * <p>
	 * This is used by Molang queries to retrieve information for evaluation.
	 */
	public record Actor<T>(AnimationState<? extends StrutAnimatable> animationState, T animatable, double animTime, MinecraftClient mc, World level) {}

	/**
	 * Set a variable value utilising the {@link #ACTOR} field, with convenient generic handling for ease of use
	 *
	 * @param name The variable name
	 * @param value The value supplier
	 * @param <T> The lowest-common type of object your actor needs to be in order to evaluate this variable
	 */
	public static <T> void setActorVariable(String name, ToDoubleFunction<Actor<T>> value) {
		getVariableFor(name.toLowerCase(Locale.ROOT)).set(() -> value.applyAsDouble((Actor)getActor()));
	}

	private static Actor<?> getActor() {
		return ACTOR;
	}

	private static void setDefaultQueryValues() {
		getVariableFor("PI").set(Math.PI);
		getVariableFor("E").set(Math.E);
		setActorVariable(CONTROLLER_SPEED, actor -> actor.animationState.getController().getAnimationSpeed());
		setActorVariable(CARDINAL_PLAYER_FACING, actor -> actor.mc.player.getHorizontalFacing().ordinal());
		setActorVariable(DAY, actor -> actor.level.getTime() / 24000d);
		setActorVariable(FRAME_ALPHA, actor -> actor.animationState().getPartialTick());
		setActorVariable(HAS_CAPE, actor -> actor.mc.player.getSkinTextures().capeTexture() != null ? 1 : 0);
		setActorVariable(IS_FIRST_PERSON, actor -> actor.mc.options.getPerspective() == Perspective.FIRST_PERSON ? 1 : 0);
		setActorVariable(LIFE_TIME, actor -> actor.animTime / 20d);
		setActorVariable(MOON_BRIGHTNESS, actor -> actor.level.getMoonSize());
		setActorVariable(MOON_PHASE, actor -> actor.level.getMoonPhase());
		setActorVariable(PLAYER_LEVEL, actor -> actor.mc.player.experienceLevel);
		setActorVariable(TIME_OF_DAY, actor -> actor.level.getSkyAngle(1.0f) / 24000f);
		setActorVariable(TIME_STAMP, actor -> actor.mc.world.getTime());

		setDefaultBlockEntityQueryValues();
		setDefaultEntityQueryValues();
		setDefaultLivingEntityQueryValues();
		setDefaultMobQueryValues();
		setDefaultItemQueryValues();
	}

	private static void setDefaultBlockEntityQueryValues() {
		MolangQueries.<BlockEntity>setActorVariable(BLOCK_STATE, actor -> actor.animatable.getCachedState().getBlock().getStateManager().getStates().indexOf(actor.animatable.getCachedState()));
	}

	private static void setDefaultEntityQueryValues() {
		MolangQueries.<Entity>setActorVariable(BODY_X_ROTATION, actor -> actor.animatable instanceof LivingEntity ? 0 : actor.animatable.getPitch(actor.animationState.getPartialTick()));
		MolangQueries.<Entity>setActorVariable(BODY_Y_ROTATION, actor -> actor.animatable instanceof LivingEntity living ? MathHelper.lerp(actor.animationState.getPartialTick(), living.prevBodyYaw, living.bodyYaw) : actor.animatable.getYaw(actor.animationState.getPartialTick()));
		MolangQueries.<Entity>setActorVariable(CARDINAL_FACING, actor -> actor.animatable.getHorizontalFacing().getId());
		MolangQueries.<Entity>setActorVariable(CARDINAL_FACING_2D, actor -> {
			int directionId = actor.animatable.getHorizontalFacing().getId();

			return directionId < 2 ? 6 : directionId;
		});
		MolangQueries.<Entity>setActorVariable(DISTANCE_FROM_CAMERA, actor -> actor.mc.gameRenderer.getCamera().getPos().distanceTo(actor.animatable.getPos()));
		MolangQueries.<Entity>setActorVariable(GET_ACTOR_INFO_ID, actor -> actor.animatable.getId());
		MolangQueries.<Entity>setActorVariable(HAS_COLLISION, actor -> !actor.animatable.noClip ? 1 : 0);
		MolangQueries.<Entity>setActorVariable(HAS_GRAVITY, actor -> !actor.animatable.hasNoGravity() ? 1 : 0);
		MolangQueries.<Entity>setActorVariable(HAS_OWNER, actor -> actor.animatable instanceof Tameable ownable && ownable.getOwnerUuid() != null ? 1 : 0);
		MolangQueries.<Entity>setActorVariable(HAS_PLAYER_RIDER, actor -> actor.animatable.hasPassenger(PlayerEntity.class::isInstance) ? 1 : 0);
		MolangQueries.<Entity>setActorVariable(HAS_RIDER, actor -> actor.animatable.hasPassengers() ? 1 : 0);
		MolangQueries.<Entity>setActorVariable(IS_ALIVE, actor -> actor.animatable.isAlive() ? 1 : 0);
		MolangQueries.<Entity>setActorVariable(IS_ANGRY, actor -> actor.animatable instanceof Angerable neutralMob && neutralMob.hasAngerTime() ? 1 : 0);
		MolangQueries.<Entity>setActorVariable(IS_BREATHING, actor -> actor.animatable.getAir() >= actor.animatable.getMaxAir() ? 1 : 0);
		MolangQueries.<Entity>setActorVariable(IS_FIRE_IMMUNE, actor -> actor.animatable.getType().isFireImmune() ? 1 : 0);
		MolangQueries.<Entity>setActorVariable(IS_INVISIBLE, actor -> actor.animatable.isInvisible() ? 1 : 0);
		MolangQueries.<Entity>setActorVariable(IS_IN_CONTACT_WITH_WATER, actor -> actor.animatable.isWet() ? 1 : 0);
		MolangQueries.<Entity>setActorVariable(IS_IN_LAVA, actor -> actor.animatable.isInLava() ? 1 : 0);
		MolangQueries.<Entity>setActorVariable(IS_IN_WATER, actor -> actor.animatable.isTouchingWater() ? 1 : 0);
		MolangQueries.<Entity>setActorVariable(IS_IN_WATER_OR_RAIN, actor -> actor.animatable.isTouchingWaterOrRain() ? 1 : 0);
		MolangQueries.<Entity>setActorVariable(IS_LEASHED, actor -> actor.animatable instanceof Leashable leashable && leashable.isLeashed() ? 1 : 0);
		MolangQueries.<Entity>setActorVariable(IS_MOVING, actor -> actor.animationState.isMoving() ? 1 : 0);
		MolangQueries.<Entity>setActorVariable(IS_ON_FIRE, actor -> actor.animatable.isOnFire() ? 1 : 0);
		MolangQueries.<Entity>setActorVariable(IS_ON_GROUND, actor -> actor.animatable.isOnGround() ? 1 : 0);
		MolangQueries.<Entity>setActorVariable(IS_POWERED, actor -> actor.animatable instanceof SkinOverlayOwner powerable && powerable.shouldRenderOverlay() ? 1 : 0);
		MolangQueries.<Entity>setActorVariable(IS_RIDING, actor -> actor.animatable.hasVehicle() ? 1 : 0);
		MolangQueries.<Entity>setActorVariable(IS_SADDLED, actor -> actor.animatable instanceof Saddleable saddleable && saddleable.isSaddled() ? 1 : 0);
		MolangQueries.<Entity>setActorVariable(IS_SILENT, actor -> actor.animatable.isSilent() ? 1 : 0);
		MolangQueries.<Entity>setActorVariable(IS_SNEAKING, actor -> actor.animatable.isInSneakingPose() ? 1 : 0);
		MolangQueries.<Entity>setActorVariable(IS_SPRINTING, actor -> actor.animatable.isSprinting() ? 1 : 0);
		MolangQueries.<Entity>setActorVariable(IS_SWIMMING, actor -> actor.animatable.isSwimming() ? 1 : 0);
		MolangQueries.<Entity>setActorVariable(MOVEMENT_DIRECTION, actor -> actor.animationState.isMoving() ? Direction.getFacing(actor.animatable.getVelocity()).getId() : 6);
		MolangQueries.<Entity>setActorVariable(RIDER_BODY_X_ROTATION, actor -> actor.animatable.hasPassengers() ? actor.animatable.getFirstPassenger() instanceof LivingEntity ? 0 : actor.animatable.getFirstPassenger().getPitch(actor.animationState.getPartialTick()) : 0);
		MolangQueries.<Entity>setActorVariable(RIDER_BODY_Y_ROTATION, actor -> actor.animatable.hasPassengers() ? actor.animatable.getFirstPassenger() instanceof LivingEntity living ? MathHelper.lerp(actor.animationState.getPartialTick(), living.prevBodyYaw, living.bodyYaw) : actor.animatable.getFirstPassenger().getYaw(actor.animationState.getPartialTick()) : 0);
		MolangQueries.<Entity>setActorVariable(RIDER_HEAD_X_ROTATION, actor -> actor.animatable.getFirstPassenger() instanceof LivingEntity living ? living.getPitch(actor.animationState.getPartialTick()) : 0);
		MolangQueries.<Entity>setActorVariable(RIDER_HEAD_Y_ROTATION, actor -> actor.animatable.getFirstPassenger() instanceof LivingEntity living ? living.getYaw(actor.animationState.getPartialTick()) : 0);
		MolangQueries.<Entity>setActorVariable(VERTICAL_SPEED, actor -> actor.animatable.getVelocity().y);
		MolangQueries.<Entity>setActorVariable(YAW_SPEED, actor -> actor.animatable.getYaw() - actor.animatable.prevYaw);
	}

	private static void setDefaultLivingEntityQueryValues() {
		MolangQueries.<LivingEntity>setActorVariable(BLOCKING, actor -> actor.animatable.isBlocking() ? 1 : 0);
		MolangQueries.<LivingEntity>setActorVariable(DEATH_TICKS, actor -> actor.animatable.deathTime == 0 ? 0 : actor.animatable.deathTime + actor.animationState.getPartialTick());
		MolangQueries.<LivingEntity>setActorVariable(EQUIPMENT_COUNT, actor -> Streams.stream(actor.animatable.getArmorItems()).filter(stack -> !stack.isEmpty()).count());
		MolangQueries.<LivingEntity>setActorVariable(GROUND_SPEED, actor -> actor.animatable.getVelocity().horizontalLength());
		MolangQueries.<LivingEntity>setActorVariable(HAS_HEAD_GEAR, actor -> !actor.animatable.getEquippedStack(EquipmentSlot.HEAD).isEmpty() ? 1 : 0);
		MolangQueries.<LivingEntity>setActorVariable(HEAD_X_ROTATION, actor -> actor.animatable.getPitch(actor.animationState.getPartialTick()));
		MolangQueries.<LivingEntity>setActorVariable(HEAD_Y_ROTATION, actor -> actor.animatable.getYaw(actor.animationState.getPartialTick()));
		MolangQueries.<LivingEntity>setActorVariable(HEALTH, actor -> actor.animatable.getHealth());
		MolangQueries.<LivingEntity>setActorVariable(HURT_TIME, actor -> actor.animatable.hurtTime == 0 ? 0 : actor.animatable.hurtTime - actor.animationState.getPartialTick());
		MolangQueries.<LivingEntity>setActorVariable(INVULNERABLE_TICKS, actor -> actor.animatable.timeUntilRegen == 0 ? 0 : actor.animatable.timeUntilRegen - actor.animationState.getPartialTick());
		MolangQueries.<LivingEntity>setActorVariable(IS_BABY, actor -> actor.animatable.isBaby() ? 1 : 0);
		MolangQueries.<LivingEntity>setActorVariable(IS_SLEEPING, actor -> actor.animatable.isSleeping() ? 1 : 0);
		MolangQueries.<LivingEntity>setActorVariable(IS_USING_ITEM, actor -> actor.animatable.isUsingItem() ? 1 : 0);
		MolangQueries.<LivingEntity>setActorVariable(IS_WALL_CLIMBING, actor -> actor.animatable.isClimbing() ? 1 : 0);
		MolangQueries.<LivingEntity>setActorVariable(MAIN_HAND_ITEM_MAX_DURATION, actor -> actor.animatable.getMainHandStack().getMaxUseTime(actor.animatable));
		MolangQueries.<LivingEntity>setActorVariable(MAIN_HAND_ITEM_USE_DURATION, actor -> actor.animatable.getActiveHand() == Hand.MAIN_HAND ? actor.animatable.getItemUseTime() / 20d + actor.animationState.getPartialTick() : 0);
		MolangQueries.<LivingEntity>setActorVariable(MAX_HEALTH, actor -> actor.animatable.getMaxHealth());
		MolangQueries.<LivingEntity>setActorVariable(SCALE, actor -> actor.animatable.getScale());
		MolangQueries.<LivingEntity>setActorVariable(SLEEP_ROTATION, actor -> Optional.ofNullable(actor.animatable.getSleepingDirection()).map(Direction::asRotation).orElse(0f));
	}

	private static void setDefaultMobQueryValues() {
		MolangQueries.<MobEntity>setActorVariable(CAN_CLIMB, actor -> !actor.animatable.isAiDisabled() && actor.animatable.getNavigation() instanceof SpiderNavigation ? 1 : 0);
		MolangQueries.<MobEntity>setActorVariable(CAN_FLY, actor -> !actor.animatable.isAiDisabled() && actor.animatable.getNavigation() instanceof BirdNavigation ? 1 : 0);
		MolangQueries.<MobEntity>setActorVariable(CAN_SWIM, actor -> !actor.animatable.isAiDisabled() && actor.animatable.getNavigation() instanceof SwimNavigation || actor.animatable.getNavigation() instanceof AmphibiousSwimNavigation ? 1 : 0);
		MolangQueries.<MobEntity>setActorVariable(CAN_WALK, actor -> !actor.animatable.isAiDisabled() && actor.animatable.getNavigation() instanceof MobNavigation || actor.animatable.getNavigation() instanceof AmphibiousSwimNavigation ? 1 : 0);
	}

	private static void setDefaultItemQueryValues() {
		MolangQueries.<Item>setActorVariable(IS_ENCHANTED, actor -> actor.animationState.getData(DataTickets.ITEMSTACK).hasEnchantments() ? 1 : 0);
		MolangQueries.<Item>setActorVariable(IS_STACKABLE, actor -> actor.animationState.getData(DataTickets.ITEMSTACK).isStackable() ? 1 : 0);
		MolangQueries.<Item>setActorVariable(ITEM_MAX_USE_DURATION, actor -> actor.animationState.getData(DataTickets.ITEMSTACK).getMaxUseTime(ClientUtil.getClientPlayer()));
		MolangQueries.<Item>setActorVariable(MAX_DURABILITY, actor -> actor.animationState.getData(DataTickets.ITEMSTACK).getMaxDamage());
		MolangQueries.<Item>setActorVariable(REMAINING_DURABILITY, actor -> {
			ItemStack stack = actor.animationState.getData(DataTickets.ITEMSTACK);

			return stack.isDamageable() ? stack.getMaxDamage() - stack.getDamage() : 1;
		});
	}
}
