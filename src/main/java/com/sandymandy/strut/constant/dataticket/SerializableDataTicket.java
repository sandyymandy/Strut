package com.sandymandy.strut.constant.dataticket;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.util.Identifier;
import com.sandymandy.strut.constant.DataTickets;

/**
 * Network-compatible {@link DataTicket} implementation
 * <p>
 * Used for sending data from server -> client in an easy manner
 */
public abstract class SerializableDataTicket<D> extends DataTicket<D> {
	public static final PacketCodec<RegistryByteBuf, SerializableDataTicket<?>> STREAM_CODEC = PacketCodec.tuple(
			PacketCodecs.STRING,
			SerializableDataTicket::id,
            DataTickets::byName);

	public SerializableDataTicket(String id, Class<? extends D> objectType) {
		super(id, objectType);
	}

	/**
	 * @return The {@link PacketCodec} for the given SerializableDataTicket
	 */
	public abstract PacketCodec<? super RegistryByteBuf, D> streamCodec();

	// Pre-defined typings for use

	/**
	 * Generate a new {@code SerializableDataTicket<Double>} for the given id
	 *
	 * @param id The unique id of your ticket. Include your modid
	 */
	public static SerializableDataTicket<Double> ofDouble(Identifier id) {
		return new SerializableDataTicket<>(id.toString(), Double.class) {
			@Override
			public PacketCodec<? super RegistryByteBuf, Double> streamCodec() {
				return PacketCodecs.DOUBLE;
			}
		};
	}

	/**
	 * Generate a new {@code SerializableDataTicket<Float>} for the given id
	 *
	 * @param id The unique id of your ticket. Include your modid
	 */
	public static SerializableDataTicket<Float> ofFloat(Identifier id) {
		return new SerializableDataTicket<>(id.toString(), Float.class) {
			@Override
			public PacketCodec<? super RegistryByteBuf, Float> streamCodec() {
				return PacketCodecs.FLOAT;
			}
		};
	}

	/**
	 * Generate a new {@code SerializableDataTicket<Boolean>} for the given id
	 *
	 * @param id The unique id of your ticket. Include your modid
	 */
	public static SerializableDataTicket<Boolean> ofBoolean(Identifier id) {
		return new SerializableDataTicket<>(id.toString(), Boolean.class) {
			@Override
			public PacketCodec<? super RegistryByteBuf, Boolean> streamCodec() {
				return PacketCodecs.BOOL;
			}
		};
	}

	/**
	 * Generate a new {@code SerializableDataTicket<Integer>} for the given id
	 *
	 * @param id The unique id of your ticket. Include your modid
	 */
	public static SerializableDataTicket<Integer> ofInt(Identifier id) {
		return new SerializableDataTicket<>(id.toString(), Integer.class) {
			@Override
			public PacketCodec<? super RegistryByteBuf, Integer> streamCodec() {
				return PacketCodecs.VAR_INT;
			}
		};
	}

	/**
	 * Generate a new {@code SerializableDataTicket<String>} for the given id
	 *
	 * @param id The unique id of your ticket. Include your modid
	 */
	public static SerializableDataTicket<String> ofString(Identifier id) {
		return new SerializableDataTicket<>(id.toString(), String.class) {
			@Override
			public PacketCodec<? super RegistryByteBuf, String> streamCodec() {
				return PacketCodecs.STRING;
			}
		};
	}

	/**
	 * Generate a new {@code SerializableDataTicket<Enum>} for the given id
	 *
	 * @param id The unique id of your ticket. Include your modid
	 */
	public static <E extends Enum<E>> SerializableDataTicket<E> ofEnum(Identifier id, Class<E> enumClass) {
		return new SerializableDataTicket<>(id.toString(), enumClass) {
			@Override
			public PacketCodec<? super RegistryByteBuf, E> streamCodec() {
				return new PacketCodec<>() {
                    @Override
                    public E decode(RegistryByteBuf buf) {
                        return Enum.valueOf(enumClass, buf.readString());
                    }

                    @Override
                    public void encode(RegistryByteBuf buf, E data) {
                        buf.writeString(data.toString());
                    }
                };
			}
		};
	}
}
