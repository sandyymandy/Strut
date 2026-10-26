package com.sandymandy.strut.event.builtin;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.regex.Pattern;
import com.sandymandy.strut.event.binding.EventBindings;

/**
 * The payload of {@code strut:sound}: <b>either</b> a sound to play directly, <b>or</b> a binding key that
 * an entity's event bindings turn into sounds
 * <p>
 * Text form: {@code target [volume [pitch [locator]]]}, whitespace separated. The target decides which:
 * <ul>
 * <li>{@code mymod:entity.thing.ambient} - has a namespace, so it is a sound id, played as it is</li>
 * <li>{@code anal_startMSG1} - a bare word, so it is a <b>binding key</b>, lower-cased, looked up in the
 *     entity's binding sets (see {@code EventBindings}). Which sound it becomes depends on who is
 *     playing the clip - one shared animation, a different voice per character</li>
 * </ul>
 * A sound id must therefore carry its namespace in a clip. That costs nothing (nobody writes
 * {@code minecraft:} sounds by hand as bare words) and is what lets a bare word mean a key instead
 * of being silently read as a {@code minecraft:} sound.
 *
 * @param sound The sound to play, or null if this names a binding key. Resolved to a {@code SoundEvent}
 *              when handled, not here, so a payload naming a sound the receiver lacks decodes fine
 * @param key The binding key, lower-case, or null if this names a sound
 * @param volume 0 or more; multiplies whatever the binding plays
 * @param pitch More than 0; multiplies whatever the binding plays
 * @param locator A bone to play the sound from, or empty for the entity itself
 */
public record SoundEventPayload(@Nullable Identifier sound, @Nullable String key, float volume, float pitch, String locator) {
	private static final Pattern BINDING_KEY = Pattern.compile("[A-Za-z0-9_./-]+");

	public SoundEventPayload {
		if ((sound == null) == (key == null))
			throw new IllegalArgumentException("a sound payload names exactly one of a sound id and a binding key");
	}

	public static SoundEventPayload ofSound(Identifier sound, float volume, float pitch, String locator) {
		return new SoundEventPayload(sound, null, volume, pitch, locator);
	}

	public static SoundEventPayload ofKey(String key, float volume, float pitch, String locator) {
		return new SoundEventPayload(null, key.toLowerCase(Locale.ROOT), volume, pitch, locator);
	}

	/**
	 * Whether this names a binding key rather than a sound
	 */
	public boolean isBindingKey() {
		return this.key != null;
	}

	public static final PacketCodec<PacketByteBuf, SoundEventPayload> CODEC = PacketCodec.of(
			(payload, buf) -> {
				buf.writeBoolean(payload.key != null);

				if (payload.key != null) {
					buf.writeString(payload.key);
				}
				else {
					buf.writeIdentifier(payload.sound);
				}

				buf.writeFloat(payload.volume);
				buf.writeFloat(payload.pitch);
				buf.writeString(payload.locator);
			},
			buf -> {
				boolean isKey = buf.readBoolean();
				String key = isKey ? buf.readString() : null;
				Identifier sound = isKey ? null : buf.readIdentifier();

				return new SoundEventPayload(sound, key, buf.readFloat(), buf.readFloat(), buf.readString());
			});

	public static SoundEventPayload parse(String text) {
		String[] fields = text.strip().split("\\s+");

		if (fields.length == 0 || fields[0].isEmpty())
			throw new IllegalArgumentException("needs a sound id or a binding key");

		if (fields.length > 4)
			throw new IllegalArgumentException("takes at most 4 fields (target [volume [pitch [locator]]]), got " + fields.length);

		float volume = fields.length > 1 ? number(fields[1], "volume") : 1f;
		float pitch = fields.length > 2 ? number(fields[2], "pitch") : 1f;

		if (volume < 0)
			throw new IllegalArgumentException("volume must be 0 or more, got " + volume);

		if (pitch <= 0)
			throw new IllegalArgumentException("pitch must be more than 0, got " + pitch);

		String locator = fields.length > 3 ? fields[3] : "";
		String target = fields[0];

		if (target.indexOf(':') >= 0) {
			Identifier sound = Identifier.tryParse(target);

			if (sound == null)
				throw new IllegalArgumentException("\"" + target + "\" is not a valid sound id");

			return ofSound(sound, volume, pitch, locator);
		}

		if (!BINDING_KEY.matcher(target).matches())
			throw new IllegalArgumentException("\"" + target + "\" is not a valid binding key (letters, digits, _ . / - only) "
					+ "- a sound id needs a namespace, like mymod:my.sound");

		return ofKey(target, volume, pitch, locator);
	}

	private static float number(String text, String what) {
		try {
			float value = Float.parseFloat(text);

			if (!Float.isFinite(value))
				throw new IllegalArgumentException(what + " must be a finite number, got \"" + text + "\"");

			return value;
		}
		catch (NumberFormatException ex) {
			throw new IllegalArgumentException(what + " must be a number, got \"" + text + "\"");
		}
	}
}
