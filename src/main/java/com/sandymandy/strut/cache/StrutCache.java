package com.sandymandy.strut.cache;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Identifier;
import net.minecraft.resource.ResourceReloader.Synchronizer;
import net.minecraft.resource.ReloadableResourceManagerImpl;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.profiler.Profiler;
import com.sandymandy.strut.StrutConstants;
import com.sandymandy.strut.animation.Animation;
import com.sandymandy.strut.cache.object.BakedStrutModel;
import com.sandymandy.strut.event.type.StrutEventTypes;
import com.sandymandy.strut.event.timeline.ClipTimeline;
import com.sandymandy.strut.event.timeline.ClipTimelineFactory;
import com.sandymandy.strut.event.timeline.ClipTimelines;
import com.sandymandy.strut.loading.FileLoader;
import com.sandymandy.strut.loading.binary.BinaryAnimationBaker;
import com.sandymandy.strut.loading.binary.BinaryAnimationReader;
import com.sandymandy.strut.loading.binary.BinaryModelBaker;
import com.sandymandy.strut.loading.binary.BinaryModelReader;
import com.sandymandy.strut.loading.binary.StrutBinaryFormats;
import com.sandymandy.strut.loading.binary.raw.RawBinaryAnimation;
import com.sandymandy.strut.loading.json.raw.Model;
import com.sandymandy.strut.loading.json.typeadapter.BakedAnimationsAdapter;
import com.sandymandy.strut.loading.object.BakedAnimations;
import com.sandymandy.strut.loading.object.BakedModelFactory;
import com.sandymandy.strut.loading.object.GeometryTree;
import com.sandymandy.strut.model.StrutModel;
import com.sandymandy.strut.util.CompoundException;

import java.io.InputStream;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Cache class for holding loaded {@link Animation Animations}
 * and {@link StrutModel Models}
 */
public final class StrutCache {
	private static final Set<String> EXCLUDED_NAMESPACES = ObjectOpenHashSet.of("moreplayermodels", "customnpcs", "gunsrpg");

	private static Map<Identifier, BakedAnimations> ANIMATIONS = Collections.emptyMap();
	private static Map<Identifier, BakedStrutModel> MODELS = Collections.emptyMap();

	public static Map<Identifier, BakedAnimations> getBakedAnimations() {
		return ANIMATIONS;
	}

	public static Map<Identifier, BakedStrutModel> getBakedModels() {
		return MODELS;
	}

	public static void registerReloadListener() {
		MinecraftClient mc = MinecraftClient.getInstance();

		if (mc != null && mc.getResourceManager() instanceof ReloadableResourceManagerImpl resourceManager)
			resourceManager.registerReloader(StrutCache::reload);
	}

