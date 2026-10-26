package com.sandymandy.strut.event.builtin;

import com.sandymandy.strut.StrutConstants;
import com.sandymandy.strut.event.type.EventAuthority;
import com.sandymandy.strut.event.type.EventCategory;
import com.sandymandy.strut.event.type.EventTypeRegistry;
import com.sandymandy.strut.event.type.StrutEventType;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.util.Identifier;
import net.minecraft.util.Unit;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The event types the engine ships
 * <p>
 * {@link #SOUND} and {@link #PARTICLE} are the two legacy keyframe kinds made typed; {@link #MARKER}
 * is the general-purpose named synchronisation point; {@link #LOOP} and {@link #END} are raised by
 * the cursor itself and cannot be authored. Sound and particle are {@link EventAuthority#CLIENT} and
 * <b>stay so</b>: a sound played from the server's world would reach other clients as a packet while
 * every client also plays it from its own timeline, so it would play twice
 */
public final class StrutBuiltinEvents {
	private static final Pattern MARKER_NAME = Pattern.compile("[A-Za-z0-9_./-]+");

	/**
	 * A named point in a clip. It exists to be listened for; the only thing the engine does with one on its
	 * own is carry out whatever the entity's event bindings bind to its name, if anything
	 */
	public static final StrutEventType<String> MARKER = StrutEventType
			.builder(Identifier.of(StrutConstants.MODID, "marker"), PacketCodecs.STRING.cast())
			.category(EventCategory.MARKER)
			.authority(EventAuthority.BOTH)
			.parser(StrutBuiltinEvents::parseMarker)
			.clientHandler(ClientSoundPlayback::onMarker)
			.build();

	/**
	 * A sound cue, played on each client from its own timeline: either a sound id played as it is, or a binding
	 * key the entity's event bindings turn into that character's sounds
	 */
	public static final StrutEventType<SoundEventPayload> SOUND = StrutEventType
			.builder(Identifier.of(StrutConstants.MODID, "sound"), SoundEventPayload.CODEC)
			.category(EventCategory.AUDIO)
			.authority(EventAuthority.CLIENT)
			.parser(SoundEventPayload::parse)
			.clientHandler(ClientSoundPlayback::onSound)
			.build();

	/** A particle effect, spawned on each client from its own timeline */
	public static final StrutEventType<ParticleEventPayload> PARTICLE = StrutEventType
			.builder(Identifier.of(StrutConstants.MODID, "particle"), ParticleEventPayload.CODEC)
			.category(EventCategory.VISUAL)
			.authority(EventAuthority.CLIENT)
			.parser(ParticleEventPayload::parse)
			.build();

	/** A looping clip wrapped; the payload is the loop being entered. Raised by the engine only */
	public static final StrutEventType<Long> LOOP = StrutEventType
			.builder(Identifier.of(StrutConstants.MODID, "loop"), PacketCodecs.VAR_LONG.cast())
			.category(EventCategory.MARKER)
			.authority(EventAuthority.BOTH)
			.build();

	/** A non-looping clip reached its end. Raised by the engine only */
	public static final StrutEventType<Unit> END = StrutEventType
			.builder(Identifier.of(StrutConstants.MODID, "end"), PacketCodec.<PacketByteBuf, Unit>unit(Unit.INSTANCE))
			.category(EventCategory.MARKER)
			.authority(EventAuthority.BOTH)
			.build();

	private StrutBuiltinEvents() {}

	/**
	 * Registers every engine type into {@code registry}
	 */
	public static void registerAll(EventTypeRegistry registry) {
		registry.register(MARKER);
		registry.register(SOUND);
		registry.register(PARTICLE);
		registry.register(LOOP);
		registry.register(END);
	}

	/**
	 * Marker names are lower-cased, so {@code becomeBare} and {@code becomebare} are one event
	 */
	private static String parseMarker(String text) {
		String name = text.strip();

		if (!MARKER_NAME.matcher(name).matches())
			throw new IllegalArgumentException("\"" + name + "\" is not a valid marker name (letters, digits, _ . / - only)");

		return name.toLowerCase(Locale.ROOT);
	}
}
