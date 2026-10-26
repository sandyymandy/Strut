package com.sandymandy.strut.assetsync.client;

import com.sandymandy.strut.assetsync.protocol.PngGuard;
import com.sandymandy.strut.assetsync.client.SyncedAssetIndex;
import net.minecraft.SharedConstants;
import net.minecraft.resource.AbstractFileResourcePack;
import net.minecraft.resource.InputSupplier;
import net.minecraft.resource.ResourcePack;
import net.minecraft.resource.ResourcePackInfo;
import net.minecraft.resource.ResourceType;
import net.minecraft.resource.metadata.ResourceMetadataReader;
import net.minecraft.util.Identifier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A resource pack that exists only as a view over {@link SyncedAssetIndex}: no folder, no zip, nothing copied
 * <p>
 * The game asks it for {@code assets/<namespace>/<path>} and it answers with the verified cache file that has that
 * content, so sounds, {@code sounds.json}, lang, textures and Strut's own models and animations all load through the
 * game's ordinary loaders with no special cases. A server-chosen path is only ever a key into the index, never a path on disk
 * <p>
 * It serves client resources only. Every PNG is header-checked by {@link PngGuard} first, so a tiny file cannot decode to
 * an enormous image
 */
public final class SyncedAssetPack implements ResourcePack {
	private static final Logger LOGGER = LogManager.getLogger("Strut");

	private final SyncedAssetIndex index;
	private final ResourcePackInfo info;
	private final long maxTexturePixels;
	private final Set<String> warned = ConcurrentHashMap.newKeySet();

	public SyncedAssetPack(SyncedAssetIndex index, ResourcePackInfo info, long maxTexturePixels) {
		this.index = index;
		this.info = info;
		this.maxTexturePixels = maxTexturePixels;
	}

	@Nullable
	@Override
	public InputSupplier<InputStream> openRoot(String... segments) {
		if (segments.length == 1 && segments[0].equals(PACK_METADATA_NAME))
			return () -> new ByteArrayInputStream(metadataJson().getBytes(StandardCharsets.UTF_8));

		return null;
	}

	@Nullable
	@Override
	public InputSupplier<InputStream> open(ResourceType type, Identifier id) {
		if (type != ResourceType.CLIENT_RESOURCES)
			return null;

		return supplierFor(id.getNamespace(), id.getPath());
	}

	@Override
	public void findResources(ResourceType type, String namespace, String prefix, ResultConsumer consumer) {
		if (type != ResourceType.CLIENT_RESOURCES)
			return;

		for (String path : this.index.list(namespace, prefix)) {
			InputSupplier<InputStream> supplier = supplierFor(namespace, path);

			if (supplier != null)
				consumer.accept(Identifier.of(namespace, path), supplier);
		}
	}

	@Nullable
	private InputSupplier<InputStream> supplierFor(String namespace, String path) {
		Path file = this.index.find(namespace, path);

		if (file == null)
			return null;

		if (path.endsWith(".png")) {
			String problem = PngGuard.problem(file, this.maxTexturePixels);

			if (problem != null) {
				if (this.warned.add(namespace + ":" + path))
					LOGGER.warn("[Strut asset sync] refusing synced texture {}:{}: {}", namespace, path, problem);

				return null;
			}
		}

		return InputSupplier.create(file);
	}

	@Override
	public Set<String> getNamespaces(ResourceType type) {
		return type == ResourceType.CLIENT_RESOURCES ? this.index.namespaces() : Set.of();
	}

	@Nullable
	@Override
	public <T> T parseMetadata(ResourceMetadataReader<T> reader) throws IOException {
		return AbstractFileResourcePack.parseMetadata(reader, new ByteArrayInputStream(metadataJson().getBytes(StandardCharsets.UTF_8)));
	}

	private static String metadataJson() {
		int format = SharedConstants.getGameVersion().getResourceVersion(ResourceType.CLIENT_RESOURCES);

		return "{\"pack\":{\"pack_format\":" + format + ",\"description\":\"Server assets synced by Strut\"}}";
	}

	@Override
	public ResourcePackInfo getInfo() {
		return this.info;
	}

	@Override
	public void close() {
		// Nothing is held open: every read opens the cache file afresh
	}
}
