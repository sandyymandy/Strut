package com.sandymandy.strut.assetsync.protocol;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import com.sandymandy.strut.assetsync.client.AssetCache;

/**
 * Receives one file as ordered chunks, hashing as it goes, and files it in the cache only if the hash is right
 * <p>
 * Chunks must arrive at exactly the offset the file has reached, and never past its declared size: a server that
 * sends anything else is refused rather than patched around
 */
public final class ChunkAssembler implements AutoCloseable {
	private final AssetCache cache;
	private final ManifestEntry entry;
	private final Path tempFile;
	private final OutputStream out;
	private final MessageDigest digest = AssetManifest.digest();
	private long received;
	private boolean done;

	public ChunkAssembler(AssetCache cache, ManifestEntry entry) {
		this.cache = cache;
		this.entry = entry;

		try {
			this.tempFile = cache.newTempFile();
			this.out = Files.newOutputStream(this.tempFile);
		}
		catch (IOException e) {
			throw new AssetSyncException("Cannot write the asset cache", e);
		}
	}

	public ManifestEntry entry() {
		return this.entry;
	}

	public long received() {
		return this.received;
	}

	/**
	 * @return Whether this chunk completed the file, which is now in the cache
	 * @throws AssetSyncException for an out-of-order or oversize chunk, or a completed file whose hash is wrong. The
	 *                            partial download is discarded
	 */
	public boolean accept(long offset, byte[] data) {
		if (this.done)
			throw new AssetSyncException("Chunk after the file was complete: " + this.entry.path());

		if (offset != this.received)
			throw fail("Chunk at offset " + offset + " but " + this.received + " expected for " + this.entry.path(), null);

		if (this.received + data.length > this.entry.size())
			throw fail("Chunk runs past the declared size of " + this.entry.path(), null);

		try {
			this.out.write(data);
		}
		catch (IOException e) {
			throw fail("Cannot write the asset cache", e);
		}

		this.digest.update(data);
		this.received += data.length;

		if (this.received < this.entry.size())
			return false;

		return finish();
	}

	private boolean finish() {
		try {
			this.out.close();
		}
		catch (IOException e) {
			throw fail("Cannot write the asset cache", e);
		}

		String actual = HexFormat.of().formatHex(this.digest.digest());

		if (!actual.equals(this.entry.sha256()))
			throw fail("Hash mismatch for " + this.entry.path(), null);

		try {
			this.cache.commit(this.tempFile, this.entry.sha256());
		}
		catch (IOException e) {
			throw fail("Cannot write the asset cache", e);
		}

		this.done = true;

		return true;
	}

	private AssetSyncException fail(String message, Throwable cause) {
		close();

		return cause == null ? new AssetSyncException(message) : new AssetSyncException(message, cause);
	}

	/**
	 * Discards an unfinished download. Safe after completion, and safe to call twice
	 */
	@Override
	public void close() {
		try {
			this.out.close();

			if (!this.done)
				Files.deleteIfExists(this.tempFile);
		}
		catch (IOException ignored) {
			// A leftover temp file is swept from the cache's tmp folder later
		}
	}
}
