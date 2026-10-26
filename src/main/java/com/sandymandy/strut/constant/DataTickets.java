package com.sandymandy.strut.constant;

import java.util.function.Predicate;
import net.minecraft.util.math.Direction;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.item.ItemStack;
import net.minecraft.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;
import com.sandymandy.strut.StrutConstants;
import com.sandymandy.strut.constant.dataticket.DataTicket;
import com.sandymandy.strut.model.data.EntityModelData;
import com.sandymandy.strut.constant.dataticket.SerializableDataTicket;
import com.sandymandy.strut.util.StrutUtil;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stores the default (builtin) {@link DataTicket DataTickets} used in Strut
 * <p>
 * Additionally handles registration of {@link SerializableDataTicket SerializableDataTickets}
 */
public final class DataTickets {
	private static final Map<String, SerializableDataTicket<?>> SERIALIZABLE_TICKETS = new ConcurrentHashMap<>();
	
	// Builtin tickets
	// These tickets are used by Strut by default, usually added in by the StrutRenderer for use in animations
	public static final DataTicket<BlockEntity> BLOCK_ENTITY = new DataTicket<>("block_entity", BlockEntity.class);
	public static final DataTicket<ItemStack> ITEMSTACK = new DataTicket<>("itemstack", ItemStack.class);
	/** Whether the model's animation file has a clip of that name, for a controller that falls back from one it does not have (set for every entity drawn) */
	@SuppressWarnings({"unchecked", "rawtypes"})
	public static final DataTicket<Predicate<String>> HAS_CLIP = new DataTicket<>("has_clip", (Class) Predicate.class);
	public static final DataTicket<Entity> ENTITY = new DataTicket<>("entity", Entity.class);
	public static final DataTicket<EquipmentSlot> EQUIPMENT_SLOT = new DataTicket<>("equipment_slot", EquipmentSlot.class);
	public static final DataTicket<EntityModelData> ENTITY_MODEL_DATA = new DataTicket<>("entity_model_data", EntityModelData.class);
	public static final DataTicket<Double> TICK = new DataTicket<>("tick", Double.class);
	public static final DataTicket<ModelTransformationMode> ITEM_RENDER_PERSPECTIVE = new DataTicket<>("item_render_perspective", ModelTransformationMode.class);
	
	// Builtin serializable tickets
	// These are not used anywhere by default, but are provided as examples and for ease of use
	public static final SerializableDataTicket<Integer> ANIM_STATE = StrutUtil.addDataTicket(SerializableDataTicket.ofInt(StrutConstants.id("anim_state")));
	public static final SerializableDataTicket<String> ANIM = StrutUtil.addDataTicket(SerializableDataTicket.ofString(StrutConstants.id("anim")));
	public static final SerializableDataTicket<Integer> USE_TICKS = StrutUtil.addDataTicket(SerializableDataTicket.ofInt(StrutConstants.id("use_ticks")));
	public static final SerializableDataTicket<Boolean> ACTIVE = StrutUtil.addDataTicket(SerializableDataTicket.ofBoolean(StrutConstants.id("active")));
	public static final SerializableDataTicket<Boolean> OPEN = StrutUtil.addDataTicket(SerializableDataTicket.ofBoolean(StrutConstants.id("open")));
	public static final SerializableDataTicket<Boolean> CLOSED = StrutUtil.addDataTicket(SerializableDataTicket.ofBoolean(StrutConstants.id("closed")));
	public static final SerializableDataTicket<Direction> DIRECTION = StrutUtil.addDataTicket(SerializableDataTicket.ofEnum(StrutConstants.id("direction"), Direction.class));

	@Nullable
	public static SerializableDataTicket<?> byName(String id) {
		return SERIALIZABLE_TICKETS.getOrDefault(id, null);
	}

	/**
	 * Register a {@link SerializableDataTicket} with Strut for handling custom data transmission
	 * <p>
	 * It is recommended you don't call this directly, and instead call it via {@link StrutUtil#addDataTicket}
	 *
	 * @param ticket The SerializableDataTicket instance to register
	 * @return The registered instance
	 */
	public static <D> SerializableDataTicket<D> registerSerializable(SerializableDataTicket<D> ticket) {
		SerializableDataTicket<?> existingTicket = SERIALIZABLE_TICKETS.putIfAbsent(ticket.id(), ticket);

		if (existingTicket != null)
			StrutConstants.LOGGER.error("Duplicate SerializableDataTicket registered! This will cause issues. Existing: " + existingTicket.id() + ", New: " + ticket.id());

		return ticket;
	}
}
