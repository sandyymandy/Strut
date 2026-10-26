package com.sandymandy.strut.cache.texture;

import com.mojang.blaze3d.systems.RenderCall;
import net.minecraft.client.texture.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.client.texture.MissingSprite;
import net.minecraft.client.texture.TextureManager;
import net.minecraft.util.Identifier;
import net.minecraft.resource.ResourceManager;
import org.jetbrains.annotations.Nullable;
import com.sandymandy.strut.StrutServices;

import java.io.File;
import java.io.IOException;
import java.util.function.Consumer;

/**
 * Abstract texture wrapper for Strut textures
 * <p>
 * Mostly just handles boilerplate
 */
public abstract class StrutAbstractTexture extends AbstractTexture {
	/**
	 * Generates the texture instance for the given path with the given appendix if it hasn't already been generated
	 */
	protected static void generateTexture(Identifier texturePath, Consumer<TextureManager> textureManagerConsumer) {
		if (!RenderSystem.isOnRenderThreadOrInit())
			throw new IllegalThreadStateException("Texture loading called outside of the render thread! This should DEFINITELY not be happening.");

		TextureManager textureManager = MinecraftClient.getInstance().getTextureManager();

		if (!(textureManager.getTexture(texturePath) instanceof StrutAbstractTexture))
			textureManagerConsumer.accept(textureManager);
	}

	@Override
	public final void load(ResourceManager resourceManager) throws IOException {
		RenderCall renderCall = loadTexture(resourceManager, MinecraftClient.getInstance());

		if (renderCall == null)
			return;

		if (!RenderSystem.isOnRenderThreadOrInit()) {
			RenderSystem.recordRenderCall(renderCall);
		}
		else {
			renderCall.execute();
		}
	}

	/**
	 * Debugging function to write out the generated glowmap image to disk
	 */
	protected void printDebugImageToDisk(Identifier id, NativeImage newImage) {
		try {
			File file = new File(StrutServices.PLATFORM.getGameDir().toFile(), "StrutTexture Debug Printouts");

			if (!file.exists()) {
				file.mkdirs();
			}
			else if (!file.isDirectory()) {
				file.delete();
				file.mkdirs();
			}

			file = new File(file, id.getPath().replace('/', '.'));

			if (!file.exists())
				file.createNewFile();

			newImage.writeTo(file);
		}
		catch (IOException ex) {
			ex.printStackTrace();
		}
	}

	/**
	 * Called at {@link AbstractTexture#load} time to load this texture for the first time into the render cache
	 * <p>
	 * Generate and apply the necessary functions here, then return the RenderCall to submit to the render pipeline
	 *
	 * @return The RenderCall to submit to the render pipeline, or null if no further action required
	 */
	@Nullable
	protected abstract RenderCall loadTexture(ResourceManager resourceManager, MinecraftClient mc) throws IOException;

	/**
	 * No-frills helper method for uploading {@link NativeImage images} into memory for use
	 */
	public static void uploadSimple(int texture, NativeImage image, boolean blur, boolean clamp) {
		TextureUtil.prepareImage(texture, 0, image.getWidth(), image.getHeight());
		image.upload(0, 0, 0, 0, 0, image.getWidth(), image.getHeight(), blur, clamp, false, true);
	}

	/**
	 * Whether the path's last segment has a file extension. A texture that was registered at runtime and has no file behind it (a player skin,
	 * {@code minecraft:skins/<hash>}) has none, and can have no companion file such as a glowmask
	 */
	public static boolean hasFileExtension(Identifier location) {
		String path = location.getPath();

		return path.lastIndexOf('.') > path.lastIndexOf('/') + 1;
	}

	/**
	 * Inserts {@code suffix} before the file extension, or appends it when there is none, so an extensionless id never throws
	 */
	public static Identifier appendToPath(Identifier location, String suffix) {
		String path = location.getPath();

		if (!hasFileExtension(location))
			return Identifier.of(location.getNamespace(), path + suffix);

		int i = path.lastIndexOf('.');

		return Identifier.of(location.getNamespace(), path.substring(0, i) + suffix + path.substring(i));
	}
}
