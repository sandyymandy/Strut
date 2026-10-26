package com.sandymandy.strut.assetsync.protocol;

import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A header check on synced PNGs, before the game's decoder sees them
 * <p>
 * A PNG's size is in its first 24 bytes, but decoding allocates width x height x 4 bytes. A small file can therefore ask
 * for gigabytes (a decompression bomb). Reading the two numbers first is nearly free and bounds what a server can make the
 * client allocate. A file that does not even start with the PNG signature is refused too: nothing legitimate is misnamed
 */
public final class PngGuard {
	private static final byte[] SIGNATURE = {(byte)0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

	private PngGuard() {}

	/**
	 * @return Why this file must not be decoded, or null if it may be
	 */
	@Nullable
	public static String problem(Path file, long maxPixels) {
		byte[] header = new byte[24];

		try (InputStream in = Files.newInputStream(file)) {
			if (in.readNBytes(header, 0, 24) < 24)
				return "truncated PNG header";
		}
		catch (IOException e) {
			return "unreadable";
		}

		return problem(header, maxPixels);
	}

	/**
	 * The same check on the first 24 bytes already in hand
	 */
	@Nullable
	public static String problem(byte[] header, long maxPixels) {
		if (header.length < 24)
			return "truncated PNG header";

		for (int i = 0; i < SIGNATURE.length; i++) {
			if (header[i] != SIGNATURE[i])
				return "not a PNG";
		}

		// Then the IHDR chunk: 4 bytes of length, "IHDR", then width and height as big-endian unsigned ints
		if (header[12] != 'I' || header[13] != 'H' || header[14] != 'D' || header[15] != 'R')
			return "no IHDR chunk";

		long width = ((long)(header[16] & 0xFF) << 24) | ((header[17] & 0xFF) << 16) | ((header[18] & 0xFF) << 8) | (header[19] & 0xFF);
		long height = ((long)(header[20] & 0xFF) << 24) | ((header[21] & 0xFF) << 16) | ((header[22] & 0xFF) << 8) | (header[23] & 0xFF);

		if (width == 0 || height == 0)
			return "zero-sized PNG";

		// Compared by division: two 32-bit sides multiplied can overflow a long and slip under the limit
		if (height > maxPixels / width)
			return "PNG of " + width + "x" + height + " is over the " + maxPixels + "-pixel limit";

		return null;
	}
}
