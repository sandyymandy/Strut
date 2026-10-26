package com.sandymandy.strut.event.type;

import com.sandymandy.strut.StrutConstants;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The text grammar of a clip's <b>custom instruction channel</b>: one typed event per line
 * <pre>
 * mymod:inflate 2.5           a namespaced type id, then its payload text
 * mymod:fade_to_black          a type with no payload
 * swap_end                  a bare word: shorthand for strut:marker swap_end
 * [server] mymod:strike        modifiers in square brackets, before the type
 * [both, reverse] swap      several modifiers, separated by commas or spaces
 * </pre>
 * One keyframe may carry several lines, and they fire in
 * line order at that keyframe's time. This class only <i>splits</i> - turning a line into a typed
 * payload needs the registry, so that is {@link EventTypeRegistry#resolve}
 *
 * <h2>Modifiers</h2>
 *
 * <p>An optional bracketed list at the very start of a line overrides how <i>that line</i> is delivered:
 * <ul>
 * <li>{@code server}, {@code client} or {@code both} - where it runs, instead of its type's default.
 *     At most one</li>
 * <li>{@code reverse} - fire it when playback crosses it backwards too, instead of skipping it</li>
 * </ul>
 * They are text, on the line, rather than a field on the keyframe, so an author sets them in the
 * instruction box Blockbench already gives them and two lines of one keyframe can differ. Only a bracket
 * at the very start of a line is a modifier list: a payload may contain brackets freely
 *
 * <p>A bare word takes no payload, and is lower-cased: a consumer mod already compares every marker
 * name case-insensitively, so normalising here removes the class of bug where {@code becomeBare} and
 * {@code becomebare} are different events
 */
public final class EventLine {
	private static final Pattern BARE_MARKER = Pattern.compile("[A-Za-z0-9_./-]+");
	private static final Pattern MODIFIER_SEPARATOR = Pattern.compile("[,\\s]+");

	private EventLine() {}

	/**
	 * One line, split. Exactly one of {@link #type} and {@link #error} is non-null
	 *
	 * @param type The event type it names ({@code strut:marker} for a bare word)
	 * @param payload The text after the type id, stripped. For a bare word, the lower-cased name
	 * @param error Why the line is not valid, worded for an author
	 * @param authority The line's authority override, or null to use the type's default
	 * @param reverse Whether the line asked to fire in reverse
	 */
	public record Line(@Nullable Identifier type, String payload, @Nullable String error, @Nullable EventAuthority authority,
					   boolean reverse) {
		static Line ok(Identifier type, String payload, @Nullable EventAuthority authority, boolean reverse) {
			return new Line(type, payload, null, authority, reverse);
		}

		static Line failed(String message) {
			return new Line(null, "", message, null, false);
		}
	}

	/**
	 * Splits a whole instruction keyframe's text into its lines, skipping blank ones
	 */
	public static List<Line> parse(String text) {
		List<Line> lines = new ArrayList<>();

		for (String raw : text.split("\\R")) {
			String line = raw.strip();

			if (line.isEmpty())
				continue;

			lines.add(parseLine(line));
		}

		return lines;
	}

	private static Line parseLine(String line) {
		EventAuthority authority = null;
		boolean reverse = false;

		if (line.startsWith("[")) {
			int close = line.indexOf(']');

			if (close < 0)
				return Line.failed("\"" + line + "\": the modifier list is missing its closing ]");

			String list = line.substring(1, close).strip();

			if (list.isEmpty())
				return Line.failed("\"" + line + "\": an empty modifier list - name one of server, client, both, reverse, or remove the brackets");

			for (String modifier : MODIFIER_SEPARATOR.split(list)) {
				switch (modifier.toLowerCase(Locale.ROOT)) {
					case "reverse" -> reverse = true;
					case "server", "client", "both" -> {
						EventAuthority named = EventAuthority.valueOf(modifier.toUpperCase(Locale.ROOT));

						if (authority != null && authority != named)
							return Line.failed("\"" + line + "\": conflicting modifiers " + authority.name().toLowerCase(Locale.ROOT)
									+ " and " + modifier.toLowerCase(Locale.ROOT) + " - a line runs in one place");

						authority = named;
					}
					default -> {
						return Line.failed("\"" + line + "\": unknown modifier \"" + modifier + "\" - the modifiers are server, client, both and reverse");
					}
				}
			}

			line = line.substring(close + 1).strip();

			if (line.isEmpty())
				return Line.failed("modifiers with nothing after them - name an event type or a marker");
		}

		return parseBody(line, authority, reverse);
	}

	private static Line parseBody(String line, @Nullable EventAuthority authority, boolean reverse) {
		int space = indexOfWhitespace(line);
		String token = space < 0 ? line : line.substring(0, space);
		String rest = space < 0 ? "" : line.substring(space).strip();

		if (token.indexOf(':') >= 0) {
			Identifier id = Identifier.tryParse(token);

			return id == null ? Line.failed("\"" + token + "\" is not a valid event type id") : Line.ok(id, rest, authority, reverse);
		}

		if (!rest.isEmpty())
			return Line.failed("\"" + line + "\": a bare marker name takes no payload - "
					+ "name a type as namespace:id to give an event one");

		if (!BARE_MARKER.matcher(token).matches())
			return Line.failed("\"" + token + "\" is not a valid marker name (letters, digits, _ . / - only)");

		return Line.ok(Identifier.of(StrutConstants.MODID, "marker"), token.toLowerCase(Locale.ROOT), authority, reverse);
	}

	private static int indexOfWhitespace(String line) {
		for (int i = 0; i < line.length(); i++) {
			if (Character.isWhitespace(line.charAt(i)))
				return i;
		}

		return -1;
	}
}