	public static CompletableFuture<Void> reload(Synchronizer stage, ResourceManager resourceManager,
			Profiler preparationsProfiler, Profiler reloadProfiler, Executor backgroundExecutor,
			Executor gameExecutor) {
		Map<Identifier, BakedAnimations> animations = new Object2ObjectOpenHashMap<>();
		Map<Identifier, BakedStrutModel> models = new Object2ObjectOpenHashMap<>();
		/*
		 * Maps of their own rather than a second writer into `animations`/`models`. Each pair of
		 * loaders (JSON + binary) runs as separate async tasks, and Object2ObjectOpenHashMap is not
		 * safe to write from both - an interleaved resize drops entries, which would show up as an
		 * asset that loads on some launches and is silently absent on others. Merged below, on one
		 * thread.
		 */
		Map<Identifier, BakedStrutModel> binaryModels = new Object2ObjectOpenHashMap<>();
		Map<Identifier, BakedAnimations> binaryAnimations = new Object2ObjectOpenHashMap<>();
		// Concurrent, unlike its neighbours: filled from the loader tasks themselves, as each file is read,
		// rather than from the single collecting thread
		Map<Identifier, Map<String, ClipTimeline>> binaryTimelines = new ConcurrentHashMap<>();

		BakedAnimationsAdapter.COMPRESSION_CACHE = new ConcurrentHashMap<>();

		return CompletableFuture.allOf(
				loadAnimations(backgroundExecutor, resourceManager, animations::put),
				loadModels(backgroundExecutor, resourceManager, models::put),
				loadBinaryModels(backgroundExecutor, resourceManager, binaryModels::put),
				loadBinaryAnimations(backgroundExecutor, resourceManager, binaryAnimations::put, binaryTimelines))
				// Both the JSON and binary animation passes read/write BakedAnimationsAdapter's
				// shared Constant compression cache while they run in parallel above, so it must not
				// be nulled out until both have finished
				.thenRunAsync(() -> BakedAnimationsAdapter.COMPRESSION_CACHE = null, backgroundExecutor)
				.thenCompose(stage::whenPrepared).thenAcceptAsync(empty -> {
					models.putAll(binaryModels);
					animations.putAll(binaryAnimations);

					StrutCache.ANIMATIONS = animations;
					StrutCache.MODELS = models;

					// The client's event timelines swap in with its clips, so they always describe exactly
					// what was just loaded
					ClipTimelines.setClientTimelines(binaryTimelines);
				}, gameExecutor);
	}

	private static CompletableFuture<Void> loadAnimations(Executor backgroundExecutor, ResourceManager resourceManager, BiConsumer<Identifier, BakedAnimations> elementConsumer) {
		return loadResources(backgroundExecutor, resourceManager, "strut/animations", ".json", resource -> {
			try {
				return FileLoader.loadAnimationsFile(resource, resourceManager);
			}
			catch (CompoundException ex) {
				ex.withMessage(resource.toString() + ": Error loading animation file").printStackTrace();

				return new BakedAnimations(new Object2ObjectOpenHashMap<>());
			}
			catch (Exception ex) {
				throw StrutConstants.exception(resource, "Error loading animation file", ex);
			}
		}, elementConsumer);
	}

	/**
	 * Load Strut's binary models, which live alongside the JSON ones and bake to the same
	 * {@link BakedStrutModel}
	 * <p>
	 * A separate pass rather than a branch inside {@link #loadModels}, because the two share no
	 * step: different extension, different parser, different bake. They meet only at the map
	 * they both fill, which is what lets a mod mix {@code .geo.json} and {@code .strutmodel}
	 * assets in one resource pack
	 */
	private static CompletableFuture<Void> loadBinaryModels(Executor backgroundExecutor, ResourceManager resourceManager, BiConsumer<Identifier, BakedStrutModel> elementConsumer) {
		CompletableFuture<Void> done = CompletableFuture.completedFuture(null);

		// one folder after the other, not together: they write into the same map from the collecting thread
		for (String folder : StrutBinaryFormats.MODEL_FOLDERS) {
			done = done.thenCompose(empty -> loadResources(backgroundExecutor, resourceManager, folder, StrutBinaryFormats.MODEL_EXTENSION, resource -> {
				try (InputStream stream = resourceManager.getResourceOrThrow(resource).getInputStream()) {
					return BinaryModelBaker.bake(BinaryModelReader.read(stream, resource.toString()), resource.toString());
				}
				catch (Exception ex) {
					throw StrutConstants.exception(resource, "Error loading binary model file", ex);
				}
			}, elementConsumer));
		}

		return done;
	}

