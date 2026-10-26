package com.sandymandy.strut.assetsync;

import com.sandymandy.strut.StrutConstants;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * A tiny built-in set of synced assets, for trying the feature without writing a data pack
 * <p>
 * <b>Off unless asked for</b>: start the game or server with {@code -Dstrut.assetSyncDemo=true}. It then offers the
 * namespace {@code strut_demo} containing a lang file and a generated PNG, so a live test can watch a client go from
 * not having them to having them, using nothing but Strut. On the client the F9 overlay shows whether the game's own lang
 * loader and resource manager can see them, which proves they arrived through the whole path and were not just cached
 * <p>
 * It is also the smallest example of {@link StrutAssetSync#addSource}
 */
public final class AssetSyncDemo {
	public static final String PROPERTY = "strut.assetSyncDemo";
	public static final String NAMESPACE = "strut_demo";
	public static final String LANG_KEY = "strut_demo.hello";
	public static final String LANG_VALUE = "Hello from the server's synced assets";
	public static final String TEXTURE = "textures/demo.png";

	private AssetSyncDemo() {}

	public static boolean isEnabled() {
		return Boolean.getBoolean(PROPERTY);
	}

	/**
	 * Called once from {@link com.sandymandy.strut.Strut#onInitialize()}
	 */
	public static void init() {
		if (!isEnabled())
			return;

		StrutAssetSync.addSource(AssetSyncDemo::contribute);
		StrutConstants.LOGGER.info("[Strut asset sync] demo assets enabled ({}): offering the '{}' namespace", PROPERTY, NAMESPACE);
	}

	static void contribute(StrutAssetSync.Sink sink) {
		sink.add(NAMESPACE, "lang/en_us.json", ("{\"" + LANG_KEY + "\": \"" + LANG_VALUE + "\"}").getBytes(StandardCharsets.UTF_8));
		sink.add(NAMESPACE, TEXTURE, texture());
	}

	/**
	 * A 16x16 checkerboard PNG
	 */
	static byte[] texture() {
		BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);

		for (int y = 0; y < 16; y++) {
			for (int x = 0; x < 16; x++)
				image.setRGB(x, y, ((x / 4 + y / 4) % 2 == 0) ? 0xFFE8A0FF : 0xFF6A2C9F);
		}

		ByteArrayOutputStream out = new ByteArrayOutputStream();

		try {
			ImageIO.write(image, "png", out);
		}
		catch (IOException e) {
			throw new IllegalStateException("Cannot encode the demo texture", e);
		}

		return out.toByteArray();
	}
}
