package com.sandymandy.strut.assetsync.client;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import com.sandymandy.strut.assetsync.protocol.AssetManifest;

/**
 * The client's content-addressed file store: {@code <root>/<first two hex>/<sha256>}
 * <p>
 * Content-addressed means a file is found by what it <i>is</i>, so it is shared across servers, survives restarts and
 * can never be stale. A file is only ever placed here after its hash was checked, and only by moving a finished temp
 * file, so a half-written file is never visible under a hash's name
 */
public final class AssetCache {
	private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");

	private final Path root;
	private final Path temp;

	public AssetCache(Path root) {
		this.root = root;
		this.temp = root.resolve("tmp");
	}

	public static boolean isHash(String candidate) {
		return HASH.matcher(candidate).matches();
	}

	/**
	 * Where a file with this hash lives (whether or not it is there)
	 */
	public Path pathOf(String sha256) {
		if (!isHash(sha256))
			throw new IllegalArgumentException("Not a SHA-256: " + sha256);

		return this.root.resolve(sha256.substring(0, 2)).resolve(sha256);
	}

	public boolean has(String sha256) {
		return Files.isRegularFile(pathOf(sha256));
	}

	/**
	 * Whether the file is cached <i>and</i> is the length the manifest says. A file of the wrong length (a torn write,
	 * disk damage) is deleted so it is fetched again rather than served
	 */
	public boolean hasIntact(String sha256, long size) {
		Path path = pathOf(sha256);

		if (!Files.isRegularFile(path))
			return false;

		if (sizeOf(path) == size)
			return true;

		try {
			Files.deleteIfExists(path);
		}
		catch (IOException ignored) {
			// It is then treated as missing; a fetched copy will not overwrite it, so the next run tries the delete again
		}

		return false;
	}

	/**
	 * The cached file, marking it as used just now so pruning keeps it
	 */
	public Optional<Path> find(String sha256) {
		Path path = pathOf(sha256);

		if (!Files.isRegularFile(path))
			return Optional.empty();

		try {
			Files.setLastModifiedTime(path, FileTime.fromMillis(System.currentTimeMillis()));
		}
		catch (IOException ignored) {
			// A failed touch only makes this file look older to pruning
		}

		return Optional.of(path);
	}

	/**
	 * A fresh empty file to write a download into
	 */
	public Path newTempFile() throws IOException {
		Files.createDirectories(this.temp);

		return Files.createFile(this.temp.resolve(UUID.randomUUID() + ".part"));
	}

	/**
	 * Moves a finished, already-verified temp file into place under its hash
	 */
	public void commit(Path tempFile, String sha256) throws IOException {
		Path target = pathOf(sha256);

		Files.createDirectories(target.getParent());

		if (Files.exists(target)) {
			Files.deleteIfExists(tempFile);

			return;
		}

		Files.move(tempFile, target, StandardCopyOption.ATOMIC_MOVE);
	}

	/**
	 * Stores bytes under their own hash, returning it. For tests and small in-memory assets
	 */
	public String put(byte[] bytes) {
		String sha256 = AssetManifest.sha256Hex(bytes);

		try {
			Path file = newTempFile();

			Files.write(file, bytes);
			commit(file, sha256);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}

		return sha256;
	}

	/**
	 * Total size of every cached file
	 */
	public long totalBytes() {
		return files().stream().mapToLong(AssetCache::sizeOf).sum();
	}

	/**
	 * Deletes least-recently-used files until the cache fits {@code limitBytes}, never touching {@code keep}
	 *
	 * @return How many bytes were freed
	 */
	public long prune(long limitBytes, Set<String> keep) {
		List<Path> files = files();
		long total = files.stream().mapToLong(AssetCache::sizeOf).sum();
		long freed = 0;

		if (total <= limitBytes)
			return 0;

		files.sort(Comparator.comparing(AssetCache::modifiedOf));

		for (Path file : files) {
			if (total - freed <= limitBytes)
				break;

			if (keep.contains(file.getFileName().toString()))
				continue;

			long size = sizeOf(file);

			try {
				Files.deleteIfExists(file);
				freed += size;
			}
			catch (IOException ignored) {
				// Left in place; the next prune tries again
			}
		}

		return freed;
	}

	private List<Path> files() {
		if (!Files.isDirectory(this.root))
			return new ArrayList<>();

		try (Stream<Path> walk = Files.walk(this.root, 2)) {
			return new ArrayList<>(walk.filter(Files::isRegularFile)
					.filter(path -> isHash(path.getFileName().toString()))
					.toList());
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static long sizeOf(Path file) {
		try {
			return Files.size(file);
		}
		catch (IOException e) {
			return 0;
		}
	}

	private static FileTime modifiedOf(Path file) {
		try {
			return Files.getLastModifiedTime(file);
		}
		catch (IOException e) {
			return FileTime.fromMillis(0);
		}
	}
}