	/**
	 * Load Strut's binary animations, which live alongside the JSON ones and bake to the same
	 * {@link BakedAnimations} - see {@link #loadBinaryModels} for why this is a separate pass
	 * rather than a branch inside {@link #loadAnimations}
	 */
	private static CompletableFuture<Void> loadBinaryAnimations(Executor backgroundExecutor, ResourceManager resourceManager, BiConsumer<Identifier, BakedAnimations> elementConsumer,
			Map<Identifier, Map<String, ClipTimeline>> timelineConsumer) {
		CompletableFuture<Void> done = CompletableFuture.completedFuture(null);

		for (String folder : StrutBinaryFormats.ANIMATION_FOLDERS) {
			done = done.thenCompose(empty -> loadResources(backgroundExecutor, resourceManager, folder, StrutBinaryFormats.ANIMATION_EXTENSION, resource -> {
				try (InputStream stream = resourceManager.getResourceOrThrow(resource).getInputStream()) {
					RawBinaryAnimation raw = BinaryAnimationReader.read(stream, resource.toString());

					// Alongside the bake, from the same parse: the bake drops each event's authority byte, and the
					// client's event dispatcher needs it. A bad event warns and is skipped, never failing the file
					timelineConsumer.put(resource, ClipTimelineFactory.fromBinary(raw, resource.toString(), StrutEventTypes.registry(),
							message -> StrutConstants.LOGGER.warn("[Strut events] {}", message)));

					return BinaryAnimationBaker.bake(raw, resource.toString());
				}
				catch (Exception ex) {
					throw StrutConstants.exception(resource, "Error loading binary animation file", ex);
				}
			}, elementConsumer));
		}

		return done;
	}

	private static CompletableFuture<Void> loadModels(Executor backgroundExecutor, ResourceManager resourceManager, BiConsumer<Identifier, BakedStrutModel> elementConsumer) {
		return loadResources(backgroundExecutor, resourceManager, "strut/models", ".json", resource -> {
			try {
				Model model = FileLoader.loadModelFile(resource, resourceManager);

				switch (model.formatVersion()) {
					case V_1_12_0 -> {}
					case V_1_14_0 -> StrutConstants.LOGGER.warn("Unsupported geometry json version: 1.14.0 for model {}. This model may not appear as expected", resource);
					case V_1_21_0 -> StrutConstants.LOGGER.warn("Unsupported geometry json version: 1.21.0 for model {}. Supported versions: 1.12.0. Remove any rotated face UVs and re-export the model to fix", resource);
					case null, default -> StrutConstants.LOGGER.warn("Unsupported geometry json version for model {}. Supported versions: 1.12.0", resource);
				}

				return BakedModelFactory.getForNamespace(resource.getNamespace()).constructStrutModel(GeometryTree.fromModel(model));
			}
			catch (Exception ex) {
				throw StrutConstants.exception(resource, "Error loading model file", ex);
			}
		}, elementConsumer);
	}

	private static <T> CompletableFuture<Void> loadResources(Executor executor, ResourceManager resourceManager,
			String path, String extension, Function<Identifier, T> loader, BiConsumer<Identifier, T> map) {
		return CompletableFuture.supplyAsync(
				() -> resourceManager.findResources(path, fileName -> fileName.toString().endsWith(extension)), executor)
				.thenApplyAsync(resources -> {
					Map<Identifier, CompletableFuture<T>> tasks = new Object2ObjectOpenHashMap<>();

					for (Identifier resource : resources.keySet()) {
						tasks.put(resource, CompletableFuture.supplyAsync(() -> loader.apply(resource), executor));
					}

					return tasks;
				}, executor)
				.thenAcceptAsync(tasks -> {
					for (Entry<Identifier, CompletableFuture<T>> entry : tasks.entrySet()) {
						// Skip known namespaces that use a "strut" folder of their own as well
						if (!EXCLUDED_NAMESPACES.contains(entry.getKey().getNamespace().toLowerCase(Locale.ROOT)))
							map.accept(entry.getKey(), entry.getValue().join());
					}
				}, executor);
	}

	/**
	 * Register a new namespace to be excluded from Strut's resource loader
	 *
	 * @param namespace The namespace to exclude
	 */
	public static synchronized void registerNamespaceExclusion(String namespace) {
		EXCLUDED_NAMESPACES.add(namespace);
	}
}
