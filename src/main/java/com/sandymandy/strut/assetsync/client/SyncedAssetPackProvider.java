package com.sandymandy.strut.assetsync.client;

import com.sandymandy.strut.assetsync.client.SyncedAssetIndex;
import net.minecraft.resource.ResourcePack;
import net.minecraft.resource.ResourcePackInfo;
import net.minecraft.resource.ResourcePackPosition;
import net.minecraft.resource.ResourcePackProfile;
import net.minecraft.resource.ResourcePackProvider;
import net.minecraft.resource.ResourcePackSource;
import net.minecraft.resource.ResourceType;
import net.minecraft.text.Text;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * Offers the current server's synced assets to the client's pack list, or nothing when there are none
 * <p>
 * Added to the game's {@code ResourcePackManager} by a mixin. The profile is <i>required, on top, fixed</i>: the same
 * flags vanilla gives a server resource pack, which makes the manager enable it automatically and keeps it out of the
 * player's reach in the pack screen. The resource reload after {@link #setActive} is what makes it take effect
 */
public final class SyncedAssetPackProvider implements ResourcePackProvider {
	public static final SyncedAssetPackProvider INSTANCE = new SyncedAssetPackProvider();

	public static final String PACK_ID = "strut_synced_assets";

	private static final ResourcePackPosition POSITION = new ResourcePackPosition(true, ResourcePackProfile.InsertionPosition.TOP, true);

	@Nullable
	private volatile SyncedAssetIndex active;
	private volatile long maxTexturePixels = 8192L * 8192L;

	private SyncedAssetPackProvider() {}

	/**
	 * Makes {@code index} the assets on offer. The caller reloads resources afterwards
	 */
	public void setActive(@Nullable SyncedAssetIndex index, long maxTexturePixels) {
		this.maxTexturePixels = maxTexturePixels;
		this.active = index;
	}

	@Nullable
	public SyncedAssetIndex active() {
		return this.active;
	}

	@Override
	public void register(Consumer<ResourcePackProfile> consumer) {
		SyncedAssetIndex index = this.active;

		if (index == null)
			return;

		ResourcePackInfo info = new ResourcePackInfo(PACK_ID, Text.literal("Server assets (Strut)"), ResourcePackSource.SERVER, Optional.empty());
		long pixels = this.maxTexturePixels;
		ResourcePackProfile profile = ResourcePackProfile.create(info, new ResourcePackProfile.PackFactory() {
			@Override
			public ResourcePack open(ResourcePackInfo packInfo) {
				return new SyncedAssetPack(index, packInfo, pixels);
			}

			@Override
			public ResourcePack openWithOverlays(ResourcePackInfo packInfo, ResourcePackProfile.Metadata metadata) {
				return new SyncedAssetPack(index, packInfo, pixels);
			}
		}, ResourceType.CLIENT_RESOURCES, POSITION);

		if (profile != null)
			consumer.accept(profile);
	}
}
