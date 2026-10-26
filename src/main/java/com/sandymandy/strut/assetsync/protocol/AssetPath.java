package com.sandymandy.strut.assetsync.protocol;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The rules for a synced asset's path - the one place both sides decide whether a path is acceptable
 * <p>
 * A path is written {@code <namespace>/<path>} and means {@code assets/<namespace>/<path>} inside the synced
 * pack. The server builds it from {@code data/<namespace>/asset_sync/<path>}. It is never used as a filesystem
 * path (cache files are named by hash), but it is still validated strictly: a client checks every path the server
 * lists, whatever the server claims
 */
public final class AssetPath {
	/** The directory inside a data pack namespace that holds server-owned assets */
	public static final String DATA_FOLDER = "asset_sync";

	/** Longest path accepted, in characters */
	public static final int MAX_LENGTH = 256;

	/** File endings a server may send. No code, no shaders */
	public static final List<String> ALLOWED_EXTENSIONS = List.of(".png", ".ogg", ".json", ".mcmeta", ".strutmodel", ".strutanim", ".lang", ".txt");

	/**
	 * Namespaces a server may never write to: overriding vanilla or loader assets is not what synced content is for, and it
	 * is the easiest way to abuse the feature (fake UI, misleading text). Everything else is fair game
	 */
	public static final Set<String> RESERVED_NAMESPACES = Set.of("minecraft", "realms", "fabric", "fabricloader", "c");

	private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_.-]+");
	private static final Pattern SEGMENT = Pattern.compile("[a-z0-9_.-]+");

	private AssetPath() {}

	/**
	 * Builds the manifest path for {@code data/<namespace>/asset_sync/<rest>}
	 *
	 * @return The path, or empty if either part is not acceptable
	 */
	public static Optional<String> fromDataPath(String namespace, String rest) {
		String path = namespace + "/" + rest;

		return rejection(path) == null ? Optional.of(path) : Optional.empty();
	}

	/**
	 * Why {@code path} is not an acceptable synced asset path, or null if it is
	 */
	@Nullable
	public static String rejection(String path) {
		if (path.isEmpty())
			return "empty path";

		if (path.length() > MAX_LENGTH)
			return "path longer than " + MAX_LENGTH + " characters";

		String[] segments = path.split("/", -1);

		if (segments.length < 2)
			return "no namespace";

		if (!NAMESPACE.matcher(segments[0]).matches() || segments[0].equals(".") || segments[0].equals(".."))
			return "invalid namespace '" + describe(segments[0]) + "'";

		if (RESERVED_NAMESPACES.contains(segments[0]))
			return "namespace '" + segments[0] + "' is reserved";

		for (int i = 1; i < segments.length; i++) {
			String segment = segments[i];

			if (segment.isEmpty())
				return "empty path segment";

			if (segment.equals(".") || segment.equals(".."))
				return "path climbs out of its folder";

			if (!SEGMENT.matcher(segment).matches())
				return "invalid path segment '" + describe(segment) + "'";
		}

		for (String extension : ALLOWED_EXTENSIONS) {
			if (path.endsWith(extension))
				return null;
		}

		return "file type not allowed";
	}

	/**
	 * Text that came from the network, made safe to put in a log line or an error message: cut short, and anything
	 * outside the characters a valid path can hold replaced. Server-supplied strings are never logged raw
	 */
	public static String describe(String untrusted) {
		int length = Math.min(untrusted.length(), 64);
		StringBuilder safe = new StringBuilder(length + 3);

		for (int i = 0; i < length; i++) {
			char c = untrusted.charAt(i);

			safe.append((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '.' || c == '-' || c == '/' ? c : '?');
		}

		if (untrusted.length() > length)
			safe.append("...");

		return safe.toString();
	}

	public static boolean isValid(String path) {
		return rejection(path) == null;
	}

	/**
	 * The path of the file inside the synced pack
	 */
	public static String inPack(String path) {
		return "assets/" + path;
	}
}
