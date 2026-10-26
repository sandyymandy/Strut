package com.sandymandy.strut.cache.texture;

import com.mojang.blaze3d.systems.RenderCall;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.resource.metadata.TextureResourceMetadata;
import net.minecraft.util.Identifier;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceManager;
import org.jetbrains.annotations.Nullable;
import com.sandymandy.strut.StrutConstants;
import com.sandymandy.strut.StrutServices;
import com.sandymandy.strut.resource.StrutGlowingTextureMeta;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

/**
 * Texture object type responsible for Strut's emissive render textures
 *
 * @see "Strut documentation: doc/rendering.html"
 */
public class AutoGlowingTexture extends StrutAbstractTexture {
	private static final String APPENDIX = "_glowmask";
	private static final GlowMaskLookup GLOW_MASKS = new GlowMaskLookup();
	/**
	 * Set to true <u><b>IN DEV</b></u> to have Strut print out the base texture and generated glowlayer textures to the base game directory (./run)
	 */
	public static boolean PRINT_DEBUG_IMAGES = false;

	protected final Identifier textureBase;
	protected final Identifier glowLayer;

	/**
	 * Whether {@link #loadTexture} actually found a real glowmask for this texture (an explicit
	 * {@code _glowmask} resource, or {@link StrutGlowingTextureMeta} on the base texture) - {@code false}
	 * for the vast majority of textures, which have neither. {@link #getEmissiveResource} always
	 * registers and returns a valid {@link Identifier} regardless (a modder-facing convenience, so
	 * a caller never has to null-check it), but the underlying GL texture behind that identifier is
	 * never actually given pixel data when this stays {@code false} - {@link #hasEmissiveLayer}
	 * exists precisely so a caller can tell the difference before sampling it, since sampling an
	 * unloaded texture is undefined, not simply transparent
	 */
	private volatile boolean hasGlowLayer;

	public AutoGlowingTexture(Identifier originalLocation, Identifier location) {
		this.textureBase = originalLocation;
		this.glowLayer = location;
	}

	/**
	 * Get the emissive resource equivalent of the input resource path
	 * <p>
	 * Additionally, prepares the texture manager for the missing texture if the resource is not present
	 *
	 * @return The glowlayer resourcepath for the provided input path
	 */
	public static Identifier getEmissiveResource(Identifier baseResource) {
		Identifier path = appendToPath(baseResource, APPENDIX);

		generateTexture(path, textureManager -> textureManager.registerTexture(path, new AutoGlowingTexture(baseResource, path)));

		return path;
	}

	/**
	 * Whether {@code baseResource} genuinely has a glowmask - registers (and, on first call, loads)
	 * the same emissive texture {@link #getEmissiveResource} does, then reports whether loading it
	 * actually found real glow data rather than nothing at all. The GPU render path
	 * uses this to decide, once per draw group, whether to bind and
	 * sample the emissive texture at all - unlike the old CPU-only {@code AutoGlowingStrutLayer}
	 * (since removed), which a modder had to explicitly opt an entity's renderer
	 * into, the GPU path has no per-renderer configuration point at all any more, so this is what
	 * makes glow automatic again: drop a {@code _glowmask} file next to the base texture and it just works,
	 * no Java code required
	 */
	public static boolean hasEmissiveLayer(Identifier baseResource) {
		// Ask the resource manager first, once per texture per reload: handing a missing file to the texture manager logged a failed load with a
		// stack trace on every draw and replaced the texture with its missing-texture stand-in each time
		ResourceManager resources = MinecraftClient.getInstance().getResourceManager();

		if (!GLOW_MASKS.hasGlowMask(resources, baseResource, base -> resources.getResource(appendToPath(base, APPENDIX)).isPresent()))
			return false;

		Identifier emissiveId = getEmissiveResource(baseResource);
		AbstractTexture texture = MinecraftClient.getInstance().getTextureManager().getTexture(emissiveId);

		return texture instanceof AutoGlowingTexture glowing && glowing.hasGlowLayer;
	}

	/**
	 * Generates the glow layer {@link NativeImage} and appropriately modifies the base texture for use in glow render layers
	 */
	@Nullable
	@Override
	protected RenderCall loadTexture(ResourceManager resourceManager, MinecraftClient mc) throws IOException {
		AbstractTexture originalTexture;

		try {
			originalTexture = mc.submit(() -> mc.getTextureManager().getTexture(this.textureBase)).get();
		}
		catch (InterruptedException | ExecutionException e) {
			throw new IOException("Failed to load original texture: " + this.textureBase, e);
		}

		Resource textureBaseResource = resourceManager.getResource(this.textureBase).get();
		NativeImage baseImage = originalTexture instanceof NativeImageBackedTexture dynamicTexture ?
								dynamicTexture.getImage() : NativeImage.read(textureBaseResource.getInputStream());
		NativeImage glowImage = null;
		Optional<TextureResourceMetadata> textureBaseMeta = textureBaseResource.getMetadata().decode(TextureResourceMetadata.READER);
		boolean blur = textureBaseMeta.isPresent() && textureBaseMeta.get().shouldBlur();
		boolean clamp = textureBaseMeta.isPresent() && textureBaseMeta.get().shouldClamp();

		try {
			Optional<Resource> glowLayerResource = resourceManager.getResource(this.glowLayer);
			StrutGlowingTextureMeta glowLayerMeta = null;

			if (glowLayerResource.isPresent()) {
				glowImage = NativeImage.read(glowLayerResource.get().getInputStream());
				glowLayerMeta = StrutGlowingTextureMeta.fromExistingImage(glowImage);

				if (baseImage != null && (glowImage.getWidth() != baseImage.getWidth() || glowImage.getHeight() != baseImage.getHeight()))
					throw new IllegalStateException(String.format("Glowmask texture dimensions do not match base texture dimensions! Mask: %s, Base: %s", this.glowLayer, this.textureBase));
			}
			else {
				Optional<StrutGlowingTextureMeta> meta = textureBaseResource.getMetadata().decode(StrutGlowingTextureMeta.DESERIALIZER);

				if (meta.isPresent()) {
					glowLayerMeta = meta.get();
					glowImage = new NativeImage(baseImage.getWidth(), baseImage.getHeight(), true);
				}
			}

			if (glowLayerMeta != null) {
				glowLayerMeta.createImageMask(baseImage, glowImage);

				if (PRINT_DEBUG_IMAGES && StrutServices.PLATFORM.isDevelopmentEnvironment()) {
					printDebugImageToDisk(this.textureBase, baseImage);
					printDebugImageToDisk(this.glowLayer, glowImage);
				}
			}
		}
		catch (IOException e) {
			StrutConstants.LOGGER.warn("Resource failed to open for glowlayer meta: {}", this.glowLayer, e);
		}

		NativeImage mask = glowImage;

		this.hasGlowLayer = mask != null;

		if (mask == null)
			return null;

		boolean animated = originalTexture instanceof AnimatableTexture animatableTexture && animatableTexture.isAnimated();

		if (animated)
			((AnimatableTexture)originalTexture).animationContents.animatedTexture.setGlowMaskTexture(this, baseImage, mask);

		return () -> {
			if (!animated)
				uploadSimple(getGlId(), mask, blur, clamp);

			if (originalTexture instanceof NativeImageBackedTexture dynamicTexture) {
				dynamicTexture.upload();
			}
			else {
				uploadSimple(originalTexture.getGlId(), baseImage, blur, clamp);
			}
		};
	}

}
